package net.imadz.m25.bank

/**
 * 批次键：`bank-<runId>-<chain>-r<round>-b<index>`。
 *
 * 它同时是分片 entityId 与 journal persistenceId 的后缀。'-' 是分隔符，
 * 因此 **chain 名与 runId 都禁止包含 '-'**——在创建时强制（fail fast），
 * 而不是靠调用方自觉。
 *
 * 编码与解码都走这里：之前 `jobKey`（编码）与 `parseBatchKey`（解码）各写一套，
 * 编码带 runId 而解码按无 runId 解析，导致解码对真实 key 直接抛 NumberFormatException、
 * `chainOfBatchId` 取回的是 runId 而不是 chain。现在 encode/decode 是同一格式的
 * 互逆操作，解码不可能与编码不一致。
 */
object BatchKey {

  /** 编码。chain/runId 含 '-' 直接抛 IllegalArgumentException（创建时强制）。 */
  def encode(runId: String, chain: String, round: Int, index: Int): String = {
    require(chain.nonEmpty, "chain name must not be empty")
    require(!chain.contains("-"),
      s"chain name must not contain '-': '$chain' (batch keys are '-' delimited)")
    require(runId.nonEmpty, "runId must not be empty")
    require(!runId.contains("-"),
      s"runId must not contain '-': '$runId' (batch keys are '-' delimited)")
    s"bank-$runId-$chain-r$round-b$index"
  }

  /**
   * 解码为 (runId, chain, round, index)。格式不对直接抛 IllegalArgumentException。
   * chain 在 encode 时已保证不含 '-'，因此这里的 split 不会歧义。
   */
  def decode(key: String): (String, String, Int, Int) = {
    require(key.startsWith("bank-"), s"malformed batch key (missing 'bank-' prefix): '$key'")
    val parts = key.stripPrefix("bank-").split("-", -1)
    require(parts.length == 4 && parts(2).startsWith("r") && parts(3).startsWith("b"),
      s"malformed batch key: '$key' (expected bank-<runId>-<chain>-r<round>-b<index>)")
    val round = parts(2).stripPrefix("r").toInt
    val index = parts(3).stripPrefix("b").toInt
    (parts(0), parts(1), round, index)
  }

  /** 仅取 chain（observer 回调里最常用的）。 */
  def chainOf(key: String): String = decode(key)._2
}
