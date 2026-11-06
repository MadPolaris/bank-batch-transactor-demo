// Without an explicit R2 version, keep the original dependency graph.
// Play-only source/route isolation lives in project/MonarchR2Profile.scala.
ThisBuild / dependencyOverrides ++= sys.props.get("monarch.r2.version").toSeq.flatMap { version =>
  Seq("net.imadz" %% "monarch-persistence" % version,
    "net.imadz" %% "monarch-core" % sys.props.getOrElse("monarch.core.version", "0.2.1"))
}

ThisBuild / resolvers ++=
  sys.props.get("monarch.r2.repository").toSeq.map("Monarch R2 candidate" at _) ++
  sys.props.get("monarch.core.repository").toSeq.map("Monarch core candidate" at _)
