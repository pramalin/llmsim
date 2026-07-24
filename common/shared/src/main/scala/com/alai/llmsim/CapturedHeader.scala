package com.alai.llmsim

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/** A response header llmsim actually emitted, most commonly from a
  * script's `headers` field (rate-limit headers, `retry-after`, etc.).
  * `Vector[CapturedHeader]` rather than `Map[String, String]`
  * deliberately -- captured wire data should preserve duplicate names,
  * original casing, and original order, none of which a Map can. The
  * public script DSL still accepts a Map for convenience; this is only
  * what got recorded after the fact.
  *
  * Lives in `common`, not `CallJournal.scala` -- step 3 of the console
  * reorganization (github.com/rockthejvm/typelevel-rite-of-passage's
  * common/app/server split), one real type moved at a time. Same
  * package as before deliberately: nothing that already references
  * CapturedHeader (CallJournal.scala, Dashboard.scala,
  * ManagementRoutes.scala, every test that decodes one) needs any
  * import changed, just resolving it from a different compiled
  * artifact now.
  */
final case class CapturedHeader(name: String, value: String)
object CapturedHeader {
  implicit val codec: Codec[CapturedHeader] = deriveCodec
}
