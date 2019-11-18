package ru.itclover.tsp.services

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardOpenOption}
import java.time.LocalDateTime

object FileService {

  /**
  * Method for converting input bytes to file
    * @param input bytes for convert
    * @return file with input bytes
    */
  def convertBytes(input: Array[Byte]): File = {

    val currentTime = LocalDateTime.now().toString
    val path = Files.createTempFile(s"temp_($currentTime)", ".temp")

    val options = Set(
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    ).toSeq

    val fileChannel = FileChannel.open(path, options: _*)
    val buffer = ByteBuffer.wrap(input)

    fileChannel.write(buffer)
    fileChannel.close()

    path.toFile

  }

}
