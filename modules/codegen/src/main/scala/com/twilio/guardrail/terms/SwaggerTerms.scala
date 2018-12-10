package com.twilio.guardrail
package terms

import cats.InjectK
import cats.free.Free
import com.twilio.guardrail.languages.LA
import io.swagger.v3.oas.models.{ Operation, PathItem }

class SwaggerTerms[L <: LA, F[_]](implicit I: InjectK[SwaggerTerm[L, ?], F]) {
  def extractOperations(paths: List[(String, PathItem)]): Free[F, List[RouteMeta]] =
    Free.inject[SwaggerTerm[L, ?], F](ExtractOperations(paths))

  def getClassName(operation: Operation): Free[F, List[String]] =
    Free.inject[SwaggerTerm[L, ?], F](GetClassName(operation))
}
object SwaggerTerms {
  implicit def swaggerTerm[L <: LA, F[_]](implicit I: InjectK[SwaggerTerm[L, ?], F]): SwaggerTerms[L, F] =
    new SwaggerTerms[L, F]
}
