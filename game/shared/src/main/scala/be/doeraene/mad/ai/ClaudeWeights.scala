package be.doeraene.mad.ai

/** Every tunable constant of [[ClaudeEvaluator]], gathered in one place so a candidate set of weights can be built,
  * mutated and scored by a tuner without ever touching the evaluator's logic.
  *
  * The numbers here are the result of an 80-round depth-3 hill climb (see [[tuning.ClaudeWeightTuner]]) starting
  * from a hand-picked baseline (materialBase=3.0, materialAlpha=0.75, materialBeta=0.5, recallCredit=0.5,
  * corvetteThreatWeight=25.0, corvetteEscapeWeight=3.0, corvetteApproachWeight=4.0, bodyguardWeight=1.2,
  * cruiserCautionWeight=8.0, huntWeight=0.1, huntHorizon=4.0, centerWeight=0.15 - reasoned about from the game's
  * structure, not fit to data). The tuned values below beat jPaul's theory outright in all 8 depth-3 games tested
  * against it, across `aValue`s both inside and outside the tuning battery (0.005 to 0.1) - not just the two
  * `aValue`s it was scored against, so this isn't merely overfit to the objective. [[tunable]] is what lets a
  * tuning loop treat this as a plain vector to search over.
  */
final case class ClaudeWeights(
    materialBase: Double = 2.7690640147268537,
    materialAlpha: Double = 1.257106312342123,
    materialBeta: Double = 0.3189173987779785,
    recallCredit: Double = 0.4490319251502,
    corvetteThreatWeight: Double = 25.0,
    corvetteEscapeWeight: Double = 7.667757934571708,
    corvetteApproachWeight: Double = 4.808031835490084,
    bodyguardWeight: Double = 0.7626093308895298,
    cruiserCautionWeight: Double = 9.779189861665541,
    huntWeight: Double = 0.1295587781102943,
    huntHorizon: Double = 3.0934441421013474,
    centerWeight: Double = 0.1277504203251976
)

object ClaudeWeights:

  val default: ClaudeWeights = ClaudeWeights()

  /** One (name, getter, setter) triple per tunable field. A tuner can then pick a field by index and perturb it
    * generically, without hand-writing a branch per field or reaching for reflection.
    */
  val tunable: List[(String, ClaudeWeights => Double, (ClaudeWeights, Double) => ClaudeWeights)] = List(
    ("materialBase", _.materialBase, (w, v) => w.copy(materialBase = v)),
    ("materialAlpha", _.materialAlpha, (w, v) => w.copy(materialAlpha = v)),
    ("materialBeta", _.materialBeta, (w, v) => w.copy(materialBeta = v)),
    ("recallCredit", _.recallCredit, (w, v) => w.copy(recallCredit = v)),
    ("corvetteThreatWeight", _.corvetteThreatWeight, (w, v) => w.copy(corvetteThreatWeight = v)),
    ("corvetteEscapeWeight", _.corvetteEscapeWeight, (w, v) => w.copy(corvetteEscapeWeight = v)),
    ("corvetteApproachWeight", _.corvetteApproachWeight, (w, v) => w.copy(corvetteApproachWeight = v)),
    ("bodyguardWeight", _.bodyguardWeight, (w, v) => w.copy(bodyguardWeight = v)),
    ("cruiserCautionWeight", _.cruiserCautionWeight, (w, v) => w.copy(cruiserCautionWeight = v)),
    ("huntWeight", _.huntWeight, (w, v) => w.copy(huntWeight = v)),
    ("huntHorizon", _.huntHorizon, (w, v) => w.copy(huntHorizon = v)),
    ("centerWeight", _.centerWeight, (w, v) => w.copy(centerWeight = v))
  )

end ClaudeWeights
