package com.twilio.guardrail
import scala.util.Try

package object extract {
  def ScalaTracingLabel[F: VendorExtension.VendorExtensible](v: F): Option[String] =
    VendorExtension(v).extract[String]("x-scala-tracing-label")

  def ScalaPackage[F: VendorExtension.VendorExtensible](v: F): Option[String] =
    VendorExtension(v).extract[String]("x-scala-package")

  def ScalaType[F: VendorExtension.VendorExtensible](v: F): Option[String] = {
    val tr = Try(VendorExtension(v).extract[String]("x-scala-type"))
    if (tr.isFailure) { //fixme
      print(tr)
    }

    tr.toOption.flatten
  }

  def ServerRawResponse[F: VendorExtension.VendorExtensible](v: F): Option[Boolean] =
    VendorExtension(v).extract[Boolean]("x-server-raw-response")

  def ScalaEmptyIsNull[F: VendorExtension.VendorExtensible](v: F): Option[EmptyToNullBehaviour] =
    VendorExtension(v).extract[EmptyToNullBehaviour]("x-scala-empty-is-null")

  def ScalaFileHashAlgorithm[F: VendorExtension.VendorExtensible](v: F): Option[String] =
    VendorExtension(v).extract[String]("x-scala-file-hash")
}
