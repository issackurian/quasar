/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.std

import quasar.Predef._
import quasar.{Data, GenericFunc, LogicalPlan}, LogicalPlan._
import quasar.RenderTree.ops._
import quasar.fp.ski._
import quasar.std.StdLib._

import matryoshka._, Recursive.ops._
import org.specs2.execute._
import org.scalacheck.Arbitrary, Arbitrary._
import scalaz.{Failure => _, _}, Scalaz._
import shapeless.Nat

/** Test the typers and simplifiers defined in the std lib functions themselves.
  */
class SimplifyStdLibSpec extends StdLibSpec {
  val notHandled: Result \/ Unit = Skipped("not simplified").left

  def shortCircuit[N <: Nat](func: GenericFunc[N], args: List[Data]): Result \/ Unit = (func, args) match {
    case (relations.Between, _) => notHandled

    case _ => ().right
  }

  /** Identify constructs that are expected not to be implemented. */
  def shortCircuitLP(args: List[Data]): AlgebraM[Result \/ ?, LogicalPlan, Unit] = {
    case LogicalPlan.InvokeF(func, _) => shortCircuit(func, args)
    case _ => ().right
  }

  def check(args: List[Data], prg: List[Fix[LogicalPlan]] => Fix[LogicalPlan]): Option[Result] =
    prg((0 until args.length).toList.map(idx => LogicalPlan.Free(Symbol("arg" + idx))))
      .cataM[Result \/ ?, Unit](shortCircuitLP(args)).swap.toOption

  def run(lp: Fix[LogicalPlan], expected: Data): Result =
    ensureCorrectTypes(lp).disjunction match {
      case  \/-(Fix(LogicalPlan.ConstantF(d))) => (d must closeTo(expected)).toResult
      case  \/-(v) => Failure("not a constant", v.render.shows)
      case -\/ (err) => Failure("simplification failed", err.toString)
    }

  val runner = new StdLibTestRunner {
    def nullary(prg: Fix[LogicalPlan], expected: Data) =
      check(Nil, κ(prg)) getOrElse
        run(prg, expected)

    def unary(prg: Fix[LogicalPlan] => Fix[LogicalPlan], arg: Data, expected: Data) =
      check(List(arg), { case List(arg1) => prg(arg1) }) getOrElse
        run(prg(LogicalPlan.Constant(arg)), expected)

    def binary(prg: (Fix[LogicalPlan], Fix[LogicalPlan]) => Fix[LogicalPlan], arg1: Data, arg2: Data, expected: Data) =
      check(List(arg1, arg2), { case List(arg1, arg2) => prg(arg1, arg2) }) getOrElse
        run(prg(LogicalPlan.Constant(arg1), LogicalPlan.Constant(arg2)), expected)

    def ternary(prg: (Fix[LogicalPlan], Fix[LogicalPlan], Fix[LogicalPlan]) => Fix[LogicalPlan], arg1: Data, arg2: Data, arg3: Data, expected: Data) =
      check(List(arg1, arg2, arg3), { case List(arg1, arg2, arg3) => prg(arg1, arg2, arg3) }) getOrElse
        run(prg(LogicalPlan.Constant(arg1), LogicalPlan.Constant(arg2), LogicalPlan.Constant(arg3)), expected)

    def intDomain = arbitrary[BigInt]

    // NB: BigDecimal parsing cannot handle values that are too close to the
    // edges of its range.
    def decDomain = arbitrary[BigDecimal].filter(i => i.scale > Int.MinValue && i.scale < Int.MaxValue)

    def stringDomain = arbitrary[String]
  }

  tests(runner)
}
