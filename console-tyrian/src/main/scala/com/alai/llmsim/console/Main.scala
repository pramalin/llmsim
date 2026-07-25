package com.alai.llmsim.console

import cats.effect.IO
import tyrian.classic.Html.*
import tyrian.classic.*
import org.http4s.{Method, Request, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dom.FetchClientBuilder
import com.alai.llmsim.{CallOutcome, CapturedCall, DashboardSummary}

import scala.concurrent.duration._
import scala.scalajs.js
import scala.scalajs.js.annotation.*

// Same-origin relative paths (/_llmsim/calls, not
// http://localhost:8089/_llmsim/calls) -- the hardcoded absolute URL
// was a real deployment bug, not just a dev convenience: it would
// break under Docker port-mapping, a different host, HTTPS, or a
// reverse proxy, none of which match localhost:8089 specifically. The
// console is genuinely same-origin now that it's served by llmsim
// itself (see App.scala's resourceServiceBuilder route), so a
// relative path is both correct AND simpler than the URL it replaces.
// For local dev against a separate Vite server, console-tyrian/
// vite.config.js now proxies /_llmsim to localhost:8089 instead --
// same relative paths work in both places, and LLMSIM_DEV_CORS
// (App.scala) is no longer something the console itself needs to lean
// on, though it's left in place in case something else wants it.

// Adds script status, fetched from /_llmsim/dashboard alongside
// /_llmsim/calls -- the piece that would have made the earlier reset-
// vs-clear confusion visible in the console itself instead of needing
// three rounds of curl to sort out. Separate dashboard/dashboardError
// fields, deliberately not reusing the existing calls-fetch error --
// one fetch failing shouldn't hide the other succeeding.
//
// Cmd.Batch confirmed directly from Tyrian's actual source
// (github.com/PurpleKingdomGames/tyrian, Cmd.scala) before use here,
// not assumed -- the one genuinely new API surface this step
// introduces. Reset/Clear's success handler now re-fetches BOTH calls
// and dashboard via Cmd.Batch, which is the actual fix for the
// original problem: script position changing on Reset but not Clear
// is now something the console shows directly.
@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model]:

  def router: Location => Msg = Routing.none(Msg.NoOp)

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (
      Model(calls = Nil, error = None, loading = true, dashboard = None, dashboardError = None,
        selectedSequence = None, actionState = ActionState.Idle, refreshing = false, lastRefreshedAt = None,
        providerFilter = None, outcomeFilter = None, streamedOnly = false, modelSearch = ""),
      Cmd.Batch(List(fetchCalls, fetchDashboard))
    )

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    // error/dashboardError are NEVER cleared on failure now, deliberately
    // -- the views (contentView, scriptStatusView, summaryStripView)
    // check "do we have data" first and render it regardless, showing
    // these only as a warning banner over existing data, or as a full
    // error state when there's genuinely nothing else to show yet
    // (the very first load). That's the actual fix for "retain the
    // previous journal when a refresh fails" -- clearing error only
    // ever happens on a NEW success, not as part of showing one.
    case Msg.CallsLoaded(calls) =>
      (model.copy(calls = calls, error = None, loading = false, refreshing = false, lastRefreshedAt = Some(nowText)), Cmd.None)
    case Msg.FetchError(err) =>
      (model.copy(error = Some(err), loading = false, refreshing = false), Cmd.None)
    case Msg.DashboardLoaded(summary) =>
      (model.copy(dashboard = Some(summary), dashboardError = None, refreshing = false, lastRefreshedAt = Some(nowText)), Cmd.None)
    case Msg.DashboardFetchError(err) =>
      (model.copy(dashboardError = Some(err), refreshing = false), Cmd.None)
    case Msg.SelectCall(seq)    => (model.copy(selectedSequence = Some(seq)), Cmd.None)
    case Msg.RefreshClicked =>
      // Not destructive like Reset/Clear, so no ActionState needed --
      // just a plain guard against a redundant fetch if one's already
      // in flight.
      if model.refreshing then (model, Cmd.None)
      else (model.copy(refreshing = true), Cmd.Batch(List(fetchCalls, fetchDashboard)))
    case Msg.ResetClicked =>
      model.actionState match
        // The real safety net against double-clicks: ignored outright
        // while an action's already in flight, regardless of whether
        // disabled := isPending (below, in controlPanel) actually
        // works as expected -- that one's unverified, this isn't.
        case ActionState.Pending(_) => (model, Cmd.None)
        case _                      => (model.copy(actionState = ActionState.Pending(Action.Reset)), resetSimulator)
    case Msg.ClearClicked =>
      model.actionState match
        case ActionState.Pending(_) => (model, Cmd.None)
        case _                      => (model.copy(actionState = ActionState.Pending(Action.Clear)), clearCalls)
    case Msg.ActionSucceeded(msg) =>
      // Re-fetch both after a successful reset/clear -- the journal
      // changed either way, but script position only changes for
      // Reset, so both need refreshing for that difference to actually
      // show up here rather than needing a separate curl to see.
      (model.copy(actionState = ActionState.Succeeded(msg), selectedSequence = None),
        Cmd.Batch(List(fetchCalls, fetchDashboard)))
    case Msg.ActionFailed(msg) => (model.copy(actionState = ActionState.Failed(msg)), Cmd.None)
    case Msg.SetProviderFilter(p) => (model.copy(providerFilter = p), Cmd.None)
    case Msg.SetOutcomeFilter(o)  => (model.copy(outcomeFilter = o), Cmd.None)
    case Msg.ToggleStreamedOnly   => (model.copy(streamedOnly = !model.streamedOnly), Cmd.None)
    case Msg.SetModelSearch(text) => (model.copy(modelSearch = text), Cmd.None)
    case Msg.NoOp                => (model, Cmd.None)

  def view(model: Model): Html[Msg] =
    div(`class` := "app-container")(
      headerView,
      scriptStatusView(model),
      summaryStripView(model),
      controlPanel(model),
      contentView(model)
    )

  // Always rendered, not tied to model state at all -- the actual fix
  // for "the initial page load looks plain": before this, everything
  // on screen depended on data that hadn't arrived yet, so the very
  // first render was just bare "loading..." text with nothing else
  // establishing what the page even is.
  private def headerView: Html[Msg] =
    div(`class` := "app-header")(
      h1("llmsim console"),
      div(`class` := "app-tagline")("Deterministic LLM API simulation and request inspection")
    )

  // Auto-refresh deliberately not implemented -- Sub.every, which
  // would provide exactly this, genuinely doesn't exist anywhere in
  // tyrian.platform.Sub for this pinned Tyrian version (confirmed by
  // reading tyrian-platform/src/tyrian/platform/Sub.scala directly
  // from the actual current source, not inferred from an example that
  // turned out to be from a different version). Building a custom
  // timer from Sub.make + org.scalajs.dom's setInterval/clearInterval
  // is possible in principle, but needs real, version-pinned
  // verification of that facade's exact signature before attempting
  // it again -- not done here after two failed guesses already.
  // Manual Refresh (controlPanel) covers the actual requirement in
  // the meantime.
  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None

  private def scriptStatusView(model: Model): Html[Msg] =
    model.dashboard match
      case None =>
        // Nothing to show yet at all -- this is the only case where an
        // error genuinely replaces the whole view, since there's no
        // stale data underneath it to preserve.
        model.dashboardError match
          case Some(err) => div(`class` := "status-message status-message-error")(s"Script status unavailable: $err")
          case None      => div(`class` := "status-message")("Script: loading...")
      case Some(d) =>
        val (badgeClass, badgeText) =
          if d.script.exhausted then ("status-badge status-badge-exhausted", "Exhausted")
          else ("status-badge status-badge-running", "Running")
        val progressText =
          if d.script.exhausted then
            s"The script is exhausted -- the next request will fail" +
              s" (configured overrun behavior: ${d.script.onOverrun})."
          else
            // nextStepIndex is 0-based internally; +1 for a
            // human-countable "Next response: 1 of N", clearer
            // than the previous "Step 0 of N" (ambiguous -- has
            // step 0 already happened, or is it next?).
            s"Next response: ${d.script.nextStepIndex.map(_ + 1).getOrElse("-")} of ${d.script.totalSteps}" +
              s" (on overrun: ${d.script.onOverrun})"
        // Laid out as a row via flexbox on the parent (.script-status
        // in the stylesheet), not inline-block on each child --
        // avoids needing an unconfirmed <span> tag just to get
        // horizontal layout; div + CSS flex achieves the same thing
        // using only the already-confirmed div/class pattern.
        div()(
          // We already have data (possibly from a while ago) -- a
          // failed refresh becomes a warning banner on top of it,
          // not a replacement for it. This is the actual fix for
          // "retain the previous journal when a refresh fails".
          (model.dashboardError match
            case Some(err) => div(`class` := "stale-warning")(s"Could not refresh script status: $err")
            case None      => div()
          ),
          div(`class` := "script-status")(
            div(`class` := badgeClass)(badgeText),
            div(s"Script: ${d.script.name.getOrElse("-")}"),
            div(progressText)
          )
        )

  // "47 calls · 3 failures · 12 streamed · OpenAI 31 / Anthropic 16 ·
  // p95 420 ms" -- makes the console read as a deterministic test-
  // observation tool at a glance, not just a raw HTTP log. "failures"
  // here means anything that isn't Responded (rejected + failed +
  // cancelled combined) -- a quick health signal, not a breakdown;
  // the full per-outcome split is still visible per-row in the table
  // below. byOutcome/byProvider keys confirmed against Dashboard.scala's
  // own KnownOutcomes/KnownProviders lists (always fully populated,
  // defaulting to 0), not guessed.
  private def summaryStripView(model: Model): Html[Msg] =
    model.dashboard match
      case None => div(`class` := "status-message")("Summary: loading...")
      case Some(d) =>
        val failures = d.calls.byOutcome.getOrElse("rejected", 0) +
          d.calls.byOutcome.getOrElse("failed", 0) +
          d.calls.byOutcome.getOrElse("cancelled", 0)
        val failuresSub =
          if d.journal.retainedCalls > 0 then
            f"${failures.toDouble / d.journal.retainedCalls * 100}%.0f%% of calls"
          else "-"
        // Real bug caught in review: this card's subtitle used to
        // show the all-calls provider breakdown, which reads as a
        // breakdown of the streamed number right above it -- it
        // wasn't. Percentage-of-calls now, matching Failures' own
        // pattern; the actual provider breakdown moved to its own
        // card below instead of being lost.
        val streamedSub =
          if d.journal.retainedCalls > 0 then
            f"${d.calls.streamed.toDouble / d.journal.retainedCalls * 100}%.0f%% of calls"
          else "-"
        val p95Text = d.latencyMillis.p95.map(p => s"${p}ms").getOrElse("-")
        val avgSub = d.latencyMillis.average.map(a => f"$a%.0fms avg").getOrElse("no data yet")
        div(`class` := "summary-cards")(
          summaryCard("Retained calls", d.journal.retainedCalls.toString, s"of ${d.journal.capacity} capacity"),
          summaryCard("Failures", failures.toString, failuresSub),
          summaryCard("Streamed", d.calls.streamed.toString, streamedSub),
          summaryCard("Providers",
            s"OpenAI ${d.calls.byProvider.getOrElse("openai", 0)} · Anthropic ${d.calls.byProvider.getOrElse("anthropic", 0)}",
            ""),
          summaryCard("Latency p95", p95Text, avgSub)
        )

  private def summaryCard(label: String, value: String, sub: String): Html[Msg] =
    // Providers has no single headline number (it's a two-part
    // breakdown), so value can be empty -- omit that line entirely
    // rather than render an empty, oddly-large-font gap where a
    // number would normally be.
    val middle: List[Html[Msg]] =
      if value.isEmpty then Nil else List(div(`class` := "summary-card-value")(value))
    val children: List[Html[Msg]] =
      div(`class` := "summary-card-label")(label) :: middle ::: List(div(`class` := "summary-card-sub")(sub))
    div(`class` := "summary-card")(children*)

  private def controlPanel(model: Model): Html[Msg] =
    val isPending = model.actionState match
      case ActionState.Pending(_) => true
      case _                      => false
    div(`class` := "control-panel")(
      // "Reset script + clear calls" / "Clear calls only" -- Reset and
      // Clear read as near-synonyms even though they do genuinely
      // different things (Reset rewinds script position too, Clear
      // doesn't) -- confirmed for real via dashboard's nextStepIndex
      // a few sessions back, not just asserted. Longer labels spell out
      // the actual difference instead of relying on the reader already
      // knowing it.
      //
      // disabled is a complete, pre-built attribute in Tyrian (real
      // HTML boolean-attribute semantics: present or absent, never
      // disabled="true"/"false"), not a String => Attribute function
      // like style/id/placeholder -- disabled := isPending was a real
      // compile error caught immediately, not a silent bug. Included
      // conditionally instead, each branch its own complete button
      // call rather than trying to build a mixed attribute list of an
      // unconfirmed common type.
      if isPending then button(onClick(Msg.ResetClicked), disabled)("Reset script + clear calls")
      else button(onClick(Msg.ResetClicked))("Reset script + clear calls"),
      if isPending then button(onClick(Msg.ClearClicked), disabled)("Clear calls only")
      else button(onClick(Msg.ClearClicked))("Clear calls only"),
      // Refresh is deliberately not part of ActionState -- it isn't
      // destructive the way Reset/Clear are, just a plain guard in
      // update against a redundant fetch already covers the double-
      // click case without needing the full Pending/Succeeded/Failed
      // machinery.
      if model.refreshing then button(onClick(Msg.RefreshClicked), disabled)("Refresh")
      else button(onClick(Msg.RefreshClicked))("Refresh"),
      (model.lastRefreshedAt match
        case Some(t) => div(`class` := "last-refreshed")(s"Updated $t")
        case None    => div()
      ),
      model.actionState match
        case ActionState.Idle                  => div()
        case ActionState.Pending(Action.Reset)  => div(`class` := "action-status")("Resetting...")
        case ActionState.Pending(Action.Clear)  => div(`class` := "action-status")("Clearing...")
        case ActionState.Succeeded(msg)         => div(`class` := "action-status action-status-success")(msg)
        case ActionState.Failed(msg)            => div(`class` := "action-status action-status-error")(msg)
    )

  private def contentView(model: Model): Html[Msg] =
    if model.loading then div(`class` := "status-message")("Loading calls...")
    else if model.calls.isEmpty then
      // Nothing to show yet at all -- this is the only case where an
      // error genuinely replaces the whole view, since there's no
      // stale data underneath it to preserve.
      model.error match
        case Some(err) => div(`class` := "status-message status-message-error")(s"Fetch failed: $err")
        case None =>
          div(`class` := "status-message")(
            "No calls recorded. Send a request to an OpenAI- or Anthropic-compatible endpoint to populate the journal."
          )
    else
      // We already have calls (possibly from a while ago) -- a failed
      // refresh becomes a warning banner on top of the still-visible
      // table, not a replacement for it. The actual fix for "retain
      // the previous journal when a refresh fails".
      val staleWarning: Html[Msg] = model.error match
        case Some(err) => div(`class` := "stale-warning")(s"Could not refresh: $err -- showing last known data.")
        case None      => div()
      val filtered = model.calls.filter(matchesFilters(_, model))
      val selected = model.selectedSequence.flatMap(seq => filtered.find(_.sequence == seq))
      // filtered.isEmpty is genuinely different from
      // model.calls.isEmpty above -- "the journal has nothing"
      // vs "the journal has data, the current filter just hides
      // all of it" are different situations, and a reader
      // switching filters needs to be able to tell them apart.
      val tableOrEmptyNote: Html[Msg] =
        if filtered.isEmpty then div(`class` := "status-message")("No calls match the current filters.")
        else callsTable(filtered, model.selectedSequence)
      val children: List[Html[Msg]] =
        staleWarning :: filterPanel(model) :: tableOrEmptyNote :: selected.map(detailView).toList
      div()(children*)

  // Button-toggle filters, not <select> dropdowns -- select/option and
  // how their change events surface a value are genuinely unverified
  // in this Tyrian setup (unlike table/h3/pre/style, which all came
  // from real confirmed examples), and this step already introduces
  // enough new Model/Msg surface without stacking an unconfirmed UI
  // pattern on top. Functionally equivalent; visual polish to match
  // the mockup's dropdowns is a separate, later styling pass.
  private def filterPanel(model: Model): Html[Msg] =
    div(`class` := "filter-panel")(
      div(`class` := "filter-label")("Provider:"),
      filterToggle("All", model.providerFilter.isEmpty, Msg.SetProviderFilter(None)),
      filterToggle("OpenAI", model.providerFilter.contains("openai"), Msg.SetProviderFilter(Some("openai"))),
      filterToggle("Anthropic", model.providerFilter.contains("anthropic"), Msg.SetProviderFilter(Some("anthropic"))),
      div(`class` := "filter-label")("Outcome:"),
      filterToggle("All", model.outcomeFilter.isEmpty, Msg.SetOutcomeFilter(None)),
      filterToggle("Responded", model.outcomeFilter.contains("responded"), Msg.SetOutcomeFilter(Some("responded"))),
      filterToggle("Rejected", model.outcomeFilter.contains("rejected"), Msg.SetOutcomeFilter(Some("rejected"))),
      filterToggle("Failed", model.outcomeFilter.contains("failed"), Msg.SetOutcomeFilter(Some("failed"))),
      filterToggle("Cancelled", model.outcomeFilter.contains("cancelled"), Msg.SetOutcomeFilter(Some("cancelled"))),
      // Unicode checkbox glyphs on the existing, confirmed button
      // pattern, not a real <input type="checkbox"> -- that's another
      // genuinely unverified Tyrian pattern (checked := ... behavior
      // specifically), and this achieves the review's actual point
      // (more recognizable than "On"/"Off" text) without introducing
      // it.
      button(onClick(Msg.ToggleStreamedOnly), `class` := "filter-toggle")(
        if model.streamedOnly then "☑ Streamed calls only" else "☐ Streamed calls only"
      ),
      // onInput confirmed working from Tyrian's own http4s-dom
      // networking example (input(placeholder := "...", onInput(s =>
      // Msg.UpdateRepo(s)))) -- not a new unverified pattern the way
      // <select> would have been. Deliberately uncontrolled (no
      // value := model.modelSearch binding), matching that same
      // reference example -- a controlled input re-rendering on every
      // keystroke is a common source of cursor-jumping bugs in Elm-
      // style frameworks, and the reference example didn't need one
      // either.
      input(placeholder := "Search model...", onInput(s => Msg.SetModelSearch(s)))
    )

  private def filterToggle(label: String, active: Boolean, msg: Msg): Html[Msg] =
    val activeClass = if active then "filter-toggle filter-toggle-active" else "filter-toggle"
    button(onClick(msg), `class` := activeClass)(label)

  private def matchesFilters(call: CapturedCall, model: Model): Boolean =
    val providerOk = model.providerFilter.forall(_ == call.provider)
    val outcomeOk  = model.outcomeFilter.forall(_ == outcomeKey(call.outcome))
    val streamedOk = !model.streamedOnly || call.streamed
    val searchTerm = model.modelSearch.trim.toLowerCase
    val modelSearchOk = searchTerm.isEmpty || call.model.exists(_.toLowerCase.contains(searchTerm))
    providerOk && outcomeOk && streamedOk && modelSearchOk

  // Matches Dashboard.scala's own outcomeKey exactly (responded/
  // rejected/failed/cancelled) -- the same lowercase discriminator
  // values byOutcome's keys already use, confirmed there, not
  // reguessed here.
  private def outcomeKey(outcome: CallOutcome): String = outcome match
    case CallOutcome.Responded(_, _) => "responded"
    case CallOutcome.Rejected(_, _)  => "rejected"
    case CallOutcome.Failed(_)       => "failed"
    case CallOutcome.Cancelled(_)    => "cancelled"

  private def callsTable(calls: List[CapturedCall], selectedSequence: Option[Long]): Html[Msg] =
    div(`class` := "table-scroll")(
      table()(
        thead()(
          tr()(
            th("Seq"), th("Provider"), th("Model"), th("Outcome"), th("Streamed"), th("Duration (ms)")
          )
        ),
        // Newest first -- explicit descending sort by sequence, not
        // .reverse, so this doesn't depend on assuming the API always
        // returns calls in a particular order.
        tbody()(calls.sortBy(c => -c.sequence).map(call => callRow(call, selected = selectedSequence.contains(call.sequence)))*)
      )
    )

  private def callRow(call: CapturedCall, selected: Boolean): Html[Msg] =
    val rowClass = if selected then "row-selected" else ""
    tr(onClick(Msg.SelectCall(call.sequence)), `class` := rowClass)(
      // A real button, not just the row's own onClick -- <tr> isn't
      // keyboard-focusable or activatable by default (Tab won't reach
      // it, Enter/Space won't trigger it), a real accessibility gap
      // the review specifically flagged. <button> is natively
      // keyboard-operable with no extra JS needed, so wrapping just
      // the sequence number in one gives keyboard users a genuine way
      // to reach this row's selection -- mouse users keep the
      // existing "click anywhere in the row" behavior unchanged,
      // since the tr's own onClick is still there too. Styled to look
      // like plain text (seq-button class), not an obviously boxed
      // button, so it doesn't look out of place next to the other
      // cells.
      td(button(onClick(Msg.SelectCall(call.sequence)), `class` := "seq-button")(call.sequence.toString)),
      td(displayProvider(call.provider)),
      td(call.model.getOrElse("-")),
      td(`class` := outcomeClass(call.outcome))(renderOutcome(call.outcome)),
      td(if call.streamed then "yes" else "no"),
      td(call.durationMillis.toString)
    )

  // "openai"/"anthropic" are the wire-level provider values (matching
  // the lowercase discriminators used throughout DashboardSummary and
  // the backend generally) -- proper display capitalization for the
  // table specifically, not a wire-format change. Falls back to a
  // simple first-letter capitalization for anything unrecognized,
  // rather than assuming only these two providers will ever exist.
  private def displayProvider(provider: String): String = provider match
    case "openai"    => "OpenAI"
    case "anthropic" => "Anthropic"
    case other       => other.headOption.map(_.toUpper.toString).getOrElse("") + other.drop(1)

  private def outcomeClass(outcome: CallOutcome): String = outcome match
    case CallOutcome.Responded(_, _) => "outcome-responded"
    case CallOutcome.Rejected(_, _)  => "outcome-rejected"
    case CallOutcome.Failed(_)       => "outcome-failed"
    case CallOutcome.Cancelled(_)    => "outcome-cancelled"

  // Compact, table-row-friendly -- no message text here, since a long
  // error message would make rows extremely tall. renderOutcomeDetail
  // (used in the detail pane, not here) is where the full message
  // lives; this one is deliberately just enough to scan a whole table
  // at a glance. Kept the type names as they actually are in
  // CallOutcome (Responded, not "Completed") for consistency with the
  // rest of the codebase -- worth reconsidering if "Completed" reads
  // better to someone who doesn't already know llmsim's internals.
  private def renderOutcome(outcome: CallOutcome): String = outcome match
    case CallOutcome.Responded(status, _) => s"Responded ($status)"
    case CallOutcome.Rejected(status, _)  => s"Rejected ($status)"
    case CallOutcome.Failed(_)            => "Failed"
    case CallOutcome.Cancelled(_)         => "Cancelled"

  private def detailView(call: CapturedCall): Html[Msg] =
    div()(
      h3(s"Call #${call.sequence}"),
      div(s"Provider: ${displayProvider(call.provider)}"),
      div(s"Model: ${call.model.getOrElse("-")}"),
      div(s"Step index: ${call.stepIndex.map(_.toString).getOrElse("-")}"),
      div(s"Received: ${formatEpochMillis(call.receivedAtEpochMillis)} (${call.receivedAtEpochMillis})"),
      div(s"Completed: ${formatEpochMillis(call.completedAtEpochMillis)} (${call.completedAtEpochMillis})"),
      div(s"Duration: ${call.durationMillis}ms"),
      div(s"Streamed: ${call.streamed}"),
      h4("Messages"),
      div()(call.messages.map(m => div(s"[${m.role}] ${m.content}"))*),
      h4("Raw request"),
      pre(call.rawRequest.spaces2),
      h4("Outcome"),
      pre(renderOutcomeDetail(call.outcome))
    )

  // js.Date, not java.time -- standard Scala.js library facade over
  // the browser's native Date, confirmed from Scala.js's own API docs
  // before use (new Date(value: Double), .toLocaleString()) rather
  // than assumed. Local time, not UTC, deliberately -- matches what
  // someone actually reading this screen expects to see, with the
  // exact epoch value kept alongside for anyone who needs the precise
  // technical value.
  private def formatEpochMillis(epochMillis: Long): String =
    new js.Date(epochMillis.toDouble).toLocaleString()

  // new js.Date() with no arguments -- a documented, standard overload
  // representing the current moment, same confirmed facade as
  // formatEpochMillis above, just without an explicit millis value.
  private def nowText: String =
    new js.Date().toLocaleString()

  private def renderOutcomeDetail(outcome: CallOutcome): String = outcome match
    case CallOutcome.Responded(status, body)   => s"Responded ($status)\n${body.spaces2}"
    case CallOutcome.Rejected(status, message) => s"Rejected ($status): $message"
    case CallOutcome.Failed(message)           => s"Failed: $message"
    case CallOutcome.Cancelled(message)        => s"Cancelled: $message"

  private def fetchCalls: Cmd[IO, Msg] =
    val client = FetchClientBuilder[IO].create
    val fetch: IO[Msg] =
      client
        .expect[List[CapturedCall]]("/_llmsim/calls")
        .attempt
        .map {
          case Right(calls) => Msg.CallsLoaded(calls)
          case Left(err)    => Msg.FetchError(err.getMessage)
        }
    Cmd.Run(fetch)(identity)

  // Same shape as fetchCalls -- .expect[A](url), already confirmed
  // working, just decoding DashboardSummary instead of List[CapturedCall].
  private def fetchDashboard: Cmd[IO, Msg] =
    val client = FetchClientBuilder[IO].create
    val fetch: IO[Msg] =
      client
        .expect[DashboardSummary]("/_llmsim/dashboard")
        .attempt
        .map {
          case Right(summary) => Msg.DashboardLoaded(summary)
          case Left(err)       => Msg.DashboardFetchError(err.getMessage)
        }
    Cmd.Run(fetch)(identity)

  private def resetSimulator: Cmd[IO, Msg] =
    runAction(Method.POST, "/_llmsim/reset", "Reset")

  private def clearCalls: Cmd[IO, Msg] =
    runAction(Method.DELETE, "/_llmsim/calls", "Clear")

  private def runAction(method: Method, url: String, actionLabel: String): Cmd[IO, Msg] =
    val client = FetchClientBuilder[IO].create
    val request = Request[IO](method, Uri.unsafeFromString(url))
    val action: IO[Msg] =
      client
        .successful(request)
        .attempt
        .map {
          case Right(true)  => Msg.ActionSucceeded(s"$actionLabel complete.")
          case Right(false) => Msg.ActionFailed(s"$actionLabel failed: non-2xx response")
          case Left(err)    => Msg.ActionFailed(s"$actionLabel failed: ${err.getMessage}")
        }
    Cmd.Run(action)(identity)

final case class Model(
    calls: List[CapturedCall],
    error: Option[String],
    loading: Boolean,
    dashboard: Option[DashboardSummary],
    dashboardError: Option[String],
    selectedSequence: Option[Long],
    actionState: ActionState,
    // True only while an explicit Refresh is in flight -- separate
    // from `loading` (the very first fetch), so the UI can say
    // "Refreshing..." rather than reusing the initial-load message.
    refreshing: Boolean,
    // Human-readable, set on any successful calls or dashboard fetch
    // (whichever completes first) -- a plain timestamp of the last
    // moment either side of the console's data was confirmed current.
    lastRefreshedAt: Option[String],
    // None means "no filter" (show everything) for both -- not an
    // empty string or a sentinel value, so "not filtering" and
    // "filtering by nothing" can't be confused.
    providerFilter: Option[String],
    outcomeFilter: Option[String],
    streamedOnly: Boolean,
    // Empty string means "no filter", not None -- unlike the
    // provider/outcome toggle filters, this one's driven by a text
    // input, which naturally has "" as its own genuine empty state,
    // no need for a separate Option wrapper on top of it.
    modelSearch: String
)

enum Action:
  case Reset
  case Clear

// Replaces the earlier actionPending: Boolean / actionStatus:
// Option[String] pair -- that combination technically allowed
// contradictory states (pending AND holding a leftover status message
// from the last run, simultaneously) that never should have been
// representable in the first place. Same "make illegal states
// unrepresentable" reasoning that motivated choosing Tyrian over a
// more ad-hoc frontend approach to begin with.
enum ActionState:
  case Idle
  case Pending(action: Action)
  case Succeeded(message: String)
  case Failed(message: String)

enum Msg:
  case CallsLoaded(calls: List[CapturedCall])
  case FetchError(message: String)
  case DashboardLoaded(summary: DashboardSummary)
  case DashboardFetchError(message: String)
  case SelectCall(sequence: Long)
  case ResetClicked
  case RefreshClicked
  case ClearClicked
  case ActionSucceeded(message: String)
  case ActionFailed(message: String)
  case SetProviderFilter(provider: Option[String])
  case SetOutcomeFilter(outcome: Option[String])
  case ToggleStreamedOnly
  case SetModelSearch(text: String)
  case NoOp
