package support
import java.util

import com.twilio.guardrail.Common._
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions

trait SwaggerSpecRunner {

  import _root_.io.swagger.v3.oas.models._
  import cats.arrow.FunctionK
  import cats.implicits._
  import com.twilio.guardrail._
  import com.twilio.guardrail.languages.ScalaLanguage
  import com.twilio.guardrail.terms.framework.FrameworkTerms
  import com.twilio.guardrail.terms.{ ScalaTerms, SwaggerTerms }
  import scala.collection.JavaConverters._

  def runSwaggerSpec(
      spec: String
  ): (Context, FunctionK[CodegenApplication, Target]) => (ProtocolDefinitions[ScalaLanguage], Clients[ScalaLanguage], Servers[ScalaLanguage]) =
    runSwagger(new OpenAPIParser().readContents(spec, new util.LinkedList(), new ParseOptions).getOpenAPI)

  def runSwagger(swagger: OpenAPI)(context: Context, framework: FunctionK[CodegenApplication, Target])(
      implicit F: FrameworkTerms[ScalaLanguage, CodegenApplication],
      Sc: ScalaTerms[ScalaLanguage, CodegenApplication],
      Sw: SwaggerTerms[ScalaLanguage, CodegenApplication]
  ): (ProtocolDefinitions[ScalaLanguage], Clients[ScalaLanguage], Servers[ScalaLanguage]) = {
    import F._
    import Sw._

    val prog = for {
      protocol <- ProtocolGenerator.fromSwagger[ScalaLanguage, CodegenApplication](swagger)
      definitions = protocol.elems

      schemes  = swagger.schemes()
      host     = swagger.host()
      basePath = swagger.basePath()
      paths    = swagger.getPathsOpt()

      routes <- extractOperations(paths)
      classNamedRoutes <- routes
        .map(route => getClassName(route.operation).map(_ -> route))
        .sequence
      groupedRoutes = classNamedRoutes
        .groupBy(_._1)
        .mapValues(_.map(_._2))
        .toList
      frameworkImports <- getFrameworkImports(context.tracing)

      clients <- ClientGenerator
        .fromSwagger[ScalaLanguage, CodegenApplication](context, frameworkImports)(schemes, host, basePath, groupedRoutes)(definitions)
      servers <- ServerGenerator
        .fromSwagger[ScalaLanguage, CodegenApplication](context, swagger, frameworkImports)(definitions)
    } yield (protocol, clients, servers)

    Target.unsafeExtract(prog.foldMap(framework))
  }

}
