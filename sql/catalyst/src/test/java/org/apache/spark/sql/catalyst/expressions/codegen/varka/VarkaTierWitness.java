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

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.ObjectName;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingStream;

/**
 * Which tier of HotSpot's JIT served each compared kernel call (VARKA-274): the interpreter, C1,
 * C2, an on-stack replacement, or code thrown away by a deoptimization in the middle of the call.
 * The fuzzers compare a kernel's answers with a reference; this says what code gave the answers,
 * so that a check meant to hold C2's vector code to the reference can show that C2 code ran.
 *
 * <p><b>Two sources, read at both edges of a call.</b> Before and after each call the witness
 * reads the code cache's list of installed methods, the DiagnosticCommand MBean's
 * {@code compilerCodelist} (the in-process {@code jcmd Compiler.codelist}): one line per compiled
 * method version, {@code <compile id> <level> <state> <class.method(signature)> [addresses]}, state
 * 0 for code in use and 1 for code made not entrant. What happens inside the call comes from one
 * JFR {@link RecordingStream} per JVM, on {@code jdk.Compilation} (every compile, threshold 0),
 * {@code jdk.Deoptimization} and {@link VarkaCheckedCallEvent}, which the check commits around the
 * call so the other two can be placed inside or outside it. The code list alone would miss a trap
 * taken and recompiled within one call; the events alone would miss code leaving the cache
 * without a trap, which an invalidation by a broken dependency does without any JFR event.
 *
 * <p><b>On-stack replacement.</b> The code list does not mark an OSR version: a loop method can
 * show a level-4 line in use while calls to it still enter the interpreter, the level-4 code
 * being reachable only from inside a running loop. So the witness keeps the compile ids that
 * {@code jdk.Compilation} reports as OSR, and a method counts as compiled at a level only through
 * a version that is not one of them.
 *
 * <p><b>Which methods.</b> A call's verdict is about the loop and epilogue methods of the body the
 * batch selects ({@link VarkaMethodNames#isGroupMethod}), where all the per-row work is. The
 * drivers and the dispatch are left out: they hold no per-row work and are often inlined into
 * their caller or never compiled at all (`VARKA-274.md` 2(b)).
 *
 * <p><b>Late delivery.</b> JFR hands events to a stream about once a second, so a verdict is read
 * only after {@link #settle}, which commits a marker event and returns once the stream has
 * delivered it and flushed once more. It never sleeps a guessed time.
 */
public final class VarkaTierWitness {

  // ---------------------------------------------------------------------------------------------
  // The verdict on one call.
  // ---------------------------------------------------------------------------------------------

  /** What served one call; {@link #label} names its kind for a count. */
  public sealed interface Verdict {
    String label();
  }

  /**
   * Every loop and epilogue method of the call's body was C2 code in use when the call began and
   * when it ended, and nothing was thrown away or replaced on the stack in between.
   */
  public record C2() implements Verdict {
    @Override public String label() {
      return "C2";
    }
  }

  /**
   * A deoptimization of the kernel's compiled code fell inside the call: {@code method} is the
   * kernel method whose compiled version was deoptimized, {@code at} the method the trap was in,
   * which differs where C2 inlined it - a Vector API method or a Varka runtime helper.
   */
  public record Trapped(String method, String reason, String action, String at)
      implements Verdict {
    @Override public String label() {
      return "trapped";
    }
  }

  /** An on-stack-replacement compile of one of the call's methods finished inside the call. */
  public record Osr(String method, int level) implements Verdict {
    @Override public String label() {
      return "osr" + level;
    }
  }

  /**
   * Nothing happened inside the call, but some of its methods were not C2 code at one of its
   * edges: each method's level before and after, 0 for the interpreter.
   */
  public record Mixed(Map<String, Integer> before, Map<String, Integer> after)
      implements Verdict {
    public Mixed {
      before = Collections.unmodifiableSortedMap(new TreeMap<>(before));
      after = Collections.unmodifiableSortedMap(new TreeMap<>(after));
    }

    @Override public String label() {
      return "mixed";
    }

    /** Whether every method was interpreted at both edges: a call no compiler had touched. */
    public boolean interpreted() {
      return before.values().stream().allMatch(l -> l == 0)
          && after.values().stream().allMatch(l -> l == 0);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The code list.
  // ---------------------------------------------------------------------------------------------

  /** One line of the code list for a method of a watched class. */
  public record CodeEntry(int compileId, int level, int state, String method) {
    /** Whether the version is in use: state 0. Not entrant is 1. */
    public boolean inUse() {
      return state == 0;
    }
  }

  /** The DiagnosticCommand MBean's code list: every compiled method version in this JVM. */
  public static String readCodeList() {
    try {
      Object reply = ManagementFactory.getPlatformMBeanServer().invoke(
          new ObjectName("com.sun.management:type=DiagnosticCommand"), "compilerCodelist",
          new Object[] {new String[0]}, new String[] {String[].class.getName()});
      return String.valueOf(reply);
    } catch (Exception e) {
      throw new IllegalStateException("could not read the code list", e);
    }
  }

  /**
   * The lines of {@code codeList} for methods of {@code className}. A line reads
   * {@code 1732 4 0 pkg.Class.method(I)J [0x..., 0x... - 0x...]}; a line of another shape is
   * skipped.
   */
  public static List<CodeEntry> parse(String codeList, String className) {
    var out = new ArrayList<CodeEntry>();
    String prefix = className + ".";
    // The listing holds every method the JVM has compiled, and is read twice a checked call, so
    // only the lines that name the class are cut out of it: a jump from one mention to the next.
    for (int at = codeList.indexOf(prefix); at >= 0; ) {
      int start = codeList.lastIndexOf('\n', at) + 1;
      int end = codeList.indexOf('\n', at);
      if (end < 0) {
        end = codeList.length();
      }
      String line = codeList.substring(start, end);
      at = codeList.indexOf(prefix, end);
      String[] parts = line.trim().split(" ", 4);
      if (parts.length < 4 || !parts[3].startsWith(prefix)) {
        continue;
      }
      int paren = parts[3].indexOf('(');
      if (paren < 0) {
        continue;
      }
      try {
        out.add(new CodeEntry(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2]), parts[3].substring(prefix.length(), paren)));
      } catch (NumberFormatException e) {
        // Not a code line: a header or a message the command printed instead.
      }
    }
    return out;
  }

  /**
   * Each of {@code methods}' level as the code list shows it: its highest level among its versions
   * in use that the recording has reported as finished compiles and not on-stack replacements
   * ({@code known}, by compile id: true for an OSR) - 4 for C2 code, 1 to 3 for C1's, 0 where none
   * is installed - raised to 4 where C2 inlined it into a caller's level-4 version in use
   * ({@code inlined}, the methods each compile inlined). A small kernel's loop is often compiled
   * only inside its driver or {@code run}, and then never on its own, since its own invocation
   * counter stops once its caller is C2 code.
   *
   * <p>A version whose compile the recording has not delivered yet does not count: the code list
   * is current and the recording about a second behind, and until its compile event arrives the
   * witness cannot tell a version from an on-stack replacement, nor what it inlined. A decision
   * taken from these levels is therefore never ahead of the recording.
   */
  public static Map<String, Integer> levels(List<CodeEntry> entries, Set<String> methods,
      Map<Integer, Boolean> known, Map<Integer, Set<String>> inlined) {
    return levels(entries, methods, known, inlined, true);
  }

  /**
   * {@link #levels}, or with {@code strict} off counting a version the recording has not
   * delivered yet as a standard one: the warm-up's quick decision, which a {@link Decision}
   * records so that it is checked with everything delivered afterwards.
   */
  private static Map<String, Integer> levels(List<CodeEntry> entries, Set<String> methods,
      Map<Integer, Boolean> known, Map<Integer, Set<String>> inlined, boolean strict) {
    var levels = new TreeMap<String, Integer>();
    for (String m : methods) {
      levels.put(m, 0);
    }
    for (CodeEntry e : entries) {
      Boolean osr = known.get(e.compileId());
      boolean counted = strict ? Boolean.FALSE.equals(osr) : !Boolean.TRUE.equals(osr);
      if (!e.inUse() || !counted) {
        continue;
      }
      if (levels.containsKey(e.method())) {
        levels.merge(e.method(), e.level(), Math::max);
      }
      if (e.level() == 4) {
        for (String callee : inlined.getOrDefault(e.compileId(), Set.of())) {
          if (levels.containsKey(callee)) {
            levels.put(callee, 4);
          }
        }
      }
    }
    return levels;
  }

  /**
   * The compiled versions that make {@code method} C2 code in {@code entries}: its own level-4
   * versions in use, and the level-4 versions in use of callers C2 inlined it into, each a known
   * compile that is not an on-stack replacement. A call is C2 only where one of these is in use at
   * both of its edges: the same code, not code thrown away and recompiled in between.
   */
  public static Set<Integer> c2Versions(List<CodeEntry> entries, String method,
      Map<Integer, Boolean> known, Map<Integer, Set<String>> inlined) {
    var out = new HashSet<Integer>();
    for (CodeEntry e : entries) {
      if (e.inUse() && e.level() == 4 && Boolean.FALSE.equals(known.get(e.compileId()))
          && (e.method().equals(method)
              || inlined.getOrDefault(e.compileId(), Set.of()).contains(method))) {
        out.add(e.compileId());
      }
    }
    return out;
  }

  /**
   * Each of {@code methods}' highest level among its on-stack-replacement versions in use, 0
   * where it has none.
   */
  public static Map<String, Integer> osrLevels(List<CodeEntry> entries, Set<String> methods,
      Map<Integer, Boolean> known) {
    var levels = new TreeMap<String, Integer>();
    for (String m : methods) {
      levels.put(m, 0);
    }
    for (CodeEntry e : entries) {
      if (e.inUse() && Boolean.TRUE.equals(known.get(e.compileId()))
          && levels.containsKey(e.method())) {
        levels.merge(e.method(), e.level(), Math::max);
      }
    }
    return levels;
  }

  /** Whether every one of {@code methods} is C2 code in use, read as {@link #levels} does. */
  public static boolean allC2(List<CodeEntry> entries, Set<String> methods,
      Map<Integer, Boolean> known, Map<Integer, Set<String>> inlined) {
    for (int level : levels(entries, methods, known, inlined).values()) {
      if (level != 4) {
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // One call.
  // ---------------------------------------------------------------------------------------------

  /** What the stream reported for a watched class: a finished compile or a trap. */
  private record Compiled(int compileId, String method, int level, boolean osr, Instant end) { }

  /** A trap: the kernel method whose code it threw away, and the method it was taken in. */
  private record Trap(String method, String inner, String reason, String action, Instant at) { }

  private record Window(Instant start, Instant end) { }

  /**
   * One compared call: the kernel, the methods its verdict reads, and the code list at its two
   * edges. It is the {@link VarkaKernelChecks.CallHook} the check runs the call with.
   */
  public final class Call implements VarkaKernelChecks.CallHook {
    private final long id;
    private final String className;
    private final Set<String> methods;
    private List<CodeEntry> before = List.of();
    private List<CodeEntry> after = List.of();
    private VarkaCheckedCallEvent event;
    private Verdict verdict;

    private Call(long id, String className, Set<String> methods) {
      this.id = id;
      this.className = className;
      this.methods = Set.copyOf(methods);
    }

    @Override public void before() {
      // The previous call's closing read stands for this one's opening read, when nothing but
      // the harness ran on the class in between (see `lastRead`).
      List<CodeEntry> last = lastRead.remove(className);
      before = last != null ? last : parse(readCodeList(), className);
      event = new VarkaCheckedCallEvent();
      event.call = id;
      event.kernel = className;
      event.begin();
    }

    @Override public void after(int status) {
      event.end();
      event.commit();
      after = parse(readCodeList(), className);
      lastRead.put(className, after);
    }

    /** The loop and epilogue methods the verdict reads; empty for a body that has none. */
    public Set<String> methods() {
      return methods;
    }

    /** The code list's lines for the kernel at the call's start, for a failure message. */
    public List<CodeEntry> codeBefore() {
      return before;
    }

    /**
     * The verdict, read once the stream has settled since the call ({@link #settle}) and kept: a
     * report asks for it several times, and each reading scans the class's compiles and traps.
     */
    public synchronized Verdict verdict() {
      if (verdict == null) {
        if (id > settledThrough) {
          throw new IllegalStateException("the verdict of call " + id + " on " + className
              + " was read before the witness settled after it");
        }
        verdict = verdictOf(this);
      }
      return verdict;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The recording.
  // ---------------------------------------------------------------------------------------------

  private static VarkaTierWitness instance;

  /** The JVM's witness, its recording started on first use. */
  public static synchronized VarkaTierWitness get() {
    if (instance == null) {
      instance = new VarkaTierWitness();
    }
    return instance;
  }

  /**
   * Stops the JVM's recording, so that the tests after a compiled run do not pay for recording
   * every compile and every inlining decision in the JVM. A verdict already read stays readable;
   * a later {@link #get} starts a new recording that knows nothing of earlier calls.
   */
  public static synchronized void close() {
    if (instance != null) {
      instance.stream.close();
      instance = null;
    }
  }

  private final RecordingStream stream = new RecordingStream();
  private final AtomicLong calls = new AtomicLong();
  private final Set<String> watched = ConcurrentHashMap.newKeySet();
  /** Every finished compile of a watched class, by id: true for an on-stack replacement. */
  private final Map<Integer, Boolean> known = new ConcurrentHashMap<>();
  /** The watched class and kernel method each of those compiles was of. */
  private final Map<Integer, String[]> compiledAs = new ConcurrentHashMap<>();
  /** Traps found through their compile id, taken in a method of another class. */
  private final java.util.concurrent.atomic.AtomicInteger inlinedTraps =
      new java.util.concurrent.atomic.AtomicInteger();
  private final List<String> inlinedExamples =
      java.util.Collections.synchronizedList(new ArrayList<>());
  // Guarded by `this`: what the stream reported, and how far it has got.
  private final Map<String, List<Compiled>> compiles = new HashMap<>();
  private final Map<String, List<Trap>> traps = new HashMap<>();
  private final Map<Long, Window> windows = new HashMap<>();
  private final Map<Integer, Set<String>> inlined = new ConcurrentHashMap<>();
  /**
   * Each class's code list as its last call closed, which the next call on it opens with: a read
   * costs about 3 ms, a whole history's worth of them doubled its time, and between two compared
   * calls only the harness runs. What a compile installs in that gap shows a call a level lower
   * than it ran at, never higher, and an OSR version installed there is still found, from the
   * stream's compile events and the call's closing read. {@link #touched} drops it when the
   * kernel itself ran in between.
   */
  private final Map<String, List<CodeEntry>> lastRead = new ConcurrentHashMap<>();
  private long markerSeen;
  private long markers;
  private long flushes;
  /** The last call id a completed {@link #settle} covers. */
  private volatile long settledThrough;

  private VarkaTierWitness() {
    stream.enable("jdk.Compilation").withThreshold(Duration.ZERO);
    stream.enable("jdk.Deoptimization").withoutStackTrace();
    stream.enable("jdk.CompilerInlining").withoutStackTrace();
    stream.enable(VarkaCheckedCallEvent.class).withThreshold(Duration.ZERO);
    stream.onEvent("jdk.Compilation", this::onCompilation);
    stream.onEvent("jdk.Deoptimization", this::onDeoptimization);
    stream.onEvent("jdk.CompilerInlining", this::onInlining);
    stream.onEvent("varka.CheckedCall", this::onCall);
    stream.onFlush(this::onFlush);
    stream.startAsync();
  }

  /** The class a recorded method belongs to, or null. */
  private static String classOf(RecordedMethod m) {
    return m == null || m.getType() == null ? null : m.getType().getName();
  }

  private void onCompilation(RecordedEvent e) {
    RecordedMethod m = e.getValue("method");
    String cls = classOf(m);
    // The success field is spelled `succeded` in the JDK's own metadata.
    if (cls == null || !watched.contains(cls) || !e.getBoolean("succeded")) {
      return;
    }
    boolean osr = e.getBoolean("isOsr");
    int id = e.getInt("compileId");
    known.put(id, osr);
    compiledAs.put(id, new String[] {cls, m.getName()});
    synchronized (this) {
      compiles.computeIfAbsent(cls, k -> new ArrayList<>())
          .add(new Compiled(id, m.getName(), e.getInt("compileLevel"), osr, e.getEndTime()));
    }
  }

  /** A method of a watched class inlined into a compile of another of its methods. */
  private void onInlining(RecordedEvent e) {
    RecordedMethod caller = e.getValue("caller");
    String cls = classOf(caller);
    if (cls == null || !watched.contains(cls) || !e.getBoolean("succeeded")) {
      return;
    }
    jdk.jfr.consumer.RecordedObject callee = e.getValue("callee");
    if (callee == null || !cls.equals(callee.getString("type").replace('/', '.'))) {
      return;
    }
    inlined.computeIfAbsent(e.getInt("compileId"), k -> ConcurrentHashMap.newKeySet())
        .add(callee.getString("name"));
  }

  /**
   * A trap is the kernel's when the compiled code it throws away is: the event's
   * {@code compileId} names that code. Its {@code method} is the method the trap was taken in,
   * which, where C2 inlined a Vector API method or a runtime helper into the kernel's loop, is
   * that callee and not the kernel's method; read by {@code method} alone such a trap is lost.
   */
  private void onDeoptimization(RecordedEvent e) {
    RecordedMethod m = e.getValue("method");
    String[] owner = compiledAs.get(e.getInt("compileId"));
    String inner = m == null ? "?" : classOf(m) + "." + m.getName();
    String cls;
    String method;
    if (owner != null) {
      cls = owner[0];
      method = owner[1];
      if (m == null || !cls.equals(classOf(m))) {
        if (inlinedTraps.incrementAndGet() <= 5) {
          inlinedExamples.add(method + " <- " + inner + " (" + e.getString("reason") + ")");
        }
      }
    } else {
      cls = classOf(m);
      method = m == null ? "?" : m.getName();
      if (cls == null || !watched.contains(cls)) {
        return;
      }
    }
    synchronized (this) {
      traps.computeIfAbsent(cls, k -> new ArrayList<>()).add(new Trap(method, inner,
          e.getString("reason"), e.getString("action"), e.getStartTime()));
    }
  }

  /** How many traps were found only through their compile id, taken inside inlined code. */
  public int inlinedTraps() {
    return inlinedTraps.get();
  }

  /** The first few of those, as {@code kernelMethod <- class.method (reason)}. */
  public List<String> inlinedTrapExamples() {
    return List.copyOf(inlinedExamples);
  }

  private void onCall(RecordedEvent e) {
    long call = e.getLong("call");
    synchronized (this) {
      if (call < 0) {
        markerSeen = Math.max(markerSeen, -call);
      } else {
        windows.put(call, new Window(e.getStartTime(), e.getEndTime()));
      }
      notifyAll();
    }
  }

  private synchronized void onFlush() {
    flushes++;
    notifyAll();
  }

  /** Starts reading the stream's events for {@code className}; before its first call. */
  public void watch(String className) {
    watched.add(className);
  }

  /** The kernel ran outside a compared call, as a warm-up does: its next call reads afresh. */
  public void touched(String className) {
    lastRead.remove(className);
  }

  /** Every trap the stream reported for {@code className}, in or out of a call, by reason. */
  public synchronized Map<String, Integer> trapReasons(String className) {
    var out = new TreeMap<String, Integer>();
    for (Trap t : traps.getOrDefault(className, List.of())) {
      out.merge(t.reason(), 1, Integer::sum);
    }
    return out;
  }

  /**
   * The compiles the stream reported for {@code className}, for a person reading why a history
   * did not happen: each as {@code method@level}, {@code osr} marked, and {@code (in k)} for one
   * that finished inside the window of the {@code k}-th of {@code calls}.
   */
  public synchronized List<String> compiles(String className, List<Call> calls) {
    var out = new ArrayList<String>();
    for (Compiled k : compiles.getOrDefault(className, List.of())) {
      String where = "";
      for (int i = 0; i < calls.size(); i++) {
        Window w = windows.get(calls.get(i).id);
        if (w != null && inside(k.end(), w)) {
          where = " (in " + i + ")";
        }
      }
      out.add(k.method() + "@" + k.level() + (k.osr() ? " osr" : "") + where);
    }
    return out;
  }

  /** A new call on {@code className}, whose verdict reads {@code methods}. */
  public Call call(String className, Set<String> methods) {
    return new Call(calls.incrementAndGet(), className, methods);
  }

  /**
   * Whether every one of {@code methods} of {@code className} is C2 code in use now, read from
   * the code list, an on-stack replacement the stream has reported not counting.
   */
  public boolean allC2Now(String className, Set<String> methods) {
    return allC2(parse(readCodeList(), className), methods, known, inlined);
  }

  /**
   * A warm-up's decision that {@code methods} are C2 code: the code list it read, the methods,
   * and the quick answer, which counts a version whose compile the recording has not delivered
   * yet as a standard one. Waiting for the recording at every look would cost each warm-up about
   * a second, JFR's delivery interval; deciding quickly and confirming afterwards
   * ({@link #confirmed}) costs nothing, and a decision taken ahead of the recording - an
   * on-stack replacement read as a standard version - is then reported, not believed.
   */
  public record Decision(List<CodeEntry> read, Set<String> methods, boolean c2) {
    public Decision {
      read = List.copyOf(read);
      methods = Set.copyOf(methods);
    }
  }

  /** The warm-up's quick decision on {@code methods} of {@code className} now. */
  public Decision decideNow(String className, Set<String> methods) {
    var read = parse(readCodeList(), className);
    boolean c2 = levels(read, methods, known, inlined, false).values().stream()
        .allMatch(l -> l == 4);
    return new Decision(read, methods, c2);
  }

  /**
   * Whether a quick decision holds with everything the recording delivered since: read after
   * {@link #settle}, every version it counted known, none an on-stack replacement.
   */
  public boolean confirmed(Decision d) {
    return d.c2() && allC2(d.read(), d.methods(), known, inlined);
  }

  /** {@link #levels} of {@code methods} of {@code className} now, for a message. */
  public Map<String, Integer> levelsNow(String className, Set<String> methods) {
    return levels(parse(readCodeList(), className), methods, known, inlined);
  }

  /**
   * Waits until the stream has delivered every event committed before this call: a marker event
   * is committed, and once it has arrived the stream flushes once more, a flush writing every
   * thread's buffer. Throws after {@code timeoutMillis}, which a loaded machine can reach; that
   * is a failure of the witness, never of a kernel's answer.
   */
  public void settle(long timeoutMillis) {
    long covered = calls.get();
    long token;
    synchronized (this) {
      token = ++markers;
    }
    var marker = new VarkaCheckedCallEvent();
    marker.call = -token;
    marker.kernel = "settle";
    marker.commit();
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    synchronized (this) {
      try {
        while (markerSeen < token) {
          waitUntil(deadline, token);
        }
        long seen = flushes;
        while (flushes == seen) {
          waitUntil(deadline, token);
        }
        settledThrough = Math.max(settledThrough, covered);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while the tier witness settled", e);
      }
    }
  }

  /** The default bound on {@link #settle}: a minute, far past JFR's one-second flushes. */
  public void settle() {
    settle(60_000L);
  }

  private void waitUntil(long deadline, long token) throws InterruptedException {
    long left = deadline - System.nanoTime();
    if (left <= 0) {
      throw new IllegalStateException("the tier witness's JFR stream did not deliver marker "
          + token + " in time (seen " + markerSeen + ", " + flushes + " flushes)");
    }
    wait(Math.max(1L, left / 1_000_000L));
  }

  /**
   * The verdict on {@code c}, in this order: a trap inside the call; an on-stack replacement that
   * served it, where one of its methods had an OSR version in use at the call's start, or one
   * finished inside the call or before it and in use at its end, at a level above the method's
   * own - the interpreter, or C1's code,
   * then jumps into it at the loop's next back edge; C2 code in use for every method at both
   * edges; and otherwise each method's level at the two edges.
   */
  private synchronized Verdict verdictOf(Call c) {
    Window w = windows.get(c.id);
    if (w == null) {
      throw new IllegalStateException("no JFR window for call " + c.id + " on " + c.className
          + ": read its verdict after settle()");
    }
    for (Trap t : traps.getOrDefault(c.className, List.of())) {
      if (inside(t.at(), w)) {
        return new Trapped(t.method(), t.reason(), t.action(), t.inner());
      }
    }
    var own = levels(c.before, c.methods, known, inlined);
    var osr = osrLevels(c.before, c.methods, known);
    var inUseAfter = new HashSet<Integer>();
    for (CodeEntry e : c.after) {
      if (e.inUse()) {
        inUseAfter.add(e.compileId());
      }
    }
    for (Compiled k : compiles.getOrDefault(c.className, List.of())) {
      // Finished inside the call, or before it and still in use when it ended.
      boolean served = inside(k.end(), w)
          || !k.end().isAfter(w.start()) && inUseAfter.contains(k.compileId());
      if (k.osr() && c.methods.contains(k.method()) && served) {
        osr.merge(k.method(), k.level(), Math::max);
      }
    }
    Osr served = null;
    for (var e : osr.entrySet()) {
      if (e.getValue() > own.get(e.getKey()) && (served == null || e.getValue() > served.level())) {
        served = new Osr(e.getKey(), e.getValue());
      }
    }
    if (served != null) {
      return served;
    }
    var after = levels(c.after, c.methods, known, inlined);
    // A body with no loop or epilogue method of its own - a kernel whose outputs read no column
    // has nothing on its masked side - has no C2 code to show, and is never called C2.
    boolean c2 = !c.methods.isEmpty();
    for (String m : c.methods) {
      var both = c2Versions(c.before, m, known, inlined);
      both.retainAll(c2Versions(c.after, m, known, inlined));
      c2 &= !both.isEmpty();
    }
    if (c2) {
      return new C2();
    }
    return new Mixed(own, after);
  }

  private static boolean inside(Instant t, Window w) {
    return !t.isBefore(w.start()) && !t.isAfter(w.end());
  }
}
