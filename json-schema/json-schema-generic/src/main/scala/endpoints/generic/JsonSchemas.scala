package endpoints
package generic

import shapeless.labelled.{FieldType, field => shapelessField}
import shapeless.ops.hlist.{ToList, Tupler}
import shapeless.{:+:, ::, Annotations, CNil, Coproduct, Generic, HList, HNil, Inl, Inr, LabelledGeneric, Witness}

import scala.language.implicitConversions
import scala.language.higherKinds
import scala.reflect.ClassTag

/**
  * Enriches [[JsonSchemas]] with two kinds of operations:
  *
  * - `genericJsonSchema[A]` derives the `JsonSchema` of an algebraic
  *   data type `A`;
  * - `(field1 :×: field2 :×: …).as[A]` builds a tuple of `Record`s and maps
  *   it to a case class `A`
  *
  * The data type description derivation is based on the underlying
  * field and constructor names.
  *
  * For instance, consider the following program that derives the description
  * of a case class:
  *
  * {{{
  *   case class User(name: String, age: Int)
  *   object User {
  *     implicit val schema: JsonSchema[User] = genericJsonSchema[User]
  *   }
  * }}}
  *
  * It is equivalent to the following:
  *
  * {{{
  *   case class User(name: String, age: Int)
  *   object User {
  *     implicit val schema: JsonSchema[User] = (
  *       field[String]("name") zip
  *       field[Int]("age")
  *     ).xmap((User.apply _).tupled)(Function.unlift(User.unapply))
  *   }
  * }}}
  *
  */
trait JsonSchemas extends algebra.JsonSchemas {

  trait GenericJsonSchema[A] {
    def jsonSchema(docs: List[Option[documentation]]): JsonSchema[A]
  }

  object GenericJsonSchema extends GenericJsonSchemaLowPriority {

    implicit def emptyRecordCase: GenericRecord[HNil] =
      new GenericRecord[HNil] {
        type D = HNil
        def jsonSchema(docs: List[Option[documentation]]): Record[HNil] =
          emptyRecord.xmap[HNil](_ => HNil)(_ => ())
      }

    implicit def singletonCoproduct[L <: Symbol, A](implicit
      labelSingleton: Witness.Aux[L],
      recordA: GenericRecord[A]
    ): GenericTagged[FieldType[L, A] :+: CNil] =
      new GenericTagged[FieldType[L, A] :+: CNil] {
        def jsonSchema(docs: List[Option[documentation]]): Tagged[FieldType[L, A] :+: CNil] =
          recordA.jsonSchema(Nil).tagged(labelSingleton.value.name).xmap[FieldType[L, A] :+: CNil] {
            a => Inl(shapelessField[L](a))
          } {
            case Inl(a) => a
            case Inr(_) => sys.error("Unreachable code")
          }
      }

  }

  trait GenericJsonSchemaLowPriority extends GenericJsonSchemaLowLowPriority {

    implicit def consRecord[L <: Symbol, H, T <: HList](implicit
      labelHead: Witness.Aux[L],
      jsonSchemaHead: JsonSchema[H],
      jsonSchemaTail: GenericRecord[T]
    ): GenericRecord[FieldType[L, H] :: T] =
      new GenericRecord[FieldType[L, H] :: T] {
        def jsonSchema(docs: List[Option[documentation]]): Record[FieldType[L, H] :: T] =
          (field(labelHead.value.name, docs.head.map(_.text))(jsonSchemaHead) zip jsonSchemaTail.jsonSchema(docs.tail))
            .xmap[FieldType[L, H] :: T] { case (h, t) => shapelessField[L](h) :: t }(ht => (ht.head, ht.tail))
      }

    implicit def consOptRecord[L <: Symbol, H, T <: HList](implicit
      labelHead: Witness.Aux[L],
      jsonSchemaHead: JsonSchema[H],
      jsonSchemaTail: GenericRecord[T]
    ): GenericRecord[FieldType[L, Option[H]] :: T] =
      new GenericRecord[FieldType[L, Option[H]] :: T] {
        def jsonSchema(docs: List[Option[documentation]]): Record[FieldType[L, Option[H]] :: T] =
          (optField(labelHead.value.name, docs.head.map(_.text))(jsonSchemaHead) zip jsonSchemaTail.jsonSchema(docs.tail))
            .xmap[FieldType[L, Option[H]] :: T] { case (h, t) => shapelessField[L](h) :: t }(ht => (ht.head, ht.tail))
      }

    implicit def consCoproduct[L <: Symbol, H, T <: Coproduct](implicit
      labelHead: Witness.Aux[L],
      recordHead: GenericRecord[H],
      taggedTail: GenericTagged[T]
    ): GenericTagged[FieldType[L, H] :+: T] =
      new GenericTagged[FieldType[L, H] :+: T] {
        def jsonSchema(docs: List[Option[documentation]]): Tagged[FieldType[L, H] :+: T] = {
          val taggedHead = recordHead.jsonSchema(Nil).tagged(labelHead.value.name)
          taggedHead.orElse(taggedTail.jsonSchema(docs.tail)).xmap[FieldType[L, H] :+: T] {
            case Left(h)  => Inl(shapelessField[L](h))
            case Right(t) => Inr(t)
          } {
            case Inl(h) => Left(h)
            case Inr(t) => Right(t)
          }
        }
      }

  }

  trait GenericJsonSchemaLowLowPriority {

    trait GenericRecord[A] extends GenericJsonSchema[A] {
      def jsonSchema(docs: List[Option[documentation]]): Record[A]
    }

    trait GenericTagged[A] extends GenericJsonSchema[A] {
      def jsonSchema(docs: List[Option[documentation]]): Tagged[A]
    }

    implicit def recordGeneric[A, R, D <: HList](implicit
      gen: LabelledGeneric.Aux[A, R],
      record: GenericRecord[R],
      docAnns: Annotations.Aux[documentation, A, D],
      docsToList: ToList[D, Option[documentation]],
      ct: ClassTag[A]
    ): GenericRecord[A] =
      new GenericRecord[A] {
        def jsonSchema(docs: List[Option[documentation]]): Record[A] =
          nameSchema(record.jsonSchema(docsToList(docAnns.apply)).xmap[A](gen.from)(gen.to))
      }

    implicit def taggedGeneric[A, R, D <: HList](implicit
      gen: LabelledGeneric.Aux[A, R],
      tagged: GenericTagged[R],
      docAnns: Annotations.Aux[documentation, A, D],
      docsToList: ToList[D, Option[documentation]],
      ct: ClassTag[A]
    ): GenericTagged[A] =
      new GenericTagged[A] {
        def jsonSchema(docs: List[Option[documentation]]): Tagged[A] =
          nameSchema(tagged.jsonSchema(docsToList(docAnns.apply)).xmap[A](gen.from)(gen.to))
      }

  }

  private def nameSchema[A: ClassTag, S[T] <: JsonSchema[T]](schema: S[A]): S[A] = {
    val jvmName = implicitly[ClassTag[A]].runtimeClass.getName
    // name fix for case objects
    val name = if(jvmName.nonEmpty && jvmName.last == '$') jvmName.init else jvmName
    named(schema, name.replace('$','.'))
  }


  /** @return a `JsonSchema[A]` obtained from an implicitly derived `GenericJsonSchema[A]`
    *
    * In a sense, this operation asks shapeless to compute a ''type level'' description
    * of a data type (based on HLists and Coproducts) and turns it into a ''term level''
    * description of the data type (based on the `JsonSchemas` algebra interface)
    *
    * This operation is calculating a name for the schema based on classTag.runtimeClass.getName
    * This could result in non unique values and mess with documentation
    */
  def genericJsonSchema[A: ClassTag](implicit genJsonSchema: GenericJsonSchema[A]): JsonSchema[A] =
    nameSchema(genJsonSchema.jsonSchema(Nil))

  /** @return a `JsonSchema[A]` obtained from an implicitly derived `GenericJsonSchema[A]`
    *
    * In a sense, this operation asks shapeless to compute a ''type level'' description
    * of a data type (based on HLists and Coproducts) and turns it into a ''term level''
    * description of the data type (based on the `JsonSchemas` algebra interface)
    *
    * This operation is using the name provided
    * Please be aware that this name should be unique or documentation will not work properly
    */
  def namedGenericJsonSchema[A](name : String)(implicit genJsonSchema: GenericJsonSchema[A]): JsonSchema[A] =
    named(genJsonSchema.jsonSchema(Nil), name)

  final class RecordGenericOps[L <: HList](record: Record[L]) {

    def :*: [H](recordHead: Record[H]): RecordGenericOps[H :: L] =
      new RecordGenericOps(
        (recordHead zip record).xmap { case (h, l) => h :: l }(hl => (hl.head, hl.tail))
      )

    def :×: [H](recordHead: Record[H]): RecordGenericOps[H :: L] = recordHead :*: this

    def as[A](implicit gen: Generic.Aux[A, L]): Record[A] = record.xmap(gen.from)(gen.to)

    def tupled[T](implicit gen: Generic.Aux[T, L]): Record[T] = as[T]

  }

  implicit def toRecordGenericOps[A](record: Record[A]): RecordGenericOps[A :: HNil] =
    new RecordGenericOps[A :: HNil](record.xmap(_ :: HNil)(_.head))

}
