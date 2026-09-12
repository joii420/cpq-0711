# backtask · repair-260911 核价 Excel 视图取数源对不上

> 🚫 只按本文件做。AC 原文在 `问题说明.md` ⑥，本文件只标编号不复制。
> 🚫 A0 已裁决方案甲，**不许改成乙或丙**（理由见 ⑤ 的「已否决备选」）。

## 背景一句话

Excel 列配置里的 `tabKey` 是**裸 `componentId`**，而核价 Excel 树走的 `CardEffectiveRows.parse` **只按 `componentId:sortOrder` 登记键** ⇒ `CardDataProvider.subtotalOf` 精确查 map 必然 miss ⇒ 四列恒 0。`ComponentDataEffectiveRows`（报价侧走的另一条路径）**早就是双键**，本次是把同一约定补齐到漏掉的这条。

## 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-2 | `CardEffectiveRows.parse` 产出 map 时双键登记：`out.put(cid, tr)`（裸 componentId，Excel 列 tabKey 约定）+ `out.putIfAbsent(cid + ":" + sortOrder, tr)`（CardRef 约定）。<br>⚠️ **优先级须与 `ComponentDataEffectiveRows:235-236` 逐字一致**：裸键 `put`、复合键 `putIfAbsent` —— 同 componentId 多实例时裸键由先出现者占，不制造第二种规则。 |
| **B-2** | AC-5 | 新增**契约测试**：断言两条 effective-rows 路径（`CardEffectiveRows.parse` / `ComponentDataEffectiveRows.compute`）对同一输入产出的**键集合口径一致** —— 同一 componentId 既能用裸键取到、也能用 `cid:sortOrder` 取到，且两者指向同一 `TabRows` 实例。<br>🚨 这条是本任务**唯一能在下次拦住同类问题**的东西（本缺陷是「同一诊断只落地一半」的第二次出现），不许省。 |
| **B-3** | AC-3, AC-4, AC-6, AC-7, AC-8 | 回归与还原实验：① 后端 `CardEffectiveRows*` / `ComponentDataEffectiveRows*` / `ExcelView*` / `TabJoin*` 相关测试全绿；② **还原实验**：把双键改回单键 → 契约测试必须红、Excel 四列回到 0；恢复后转绿；③ 报价侧 `quote_excel_values` 数值等价比对无差异（🚫 不要逐字节 diff，见 AC-3 注）；④ 确认未引入活表穿透（`loadFrozenComponentMetaMap` 的 `TemplateNotFrozenException` 语义不变）。 |

## 明确不做

| 不做项 | 理由 |
|---|---|
| 改 `CardDataProvider.subtotalOf` 的「精确命中不回退」 | A0 否决方案乙：那是刻意设计，同组件多实例有歧义 |
| 改任何模板的 `excel_columns` 配置 | A0 否决方案丙：治标 |
| 改 Excel 列表达式语义 / 让 `(总计)` 按节点取值 | 见 ⑥「已知语义」—— 那是配置问题不是引擎问题，不许加特例 |
| 前端任何改动 | 本次预期零前端改动 |
| 一次性刷新存量 `costing_excel_values` | 修好后由 `ensure-excel-values` 的 `IS NULL` 谓词决定；存量刷新方式另议（**若你发现存量不会自愈，停下来报主线，不要自行批量 UPDATE**） |

## 硬约束

- 🚨 **`CLAUDE.md` §3.2 红线**：本任务零迁移、零 DDL、零批量写。遇到需要 `DROP`/`TRUNCATE`/无 `WHERE` 的 `UPDATE`/`DELETE`/`rm -rf`/`git reset --hard` → **停下来报主线，你没有批准权**。
- 🚫 不许 `git commit`，不许 `git stash`（worktree 与他人共用）。
- worktree 内操作，测试在 worktree 的 `cpq-backend/` 下跑（`cd` 回主仓会测错树 = 假绿）。
- ⚠️ 全量 `mvnw test` 永远不可能全绿（`mat_*` 表在本库从未创建，25 个夹具文件 235 处引用恒红），**不是本次验收门槛**；只跑 B-3 点名的相关类。
