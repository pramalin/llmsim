package com.alai.llmsim

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/** A message from the request, in a shape that doesn't require knowing
  * whether it came in as OpenAI's flat `content: String` or Anthropic's
  * `content: List[ContentBlock]` -- both get normalized to this.
  *
  * Lives in `common`, not `CallJournal.scala` -- step 5 of the console
  * reorganization, same pattern as CapturedHeader (step 3): same
  * package as before, so nothing that already references
  * CapturedMessage needs any import changed.
  */
final case class CapturedMessage(role: String, content: String)
object CapturedMessage {
  implicit val codec: Codec[CapturedMessage] = deriveCodec
}
