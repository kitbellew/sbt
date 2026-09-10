/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package testpkg

import java.io.File
import java.lang.ProcessBuilder.Redirect

import sbt.io.IO
import sbt.io.syntax.*

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Properties.isLinux

/**
 * An sbt server that finds another one serving the build keeps the build loaded and serves
 * nothing. This covers what becomes of the portfile when a client deletes it while such a
 * server is sitting there.
 */
class PortfileTakeoverTest extends AbstractServerTest:
  override val testDirectory: String = "client"

  private val settle = 90.seconds

  private def portfile: File = svr.baseDirectory / "project" / "target" / "active.json"

  /**
   * Another sbt server on this build. It finds the socket taken, loads the project again, and
   * then waits in the shell with the build loaded.
   */
  private def anotherServer(reloading: Boolean): Process =
    val java = new File(new File(System.getProperty("java.home"), "bin"), "java").toString
    val classpath = TestProperties.classpath
    val jvm = List(java, "-Djline.terminal=none", "-Dsbt.io.virtual=false", "-Dsbt.banner=false")
    val ivy = sys.props.get("sbt.ivy.home").map(h => s"-Dsbt.ivy.home=$h").toList
    val main = List("-cp", classpath, "sbt.RunFromSourceMain")
    val args = List(
      svr.baseDirectory.toString,
      TestProperties.scalaVersion,
      TestProperties.version,
      classpath,
      "startServer",
    ) ++ (if reloading then List("reload") else Nil) ++ List(
      "markLoaded",
      "shell",
    )
    val options = jvm ::: ivy ::: main ::: args
    val builder = new ProcessBuilder(options.asJava)
    /*
     * Inherited, this tells the child a thin client started it, and such an sbt server shuts
     * down as soon as it finds it serves nothing.
     */
    builder.environment.remove("SBT_TERMINAL_PROPS")
    builder
      .directory(svr.baseDirectory)
      .redirectOutput(Redirect.DISCARD)
      .redirectErrorStream(true)
      .start()
  end anotherServer

  /* Linux alone: forking a second sbt server is expensive, and this behaviour is the same
   * everywhere. */
  test("a portfile deleted while another sbt server is loaded") {
    if isLinux then
      val marker = svr.baseDirectory / "loaded.txt"
      IO.delete(marker)
      val another = anotherServer(reloading = true)
      try
        assert(waitUntil(settle)(marker.exists), "another sbt server loads the build twice")
        val owner = IO.read(portfile)
        IO.delete(portfile)
        assert(!waitUntil(settle)(portfile.exists), "the portfile stays deleted")
      finally another.destroy()
  }

  /* Linux alone, for the same reason. */
  test("a portfile deleted while another sbt server waits") {
    if isLinux then
      val marker = svr.baseDirectory / "loaded.txt"
      IO.delete(marker)
      val another = anotherServer(reloading = false)
      try
        assert(waitUntil(settle)(marker.exists), "another sbt server loads the build")
        val owner = IO.read(portfile)
        IO.delete(portfile)
        assert(waitUntil(settle)(portfile.exists), "a portfile appears again")
        assert(IO.read(portfile) != owner, "it names the sbt server that was waiting")
      finally another.destroy()
  }
end PortfileTakeoverTest
