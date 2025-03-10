/*
 * Copyright (c) 2019-2021, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids

import scala.collection.mutable.ArrayBuffer

import ai.rapids.cudf.{Cuda, NvtxColor, Table}
import com.nvidia.spark.rapids.RapidsPluginImplicits._
import com.nvidia.spark.rapids.shims.v2.{ShimExpression, ShimUnaryExecNode}

import org.apache.spark.TaskContext
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, SortOrder}
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.types.{DataType, NullType, StructType}
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * Consumes an Iterator of ColumnarBatches and concatenates them into a single ColumnarBatch.
 * The batches will be closed when this operation is done.
 */
object ConcatAndConsumeAll {
  /**
   * Build a single batch from the batches collected so far. If array is empty this will likely
   * blow up.
   * @param arrayOfBatches the batches to concat. This will be consumed and you do not need to
   *                       close any of the batches after this is called.
   * @param schema the schema of the output types.
   * @return a single batch with all of them concated together.
   */
  def buildNonEmptyBatch(arrayOfBatches: Array[ColumnarBatch],
      schema: StructType): ColumnarBatch = {
    if (arrayOfBatches.length == 1) {
      arrayOfBatches(0)
    } else {
      val tables = arrayOfBatches.map(GpuColumnVector.from)
      try {
        val combined = Table.concatenate(tables: _*)
        try {
          GpuColumnVector.from(combined, GpuColumnVector.extractTypes(schema))
        } finally {
          combined.close()
        }
      } finally {
        tables.foreach(_.close())
        arrayOfBatches.foreach(_.close())
      }
    }
  }

  /**
   * Verify that a single batch was returned from the iterator, or if it is empty return an empty
   * batch.
   * @param batches batches to be consumed.
   * @param format the format of the batches in case we need to return an empty batch.  Typically
   *               this is the output of your exec.
   * @return the single batch or an empty batch if needed.  Please be careful that your exec
   *         does not return empty batches as part of an RDD.
   */
  def getSingleBatchWithVerification(batches: Iterator[ColumnarBatch],
      format: Seq[Attribute]): ColumnarBatch = {
    import collection.JavaConverters._
    if (!batches.hasNext) {
      GpuColumnVector.emptyBatch(format.asJava)
    } else {
      val batch = batches.next()
      if (batches.hasNext) {
        batch.close()
        throw new IllegalStateException("Expected to only receive a single batch")
      }
      batch
    }
  }
}

object CoalesceGoal {
  def maxRequirement(a: CoalesceGoal, b: CoalesceGoal): CoalesceGoal = (a, b) match {
    case (RequireSingleBatch, _) => a
    case (_, RequireSingleBatch) => b
    case (_: BatchedByKey, _: TargetSize) => a
    case (_: TargetSize, _: BatchedByKey) => b
    case (a: BatchedByKey, b: BatchedByKey) =>
      if (satisfies(a, b)) {
        a // They are equal so it does not matter
      } else {
        // Nothing is the same so there is no guarantee
        BatchedByKey(Seq.empty)(Seq.empty)
      }
    case (TargetSize(aSize), TargetSize(bSize)) if aSize > bSize => a
    case _ => b
  }

  def minProvided(a: CoalesceGoal, b:CoalesceGoal): CoalesceGoal = (a, b) match {
    case (RequireSingleBatch, _) => b
    case (_, RequireSingleBatch) => a
    case (_: BatchedByKey, _: TargetSize) => b
    case (_: TargetSize, _: BatchedByKey) => a
    case (a: BatchedByKey, b: BatchedByKey) =>
      if (satisfies(a, b)) {
        a // They are equal so it does not matter
      } else {
        null
      }
    case (TargetSize(aSize), TargetSize(bSize)) if aSize < bSize => a
    case _ => b
  }

  def satisfies(found: CoalesceGoal, required: CoalesceGoal): Boolean = (found, required) match {
    case (RequireSingleBatch, _) => true
    case (_, RequireSingleBatch) => false
    case (_: BatchedByKey, _: TargetSize) => true
    case (_: TargetSize, _: BatchedByKey) => false
    case (BatchedByKey(aOrder), BatchedByKey(bOrder)) =>
      aOrder.length == bOrder.length &&
          aOrder.zip(bOrder).forall {
            case (a, b) => a.satisfies(b)
          }
    case (TargetSize(foundSize), TargetSize(requiredSize)) => foundSize >= requiredSize
    case _ => false // found is null so it is not satisfied
  }
}

/**
 * Provides a goal for batching of data.
 */
sealed abstract class CoalesceGoal extends GpuUnevaluable with ShimExpression {
  override def nullable: Boolean = false

  override def dataType: DataType = NullType

  override def children: Seq[Expression] = Seq.empty
}

sealed abstract class CoalesceSizeGoal extends CoalesceGoal {

  val targetSizeBytes: Long = Integer.MAX_VALUE
}

/**
 * A single batch is required as the input to a note in the SparkPlan. This means
 * all of the data for a given task is in a single batch. This should be avoided
 * as much as possible because it can result in running out of memory or run into
 * limitations of the batch size by both Spark and cudf.
 */
case object RequireSingleBatch extends CoalesceSizeGoal {

  override val targetSizeBytes: Long = Long.MaxValue

  /** Override toString to improve readability of Spark explain output */
  override def toString: String = "RequireSingleBatch"
}

/**
 * Produce a stream of batches that are at most the given size in bytes. The size
 * is estimated in some cases so it may go over a little, but it should generally be
 * very close to the target size. Generally you should not go over 2 GiB to avoid
 * limitations in cudf for nested type columns.
 * @param targetSizeBytes the size of each batch in bytes.
 */
case class TargetSize(override val targetSizeBytes: Long) extends CoalesceSizeGoal {
  require(targetSizeBytes <= Integer.MAX_VALUE,
    "Target cannot exceed 2GB without checks for cudf row count limit")
}

/**
 * Split the data into batches where a set of keys are all within a single batch. This is
 * generally used for things like a window operation or a sort based aggregation where you
 * want all of the keys for a given operation to be available so the GPU can produce a
 * correct answer. There is no limit on the target size so if there is a lot of data skew
 * for a key, the batch may still run into limits on set by Spark or cudf. It should be noted
 * that it is required that a node in the Spark plan that requires this should also require
 * an input ordering that satisfies this ordering as well.
 * @param gpuOrder the GPU keys that should be used for batching.
 * @param cpuOrder the CPU keys that should be used for batching.
 */
case class BatchedByKey(gpuOrder: Seq[SortOrder])(val cpuOrder: Seq[SortOrder])
    extends CoalesceGoal {
  require(gpuOrder.size == cpuOrder.size)

  override def otherCopyArgs: Seq[AnyRef] = cpuOrder :: Nil

  override def children: Seq[Expression] = gpuOrder
}

abstract class AbstractGpuCoalesceIterator(
    batches: Iterator[ColumnarBatch],
    goal: CoalesceSizeGoal,
    numInputRows: GpuMetric,
    numInputBatches: GpuMetric,
    numOutputRows: GpuMetric,
    numOutputBatches: GpuMetric,
    streamTime: GpuMetric,
    concatTime: GpuMetric,
    opTime: GpuMetric,
    opName: String) extends Iterator[ColumnarBatch] with Arm with Logging {

  private val iter = new CollectTimeIterator(s"$opName: collect", batches, streamTime)

  private var batchInitialized: Boolean = false

  /**
   * Return true if there is something saved on deck for later processing.
   */
  protected def hasOnDeck: Boolean

  /**
   * Save a batch for later processing.
   */
  protected def saveOnDeck(batch: ColumnarBatch): Unit

  /**
   * If there is anything saved on deck close it.
   */
  protected def clearOnDeck(): Unit

  /**
   * Remove whatever is on deck and return it.
   */
  protected def popOnDeck(): ColumnarBatch

  /** Optional row limit */
  var batchRowLimit: Int = 0

  // note that TaskContext.get() can return null during unit testing so we wrap it in an
  // option here
  Option(TaskContext.get())
      .foreach(_.addTaskCompletionListener[Unit](_ => clearOnDeck()))

  override def hasNext: Boolean = {
    while (!hasOnDeck && iter.hasNext) {
      closeOnExcept(iter.next()) { cb =>
        withResource(new MetricRange(opTime)) { _ =>
          val numRows = cb.numRows()
          numInputBatches += 1
          numInputRows += numRows
          if (numRows > 0) {
            saveOnDeck(cb)
          } else {
            cb.close()
          }
        }
      }
    }
    hasOnDeck
  }

  /**
   * Called first to initialize any state needed for a new batch to be created.
   */
  def initNewBatch(batch: ColumnarBatch): Unit

  /**
   * Called to add a new batch to the final output batch. The batch passed in will
   * not be closed.  If it needs to be closed it is the responsibility of the child class
   * to do it.
   *
   * @param batch the batch to add in.
   */
  def addBatchToConcat(batch: ColumnarBatch): Unit

  /**
   * Called after all of the batches have been added in.
   *
   * @return the concated batches on the GPU.
   */
  def concatAllAndPutOnGPU(): ColumnarBatch

  /**
   * Called to cleanup any state when a batch is done (even if there was a failure)
   */
  def cleanupConcatIsDone(): Unit

  /**
   * Gets the size in bytes of the data buffer for a given column
   */
  def getBatchDataSize(cb: ColumnarBatch): Long = {
    if (cb.numCols() > 0) {
      cb.column(0) match {
        case g: GpuColumnVectorFromBuffer =>
          g.getBuffer.getLength
        case _: GpuColumnVector =>
          (0 until cb.numCols()).map {
            i => cb.column(i).asInstanceOf[GpuColumnVector].getBase.getDeviceMemorySize
          }.sum
        case g: GpuCompressedColumnVector =>
          g.getTableBuffer.getLength
        case g =>
          throw new IllegalStateException(s"Unexpected column type: $g")
      }
    } else {
      0
    }
  }

  /**
   * Each call to next() will combine incoming batches up to the limit specified
   * by [[RapidsConf.GPU_BATCH_SIZE_BYTES]]. However, if any incoming batch is greater
   * than this size it will be passed through unmodified.
   *
   * If the coalesce goal is `RequireSingleBatch` then an exception will be thrown if there
   * is remaining data after the first batch is produced.
   *
   * @return The coalesced batch
   */
  override def next(): ColumnarBatch = withResource(new MetricRange(opTime)) { _ =>
    // reset batch state
    batchInitialized = false
    batchRowLimit = 0

    try {
      var numRows: Long = 0 // to avoid overflows
      var numBytes: Long = 0

      // check if there is a batch "on deck" from a previous call to next()
      if (hasOnDeck) {
        val batch = popOnDeck()
        numRows += batch.numRows()
        numBytes += getBatchDataSize(batch)
        addBatch(batch)
      }

      // there is a hard limit of 2^31 rows
      while (numRows < Int.MaxValue && !hasOnDeck && iter.hasNext) {
        closeOnExcept(iter.next()) { cb =>
          val nextRows = cb.numRows()
          numInputBatches += 1

          // filter out empty batches
          if (nextRows > 0) {
            numInputRows += nextRows
            val nextBytes = getBatchDataSize(cb)

            // calculate the new sizes based on this input batch being added to the current
            // output batch
            val wouldBeRows = numRows + nextRows
            val wouldBeBytes = numBytes + nextBytes

            if (wouldBeRows > Int.MaxValue) {
              if (goal == RequireSingleBatch) {
                throw new IllegalStateException("A single batch is required for this operation," +
                    s" but cuDF only supports ${Int.MaxValue} rows. At least $wouldBeRows" +
                    s" are in this partition. Please try increasing your partition count.")
              }
              saveOnDeck(cb)
            } else if (batchRowLimit > 0 && wouldBeRows > batchRowLimit) {
              saveOnDeck(cb)
            } else if (wouldBeBytes > goal.targetSizeBytes && numBytes > 0) {
              // There are no explicit checks for the concatenate result exceeding the cudf 2^31
              // row count limit for any column. We are relying on cudf's concatenate to throw
              // an exception if this occurs and limiting performance-oriented goals to under
              // 2GB data total to avoid hitting that error.
              saveOnDeck(cb)
            } else {
              addBatch(cb)
              numRows = wouldBeRows
              numBytes = wouldBeBytes
            }
          } else {
            cb.close()
          }
        }
      }

      val isLastBatch = !(hasOnDeck || iter.hasNext)

      // enforce single batch limit when appropriate
      if (goal == RequireSingleBatch && !isLastBatch) {
        throw new IllegalStateException("A single batch is required for this operation." +
            " Please try increasing your partition count.")
      }

      numOutputRows += numRows
      numOutputBatches += 1
      withResource(new NvtxWithMetrics(s"$opName concat", NvtxColor.CYAN, concatTime)) { _ =>
        val batch = concatAllAndPutOnGPU()
        if (isLastBatch) {
          GpuColumnVector.tagAsFinalBatch(batch)
        }
        batch
      }
    } finally {
      cleanupConcatIsDone()
    }
  }

  private def addBatch(batch: ColumnarBatch): Unit = {
    if (!batchInitialized) {
      initNewBatch(batch)
      batchInitialized = true
    }
    addBatchToConcat(batch)
  }
}

class GpuCoalesceIterator(iter: Iterator[ColumnarBatch],
    schema: StructType,
    goal: CoalesceSizeGoal,
    maxDecompressBatchMemory: Long,
    numInputRows: GpuMetric,
    numInputBatches: GpuMetric,
    numOutputRows: GpuMetric,
    numOutputBatches: GpuMetric,
    collectTime: GpuMetric,
    concatTime: GpuMetric,
    opTime: GpuMetric,
    peakDevMemory: GpuMetric,
    spillCallback: SpillCallback,
    opName: String,
    codecConfigs: TableCompressionCodecConfig)
  extends AbstractGpuCoalesceIterator(iter,
    goal,
    numInputRows,
    numInputBatches,
    numOutputRows,
    numOutputBatches,
    collectTime,
    concatTime,
    opTime,
    opName) with Arm {

  private val sparkTypes: Array[DataType] = GpuColumnVector.extractTypes(schema)
  private val batches: ArrayBuffer[SpillableColumnarBatch] = ArrayBuffer.empty
  private var maxDeviceMemory: Long = 0

  override def initNewBatch(batch: ColumnarBatch): Unit = {
    batches.safeClose()
    batches.clear()
  }

  override def addBatchToConcat(batch: ColumnarBatch): Unit =
    batches.append(SpillableColumnarBatch(batch, SpillPriorities.ACTIVE_BATCHING_PRIORITY,
      spillCallback))

  private[this] var codec: TableCompressionCodec = _

  private[this] def popAllDecompressed(): Array[ColumnarBatch] = {
    closeOnExcept(batches.map(_.getColumnarBatch())) { wip =>
      batches.safeClose()
      batches.clear()

      val compressedBatchIndices = wip.zipWithIndex.filter { pair =>
        GpuCompressedColumnVector.isBatchCompressed(pair._1)
      }.map(_._2)
      if (compressedBatchIndices.nonEmpty) {
        val compressedVecs = compressedBatchIndices.map { batchIndex =>
          wip(batchIndex).column(0).asInstanceOf[GpuCompressedColumnVector]
        }
        if (codec == null) {
          val descr = compressedVecs.head.getTableMeta.bufferMeta.codecBufferDescrs(0)
          codec = TableCompressionCodec.getCodec(descr.codec, codecConfigs)
        }
        withResource(codec.createBatchDecompressor(maxDecompressBatchMemory,
            Cuda.DEFAULT_STREAM)) { decompressor =>
          compressedVecs.foreach { cv =>
            val buffer = cv.getTableBuffer
            val bufferMeta = cv.getTableMeta.bufferMeta
            // don't currently support switching codecs when partitioning
            buffer.incRefCount()
            decompressor.addBufferToDecompress(buffer, bufferMeta)
          }
          withResource(decompressor.finishAsync()) { outputBuffers =>
            outputBuffers.zipWithIndex.foreach { case (outputBuffer, outputIndex) =>
              val cv = compressedVecs(outputIndex)
              val batchIndex = compressedBatchIndices(outputIndex)
              val compressedBatch = wip(batchIndex)
              wip(batchIndex) =
                  MetaUtils.getBatchFromMeta(outputBuffer, cv.getTableMeta, sparkTypes)
              compressedBatch.close()
            }
          }
        }
      }
      wip.toArray
    }
  }

  override def concatAllAndPutOnGPU(): ColumnarBatch = {
    val ret = ConcatAndConsumeAll.buildNonEmptyBatch(popAllDecompressed(), schema)
    // sum of current batches and concatenating batches. Approximately sizeof(ret * 2).
    maxDeviceMemory = GpuColumnVector.getTotalDeviceMemoryUsed(ret) * 2
    ret
  }

  override def cleanupConcatIsDone(): Unit = {
    peakDevMemory.set(maxDeviceMemory)
    batches.clear()
  }

  private var onDeck: Option[SpillableColumnarBatch] = None

  override protected def hasOnDeck: Boolean = onDeck.isDefined

  override protected def saveOnDeck(batch: ColumnarBatch): Unit = {
    assert(onDeck.isEmpty)
    onDeck = Some(SpillableColumnarBatch(batch, SpillPriorities.ACTIVE_ON_DECK_PRIORITY,
      spillCallback))
  }

  override protected def clearOnDeck(): Unit = {
    onDeck.foreach(_.close())
    onDeck = None
  }

  override protected def popOnDeck(): ColumnarBatch = {
    val ret = onDeck.get.getColumnarBatch()
    clearOnDeck()
    ret
  }
}

case class GpuCoalesceBatches(child: SparkPlan, goal: CoalesceGoal)
  extends ShimUnaryExecNode with GpuExec {
  import GpuMetric._

  private[this] val (codecConfigs, maxDecompressBatchMemory) = {
    val rapidsConf = new RapidsConf(child.conf)
    (TableCompressionCodec.makeCodecConfig(rapidsConf),
     rapidsConf.shuffleCompressionMaxBatchMemory)
  }

  protected override val outputBatchesLevel: MetricsLevel = MODERATE_LEVEL
  override lazy val additionalMetrics: Map[String, GpuMetric] = Map(
    OP_TIME -> createNanoTimingMetric(MODERATE_LEVEL, DESCRIPTION_OP_TIME),
    NUM_INPUT_ROWS -> createMetric(DEBUG_LEVEL, DESCRIPTION_NUM_INPUT_ROWS),
    NUM_INPUT_BATCHES -> createMetric(DEBUG_LEVEL, DESCRIPTION_NUM_INPUT_BATCHES),
    CONCAT_TIME -> createNanoTimingMetric(DEBUG_LEVEL, DESCRIPTION_CONCAT_TIME),
    PEAK_DEVICE_MEMORY -> createSizeMetric(DEBUG_LEVEL, DESCRIPTION_PEAK_DEVICE_MEMORY)
  ) ++ spillMetrics

  override protected def doExecute(): RDD[InternalRow] = {
    throw new IllegalStateException("ROW BASED PROCESSING IS NOT SUPPORTED")
  }

  override def output: Seq[Attribute] = child.output

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def outputBatching: CoalesceGoal = goal

  override def requiredChildOrdering: Seq[Seq[SortOrder]] = goal match {
    case batchingGoal: BatchedByKey =>
      Seq(batchingGoal.cpuOrder)
    case _ =>
      super.requiredChildOrdering
  }

  override def outputOrdering: Seq[SortOrder] = goal match {
    case batchingGoal: BatchedByKey =>
      batchingGoal.cpuOrder
    case _ =>
      child.outputOrdering
  }

  override def doExecuteColumnar(): RDD[ColumnarBatch] = {
    val numInputRows = gpuLongMetric(NUM_INPUT_ROWS)
    val numInputBatches = gpuLongMetric(NUM_INPUT_BATCHES)
    val numOutputRows = gpuLongMetric(NUM_OUTPUT_ROWS)
    val numOutputBatches = gpuLongMetric(NUM_OUTPUT_BATCHES)
    val concatTime = gpuLongMetric(CONCAT_TIME)
    val opTime = gpuLongMetric(OP_TIME)
    val peakDevMemory = gpuLongMetric(PEAK_DEVICE_MEMORY)

    // cache in local vars to avoid serializing the plan
    val outputSchema = schema
    val decompressMemoryTarget = maxDecompressBatchMemory

    val batches = child.executeColumnar()
    if (outputSchema.isEmpty) {
      batches.mapPartitions { iter =>
        val numRows = iter.map(_.numRows).sum
        val combinedCb = new ColumnarBatch(Array.empty, numRows)
        Iterator.single(combinedCb)
      }
    } else {
      val callback = GpuMetric.makeSpillCallback(allMetrics)
      goal match {
        case sizeGoal: CoalesceSizeGoal =>
          batches.mapPartitions { iter =>
            new GpuCoalesceIterator(iter, outputSchema, sizeGoal, decompressMemoryTarget,
              numInputRows, numInputBatches, numOutputRows, numOutputBatches, NoopMetric,
              concatTime, opTime, peakDevMemory, callback, "GpuCoalesceBatches",
              codecConfigs)
          }
        case batchingGoal: BatchedByKey =>
          val targetSize = RapidsConf.GPU_BATCH_SIZE_BYTES.get(conf)
          val f = GpuKeyBatchingIterator.makeFunc(batchingGoal.gpuOrder, output.toArray, targetSize,
            numInputRows, numInputBatches, numOutputRows, numOutputBatches,
            concatTime, opTime, peakDevMemory, callback)
          batches.mapPartitions { iter =>
            f(iter)
          }
      }
    }
  }
}
