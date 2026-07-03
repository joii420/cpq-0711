> ⚠️ **已被取代(SUPERSEDED)**：本设计（旧 spineKeys/逐根闭包/组件级开关机制）已于 2026-07-03 被 `docs/superpowers/specs/2026-07-02-核价单全量递归按料号分组渲染-design.md` 彻底替换，仅作历史追溯，勿再据此实现。

# 核价 BOM 递归展开 — 组件级开关 设计方案

- 日期：2026-06-06
- 状态：已评审通过（待写实施计划）
- 关联：核价 BOM 递归展开 P1（`docs/superpowers/plans/2026-06-04-核价BOM递归展开-P1.md` + spec `docs/superpowers/specs/2026-06-04-核价BOM递归展开-design.md`）、P2B 核价 Excel 树状（`docs/superpowers/plans/2026-06-05-核价Excel树状-P2B.md`）。本期把 P1「核价侧一律递归」改为 **per-component 可配**。

## 1. 背景与需求

P1 落地了核价侧 BOM 递归展开：`CardSnapshotService` 按 `material_bom_item` 算根料号的 BOM 闭包（`BomClosureService.compute`），对核价模板**所有** driver 组件用 `ComponentDriverService.expandForPartSet` 多料号展开 + spine 建树，并给每个核价组件注入 3 个系统固定列（料号/父料号/版本）。**P1 当时明确"核价一律生效、无开关、无 schema 字段"**（P1 计划 §范围说明 line 17，用户当时确认方案 1）。

实际业务中，**核价模板里并非所有组件都需要按 BOM 树递归展开子料号**——有些组件只需按根产品料号取数。本期把递归能力下沉为**组件管理中的组件级开关**：勾选才走 BOM 闭包递归（树 + 系统列），不勾选按正常单料号取数。

### 与现有 `tree_config` 的区别（两套独立的"树"，勿混淆）

- **`tree_config`**（Component 已有 jsonb 列）：组件**数据自身**含 `idField`/`parentField` 时的树形展示（数据本身带父子字段建树），与跨料号无关。
- **本期 BOM 递归展开**：按 `material_bom_item` 跨料号 BOM 闭包递归查出所有子料号数据。
- 两者**语义正交**，本期新增独立字段，不复用 `tree_config`。

## 2. 范围（已与用户确认）

- **配置粒度**：组件级全局——开关存 `Component` 实体；同一组件被多个核价模板复用时，开关对所有模板统一生效（不支持模板内 per-用法 差异化）。
- **默认值**：默认**关**（`false`，勾选才递归）（2026-06-06 需求变更，原为默认开；见 §10 演进）。存量组件由 V296 一并置 `false`——现有核价单下次重算快照会从 BOM 树变回普通单料号渲染，需手动勾选要树展开的组件。
- **未勾选组件渲染口径**：**完全普通渲染**——按整单根产品料号（`li.productPartNoSnapshot`）单料号取该组件数据，不展开子料号、不加料号/父料号/版本系统列、不建树（核价卡片普通表，等同报价侧取数）。核价 Excel 里该组件值只落根料号行。
- **仅核价侧**：报价侧本就不递归（`closure=null` 路径），不受本开关影响。
- **不做**：模板内 per-用法 配置；`tree_config` 与 BOM 递归的合并；版本切换/累乘等 P2 后置项（沿用 P1/P2 现状）。

## 3. 现状基线（事实，落实施前已核对）

- `Component`（`com.cpq.component.entity.Component`）已有 jsonb `tree_config`、`data_driver_path`、`fields`、`formulas`、`component_type`、`row_key_fields` 等列。
- 后端递归链路：`CardSnapshotService` line 536 `bomClosureService.compute(li.productPartNoSnapshot, Map.of())` → line 540/945 `expandTemplateDriverBaseRows(templateId, li, customerId, quotationId, closure)` → line 968 对**每个** driver 组件 `componentDriverService.expandForPartSet(...)` → line 986 `buildSpineBaseRows`（每 spine 节点一行，line 1003 `spineRowNode` 注入 `__nodeId/__parentId/__lvl/__hfPartNo/__parentNo/__bomVersion`）。
  - line 949 重载：`closure == null` → 走普通 `expandTemplateDriverBaseRows`（报价侧，line 1106）。
- 前端渲染（`QuotationStep2.tsx`）当前是 **`cardSide === 'COSTING'` 无条件**渲染 3 系统固定列：表头 line 1618、单元格 line 1819、Excel 补 td line 1971；建树 `isBomTree` line 1779（`cardSide === 'COSTING' && <含系统列>`）；`__sys` 透传 line 929（`br.__nodeId !== undefined` 时）。
- 组件管理前端（`ComponentManagement.tsx`）已有 `tree_config` 勾选 UI（line 758 `checked={!!treeConfig}` + idField/parentField 选择）。

## 4. 设计

### 4.1 数据模型
- Flyway 迁移：V295 `ADD COLUMN bom_recursive_expand BOOLEAN NOT NULL DEFAULT true`；**V296（需求变更）** `UPDATE component SET bom_recursive_expand=false` + `ALTER COLUMN ... SET DEFAULT false`（默认改关、存量一并置 false）。
- `Component` 实体加 `public Boolean bomRecursiveExpand = true;`（`@Column(name="bom_recursive_expand")`）。
- DTO 透传：`ComponentDTO`（出参）、`CreateComponentRequest` / `UpdateComponentRequest`（入参），`ComponentService` 创建/更新时落值（null 入参兜底 `true`）。

### 4.2 后端求值（`CardSnapshotService.expandTemplateDriverBaseRows(..., closure)`，line 945）
- 仍整单算一次 `bomClosureService.compute`（`cyclePartNos` 等通用元数据）。
- **per-component 分流**——遍历核价 driver 组件时读 `component.bomRecursiveExpand`：
  - `true` → 现状路径：`expandForPartSet(closure.partSet)` + `buildSpineBaseRows`（带 `__nodeId` 等系统列）。
  - `false` → 单 `partNo` expand（即 `closure==null` 重载里那套普通 baseRows 逻辑，line 1106 同款），**不并入系统列**。
- **实时兜底路径**（`ComponentDriverService.batchExpand` / `useDriverExpansions` 走的核价 task）同步按 `bomRecursiveExpand` 分流；未勾选组件不注入闭包 partSet、不旁路合桶按普通 task 处理。
- **优化（可选）**：模板内所有 driver 组件 `bomRecursiveExpand=false` → 跳过 `bomClosureService.compute`，省一次闭包查询；否则照常 compute。

### 4.3 前端渲染（`QuotationStep2.tsx`）
- 核价系统列/建树渲染条件从 `cardSide === 'COSTING'` 改为 **`cardSide === 'COSTING' && <该组件含 __nodeId 系统列>`**（数据驱动）：
  - 表头 line 1618、单元格 line 1819、Excel 补 td line 1971、建树 `isBomTree` line 1779 统一加"该组件有系统列"判断（可复用 line 929 `__sys` / per-row `__nodeId` 推导出"该组件是否树展开"的卡片级标志）。
- 未勾选组件 → baseRows 无系统列 → 走普通表分支（无料号/父料号/版本列、无缩进），与报价侧普通卡片一致。
- 核价 Excel（spine 行主轴）：未勾选组件值按料号匹配只落根料号行（自然结果，无需特殊处理）。

### 4.4 组件管理 UI（`ComponentManagement.tsx`）
- 在现有 `tree_config` 勾选项附近加独立 Checkbox：「核价时按 BOM 树递归展开子料号」，绑 `bomRecursiveExpand`，默认勾选（true）。
- 加载组件时回填 `bomRecursiveExpand`（缺省 → true）；保存时随 create/update 请求提交。
- 文案提示该项仅在核价模板渲染时生效、与上方"数据树（tree_config）"是不同概念。

## 5. 测试

- **后端** `@QuarkusTest`（`CardSnapshotService` 维度）：
  - 勾选组件 → baseRows 行数 = 闭包子料数、每行带 `__nodeId/__parentId/__lvl`、根 `__parentId=""`（回归 P1）。
  - 未勾选组件 → baseRows = 单料号普通行、**无任何 `__*` 系统列**。
  - **混合模板**（一个勾选 + 一个未勾选）→ 各自正确、互不影响（核心新增回归）。
  - 默认值：未显式设置的组件 `bomRecursiveExpand` 读出为 `true`。
- **前端 E2E**（`e2e/costing-bom-tree.spec.ts` 扩展或新 spec）：
  - 核价单含勾选 + 未勾选两组件 → 勾选 Tab 出树（系统列 + 缩进）、未勾选 Tab 普通表（无系统列）。
  - 报价侧同组件无变化（隔离，防 AP-41）。
  - 刷新 3 次行数稳定（AP-51 不累加）；`'加载中' final count = 0`。

## 6. 文件清单

**后端**
- `db/migration/V###__component_bom_recursive_expand.sql`（新）— 加列 default true。
- `component/entity/Component.java`（改）— 加字段。
- `component/dto/ComponentDTO.java` / `CreateComponentRequest.java` / `UpdateComponentRequest.java`（改）— 透传。
- `component/service/ComponentService.java`（改）— create/update 落值 + null 兜底 true。
- `quotation/service/CardSnapshotService.java`（改）— `expandTemplateDriverBaseRows(...,closure)` per-component 分流。
- `component/service/ComponentDriverService.java`（改，若兜底路径需要）— `batchExpand` 核价 task per-component 分流。
- 测试：`quotation/.../CardSnapshotRecursiveToggleTest.java`（新）。

**前端**
- `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（改）— 系统列/建树渲染改 per-component 数据驱动。
- `cpq-frontend/src/pages/component/ComponentManagement.tsx`（改）— 加 Checkbox + 回填 + 提交。
- `cpq-frontend/src/pages/component/types.ts`（改，若需）— 类型加 `bomRecursiveExpand`。
- 测试：`e2e/costing-bom-tree.spec.ts`（扩展或新 spec）。

## 7. 部署纪律
- Flyway 迁移：放 `db/migration/`，让 Quarkus dev `migrate-at-start` 自动跑（勿手工 `psql -f`）；`touch` java 触发重启；`flyway_schema_history` success=t。
- 后端改完 `/q/health` 200。
- 前端 `tsc --noEmit` 0 + 改动 `.tsx` curl Vite 200。
- `QuotationStep2.tsx` 属 AP-31/AP-44 协议必跑 E2E 文件，**禁止跳过 E2E**。
- AP-44 字段类型联动：本期是**组件级配置字段**（非 `field_type`/`component.fields` 内字段），不触发 17 检查点矩阵；但 DTO 透传需前后端对齐（create/update/get 三处）。

## 8. 风险 / 注意点
- **存量行为变更面**：默认改关（V296 存量全 false）后，现有核价单**下次重算快照**会从 BOM 树变回普通单料号渲染（快照驱动，非即时）；需手动勾选要树展开的组件——需在 UI 文案/RECORD 提示。
- **混合模板渲染**：前端务必按 per-component（数据驱动）分流，杜绝"核价侧一律 3 系统列"残留导致未勾选组件出现空系统列（本期核心改动点）。
- **隔离纪律**：仅动核价侧分支，报价侧零改动（防 AP-41 双视图不对齐）。
- **行数权威纪律**：勾选组件仍遵循 AP-51（driver/spine 行数权威，禁 `Math.max` 累加）。
- **`tree_config` 共存**：一个组件理论上可同时配 `tree_config`（数据自带树）与 `bom_recursive_expand`（BOM 闭包递归）；本期两者正交独立判断，不做合并语义（罕见组合，留观察）。

## 9. 后置（不在本期）
- 模板内 per-用法 递归配置（若将来同组件需差异化）。
- `tree_config` × BOM 递归的叠加渲染语义。
- P2 版本切换 / 累乘 / 性能并发（沿用 P1/P2 既有后置清单）。

## 10. 演进史
- **2026-06-06 初版**：默认值定为**开**（`true`，保 P1 现状），存量不惊扰（V295 `DEFAULT true`）。
- **2026-06-06 需求变更**：用户改为默认**关**（`false`，勾选才递归，更贴合"不勾选按正常渲染"的原始诉求），且存量一并置 false（彻底默认关）。新增 V296（`UPDATE ... false` + `ALTER ... SET DEFAULT false`），后端实体/DTO/Service 兜底改 `false`，前端 state 初始/回填语义改 `=== true`。E2E `costing-bom-toggle.spec.ts` 改为显式勾选材质(true)、工序保持默认 false。影响：现有核价单下次快照重算变回普通渲染，需手动勾选。
