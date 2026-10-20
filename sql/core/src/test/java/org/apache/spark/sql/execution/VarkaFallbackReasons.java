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
package org.apache.spark.sql.execution;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import scala.jdk.javaapi.CollectionConverters;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.execution.metric.SQLMetric;
import org.apache.spark.sql.execution.ui.SQLAppStatusStore;
import org.apache.spark.sql.execution.ui.SQLExecutionUIData;
import org.apache.spark.sql.execution.varka.VarkaFallbackAccounting;
import org.apache.spark.sql.execution.varka.VarkaReasonMetric;

/**
 * Reads a Varka node's declined batches by refusal reason, the way a suite asserts which reason a
 * query fell back for (VARKA-313; Comet's {@code checkSparkAnswerAndFallbackReason} is the model):
 * from the driver's accumulators, decoded into the batches that named a reason exactly and those
 * whose candidate set included it ({@link VarkaReasonMetric}), and as the SQL UI renders them.
 */
public final class VarkaFallbackReasons {

  private VarkaFallbackReasons() {
  }

  /** Each reason's batches that named it exactly, from the driver's accumulators. */
  public static Map<String, Long> counts(SparkPlan node) {
    var out = new LinkedHashMap<String, Long>();
    for (String reason : VarkaFallbackAccounting.REASONS) {
      out.put(reason, VarkaReasonMetric.exact(metric(node, reason).value()));
    }
    return out;
  }

  /**
   * Each reason's batches whose status named it among others under the default emit options, a
   * candidate set that cannot be counted against it.
   */
  public static Map<String, Long> unattributed(SparkPlan node) {
    var out = new LinkedHashMap<String, Long>();
    for (String reason : VarkaFallbackAccounting.REASONS) {
      out.put(reason, VarkaReasonMetric.unattributed(metric(node, reason).value()));
    }
    return out;
  }

  /**
   * Each reason as the SQL UI renders it for the execution that ran {@code node}: a count, or
   * {@link VarkaReasonMetric#NOT_REPORTED}. The execution is the one whose metrics hold the
   * node's reason metrics, not the session's latest, because another execution can be numbered
   * after the checked one. A reason that no task ever bumped has no entry, so any of the node's
   * reasons identifies the execution. Waits for the listener bus first, since the UI's store is
   * filled from it.
   */
  public static Map<String, String> rendered(SparkSession session, SparkPlan node)
      throws TimeoutException {
    session.sparkContext().listenerBus().waitUntilEmpty();
    SQLAppStatusStore store = session.sharedState().statusStore();
    var ids = new HashSet<Long>();
    for (String reason : VarkaFallbackAccounting.REASONS) {
      ids.add(metric(node, reason).id());
    }
    long own = -1;
    for (SQLExecutionUIData execution : CollectionConverters.asJava(store.executionsList())) {
      var reported = store.executionMetrics(execution.executionId());
      if (ids.stream().anyMatch(reported::contains)) {
        own = Math.max(own, execution.executionId());
      }
    }
    if (own < 0) {
      throw new IllegalStateException("no SQL execution of the session reports the node's metrics");
    }
    scala.collection.immutable.Map<Object, String> values = store.executionMetrics(own);
    var out = new LinkedHashMap<String, String>();
    for (String reason : VarkaFallbackAccounting.REASONS) {
      scala.Option<String> value = values.get(metric(node, reason).id());
      out.put(reason, value.isDefined() ? value.get() : null);
    }
    return out;
  }

  private static SQLMetric metric(SparkPlan node, String reason) {
    String key = VarkaExecMetrics.reasonKey(reason);
    scala.Option<SQLMetric> metric = node.metrics().get(key);
    if (metric.isEmpty()) {
      throw new IllegalArgumentException(node.nodeName() + " has no metric " + key);
    }
    return metric.get();
  }
}
