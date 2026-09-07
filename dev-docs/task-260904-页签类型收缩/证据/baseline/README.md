# 改动前基线（task-260904 第一批）

| 项 | 值 |
|---|---|
| 采集时间 | 见 `baseline-captured-at.txt`（UTC） |
| 采集时的代码状态 | `feat/task-260904-tabtype-shrink` == `master`（`git rev-list --count master..HEAD` = 0，工作区干净）⇒ **实现一行都还没写** |
| 库 | `10.177.152.12:5432/cpq_db_0724`（共享开发库，`test` profile 默认库） |
| 采集方式 | 只读 SQL，见下表。🚫 未写库、未改任何一行 |

## 文件

| 文件 | 列 | 服务的 AC |
|---|---|---|
| `baseline-component-data.tsv.gz` | `id\|line_item_id\|component_id\|tab_type\|rowCount\|treeOrderMd5\|md5(snapshot_rows)\|md5(row_data)` | AC-13 / AC-14 / AC-15 / AC-25②③ |
| `baseline-inflight-cardvalues.tsv.gz` | `line_item_id\|quotation_number\|md5(quote_card_values)\|md5(costing_card_values)` | AC-13 |
| `baseline-component-tabtype.tsv` | `component_id\|code\|tab_type\|bom_recursive_expand` | AC-21③ / AC-25④ |
| `baseline-template-snapshot-tabtype.tsv` | `template_id\|component_id\|tab_type` | AC-14 |

`treeOrderMd5` = `md5(string_agg(row->>'__nodeId','>' ORDER BY 数组下标))`，只对 `tab_type='BOM'` 计算，其余置 `(n/a)`。
它比整段 `md5(snapshot_rows)` 更贴 AC-25② 的「**树行数与树序**逐字相同」——
整段 md5 会被任何一个业务值变化打破，分不清「树结构变了」还是「某个单价被别的会话改了」。**两个都留，失败时对照读。**

## 🚨 使用纪律

1. 共享库有并发写入。基线不等**不必然**是本次改动引入 —— 失败信息会打印差异行，**必须先做 A/B 归因**（对照干净 master 是否同样差异），🚫 不许直接归因「本次引入」。
2. 基线只在**改动前**采一次。重采会把回归本身洗掉 —— 🚫 **任何情况下不要重新生成这些文件**，除非主线明确决定重置基线并说明理由。

---

## ⚠️ 基线失效声明（2026-09-06，主线闸门 B 期间追加）

**本基线采集于 `2026-09-05 05:57:24 UTC`**（当时 `task-260904` 一行实现代码都还没写，时点无可挑剔）。
但共享开发库 `cpq_db_0724` 在此后被**多方写入**，本基线已**不能再作为「存量零变化」的判据**。

### 实测失效证据（2026-09-06 主线亲验）

| 用例 | 差异数 | 归属 |
|---|---|---|
| `ac13_inflightQuotationsUnchanged` | 12 处 | 1 处 = 主线亲验（`e4a6b929` @ QT-20260901-0233，`updated_at 09-07 00:38`）；11 处 = `QT-20260830-0210`（`09-07 01:09`，主线从未操作该单，为全量测试期间的写入） |
| `ac25_legacyTreeComponentsUnchanged` | 1 处 | `QT-20260830-0210` 的 `row_data`（同上时点），**非主线所为** |

### 🔬 A/B 归因：与本次代码改动无关

在**合并前的 master 代码**（`200494e6`，不含本任务任何改动）上跑同一比对：

```
Tests run: 8, Failures: 2
AC-13：在途单的冻结值与改动前不一致，共 12 处      ← 与改动后逐字相同
AC-25②：与改动前基线不一致，共 1 处                ← 与改动后逐字相同
```

⇒ **纯数据漂移，不是代码回归。** 且差异全部落在 `quote_card_values` / `costing_card_values` / `row_data`
（卡片值与用户编辑行，含大量 `(null) → 有值` 的懒算触发），**`snapshot_rows`（本次改动的作用面）一处未变**。

### 🚫 为什么不重采基线

重采会让这两条断言退化成「基线 = 当前」的**恒真判断**，永远绿、永远测不出东西。
本次改动的验证已由「旧基线 + A/B 归因」完成，证据链完整。

⇒ **给下一个人的处置建议**：这两条用例在共享库上**天生不稳定**。若第二批仍要用，应改为
「**改动前当场采基线 → 改动 → 立即比对**」的短窗口模式，而不是复用几天前的静态基线。
其余 AC-25 用例（`ac25_allLegacyComponentsStillOpenable` / `ac25_legacyComponentsStillOnBranchTwo` 等）
已全部改为**现场探测**，不受此影响 —— 那才是共享库上正确的写法。
