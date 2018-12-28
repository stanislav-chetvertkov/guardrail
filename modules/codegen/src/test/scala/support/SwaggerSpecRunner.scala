package support
import com.twilio.guardrail.Common.OpenApiConversion
import io.swagger.v3.parser.OpenAPIV3Parser

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
    runSwagger(new OpenAPIV3Parser().read(spec)) _

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

      serverUrls = swagger.getServers.asScala.toList.map(_.getUrl)

      schemes  = OpenApiConversion.schemes(serverUrls)
      host     = OpenApiConversion.host(serverUrls)
      basePath = OpenApiConversion.basePath(serverUrls)

      paths = Option(swagger.getPaths)
        .map(_.asScala.toList)
        .getOrElse(List.empty)
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
