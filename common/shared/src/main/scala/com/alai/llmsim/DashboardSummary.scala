package com.alai.llmsim

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/** The dashboard's data shapes (roadmap item 13's `GET /_llmsim/dashboard`)
  * -- moved to `common`, not `Dashboard.scala`, the next step of the
  * console reorganization after CapturedHeader/CapturedMessage/
  * CallOutcome/CapturedCall. Grouped in one file, unlike those four:
  * these five don't make sense independently the way the others did --
  * ScriptSummary is always a field of DashboardSummary, never used on
  * its own.
  *
  * `Dashboard.summarize` (the pure function building a DashboardSummary
  * from a journal's calls and a ScriptStatus) stays in Dashboard.scala,
  * backend-only -- it depends on CallOutcome/CapturedCall/ScriptStatus/
  * Overrun for its INPUT, but only its OUTPUT (these five types) is
  * what the console actually needs. Upgraded from Encoder-only (the
  * backend never needed to decode its own dashboard JSON back) to a
  * full Codec here, matching CapturedCall's own upgrade -- the console
  * needs a Decoder this time, not just an Encoder.
  */
final case class ScriptSummary(
    name: Option[String],
    totalSteps: Int,
    nextStepIndex: Option[Int],
    onOverrun: String,
    exhausted: Boolean
)

final case class JournalSummary(retainedCalls: Int, capacity: Int)

final case class CallsSummary(
    byOutcome: Map[String, Int],
    byProvider: Map[String, Int],
    streamed: Int
)

/** `average`/`p95`/`max` and `lastCallAtEpochMillis` (on the enclosing
  * summary) are `None`, not `0`, when the journal is empty -- a
  * fresh, idle simulator should read as "no data yet," not "average
  * latency 0ms," which would be a real answer to a question nobody
  * asked.
  */
final case class LatencySummary(
    sampleCount: Int,
    average: Option[Double],
    p95: Option[Long],
    max: Option[Long]
)

final case class DashboardSummary(
    schemaVersion: Int,
    script: ScriptSummary,
    journal: JournalSummary,
    calls: CallsSummary,
    latencyMillis: LatencySummary,
    lastCallAtEpochMillis: Option[Long]
)

object ScriptSummary   { implicit val codec: Codec[ScriptSummary]   = deriveCodec }
object JournalSummary  { implicit val codec: Codec[JournalSummary]  = deriveCodec }
object CallsSummary    { implicit val codec: Codec[CallsSummary]    = deriveCodec }
object LatencySummary  { implicit val codec: Codec[LatencySummary]  = deriveCodec }
object DashboardSummary { implicit val codec: Codec[DashboardSummary] = deriveCodec }
