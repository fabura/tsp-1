package ru.itclover.streammachine

import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import org.apache.flink.types.Row
import ru.itclover.streammachine.core.PhaseParser
import scala.reflect.ClassTag


class Eval(classLoader: ClassLoader) extends com.twitter.util.Eval {
  override lazy val impliedClassPath: List[String] = {
    def getClassPath(cl: ClassLoader, acc: List[List[String]] = List.empty): List[List[String]] = {
      val cp = cl match {
        case urlClassLoader: URLClassLoader => urlClassLoader.getURLs.filter(_.getProtocol == "file").
          map(u => new File(u.toURI).getPath).toList
        case _ => Nil
      }
      cl.getParent match {
        case null => (cp :: acc).reverse
        case parent => getClassPath(parent, cp :: acc)
      }
    }

    val classPath = getClassPath(classLoader)
    val currentClassPath = classPath.head

    // if there's just one thing in the classpath, and it's a jar, assume an executable jar.
    currentClassPath ::: (if (currentClassPath.size == 1 && currentClassPath(0).endsWith(".jar")) {
      val jarFile = currentClassPath(0)
      val relativeRoot = new File(jarFile).getParentFile()
      val nestedClassPath = new JarFile(jarFile).getManifest.getMainAttributes.getValue("Class-Path")
      if (nestedClassPath eq null) {
        Nil
      } else {
        nestedClassPath.split(" ").map { f => new File(relativeRoot, f).getAbsolutePath }.toList
      }
    } else {
      Nil
    }) ::: classPath.tail.flatten
  }
}

object EvalUtils {

  def composePhaseCodeUsingRowExtractors(phaseCode: String, timestampFieldIndex: Int, fieldsIndexesMap: Map[Symbol, Int]) = {
      s"""
         |import ru.itclover.streammachine.core.Aggregators._
         |import ru.itclover.streammachine.core.AggregatingPhaseParser._
         |import ru.itclover.streammachine.core.NumericPhaseParser._
         |import ru.itclover.streammachine.core.Time._
         |import ru.itclover.streammachine.core.PhaseParser
         |import Predef.{any2stringadd => _, _}
         |import ru.itclover.streammachine.phases.Phases._
         |import org.apache.flink.types.Row
         |
        |implicit val symbolNumberExtractorRow: SymbolNumberExtractor[Row] = new SymbolNumberExtractor[Row] {
         |  val fieldsIndexesMap: Map[Symbol, Int] = ${fieldsIndexesMap.toString}
         |
        |  override def extract(event: Row, symbol: Symbol) = {
         |    event.getField(fieldsIndexesMap(symbol)).asInstanceOf[Double]
         |  }
         |}
         |implicit val timeExtractor: TimeExtractor[Row] = new TimeExtractor[Row] {
         |  override def apply(v1: Row) = {
         |    v1.getField($timestampFieldIndex).asInstanceOf[java.sql.Timestamp]
         |  }
         |}
         |
        |val phase = $phaseCode
         |phase
      """.stripMargin
  }

}
