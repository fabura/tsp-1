package ru.itclover.tsp.transformers

import java.sql.DriverManager
import java.util
import cats.data.EitherT
import collection.JavaConverters._
import org.apache.flink.api.common.io.RichInputFormat
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.core.io.InputSplit
import collection.JavaConversions._
import cats.syntax.either._
import com.typesafe.scalalogging.Logger
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import ru.itclover.tsp.io.input.{InputConf, JDBCInputConf, NarrowDataUnfolding}
import ru.itclover.tsp.utils.CollectionsOps.TryOps
import org.apache.flink.streaming.api.scala._
import org.apache.flink.types.Row
import org.influxdb.dto.QueryResult
import ru.itclover.tsp.utils.UtilityTypes.ThrowableOr
import ru.itclover.tsp.JDBCInputFormatProps
import ru.itclover.tsp.core.Time
import ru.itclover.tsp.io.{Decoder, Extractor, TimeExtractor}
import ru.itclover.tsp.io.Decoder.AnyDecoder
import ru.itclover.tsp.utils.ErrorsADT._
import ru.itclover.tsp.utils.RowOps.{RowIdxExtractor, RowOps}
import scala.collection.mutable
import scala.util.Try

sealed trait StreamSource[Event, EKey, EItem] extends Product with Serializable {
  def createStream: DataStream[Event]

  def conf: InputConf[Event]

  def emptyEvent: Event

  def isEventTerminal(e: Event): Boolean

  def fieldsClasses: Seq[(Symbol, Class[_])]

  def fieldToEKey(fieldId: String): EKey
  
  def partitioner(event: Event): Any

  implicit def timeExtractor: TimeExtractor[Event]
  
  implicit def extractor: Extractor[Event, EKey, EItem]
}

object StreamSource {
  def create[Event, EKey, EItem](inputConf: InputConf[Event])(
    implicit env: StreamExecutionEnvironment
  ) = inputConf match {
    case conf: JDBCInputConf => JdbcSource.create(conf)
    // case conf: InfluxDBInputConf => InfluxDBSource.create(conf)
  }
  
  def findNullField(allFields: Seq[Symbol], excludedFields: Seq[Symbol]) = {
    allFields.find { field => !excludedFields.contains(field) }
  }
}

object JdbcSource {

  def create(conf: JDBCInputConf)(implicit strEnv: StreamExecutionEnvironment): Either[ConfigErr, JdbcSource] =
    for {
      types <- fetchFieldsTypesInfo(conf.driverName, conf.jdbcUrl, conf.query)
        .leftMap[ConfigErr](e => SourceUnavailable(Option(e.getMessage).getOrElse(e.toString)))
      source <- StreamSource.findNullField(types.map(_._1), conf.datetimeField +: conf.partitionFields) match {
        case Some(nullField) => JdbcSource(conf, types, nullField).asRight
        case None => InvalidRequest("Source should contain at least one non partition and datatime field.").asLeft
      }
  } yield source

  def fetchFieldsTypesInfo(driverName: String, jdbcUrl: String, query: String): ThrowableOr[Seq[(Symbol, Class[_])]] = {
    val classTry: Try[Class[_]] = Try(Class.forName(driverName))

    val connectionTry = Try(DriverManager.getConnection(jdbcUrl))
    (for {
      _          <- classTry
      connection <- connectionTry
      resultSet  <- Try(connection.createStatement().executeQuery(s"SELECT * FROM (${query}) as mainQ LIMIT 1"))
      metaData   <- Try(resultSet.getMetaData)
    } yield {
      (1 to metaData.getColumnCount) map { i: Int =>
        val className = metaData.getColumnClassName(i)
        (Symbol(metaData.getColumnName(i)), Class.forName(className))
      }
    }).toEither
  }
}


// .. todo rm nullField, only emptyEvent (after debug and tests)
case class JdbcSource(conf: JDBCInputConf, fieldsClasses: Seq[(Symbol, Class[_])], nullField: Symbol)(
  implicit streamEnv: StreamExecutionEnvironment
) extends StreamSource[Row, Int, Any] {
  
  val stageName = "JDBC input processing stage"
  val log = Logger[JdbcSource]
  val fieldsIdx = fieldsClasses.map(_._1).zipWithIndex
  val fieldsIdxMap = fieldsIdx.toMap
  
  require(fieldsIdxMap.get(conf.datetimeField).isDefined, "Cannot find datetime field, index overflow.")
  require(fieldsIdxMap(conf.datetimeField) < fieldsIdxMap.size, "Cannot find datetime field, index overflow.")
  private val badPartitions = conf.partitionFields.map(fieldsIdxMap.get)
    .find(idx => idx.isEmpty || idx.get >= fieldsIdxMap.size).flatten
    .map(p => fieldsClasses(p)._1)
  require(badPartitions.isEmpty, s"Cannot find partition field (${badPartitions.get}), index overflow.")
  
  val timeIndex = fieldsIdxMap(conf.datetimeField)
  val fieldsTypesInfo: Array[TypeInformation[_]] = fieldsClasses.map(c => TypeInformation.of(c._2)).toArray
  val rowTypesInfo = new RowTypeInfo(fieldsTypesInfo, fieldsClasses.map(_._1.toString.tail).toArray)
  val emptyEvent = {
    val r = new Row(fieldsIdx.length)
    fieldsIdx.foreach { case (_, ind) => r.setField(ind, 0) }
    r
  }

  override def createStream = {
    val stream = streamEnv
      .createInput(inputFormat)
      .name(stageName)
    conf.parallelism match {
      case Some(p) => stream.setParallelism(p)
      case None    => stream
    }
  }
  
  override def isEventTerminal(event: Row) = {
    val nullInd = fieldsIdxMap(nullField)
    event.getArity > nullInd && event.getField(nullInd) == null
  }
  
  def nullEvent = {
    val r = new Row(fieldsIdxMap.size)
    fieldsIdxMap.foreach { case (_, ind) => r.setField(ind, 0) }
    r
  }
  
  val inputFormat: RichInputFormat[Row, InputSplit] =
    JDBCInputFormatProps
      .buildJDBCInputFormat()
      .setDrivername(conf.driverName)
      .setDBUrl(conf.jdbcUrl)
      .setUsername(conf.userName.getOrElse(""))
      .setPassword(conf.password.getOrElse(""))
      .setQuery(conf.query)
      .setRowTypeInfo(rowTypesInfo)
      .finish()


  val tsMultiplier = conf.timestampMultiplier.getOrElse {
    log.info("timestampMultiplier in JDBC source conf is not provided, use default = 1000.0")
    1000.0
  }
  override def timeExtractor = new TimeExtractor[Row] {
    override def apply(e: Row) = {
      val millis = e.getField(timeIndex) match {
        case d: java.lang.Double => (d * tsMultiplier).toLong
        case f: java.lang.Float  => (f * tsMultiplier).toLong
        case n: java.lang.Number => (n.doubleValue() * tsMultiplier).toLong
        // .. todo typeOfTime parameter in conf
        case error => throw new RuntimeException(s"Cannot parse time: `$error`, should be number.")
      }
      Time(toMillis=millis)
    }
  }

  override def extractor = RowIdxExtractor()
}


/*
case class InfluxDBSource(conf: InfluxDBInputConf)(implicit streamEnv: StreamExecutionEnvironment)
    extends StreamSource[Row] {
  val dummyResult: Class[QueryResult.Result] = new QueryResult.Result().getClass.asInstanceOf[Class[QueryResult.Result]]
  val queryResultTypeInfo: TypeInformation[QueryResult.Result] = TypeInformation.of(dummyResult)
  val stageName = "InfluxDB input processing stage"

  override def createStream = for {
    fieldsTypesInfo <- conf.fieldsTypesInfo
    fieldsIdxMap    <- conf.errOrFieldsIdxMap
  } yield {
    streamEnv
      .createInput(conf.getInputFormat(fieldsTypesInfo.toArray))(queryResultTypeInfo)
      .flatMap(queryResult => {
        // extract Flink.rows form series of points
        if (queryResult == null || queryResult.getSeries == null) {
          mutable.Buffer[Row]()
        } else
          for {
            series   <- queryResult.getSeries
            valueSet <- series.getValues
          } yield {
            val tags = if (series.getTags != null) series.getTags else new util.HashMap[String, String]()
            val row = new Row(tags.size() + valueSet.size())
            val fieldsAndValues = tags ++ series.getColumns.toSeq.zip(valueSet)
            fieldsAndValues.foreach {
              case (field, value) => row.setField(fieldsIdxMap(Symbol(field)), value)
            }
            row
          }
      })
      .name(stageName)
  }

  override def emptyEvent = nullEvent match {
    case Right(e) => e
    case Left(ex) => throw ex
  }

  def nullEvent = for {
    fieldsIdxMap <- conf.errOrFieldsIdxMap
  } yield {
    val r = new Row(fieldsIdxMap.size)
    fieldsIdxMap.foreach { case (_, ind) => r.setField(ind, 0) }
    r
  }

  override def getTerminalCheck = for {
    fieldsIdxMap <- conf.errOrFieldsIdxMap
    nullField    <- findNullField(fieldsIdxMap.keys.toSeq, conf.datetimeField +: conf.partitionFields)
    nullInd = fieldsIdxMap(nullField)
  } yield (event: Row) => event.getArity > nullInd && event.getField(nullInd) == null
}
*/
