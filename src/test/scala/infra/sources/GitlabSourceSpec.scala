package infra.sources

import org.scalatest._
import org.scalatest.OptionValues.convertOptionToValuable
import flatspec._
import org.scalatest.matchers.should.Matchers._
import org.scalactic.Explicitly._
import cats._
import cats.implicits._
import org.legogroup.woof.{given, *}
import org.legogroup.woof.Logger.StringLocal
import infra.GitlabApi
import domain.dependency.Dependency
import domain.registry._
import infra.responses.RepositoryTreeFile
import infra.responses.RepositoryFile
import infra.responses.RepositoryTree

class GitlabSourceSpec extends AnyFlatSpec {
  import GitlabSourceSpec.{given, *}

  "Extract" should "return an empty list if failed to get the project's concrete file" in {
    GitlabSource
      .make(failingFileApi, testContentParser)
      .extract(testProject)
      .head
      .items shouldBe empty
  }

  "Extract" should "return the list of dependencies" in {
    val tree = List(RepositoryTreeFile("foo", "foo"))
    val file = RepositoryFile("")

    val dependencies =
      GitlabSource
        .make(dataGitlabApi(tree, file), testContentParser)
        .extract(testProject)
        .flatMap(_.items)

    dependencies should contain only (testDependencies.head, testDependencies.tail.head)
  }
}

object GitlabSourceSpec {
  val testProject = Project(
    id = "123",
    name = "test-project",
    sources = List(DependencySource.TxtSource(path = "requirements.txt"))
  )
  val testDependencies =
    List(Dependency("baz", None), Dependency("quux", "1.2.3".some))
  val testContentParser = (sourcee: DependencySource) =>
    (content: String) => testDependencies

  val failingFileApi =
    makeApi(RuntimeException("Unable to get the file").asLeft)

  def dataGitlabApi(tree: RepositoryTree, file: RepositoryFile) =
    makeApi(file.asRight)

  def makeApi(
      fileResult: Either[Throwable, RepositoryFile]
  ) = new GitlabApi[Id] {
    override def getFile(
        id: String,
        branch: String,
        filePath: String
    ): Id[Either[Throwable, RepositoryFile]] = fileResult
  }

  given Logger[Id] = new Logger[Id] {

    // Theorically unsafe, but since it doesn't seem to get called then... who cares?
    override val stringLocal: StringLocal[Id] = null

    override def doLog(level: LogLevel, message: String)(using
        LogInfo
    ): Id[Unit] = ()

  }
}