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
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.In;
import org.apache.spark.sql.catalyst.expressions.InSet;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.codegen.CompiledVarkaProjection;
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaExpressionCompiler;
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser$;

/**
 * Whether the template warm-up ({@link VarkaVectorTemplateWarmup}) calls every Vector API method
 * an emitted kernel calls often enough (VARKA-397): the kernels' methods read off their bytes,
 * the warm-up's calls counted by running a counting copy of its rounds.
 *
 * <p>The unit is the method HotSpot keeps an invocation count for: the implementation a call
 * dispatches to ({@link #dispatch}), so that {@code IntVector.lanewise} on a 512-bit receiver is
 * {@code Int512Vector.lanewise}, and a call written against {@code VectorMask} is the mask class
 * of its lane. The mark is {@link #OLD_MARK} calls, past which the tiered policy treats a method
 * as old: its later tier decisions ignore C2's queue and its queued compile cannot go stale
 * ({@code VarkaVectorTemplateWarmup.THIN_CALLS_PER_ROUND}'s doc says why the warm-up wants every
 * method there before a kernel runs).
 */
final class VarkaWarmupReach {

  private static final String VECTOR_PREFIX = "jdk/incubator/vector/";

  /**
   * HotSpot's mark for an old method ({@code CompilationPolicy::is_old}): an invocation count of
   * {@code Tier3InvocationThreshold} (200) scaled by {@code TieredOldPercentage} (1000, ten times).
   */
  static final long OLD_MARK = 2_000;

  private VarkaWarmupReach() {
  }

  /** A Vector API method as a call site names it: owner (without the package), name, type. */
  record Call(String owner, String name, String descriptor) {
    @Override
    public String toString() {
      return owner + "." + name + params(MethodTypeDesc.ofDescriptor(descriptor));
    }
  }

  private static Call callOf(InvokeInstruction inv) {
    return new Call(inv.owner().asInternalName().substring(VECTOR_PREFIX.length()),
        inv.name().stringValue(), inv.type().stringValue());
  }

  private static boolean isVectorCall(InvokeInstruction inv) {
    return inv.owner().asInternalName().startsWith(VECTOR_PREFIX);
  }

  /** The parameter list of {@code type} with simple class names, as a report prints it. */
  private static String params(MethodTypeDesc type) {
    String display = type.displayDescriptor();
    return display.substring(0, display.indexOf(')') + 1);
  }

  // --- what HotSpot counts ---------------------------------------------------------------------

  /**
   * The lane types the engine emits vectors of, by their vector class: each lane the type
   * registry says some type's kernels reach (VARKA-494), in width order, and the double lane in
   * any case, which the long lane's divisions convert through even where no type is read on it.
   */
  private static final List<Class<?>> LANES = lanes();

  private static List<Class<?>> lanes() {
    var reached = new TreeSet<VarkaVectorIR.LaneType>();
    for (VarkaTypes.Info i : VarkaTypes.all()) {
      reached.addAll(i.lanes());
    }
    var out = new ArrayList<Class<?>>();
    for (VarkaVectorIR.LaneType lane : reached) {
      out.add(switch (lane) {
        case BYTE -> ByteVector.class;
        case SHORT -> ShortVector.class;
        case INT -> IntVector.class;
        case LONG -> LongVector.class;
        case FLOAT -> FloatVector.class;
        case DOUBLE -> DoubleVector.class;
      });
    }
    if (!out.contains(DoubleVector.class)) {
      out.add(DoubleVector.class);
    }
    return List.copyOf(out);
  }

  /**
   * The classes a receiver of one lane type can be in this JVM: the vector, the mask and the
   * species class of the lane's preferred species, and for a narrow lane those of the species a
   * widened kernel loads and stores it at - the one {@link Lane#widenLanes} names for the
   * preferred int species, a Byte64 or Short128 where the preferred narrow species is wider
   * (VARKA-329). The widened arm is the default, so those are the classes production kernels
   * dispatch to. The bytes do not say which arm emitted a call, so a narrow call stands for both
   * species: a call only the native arm makes is counted at the widened species too, and the
   * warm-up warms it there - an over-count, cheap, and preferred to a cold site the gate misses.
   */
  private static List<Class<?>> receivers(Class<?> lane) {
    VectorSpecies<?> s = lane == ByteVector.class ? ByteVector.SPECIES_PREFERRED
        : lane == ShortVector.class ? ShortVector.SPECIES_PREFERRED
        : lane == IntVector.class ? IntVector.SPECIES_PREFERRED
        : lane == LongVector.class ? LongVector.SPECIES_PREFERRED
        : lane == FloatVector.class ? FloatVector.SPECIES_PREFERRED
        : DoubleVector.SPECIES_PREFERRED;
    var out = new ArrayList<Class<?>>(
        List.of(s.zero().getClass(), s.maskAll(true).getClass(), s.getClass()));
    Lane narrow = lane == ByteVector.class ? Lane.BYTE
        : lane == ShortVector.class ? Lane.SHORT : null;
    if (narrow != null) {
      VectorSpecies<?> w = widenedSpecies(narrow);
      if (w != null && w != s) {
        out.addAll(List.of(w.zero().getClass(), w.maskAll(true).getClass(), w.getClass()));
      }
    }
    return out;
  }

  /** The species a widened kernel reads {@code narrow} at in this JVM, or null if none. */
  static VectorSpecies<?> widenedSpecies(Lane narrow) {
    int lanes = Lane.widenLanes(IntVector.SPECIES_PREFERRED.length(), narrow);
    if (lanes == 0) {
      return null;
    }
    VectorShape shape = VectorShape.forBitSize(lanes * narrow.bits);
    return narrow == Lane.BYTE ? VectorSpecies.of(byte.class, shape)
        : VectorSpecies.of(short.class, shape);
  }

  private static Class<?> ownerClass(Call call) throws ClassNotFoundException {
    return Class.forName("jdk.incubator.vector." + call.owner().replace('/', '.'));
  }

  /**
   * The method HotSpot counts a call of {@code call} against when its receiver is of class
   * {@code receiver} (null for a static call): the first class up the receiver's chain that
   * declares the method with a body, or the static method itself. Named by its simple class name
   * and parameter list, for example {@code Int512Vector.lanewise(VectorOperators$Binary,Vector)}.
   * A covariant override's bridge is not told apart from the method it forwards to.
   */
  static String dispatch(Call call, Class<?> receiver) {
    try {
      MethodTypeDesc type = MethodTypeDesc.ofDescriptor(call.descriptor());
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      var params = new Class<?>[type.parameterCount()];
      for (int i = 0; i < params.length; i++) {
        params[i] = (Class<?>) type.parameterType(i).resolveConstantDesc(lookup);
      }
      for (Class<?> k = receiver; k != null; k = k.getSuperclass()) {
        if (declaresWithBody(k, call.name(), params)) {
          return k.getSimpleName() + "." + call.name() + params(type);
        }
      }
      return ownerClass(call).getSimpleName() + "." + call.name() + params(type);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot resolve " + call, e);
    }
  }

  private static boolean declaresWithBody(Class<?> k, String name, Class<?>[] params) {
    for (Method m : k.getDeclaredMethods()) {
      if (m.getName().equals(name) && Arrays.equals(m.getParameterTypes(), params)
          && !m.isBridge() && !Modifier.isAbstract(m.getModifiers())
          && !Modifier.isStatic(m.getModifiers())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The methods HotSpot may count a kernel's call of {@code call} against: for an instance call,
   * the dispatch from every receiver of {@code lanes} its owner admits. The bytes give a call's
   * static owner and not its receiver, so a call written against {@code VectorMask} or
   * {@code Vector} stands for each lane the kernel's class uses, and each must be warm.
   */
  static List<String> targets(Call call, Set<Class<?>> lanes) {
    var out = new ArrayList<String>();
    try {
      Class<?> owner = ownerClass(call);
      for (Class<?> lane : LANES) {
        if (!lanes.contains(lane)) {
          continue;
        }
        for (Class<?> receiver : receivers(lane)) {
          if (owner.isAssignableFrom(receiver)) {
            String target = dispatch(call, receiver);
            if (!out.contains(target)) {
              out.add(target);
            }
          }
        }
      }
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("cannot resolve " + call, e);
    }
    if (out.isEmpty()) {
      out.add(dispatch(call, null));
    }
    return out;
  }

  // --- the emitted kernels' calls --------------------------------------------------------------

  /**
   * The lane types a class uses: the vector classes its calls are written against and whose
   * species fields it reads. A kernel's mask and conversion calls receive vectors of these.
   */
  private static Set<Class<?>> lanesUsed(ClassModel model) {
    var names = new HashSet<String>();
    for (PoolEntry entry : model.constantPool()) {
      if (entry instanceof MemberRefEntry member) {
        names.add(member.owner().asInternalName());
      }
    }
    var out = new HashSet<Class<?>>();
    for (Class<?> lane : LANES) {
      if (names.contains(lane.getName().replace('.', '/'))) {
        out.add(lane);
      }
    }
    return out.isEmpty() ? Set.copyOf(LANES) : out;
  }

  /**
   * The methods HotSpot counts that the Vector API calls in {@code bytes} reach, each with the
   * call that reaches it first.
   */
  static Map<String, Call> kernelTargets(byte[] bytes) {
    ClassModel model = ClassFile.of().parse(bytes);
    Set<Class<?>> lanes = lanesUsed(model);
    var out = new LinkedHashMap<String, Call>();
    for (MethodModel m : model.methods()) {
      m.code().ifPresent(code -> code.forEach(e -> {
        if (e instanceof InvokeInstruction inv && isVectorCall(inv)) {
          Call call = callOf(inv);
          for (String target : targets(call, lanes)) {
            out.putIfAbsent(target, call);
          }
        }
      }));
    }
    return out;
  }

  /** A shape to emit: roots over inputs and literal slots, and its name in a report. */
  record Shape(String name, List<VarkaVectorIR> roots, int numInputs, int numLiterals) {
    Shape {
      roots = List.copyOf(roots);
    }
  }

  private static <T> Seq<T> seq(List<T> list) {
    return CollectionConverters.asScala(list).toList();
  }

  /**
   * Every row of the coverage table ({@code sql/varka/coverage.json}) through the compiler under
   * {@code options}: a projection row's every kernel, a predicate row's fused one, and a row the
   * table records as an {@code InSet} rewritten to one first, as the optimizer hands it on
   * ({@code VarkaEmittedBytesSuite} compiles the rows the same way). Every expression the engine
   * fuses has a row, so a new lane's kernels are here the day it lands.
   */
  static List<Shape> coverageShapes(Path table, VarkaEmitOptions options) throws IOException {
    JsonNode doc = new ObjectMapper().readTree(Files.readString(table));
    var columns = new ArrayList<Attribute>();
    doc.get("columns").properties().forEach(c -> columns.add(VarkaSqlResolve.column(
        c.getKey(), CatalystSqlParser$.MODULE$.parseDataType(c.getValue().asText()))));
    Seq<Attribute> cols = seq(columns);
    var shapes = new ArrayList<Shape>();
    for (JsonNode row : doc.get("expressions")) {
      String sql = row.get("sql").asText();
      Expression expr = VarkaSqlResolve.parse(row.path("executable").asText(sql), cols);
      boolean inSet = false;
      for (JsonNode c : row.path("catalyst")) {
        inSet |= c.asText().equals("InSet");
      }
      if (inSet && expr instanceof In in) {
        var values = new HashSet<Object>();
        in.list().foreach(l -> values.add(((Literal) l).value()));
        expr = new InSet(in.value(), CollectionConverters.asScala(values).toSet());
      }
      var kernels = new ArrayList<CompiledVarkaProjection>();
      if (row.get("form").asText().equals("predicate")) {
        VarkaExpressionCompiler.compilePredicate(expr, cols, options)
            .ifPresent(p -> kernels.add(p.fused()));
      } else {
        VarkaExpressionCompiler.compilePartial(seq(List.of(VarkaSqlResolve.named(expr, "c"))),
            cols, options).ifPresent(p -> {
              kernels.add(p.fused());
              kernels.addAll(p.more());
            });
      }
      for (CompiledVarkaProjection k : kernels) {
        shapes.add(new Shape(sql, k.outputs(), k.inputOrdinals().size(), k.numLiterals()));
      }
    }
    return shapes;
  }

  /**
   * The first {@code count} shapes of the fuzz grammar's int and long sequences, the ones
   * {@code VarkaIrFuzzSuite} runs: they reach mechanisms no single coverage row does, such as
   * several roots sharing a method.
   */
  static List<Shape> fuzzShapes(int count) {
    var shapes = new ArrayList<Shape>();
    for (int k = 0; k < count; k++) {
      VarkaIrGrammar.Drawn d =
          VarkaIrGrammar.drawShape(VarkaIrGrammar.shapeRandom(VarkaIrGrammar.FUZZ_SEED, k));
      shapes.add(new Shape("int fuzz shape " + k, d.roots(), d.numInputs(), d.numLiterals()));
      VarkaIrGrammar.DrawnLong l = VarkaIrGrammar.drawLongShape(
          VarkaIrGrammar.shapeRandom(VarkaIrGrammar.LONG_FUZZ_SEED, k));
      shapes.add(new Shape("long fuzz shape " + k, l.roots(), l.numInputs(), l.numLiterals()));
    }
    return shapes;
  }

  /**
   * Adds to {@code into} every method HotSpot counts that the kernels of {@code shapes} reach
   * under {@code options}, each with the call and shape that reach it first; a shape the emitter
   * declines under them ({@link VarkaEmitDeclined}, a size limit) adds nothing, and any other
   * failure, a structural refusal or the class-file library's, fails the corpus. Returns how many
   * shapes it emitted.
   */
  static int emitterTargets(List<Shape> shapes, VarkaEmitOptions options, String label,
      Map<String, String> into) {
    int emitted = 0;
    for (Shape s : shapes) {
      byte[] bytes;
      try {
        bytes = VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaWarmupReach",
            s.roots(), s.numInputs(), s.numLiterals(), null, null, options);
      } catch (VarkaEmitDeclined declined) {
        continue;
      }
      emitted++;
      kernelTargets(bytes).forEach((target, call) ->
          into.putIfAbsent(target, call + " in " + s.name() + " (" + label + ")"));
    }
    return emitted;
  }

  // --- the warm-up's calls ---------------------------------------------------------------------

  /**
   * Where the counting copy of the rounds records its calls: per call site's method and
   * receiver class, the number of calls. One run at a time.
   */
  public static final class Counter {
    private static final Map<Integer, Map<Class<?>, long[]>> COUNTS = new HashMap<>();

    private Counter() {
    }

    /** Before an instance call: the call's id and its receiver. */
    public static void hit(Object receiver, int id) {
      COUNTS.computeIfAbsent(id, k -> new HashMap<>())
          .computeIfAbsent(receiver.getClass(), k -> new long[1])[0]++;
    }

    /** Before a static call: the call's id. */
    public static void hitStatic(int id) {
      COUNTS.computeIfAbsent(id, k -> new HashMap<>())
          .computeIfAbsent(Void.class, k -> new long[1])[0]++;
    }
  }

  private static final String ROUNDS =
      VarkaVectorTemplateWarmup.class.getName().replace('.', '/') + "$Rounds";

  /**
   * How many times one pass of the warm-up's rounds calls each method HotSpot counts, the calls
   * of {@code uncounted} left out as if the rounds never made them. The rounds' class is read
   * from the class path; before each of its Vector API call sites goes a call to {@link Counter}
   * with the site's method and, for an instance call, its receiver (the arguments set aside in
   * new locals and put back); and the copy is defined as a hidden nestmate of the warm-up, so
   * that it reaches the warm-up's private state as the original does, and run once. The pacing
   * runs in the copy as in the original.
   */
  static synchronized Map<String, Long> warmupCounts(Set<Call> uncounted) {
    byte[] original;
    try (InputStream in = VarkaVectorTemplateWarmup.class.getClassLoader()
        .getResourceAsStream(ROUNDS + ".class")) {
      if (in == null) {
        throw new IllegalStateException(ROUNDS + ".class is not on the class path");
      }
      original = in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    var ids = new LinkedHashMap<Call, Integer>();
    ClassDesc counter = ClassDesc.of(Counter.class.getName());
    MethodTypeDesc hit = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object,
        ConstantDescs.CD_int);
    MethodTypeDesc hitStatic = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int);
    CodeTransform counting = (cb, e) -> {
      if (e instanceof InvokeInstruction inv && isVectorCall(inv)
          && !uncounted.contains(callOf(inv))) {
        int id = ids.computeIfAbsent(callOf(inv), c -> ids.size());
        if (inv.opcode() == Opcode.INVOKESTATIC) {
          cb.loadConstant(id);
          cb.invokestatic(counter, "hitStatic", hitStatic);
        } else {
          List<ClassDesc> params = inv.typeSymbol().parameterList();
          int[] slots = new int[params.size()];
          for (int i = params.size() - 1; i >= 0; i--) {
            slots[i] = cb.allocateLocal(TypeKind.from(params.get(i)));
            cb.storeLocal(TypeKind.from(params.get(i)), slots[i]);
          }
          cb.dup();
          cb.loadConstant(id);
          cb.invokestatic(counter, "hit", hit);
          for (int i = 0; i < params.size(); i++) {
            cb.loadLocal(TypeKind.from(params.get(i)), slots[i]);
          }
        }
      }
      cb.with(e);
    };
    ClassLoader loader = VarkaVectorTemplateWarmup.class.getClassLoader();
    var classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
        ClassHierarchyResolver.ofClassLoading(loader)));
    byte[] copy = classFile.transformClass(classFile.parse(original),
        ClassTransform.transformingMethodBodies(counting));
    Counter.COUNTS.clear();
    try {
      MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(VarkaVectorTemplateWarmup.class,
          MethodHandles.lookup()).defineHiddenClass(copy, true,
          MethodHandles.Lookup.ClassOption.NESTMATE);
      long unused = (long) lookup.findStatic(lookup.lookupClass(), "run",
          MethodType.methodType(long.class)).invokeExact();
    } catch (Throwable t) {
      throw new IllegalStateException("the counting copy of the rounds failed", t);
    }
    var out = new TreeMap<String, Long>();
    ids.forEach((call, id) -> Counter.COUNTS.getOrDefault(id, Map.of()).forEach(
        (receiver, n) -> out.merge(dispatch(call, receiver == Void.class ? null : receiver),
            n[0], Long::sum)));
    return out;
  }

  // --- the check -------------------------------------------------------------------------------

  /**
   * The methods of {@code emitted} that {@code warmed}, the warm-up's counts, puts under
   * {@code mark}: one line each, in {@code emitted}'s order, naming the method, its count, and
   * the call and shape that reach it.
   */
  static List<String> underMark(Map<String, String> emitted, Map<String, Long> warmed,
      long mark) {
    var out = new ArrayList<String>();
    emitted.forEach((target, origin) -> {
      long n = warmed.getOrDefault(target, 0L);
      if (n < mark) {
        out.add(target + ": " + n + " calls, reached by " + origin);
      }
    });
    return out;
  }

  /** Fuzz shapes of each lane emitted under the matrix's base, and under every other option. */
  private static final int FUZZ_AT_BASE = 200;
  private static final int FUZZ_PER_CONFIGURATION = 20;

  /**
   * Every method HotSpot counts that the corpus's kernels reach, with the call and shape that
   * reach it first: the coverage table's rows and {@link #FUZZ_AT_BASE} fuzz shapes of each lane
   * under the defaults and under the matrix's base (the same options outside the matrix), then
   * the rows and {@link #FUZZ_PER_CONFIGURATION} shapes of each lane under every other distinct
   * option configuration of the matrix, whose JVMs run the warm-up too. The lane-count
   * configurations are left out: their kernels name another species, whose classes the warm-up
   * does not feed (its class doc says so).
   */
  static Map<String, String> corpusTargets(Path table) throws IOException {
    var out = new TreeMap<String, String>();
    var seen = new HashSet<VarkaEmitOptions>();
    for (VarkaEmitOptions base : List.of(VarkaEmitOptions.DEFAULTS, VarkaMatrixRuns.BASE)) {
      if (seen.add(base)) {
        String label = base.equals(VarkaEmitOptions.DEFAULTS) ? "defaults" : "base";
        emitterTargets(coverageShapes(table, base), base, label, out);
        emitterTargets(fuzzShapes(FUZZ_AT_BASE), base, label, out);
      }
    }
    for (String configuration : VarkaMatrixRuns.configurations()) {
      VarkaEmitOptions options = VarkaMatrixRuns.parse(configuration);
      if (options.lanesOverride() == 0 && seen.add(options)) {
        int emitted = emitterTargets(coverageShapes(table, options), options, configuration, out)
            + emitterTargets(fuzzShapes(FUZZ_PER_CONFIGURATION), options, configuration, out);
        if (emitted == 0) {
          throw new IllegalStateException("the emitter declined every shape under "
              + configuration + ", so the corpus reads nothing of that configuration");
        }
      }
    }
    return out;
  }
}
