package parsers.python

import java.nio.file.{ Files, Paths, StandardOpenOption }
import java.util.UUID

import scala.sys.process.*

import cats.syntax.all.*
import cats.effect.kernel.Sync

case class PoetryFiles(pyProjectContent: String, lockContent: String)

trait Poetry[F[_]]:
  def update(
      packageName: String,
      from: String,
      to: String,
      files: PoetryFiles
  ): F[PoetryFiles]

object Poetry:
  def make[F[_]: Sync]: Poetry[F] = new:
    def update(
        packageName: String,
        from: String,
        to: String,
        files: PoetryFiles
    ): F[PoetryFiles] =
      val newPyProjectContent =
        updatePackage(packageName, from, to, files.pyProjectContent)
      val newFiles = files.copy(pyProjectContent = newPyProjectContent)
      updateLock(newFiles).map: newLockContent =>
        newFiles.copy(lockContent = newLockContent)

    private def updatePackage(
        packageName: String,
        from: String,
        to: String,
        pyProjectContent: String
    ): String =
      pyProjectContent
        .split("\n")
        .map: line =>
          val index                = line.indexOf(packageName)
          val indexOfCharAfterName = index + packageName.length
          val isLineNameAndVersion = index == 0
            && line.length > indexOfCharAfterName
            && line(indexOfCharAfterName) == ' '
          if isLineNameAndVersion then
            line.replace(from, to)
          else
            line
        .mkString("\n") + "\n"

    private def updateLock(files: PoetryFiles): F[String] =
      // TODO: Handle errors in case poetry doesn't exists, etc.
      Sync[F].delay:
        val dir           = Paths.get(s"./data/poetry/${UUID.randomUUID()}")
        val pyProjectPath = dir.resolve(Paths.get("pyproject.toml"))
        val lockPath      = dir.resolve(Paths.get("poetry.lock"))

        Files.createDirectories(dir)
        Files.write(
          pyProjectPath,
          files.pyProjectContent.getBytes,
          StandardOpenOption.CREATE
        )
        Files.write(
          lockPath,
          files.lockContent.getBytes,
          StandardOpenOption.CREATE
        )

        s"poetry lock --directory=$dir".!!
        val newLockContent = Files.readString(lockPath)

        Files.deleteIfExists(pyProjectPath)
        Files.deleteIfExists(lockPath)

        newLockContent
