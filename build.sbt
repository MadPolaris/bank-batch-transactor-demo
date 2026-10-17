ThisBuild / organization := "net.imadz"
ThisBuild / organizationName := "MadPolaris"
ThisBuild / organizationHomepage := Some(url("https://github.com/MadPolaris"))
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / version := "0.2"

val akkaVersion = "2.6.20"

ThisBuild / resolvers ++= Seq(
  "Aliyun" at "https://maven.aliyun.com/repository/public/"
)

ThisBuild / scalacOptions ++= Seq("-target:11", "-deprecation", "-feature", "-unchecked", "-Xlint")
ThisBuild / javacOptions ++= Seq("--release", "11")

lazy val akkaDeps = Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion
)

// ------------------------------------------------------------------
// core: 引擎（Monarch 六阶段链 + monarch-persistence 持久化 + protobuf 快照）
// ------------------------------------------------------------------
lazy val core = (project in file("core"))
  .settings(
    name := "bank-batch-transactor-core",
    // Standalone demo: never published to Maven Central.
    publish / skip := true,
    libraryDependencies ++= Seq(
      "net.imadz" %% "common-core" % "0.2",
      "net.imadz" %% "monarch-core" % "0.2",
      "net.imadz" %% "monarch-persistence" % "0.2",
      "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.14",
    ) ++ akkaDeps ++ Seq(
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,
      "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.14" % Test,
      "org.scalatest" %% "scalatest" % "3.2.15" % Test,
      "ch.qos.logback" % "logback-classic" % "1.4.5" % Test
    ),
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
    )
  )

// ------------------------------------------------------------------
// web: Play 网页（银行批量演示：页面 + WebSocket + API）
// ------------------------------------------------------------------
lazy val web = (project in file("web"))
  .enablePlugins(PlayScala)
  .dependsOn(core)
  .settings(
    name := "bank-batch-transactor-web",
    publish / skip := true,
    libraryDependencies ++= akkaDeps ++ Seq(
      "net.imadz" %% "monarch-persistence" % "0.2",
      "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.14",
      "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test
    ),
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
    )
  )

// Root: aggregate only.
lazy val root = (project in file("."))
  .aggregate(core, web)
  .settings(
    name := "bank-batch-transactor-demo",
    publish / skip := true
  )
