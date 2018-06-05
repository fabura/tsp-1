package ru.itclover.streammachine.core

import org.scalatest.{Matchers, WordSpec}
import ru.itclover.streammachine.phases.CombiningPhases.{And, EitherParser, Or}
import ru.itclover.streammachine.core.PhaseResult.{Failure, Stay, Success}

class OrParserTest extends WordSpec with Matchers {

  // TODO(1)
  /*"OrParser" should {
    "Failure if both side is failure" in {
      val orPhase = EitherParser(alwaysFailure, alwaysFailure)

      val (result, _) = orPhase(probe, orPhase.initialState)

      result shouldBe a[Failure]
    }

    "Success if any side is success" in {
      def orPhaseLeft(right: PhaseParser[TestEvent, Unit, Int]) = EitherParser(alwaysSuccess, right)

      def orPhaseRight(left: PhaseParser[TestEvent, Unit, Int]) = EitherParser(left, alwaysSuccess)

      val results = for (secondResult <- Set(alwaysFailure, alwaysSuccess, alwaysStay);
                         parserFunc <- Set(orPhaseLeft _, orPhaseRight _)
      ) yield {
        val phase: PhaseParser[TestEvent, Unit And Unit, Int Or Int] = parserFunc(secondResult)
        val (result, _) = phase(probe, phase.initialState)
        result
      }

      results foreach (_ shouldBe a[Success[_]])
    }

    "Stay if (Stay or Stay) | (Failure or Stay) | (Stay or Failure)" in {
      Seq(
        EitherParser(alwaysStay, alwaysStay),
        EitherParser(alwaysFailure, alwaysStay),
        EitherParser(alwaysStay, alwaysFailure)
      ).map {
        phase =>
          val (result, _) = phase(probe, phase.initialState)
          result
      }.foreach(_ shouldBe Stay)
    }
  }*/

}
