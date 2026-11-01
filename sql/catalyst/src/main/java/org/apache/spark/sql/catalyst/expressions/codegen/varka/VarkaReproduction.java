/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.apache.spark.internal.SparkLogger;
import org.apache.spark.internal.SparkLoggerFactory;
import org.apache.spark.sql.internal.SQLConf;

/**
 * A reproduction file: what a Varka kernel failure leaves behind so that it can be replayed
 * without the query, the data source or the cluster (VARKA-315), after Velox's
 * {@code persistDataAndSql} and {@code ExpressionRunner} and the JVM's {@code hs_err} report.
 *
 * <p>It is written for three failures: a generated class that fails to verify or define, a memory
 * sanitizer violation, and an exception thrown inside a kernel. Each write site already holds what
 * the file needs and calls {@link #write} from its {@code catch}, then rethrows or falls back
 * exactly as it did before; the file is evidence beside the failure, never a change to it.
 *
 * <p><b>What the file holds</b>, as {@code key: value} lines a reader can open and
 * {@link #parse} can read back:
 *
 * <ul>
 *   <li>the failure's {@link VarkaFailureSignature} first, so a replay is compared with it
 *       mechanically, then the {@link VarkaErrorContext} block 314 renders, as comment lines;</li>
 *   <li>the shape key the emitter builds the class from - each output's canonical IR, the input
 *       and literal counts, the options that differ from the defaults (by name, as the option
 *       matrix labels them), whether the kernel was warmed - and its shape hash, which a replay
 *       recomputes to prove it rebuilt the same key;</li>
 *   <li>the literal values, which travel beside the key and not in it;</li>
 *   <li>the kernel call, where the failure was in one: the rows, and each kernel input as the
 *       kernel read it - its bytes, validity and null count, and the capacity its buffer had,
 *       which the sanitizer checks a mapping against;</li>
 *   <li>the failing class's bytes, where one was defined.</li>
 * </ul>
 *
 * <p><b>Bounds.</b> One file per shape and signature kind per JVM, at most
 * {@link #MAX_FILES_PER_JVM} files per JVM and {@link #MAX_FILES_PER_DIRECTORY} in a directory,
 * the batch's bytes only up to {@code spark.sql.codegen.varka.reproduction.maxBatchBytes} (the
 * rest of the call is kept, so a replay can run a synthetic batch of the same shape), and the
 * class only up to {@link #MAX_CLASS_BYTES}. A batch is user data, so the directory and the file
 * are made readable by their owner only where the file system has POSIX permissions.
 *
 * @param signature the failure's signature: what a replay must reproduce
 * @param stage where it failed, which decides how far a replay goes: {@code planning} (the
 *        admission's verification), {@code emission-failure} (definition and linking), or
 *        {@code kernel-failure} and {@code memory-violation} (a run over the call)
 * @param context the {@link VarkaErrorContext} block, for a reader; not read back exactly
 * @param writtenAt when the file was written, ISO-8601
 * @param java the JVM's {@code java.version}
 * @param sanitizer whether the memory sanitizer was on in the JVM that failed
 * @param shapeHash the shape key's hash
 * @param numInputs the shape key's input count
 * @param numLiterals the shape key's literal count
 * @param warmed whether the kernel was emitted under the warm-up's name
 * @param options the options that differ from the defaults, {@code name=value,...}
 * @param outputs each output's canonical IR, in order
 * @param intLiterals the int literal table the kernel was called with
 * @param longLiterals the long literal table
 * @param call the kernel call, or null where the failure was not in one
 * @param classBytes the failing class, or null where none was defined or it was over the cap;
 *        an array, so the record's equality is identity on it, as on {@link Input}'s
 */
public record VarkaReproduction(
    VarkaFailureSignature signature,
    String stage,
    String context,
    String writtenAt,
    String java,
    boolean sanitizer,
    String shapeHash,
    int numInputs,
    int numLiterals,
    boolean warmed,
    String options,
    List<String> outputs,
    List<Integer> intLiterals,
    List<Long> longLiterals,
    @Nullable KernelCall call,
    // An array on purpose, so equality is identity on it; see @param classBytes above.
    @SuppressWarnings("ArrayRecordComponent") @Nullable byte[] classBytes) {

  private static final SparkLogger LOG = SparkLoggerFactory.getLogger(VarkaReproduction.class);

  /** The format's version, the first line's; a reader refuses another. */
  public static final int FORMAT = 1;

  private static final String HEADER = "# Varka reproduction file, format " + FORMAT
      + ". Replay it with: dev/varka_emit.sh --replay <this file>";

  /** The most files one JVM writes, however many shapes fail in it. */
  public static final int MAX_FILES_PER_JVM = 32;

  /** The largest class the file carries; a wider one is left out, and the replay emits it. */
  public static final int MAX_CLASS_BYTES = 4 << 20;

  /** The directory under {@code java.io.tmpdir} the files go to when none is configured. */
  public static final String DEFAULT_DIRECTORY_NAME = "varka-reproductions";

  /**
   * The most files a directory holds before no JVM adds another: the per-JVM bounds do not stop a
   * cluster whose executors restart from filling a shared directory, since each new JVM starts
   * with none written.
   */
  public static final int MAX_FILES_PER_DIRECTORY = 256;

  /** What a shape hash looks like: sixteen hex digits, after the warm-up's mark if warmed. */
  private static final Pattern SHAPE_HASH = Pattern.compile("w?[0-9a-f]{16}");

  /**
   * One per JVM, in each file's name beside the pid: containers often run every executor as
   * pid 1, and two of them sharing a directory must not replace each other's files.
   */
  private static final String JVM_TOKEN =
      Long.toHexString(new SecureRandom().nextLong() & 0xffffffL);

  /** The {@code (shape hash, signature kind)} pairs this JVM has written. */
  private static final Set<String> WRITTEN = ConcurrentHashMap.newKeySet();

  private static final AtomicInteger FILES = new AtomicInteger();

  public VarkaReproduction {
    outputs = List.copyOf(outputs);
    intLiterals = List.copyOf(intLiterals);
    longLiterals = List.copyOf(longLiterals);
  }

  /**
   * One kernel input as the kernel read it. Arrays, so the record's equality is identity on them;
   * compare a parsed file's inputs by their content.
   *
   * @param width bytes per value: the Arrow vector's type width, 4 for a derived input, or 1 for
   *        a boolean column, whose data is a bitmap of {@code (rows + 7) / 8} bytes
   * @param dataCapacity the capacity of the buffer the data came from, which the sanitizer
   *        registers an input at
   * @param validityCapacity the validity buffer's capacity; 0 where the kernel was given none
   * @param nullCount the null count the kernel was given
   * @param derived whether the row engine's parser computed it into the task's scratch, which
   *        the runner guards at its nominal size rather than registering at a capacity
   * @param data the values, {@code rows * width} bytes (a boolean column's bitmap, {@code (rows +
   *        7) / 8}); null when the batch was left out
   * @param validity the validity words the kernel can read; null when it was given none (no
   *        nulls, or every row null) or the batch was left out
   */
  public record Input(int width, long dataCapacity, long validityCapacity, int nullCount,
      boolean derived,
      @SuppressWarnings("ArrayRecordComponent") // an array on purpose, as said above
      @Nullable byte[] data,
      @SuppressWarnings("ArrayRecordComponent") // an array on purpose, as said above
      @Nullable byte[] validity) {}

  /**
   * The kernel call a failure happened in.
   *
   * @param rows the batch's length
   * @param scratchBytesPerRow the scratch the kernel asks for per row
   * @param outputWidths bytes per value of each output; 0 for a selection bitmap, whose data slot
   *        is unused, and {@link #BIT_OUTPUT} for a boolean output, whose data is a bitmap
   * @param inputs the kernel's inputs, in kernel order
   * @param omitted why the inputs carry no bytes, or null when they do
   * @param selectionBitmaps whether the outputs were a filter's selection bitmaps, which the
   *        filter lays out in one buffer, a whole number of words each, and guards as one
   */
  public record KernelCall(int rows, int scratchBytesPerRow, List<Integer> outputWidths,
      List<Input> inputs, @Nullable String omitted, boolean selectionBitmaps) {
    /**
     * The output width of a boolean output (VARKA-27): a {@code BitVector}, whose data is a
     * bitmap of whole words and whose Arrow type width is 0, the width that already means a
     * selection bitmap with no data at all.
     *
     * <p>One of three widths for a bitmap, each the answer to a different question, so they are
     * not one constant. {@code VarkaKernelWarmup.BIT_WIDTH} (0) is an input's width to the
     * warm-up, which tiles its data bit by bit and slices it at {@code start / 8} bytes; inputs
     * have no selection bitmap for 0 to clash with. {@code VarkaKernelRunner.bytesPerRow} (1) is
     * an input's width in a reproduction, an allocation size for the replay's copy and the batch
     * cap, where 0 would allocate nothing. This one is an output's, where 0 is taken. It lives
     * here because a reproduction records it, but the runner ({@code dstWidth}) and the
     * evaluator, which sets it for a {@code BitVector}, read it as their own contract too.
     */
    public static final int BIT_OUTPUT = -1;

    public KernelCall {
      outputWidths = List.copyOf(outputWidths);
      inputs = List.copyOf(inputs);
    }

    /**
     * The data bytes an output of {@code width} takes over {@code rows} rows: a bit output's
     * bitmap to its last whole word, which the kernel may store whole, else a value a row.
     */
    public static long outputDataBytes(int width, long rows) {
      return width == BIT_OUTPUT ? ((rows + 63) / 64) * 8 : rows * width;
    }

    /** The bytes the inputs' values and validity take, which the batch cap is checked against. */
    public static long bytesOf(int rows, List<Input> inputs) {
      long total = 0;
      for (Input input : inputs) {
        total += (long) rows * input.width() + (input.nullCount() == 0
            || input.nullCount() == rows ? 0 : ((rows + 63L) / 64) * 8);
      }
      return total;
    }
  }

  /**
   * The settings, read where a failure is handled and nowhere else, so they cost nothing per batch
   * and reach no exec node's constructor.
   */
  public record Settings(boolean enabled, Path directory, long maxBatchBytes) {

    /** The session's, through {@link SQLConf#get}: on an executor, the task's copy of them. */
    public static Settings current() {
      SQLConf conf = SQLConf.get();
      scala.Option<String> configured = conf.varkaReproductionDirectory();
      Path dir = configured.isDefined() && !configured.get().isEmpty()
          ? Path.of(configured.get())
          : Path.of(System.getProperty("java.io.tmpdir"), DEFAULT_DIRECTORY_NAME);
      return new Settings(conf.varkaReproductionEnabled(), dir,
          conf.varkaReproductionMaxBatchBytes());
    }
  }

  // ---- writing --------------------------------------------------------------------------------

  /**
   * Writes the reproduction of {@code failure} and logs where, or does nothing when the settings
   * say not to, when this JVM already wrote one for the shape and signature kind, when it has
   * written {@link #MAX_FILES_PER_JVM}, or when the directory already holds
   * {@link #MAX_FILES_PER_DIRECTORY}. Never throws: it runs in a {@code catch} while another
   * failure is handled, and a write that failed is logged as a warning and nothing else, so the
   * query falls back or fails exactly as it would have. That includes a
   * {@link VirtualMachineError}, unlike {@link VarkaErrorContext#safely}: an
   * {@code OutOfMemoryError} here is most likely the copy of the batch this makes, and the failure
   * to report is still the one being handled; a JVM that is really out of memory fails again on
   * the next allocation outside this.
   *
   * @param context the failure's context record, whose kind is the file's stage; built only when
   *        a file is written, so a kernel that fails on every batch builds one
   * @param knownHash the key's shape hash where the caller holds it, or null to compute it from
   *        the key; anything that is not a shape hash is ignored, so the file always names the key
   *        it records
   * @param failure what failed: the kernel's own exception, the violation, or what the emission,
   *        the definition or the verification threw
   * @param key the shape key the class was built from
   * @param intLiterals the int literal table
   * @param longLiterals the long literal table
   * @param capture the kernel call, given the batch cap; null when the failure was not in one.
   *        Called only when a file is written, so a kernel that fails on every batch copies one
   * @param classBytes the failing class's bytes, or null where none was defined
   * @return the file, when one was written
   */
  public static Optional<Path> write(Supplier<VarkaErrorContext> context,
      @Nullable String knownHash, Throwable failure, VarkaShapeKey key,
      List<Integer> intLiterals, List<Long> longLiterals,
      @Nullable LongFunction<KernelCall> capture, @Nullable byte[] classBytes) {
    boolean counted = false;
    try {
      VarkaFailureSignature signature = VarkaFailureSignature.of(failure, "");
      String hash = knownHash != null && SHAPE_HASH.matcher(knownHash).matches()
          ? knownHash : VarkaShapeCacheImpl.shapeHash(key);
      String candidate = hash + "|" + signature.kind();
      // The cheap test first: a kernel that fails on every batch stops here after its first file.
      if (WRITTEN.contains(candidate) || FILES.get() >= MAX_FILES_PER_JVM) {
        return Optional.empty();
      }
      Settings settings = Settings.current();
      if (!settings.enabled() || !WRITTEN.add(candidate)) {
        return Optional.empty();
      }
      counted = true;
      if (FILES.incrementAndGet() > MAX_FILES_PER_JVM
          || filesIn(settings.directory()) >= MAX_FILES_PER_DIRECTORY) {
        return Optional.empty();
      }
      VarkaErrorContext ctx = context.get();
      var outputs = new ArrayList<String>();
      for (VarkaVectorIR output : key.outputs()) {
        outputs.add(VarkaVectorIR.canonical(output));
      }
      var reproduction = new VarkaReproduction(signature, ctx.kind(), ctx.render(),
          Instant.now().toString(), System.getProperty("java.version"),
          VarkaMemorySanitizer.ENABLED, hash, key.numInputs(), key.numLiterals(), key.warmed(),
          VarkaErrorContext.renderOptions(key.options()), outputs, intLiterals, longLiterals,
          capture == null ? null : capture.apply(settings.maxBatchBytes()),
          classBytes != null && classBytes.length <= MAX_CLASS_BYTES ? classBytes : null);
      Path file = settings.directory().resolve(fileName(hash, signature.kind()));
      writeAtomically(settings.directory(), file, reproduction.render());
      counted = false;
      LOG.warn("Wrote a reproduction of this Varka failure (" + signature + ") to " + file
          + "; replay it with dev/varka_emit.sh --replay " + file);
      return Optional.of(file);
    } catch (Throwable t) {
      unwritten(t);
      return Optional.empty();
    } finally {
      // A file not written does not count against the cap. Its claim stays: a kernel that fails
      // on every batch over a directory that cannot be written warns once, not once a batch.
      if (counted) {
        FILES.decrementAndGet();
      }
    }
  }

  /** The reproduction files already in {@code dir}, from every JVM; 0 when it does not exist. */
  private static long filesIn(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return 0;
    }
    try (var files = Files.list(dir)) {
      return files.filter(f -> f.getFileName().toString().startsWith("varka-repro-")).count();
    }
  }

  /**
   * Logs that a reproduction could not be written, for a write site whose own preparation threw;
   * never throws, not even when logging does.
   */
  public static void unwritten(Throwable t) {
    try {
      LOG.warn("Could not write a reproduction of a Varka failure; the failure itself is "
          + "handled as before.", t);
    } catch (Throwable ignored) {
      // Nothing is left to tell, and the failure being handled must still propagate.
    }
  }

  /**
   * {@code varka-repro-<hash>-<kind>-pid<pid>-<token>.txt}, the kind in lower-case words and the
   * token this JVM's own.
   */
  static String fileName(String hash, String kind) {
    String slug = kind.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-|-$", "");
    return "varka-repro-" + hash + "-" + slug + "-pid" + ProcessHandle.current().pid() + "-"
        + JVM_TOKEN + ".txt";
  }

  /**
   * {@code text} to {@code file} through a temporary file in the same directory and a move, so a
   * reader never sees half a file; the directory and the file readable by their owner only where
   * the file system can say so.
   */
  private static void writeAtomically(Path dir, Path file, String text) throws IOException {
    boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    if (posix) {
      Files.createDirectories(dir,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } else {
      Files.createDirectories(dir);
    }
    Path temp = posix
        ? Files.createTempFile(dir, ".varka-repro", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        : Files.createTempFile(dir, ".varka-repro", ".tmp");
    try {
      Files.writeString(temp, text, StandardCharsets.UTF_8);
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** Forgets what this JVM wrote, for a suite that plants the same failure twice. */
  public static void resetForTesting() {
    WRITTEN.clear();
    FILES.set(0);
  }

  // ---- the text form --------------------------------------------------------------------------

  /** The file's text; {@link #parse} reads it back. */
  public String render() {
    var out = new StringBuilder(HEADER).append('\n');
    line(out, "signature kind", signature.kind());
    line(out, "signature message", signature.text());
    line(out, "stage", stage);
    for (String contextLine : context.split("\n", -1)) {
      out.append("# ").append(contextLine).append('\n');
    }
    line(out, "written at", writtenAt);
    line(out, "java", java);
    line(out, "sanitizer", sanitizer ? "on" : "off");
    line(out, "shape hash", shapeHash);
    line(out, "inputs", Integer.toString(numInputs));
    line(out, "literals", Integer.toString(numLiterals));
    line(out, "warmed", Boolean.toString(warmed));
    line(out, "options", options);
    for (String output : outputs) {
      line(out, "output", output);
    }
    line(out, "int literals", join(intLiterals));
    line(out, "long literals", join(longLiterals));
    if (call != null) {
      line(out, "rows", Integer.toString(call.rows()));
      line(out, "scratch bytes per row", Integer.toString(call.scratchBytesPerRow()));
      line(out, "output widths", join(call.outputWidths()));
      line(out, "selection bitmaps", Boolean.toString(call.selectionBitmaps()));
      if (call.omitted() != null) {
        line(out, "batch omitted", call.omitted());
      }
      for (Input input : call.inputs()) {
        line(out, "input", "width=" + input.width() + " data-capacity=" + input.dataCapacity()
            + " validity-capacity=" + input.validityCapacity() + " nulls=" + input.nullCount()
            + " derived=" + input.derived());
        line(out, "input data", base64(input.data()));
        line(out, "input validity", base64(input.validity()));
      }
    }
    line(out, "class", base64(classBytes));
    return out.toString();
  }

  private static void line(StringBuilder out, String key, String value) {
    if (value.indexOf('\n') >= 0) {
      throw VarkaErrors.illegalArgument(key + " spans lines: " + value);
    }
    out.append(key).append(": ").append(value).append('\n');
  }

  private static String join(List<?> values) {
    var out = new StringBuilder();
    for (Object v : values) {
      if (out.length() > 0) {
        out.append(',');
      }
      out.append(v);
    }
    return out.toString();
  }

  private static String base64(@Nullable byte[] bytes) {
    return bytes == null ? "-" : Base64.getEncoder().encodeToString(bytes);
  }

  private static @Nullable byte[] bytes(String value) {
    return value.equals("-") ? null : Base64.getDecoder().decode(value);
  }

  /** One {@code key: value} line of a file. */
  private record Line(String key, String value) {}

  /** A file's lines in order, comments and blank lines left out, read one key at a time. */
  private static final class Lines {
    private final Iterator<Line> it;
    private @Nullable Line peeked;

    Lines(Iterator<Line> it) {
      this.it = it;
    }

    @Nullable Line peek() {
      if (peeked == null && it.hasNext()) {
        peeked = it.next();
      }
      return peeked;
    }

    /** The next line's value, which must be {@code key}'s. */
    String take(String key) {
      Line l = peek();
      if (l == null || !l.key().equals(key)) {
        throw new IllegalArgumentException("expected '" + key + "', got "
            + (l == null ? "the end of the file" : "'" + l.key() + "'"));
      }
      peeked = null;
      return l.value();
    }

    /** Whether the next line is {@code key}'s. */
    boolean next(String key) {
      Line l = peek();
      return l != null && l.key().equals(key);
    }
  }

  /** A file's text, as {@link #render} writes it. */
  public static VarkaReproduction parse(String text) {
    var lines = new ArrayList<Line>();
    var raw = text.split("\n", 0);
    if (raw.length == 0 || !raw[0].equals(HEADER)) {
      throw new IllegalArgumentException("not a Varka reproduction file of format " + FORMAT
          + "; its first line is " + (raw.length == 0 ? "missing" : raw[0]));
    }
    var context = new StringBuilder();
    for (int i = 1; i < raw.length; i++) {
      String l = raw[i];
      if (l.startsWith("# ")) {
        context.append(context.length() > 0 ? "\n" : "").append(l.substring(2));
      } else if (!l.isEmpty()) {
        int colon = l.indexOf(": ");
        if (colon < 0 && l.endsWith(":")) {
          lines.add(new Line(l.substring(0, l.length() - 1), ""));
        } else if (colon < 0) {
          throw new IllegalArgumentException("line " + (i + 1) + " is not 'key: value': " + l);
        } else {
          lines.add(new Line(l.substring(0, colon), l.substring(colon + 2)));
        }
      }
    }
    var reader = new Lines(lines.iterator());
    var signature = new VarkaFailureSignature(reader.take("signature kind"),
        reader.take("signature message"));
    String stage = reader.take("stage");
    String writtenAt = reader.take("written at");
    String java = reader.take("java");
    boolean sanitizer = reader.take("sanitizer").equals("on");
    String shapeHash = reader.take("shape hash");
    int numInputs = Integer.parseInt(reader.take("inputs"));
    int numLiterals = Integer.parseInt(reader.take("literals"));
    boolean warmed = Boolean.parseBoolean(reader.take("warmed"));
    String options = reader.take("options");
    var outputs = new ArrayList<String>();
    while (reader.next("output")) {
      outputs.add(reader.take("output"));
    }
    var ints = new ArrayList<Integer>();
    for (String v : split(reader.take("int literals"))) {
      ints.add(Integer.parseInt(v));
    }
    var longs = new ArrayList<Long>();
    for (String v : split(reader.take("long literals"))) {
      longs.add(Long.parseLong(v));
    }
    KernelCall call = null;
    if (reader.next("rows")) {
      int rows = Integer.parseInt(reader.take("rows"));
      int scratch = Integer.parseInt(reader.take("scratch bytes per row"));
      var widths = new ArrayList<Integer>();
      for (String v : split(reader.take("output widths"))) {
        widths.add(Integer.parseInt(v));
      }
      boolean selection = Boolean.parseBoolean(reader.take("selection bitmaps"));
      String omitted = reader.next("batch omitted") ? reader.take("batch omitted") : null;
      var inputs = new ArrayList<Input>();
      while (reader.next("input")) {
        var fields = new HashMap<String, String>();
        for (String kv : reader.take("input").split(" ", 0)) {
          String[] pair = kv.split("=", 2);
          fields.put(pair[0], pair[1]);
        }
        inputs.add(new Input(Integer.parseInt(fields.get("width")),
            Long.parseLong(fields.get("data-capacity")),
            Long.parseLong(fields.get("validity-capacity")),
            Integer.parseInt(fields.get("nulls")), Boolean.parseBoolean(fields.get("derived")),
            bytes(reader.take("input data")), bytes(reader.take("input validity"))));
      }
      call = new KernelCall(rows, scratch, widths, inputs, omitted, selection);
    }
    byte[] classBytes = bytes(reader.take("class"));
    Line extra = reader.peek();
    if (extra != null) {
      throw new IllegalArgumentException("unexpected '" + extra.key() + "' at the end");
    }
    return new VarkaReproduction(signature, stage, context.toString(), writtenAt, java, sanitizer,
        shapeHash, numInputs, numLiterals, warmed, options, outputs, ints, longs, call,
        classBytes);
  }

  private static List<String> split(String value) {
    return value.isEmpty() ? List.of() : List.of(value.split(","));
  }
}
