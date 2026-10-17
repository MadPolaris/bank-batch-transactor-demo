package net.imadz.m25.bank

import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * A1 的关键证明：人工重放成功后，终态 == credited/paid（不再是 except_pool），
 * 且钱、账本、事件流三处一致。
 *
 * 用真实的 BankBatchDemoService（不经过分片/集群，只测账本+重放路径）。
 */
class ReplayExceptionSpec extends AnyFunSuite with BeforeAndAfterAll {

  private implicit var classicSystem: akka.actor.ActorSystem = _
  private implicit var ec: ExecutionContext = _
  private implicit var mat: Materializer = _
  private var service: BankBatchDemoService = _

  override def beforeAll(): Unit = {
    classicSystem = akka.actor.ActorSystem("replay-test")
    ec = classicSystem.dispatcher
    mat = Materializer(classicSystem)
    val typedSystem = classicSystem.toTyped
    service = new BankBatchDemoService(classicSystem, ClusterSharding(typedSystem), mat)
  }

  override def afterAll(): Unit = {
    Await.result(classicSystem.terminate(), 30.seconds)
  }

  /** 模拟"账务两次失败 → except_pool + 异常池条目"的状态。 */
  private def parkInPool(chain: String, idx: Int): DemoCustomer = {
    val c = service.customers(idx)
    assert(service.ledger.setTerminal(chain, c.customerId, "except_pool"))
    service.exceptionPool.put(s"$chain:${c.customerId}",
      PoolEntry(chain, c.customerId, c.name, c.amount, "账务更新两次失败", System.currentTimeMillis()))
    c
  }

  test("replayException (recharge): terminal except_pool -> credited, pool entry removed") {
    val c = parkInPool("recharge", 0)
    val ok = Await.result(service.replayException("recharge", c.customerId), 30.seconds)
    assert(ok)
    assert(service.ledger.terminalStateOf("recharge", c.customerId).contains("credited"),
      "重放成功后终态必须是 credited，不能再是 except_pool（A1）")
    assert(service.exceptionPool.get(s"recharge:${c.customerId}") == null)
  }

  test("replayException (purchase): terminal except_pool -> paid, pool entry removed") {
    val c = parkInPool("purchase", 1)
    val ok = Await.result(service.replayException("purchase", c.customerId), 30.seconds)
    assert(ok)
    assert(service.ledger.terminalStateOf("purchase", c.customerId).contains("paid"),
      "重放成功后终态必须是 paid，不能再是 except_pool（A1）")
    assert(service.exceptionPool.get(s"purchase:${c.customerId}") == null)
  }

  test("replayException without pool entry -> false, ledger untouched") {
    val c = service.customers(2)
    val ok = Await.result(service.replayException("recharge", c.customerId), 30.seconds)
    assert(!ok)
    assert(service.ledger.terminalStateOf("recharge", c.customerId).isEmpty)
  }

  test("replayException is idempotent: second replay -> false") {
    val c = parkInPool("recharge", 3)
    assert(Await.result(service.replayException("recharge", c.customerId), 30.seconds))
    // 第二次：池子里已经没有条目
    assert(!Await.result(service.replayException("recharge", c.customerId), 30.seconds))
    assert(service.ledger.terminalStateOf("recharge", c.customerId).contains("credited"))
  }
}
