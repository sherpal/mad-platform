package be.doeraene.mad.ai

/** Every tunable constant of [[TacticalEvaluator]], gathered so a candidate can be built, mutated and scored without
  * the evaluator's logic ever being touched - same contract as [[ClaudeWeights]] has with [[ClaudeEvaluator]].
  *
  * These values are reasoned from the game's structure rather than fit to data:
  *   - the material scale gives every ship a strictly positive value, so no term ever rewards throwing one away, and
  *     spreads them by what actually decides a capture, `(attack, defence)`, with `movement` a secondary bonus and
  *     `dualBonus` for the `(2,2)` ships that can take everything while only `(2,*)` can touch them;
  *   - `hangingWeight` is 1.0 on purpose: a hanging-piece loss is already expressed in piece-value units, so it is
  *     directly commensurable with the material term and needs no rescaling;
  *   - the corvette terms sit an order of magnitude above material, because losing 111 is not a material loss, it is
  *     the end of the game.
  *
  * A word of warning for anyone running [[be.doeraene.mad.ai.tuning.TacticalWeightTuner]] over these: **tune at the
  * depth you intend to play at**. A depth-2 hill climb produced a set that scored 69.2% against jPaul's theory at
  * depth 2, on a battery of openings it had never seen - and 43.8% at depth 3, well below the 48.8% of the untuned
  * values here. The reason is leaf parity. `alphaBeta` stops on a node where the *opponent* is to move at even
  * depths and where *we* are at odd ones, and several terms here ([[rescueFactor]], [[corvetteTempoRelief]], and the
  * on-move switch they feed in [[TacticalEvaluator]]) exist precisely to distinguish those two cases. Tuning at one
  * parity fits weights to the regime the other parity never sees.
  */
final case class TacticalWeights(
    // --- material scale ---
    materialBase: Double = 3.0,
    attackBonus: Double = 1.5,
    defenceBonus: Double = 1.0,
    movementBonus: Double = 0.7,
    dualBonus: Double = 1.5,
    recallCredit: Double = 0.4,
    // --- hanging pieces / exchanges ---
    hangingWeight: Double = 1.0,
    rescueFactor: Double = 0.35,
    // --- corvette safety ---
    corvetteAttackedWeight: Double = 40.0,
    corvetteTempoRelief: Double = 0.25,
    corvetteTrappedWeight: Double = 3.0,
    corvetteApproachWeight: Double = 0.7,
    bodyguardWeight: Double = 0.4,
    // --- initiative ---
    huntWeight: Double = 0.15,
    huntHorizon: Double = 3.5,
    mobilityWeight: Double = 0.06,
    centerWeight: Double = 0.12,
    // --- the 30-turns-without-an-exile draw ---
    drawFear: Double = 0.4
)

object TacticalWeights:

  val default: TacticalWeights = TacticalWeights()

  /** Applies a sparse `name -> value` override map on top of these weights, ignoring names that aren't tunable.
    *
    * Goes through [[tunable]] rather than a derived JSON codec so that a *partial* override works: probing one term
    * at a time is the whole point of being able to set these from the command line, and a derived codec would demand
    * all eighteen fields every time.
    */
  def withOverrides(base: TacticalWeights, overrides: Map[String, Double]): TacticalWeights =
    overrides.foldLeft(base) { case (weights, (name, value)) =>
      tunable.find(_._1 == name) match
        case Some((_, _, setter)) => setter(weights, value)
        case None                 => throw IllegalArgumentException(s"Unknown tactical weight '$name'")
    }

  /** One (name, getter, setter) triple per tunable field, so a tuner can pick a field by index and perturb it
    * generically rather than hand-writing a branch per field or reaching for reflection.
    */
  val tunable: List[(String, TacticalWeights => Double, (TacticalWeights, Double) => TacticalWeights)] = List(
    ("materialBase", _.materialBase, (w, v) => w.copy(materialBase = v)),
    ("attackBonus", _.attackBonus, (w, v) => w.copy(attackBonus = v)),
    ("defenceBonus", _.defenceBonus, (w, v) => w.copy(defenceBonus = v)),
    ("movementBonus", _.movementBonus, (w, v) => w.copy(movementBonus = v)),
    ("dualBonus", _.dualBonus, (w, v) => w.copy(dualBonus = v)),
    ("recallCredit", _.recallCredit, (w, v) => w.copy(recallCredit = v)),
    ("hangingWeight", _.hangingWeight, (w, v) => w.copy(hangingWeight = v)),
    ("rescueFactor", _.rescueFactor, (w, v) => w.copy(rescueFactor = v)),
    ("corvetteAttackedWeight", _.corvetteAttackedWeight, (w, v) => w.copy(corvetteAttackedWeight = v)),
    ("corvetteTempoRelief", _.corvetteTempoRelief, (w, v) => w.copy(corvetteTempoRelief = v)),
    ("corvetteTrappedWeight", _.corvetteTrappedWeight, (w, v) => w.copy(corvetteTrappedWeight = v)),
    ("corvetteApproachWeight", _.corvetteApproachWeight, (w, v) => w.copy(corvetteApproachWeight = v)),
    ("bodyguardWeight", _.bodyguardWeight, (w, v) => w.copy(bodyguardWeight = v)),
    ("huntWeight", _.huntWeight, (w, v) => w.copy(huntWeight = v)),
    ("huntHorizon", _.huntHorizon, (w, v) => w.copy(huntHorizon = v)),
    ("mobilityWeight", _.mobilityWeight, (w, v) => w.copy(mobilityWeight = v)),
    ("centerWeight", _.centerWeight, (w, v) => w.copy(centerWeight = v)),
    ("drawFear", _.drawFear, (w, v) => w.copy(drawFear = v))
  )

end TacticalWeights
