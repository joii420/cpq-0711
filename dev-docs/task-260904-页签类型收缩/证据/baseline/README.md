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
