# rowKey 字段名→驱动列名 解析修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `computeRowKey` / `computeDedupKey` 按 `row_key_fields`（字段名）裸读 driverRow 导致多 key 字段组件 rowKey 全塌成 `"||"`、单条编辑抹平全部 driver 行、cross_tab 求和退化成「末值×行数」的潜伏 bug。

**Architecture:** driverRow 的键是**视图列名**（如 `_料件`，源自 `$wgj_view._料件`），而 `row_key_fields` 存的是**字段名**（`料件`）。修复 = 让 `computeRowKey`/`computeDedupKey` 在取 key 字段值时，按字段定义的 default_source/basic_data_path 把字段名解析成值（复用后端 `resolveRowByFieldName` / 前端等价解析），并在「全部 key 段为空」时回退行号。前后端两套求值器同步改，靠对拍测试锁一致。

**Tech Stack:** Java 17 / Quarkus（后端 `FormulaCalculator`、`CardSnapshotService`、`CardEffectiveRows`、`RowKeyUniquenessService`）；React + TS / Vitest（前端 `useCardSnapshots.ts`、`rowDedup.ts`、`QuotationStep2.tsx`、`ReadonlyProductCard.tsx`）。

**复现基线（QT-20260613-1710，line_item 61213558-b269-4dfb-937b-07cac220280f）：** 外购件 `row_key_fields=["料件","要素"]`，driverRow 键 `_料件/_要素/费用/单位`；4 行料9 的 `费用`=0.05/0.2/0.002/0.007，正确求和 **0.259**；当前 rowKey 全 `"||"` → 单条 editRow(`费用=0.002`) 抹平 4 行 → `SUM(外购件.费用)`=0.002×4=**0.008**。修复目标：料9 材料费 = 0.259。

---

## File Structure

- `cpq-backend/.../quotation/service/FormulaCalculator.java` — `computeRowKey`（:584）改签名+复用 `resolveRowByFieldName`；`computeDedupKey`（:602）同款；调用点 :532 透传。
- `cpq-backend/.../quotation/service/CardSnapshotService.java` — 3 个 `computeRowKey` 调用点（:872/:919/:1400）透传 fields+basicDataValues。
- `cpq-backend/.../quotation/service/card/CardEffectiveRows.java` — 私有 `computeRowKey`（:159）同款修 + 调用点 :85 透传。
- `cpq-backend/.../quotation/service/rowkey/RowKeyUniquenessService.java` — `computeDedupKey` 调用点（:61/:65）透传 fields。
- `cpq-frontend/src/pages/quotation/useCardSnapshots.ts` — `computeRowKey`（:52）改签名+加解析；内部调用点 :136/:148。
- `cpq-frontend/src/pages/quotation/rowDedup.ts` — `computeDedupKey`（:6）同款。
- `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` — 调用点 :1978 透传。
- `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` — 调用点 :482 透传。
- 测试：后端 `FormulaCalculatorTest`、`FormulaCalculatorComputeDedupKeyTest`、对拍 `FormulaCalculatorCrossTabTest`；前端 `useCardSnapshots` 新测试、`rowDedup.test.ts`。

**契约不变量：** 前后端 `computeRowKey` 对同一 (rowKeyFields, fields, driverRow, basicDataValues) 必须产出**逐字节相同**的 rowKey 字符串（editRows/formulaResults 跨端查表依赖）。

---

## Task 1：后端 computeRowKey — 失败测试（复现 "||" 塌缩）

**Files:**
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorTest.java`

- [ ] **Step 1: 写失败测试**

在 `FormulaCalculatorTest` 加（`fc` 为被测 `FormulaCalculator` 实例，`om` 为 `new ObjectMapper()`；若类中已有等价 fixture 复用之）：

```java
@Test
void computeRowKey_resolvesFieldNameToDriverColumn_viaDefaultSourcePath() throws Exception {
    // 外购件: row_key_fields 用字段名 料件/要素, 但 driverRow 键是视图列名 _料件/_要素
    JsonNode rowKeyFields = om.readTree("[\"料件\",\"要素\"]");
    JsonNode fields = om.readTree("""
        [
          {"name":"料件","field_type":"INPUT_TEXT","default_source":{"type":"BASIC_DATA","path":"$wgj_view._料件"}},
          {"name":"要素","field_type":"INPUT_TEXT","default_source":{"type":"BASIC_DATA","path":"$wgj_view._要素"}},
          {"name":"费用","field_type":"INPUT_NUMBER","default_source":{"type":"BASIC_DATA","path":"$wgj_view.费用"}}
        ]""");
    JsonNode driverRow = om.readTree("{\"_料件\":\"料9\",\"_要素\":\"加工费\",\"费用\":0.05}");
    JsonNode bdv = om.readTree("{\"{$wgj_view._料件}\":\"料9\",\"{$wgj_view._要素}\":\"加工费\",\"{$wgj_view.费用}\":0.05}");

    String rk = fc.computeRowKey(rowKeyFields, fields, driverRow, bdv);

    assertEquals("料9||加工费", rk);  // 修复前为 "||"
}

@Test
void computeRowKey_allKeyPartsEmpty_returnsNull_soCallerFallsBackToIndex() throws Exception {
    JsonNode rowKeyFields = om.readTree("[\"料件\",\"要素\"]");
    JsonNode fields = om.readTree("[{\"name\":\"料件\",\"field_type\":\"INPUT_TEXT\"},{\"name\":\"要素\",\"field_type\":\"INPUT_TEXT\"}]");
    JsonNode driverRow = om.readTree("{}");
    String rk = fc.computeRowKey(rowKeyFields, fields, driverRow, null);
    assertNull(rk);  // 全空 → null（调用方用行号），不再返回 "||"
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -Dtest=FormulaCalculatorTest#computeRowKey_resolvesFieldNameToDriverColumn_viaDefaultSourcePath test`
Expected: 编译失败（`computeRowKey` 还是 2 参）或断言失败（得到 `"||"`）。

- [ ] **Step 3: 提交失败测试**

```bash
git add cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorTest.java
git commit -m "test(rowkey): 复现 computeRowKey 字段名裸读 driverRow 塌成 || (RED)"
```

---

## Task 2：后端 computeRowKey — 实现解析 + 全空回退

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java:584`（方法体）

- [ ] **Step 1: 新签名 + 复用 resolveRowByFieldName**

把 `computeRowKey(JsonNode rowKeyFields, JsonNode driverRow)` 替换为：

```java
/** rowKey = 按 rowKeyFields（字段名）解析值用 {@code ||} 拼接。
 *  driverRow 的键常是视图列名（如 _料件），故先经 resolveRowByFieldName 把字段名→值
 *  解析（覆盖 default_source/basic_data_path → basicDataValues）。全部 key 段为空 → null。 */
public String computeRowKey(JsonNode rowKeyFields, JsonNode fields, JsonNode driverRow, JsonNode basicDataValues) {
    if (rowKeyFields == null || !rowKeyFields.isArray() || rowKeyFields.size() == 0) return null;
    if (rowKeyFields.size() == 1 && "__seq_no__".equals(rowKeyFields.get(0).asText(""))) return null;
    Map<String, Object> resolved = resolveRowByFieldName(fields, driverRow, basicDataValues, null, null);
    List<String> parts = new ArrayList<>();
    boolean anyNonEmpty = false;
    for (JsonNode k : rowKeyFields) {
        String field = k.asText("");
        Object v = resolved.get(field);
        if (v == null && driverRow != null) {           // 兜底: driver 列名==字段名 的组件
            JsonNode dv = driverRow.path(field);
            if (!dv.isMissingNode() && !dv.isNull()) v = dv.asText();
        }
        String s = (v == null) ? "" : String.valueOf(v);
        if (!s.isEmpty()) anyNonEmpty = true;
        parts.add(s);
    }
    if (!anyNonEmpty) return null;                       // 全空 → 调用方按行号
    return String.join("||", parts);
}
```

> 注：`resolveRowByFieldName` 已是纯内存解析（只读 driverRow/basicDataValues/content）。`fields==null` 时它返回空 map，computeRowKey 退化为「driverRow[field] 兜底」+ 全空 null，行为安全。

- [ ] **Step 2: 改调用点 :532（computeRows 内，已有 fields + basicDataValues 在作用域）**

把 `String rowKey = computeRowKey(rowKeyFields, driverRow);` 改为：

```java
String rowKey = computeRowKey(rowKeyFields, fields, driverRow, basicDataValues);
```

（`fields` 是 computeRows 形参；`basicDataValues` 是循环内 `baseRow.path("basicDataValues")`，见 :530。）

- [ ] **Step 3: 跑 Task 1 测试确认通过**

Run: `cd cpq-backend && ./mvnw -q -Dtest=FormulaCalculatorTest#computeRowKey_resolvesFieldNameToDriverColumn_viaDefaultSourcePath+computeRowKey_allKeyPartsEmpty_returnsNull_soCallerFallsBackToIndex test`
Expected: PASS（"料9||加工费" + null）。

- [ ] **Step 4: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java
git commit -m "fix(rowkey): computeRowKey 复用 resolveRowByFieldName 解析字段名→驱动列 + 全空回退 (GREEN)"
```

---

## Task 3：后端 — CardSnapshotService / CardEffectiveRows 调用点透传

**Files:**
- Modify: `cpq-backend/.../quotation/service/CardSnapshotService.java:872,919,1400`
- Modify: `cpq-backend/.../quotation/service/card/CardEffectiveRows.java:85,159`

- [ ] **Step 1: 读三处 CardSnapshotService 调用点上下文**

Run: `grep -n "computeRowKey\|fields\|basicDataValues\|driverRow" cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java | sed -n '1,60p'`
对 :872 / :919 / :1400 各确认同作用域可取到 `fields`（组件 fields JsonNode）与 `basicDataValues`（对应 baseRow 的 `basicDataValues` 节点）。

- [ ] **Step 2: 三处改为 4 参**

模式（按各点实际变量名）：
```java
// :872  formulaCalculator.computeRowKey(rowKeyFields, driverRow)
formulaCalculator.computeRowKey(rowKeyFields, fields, driverRow, basicDataValues);
// :919  formulaCalculator.computeRowKey(rkf, br.path("driverRow"))
formulaCalculator.computeRowKey(rkf, fields, br.path("driverRow"), br.path("basicDataValues"));
// :1400 formulaCalculator.computeRowKey(rkf, driverRow)
formulaCalculator.computeRowKey(rkf, fields, driverRow, basicDataValues);
```
若某点作用域缺 `fields`/`basicDataValues`，向上取（snapshot tab 的 fields 节点 / 同 baseRow 的 basicDataValues）。**不得传 null 跳过**——传 null 会让该路径仍塌缩。

- [ ] **Step 3: CardEffectiveRows 私有 computeRowKey(:159) 同款修 + 调用点(:85) 透传**

`CardEffectiveRows.computeRowKey(JsonNode rkf, JsonNode driverRow, int idx)` 改为接收 `fields` + `basicDataValues`，内部解析逻辑对齐 Task 2（此处是 static util，复制等价解析：按 rowKeyFields 先查 basicDataValues[bnfDriverLookupKey(字段 default_source/basic_data_path)]，再 driverRow[字段名]，全空→`String.valueOf(idx)`）。调用点 :85 透传 fields + basicDataValues。

```java
// CardEffectiveRows: 等价解析（无 FormulaCalculator 实例时的 static 版）
private static String computeRowKey(JsonNode rkf, JsonNode fields, JsonNode driverRow, JsonNode bdv, int idx) {
    if (rkf == null || !rkf.isArray() || rkf.size() == 0) return String.valueOf(idx);
    if (rkf.size() == 1 && "__seq_no__".equals(rkf.get(0).asText(""))) return String.valueOf(idx);
    List<String> parts = new ArrayList<>();
    boolean any = false;
    for (JsonNode k : rkf) {
        String field = k.asText("");
        String s = resolveKeyPart(fields, field, driverRow, bdv);   // 见下
        if (!s.isEmpty()) any = true;
        parts.add(s);
    }
    return any ? String.join("||", parts) : String.valueOf(idx);
}
// resolveKeyPart: driverRow[field] 非空? → 否则按 field 的 default_source/basic_data_path
//   取 bdv[bnfDriverLookupKey(path)]; 再 driverRow[lastSeg(path)]; 全无→""。
```

> 实现 `resolveKeyPart` 时若 CardEffectiveRows 已 import FormulaCalculator，可直接调用注入实例的 `computeRowKey` 单字段版；否则就地实现 bnfDriverLookupKey（`"{" + path + "}"`，去重前导无需特殊处理，对齐 FormulaCalculator.bnfDriverLookupKey:1288）。

- [ ] **Step 4: 编译 + 跑既有快照测试**

Run: `cd cpq-backend && ./mvnw -q -Dtest=RefreshCardSnapshotTest,SnapshotReconcileTest,FormulaCalculatorTest test`
Expected: 全 PASS（既有用例不回归）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java cpq-backend/src/main/java/com/cpq/quotation/service/card/CardEffectiveRows.java
git commit -m "fix(rowkey): CardSnapshotService/CardEffectiveRows computeRowKey 透传 fields+basicDataValues"
```

---

## Task 4：后端 computeDedupKey — 同款修 + 测试

**Files:**
- Modify: `cpq-backend/.../quotation/service/FormulaCalculator.java:602`
- Modify: `cpq-backend/.../quotation/service/rowkey/RowKeyUniquenessService.java:61,65`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorComputeDedupKeyTest.java`

- [ ] **Step 1: 失败测试**

```java
@Test
void computeDedupKey_resolvesFieldNameViaDefaultSourcePath() throws Exception {
    JsonNode rkf = om.readTree("[\"料件\",\"要素\"]");
    JsonNode fields = om.readTree("""
        [{"name":"料件","field_type":"INPUT_TEXT","default_source":{"type":"BASIC_DATA","path":"$wgj_view._料件"}},
         {"name":"要素","field_type":"INPUT_TEXT","default_source":{"type":"BASIC_DATA","path":"$wgj_view._要素"}}]""");
    JsonNode driverRow = om.readTree("{\"_料件\":\"料9\",\"_要素\":\"运费\"}");
    JsonNode bdv = om.readTree("{\"{$wgj_view._料件}\":\"料9\",\"{$wgj_view._要素}\":\"运费\"}");
    String k = FormulaCalculator.computeDedupKey(rkf, fields, driverRow, bdv, null);
    assertEquals("料9||运费", k);  // 修复前: 两段空, driverRow[料件]/[要素] 缺 → null 或 "||"
}
```

- [ ] **Step 2: 跑确认失败** → `./mvnw -q -Dtest=FormulaCalculatorComputeDedupKeyTest#computeDedupKey_resolvesFieldNameViaDefaultSourcePath test`，Expected: 编译/断言失败。

- [ ] **Step 3: 实现** — `computeDedupKey(JsonNode rowKeyFields, JsonNode fields, JsonNode driverRow, JsonNode basicDataValues, JsonNode rowValues)`：逐 key 字段 `pickNonEmpty(driverRow, lastSegResolve)` 改为先 `resolveRowByFieldName`-等价解析（driverRow[name]→default_source/basic_data_path→bdv→rowValues[name]），任一非空 `any=true`；全空→null（保持「不参与判重」语义）。

- [ ] **Step 4: 改 RowKeyUniquenessService :61/:65 透传 fields+basicDataValues**（:61 路径有 driverRow+overlay，:65 emptyDriver+mr；按作用域取 cfg 的 fields 与该行 basicDataValues）。

- [ ] **Step 5: 跑通过 + 既有 dedup 测试不回归** → `./mvnw -q -Dtest=FormulaCalculatorComputeDedupKeyTest test`，PASS。

- [ ] **Step 6: 提交** `git commit -m "fix(rowkey): computeDedupKey 同款字段名解析 + RowKeyUniquenessService 透传"`

---

## Task 5：前端 computeRowKey — 失败测试

**Files:**
- Test: `cpq-frontend/src/pages/quotation/useCardSnapshots.test.ts`（新建）

- [ ] **Step 1: 失败测试（Vitest）**

```ts
import { describe, it, expect } from 'vitest';
import { computeRowKey } from './useCardSnapshots';

const fields = [
  { name: '料件', field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$wgj_view._料件' } },
  { name: '要素', field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$wgj_view._要素' } },
];
const driverRow = { _料件: '料9', _要素: '加工费', 费用: 0.05 };
const bdv = { '{$wgj_view._料件}': '料9', '{$wgj_view._要素}': '加工费', '{$wgj_view.费用}': 0.05 };

describe('computeRowKey 字段名→驱动列解析', () => {
  it('用 default_source path 解析字段名, 多 key 不再塌成 ||', () => {
    expect(computeRowKey(fields, ['料件', '要素'], driverRow, 0, bdv)).toBe('料9||加工费');
  });
  it('全部 key 段空 → 回退行号', () => {
    expect(computeRowKey([], ['料件', '要素'], {}, 3, undefined)).toBe('3');
  });
});
```

> 注：新签名定为 `computeRowKey(fields, rowKeyFields, driverRow, rowIndex, basicDataValues?)`，fields 置首参便于与后端阅读对齐；务必同步所有调用点（Task 6）。

- [ ] **Step 2: 跑确认失败** → `cd cpq-frontend && npx vitest run src/pages/quotation/useCardSnapshots.test.ts`，Expected: FAIL（旧签名/得到 "||"）。

- [ ] **Step 3: 提交失败测试** `git commit -m "test(rowkey): 前端 computeRowKey 字段名解析失败用例 (RED)"`

---

## Task 6：前端 computeRowKey — 实现 + 调用点同步

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/useCardSnapshots.ts:52`
- Modify: `cpq-frontend/src/pages/quotation/useCardSnapshots.ts:136,148`（内部调用）
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:1978`
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx:482`

- [ ] **Step 1: 重写 computeRowKey + 新增字段解析 helper**

```ts
/** 把 row_key_field（字段名）解析成值：driverRow[name] 非空优先；否则按字段
 *  default_source / basic_data_path 取 basicDataValues[bnfDriverLookupKey(path)]；
 *  再退 driverRow[path 末段]。文本保留。 */
function resolveRowKeyPart(
  field: any | undefined,
  name: string,
  driverRow: Record<string, any> | undefined,
  basicDataValues: Record<string, any> | undefined,
): string {
  const raw = driverRow?.[name];
  if (raw != null && raw !== '') return String(raw);
  const ds = field?.default_source;
  const path: string | undefined =
    (ds && (ds.type === 'BNF_PATH' || ds.type === 'BASIC_DATA') && ds.path) ? ds.path
    : field?.basic_data_path || undefined;
  const gvCode: string | undefined = (ds && ds.type === 'GLOBAL_VARIABLE' && ds.code) ? ds.code : undefined;
  if (basicDataValues) {
    if (gvCode) {
      const v = basicDataValues[`@gvar:${gvCode}`];
      if (v != null && !(Array.isArray(v) && v.length === 0)) return String(Array.isArray(v) ? v[0] : v);
    }
    if (path) {
      const v = basicDataValues[bnfDriverLookupKey(path)];
      if (v != null && !(Array.isArray(v) && v.length === 0)) return String(Array.isArray(v) ? v[0] : v);
    }
  }
  if (path) {                                  // 末段驱动列兜底（如 _料件）
    const seg = path.includes('.') ? path.slice(path.lastIndexOf('.') + 1) : path;
    const dv = driverRow?.[seg];
    if (dv != null && dv !== '') return String(dv);
  }
  return '';
}

export function computeRowKey(
  fields: any[] | undefined,
  rowKeyFields: string[] | undefined | null,
  driverRow: Record<string, any> | undefined,
  rowIndex: number,
  basicDataValues?: Record<string, any>,
): string {
  if (!rowKeyFields || rowKeyFields.length === 0) return String(rowIndex);
  if (rowKeyFields.length === 1 && rowKeyFields[0] === '__seq_no__') return String(rowIndex);
  const byName = new Map<string, any>((fields ?? []).map((f) => [f.name || f.key, f]));
  let anyNonEmpty = false;
  const parts = rowKeyFields.map((name) => {
    const s = resolveRowKeyPart(byName.get(name), name, driverRow, basicDataValues);
    if (s !== '') anyNonEmpty = true;
    return s;
  });
  return anyNonEmpty ? parts.join('||') : String(rowIndex);  // 全空 → 行号(对齐后端 null→idx)
}
```

> `bnfDriverLookupKey` 已在本文件 import（来自 useDriverExpansions）。若未 import 则补 `import { bnfDriverLookupKey } from './useDriverExpansions';`

- [ ] **Step 2: 改内部调用点 :136 / :148**

```ts
// :136  return computeRowKey(st?.rowKeyFields, driverRow, rowIndex);
return computeRowKey(st?.fields, st?.rowKeyFields, driverRow, rowIndex, vt?.baseRows?.[rowIndex]?.basicDataValues);
// :148  const rk = computeRowKey(st.rowKeyFields, baseRow?.driverRow, rowIndex);
const rk = computeRowKey(st.fields, st.rowKeyFields, baseRow?.driverRow, rowIndex, baseRow?.basicDataValues);
```
（`st` 为 struct tab；确认其含 `fields`。`vt`/`baseRow` 为 value tab 行，含 `basicDataValues`。）

- [ ] **Step 3: 改 QuotationStep2.tsx :1978**

```ts
// const rowKey = useSnapEdit ? computeRowKey(activeRowKeyFields, driverRowForKey, i) : String(i);
const rowKey = useSnapEdit
  ? computeRowKey(activeComponent.fields, activeRowKeyFields, driverRowForKey, i, baseRows[i]?.driverRow ? baseRows[i]?.basicDataValues : undefined)
  : String(i);
```
（取本行 `basicDataValues`：来自 `activeSnap`/`baseRows[i]`；按 :1879 注释处的 baseRows 取值方式对齐。）

- [ ] **Step 4: 改 ReadonlyProductCard.tsx :482**

```ts
// const rowKey = useSnap ? computeRowKey(activeRowKeyFields, driverRowForKey, ri) : String(ri);
const rowKey = useSnap
  ? computeRowKey(activeComponent.fields, activeRowKeyFields, driverRowForKey, ri, baseRowsForKey?.[ri]?.basicDataValues)
  : String(ri);
```

- [ ] **Step 5: TS 编译 + 跑前端测试**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json && npx vitest run src/pages/quotation/useCardSnapshots.test.ts`
Expected: tsc 0 错误；测试 PASS。

- [ ] **Step 6: 改动 .tsx 的 Vite transform 自检**

Run（每个改动 .tsx）：`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx` 与 `.../ReadonlyProductCard.tsx`
Expected: 200。

- [ ] **Step 7: 提交** `git commit -m "fix(rowkey): 前端 computeRowKey 字段名→驱动列解析 + 4 调用点透传 (GREEN)"`

---

## Task 7：前端 computeDedupKey（rowDedup.ts）同款修

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/rowDedup.ts:6,41`
- Test: `cpq-frontend/src/pages/quotation/rowDedup.test.ts`

- [ ] **Step 1: 加失败测试**：`computeDedupKey` 对 `["料件","要素"]` + `_`前缀 driverRow + bdv 期望 `"料9||运费"`（修复前为 null/"||"）。
- [ ] **Step 2: 跑确认失败** → `npx vitest run src/pages/quotation/rowDedup.test.ts`。
- [ ] **Step 3: 实现** — `computeDedupKey(fields, rowKeyFields, driverRow, rowValues, basicDataValues?)`：复用 Step6 的 `resolveRowKeyPart`（导出或复制），逐字段 driverRow[name]→default_source/basic_data_path→bdv→rowValues[name]，全空→null。
- [ ] **Step 4: 改调用点 :41 透传 fields + basicDataValues**（`computeRowKeysForRows` 的入参补 fields/bdv；其上游 `useDriverExpansions` 行含 basicDataValues）。
- [ ] **Step 5: 跑通过** → PASS。
- [ ] **Step 6: 提交** `git commit -m "fix(rowkey): 前端 computeDedupKey 同款字段名解析"`

---

## Task 8：对拍 — 前后端 computeRowKey 一致护栏

**Files:**
- Modify: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorCrossTabTest.java`（或对拍专用类）
- Modify: `cpq-frontend/src/pages/quotation/useCardSnapshots.test.ts`

- [ ] **Step 1: 后端断言** 外购件 fixture（2 key 字段 + `_`前缀驱动列 + 4 行不同 `费用`）逐行 rowKey = `料9||加工费`/`料9||单价`/`料9||运费`/`料9||包装费`（**4 行互不相同**），且 `SUM(外购件.费用)`（cross_tab）= 0.259（0.05+0.2+0.002+0.007），非 0.008。
- [ ] **Step 2: 前端断言** 同 fixture 同 4 个 rowKey 字符串，逐字节等于后端期望（对拍硬编码相同字符串）。
- [ ] **Step 3: 跑两端** → 后端 `./mvnw -q -Dtest=FormulaCalculatorCrossTabTest test`；前端 `npx vitest run src/pages/quotation/useCardSnapshots.test.ts`，均 PASS。
- [ ] **Step 4: 提交** `git commit -m "test(rowkey): 前后端 computeRowKey 对拍 (外购件 2-key _前缀 4 行不塌缩 + SUM=0.259)"`

---

## Task 9：数据清理 stale editRow + 复测 QT-1710（合并后执行）

> 本任务在**合并回 master 后**执行（dev server 8081/5174 服务 master 代码）。worktree 内仅准备 SQL。

- [ ] **Step 1: 定位 stale editRow** — 受影响 line_item 的 `外购件` tab `editRows` 含 `rowKey="||"`（如 QT-1710 li=61213558-...）。修复后新键不再是 `"||"`，该条不再匹配；为干净起见从 quote_card_values 移除（或令用户在 UI 重存触发重算）。

Run（先查影响面，不改）：
```sql
SELECT li.id, q.quotation_number
FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id,
     jsonb_array_elements(li.quote_card_values->'tabs') tab,
     jsonb_array_elements(tab->'editRows') er
WHERE tab->>'tabName'='外购件' AND er->>'rowKey'='||';
```

- [ ] **Step 2: 合并后复测** — 打开 `http://localhost:5174/quotations/60d087c1-8370-445e-879e-751d737ed478/edit` 来料 tab，料9 材料费 = **0.259**（= 0.05+0.2+0.002+0.007；组成用量 1）。F12 Network 或重存后查 `resolvedRows` 外购件 4 行 `费用` 分别为 0.05/0.2/0.002/0.007（不再全 0.002）。

- [ ] **Step 3: E2E（协议级改动强制）** — `cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts e2e/composite-product-flow.spec.ts --reporter=list`，Expected: 全 `passed`，`'加载中' final count = 0`，8 Tab 渲染正常（附截图）。

---

## Self-Review

- **Spec 覆盖：** 根因（字段名/驱动列错配 + "||" 不回退）→ Task 2/6 修解析、全空回退；前后端一致 → Task 8 对拍；dedup 同病 → Task 4/7；所有调用点 → Task 3/6/7；数据残留 + 验收 → Task 9。✓
- **签名一致：** 后端 `computeRowKey(rowKeyFields, fields, driverRow, basicDataValues)`；前端 `computeRowKey(fields, rowKeyFields, driverRow, rowIndex, basicDataValues?)`（参序不同但各自调用点已列全）；`computeDedupKey` 两端均加 fields + basicDataValues。✓
- **无占位：** 核心解析 + 测试均含完整代码；调用点透传给出模式 + 精确行号，子代理按现场作用域取 fields/basicDataValues。✓
- **风险：** 核心模块（FormulaCalculator/快照/前端求值器），AP-40/AP-51 邻域 → 强制 E2E + 对拍；改动期间禁 `git add -A`，只 add 本任务文件。
