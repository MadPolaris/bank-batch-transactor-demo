# Bank Durable Workflow（独立配置）

这是 Bank 的独立薄适配层：使用共享 runtime 的公开 `WorkflowHost`，没有复制引擎、外部服务实现或接入旧 Bank 执行链。原有源码、配置和 `build.sbt` 不需改写。当前公开候选下编译与7项策略测试已通过；最终HTTP/JVM验收见本文末节。

## 版本与启动前提

- 使用 **JDK 11**，沿用工程 Scala 2.13.14 / Play 2.8 / Akka 2.6.20。
- `monarch-r2.sbt` 只有显式传入 `monarch.r2.version` 才选择新 persistence/core 制品。`project/MonarchR2Profile.scala` 自动作用于 Play 项目，不启用R2时排除本次新增Scala源码、测试和路由；旧依赖不变。
- 新路由只生成forward router，不覆盖旧 `controllers.routes` 的reverse router。需先准备含 `WorkflowHost` 的明确候选，不能假设Maven Central已有R2，也不能替换冻结0.2.1。
- 候选：`0.3.0-dev.r2-20261106-dc168f96fa62`，JAR SHA-256 `2dea09f3c97b4832fd78552f546a1d5fb4f68d5b92c42cb3d4466101c59d21b0`；core固定0.2.1。
- `monarch.r2.repository`、`monarch.core.repository` 是Maven URL。启用R2时override对core/web均生效，profile补纯Java LevelDB依赖；不发布应用制品。
- 外部服务必须是**另一个独立进程**，实现下述固定 socket 协议。Web 应用不自动创建、启动或停止服务，不依赖 runtime 的测试类。

## 独立外部服务

运行已准备好的同版本 runtime 测试 fixture，或者兼容该协议的本机服务。fixture 的类路径需单独提供，不能把普通 persistence 发布 jar 当作含有测试类的 jar。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
# MONARCH_R2_FIXTURE_CLASSPATH 包含已准备的 fixture 测试类及 scala-library。
java -cp "$MONARCH_R2_FIXTURE_CLASSPATH" \
  net.imadz.monarch.persistence.ExternalTaskServiceFixture \
  /absolute/owned/bank-external-ledger 19091
```

服务应报告 `EXTERNAL_READY 19091`。它的账本目录必须独占、与 Web journal 分离，只用于本 Bank 示例；不要连接另一个演示或真实金融系统。初始 epoch 固定为 `ledger-1`。`FORGET` 后的 epoch 变化不会被适配器自动接受，也不构成安全重发证据。

fixture 自己维护充值和购买的模拟结果；第一次执行、ID 以 `1` 结尾的充值条目返回 `RETRY`，其余合法充值记入 100，合法购买扣除 100 并增加模拟持仓。**只有外部 COMPLETE 命令触发这些模拟业务效果**，SUBMIT、QUERY、页面轮询和宿主恢复都不能替代外部完成。

## Web 启动

下面使用当前隔离候选；不覆盖任何已存在的候选版本或应用journal。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
cd /Users/zhongdj/Workspaces/mad-polaris/bank-batch-transactor-demo
export BANK_DURABLE_PLAY_SECRET="$(openssl rand -hex 32)"
export MONARCH_DURABLE_BANK_DIR=/Users/zhongdj/Workspaces/mad-polaris/bank-batch-transactor-demo/target/durable-bank-owned-demo
export MONARCH_DURABLE_BANK_EXTERNAL_PORT=19091
export MONARCH_DURABLE_BANK_EXTERNAL_CONTROLS=true
sbt -Dmonarch.r2.version=0.3.0-dev.r2-20261106-dc168f96fa62 \
  -Dmonarch.r2.repository=file:///Users/zhongdj/Workspaces/mad-polaris/monarch-persistence/target/r2-candidate-20261106-dc168f96fa62 \
  -Dmonarch.core.repository=file:///Users/zhongdj/Workspaces/mad-polaris/monarch-persistence/target/demo-candidate-20261021 \
  -Dconfig.resource=application-durable-bank.conf \
  -Dhttp.address=127.0.0.1 \
  "web / run 9001"
```

打开 `http://127.0.0.1:9001/bank-batch/durable`。9001 是 HTTP 端口，19091 是 socket 端口，不是第二个 HTTP 服务；端口占用时先显式调整，不能停止他人服务。

新配置独立于 `application.conf`，只使用 `durablebank.Routes`，禁用 BankRuntimeModule/BankModule/DB/evolutions，不加载旧cluster和serializer绑定。纯Java LevelDB journal/snapshot使用必填绝对路径 `MONARCH_DURABLE_BANK_DIR`；secret由`BANK_DURABLE_PLAY_SECRET`注入。HTTP仅loopback、控制器核对远端地址，并对POST启用CSRF。不要并发打开同一存储目录或用删除journal冒充恢复。

## 操作与 HTTP API

1. 输入稳定 `jobId`（默认 `bank-demo-1`）及 count（默认 3，范围 1..20），提交。
2. 页面每秒读取一次已提交 Batch View，显示 item/batch 阶段、执行序号、结果与原因。它不是 observer 推演状态。
3. 显式点击「列出当前作业的外部任务」，逐个点击外部完成按钮。新的重试充值/购买任务出现后，再列出并完成；页面没有自动 COMPLETE 循环。
4. 外部完成响应不等于宿主成功，继续观察 `/state`，由runtime查询/验证并提交。
5. 自动查证有有限预算，长时间未完成时保持Blocked；显式点击「查证原操作」可授予下一有限查询轮次。页面轮询从不自动reconcile，不更换operationId。

```bash
# GET页面获得session cookie与CSRF token；浏览器按钮已自动携带。
# 以下POST示意需先把页面中的token置入CSRF并提供相应cookies.txt。
curl -b cookies.txt -X POST http://127.0.0.1:9001/api/bank-durable/bank-demo-1/start \
  -H "Csrf-Token: $CSRF" -H 'Content-Type: application/json' -d '{"count":3}'
curl http://127.0.0.1:9001/api/bank-durable/bank-demo-1/state
curl http://127.0.0.1:9001/api/bank-durable/external/tasks
# operationKey 必须来自该 job 的已提交 batches：batchId + /execute。
curl -b cookies.txt -X POST http://127.0.0.1:9001/api/bank-durable/external/complete \
  -H "Csrf-Token: $CSRF" -H 'Content-Type: application/json' \
  -d '{"jobId":"bank-demo-1","operationKey":"<该作业实际的 operationKey>"}'
```

`start` 空 body 或 `{}` 等价于 `{"count":3}`；只支持 count 字段。按 sequence 0..n-1 串行发送稳定 `jobId-item-1` 等条目，同一作业使用完全相同的规范化 payload，包含 count/amount/fund。只有所有 Offer 明确 Accepted 后才 CloseInput。成功接纳返回 202，并逐项列出 `duplicate`；相同输入重放可被幂等接纳，改变 count 会冲突。Rejected 返回 409，Backpressured 返回 429，ask 超时返回 504（结果未知而非未提交），其他不可用返回 503。部分接纳或超时后只能使用相同 job/body 继续，不生成随机 runId。

jobId 和 itemId 均需匹配 `[A-Za-z0-9][A-Za-z0-9_-]{0,79}`；jobId 要为派生的 `-item-n` 留空间，建议不超过 72 字符。实例使用现有 `ActorSystem.toTyped` 中唯一的 `bankdurable` namespace，保留 WorkflowHost 默认最多 32 个作业的有界限制。

外部控制默认关闭；只有设置 `MONARCH_DURABLE_BANK_EXTERNAL_CONTROLS=true` 才开放两个代理接口。完成请求仅接受 jobId/operationKey，并检查 operation 属于该作业的已提交 batch。它不接受调用者提供的 SUCCESS 或结果。两个接口只是**本机演示控制面**，没有生产鉴权，不应向网络开放。控制器独占 4 线程、32 队列的外部 I/O executor，关闭应用时通过 `ApplicationLifecycle` 关闭连接及 executor；控制器 actor ask 为4秒，公共host查证每次ask为5秒且整体并发有界。

显式查证入口：POST `/api/bank-durable/:jobId/reconcile?batchId=...`，同样携带CSRF。默认Operation自动观察120次、人工30次/轮、Inbox300次/轮；首次接管冻结这些预算，重启不重置。

## 固定协议和证据边界

- 单连接单命令，固定连接 `127.0.0.1`；连接超时 2 秒，完整响应（含分段读取）总读取期限 2 秒。
- 大端 `int32 bodyLength`，body 为 `int32 stringCount` 后跟重复的 `int32 utf8Length + strict UTF-8`；不是 Java `writeUTF`。最多 16 个字符串（包括命令/状态），每字符串最多 64 KiB，完整帧连长度前缀最多 128 KiB，拒绝尾随数据。
- `SUBMIT [bank, operationKey, requestDigest, ledgerEpoch, effectKey, WF2:<area>:<base64url-itemId>~<execution>,...]`。
- SUBMIT 返回 `[RUNNING, task, epoch]` 或 `[SUCCEEDED, task, epoch, result]`，**没有** key/digest 后缀。
- `QUERY [operationKey]` 返回 `[RUNNING, task, epoch, key, digest]` 或 `[SUCCEEDED, task, epoch, result, key, digest]`；`NOT_FOUND` 不允许安全重发。
- `result` 为 `WR2:<recharge|purchase>:<base64url-itemId>~<SUCCESS|RETRY>,...`。适配器检查严格格式、任务身份及前次 task 一致性、epoch、operationKey、请求 digest、区域和**全部且仅一次**的条目覆盖；缺失、重复、外来条目和未知结果不会被盲目当作成功。
- runtime Request 的 payload 始终是 `BatchCodec.manifestToBytes`，不是 WF2；WF2 仅是外部 wire payload。Request 固定 step `execute`、connector `bank/complete-1`、epoch `ledger-1`，reservation 为 manifest 的 reservationId；effect key 由冻结的 itemId/area/execution 组成。

充值用 countOrWait(3, 300ms)、`ftp=1`，最多执行 2 次；购买单条、`purchase-system=1`，最多执行 1 次。已确认的充值 SUCCESS 路由 purchase，RETRY 延迟 200ms；只有已确认的购买 SUCCESS 才 Completed。未知结果不释放资源或生成成功，purchase RETRY 被 Hold；执行预算由 runtime 检查。资源释放证据为 `operationKey + '\n' + WR2-result`，校验外层和证据内层 owner 完全匹配，并验证已知结果格式及资源区域。

这些是**独立本地模拟账本的证据**，不是金融机构签名、结算证明或真实资金安全承诺。策略测试7/7通过；真实HTTP/独立进程结果见下节。

## 本地验收（2026-11-06）

- 使用JDK11、冻结R2公共JAR，core/web完整源码在独立target编译通过；7/7策略测试通过。
- 最终stage：`web/target/r2-stage-dc168f96fa62-final`；未覆盖旧`target/universal/stage`。
- `monarch-persistence/examples/verify_http_consumers.py --kind bank`通过真实HTTP验收：页面JS语法、POST缺CSRF拒绝、重复输入幂等/改count冲突、完整充值申购、预算Blocked后杀整个PlayJVM/查证原操作、终态再次重启。
- 两作业共6项：外部账本credit=6/purchase=6，余额0、持仓600；每作业只有item-1充值两次，其余一次、申购各一次。10个外部任务、10次submit、重复业务效果0。
- 实际加载stage内persistence JAR SHA与上文冻结候选一致；不只是声明依赖。
- 证据：`monarch-persistence/target/r2-http-evidence-20261106/r2-http-bank-tj3yjx0p/`，含report/HTML/JS/LevelDB/WAL、三个不同Play PID与退出记录。只停止本验证创建的进程。
- 首次HTTP门用`-Dhttp.port`没覆盖profile内`play.server.http.port`，启动默认9001导致请求超时；未发生业务输入，已停止该测试进程。验证器改成显式`-Dplay.server.http.port`和独占cwd后通过。不是产品恢复失败，也不隐藏这次失败证据。
- 未指定R2版本时实际检查sources、routes tasks、dependencyOverrides，确认旧构建不含本次新增项；这是配置检查，不声称已运行旧页面的全量功能测试。
- 是HTTP契约与进程恢复验收，未自动驱动真实浏览器，不声称视觉/浏览器兼容性、生产journal或物理断电认证。
