package application

import scala.concurrent.*
import scala.util.*

import cats.*
import cats.effect.*
import cats.implicits.*
import domain.dependency.*
import org.legogroup.woof.{ *, given }
import domain.PackageIndex

object PythonDependencyReporter:
  def forIo(packageIndex: PackageIndex[IO])(using
  Logger[IO]): DependencyReporter[IO] =
    new DependencyReporter[IO]:
      def getDetails(
          dependencies: List[Dependency]
      ): IO[List[DependencyDetails]] =
        dependencies
          .grouped(12)
          .toList
          .zipWithIndex
          .traverse {
            case (dependencies, index) =>
              Logger[IO].debug(s"Requesting details of $index-th batch") >>
                dependencies.parTraverse(d => packageIndex.getDetails(d)).flatTap(
                  _ => Logger[IO].debug(s"Got results for $index-th batch")
                )
          }
          .flatMap(results =>
            val (details, exceptions) = results.flatten
              .foldLeft(
                (List.empty[DependencyDetails], List.empty[String])
              ) {
                case ((results, exceptions), result) =>
                  result match
                    case Left(exception) => (results, exception :: exceptions)
                    case Right(value)    => (value :: results, exceptions)
              }
            exceptions.traverse(Logger[IO].error) *> details.pure
          )
