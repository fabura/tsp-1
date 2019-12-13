package ru.itclover.tsp.serializers.core

import java.io.File
import java.nio.file.Files

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, Schema}
import org.apache.flink.types.Row
import ru.itclover.tsp.io.output.RowSchema
import ru.itclover.tsp.serializers.utils.SerializationUtils
import ru.itclover.tsp.services.FileService
import ru.itclover.tsp.utils.ArrowOps

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
* Serialization for Apache Arrow format
  */
class ArrowSerialization extends Serialization[Array[Byte], Row]{

  override def serialize(output: Row, rowSchema: RowSchema): Array[Byte] = {

    val tempPath = FileService.createTemporaryFile()
    val tempFile = tempPath.toFile

    val schemaFields = List(
      new Field(
        rowSchema.sourceIdField.name,
        false,
        new ArrowType.Int(32, true),
        null
      ),
      new Field(
        rowSchema.fromTsField.name,
        false,
        new ArrowType.Decimal(3, 3),
        null
      ),
      new Field(
        rowSchema.toTsField.name,
        false,
        new ArrowType.Decimal(3, 3),
        null
      ),
      new Field(
        rowSchema.appIdFieldVal._1.name,
        false,
        new ArrowType.Int(32, true),
        null
      ),
      new Field(
        rowSchema.patternIdField.name,
        false,
        new ArrowType.Utf8,
        null
      ),
      new Field(
        rowSchema.processingTsField.name,
        false,
        new ArrowType.Decimal(3, 3),
        null
      ),
      new Field(
        rowSchema.contextField.name,
        false,
        new ArrowType.Utf8,
        null
      )
    )

    val schema = new Schema(schemaFields.asJava)
    val allocator = new RootAllocator(1000000)

    val data = mutable.ListBuffer(
      mutable.Map(
        rowSchema.sourceIdField.name     -> output.getField(rowSchema.sourceIdInd).asInstanceOf[Int],
        rowSchema.fromTsField.name       -> output.getField(rowSchema.beginInd).asInstanceOf[Double],
        rowSchema.toTsField.name         -> output.getField(rowSchema.endInd).asInstanceOf[Double],
        rowSchema.appIdFieldVal._1.name  -> output.getField(rowSchema.appIdInd).asInstanceOf[Int],
        rowSchema.patternIdField.name    -> output.getField(rowSchema.patternIdInd).asInstanceOf[String],
        rowSchema.processingTsField.name -> output.getField(rowSchema.processingTimeInd).asInstanceOf[Double],
        rowSchema.contextField.name      -> output.getField(rowSchema.contextInd).asInstanceOf[String]
      )
    )

    ArrowOps.writeData((tempFile, schema, data, allocator))

    val result = Files.readAllBytes(tempPath)
    tempFile.delete()

    result

  }

  override def deserialize(input: Array[Byte], fieldsIdxMap: Map[Symbol, Int]): Row = {

    val tempFile: File = FileService.convertBytes(input)
    val schemaAndReader = ArrowOps.retrieveSchemaAndReader(tempFile, Integer.MAX_VALUE)
    val rowData = ArrowOps.retrieveData(schemaAndReader)
    tempFile.delete()

    SerializationUtils.combineRows(rowData)

  }
}