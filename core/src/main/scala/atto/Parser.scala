package atto

import scala.language.implicitConversions
import scala.language.higherKinds

import java.lang.String
import scala.{ Boolean, List, Nothing }

import scalaz._
import scalaz.Scalaz._
import scalaz.Free.Trampoline
import Trampoline._

// Operators not needed for use in `for` comprehensions are provided via added syntax.
trait Parser[+A] { m =>
  import Parser._
  import Parser.Internal._

  def apply[R](st0: State, kf: Failure[R], ks: Success[A,R]): TResult[R]

  // TODO: get rid of this
  def infix(s: String): String =
    "(" + m.toString + ") " + s

  def flatMap[B](f: A => Parser[B]): Parser[B] =
    new Parser[B] {
      def apply[R](st0: State, kf: Failure[R], ks: Success[B,R]): TResult[R] =
        suspend(m(st0,kf,(s:State, a:A) => f(a)(s,kf,ks)))
      override def toString = m infix "flatMap ..."
    }

  def map[B](f: A => B): Parser[B] =
    new Parser[B] {
      override def toString = m infix "map ..."
      def apply[R](st0: State, kf: Failure[R], ks: Success[B,R]): TResult[R] =
        suspend(m(st0,kf,(s:State, a:A) => suspend(ks(s,f(a)))))
    }

  def filter(p: A => Boolean): Parser[A] =
    parser.combinator.filter(this)(p)

}

object Parser extends ParserInstances with ParserFunctions {

  type Pos = Int

  final case class State private (input: String, pos: Pos, complete: Boolean) {
    def completed: State = copy(complete = true)
  }

  object State {
    def apply(s: String, done: Boolean) = new State(s, 0, done)
  }

  object Internal {
    sealed abstract class Result[T] {
      def translate: ParseResult[T]
    }
    case class Fail[T](input: State, stack: IList[String], message: String) extends Result[T] {
      def translate = ParseResult.Fail(input.input, stack, message)
      def push(s: String) = Fail(input, stack = s :: stack, message)
    }
    case class Partial[T](k: String => Trampoline[Result[T]]) extends Result[T] {
      def translate = ParseResult.Partial(a => k(a).run.translate)
    }
    case class Done[T](input: State, result: T) extends Result[T] {
      def translate = ParseResult.Done(input.input, result)
    }
  }

  import Internal._

  type TResult[R] = Trampoline[Result[R]]
  type Failure[R] = (State,IList[String],String) => TResult[R]
  type Success[-A, R] = (State, A) => TResult[R]

}

trait ParserFunctions {
  import Parser._
  import Parser.Internal._

  /**
   * Run a parser
   */
  def parse[A](m: Parser[A], b: String): ParseResult[A] = {
    def kf(a:State, b: IList[String], c: String) = done[Result[A]](Fail(a.copy(input = a.input.drop(a.pos)), b, c))
    def ks(a:State, b: A) = done[Result[A]](Done(a.copy(input = a.input.drop(a.pos)), b))
    m(State(b, false), kf, ks).run.translate
  }

  /**
   * Run a parser that cannot be resupplied via a 'Partial' result.
   *
   * This function does not force a parser to consume all of its input.
   * Instead, any residual input will be discarded.
   */
  def parseOnly[A](m: Parser[A], b: String): ParseResult[A] = {
    def kf(a:State, b: IList[String], c: String) = done[Result[A]](Fail(a.copy(input = a.input.drop(a.pos)), b, c))
    def ks(a:State, b: A) = done[Result[A]](Done(a.copy(input = a.input.drop(a.pos)), b))
    m(State(b, true), kf, ks).run.translate
  }

  // def parse[M[_]:Monad, A](m: Parser[A], refill: M[String], init: String): M[ParseResult[A]] = {
  //   def step[A] (r: Result[A]): M[ParseResult[A]] = r match {
  //     case Partial(k) => refill flatMap (a => step(k(a)))
  //     case x => x.translate.pure[M]
  //   }
  //   step(m(State(init, "", false),(a,b,c) => done(Fail(a, b, c)), (a,b) => done(Done(a, b))))
  // }

  // def parseAll[A](m: Parser[A], init: String): ParseResult[A] =
  //   Parser.phrase(m) parse init

  // def parseAll[M[_]:Monad, A](m: Parser[A], refill: M[String], init: String): M[ParseResult[A]] =
  //   parse[M,A](Parser.phrase(m), refill, init)

}

trait ParserInstances {
  import parser.combinator._
  import syntax.parser._

  implicit def monad: Monad[Parser] =
    new Monad[Parser] {
      def point[A](a: => A): Parser[A] = ok(a)
      def bind[A,B](ma: Parser[A])(f: A => Parser[B]) = ma flatMap f
      override def map[A,B](ma: Parser[A])(f: A => B) = ma map f
    }

  implicit def plus: Plus[Parser] =
    new Plus[Parser] {
      def plus[A](a: Parser[A], b: => Parser[A]): Parser[A] = a | b
    }

  implicit def monoid[A]: Monoid[Parser[A]] =
    new Monoid[Parser[A]] {
      def append(s1: Parser[A], s2: => Parser[A]): Parser[A] = s1 | s2
      val zero: Parser[A] = err("zero")
    }

}





