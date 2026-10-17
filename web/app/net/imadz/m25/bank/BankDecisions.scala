package net.imadz.m25.bank

import net.imadz.m25.component._
import scala.concurrent.duration._

/**
 * 账务终态的纯决策函数。
 *
 * live 路径（`BankBatchDemoService.businessClosure`）与恢复路径
 * （`LedgerRecovery`）**共用这一套**，因此"恢复重建出的终态 == 正常跑出的终态"
 * 是构造性保证，不需要用测试去对拍两套逻辑。
 *
 * 所有随机性都是确定性哈希（`hash`），与 live 路径逐字一致：
 *  - 查证结论：`reconfirmOf`
 *  - 账务 1% 瞬时故障：`glitchOf`（与 `updateAccountGlitchy` 的判定同式）
 *  - 失败路由：`routeStep`（与 `PolicyBasedReBatchRouter.route` 的单条判定同式）
 */
object BankDecisions {

  def hash(s: String): Int = math.abs(s.hashCode)

  /** 查证结论：60% 确认成功，40% 网络类失败。 */
  def reconfirmOf(chain: String, customerId: String): String = {
    val v = hash(s"$chain-verify-$customerId") % 100
    if (v < 60) "VERIFIED_SUCCESS" else "NETWORK_ERROR"
  }

  /** 账务瞬时故障判定（1%）。与 live 路径 `updateAccountGlitchy` 的 once() 同式。 */
  def glitchOf(chain: String, customerId: String): Boolean =
    hash(s"glitch-$chain-$customerId") % 100 < 1

  /** 成功终态：recharge → credited，purchase → paid。 */
  def successStateOf(chain: String): String =
    if (chain == "recharge") "credited" else "paid"

  /**
   * 单条失败的路由判定，与 `PolicyBasedReBatchRouter.route` 同式：
   * 先看重试上限，再看 reason 自带的 suggestedAction，再查 policy.actionMap，
   * 都没有则 RetrySameArea(defaultCooldown)。
   */
  def routeStep(policy: ReBatchPolicy, reason: FailureReason, retryCount: Int): NextStep =
    if (retryCount >= policy.maxRetries)
      NextStep.ManualIntervention(s"MAX_RETRY_EXCEEDED-${reason.code}")
    else
      reason.suggestedAction
        .orElse(policy.actionMap.get(reason.code))
        .getOrElse(NextStep.RetrySameArea(policy.defaultCooldown))

  /**
   * 单个客户在某一轮的终态判定。返回 None = 本轮不落终态（仍在途：重批/路由）。
   *
   * 与 `businessClosure` 的逐项逻辑对应：
   *  - Success → 账务更新（glitch 则 except_pool，否则 credited/paid）
   *  - Suspicious → 查证；确认成功走账务，网络类失败按失败路由
   *  - Failure → 路由：Scrap → rejected，Manual → manual，其余不在途
   */
  def decideTerminal(
      chain: String,
      round: Int,
      customerId: String,
      classification: Classification[String],
      policies: Map[String, ReBatchPolicy]
  ): Option[String] = {
    def accountOutcome(): String =
      if (glitchOf(chain, customerId)) "except_pool" else successStateOf(chain)

    def routeOutcome(reason: FailureReason): Option[String] =
      routeStep(policies(chain), reason, retryCount = round - 1) match {
        case NextStep.Scrap                  => Some("rejected")
        case NextStep.ManualIntervention(_)   => Some("manual")
        case _                               => None // RetrySameArea / RouteToArea → 仍在途
      }

    classification match {
      case Success(_, _) => Some(accountOutcome())
      case Suspicious(_, _) =>
        reconfirmOf(chain, customerId) match {
          case "VERIFIED_SUCCESS" => Some(accountOutcome())
          case _ =>
            routeOutcome(FailureReason("NETWORK_ERROR", "外部系统拥堵",
              Some(NextStep.RetrySameArea(3.seconds))))
        }
      case Failure(_, reason) => routeOutcome(reason)
    }
  }

  /** demo 的两条链策略（与 service 的 `policies` 同一定义，恢复路径复用）。 */
  val policies: Map[String, ReBatchPolicy] = Map(
    "recharge" -> ReBatchPolicy(
      maxRetries = 2,
      actionMap = Map("BALANCE_INSUFFICIENT" -> NextStep.Scrap,
        "NETWORK_ERROR" -> NextStep.RetrySameArea(3.seconds)),
      defaultCooldown = 3.seconds),
    "purchase" -> ReBatchPolicy(
      maxRetries = 2,
      actionMap = Map("QUOTA_EXCEEDED" -> NextStep.Scrap,
        "NETWORK_ERROR" -> NextStep.RetrySameArea(3.seconds)),
      defaultCooldown = 3.seconds))
}
