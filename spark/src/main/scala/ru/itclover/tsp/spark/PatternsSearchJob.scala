package ru.itclover.tsp.spark


import cats.Traverse
import cats.data.Validated
import cats.implicits._
import com.typesafe.scalalogging.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrameWriter, Dataset, Row, SQLContext, SaveMode, SparkSession}
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.streaming.Milliseconds
import ru.itclover.tsp.core.IncidentInstances.semigroup
import ru.itclover.tsp.core.Pattern.IdxExtractor
import ru.itclover.tsp.core.aggregators.TimestampsAdderPattern
import ru.itclover.tsp.core.io.{BasicDecoders, Extractor, TimeExtractor}
import ru.itclover.tsp.core.optimizations.Optimizer
import ru.itclover.tsp.core.{Incident, RawPattern, _}
import ru.itclover.tsp.dsl.{ASTPatternGenerator, AnyState, PatternMetadata}
import ru.itclover.tsp.spark.utils._
import ru.itclover.tsp.spark.io.{JDBCOutputConf, OutputConf, RowSchema}
import ru.itclover.tsp.spark.transformers.SparseRowsDataAccumulator
import ru.itclover.tsp.spark.utils.ErrorsADT.{ConfigErr, InvalidPatternsCode}
//import ru.itclover.tsp.utils.ErrorsADT.{ConfigErr, InvalidPatternsCode}
// import ru.itclover.tsp.spark.utils.EncoderInstances._
import org.apache.spark.mllib.rdd.RDDFunctions._

import scala.reflect.ClassTag

case class PatternsSearchJob[In: ClassTag, InKey, InItem](
                                                                  source: StreamSource[In, InKey, InItem],
                                                                  fields: Set[InKey],
                                                                  decoders: BasicDecoders[InItem]
                                                                ) {
  // TODO: Restore InKey as a type parameter

  import PatternsSearchJob._
  import decoders._
  // import source.{eventCreator, keyCreator, kvExtractor}

  def patternsSearchStream[OutE: ClassTag, OutKey, S](
                                                              rawPatterns: Seq[RawPattern],
                                                              outputConf: OutputConf[OutE],
                                                              resultMapper: Incident => OutE,
                                                            ): Either[ConfigErr, (Seq[RichPattern[In, Segment, AnyState[Segment]]], DataFrameWriter[OutE])] = {
    import source.{idxExtractor, transformedExtractor, transformedTimeExtractor}
    preparePatterns[In, S, InKey, InItem](
      rawPatterns,
      source.fieldToEKey,
      source.conf.defaultToleranceFraction.getOrElse(0),
      source.conf.eventsMaxGapMs,
      source.fieldsClasses.map { case (s, c) => s -> ClassTag(c) }.toMap
    ).map { patterns =>
      val forwardFields = outputConf.forwardedFieldsIds.map(id => (id, source.fieldToEKey(id)))
      val useWindowing = true // !source.conf.isInstanceOf[KafkaInputConf]
      val incidents = cleanIncidentsFromPatterns(patterns, forwardFields, useWindowing)
      val mapped = incidents.map(resultMapper)
      (patterns,  saveStream(rddToDataset(mapped, outputConf.rowSchema), outputConf))
    }
  }

  def cleanIncidentsFromPatterns(
                                  richPatterns: Seq[RichPattern[In, Segment, AnyState[Segment]]],
                                  forwardedFields: Seq[(Symbol, InKey)],
                                  useWindowing: Boolean
                                ): RDD[Incident] = {
    import source.timeExtractor
    val stream = source.createStream
    val singleIncidents = incidentsFromPatterns(
      applyTransformation(stream/*.assignAscendingTimestamps(timeExtractor(_).toMillis)*/)(source.spark),
      richPatterns,
      forwardedFields,
      useWindowing
    )
    if (source.conf.defaultEventsGapMs > 0L) reduceIncidents(singleIncidents)(source.spark) else singleIncidents
  }

  def incidentsFromPatterns[T](
                                stream: RDD[In],
                                patterns: Seq[RichPattern[In, Segment, AnyState[Segment]]],
                                forwardedFields: Seq[(Symbol, InKey)],
                                useWindowing: Boolean
                              ): RDD[Incident] = {

    import source.{transformedExtractor, idxExtractor, transformedTimeExtractor => timeExtractor}

    log.debug("incidentsFromPatterns started")

    val mappers: Seq[PatternProcessor[In, Optimizer.S[Segment], Incident]] = patterns.map {
      case ((pattern, meta), rawP) =>
        val allForwardFields = forwardedFields ++ rawP.forwardedFields.map(id => (id, source.fieldToEKey(id)))

        val toIncidents = ToIncidentsMapper(
          rawP.id,
          allForwardFields.map { case (id, k) => id.toString.tail -> k },
          rawP.payload.toSeq,
          if (meta.sumWindowsMs > 0L) meta.sumWindowsMs else source.conf.defaultEventsGapMs,
          source.conf.partitionFields.map(source.fieldToEKey)
        )

        val optimizedPattern = new Optimizer[In].optimize(pattern)

        val incidentPattern = MapWithContextPattern(optimizedPattern)(toIncidents.apply)

        PatternProcessor[In, Optimizer.S[Segment], Incident](
          incidentPattern,
          source.conf.eventsMaxGapMs
        )
    }
    val keyedRDD = stream
      .keyBy(source.transformedPartitioner)
      //.partitionBy()
    val windowed: RDD[Iterable[In]] =
      if (useWindowing) {
        val chunkSize = 100000
        keyedRDD
          .map { case (k, x) => ((k, (idxExtractor(x) / chunkSize)), x)}
          .groupByKey()
          .map(_._2)
          //.window(Milliseconds(source.conf.chunkSizeMs.getOrElse(900000)))
      } else {
        keyedRDD.map{ case (_, x) => List(x) }
      }
    val processed = windowed
      .flatMap[Incident](
        (x: Iterable[In]) => ProcessorCombinator(mappers, timeExtractor).process(x)
      )
      //.setMaxParallelism(source.conf.maxPartitionsParallelism)

    log.debug("incidentsFromPatterns finished")
    processed
  }

  // TODO: Remove that stub
  def applyTransformation(stream: RDD[In])(implicit spark: SparkSession): RDD[In] = source.conf.dataTransformation match {
    case Some(_) =>
      import source.{extractor, timeExtractor, kvExtractor, eventCreator, keyCreator}
      val acc = SparseRowsDataAccumulator[In, InKey, InItem, In](source.asInstanceOf[StreamSource[In, InKey, InItem]], fields)
      stream
        .union(spark.sparkContext.parallelize(List(source.eventCreator.create(Seq.empty))))
        .coalesce(1)
        .flatMap(acc.process)
    case None => stream
  }

//  def applyTransformation(stream: RDD[In]): RDD[In] = source.conf.dataTransformation match {
//    case Some(_) =>
//      import source.{extractor, timeExtractor}
//      RDD
//        .keyBy(source.partitioner)
//        .process(
//          SparseRowsDataAccumulator[In, InKey, InItem, In](source.asInstanceOf[StreamSource[In, InKey, InItem]], fields)
//        )
//        .setParallelism(1) // SparseRowsDataAccumulator cannot work in parallel
//    case _ => stream
//  }
  def rddToDataset[Out](rdd: RDD[Out], rowSchema: RowSchema): Dataset[Out] = {
    source.spark.createDataFrame(rdd.asInstanceOf[RDD[Row]], rowSchemaToSchema(rowSchema)).asInstanceOf[Dataset[Out]]
  }
}

object PatternsSearchJob {
  type RichSegmentedP[E] = RichPattern[E, Segment, AnyState[Segment]]
  type RichPattern[E, T, S] = ((Pattern[E, S, T], PatternMetadata), RawPattern)

  val log = Logger("PatternsSearchJob")
  def maxPartitionsParallelism = 8192

  def preparePatterns[E, S, EKey, EItem](
                                          rawPatterns: Seq[RawPattern],
                                          fieldsIdxMap: Symbol => EKey,
                                          toleranceFraction: Double,
                                          eventsMaxGapMs: Long,
                                          fieldsTags: Map[Symbol, ClassTag[_]]
                                        )(
                                          implicit extractor: Extractor[E, EKey, EItem],
                                          getTime: TimeExtractor[E],
                                          idxExtractor: IdxExtractor[E] /*,
    dDecoder: Decoder[EItem, Double]*/
                                        ): Either[ConfigErr, List[RichPattern[E, Segment, AnyState[Segment]]]] = {

    log.debug("preparePatterns started")

    val pGenerator = ASTPatternGenerator[E, EKey, EItem]()(
      idxExtractor,
      getTime,
      extractor,
      fieldsIdxMap
    )
    val res = Traverse[List]
      .traverse(rawPatterns.toList)(
        p =>
          Validated
            .fromEither(pGenerator.build(p.sourceCode, toleranceFraction, eventsMaxGapMs, fieldsTags))
            .leftMap(err => List(s"PatternID#${p.id}, error: ${err.getMessage}"))
            .map(
              p =>
                (
                  new TimestampsAdderPattern(SegmentizerPattern(p._1))
                    .asInstanceOf[Pattern[E, AnyState[Segment], Segment]],
                  p._2
                )
            )
      )
      .leftMap[ConfigErr](InvalidPatternsCode(_))
      .map(_.zip(rawPatterns))
      .toEither

    log.debug("preparePatterns finished")

    res
  }

  def reduceIncidents(incidents: RDD[Incident])(implicit spark: SparkSession): RDD[Incident] = {
    log.debug("reduceIncidents started")

    // WARNING: Non-parallelizable, TODO: better solution
    var seriesCount = 1

    if (incidents.isEmpty) {
      return incidents
    }

    // Repeat the starting entry
    val newIncidents = spark.sparkContext.parallelize(List(incidents.first)).union(incidents)

    val res = newIncidents
        .sliding(2)
        .map(data => {
          if (data(1).segment.from.toMillis - data(0).segment.to.toMillis > data(1).maxWindowMs) {
            seriesCount += 1
          }
          (seriesCount, data(1))
        })
        .reduceByKey { _ |+| _ }
        .map(_._2)
      //.assignAscendingTimestamps_withoutWarns(p => p.segment.from.toMillis)
      //.keyBy(_.id)
//      .window(EventTimeSessionWindows.withDynamicGap(new SessionWindowTimeGapExtractor[Incident] {
//        override def extract(element: Incident): Long = element.maxWindowMs
//      }))
//      .reduce { _ |+| _ }
//      .name("Uniting adjacent incidents")

    log.debug("reduceIncidents finished")
    res
  }

  def saveStream[E](stream: Dataset[E], outputConf: OutputConf[E]): DataFrameWriter[E] = {
    log.debug("saveStream started")

    outputConf match {
//      case kafkaConf: KafkaOutputConf =>
//        // TODO: Kafka
//        log.debug("saveStream finished")
//        res
//
//      case redisConf: RedisOutputConf =>
//        // TODO: Redis
//        log.debug("saveStream finished")
//        res

      case jdbcConf: JDBCOutputConf =>
        val res = stream.write
          .format("jdbc")
            .option("url", jdbcConf.jdbcUrl)
            .option("dbtable", jdbcConf.tableName)
            .option("user", jdbcConf.userName.getOrElse(""))
            .option("password", jdbcConf.password.getOrElse(""))
            .mode(SaveMode.Append)
        log.debug("saveStream finished")
        res
    }
  }

  def rowSchemaToSchema(rowSchema: RowSchema): StructType = StructType(
    rowSchema.fieldDatatypes.zip(rowSchema.fieldsNames).map { case (t, n) => new StructField(n.name, t) }
  )
}
