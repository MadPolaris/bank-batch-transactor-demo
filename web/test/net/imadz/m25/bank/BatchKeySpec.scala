package net.imadz.m25.bank

import org.scalatest.funsuite.AnyFunSuite

/**
 * A4：批次键的编码/解码一致性 + 不变量强制。
 *
 * 回归背景：`jobKey`（编码）带 runId 而旧 `parseBatchKey`（解码）按无 runId 解析，
 * 对真实 key 直接抛 NumberFormatException；`chainOfBatchId` 取回的是 runId。
 * 现在 encode/decode 是同一格式的互逆操作。
 */
class BatchKeySpec extends AnyFunSuite {

  test("encode/decode round-trip") {
    val k = BatchKey.encode("a1b2c3d4", "recharge", 1, 5)
    assert(k == "bank-a1b2c3d4-recharge-r1-b5")
    assert(BatchKey.decode(k) == ("a1b2c3d4", "recharge", 1, 5))
  }

  test("decode extracts the chain, not the runId (old chainOfBatchId bug)") {
    val k = BatchKey.encode("a1b2c3d4", "purchase", 2, 0)
    assert(BatchKey.chainOf(k) == "purchase")
  }

  test("chain name containing '-' is rejected at creation (fail fast)") {
    val ex = intercept[IllegalArgumentException] {
      BatchKey.encode("a1b2c3d4", "my-chain", 1, 0)
    }
    assert(ex.getMessage.contains("must not contain '-'"))
  }

  test("runId containing '-' is rejected at creation") {
    intercept[IllegalArgumentException] {
      BatchKey.encode("a1-b2", "recharge", 1, 0)
    }
  }

  test("malformed keys fail fast on decode") {
    intercept[IllegalArgumentException] { BatchKey.decode("bank-a1b2c3d4-recharge-r1") }
    intercept[IllegalArgumentException] { BatchKey.decode("nope") }
  }

  test("multi-digit rounds/indexes round-trip") {
    val k = BatchKey.encode("deadbeef", "recharge", 12, 49)
    assert(BatchKey.decode(k) == ("deadbeef", "recharge", 12, 49))
  }
}
