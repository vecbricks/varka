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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitCostCorpus.Group;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitCostCorpus.Shape;

/**
 * How well the emit cost model predicts, rendered as {@code sql/varka/emit_cost_audit.json}:
 * both pricings against the classes the emitter builds, method by method, over shapes neither
 * was derived from, and what the grouping switch the fitted prices drive does to the number of
 * builds and loop methods. It counts bytes and call sites rather than time, so it is the same on
 * any machine with the same JDK, and every claim about the model's accuracy quotes it.
 * {@code VarkaEmitCostAuditSuite} pins the file.
 */
final class VarkaEmitCostAudit {

  private VarkaEmitCostAudit() {}

  /** The two pricings the audit scores, by the name the file gives each. */
  static final Map<String, Map<String, double[]>> PRICINGS = orderedOf(
      "fitted (VarkaEmitCostTable)", VarkaEmitCostTable.PRICES,
      "register (VarkaEmitCostRegister)", VarkaEmitCostRegister.PRICES);

  /** The shapes the audit scores: the odd-numbered fuzz and wide shapes, and every ladder. */
  static List<Shape> heldOut() {
    List<Shape> shapes = new ArrayList<>();
    VarkaEmitCostCorpus.fuzz().stream().filter(s -> s.index() % 2 == 1).forEach(shapes::add);
    VarkaEmitCostCorpus.wide().stream().filter(s -> s.index() % 2 == 1).forEach(shapes::add);
    shapes.addAll(VarkaEmitCostCorpus.ladders());
    return shapes;
  }

  /** One method of one group: the measured quantity and each pricing's prediction of it. */
  private record Point(String family, boolean sites, double measured, double measuredBytes,
      Map<String, Double> predicted) {}

  private static List<Point> points(List<Shape> shapes) {
    List<Point> points = new ArrayList<>();
    for (Shape shape : shapes) {
      for (Map.Entry<String, VarkaEmitOptions> arm : VarkaEmitCostCorpus.arms()) {
        for (Group group : VarkaEmitCostCorpus.measure(shape.roots(), shape.numInputs(),
            shape.numLiterals(), arm.getValue()).orElse(List.of())) {
          Map<String, double[]> predictions = new LinkedHashMap<>();
          PRICINGS.forEach((name, prices) ->
              predictions.put(name, VarkaEmitCost.predict(prices, group.counts())));
          for (int q = 0; q < VarkaEmitCost.QUANTITIES; q++) {
            if (Double.isNaN(group.measured()[q])) {
              continue;
            }
            Map<String, Double> predicted = new LinkedHashMap<>();
            final int at = q;
            predictions.forEach((name, p) -> predicted.put(name, p[at]));
            points.add(new Point(shape.family(), q >= 4, group.measured()[q],
                group.measured()[q % 4], predicted));
          }
        }
      }
    }
    return points;
  }

  /** The size bands the errors are reported in, by the method's measured bytes. */
  private static final Map<String, Predicate<Double>> BANDS = orderedOf(
      "under 500 bytes", (Predicate<Double>) b -> b < 500,
      "500 to 1999 bytes", (Predicate<Double>) b -> b >= 500 && b < 2000,
      "2000 to 7999 bytes", (Predicate<Double>) b -> b >= 2000 && b < 8000,
      "8000 bytes and over", (Predicate<Double>) b -> b >= 8000,
      "2000 bytes and over", (Predicate<Double>) b -> b >= 2000);

  @SuppressWarnings("unchecked")
  private static <V> Map<String, V> orderedOf(Object... pairs) {
    Map<String, V> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], (V) pairs[i + 1]);
    }
    return map;
  }

  private static double round(double v) {
    return Math.round(v * 10) / 10.0;
  }

  /** The error distribution of one pricing over {@code ps}, relative to what was measured. */
  private static Map<String, Object> stats(List<Point> ps, String pricing) {
    List<Point> scored = ps.stream().filter(p -> p.measured() > 0).toList();
    if (scored.isEmpty()) {
      return orderedOf("methods", 0);
    }
    double[] errors = scored.stream()
        .mapToDouble(p -> 100.0 * Math.abs(p.predicted().get(pricing) - p.measured())
            / p.measured())
        .sorted().toArray();
    java.util.function.DoubleUnaryOperator pct =
        q -> round(errors[Math.min(errors.length - 1, (int) (q * errors.length))]);
    long under = scored.stream().filter(p -> p.predicted().get(pricing) < p.measured()).count();
    long within = java.util.Arrays.stream(errors).filter(e -> e <= 10.0).count();
    return orderedOf(
        "methods", scored.size(),
        "median error %", pct.applyAsDouble(0.5),
        "90th percentile %", pct.applyAsDouble(0.9),
        "99th percentile %", pct.applyAsDouble(0.99),
        "worst %", round(errors[errors.length - 1]),
        "under-predicted %", round(100.0 * under / scored.size()),
        "within 10% share", round(100.0 * within / errors.length));
  }

  private static Map<String, Object> accuracy(List<Point> all) {
    Map<String, Object> byPricing = new LinkedHashMap<>();
    for (String pricing : PRICINGS.keySet()) {
      Map<String, Object> quantities = new LinkedHashMap<>();
      for (boolean sites : new boolean[] {false, true}) {
        Map<String, Object> bands = new LinkedHashMap<>();
        BANDS.forEach((band, in) -> bands.put(band, stats(all.stream()
            .filter(p -> p.sites() == sites && in.test(p.measuredBytes())).toList(), pricing)));
        quantities.put(sites ? "call sites" : "bytes", bands);
      }
      byPricing.put(pricing, quantities);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("by pricing, quantity and size band", byPricing);
    for (boolean sites : new boolean[] {false, true}) {
      Map<String, Object> families = new LinkedHashMap<>();
      for (String pricing : PRICINGS.keySet()) {
        Map<String, Object> perFamily = new LinkedHashMap<>();
        all.stream().map(Point::family).distinct().forEach(family ->
            perFamily.put(family, stats(all.stream().filter(p -> p.sites() == sites
                && p.family().equals(family) && p.measuredBytes() >= 2000).toList(), pricing)));
        families.put(pricing, perFamily);
      }
      result.put((sites ? "call sites" : "bytes")
          + " of methods of 2000 bytes and over, by shape family", families);
    }
    return result;
  }

  /**
   * One shape at the shipped options: what the emitter's size control did to build it, and its
   * loop methods, or -1 when it declined.
   */
  private record Emitted(VarkaEmitTrace trace, int loops) {
    int builds() {
      return trace.builds;
    }
  }

  private static Emitted emitted(Shape shape, boolean predict) {
    VarkaEmitTrace trace = new VarkaEmitTrace();
    try {
      byte[] bytes = VarkaLoopEmitter.emitTraced(
          "org.apache.spark.sql.varka.execution.VarkaEmitCostAudit", shape.roots(),
          shape.numInputs(), shape.numLiterals(), shipped(predict), trace);
      var names = VarkaEmittedClass.measure(bytes).codeLength().keySet();
      int loops = (int) Math.max(names.stream().filter(n -> n.startsWith("loopDense")).count(),
          names.stream().filter(n -> n.startsWith("loopMasked")).count());
      return new Emitted(trace, loops);
    } catch (VarkaEmitDeclined e) {
      return new Emitted(trace, -1);
    }
  }

  /** A shape built more than once: its index, its builds, and the reactions that forced them. */
  private static String rebuilt(Shape s, VarkaEmitTrace t) {
    List<String> why = new ArrayList<>();
    t.reactions().forEach((name, count) -> {
      if (count > 0) {
        why.add(count + " " + name);
      }
    });
    return s.index() + ": " + t.builds + " builds (" + String.join(", ", why) + ")";
  }

  /** The shipped options at the audit's width, with the switch on or off. */
  static VarkaEmitOptions shipped(boolean predict) {
    return VarkaEmitOptions.DEFAULTS.withLanesOverride(VarkaEmitCostCorpus.LANES)
        .withPredictGrouping(predict);
  }

  private static Map<String, Object> grouping(List<Shape> shapes) {
    Map<String, Object> families = new LinkedHashMap<>();
    for (String family : shapes.stream().map(Shape::family).distinct().toList()) {
      List<Shape> mine = shapes.stream().filter(s -> s.family().equals(family)).toList();
      int oneOff = 0;
      int oneOn = 0;
      int loopsOff = 0;
      int loopsOn = 0;
      int declinedOff = 0;
      int declinedOn = 0;
      int declineBuildsOff = 0;
      int declineBuildsOn = 0;
      int buildsOff = 0;
      int buildsOn = 0;
      Map<String, Integer> reactionsOff = new LinkedHashMap<>();
      Map<String, Integer> reactionsOn = new LinkedHashMap<>();
      List<String> rebuiltOff = new ArrayList<>();
      List<String> rebuiltOn = new ArrayList<>();
      List<String> gained = new ArrayList<>();
      StringBuilder groupings = new StringBuilder();
      for (Shape s : mine) {
        Emitted off = emitted(s, false);
        Emitted on = emitted(s, true);
        groupings.append(s.index()).append(VarkaLoopEmitter.groupsForTest(s.roots(),
            shipped(true)).stream().map(List::size).toList()).append('\n');
        declinedOff += off.loops() < 0 ? 1 : 0;
        declinedOn += on.loops() < 0 ? 1 : 0;
        buildsOff += off.builds();
        buildsOn += on.builds();
        off.trace().reactions().forEach((name, count) -> reactionsOff.merge(name, count,
            Integer::sum));
        on.trace().reactions().forEach((name, count) -> reactionsOn.merge(name, count,
            Integer::sum));
        if (off.builds() > 1) {
          rebuiltOff.add(rebuilt(s, off.trace()));
        }
        if (on.builds() > 1) {
          rebuiltOn.add(rebuilt(s, on.trace()));
        }
        if (off.loops() < 0 && on.loops() < 0) {
          declineBuildsOff += off.builds();
          declineBuildsOn += on.builds();
        }
        if (off.loops() >= 0 && on.loops() >= 0) {
          oneOff += off.builds() == 1 ? 1 : 0;
          oneOn += on.builds() == 1 ? 1 : 0;
          loopsOff += off.loops();
          loopsOn += on.loops();
          if (on.loops() > off.loops()) {
            gained.add(s.index() + ": " + off.loops() + " -> " + on.loops());
          }
        }
      }
      families.put(family, orderedOf(
          "shapes", mine.size(),
          "emitted in one build, weights", oneOff,
          "emitted in one build, predicted", oneOn,
          "loop methods, weights", loopsOff,
          "loop methods, predicted", loopsOn,
          "shapes with more loop methods, predicted", gained,
          "declined, weights", declinedOff,
          "declined, predicted", declinedOn,
          "builds spent on shapes both decline, weights", declineBuildsOff,
          "builds spent on shapes both decline, predicted", declineBuildsOn,
          "builds, weights", buildsOff,
          "builds, predicted", buildsOn,
          "reactions, weights", reactionsOff,
          "reactions, predicted", reactionsOn,
          "shapes built more than once, weights", rebuiltOff,
          "shapes built more than once, predicted", rebuiltOn,
          "predicted first groupings, digest", digest(groupings.toString())));
    }
    return families;
  }

  private static String digest(String text) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(
          text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(h, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The audit file's text. {@code extra} are shapes only the count of builds reads, beside the
   * held-out ones: the families {@code PLAN_TASK_236.md} 2 added. The count is of the emitter's
   * builds; the compiler's admission emissions, several kernels' bisection among them, are not
   * in it.
   */
  static String render(List<Shape> extra) {
    List<Shape> shapes = heldOut();
    List<Shape> built = new ArrayList<>(shapes);
    built.addAll(extra);
    Map<String, Object> doc = orderedOf(
        "generated_by", "VarkaEmitCostAuditSuite; regenerate with VARKA_COST_REGEN=true "
            + "build/sbt 'catalyst/testOnly *VarkaEmitCostAuditSuite'",
        "description", "The emit cost model's two pricings - the fitted prices the grouping "
            + "uses (VarkaEmitCostTable) and the measured register (VarkaEmitCostRegister) - "
            + "scored against the classes the emitter builds, at sixteen int lanes, over the "
            + "odd-numbered fuzz and wide shapes and every ladder, none of which either was "
            + "derived from, each emitted under its default grouping and under groups up to the "
            + "fused ceiling. An error is |predicted - measured| / measured for one method of "
            + "one group. Then each shape at the shipped options with predictGrouping off and "
            + "on: how many builds it took, its loop methods, the builds spent on shapes that "
            + "decline either way, every build and the reaction to a measurement that forced it, "
            + "the shapes built more than once, and a digest of the first "
            + "groupings the prediction forms, which moves whenever the prices regroup a shape. "
            + "The count of builds also reads task 200's mixed and interleaved families, and "
            + "the size ladder past the driver's ceiling with compositions of wide draws near and "
            + "past it; it counts the emitter's builds, not the compiler's admission emissions. "
            + "See PLAN_TASK_199.md and PLAN_TASK_236.md.",
        "jdk", System.getProperty("java.specification.version"),
        "accuracy", accuracy(points(shapes)),
        "grouping", grouping(built));
    try {
      return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n";
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
