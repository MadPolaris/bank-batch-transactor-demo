package net.imadz.m25.component

import net.imadz.monarch.persistence.{PersistentMonarch, RunObserver}

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.mutable
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

/** Bank-domain integration for `monarch-persistence`: the six-stage chain runs
  * through `PersistentMonarch` (via the `ChainExecutionActor` facade).
  *
  *   - happy path: six stages complete, observer sees the final snapshot;
  *   - crash recovery: gate the poll stage, stop the actor mid-run, respawn —
  *     completed stages are never re-run, the run finishes from the breakpoint.
  */
class ChainExecutionActorSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
      |akka.persistence.journal.plugin = "akka.persistence.testkit.journal"
      |akka.persistence.snapshot-store.plugin = "akka.persistence.no-snapshot-store"
      |akka.persistence.testkit.events.serialize = off
      |akka.actor.testkit.typed.single-expect-default = 10s
      |akka.actor.testkit.typed.filter-leeway = 30s
    """.stripMargin).withFallback(EventSourcedBehaviorTestKit.config)
) with AnyWordSpecLike with org.scalatest.concurrent.Eventually {

  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  private val items: List[String] = (1 to 4).map(i => s"item-$i").toList
  private val response = ResponseFile("/tmp/r.dat", "r.dat", 8L, Array.emptyByteArray)

  /** Controllable pipeline: the poll stage can be gated to manufacture a breakpoint. */
  private def pipeline(pollGate: Option[Promise[ResponseFile]]): SubBatchPipeline[String, String] = {
    def after[T](ms: Int)(f: => T): Future[T] = Future { Thread.sleep(ms); f }
    SubBatchPipeline[String, String](
      fileGen = (its, _) => after(5)(GeneratedFile(s"/tmp/b${its.size}.dat", "b.dat", 8L, "dat")),
      upload = (f, _) => after(5)(UploadReceipt("/remote/b.dat", f.byteSize, 1L)),
      waitAck = (_, _) => after(5)(AckReceived),
      pollResp = _ => pollGate match {
        case Some(gate) => gate.future.map(ResponseReady(_))
        case None       => after(5)(ResponseReady(response))
      },
      parse = (_, _) => after(5)(Seq("raw-1", "raw-2")),
      classify = (_, its) => after(5)(its.map(i => net.imadz.m25.component.Success[String](i, "OK")))
    )
  }

  private class RecordingObserver extends RunObserver[BankChainState[String, String]] {
    val done: mutable.ListBuffer[String] = mutable.ListBuffer.empty[String]
    val completed = Promise[String]()
    override def onStageComplete(cursor: String, metadata: Map[String, String], snapshot: BankChainState[String, String]): Unit =
      done.synchronized(done += cursor)
    override def onCompleted(runId: String): Unit = {
      completed.trySuccess(runId); ()
    }
    def doneCount: Int = done.synchronized(done.size)
  }

  private def spawn(runId: String, pipe: SubBatchPipeline[String, String], observer: RunObserver[BankChainState[String, String]]) = {
    val ref = testKit.spawn(
      ChainExecutionActor.behavior(PersistenceId("m25-chain-executor", runId), pipe, observer),
      runId)
    (ref, testKit.createTestProbe[PersistentMonarch.RunReply]())
  }

  "A persistent bank chain (monarch-persistence)" should {

    "run the six-stage chain to completion" in {
      val observer = new RecordingObserver
      val (ref, probe) = spawn("recharge-happy", pipeline(None), observer)
      ChainExecutionActor.startRun(ref, "b-happy", "recharge", items, probe.ref)
      probe.expectMessage(10.seconds, PersistentMonarch.Accepted("recharge-b-happy"))

      Await.result(observer.completed.future, 15.seconds) shouldBe "recharge-b-happy"
      observer.done.toList shouldBe List(
        "file-gen#0", "upload#1", "wait-ack#2", "poll#3", "parse#4", "classify#5")
      testKit.stop(ref)
    }

    "resume from the breakpoint after a crash without re-running completed stages" in {
      val gate = Promise[ResponseFile]()
      val observer1 = new RecordingObserver
      val (ref1, probe1) = spawn("recharge-resume", pipeline(Some(gate)), observer1)
      ChainExecutionActor.startRun(ref1, "b-crash", "recharge", items, probe1.ref)
      probe1.expectMessage(10.seconds, PersistentMonarch.Accepted("recharge-b-crash"))

      // file-gen/upload/wait-ack complete, poll blocks on the gate.
      eventually { observer1.doneCount shouldBe 3 }

      // ---- crash ----
      testKit.stop(ref1)

      // ---- restart: same persistenceId replays the journal ----
      val observer2 = new RecordingObserver
      val (ref2, _) = spawn("recharge-resume", pipeline(Some(gate)), observer2)
      // poll re-runs (it never completed), but the finished three must not.
      Thread.sleep(1000)
      observer2.doneCount shouldBe 0

      // Open the gate: poll/parse/classify drain, run completes.
      gate.success(response)
      Await.result(observer2.completed.future, 15.seconds) shouldBe "recharge-b-crash"
      observer2.done.toList shouldBe List("poll#3", "parse#4", "classify#5")
      testKit.stop(ref2)
    }

    "reject a second StartRun while the run is active" in {
      val observer = new RecordingObserver
      val (ref, probe) = spawn("recharge-dup", pipeline(None), observer)
      val initial = BankChainState[String, String](
        batchId = "b-dup", chainId = "recharge", items = items,
        context = Map("batchId" -> "b-dup", "chainId" -> "recharge"))
      ref ! PersistentMonarch.StartRun("recharge-b-dup", initial, probe.ref)
      probe.expectMessage(10.seconds, PersistentMonarch.Accepted("recharge-b-dup"))
      Await.result(observer.completed.future, 15.seconds)

      ref ! PersistentMonarch.StartRun("recharge-b-dup", initial, probe.ref)
      probe.expectMessageType[PersistentMonarch.Rejected](10.seconds)
      testKit.stop(ref)
    }
  }
}
