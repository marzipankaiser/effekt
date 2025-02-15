package effekt
package typer

import effekt.context.Context
import effekt.symbols._


/**
 * All effects inferred by Typer are required to be concrete and dealiased.
 *
 * This way, we can easily compare them for equality.
 */
class ConcreteEffects private[typer] (protected val effects: List[InterfaceType]) {

  def toList: List[InterfaceType] = effects
  def toEffects: Effects = Effects(effects)

  // both are known to be concrete, no need to go through validation again
  def ++(other: ConcreteEffects): ConcreteEffects = ConcreteEffects.fromList(this.effects ++ other.effects)

  // we can use set difference since type constructors are assumed to be invariant and all unification variables
  // are substituted away.
  def --(other: ConcreteEffects): ConcreteEffects = ConcreteEffects.fromList(
    (this.effects.toSet -- other.effects.toSet).toList
  )

  def isEmpty: Boolean = effects.isEmpty
  def nonEmpty: Boolean = effects.nonEmpty

  def filterNot(p: InterfaceType => Boolean): ConcreteEffects = ConcreteEffects.fromList(effects.filterNot(p))

  def controlEffects: List[InterfaceType] = effects.controlEffects
  def builtinEffects: List[InterfaceType] = effects.builtinEffects

  def forall(p: InterfaceType => Boolean): Boolean = effects.forall(p)
  def exists(p: InterfaceType => Boolean): Boolean = effects.exists(p)

  override def toString = toEffects.toString
}
object ConcreteEffects {
  // unsafe, doesn't perform check
  private def fromList(eff: List[InterfaceType]): ConcreteEffects = new ConcreteEffects(eff.distinct)

  /**
   * These smart constructors should not be used directly.
   * [[Typer.asConcrete]] should be used instead, since it performs substitution and dealiasing.
   */
  def apply(eff: List[InterfaceType])(using Context): ConcreteEffects =
    eff foreach assertConcrete
    fromList(eff)

  def apply(effs: Effects)(using Context): ConcreteEffects = apply(effs.toList)

  def empty: ConcreteEffects = fromList(Nil)
}

val Pure = ConcreteEffects.empty

implicit def asConcrete(effs: Effects)(using C: Context): ConcreteEffects =
  ConcreteEffects(C.unification(effs))


/**
 * Asserts that all effects in the list are _concrete_, that is,
 * no unification variables (neither type, nor region) are allowed.
 *
 * If all effects are concrete (and we assume effect type constructors are INVARIANT):
 *   - we can use structural equality to compare them
 *   - we can use sets and hash maps
 *
 * Consequences:
 *   - If we try to add an effect that is not concrete, we should raise an "Could not infer..." error.
 *   - We need to substitute early in order to have more concrete effects.
 *   - Requiring effects to be concrete also simplifies effect-set comparison in [[TypeComparer]].
 *
 * TODO Question: should we ALWAYS require effects to be concrete, also when compared with [[TypeUnifier]]?
 */
private[typer] def assertConcrete(effs: Effects)(using C: Context): Unit =
  if (!isConcreteEffects(effs)) C.abort(pp"Effects need to be fully known: ${effs}")

private[typer] def assertConcrete(eff: InterfaceType)(using C: Context): Unit =
  if (!isConcreteEffect(eff)) {
    C.abort(pp"Effects need to be fully known: ${eff}")
  }

private def isConcreteValueType(tpe: ValueType): Boolean = tpe match {
  case x: UnificationVar => false
  case x: TypeVar => true
  case t: TypeConstructor => true
  case t : BuiltinType => true
  case ValueTypeApp(tpe, args) => isConcreteValueType(tpe) && args.forall(isConcreteValueType)
  case BoxedType(tpe, capture) => isConcreteBlockType(tpe) && isConcreteCaptureSet(capture)
}

private def isConcreteBlockType(tpe: BlockType): Boolean = tpe match {
  case FunctionType(tparams, cparams, vparams, bparams, result, effects) =>
    vparams.forall(isConcreteValueType) && bparams.forall(isConcreteBlockType) && isConcreteValueType(result) && isConcreteEffects(effects)
  case BlockTypeApp(tpe, args) => isConcreteBlockType(tpe) && args.forall(isConcreteValueType)
  case t: Interface => true
  case b: BuiltinEffect => true
}
private def isConcreteCaptureSet(capt: Captures): Boolean = capt.isInstanceOf[CaptureSet]

private def isConcreteEffect(eff: InterfaceType): Boolean = eff match {
  case t: Interface => true
  case t: BuiltinEffect => true
  case BlockTypeApp(tpe, args) => isConcreteBlockType(tpe) && args.forall(isConcreteValueType)
}
private def isConcreteEffects(effs: Effects): Boolean = effs.toList.forall(isConcreteEffect)
