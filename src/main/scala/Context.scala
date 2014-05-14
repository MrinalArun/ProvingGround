package provingGround

import provingGround.HoTT._
import scala.reflect.runtime.universe.{Try => UnivTry, Function => FunctionUniv, _}
import annotation._

object Context{
  trait DefnEquality{
    val lhs : Term
    val rhs: Term
    val freevars: List[Term]
    
    def map(f: Term => Term): DefnEquality
    
  }
  
  case class DefnEqual(lhs: Term, rhs: Term, freevars : List[Term] = List()) extends DefnEquality{
    def map(f : Term => Term) = DefnEqual(f(lhs), f(rhs), freevars map (f))
    
    def apply(substitutions : Term => Option[Term]) = DefnEqual.instantiate(substitutions)(this)
    
    def apply(substitutions : PartialFunction[Term, Term]) = DefnEqual.instantiate(substitutions.lift)(this)
  }
  
  object DefnEqual{
    def instantiate(substitutions : Term => Option[Term])(dfn : DefnEqual) : Option[DefnEquality] = {
      if (dfn.freevars.isEmpty) Some(dfn) 
      else for (headvar <- dfn.freevars.headOption; 
      value <- substitutions(headvar); 
      recval <- instantiate(substitutions)(DefnEqual(dfn.lhs.subs(headvar, value), dfn.rhs.subs(headvar, value), dfn.freevars.tail))) 
        yield recval
    }
  }
  
  case class Defn[+A](lhsname : A, typ : Typ[Term], rhs: Term, freevars: List[Term]) extends DefnEquality{    
    val lhs = typ.symbObj(lhsname)
    
    def map(f : Term => Term, F : Typ[Term]=> Typ[Term]) = Defn(lhsname, F(typ), f(rhs), freevars map (f))
    
    def map(f: Term => Term) = Defn(lhsname, f(rhs))
  }
  
  object Defn{
    def infer[A](name: A, rhs : Term) : Defn[A] = Defn(name, rhs.typ, rhs, List())
    
    def apply[A](name: A, rhs : Term) = infer(name, rhs)
  }
  
  trait Context[+A, + U <: Term , +V <: Term]{
    val typ: Typ[CtxType]
    
    type CtxType <: U
    
    def foldin : CtxType => V
    
    def foldinSym[B](name: B) = foldin(typ.symbObj(name))
    
    def symblist[A](varnames: List[A]): List[Term]
    
    def constants : Seq[Term]
    
    def defns : Seq[Defn[A]]
    
    def defnEqualities : Seq[DefnEquality]
    
    def eliminate(isVar : Term => Boolean): Term => Term
    
    val elim : Term => Term
    
    def lmbda(x : Term) = LambdaMixin(x, this)
    
    def deplmbda(x : Term) = LambdaMixin(x, this, true)
    
    def cnst(x: Term) = KappaMixin(x, this)
    
    def dfn(x: Term, y: Term, local: Boolean = true) = DefnMixin(Defn(x, y), this)
    
    def dfneql(lhs: Term, rhs: Term, simp: Boolean = false) = DefnEqualityMixin(DefnEqual(lhs, rhs), this)
    
    def globaldfn(x: Term, y: Term, local: Boolean = true) = GlobalDefnMixin(Defn(x, y), this)
    
    def simpeql(lhs: Term, rhs: Term, simp: Boolean = false) = SimpEqualityMixin(DefnEqual(lhs, rhs), this)
  }
  
  object Context{
    case class empty[U <: Term : TypeTag](typ : Typ[U]) extends Context[Any, U, U]{
    	type CtxType = U
      
    	def foldin : CtxType => U = (t) => t
    	
    	def symblist[A](varnames: List[A]) = List()
    	
    	def constants : Seq[Term] = List.empty
    
    	def defns : Seq[Defn[U]] = List.empty
    
    	def defnEqualities : Seq[DefnEquality] = List.empty
    
    	def eliminate(isVar : Term => Boolean): Term => Term = (x) => x
    	
    	val elim = (t: Term) => t
      
    }
    
    def apply[U <: Term : TypeTag](typ: Typ[U]) = empty[U](typ)
  }
  

  
  
  case class LambdaMixin[+A, +U<: Term, +V <: Term](variable: Term, tail: Context[A, U, V], dep: Boolean = false) extends Context[A, FuncTerm[Term,U], V]{
    type CtxType = FuncTerm[Term, tail.CtxType]
    
    def foldin : CtxType => V = (f) => tail.foldin(f(variable))
    
    def symblist[A](varnames: List[A]) = typ.symbObj(varnames.head) :: tail.symblist(varnames.tail)
    
    def constants = variable +: tail.constants
    
    def eliminator(y : Term) = Lambda(variable, y)
    
    def defns : Seq[Defn[A]] = tail.defns map ((dfn) => dfn.map(optlambda(variable)))
    
    def defnEqualities : Seq[DefnEquality] = tail.defnEqualities map ((dfn) => dfn.map(optlambda(variable)))
    
    def eliminate(isVar : Term => Boolean): Term => Term = (y) => 
      if (isVar(variable)) Lambda(x, tail.eliminate(isVar)(y))
      else tail.eliminate(isVar)(y)
    
      
    val elim = Lambda(variable, tail.elim(y))
    
    // If tail.typ is a function of variable, we get a Pi-type instead.
    val typ = if (dep) {
      val fibre = (t : Term) => tail.typ subs (x, t)
	    
	    val family = typFamilyDefn[Term, tail.CtxType](variable.typ, MiniVerse(tail.typ), fibre)
	    PiTyp(family)
    }
    else FuncTyp(variable.typ, tail.typ)
  }
  
  case class KappaMixin[+A, +U<: Term, +V <: Term](const : Term, tail: Context[A, U, V]) extends Context[A, U, V]{
    type CtxType = tail.CtxType
    
    def foldin : CtxType => V = tail.foldin
    
    def symblist[A](varnames: List[A]) = tail.symblist(varnames)
    
    def constants = const +: tail.constants
    
    def defns : Seq[Defn[A]] = tail.defns
    
    def defnEqualities : Seq[DefnEquality] = tail.defnEqualities
    
    def eliminate(isVar : Term => Boolean): Term => Term = tail.eliminate(isVar)
    
    val elim = tail.elim
    
    val typ = tail.typ
  }
  
  case class DefnMixin[+A, +U<: Term, +V <: Term](dfn : Defn[A], tail: Context[A, U, V], dep: Boolean = false) extends Context[A, FuncTerm[Term,U], V]{
    type CtxType = FuncTerm[Term, tail.CtxType]
    
    def foldin : CtxType => V = (f) => tail.foldin(f(dfn.lhs))
    
    def symblist[A](varnames: List[A]) = typ.symbObj(varnames.head) :: tail.symblist(varnames.tail)
    
    def constants : Seq[Term] = tail.constants
    
    def defns : Seq[Defn[A]] = dfn +: tail.defns
    
    def defnEqualities : Seq[DefnEquality] = dfn +: tail.defnEqualities
    
    def eliminate(isVar : Term => Boolean): Term => Term = tail.eliminate(isVar)
    
    val elim  =  tail.elim andThen ((t : Term) => t.subs(dfn.lhs, dfn.rhs))
    								
    
    val typ = if (dep) {
      val fibre = (t : Term) => tail.typ subs (x, t)
	    
	    val family = typFamilyDefn[Term, tail.CtxType](dfn.lhs.typ, MiniVerse(tail.typ), fibre)
	    PiTyp[Term, tail.CtxType](family)
    }
    else FuncTyp(dfn.lhs.typ, tail.typ)
  } 
  
  case class GlobalDefnMixin[+A, +U <: Term, +V <: Term](dfn : Defn[A], tail: Context[A, U, V]) extends Context[A, U, V]{
    type CtxType = tail.CtxType
    
    def foldin : CtxType => V = tail.foldin
    
    def symblist[A](varnames: List[A]) = tail.symblist(varnames)
    
    def constants : Seq[Term] = tail.constants
    
    def defns : Seq[Defn[A]] = dfn +: tail.defns
    
    def defnEqualities : Seq[DefnEquality] = dfn +: tail.defnEqualities
    
    def eliminate(isVar : Term => Boolean): Term => Term = tail.eliminate(isVar)
    
    val elim  = tail.elim
    
    val typ = tail.typ								
  }
  
  case class DefnEqualityMixin[+A, +U <: Term, +V <: Term](eqlty : DefnEquality, tail: Context[A, U, V]) extends Context[A, U, V]{
    type CtxType = tail.CtxType
    
    def foldin : CtxType => V = tail.foldin
    
    def symblist[A](varnames: List[A]) = tail.symblist(varnames)
    
    def constants : Seq[Term] = tail.constants
    
    def defns : Seq[Defn[A]] = tail.defns
    
    def defnEqualities : Seq[DefnEquality] = eqlty +: tail.defnEqualities
    
    def eliminate(isVar : Term => Boolean): Term => Term = tail.eliminate(isVar)
    
    val elim  = tail.elim
    
    val typ = tail.typ								
  }
  
  case class SimpEqualityMixin[+A, +U <: Term, +V <: Term](eqlty : DefnEquality, tail: Context[A, U, V], dep : Boolean = false) extends Context[A, FuncTerm[Term,U], V]{
    type CtxType = FuncTerm[Term, tail.CtxType]
    
    def foldin : CtxType => V = (f) => tail.foldin(f(eqlty.lhs))
    
    def symblist[A](varnames: List[A]) = typ.symbObj(varnames.head) :: tail.symblist(varnames.tail)
    
    def constants : Seq[Term] = tail.constants
    
    def defns : Seq[Defn[A]] = tail.defns
    
    def defnEqualities : Seq[DefnEquality] = eqlty +: tail.defnEqualities
    
    def eliminate(isVar : Term => Boolean): Term => Term = tail.eliminate(isVar)
    
    val elim  =  tail.elim andThen ((t : Term) => t.subs(eqlty.lhs, eqlty.rhs))
    								
    val typ = if (dep) {
      val fibre = (t : Term) => tail.typ subs (x, t)
	    
	    val family = typFamilyDefn[Term, tail.CtxType](eqlty.lhs.typ, MiniVerse(tail.typ), fibre)
	    PiTyp(family)
    }
    else FuncTyp(eqlty.lhs.typ, tail.typ) 							
  }

    /*
   * Change in context for a TypPatn (i.e., simple pattern ending in W).
   * Should allow for dependent types when making a lambda.
   */
  def recContextChange[A, U <: Term, V<: Term](f : => (FuncTerm[Term, Term]), 
      ptn : TypPtn[U], varname : A, W : Typ[Term], X : Typ[Term]) : Context[A, Term, V] => Context[A, Term, V] = {
    val x = ptn(X).symbObj(varname)
    ctx => ctx lmbda(x) lmbda(ptn.induced(W, X)(f)(x))
  }
  
  def simpleContextChange[A, U <: Term, V<: Term](ptn : TypPtn[U], varname : A, W : Typ[Term], X : Typ[Term]) : Context[A, Term, V] => Context[A, Term, V] = {
    val x = ptn(X).symbObj(varname)
    ctx => ctx lmbda(x)
  }
  
    /*
   * Change in context (as function on W) for Induction - check
   */
  def indContextChange[A, U <: Term, V<: Term](f : => (FuncTerm[Term, Term]), ptn : TypPtn[U],
      varname : A, W : Typ[Term], Xs : Term => Typ[Term]) : (Term => Context[A, Term, V]) => (Term => Context[A, Term, V]) = {
    val xs =  (t : Term) => ptn(Xs(t)).symbObj(varname)
    ctxs => (t : Term) => ctxs(t) lmbda(xs(t)) lmbda(ptn.inducedDep(W, Xs)(f)(xs(t)))
  }
  
  /*
   * The context, recursively defined, for the constructor of a single context.
   * This is also a change, to be applied by default to the empty context of the target type.
   */
  @tailrec def cnstrRecContext[A, V<: Term](f : => (FuncTerm[Term, Term]), 
      ptn : PolyPtn, varnames : List[A], 
      W : Typ[Term], 
      X : Typ[Term])(ctx: Context[A, Term, V] = Context.empty[Term](X)) : Context[A, Term, V] = {
    ptn match {
      case tp: TypPtn[_] => recContextChange(f, tp, varnames.head, W, X)(ctx)
      case FuncPtn(tail, head) => cnstrRecContext(f, head, varnames.tail, W, X)( recContextChange(f, tail, varnames.head, W, X)(ctx))
      case CnstFncPtn(tail : Typ[_], head) => cnstrRecContext(f, head, varnames.tail, W, X)( ctx lmbda(tail.symbObj(varnames.head)))
      case DepFuncPtn(tail, headfibre , _) =>
        val x  = tail(X).symbObj(varnames.head).asInstanceOf[Term]
        cnstrRecContext(f, headfibre(x), varnames.tail, W, X)( ctx lmbda(x))
      case CnstDepFuncPtn(tail, headfibre , _) =>
        val x = tail.symbObj(varnames.head)
        cnstrRecContext(f, headfibre(x), varnames.tail, W, X)( ctx lmbda(x))
    }
  }
  
  @tailrec def cnstrIndContext[A, V<: Term](f : => (FuncTerm[Term, Term]), 
      ptn : PolyPtn, varnames : List[A], 
      W : Typ[Term], 
      Xs :  Term => Typ[Term])(ctx: Term  => Context[A, Term, V] = (t: Term) => Context.empty[Term](Xs(t))) : Term => Context[A, Term, V] = {
    ptn match {
      case tp: TypPtn[_] => indContextChange(f, tp, varnames.head, W, Xs)(ctx)
      case FuncPtn(tail, head) => cnstrIndContext(f, head, varnames.tail, W, Xs)( indContextChange(f, tail, varnames.head, W, Xs)(ctx))
      case CnstFncPtn(tail : Typ[_], head) => 
        cnstrIndContext(f, head, varnames.tail, W, Xs)((t: Term) => ctx(t) lmbda(tail.symbObj(varnames.head)))
      case DepFuncPtn(tail, headfibre , _) =>
//            val x = tail(Xs(t)).symbObj(varnames.head)
            cnstrIndContext(f, headfibre(x), varnames.tail, W, Xs)((t: Term) =>  ctx(t) lmbda((tail(Xs(t)).symbObj(varnames.head)).asInstanceOf[Term]))
      case CnstDepFuncPtn(tail, headfibre , _) =>
//        	val x = tail.symbObj(varnames.head)
        	cnstrIndContext(f, headfibre(x), varnames.tail, W, Xs)((t : Term) => ctx(t) lmbda(tail.symbObj(varnames.head)))
    }
  }
  
  
  @tailrec def cnstrSimpleContext[A, V<: Term]( 
      ptn : PolyPtn, varnames : List[A], 
      W : Typ[Term], 
      X : Typ[Term])(ctx: Context[A, Term, V] = Context.empty[Term](X)) : Context[A, Term, V] = {
    ptn match {
      case tp: TypPtn[_] => simpleContextChange(tp, varnames.head, W, X)(ctx)
      case FuncPtn(tail, head) => cnstrSimpleContext(head, varnames.tail, W, X)( simpleContextChange(tail, varnames.head, W, X)(ctx))
      case CnstFncPtn(tail : Typ[_], head) => cnstrSimpleContext(head, varnames.tail, W, X)( ctx lmbda(tail.symbObj(varnames.head)))
      case DepFuncPtn(tail, headfibre , _) =>
        val x = tail(X).symbObj(varnames.head).asInstanceOf[Term]
        cnstrSimpleContext(headfibre(x), varnames.tail, W, X)( ctx lmbda(x))
      case CnstDepFuncPtn(tail, headfibre , _) =>
        val x = tail.symbObj(varnames.head)
        cnstrSimpleContext(headfibre(x), varnames.tail, W, X)( ctx lmbda(x))
    }
  }
  
  
  case class RecInduced(cons : Term, func: Term => Term)
  
  case class IndInduced(cons: Term, func: Term => Term)
  
  def addConstructor[V <: Term](f : => (FuncTerm[Term, Term]), 
      cnstr : Constructor, varnames : List[Any], 
      W : Typ[Term], 
      X : Typ[Term]) : Context[Any, Term, V] => Context[Any, Term, V] = ctx => {
        val cnstrctx = cnstrRecContext(f, cnstr.pattern, varnames, W, X)()
        val name = cnstrctx.foldinSym(RecInduced(cnstr.cons, f))
      		ctx lmbda (name)
      }
      
  def addIndConstructor[V <: Term](f : => (FuncTerm[Term, Term]), 
      cnstr : Constructor, varnames : List[Any], 
      W : Typ[Term], 
      Xs : Term => Typ[Term]) : (Context[Any, Term, V]) => (Context[Any, Term, V]) =  ctx => {
        val cnstrctxs = cnstrIndContext(f, cnstr.pattern, varnames, W, Xs)()
        val silly = Unit.symbObj("silly")
        val term : Term = cnstrSimpleContext(cnstr.pattern, varnames, W, Xs(silly))().foldinSym(varnames)
        val name = cnstrctxs(term).foldinSym(IndInduced(cnstr.cons, f))
      		 ctx lmbda (name)
      }
      
  def recContext(f : => (FuncTerm[Term, Term]), 
      cnstrvars : List[(Constructor, List[Any])], 
      W : Typ[Term], 
      X : Typ[Term]) ={
    val add : (Context[Any, Term, FuncTerm[Term, Term]], 
        (Constructor, List[Any])) => Context[Any, Term, FuncTerm[Term, Term]] = (ctx, cnvr) =>
      addConstructor(f, cnvr._1, cnvr._2, W, X)(ctx)
      val empty : Context[Any, Term, FuncTerm[Term, Term]] = Context.empty(FuncTyp(W, X))
    (empty /: cnstrvars)(add)
  }
  
  def recFunction(f : => (FuncTerm[Term, Term]), 
      cnstrvars : List[(Constructor, List[Any])], 
      W : Typ[Term], 
      X : Typ[Term]) = {

    recContext(f, cnstrvars, W, X).foldinSym(RecSymbol(W, X))}
  
  // Should avoid type coercion by having proper types for constructors and polypatterns.
  def recIdentities(f : => (FuncTerm[Term, Term]), 
      cnstrvars : List[(Constructor, List[Any])], 
      W : Typ[Term], 
      X : Typ[Term]) = {

    val recfn = recContext(f, cnstrvars, W, X).foldinSym(RecSymbol(W, X))
    
    def eqn(cnstr : Constructor, varnames : List[Any]) = {	
    	val ptn = cnstr.pattern
    	val argctx = cnstrSimpleContext(ptn, varnames, W, X)()
    	val cons = cnstr.cons.asInstanceOf[argctx.CtxType]
    	val arg = argctx.foldin(cons)
    	val lhs = recfn(arg)
    	val rhsctx = cnstrRecContext(f, ptn, varnames, W, X)()
    	val rhs = rhsctx.foldinSym(RecInduced(cons, f))
    	DefnEqual(lhs, rhs, argctx.symblist(varnames))
    }
    
    val eqntail = for ((cnstr, varnames) <- cnstrvars) yield eqn(cnstr, varnames)
	
    val allvars = eqntail flatMap (_.freevars)
    
    DefnEqual(f, recfn, allvars) :: eqntail
  }
  
  /*
   * The symbolic object for defining a recursion function from a context.
   */
  case class RecSymbol(W : Typ[Term], X : Typ[Term])
  
}
