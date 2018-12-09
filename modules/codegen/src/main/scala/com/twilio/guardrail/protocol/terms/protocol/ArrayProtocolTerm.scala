package com.twilio.guardrail.protocol.terms.protocol

import com.twilio.guardrail.generators.GeneratorSettings
import com.twilio.guardrail.languages.LA
import io.swagger.v3.oas.models.media.ArraySchema

sealed trait ArrayProtocolTerm[L <: LA, T]
case class ExtractArrayType[L <: LA](arr: ArraySchema, concreteTypes: List[PropMeta]) extends ArrayProtocolTerm[L, L#Type]
