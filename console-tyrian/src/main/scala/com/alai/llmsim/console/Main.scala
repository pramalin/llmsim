package com.alai.llmsim.console

import cats.effect.IO
import tyrian.classic.Html.*
import tyrian.classic.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dom.FetchClientBuilder
import com.alai.llmsim.CapturedCall

import scala.scalajs.js.annotation.*

// Step 9: the first real fetch against llmsim's own API, and the
// first genuine exercise of the CORS question -- llmsim (port 8089)
// and this Vite dev server (port 5173) are different origins, so this
// is either going to work cleanly or surface a real CORS error, not
// something to guess about in advance.
//
// GET /_llmsim/calls specifically, not /_llmsim/dashboard -- reuses
// CapturedCall, already in common and already proven working, so this
// step isolates the new risk (the fetch itself) from anything already
// tested, rather than combining it with moving another type over.
@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model]:

  def router: Location => Msg = Routing.none(Msg.NoOp)

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model(status = "fetching..."), fetchCalls)

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    case Msg.CallsLoaded(calls) => (model.copy(status = s"loaded ${calls.size} call(s): $calls"), Cmd.None)
    case Msg.FetchError(err)    => (model.copy(status = s"fetch failed: $err"), Cmd.None)
    case Msg.NoOp                => (model, Cmd.None)

  def view(model: Model): Html[Msg] =
    div(model.status)

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None

  // Same shape as Tyrian's own confirmed http4s-dom networking example
  // (client.expect[...].attempt.map { case Right/Left => ... }), just
  // targeting llmsim's real management API instead of GitHub's.
  // Hardcoded absolute URL, deliberately, for this dev-only step --
  // Vite (5173) and llmsim (8089) are different ports during
  // development, so a relative URL wouldn't reach llmsim at all.
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

final case class Model(status: String)

enum Msg:
  case CallsLoaded(calls: List[CapturedCall])
  case FetchError(message: String)
  case NoOp
