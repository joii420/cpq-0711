# 报价 Excel 视图与产品卡片统一前端单引擎计算 实现计划（报价侧）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让报价单 Excel 视图改用与产品卡片**同一套前端 token 引擎**计算列值，使 Excel 值**按构造恒等于卡片**（消除前后端双引擎分叉，0.93≡0.93），saveDraft 落前端算好的两份快照，导出/提交读快照不再后端重算。

**Architecture:** 新增前端纯函数 `buildExcelSnapshot`，复用卡片现有引擎 `getComponentSubtotals` / `buildCrossTabRows` / `evalProductSubtotalFromSubtotals` / `evaluateExpression`，逐列按 `source_type` 求值产出 `{rows:[{col_key:value}]}`（与现有 `quote_excel_values` 快照同形态）。`LinkedExcelView` 始终走「前端快照」路径，退役 `isV2` 的后端 GET。`buildDraftPayload` 携带前端算好的 `quoteExcelValues`，后端 `saveDraft` 原样落库、**停止**用后端引擎 `buildExcelValues` 重算覆盖。导出（XLSX/PDF）改读 `quote_excel_values` 快照渲染。这是**核心渲染基线反转**（后端权威→前端权威），落地后同步更新 `docs/三大核心模块基线.md`。

**Tech Stack:** 前端 React + TypeScript + Vitest；后端 Quarkus + Hibernate Panache + Apache POI + Qute；DB PostgreSQL（JSONB 快照列）。

---

## 范围与节奏（已与用户确认）

- 本计划**只覆盖报价侧（QUOTE）**。核价侧（COSTING）在报价侧恒等性经 vitest + LIVE 验证通过后，另写一份后续计划（核价 Excel VARIABLE 列引用 `v_costing_summary_full.*` 服务端 SQL 视图值 + BOM spine 树渲染，耦合面不同，须单独立用例）。
- `source_type` 覆盖：前端求值器覆盖后端 switch **全部 8 类**（`PRODUCT_ATTRIBUTE / COMPONENT_FIELD / VARIABLE / FORMULA / EXCEL_FORMULA / FIXED_VALUE / CARD_FORMULA / TAB_JOIN_FORMULA`），报价测试模板实际在用 `TAB_JOIN_FORMULA`（A/B/C 三列）逐列 vitest，其余类做轻量分支 + 至少 1 用例兜底。
- **纪律（贯穿全程）**：不改产品卡片、不质疑卡片 0.93；目标是 Excel 用卡片同引擎算到恒等卡片。收口后**不得残留两条会产出不同值的活路径**。隔离 worktree 基于最新 master，提交只 `git add` 本次明确文件，**严禁 `git add -A`**。

## 关键事实（实现前必读，已查证）

- **`quote_excel_values` 列已存在**（`quotation_line_item` JSONB），形态 `{"rows":[{"A":..,"B":..,"C":..}]}`，93 行有数据，`useExcelSnapshotRows` 已读它。本计划改的是「谁来算这份快照」：后端 `buildExcelValues` → 前端 `buildExcelSnapshot`。
- 报价测试模板（`8be8cc2c-2c1f-45f7-b401-d0e3b15abca2` = 罗克韦尔模板0617）的 EXCEL 组件 `a8d4198c-5f3b-4a21-85a3-876a3fe2be7d` 三列（DB 实测）：
  - `A` 材料成本：`source_type=TAB_JOIN_FORMULA`，`expression="[来料.材料成本]"`，`tabs=[{alias:来料}]`
  - `B` 损耗成本：`TAB_JOIN_FORMULA`，`expression="[来料.材料损耗成本]"`
  - `C` 产品小计：`TAB_JOIN_FORMULA`，`expression="[报价小计(总计)]"`，`tabs=[{alias:报价小计, tabKey=8ccd4a88-...（报价小计 SUBTOTAL 组件 id）}]`
- TAB_JOIN_FORMULA 列的可求值表达式在 **`col.expression`** 字段（字符串），`col.formula` 为 null。需 `expressionToTokens(col.expression, ...)` 转 `ExpressionToken[]` 再 `evaluateExpression`。
- `C = [报价小计(总计)]` 引用 报价小计 SUBTOTAL 组件整页签总计 = `evalProductSubtotalFromSubtotals(item, componentSubtotals)` = 卡片 footer = **0.93**。恒等性按构造成立。
- 后端 `quote_excel_values` 现写入点（本计划要停掉其重算职责）：`CardSnapshotService.snapshotLineValues`(L413-418)、`refreshQuoteCardValues`(L1366-1374)、`editCardValue`(L1479-1504)，三处都调 `buildExcelValues(...)`（后端引擎）。
- 显示 GET 与 XLSX 导出都经 `ExcelViewService.getExcelView`(L116) → `ComponentDataEffectiveRows.compute`（读 row_data 求和，**这就是产 0.8527 的后端引擎**）。
- 验证环境：DB `PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db`；admin/Admin@2026 cookie；前端 5174 / 后端 8081（服务主树，worktree 改动合并 master 后才反映）；后端单测 `cd cpq-backend && ./mvnw test -Dtest='...' -Dquarkus.http.test-port=0`（须在 worktree 自己的 cpq-backend 跑）。
- 测试数据：报价单 `QT-20260621-1787`（quotationId `145c4b2d-f7ed-4c97-9f1a-bd91cef91831`，lineItemId `2354f7f8-d3a2-477a-8f2f-90021755a6b6`，customerTemplateId `8be8cc2c-...`）；元素组件 `1b2d1bdb-...`（rowKeyFields `["元素","料件"]` sep `||`），来料 `e31bbdd1-...`，报价小计 SUBTOTAL `8ccd4a88-...`。复现：元素 Cu 单价=1122(g/PCS)→卡片 footer 0.93 vs Excel C（旧后端）0.8527，A 材料成本 0.7782。

## 现有引擎接口（buildExcelSnapshot 复用，确切签名）

```typescript
// cpq-frontend/src/pages/quotation/QuotationStep2.tsx
export function getComponentSubtotals(
  item: LineItem,
  driverExpansions?: DriverExpansionMap,
  customerId?: string,
): Record<string, number>;
// 返回三层键：componentSubtotals[componentId|componentCode|tabName] = 整组件小计；
//            componentSubtotals[`${id|code|tabName}#${colName}`] = 列小计。

export function buildCrossTabRows(
  componentData: ComponentDataItem[],
  allComponentSubtotals: Record<string, number>,  // 原地回填 cross_tab 列小计 'self#colName'
  partNo: string | undefined,
  lookupExpansion: (comp: ComponentDataItem) => (DriverExpansion | undefined),
  globalVariableDefs?: Record<string, GlobalVariableDefinition>,
): { store: Record<string, Array<Record<string, any>>>; columnSumsByComp: Record<string, Record<string, number>> };

export function evalProductSubtotalFromSubtotals(
  item: LineItem,
  componentSubtotals: Record<string, number>,
): number;  // 找 SUBTOTAL 组件用其公式 evaluateExpression 求值 = 卡片 footer

// cpq-frontend/src/utils/formulaEngine.ts
export function evaluateExpression(
  tokens: ExpressionToken[],
  fieldValues: Record<string, number>,
  componentSubtotals?: Record<string, number>,   // 第3参
  productAttributes?: Record<string, number>,     // 第4参
  quotationFields?: Record<string, number>,       // 第5参
  pathCache?: Record<string, number>,
  partNo?: string,
  basicDataValues?: Record<string, any>,
  previousRowSubtotal?: number,
  globalVariableDefs?: Record<string, GlobalVariableDefinition>,
  currentRow?: Record<string, any>,
  crossTabRows?: Record<string, Array<Record<string, any>>>,
  outDiag?: { crossTabError?: string },
): number;

// cpq-frontend/src/pages/component/formulaSerialize.ts:320
export function expressionToTokens(expr: string, /* ...alias/field 解析参数 */): ExpressionToken[];

// cpq-frontend/src/pages/quotation/useLinkedExcelRows.ts —— 可复用列解析分支
//  - resolveVariable(vp, li, ctx)（L62-97，legacy {CODE} 变量）
//  - evaluateFormula(formula, rowCellValues, varValues)（L100-131，FORMULA 列 [col_key] 引用）
//  - isLegacyVarCode(vp) / pathCacheKey(partNo, vp) / formatPathValue(v)
```

## 文件结构（创建/修改清单）

**前端：**
- **新建** `cpq-frontend/src/pages/quotation/buildExcelSnapshot.ts` — 前端 Excel 列求值器（纯函数，逐 source_type 求值，复用卡片引擎）。单一职责：LineItem + 列定义 → `{rows:[{col_key:value}]}`。
- **新建** `cpq-frontend/src/pages/quotation/buildExcelSnapshot.test.ts` — vitest（逐 source_type + 恒等性 0.93 + 边界）。
- **修改** `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx` — 始终走前端快照（in-memory `buildExcelSnapshot` 产出）；退役 `isV2` → `useBackendExcelRows` 分支。⚠️主工作区有并发未提交 WIP，须在 worktree 基于最新 master、与并发会话协调避冲突。
- **修改** `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` — `buildDraftPayload` 每 lineItem 增 `quoteExcelValues`（调 `buildExcelSnapshot`）。
- **修改** `cpq-frontend/src/services/quotationService.ts` — saveDraft payload 类型（如有显式类型）补 `quoteExcelValues`。
- **退役** `cpq-frontend/src/pages/quotation/useBackendExcelRows.ts` — 报价显示不再用（Phase 6 决定删/留）。

**后端：**
- **修改** `cpq-backend/src/main/java/com/cpq/quotation/dto/SaveDraftRequest.java` — `LineItemDraft` 增字段 `public String quoteExcelValues;`
- **修改** `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java` — `saveDraft` 主循环把 `liDraft.quoteExcelValues` 原样落 `li.quoteExcelValues`（在 `li.persist()` 前）。
- **修改** `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java` — 停止报价侧 `buildExcelValues` 重算覆盖 `quoteExcelValues`（`snapshotLineValues` / `refreshQuoteCardValues` / `editCardValue` 三处，仅当前端已送快照时不覆盖；详见 Phase 3/6）。
- **修改** `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java` — `exportExcelView`(XLSX) 改读 `quote_excel_values` 快照渲染（保留列定义/格式/EXCEL_FORMULA 直写公式）。
- **修改** `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationExportService.java` — PDF(Qute) 行值改读 `quote_excel_values` 快照。
- **修改** `docs/三大核心模块基线.md` — 报价单渲染基线由「后端权威」反转为「前端权威」（Phase 6）。

---

## Phase 1 — 前端 Excel 求值器 `buildExcelSnapshot`（报价侧核心）

### Task 1: 新建 `buildExcelSnapshot` + 恒等性 vitest（0.93 场景）

**Files:**
- Create: `cpq-frontend/src/pages/quotation/buildExcelSnapshot.ts`
- Test: `cpq-frontend/src/pages/quotation/buildExcelSnapshot.test.ts`

- [ ] **Step 1: 先研究 token 化语义（写测试前的必要侦察，≤10 分钟）**

阅读以下确认 `expressionToTokens` 能否解析列表达式 `[来料.材料成本]` / `[报价小计(总计)]`：
- `cpq-frontend/src/pages/component/formulaSerialize.ts:320` `expressionToTokens` 的入参与它能识别的引用形态（`[别名.字段]` → cross_tab_ref / component_subtotal；`[别名(总计)]` → 整页签 component_subtotal）。
- `cpq-frontend/src/utils/formulaEngine.ts:234-494` `evaluateExpression` 各 token 分支（`component_subtotal` L257-274 查 `${code}#${colName}`；`cross_tab_ref` L334-493）。

确认结论（写进文件顶部注释）：列 `expression` 用 `expressionToTokens` 转 token 后，`cross_tab_ref`/`component_subtotal` token 由 `evaluateExpression` 配合 `componentSubtotals`+`crossTabRows` 求值，与卡片 footer 同口径。若 `expressionToTokens` 需要 alias→componentId 映射，从 `col.tabs[].{alias,tabKey}` 提供。

- [ ] **Step 2: 写失败测试（恒等性 + 逐 source_type）**

```typescript
// buildExcelSnapshot.test.ts
import { describe, it, expect } from 'vitest';
import { buildExcelSnapshot } from './buildExcelSnapshot';
import { evalProductSubtotalFromSubtotals, getComponentSubtotals } from './QuotationStep2';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';

// 用最小但真实的 LineItem 夹具复现 0.93 场景（元素 Cu 单价=1122 g/PCS → 来料.材料成本=0.7782, 报价小计=0.93）。
// 夹具构造：从 QuotationStep2 既有测试夹具或手搓含 元素/来料/报价小计 三组件 + subtotalFormula。
import { makeLineItem093 } from './__fixtures__/lineItem093';  // Step 内一并新建夹具

const COLUMNS: CostingTemplateColumn[] = [
  { col_key: 'A', title: '材料成本', source_type: 'TAB_JOIN_FORMULA', /* expression+tabs */ } as any,
  { col_key: 'B', title: '损耗成本', source_type: 'TAB_JOIN_FORMULA' } as any,
  { col_key: 'C', title: '产品小计', source_type: 'TAB_JOIN_FORMULA' } as any,
];

describe('buildExcelSnapshot — 报价侧恒等卡片', () => {
  it('C 列(产品小计) 恒等于卡片 footer(evalProductSubtotalFromSubtotals)', () => {
    const item = makeLineItem093();
    const subtotals = getComponentSubtotals(item);
    const cardFooter = evalProductSubtotalFromSubtotals(item, subtotals); // 卡片口径
    const snap = buildExcelSnapshot(item, COLUMNS, undefined, undefined, {});
    expect(snap.rows).toHaveLength(1);
    expect(snap.rows[0].C).toBeCloseTo(cardFooter, 6);   // 恒等
    expect(snap.rows[0].C).toBeCloseTo(0.93, 2);          // 复现值
  });

  it('A 列(来料.材料成本) == 来料组件 材料成本 列小计', () => {
    const item = makeLineItem093();
    const subtotals = getComponentSubtotals(item);
    const snap = buildExcelSnapshot(item, COLUMNS, undefined, undefined, {});
    expect(snap.rows[0].A).toBeCloseTo(subtotals['来料#材料成本'] ?? subtotals['来料'], 6);
  });

  it('逐 source_type: FIXED_VALUE / PRODUCT_ATTRIBUTE / VARIABLE / FORMULA / COMPONENT_FIELD 各产出非空', () => {
    const item = makeLineItem093();
    const cols: CostingTemplateColumn[] = [
      { col_key: 'F', title: '固定', source_type: 'FIXED_VALUE', fixed_value: '7' } as any,
      { col_key: 'P', title: '属性', source_type: 'PRODUCT_ATTRIBUTE', field_key: '年用量' } as any,
      { col_key: 'V', title: '变量', source_type: 'VARIABLE', variable_path: '{UNIT_WEIGHT}' } as any,
      { col_key: 'CF', title: '组件字段', source_type: 'COMPONENT_FIELD', field_key: '单价' } as any,
      { col_key: 'FM', title: '公式', source_type: 'FORMULA', formula: '=[F]*2' } as any,
    ];
    const snap = buildExcelSnapshot(item, cols, undefined, undefined, {});
    expect(snap.rows[0].F).toBe(7);
    expect(snap.rows[0].FM).toBe(14);   // [F]*2
    // P/V/CF 视夹具断言（≥ 不抛错、键存在）
    expect(snap.rows[0]).toHaveProperty('P');
    expect(snap.rows[0]).toHaveProperty('V');
    expect(snap.rows[0]).toHaveProperty('CF');
  });

  it('hidden 列仍参与求值但可被消费方过滤（不报错）', () => {
    const item = makeLineItem093();
    const cols = [{ col_key: 'H', title: '隐藏', source_type: 'FIXED_VALUE', fixed_value: '1', hidden: true } as any];
    expect(() => buildExcelSnapshot(item, cols, undefined, undefined, {})).not.toThrow();
  });
});
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/buildExcelSnapshot.test.ts`
Expected: FAIL（`buildExcelSnapshot` 未定义 / 夹具未建）。

- [ ] **Step 4: 实现 `buildExcelSnapshot`（逐 source_type）**

```typescript
// cpq-frontend/src/pages/quotation/buildExcelSnapshot.ts
import type { LineItem, ComponentDataItem } from './QuotationStep2';
import { getComponentSubtotals, buildCrossTabRows, evalProductSubtotalFromSubtotals } from './QuotationStep2';
import type { DriverExpansionMap, DriverExpansion } from './useDriverExpansions';
import { evaluateExpression } from '../../utils/formulaEngine';
import { expressionToTokens } from '../component/formulaSerialize';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { GlobalVariableDefinition } from '../../utils/formulaEngine';
// 复用 useLinkedExcelRows 内的列解析（如未 export，Step 内一并 export 这几个纯函数）：
import { resolveVariable, evaluateFormula, isLegacyVarCode, pathCacheKey, formatPathValue } from './useLinkedExcelRows';

export interface BuildExcelSnapshotCtx {
  pathCache?: Record<string, number>;
  basicDataValues?: Record<string, any>;
  globalVariableDefs?: Record<string, GlobalVariableDefinition>;
  quotationFields?: Record<string, number>;
  lookupExpansion?: (comp: ComponentDataItem) => (DriverExpansion | undefined);
}

export interface ExcelSnapshot { rows: Array<Record<string, any>>; }

/**
 * 前端 Excel 列求值器：复用卡片同一 token 引擎，逐列按 source_type 求值。
 * 不变量：每个计算列值 == 卡片对应口径（同函数同输入）。报价侧单行（rows.length===1）。
 * C=[报价小计(总计)] == evalProductSubtotalFromSubtotals(item, subtotals) == 卡片 footer。
 */
export function buildExcelSnapshot(
  item: LineItem,
  columns: CostingTemplateColumn[],
  driverExpansions: DriverExpansionMap | undefined,
  customerId: string | undefined,
  ctx: BuildExcelSnapshotCtx,
): ExcelSnapshot {
  const partNo = item.productPartNo;
  const lookupExpansion = ctx.lookupExpansion ?? (() => undefined);

  // 1) 卡片引擎 PASS1+PASS2（与卡片 footer 同源）
  const componentSubtotals = getComponentSubtotals(item, driverExpansions, customerId);
  const { store: crossTabRows } = buildCrossTabRows(
    item.componentData, componentSubtotals, partNo, lookupExpansion, ctx.globalVariableDefs,
  ); // buildCrossTabRows 原地回填 componentSubtotals 的 cross_tab 列小计

  // 2) 显式登记 SUBTOTAL 整页签值（保证 [报价小计(总计)] == footer）
  const productSubtotal = evalProductSubtotalFromSubtotals(item, componentSubtotals);
  for (const comp of item.componentData) {
    if ((comp as any).componentType === 'SUBTOTAL') {
      if (comp.componentId) componentSubtotals[comp.componentId] = productSubtotal;
      if (comp.componentCode) componentSubtotals[comp.componentCode] = productSubtotal;
      if (comp.tabName) componentSubtotals[comp.tabName] = productSubtotal;
    }
  }

  const productAttrs: Record<string, number> = {};
  for (const [k, v] of Object.entries(item.productAttributeValues || {})) {
    const n = typeof v === 'number' ? v : parseFloat(v as any);
    if (!Number.isNaN(n)) productAttrs[k] = n;
  }

  // 3) 逐列求值（FORMULA 列引用其它 col_key，须最后一遍；先算非 FORMULA）
  const cell: Record<string, any> = {};
  const evalToken = (expr: string) => {
    const tokens = expressionToTokens(expr); // [别名.字段]/[别名(总计)] → cross_tab_ref/component_subtotal
    return evaluateExpression(
      tokens, {}, componentSubtotals, productAttrs, ctx.quotationFields,
      ctx.pathCache, partNo, ctx.basicDataValues, undefined,
      ctx.globalVariableDefs, undefined, crossTabRows,
    );
  };

  for (const col of columns) {
    switch (col.source_type) {
      case 'TAB_JOIN_FORMULA':
      case 'CARD_FORMULA':
        cell[col.col_key] = col.expression ? evalToken((col as any).expression) : null;
        break;
      case 'EXCEL_FORMULA':
        // 显示用计算值（与 FORMULA 同口径）；导出阶段(Phase 4)单独保留 POI =formula 直写
        cell[col.col_key] = col.formula ? evaluateFormula(col.formula, cell, {}) : null;
        break;
      case 'FIXED_VALUE':
        cell[col.col_key] = col.fixed_value != null ? Number(col.fixed_value) : null;
        if (Number.isNaN(cell[col.col_key])) cell[col.col_key] = col.fixed_value; // 文本固定值
        break;
      case 'PRODUCT_ATTRIBUTE':
        cell[col.col_key] = col.field_key != null
          ? (item.productAttributeValues?.[col.field_key] ?? null) : null;
        break;
      case 'COMPONENT_FIELD':
        cell[col.col_key] = resolveComponentField(item, col.field_key);
        break;
      case 'VARIABLE': {
        const vp = (col.variable_path || '').trim();
        if (!vp) { cell[col.col_key] = null; break; }
        if (isLegacyVarCode(vp)) cell[col.col_key] = resolveVariable(vp, item, ctx as any);
        else {
          const k = partNo ? pathCacheKey(partNo, vp) : undefined;
          cell[col.col_key] = (k && ctx.pathCache && k in ctx.pathCache)
            ? formatPathValue(ctx.pathCache[k]) : null;
        }
        break;
      }
      case 'FORMULA':
        // 引用其它 col_key [A]/[B]，须等其它列算完——见 Step 5 二次遍历
        cell[col.col_key] = undefined;
        break;
      default:
        cell[col.col_key] = null;
    }
  }

  // 4) FORMULA 列二次遍历（引用其它列值）
  for (const col of columns) {
    if (col.source_type === 'FORMULA' && col.formula) {
      cell[col.col_key] = evaluateFormula(col.formula, cell, {});
    }
  }

  return { rows: [{ ...cell, __hfPartNo: partNo, _lineItemId: (item as any).id ?? (item as any).tempId }] };
}

function resolveComponentField(item: LineItem, fieldKey?: string): any {
  if (!fieldKey) return null;
  for (const comp of item.componentData || []) {
    const rows = (comp as any).rows || [];
    if (rows.length && fieldKey in rows[0]) return rows[0][fieldKey];
  }
  return null;
}
```

> 实现注意：`resolveVariable`/`evaluateFormula`/`isLegacyVarCode`/`pathCacheKey`/`formatPathValue` 若在 `useLinkedExcelRows.ts` 内未 `export`，本 Step 顺手把它们 `export`（不改逻辑）。`expressionToTokens` 的实际入参以 formulaSerialize.ts:320 签名为准；若它需要 fields/alias 上下文，从 `col.tabs` 构造传入。

- [ ] **Step 5: 运行测试至全绿**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/buildExcelSnapshot.test.ts`
Expected: PASS（恒等性 C≈footer≈0.93，A≈来料.材料成本，逐 source_type 用例绿）。
若 C 不等 footer：检查 SUBTOTAL 登记 + `expressionToTokens("[报价小计(总计)]")` 是否产 component_subtotal token（whole-tab）。**这是恒等性硬闸，不绿不得进 Phase 2**。

- [ ] **Step 6: 自检 + 提交**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json   # 0 错误
git add src/pages/quotation/buildExcelSnapshot.ts src/pages/quotation/buildExcelSnapshot.test.ts src/pages/quotation/__fixtures__/lineItem093.ts src/pages/quotation/useLinkedExcelRows.ts
git commit -m "feat(excel): 新增前端 Excel 列求值器 buildExcelSnapshot（恒等卡片，覆盖8类source_type）"
```

---

## Phase 2 — Excel 视图改走前端快照（退役后端 GET）

### Task 2: `LinkedExcelView` 用 `buildExcelSnapshot` 产出内存快照并显示

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx`
- Test: `cpq-frontend/src/pages/quotation/LinkedExcelView.snapshot.test.ts`（新建，渲染 rows 形态断言）

- [ ] **Step 1: 写失败测试**

```typescript
// LinkedExcelView.snapshot.test.ts —— 验证：报价侧 rows 来自前端 buildExcelSnapshot，而非后端 GET
import { describe, it, expect, vi } from 'vitest';
import { buildExcelSnapshot } from './buildExcelSnapshot';
import { makeLineItem093 } from './__fixtures__/lineItem093';

describe('LinkedExcelView 报价侧 — 前端快照路径', () => {
  it('C 列显示值 == 卡片 footer 0.93（前端引擎，非后端 0.8527）', () => {
    const item = makeLineItem093();
    const cols = [{ col_key: 'C', title: '产品小计', source_type: 'TAB_JOIN_FORMULA' } as any];
    const snap = buildExcelSnapshot(item, cols, undefined, undefined, {});
    expect(snap.rows[0].C).toBeCloseTo(0.93, 2);
    expect(snap.rows[0].C).not.toBeCloseTo(0.8527, 3); // 不再是后端分叉值
  });
});
```

- [ ] **Step 2: 运行确认失败/基线**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/LinkedExcelView.snapshot.test.ts`
Expected: PASS（验证 buildExcelSnapshot 行为）—— 此用例锚定后续改动不回退。

- [ ] **Step 3: 改 `LinkedExcelView` 路径选择**

定位 `LinkedExcelView.tsx:100-144` 路径选择块（现状：`isV2 ? backendResult : useSnapshot ? snapshotResult : legacyResult`）。改为：报价侧始终用前端 `buildExcelSnapshot` 产出的快照。具体：

```typescript
// 用 useMemo 对每个 lineItem 算前端快照，合成与 useExcelSnapshotRows 同形态的 rows
const frontendRows = React.useMemo(() => {
  if (!parsedColumns?.length) return [];
  return lineItems.flatMap((li, i) => {
    const snap = buildExcelSnapshot(li, parsedColumns, driverExpansions, customerId, {
      pathCache, basicDataValues, globalVariableDefs, lookupExpansion,
    });
    return snap.rows.map((r, ri) => ({
      __key: `fe-${(li as any).id ?? (li as any).tempId ?? i}-${ri}`,
      __label: r.__hfPartNo ?? `产品 ${i + 1}`,
      __hfPartNo: r.__hfPartNo,
      __noData: false,
      ...r,
    }));
  });
}, [lineItems, parsedColumns, driverExpansions, customerId, pathCache, basicDataValues, globalVariableDefs]);

// 报价侧统一用 frontendRows；退役 isV2→backendResult 分支
const { rows, parsedColumns: cols, loading, error } = side === 'COSTING'
  ? /* 核价侧暂留原路径，后续计划处理 */ (isV2 ? backendResult : useSnapshot ? snapshotResult : legacyResult)
  : { rows: frontendRows, parsedColumns, loading: legacyResult.loading, error: legacyResult.error };
```

> 保留 `useLinkedExcelRows` 仅用于「解析列定义 parsedColumns + configShape + pathCache 预取」，其行值计算不再用于报价显示。`renderCellValue`（L51-69）不变（值来自前端快照，仍走 `formatNumber` 同口径）。退役 `useBackendExcelRows` 调用（报价侧不再 enabled；Phase 6 决定删除文件）。
> ⚠️ 主工作区该文件有并发 WIP（v2 屏幕迁移 + decimal）。在 worktree 基于最新 master 改；合并时与并发改动取并集、避开同段冲突（参照 RECORD 2026-06-21「union 合并」先例）。

- [ ] **Step 4: 验证显示恒等 + 自检**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json   # 0 错误
npx vitest run src/pages/quotation/LinkedExcelView.snapshot.test.ts src/pages/quotation/buildExcelSnapshot.test.ts  # 全绿
```
合并 master 后 LIVE 复验（dev server 服务主树）：admin 登录 → 打开 QT-20260621-1787 → 切 Excel 视图 → C 列显示 **0.93**（= 卡片 footer），不再 0.8527。
Vite transform：`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/LinkedExcelView.tsx` → 200。

- [ ] **Step 5: 提交**

```bash
git add src/pages/quotation/LinkedExcelView.tsx src/pages/quotation/LinkedExcelView.snapshot.test.ts
git commit -m "feat(excel): 报价Excel视图改走前端buildExcelSnapshot快照(恒等卡片), 退役isV2后端GET"
```

---

## Phase 3 — saveDraft 落前端两份快照（停后端重算）

### Task 3: `buildDraftPayload` 携带 `quoteExcelValues` + 后端原样落库

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`（`buildDraftPayload` L741-798）
- Modify: `cpq-frontend/src/services/quotationService.ts`（如有显式 payload 类型）
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/dto/SaveDraftRequest.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`（saveDraft 主循环）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/SaveDraftExcelSnapshotTest.java`（新建）

- [ ] **Step 1: 后端写失败测试（saveDraft 原样落 quoteExcelValues）**

```java
// SaveDraftExcelSnapshotTest.java
@QuarkusTest
class SaveDraftExcelSnapshotTest {
  @Inject QuotationService quotationService;
  @Test @TestTransaction
  void saveDraft_persistsFrontendQuoteExcelValuesVerbatim() {
    // 造一张草稿 + 一个 lineItem draft，quoteExcelValues = {"rows":[{"C":0.93}]}
    SaveDraftRequest req = /* 构造，含 lineItems[0].quoteExcelValues = "{\"rows\":[{\"C\":0.93}]}" */;
    UUID qId = /* 已存在草稿 id */;
    quotationService.saveDraft(qId, req);
    QuotationLineItem li = QuotationLineItem.find("quotationId", qId).firstResult();
    assertNotNull(li.quoteExcelValues);
    assertTrue(li.quoteExcelValues.contains("0.93"));  // 原样落库，未被后端 buildExcelValues 覆盖
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=SaveDraftExcelSnapshotTest -Dquarkus.http.test-port=0`
Expected: FAIL（`LineItemDraft.quoteExcelValues` 不存在 / 未落库）。

- [ ] **Step 3: 后端 DTO + saveDraft 落库**

`SaveDraftRequest.java` 的 `LineItemDraft` 增字段（紧邻现有快照无关字段）：
```java
/** 前端算好的报价 Excel 列值快照 {rows:[{col_key:value}]}，后端原样落库不重算（2026-06-21 前端单引擎） */
public String quoteExcelValues;
```

`QuotationService.saveDraft` 主循环（约 L460-500，`li.persist()` 之前、与 `li.subtotal=` 等并列）增：
```java
if (liDraft.quoteExcelValues != null) li.quoteExcelValues = liDraft.quoteExcelValues;
```

- [ ] **Step 4: 运行后端测试至绿**

Run: `cd cpq-backend && ./mvnw test -Dtest=SaveDraftExcelSnapshotTest -Dquarkus.http.test-port=0`
Expected: PASS。

- [ ] **Step 5: 前端 `buildDraftPayload` 携带快照**

`QuotationWizard.tsx` `buildDraftPayload`（L741-798）每个 lineItem 对象增（与 `subtotal: computeProductSubtotalSafe(...)` 并列）：
```typescript
quoteExcelValues: (() => {
  try {
    const cols = excelParsedColumnsByTemplate(li.templateId); // 复用已解析列；无则跳过
    if (!cols?.length) return undefined;
    return JSON.stringify(buildExcelSnapshot(li, cols, driverExpansions, customerIdValue, {
      pathCache: pathCacheRef.current, basicDataValues: undefined,
      globalVariableDefs: gvDefsRef.current, lookupExpansion,
    }));
  } catch { return undefined; }  // 求值失败不阻塞保存
})(),
```
> 列定义来源：复用 `LinkedExcelView`/`useLinkedExcelRows` 已解析的 parsedColumns。若 Wizard 层拿不到，封一个轻量解析 helper 或经 props/context 传入（实现期评估，优先复用避免重复解析）。

- [ ] **Step 6: 停后端报价侧重算覆盖**

`CardSnapshotService` 三处报价侧 `buildExcelValues` 写 `quoteExcelValues`：改为「仅当前端未送快照（li.quoteExcelValues==null）时才回退后端算」——但收口期目标是前端权威。Phase 3 先做**最小**：`snapshotLineValues`（新行初始化，前端尚无快照时）保留后端兜底；`editCardValue` / `refreshQuoteCardValues` 的报价 Excel 重算在 Phase 6 退役（避免与 saveDraft 前端值打架）。本 Step 仅加注释标记 + TODO 引用 Phase 6，不在此删（保持 Phase 间可独立验证）。

- [ ] **Step 7: 自检 + 提交**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json   # 0
cd cpq-backend && ./mvnw test -Dtest=SaveDraftExcelSnapshotTest -Dquarkus.http.test-port=0   # PASS
git add cpq-backend/src/main/java/com/cpq/quotation/dto/SaveDraftRequest.java \
        cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/SaveDraftExcelSnapshotTest.java \
        cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "feat(excel): saveDraft 携带前端 quoteExcelValues 快照并原样落库"
```

---

## Phase 4 — 导出读快照（XLSX + PDF）

### Task 4: `ExcelViewService.exportExcelView` + `QuotationExportService` 改读 `quote_excel_values`

**Files:**
- Modify: `cpq-backend/.../quotation/service/ExcelViewService.java`（`exportExcelView` L724-789）
- Modify: `cpq-backend/.../quotation/service/QuotationExportService.java`
- Test: `cpq-backend/.../quotation/service/ExportFromSnapshotTest.java`（新建）

- [ ] **Step 1: 写失败测试（导出值 == 快照值）**

```java
// ExportFromSnapshotTest.java
@QuarkusTest
class ExportFromSnapshotTest {
  @Inject ExcelViewService excelViewService;
  @Test @TestTransaction
  void exportXlsx_readsQuoteExcelValuesSnapshot_notRecompute() throws Exception {
    // 造 lineItem，quote_excel_values = {"rows":[{"C":0.93}]}；row_data 故意写成与快照不同(0.8527)
    UUID qId = /* 草稿 */;
    byte[] xlsx = excelViewService.exportExcelView(qId);
    // 解析 POI 读 C 列单元格 == 0.93（来自快照），不是 0.8527（row_data 重算）
    double c = readCellC(xlsx);
    assertEquals(0.93, c, 0.005);
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=ExportFromSnapshotTest -Dquarkus.http.test-port=0`
Expected: FAIL（导出仍走 `getExcelView` 重算 = 0.8527）。

- [ ] **Step 3: 改 `exportExcelView` 取值源**

`ExcelViewService.exportExcelView`（L724-789）：列定义/标题/合并/格式装配**不变**；单元格取值（L757-775）从 `getExcelView` 重算结果改为读 `li.quoteExcelValues` 快照的 `rows[i][col_key]`。
- EXCEL_FORMULA 列（L762-771）：**保留** POI `setCellFormula` 直写公式（公式由列定义决定，确定性，非引擎分叉）。
- 其它列：`writeFormattedCell(cell, snapshotRow.get(colKey), col)`，格式化逻辑 L797-818 不变（`isComputedColumn` + `COMPUTED_FALLBACK_DECIMALS`）。
- 多 lineItem / 多行：按快照 `rows` 顺序逐行写。

- [ ] **Step 4: PDF 同改**

`QuotationExportService.exportHtml`/`buildLineItemsData`：行值视图模型从 `quote_excel_values` 快照取（Qute `quotation-pdf.html` 绑定不变，仅数据源换）。新增/扩展用例断言 PDF 行值 == 快照值。

- [ ] **Step 5: 运行至绿 + 四口径一致性回归**

Run: `cd cpq-backend && ./mvnw test -Dtest=ExportFromSnapshotTest -Dquarkus.http.test-port=0` → PASS
LIVE（合并 master 后）：导出 XLSX + PDF，C 列 == 屏上 Excel == 卡片 footer == 0.93（四口径一致，本次目标的反命题验证）。

- [ ] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java \
        cpq-backend/src/main/java/com/cpq/quotation/service/QuotationExportService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/ExportFromSnapshotTest.java
git commit -m "feat(excel): XLSX/PDF 导出改读 quote_excel_values 前端快照, 不再后端重算"
```

---

## Phase 5 — 提交冻结快照（无后端重算）

### Task 5: 验证 submit 冻结前端快照、移除权威重算分支

**Files:**
- Modify: `cpq-backend/.../quotation/service/QuotationService.java`（`submit` L684-779 / `snapshotCollectorService.collect`）
- Test: `cpq-backend/.../quotation/service/SubmitFreezeSnapshotTest.java`（新建）

- [ ] **Step 1: 写失败/锚定测试**

```java
// SubmitFreezeSnapshotTest.java
@Test @TestTransaction
void submit_freezesQuoteExcelValues_noRecomputeDrift() {
  // 草稿 quote_excel_values = {"rows":[{"C":0.93}]}
  quotationService.submit(qId, userId);
  QuotationLineItem li = QuotationLineItem.find("quotationId", qId).firstResult();
  assertTrue(li.quoteExcelValues.contains("0.93"));   // 提交后值不漂移
  Quotation q = Quotation.findById(qId);
  assertTrue(q.submissionSnapshot.contains("0.93"));   // submission_snapshot 用前端快照
}
```

- [ ] **Step 2: 运行确认现状**

Run: `cd cpq-backend && ./mvnw test -Dtest=SubmitFreezeSnapshotTest -Dquarkus.http.test-port=0`
Expected: 现状 submit 不重算（已 PASS）则锚定；若 `snapshotCollectorService.collect` 触发重算导致漂移则 FAIL。

- [ ] **Step 3: 收口 submit（如有重算则移除）**

确认 `submit`（L684-779）未对 `quoteExcelValues` 二次权威重算；`snapshotCollectorService.collect` 采集时直接读持久化 `quoteExcelValues`（前端快照），不调 `buildExcelValues`。如发现重算分支，移除/跳过。

- [ ] **Step 4: 运行至绿 + 提交**

Run: `./mvnw test -Dtest=SubmitFreezeSnapshotTest -Dquarkus.http.test-port=0` → PASS
```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/SubmitFreezeSnapshotTest.java
git commit -m "feat(excel): submit 冻结前端 quoteExcelValues 快照, 移除权威重算漂移"
```

---

## Phase 6 — 退役/收口 + 更新基线文档

### Task 6: 删/绕双口径活路径 + 反转基线文档

**Files:**
- Modify: `cpq-backend/.../quotation/service/CardSnapshotService.java`（`editCardValue` / `refreshQuoteCardValues` / `snapshotLineValues` 的报价 Excel 重算 + `materializeWholeLineRowData`）
- Modify: `cpq-frontend/src/pages/quotation/useBackendExcelRows.ts`（报价侧退役）
- Modify: `docs/三大核心模块基线.md`
- Modify: `docs/RECORD.md`（追加本次记录）
- Modify: `docs/反模式.md`（如需新增「前后端双引擎分叉」反模式条目）

- [ ] **Step 1: 退役后端报价 Excel 重算（editCardValue / refresh）**

`CardSnapshotService.editCardValue`（L1479-1504）：移除/跳过 `buildExcelValues` 重算 `quoteExcelValues`（前端权威，编辑即时由前端算、saveDraft 落）。`materializeWholeLineRowData`（L1540-1570）的报价 row_data 物化：评估是否仍需（row_data 降为「前端快照扁平存档」，由 saveDraft 写）；若导出已不读 row_data，退役该物化。`refreshQuoteCardValues`（L1366-1374）同理停报价 Excel 重算。
> 纪律核对：退役后**不得有第二条写 `quoteExcelValues` 且产不同值的活路径**。grep `quoteExcelValues =` 与 `buildExcelValues(` 全后端，确认报价侧唯一写入源 = saveDraft 原样落前端值。

- [ ] **Step 2: 退役 `useBackendExcelRows`（报价）**

报价侧不再 enabled。若核价侧也不再用，删除文件 + `useBackendExcelRows.refreshSignal.test.ts`；否则保留给核价（后续计划处理）。同步评估 `quoteValuesAt` 刷新信号是否仍需（前端即时算后可简化；实现期定）。

- [ ] **Step 3: 跑回归确认无残留双路径**

```bash
cd cpq-backend && ./mvnw test -Dtest='SaveDraftExcelSnapshotTest,ExportFromSnapshotTest,SubmitFreezeSnapshotTest,CardSnapshotServiceTest,ConfigureProductServiceTest' -Dquarkus.http.test-port=0   # 全绿
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json && npx vitest run src/pages/quotation/buildExcelSnapshot.test.ts src/pages/quotation/LinkedExcelView.snapshot.test.ts   # 全绿
```
LIVE 四口径一致性（合并 master 后）：QT-20260621-1787 编辑元素 Cu 单价 → 卡片 footer == Excel 视图 C == 导出 XLSX C == 导出 PDF C（全 ≈0.93，改值后同步变化）。

- [ ] **Step 4: 反转基线文档**

`docs/三大核心模块基线.md` 报价单渲染章节：把「后端权威（ExcelViewService/ComponentDataEffectiveRows 读 row_data 计算）」改为「前端权威（buildExcelSnapshot 复用卡片引擎，Excel 恒等卡片；后端 = 存储 + 按快照导出 + 提交冻结）」。注明 2026-06-21 基线反转 + 本计划链接。

- [ ] **Step 5: RECORD + 反模式**

`docs/RECORD.md` 追加：`[2026-06-21] 报价 Excel 视图前端单引擎统一 | 涉及文件 | 关键决策(前端权威/恒等卡片/saveDraft两份快照/导出读快照/基线反转)`。`docs/反模式.md` 评估新增「同一展示值前后端双引擎必然分叉」条目（症状 0.93 vs 0.8527 → 反模式 → 单引擎单写源）。

- [ ] **Step 6: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java \
        cpq-frontend/src/pages/quotation/useBackendExcelRows.ts \
        docs/三大核心模块基线.md docs/RECORD.md docs/反模式.md
# 注意：RECORD/反模式/基线 docs 主工作区可能有并发未提交编辑，只 add 本次明确改动, 必要时与并发会话协调
git commit -m "refactor(excel): 退役后端报价Excel重算双路径 + 基线反转(后端权威→前端权威)"
```

---

## 验收标准（报价侧整体）

1. **恒等性 vitest 绿**：`buildExcelSnapshot.test.ts` C≈footer≈0.93、A≈来料.材料成本、逐 source_type 用例全绿。
2. **后端套件绿**：`SaveDraftExcelSnapshotTest` / `ExportFromSnapshotTest` / `SubmitFreezeSnapshotTest` + 回归（`CardSnapshotServiceTest`/`ConfigureProductServiceTest` 无回归）。
3. **四口径一致（LIVE）**：QT-20260621-1787 元素 Cu 单价改值后，卡片 footer == 屏上 Excel C == 导出 XLSX C == 导出 PDF C，全 ≈0.93 且随编辑同步刷新。
4. **无残留双路径**：grep 确认报价 `quoteExcelValues` 唯一写入源 = saveDraft 原样落前端值；后端无第二条产不同值的 Excel 计算活路径。
5. **基线文档更新**：`docs/三大核心模块基线.md` 反转为前端权威；RECORD 追加。
6. **自检声明**：每次「完成」附 TS 0 错误 ✅ + Vite 200 ✅ + 后端 401(非500) ✅ + 相关测试 PASS ✅。

## 已知风险与缓解

- **`expressionToTokens` 列表达式解析覆盖**：`[别名.字段]`/`[别名(总计)]` 须正确产 cross_tab_ref/component_subtotal token。缓解：Task 1 Step 1 先侦察 + 恒等性 vitest 硬闸。
- **E2E 两 spec 预存损坏**（`quotation-flow.spec.ts`/`composite-product-flow.spec.ts`，合并前既存）：本计划以 vitest + 后端单测 + LIVE API 为功能闸门；E2E 修复属独立技术债，如时间允许在 Phase 6 顺修。
- **并发 WIP 冲突**：`LinkedExcelView.tsx` / `useLinkedExcelRows.ts` / RECORD/基线 docs 主工作区有并发未提交改动。缓解：worktree 基于最新 master、合并取并集、只 add 本次文件、与对应会话协调。
- **核价侧未覆盖**：本计划只报价侧。核价 VARIABLE 列引用 `v_costing_summary_full.*`（服务端 SQL 视图）+ BOM spine 树渲染，另写后续计划。

## 不在本计划范围

- 核价侧（COSTING）Excel 统一（后续计划，报价恒等性验证通过后启动）。
- 「报价小计.总成本 公式是否应含加工费」等业务公式语义问题（前端单引擎后 Excel 与卡片一致即按卡片口径；口径本身要变属配置层另议）。
- 服务端对前端提交值的二次权威风控复核（提交冻结已满足「所见即所提」）。
