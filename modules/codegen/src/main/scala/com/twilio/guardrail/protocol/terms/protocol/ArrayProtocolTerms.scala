package com.twilio.guardrail.protocol.terms.protocol

import cats.InjectK
import cats.free.Free
import com.twilio.guardrail.generators.GeneratorSettings
import com.twilio.guardrail.languages.LA
import io.swagger.v3.oas.models.media.ArraySchema

class ArrayProtocolTerms[L <: LA, F[_]](implicit I: InjectK[ArrayProtocolTerm[L, ?], F]) {
  def extractArrayType(arr: ArraySchema, concreteTypes: List[PropMeta]): Free[F, L#Type] =
    Free.inject[ArrayProtocolTerm[L, ?], F](ExtractArrayType[L](arr, concreteTypes))
}

object ArrayProtocolTerms {
  implicit def arrayProtocolTerms[L <: LA, F[_]](implicit I: InjectK[ArrayProtocolTerm[L, ?], F]): ArrayProtocolTerms[L, F] =
    new ArrayProtocolTerms[L, F]
}
