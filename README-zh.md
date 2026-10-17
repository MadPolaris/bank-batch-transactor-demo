# bank-batch-transactor-demo

[MadPolaris](https://github.com/MadPolaris) 引擎家族的**可独立运行演示**：金融批量交易处理
（BankChain——子批量流水线、重批路由、结果分类、复核处理）。

基于 [`monarch-core`](https://github.com/MadPolaris/monarch-core)（可续跑的阶段队列引擎）+
[`monarch-persistence`](https://github.com/MadPolaris/monarch-persistence)（Akka Persistence
落盘、基于 cursor 的断点续跑）+ [`common-core`](https://github.com/MadPolaris/common-core)。

从 [MadPolaris/playAkkaCQRS](https://github.com/MadPolaris/playAkkaCQRS) 抽离
（`dag-engine-core/` 模块）。[English](README.md).

## 模块

| 模块 | 说明 |
|------|------|
| `core/` | 引擎：六阶段 BankChain、`PersistentMonarch` 封装、protobuf 状态快照、测试 |
| `web/` | Play 2.8 演示页：批量看板、WebSocket 事件流、宕机注入按钮 |

## 运行

控制台演示（六个机械阶段，对接模拟银行系统）：

```bash
sbt "core / run"
```

启动 `net.imadz.m25.demo.BankBatchDemo`：

- **场景 1**——happy path：3 笔支付，Success / Failure / Suspicious 混合结果
- **场景 2**——故障路径：银行静默，`wait-ack` 以分类后的 `ACK_TIMEOUT`
  `StageFailedException` 失败（与生产链落盘用的是同一套故障词汇）

网页演示（100,000 客户 · 2,000/批 · 50 批 · 并发 6）：

```bash
sbt "web / run"
```

打开 http://localhost:9000/bank-batch——充值链（合作银行）→ 申购链（基金公司），
实时进度、人工异常池、手动重放，还有个宕机按钮：杀掉运行中的批次，演示断点续跑。

### 独立发行包

```bash
sbt stage
./target/universal/stage/bin/bank-batch-transactor-demo
```

目标机器不需要 sbt，只要 Java 11+。

## 恢复是怎么工作的

每批跑成一个 Akka Persistence 实体（`PersistentMonarch`）。每道工序完成后落一个
`StageDone` 事件：cursor **加完整 `BankChainState` 的 protobuf 快照**
（`core/src/main/protobuf/bank_chain.proto`——字段号就是跨版本兼容契约，不用 Java 序列化）。

重启时实体重放 journal，从最后一个完成的 cursor 续跑——做完的工序不重跑。
网页演示的宕机按钮直接停掉实体，下一条消息触发从 journal 恢复。

## 测试

```bash
sbt "core / test"
```

9 个测试，含真实的 crash→restart 断点续跑测试（a/b 各跑一次，c 重启后重跑一次），
验证 protobuf 快照的 round-trip。

## 说明

这是演示应用，不是类库：永不发布到 Maven 中央仓（`publish / skip := true`）。

License: Apache-2.0.
