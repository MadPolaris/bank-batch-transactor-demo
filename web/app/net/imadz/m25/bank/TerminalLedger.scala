package net.imadz.m25.bank

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/**
 * 终态账本：每笔单子（链 × 客户）有且只有一个终态。
 *
 * recharge: credited / rejected / manual / except_pool
 * purchase: paid     / rejected / manual / except_pool
 *
 * 不变量（与 truth/replay-exception.slice.yaml 及 ledger 读模型一致）：
 *  1. 首次写入是 putIfAbsent（exactly-once）：重批重跑、重复分类都不会
 *     覆盖已落终态，保证"有且只有一个"。
 *  2. 唯一允许的显式迁移是人工重放：except_pool → credited/paid（A1 仲裁）。
 *     它是 compare-and-set 的原子操作：当前终态必须恰好是 except_pool
 *     才会切换，其它情况一律拒绝——钱动了账就得动，账不动钱也不许动。
 *  3. 恢复路径（A3）用 restoreTerminal 直接重建，不走上面的写语义。
 *
 * @param onFirstWrite 首次落终态时的回调（service 用来累加计数器；测试可忽略）
 */
class TerminalLedger(onFirstWrite: (String, String) => Unit = (_, _) => ()) {

  private val seen      = new ConcurrentHashMap[String, ConcurrentHashMap[String, java.lang.Boolean]]()
  private val terminals = new ConcurrentHashMap[String, ConcurrentHashMap[String, String]]()

  def markSeen(chain: String, customerId: String): Unit =
    seen.computeIfAbsent(chain, _ => new ConcurrentHashMap[String, java.lang.Boolean]())
      .putIfAbsent(customerId, java.lang.Boolean.TRUE)

  def enteredOf(chain: String): Int =
    Option(seen.get(chain)).map(_.size()).getOrElse(0)

  def terminalCount(chain: String): Int =
    Option(terminals.get(chain)).map(_.size()).getOrElse(0)

  def inFlightOf(chain: String): Int =
    math.max(0, enteredOf(chain) - terminalCount(chain))

  def isTerminal(chain: String, customerId: String): Boolean =
    Option(terminals.get(chain)).exists(_.containsKey(customerId))

  def terminalStateOf(chain: String, customerId: String): Option[String] =
    Option(terminals.get(chain)).flatMap(m => Option(m.get(customerId)))

  /**
   * 终态只落一次；返回是否为新落终态（首次才触发 onFirstWrite）。
   * 幂等键 = customerId：重复的账务更新/重复的分类一律跳过。
   */
  def setTerminal(chain: String, customerId: String, state: String): Boolean = {
    val fresh = terminals.computeIfAbsent(chain, _ => new ConcurrentHashMap[String, String]())
      .putIfAbsent(customerId, state) == null
    if (fresh) onFirstWrite(chain, state)
    fresh
  }

  /**
   * A1 仲裁的显式迁移：人工重放成功时 except_pool → credited/paid。
   * 原子 compare-and-set：当前终态 == from 才切换，否则返回 false 且不碰任何东西。
   * 迁移同样触发 onFirstWrite（新终态的计数），调用方负责把旧终态的计数减回去
   * ——本类不持有计数器语义，只保证账本本身的原子一致。
   */
  def transitionTerminal(chain: String, customerId: String, from: String, to: String): Boolean = {
    val map = terminals.get(chain)
    if (map == null) return false
    if (map.replace(customerId, from, to)) {
      onFirstWrite(chain, to)
      true
    } else false
  }

  /**
   * A3 恢复路径：从 journal 重建时直接写入，不经过 putIfAbsent/迁移语义。
   * 调用方保证输入是某次运行的最终事实（去重后的）。
   */
  def restoreTerminal(chain: String, customerId: String, state: String): Unit = {
    terminals.computeIfAbsent(chain, _ => new ConcurrentHashMap[String, String]())
      .put(customerId, state)
    onFirstWrite(chain, state)
  }

  /** 终态分布直方图（统计视图用）。 */
  def histogram(chain: String): Map[String, Int] =
    Option(terminals.get(chain))
      .map(_.values().asScala.groupBy(identity).map { case (k, vs) => k -> vs.size }.toMap)
      .getOrElse(Map.empty)

  def clear(): Unit = { seen.clear(); terminals.clear() }
}
