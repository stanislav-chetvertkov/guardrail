package com.twilio.guardrail

import java.net.URI

import _root_.io.swagger.v3.oas.models.OpenAPI
import cats.data.NonEmptyList
import cats.free.Free
import cats.implicits._
import cats.~>
import com.twilio.guardrail.languages.LA
import com.twilio.guardrail.protocol.terms.protocol.{ ArrayProtocolTerms, EnumProtocolTerms, ModelProtocolTerms, PolyProtocolTerms, ProtocolSupportTerms }
import com.twilio.guardrail.terms.framework.FrameworkTerms
import com.twilio.guardrail.protocol.terms.client.ClientTerms
import com.twilio.guardrail.protocol.terms.server.ServerTerms
import com.twilio.guardrail.terms.{ CoreTerms, ScalaTerms, SwaggerTerms }
import java.nio.file.{ Path, Paths }
import java.util.Locale

import scala.collection.JavaConverters._
import scala.io.AnsiColor
import scala.meta._

object Common {

  // fixme: temporary means of using open-api v3 model without introducing too many changes at the same time
  // fixme: remove
  object OpenApiConversion {
    def schemes(serversUrls: List[String]): List[String] = //fixme: toUpperCase ???
      serversUrls.map(s => new URI(s)).map(_.getScheme)

    def host(serversUrls: List[String]): Option[String] =
      for {
        list <- Option(serversUrls).filter(_.nonEmpty)
        head <- list.headOption
      } yield {
        new URI(head).getHost
      }

    def basePath(serversUrls: List[String]): Option[String] =
      for {
        list <- Option(serversUrls).filter(_.nonEmpty)
        head <- list.headOption
      } yield {
        new URI(head).getPath
      }

  }

  def writePackage[L <: LA, F[_]](kind: CodegenTarget,
                                  context: Context,
                                  swagger: OpenAPI,
                                  outputPath: Path,
                                  pkgName: List[String],
                                  dtoPackage: List[String],
                                  customImports: List[L#Import])(implicit
                                                                 C: ClientTerms[L, F],
                                                                 R: ArrayProtocolTerms[L, F],
                                                                 E: EnumProtocolTerms[L, F],
                                                                 F: FrameworkTerms[L, F],
                                                                 M: ModelProtocolTerms[L, F],
                                                                 Pol: PolyProtocolTerms[L, F],
                                                                 S: ProtocolSupportTerms[L, F],
                                                                 Sc: ScalaTerms[L, F],
                                                                 Se: ServerTerms[L, F],
                                                                 Sw: SwaggerTerms[L, F]): Free[F, List[WriteTree]] = {
    import F._
    import Sc._
    import Sw._

    val resolveFile: Path => List[String] => Path       = root => _.foldLeft(root)(_.resolve(_))
    val splitComponents: String => Option[List[String]] = x => Some(x.split('.').toList).filterNot(_.isEmpty)

    val pkgPath        = resolveFile(outputPath)(pkgName)
    val dtoPackagePath = resolveFile(pkgPath.resolve("definitions"))(dtoPackage)

    val definitions: List[String]   = pkgName :+ "definitions"
    val dtoComponents: List[String] = definitions ++ dtoPackage
    val buildPkgTerm: List[String] => Term.Ref =
      _.map(Term.Name.apply _).reduceLeft(Term.Select.apply _)

    for {
      proto <- ProtocolGenerator.fromSwagger[L, F](swagger)
      ProtocolDefinitions(protocolElems, protocolImports, packageObjectImports, packageObjectContents) = proto
      imports                                                                                          = customImports ++ protocolImports
      utf8                                                                                             = java.nio.charset.Charset.availableCharsets.get("UTF-8")

      protoOut <- protocolElems.traverse(writeProtocolDefinition(outputPath, pkgName, definitions, dtoComponents, imports, _))
      (protocolDefinitions, extraTypes) = protoOut.foldLeft((List.empty[WriteTree], List.empty[L#Statement]))(_ |+| _)

      dtoHead :: dtoRest = dtoComponents
      dtoPkg = dtoRest.init
        .foldLeft[Term.Ref](Term.Name(dtoHead)) {
          case (acc, next) => Term.Select(acc, Term.Name(next))
        }
      companion = Term.Name(s"${dtoComponents.last}$$")

      packageObject <- writePackageObject(
        dtoPackagePath,
        dtoComponents,
        customImports,
        packageObjectImports,
        protocolImports,
        packageObjectContents,
        extraTypes
      )

      serverUrls = swagger.getServers.asScala.toList.map(_.getUrl)

      schemes  = OpenApiConversion.schemes(serverUrls)
      host     = OpenApiConversion.host(serverUrls)
      basePath = OpenApiConversion.basePath(serverUrls)

      paths = Option(swagger.getPaths)
        .map(_.asScala.toList)
        .getOrElse(List.empty)
      routes           <- extractOperations(paths)
      classNamedRoutes <- routes.traverse(route => getClassName(route.operation).map(_ -> route))
      groupedRoutes = classNamedRoutes
        .groupBy(_._1)
        .mapValues(_.map(_._2))
        .toList
      frameworkImports    <- getFrameworkImports(context.tracing)
      _frameworkImplicits <- getFrameworkImplicits()
      (frameworkImplicitName, frameworkImplicits) = _frameworkImplicits

      codegen <- kind match {
        case CodegenTarget.Client =>
          for {
            clientMeta <- ClientGenerator
              .fromSwagger[L, F](context, frameworkImports)(schemes, host, basePath, groupedRoutes)(protocolElems)
            Clients(clients) = clientMeta
          } yield CodegenDefinitions[L](clients, List.empty)

        case CodegenTarget.Server =>
          for {
            serverMeta <- ServerGenerator
              .fromSwagger[L, F](context, swagger, frameworkImports)(protocolElems)
            Servers(servers) = serverMeta
          } yield CodegenDefinitions[L](List.empty, servers)
      }

      CodegenDefinitions(clients, servers) = codegen

      files <- (clients.traverse(writeClient(pkgPath, pkgName, customImports, frameworkImplicitName, dtoComponents, _)),
                servers.traverse(writeServer(pkgPath, pkgName, customImports, frameworkImplicitName, dtoComponents, _))).mapN(_ ++ _)

      implicits              <- renderImplicits(pkgPath, pkgName, frameworkImports, protocolImports, customImports)
      frameworkImplicitsFile <- renderFrameworkImplicits(pkgPath, pkgName, frameworkImports, protocolImports, frameworkImplicits, frameworkImplicitName)
    } yield
      (
        protocolDefinitions ++
          List(packageObject) ++
          files ++
          List(
            implicits,
            frameworkImplicitsFile
          )
      ).toList
  }

  def processArgs[F[_]](
      args: NonEmptyList[Args]
  )(implicit C: CoreTerms[F]): Free[F, NonEmptyList[ReadSwagger[Target[List[WriteTree]]]]] = {
    import C._
    args.traverse(
      arg =>
        for {
          targetInterpreter <- extractGenerator(arg.context)
          writeFile         <- processArgSet(targetInterpreter)(arg)
        } yield writeFile
    )
  }

  def runM[F[_]](
      args: Array[String]
  )(implicit C: CoreTerms[F]): Free[F, NonEmptyList[ReadSwagger[Target[List[WriteTree]]]]] = {
    import C._

    for {
      defaultFramework <- getDefaultFramework
      parsed           <- parseArgs(args, defaultFramework)
      args             <- validateArgs(parsed)
      writeTrees       <- processArgs(args)
    } yield writeTrees
  }
}
