# Excel 视图导入即正确 + 全局小数位数统一 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让报价单/核价单的 Excel 视图在"刚导入、零手动操作"时就显示与产品卡片一致的正确数据，并让卡片/Excel视图/导出三处对同一个值显示完全一致的小数位数。

**Architecture:**
- **Part A（导入即正确）**：采用"写时快照"路线——在 导入/配置产品 收尾处复用后端公式引擎（`FormulaCalculationService.calculateRowFormulas`），把每个组件每行的 FORMULA 叶子列（材料成本/费用…）算齐后写进 `quotation_line_component_data.row_data`，使其与"用户编辑一次后"的状态一致。Excel 读路径（`ComponentDataEffectiveRows` 读 row_data 列求和 + 算小计）保持不变，从此对新导入数据也能命中全部叶子列。
- **Part B（小数位数统一）**：内部计算精度保持现状 4 位不动（不拆引擎，避开 AP-44/AP-51 雷区）；新增前端 1 个 `formatNumber` util + 后端 1 个 `NumberFormatUtil` helper 作为**唯一格式化口径**，替换现散落 5+ 处。位数来源：普通字段读组件管理新增的 `decimals` 键、结果列读 Excel 视图 column override 的 `display_format.decimals`；未配时"输入/取数列保留原精度、计算列兜底 2 位"。四舍五入 HALF_UP，"最多 N 位"去尾零。

**Tech Stack:** Java 17 + Quarkus + Hibernate Panache（后端公式引擎 / Apache POI 导出）、React + TypeScript + Ant Design（前端）、Playwright（E2E）、JUnit（后端单测）、Vitest（前端单测）。

**Scope note:** Part A 与 Part B 相互独立、各自可独立交付与测试。本文件合为一份 plan 但分两部分顺序执行（A 先，B 后；B 的 E2E 验证依赖 A 已修好的数据）。

---

## File Structure

**Part A（后端为主）**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/ConfigureProductResource.java`（配置产品收尾触发物化）
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/RowDataMaterializer.java`（新服务：snapshot_rows → 算公式叶子列 → 写 row_data）
- Create: `cpq-backend/src/test/java/com/cpq/quotation/service/RowDataMaterializerTest.java`
- Reference (不改，仅调用): `cpq-backend/src/main/java/com/cpq/engine/formula/FormulaCalculationService.java:50`（`calculateRowFormulas`）

**Part B（前后端）**
- Create: `cpq-frontend/src/utils/formatNumber.ts` + `cpq-frontend/src/utils/formatNumber.test.ts`
- Create: `cpq-backend/src/main/java/com/cpq/common/NumberFormatUtil.java` + 对应 test
- Modify（前端格式化接入点）:
  - `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx:38`（`renderCellValue` 补 NUMBER+decimals 分支）
  - `cpq-frontend/src/pages/quotation/components/ComponentCell.tsx:229-234`（FORMULA 渲染）
  - `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:1860`（`formatCurrency`）、`:2619`（列小计 `toFixed(4)`）
  - `cpq-frontend/src/pages/quotation/components/formatPathValue.ts:30`（取数数值分支）
  - `cpq-frontend/src/pages/component/types.ts:60`（`FieldItem` 加 `decimals`）、`newFieldRow`
  - `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（加"小数位数"编辑列）
- Modify（后端导出格式化）:
  - `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java:735`（`setCellValue` 套 POI 数字格式 / 值取整）
  - `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationExportService.java`（PDF/汇总 Excel 行级取整）

---

## PART A — 导入即正确（写时物化 row_data）

### Task A1: 新建 `RowDataMaterializer` 服务（核心：算齐 FORMULA 叶子列写回 row_data）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/quotation/service/RowDataMaterializer.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/RowDataMaterializerTest.java`
- Reference: `cpq-backend/src/main/java/com/cpq/engine/formula/FormulaCalculationService.java:50`（`calculateRowFormulas(componentsSnapshotJson, componentCode, rowData, crossComponentSubtotals)`）；`cpq-backend/src/main/java/com/cpq/quotation/entity/QuotationLineComponentData.java`

- [ ] **Step 1: 写失败单测**

先用 codegraph 确认 `FormulaCalculationService.calculateRowFormulas` 的精确签名与返回类型（`codegraph_node FormulaCalculationService.calculateRowFormulas`），据此定型下方 stub。测试覆盖：①一个含 FORMULA 列(材料成本=单价×用量)的组件，snapshot_rows 有单价/用量但 row_data 缺材料成本 → 物化后 row_data 每行含材料成本且=单价×用量；②跨页签 component_subtotal 引用能取到值；③snapshot_rows 为空 → row_data 不被破坏（保持原值，不抛异常）。

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RowDataMaterializerTest {

    @Inject RowDataMaterializer materializer;
    static final ObjectMapper M = new ObjectMapper();

    @Test
    void formulaLeafColumnsMaterializedFromSnapshotRows() throws Exception {
        // 组件: fields=[单价(INPUT_NUMBER),用量(INPUT_NUMBER),材料成本(FORMULA=单价*用量)]
        String componentsSnapshot = "[{" +
            "\"code\":\"LL\",\"name\":\"来料\",\"componentType\":\"NORMAL\"," +
            "\"fields\":[{\"name\":\"单价\",\"field_type\":\"INPUT_NUMBER\"}," +
            "{\"name\":\"用量\",\"field_type\":\"INPUT_NUMBER\"}," +
            "{\"name\":\"材料成本\",\"field_type\":\"FORMULA\",\"formula_name\":\"f1\"}]," +
            "\"formulas\":[{\"name\":\"f1\",\"target\":\"材料成本\"," +
            "\"expression\":[{\"type\":\"field\",\"value\":\"单价\"},{\"type\":\"op\",\"value\":\"*\"},{\"type\":\"field\",\"value\":\"用量\"}]}]}]";
        // snapshot_rows: 两行, 仅有单价/用量
        String snapshotRows = "[{\"单价\":2,\"用量\":3},{\"单价\":5,\"用量\":1}]";

        JsonNode out = materializer.materializeComponentRows(
            M.readTree(componentsSnapshot), "LL", M.readTree(snapshotRows), java.util.Map.of());

        assertEquals(2, out.size());
        assertEquals(6.0, out.get(0).path("材料成本").asDouble(), 1e-9);   // 2*3
        assertEquals(5.0, out.get(1).path("材料成本").asDouble(), 1e-9);   // 5*1
    }

    @Test
    void emptySnapshotRowsYieldsEmptyArrayNotError() throws Exception {
        String componentsSnapshot = "[{\"code\":\"LL\",\"name\":\"来料\",\"componentType\":\"NORMAL\",\"fields\":[],\"formulas\":[]}]";
        JsonNode out = materializer.materializeComponentRows(
            M.readTree(componentsSnapshot), "LL", M.createArrayNode(), java.util.Map.of());
        assertTrue(out.isArray());
        assertEquals(0, out.size());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run（在后端目录）：`cd cpq-backend && ./mvnw test -Dtest=RowDataMaterializerTest`
Expected: 编译失败 / 找不到 `RowDataMaterializer`。

- [ ] **Step 3: 实现 `RowDataMaterializer`**

按 Step 1 用 codegraph 核对的真实签名实现。核心逻辑：对每个组件，把 snapshot_rows 包成 `calculateRowFormulas` 期望的 baseRows 形态（注意 driverRow 包装，参照前端 `computeAllFormulas` 与 `FormulaCalculator.computeRows` 的 `baseRow.path("driverRow")` 约定），逐行求 FORMULA 列，回填进每行 map，产出新的 row_data 数组。`crossComponentSubtotals` 由调用方（Task A2）按各组件列和预聚合后传入（键 `code#col`/`name#col`，与 `ComponentDataEffectiveRows.SUBTOTAL_KEY_SEP` 同口径）。

```java
package com.cpq.quotation.service;

import com.cpq.engine.formula.FormulaCalculationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * 写时物化：导入/配置产品收尾时，把 snapshot_rows（driver 原始取数）按组件 FORMULA 定义
 * 算齐叶子列（材料成本/费用…），产出与"用户编辑一次后"同形的 row_data。
 * 复用 {@link FormulaCalculationService}，保证与前端 computeAllFormulas 同口径。
 */
@ApplicationScoped
public class RowDataMaterializer {

    @Inject FormulaCalculationService formulaCalculationService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param componentsSnapshot 模板 components 快照（含 fields + formulas）
     * @param componentCode      目标组件 code
     * @param snapshotRows       driver 展开原始行数组
     * @param crossComponentSubtotals 跨组件 component_subtotal 取值（code#col / name#col）
     * @return 算齐 FORMULA 叶子列后的 row_data 数组
     */
    public JsonNode materializeComponentRows(JsonNode componentsSnapshot, String componentCode,
                                             JsonNode snapshotRows, Map<String, Double> crossComponentSubtotals) {
        ArrayNode out = MAPPER.createArrayNode();
        if (snapshotRows == null || !snapshotRows.isArray() || snapshotRows.isEmpty()) return out;
        // 委托既有引擎逐行算公式；下方为意图骨架，实参形态以 Step 1 codegraph 核对结果为准
        JsonNode computed = formulaCalculationService.calculateRowFormulas(
            componentsSnapshot, componentCode, snapshotRows, crossComponentSubtotals);
        return computed != null && computed.isArray() ? computed : out;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run：`cd cpq-backend && ./mvnw test -Dtest=RowDataMaterializerTest`
Expected: PASS（2 tests）。若 `calculateRowFormulas` 实参形态与骨架不符，按真实签名在本类内做适配（包/解 driverRow），不要改 `FormulaCalculationService`。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/RowDataMaterializer.java cpq-backend/src/test/java/com/cpq/quotation/service/RowDataMaterializerTest.java
git commit -m "feat(quotation): RowDataMaterializer 写时算齐 FORMULA 叶子列写回 row_data"
```

### Task A2: 在 导入/配置产品 收尾处触发物化并落库

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/ConfigureProductResource.java`（先用 `codegraph_trace ConfigureProductResource.configureProduct` 定位收尾段：snapshot 写完之后、事务提交之前）
- Reference: `cpq-backend/src/main/java/com/cpq/quotation/entity/QuotationLineComponentData.java`（`rowData` 字段）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/RowDataMaterializerTest.java`（追加集成断言）

- [ ] **Step 1: 写失败集成测试**

针对 configure/import 流程：构造一个含跨页签 报价小计=[来料.材料成本]+[组装加工费.费用] 的最小模板，走 configure 入口后，直接查 `QuotationLineComponentData.row_data`，断言"组装加工费.费用"列已在 row_data 中（非空、=公式结果），且各页签列和满足报价小计=各页签之和。用 `codegraph_callers RowDataMaterializer.materializeComponentRows` 在 Step 3 后确认接入点唯一。

（测试代码：复用 `cpq-e2e` 或后端集成测试既有夹具；若无现成 configure 夹具，则以 service 层直调 + 断言 row_data 内容替代，避免 HTTP 层耦合。具体夹具构造在实现时按 `QuotationServiceTest` 既有模式编写。）

- [ ] **Step 2: 运行确认失败**

Run：`cd cpq-backend && ./mvnw test -Dtest=RowDataMaterializerTest`
Expected: 新增集成断言 FAIL（row_data 仍缺"费用"列）。

- [ ] **Step 3: 接入收尾物化**

在 `ConfigureProductResource.configureProduct` 写完 snapshot 后，遍历该行各组件：先按 `ComponentDataEffectiveRows.columnSums` 同口径预聚合 `crossComponentSubtotals`（两遍：第一遍聚合非依赖列，第二遍算依赖跨页签的列），对每个组件调 `materializer.materializeComponentRows(...)`，把结果 `JsonNode` 序列化写入对应 `QuotationLineComponentData.rowData` 并 `persist`。

```java
// ConfigureProductResource.configureProduct(...) 收尾段（snapshot 写完之后）
// 注入: @Inject RowDataMaterializer rowDataMaterializer;
for (QuotationLineItem line : savedLines) {
    Map<String, Double> crossSubtotals = preAggregateColumnSums(line); // 复用 columnSums 口径
    for (QuotationLineComponentData cd : line.getComponentData()) {
        JsonNode snapRows = readSnapshotRows(cd);               // 解析 snapshot_rows
        JsonNode materialized = rowDataMaterializer.materializeComponentRows(
            componentsSnapshot, cd.componentCode, snapRows, crossSubtotals);
        cd.rowData = MAPPER.writeValueAsString(materialized);
        cd.persist();
    }
}
```

⚠️ AP-51 行数纪律：物化产出行数 = snapshot_rows 行数（driver 权威），**不得** `Math.max(已有 row_data 行数, ...)`。

- [ ] **Step 4: 运行确认通过**

Run：`cd cpq-backend && ./mvnw test -Dtest=RowDataMaterializerTest`
Expected: PASS（含新集成断言）。
后端自检：`touch` 一个 java 文件强制 Quarkus 重启 → 等 6 秒 → `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health` 期望 200。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/resource/ConfigureProductResource.java cpq-backend/src/test/java/com/cpq/quotation/service/RowDataMaterializerTest.java
git commit -m "feat(quotation): 配置产品收尾物化各组件 row_data(导入即正确)"
```

### Task A3: E2E 验证"刚导入 Excel 即正确"

**Files:**
- Reference: `cpq-frontend/e2e/quotation-flow.spec.ts`、`docs/E2E测试方法.md`

- [ ] **Step 1: 复测脚本（用真实报价单 QT 链路）**

在 dev 环境用 `psql` 造/选一个刚配置完、未编辑过的报价单（参照历史记忆 `cpq-e2e-quotation-flow-test-data`：苏州西门子 + 报价模板），不打开卡片编辑，直接 GET Excel 视图接口。

Run：
```bash
export PGPASSWORD=joii5231
# 取最近一个 DRAFT 报价单 id
psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db -c "SELECT id, quote_no FROM quotation WHERE status='DRAFT' ORDER BY created_at DESC LIMIT 3"
# 登录拿 cookie 后 GET excel-view（templateId=报价模板），断言 产品小计 == 卡片 产品小计
```
Expected: Excel 产品小计 与卡片产品小计一致（如均为 0.14 量级），不再是只含来料的 0.077。

- [ ] **Step 2: 跑全量 E2E（协议级改动强制）**

Run：
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: 所有 test `passed`，`'加载中' final count = 0`，8 Tab `'加载中'=0`。

- [ ] **Step 3: 提交（截图证据）**

```bash
git add cpq-frontend/e2e/quotation-flow.spec.ts
git commit -m "test(quotation-flow): 验证刚导入报价单 Excel 视图即与卡片一致"
```

---

## PART B — 全局小数位数统一（展示/导出层）

### Task B1: 新建前端唯一格式化 util `formatNumber`

**Files:**
- Create: `cpq-frontend/src/utils/formatNumber.ts`
- Test: `cpq-frontend/src/utils/formatNumber.test.ts`

- [ ] **Step 1: 写失败单测**

```ts
import { describe, it, expect } from 'vitest';
import { formatNumber, resolveDecimals } from './formatNumber';

describe('resolveDecimals', () => {
  it('显式 decimals 优先', () => {
    expect(resolveDecimals({ decimals: 3, isComputed: true })).toBe(3);
  });
  it('计算列未配 → 兜底 2', () => {
    expect(resolveDecimals({ isComputed: true })).toBe(2);
  });
  it('输入/取数列未配 → null(保留原精度)', () => {
    expect(resolveDecimals({ isComputed: false })).toBeNull();
  });
});

describe('formatNumber', () => {
  it('计算列兜底 2 位、四舍五入 HALF_UP', () => {
    expect(formatNumber(0.144, { isComputed: true })).toBe('0.14');
    expect(formatNumber(0.145, { isComputed: true })).toBe('0.15'); // 半进
  });
  it('"最多两位"去尾零', () => {
    expect(formatNumber(0.1, { isComputed: true })).toBe('0.1');
    expect(formatNumber(5, { isComputed: true })).toBe('5');
  });
  it('输入/取数列保留原精度(汇率不被压)', () => {
    expect(formatNumber(6.9755, { isComputed: false })).toBe('6.9755');
  });
  it('显式 decimals 覆盖', () => {
    expect(formatNumber(6.9755, { decimals: 4 })).toBe('6.9755');
    expect(formatNumber(6.9755, { decimals: 2 })).toBe('6.98');
  });
  it('PERCENT: *100 + %', () => {
    expect(formatNumber(0.0825, { isPercent: true, decimals: 2 })).toBe('8.25%');
  });
  it('空/非数字 → null', () => {
    expect(formatNumber('', {})).toBeNull();
    expect(formatNumber(null, {})).toBeNull();
    expect(formatNumber('abc', {})).toBeNull();
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run：`cd cpq-frontend && npx vitest run src/utils/formatNumber.test.ts`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 实现 util**

用项目已依赖的 `decimal.js`（`Decimal`，见 `formulaEngine.ts`）做精确 HALF_UP，与后端 `RoundingMode.HALF_UP` 对齐。

```ts
// cpq-frontend/src/utils/formatNumber.ts
import Decimal from 'decimal.js';

export interface DecimalSpec {
  /** 显式配置位数：字段 decimals 或列 display_format.decimals。null/undefined = 未配。 */
  decimals?: number | null;
  /** 是否为"计算得出的列"(FORMULA/TAB_JOIN/CARD_FORMULA/小计/总计/is_subtotal)；未配时兜底 2 位。 */
  isComputed?: boolean;
  /** PERCENT 列：值 ×100 加 % 后缀（默认 2 位）。 */
  isPercent?: boolean;
}

const COMPUTED_FALLBACK = 2;

/** 决定最终位数：显式 > 计算列兜底 2 > null(保留原精度)。 */
export function resolveDecimals(spec: DecimalSpec): number | null {
  if (spec.decimals != null) return spec.decimals;
  if (spec.isComputed) return COMPUTED_FALLBACK;
  return null;
}

function trimTrailing(s: string): string {
  return s.includes('.') ? s.replace(/\.?0+$/, '') : s;
}

/** 统一数字格式化口径（卡片/Excel视图/导出共用）。返回 null 表示应显示占位 "—"。 */
export function formatNumber(value: unknown, spec: DecimalSpec = {}): string | null {
  if (value == null || value === '') return null;
  let d: Decimal;
  try { d = new Decimal(typeof value === 'number' ? value : String(value).trim()); }
  catch { return null; }
  if (!d.isFinite()) return null;

  if (spec.isPercent) {
    const dec = spec.decimals ?? 2;
    return `${d.times(100).toDecimalPlaces(dec, Decimal.ROUND_HALF_UP).toFixed(dec)}%`;
  }
  const dec = resolveDecimals(spec);
  if (dec == null) return trimTrailing(d.toString());                 // 保留原精度
  return trimTrailing(d.toDecimalPlaces(dec, Decimal.ROUND_HALF_UP).toFixed(dec));
}
```

- [ ] **Step 4: 运行确认通过**

Run：`cd cpq-frontend && npx vitest run src/utils/formatNumber.test.ts`
Expected: PASS（全部用例）。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/utils/formatNumber.ts cpq-frontend/src/utils/formatNumber.test.ts
git commit -m "feat(format): 新增统一数字格式化 util formatNumber"
```

### Task B2: 组件管理字段加"小数位数"配置

**Files:**
- Modify: `cpq-frontend/src/pages/component/types.ts:60`（`FieldItem` 加 `decimals`）+ `newFieldRow`
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（加编辑列，仅数值类字段显示）
- Reference: 后端 `ComponentService.java:37 VALID_FIELD_TYPES`（**无需改**——decimals 是字段元数据键，非新 field_type；jsonb 整存自动透传）

- [ ] **Step 1: types.ts 加键**

```ts
// FieldItem 内追加（types.ts:60 附近）
  /** 显示小数位数（卡片/Excel/导出共用）。未配 → 计算列兜底 2 位、输入/取数列保留原精度。 */
  decimals?: number | null;
```
`newFieldRow(...)` 工厂里**不**主动写 `decimals`（默认 undefined = 未配，走兜底规则）。

- [ ] **Step 2: FieldConfigTable 加编辑列**

在字段配置表加一列"小数位数"，仅当 `field_type ∈ {INPUT_NUMBER, FORMULA, BASIC_DATA, LIST_FORMULA}` 时渲染 `InputNumber`（min=0, max=6, 允许清空=未配），其余类型显示 `—`。

```tsx
{
  title: '小数位数',
  dataIndex: 'decimals',
  width: 110,
  render: (_: any, row: FieldItem, idx: number) => {
    const numeric = ['INPUT_NUMBER', 'FORMULA', 'BASIC_DATA', 'LIST_FORMULA'].includes(row.field_type);
    if (!numeric) return <span style={{ color: '#bbb' }}>—</span>;
    return (
      <InputNumber min={0} max={6} value={row.decimals ?? undefined}
        placeholder="默认"
        onChange={(v) => updateField(idx, { decimals: v == null ? null : Number(v) })} />
    );
  },
},
```
（`updateField` 用本文件既有的行更新函数名；实现时按真实函数名对齐。）

- [ ] **Step 3: 前端自检**

Run：
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/FieldConfigTable.tsx
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/types.ts
```
Expected: tsc 0 错误；两个 200。

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/component/types.ts cpq-frontend/src/pages/component/FieldConfigTable.tsx
git commit -m "feat(component): 字段配置新增'小数位数'(decimals)"
```

### Task B3: 前端各显示点接入 `formatNumber`

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx:38`、`cpq-frontend/src/pages/quotation/components/ComponentCell.tsx:229-234`、`cpq-frontend/src/pages/quotation/QuotationStep2.tsx:1860,2619`、`cpq-frontend/src/pages/quotation/components/formatPathValue.ts:30`

- [ ] **Step 1: LinkedExcelView.renderCellValue 补 NUMBER+decimals 分支**

报价 Excel 与核价 Excel 共用本组件，一处改两处生效。"是否计算列"判据：`col.source_type ∈ {FORMULA, CARD_FORMULA, TAB_JOIN_FORMULA, EXCEL_FORMULA}` 或列被标记 subtotal/total。

```tsx
import { formatNumber } from '../../utils/formatNumber';
// ...
function renderCellValue(val: any, col: CostingTemplateColumn): React.ReactNode {
  if (val === null || val === undefined || val === '' || val === '—') {
    return <span style={{ color: '#bbb' }}>—</span>;
  }
  const fmt = col.display_format;
  const isComputed = ['FORMULA', 'CARD_FORMULA', 'TAB_JOIN_FORMULA', 'EXCEL_FORMULA'].includes(col.source_type as string);
  if (fmt?.type === 'PERCENT') {
    const s = formatNumber(val, { isPercent: true, decimals: fmt.decimals ?? 2 });
    return s ?? <span style={{ color: '#bbb' }}>—</span>;
  }
  if (typeof val === 'number' || (!isNaN(parseFloat(String(val))) && isFinite(Number(val)))) {
    const s = formatNumber(val, { decimals: fmt?.decimals ?? null, isComputed });
    return s ?? <span style={{ color: '#bbb' }}>—</span>;
  }
  return String(val);
}
```

- [ ] **Step 2: ComponentCell FORMULA 渲染套用字段 decimals**

```tsx
// ComponentCell.tsx:229-234 FORMULA 分支
const val = formulaCache[field.name];
const shown = formatNumber(val, { decimals: field.decimals ?? null, isComputed: true });
return (
  <span className="qt-formula-cell-value">
    {shown ?? '—'}
  </span>
);
```
（顶部 `import { formatNumber } from '../../../utils/formatNumber';`，按真实相对深度修正。）BASIC_DATA 数值分支同理：在 `formatPathValue` 命中纯数值时，由调用方传入 `field.decimals` 走 `formatNumber`（见 Step 4）。

- [ ] **Step 3: QuotationStep2 金额与列小计统一**

`formatCurrency`（:1860）改为 `¥${formatNumber(n, { isComputed: true, decimals: 2 }) ?? '0'}`（保持 2 位语义但走统一口径）；列小计（:2619 的 `parseFloat(v.toFixed(4)).toString()`）改为 `formatNumber(v, { isComputed: true, decimals: colDecimals ?? null }) ?? '—'`，金额列仍前缀 ¥。

- [ ] **Step 4: formatPathValue 数值分支支持位数**

给 `formatPathValue` 增加可选第二参 `decimals?: number | null`，纯数值（:30 的 `typeof v === 'number'`）改走 `formatNumber(v, { decimals })`；调用方（ComponentCell BASIC_DATA）传 `field.decimals`。非数值分支不变。

- [ ] **Step 5: 前端自检**

Run：
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
for f in pages/quotation/LinkedExcelView.tsx pages/quotation/components/ComponentCell.tsx pages/quotation/QuotationStep2.tsx pages/quotation/components/formatPathValue.ts; do
  curl -s -o /dev/null -w "$f %{http_code}\n" "http://localhost:5174/src/$f"; done
```
Expected: tsc 0 错误；4 个文件 200。

- [ ] **Step 6: 提交**

```bash
git add cpq-frontend/src/pages/quotation/LinkedExcelView.tsx cpq-frontend/src/pages/quotation/components/ComponentCell.tsx cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/components/formatPathValue.ts
git commit -m "feat(format): 卡片/Excel视图统一接入 formatNumber"
```

### Task B4: 后端导出格式化（POI Excel 视图导出 + PDF/汇总 Excel）

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/common/NumberFormatUtil.java` + test
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java:735`（`setCellValue`）
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationExportService.java`（行级 subtotal 取整）

- [ ] **Step 1: 写 NumberFormatUtil 失败单测**

```java
package com.cpq.common;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class NumberFormatUtilTest {
    @Test void computedFallbackTwo() {
        assertEquals("0.14", NumberFormatUtil.format(new BigDecimal("0.144"), null, true));
        assertEquals("0.15", NumberFormatUtil.format(new BigDecimal("0.145"), null, true)); // HALF_UP
    }
    @Test void trimTrailingZeros() {
        assertEquals("0.1", NumberFormatUtil.format(new BigDecimal("0.10"), null, true));
        assertEquals("5", NumberFormatUtil.format(new BigDecimal("5.00"), null, true));
    }
    @Test void rawKeepsPrecision() {
        assertEquals("6.9755", NumberFormatUtil.format(new BigDecimal("6.9755"), null, false));
    }
    @Test void explicitOverrides() {
        assertEquals("6.98", NumberFormatUtil.format(new BigDecimal("6.9755"), 2, false));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run：`cd cpq-backend && ./mvnw test -Dtest=NumberFormatUtilTest`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现 NumberFormatUtil（与前端 formatNumber 同口径）**

```java
package com.cpq.common;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 后端统一数字格式化（与前端 utils/formatNumber.ts 同口径：HALF_UP + 最多 N 位去尾零）。 */
public final class NumberFormatUtil {
    private static final int COMPUTED_FALLBACK = 2;
    private NumberFormatUtil() {}

    /** @param decimals 显式位数(null=未配)；@param isComputed 计算列(未配兜底 2，输入列保留原精度) */
    public static String format(BigDecimal value, Integer decimals, boolean isComputed) {
        if (value == null) return "";
        Integer d = decimals != null ? decimals : (isComputed ? COMPUTED_FALLBACK : null);
        BigDecimal r = (d == null) ? value : value.setScale(d, RoundingMode.HALF_UP);
        return r.stripTrailingZeros().toPlainString();
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run：`cd cpq-backend && ./mvnw test -Dtest=NumberFormatUtilTest`
Expected: PASS。

- [ ] **Step 5: 接入 Excel 视图导出**

`ExcelViewService.exportExcelView`（:672）写值处（:716/735）：对数值列改为读该列 `display_format.decimals` + 是否计算列，套 POI `DataFormat`（`#,##0.##`/按位数生成）或先 `NumberFormatUtil.format` 后写字符串。优先 POI 样式（保留 Excel 数字态可计算），位数用 `createAmountStyle` 同法按列动态生成格式串。

- [ ] **Step 6: 接入 PDF/汇总 Excel**

`QuotationExportService` 行级 subtotal（agent 标 :222 `toString()`）改 `NumberFormatUtil.format(subtotal, null, true)`；顶层 originalAmount/totalAmount 已 `setScale(2)` 保持；汇总 Excel `createAmountStyle`（:295）保持 `#,##0.00`。

- [ ] **Step 7: 后端自检 + 提交**

Run：`cd cpq-backend && ./mvnw test -Dtest=NumberFormatUtilTest` → PASS；`touch` java 重启 → `curl .../q/health` 200。
```bash
git add cpq-backend/src/main/java/com/cpq/common/NumberFormatUtil.java cpq-backend/src/test/java/com/cpq/common/NumberFormatUtilTest.java cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java cpq-backend/src/main/java/com/cpq/quotation/service/QuotationExportService.java
git commit -m "feat(export): 后端 Excel/PDF 导出接入 NumberFormatUtil 统一小数位"
```

### Task B5: 结果列兜底 + column override decimals 真正消费（端到端口径一致）

**Files:**
- Reference/Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelColumnResolver.java:96 mergeColumnOverrides`（确认 `display_format.decimals` 合并进列定义后被导出/视图消费）
- Modify: `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`（如该入口配置 TAB_JOIN 结果列，确保能写 `display_format.decimals`；`ExcelViewConfigTab.tsx:415-433` 已有 NUMBER+decimals UI，确认覆盖结果列）

- [ ] **Step 1: 验证 column override decimals 贯通**

确认链路：前端 `ExcelViewConfigTab` 配 NUMBER decimals → 存 `excel_view_config.column_overrides[].display_format.decimals` → 后端 `ExcelColumnResolver.mergeColumnOverrides` 合并 → `getExcelView`/`exportExcelView` 与前端 `renderCellValue` 都读到。用 codegraph `codegraph_trace` 从 `mergeColumnOverrides` 追到消费点，补齐任何未消费分支。

- [ ] **Step 2: 兜底规则单测/手测**

结果列（小计/总计/TAB_JOIN/FORMULA）未配 decimals 时显示 2 位；输入/取数列未配时保留原精度。覆盖在 B1/B4 单测已断言；此步补一个端到端手测：同一报价单同一值，卡片显示 = Excel 屏幕显示 = 导出 Excel 显示 = 导出 PDF 显示（位数一致）。

- [ ] **Step 3: 自检 + 提交**

Run：`cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误。
```bash
git add cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx cpq-frontend/src/pages/template/ExcelViewConfigTab.tsx cpq-backend/src/main/java/com/cpq/quotation/service/ExcelColumnResolver.java
git commit -m "feat(format): 结果列兜底2位 + column override decimals 端到端消费"
```

### Task B6: 全链路 E2E + 四口径一致性验证

**Files:**
- Reference: `cpq-frontend/e2e/quotation-flow.spec.ts`、`cpq-frontend/e2e/composite-product-flow.spec.ts`、`docs/E2E测试方法.md`

- [ ] **Step 1: 双 spec E2E**

Run：
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```
Expected: 两个 spec 全 `passed`，`'加载中' final count = 0`。

- [ ] **Step 2: 四口径同值同位数手测**

对一个真实报价单：①卡片 产品小计、②报价 Excel 视图 产品小计、③导出 Excel 产品小计、④导出 PDF 产品小计——四处**数值相等且小数位数一致**（如均 `0.14`，不出现 `0.144`）；核价单 Excel 视图同验。汇率列四处均保留原精度（如 `6.9755`，未被压成 2 位）。

- [ ] **Step 3: 提交（截图证据）**

附 qf-19 + qf-21~28 截图 + 四口径对比截图。
```bash
git add cpq-frontend/e2e/quotation-flow.spec.ts
git commit -m "test: 验证卡片/Excel视图/导出 Excel/导出 PDF 四口径小数位一致"
```

---

## 文档与记录（收尾）

- [ ] 更新 `docs/RECORD.md`：追加本次"导入即正确(写时物化 row_data) + 全局小数位统一(formatNumber/NumberFormatUtil)"条目（日期/模块/文件/关键决策）。
- [ ] 在 `docs/配置方法论-合并版.md` 补一节"字段小数位数(decimals)配置 + 结果列兜底 2 位规则"。
- [ ] 若涉及 `field_type` 渲染协议变更，对照 `docs/反模式.md` AP-44 17 检查点自检（本方案 decimals 是字段元数据键、非新 field_type，预期不触发全量 17 点，但 enrich/snapshot 透传需确认）。

---

## Self-Review 结论

- **Spec 覆盖**：问题1(导入即正确)=Part A；问题2(小数:内部精度不动 / HALF_UP / 字段+列覆盖配置 / 输入列保原精度·计算列兜底2 / 小计→总计末端取整 / 覆盖报价+核价+导出+卡片四面)=Part B B1~B6。✅
- **占位符**：核心新模块（formatNumber / NumberFormatUtil / RowDataMaterializer）给出完整代码；既有文件接入点给出 file:line + 代表性代码 + "按真实签名/函数名对齐"提示（因 subagent-driven 执行时会读真实代码）。
- **类型一致**：`formatNumber`/`resolveDecimals`/`DecimalSpec`/`NumberFormatUtil.format`/`materializeComponentRows` 跨任务签名一致。
- **风险**：Part A 触 AP-51 行数纪律（已标注 driver 权威）；Part B 限制在展示/导出层、不拆引擎，规避 AP-44/AP-51 计算协议雷区。
