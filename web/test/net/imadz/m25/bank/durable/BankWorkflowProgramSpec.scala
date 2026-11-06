package net.imadz.m25.bank.durable

import akka.util.ByteString
import net.imadz.monarch.persistence.batch.BatchCodec
import net.imadz.monarch.persistence.operation.OperationProtocol._
import net.imadz.monarch.persistence.resource.ResourceProtocol
import net.imadz.monarch.persistence.workflow._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext

/** Pure adapter tests: no ActorSystem, journal, socket call or external process. */
final class BankWorkflowProgramSpec extends AnyWordSpec with Matchers {
  private implicit val io: ExecutionContext = ExecutionContext.parasitic
  private val program = new BankWorkflowProgram(new LocalTaskClient(19091))

  private def manifest(area: String = "recharge", ids: Vector[String] = Vector("job-item-1", "job-item-2"),
      execution: Int = 0): BatchManifest = BatchManifest("job", "job/batch/" + area + "-0", area, "1",
    ids.zipWithIndex.map { case (id, index) =>
      Item(index.toLong, id, Vector[Byte](1), "fund-1", area, Map(area -> execution))
    }, 1L)

  private def receipt(area: String, rows: Vector[(String, String)]): ByteString = ByteString(
    "WR2:" + area + ":" + rows.map { case (id, status) => BankWorkflowProgram.encodeId(id) + "~" + status }.mkString(","))

  private def terminal(m: BatchManifest, result: ByteString): Succeeded = {
    val req = program.operation(m)
    Succeeded(result, Evidence(req.operationKey, req.fingerprint, req.ledgerEpoch, Some("task-1"), result))
  }

  "BankWorkflowProgram" should {
    "freeze stable operation identity and the complete manifest bytes" in {
      val m = manifest()
      val req = program.operation(m)
      req.operationKey shouldBe m.operationKey
      req.effectKey shouldBe "effect:job-item-1:recharge:0,job-item-2:recharge:0"
      req.stepId shouldBe "execute"
      req.connectorId shouldBe "bank"
      req.connectorVersion shouldBe "complete-1"
      req.ledgerEpoch shouldBe "ledger-1"
      req.reservationId shouldBe Some(m.reservationId)
      req.payload shouldBe BatchCodec.manifestToBytes(m)
      program.definition.byId("recharge").batch shouldBe BatchPolicy.countOrWait(3, 300)
      program.definition.byId("recharge").maxExecutions shouldBe 2
      program.definition.byId("purchase").maxExecutions shouldBe 1
      program.capacities shouldBe Map("ftp" -> 1, "purchase-system" -> 1)
    }

    "use each external recharge outcome and bind resource release to its exact owner" in {
      val m = manifest()
      val result = receipt(m.area, Vector("job-item-1" -> "RETRY", "job-item-2" -> "SUCCESS"))
      val completion = program.complete(m, terminal(m, result)).toOption.get
      completion.decisions shouldBe Vector(
        ItemDecision("job-item-1", ItemDisposition.Retry(200)),
        ItemDecision("job-item-2", ItemDisposition.Route("purchase", "fund-1")))
      val proof = ResourceProtocol.Proof(m.operationKey, ByteString(m.operationKey + "\n") ++ result)
      completion.resource shouldBe ResourceDisposition.Release(proof.evidence.toVector)
      val grant = ResourceProtocol.Grant(m.reservationId, m.operationKey, "token", Map("ftp" -> 1))
      program.resourceEvidence.validate(grant, proof) shouldBe Right(())
      program.resourceEvidence.validate(grant.copy(ownerOperationKey = "other/execute"), proof).isLeft shouldBe true
      program.resourceEvidence.validate(grant, proof.copy(operationKey = "other/execute")).isLeft shouldBe true
      program.resourceEvidence.validate(grant, proof.copy(evidence = ByteString("other/execute\n") ++ result)).isLeft shouldBe true
      program.resourceEvidence.validate(grant, proof.copy(evidence = result)).isLeft shouldBe true
    }

    "complete purchases only from a fully evidenced external SUCCESS" in {
      val m = manifest("purchase", Vector("job-item-1"))
      val result = receipt(m.area, Vector("job-item-1" -> "SUCCESS"))
      program.complete(m, terminal(m, result)).toOption.get.decisions shouldBe
        Vector(ItemDecision("job-item-1", ItemDisposition.Completed(result.toVector)))
      val retry = receipt(m.area, Vector("job-item-1" -> "RETRY"))
      program.complete(m, terminal(m, retry)).toOption.get.decisions.head.disposition
        .isInstanceOf[ItemDisposition.Hold] shouldBe true
    }

    "reject missing, duplicate, foreign and unknown per-item outcomes without a release" in {
      val m = manifest()
      Vector(
        Vector("job-item-1" -> "SUCCESS"),
        Vector("job-item-1" -> "SUCCESS", "job-item-1" -> "RETRY"),
        Vector("job-item-1" -> "SUCCESS", "foreign-item" -> "SUCCESS"),
        Vector("job-item-1" -> "UNKNOWN", "job-item-2" -> "SUCCESS")
      ).foreach { rows =>
        val result = receipt(m.area, rows)
        program.complete(m, terminal(m, result)).isLeft shouldBe true
      }
      val duplicateManifest = m.copy(items = Vector(m.items.head, m.items.head))
      val result = receipt(m.area, Vector("job-item-1" -> "SUCCESS"))
      program.complete(duplicateManifest, terminal(m, result)).isLeft shouldBe true
      intercept[IllegalArgumentException](program.operation(duplicateManifest))
    }

    "reject terminal evidence with the wrong task, digest, epoch, owner or payload" in {
      val m = manifest()
      val good = terminal(m, receipt(m.area, m.items.map(_.itemId -> "SUCCESS")))
      Vector(
        good.evidence.copy(taskId = None), good.evidence.copy(taskId = Some("not-a-task")),
        good.evidence.copy(requestDigest = "wrong"), good.evidence.copy(ledgerEpoch = "ledger-2"),
        good.evidence.copy(operationKey = "other/execute"), good.evidence.copy(payload = ByteString("SUCCESS"))
      ).foreach { evidence => program.complete(m, good.copy(evidence = evidence)).isLeft shouldBe true }
      val wrongArea = receipt("purchase", Vector("job-item-1" -> "SUCCESS"))
      program.complete(m, terminal(m, wrongArea)).isLeft shouldBe true
    }

    "honor QUERY suffixes, reject changed tasks and never accept a bare success marker" in {
      val m = manifest()
      val req = program.operation(m)
      val good = terminal(m, receipt(m.area, m.items.map(_.itemId -> "SUCCESS")))
      val submit = Vector("SUCCEEDED", "task-1", "ledger-1", good.result.utf8String)
      program.observe(m.area, req, submit, query = false) shouldBe Confirmed(good)
      val query = submit ++ Vector(req.operationKey, req.fingerprint)
      program.observe(m.area, req, query, query = true) shouldBe Confirmed(good)
      program.observe(m.area, req, submit, query = true).isInstanceOf[Unknown] shouldBe true
      program.observe(m.area, req, query.updated(5, "wrong"), query = true).isInstanceOf[Unknown] shouldBe true
      program.observe(m.area, req, query.updated(1, "task-2"), query = true,
        previous = Some(good.evidence)).isInstanceOf[Unknown] shouldBe true
      program.observe(m.area, req, Vector("SUCCEEDED", "task-1", "ledger-1", "SUCCESS"),
        query = false).isInstanceOf[Unknown] shouldBe true
    }

    "leave Unknown and missing history held rather than authorize completion or a blind retry" in {
      val m = manifest()
      val req = program.operation(m)
      program.observe(m.area, req, Vector("UNKNOWN"), query = true).isInstanceOf[Unknown] shouldBe true
      program.observe(m.area, req, Vector("NOT_FOUND", "ledger-1"), query = true).isInstanceOf[NotFound] shouldBe true
      val proof = Evidence(req.operationKey, req.fingerprint, req.ledgerEpoch, Some("task-1"), ByteString.empty)
      program.complete(m, KnownFailed("UNCONFIRMED", "no confirmed result", ByteString.empty, proof)).isLeft shouldBe true
      program.connector(m.area).validateSafeToSubmit(req, proof, Some(proof)).isLeft shouldBe true
    }
  }
}
