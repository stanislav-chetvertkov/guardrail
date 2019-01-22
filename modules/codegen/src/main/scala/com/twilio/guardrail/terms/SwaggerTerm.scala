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

import scala.collection.immutable
import scala.util.Try

case class RouteMeta(path: String, method: HttpMethod, operation: Operation) {

  import Common._

  private def extractRefParamFromRequestBody(requestBody: RequestBody) =
    Try {
      requestBody.getContent.values().asScala.toList.flatMap { mt =>
        Option(mt.getSchema.get$ref())
          .map { ref =>
            val p = new Parameter

            val schema = mt.getSchema

            if (schema.getFormat == "binary") {
              schema.setType("file")
              schema.setFormat(null)
            }

            p.setIn("body")
            p.setName("body")
            p.setSchema(schema)
            p.set$ref(ref)

            p.setRequired(requestBody.getRequired)
            p.setExtensions(Option(schema.getExtensions).getOrElse(new util.HashMap[String, Object]()))
            p
          }
      }
    }.map(_.headOption).toOption.flatten

  /** Temporary hack method to adapt to open-api v3 spec */
  private def extractParamsFromRequestBody(requestBody: RequestBody): List[Parameter] =
    Try {
      requestBody.getContent.values().asScala.toList.flatMap { mt =>
        val requiredFields = mt.requiredFields()
        Option(mt.getSchema.getProperties).map(_.asScala.toList).getOrElse(List.empty).map {
          case (name, schema) =>
            val p = new Parameter

            if (schema.getFormat == "binary") {
              schema.setType("file")
              schema.setFormat(null)
            }

            p.setName(name)
            p.setIn("formData")
            p.setSchema(schema)

//            assert(operation.getRequestBody.getRequired == requiredFields.contains(name))

            p.setRequired(requiredFields.contains(name) || requestBody.getRequired)
            p.setExtensions(Option(schema.getExtensions).getOrElse(new util.HashMap[String, Object]()))
            p
        }

      }
    }.toOption.getOrElse(List.empty)

  private val parameters: Option[List[Parameter]] = { //option of list is a bad signature
    val p = Option(operation.getParameters)
      .map(_.asScala.toList)
      .getOrElse(List.empty)

    val params = Option((extractRefParamFromRequestBody(operation.getRequestBody) ++ p ++ extractParamsFromRequestBody(operation.getRequestBody)).toList)
    params
  }

  def getParameters[L <: LA, F[_]](
      protocolElems: List[StrictProtocolElems[L]]
  )(implicit Fw: FrameworkTerms[L, F], Sc: ScalaTerms[L, F], Sw: SwaggerTerms[L, F]): Free[F, ScalaParameters[L]] = {
    val x = parameters
      .fold(Free.pure[F, List[ScalaParameter[L]]](List.empty))(ScalaParameter.fromParameters(protocolElems))

    x.map(new ScalaParameters[L](_))
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
