package ru.itclover.tsp.spark.io

import ru.itclover.tsp.spark.utils.RowWithIdx

trait InputConf[Event, EKey, EItem] extends Serializable {
  def sourceId: Int // todo .. Rm

  def datetimeField: Symbol
  def partitionFields: Seq[Symbol]

  def parallelism: Option[Int] // Parallelism per each source
  def numParallelSources: Option[Int] // Number on parallel (separate) sources to be created
  def patternsParallelism: Option[Int] // Number of parallel branches after source step

  def eventsMaxGapMs: Long
  def defaultEventsGapMs: Long
  def chunkSizeMs: Option[Long] // Chunk size

  def dataTransformation: Option[SourceDataTransformation[Event, EKey, EItem]]

  def defaultToleranceFraction: Option[Double]

  // Set maximum number of physically independent partitions for stream.keyBy operation
  def maxPartitionsParallelism: Int = 8192
}

case class JDBCInputConf(
  sourceId: Int,
  jdbcUrl: String,
  query: String,
  driverName: String,
  datetimeField: Symbol,
  eventsMaxGapMs: Long,
  defaultEventsGapMs: Long,
  chunkSizeMs: Option[Long],
  partitionFields: Seq[Symbol],
  userName: Option[String] = None,
  password: Option[String] = None,
  dataTransformation: Option[SourceDataTransformation[RowWithIdx, Symbol, Any]] = None,
  defaultToleranceFraction: Option[Double] = None,
  parallelism: Option[Int] = None,
  numParallelSources: Option[Int] = Some(1),
  patternsParallelism: Option[Int] = Some(1),
  timestampMultiplier: Option[Double] = Some(1000.0)
) extends InputConf[RowWithIdx, Symbol, Any]
