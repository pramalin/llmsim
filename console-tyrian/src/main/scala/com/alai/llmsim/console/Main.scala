package com.alai.llmsim.console

import cats.effect.IO
import tyrian.classic.Html.*
import tyrian.classic.*
import org.http4s.{Method, Request, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dom.FetchClientBuilder
import com.alai.llmsim.{CallOutcome, CapturedCall, DashboardSummary}

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
        selectedSequence = None, actionState = ActionState.Idle),
      Cmd.Batch(List(fetchCalls, fetchDashboard))
    )

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    case Msg.CallsLoaded(calls) => (model.copy(calls = calls, error = None, loading = false), Cmd.None)
    case Msg.FetchError(err)    => (model.copy(error = Some(err), loading = false), Cmd.None)
    case Msg.DashboardLoaded(summary) => (model.copy(dashboard = Some(summary), dashboardError = None), Cmd.None)
    case Msg.DashboardFetchError(err) => (model.copy(dashboardError = Some(err)), Cmd.None)
    case Msg.SelectCall(seq)    => (model.copy(selectedSequence = Some(seq)), Cmd.None)
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
    case Msg.NoOp                => (model, Cmd.None)

  def view(model: Model): Html[Msg] =
    div()(
      scriptStatusView(model),
      controlPanel(model),
      contentView(model)
    )

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None

  private def scriptStatusView(model: Model): Html[Msg] =
    model.dashboardError match
      case Some(err) => div(s"Script status unavailable: $err")
      case None =>
        model.dashboard match
          case None => div("Script: loading...")
          case Some(d) =>
            val exhaustedNote = if d.script.exhausted then " (EXHAUSTED)" else ""
            div(
              s"Script: ${d.script.name.getOrElse("-")}" +
                s" | Step ${d.script.nextStepIndex.map(_.toString).getOrElse("-")} of ${d.script.totalSteps}" +
                s" | On overrun: ${d.script.onOverrun}$exhaustedNote"
            )

  private def controlPanel(model: Model): Html[Msg] =
    val isPending = model.actionState match
      case ActionState.Pending(_) => true
      case _                      => false
    div()(
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
      model.actionState match
        case ActionState.Idle                  => div()
        case ActionState.Pending(Action.Reset)  => div("Resetting...")
        case ActionState.Pending(Action.Clear)  => div("Clearing...")
        case ActionState.Succeeded(msg)         => div(msg)
        case ActionState.Failed(msg)            => div(msg)
    )

  private def contentView(model: Model): Html[Msg] =
    if model.loading then div("loading...")
    else
      model.error match
        case Some(err) => div(s"fetch failed: $err")
        case None =>
          if model.calls.isEmpty then div("No calls yet.")
          else
            val selected = model.selectedSequence.flatMap(seq => model.calls.find(_.sequence == seq))
            val children: List[Html[Msg]] = callsTable(model.calls, model.selectedSequence) :: selected.map(detailView).toList
            div()(children*)

  private def callsTable(calls: List[CapturedCall], selectedSequence: Option[Long]): Html[Msg] =
    table()(
      thead()(
        tr()(
          th("Seq"), th("Provider"), th("Model"), th("Outcome"), th("Streamed"), th("Duration (ms)")
        )
      ),
      tbody()(calls.map(call => callRow(call, selected = selectedSequence.contains(call.sequence)))*)
    )

  private def callRow(call: CapturedCall, selected: Boolean): Html[Msg] =
    val rowStyle = if selected then "cursor: pointer; background-color: #eef4ff;" else "cursor: pointer;"
    tr(onClick(Msg.SelectCall(call.sequence)), style := rowStyle)(
      td(call.sequence.toString),
      td(call.provider),
      td(call.model.getOrElse("-")),
      td(renderOutcome(call.outcome)),
      td(if call.streamed then "yes" else "no"),
      td(call.durationMillis.toString)
    )

  private def renderOutcome(outcome: CallOutcome): String = outcome match
    case CallOutcome.Responded(status, _)      => s"Responded ($status)"
    case CallOutcome.Rejected(status, message) => s"Rejected ($status): $message"
    case CallOutcome.Failed(message)           => s"Failed: $message"
    case CallOutcome.Cancelled(message)        => s"Cancelled: $message"

  private def detailView(call: CapturedCall): Html[Msg] =
    div()(
      h3(s"Call #${call.sequence}"),
      div(s"Provider: ${call.provider}"),
      div(s"Model: ${call.model.getOrElse("-")}"),
      div(s"Step index: ${call.stepIndex.map(_.toString).getOrElse("-")}"),
      div(s"Received at (epoch ms): ${call.receivedAtEpochMillis}"),
      div(s"Completed at (epoch ms): ${call.completedAtEpochMillis}"),
      div(s"Duration: ${call.durationMillis}ms"),
      div(s"Streamed: ${call.streamed}"),
      h4("Messages"),
      div()(call.messages.map(m => div(s"[${m.role}] ${m.content}"))*),
      h4("Raw request"),
      pre(call.rawRequest.spaces2),
      h4("Outcome"),
      pre(renderOutcomeDetail(call.outcome))
    )

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
    actionState: ActionState
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
  case ClearClicked
  case ActionSucceeded(message: String)
  case ActionFailed(message: String)
  case NoOp
