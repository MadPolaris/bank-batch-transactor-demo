package net.imadz.m25.bank.durable

import akka.util.ByteString
import net.imadz.monarch.persistence.batch.{BatchCodec, BatchDefinition, BatchSettings, WorkflowProgram}
import net.imadz.monarch.persistence.operation.{ExternalOperationPort, OperationProtocol}
import net.imadz.monarch.persistence.operation.OperationProtocol._
import net.imadz.monarch.persistence.resource.{ResourceEvidenceValidator, ResourceProtocol}
import net.imadz.monarch.persistence.workflow._

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.control.NonFatal

object BankWorkflowProgram {
  def validId(id: String): Boolean = id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")
  private[durable] def encodeId(id: String): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8))
}

/** Bank policy only. WorkflowHost owns all durable orchestration and recovery. */
final class BankWorkflowProgram(client: LocalTaskClient)(implicit io: ExecutionContext) extends WorkflowProgram {
  import BankWorkflowProgram._

  override val definition: BatchDefinition = BatchDefinition("bank-complete", "1", Vector(
    AreaDefinition("recharge", "1", BatchPolicy.countOrWait(3, 300), Map("ftp" -> 1),
      parallelism = 2, maxExecutions = 2),
    AreaDefinition("purchase", "1", BatchPolicy.single, Map("purchase-system" -> 1),
      parallelism = 1, maxExecutions = 1)
  ), BatchSettings(maxAcceptedItems = 20, maxActiveItems = 20, maxActiveBytes = 131072,
    pollIntervalMillis = 100, callTimeoutMillis = 3000, maxBatchCalls = 1000))
  override val capacities: Map[String, Int] = Map("ftp" -> 1, "purchase-system" -> 1)

  override def operation(m: BatchManifest): Request = Request(m.operationKey,
    m.items.map(i => s"${i.itemId}:${m.area}:${i.execution}").mkString("effect:", ",", ""),
    "execute", "bank", "complete-1", "ledger-1", BatchCodec.manifestToBytes(m), Some(m.reservationId))

  private def checkManifest(m: BatchManifest): Either[String, Unit] = definition.byId.get(m.area) match {
    case None => Left("unknown bank area")
    case Some(area) if !validId(m.jobId) || m.batchId.isEmpty || m.batchId.contains("\n") ||
        m.policyVersion != area.policyVersion || m.items.isEmpty || m.items.size > area.batch.maxItems =>
      Left("invalid bank manifest identity, policy or size")
    case Some(area) if m.items.map(_.itemId).distinct.size != m.items.size ||
        m.items.map(_.sequence).distinct.size != m.items.size || m.items.exists(i =>
          !validId(i.itemId) || i.sequence < 0 || i.area != m.area || i.execution < 0 ||
            i.execution >= area.maxExecutions) => Left("invalid or duplicate manifest item")
    case _ => Right(())
  }

  private def requestManifest(req: Request, area: String): Either[String, BatchManifest] = try {
    val m = BatchCodec.manifestFromBytes(req.payload)
    checkManifest(m).flatMap { _ =>
      val expected = operation(m)
      if (m.area == area && req.operationKey == expected.operationKey && req.effectKey == expected.effectKey &&
          req.stepId == expected.stepId && req.connectorId == expected.connectorId &&
          req.connectorVersion == expected.connectorVersion && req.ledgerEpoch == expected.ledgerEpoch &&
          req.reservationId == expected.reservationId) Right(m)
      else Left("request does not match its frozen bank manifest")
    }
  } catch { case NonFatal(_) => Left("invalid encoded bank manifest") }

  private def businessPayload(m: BatchManifest): String = "WF2:" + m.area + ":" +
    m.items.map(i => encodeId(i.itemId) + "~" + i.execution).mkString(",")

  private def parseResult(result: ByteString): Either[String, (String, Vector[(String, String)])] = try {
    require(result.nonEmpty && result.size <= definition.settings.maxResultBytes)
    val sections = LocalTaskClient.utf8(result.toArray).split(":", -1)
    require(sections.length == 3 && sections(0) == "WR2" && definition.byId.contains(sections(1)))
    val rows = sections(2).split(",", -1).toVector.map { row =>
      val fields = row.split("~", -1)
      require(fields.length == 2 && fields(0).nonEmpty)
      val id = LocalTaskClient.utf8(Base64.getUrlDecoder.decode(fields(0)))
      require(validId(id) && encodeId(id) == fields(0) && Set("SUCCESS", "RETRY")(fields(1)))
      id -> fields(1)
    }
    require(rows.nonEmpty && rows.size <= definition.byId(sections(1)).batch.maxItems &&
      rows.map(_._1).distinct.size == rows.size)
    Right(sections(1) -> rows)
  } catch { case NonFatal(_) => Left("malformed, duplicate or unknown WR2 receipt") }

  private def outcomes(result: ByteString, m: BatchManifest): Either[String, Map[String, String]] =
    parseResult(result).flatMap { case (area, rows) =>
      if (area == m.area && rows.map(_._1).toSet == m.items.map(_.itemId).toSet) Right(rows.toMap)
      else Left("receipt must cover exactly every frozen item in the same area")
    }

  private def validateReceipt(area: String, req: Request, observation: WithEvidence): Either[String, Unit] =
    requestManifest(req, area).flatMap { m =>
      val proof = observation.evidence
      if (proof.operationKey != req.operationKey || proof.requestDigest != req.fingerprint ||
          proof.ledgerEpoch != req.ledgerEpoch ||
          !proof.taskId.exists(t => validId(t) && t.matches("task-[1-9][0-9]*")))
        Left("external task, operation, digest or ledger identity mismatch")
      else observation match {
        case _: Accepted | _: Running if proof.payload == ByteString("running") => Right(())
        case Confirmed(Succeeded(result, evidence)) if evidence.payload == result => outcomes(result, m).map(_ => ())
        case _ => Left("observation is not a supported, evidenced bank outcome")
      }
    }

  /** Package-visible for pure protocol tests; never manufactures a successful receipt. */
  private[durable] def observe(area: String, req: Request, response: Vector[String], query: Boolean,
      previous: Option[Evidence] = None): Observation = {
    def proof(task: String, epoch: String, key: String, digest: String, result: String): Evidence =
      Evidence(key, digest, epoch, Some(task), ByteString(result))
    val candidate: Observation = (query, response) match {
      case (false, Vector("RUNNING", task, epoch)) =>
        Accepted(proof(task, epoch, req.operationKey, req.fingerprint, "running"))
      case (false, Vector("SUCCEEDED", task, epoch, result)) =>
        Confirmed(Succeeded(ByteString(result), proof(task, epoch, req.operationKey, req.fingerprint, result)))
      case (true, Vector("RUNNING", task, epoch, key, digest)) => Running(proof(task, epoch, key, digest, "running"))
      case (true, Vector("SUCCEEDED", task, epoch, result, key, digest)) =>
        Confirmed(Succeeded(ByteString(result), proof(task, epoch, key, digest, result)))
      case (true, Vector("NOT_FOUND", _)) => NotFound("external history unavailable; this does not authorize resubmission")
      case _ => Unknown("external response rejected or unrecognized")
    }
    candidate match {
      case evidence: WithEvidence => validateReceipt(area, req, evidence) match {
        case Left(reason) => Unknown(reason)
        case Right(_) if previous.exists(p => p.taskId != evidence.evidence.taskId ||
            p.operationKey != req.operationKey || p.requestDigest != req.fingerprint || p.ledgerEpoch != req.ledgerEpoch) =>
          Unknown("external task identity changed during reconciliation")
        case Right(_) => evidence
      }
      case other => other
    }
  }

  private def external(body: => Observation): Future[Observation] = try {
    Future(blocking {
      try body catch { case NonFatal(_) => Unknown("external I/O failed; outcome remains unknown") }
    })(io)
  } catch { case NonFatal(_) => Future.successful(Unknown("external I/O executor unavailable")) }

  override def connector(area: String): ExternalOperationPort = {
    require(definition.byId.contains(area), "only bank recharge/purchase connectors are supported")
    new ExternalOperationPort {
      override val connectorId = "bank"
      override val connectorVersion = "complete-1"
      override def submit(req: Request): Future[Observation] = external {
        requestManifest(req, area).fold(Unknown.apply, m => observe(area, req,
          client.call("SUBMIT", Vector("bank", req.operationKey, req.fingerprint, req.ledgerEpoch,
            req.effectKey, businessPayload(m))), query = false))
      }
      override def query(req: Request, previous: Option[Evidence]): Future[Observation] = external {
        requestManifest(req, area).fold(Unknown.apply, _ => observe(area, req,
          client.call("QUERY", Vector(req.operationKey)), query = true, previous = previous))
      }
      override def validateEvidence(req: Request, observation: WithEvidence): Either[String, Unit] =
        validateReceipt(area, req, observation)
      // No SafeToSubmit override: timeout/NOT_FOUND/restart never permits a blind resend.
    }
  }

  override def complete(m: BatchManifest, terminal: OperationProtocol.Terminal): Either[String, BatchCompletion] = terminal match {
    case succeeded @ Succeeded(result, _) =>
      checkManifest(m).flatMap { _ =>
      validateReceipt(m.area, operation(m), Confirmed(succeeded)).flatMap { _ =>
        outcomes(result, m).map { confirmed =>
          val decisions = m.items.map { item =>
            val disposition: ItemDisposition = (m.area, confirmed(item.itemId)) match {
              case ("recharge", "SUCCESS") => ItemDisposition.Route("purchase", "fund-1")
              case ("recharge", "RETRY") => ItemDisposition.Retry(200)
              case ("purchase", "SUCCESS") => ItemDisposition.Completed(result.toVector)
              case _ => ItemDisposition.Hold("external outcome does not authorize a bank transition")
            }
            ItemDecision(item.itemId, disposition)
          }
          BatchCompletion(decisions, ResourceDisposition.Release((ByteString(m.operationKey + "\n") ++ result).toVector))
        }
      }
      }
    case _ => Left("no confirmed bank completion; keep the workflow held rather than guessing an outcome")
  }

  // Local fixture receipts are not financial certificates or cryptographic authorization.
  // Full manifest coverage is checked above before this owner-bound release is produced.
  override val resourceEvidence: ResourceEvidenceValidator = new ResourceEvidenceValidator {
    override def validate(grant: ResourceProtocol.Grant, proof: ResourceProtocol.Proof): Either[String, Unit] = try {
      val receipt = LocalTaskClient.utf8(proof.evidence.toArray)
      val separator = receipt.indexOf('\n')
      if (proof.operationKey != grant.ownerOperationKey || separator <= 0 ||
          receipt.substring(0, separator) != grant.ownerOperationKey) Left("resource receipt owner mismatch")
      else parseResult(ByteString(receipt.substring(separator + 1))).flatMap { case (area, _) =>
        if (grant.units == definition.byId(area).resources) Right(())
        else Left("receipt area does not match the reserved resource bundle")
      }
    } catch { case NonFatal(_) => Left("invalid resource receipt") }
  }
}
