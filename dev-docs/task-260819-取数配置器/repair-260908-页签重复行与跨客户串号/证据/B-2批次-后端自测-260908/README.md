# 后端第二批（B-3 / B-4 / B-5）自测证据 · 2026-09-08

> 环境：8098（主线起的实例，热重载）｜库 `cpq_db_0724`｜全程只读，**未跑 `confirm=true`**

| 文件 | 是什么 |
|---|---|
| `B-6预览-第二批.json` | 带 `axisScope` 维度后的 `{recompile:true, confirm:false}` 完整响应 |
| `README.md` | 本文件 |

## 关键数字

```
recompileViews        = 30     recompileChanged = 0      ← sql_template 一个字没变（客户谓词那轮已落库）
axisScopeToWrite      = 30                               ← 30 个 builder_config 全都缺 axisScope 键
snapshotTemplates     = 5      snapshotEntries = 48
snapshotEntriesStale  = 5                                ← 恰好是 3 个「主件」视图在 5 个模板里的 5 次出现
```

`snapshotEntriesStale` 的 5 条明细（全部是 `axis_scope CLOSURE→SELF`）：

```
取值测试模板1        :: builder_7277969cc41c
ds 原生 v1.1         :: builder_c6a71e5e217a
取值测试模板2        :: builder_a71947b68d50
ds 原生 v1.2         :: builder_c6a71e5e217a
ds 原生 v1.0         :: builder_c6a71e5e217a
```

🔑 **其余 43 条一条都不动** —— 这就是「`SELF` 只作用在 `tabType='主件'`、B 族 15 个页签一行不少」的直接证据。

## `tabType='主件'` 的视图（实测 4 个）

```
builder_7277969cc41c | 主件 | QUOTE
builder_a515014e6ed3 | 主件 | COST_BASIC   ← 也会编成 SELF，但驱动层读不到（见下）
builder_a71947b68d50 | 主件 | QUOTE
builder_c6a71e5e217a | 主件 | QUOTE
```

## D-2（缺陷②只做报价侧）靠什么保证

**不是靠"记得别用"，是结构性的**：核价树走 `BomTreeRenderService → expandUncached(compId, customerId)`
（`ComponentDriverService:349`），该重载 **partNo 恒传 null** ⇒ `materialsByRoot.get(null)` = null
⇒ `_widenedHfPartNos` 为 null ⇒ `SELF` 分支根本进不来。

## 零写入复核

```
SELECT count(*) FROM component_sql_view WHERE builder_config ? 'axisScope'   →  0
```
预览跑了两次，键一个都没写进去。
