package com.alai.llmsim

import io.circe.{Encoder, Json}
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._

/** The bare-bones dashboard (roadmap item 13): a plain JSON summary
  * (`GET /_llmsim/dashboard`) plus a zero-build static HTML page that
  * polls and renders it (`GET /_llmsim/ui`). Not a final UI -- the real
  * Angular console (roadmap item 16) will eventually replace the page
  * at that same path; the data model here is deliberately simple enough
  * that it doesn't need to anticipate that redesign.
  *
  * Everything here is a pure function of the journal's current
  * contents plus a `ScriptStatus` snapshot -- ManagementRoutes owns the
  * actual HTTP routes and calls into this for the data and markup.
  */
object Dashboard {

  val SchemaVersion = 1

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

  implicit val scriptSummaryEncoder: Encoder[ScriptSummary] = deriveEncoder
  implicit val journalSummaryEncoder: Encoder[JournalSummary] = deriveEncoder
  implicit val callsSummaryEncoder: Encoder[CallsSummary] = deriveEncoder
  implicit val latencySummaryEncoder: Encoder[LatencySummary] = deriveEncoder
  implicit val dashboardSummaryEncoder: Encoder[DashboardSummary] = deriveEncoder

  // "fail"/"repeatLast"/"cycle" -- the actual Scala case-object names
  // with just the first letter lowercased, not Scala's raw .toString
  // (which would leak "Fail"/"RepeatLast"/"Cycle" -- fine internally,
  // but this is a wire contract a future Angular client will depend on,
  // so it gets an explicit, stable mapping instead).
  private def overrunName(o: Overrun): String = o match {
    case Overrun.Fail       => "fail"
    case Overrun.RepeatLast => "repeatLast"
    case Overrun.Cycle      => "cycle"
  }

  private val KnownOutcomes = List("responded", "rejected", "failed", "cancelled")
  private val KnownProviders = List("openai", "anthropic")

  private def outcomeKey(outcome: CallOutcome): String = outcome match {
    case _: CallOutcome.Responded => "responded"
    case _: CallOutcome.Rejected  => "rejected"
    case _: CallOutcome.Failed    => "failed"
    case _: CallOutcome.Cancelled => "cancelled"
  }

  /** Nearest-rank percentile: the ceil(sampleSize * p)-th smallest value
    * (1-indexed), converted to a 0-indexed lookup into the sorted list.
    * For very small sample sizes this is a genuinely coarse answer --
    * p95 of 12 samples lands on the max, which is the honest result for
    * a sample that small, not a flaw in the method.
    */
  private def percentile(sortedAscending: Vector[Long], p: Double): Long = {
    val index = math.ceil(sortedAscending.size * p).toInt - 1
    sortedAscending(index.max(0).min(sortedAscending.size - 1))
  }

  /** @param calls the journal's CURRENTLY RETAINED calls -- not a
    *              lifetime count. The journal is intentionally bounded
    *              (oldest entries dropped past `journalCapacity`) and
    *              can be cleared independently of the script (see
    *              `DELETE /_llmsim/calls` vs `POST /_llmsim/reset`), so
    *              every count and latency figure here is scoped to
    *              whatever's currently in that window, not "since this
    *              simulator started."
    */
  def summarize(
      calls: List[CapturedCall],
      journalCapacity: Int,
      scriptStatus: ScriptStatus,
      scriptName: Option[String]
  ): DashboardSummary = {
    val byOutcome  = KnownOutcomes.map(k => k -> 0).toMap ++
      calls.groupBy(c => outcomeKey(c.outcome)).view.mapValues(_.size).toMap
    val byProvider = KnownProviders.map(k => k -> 0).toMap ++
      calls.groupBy(_.provider).view.mapValues(_.size).toMap
    val streamed = calls.count(_.streamed)

    val durations = calls.map(_.durationMillis).sorted.toVector
    val latency =
      if (durations.isEmpty) LatencySummary(sampleCount = 0, average = None, p95 = None, max = None)
      else LatencySummary(
        sampleCount = durations.size,
        average = Some(durations.sum.toDouble / durations.size),
        p95 = Some(percentile(durations, 0.95)),
        max = Some(durations.last)
      )

    // Calls come back from the journal in sequence (insertion) order --
    // CallJournal records sequence and the call in one atomic Ref
    // update specifically so that ordering is guaranteed -- so the last
    // element genuinely is the most recently received call, no
    // separate max-by needed.
    val lastCallAt = calls.lastOption.map(_.receivedAtEpochMillis)

    DashboardSummary(
      schemaVersion = SchemaVersion,
      script = ScriptSummary(
        name = scriptName,
        totalSteps = scriptStatus.totalSteps,
        nextStepIndex = scriptStatus.nextStepIndex,
        onOverrun = overrunName(scriptStatus.onOverrun),
        exhausted = scriptStatus.exhausted
      ),
      journal = JournalSummary(retainedCalls = calls.size, capacity = journalCapacity),
      calls = CallsSummary(byOutcome, byProvider, streamed),
      latencyMillis = latency,
      lastCallAtEpochMillis = lastCallAt
    )
  }

  // Single embedded string, no build step: fetches /_llmsim/dashboard
  // on a poll loop and renders it. Deliberately no charts, no session
  // view (no session concept exists -- see the roadmap), no per-call
  // browser beyond a plain link to /_llmsim/calls. A recursive
  // setTimeout rather than setInterval, so a slow request can never
  // stack overlapping fetches; cache: "no-store" on the fetch matches
  // the Cache-Control: no-store the JSON endpoint itself sends.
  val htmlPage: String =
    """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>llmsim dashboard</title>
<style>
  body { font-family: -apple-system, sans-serif; max-width: 720px; margin: 2rem auto; padding: 0 1rem; color: #1a1a1a; }
  h1 { font-size: 1.25rem; }
  .row { display: flex; justify-content: space-between; border-bottom: 1px solid #eee; padding: 0.4rem 0; }
  .row .label { color: #666; }
  .row .value { font-variant-numeric: tabular-nums; }
  section { margin-bottom: 1.5rem; }
  section h2 { font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.05em; color: #888; margin-bottom: 0.25rem; }
  #error { display: none; background: #fee; border: 1px solid #f99; color: #900; padding: 0.5rem 0.75rem; border-radius: 4px; margin-bottom: 1rem; }
  #refreshed { color: #999; font-size: 0.8rem; }
  a { color: #06c; }
</style>
</head>
<body>
<h1>llmsim dashboard</h1>
<div id="error"></div>
<div id="content">Loading...</div>
<p><span id="refreshed"></span> &middot; <a href="/_llmsim/calls">view raw calls</a></p>
<script>
// Single-quoted JS strings throughout, deliberately: this whole page is
// one big Scala triple-quoted string, which does NOT process backslash
// escapes at all, so double-quoted JS strings needing escaped quotes
// for HTML attributes would need real care to get right. Single quotes
// for JS, double quotes for HTML attributes -- both valid, and neither
// ever needs to escape the other.
function fmt(n) { return (n === null || n === undefined) ? '-' : n; }
function fmtMs(n) { return (n === null || n === undefined) ? '-' : (n.toFixed ? n.toFixed(1) : n) + ' ms'; }

function row(label, value) {
  return '<div class="row"><span class="label">' + label + '</span><span class="value">' + value + '</span></div>';
}

function render(d) {
  var s = d.script, j = d.journal, c = d.calls, l = d.latencyMillis;
  var html = '';

  html += '<section><h2>Script</h2>';
  html += row('Name', fmt(s.name));
  html += row('Steps', s.totalSteps);
  html += row('Next step', fmt(s.nextStepIndex));
  html += row('On overrun', s.onOverrun);
  html += row('Exhausted', s.exhausted);
  html += '</section>';

  html += '<section><h2>Journal</h2>';
  html += row('Retained calls', j.retainedCalls + ' / ' + j.capacity);
  html += '</section>';

  html += '<section><h2>Calls by outcome</h2>';
  Object.keys(c.byOutcome).forEach(function(k) { html += row(k, c.byOutcome[k]); });
  html += '</section>';

  html += '<section><h2>Calls by provider</h2>';
  Object.keys(c.byProvider).forEach(function(k) { html += row(k, c.byProvider[k]); });
  html += row('streamed', c.streamed);
  html += '</section>';

  html += '<section><h2>Latency (retained calls)</h2>';
  html += row('Samples', l.sampleCount);
  html += row('Average', fmtMs(l.average));
  html += row('p95', fmtMs(l.p95));
  html += row('Max', fmtMs(l.max));
  html += '</section>';

  document.getElementById('content').innerHTML = html;
  document.getElementById('refreshed').textContent = 'last refreshed ' + new Date().toLocaleTimeString();
}

function poll() {
  fetch('/_llmsim/dashboard', { cache: 'no-store' })
    .then(function(r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    })
    .then(function(d) {
      document.getElementById('error').style.display = 'none';
      render(d);
    })
    .catch(function(e) {
      var el = document.getElementById('error');
      el.textContent = "Couldn't refresh: " + e.message;
      el.style.display = 'block';
    })
    .finally(function() {
      setTimeout(poll, 2000);
    });
}

poll();
</script>
</body>
</html>
"""
}
