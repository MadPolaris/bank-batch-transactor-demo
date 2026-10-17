package net.imadz.m25.demo

import net.imadz.monarch.{LifecycleHooks, StageError, StageFailedException}
import net.imadz.m25.component._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/** Standalone runnable demo of the monarch-core engine through the bank batch chain.
  *
  * Run with `sbt run`, or build a self-contained distribution with `sbt stage`
  * and launch `./target/universal/stage/bin/bank-batch-transactor-demo`.
  *
  * Scenario 1 — happy path: 3 payment items flow through the six mechanical stages
  * (file-gen → upload → wait-ack → poll → parse → classify) against a simulated
  * external bank system, ending in Success / Failure / Suspicious classifications.
  *
  * Scenario 2 — failure path: the external system stays silent, the wait-ack stage
  * fails with a classified ACK_TIMEOUT StageFailedException — the same failure
  * vocabulary the production chain journals.
  */
object BankBatchDemo {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private def delayed[T](ms: Long)(body: => T): Future[T] = Future {
    Thread.sleep(ms)
    body
  }

  /** A fake "bank host": each stage pauses a beat (so you can watch the chain move),
    * then returns a canned response. Pass `AckTimeout` to demo the failure path. */
  private def simulatedPipeline(ackBehavior: AckResult): SubBatchPipeline[String, String] =
    SubBatchPipeline[String, String](
      fileGen = (items, _) => delayed(400) {
        println(s"    [bank-host] generated batch file for ${items.size} items")
        GeneratedFile("/tmp/demo-batch.xml", "demo-batch.xml", 1024L, "xml")
      },
      upload = (file, _) => delayed(400) {
        println(s"    [bank-host] uploaded ${file.fileName} (${file.byteSize} bytes)")
        UploadReceipt("/remote/demo-batch.xml", file.byteSize, System.currentTimeMillis())
      },
      waitAck = (_, _) => delayed(600) {
        ackBehavior match {
          case AckReceived    => println("    [bank-host] ACK received")
          case AckTimeout(ms) => println(s"    [bank-host] ... silence, no ACK within ${ms}ms")
          case AckRejected(r) => println(s"    [bank-host] REJECTED: $r")
        }
        ackBehavior
      },
      pollResp = _ => delayed(500) {
        println("    [bank-host] response file ready")
        ResponseReady(ResponseFile(
          "/tmp/demo-result.xml", "demo-result.xml", 512L,
          "OK:acct-001;FAIL:acct-002:INSUFFICIENT_FUNDS;PENDING:acct-003".getBytes("UTF-8")))
      },
      parse = (file, _) => delayed(300) {
        new String(file.content, "UTF-8").split(";").toSeq
      },
      classify = (raw, items) => Future.successful {
        raw.map {
          case r if r.startsWith("OK:") =>
            Success(itemOf(r, items), r)
          case r if r.startsWith("PENDING:") =>
            Suspicious(itemOf(r, items), SuspiciousReason("PENDING", "bank has not settled yet"))
          case r =>
            Failure(itemOf(r, items), FailureReason("BANK_REJECT", r, None))
        }
      }
    )

  private def itemOf(raw: String, items: Seq[String]): String =
    items.find(id => raw.contains(id)).getOrElse("unknown")

  private def printingHooks: LifecycleHooks[BankStage, BankChainState[String, String]] =
    new LifecycleHooks[BankStage, BankChainState[String, String]] {
      override def stageName(stage: BankStage): String = BankStage.stageName(stage)
      override def onStageStart(cursor: String): Unit =
        println(f"  >> $cursor%-16s started")
      override def onStageComplete(
          cursor: String,
          state: BankChainState[String, String],
          metadata: Map[String, String]): Unit = {
        val meta = BankChain.metadataOf(state)
        val summary =
          if (meta.nonEmpty) "  " + meta.map { case (k, v) => s"$k=$v" }.mkString(" ") else ""
        println(f"  << $cursor%-16s done$summary")
      }
      override def onStageFailed(cursor: String, error: StageError): Unit =
        println(f"  !! $cursor%-16s FAILED [${error.errorCode}] ${error.detail}")
    }

  private def runScenario(title: String, ack: AckResult): Unit = {
    println()
    println(s"=== $title ===")
    val monarch = BankChain.monarch(simulatedPipeline(ack), printingHooks)
    monarch.initialize(BankStage.chain)
    val initial = BankChainState[String, String](
      batchId = "demo-batch-001",
      chainId = "recharge",
      items = Seq("acct-001", "acct-002", "acct-003"))
    try {
      val result = Await.result(monarch.process(initial), 60.seconds)
      val (ok, failed, susp) =
        result.classifications.getOrElse(Nil).foldLeft((0, 0, 0)) {
          case ((s, f, u), _: Success[String])    => (s + 1, f, u)
          case ((s, f, u), _: Failure[String])    => (s, f + 1, u)
          case ((s, f, u), _: Suspicious[String]) => (s, f, u + 1)
        }
      println(s"  => batch complete: $ok succeeded, $failed failed, $susp suspicious")
    } catch {
      case e: StageFailedException =>
        println(s"  => batch aborted: [${e.error.errorCode}] ${e.error.detail}")
        println("     (a host can install a failure interceptor to resolve-and-continue instead)")
    }
  }

  def main(args: Array[String]): Unit = {
    println("Bank Batch Transactor — monarch-core engine demo")
    println("Six mechanical stages: file-gen -> upload -> wait-ack -> poll -> parse -> classify")
    runScenario("Scenario 1: happy path (3 items, mixed results)", AckReceived)
    runScenario("Scenario 2: external system goes silent (ACK_TIMEOUT)", AckTimeout(5000L))
    println()
    println("Done.")
  }
}
