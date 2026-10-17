package net.imadz.m25.bank

import org.scalatest.funsuite.AnyFunSuite

/**
 * A1：终态账本的写语义。
 *
 * - 首次写入 putIfAbsent（exactly-once）
 * - 唯一允许的迁移：人工重放 except_pool → credited/paid（原子 compare-and-set）
 * - 其它迁移一律拒绝
 */
class TerminalLedgerSpec extends AnyFunSuite {

  test("first write wins (putIfAbsent): exactly-once") {
    val l = new TerminalLedger()
    assert(l.setTerminal("recharge", "c1", "credited") == true)
    assert(l.setTerminal("recharge", "c1", "paid") == false) // 被拒绝，不覆盖
    assert(l.terminalStateOf("recharge", "c1").contains("credited"))
  }

  test("replay transition: except_pool -> credited succeeds") {
    val l = new TerminalLedger()
    assert(l.setTerminal("recharge", "c1", "except_pool") == true)
    assert(l.transitionTerminal("recharge", "c1", "except_pool", "credited") == true)
    assert(l.terminalStateOf("recharge", "c1").contains("credited"))
  }

  test("replay transition: except_pool -> paid succeeds (purchase chain)") {
    val l = new TerminalLedger()
    assert(l.setTerminal("purchase", "c9", "except_pool") == true)
    assert(l.transitionTerminal("purchase", "c9", "except_pool", "paid") == true)
    assert(l.terminalStateOf("purchase", "c9").contains("paid"))
  }

  test("transition from a wrong from-state is refused (no-op)") {
    val l = new TerminalLedger()
    assert(l.setTerminal("recharge", "c1", "credited") == true)
    // 当前是 credited，不是 except_pool → 拒绝
    assert(l.transitionTerminal("recharge", "c1", "except_pool", "paid") == false)
    assert(l.terminalStateOf("recharge", "c1").contains("credited"))
  }

  test("transition on a missing entry is refused") {
    val l = new TerminalLedger()
    assert(l.transitionTerminal("recharge", "ghost", "except_pool", "credited") == false)
    assert(l.terminalStateOf("recharge", "ghost").isEmpty)
  }

  test("onFirstWrite fires on first write and on transition") {
    var calls = Vector.empty[(String, String)]
    val l = new TerminalLedger((c, s) => calls :+= ((c, s)))
    l.setTerminal("recharge", "c1", "except_pool")
    l.transitionTerminal("recharge", "c1", "except_pool", "credited")
    assert(calls == Vector(("recharge", "except_pool"), ("recharge", "credited")))
  }

  test("restoreTerminal bypasses write semantics (recovery path)") {
    val l = new TerminalLedger()
    l.setTerminal("recharge", "c1", "credited")
    l.restoreTerminal("recharge", "c1", "manual") // 直接覆盖，用于重建
    assert(l.terminalStateOf("recharge", "c1").contains("manual"))
  }

  test("histogram / entered / inFlight") {
    val l = new TerminalLedger()
    l.markSeen("recharge", "c1"); l.markSeen("recharge", "c2"); l.markSeen("recharge", "c3")
    l.setTerminal("recharge", "c1", "credited")
    l.setTerminal("recharge", "c2", "rejected")
    assert(l.enteredOf("recharge") == 3)
    assert(l.inFlightOf("recharge") == 1)
    assert(l.histogram("recharge") == Map("credited" -> 1, "rejected" -> 1))
  }
}
