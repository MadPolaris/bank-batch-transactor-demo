package net.imadz.m25.bank

import net.imadz.m25.component._
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._

/**
 * A3：从 journal 快照重建账本的纯逻辑。
 *
 * - 同一客户多轮：按 round 升序，第一个终态判定胜出（== live 路径的 isTerminal 过滤）
 * - 某轮判定为 None（重批/路由）→ 看下一轮；都没有终态 = 仍在途，不进账本
 * - 所有快照中的客户都记入 seen（entered 计数）
 */
class LedgerRecoverySpec extends AnyFunSuite {

  private def snap(chain: String, round: Int, cs: Classification[String]*): Array[Byte] = {
    val st: ChainExecutionActor.BankState = BankChainState[String, String](
      batchId = s"bank-r1-$chain-r$round-b0",
      chainId = s"$chain-r$round-b0",
      items = cs.map(_.asInstanceOf[Classification[String]] match {
        case Success(i, _)    => i
        case Failure(i, _)    => i
        case Suspicious(i, _) => i
      }),
      classifications = Some(cs.toSeq)
    )
    BankStateCodec.protobuf.encode(st)
  }

  private def ent(chain: String, round: Int, bytes: Array[Byte], ts: Long = 1L) =
    ("r1", chain, round, 0, ts, Some(bytes))

  test("rebuild: single round Success -> credited") {
    val r = LedgerRecovery.rebuild(Seq(ent("recharge", 1, snap("recharge", 1, Success("c1", "c1")))))
    // c1 是否 glitch 取决于确定性哈希；只断言"有终态且自洽"
    assert(r.terminals.get(("recharge", "c1")).isDefined)
    assert(r.seen.contains(("recharge", "c1")))
  }

  test("rebuild: retry round then success -> success terminal wins") {
    // round1: NETWORK_ERROR → None（仍在途）；round2: Success → credited/paid
    val cid = "rk-1"
    val r1 = ent("recharge", 1, snap("recharge", 1,
      Failure(cid, FailureReason("NETWORK_ERROR", "x", Some(NextStep.RetrySameArea(3.seconds))))), ts = 1L)
    // round2 的判定：用 decideTerminal 的真实结果（可能是 credited 或 except_pool）
    val expected = BankDecisions.decideTerminal("recharge", 2, cid, Success(cid, cid), BankDecisions.policies)
    val r2 = ent("recharge", 2, snap("recharge", 2, Success(cid, cid)), ts = 2L)
    val r = LedgerRecovery.rebuild(Seq(r1, r2))
    assert(r.terminals.get(("recharge", cid)) == expected)
    assert(expected.isDefined)
  }

  test("rebuild: only in-flight (retry, no later round) -> not terminal but seen") {
    val cid = "rk-2"
    val r = LedgerRecovery.rebuild(Seq(ent("recharge", 1, snap("recharge", 1,
      Failure(cid, FailureReason("NETWORK_ERROR", "x", Some(NextStep.RetrySameArea(3.seconds))))))))
    assert(!r.terminals.contains(("recharge", cid)))
    assert(r.seen.contains(("recharge", cid)))
  }

  test("rebuild: first terminal wins across rounds (== live isTerminal filter)") {
    // round1: BALANCE_INSUFFICIENT → rejected；round2 病态地出现 Success → 仍以 rejected 为准
    val cid = "rk-3"
    val r = LedgerRecovery.rebuild(Seq(
      ent("recharge", 1, snap("recharge", 1, Failure(cid, FailureReason("BALANCE_INSUFFICIENT", "x", None))), ts = 1L),
      ent("recharge", 2, snap("recharge", 2, Success(cid, cid)), ts = 2L)
    ))
    assert(r.terminals.get(("recharge", cid)).contains("rejected"))
  }

  test("rebuild: empty entities -> empty ledger") {
    assert(LedgerRecovery.rebuild(Seq.empty) == LedgerRecovery.RecoveredLedger.empty)
  }

  test("rebuild: entity without snapshot contributes nothing") {
    val r = LedgerRecovery.rebuild(Seq(("r1", "recharge", 1, 0, 1L, None)))
    assert(r == LedgerRecovery.RecoveredLedger.empty)
  }
}
