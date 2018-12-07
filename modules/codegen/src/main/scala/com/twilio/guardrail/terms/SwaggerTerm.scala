package com.twilio.guardrail
package terms

import com.twilio.guardrail.languages.LA
import io.swagger.v3.oas.models.PathItem.HttpMethod
import io.swagger.v3.oas.models._

case class RouteMeta(path: String, method: HttpMethod, operation: Operation)

sealed trait SwaggerTerm[L <: LA, T]
case class ExtractOperations[L <: LA](paths: List[(String, PathItem)]) extends SwaggerTerm[L, List[RouteMeta]]
case class GetClassName[L <: LA](operation: Operation)             extends SwaggerTerm[L, List[String]]
