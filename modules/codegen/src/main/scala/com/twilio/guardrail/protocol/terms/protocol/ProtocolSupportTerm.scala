package com.twilio.guardrail.protocol.terms.protocol

import com.twilio.guardrail.languages.{LA, ScalaLanguage}
import com.twilio.guardrail.{ProtocolElems, StrictProtocolElems}
import io.swagger.v3.oas.models.media.Schema

case class PropMeta(clsName: String, tpe: ScalaLanguage#Type)
sealed trait ProtocolSupportTerm[L <: LA, T]
case class ExtractConcreteTypes[L <: LA](models: List[(String, Schema[_])]) extends ProtocolSupportTerm[L, List[PropMeta]]
case class ProtocolImports[L <: LA]()                                   extends ProtocolSupportTerm[L, List[L#Import]]
case class PackageObjectImports[L <: LA]()                              extends ProtocolSupportTerm[L, List[L#Import]]
case class PackageObjectContents[L <: LA]()                             extends ProtocolSupportTerm[L, List[L#ValueDefinition]]
case class ResolveProtocolElems[L <: LA](elems: List[ProtocolElems[L]]) extends ProtocolSupportTerm[L, List[StrictProtocolElems[L]]]
