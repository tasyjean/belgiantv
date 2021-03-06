import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {

  val appName         = "belgiantv"
  val appVersion      = "1.0-SNAPSHOT"

  val appDependencies = Seq(
    // Add your project dependencies here,
    jdbc,
    anorm,
    "org.jsoup" % "jsoup" % "1.7.1",
    "commons-lang" % "commons-lang" % "2.6",
    //"net.vz.mongodb.jackson" %% "play-mongo-jackson-mapper" % "1.0.0"
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.1.3",
    "org.reactivemongo" %% "reactivemongo" % "0.8",
    "org.reactivemongo" %% "play2-reactivemongo" % "0.8"  // cross CrossVersion.full
  )

  // copying jvm parameters for testing:
  // http://play.lighthouseapp.com/projects/82401/tickets/981-overriding-configuration-for-tests
  val extraJavaOptions = List("TMDB_API_KEY", "TOMATOES_API_KEY", "MONGOLAB_URI").map( property =>
    Option(System.getProperty(property)).map{value =>
       "-D" + property + "=" + value
    }).flatten
  extraJavaOptions.foreach(o => println("Adding test scope jvm option: " + o))

  val main = play.Project(appName, appVersion, appDependencies).settings(
    //resolvers += "sgodbillon" at "https://bitbucket.org/sgodbillon/repository/raw/master/snapshots/"
    javaOptions in test ++= extraJavaOptions
  ).settings(
    net.virtualvoid.sbt.graph.Plugin.graphSettings: _*
  )



}
