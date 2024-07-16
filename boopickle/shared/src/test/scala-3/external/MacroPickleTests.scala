package external

import java.nio.ByteBuffer

import boopickle.Default._
import boopickle.{DecoderSize, EncoderSize, UnpickleState}
import utest._

object MacroPickleTests extends TestSuite {

  case class Test1(i: Int, x: String)

  case class Test2(i: Int, next: Option[Test2], l: Map[String, String] = Map.empty)

  implicit val pickerTest2: Pickler[Test2] = generatePickler[Test2]

  case object TestO

  sealed trait MyTrait

  case class TT1(i: Int) extends MyTrait

  sealed trait DeepTrait extends MyTrait

  case class TT2(s: String, next: MyTrait) extends DeepTrait

  class TT3(val i: Int, val s: String) extends DeepTrait {
    // a normal class requires an equals method to work properly
    override def equals(obj: scala.Any): Boolean = obj match {
      case t: TT3 => i == t.i && s == t.s
      case _      => false
    }
  }

  object TT3

  object MyTrait {
    // a pickler for non-case classes cannot be automatically generated, so use the transform pickler
    implicit val pickler3: Pickler[TT3] = transformPickler[TT3, (Int, String)]((t) => new TT3(t._1, t._2))((t) => (t.i, t.s))
    // implicit val pickler: Pickler[MyTrait] = generatePickler[MyTrait]
  }

  case class A(fills: List[B])

  case class B(stops: List[(Double, Double)])

  sealed trait A1Trait[T]

  case class A1[T](i: T) extends A1Trait[T]

  sealed abstract class AClass

  case class AB(i: Int) extends AClass

  sealed abstract class Version(val number: Int)

  case object V1 extends Version(1)

  case object V2 extends Version(2)

  case class ValueClass(value: Int) extends AnyVal

  sealed trait ValueTrait[T] extends Any

  case class ValueTraitClass[T](value: T) extends AnyVal with ValueTrait[T]

  sealed trait MultiT[S, T, O]

  case class Multi[S, T](s: S, t: T) extends MultiT[S, T, String]

  case class Multi2[S, T](s: S, t: T) extends MultiT[S, T, String]

  override def tests = Tests {
    // must import pickler from the companion object, otherwise scalac will try to use a macro to generate it
    import MyTrait._
    "CaseClasses" - {
      "Case1" - {
        val bb = Pickle.intoBytes(Test1(5, "Hello!"))
        assert(bb.limit() == 1 + 1 + 7)
        assert(Unpickle[Test1].fromBytes(bb) == Test1(5, "Hello!"))
      }
      "SeqCase" - {
        implicit def pstate: PickleState                 = new PickleState(new EncoderSize, true)
        implicit def ustate: ByteBuffer => UnpickleState = b => new UnpickleState(new DecoderSize(b), true)
        val t                                            = Test1(99, "Hello!")
        val s                                            = Seq(t, t, t)
        val bb                                           = Pickle.intoBytes(s)
        assert(bb.limit() == 1 + 1 + 1 + 7 + 2 * 2)
        val u = Unpickle[Seq[Test1]].fromBytes(bb)
        assert(u == s)
      }
      "Recursive" - {
        val t  = List(Test2(1, Some(Test2(2, Some(Test2(3, None))))))
        val bb = Pickle.intoBytes(t)
        assert(bb.limit() == 13)
        val u = Unpickle[List[Test2]].fromBytes(bb)
        assert(u == t)
      }
      "CaseObject" - {
        val bb = Pickle.intoBytes(TestO)
        // yea, pickling a case object takes no space at all :)
        assert(bb.limit() == 0)
        val u = Unpickle[TestO.type].fromBytes(bb)
        assert(u == TestO)
      }
      //   "Trait" - {
      //     // Scala 3 doesn't yet provide a Mirror for this case
      //     val t: Seq[MyTrait] = Seq(TT1(5), TT2("five", TT2("six", new TT3(42, "fortytwo"))))
      //     val bb              = Pickle.intoBytes(t)
      //     val u               = Unpickle[Seq[MyTrait]].fromBytes(bb)
      //     assert(u == t)
      //   }
      //   "TraitToo" - {
      //     // Scala 3 doesn't yet provide a Mirror for this case
      //     // the same test code twice, to check that additional .class files are not generated for the MyTrait pickler
      //     val t: Seq[MyTrait] = Seq(TT1(5), TT2("five", TT2("six", new TT3(42, "fortytwo"))))
      //     val bb              = Pickle.intoBytes(t)
      //     val u               = Unpickle[Seq[MyTrait]].fromBytes(bb)
      //     assert(u == t)
      //   }
      "AbstractClass" - {
        val t: Seq[AClass] = Seq(AB(5), AB(2))
        val bb             = Pickle.intoBytes(t)
        val u              = Unpickle[Seq[AClass]].fromBytes(bb)
        assert(u == t)
      }
      "AbstractClass2" - {
        val t: Seq[Version] = Seq(V1, V2)
        val bytes           = Pickle.intoBytes(t)
        val u               = Unpickle[Seq[Version]].fromBytes(bytes)
        assert(u == t)
      }
      "CaseTupleList" - {
        // this won't compile due to "diverging implicits"
        // val x = A(List(B(List(Tuple2(2.0, 1.0)))))
        // val bb = Pickle.intoBytes(x)
        // val u = Unpickle[A].fromBytes(bb)
        // assert(x == u)
      }
      "CaseTupleList2" - {
        implicit val bPickler: Pickler[B] = generatePickler[B]
        val x                             = A(List(B(List((2.0, 3.0)))))
        val bb                            = Pickle.intoBytes(x)
        val u                             = Unpickle[A].fromBytes(bb)
        assert(x == u)
      }
      "CaseTupleList3" - {
        val x  = List(B(List((2.0, 3.0))))
        val bb = Pickle.intoBytes(x)
        val u  = Unpickle[List[B]].fromBytes(bb)
        assert(x == u)
      }
      "CaseGenericTraitAndCaseclass" - {
        val x: A1Trait[Int] = A1[Int](2)
        val bb              = Pickle.intoBytes(x)
        val u               = Unpickle[A1Trait[Int]].fromBytes(bb)
        assert(x == u)
      }
      "CaseGenericTraitAndCaseclass2" - {
        val x: A1Trait[Double] = A1[Double](2.0)
        val bb                 = Pickle.intoBytes(x)
        val u                  = Unpickle[A1Trait[Double]].fromBytes(bb)
        assert(x == u)
      }
      "ValueClass" - {
        val x: ValueClass = ValueClass(3)
        val bb            = Pickle.intoBytes(x)
        val u             = Unpickle[ValueClass].fromBytes(bb)
        assert(x == u)
      }
      // "TraitAndValueClass" - {
      //   // Scala 3 doesn't yet provide a Mirror for this case
      //   val x: ValueTrait[Int] = new ValueTraitClass[Int](3)
      //   val bb                 = Pickle.intoBytes(x)
      //   val u                  = Unpickle[ValueTrait[Int]].fromBytes(bb)
      //   assert(x == u)
      // }
      "MultipleGenerics" - {
        val x: MultiT[Int, Double, String] = Multi[Int, Double](1, 2.0)
        val bb                             = Pickle.intoBytes(x)
        val u                              = Unpickle[MultiT[Int, Double, String]].fromBytes(bb)
        assert(x == u)
      }
    }
  }
}
