package net.imadz.m25.component

import net.imadz.monarch.persistence.StateCodec
import net.imadz.m25.proto.bank_chain._

import com.google.protobuf.ByteString

/**
 * `BankChainState[String, String]` 的 protobuf 编解码。
 *
 * 比 Java 序列化强在三点：① schema 即契约，字段号是跨版本兼容的唯一依据；
 * ② 二进制稳定，不依赖 JVM 类路径；③ 未知字段保留，老 journal 在新代码下可读。
 *
 * Demo 级别取舍（生产应收紧）：
 * - `Classification.Success.rawResult: Any` 只承载 String（demo 里本来就是 String）；
 * - `FailureReason.suggestedAction` 不进快照——resume 不需要它。
 */
object BankStateCodec {

  val protobuf: StateCodec[ChainExecutionActor.BankState] =
    new StateCodec[ChainExecutionActor.BankState] {
      def encode(s: ChainExecutionActor.BankState): Array[Byte] =
        toProto(s).toByteArray

      def decode(bytes: Array[Byte]): ChainExecutionActor.BankState =
        fromProto(BankChainStateProto.parseFrom(bytes))
    }

  // ---------- to proto ----------

  private def toProto(s: ChainExecutionActor.BankState): BankChainStateProto =
    BankChainStateProto(
      batchId = s.batchId,
      chainId = s.chainId,
      items = s.items,
      context = s.context.view.mapValues(_.toString).toMap,
      generatedFile = s.generatedFile.map(g =>
        GeneratedFileProto(g.localPath, g.fileName, g.byteSize, g.encoding)),
      receipt = s.receipt.map(r =>
        UploadReceiptProto(r.remotePath, r.bytesTransferred, r.timestamp)),
      ack = s.ack.map {
        case AckReceived       => AckResultProto().withResult(AckResultProto.Result.Received(true))
        case AckTimeout(ms)    => AckResultProto().withResult(AckResultProto.Result.TimeoutMs(ms))
        case AckRejected(why)  => AckResultProto().withResult(AckResultProto.Result.RejectedReason(why))
      },
      responseFile = s.responseFile.map(f =>
        ResponseFileProto(f.localPath, f.fileName, f.byteSize, ByteString.copyFrom(f.content))),
      rawResults = s.rawResults.getOrElse(Seq.empty),
      classifications = s.classifications.getOrElse(Seq.empty).map(toProtoClassification),
      lastStage = s.lastStage.map(BankStage.stageName).getOrElse("")
    )

  private def toProtoClassification(c: Classification[String]): ClassificationProto =
    c match {
      case Success(item, raw) =>
        ClassificationProto(item).withKind(
          ClassificationProto.Kind.SuccessRaw(raw.toString))
      case Failure(item, FailureReason(code, msg, _)) =>
        ClassificationProto(item).withKind(
          ClassificationProto.Kind.Failure(FailureReasonProto(code, msg)))
      case Suspicious(item, SuspiciousReason(code, msg)) =>
        ClassificationProto(item).withKind(
          ClassificationProto.Kind.Suspicious(SuspiciousReasonProto(code, msg)))
    }

  // ---------- from proto ----------

  private def fromProto(p: BankChainStateProto): ChainExecutionActor.BankState =
    BankChainState[String, String](
      batchId = p.batchId,
      chainId = p.chainId,
      items = p.items,
      context = p.context,
      generatedFile = p.generatedFile.map(g =>
        GeneratedFile(g.localPath, g.fileName, g.byteSize, g.encoding)),
      receipt = p.receipt.map(r =>
        UploadReceipt(r.remotePath, r.bytesTransferred, r.timestamp)),
      ack = p.ack.map(fromProtoAck),
      responseFile = p.responseFile.map(f =>
        ResponseFile(f.localPath, f.fileName, f.byteSize, f.content.toByteArray)),
      rawResults =
        if (p.rawResults.isEmpty) None else Some(p.rawResults),
      classifications =
        if (p.classifications.isEmpty) None
        else Some(p.classifications.map(fromProtoClassification)),
      lastStage =
        if (p.lastStage.isEmpty) None
        else BankStage.chain.find(BankStage.stageName(_) == p.lastStage)
    )

  private def fromProtoAck(p: AckResultProto): AckResult =
    p.result match {
      case AckResultProto.Result.Received(_)      => AckReceived
      case AckResultProto.Result.TimeoutMs(ms)   => AckTimeout(ms)
      case AckResultProto.Result.RejectedReason(why) => AckRejected(why)
      case AckResultProto.Result.Empty           =>
        throw new IllegalArgumentException("AckResultProto has no result set")
    }

  private def fromProtoClassification(p: ClassificationProto): Classification[String] =
    p.kind match {
      case ClassificationProto.Kind.SuccessRaw(raw) =>
        Success(p.item, raw)
      case ClassificationProto.Kind.Failure(fr) =>
        Failure(p.item, FailureReason(fr.code, fr.message, None))
      case ClassificationProto.Kind.Suspicious(sr) =>
        Suspicious(p.item, SuspiciousReason(sr.code, sr.message))
      case ClassificationProto.Kind.Empty =>
        throw new IllegalArgumentException("ClassificationProto has no kind set")
    }
}
