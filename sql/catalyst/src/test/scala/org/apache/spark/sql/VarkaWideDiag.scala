package org.apache.spark.sql

import java.lang.foreign.{Arena, ValueLayout}

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.{VarkaFusedKernel, VarkaLoopEmitter, VarkaVectorIR}
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._

// Temporary diagnostic, not for commit: where does the wide-shape cliff start under the
// task-11 emitter, and how large are the emitted bodies?
object VarkaWideDiag {
  private val numRows = 1_000_000

  private def chain(depth: Int, slotBase: Int): VarkaVectorIR = {
    var node: VarkaVectorIR = new ColumnRef(0)
    for (level <- 0 until depth) {
      node = if (level % 2 == 0) new AddDays(node, new LiteralSlot(slotBase + level))
      else new SubDays(node, new LiteralSlot(slotBase + level))
    }
    node
  }

  def main(args: Array[String]): Unit = {
    val arena = Arena.ofConfined()
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    try {
      val data = arena.allocate(numRows * 4L, 8)
      val validity = arena.allocate((numRows + 7) / 8L, 8)
      validity.fill(0.toByte)
      var nulls = 0
      for (i <- 0 until numRows) {
        data.set(ValueLayout.JAVA_INT, i * 4L, i % 20000 - 10000)
        if (i % 7 == 0) nulls += 1
        else {
          val off = i / 8L
          val old = validity.get(ValueLayout.JAVA_BYTE, off)
          validity.set(ValueLayout.JAVA_BYTE, off, (old | (1 << (i % 8))).toByte)
        }
      }
      for (k <- Seq(1, 2, 3, 4)) {
        val roots = (0 until k).map(c => chain(16, c * 16))
        val numLits = k * 16
        val offsets = (0 until numLits).map(_ * 7 + 1).toArray
        val name = s"org.apache.spark.sql.varka.execution.VarkaWideDiag$k"
        val jr = new java.util.ArrayList[VarkaVectorIR]()
        roots.foreach(jr.add)
        val bytes = VarkaLoopEmitter.emit(name, jr, 1, numLits)
        // Rough per-method code sizes from the class bytes.
        // scalastyle:off println
        println(s"$k chains: class ${bytes.length} bytes")
        // scalastyle:on println
        loader.defineGeneratedClass(name, bytes)
        val kernel = loader.loadClass(name).getConstructor().newInstance()
          .asInstanceOf[VarkaFusedKernel]
        val dsts = Array.fill(k)(arena.allocate(numRows * 4L, 8))
        val dstVs = Array.fill(k)(arena.allocate((numRows + 7) / 8L, 8))
        def once(): Unit = kernel.run(Array(data.address()), Array(validity.address()),
          Array(nulls), dsts.map(_.address()), dstVs.map(_.address()), offsets, numRows)
        var t = System.nanoTime()
        var iters = 0
        while (System.nanoTime() - t < 2_000_000_000L) { once(); iters += 1 }
        var best = Long.MaxValue
        for (_ <- 0 until math.max(iters / 2, 3)) {
          val s = System.nanoTime()
          once()
          best = math.min(best, System.nanoTime() - s)
        }
        val rate = numRows.toDouble / best * 1000.0
        // scalastyle:off println
        println(f"$k%d chains x depth16 (${k * 16}%d ops): $rate%.0f M rows/s")
        // scalastyle:on println
      }
    } finally {
      loader.release()
      arena.close()
    }
  }
}
