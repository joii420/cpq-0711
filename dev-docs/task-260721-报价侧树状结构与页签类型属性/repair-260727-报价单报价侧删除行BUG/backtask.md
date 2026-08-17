# 后端任务 —— repair-0727 报价侧树页签删除行 BUG

> 上游：`需求说明.md`（§11 根因勘察 + 实机复现 / §12 裁决 / §13 方案定稿）、`api.md`（契约）。
> 分支：`fix/repair-0727-tree-delete-row`；工作区：`.claude/worktrees/repair-0727-tree-delete`。
> **共享约束**：dev server(8081/5174)、远程 DB、node_modules 全局共享 —— **不要**在 worktree 里另起 server 或重装依赖。

---

## 0. 开工前必读

- `docs/方案制定前必读.md`（历史教训速查）
- `docs/反模式.md` **AP-54**（过滤子集下标 / 单一口径不变量）、**AP-51**（driver 行数纪律）、**AP-22**（多源不同源）
- `需求说明.md` §11.1 / §11.2 / §11.3 的实测数据 —— **不要重新推测根因，按实测事实做**

**验收数据（现成，勿删）**：报价单 `QT-20260726-0006`
- `quotationId = 69aab7ec-9140-427b-b717-ed0a806485d1`
- `lineItemId  = dfee1e78-94c7-4af1-899b-caa9b60fd29a`
- 树组件 `componentId = 656c9b87-cda5-4c32-8d72-45d94714f77a`（`tab_type='BOM'`，`rowKeyFields=["料件"]`）
- 树 6 行；**row4** = 992/AgNi11#-Ⅰ 挂 `S-3120014539`，**row5** = 同料号挂 `S-80011`（DAG 重复子件）
- 现存 1 条墓碑（针对 row4）；`row_data` 5 行

---

## B0（前置·必须先做）—— F-1：`effKey` 四处口径核查与对齐

### 背景（实测事实）

task-0721 B10 给树行 effKey 加了 `__nodeId::` 前缀，但**只加在一处**：

| # | 位置 | 是否带 `__nodeId::` 前缀 |
|---|---|---|
| 1 | `FormulaCalculator.computeRows`（约 :786-810） | ✅ 带（`deleted != null` 且行有 `__nodeId`） |
| 2 | `CardSnapshotService.buildResolvedRows`（约 :1800-1806） | ❌ 不带 |
| 3 | `RowDataMaterializer`（约 :177-183，注释还写着"与 calculate 逐字节一致"） | ❌ 不带 |
| 4 | 前端 `useCardSnapshots.buildUniqueRowKeys` | ❌ 不带（前端任务 F0） |

**实测证据**：QT-20260726-0008 树页签 `formulaResults[0].rowKey = "S-3120014539::主料1"`（带前缀），
而 #2/#3 按无前缀键查 `frByKey` / `editByKey` → **miss**。当前未爆是因为该测试模板的树页签
**没有配 FORMULA 列**（`values` 为空对象）；配上即显形（公式列取不到值、editRows 绑定丢失）。

### 任务

1. **先核查再改**：写一个最小验证（单测或临时脚本）确认 #2/#3 在"树行 + deleted != null + 有 FORMULA 列"
   时确实取不到 `formulaValues` / `editValues`。**把验证结论写进 PR 说明**（若证伪，停手并报告，不要盲改）。
2. 确认后，把 #2 / #3 的 effKey 计算**对齐到 #1 的口径**（同样的 `deleted != null && __nodeId 非空 → nodeId + "::" + base`）。
   建议抽成 `FormulaCalculator` 的一个 `public static` 工具方法（如 `buildRawRowKeys(...)`），三处共用，
   杜绝再次漂移。
3. **存量兼容（重要）**：查 `editByKey` / `frByKey` 时，若按新键（带前缀）未命中，**回退用旧键（不带前缀）再查一次**。
   理由：存量单据的 `editRows` 是用旧键存的，直接切换会让历史编辑值全部读不到。
   落地位置：`buildResolvedRows` 与 `RowDataMaterializer` 的两处查表。

### 验收

- [ ] 核查结论有书面记录（证实 / 证伪）
- [ ] 三处 effKey 由同一份代码产出（不再各写一遍）
- [ ] 旧键回退分支有单测覆盖（存量 editRows 仍能读到）
- [ ] 核价侧 `deleted == null` → 不进新分支，effKey 逐位不变（单测断言）

---

## B1 —— `DeletedRowKeys` 支持 `nodeId` 维度

文件：`cpq-backend/src/main/java/com/cpq/quotation/rowkey/DeletedRowKeys.java`

1. `Tombstone` record 增加 `String nodeId`（可空）。
   **注意**：该 record 已被多处 `new Tombstone(effKey, fp)` 构造 —— 保留双参构造（`nodeId=null`）以免大面积改动。
2. `parse(String json)`：读取 `nodeId` 字段（缺失 → null）。
3. **新增** `keepMask(List<String> effKeys, List<String> fps, List<String> nodeIds, List<Tombstone> deleted)`：
   按 `api.md §2.2` 的规则匹配。
4. 保留旧三参 `keepMask(effKeys, fps, deleted)` → delegate 到新重载，`nodeIds` 传全 null → **逐字节旧行为**。
5. 类注释更新：说明 nodeId 维度的引入原因（同料号多 occurrence）与向后兼容策略。

### 验收
- [ ] 单测：新墓碑(含 nodeId) × 两条同 fp 不同 nodeId 的行 → 只删匹配的那条
- [ ] 单测：旧墓碑(无 nodeId) × 同上两行 → 两条都删（**证明退化行为保持**）
- [ ] 单测：非树行（nodeId=null）× 新墓碑 → 按 fp 匹配
- [ ] 旧三参重载的现有单测**全部不改**且通过

---

## B2 —— 三处 `keepMask` 调用点传入逐行 `nodeId`

| # | 文件 | 位置 |
|---|---|---|
| 1 | `FormulaCalculator.java` | 约 `:818-822` |
| 2 | `CardSnapshotService.java`（`buildResolvedRows`） | 约 `:1811-1815` |
| 3 | `RowDataMaterializer.java` | 约 `:193-199` |

每处：遍历**完整** `baseRows`，从 **row 顶层** `__nodeId` 取值（不在 `driverRow` 里！），组成与 `fps` 等长的
`List<String> nodeIds`（无该列 → null），传新重载。

> ⚠️ **AP-54 头号不变量**：过滤前的唯一化、下标推进方式一律不改；只把匹配依据从 `fp` 换成 `nodeId+fp`。
> 迭代仍走完整集，命中即 `continue`，**绝不重排、绝不在过滤后的子集上重算键**。

### 验收
- [ ] 三处传参一致（同一取值方式）
- [ ] 核价侧 `deleted == null` 路径未被触碰

---

## B3 —— 树删除写墓碑带 `nodeId` + 补物化 + 补响应投影

文件：`cpq-backend/src/main/java/com/cpq/quotation/service/QuotationTreeService.java`

### B3.1 墓碑带 nodeId
- `appendRowTombstone(...)` 增加 `nodeId` 入参并写入 JSON；判重条件从"fp 相同"改为 **"fp 相同 且 nodeId 相同"**。
- ROW 分支（约 `:461-471`）传入被删节点的 `nodeId`。
- 级联分支（约 `:474-493`）写的是**非树页签**的行 → `nodeId` 传 `null`（保持 fp 单键语义）。

### B3.2 ⭐ 补物化（实测 ①-b，必做）

**实测**：现在 `executeDelete` 只调 `cardSnapshotService.snapshotQuoteSideOnly(li, q)`，
**不重新物化 `row_data`**；且 `refresh-card-snapshot` 也不补 →
删除后 `snapshot_rows=6 / row_data=5 / 墓碑=2`，渲染 4 行 vs row_data 5 行 → **下标错位**（AP-54 族）。

改法：把该调用换成 `cardSnapshotService.refreshQuoteProjection(lineItemId)`
（它的职责已含"② 物化 row_data（同墓碑 → N-1，与前端展开同序）"）。

**兜底**：`refreshQuoteProjection` **仅限 `DRAFT`**、失败返 `null`。
→ 返回 null 时**必须**回落原 `snapshotQuoteSideOnly(li, q)`，且响应体**不带** `componentData`
（前端据此回落旧行为，见 `api.md §1.2`）。不得因此让非 DRAFT 单据静默不刷新。

### B3.3 响应投影
- `refreshQuoteProjection` 返回的 Map 已含 `quoteCardValues / quoteExcelValues / quoteValuesAt / componentData`
  → 合并进 `executeDelete` 的响应（保留既有 `deletedNodeIds` / `cascadeDeletedRowKeys`）。

### 验收（用 QT-20260726-0006 真实复现，不接受只跑单测）
- [ ] 删 row5 后：响应含 `componentData`，其中树组件 `rowData` 行数 = 4（原 5 − 本次 1）
- [ ] DB 校验：`snapshot_rows=6` / `row_data=4` / `deleted_row_keys` 2 条且第 2 条含 `nodeId`
- [ ] row4 的删除**不受影响**（另一条 AgNi 该在的还在 —— 见 B4）
- [ ] PRUNE 模式回归：剪枝 + 级联仍按 task-0721 行为，`retainedParts` 正确

---

## B4 —— `computeRowFpForNode` 精确定位（同节点多行）

文件：同上，约 `:537-546`。

现状：按 `__nodeId` 找**第一条**匹配行取 fp。同一 `nodeId` 下有多条业务行时，删第 2 行会算出第 1 行的 fp
→ **删错行**。

**本次要求（最小且安全）**：
1. 收集该 `nodeId` 下的**全部**行；
2. 若只有 1 行 → 行为不变；
3. 若 >1 行 → 用请求里的 `rowKey`（前端 `__effKey`）在这些行中定位：
   按 B0 对齐后的 effKey 算法逐行算出 effKey，取与 `rowKey` 相等的那条；
4. 仍无法定位（算不出 / 不匹配）→ 退回"第一条"，**并 `LOG.warn` 记录**（便于后续定位 Q6）。

> 不要在此引入"节点内行序"等新契约字段 —— 那属于 Q6 定性后的方案，不在本次范围。

### 验收
- [ ] 单测：同 nodeId 两行内容不同 → 删第 2 行，被墓碑的是第 2 行
- [ ] 单测：同 nodeId 单行 → 行为与改动前一致
- [ ] 无法定位时有 warn 日志

---

## B5 —— 提交期行键校验消费墓碑 + 树按 `__nodeId` 判重（**P0**）

### B5.1 喂数据（`QuotationService.submitForApproval`，约 `:719-745`）
- 构造 `RowKeyUniquenessService.CompRows` 时增加两项：`cd.deletedRowKeys`、以及该 lineItem 的 `li.deletedTreeNodes`。
- 建议把 `CompRows` 扩成 `record CompRows(String componentId, String snapshotRowsJson, String rowDataJson, String deletedRowKeysJson)`，
  `LineItemComps` 增加 `String deletedTreeNodesJson`。

### B5.2 过滤 + 判重（`RowKeyUniquenessService.collectConflicts`，约 `:52-77`）

顺序**必须**是：

```
snapshot_rows 全集
  → ① 剪枝过滤：__nodeId 前缀命中 deleted_tree_nodes 的行剔除     （复用 CardSnapshotService 同款前缀匹配语义）
  → ② 行墓碑过滤：按 api.md §2.2 规则剔除                        （复用 DeletedRowKeys.keepMask）
  → ③ 逐行算判重键：树行 = computeDedupKey(...) + "@" + __nodeId；非树行 = computeDedupKey(...)
  → ④ RowKeyConflictDetector.detect
```

**同源纪律（AP-22）**：①②必须复用与渲染/物化**同一份**过滤实现，不得在此另写一套前缀匹配或 fp 比较。
若现有实现是 private，请提取为可复用的 static 工具（**不要复制粘贴**）。

**行号口径**：`rowIndices` 目前是"参与判重的序号 + 1"。过滤后序号会与页面行号错开 —— 本次**保持
过滤后序号**（页面上被删的行本就不显示，过滤后序号反而与页面一致），并在 `RowKeyConflictDrawer`
的提示语上无需改动（其文案已写明"参考行号为后端校验序，仅作参考"）。

### 验收（**必须用 QT-20260726-0006 实跑，附 curl 输出**）
- [ ] 两条 AgNi 都在（不删）→ `POST /quotations/{id}/submit` **不再** 422
- [ ] 只删一条 → 不 422
- [ ] 两条都删 → 不 422（**这条是用户报的 P0 场景**）
- [ ] 构造"同一节点下两条相同行键"的场景 → **仍 422**（真撞键不得放过）
- [ ] 非树页签撞键 → **仍 422**（逐字节不变）
- [ ] 提交成功后单据状态正确流转，且可 `withdraw` 复位（**测完请把该单复位回 DRAFT**）

---

## B6 —— 单测与自检

### 单测清单（新增/补充）
- `DeletedRowKeysTest`：B1 的 4 条
- `RowKeyUniquenessServiceTest`：B5 的 5 条（含非树零回归）
- `QuotationTreeServiceTest`：B3/B4 的 3 条
- B0 的口径对齐测试（含旧键回退）

### 强制自检（缺一不可，写进交付说明）
```bash
# 1. 后端编译 + 单测（必须在 worktree 的 cpq-backend 下跑！）
cd .claude/worktrees/repair-0727-tree-delete/cpq-backend && ./mvnw -q test

# 2. 触发 dev server 重启（主工作区共享的 8081；本改动无 Flyway 迁移）
#    ⚠️ 本改动不产生 Flyway 迁移，若你自认为需要迁移 → 先停下来问技术总监

# 3. 端点存活（注意 --noproxy，且 401 = 正常）
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components   # 期望 401
```

### 交付说明必须包含
1. B0 核查结论（证实/证伪 + 依据）
2. `mvnw test` 结果（通过数 / 失败数原文）
3. B3、B5 在 QT-20260726-0006 上的 **curl 实跑输出**（删除响应字段 + 提交结果）
4. DB 校验 SQL 与输出（`snapshot_rows / row_data / deleted_row_keys` 三者行数）
5. **测完把 QT-20260726-0006 复位**（墓碑恢复为原始 1 条、状态 DRAFT），并说明复位方式

---

## 红线（违反即打回）

1. **不得**改动核价侧任何取数/渲染行为（`deleted == null` 路径逐字节不变）
2. **不得**改动非树页签的删除匹配与提交校验语义
3. **不得**新增 Flyway 迁移（本需求无 DDL）
4. **不得**在过滤后的行子集上重算 effKey（AP-54 头号不变量）
5. **不得**用 `git add -A`；只 add 本任务明确改动的文件
6. 自报"完成"必须附**实跑输出**，不接受"应该可以"
