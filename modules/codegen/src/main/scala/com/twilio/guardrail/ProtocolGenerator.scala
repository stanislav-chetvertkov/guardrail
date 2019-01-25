package com.twilio.guardrail

import _root_.io.swagger.v3.oas.models._
import _root_.io.swagger.v3.oas.models.media.{ ArraySchema, ComposedSchema, ObjectSchema, Schema }
import cats.data.EitherK
import cats.free.Free
import cats.implicits._
import com.twilio.guardrail.extract.ScalaType
import java.util.Locale

import com.twilio.guardrail.languages.LA
import com.twilio.guardrail.protocol.terms.protocol._
import com.twilio.guardrail.terms.framework.FrameworkTerms
import com.twilio.guardrail.terms.{ ScalaTerms, SwaggerTerms }

import scala.collection.JavaConverters._
import scala.language.higherKinds
import scala.language.postfixOps
import scala.language.reflectiveCalls
import Common._

case class ProtocolDefinitions[L <: LA](elems: List[StrictProtocolElems[L]],
                                        protocolImports: List[L#Import],
                                        packageObjectImports: List[L#Import],
                                        packageObjectContents: List[L#ValueDefinition])
sealed trait EmptyToNullBehaviour
case object EmptyIsNull  extends EmptyToNullBehaviour
case object EmptyIsEmpty extends EmptyToNullBehaviour

case class ProtocolParameter[L <: LA](term: L#MethodParameter,
                                      name: String,
                                      dep: Option[L#TermName],
                                      readOnlyKey: Option[String],
                                      emptyToNull: EmptyToNullBehaviour)

case class SuperClass[L <: LA](
    clsName: String,
    tpl: L#TypeName,
    interfaces: List[String],
    params: List[ProtocolParameter[L]],
    discriminators: List[String]
)

object ProtocolGenerator {
  private[this] def fromEnum[L <: LA, F[_]](
      clsName: String,
      swagger: Schema[_]
  )(implicit E: EnumProtocolTerms[L, F], F: FrameworkTerms[L, F], Sc: ScalaTerms[L, F]): Free[F, Either[String, ProtocolElems[L]]] = {
    import E._
    import F._
    import Sc._

    val toPascalRegexes = List(
      "[\\._-]([a-z])".r, // dotted, snake, or dashed case
      "\\s+([a-zA-Z])".r, // spaces
      "^([a-z])".r // initial letter
    )

    def toPascalCase(s: String): String =
      toPascalRegexes.foldLeft(s)(
        (accum, regex) => regex.replaceAllIn(accum, m => m.group(1).toUpperCase(Locale.US))
      )

    def validProg(enum: List[String], tpe: L#Type): Free[F, EnumDefinition[L]] =
      for {
        elems <- enum.traverse { elem =>
          val termName = toPascalCase(elem)
          for {
            valueTerm <- pureTermName(termName)
            accessor  <- buildAccessor(clsName, termName)
          } yield (elem, valueTerm, accessor)
        }
        pascalValues = elems.map(_._2)
        members <- renderMembers(clsName, elems)
        encoder <- encodeEnum(clsName)
        decoder <- decodeEnum(clsName)

        defn      <- renderClass(clsName, tpe)
        companion <- renderCompanion(clsName, members, pascalValues, encoder, decoder)
        classType <- pureTypeName(clsName)
      } yield EnumDefinition[L](clsName, classType, elems, defn, companion)

    // Default to `string` for untyped enums.
    // Currently, only plain strings are correctly supported anyway, so no big loss.
    val tpeName = Option(swagger.getType).getOrElse("string")
    //fixme why is it objectSchema and not StringSchema

    for {
      enum <- extractEnum(swagger)
      tpe  <- SwaggerUtil.typeName(tpeName, Option(swagger.getFormat()), ScalaType(swagger))
      res  <- enum.traverse(validProg(_, tpe))
    } yield res
  }

  /**
    * types of things we can losslessly convert between snake and camel case:
    *   - foo
    *   - foo_bar
    *   - foo_bar_baz
    *   - foo.bar
    *
    * types of things we canNOT losslessly convert between snake and camel case:
    *   - Foo
    *   - Foo_bar
    *   - Foo_Bar
    *   - FooBar
    *   - foo_barBaz
    *
    * so essentially we have to return false if:
    *   - there are any uppercase characters
    */
  def couldBeSnakeCase(s: String): Boolean = s.toLowerCase(Locale.US) == s

  /**
    * Handle polymorphic model
    */
  private[this] def fromPoly[L <: LA, F[_]](
      hierarchy: ClassParent,
      concreteTypes: List[PropMeta[L]],
      definitions: List[(String, Schema[_])]
  )(implicit F: FrameworkTerms[L, F],
    P: PolyProtocolTerms[L, F],
    M: ModelProtocolTerms[L, F],
    Sc: ScalaTerms[L, F],
    Sw: SwaggerTerms[L, F]): Free[F, ProtocolElems[L]] = {
    import P._
    import M._
    import Sc._

    def child(hierarchy: ClassHierarchy): List[String] =
      hierarchy.children.map(_.name) ::: hierarchy.children.flatMap(child)
    def parent(hierarchy: ClassHierarchy): List[String] =
      if (hierarchy.children.nonEmpty) hierarchy.name :: hierarchy.children.flatMap(parent)
      else Nil

    val children      = child(hierarchy).diff(parent(hierarchy)).distinct
    val discriminator = hierarchy.discriminator

    for {
      parents <- extractParents(hierarchy.model, definitions, concreteTypes)
      props   <- extractProperties(hierarchy.model)
      needCamelSnakeConversion = props.forall { case (k, _) => couldBeSnakeCase(k) }
      params <- props.traverse({
        case (name, prop) =>
          SwaggerUtil
            .propMeta[L, F](prop)
            .flatMap(transformProperty(hierarchy.name, needCamelSnakeConversion, concreteTypes)(name, prop, _, isRequired = false))
      })
      terms = params.map(_.term)
      definition <- renderSealedTrait(hierarchy.name, terms, discriminator, parents)
      encoder    <- encodeADT(hierarchy.name, children)
      decoder    <- decodeADT(hierarchy.name, children)
      cmp        <- renderADTCompanion(hierarchy.name, discriminator, encoder, decoder)
      tpe        <- pureTypeName(hierarchy.name)
    } yield {
      ADT[L](
        name = hierarchy.name,
        tpe = tpe,
        trt = definition,
        companion = cmp
      )
    }
  }

  def extractParents[L <: LA, F[_]](elem: Schema[_], definitions: List[(String, Schema[_])], concreteTypes: List[PropMeta[L]])(
      implicit M: ModelProtocolTerms[L, F],
      F: FrameworkTerms[L, F],
      P: PolyProtocolTerms[L, F],
      Sc: ScalaTerms[L, F],
      Sw: SwaggerTerms[L, F]
  ): Free[F, List[SuperClass[L]]] = {
    import M._
    import P._
    import Sc._

    for {
      a <- extractSuperClass(elem, definitions)
      supper <- a.flatTraverse { structure =>
        val (clsName, _extends, interfaces) = structure
        val concreteInterfaces = interfaces
          .flatMap(
            x =>
              definitions.collectFirst[Schema[_]] {
                case (cls, y: ComposedSchema) if x.getSimpleRef.contains(cls) => y
                case (cls, y: Schema[_]) if x.getSimpleRef.contains(cls)      => y
            }
          )
        for {
          _extendsProps <- extractProperties(_extends)
          _withProps    <- concreteInterfaces.traverse(extractProperties)
          props                    = _extendsProps ++ _withProps.flatten
          needCamelSnakeConversion = props.forall { case (k, _) => couldBeSnakeCase(k) }
          params <- props.traverse({
            case (name, prop) =>
              SwaggerUtil
                .propMeta[L, F](prop)
                .flatMap(transformProperty(clsName, needCamelSnakeConversion, concreteTypes)(name, prop, _, isRequired = false))
          })
          interfacesCls = interfaces.map(_.getSimpleRef.getOrElse(""))
          tpe <- parseTypeName(clsName)
        } yield
          tpe
            .map(
              SuperClass[L](
                clsName,
                _,
                interfacesCls,
                params,
                (_extends :: concreteInterfaces).collect {
                  case m: ObjectSchema if Option(m.getDiscriminator).isDefined => m.getDiscriminator.getPropertyName
                }
              )
            )
            .toList
      }

    } yield {
      supper
    }
  }

  private[this] def fromModel[L <: LA, F[_]](clsName: String, model: Schema[_], parents: List[SuperClass[L]], concreteTypes: List[PropMeta[L]])(
      implicit M: ModelProtocolTerms[L, F],
      F: FrameworkTerms[L, F],
      Sc: ScalaTerms[L, F],
      Sw: SwaggerTerms[L, F]
  ): Free[F, Either[String, ProtocolElems[L]]] = {
    import M._
    import F._
    import Sc._

    for {
      props <- extractProperties(model)
      requiredFields           = Option(model.getRequired).map(_.asScala.toList).getOrElse(List.empty)
      needCamelSnakeConversion = props.forall { case (k, _) => couldBeSnakeCase(k) }
      params <- props.traverse({
        case (name, prop) =>
          val isRequired = requiredFields.contains(name)
          SwaggerUtil.propMeta[L, F](prop).flatMap(transformProperty(clsName, needCamelSnakeConversion, concreteTypes)(name, prop, _, isRequired))
      })
      terms = params.map(_.term)
      defn <- renderDTOClass(clsName, terms, parents)
      deps = params.flatMap(_.dep)
      encoder <- encodeModel(clsName, needCamelSnakeConversion, params, parents)
      decoder <- decodeModel(clsName, needCamelSnakeConversion, params, parents)
      cmp     <- renderDTOCompanion(clsName, List.empty, encoder, decoder)
      tpe     <- parseTypeName(clsName)
    } yield
      if (parents.isEmpty && props.isEmpty) Left("Entity isn't model")
      else tpe.toRight("Empty entity name").map(ClassDefinition[L](clsName, _, defn, cmp, parents))
  }

  def modelTypeAlias[L <: LA, F[_]](clsName: String, abstractModel: Schema[_])(
      implicit
      F: FrameworkTerms[L, F],
      Sc: ScalaTerms[L, F]
  ): Free[F, ProtocolElems[L]] = {
    import F._
    import Sc._
    val model = abstractModel match {
      case m: ObjectSchema => Some(m)
      case m: ComposedSchema =>
        m.getAllOf.asScala.toList.get(1).flatMap {
          case m: ObjectSchema => Some(m)
          case _               => None
        }
      case _ => None
    }
    for {
      tpe <- model
        .flatMap(model => Option(model.getType))
        .fold[Free[F, L#Type]](objectType(None))(
          raw => SwaggerUtil.typeName[L, F](raw, model.flatMap(f => Option(f.getFormat)), model.flatMap(ScalaType(_)))
        )
      res <- typeAlias[L, F](clsName, tpe)
    } yield res
  }

  def plainTypeAlias[L <: LA, F[_]](
      clsName: String
  )(implicit F: FrameworkTerms[L, F], Sc: ScalaTerms[L, F]): Free[F, ProtocolElems[L]] = {
    import F._
    import Sc._
    for {
      tpe <- objectType(None)
      res <- typeAlias[L, F](clsName, tpe)
    } yield res
  }

  def typeAlias[L <: LA, F[_]](clsName: String, tpe: L#Type): Free[F, ProtocolElems[L]] =
    Free.pure(RandomType[L](clsName, tpe))

  def fromArray[L <: LA, F[_]](clsName: String, arr: ArraySchema, concreteTypes: List[PropMeta[L]])(
      implicit R: ArrayProtocolTerms[L, F],
      F: FrameworkTerms[L, F],
      P: ProtocolSupportTerms[L, F],
      Sc: ScalaTerms[L, F],
      Sw: SwaggerTerms[L, F]
  ): Free[F, ProtocolElems[L]] = {
    import P._
    import R._
    for {
      deferredTpe <- SwaggerUtil.modelMetaType(arr)
      tpe         <- extractArrayType(deferredTpe, concreteTypes)
      ret         <- typeAlias[L, F](clsName, tpe)
    } yield ret
  }

  sealed trait ClassHierarchy {
    def name: String
    def model: Schema[_]
    def children: List[ClassChild]
  }
  case class ClassChild(name: String, model: Schema[_], children: List[ClassChild])                         extends ClassHierarchy
  case class ClassParent(name: String, model: Schema[_], children: List[ClassChild], discriminator: String) extends ClassHierarchy

  /**
    * returns objects grouped into hierarchies
    */
  def groupHierarchies(definitions: List[(String, Schema[_])]): List[ClassParent] = {

    def firstInHierarchy(model: Schema[_]): Option[ObjectSchema] =
      (model match {
        case elem: ComposedSchema =>
          definitions.collectFirst {
            case (clsName, element) if elem.getAllOf.asScala.exists(r => r.getSimpleRef.contains(clsName)) => element
          }
        case _ => None
      }) match {
        case Some(x: ComposedSchema) => firstInHierarchy(x)
        case Some(x: ObjectSchema)   => Some(x)
        case _                       => None
      }

    def children(cls: String, model: Schema[_]): List[ClassChild] = definitions.collect {
      case (clsName, comp: ComposedSchema)
          if Option(comp.getAllOf)
            .map(_.asScala)
            .getOrElse(List.empty)
            .exists(_.getSimpleRef.contains(cls)) =>
        ClassChild(clsName, comp, children(clsName, comp))
    }

    def classHierarchy(cls: String, model: Schema[_]): Option[ClassParent] =
      (model match {
        case m: ObjectSchema if Option(m.getDiscriminator).isDefined   => Option(m.getDiscriminator.getPropertyName)
        case c: ComposedSchema if Option(c.getDiscriminator).isDefined => firstInHierarchy(c).map(_.getDiscriminator.getPropertyName)
        case _                                                         => None
      }).map(
        ClassParent(
          cls,
          model,
          children(cls, model),
          _
        )
      )

    definitions.map(classHierarchy _ tupled).collect {
      case Some(x) if x.children.nonEmpty => x
    }

  }

  def fromSwagger[L <: LA, F[_]](swagger: OpenAPI)(
      implicit E: EnumProtocolTerms[L, F],
      M: ModelProtocolTerms[L, F],
      R: ArrayProtocolTerms[L, F],
      S: ProtocolSupportTerms[L, F],
      F: FrameworkTerms[L, F],
      P: PolyProtocolTerms[L, F],
      Sc: ScalaTerms[L, F],
      Sw: SwaggerTerms[L, F]
  ): Free[F, ProtocolDefinitions[L]] = {
    import S._
    import F._
    import P._

    val definitions = Option(swagger.getComponents.getSchemas).toList.flatMap(_.asScala)
    val hierarchies = groupHierarchies(definitions)

    val definitionsWithoutPoly: List[(String, Schema[_])] = definitions.filter { // filter out polymorphic definitions
      case (clsName, _: ComposedSchema) if definitions.exists {
            case (_, m: ComposedSchema) =>
              Option(m.getAllOf)
                .map(_.asScala.toList)
                .map(_.headOption)
                .collect { case Some(x) => x }
                .exists(s => s.getSimpleRef.contains(clsName))
            case _ => false
          } =>
        false
      case (_, m: Schema[_]) if Option(m.getDiscriminator).isDefined => false
      case _                                                         => true
    }

    for {
      concreteTypes <- SwaggerUtil.extractConcreteTypes[L, F](definitions)
      polyADTs      <- hierarchies.traverse(fromPoly(_, concreteTypes, definitions))
      elems <- definitionsWithoutPoly.traverse {
        case (clsName, model) =>
          model match {
            case comp: ComposedSchema =>
              for {
                parents <- extractParents(comp, definitions, concreteTypes)
                model   <- fromModel(clsName, comp, parents, concreteTypes)
                alias   <- modelTypeAlias(clsName, comp)
              } yield model.getOrElse(alias)

            case arr: ArraySchema =>
              fromArray(clsName, arr, concreteTypes)

            case m: Schema[_] =>
              for {
                enum    <- fromEnum(clsName, m)
                parents <- extractParents(m, definitions, concreteTypes)
                model   <- fromModel(clsName, m, parents, concreteTypes)
                alias   <- modelTypeAlias(clsName, m)
              } yield enum.orElse(model).getOrElse(alias)

            case x =>
              println(s"Warning: ${x} being treated as Json")
              plainTypeAlias[L, F](clsName)
          }
      }
      protoImports      <- protocolImports
      pkgImports        <- packageObjectImports
      pkgObjectContents <- packageObjectContents

      polyADTElems <- ProtocolElems.resolve[L, F](polyADTs)
      strictElems  <- ProtocolElems.resolve[L, F](elems)
    } yield ProtocolDefinitions[L](strictElems ++ polyADTElems, protoImports, pkgImports, pkgObjectContents)
  }
}
