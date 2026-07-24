package com.alai.llmsim.console

import cats.effect.IO
import tyrian.classic.Html.*
import tyrian.classic.*
import org.http4s.{Method, Request, Uri}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dom.FetchClientBuilder
import com.alai.llmsim.{CallOutcome, CapturedCall}

import scala.scalajs.js.annotation.*

// Adds Reset and Clear -- the last real behavior piece from the
// vertical slice plan (docs/console-framework-decision.md). Pending/
// success/failure all represented: actionPending disables nothing yet
// (deliberately -- see below) but shows "Working...", actionStatus
// holds whichever of the two outcomes happened last.
//
// Deliberately did NOT reach for `disabled := model.actionPending` on
// the buttons -- that's a new, unconfirmed attribute pattern, and
// this step already has one new one (POST/DELETE via a raw Request,
// not the .expect[A](url) convenience method used everywhere else so
// far). Keeping new unverified surface area to one thing per step,
// same discipline as every step before this one.
@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model]:

  def router: Location => Msg = Routing.none(Msg.NoOp)

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model(calls = Nil, error = None, loading = true, selectedSequence = None,
      actionPending = false, actionStatus = None), fetchCalls)

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    case Msg.CallsLoaded(calls) => (model.copy(calls = calls, error = None, loading = false), Cmd.None)
    case Msg.FetchError(err)    => (model.copy(error = Some(err), loading = false), Cmd.None)
    case Msg.SelectCall(seq)    => (model.copy(selectedSequence = Some(seq)), Cmd.None)
    case Msg.ResetClicked       => (model.copy(actionPending = true, actionStatus = None), resetSimulator)
    case Msg.ClearClicked       => (model.copy(actionPending = true, actionStatus = None), clearCalls)
    case Msg.ActionSucceeded(msg) =>
      // Re-fetch after a successful reset/clear -- the journal just
      // changed, and the previously-selected call may no longer exist,
      // so it's cleared rather than left pointing at stale data.
      (model.copy(actionPending = false, actionStatus = Some(msg), selectedSequence = None), fetchCalls)
    case Msg.ActionFailed(msg) => (model.copy(actionPending = false, actionStatus = Some(msg)), Cmd.None)
    case Msg.NoOp                => (model, Cmd.None)

  def view(model: Model): Html[Msg] =
    div()(
      controlPanel(model),
      contentView(model)
    )

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None

  private def controlPanel(model: Model): Html[Msg] =
    div()(
      button(onClick(Msg.ResetClicked))("Reset"),
      button(onClick(Msg.ClearClicked))("Clear"),
      if model.actionPending then div("Working...")
      else
        model.actionStatus match
          case Some(msg) => div(msg)
          case None      => div()
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

  // Same shape as Tyrian's own confirmed http4s-dom networking example
  // (client.expect[...].attempt.map { case Right/Left => ... }), just
  // targeting llmsim's real management API instead of GitHub's.
  private def fetchCalls: Cmd[IO, Msg] =
    val client = FetchClientBuilder[IO].create
    val fetch: IO[Msg] =
      client
        .expect[List[CapturedCall]]("http://localhost:8089/_llmsim/calls")
        .attempt
        .map {
          case Right(calls) => Msg.CallsLoaded(calls)
          case Left(err)    => Msg.FetchError(err.getMessage)
        }
    Cmd.Run(fetch)(identity)

  private def resetSimulator: Cmd[IO, Msg] =
    runAction(Method.POST, "http://localhost:8089/_llmsim/reset", "Reset")

  private def clearCalls: Cmd[IO, Msg] =
    runAction(Method.DELETE, "http://localhost:8089/_llmsim/calls", "Clear")

  // New this step: a raw Request[IO] with an explicit method, rather
  // than the .expect[A](url) convenience method fetchCalls uses --
  // POST/DELETE have no body to decode, just success or failure.
  // client.successful(req): IO[Boolean] is a standard http4s Client
  // method (true for a 2xx response), and http4s-dom's FetchClientBuilder
  // produces an ordinary org.http4s.client.Client[IO], so the same
  // Client API that's well-established on llmsim's own JVM side should
  // apply here too -- reasonable confidence, not a direct confirmation
  // the way fetchCalls's exact shape was (that one came from a real,
  // cloned Tyrian example; this one is inferred from standard http4s
  // API knowledge instead).
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
    selectedSequence: Option[Long],
    actionPending: Boolean,
    actionStatus: Option[String]
)

enum Msg:
  case CallsLoaded(calls: List[CapturedCall])
  case FetchError(message: String)
  case SelectCall(sequence: Long)
  case ResetClicked
  case ClearClicked
  case ActionSucceeded(message: String)
  case ActionFailed(message: String)
  case NoOp
