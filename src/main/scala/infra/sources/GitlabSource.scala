package infra.sources

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

import cats.*
import cats.implicits.*
import domain.dependency.*
import domain.registry.DependencySource.{ TomlSource, TxtSource }
import domain.registry.*
import domain.{ Grouped, Source }
import infra.parsers.python.{ PyProjectToml, RequirementsTxt }
import infra.{ GitlabApi, RepositoryFile }
import org.legogroup.woof.{ *, given }

object GitlabSource:
  case class GitlabProps(host: String, token: Option[String])

  def make[F[_]: Monad: Logger](
      api: GitlabApi[F],
      contentParser: DependencySource => String => List[Dependency] =
        defaultContentParser
  ): Source[F, Project] = new:
    def extract(project: Project): F[List[Grouped[Dependency]]] =
      project.sources
        .traverse(source =>
          extractFromFile(project, source.path, contentParser(source))
            .map(dependencies => Grouped(source.groupName, dependencies))
        )

    private def extractFromFile(
        project: Project,
        filePath: String,
        contentExtractor: String => List[Dependency]
    ): F[List[Dependency]] =
      api
        .getFile(project.id, project.branch, filePath)
        .flatMap {
          case Left(reason) =>
            Logger[F].error(
              s"Could not get the file contents of ${project.name} and $filePath due to $reason"
            ) *> List.empty.pure

          case Right(RepositoryFile(content)) =>
            GitlabApi.decodeContent(content) match
              case Left(_) =>
                Logger[F].error(
                  s"Could not decode content of ${project.name}'s $filePath"
                ) *> List.empty.pure

              case Right(decodedContent) =>
                contentExtractor(decodedContent).pure
        }

  def defaultContentParser(
      source: DependencySource
  ): String => List[Dependency] =
    source match
      case TxtSource(path) => RequirementsTxt.extract
      case TomlSource(path, group) =>
        PyProjectToml.extract(group).andThen(_.getOrElse(List.empty))
