package com.twilio.guardrail.protocol.terms.protocol

import com.twilio.guardrail.{ ProtocolParameter, SuperClass }
import com.twilio.guardrail.generators.GeneratorSettings
import com.twilio.guardrail.languages.LA
import io.swagger.v3.oas.models.media.Schema

sealed trait ModelProtocolTerm[L <: LA, T]
case class ExtractProperties[L <: LA](swagger: Schema[_]) extends ModelProtocolTerm[L, List[(String, Schema[_])]]
case class TransformProperty[L <: LA](clsName: String, name: String, prop: Schema[_], needCamelSnakeConversion: Boolean, concreteTypes: List[PropMeta])
    extends ModelProtocolTerm[L, ProtocolParameter[L]]
case class RenderDTOClass[L <: LA](clsName: String, terms: List[L#MethodParameter], parents: List[SuperClass[L]] = Nil)
    extends ModelProtocolTerm[L, L#ClassDefinition]
case class EncodeModel[L <: LA](clsName: String, needCamelSnakeConversion: Boolean, params: List[ProtocolParameter[L]], parents: List[SuperClass[L]] = Nil)
    extends ModelProtocolTerm[L, L#Statement]
case class DecodeModel[L <: LA](clsName: String, needCamelSnakeConversion: Boolean, params: List[ProtocolParameter[L]], parents: List[SuperClass[L]] = Nil)
    extends ModelProtocolTerm[L, L#Statement]
case class RenderDTOCompanion[L <: LA](clsName: String, deps: List[L#TermName], encoder: L#Statement, decoder: L#Statement)
    extends ModelProtocolTerm[L, L#ObjectDefinition]
