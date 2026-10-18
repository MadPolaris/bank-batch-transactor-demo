# 真源树 — bank-batch-transactor-demo

本目录是银行批量交易演示的**领域真源**，从源代码逆向提取（场景 A：只读提取，
没有从它生成过代码）。

## 这个演示是什么

对外部银行 / 基金公司系统的批量**充值**与**申购**交易。每批 items 跑过一条
**六阶段可续跑链**（file-gen → upload → wait-ack → poll → parse → classify）：
每完成一阶段就记下进度，中断后从断点续跑，已完成的阶段不重做。结果做三分类
（成功 / 失败 / 可疑）；失败项被路由（重试 / 放弃 / 转人工），可疑项走复核确认，
每个客户订单最终有且只有一个终态。

## 如何自上而下阅读（不需要工具）

| 目录 | 内容 |
|---|---|
| `workshop.yaml` | 根清单：格式版本、工作坊名称、会话顺序 |
| `aggregates/` | 两个一致性边界：`batch-chain-run`（批次链运行）、`customer-order`（客户订单） |
| `pipelines/` | 六阶段链定义（阶段顺序、槽位、失败码） |
| `value-objects/` | 共享不可变领域类型（分类、路由动作、策略…） |
| `policies/` | 响应式领域规则（路由、复核、业务收尾、账本不变式） |
| `read-models/` | Web UI 提供的数据（统计、异常池、事件流） |
| `scenarios/` | 规模演示剧本（10 万客户、确定性结果） |
| `inbox/` | 13 个工作坊产物（事件流…读模型），一产物一目录 |

每个目录有自己的 `README.md`。每个原子文件以
`kind` 开头——读它加上 `name` 就知道它是什么。（2026-10-18 已迁移到
truthsrc v0.4 格式，见 `MIGRATION-NOTES.md`。）

## 约定

- **用稳定 ID 引用，不复制文本。** 交叉引用一律用 `id`
  （如 `aggregate: batch-chain-run`、`event: stage-completed`）。名字可改，ID 不变。
- **一文件一类型化记录。** `kind` 取自 truthsrc v0.4 词汇表（见 `zhongdj/metarch-truthsrc` 的 `SCHEMA.md`），校验器按类型检查必填字段。
- **领域重的概念中英双语**，沿用本仓 `README-zh.md` 的先例。
- **零机器味。** 只为字节恒等或编译器记账存在的字段（排序键、序号、代码生成提示）
  不属于这里——那是编译器的事。

## 机器门禁

工具链在 `zhongdj/metarch-truthsrc` 仓（不在本仓）：

```bash
node bin/truthsrc.js validate --tree <path-to>/truth
node bin/truthsrc.js roundtrip --tree <path-to>/truth
```

`validate` 是结构门禁——要求 0 个 hard 错误（notice 只标注建模质量）。
`roundtrip` 证明 `compile ∘ decompose` 字节恒等：树是唯一的编辑面，
`canonical.json` 只是纯投影。领域真理由人定，工具只报告结构断裂。

## 这棵树不是什么

- 不是代码的第二份拷贝——它记录**领域事实**（阶段顺序、失败码、路由表、终态），
  不是实现细节。
- 不是从工作坊便签生成的——它从能跑的代码提取，所以词汇是
  pipeline/slice/policy，而不是事件风暴便签。
- 刻意省略了什么、代码含糊之处在哪里，见 `EXTRACTION-NOTES.md`
 （那些需要人类仲裁，不是提取器能定的）。
