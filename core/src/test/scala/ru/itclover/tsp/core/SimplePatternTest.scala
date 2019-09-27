package ru.itclover.tsp.core

import java.time.Instant

import cats.Id
import org.scalatest.{FlatSpec, Matchers}
import ru.itclover.tsp.core.fixtures.Common.EInt
import ru.itclover.tsp.core.fixtures.Event
import ru.itclover.tsp.core.utils.{Change, Constant, Timer}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class SimplePatternTest extends FlatSpec with Matchers {

  val p = Patterns[EInt]
  import p._

  val pattern = field(_.row)

  private def runAndCollectOutput[A](events: Seq[Event[Int]]) = {
    val collect = new ArrayBuffer[IdxValue[Int]]()
    StateMachine[Id].run(pattern, events, pattern.initialState(), (x: IdxValue[Int]) => collect += x)
    collect
  }

  it should "return correct results for changing values" in {

    val events = (for (time <- Timer(from = Instant.now());
                       row  <- Change(from = 0.0, to = 100.0, 100.seconds).after(Constant(1)))
      yield Event[Int](time.toEpochMilli, row.toInt, 0)).run(seconds = 100)

    val out = runAndCollectOutput(events)
    out.size shouldBe (100)
  }

  it should "collect points to segments for same values" in {

    val events = (for (time <- Timer(from = Instant.now());
                       row  <- Constant(0).timed(10.seconds).after(Constant(1)))
      yield Event[Int](time.toEpochMilli, row.toInt, 0)).run(seconds = 100)

    val out = runAndCollectOutput(events)
    out.size shouldBe (2)
    out(0) shouldBe (IdxValue(0, 10, Result.succ(0)))
  }

}
