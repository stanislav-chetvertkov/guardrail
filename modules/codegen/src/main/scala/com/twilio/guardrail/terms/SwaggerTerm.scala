package com.twilio.guardrail
package terms

import java.util

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
import io.swagger.v3.oas.models.parameters.{ Parameter, RequestBody }
import io.swagger.v3.oas.models.responses.ApiResponse

import scala.util.Try

case class RouteMeta(path: String, method: HttpMethod, operation: Operation) {

  /** Temporary hack method to adapt to open-api v3 spec */
  private def extractParamsFromRequestBody(requestBody: RequestBody, contentType: String) =
    Try {
      requestBody.getContent.get(contentType).getSchema.getProperties.asScala.toList.map {
        case (name, schema) =>
          val p = new Parameter

          if (schema.getFormat == "binary") {
            schema.setType("file")
            schema.setFormat(null)
          }

          p.setName(name)
          p.setIn("formData")
          p.setSchema(schema)
          p.setExtensions(new util.HashMap[String, Object]())
          p
      }
    }.toOption.getOrElse(List.empty)

  private val parameters = { //option of list is a bad signature
    val p = Option(operation.getParameters)
      .map(_.asScala.toList)

    //fixme can have formData parameters defined with empty operation.getParameters
    //fixme try using monoid of option/list
    p.map(
      _ ++
        extractParamsFromRequestBody(operation.getRequestBody, "multipart/form-data") ++
        extractParamsFromRequestBody(operation.getRequestBody, "application/x-www-form-urlencoded")
    )
  }

  def getParameters[L <: LA, F[_]](
      protocolElems: List[StrictProtocolElems[L]]
  )(implicit Fw: FrameworkTerms[L, F], Sc: ScalaTerms[L, F], Sw: SwaggerTerms[L, F]): Free[F, ScalaParameters[L]] = {
    if (operation.getOperationId == "uploadFile") {
      print("here")
    }

    parameters
      .fold(Free.pure[F, List[ScalaParameter[L]]](List.empty))(ScalaParameter.fromParameters(protocolElems))
      .map(new ScalaParameters[L](_))
  }
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
