import provingground._
import FiniteDistributionLearner._
import Collections._ ; import FiniteDistribution._; import provingground._
import org.scalatest.FlatSpec
import FiniteDistribution._

import scala.util._

/**
 * @author gadgil
 */
class DiffFnFromMoveSpec extends FlatSpec{
  val double =  MoveFn ((x: Double) => Some(2 * x))

  val square =  MoveFn ((x: Double) => Some(x * x))

  def sqrt(x: Double) = if (x >= 0) Some(math.sqrt(x)) else None

  val at12 = unif(1.0, 2.0)

  behavior of "the differentiable function induced by a move"

  it should "map uniform distributions forward correctly" in {
    assert(double.func(at12) == unif(2.0, 4.0))
  }

  it should "map non-uniform distributions correctly" in {

    val d = FiniteDistribution((1 to 10).toSet map ((i: Int) => Weighted(i.toDouble, i.toDouble))).flatten

    val dd = FiniteDistribution((1 to 10).toSet map ((i : Int) => Weighted(i.toDouble * 2.0, i.toDouble))).flatten

    for (i <-1 to 20) yield assert(double.func(d) == dd)
  }

  it should "fold together elements and flatten" in {

    assert(square.func(unif(1.0, 2.0, -1.0, -2.0)) == unif(1.0, 4.0))
  }

  it should "ignore elements that are not acted on" in {


    val fn = MoveFn(sqrt)

    assert(fn.func(unif(1.0, 4.0, -1.0, -3.0)) == FiniteDistribution(weights(1.0 -> 0.25, 2.0 -> 0.25)))
  }

  it should "have expected gradient" in {
    val p = FiniteDistribution.unif(1.0, 3.0, 4.0)

    val w = FiniteDistribution(weights(2.0 -> 0.5, 6.0 -> 0.25, 10.0 -> 0.25))

    assert(double.grad(p)(w)(1.0) == 0.5)

    assert(double.grad(p)(w)(3.0) == 0.25)

//    assert(fn.grad(p)(w) == FiniteDistribution.fromWts(1 -> 0.5, 3 -> 0.25))
  }
}