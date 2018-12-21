package com.twilio.guardrail

import java.nio.file.Path

import cats._
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.OpenAPIV3Parser

import scala.io.AnsiColor

case class ReadSwagger[T](path: Path, next: OpenAPI => T)
object ReadSwagger {
  @deprecated("0.37.1", "Hiding the error result prevents build tools from failing on file read")
  def unsafeReadSwagger[T: Monoid](rs: ReadSwagger[T]): T =
    readSwagger(rs)
      .fold({ err =>
        println(s"${AnsiColor.RED}${err}${AnsiColor.RESET}")
        Monoid.empty[T]
      }, identity)

  def readSwagger[T](rs: ReadSwagger[T]): Either[String, T] =
    if (rs.path.toFile.exists()) {
      Option(new OpenAPIV3Parser().read(rs.path.toAbsolutePath.toString))
        .map(rs.next)
        .toRight(s"Spec file ${rs.path} is incorrectly formatted.")
    } else {
      Left(s"Spec file ${rs.path} does not exist.")
    }
}
