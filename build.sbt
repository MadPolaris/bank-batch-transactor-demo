ThisBuild / organization := "net.imadz"
ThisBuild / organizationName := "MadPolaris"
ThisBuild / organizationHomepage := Some(url("https://github.com/MadPolaris"))
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / versionScheme := Some("early-semver")

licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

ThisBuild / version := "0.2"

val akkaVersion = "2.6.20"
val AkkaProjectionVersion = "1.2.5"
val ScalikeJdbcVersion = "3.5.0"
val MongoPluginVersion = "3.0.8"

resolvers ++= Seq(
  "Aliyun" at "https://maven.aliyun.com/repository/public/"
)

Compile / scalacOptions ++= Seq("-target:11", "-deprecation", "-feature", "-unchecked", "-Xlint")
Compile / javacOptions ++= Seq("--release", "11")

dependencyOverrides ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
  "com.typesafe.akka" %% "akka-distributed-data" % akkaVersion,
  "com.typesafe.akka" %% "akka-coordination" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-pki" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  "org.mongodb.scala" %% "mongo-scala-driver" % "4.3.4",
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "org.reactivemongo" %% "reactivemongo" % "1.1.0-noshaded-RC11",
  "com.typesafe.play" %% "play-json" % "2.9.3",
  "com.google.guava" % "guava" % "30.1.1-jre",
  "com.typesafe.akka" %% "akka-http-core" % "10.2.7",
  "org.slf4j" % "slf4j-api" % "2.0.4",
  "org.scalatest" %% "scalatest" % "3.2.15" % Test,
  "ch.qos.logback" % "logback-classic" % "1.4.5" % Test
)

// bank-batch-transactor-demo — financial batch transaction processing demo built on monarch-core.
name := "bank-batch-transactor-demo"

// Standalone demo: never published to Maven Central.
publish / skip := true

enablePlugins(JavaAppPackaging)

libraryDependencies ++= Seq(
  "net.imadz" %% "common-core" % "0.2",
  "net.imadz" %% "monarch-core" % "0.2",
  "net.imadz" %% "monarch-persistence" % "0.2",
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
  "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.14",
  "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.14" % Test,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.2.15" % Test,
  "ch.qos.logback" % "logback-classic" % "1.4.5" % Test
)

Compile / PB.targets := Seq(
  scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)
