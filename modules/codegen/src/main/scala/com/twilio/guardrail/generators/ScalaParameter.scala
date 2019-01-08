package com.twilio.guardrail
package generators

import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters._
import com.twilio.guardrail.extract.{ Default, ScalaFileHashAlgorithm, ScalaType }
import com.twilio.guardrail.languages.LA
import com.twilio.guardrail.languages.ScalaLanguage
import com.twilio.guardrail.terms.{ ScalaTerm, ScalaTerms, SwaggerTerm, SwaggerTerms }
import com.twilio.guardrail.terms.framework.{ FrameworkTerm, FrameworkTerms }
import java.util.Locale

import scala.meta._
import cats.MonadError
import cats.implicits._
import cats.arrow.FunctionK
import cats.free.Free
import cats.data.{ EitherK, EitherT }

case class RawParameterName private[generators] (value: String)
class ScalaParameters[L <: LA](val parameters: List[ScalaParameter[L]]) {
  val filterParamBy     = ScalaParameter.filterParams(parameters)
  val headerParams      = filterParamBy("header")
  val pathParams        = filterParamBy("path")
  val queryStringParams = filterParamBy("query")
  val bodyParams        = filterParamBy("body").headOption
  val formParams        = filterParamBy("formData")
}
class ScalaParameter[L <: LA] private[generators] (
    val in: Option[String],
    val param: L#MethodParameter,
    val paramName: L#TermName,
    val argName: RawParameterName,
    val argType: L#Type,
    val required: Boolean,
    val hashAlgorithm: Option[String],
    val isFile: Boolean
) {
  override def toString: String =
    s"ScalaParameter($in, $param, $paramName, $argName, $argType)"

  def withType(newArgType: L#Type): ScalaParameter[L] =
    new ScalaParameter[L](in, param, paramName, argName, newArgType, required, hashAlgorithm, isFile)
}
object ScalaParameter {
  def unapply[L <: LA](param: ScalaParameter[L]): Option[(Option[String], L#MethodParameter, L#TermName, RawParameterName, L#Type)] =
    Some((param.in, param.param, param.paramName, param.argName, param.argType))

  def fromParameter[L <: LA, F[_]](
      protocolElems: List[StrictProtocolElems[L]]
  )(implicit Fw: FrameworkTerms[L, F], Sc: ScalaTerms[L, F], Sw: SwaggerTerms[L, F]): Parameter => Free[F, ScalaParameter[L]] = { parameter =>
    import Fw._
    import Sc._
    import Sw._

    def toCamelCase(s: String): String = {
      val fromSnakeOrDashed =
        "[_-]([a-z])".r.replaceAllIn(s, m => m.group(1).toUpperCase(Locale.US))
      "^([A-Z])".r
        .replaceAllIn(fromSnakeOrDashed, m => m.group(1).toLowerCase(Locale.US))
    }

    def paramMeta(param: Parameter): Free[F, SwaggerUtil.ResolvedType[L]] = {
      def getDefault[U <: Parameter: Default.GetDefault](p: U): Free[F, Option[L#Term]] =
        Option(p.getSchema.getType)
          .flatTraverse[Free[F, ?], L#Term]({ _type =>
            val fmt = Option(p.getSchema.getFormat)
            (_type, fmt) match {
              case ("string", None) =>
                Default(p).extract[String].traverse(litString(_))
              case ("number", Some("float")) =>
                Default(p).extract[Float].traverse(litFloat(_))
              case ("number", Some("double")) =>
                Default(p).extract[Double].traverse(litDouble(_))
              case ("integer", Some("int32")) =>
                Default(p).extract[Int].traverse(litInt(_))
              case ("integer", Some("int64")) =>
                Default(p).extract[Long].traverse(litLong(_))
              case ("boolean", None) =>
                Default(p).extract[Boolean].traverse(litBoolean(_))
              case x => Free.pure(Option.empty[L#Term])
            }
          })

      param match {
        case x: Parameter if x.getIn == "body" =>
          getBodyParameterSchema(x).flatMap(x => SwaggerUtil.modelMetaType[L, F](x))

        case x: Parameter if x.getIn == "header" =>
          getHeaderParameterType(x).flatMap(
            tpeName =>
              (SwaggerUtil.typeName[L, F](tpeName, Option(x.getSchema.getFormat()), ScalaType(x)), getDefault(x)).mapN(SwaggerUtil.Resolved[L](_, None, _))
          )

        case x: Parameter if x.getIn == "path" =>
          getPathParameterType(x)
            .flatMap(
              tpeName =>
                (SwaggerUtil.typeName[L, F](tpeName, Option(x.getSchema.getFormat()), ScalaType(x)), getDefault(x)).mapN(SwaggerUtil.Resolved[L](_, None, _))
            )

        case x: Parameter if x.getIn == "query" =>
          getQueryParameterType(x)
            .flatMap(
              tpeName =>
                (SwaggerUtil.typeName[L, F](tpeName, Option(x.getSchema.getFormat()), ScalaType(x)), getDefault(x)).mapN(SwaggerUtil.Resolved[L](_, None, _))
            )

        case x: Parameter if x.getIn == "cookie" =>
          getCookieParameterType(x)
            .flatMap(
              tpeName =>
                (SwaggerUtil.typeName[L, F](tpeName, Option(x.getSchema.getFormat()), ScalaType(x)), getDefault(x)).mapN(SwaggerUtil.Resolved[L](_, None, _))
            )

//        case x: FormParameter =>
//          getFormParameterType(x)
//            .flatMap(
//              tpeName => (SwaggerUtil.typeName[L, F](tpeName, Option(x.getFormat()), ScalaType(x)), getDefault(x)).mapN(SwaggerUtil.Resolved[L](_, None, _))
//            )

        case r: Parameter if Option(r.get$ref()).isDefined =>
          getRefParameterRef(r)
            .map(SwaggerUtil.Deferred(_): SwaggerUtil.ResolvedType[L])

        case x =>
          fallbackParameterHandler(x)
      }
    }

    for {
      meta     <- paramMeta(parameter)
      resolved <- SwaggerUtil.ResolvedType.resolve[L, F](meta, protocolElems)
      SwaggerUtil.Resolved(paramType, _, baseDefaultValue) = resolved

      required = parameter.getRequired()
      declType <- if (!required) {
        liftOptionalType(paramType)
      } else {
        Free.pure[F, L#Type](paramType)
      }

      enumDefaultValue <- extractTypeName(paramType).flatMap(_.fold(baseDefaultValue.traverse(Free.pure[F, L#Term] _)) { tpe =>
        protocolElems
          .flatTraverse({
            case x @ EnumDefinition(_, _tpeName, _, _, _) =>
              for {
                areEqual <- typeNamesEqual(tpe, _tpeName)
              } yield if (areEqual) List(x) else List.empty[EnumDefinition[L]]
            case _ => Free.pure[F, List[EnumDefinition[L]]](List.empty)
          })
          .flatMap(_.headOption.fold[Free[F, Option[L#Term]]](baseDefaultValue.traverse(Free.pure _)) { x =>
            baseDefaultValue.traverse(lookupEnumDefaultValue(tpe, _, x.elems).flatMap(widenTermSelect))
          })
      })

      defaultValue <- if (!required) {
        (enumDefaultValue.traverse(liftOptionalTerm), emptyOptionalTerm().map(Option.apply _)).mapN(_.orElse(_))
      } else {
        Free.pure[F, Option[L#Term]](enumDefaultValue)
      }

      name <- getParameterName(parameter)

      paramName <- pureTermName(toCamelCase(name))
      param     <- pureMethodParameter(paramName, declType, defaultValue)

      ftpe       <- fileType(None)
      isFileType <- typesEqual(paramType, ftpe)
    } yield {
      new ScalaParameter[L](Option(parameter.getIn),
                            param,
                            paramName,
                            RawParameterName(name),
                            declType,
                            required,
                            ScalaFileHashAlgorithm(parameter),
                            isFileType)
    }
  }

  def fromParameters[L <: LA, F[_]](
      protocolElems: List[StrictProtocolElems[L]]
  )(implicit Fw: FrameworkTerms[L, F], Sc: ScalaTerms[L, F], Sw: SwaggerTerms[L, F]): List[Parameter] => Free[F, List[ScalaParameter[L]]] = { params =>
    import Sc._
    for {
      parameters <- params.traverse(fromParameter(protocolElems))
      counts     <- parameters.traverse(param => extractTermName(param.paramName)).map(_.groupBy(identity).mapValues(_.length))
      result <- parameters.traverse { param =>
        extractTermName(param.paramName).flatMap { name =>
          if (counts.getOrElse(name, 0) > 1) {
            pureTermName(param.argName.value).flatMap { escapedName =>
              alterMethodParameterName(param.param, escapedName).map { newParam =>
                new ScalaParameter[L](
                  param.in,
                  newParam,
                  escapedName,
                  param.argName,
                  param.argType,
                  param.required,
                  param.hashAlgorithm,
                  param.isFile
                )
              }
            }
          } else Free.pure[F, ScalaParameter[L]](param)
        }
      }
    } yield result
  }

  /**
    * Create method parameters from Swagger's Path parameters list. Use Option for non-required parameters.
    * @param params
    * @return
    */
  def filterParams[L <: LA](params: List[ScalaParameter[L]]): String => List[ScalaParameter[L]] = { in =>
    params.filter(_.in == Some(in))
  }
}
