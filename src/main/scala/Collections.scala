package provingGround

import scala.annotation._
import scala.util._

import scala.language.implicitConversions

object Collections{
    val runTime = java.lang.Runtime.getRuntime()
   
    def  freeMem = runTime.freeMemory()
   
    def maxMem = runTime.maxMemory
  
    def totalMem = runTime.totalMemory
    
    implicit val ZeroReal : Double = 0
    
    implicit def ZeroPair[A, B](za: A, zb: B): (A, B) = (za, zb)
     
    trait InfSeq[T]{
      val head: T
      val tail: InfSeq[T]
        
      @tailrec final def apply(n: Int): T = {require(n>=0); if (n==0) head else tail.apply(n-1)}
        
      @tailrec final def drop(n: Int): InfSeq[T] = if (n<1) this else tail.drop(n-1)
        
      def take(n: Int) = for (i<-0 to (n-1)) yield (apply(i))
        
      def #::(head: T) :InfSeq[T] = InfSeq.cons(head, this)
      
      def map[S](f: T =>S): InfSeq[S] = InfSeq.cons(f(head), tail.map(f))
      
      /*
       * This is a diagonal, not a true flatMap, so works only in some cases: approximations, independent random variables.
       */
      def flatMap[S](f: T => InfSeq[S]): InfSeq[S] = InfSeq.diagonal(map(f))
        
    }
    
    object InfSeq{
        class fromFunction[T](seq: => (Int => T)) extends InfSeq[T]{
        	lazy val head = seq(0)
            lazy val tail = new fromFunction((n: Int) => seq(n+1))
        }        
        
        def cons[T](head: T, tail: => InfSeq[T]) = {
            def seq(n: Int) = if (n<1) head else tail(n-1)
            new fromFunction(seq)
        }
        
        def apply[T](seq: => (Int => T)) = new fromFunction(seq)
        
        def diagonal[T](seq: InfSeq[InfSeq[T]]): InfSeq[T] = cons(seq.head.head, diagonal(seq.tail.tail))
    }
    
    trait ApproxSeq[T] extends InfSeq[T]{
      val stable: Boolean
      
      val tail: ApproxSeq[T]
      
      override def #::(head: T) = ApproxSeq.cons(head, this)
      
      override def map[S](f: T =>S): ApproxSeq[S] = ApproxSeq.cons(f(head), tail.map(f))
    }

    /*
     * Does this change on disc?
     */
    
    object ApproxSeq{
      case class Const[T](t: T) extends ApproxSeq[T]{
        lazy val head = t
        val stable = true
        
        lazy val tail = this
      }
      
      class Prepend[T](h:  => T, t: => ApproxSeq[T], val stable: Boolean = false) extends ApproxSeq[T]{
        lazy val head = h
        lazy val tail = t
        
      }
      
      def cons[T](h: => T, t: => ApproxSeq[T], stable: Boolean = false) = new Prepend(h, t, stable)
      
      case class Dyn[T](init: T, dyn: T=> T, halt: T => Boolean) extends ApproxSeq[T]{
        lazy val head = init
        
        lazy val stable = halt(init)
        
        lazy val tail : ApproxSeq[T] = if (stable) Const(init) else Dyn(dyn(init), dyn, halt)
      }
      
      def diagonal[T](seq: ApproxSeq[ApproxSeq[T]]): ApproxSeq[T] = cons(seq.head.head, diagonal(seq.tail.tail), seq.stable && seq.head.stable)
    }
    

    trait ProbabilityDistribution[T]{self => 
      def next: T 
      
      def iid : InfSeq[T] = InfSeq((_ : Int) => next)
      
      def map[S](f: T => S) = ProbabilityDistribution(f(next))
      
      def flatMap[S](f: T => ProbabilityDistribution[S]) = ProbabilityDistribution(f(next).next)
    }
    
    object ProbabilityDistribution{
      def apply[T](nxt: => T) = new ProbabilityDistribution[T]{
        def next = nxt
      }
    }
    
    class Uniform extends ProbabilityDistribution[Double]{
      lazy val rand = new Random
      
      def next = rand.nextDouble
    }
    
    case class Weighted[T](elem: T, weight: Double){
      def scale(s: Double) = Weighted(elem, weight * s)
    }
    
    object Weighted{
      @tailrec final def pick[T](dist: Seq[Weighted[T]], t: Double): T = if (t - dist.head.weight <0) dist.head.elem 
    		  	else pick(dist.tail, t- dist.head.weight) 
      def sumWeigths[T](seq: Seq[Weighted[T]]) = seq.map(_.weight).sum  	
      
      private def gather[T](seq: Seq[Weighted[T]]) = Weighted(seq.head.elem, Weighted.sumWeigths(seq))
  
      def flatten[T](seq: Seq[Weighted[T]]) = seq groupBy (_.elem) map (_._2) map (gather(_))
      
      def combine[T](seqs: Seq[Weighted[T]]*) = flatten(seqs.flatten)
    }
    
    case class FiniteDistribution[T](pmf: Seq[Weighted[T]]) extends ProbabilityDistribution[T] with LabelledArray[T, Double]{    
      lazy val support = (pmf filter (_.weight > 0) map (_.elem)).toSet 
        
      lazy val rand = new Random
      
      def next = Weighted.pick(pmf, rand.nextDouble)
      
      def get(label: T) = pmf find (_.elem == label) map (_.weight)
      
      def getsum(label : T) = (0.0 /: (pmf filter (_.elem == label) map (_.weight)))(_ + _)
      
      def apply(label: T) = getsum(label)
      
      def flatten = {
        val newpmf = support map ((l) => Weighted(l, getsum(l)))
        FiniteDistribution(newpmf.toSeq)
      }
    
      private def posmf = pmf filter (_.weight > 0)
      
      private def postotal = ((posmf map (_.weight)) :\ 0.0)(_ + _) ensuring (_ > 0)
      
      def normalized = new FiniteDistribution(posmf map (_.scale(1.0/postotal)))
      
      def *(sc: Double) = new FiniteDistribution(pmf map (_.scale(sc)))
      
      def +(elem: T , prob : Double) = ++(FiniteDistribution(Seq(Weighted(elem, prob))))
      
      def ++(that: FiniteDistribution[T]) = {
        val combined = (for (k <- support.toSet union that.support.toSet) yield Weighted(k, apply(k) + that(k))).toSeq
        new FiniteDistribution(combined)   
      }
      
      override def map[S](f: T => S) = {
        val newpmf = for (Weighted(elem, wt) <- pmf) yield Weighted(f(elem), wt) 
        FiniteDistribution(newpmf).flatten
      }
    }
    
    case class LinearStructure[A](sum: (A, A) => A, mult : (Double, A) => A){
      def diff(frm: A, remove: A) = sum(frm, mult(-1.0, remove))
    }
    
    implicit val RealsAsLinearStructure = LinearStructure[Double]((_+_), (_*_))
    
    implicit def VectorPairs[A, B](implicit lsa: LinearStructure[A], lsb: LinearStructure[B]): LinearStructure[(A, B)] = {
       def sumpair(fst: (A, B), scnd: (A, B)) =(lsa.sum(fst._1, scnd._1), lsb.sum(fst._2, scnd._2)) 
       
       def multpair(sc: Double, vect: (A, B)) = (lsa.mult(sc, vect._1), lsb.mult(sc, vect._2))
       
       LinearStructure(sumpair, multpair)
    }

    implicit def FiniteDistVec[T] = LinearStructure[FiniteDistribution[T]](_++_, (w, d) => d * w)
    
    
    trait LabelledArray[L,T] extends Traversable[T]{
      val support: Traversable[L]
      
      def foreach[U](f: T => U): Unit = support.map(get(_).get).foreach(f)
      
      def get(label: L): Option[T]
        
      def apply(label: L)(implicit zero: T)  = get(label).getOrElse(zero)
      
      def incl(label: L)(arg: T) = ArrayMap(Map(label -> arg))
      
      def proj(label : L)(arr: ArrayMap[L, T])(implicit zero: T) = arr(label)(zero)
      
      def inclusion(label: L)(implicit zero: T) = {
        require (!((support find (_ == label)).isEmpty))
        
        LearningSystem.DiffbleFunction[T, ArrayMap[L, T]](incl(label))((_ : T) => proj(label))
      }
    }
    
    
    
    case class ArrayMap[L, T](coords: Map[L, T], supp: Option[Traversable[L]] = None) extends LabelledArray[L, T]{
      lazy val support = supp getOrElse (coords.keys)  
        
      def get(label: L) = coords.get(label)
      
      def map[S](f: T => S) = {
        val newmap = (for ((k, t) <- coords) yield (k -> f(t))).toMap
        ArrayMap(newmap, supp)
      }
      
      def total(implicit zero: T, ls: LinearStructure[T]) = coords.values.foldLeft(zero)(ls.sum(_,_))
      
      def ++(that: ArrayMap[L, T])(implicit zero: T, ls: LinearStructure[T]) ={
        val unionmap = (for (k <- coords.keySet.union(that.coords.keySet)) yield (k -> ls.sum(this(k), that(k)))).toMap
        
        ArrayMap(unionmap, supp)
      }
    }
    
    implicit def ZeroMap[L, T] : ArrayMap[L, T] = ArrayMap(Map.empty: Map[L, T])
    
    implicit def VectorArray[L, T](implicit zero: T, ls: LinearStructure[T]): LinearStructure[ArrayMap[L, T]] = {
      def mult(sc: Double, arr: ArrayMap[L, T]) = arr map ((t: T) => ls.mult(sc,t))
      
      LinearStructure[ArrayMap[L, T]](_++_, mult)
    }
    

    
    
    implicit class Shift[B](shift: (B, B, Double) => B){
      def apply(base: B, tang: B, sc: Double) = shift(base, tang, sc)
    }
    
    implicit def shiftFromVS[V](implicit ls: LinearStructure[V]) = Shift((base: V, tang : V, e: Double) => ls.diff(base, ls.mult(e,tang)))
    
    def update[B](init: B, tangent: B, epsilon: Double)(implicit s: Shift[B]) = s(init, tangent, epsilon)
    


    case class MultiSet[A](wts: Map[A, Int]) extends Set[A]{
      def weight(elem: A) = wts.getOrElse(elem, 0)
      
      lazy val support = wts.keySet
     
      // May not be needed - comes from iterator
 //     override def foreach[U](f: A => U): Unit = wts.keys.foreach((k) =>
 //       	(1 to wts(k)).foreach((j) =>
 //       	  f(k)))

      def ++(that: MultiSet[A]) = {
        val newmap = ((wts.keySet union that.wts.keySet) map ((k : A) => (k, weight(k) + that.weight(k)))).toMap
        MultiSet(newmap)
      }
      
      def +(elem: A) = {
        MultiSet(wts updated (elem, weight(elem)+ 1)) 
      }
      
      def -(elem: A) ={
        MultiSet(wts updated (elem, math.min(weight(elem)-1,0)))
      }
      
      def contains(elem: A) = weight(elem)>0
      
      def asSeq = (wts map ( kn =>
        (1 to kn._2) map ( _ => kn._1))).flatten
        
      def iterator = asSeq.iterator
    }
    
    object MultiSet{
      def empty[A] = MultiSet[A](Map.empty)
    }
    
    /* 
     * Should move this elsewhere
     */
    
    @tailrec def IterateDyn[A](init: A, step: A => A, n: Int) : A = if (n < 1) init else IterateDyn(step(init), step, n-1)
   
}

