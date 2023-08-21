package scanning.application

import core.application.controller.Controller
import org.http4s.{ EntityDecoder, HttpRoutes, MediaType }
import org.http4s.headers.*
import org.http4s.dsl.Http4sDsl
import org.http4s.*
import cats.{ Monad, MonadError }
import cats.syntax.all.*
import scalatags.Text.all.*
import scalatags.Text.TypedTag
import core.domain.project.ProjectScanConfig
import core.domain.dependency.DependencySource.{ TomlSource, TxtSource }
import org.legogroup.woof.{ *, given }
import cats.effect.kernel.{ Concurrent, Sync }
import core.domain.project.Project
import io.circe.*
import io.circe.syntax.*
import org.http4s.circe.*
import org.http4s.server.Router
import cats.Show
import scalatags.generic.AttrPair
import scalatags.text.Builder

object ProjectController:
  // TODO: Move this to a dedicated module
  // And mode ScanningViews' layout to a shared module (/lib?)
  import ProjectViews.*
  import ProjectPayloads.*

  type ThrowableMonadError[F[_]] = MonadError[F, Throwable]

  def make[F[_]: Monad: ThrowableMonadError: Logger: Concurrent](
      service: ProjectService[F]
  ): Controller[F] =
    new Controller[F] with Http4sDsl[F]:
      private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F]:
        case GET -> Root =>
          service
            .all
            .map: projects =>
              views.layout(renderProjects(projects))
            .flatMap: html =>
              Ok(html.toString, `Content-Type`(MediaType.text.html))
            .handleErrorWith: error =>
              Logger[F].error(error.toString)
                *> InternalServerError("Oops, something went wrong")
        case req @ POST -> Root =>
          req
            .as[ProjectPayload]
            .flatMap: payload =>
              service.add(payload.toDomain)
                *> Ok(renderProjectForm(info =
                  s"Project ${payload.name} added successfully".some
                ).toString)
            .handleErrorWith: error =>
              Logger[F].error(error.toString)
                *> InternalServerError("Oops, something went wrong")
        case GET -> Root / "new" =>
          Ok(
            views.layout(renderProjectForm(info = None)).toString,
            `Content-Type`(MediaType.text.html)
          )
        case GET -> Root / projectName / "detailed" =>
          service
            .find(projectName)
            .flatMap:
              case None => ???
              case Some(project) => Ok(
                  renderProjectDetails(project).toString,
                  `Content-Type`(MediaType.text.html)
                )
        case GET -> Root / projectName / "short" =>
          service
            .find(projectName)
            .flatMap:
              case None => ???
              case Some(project) => Ok(
                  renderProjectShort(project).toString,
                  `Content-Type`(MediaType.text.html)
                )
        case PATCH -> Root / projectName / "enable" =>
          service.setEnabled(projectName, true).flatMap:
            case None => ???
            case Some(project) => Ok(
                renderProjectDetails(project).toString,
                `Content-Type`(MediaType.text.html)
              )
        case PATCH -> Root / projectName / "disable" =>
          service.setEnabled(projectName, false).flatMap:
            case None => ???
            case Some(project) => Ok(
                renderProjectDetails(project).toString,
                `Content-Type`(MediaType.text.html)
              )

      val routes: HttpRoutes[F] = Router("project" -> httpRoutes)

private object ProjectPayloads:
  type VariadicString = List[String] | String
  object VariadicString:
    given Decoder[VariadicString] with
      final def apply(c: HCursor): Decoder.Result[VariadicString] =
        c.as[List[String]] match
          case Left(_)   => c.as[String]
          case Right(xs) => xs.pure
    given decoder[F[_]: Concurrent]: EntityDecoder[F, VariadicString] =
      jsonOf[F, VariadicString]

  import VariadicString.given

  case class ProjectPayload(
      name: String,
      gitlabId: Int,
      branch: String,
      `txtSources[path]`: Option[List[String] | String] = None,
      `tomlSources[path]`: Option[List[String] | String] = None,
      `tomlSources[group]`: Option[List[String] | String] = None
  ) derives Decoder:
    def toDomain: ProjectScanConfig =
      val txtSources = `txtSources[path]`
        .map:
          case path: String        => List(TxtSource(path))
          case paths: List[String] => paths.map(TxtSource.apply)
        .getOrElse(List.empty)
      val tomlSources = `tomlSources[path]`.zip(`tomlSources[group]`)
        .map:
          case (path: String, group: String) =>
            List(TomlSource(path, group.some))
          case (paths: List[String], groups: List[String]) =>
            paths.zip(groups).map: (path, group) =>
              TomlSource(path, group.some)
          case _ => List.empty // TODO: This should be a validation failure
        .getOrElse(List.empty)
      val enabled = true
      ProjectScanConfig(
        Project(gitlabId.toString, name),
        txtSources ++ tomlSources,
        enabled,
        branch
      )
  object ProjectPayload:
    implicit def decoder[F[_]: Concurrent]: EntityDecoder[F, ProjectPayload] =
      jsonOf[F, ProjectPayload]

private object ProjectViews:
  def renderProjects(projects: List[ProjectScanConfig]) =
    div(
      cls := "container mx-auto my-10",
      h2(
        cls := "text-center font-semibold text-5xl",
        "Registered projects"
      ),
      a(
        href := "/project/new",
        cls  := "block w-full my-3 p-4 bg-teal-500 text-gray-200 border-2 border-gray-700 cursor-pointer text-center",
        "Add new project"
      ),
      button(
        cls                    := "block w-full my-3 p-4 bg-orange-500 text-gray-200 border-2 border-gray-700 cursor-pointer text-center",
        htmx.ajax.post         := "/scan/all",
        htmx.trigger.attribute := htmx.trigger.value.click,
        htmx.swap.attribute    := htmx.swap.value.outerHTML,
        "Scan all projects"
      ),
      div(
        cls := "my-5",
        projects.map(renderProjectShort)
      )
    )

  def renderProjectShort(config: ProjectScanConfig) =
    // TODO: Add some animations when details unfold
    div(
      id  := config.project.name,
      cls := "my-3 p-3 bg-gray-800 text-gray-300 border-2 border-gray-700 cursor-pointer",
      div(
        cls := "flex justify-between",
        p(
          cls                   := "grow text-2xl",
          htmx.ajax.get         := s"/project/${config.project.name}/detailed",
          htmx.swap.attribute   := htmx.swap.value.outerHTML,
          htmx.target.attribute := s"#${config.project.name}",
          config.project.name
        ),
        div(
          cls := "my-auto",
          a(
            cls                    := "bg-orange-500 m-1 py-2 px-3 text-gray-100 cursor-pointer",
            htmx.ajax.post         := s"/scan/${config.project.name}",
            htmx.trigger.attribute := htmx.trigger.value.click,
            htmx.swap.attribute    := htmx.swap.value.outerHTML,
            "Scan"
          ),
          a(
            cls  := "bg-teal-500 m-1 py-2 px-3 text-gray-100",
            href := s"/scan-report/${config.project.name}/latest",
            "Scan report"
          )
        )
      )
    )

  private val checkboxClass = List(
    "mr-2",
    "mt-[0.3rem]",
    "h-3.5",
    "w-8",
    "appearance-none",
    "rounded-[0.4375rem]",
    "bg-neutral-300",
    "before:pointer-events-none",
    "before:absolute",
    "before:h-3.5",
    "before:w-3.5",
    "before:rounded-full",
    "before:bg-transparent",
    "before:content-['']",
    "after:absolute",
    "after:z-[2]",
    "after:-mt-[0.1875rem]",
    "after:h-5",
    "after:w-5",
    "after:rounded-full",
    "after:border-none",
    "after:bg-neutral-100",
    "after:shadow-[0_0px_3px_0_rgb(0_0_0_/_7%),_0_2px_2px_0_rgb(0_0_0_/_4%)]",
    "after:transition-[background-color_0.2s,transform_0.2s]",
    "after:content-['']",
    "checked:bg-teal-500",
    "checked:after:absolute",
    "checked:after:z-[2]",
    "checked:after:-mt-[3px]",
    "checked:after:ml-[1.0625rem]",
    "checked:after:h-5",
    "checked:after:w-5",
    "checked:after:rounded-full",
    "checked:after:border-none",
    "checked:after:bg-primary",
    "checked:after:shadow-[0_3px_1px_-2px_rgba(0,0,0,0.2),_0_2px_2px_0_rgba(0,0,0,0.14),_0_1px_5px_0_rgba(0,0,0,0.12)]",
    "checked:after:transition-[background-color_0.2s,transform_0.2s]",
    "checked:after:content-['']",
    "hover:cursor-pointer",
    "focus:outline-none",
    "focus:ring-0",
    "focus:before:scale-100",
    "focus:before:opacity-[0.12]",
    "focus:before:shadow-[3px_-1px_0px_13px_rgba(0,0,0,0.6)]",
    "focus:before:transition-[box-shadow_0.2s,transform_0.2s]",
    "focus:after:absolute",
    "focus:after:z-[1]",
    "focus:after:block",
    "focus:after:h-5",
    "focus:after:w-5",
    "focus:after:rounded-full",
    "focus:after:content-['']",
    "checked:focus:border-primary",
    "checked:focus:bg-primary",
    "checked:focus:before:ml-[1.0625rem]",
    "checked:focus:before:scale-100",
    "checked:focus:before:shadow-[3px_-1px_0px_13px_#3b71ca]",
    "checked:focus:before:transition-[box-shadow_0.2s,transform_0.2s]"
  ).mkString(" ")

  def renderProjectDetails(config: ProjectScanConfig) =
    val toggleUrl =
      if config.enabled
      then s"/project/${config.project.name}/disable"
      else s"/project/${config.project.name}/enable"
    var enabledCheckboxAttrs = List(
      cls                    := checkboxClass,
      `type`                 := "checkbox",
      role                   := "switch",
      htmx.ajax.patch        := toggleUrl,
      htmx.trigger.attribute := htmx.trigger.value.change,
      htmx.swap.attribute    := htmx.swap.value.outerHTML,
      htmx.target.attribute := htmx.target.value.closest(
        s"#${config.project.name}"
      )
    )
    if config.enabled
    then enabledCheckboxAttrs = (checked := "1") :: enabledCheckboxAttrs

    div(
      id  := config.project.name,
      cls := "my-3 p-3 bg-gray-800 text-gray-300 border-2 border-gray-700 cursor-pointer divide-y divide-gray-700",
      div(
        cls := "pb-3 flex justify-between",
        div(
          cls                   := "grow text-2xl",
          htmx.ajax.get         := s"/project/${config.project.name}/short",
          htmx.swap.attribute   := htmx.swap.value.outerHTML,
          htmx.target.attribute := s"#${config.project.name}",
          config.project.name
        ),
        div(
          cls := "my-auto",
          a(
            cls                    := "bg-orange-500 m-1 py-2 px-3 text-gray-100 cursor-pointer",
            htmx.ajax.post         := s"/scan/${config.project.name}",
            htmx.trigger.attribute := htmx.trigger.value.click,
            htmx.swap.attribute    := htmx.swap.value.outerHTML,
            "Scan"
          ),
          a(
            cls  := "bg-teal-500 m-1 py-2 px-3 text-gray-100",
            href := s"/scan-report/${config.project.name}/latest",
            "Scan report"
          )
        )
      ),
      div(
        cls := "pt-3",
        p(span(cls := "font-semibold", "Gitlab ID: "), config.project.id),
        p(span(cls := "font-semibold", "Target branch: "), config.branch),
        p(
          span(cls := "font-semibold", "Enabled: "),
          input(enabledCheckboxAttrs)
        ),
        div(
          cls := "grid grid-cols-1 divide-y divide-gray-700 divide-dashed",
          span(cls := "font-semibold", "Sources:"),
          config
            .sources
            .map:
              case TxtSource(path) => path
              case TomlSource(path, group) =>
                val suffix = group
                  .map: group =>
                    s":$group"
                  .getOrElse("")
                s"$path:$suffix"
            .map: str =>
              p(cls := "pl-3", str)
        )
      )
    )

  def renderProjectForm(info: Option[String]) =
    div(
      id  := "form-start",
      cls := "container mx-auto my-10",
      h2(
        cls := "text-center font-semibold text-3xl",
        "Create a new project config"
      ),
      div(
        cls := "w-full max-w-md mx-auto mt-5",
        info
          .map: info =>
            div(
              cls := "info bg-teal-100 flex p-2 my-2 text-sm text-gray-900",
              p(cls := "mr-auto", info),
              button(
                `type`  := "button",
                onclick := "removeClosest(this, '.info')",
                "X"
              )
            )
          .getOrElse(span()),
        form(
          cls                   := "text-sm font-bold",
          htmx.ajax.post        := "/project",
          htmx.target.attribute := htmx.target.value.closest("#form-start"),
          htmx.swap.attribute   := htmx.swap.value.outerHTML,
          attr("hx-ext")        := "json-enc",
          div(
            cls := "mb-4",
            formLabel("name", "Name"),
            FormInput.asHtml(FormInput.Text(
              cls = formInputClass,
              name = "name",
              required = true
            ))
          ),
          div(
            cls := "mb-4",
            formLabel("gitlabId", "Gitlab ID"),
            FormInput.asHtml(FormInput.Number(
              cls = formInputClass,
              name = "gitlabId",
              required = true,
              min = 0.some
            ))
          ),
          div(
            cls := "mb-4",
            formLabel("branch", "Branch"),
            FormInput.asHtml(FormInput.Text(
              cls = formInputClass,
              name = "branch",
              required = true,
              value = "master".some
            ))
          ),
          div(
            cls := "mb-4",
            formLabel("sources", "Sources"),
            div(
              cls := "border border-2 border-gray-400",
              div(
                button(
                  cls     := "bg-green-500 w-1/2 py-2",
                  onclick := "addTxtInput()",
                  `type`  := "button",
                  "+ TXT"
                ),
                button(
                  cls     := "bg-lime-500 w-1/2 py-2",
                  onclick := "addTomlInput()",
                  `type`  := "button",
                  "+ TOML"
                )
              ),
              div(
                id  := "sources",
                cls := "grid grid-cols-1 divide-y-2 divide-gray-700 divide-dashed"
              )
            )
          ),
          button(cls := "w-full bg-teal-500 py-2 px-3", "Submit")
        ),
        txtSourceInputTemplate,
        tomlSourceInputTemplate
      )
    )

  private val formInputClass =
    "shadow appearance-none border w-full py-2 px-3 text-gray-300 leading-tight focus:outline-none focus:shadow-outline focus:border-teal-200 bg-gray-900"

  private def txtSourceInputTemplate =
    div(
      id  := "txt-source-template",
      cls := "p-3 form-group hidden",
      div(
        cls := "flex",
        h4(cls := "w-full mb-3", "TXT source"),
        button(
          cls     := "px-3 bg-teal-500",
          onclick := "removeClosest(this, '.form-group')",
          `type`  := "button",
          "X"
        )
      ),
      formLabel("txtSources[path]", "Path"),
      FormInput.asHtml(FormInput.Text(
        cls = formInputClass,
        name = s"txtSources[path]",
        required = true,
        value = "requirements.txt".some
      ))
    )

  private def tomlSourceInputTemplate =
    div(
      id  := "toml-source-template",
      cls := "p-3 form-group hidden",
      div(
        cls := "flex",
        h4(cls := "w-full mb-3", "TOML source"),
        button(
          cls     := "px-3 bg-teal-500",
          onclick := "removeClosest(this, '.form-group')",
          `type`  := "button",
          "X"
        )
      ),
      div(
        formLabel("tomlSources[path]", "Path"),
        FormInput.asHtml(FormInput.Text(
          cls = formInputClass,
          name = s"tomlSources[path]",
          required = true,
          value = "pyproject.toml".some
        ))
      ),
      div(
        formLabel("tomlSources[group]", "Group"),
        FormInput.asHtml(FormInput.Text(
          cls = formInputClass,
          name = s"tomlSources[group]",
          required = true,
          value = "tool.poetry.dependencies".some
        ))
      )
    )

  private def formLabel(elementName: String, labelText: String) =
    label(
      cls   := "block font-bold",
      `for` := elementName,
      labelText
    )

  enum FormInput:
    case Text(
        cls: String,
        name: String,
        required: Boolean = false,
        value: Option[String] = None
    ) extends FormInput
    case Number(
        cls: String,
        name: String,
        required: Boolean = false,
        min: Option[Int]
    ) extends FormInput
  object FormInput:
    def asHtml(formInput: FormInput): TypedTag[String] =
      formInput match
        case x @ FormInput.Text(_, _, _, _) =>
          var attrs = List(
            cls      := x.cls,
            name     := x.name,
            required := x.required,
            `type`   := "text"
          )
          x.value match
            case Some(inputVal) => attrs = (value := inputVal) :: attrs
            case None           =>
          input(attrs)
        case x @ FormInput.Number(_, _, _, _) =>
          var attrs: List[AttrPair[
            Builder,
            ? >: String & Boolean & Int <: String | Boolean | Int
          ]] = List(
            cls      := x.cls,
            name     := x.name,
            required := x.required,
            `type`   := "number"
          )
          x.min match
            case Some(value) => attrs = (min := value) :: attrs
            case None        =>
          input(attrs)
