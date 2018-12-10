package com.twilio.guardrail.protocol.terms.protocol

import cats.InjectK
import cats.free.Free
import com.twilio.guardrail.languages.LA
import com.twilio.guardrail.{ ProtocolElems, StrictProtocolElems }
import io.swagger.v3.oas.models.media.Schema

class ProtocolSupportTerms[L <: LA, F[_]](implicit I: InjectK[ProtocolSupportTerm[L, ?], F]) {
  def extractConcreteTypes(models: List[(String, Schema[_])]): Free[F, List[PropMeta]] =
    Free.inject[ProtocolSupportTerm[L, ?], F](ExtractConcreteTypes(models))
  def protocolImports(): Free[F, List[L#Import]] =
    Free.inject[ProtocolSupportTerm[L, ?], F](ProtocolImports())
  def packageObjectImports(): Free[F, List[L#Import]] =
    Free.inject[ProtocolSupportTerm[L, ?], F](PackageObjectImports())
  def packageObjectContents(): Free[F, List[L#ValueDefinition]] =
    Free.inject[ProtocolSupportTerm[L, ?], F](PackageObjectContents())
  def resolveProtocolElems(elems: List[ProtocolElems[L]]): Free[F, List[StrictProtocolElems[L]]] =
    Free.inject[ProtocolSupportTerm[L, ?], F](ResolveProtocolElems(elems))
}
object ProtocolSupportTerms {
  implicit def protocolSupportTerms[L <: LA, F[_]](implicit I: InjectK[ProtocolSupportTerm[L, ?], F]): ProtocolSupportTerms[L, F] =
    new ProtocolSupportTerms[L, F]
}
