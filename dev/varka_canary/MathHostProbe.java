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
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Does {@code java.lang.Math.<fn>} return the same bits as {@code StrictMath.<fn>} on this host
 * (VARKA-587)? Spark's {@code sin}, {@code cos}, {@code tan}, {@code sec}, {@code csc},
 * {@code cot}, {@code tanh} and {@code cbrt} call {@code java.lang.Math}, whose methods HotSpot
 * may replace by a platform stub, and its {@code exp}, {@code ln}, {@code log10}, {@code log1p},
 * {@code log2} and {@code pow} call {@code StrictMath}, which is fdlibm in Java on every host.
 * Where a stub differs from fdlibm, Spark's answer depends on the host.
 *
 * <p>Every function runs over the same 2^20 inputs, drawn with a fixed seed four ways in turn
 * (uniform bit patterns, [-750, 750], [-2, 2], and (u - 0.5) * 2^-j for j up to 59), the binary
 * ones over pairs of such draws. A line per function gives the count of inputs where the two
 * classes differ by raw bits, split into values (with the largest distance in units in the last
 * place and a histogram) and NaNs of another payload, the count per draw, and a digest of each
 * class's result bits, so two hosts' files can be compared by their digests alone. Spark's class
 * for the function is named on the line.
 *
 * <p>The same inputs then run again in child JVMs, one per set of flags: the interpreter only,
 * C1 only, C2 only, and the math intrinsics disabled. A child prints its Math digests, and the
 * parent says whether each equals its own (a tier that computed other bits would make an answer
 * change as a query warms up) or StrictMath's (what disabling the intrinsics gives).
 *
 * <p>A last section takes the thousand two-decimal literals 0.01 to 10.00, as a query would write
 * them, and lists the ones where Math and StrictMath differ for each of Spark's Math functions.
 *
 * <p>Run (any JDK from 17; the canary workflow runs it on both runner architectures):
 * <pre>
 *   java dev/varka_canary/MathHostProbe.java
 *   java dev/varka_canary/MathHostProbe.java --dump DIR     # also write DIR/mathhost-dump.txt
 *   java dev/varka_canary/MathHostProbe.java --compare FILE # this host against another's dump
 * </pre>
 * On a GitHub runner the dump goes to {@code $RUNNER_TEMP/canary-out}, the canary workflow's
 * artifact, with no argument. The dump holds, per function, every input where Math differs
 * from StrictMath with the signed distance; StrictMath being the same everywhere (the digests
 * say so), another host's Math bits are rebuilt from it, and {@code --compare} counts the
 * inputs where the two hosts differ.
 */
public class MathHostProbe {
  static final int PER_DRAW = 1 << 18;
  static final int N = 4 * PER_DRAW;
  static final long SEED = 587;
  static final String[] DRAWS = {"bits", "[-750,750]", "[-2,2]", "near0"};

  /** The functions, by the name of the JDK method; Spark's class and SQL names beside each. */
  static final String[] FNS = {
    "sin", "cos", "tan", "tanh", "cbrt", "asin", "acos", "atan", "sinh", "cosh", "atan2",
    "hypot", "exp", "expm1", "log", "log10", "log1p", "pow"
  };
  static final Map<String, String> SPARK = Map.ofEntries(
      Map.entry("sin", "Math: sin, csc"), Map.entry("cos", "Math: cos, sec"),
      Map.entry("tan", "Math: tan, cot"), Map.entry("tanh", "Math: tanh"),
      Map.entry("cbrt", "Math: cbrt"), Map.entry("asin", "Math: asin"),
      Map.entry("acos", "Math: acos"), Map.entry("atan", "Math: atan"),
      Map.entry("sinh", "Math: sinh"), Map.entry("cosh", "Math: cosh"),
      Map.entry("atan2", "Math: atan2"), Map.entry("hypot", "Math: hypot"),
      Map.entry("exp", "StrictMath: exp"), Map.entry("expm1", "StrictMath: expm1"),
      Map.entry("log", "StrictMath: ln, log2, log"), Map.entry("log10", "StrictMath: log10"),
      Map.entry("log1p", "StrictMath: log1p, atanh"), Map.entry("pow", "StrictMath: pow"));
  /**
   * The flags of each child JVM, and what its Math digests are expected to equal. A JVM refuses
   * an intrinsic name it does not know, and {@code _dtanh} came in JDK 24, {@code _dcbrt} in 25.
   */
  static final String[][] CHILDREN = {
    {"interpreter only", "math", "-Xint"},
    {"C1 only", "math", "-XX:TieredStopAtLevel=1"},
    {"C2 only", "math", "-XX:-TieredCompilation"},
    {"intrinsics disabled", "strict", "-XX:+UnlockDiagnosticVMOptions",
      "-XX:DisableIntrinsic=_dsin,_dcos,_dtan,_dexp,_dlog,_dlog10,_dpow"
        + (Runtime.version().feature() >= 24 ? ",_dtanh" : "")
        + (Runtime.version().feature() >= 25 ? ",_dcbrt" : "")},
  };
  static final String[] LITERAL_FNS = {"sin", "cos", "tan", "tanh", "cbrt"};

  static boolean binary(String f) {
    return f.equals("atan2") || f.equals("hypot") || f.equals("pow");
  }

  static double math(String f, double x, double y) {
    switch (f) {
      case "sin": return Math.sin(x);
      case "cos": return Math.cos(x);
      case "tan": return Math.tan(x);
      case "tanh": return Math.tanh(x);
      case "cbrt": return Math.cbrt(x);
      case "asin": return Math.asin(x);
      case "acos": return Math.acos(x);
      case "atan": return Math.atan(x);
      case "sinh": return Math.sinh(x);
      case "cosh": return Math.cosh(x);
      case "atan2": return Math.atan2(x, y);
      case "hypot": return Math.hypot(x, y);
      case "exp": return Math.exp(x);
      case "expm1": return Math.expm1(x);
      case "log": return Math.log(x);
      case "log10": return Math.log10(x);
      case "log1p": return Math.log1p(x);
      case "pow": return Math.pow(x, y);
      default: throw new IllegalArgumentException(f);
    }
  }

  static double strict(String f, double x, double y) {
    switch (f) {
      case "sin": return StrictMath.sin(x);
      case "cos": return StrictMath.cos(x);
      case "tan": return StrictMath.tan(x);
      case "tanh": return StrictMath.tanh(x);
      case "cbrt": return StrictMath.cbrt(x);
      case "asin": return StrictMath.asin(x);
      case "acos": return StrictMath.acos(x);
      case "atan": return StrictMath.atan(x);
      case "sinh": return StrictMath.sinh(x);
      case "cosh": return StrictMath.cosh(x);
      case "atan2": return StrictMath.atan2(x, y);
      case "hypot": return StrictMath.hypot(x, y);
      case "exp": return StrictMath.exp(x);
      case "expm1": return StrictMath.expm1(x);
      case "log": return StrictMath.log(x);
      case "log10": return StrictMath.log10(x);
      case "log1p": return StrictMath.log1p(x);
      case "pow": return StrictMath.pow(x, y);
      default: throw new IllegalArgumentException(f);
    }
  }

  /** The inputs: draw d fills [d * PER_DRAW, (d + 1) * PER_DRAW) of both arrays. */
  static double[][] inputs() {
    double[] xs = new double[N];
    double[] ys = new double[N];
    SplittableRandom r = new SplittableRandom(SEED);
    for (int d = 0; d < 4; d++) {
      for (int i = d * PER_DRAW; i < (d + 1) * PER_DRAW; i++) {
        xs[i] = draw(d, r);
        ys[i] = draw(d, r);
      }
    }
    return new double[][] {xs, ys};
  }

  static double draw(int d, SplittableRandom r) {
    switch (d) {
      case 0: return Double.longBitsToDouble(r.nextLong());
      case 1: return (r.nextDouble() * 2 - 1) * 750;
      case 2: return (r.nextDouble() * 2 - 1) * 2;
      default: return (r.nextDouble() - 0.5) * Math.scalb(1.0, -r.nextInt(60));
    }
  }

  static long[] evaluate(String f, boolean useMath, double[] xs, double[] ys) {
    long[] out = new long[N];
    for (int i = 0; i < N; i++) {
      double v = useMath ? math(f, xs[i], ys[i]) : strict(f, xs[i], ys[i]);
      out[i] = Double.doubleToRawLongBits(v);
    }
    return out;
  }

  /** FNV-1a over the result bits, a byte at a time: one hex word a function. */
  static String digest(long[] bits) {
    long h = 0xcbf29ce484222325L;
    for (long b : bits) {
      for (int k = 0; k < 8; k++) {
        h ^= (b >>> (8 * k)) & 0xff;
        h *= 0x100000001b3L;
      }
    }
    return String.format("%016x", h);
  }

  /** The bits mapped to a long that orders as the doubles do, so a difference counts ULPs. */
  static long ordered(long bits) {
    return bits ^ ((bits >> 63) & Long.MAX_VALUE);
  }

  static boolean isNaN(long bits) {
    return Double.isNaN(Double.longBitsToDouble(bits));
  }

  /** What two arrays of result bits say against each other, as a probe line's fields. */
  static final class Diff {
    long values;
    long nans;
    long other;
    long maxUlp;
    final long[] hist = new long[3];
    final long[] perDraw = new long[4];
    int firstValue = -1;

    static Diff of(long[] a, long[] b) {
      Diff d = new Diff();
      for (int i = 0; i < N; i++) {
        if (a[i] == b[i]) {
          continue;
        }
        d.perDraw[i / PER_DRAW]++;
        boolean na = isNaN(a[i]);
        boolean nb = isNaN(b[i]);
        if (na && nb) {
          d.nans++;
        } else if (na || nb) {
          d.other++;
        } else {
          d.values++;
          long ulp = Math.abs(ordered(a[i]) - ordered(b[i]));
          d.maxUlp = Math.max(d.maxUlp, ulp);
          d.hist[(int) Math.min(ulp, 3) - 1]++;
          if (d.firstValue < 0) {
            d.firstValue = i;
          }
        }
      }
      return d;
    }

    long total() {
      return values + nans + other;
    }

    String fields() {
      return String.format("differ=%d values=%d maxUlp=%d ulp1=%d ulp2=%d ulp3+=%d nanPayload=%d"
          + " nanVsValue=%d byDraw=%d/%d/%d/%d", total(), values, maxUlp, hist[0], hist[1],
          hist[2], nans, other, perDraw[0], perDraw[1], perDraw[2], perDraw[3]);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 2 && args[0].equals("--child")) {
      child();
      return;
    }
    Path dump = null;
    Path compare = null;
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--dump") && i + 1 < args.length) {
        dump = Path.of(args[++i]);
      } else if (args[i].equals("--compare") && i + 1 < args.length) {
        compare = Path.of(args[++i]);
      } else {
        throw new IllegalArgumentException("usage: MathHostProbe [--dump DIR] [--compare FILE]");
      }
    }
    // On a GitHub runner the canary workflow keeps $RUNNER_TEMP/canary-out as the job's
    // artifact, and a push-triggered run passes no arguments, so the dump goes there unasked.
    String runnerTemp = System.getenv("RUNNER_TEMP");
    if (dump == null && runnerTemp != null && "true".equals(System.getenv("GITHUB_ACTIONS"))) {
      dump = Path.of(runnerTemp, "canary-out");
    }
    host(System.out);
    double[][] in = inputs();
    double[] xs = in[0];
    double[] ys = in[1];
    System.out.printf("inputs: %d per function, %d per draw, seed %d, draws %s%n", N, PER_DRAW,
        SEED, String.join(" ", DRAWS));
    System.out.println();
    System.out.println("1. Math against StrictMath by raw bits; pass 1 is cold, pass 2 hot, on"
        + " the same inputs");
    Map<String, long[]> mathBits = new HashMap<>();
    Map<String, long[]> strictBits = new HashMap<>();
    Map<String, String> mathDigest = new HashMap<>();
    Map<String, String> strictDigest = new HashMap<>();
    for (String f : FNS) {
      long[] cold = evaluate(f, true, xs, ys);
      long[] s = evaluate(f, false, xs, ys);
      long[] hot = evaluate(f, true, xs, ys);
      Diff d = Diff.of(hot, s);
      String tiers = digest(cold).equals(digest(hot)) ? "cold=hot" : "COLD!=HOT";
      System.out.printf("%-6s spark=%-26s %s %s%n", f, SPARK.get(f), d.fields(), tiers);
      mathBits.put(f, hot);
      strictBits.put(f, s);
      mathDigest.put(f, digest(hot));
      strictDigest.put(f, digest(s));
    }
    System.out.println();
    System.out.println("2. Digests of the result bits (FNV-1a), to compare hosts");
    for (String f : FNS) {
      System.out.printf("%-6s math=%s strict=%s%n", f, mathDigest.get(f), strictDigest.get(f));
    }
    System.out.println();
    System.out.println("3. Examples: the first input of each function where the values differ");
    for (String f : FNS) {
      int i = Diff.of(mathBits.get(f), strictBits.get(f)).firstValue;
      if (i >= 0) {
        System.out.printf("%-6s x=%s%s Math=%s (%016x) StrictMath=%s (%016x)%n", f, xs[i],
            binary(f) ? " y=" + ys[i] : "", Double.longBitsToDouble(mathBits.get(f)[i]),
            mathBits.get(f)[i], Double.longBitsToDouble(strictBits.get(f)[i]),
            strictBits.get(f)[i]);
      }
    }
    System.out.println();
    System.out.println("4. The same inputs in child JVMs: Math digests against this JVM's");
    for (String[] c : CHILDREN) {
      child(c, mathDigest, strictDigest);
    }
    System.out.println();
    literals();
    if (dump != null) {
      writeDump(dump, mathBits, strictBits, strictDigest);
    }
    if (compare != null) {
      System.out.println();
      compare(compare, mathBits, strictBits, strictDigest);
    }
  }

  static void host(PrintStream out) {
    out.printf("host: os.arch=%s java.runtime.version=%s java.vm.name=%s%n",
        System.getProperty("os.arch"), System.getProperty("java.runtime.version"),
        System.getProperty("java.vm.name"));
    out.println("cpu: " + cpu());
    out.println("flags: " + vmFlags());
    out.println("jvm arguments: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
  }

  static String cpu() {
    try {
      for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
        if (line.startsWith("model name") || line.startsWith("CPU part")) {
          return line.substring(line.indexOf(':') + 1).trim();
        }
      }
    } catch (IOException e) {
      return "unknown (" + e.getMessage() + ")";
    }
    return "unknown";
  }

  static String vmFlags() {
    StringBuilder sb = new StringBuilder();
    for (String flag : new String[] {"UseAVX", "UseFMA", "UseSVE", "MaxVectorSize"}) {
      try {
        com.sun.management.HotSpotDiagnosticMXBean bean = ManagementFactory
            .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        String value = bean.getVMOption(flag).getValue();
        sb.append(flag).append('=').append(value).append(' ');
      } catch (IllegalArgumentException e) {
        sb.append(flag).append("=n/a ");
      }
    }
    return sb.toString().trim();
  }

  // ---------------------------------------------------------------------------------------
  // Child JVMs
  // ---------------------------------------------------------------------------------------

  /** In a child: every function's Math digest over the same inputs, one line each. */
  static void child() {
    double[][] in = inputs();
    for (String f : FNS) {
      System.out.println("digest " + f + " " + digest(evaluate(f, true, in[0], in[1])));
    }
  }

  static void child(String[] c, Map<String, String> mathDigest,
      Map<String, String> strictDigest) throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>();
    cmd.add(ProcessHandle.current().info().command().orElse("java"));
    for (int i = 2; i < c.length; i++) {
      cmd.add(c[i]);
    }
    cmd.add("-XX:ErrorFile=" + System.getProperty("java.io.tmpdir") + "/mathhost_hs_err_%p.log");
    cmd.add(sourceFile());
    cmd.add("--child");
    cmd.add(c[1]);
    long start = System.nanoTime();
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    Map<String, String> got = new HashMap<>();
    List<String> other = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(
        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        String[] w = line.split(" ");
        if (w.length == 3 && w[0].equals("digest")) {
          got.put(w[1], w[2]);
        } else if (!line.startsWith("WARNING")) {
          other.add(line);
        }
      }
    }
    int status = p.waitFor();
    double seconds = (System.nanoTime() - start) / 1e9;
    Map<String, String> want = c[1].equals("math") ? mathDigest : strictDigest;
    List<String> agree = new ArrayList<>();
    List<String> disagree = new ArrayList<>();
    for (String f : FNS) {
      if (want.get(f).equals(got.get(f))) {
        agree.add(f);
      } else {
        disagree.add(f + (strictDigest.get(f).equals(got.get(f)) ? "(=StrictMath)"
            : mathDigest.get(f).equals(got.get(f)) ? "(=this JVM's Math)" : ""));
      }
    }
    System.out.printf("%-20s %s: exit %d in %.0f s; equal to %s for %d of %d%s%n", c[0],
        String.join(" ", List.of(c).subList(2, c.length)), status, seconds,
        c[1].equals("math") ? "this JVM's Math" : "StrictMath", agree.size(), FNS.length,
        disagree.isEmpty() ? "" : "; differs: " + String.join(" ", disagree));
    if (status != 0 || got.size() != FNS.length) {
      for (String line : other.subList(0, Math.min(other.size(), 8))) {
        System.out.println("    " + line);
      }
    }
  }

  /** The path of this source file, as the launcher was given it. */
  static String sourceFile() {
    String command = System.getProperty("sun.java.command", "");
    for (String w : command.split(" ")) {
      if (w.endsWith("MathHostProbe.java")) {
        return w;
      }
    }
    return "dev/varka_canary/MathHostProbe.java";
  }

  // ---------------------------------------------------------------------------------------
  // Two-decimal literals
  // ---------------------------------------------------------------------------------------

  /** The literals 0.01 to 10.00, each as k / 100.0 is the double its text parses to. */
  static void literals() {
    System.out.println("5. The literals 0.01 to 10.00 (k / 100 for k = 1..1000): where Math and"
        + " StrictMath differ, as k:ULPs (Math above StrictMath is positive)");
    for (String f : LITERAL_FNS) {
      StringBuilder sb = new StringBuilder();
      int n = 0;
      String first = null;
      for (int k = 1; k <= 1000; k++) {
        double x = Double.parseDouble(String.format("%d.%02d", k / 100, k % 100));
        long m = Double.doubleToRawLongBits(math(f, x, 0));
        long s = Double.doubleToRawLongBits(strict(f, x, 0));
        if (m != s) {
          n++;
          sb.append(' ').append(k).append(':').append(ordered(m) - ordered(s));
          if (first == null) {
            first = String.format("%s(%s) = %s against StrictMath's %s", f, x,
                Double.longBitsToDouble(m), Double.longBitsToDouble(s));
          }
        }
      }
      System.out.printf("%-5s %d of 1000%s%n", f, n, n == 0 ? "" : "; first: " + first);
      if (n > 0) {
        System.out.println("     " + sb.toString().trim());
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // The dump and the comparison of two hosts
  // ---------------------------------------------------------------------------------------

  static void writeDump(Path dir, Map<String, long[]> mathBits, Map<String, long[]> strictBits,
      Map<String, String> strictDigest) throws IOException {
    Files.createDirectories(dir);
    Path file = dir.resolve("mathhost-dump.txt");
    try (PrintStream out = new PrintStream(Files.newOutputStream(file), false, "UTF-8")) {
      host(out);
      for (String f : FNS) {
        long[] m = mathBits.get(f);
        long[] s = strictBits.get(f);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int prev = 0;
        int n = 0;
        for (int i = 0; i < N; i++) {
          if (m[i] != s[i]) {
            varint(bytes, i - prev);
            long delta = ordered(m[i]) - ordered(s[i]);
            varint(bytes, (delta << 1) ^ (delta >> 63));
            prev = i;
            n++;
          }
        }
        out.printf("fn %s strict=%s n=%d%n", f, strictDigest.get(f), n);
        out.println("d " + Base64.getEncoder().encodeToString(bytes.toByteArray()));
      }
    }
    System.out.println();
    System.out.println("dump written: " + file);
  }

  static void varint(ByteArrayOutputStream out, long v) {
    while ((v & ~0x7fL) != 0) {
      out.write((int) ((v & 0x7f) | 0x80));
      v >>>= 7;
    }
    out.write((int) v);
  }

  /** This host's Math against another host's, rebuilt from its dump over the shared StrictMath. */
  static void compare(Path file, Map<String, long[]> mathBits, Map<String, long[]> strictBits,
      Map<String, String> strictDigest) throws IOException {
    List<String> lines = Files.readAllLines(file);
    System.out.println("6. This host's Math against the Math of the host in " + file.getFileName());
    for (String line : lines) {
      if (line.startsWith("host:") || line.startsWith("cpu:")) {
        System.out.println("   other " + line);
      }
    }
    for (int li = 0; li < lines.size(); li++) {
      String line = lines.get(li);
      if (!line.startsWith("fn ")) {
        continue;
      }
      String[] w = line.split(" ");
      String f = w[1];
      String strict = w[2].substring("strict=".length());
      if (!strict.equals(strictDigest.get(f))) {
        System.out.printf("%-6s StrictMath differs between the hosts (%s against %s): not compared"
            + "%n", f, strict, strictDigest.get(f));
        continue;
      }
      byte[] data = Base64.getDecoder().decode(lines.get(li + 1).substring(2));
      long[] other = strictBits.get(f).clone();
      int pos = 0;
      int index = 0;
      while (pos < data.length) {
        long[] r = readVarint(data, pos);
        index += (int) r[0];
        r = readVarint(data, (int) r[1]);
        long z = r[0];
        long delta = (z >>> 1) ^ -(z & 1);
        long o = ordered(strictBits.get(f)[index]) + delta;
        other[index] = ordered(o);
        pos = (int) r[1];
      }
      Diff d = Diff.of(mathBits.get(f), other);
      System.out.printf("%-6s %s%n", f, d.fields());
    }
  }

  static long[] readVarint(byte[] data, int pos) {
    long v = 0;
    int shift = 0;
    while (true) {
      byte b = data[pos++];
      v |= (long) (b & 0x7f) << shift;
      if ((b & 0x80) == 0) {
        return new long[] {v, pos};
      }
      shift += 7;
    }
  }
}
