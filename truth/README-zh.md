# 真源树 — bank-batch-transactor-demo

本目录是银行批量交易演示的**领域真源**，从源代码逆向提取（场景 A：只读提取，
没有从它生成过代码）。

## 这个演示是什么

对外部银行 / 基金公司系统的批量**充值**与**申购**交易。每批 items 跑过一条
**六阶段可续跑链**（file-gen → upload → wait-ack → poll → parse → classify），
由 `monarch-persistence` 的 `PersistentMonarch` 执行：每阶段 journal 一个快照，
宕机后从断点续跑，已完成的阶段不重做。结果做三分类（成功 / 失败 / 可疑）；
失败项被路由（重试 / 放弃 / 转人工），可疑项走复核确认，每个客户订单最终有且
只有一个终态。

## 如何自上而下阅读（不需要工具）

| 目录 | 内容 |
|---|---|
| `tree.yaml` | 根清单：名称、版本、来源、全文索引 |
| `types.yaml` | **类型词汇表**——全部 7 种记录类型 |
| `aggregates/` | 两个一致性边界：`batch-chain-run`（批次链运行）、`customer-order`（客户订单） |
| `pipelines/` | 六阶段链定义（阶段顺序、槽位、失败码） |
| `value-objects/` | 共享不可变领域类型（分类、路由动作、策略…） |
| `policies/` | 响应式领域规则（路由、复核、业务收尾、账本不变式） |
| `read-models/` | Web UI 提供的数据（统计、异常池、事件流） |
| `scenarios/` | 规模演示剧本（10 万客户、确定性结果） |
| `tools/` | `validate-tree.py` —— 机器门禁 |

每个目录有自己的 `README.md`。每个原子文件以
`kind` / `id` / `name` / `description` 开头——读这四行就知道它是什么。

## 约定

- **用稳定 ID 引用，不复制文本。** 交叉引用一律用 `id`
  （如 `aggregate: batch-chain-run`、`event: stage-completed`）。名字可改，ID 不变。
- **一文件一类型化记录。** `kind` 取自 `types.yaml`，校验器按类型检查必填字段。
- **领域重的概念中英双语**，沿用本仓 `README-zh.md` 的先例。
- **零机器味。** 只为字节恒等或编译器记账存在的字段（排序键、序号、代码生成提示）
  不属于这里——那是编译器的事。

## 机器门禁

```bash
python3 truth/tools/validate-tree.py truth/
```

检查：文件可解析、类型/ID 合法、ID 全局唯一、交叉引用全部可解、
每个聚合至少一个 slice、每个 policy 的 trigger 都有 slice 产出该事件。
领域真理由人定，工具只报告结构断裂。

## 这棵树不是什么

- 不是代码的第二份拷贝——它记录**领域事实**（阶段顺序、失败码、路由表、终态），
  不是实现细节。
- 不是从工作坊便签生成的——它从能跑的代码提取，所以词汇是
  pipeline/slice/policy，而不是事件风暴便签。
- 刻意省略了什么、代码含糊之处在哪里，见 `EXTRACTION-NOTES.md`
 （那些需要人类仲裁，不是提取器能定的）。
