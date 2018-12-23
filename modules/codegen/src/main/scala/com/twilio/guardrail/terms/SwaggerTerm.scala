package com.twilio.guardrail
package terms

import cats.MonadError
import cats.free.Free
import cats.implicits._
import com.twilio.guardrail.generators.{ ScalaParameter, ScalaParameters }
import com.twilio.guardrail.languages.LA

import scala.collection.JavaConverters._
import com.twilio.guardrail.terms.framework.FrameworkTerms
import io.swagger.v3.oas.models.{ Operation, PathItem }
import io.swagger.v3.oas.models.PathItem.HttpMethod
import io.swagger.v3.oas.models.media.{ ArraySchema, ObjectSchema, Schema }
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse

case class RouteMeta(path: String, method: HttpMethod, operation: Operation) {
  private val parameters = {
    Option(operation.getParameters)
      .map(_.asScala.toList)
  }

  def getParameters[L <: LA, F[_]](
      protocolElems: List[StrictProtocolElems[L]]
  )(implicit Fw: FrameworkTerms[L, F], Sc: ScalaTerms[L, F], Sw: SwaggerTerms[L, F]): Free[F, ScalaParameters[L]] =
    parameters
      .fold(Free.pure[F, List[ScalaParameter[L]]](List.empty))(ScalaParameter.fromParameters(protocolElems))
      .map(new ScalaParameters[L](_))
}

sealed trait SwaggerTerm[L <: LA, T]
case class ExtractOperations[L <: LA](paths: List[(String, PathItem)])                     extends SwaggerTerm[L, List[RouteMeta]]
case class GetClassName[L <: LA](operation: Operation)                                     extends SwaggerTerm[L, List[String]]
case class GetParameterName[L <: LA](parameter: Parameter)                                 extends SwaggerTerm[L, String]
case class GetBodyParameterSchema[L <: LA](parameter: Parameter)                           extends SwaggerTerm[L, Schema[_]]
case class GetHeaderParameterType[L <: LA](parameter: Parameter)                           extends SwaggerTerm[L, String]
case class GetPathParameterType[L <: LA](parameter: Parameter)                             extends SwaggerTerm[L, String]
case class GetQueryParameterType[L <: LA](parameter: Parameter)                            extends SwaggerTerm[L, String]
case class GetCookieParameterType[L <: LA](parameter: Parameter)                           extends SwaggerTerm[L, String]
case class GetFormParameterType[L <: LA](parameter: Parameter)                             extends SwaggerTerm[L, String]
case class GetSerializableParameterType[L <: LA](parameter: Parameter)                     extends SwaggerTerm[L, String]
case class GetRefParameterRef[L <: LA](parameter: Parameter)                               extends SwaggerTerm[L, String]
case class FallbackParameterHandler[L <: LA](parameter: Parameter)                         extends SwaggerTerm[L, SwaggerUtil.ResolvedType[L]]
case class GetOperationId[L <: LA](operation: Operation)                                   extends SwaggerTerm[L, String]
case class GetResponses[L <: LA](operationId: String, operation: Operation)                extends SwaggerTerm[L, Map[String, ApiResponse]]
case class GetSimpleRef[L <: LA](ref: Schema[_])                                           extends SwaggerTerm[L, String]
case class GetSimpleRefP[L <: LA](ref: Schema[_])                                          extends SwaggerTerm[L, String]
case class GetItems[L <: LA](arr: ArraySchema)                                             extends SwaggerTerm[L, Schema[_]]
case class GetItemsP[L <: LA](arr: ArraySchema)                                            extends SwaggerTerm[L, Schema[_]] //fixme remove
case class GetType[L <: LA](model: Schema[_])                                              extends SwaggerTerm[L, String]
case class FallbackPropertyTypeHandler[L <: LA](prop: Schema[_])                           extends SwaggerTerm[L, L#Type]
case class ResolveType[L <: LA](name: String, protocolElems: List[StrictProtocolElems[L]]) extends SwaggerTerm[L, StrictProtocolElems[L]]
case class FallbackResolveElems[L <: LA](lazyElems: List[LazyProtocolElems[L]])            extends SwaggerTerm[L, List[StrictProtocolElems[L]]]
