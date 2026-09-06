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

package org.apache.spark.sql.varka.bench;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.sun.management.HotSpotDiagnosticMXBean;

/**
 * The provenance block at the top of every results file: the fields
 * {@code dev/varka_bench_regen.sh} writes (commit, date, JDK, kernel, CPU, power, load), plus
 * what this benchmark needs to be believed across machines - Spark's version, the
 * {@code MaxVectorSize} the JVM actually ran with (read back from the running VM, not from the
 * command line), the AVX-512 flags the CPU reports, and whatever the shell driver knows and the
 * JVM cannot (the git commit, the datapath probe), passed in as {@code key=value}.
 * A value the host cannot provide prints as {@code n/a}; nothing here fails a run.
 */
public final class Provenance {

  private Provenance() {}

  /** Collects the block, in print order. */
  public static Map<String, String> collect(
      String label, String sparkVersion, double loadAtStart, Map<String, String> extra) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("label", label);
    m.put("spark", sparkVersion);
    extra.forEach(m::put);
    m.put("date", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    m.put("jdk", System.getProperty("java.vm.name", "n/a") + " "
        + System.getProperty("java.runtime.version", "n/a"));
    m.put("kernel", System.getProperty("os.version", "n/a"));
    m.put("cpu", cpuInfo("model name").orElse("n/a"));
    m.put("cpu flags", avxFlags());
    m.put("MaxVectorSize", vmOption("MaxVectorSize"));
    m.put("power", "governor=" + firstLine("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        + " epp=" + firstLine(
            "/sys/devices/system/cpu/cpu0/cpufreq/energy_performance_preference"));
    m.put("load at start", String.format(java.util.Locale.ROOT, "%.2f", loadAtStart));
    return m;
  }

  /** The block as text, one {@code key: value} per line, keys padded to one column. */
  public static String format(Map<String, String> m) {
    int width = 0;
    for (String k : m.keySet()) {
      width = Math.max(width, k.length() + 1);
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : m.entrySet()) {
      sb.append(String.format(java.util.Locale.ROOT, "%-" + (width + 2) + "s%s%n",
          e.getKey() + ":", e.getValue()));
    }
    return sb.toString();
  }

  /** The 1-minute load average, or -1 where {@code /proc/loadavg} is absent. */
  public static double loadAverage() {
    String line = firstLine("/proc/loadavg");
    if (line.equals("n/a")) {
      return -1.0;
    }
    try {
      return Double.parseDouble(line.split("\\s+")[0]);
    } catch (NumberFormatException e) {
      return -1.0;
    }
  }

  static String vmOption(String name) {
    try {
      HotSpotDiagnosticMXBean bean =
          ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
      return bean.getVMOption(name).getValue();
    } catch (RuntimeException | LinkageError e) {
      return "n/a";
    }
  }

  static Optional<String> cpuInfo(String key) {
    try {
      for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
        int colon = line.indexOf(':');
        if (colon > 0 && line.substring(0, colon).trim().equals(key)) {
          return Optional.of(line.substring(colon + 1).trim());
        }
      }
    } catch (IOException | RuntimeException e) {
      // fall through
    }
    return Optional.empty();
  }

  /** The {@code avx*} tokens of the CPU's flags line, the ones that decide the vector width. */
  static String avxFlags() {
    Optional<String> flags = cpuInfo("flags");
    if (flags.isEmpty()) {
      return "n/a";
    }
    List<String> avx = new ArrayList<>();
    for (String f : flags.get().split("\\s+")) {
      if (f.startsWith("avx")) {
        avx.add(f);
      }
    }
    return avx.isEmpty() ? "none" : String.join(" ", avx);
  }

  static String firstLine(String path) {
    try {
      List<String> lines = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
      return lines.isEmpty() ? "n/a" : lines.get(0).trim();
    } catch (IOException | RuntimeException e) {
      return "n/a";
    }
  }
}
