# 核价 Excel 树状视图（P2-B）— 设计方案

- 日期：2026-06-05
- 状态：已与用户确认全部口径，待写实施计划（plan）
- 适用范围：**仅核价侧** Excel 视图；报价 Excel 零改动
- 前置：P1「核价 BOM 递归展开」已落地（卡片视图整棵树 + spine `__*` 系统列 + `BomClosureService`）
- 关联文档：
  - `docs/superpowers/specs/2026-06-04-核价BOM递归展开-design.md`（P1 设计，§5.2 Excel 树状即本期）
  - `docs/superpowers/plans/2026-06-04-核价BOM递归展开-P1.md`（P1 实施，本期为其「后置 P2」一部分）
  - `docs/Excel模板配置指南.md`（Excel 列配置 VARIABLE/FORMULA/CARD_FORMULA/EXCEL_FORMULA）
  - `docs/反模式.md` AP-41（报价/核价视图不对齐串号）、AP-22（多行重复）、AP-51（行数权威）

---

## 0. 定位（一句话）

> 核价 Excel 视图从「**1 行 / 产品**」改为「**N 行 / 产品**」—— 每个 BOM `spine` 节点（料号）一行，自动注入 `料号 / 父料号 / 版本` 三列、按 `lvl` 缩进成树；每一列的值按**本节点自身**的卡片有效行重新聚合。仅核价侧，报价 Excel 不变。

不做：节点级版本切换（P2-A，无多版本数据，另期）、累乘用量列（后续）、Excel 折叠交互（评审/导出表，扁平缩进即可）、**POI xlsx 导出树化**（`exportExcelView` 当前走 `getExcelView` 默认=报价模板，与核价渲染是两条路；核价 POI 树导出列 follow-up）。本期只做用户实际查看的**渲染（快照 `costingExcelValues`）路径**。

---

## 1. 需求与已确认口径

| # | 决策点 | 结论 |
|---|---|---|
| 1 | Excel 行粒度 | **一个 BOM 节点（料号）一行**（成本汇总树），非「明细行一行」、非「沿用 1 行/产品」 |
| 2 | 树结构如何显示 | **自动注入 `料号/父料号/版本` 三列**（对齐卡片视图）+ `料号` 列按 `lvl` 缩进 |
| 3 | 列值聚合范围 | 每列（含 CARD_FORMULA）按**本节点**的有效行聚合（非整卡聚合） |
| 4 | spine 来源 | **方案 A**：Excel 构建时复算 `BomClosureService.compute(rootPartNo)` 拿权威 spine；按 `__nodeId` 过滤卡片有效行到本节点 |
| 5 | 折叠交互 | **不做**（仅按 `lvl` 缩进；Excel 是评审/导出表，折叠不随 xlsx 导出保留） |
| 6 | 空节点某列 | 该列在本节点 0 行 → 聚合为**空白（null）**，对齐卡片「—」 |
| 7 | 报价侧 | **完全不改**（报价 Excel 仍 1 行/产品、无注入列） |
| 8 | DAG 重复子件 | 按 `__nodeId`（occurrence）过滤 → 重复子件多 occurrence 各出一行（数据同、行身份不同），与卡片一致 |
| 9 | 环 | 闭包已标 `isCycle` + 根 `cyclePartNos`；成环节点照常出行（已截断），Excel 不额外处理 |

---

## 2. 架构决策：方案 A（spine 权威同源）

否决方案 B（完全从 `costingCardValues` 的 `__*` 自建 spine、不复算闭包）：B 等于把「建树排序（DFS / `cyc_path` 序 / occurrence 去重 / 环标记）」**第三次重写**（卡片前端 `layoutTreeRows` 一处、闭包 SQL `CYCLE+cyc_path` 一处），三处稍有出入即出「Excel 树序与卡片不一致」的 AP-41 类联调坑；且与 P1「补空行」行为耦合。

**采用 A**：spine 的建树/排序/环/DAG 逻辑只有一个权威源 `BomClosureService`。卡片视图间接消费它（快照 `__*` 即其产物），Excel 也直接复算它 → 卡片与 Excel 树**同源同序**。复算成本可忽略（`BomClosureService` 进程级 Caffeine 30s 缓存，卡片快照生成时刚算过，几乎必命中）。

---

## 3. 数据流

```
buildExcelValues(li, costingTemplateId, costingCardValues)        // 仅核价侧路径
  1. closure = bomClosureService.compute(li.productPartNoSnapshot, Map.of())
        → spine（cyc_path 序、lvl、nodeId/parentId、hfPartNo/parentNo/bomVersion、isCycle）
  2. eff = CardEffectiveRows.build(costingCardValues, ...)         // 每行 Map 带 __nodeId（本期补）
  3. rows = []
     for 节点 N in closure.spine:                                  // 按 spine 顺序 = 树序
        effN = CardEffectiveRows.filterByNodeId(eff, N.nodeId)     // 仅本节点 occurrence 的行
        row = LinkedHashMap()
        row.put("__hfPartNo", N.hfPartNo); row.put("__parentNo", N.parentNo)
        row.put("__bomVersion", N.bomVersion); row.put("__nodeId", N.nodeId)
        row.put("__parentId", N.parentId);   row.put("__lvl", N.lvl)
        for col in excel_view_config:                             // 复用现有逐列求值
           row.put(col.col_key, evalColumn(col, effN, ...))       // CARD_FORMULA 用 effN; 空→null
        // 列类型按节点 scope 边界：
        //   CARD_FORMULA → 用 effN 按本节点有效行聚合（本期核心）
        //   FORMULA/EXCEL_FORMULA → 引用同行已算列（cachedCells），天然随本节点 CARD_FORMULA 变
        //   PRODUCT_ATTRIBUTE → 产品级，各节点同值（合理，产品属性与节点无关）
        //   COMPONENT_FIELD/VARIABLE → 核价 Excel 配置未使用；如出现本期取产品级值(不按节点 scope)，记 follow-up
        rows.add(row)
  4. return {"rows": rows, "treeMode": true}
```

报价侧 `buildExcelValues(li, customerTemplateId, quoteCardValues)`：**不进树分支**（无 closure / 无注入列 / 仍 1 行），由「是否核价模板上下文」判据区分（见 §5）。

---

## 4. 落点（文件触点）

### 后端
| 改动 | 文件 / 位置 | 说明 |
|---|---|---|
| `resolvedRows` 补 `__nodeId` | `CardSnapshotService`（组装 `resolvedRows` 处） | Excel 优先读 `resolvedRows`；不带 `__nodeId` → `filterByNodeId` 过滤不到 → 全空。**协议传播点** |
| 有效行带 `__nodeId` + `filterByNodeId` | `CardEffectiveRows`（`build` + 新方法） | 每行 Map 写入 `__nodeId`（resolvedRows 路径读 `rr.__nodeId`，回退路径读 `br.__nodeId`）；`filterByNodeId(Map<tabKey,TabRows>, nodeId)` 返回过滤副本 |
| 核价树分支：按 spine 出 N 行 + 注入 3 列 | `ExcelViewService.buildLineRowData` / `buildRowData` | 仅核价路径；复用 `cardFormulaEvaluator`，喂 `effN`；空节点列 → null。本期改**快照路径**（`buildExcelValues`→`buildLineRowData`），不动 `getExcelView` 实时 API |
| ~~POI 导出~~ | `ExcelViewService.exportExcelView` | **本期不改**（走 `getExcelView` 默认=报价模板；核价 POI 树导出 follow-up） |

### 前端
| 改动 | 文件 | 说明 |
|---|---|---|
| 多行解析 | `useExcelSnapshotRows` | 核价树（snapshot `treeMode`/多 rows）时读**全部 rows**，每产品出 N 行，带 `__*` 树元数据 |
| 列前置 + 缩进 | `LinkedExcelView` | 核价侧 `parsedColumns` 前置 `料号/父料号/版本`；`料号` 单元格按 `__lvl` 缩进渲染；报价侧分支不动 |

---

## 5. 隔离（AP-41）

- 触发判据：**核价模板上下文**（`buildExcelValues` 的 `templateId == quotation.costingCardTemplateId`，或入参快照含 spine `__nodeId`）。
- 报价 Excel：`quoteExcelValues` 仍 1 行/产品、无注入列、无 `treeMode`。
- 前端：树渲染（多行 + 3 列 + 缩进）仅 `side==='COSTING'` 生效；报价侧 `LinkedExcelView` 分支零改动。
- 引擎以「是否核价/含 spine」为判据，不写死 costing 业务分支。

---

## 6. 边界与风险

1. **DAG 重复子件**：按 `__nodeId`（occurrence，非料号）过滤 → 各 occurrence 各出一行（数据同、行身份不同），与卡片一致。靠 `__nodeId` 区分。
2. **空节点列**：本节点该列 0 行 → 聚合为 null（空白），对齐卡片「—」；数值聚合列空节点不写 0（避免误导成本=0）。
3. **环**：闭包 `CYCLE` 已截断 + 标 `isCycle`；成环节点照常出行；根 `cyclePartNos` 已有卡片告警，Excel 不重复告警。
4. **AP-51 行数权威**：Excel 行数 = `closure.spine.size()`（确定性），不与历史快照取 `Math.max`。
5. **`resolvedRows __nodeId` 传播遗漏**：最大风险点——若 `CardSnapshotService` 的 `resolvedRows` 没补 `__nodeId`，而 `CardEffectiveRows` 优先走 `resolvedRows` 路径，则 `filterByNodeId` 过滤不到任何行 → Excel 全空。测试必须覆盖「有数据节点列非空」。
6. **存量核价单**：需经 `refresh-snapshot`（P1 已接 `refreshCostingCardValues`，会重算 `costingCardValues` + `costingExcelValues`）刷出树 Excel。

## 7. 自检 / 验收（DoD）

1. 打开/刷新核价单 → 核价 Excel 视图按整棵 BOM 树逐节点出行（= spine 节点数），含 `料号/父料号/版本` 前置列 + `lvl` 缩进。
2. 各列按本节点聚合：有元素数据的节点（如某料号）CARD_FORMULA 列非空；无数据节点该列空白。
3. DAG 重复子件出多行（occurrence 区分）；行数刷新 3 次稳定（AP-51）。
4. 报价 Excel 行为与改动前一致（1 行/产品、无注入列）。
5. 全量自检：后端 `touch` 重启 + `/q` 存活；前端 `tsc` 0 错 + Vite 200；E2E 核价 Excel 树断言 + 报价隔离回归。

## 8. 后置（不在本期）

- P2-A：节点级版本切换（`versionOverrides` + `availableVersions` 真下拉 + `SqlViewExecutor (料号,版本)` 配对 + 业务视图带 `bom_version`）—— 需先有多版本数据 + 定 §10-3 版本×characteristic 键。
- 累乘用量列。
- Excel 折叠交互（如确有需要）。
