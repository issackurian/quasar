package precog

import sbt._, Keys._

object PlatformBuild {
  val BothScopes = "compile->compile;test->test"

  def optimizeOpts = if (sys.props contains "precog.optimize") Seq("-optimize") else Seq()
  def debugOpts    = if (sys.props contains "precog.dev") Seq("-deprecation", "-unchecked") else Seq()

  def versionDeps(scalaVersion: String): Seq[ModuleID] = (
    if (scalaVersion startsWith "2.9") Seq(
      "com.typesafe.akka"  % "akka-actor"  % "2.0.5",
      "org.spire-math"     % "spire_2.9.2" % "0.3.0"
      // "org.specs2"        %% "specs2"      % "1.12.3" % Test
    ) else Seq(
      "org.spire-math" %% "spire"  % "0.3.0"
      // "org.specs2"     %% "specs2" %  "1.14"  % Test
    )
  )

  /** Watch out Jonesy! It's the ol' double-cross!
   *  Why, you...
   *
   *  Given a path like src/main/scala we want that to explode into something like the
   *  following, assuming we're currently building with java 1.7 and scala 2.10.
   *
   *    src/main/scala
   *    src/main/scala_2.10
   *    src/main_1.7/scala
   *    src/main_1.7/scala_2.10
   *
   *  Similarly for main/test, 2.10/2.11, 1.7/1.8.
   */
  def doubleCross(config: Configuration) = {
    unmanagedSourceDirectories in config ++= {
      val jappend = Seq("", "_" + javaSpecVersion)
      val sappend = Seq("", "_" + scalaBinaryVersion.value)
      val basis   = (sourceDirectory in config).value
      val parent  = basis.getParentFile
      val name    = basis.getName
      for (j <- jappend ; s <- sappend) yield parent / s"$name$j" / s"scala$s"
    }
  }

  def javaSpecVersion: String                       = sys.props("java.specification.version")
  def inBoth[A](f: Configuration => Seq[A]): Seq[A] = List(Test, Compile) flatMap f

  implicit class ProjectOps(val p: sbt.Project) {
    def noArtifacts: Project = also(
                publish := (()),
           publishLocal := (()),
         Keys.`package` := file(""),
             packageBin := file(""),
      packagedArtifacts := Map()
    )
    def root: Project                                 = p in file(".")
    def inTestScope: ClasspathDependency              = p % "test->test"
    def also(ss: Seq[Setting[_]]): Project            = p settings (ss: _*)
    def also(s: Setting[_], ss: Setting[_]*): Project = also(s +: ss.toSeq)
    def deps(ms: ModuleID*): Project                  = also(libraryDependencies ++= ms.toSeq)
    def compileArgs(args: String*): Project           = also(scalacOptions in Compile ++= args.toList)

    def setup: Project = also(
                   organization :=  "com.precog",
                        version :=  "2.6.1-SNAPSHOT",
                  scalacOptions ++= Seq("-g:vars") ++ optimizeOpts ++ debugOpts,
                   javacOptions ++= Seq("-source", "1.6", "-target", "1.6"),
                   scalaVersion :=  "2.10.6",
             crossScalaVersions :=  Seq("2.9.3", "2.10.6"),
      parallelExecution in Test :=  false,
            logBuffered in Test :=  false,
                       ivyScala :=  ivyScala.value map (_.copy(overrideScalaVersion = true)),
                      resolvers +=  "Akka Repo" at "http://repo.akka.io/repository"
    ) also inBoth(doubleCross)

    // .compileArgs("-Ywarn-numeric-widen")
    // compileArgs("-Xlog-implicits")
  }
}
