package be.doeraene.mad.ai

/** Every tunable constant of [[TacticalEvaluator]], gathered so a candidate can be built, mutated and scored without
  * the evaluator's logic ever being touched - same contract as [[ClaudeWeights]] has with [[ClaudeEvaluator]].
  *
  * These values are **fitted**, by [[be.doeraene.mad.ai.tuning.TexelTuner]], and they disagree sharply with the
  * hand-reasoned set that preceded them. Against jPaul's theory over the full 81-opening battery they score 66.7% at
  * depth 3 and 76.2% at depth 4, where the hand-reasoned values managed 51.5% and 49.7%. Against
  * [[ClaudeEvaluator]] at depth 4, on openings no position of which entered the fit, 81.5%.
  *
  * Two things the fit did are worth understanding before touching these numbers.
  *
  * It raised [[huntWeight]] from 0.15 to 2.18 and [[huntHorizon]] from 3.5 to 5.1, while zeroing [[centerWeight]],
  * [[bodyguardWeight]] and [[drawFear]]. The resulting evaluator is close to jPaul's own theory - drive everything at
  * the enemy corvette - with this evaluator's exchange resolution and corvette safety layered on top. The
  * hand-reasoned weights had that pull an order of magnitude too weak, and the visible symptom was draws: 25 of 162
  * at depth 3 before, 12 after.
  *
  * The material scale is floored deliberately. An unconstrained fit drives [[materialBase]], [[defenceBonus]] and
  * [[movementBonus]] to zero, loads everything onto [[attackBonus]], and so prices 112, 211 and 212 at *nothing* -
  * which fits the data fine ("has attack-2 ships alive" predicts winning) and even scores 70.4% against jPaul at
  * depth 4, because material is not what protects a ship here. But nothing in that evaluation objects to handing
  * those three ships over, and a human will take them in a way no engine in the battery ever tried. With floors
  * under the material weights (see [[be.doeraene.mad.ai.tuning.TexelTuner]]) the cheapest ship is worth 0.80 against
  * the cruiser's 7.46, and the fit came back *stronger* at depth 4 - 76.2% against 70.4%, with 37 draws instead of
  * 50. Keeping material worth something costs nothing and converts won positions better.
  *
  * That 9:1 spread is still steep, so a human may find favourable trades against 112. The floor closes the outright
  * giveaway, not the whole gap.
  */
final case class TacticalWeights(
    // --- material scale ---
    materialBase: Double = 0.00018310546875,
    attackBonus: Double = 1.9284236454367385,
    defenceBonus: Double = 0.8,
    movementBonus: Double = 4.7258883,
    dualBonus: Double = 0.005651267072493164,
    recallCredit: Double = 0.00024694824218749996,
    // --- hanging pieces / exchanges ---
    hangingWeight: Double = 2.1320162149188997,
    rescueFactor: Double = 0.28302713808849905,
    // --- corvette safety ---
    corvetteAttackedWeight: Double = 89.17401575941923,
    corvetteTempoRelief: Double = 0.08839582233701995,
    corvetteTrappedWeight: Double = 2.553628313580442,
    corvetteApproachWeight: Double = 0.12214487249999996,
    bodyguardWeight: Double = 0.0001,
    // --- initiative ---
    huntWeight: Double = 2.1828517708717126,
    huntHorizon: Double = 5.145643234374999,
    mobilityWeight: Double = 0.516096,
    centerWeight: Double = 0.0001,
    // --- the 30-turns-without-an-exile draw ---
    drawFear: Double = 0.0001
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
