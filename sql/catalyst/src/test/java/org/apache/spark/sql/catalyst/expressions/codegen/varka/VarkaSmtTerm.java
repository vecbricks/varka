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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The proof printer's term tree (VARKA-506 2.2): a constant, a variable, an application of one of
 * {@code java.smt2}'s operators or of SMT-LIB's own, and a named quotient. A term renders to
 * SMT-LIB and evaluates over {@code BigInteger} and {@code Boolean}, so a term can be held to
 * the JVM's answers in the JVM and, as text, to the same answers under the solvers:
 * {@code VarkaSmtTermSuite} evaluates each operator against Java's and the word form below
 * against the prelude form, and {@code smt_print_check.smt2}, rendered by
 * {@code VarkaProofFiles}, states the word form's laws for the solvers.
 *
 * <p>Each operator's evaluation is written here from the JLS, independently of
 * {@code java.smt2}'s definition of the same name; {@code java_check.smt2} holds those definitions
 * to the JVM. A {@link Quot} is {@code floor(dividend / divisor)}: it renders as a declared
 * constant pinned by its remainder's range, the form the README advises for cvc5. Its name starts
 * with {@link #QUOTIENT}, which no variable's may, so a quotient never shadows an input.
 */
sealed interface VarkaSmtTerm {

  BigInteger WORD = BigInteger.ONE.shiftLeft(64);
  BigInteger HALF = BigInteger.ONE.shiftLeft(63);

  /** The prefix of every quotient's name, reserved: no variable's name starts with it. */
  String QUOTIENT = "q.";

  /** What a term denotes: an exact integer, an int or long lane value, or a truth value. */
  enum Sort { EXACT, INT, LONG, BOOL }

  record Num(BigInteger value) implements VarkaSmtTerm { }

  record Flag(boolean value) implements VarkaSmtTerm { }

  record Var(String name, Sort sort) implements VarkaSmtTerm {
    public Var {
      if (name.startsWith(QUOTIENT)) {
        throw new IllegalArgumentException("a variable named in the quotients' prefix: " + name);
      }
    }
  }

  /**
   * {@code floor(dividend / divisor)} for a positive constant divisor, named {@code name}. Its pin
   * {@code 0 <= d - k q < k} has no solution for {@code k <= 0}, which would make every check
   * over it hold vacuously, so such a divisor is refused here.
   */
  record Quot(String name, VarkaSmtTerm dividend, BigInteger divisor) implements VarkaSmtTerm {
    public Quot {
      if (divisor.signum() <= 0) {
        throw new IllegalArgumentException("a quotient by " + divisor + ", not a positive one");
      }
      if (!name.startsWith(QUOTIENT)) {
        throw new IllegalArgumentException("a quotient named outside its prefix: " + name);
      }
    }
  }

  record App(Op op, List<VarkaSmtTerm> args) implements VarkaSmtTerm {
    public App {
      args = List.copyOf(args);
    }
  }

  /** The operators: SMT-LIB's, then the prelude's, each with its sort and its JLS meaning. */
  enum Op {
    ADD("+", Sort.EXACT), SUB("-", Sort.EXACT), NEG("-", Sort.EXACT), MUL("*", Sort.EXACT),
    DIV("div", Sort.EXACT), MOD("mod", Sort.EXACT),
    LT("<", Sort.BOOL), LE("<=", Sort.BOOL), GT(">", Sort.BOOL), GE(">=", Sort.BOOL),
    EQ("=", Sort.BOOL), AND("and", Sort.BOOL), OR("or", Sort.BOOL), NOT("not", Sort.BOOL),
    ITE("ite", null),
    JINT_IN("jint.in", Sort.BOOL), JLONG_IN("jlong.in", Sort.BOOL),
    JLONG_WRAP("jlong.wrap", Sort.LONG), I2L("i2l", Sort.LONG), L2I("l2i", Sort.INT),
    JINT_ADD("jint.add", Sort.INT), JINT_SUB("jint.sub", Sort.INT),
    JINT_MUL("jint.mul", Sort.INT), JINT_NEG("jint.neg", Sort.INT),
    JINT_SHL("jint.shl", Sort.INT), JINT_SHR("jint.shr", Sort.INT),
    JINT_USHR("jint.ushr", Sort.INT),
    JLONG_ADD("jlong.add", Sort.LONG), JLONG_SUB("jlong.sub", Sort.LONG),
    JLONG_MUL("jlong.mul", Sort.LONG), JLONG_NEG("jlong.neg", Sort.LONG),
    JLONG_SHL("jlong.shl", Sort.LONG), JLONG_SHR("jlong.shr", Sort.LONG),
    JLONG_USHR("jlong.ushr", Sort.LONG),
    JLONG_AND_LOW("jlong.and.low", Sort.LONG), JLONG_AND_SIGN("jlong.and.sign", Sort.LONG),
    JLONG_ULT("jlong.ult", Sort.BOOL), JLONG_ULE("jlong.ule", Sort.BOOL),
    JLONG_UGT("jlong.ugt", Sort.BOOL), JLONG_UGE("jlong.uge", Sort.BOOL);

    final String smt;
    final Sort sort;

    Op(String smt, Sort sort) {
      this.smt = smt;
      this.sort = sort;
    }

    /** The operator over evaluated arguments, as the JLS defines the Java one. */
    Object apply(List<Object> a) {
      return switch (this) {
        case ADD -> arg(a, 0).add(arg(a, 1));
        case SUB -> arg(a, 0).subtract(arg(a, 1));
        case NEG -> arg(a, 0).negate();
        case MUL -> arg(a, 0).multiply(arg(a, 1));
        case DIV -> euclid(arg(a, 0), arg(a, 1));
        case MOD -> arg(a, 0).subtract(arg(a, 1).multiply(euclid(arg(a, 0), arg(a, 1))));
        case LT -> arg(a, 0).compareTo(arg(a, 1)) < 0;
        case LE -> arg(a, 0).compareTo(arg(a, 1)) <= 0;
        case GT -> arg(a, 0).compareTo(arg(a, 1)) > 0;
        case GE -> arg(a, 0).compareTo(arg(a, 1)) >= 0;
        case EQ -> a.get(0).equals(a.get(1));
        case AND -> a.stream().allMatch(Boolean.TRUE::equals);
        case OR -> a.stream().anyMatch(Boolean.TRUE::equals);
        case NOT -> !(Boolean) a.get(0);
        case ITE -> (Boolean) a.get(0) ? a.get(1) : a.get(2);
        case JINT_IN -> wrap(arg(a, 0), 32).equals(arg(a, 0));
        case JLONG_IN -> wrap(arg(a, 0), 64).equals(arg(a, 0));
        case JLONG_WRAP -> wrap(arg(a, 0), 64);
        case I2L -> arg(a, 0);
        case L2I -> wrap(arg(a, 0), 32);
        case JINT_ADD -> wrap(arg(a, 0).add(arg(a, 1)), 32);
        case JINT_SUB -> wrap(arg(a, 0).subtract(arg(a, 1)), 32);
        case JINT_MUL -> wrap(arg(a, 0).multiply(arg(a, 1)), 32);
        case JINT_NEG -> wrap(arg(a, 0).negate(), 32);
        case JINT_SHL -> shift(a, 32, 0);
        case JINT_SHR -> shift(a, 32, 1);
        case JINT_USHR -> shift(a, 32, 2);
        case JLONG_ADD -> wrap(arg(a, 0).add(arg(a, 1)), 64);
        case JLONG_SUB -> wrap(arg(a, 0).subtract(arg(a, 1)), 64);
        case JLONG_MUL -> wrap(arg(a, 0).multiply(arg(a, 1)), 64);
        case JLONG_NEG -> wrap(arg(a, 0).negate(), 64);
        case JLONG_SHL -> shift(a, 64, 0);
        case JLONG_SHR -> shift(a, 64, 1);
        case JLONG_USHR -> shift(a, 64, 2);
        // Defined for a mask 2^k - 1, passed as 2^k, and for a sign word: elsewhere the prelude's
        // definitions are not Java's &, so a use outside them is an error, not a value.
        case JLONG_AND_LOW -> arg(a, 0).mod(lowMask(arg(a, 1)));
        case JLONG_AND_SIGN -> {
          if (arg(a, 0).signum() > 0 || arg(a, 0).compareTo(BigInteger.ONE.negate()) < 0) {
            throw new IllegalStateException("jlong.and.sign takes a sign word, not " + a.get(0));
          }
          yield arg(a, 0).signum() == 0 ? BigInteger.ZERO : arg(a, 1);
        }
        case JLONG_ULT -> arg(a, 0).mod(WORD).compareTo(arg(a, 1).mod(WORD)) < 0;
        case JLONG_ULE -> arg(a, 0).mod(WORD).compareTo(arg(a, 1).mod(WORD)) <= 0;
        case JLONG_UGT -> arg(a, 0).mod(WORD).compareTo(arg(a, 1).mod(WORD)) > 0;
        case JLONG_UGE -> arg(a, 0).mod(WORD).compareTo(arg(a, 1).mod(WORD)) >= 0;
      };
    }

    private static BigInteger arg(List<Object> a, int k) {
      return (BigInteger) a.get(k);
    }

    /** A shift by the count's low bits (JLS 15.19): left, arithmetic right, logical right. */
    private static BigInteger shift(List<Object> a, int bits, int kind) {
      int s = arg(a, 1).mod(BigInteger.valueOf(bits)).intValueExact();
      BigInteger x = arg(a, 0);
      return switch (kind) {
        case 0 -> wrap(x.shiftLeft(s), bits);
        case 1 -> x.shiftRight(s);
        default -> wrap(x.mod(BigInteger.ONE.shiftLeft(bits)).shiftRight(s), bits);
      };
    }
  }

  /** The value in a {@code bits}-wide two's complement range congruent to {@code v}. */
  static BigInteger wrap(BigInteger v, int bits) {
    BigInteger half = BigInteger.ONE.shiftLeft(bits - 1);
    return v.add(half).mod(BigInteger.ONE.shiftLeft(bits)).subtract(half);
  }

  /** {@code k} if it is a {@code 2^k}, the mask {@code jlong.and.low} takes; else it throws. */
  static BigInteger lowMask(BigInteger k) {
    if (k.signum() <= 0 || k.bitCount() != 1) {
      throw new IllegalStateException("jlong.and.low takes 2^k, not " + k);
    }
    return k;
  }

  /**
   * SMT-LIB's {@code div}, the Euclidean quotient: {@code a = b q + r} with {@code 0 <= r < |b|}.
   * It is the floor for a positive divisor and the negated floor by {@code -b} for a negative one;
   * a zero divisor, which SMT-LIB leaves unspecified, throws.
   */
  static BigInteger euclid(BigInteger a, BigInteger b) {
    return b.signum() > 0 ? floorDiv(a, b) : floorDiv(a, b.negate()).negate();
  }

  static BigInteger floorDiv(BigInteger a, BigInteger b) {
    BigInteger[] qr = a.divideAndRemainder(b);
    return qr[1].signum() != 0 && (qr[1].signum() < 0) != (b.signum() < 0)
        ? qr[0].subtract(BigInteger.ONE) : qr[0];
  }

  default Sort sort() {
    return switch (this) {
      case Num n -> Sort.EXACT;
      case Flag f -> Sort.BOOL;
      case Var v -> v.sort();
      case Quot q -> Sort.EXACT;
      case App(var op, var args) -> op == Op.ITE ? args.get(1).sort() : op.sort;
    };
  }

  /** SMT-LIB text: a negative constant as {@code (- n)}, a quotient by its name. */
  default String render() {
    return switch (this) {
      case Num n -> n.value().signum() >= 0 ? n.value().toString()
          : "(- " + n.value().negate() + ")";
      case Flag f -> Boolean.toString(f.value());
      case Var v -> v.name();
      case Quot q -> q.name();
      case App(var op, var args) -> {
        var sb = new StringBuilder("(").append(op.smt);
        for (VarkaSmtTerm t : args) {
          sb.append(' ').append(t.render());
        }
        yield sb.append(')').toString();
      }
    };
  }

  /** The value at {@code env}, each variable's {@code BigInteger} or {@code Boolean}. */
  default Object eval(Map<String, Object> env) {
    return switch (this) {
      case Num n -> n.value();
      case Flag f -> f.value();
      case Var v -> {
        Object x = env.get(v.name());
        if (x == null) {
          throw new IllegalArgumentException("no value for " + v.name());
        }
        yield x;
      }
      case Quot q -> floorDiv((BigInteger) q.dividend().eval(env), q.divisor());
      case App(var op, var args) -> {
        var vals = new ArrayList<Object>();
        for (VarkaSmtTerm t : args) {
          vals.add(t.eval(env));
        }
        yield op.apply(vals);
      }
    };
  }

  /**
   * This term with each variable named in {@code by} replaced, and each quotient renamed with
   * {@code suffix}, so a term over free inputs can be pinned at one row of values.
   */
  default VarkaSmtTerm subst(Map<String, VarkaSmtTerm> by, String suffix) {
    return switch (this) {
      case Num n -> n;
      case Flag f -> f;
      case Var v -> by.getOrDefault(v.name(), v);
      case Quot q -> new Quot(q.name() + suffix, q.dividend().subst(by, suffix), q.divisor());
      case App(var op, var args) ->
          new App(op, args.stream().map(t -> t.subst(by, suffix)).toList());
    };
  }

  /** The variables and quotients {@code t} reads, each quotient after those its dividend reads. */
  static void collect(VarkaSmtTerm t, Map<String, Var> vars, Map<String, Quot> quots) {
    switch (t) {
      case Num n -> { }
      case Flag f -> { }
      case Var v -> vars.put(v.name(), v);
      case Quot q -> {
        collect(q.dividend(), vars, quots);
        quots.put(q.name(), q);
      }
      case App a -> a.args().forEach(x -> collect(x, vars, quots));
    }
  }

  /** A quotient's pin, {@code 0 <= d - k q < k}, as one assertion. */
  static String pin(Quot q) {
    String k = num(q.divisor()).render();
    String r = "(- " + q.dividend().render() + " (* " + k + " " + q.name() + "))";
    return "(assert (and (<= 0 " + r + ") (< " + r + " " + k + ")))";
  }

  // -------------------------------------------------------------------------------------------
  // Building terms, with the constant truth values folded away.
  // -------------------------------------------------------------------------------------------

  static Num num(long v) {
    return new Num(BigInteger.valueOf(v));
  }

  static Num num(BigInteger v) {
    return new Num(v);
  }

  static VarkaSmtTerm app(Op op, VarkaSmtTerm... args) {
    return new App(op, List.of(args));
  }

  static VarkaSmtTerm and(VarkaSmtTerm a, VarkaSmtTerm b) {
    if (a instanceof Flag(var x)) {
      return x ? b : a;
    }
    if (b instanceof Flag(var y)) {
      return y ? a : b;
    }
    return app(Op.AND, a, b);
  }

  static VarkaSmtTerm or(VarkaSmtTerm a, VarkaSmtTerm b) {
    if (a instanceof Flag(var x)) {
      return x ? a : b;
    }
    if (b instanceof Flag(var y)) {
      return y ? b : a;
    }
    return app(Op.OR, a, b);
  }

  static VarkaSmtTerm not(VarkaSmtTerm a) {
    return a instanceof Flag(var x) ? new Flag(!x) : app(Op.NOT, a);
  }

  static VarkaSmtTerm ite(VarkaSmtTerm c, VarkaSmtTerm a, VarkaSmtTerm b) {
    return c instanceof Flag(var x) ? (x ? a : b) : app(Op.ITE, c, a, b);
  }

  // -------------------------------------------------------------------------------------------
  // The word form (VARKA-506 2.3).
  // -------------------------------------------------------------------------------------------

  /**
   * The word form of the long lane, the admission spike's form B: each long is rendered by its
   * unsigned reading in {@code [0, 2^64)}, with an interval computed from its operands'. An add,
   * subtract, negation, shift left or multiply by a constant whose interval stays in range is
   * rendered exact, and its no-wrap claim is an {@link Obligation} for the solver to prove; one
   * that can wrap keeps one reduction, a quotient by {@code 2^64}. A logical shift right and a
   * mask by {@code 2^k - 1} are a quotient by {@code 2^k} and its remainder, shared by both; an
   * arithmetic shift by 63 is the sign of the signed reading; a signed compare reads the words
   * signed and an unsigned one unsigned. An operator with no rule here, or a shift by a count
   * that is not a constant, is read as {@code (mod t 2^64)} of its prelude form, which is exact
   * but keeps the wrap for the solver to reason about.
   */
  final class WordForm {

    /** A long's unsigned reading and the interval the printer proved it within. */
    record Word(VarkaSmtTerm u, BigInteger lo, BigInteger hi) { }

    /** A claim the rendering rests on, which a proof file checks for every input. */
    record Obligation(String what, VarkaSmtTerm claim) { }

    /** An unsigned compare of two longs is the exact compare of their readings. */
    private static final Map<Op, Op> UNSIGNED =
        Map.of(Op.JLONG_ULT, Op.LT, Op.JLONG_ULE, Op.LE, Op.JLONG_UGT, Op.GT, Op.JLONG_UGE, Op.GE);

    final List<Obligation> obligations = new ArrayList<>();
    private final Map<String, Quot> quotients = new HashMap<>();

    /** {@code t} with every long in it rendered in the word form, read signed where it is used. */
    VarkaSmtTerm term(VarkaSmtTerm t) {
      if (t.sort() == Sort.LONG && !(t instanceof Var)) {
        return signed(word(t).u());
      }
      if (t instanceof App(var op, var args)) {
        Op unsigned = UNSIGNED.get(op);
        return unsigned != null ? app(unsigned, word(args.get(0)).u(), word(args.get(1)).u())
            : new App(op, args.stream().map(this::term).toList());
      }
      return t;
    }

    /** The unsigned reading of a long term. */
    Word word(VarkaSmtTerm t) {
      if (t instanceof Num(var v)) {
        return point(v.mod(WORD));
      }
      if (!(t instanceof App(var op, var args))) {
        return read(t);
      }
      return switch (op) {
        case JLONG_ADD -> exact("add", sum(word(args.get(0)), word(args.get(1)), true));
        case JLONG_SUB -> exact("subtract", sum(word(args.get(0)), word(args.get(1)), false));
        case JLONG_NEG -> exact("negate", sum(point(BigInteger.ZERO), word(args.get(0)), false));
        case JLONG_MUL -> args.get(1) instanceof Num(var c)
            ? exact("multiply", scale(word(args.get(0)), c.mod(WORD)))
            : read(t);
        case JLONG_SHL -> args.get(1) instanceof Num(var s)
            ? exact("shift left", scale(word(args.get(0)), BigInteger.ONE.shiftLeft(count(s))))
            : read(t);
        case JLONG_USHR -> args.get(1) instanceof Num(var s)
            ? quotient(word(args.get(0)), BigInteger.ONE.shiftLeft(count(s)))
            : read(t);
        case JLONG_SHR -> args.get(1) instanceof Num(var s)
            ? arithmetic(word(args.get(0)), count(s)) : read(t);
        // A mask that is no 2^k has no meaning here, as it has none in eval: it throws.
        case JLONG_AND_LOW -> args.get(1) instanceof Num(var k2)
            ? low(word(args.get(0)), lowMask(k2)) : read(t);
        case JLONG_AND_SIGN -> {
          Word m = word(args.get(0));
          Word y = word(args.get(1));
          BigInteger ones = WORD.subtract(BigInteger.ONE);
          obligations.add(new Obligation("the mask of jlong.and.sign is a sign word, 0 or -1",
              or(app(Op.EQ, m.u(), num(0)), app(Op.EQ, m.u(), num(ones)))));
          yield new Word(ite(app(Op.EQ, m.u(), num(ones)), y.u(), num(0)), BigInteger.ZERO,
              y.hi());
        }
        case ITE -> {
          Word a = word(args.get(1));
          Word b = word(args.get(2));
          yield new Word(ite(term(args.get(0)), a.u(), b.u()), a.lo().min(b.lo()),
              a.hi().max(b.hi()));
        }
        case ADD, SUB, NEG, MUL, DIV, MOD, LT, LE, GT, GE, EQ, AND, OR, NOT, JINT_IN, JLONG_IN,
            JLONG_WRAP, I2L, L2I, JINT_ADD, JINT_SUB, JINT_MUL, JINT_NEG, JINT_SHL, JINT_SHR,
            JINT_USHR, JLONG_ULT, JLONG_ULE, JLONG_UGT, JLONG_UGE -> read(t);
      };
    }

    private static int count(BigInteger s) {
      return s.mod(BigInteger.valueOf(64)).intValueExact();
    }

    private static Word point(BigInteger v) {
      return new Word(num(v), v, v);
    }

    /** The fallback: the prelude form's reading, {@code (mod t 2^64)}, anywhere in range. */
    private Word read(VarkaSmtTerm t) {
      VarkaSmtTerm signedForm = t instanceof App(var op, var args)
          ? new App(op, args.stream().map(this::term).toList()) : t;
      return new Word(app(Op.MOD, signedForm, num(WORD)), BigInteger.ZERO,
          WORD.subtract(BigInteger.ONE));
    }

    private static Word sum(Word a, Word b, boolean add) {
      return add
          ? new Word(app(Op.ADD, a.u(), b.u()), a.lo().add(b.lo()), a.hi().add(b.hi()))
          : new Word(app(Op.SUB, a.u(), b.u()), a.lo().subtract(b.hi()), a.hi().subtract(b.lo()));
    }

    private static Word scale(Word a, BigInteger c) {
      return new Word(app(Op.MUL, a.u(), num(c)), a.lo().multiply(c), a.hi().multiply(c));
    }

    /** A step kept exact where its interval is in range, the claim an obligation; else reduced. */
    private Word exact(String step, Word w) {
      if (w.lo().signum() >= 0 && w.hi().compareTo(WORD) < 0) {
        obligations.add(new Obligation("the " + step + " " + w.u().render() + " does not wrap",
            and(app(Op.LE, num(0), w.u()), app(Op.LT, w.u(), num(WORD)))));
        return w;
      }
      Quot q = named(w.u(), WORD);
      return new Word(app(Op.SUB, w.u(), app(Op.MUL, num(WORD), q)), BigInteger.ZERO,
          WORD.subtract(BigInteger.ONE));
    }

    private Word quotient(Word a, BigInteger k) {
      return k.equals(BigInteger.ONE) ? a
          : new Word(named(a.u(), k), a.lo().divide(k), a.hi().divide(k));
    }

    private Word low(Word a, BigInteger k2) {
      Quot q = named(a.u(), k2);
      return new Word(app(Op.SUB, a.u(), app(Op.MUL, num(k2), q)), BigInteger.ZERO,
          k2.subtract(BigInteger.ONE).min(a.hi()));
    }

    /** {@code x >> s}: the sign word at 63, else the floor of the signed reading, read back. */
    private Word arithmetic(Word a, int s) {
      if (s == 0) {
        return a;
      }
      BigInteger ones = WORD.subtract(BigInteger.ONE);
      if (s == 63) {
        return new Word(ite(app(Op.GE, a.u(), num(HALF)), num(ones), num(0)), BigInteger.ZERO,
            ones);
      }
      if (a.hi().compareTo(HALF) < 0) {
        return quotient(a, BigInteger.ONE.shiftLeft(s));
      }
      Quot q = named(signed(a.u()), BigInteger.ONE.shiftLeft(s));
      return new Word(ite(app(Op.LT, q, num(0)), app(Op.ADD, q, num(WORD)), q), BigInteger.ZERO,
          ones);
    }

    /** One quotient per dividend and divisor, so a shift and its mask share it. */
    private Quot named(VarkaSmtTerm dividend, BigInteger divisor) {
      return quotients.computeIfAbsent(dividend.render() + "/" + divisor,
          key -> new Quot(QUOTIENT + quotients.size(), dividend, divisor));
    }

    /** The signed reading of an unsigned one. */
    static VarkaSmtTerm signed(VarkaSmtTerm u) {
      if (u instanceof Num(var v)) {
        return num(v.compareTo(HALF) >= 0 ? v.subtract(WORD) : v);
      }
      return ite(app(Op.GE, u, num(HALF)), app(Op.SUB, u, num(WORD)), u);
    }
  }
}
