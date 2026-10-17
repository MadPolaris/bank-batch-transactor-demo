package net.imadz.m25.bank

import net.imadz.m25.component._
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._

/**
 * A3：纯决策函数的行为钉死（live 路径与恢复路径共用，同一映射）。
 *
 * 这些测试钉的是领域语义（路由表、重试上限、查证、glitch），不是哈希实现：
 * 若有人改了 `BankDecisions` 的公式而 live 路径没同步，这里会红。
 */
class BankDecisionsSpec extends AnyFunSuite {

  private val policies = BankDecisions.policies

  // -- routeStep ------------------------------------------------------

  test("routeStep: over maxRetries -> ManualIntervention") {
    val step = BankDecisions.routeStep(policies("recharge"),
      FailureReason("BALANCE_INSUFFICIENT", "x", None), retryCount = 2)
    assert(step.isInstanceOf[NextStep.ManualIntervention])
  }

  test("routeStep: BALANCE_INSUFFICIENT -> Scrap (policy table)") {
    val step = BankDecisions.routeStep(policies("recharge"),
      FailureReason("BALANCE_INSUFFICIENT", "x", None), retryCount = 0)
    assert(step == NextStep.Scrap)
  }

  test("routeStep: suggestedAction wins over the policy table") {
    val step = BankDecisions.routeStep(policies("recharge"),
      FailureReason("NETWORK_ERROR", "x", Some(NextStep.RetrySameArea(3.seconds))), retryCount = 0)
    assert(step == NextStep.RetrySameArea(3.seconds))
  }

  test("routeStep: unknown code -> RetrySameArea(defaultCooldown)") {
    val step = BankDecisions.routeStep(policies("recharge"),
      FailureReason("SOMETHING_WEIRD", "x", None), retryCount = 0)
    assert(step == NextStep.RetrySameArea(3.seconds))
  }

  // -- decideTerminal ---------------------------------------------------

  /** 找一个确定性哈希下"不触发 glitch"的客户（与实现无关的扫描）。 */
  private def findCustomer(glitch: Boolean, chain: String): String =
    (0 until 10000).map(i => s"probe-$i").find(cid =>
      BankDecisions.glitchOf(chain, cid) == glitch).get

  test("decideTerminal: Success -> credited (recharge, no glitch)") {
    val cid = findCustomer(glitch = false, "recharge")
    val d = BankDecisions.decideTerminal("recharge", 1, cid, Success(cid, cid), policies)
    assert(d.contains("credited"))
  }

  test("decideTerminal: Success -> paid (purchase, no glitch)") {
    val cid = findCustomer(glitch = false, "purchase")
    val d = BankDecisions.decideTerminal("purchase", 1, cid, Success(cid, cid), policies)
    assert(d.contains("paid"))
  }

  test("decideTerminal: Success + glitch -> except_pool") {
    val cid = findCustomer(glitch = true, "recharge")
    val d = BankDecisions.decideTerminal("recharge", 1, cid, Success(cid, cid), policies)
    assert(d.contains("except_pool"))
  }

  test("decideTerminal: BALANCE_INSUFFICIENT round 1 -> rejected") {
    val d = BankDecisions.decideTerminal("recharge", 1, "c1",
      Failure("c1", FailureReason("BALANCE_INSUFFICIENT", "x", None)), policies)
    assert(d.contains("rejected"))
  }

  test("decideTerminal: round 3 hits maxRetries -> manual") {
    val d = BankDecisions.decideTerminal("recharge", 3, "c1",
      Failure("c1", FailureReason("BALANCE_INSUFFICIENT", "x", None)), policies)
    assert(d.contains("manual"))
  }

  test("decideTerminal: NETWORK_ERROR round 1 -> None (still in flight, will rebatch)") {
    val d = BankDecisions.decideTerminal("recharge", 1, "c1",
      Failure("c1", FailureReason("NETWORK_ERROR", "x", Some(NextStep.RetrySameArea(3.seconds)))), policies)
    assert(d.isEmpty)
  }

  test("decideTerminal: Suspicious + VERIFIED_SUCCESS behaves like Success") {
    val cid = (0 until 10000).map(i => s"probe-$i")
      .find(c => BankDecisions.reconfirmOf("recharge", c) == "VERIFIED_SUCCESS").get
    // 用一个不 glitch 的客户，避免 except_pool 干扰断言
    val clean = (0 until 10000).map(i => s"clean-$i").find(c =>
      BankDecisions.reconfirmOf("recharge", c) == "VERIFIED_SUCCESS" &&
        !BankDecisions.glitchOf("recharge", c)).get
    val d = BankDecisions.decideTerminal("recharge", 1, clean,
      Suspicious(clean, SuspiciousReason("TIMEOUT", "x")), policies)
    assert(d.contains("credited"))
    assert(cid.nonEmpty) // 60% 概率分支确实存在
  }

  test("decideTerminal: Suspicious + NETWORK_ERROR behaves like a routable Failure") {
    val cid = (0 until 10000).map(i => s"net-$i").find(c =>
      BankDecisions.reconfirmOf("recharge", c) != "VERIFIED_SUCCESS").get
    val d = BankDecisions.decideTerminal("recharge", 1, cid,
      Suspicious(cid, SuspiciousReason("TIMEOUT", "x")), policies)
    assert(d.isEmpty) // NETWORK_ERROR → RetrySameArea → 仍在途
  }
}
