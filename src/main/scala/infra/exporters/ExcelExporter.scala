package infra.exporters

import spoiwo.model.{Row, Sheet, Font, Workbook, CellStyle}
import spoiwo.natures.xlsx.Model2XlsxConversions._
import org.apache.poi.ss.usermodel.Cell
import spoiwo.model.Color
import spoiwo.model.enums.CellFill
import cats._
import cats.implicits._
import domain.project.ExportProjectDependencies
import domain.dependency.DependencyReport
import domain.semver._
import domain.severity._
import domain.Exporter

object ExcelExporter:
  def make[F[_]: Applicative, A](
      toSheet: A => Sheet,
      path: String
  ): Exporter[F, A] = new Exporter[F, A]:

    override def exportData(data: List[A]): F[Unit] =
      val workbook = Workbook().withSheets(data.map(toSheet))
      workbook.saveAsXlsx(path).pure

  object dependencies:
    def toSheet(repoDependencies: ExportProjectDependencies): Sheet =
      val rows = repoDependencies.dependenciesReports.flatMap { group =>
        val groupName =
          Row(style = headerStyle).withCellValues("Source:", group.groupName)
        val tableDescription = Row(style = headerStyle)
          .withCellValues(
            "Name",
            "Current Version",
            "Latest Version",
            "Vulnerabilities",
            "Notes"
          )
        (groupName :: tableDescription :: group.items
          .map(d =>
            Row(style = chooseStyle(d)).withCellValues(
              d.name,
              d.currentVersion.getOrElse(""),
              d.latestVersion,
              d.vulnerabilities.mkString(",\n"),
              d.notes.getOrElse("")
            )
          )) :+ Row() // Add pseudo "margin-bottom"
      }
      Sheet(name = repoDependencies.project.name).withRows(rows: _*)

    private val headerStyle = CellStyle(font = Font(bold = true))

    private val chooseStyle = determineSeverity.andThen(matchSeverityToStyle)

    private def matchSeverityToStyle(severity: Severity): CellStyle =
      severity match
        case Severity.Unknown => CellStyle()

        case Severity.None =>
          CellStyle(
            fillForegroundColor = Color.Green,
            fillPattern = CellFill.Solid
          )

        case Severity.Low =>
          CellStyle(
            fillForegroundColor = Color.LightGreen,
            fillPattern = CellFill.Solid
          )

        case Severity.Medium =>
          CellStyle(
            fillForegroundColor = Color.Yellow,
            fillPattern = CellFill.Solid
          )

        case Severity.High =>
          CellStyle(
            fillForegroundColor = Color.Red,
            fillPattern = CellFill.Solid
          )