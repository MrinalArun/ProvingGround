package provingground

import HoTT._

import ScalaRep._

import spire.algebra._
import spire.math._
import spire.implicits._
import scala.util._
import scala.language.implicitConversions

/**
 * @author gadgil
 * 
 * Symbolic algebra for numeric types, with Sigma's and Pi's
 * Requivers a commutative rig, but Pi's and Sigma's are written to allow rings and fields
 * 
 * Using type Rig, not CRig as CRing does not extend CRig
 */
class NumericTyp[A : Rig] {self =>
  val rig = implicitly[Rig[A]]
  
  import rig._
  
//  trait LocalTerm extends Term with Subs[LocalTerm]
  
  type LocalTerm = RepTerm[A]
  
  type Op = Func[LocalTerm, Func[LocalTerm, LocalTerm]]
  
  object LocalTyp extends ScalaTyp[A]{
    type Obj = LocalTerm
/*
    val typ = Universe(0)

    def symbObj(name: AnySym): LocalTerm = LocalSymbObj(name, this)

    def newobj = this

    def subs(x: Term, y: Term) = (x, y) match {
      case (xt: Typ[_], yt: Typ[_]) if (xt == this) => yt.asInstanceOf[Typ[LocalTerm]]
      case _ => this
    }
    
    implicit val rep : ScalaRep[LocalTerm, A] = SimpleRep(this)*/
  }
  
  case class LocalSymbObj[+U <: LocalTerm](name: AnySym, typ: Typ[U]) extends LocalTerm with Symbolic {
    override def toString = name.toString + " : (" + typ.toString + ")"

    def newobj = LocalSymbObj(new InnerSym(this), typ)

    def subs(x: Term, y: Term) = if (x == this) y.asInstanceOf[LocalTerm] else {
      def symbobj(sym: AnySym) = typ.replace(x, y).symbObj(sym)
      symSubs(symbobj)(x, y)(name)
    }
  }
  


  
  object Literal extends ScalaSym[LocalTerm, A](LocalTyp)

  object Comb{
    def unapply(term: Term): Option[(Op, LocalTerm, LocalTerm)] = term match {
      case FormalAppln(FormalAppln(op, x), y) => 
        Try((op.asInstanceOf[Op], x. asInstanceOf[LocalTerm], y.asInstanceOf[LocalTerm])).toOption
      case _ => None
    }
    
    def apply(op: Func[LocalTerm, Func[LocalTerm, LocalTerm]], x: LocalTerm, y: LocalTerm) =
      FormalAppln(FormalAppln(op, x), y)
  }
  
  case class SigmaTerm(elems: Set[LocalTerm]) extends LocalTerm{
    val typ = LocalTyp
    
    def subs(x: Term, y: Term) = (elems map (_.subs(x, y))).reduce((a: LocalTerm, b: LocalTerm) => sum(a)(b))
    
    def newobj = LocalTyp.obj
    
    val head = elems.head
    
    val tail = 
      if (elems.size == 2) elems.last else SigmaTerm(elems.tail)
    
    /**
     * add a term, simplifies only in cases that remain in definition of sum
     */
    def +:(x: LocalTerm) : LocalTerm = {
        val l = LitProd.fold(x)(elems.toList)
        l match {
          case List() => Literal(zero)
          case s :: List() => s
          case _ => SigmaTerm(l.toSet)
        }
      }
    
  }
  
  object LitProd{
    def apply(a: A, x: LocalTerm) = prod(Literal(a))(x)
    
    def unapply(x: LocalTerm) : Option[(A, LocalTerm)] = x match {
      case Comb(mult, Literal(a), y) if mult == prod =>
        Some((a, y))
      case _ => None
    }
    
    def add(x: LocalTerm, y: LocalTerm) = (x, y) match {
      case (LitProd(a, u), LitProd(b, v)) if u == v =>
        Some(LitProd(a + b, u))
      case _ => None
    }
    
    def fold(x: LocalTerm)(l: List[LocalTerm]) : List[LocalTerm] = (x, l) match {
      case (_, List()) => List(x)
      case (_, head:: tail) =>
        (add(x, head) map ((u: LocalTerm) => fold(u)(tail))).
        getOrElse(head :: fold(x)(tail))
    }
  }
  
  
  case class PiTerm(elems: Map[LocalTerm, Int]) extends LocalTerm{
    val typ = LocalTyp
    
    def subs(x: Term, y: Term) = 
      (elems map ((an) => power(an._1, an._2))).reduce((a: LocalTerm, b: LocalTerm) => prod(a)(b))
    
    def newobj = LocalTyp.obj
    
    val head = power(elems.head._1, elems.head._2) 
    
    val tail = if (elems.tail.isEmpty) Literal(one) else PiTerm(elems.tail)
    
    val isComposite = (elems.size > 1)
    
    def *:(y: LocalTerm) = {
      import Reciprocal.{base, expo}
      
      val ind = (elems.get(base(y)) map (_ + expo(y))) getOrElse (expo(y))
      PiTerm.purge(elems + (base(y) -> ind))
    }
  }
  
  object PiTerm{
    def purge(elems: Map[LocalTerm, Int]) = {
      val nontriv = elems filter ({case (x, p) => p > 0 })
      if (!nontriv.isEmpty) PiTerm(nontriv) else Literal(one)
    }
  }
  
  object Reciprocal{
    def apply(a: LocalTerm) = PiTerm(Map(a -> -1))
    
    def unapply(a : Term) = a match {
      case PiTerm(elems) => {
        elems.toList match {
          case xp :: List() if xp._2 == -1 => Some(xp._1)
          case _ => None
        } 
      }
      case _ => None
    }
    
  def base(y: LocalTerm) = y match {
        case Reciprocal(a) => a
        case a => a
      }
  
  def expo(y: LocalTerm) = y match {
        case Reciprocal(a) => -1
        case _ => 1
      }
    
  }
  

  
  import LocalTyp.rep
  
  object sum extends Func[LocalTerm, Func[LocalTerm, LocalTerm]]{
    val dom = LocalTyp
    
    val codom = LocalTyp ->: LocalTyp
    
    val typ = dom ->: codom

    def subs(x: Term, y: Term) = this
    
    def newobj = this
    
    def act(x: LocalTerm) = x match {
      case Literal(a) => 
        if (a == zero)
        {
          val x = LocalTyp.obj
          lmbda(x)(x)
        }
        else
          ((b: A) => a + b).term
      case Comb(op, u, v) if op == sum =>
        composition(sum(u), sum(v))
      case s @ SigmaTerm(terms) => 
        composition(sum(s.head), sum(s.tail))
      case y => AddTerm(y)
    }
  }
  
  case class AddTerm(x: LocalTerm) extends Func[LocalTerm, LocalTerm]{
    val dom = LocalTyp
    
    val codom = LocalTyp
    
    val typ = LocalTyp ->: LocalTyp
    
    def subs(x: Term, y: Term) = this
    
    def newobj = this
    
    def act(y: LocalTerm) = y match{
      case Literal(a) => Comb(sum, Literal(a), x)
      case Comb(f, Literal(a), v) if f == sum => sum(Literal(a))(sum(x)(v))
      case s : SigmaTerm => x +: s
      case _ => LitProd.add(x, y) getOrElse(SigmaTerm(Set(x, y)))
    }
  }
  
  def funcSum(f: LocalTerm => LocalTerm, g: LocalTerm => LocalTerm) = {
    val x = LocalTyp.obj
    lmbda(x)(sum(f(x))(g(x)))
  }
  
  case class AdditiveMorphism[U<: LocalTerm with Subs[U]](base: Func[LocalTerm, U], op: (U, U) => U) extends Func[LocalTerm, LocalTerm]{
    val dom = LocalTyp
    
    val codom = base.codom
    
    val typ = LocalTyp ->: codom
    
    def subs(x: Term, y: Term) = AdditiveMorphism(base.subs(x, y), op)
    
    def newobj = AdditiveMorphism(base.newobj, op)
 
  
    def act(x: LocalTerm) = x match {
      case Comb(f, u, v) if f == sum => op(base(u), base(v))
      case SigmaTerm(elems) => (elems map ((u) => base(u))).reduce(op)
      case _ => base(x)
    }
  }
  
  @annotation.tailrec 
  final def posPower(x: LocalTerm, n: Int, accum: LocalTerm = Literal(one)): LocalTerm = {
    require(n >=0, s"attempted to compute negative power $n of $x recursively")
    if (n ==0) Literal(one)
    else 
      if (n == 1) x
      else posPower(x, n-1, prod(accum)(x))
  }
  
  // override this in fields
  def power(x: LocalTerm, n: Int) = posPower(x, n)
  
  object prod extends Func[LocalTerm, Func[LocalTerm, LocalTerm]]{
    val dom = LocalTyp
    
    val codom = LocalTyp ->: LocalTyp
    
    val typ = dom ->: codom

    def subs(x: Term, y: Term) = this
    
    def newobj = this
    
    def act(x: LocalTerm) = x match {
      case Literal(a) => 
        if (a == one)
        {
          val x = LocalTyp.obj
          lmbda(x)(x)
        }
        else
          AdditiveMorphism(((b: A) => a * b).term, (x: LocalTerm, y: LocalTerm) => sum(x)(y))
      case Comb(op, u, v) if op == prod =>
        composition(prod(u), prod(v))
      case Comb(op, u, v) if op == sum =>
        funcSum(prod(u), prod(v))
      case s @ SigmaTerm(terms) => 
        (terms map ((t) => prod(t))).reduce(funcSum)
      case Reciprocal(x) => multTerm(Reciprocal(x))
      case p: PiTerm =>  
        if (p.isComposite) composition(prod(p.head), prod(p.tail))
        else prod(p.head)
      case y => multTerm(y)
    }
  }
  
  
  case class multTerm(x: LocalTerm) extends Func[LocalTerm, LocalTerm]{
    val dom = LocalTyp
    
    import Reciprocal.{base, expo}
    
    val codom = LocalTyp
    
    val typ = LocalTyp ->: LocalTyp
    
    def subs(x: Term, y: Term) = this
    
    def newobj = this
    
    def act(y: LocalTerm) = y match{
      case Literal(a) => prod(Literal(a))(x)
      case Comb(f, Literal(a), v) if f == prod => prod(Literal(a))(prod(x)(v))
      case Comb(f, u, v) if f == sum => sum(prod(x)(u))(prod(x)(v))
      case SigmaTerm(elems) => (elems map ((u) => prod(x)(u))).reduce((a: LocalTerm, b: LocalTerm) => sum(a)(b))
      case p : PiTerm => x *: p
      case `x` => PiTerm(Map(base(x) -> 2 * expo(x)))
      case _ => PiTerm(Map(base(x) -> expo(x), base(y) -> expo(y)))
    }
  }
  
  implicit val crigStructure : CRig[LocalTerm] = new CRig[LocalTerm]{
    val zero = Literal(rig.zero)
    
    val one = Literal(rig.one)
    
    def plus(x: LocalTerm, y: LocalTerm) = self.sum(x)(y)
    
    def times(x: LocalTerm, y: LocalTerm) = self.prod(x)(y)
  }
  
}