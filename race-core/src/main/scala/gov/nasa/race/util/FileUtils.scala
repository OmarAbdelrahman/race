/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.nasa.race.util

import java.io._
import java.nio.ByteBuffer
import java.nio.charset.{CharsetDecoder, StandardCharsets}
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import scala.io.BufferedSource

import StringUtils._
import gov.nasa.race._

/**
 * common file related functions
 */
object FileUtils {

  @inline def file (pathName: String): File = new File(pathName)

  def fileContentsAsUTF8String(file: File): Option[String] = {
    if (file.isFile) {
      Some(new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8))
    } else {
      None
    }
  }

  def fileContentsAsUTF8String(pathName: String): Option[String] = fileContentsAsUTF8String(new File(pathName))

  def resourceContentsAsUTF8String(cls: Class[_],fileName: String): Option[String] = {
    val is = cls.getResourceAsStream(fileName)
    if (is != null){
      val n = is.available()
      val bytes = new Array[Byte](n)
      is.read(bytes)
      Some(new String(bytes,StandardCharsets.UTF_8))
    } else None
  }

  def fileContentsAsChars(file: File): Option[Array[Char]] = {
    if (file.isFile) {
      val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder
      val in: ByteBuffer = ByteBuffer.wrap(Files.readAllBytes(file.toPath))
      Some(decoder.decode(in).array)
    } else None
  }

  def fileContentsAsChars(pathName: String): Option[Array[Char]] = fileContentsAsChars(new File(pathName))

  def fileContentsAsBytes(file: File): Option[Array[Byte]] = {
    if (file.isFile) Some(Files.readAllBytes(file.toPath)) else None
  }

  def existingFile(pathName: String): Option[File] = existingFile(new File(pathName))

  def existingFile(file: File, ext: String = null): Option[File] = {
    if (file.isFile) {
      return Some(file)
    } else if (ext != null) {
      val f = new File(s"${file.getPath}.$ext")
      if (f.isFile) return Some(f)
    }
    None
  }

  def isExistingNonEmptyFile(pathName: String) = existingNonEmptyFile(pathName).isDefined

  def existingNonEmptyFile(file: File, ext: String = null): Option[File] = {
    if (file.isFile) {
      if (file.length() > 0) return Some(file)
    } else if (ext != null) {
      val f = new File(s"${file.getPath}.$ext")
      if (f.isFile && f.length() > 0) return Some(f)
    }
    None
  }

  def existingNonEmptyFile(s: String): Option[File] = existingNonEmptyFile(new File(s))

  def inputStreamFor (f: File, bufLen: Int): Option[InputStream] = {
    if (f.isFile) {
      if (f.getName.endsWith(".gz")){
        Some( new GZIPInputStream( new FileInputStream(f)))
      } else {
        Some( new FileInputStream(f))
      }
    } else None
  }
  def inputStreamFor (pathName: String, bufLen: Int): Option[InputStream] = inputStreamFor(new File(pathName),bufLen)

  def ensureDir (pathName: String): Option[File] = ensureDir(new File(pathName))

  def ensureDir (dir: File): Option[File] = {
    if (!dir.isDirectory) {
      if (dir.mkdirs) Some(dir) else None
    } else Some(dir)
  }

  def ensureWritableDir(dir: File): Option[File] = {
    ensureDir(dir) match {
      case o@Some(dir) => if (dir.canWrite) o else None
      case None => None
    }
  }

  def ensureWriteable (file: File): Option[File] = {
    if (ensureWritableDir(file.getParentFile).isDefined){
      if (file.canWrite) Some(file) else None
    } else None
  }

  def ensureEmptyWritable (file: File): Option[File] = {
    if (ensureWritableDir(file.getParentFile).isDefined){
      if (file.isFile) {
        if (file.delete) Some(file) else None
      } else {
        Some(file) // nothing there yet but we know dir is writable
      }
    } else None
  }

  def using[T](is: InputStream)(f: (InputStream) => T): T = {
    try {
      f(is)
    } finally {
      is.close()
    }
  }

  def getLines(pathName: String): Iterator[String] = {
    inputStreamFor(pathName,4096) match {
      case Some(is) => new BufferedSource(is).getLines
      case None => Iterator.empty
    }
  }

  private def hexCheckSum(file: File, msgDigest: MessageDigest): Option[String] = {
    if (file.isFile) {
      using(new FileInputStream(file)) { fis =>
        val buf = new Array[Byte](8192)
        var len = fis.read(buf)
        while (len > 0) {
          msgDigest.update(buf, 0, len)
          len = fis.read(buf)
        }
        Some(toHexString(msgDigest.digest))
      }
    } else None
  }

  def sha1CheckSum(file: File) = hexCheckSum(file, MessageDigest.getInstance("SHA-1"))
  def md5CheckSum(file: File) = hexCheckSum(file, MessageDigest.getInstance("MD5")).map(padLeft(_, 32, ' '))

  /**
   * wrapper object for java.nio.file FileSystem matchers
   */
  class FileFinder(val dir: String, val globPattern: String) extends SimpleFileVisitor[Path] {
    val fs = FileSystems.getDefault
    val matcher = fs.getPathMatcher(s"glob:$dir/$globPattern")
    val base = fs.getPath(dir)
    var matches = Seq.empty[File]

    Files.walkFileTree(base, this)

    def find(file: Path): Unit = {
      if (matcher.matches(file)) {
        matches = matches :+ file.toFile
      }
    }

    def visitPath(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
      find(file)
      FileVisitResult.CONTINUE
    }

    override def visitFile(file: Path, attrs: BasicFileAttributes) = visitPath(file, attrs)
    override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = visitPath(dir, attrs)
    override def visitFileFailed(file: Path, exception: IOException) = FileVisitResult.CONTINUE
  }

  def getMatchingFilesIn(dir: String, globPattern: String): Seq[File] = {
    new FileFinder(dir, globPattern).matches
  }

  def isGlobPattern(pattern: String): Boolean = {
    (pattern.indexOf('*') >= 0) || (pattern.indexOf('?') >= 0)
  }

  def relUserHomePath(file: File) = {
    file.getAbsolutePath.substring(userHome.length + 1)
  }

  def relUserDirPath(file: File) = {
    file.getAbsolutePath.substring(userDir.length + 1)
  }

  def relPath(dir: File, file: File) = {
    val dirPath = dir.getAbsolutePath + '/'
    val filePath = file.getAbsolutePath
    if (filePath.startsWith(dirPath)) filePath.substring(dirPath.length) else file.getPath
  }

  def filename(path: String) = (new File(path)).getName

  def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      file.listFiles.foreach(deleteRecursively)
    }
    if (file.exists && !file.delete) {
      throw new Exception(s"deleteRecursively ${file.getAbsolutePath} failed")
    }
  }

  final val kB: Long = 1024
  final val MB: Long = kB * 1024
  final val GB: Long = MB * 1024
  final val TB: Long = GB * 1024

  def sizeString (nBytes: Long) = {
    // string formatting automatically does rounding according to specified decimals
    if (nBytes >= TB) f"${nBytes.toDouble/TB}%.1f TB"
    else if (nBytes >= GB) f"${nBytes.toDouble/GB}%.1f GB"
    else if (nBytes > MB) f"${nBytes.toDouble/MB}%.1f MB"
    else if (nBytes > kB) f"${nBytes.toDouble / kB}%.0f kB"
    else nBytes.toString
  }

  def processPathUpwards (path: String)(f: String=>Unit) = {
    var p = path
    while (p.nonEmpty) {
      f(p)
      p = p.substring(0,Math.max(p.lastIndexOf('/'),0))
    }
    if (path.startsWith("/")) f("/")
  }
}

class BufferedFileWriter (val file: File, val bufferSize: Int, val append: Boolean) extends CharArrayWriter(bufferSize) {
  def writeFile = {
    val fw = new FileWriter(file,append)
    fw.write(buf,0,count)
    fw.close
  }
}
