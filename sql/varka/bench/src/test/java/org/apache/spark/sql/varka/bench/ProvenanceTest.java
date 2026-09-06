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

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The block has every key, the VM flag reads back as an integer, and a missing value is n/a. */
public class ProvenanceTest {

  @Test
  public void everyKeyIsPresentAndTheFlagIsAnInteger() {
    Map<String, String> m = Provenance.collect("test", "4.2.0", 0.5, Map.of("commit", "abc"));
    for (String k : new String[] {"label", "spark", "commit", "date", "jdk", "kernel", "cpu",
        "cpu flags", "MaxVectorSize", "power", "load at start"}) {
      assertTrue(m.containsKey(k), k);
    }
    assertEquals("abc", m.get("commit"));
    Integer.parseInt(m.get("MaxVectorSize"));
    assertEquals("0.50", m.get("load at start"));
  }

  @Test
  public void missingHostFactsPrintAsNotAvailable() {
    assertEquals("n/a", Provenance.firstLine("/nonexistent/path"));
    assertEquals("n/a", Provenance.vmOption("NoSuchFlagAnywhere"));
    String text = Provenance.format(Map.of("a", "1"));
    assertTrue(text.startsWith("a:"), text);
  }
}
