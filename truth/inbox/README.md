# inbox/ — 工作坊产物重建（reconstructed）

> **声明**：本目录是**场景 A 重建**——从未开过真实工作坊。这里的 13 个产物
> 是依据 `truth/` 树中已有的领域知识**反推**出来的（"如果开过工作坊，每个
> 阶段会产出什么"），不是真实便签的格式化。内容只取自树中已有事实，
> 不编造；凡反推中需要解读的地方，以树为准，树没有的不写。

## 13 个产物（一阶段一产物，目录序号即规范顺序）

| 目录 | 产物 | 内容 |
|---|---|---|
| `01-event-flow/` | 事件流 | 领域事件序列。每条事件带 `sequence`——它在业务叙事中的位置，**问题空间**的描述，不是机器计数器 |
| `02-commands/` | 命令与触发 | 命令、角色、外部系统、命令→事件因果链 |
| `03-aggregates/` | 聚合清单 | 聚合边界划分 |
| `04-subdomains/` | 子域划分 | **本 demo 跳过**：批量结算是一个小的限界上下文，无真实子域可分（见下） |
| `05-context-map/` | 上下文映射 | **限界上下文级别**的关系：本上下文与外部系统上下文如何协作、上下游 |
| `06-aggregate-detail/` | 聚合细化 | 聚合自身：状态槽位、字段、值对象定义 |
| `07-aggregate-relations/` | 聚合间关系 | **聚合级别**的引用：哪个聚合引用哪个、基数、业务含义（问题空间语言，不写 foreignKey） |
| `08-message-detail/` | 消息字段细化 | 事件/命令的字段定义 |
| `09-lineage/` | 字段血缘 | 事件/命令/状态之间的字段血缘关系 |
| `10-policies/` | 策略定义 | 业务策略：触发事件 → 调用的切片 |
| `11-stories/` | 用户故事 | 从演示场景反推的用户故事（role/goal/benefit） |
| `12-use-cases/` | 用例 | 前置条件、步骤、扩展 |
| `13-read-models/` | 读模型 | 定义含字段 + 字段来源（血缘） |

### #5 / #6 / #7 分工（不要混）

- **#5 上下文映射**：站在**限界上下文**的高度——"我们这个上下文跟谁协作"。
  本 demo：批量结算上下文 ↔ 外部银行/基金公司系统上下文（文件交换协作）；
  充值链 → 申购链（上下游）。
- **#6 聚合细化**：站在**聚合内部**——状态槽位有哪些、字段什么类型、值对象长什么样。
- **#7 聚合间关系**：站在**聚合之间**——A 聚合引用 B 聚合，基数是什么
  （1:N / N:1 / 1:1），一句话业务含义。例如"一批运行的分类结果写入多行
  客户订单终态（1:N）"。

### 到 truthsrc v0.3 阶段码的映射

| truthsrc v0.3 阶段 | 本目录 |
|---|---|
| `eventStorming` | `01-event-flow/` |
| `commandStorming` | `02-commands/`（含 policy 萌芽） |
| `aggregateDiscovery` | `03-aggregates/` |
| `subdomainExploration` | `04-subdomains/`（本 demo 无，跳过） |
| `contextMapping` | `05-context-map/` |
| `refining` | `06-aggregate-detail/`、`07-aggregate-relations/`、`08-message-detail/`、`09-lineage/` |
| （`commandStorming` 的 policy 面） | `10-policies/` |
| `storyMapping` | `11-stories/` |
| flow 模型（`use-cases.yaml`） | `12-use-cases/` |
| readModel 面 | `13-read-models/` |

### 关于 sequence 的说明

- `01-event-flow/` 中每条事件的 `sequence` **保留**：它是"事件在业务叙事中的位置"，
  问题空间的描述（用户裁定：去机械留语义）。
- 目录序号（01–13）即规范顺序；**不做跨文件连续重编号**——那是编译器的事，
  不进树。

## 便签格式

```yaml
# ═══ truth note — reconstructed from truth/ tree (no live workshop was held) ═══
kind: note
type: event            # event | command | role | policy | aggregate | readModel | ...
name: <Name>
name_zh: <中文名>
description: >
  <problem-space description>
description_zh: >
  <中文描述>
sequence: 3            # 仅事件：业务流中的位置（问题空间，非机器计数器）
triggers: [...]        # 命令/策略：什么触发它
produces: [...]        # 命令：产生什么事件
```

文件名用 `<slug>.yaml` 简单命名，不做 board/seq 元组——用户明确要求简化阶段机制。

## 校验

`python3 truth/tools/validate-tree.py`（仓根执行）：I1/I2 检查 inbox 便签
能解析、头字段齐全、事件带 `sequence`。不做跨文件连续性检查。

## 角色—权限矩阵（02 × 13，不另起产物）

`02-commands/` 与 `13-read-models/` 使用**同一套角色词汇**（相同 id）；
对应关系显式写出，即一张完整权限矩阵：

| 角色 id | 名称 | 可签发命令 `can_issue` | 可查看读模型 `can_view` |
|---|---|---|---|
| `operator` | 运营人员 | start-batch-run、simulate-crash、replay-exception | chain-stats、event-feed、exception-pool |
| `system` | 系统 | settle-recharge、settle-purchase、abandon-order、escalate-to-manual、park-in-exception-pool | （无——系统不看界面） |

- 角色便签（`02-commands/role-*.yaml`）同时列出 `can_issue` 与 `can_view` 两面；
  读模型便签带 `visible_to: [role ids]`。
- **诚实声明**：本演示代码里没有显式 RBAC，以上是重建时的最小假设——
  点按钮的人（运营人员，演示时兼任演示者点宕机按钮）与自动执行的系统。
  读模型无访问控制，谁打开页面谁看；矩阵按"签发"区分，不虚构"查看"限制。
