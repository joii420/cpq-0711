# cross_tab 公式列 列小计恒 0 修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 cross_tab 公式列（如 来料.材料费 = `SUM([元素…]) + SUM([外购件.费用])`）的**列小计恒 0** 缺陷：列小计在 PASS1 计算（crossTabRows 尚未构建），cross_tab token 因无源数据返 0，每行按 0 累加 → 小计 0；而每行显示走 PASS2（有 crossTabRows）算出正确值（如 0.259），二者割裂。

**Architecture:** 列小计不再在 PASS1 脱离 crossTabRows 重算，而是**在 PASS2（crossTabRows/resolvedRows 构建完成）后，从 resolvedRows 的 is_subtotal 列直接求和**回填。前端在 `buildCrossTabRows` 的拓扑序循环内、每算完一个组件即把其 is_subtotal 列小计写回传入的 `allComponentSubtotals`（按引用）；后端在 `CardSnapshotService` PASS2 循环内、`resolved` 构建后回填 `componentSubtotals`。拓扑序保证下游组件引用上游小计时已是修正值。保证「列小计 == 该列各显示行之和」，无割裂。

**Tech Stack:** React+TS / Vitest（`QuotationStep2.tsx` `buildCrossTabRows` + `ReadonlyProductCard.tsx`）；Java/Quarkus（`CardSnapshotService.java` PASS2）。

**复现基线（QT-20260613-1710, li=61213558-b269-4dfb-937b-07cac220280f）：** 来料.材料费 每行已正确（料9=0.259），但列小计显示 0。修复目标：列小计 = Σ各行材料费（料9 行 0.259 + 其余行）。

---

## 根因（已确认，前后端同病）

- 前端 `QuotationStep2.tsx` PASS1（~:1589-1614）逐组件调 `computeTabSubtotalsByColumn`→`allComponentSubtotals[cid#col]`；此时 crossTabRows 未建（:1618 才建）。`computeTabSubtotalsByColumn` 内（:923-928）调 `computeAllFormulas(..., undefined /*crossTabRows*/, ...)` → cross_tab token 返 0 → cross_tab 列小计 0。底部小计读 `allComponentSubtotals[cid#col]`（:2250）= 0。
- 后端 `CardSnapshotService.java` PASS1（:708-733）调 `formulaCalculator.computeTabSubtotalsByColumn(...)`（7 参，无 crossTabRows）→ `componentSubtotals[cid#col]`=0；PASS2（:765-783）才建 crossTabRows + `calculate`(10 参) 出正确 `resolved`；`buildTabNode`（:834-845）的 `subtotalByColumn` 取自 PASS1 的 `componentSubtotals#col` → 0。

---

## File Structure

- `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` — `buildCrossTabRows`（:821）拓扑序循环内回填 is_subtotal 列小计到 `allComponentSubtotals`；新增内部 helper `subtotalsFromResolvedRows`。
- `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` — 确认 buildCrossTabRows(:325) 调用后再读 `allComponentSubtotals`（必要时调整顺序）。
- `cpq-backend/.../quotation/service/CardSnapshotService.java` — PASS2 循环（:765-783）`resolved` 后回填 `componentSubtotals`。
- 测试：前端 `buildCrossTabRows` 新测试（cross_tab 列小计）；后端 `CardSnapshotService`/快照测试断言 `subtotalByColumn[材料费]≠0`。

**不变量：** 列小计 == 该 is_subtotal 列各 resolvedRow 值之和（与每行显示完全一致，前后端逐字节对齐 subtotalByColumn）。

---

## Task 1：前端 — buildCrossTabRows 回填列小计（失败测试 RED）

**Files:**
- Test: `cpq-frontend/src/pages/quotation/buildCrossTabRows.test.ts`（新建）

- [ ] **Step 1: 失败测试**

构造最小 componentData：一个源组件「外购件」(componentId='wgj', 4 行 driver, 费用列 INPUT_NUMBER default_source `$wgj_view.费用` 值 0.05/0.2/0.002/0.007, row_key `_料件`/`_要素` 略) + 一个宿主组件「来料」(componentId='ll', 1 行 料9, 含 is_subtotal FORMULA 列 材料费 = cross_tab `SUM([外购件.费用])` match 料件)。给 lookupExpansion 返回各组件 driver 展开。调用：
```ts
const allComponentSubtotals: Record<string, number> = {};
const store = buildCrossTabRows(componentData, allComponentSubtotals, '料9', lookupExpansion);
// 断言: 来料 材料费 列小计被回填
expect(allComponentSubtotals['ll#材料费']).toBeCloseTo(0.259, 3);   // 修复前: undefined / 0
expect(allComponentSubtotals['ll']).toBeCloseTo(0.259, 3);
```
（若构造完整 driver fixture 过重，可用 1 行来料 + 外购件求和=0.259 的最小集；关键是断言 `#材料费` 被回填为非 0 = 各行材料费之和。）

- [ ] **Step 2: 跑确认失败** → `cd cpq-frontend && npx vitest run src/pages/quotation/buildCrossTabRows.test.ts`，Expected: FAIL（`ll#材料费` 未被回填/为 undefined）。

- [ ] **Step 3: 提交** `git commit -m "test(subtotal): buildCrossTabRows 回填 cross_tab 列小计失败用例 (RED)"`

---

## Task 2：前端 — buildCrossTabRows 实现回填（GREEN）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`buildCrossTabRows` :821-859 + 新 helper）

- [ ] **Step 1: 新增 helper（按 is_subtotal 列对 resolvedRows 求和）**

在 `buildCrossTabRows` 上方加：
```ts
/** 从一组 resolvedRows 按组件 is_subtotal 列求和。列小计 = 该列各行值之和（与显示行一致）。 */
function subtotalsFromResolvedRows(
  comp: ComponentDataItem,
  rows: Array<Record<string, any>>,
): Record<string, number> {
  const out: Record<string, number> = {};
  const subtotalFields = (comp.fields ?? []).filter(f => (f as any).is_subtotal);
  for (const sf of subtotalFields) {
    const name = sf.name || sf.key || '';
    if (!name) continue;
    let s = 0;
    for (const r of rows) { const v = Number(r?.[name]); if (!isNaN(v)) s += v; }
    out[name] = s;
  }
  return out;
}
```

- [ ] **Step 2: 在 buildCrossTabRows 拓扑循环内回填 allComponentSubtotals**

在 `store[cid] = rows;`（:854）之后、同组件作用域内加：
```ts
// 列小计回填: resolvedRows 已含每行正确(含 cross_tab)的 is_subtotal 列值;
// 从中求和覆盖 PASS1 脱离 crossTabRows 算出的 0。拓扑序 → 下游引用上游小计时已修正。
const byCol = subtotalsFromResolvedRows(comp, rows);
if (Object.keys(byCol).length > 0) {
  let tot = 0;
  for (const [colName, colVal] of Object.entries(byCol)) {
    tot += colVal;
    if (comp.componentId) allComponentSubtotals[`${comp.componentId}#${colName}`] = colVal;
    if (comp.componentCode) allComponentSubtotals[`${comp.componentCode}#${colName}`] = colVal;
    allComponentSubtotals[`${comp.tabName}#${colName}`] = colVal;
  }
  if (comp.componentId) allComponentSubtotals[comp.componentId] = tot;
  if (comp.componentCode) allComponentSubtotals[comp.componentCode] = tot;
  allComponentSubtotals[comp.tabName] = tot;
}
```
> `allComponentSubtotals` 是传入参数（按引用）；此处 mutate 使调用方 PASS1 后读到修正值。在 `buildCrossTabRows` 文档注释补一行：「副作用：按 resolvedRows 回填各 NORMAL 组件 is_subtotal 列小计到传入的 allComponentSubtotals（覆盖 PASS1 的 cross_tab 0 值）」。

- [ ] **Step 3: 跑 Task1 测试 GREEN + 既有 buildCrossTabRows/相关 vitest 不回归**

Run: `npx vitest run src/pages/quotation/buildCrossTabRows.test.ts src/pages/quotation/useCardSnapshots.test.ts src/pages/quotation/rowDedup.test.ts`
Expected: 全 PASS。

- [ ] **Step 4: 校验 QuotationStep2 footer 读取顺序**

确认 footer 小计读 `allComponentSubtotals[#col]`（:2250-2252）发生在 `buildCrossTabRows`(:1618) **之后**（render 阶段，:1618 在组件函数体顶部，:2250 在 JSX —— 天然在后）。无需改顺序则记录确认；若 PASS1 的 `allComponentSubtotals` 在 :1604 也写了 `comp` 总小计(可能非0?)，确认 :854 回填会覆盖。

- [ ] **Step 5: tsc + Vite 200**

Run: `npx tsc --noEmit -p tsconfig.json`（0 错误）；`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx`（200）。

- [ ] **Step 6: 提交** `git commit -m "fix(subtotal): buildCrossTabRows 从 resolvedRows 回填 cross_tab 列小计 (GREEN)"`

---

## Task 3：前端 — ReadonlyProductCard（核价/详情视图）对齐

**Files:**
- Modify/确认: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`（:311 小计 / :325 buildCrossTabRows）

- [ ] **Step 1: 读 :300-340 确认结构**

确认 ReadonlyProductCard 是否也有「PASS1 算 allComponentSubtotals → :325 buildCrossTabRows」结构，且小计显示读 allComponentSubtotals。

- [ ] **Step 2: 确保读取在 buildCrossTabRows 之后**

由于 Task2 让 buildCrossTabRows mutate allComponentSubtotals，只要 ReadonlyProductCard 在 :325 调用后才读 `allComponentSubtotals[#col]` 显示小计，即自动修正。若读取发生在 :325 之前（顺序倒置），把 buildCrossTabRows 调用上移到小计读取之前，或把小计读取下移。**不引入第二套求和逻辑**（DRY，复用 buildCrossTabRows 回填）。

- [ ] **Step 3: tsc + Vite 200**

Run: `npx tsc --noEmit -p tsconfig.json`；`curl ... /src/pages/quotation/ReadonlyProductCard.tsx`（200）。

- [ ] **Step 4: 提交** `git commit -m "fix(subtotal): ReadonlyProductCard 列小计读取对齐 buildCrossTabRows 回填后"`

---

## Task 4：后端 — CardSnapshotService PASS2 回填 componentSubtotals（含失败测试）

**Files:**
- Modify: `cpq-backend/.../quotation/service/CardSnapshotService.java`（PASS2 循环 :765-783）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/RefreshCardSnapshotTest.java`（或快照测试类）

- [ ] **Step 1: 失败测试**

构造含 cross_tab is_subtotal 列的快照（来料 材料费 = `SUM([外购件.费用])`，外购件源行费用合计 0.259），跑 `assembleSnapshot`/`refresh` 路径，断言来料 tab 的 `subtotalByColumn["材料费"]` ≈ 0.259（修复前 0）。若已有相近 fixture（外购件/来料）复用之。

- [ ] **Step 2: 跑确认失败** → `cd cpq-backend && ./mvnw -q -Dtest=RefreshCardSnapshotTest#<新测试> test`，Expected: 断言 0 ≠ 0.259 失败。

- [ ] **Step 3: 实现 — PASS2 循环内回填**

在 PASS2 循环（:765-783）`List<Map<String,Object>> resolved = buildResolvedRows(...)`（:775）之后、`buildTabNode`（:781）之前加：从 `resolved` 按该 tab 的 is_subtotal 列求和，回填 `componentSubtotals`：
```java
// 列小计回填: resolved 已含每行正确(含 cross_tab)的 is_subtotal 列值; 从中求和覆盖 PASS1 的 0。
// 拓扑序 → 下游 component_subtotal token 引用本组件小计时已修正。
java.util.Map<String, Double> byColResolved = subtotalsFromResolved(tab.path("fields"), resolved);
double subResolved = 0.0;
for (Double v : byColResolved.values()) subResolved += v;
String codeR = tab.path("componentCode").asText(null);
String tabNameR = tab.path("tabName").asText("");
if (!cid.isBlank()) componentSubtotals.put(cid, subResolved);
if (codeR != null && !codeR.isBlank()) componentSubtotals.put(codeR, subResolved);
componentSubtotals.put(tabNameR, subResolved);
for (java.util.Map.Entry<String,Double> e : byColResolved.entrySet()) {
    if (!cid.isBlank()) componentSubtotals.put(cid + "#" + e.getKey(), e.getValue());
    if (codeR != null && !codeR.isBlank()) componentSubtotals.put(codeR + "#" + e.getKey(), e.getValue());
    componentSubtotals.put(tabNameR + "#" + e.getKey(), e.getValue());
}
```
新增私有 helper：
```java
/** 按 is_subtotal 列对 resolvedRows 求和（列小计 = 该列各行值之和，与前端 subtotalsFromResolvedRows 对齐）。 */
private java.util.Map<String, Double> subtotalsFromResolved(JsonNode fields, List<Map<String,Object>> rows) {
    java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
    if (fields == null || !fields.isArray()) return out;
    for (JsonNode f : fields) {
        if (!f.path("is_subtotal").asBoolean(false)) continue;
        String name = f.path("name").asText(f.path("key").asText(""));
        if (name.isEmpty()) continue;
        double s = 0.0;
        for (Map<String,Object> r : rows) {
            Object v = r.get(name);
            if (v instanceof Number) s += ((Number) v).doubleValue();
            else if (v != null) { try { s += Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException ignore) {} }
        }
        out.put(name, s);
    }
    return out;
}
```
> `buildTabNode`（:834-845）已从 `componentSubtotals#col` 取 `subtotalByColumn` —— 回填后即自动写出正确值，buildTabNode 无需改。

- [ ] **Step 4: 跑 GREEN + 既有快照测试不回归** → `./mvnw -q -Dtest=RefreshCardSnapshotTest,SnapshotReconcileTest,FormulaCalculatorTest,CardEffectiveRowsTest test`，全 PASS。

- [ ] **Step 5: 提交** `git commit -m "fix(subtotal): CardSnapshotService PASS2 从 resolved 回填 cross_tab 列小计 (TDD)"`

---

## Task 5：对拍 + E2E（前后端列小计一致 + 渲染无回归）

**Files:**
- Modify: 前端 `buildCrossTabRows.test.ts` / 后端快照测试 — 同一外购件 fixture（费用 0.05/0.2/0.002/0.007）断言列小计两端都 = 0.259。

- [ ] **Step 1: 对拍断言** 前端 `allComponentSubtotals['ll#材料费']` 与后端 `subtotalByColumn["材料费"]` 同 fixture 都 ≈ 0.259（数值一致）。
- [ ] **Step 2: 跑两端** → 前端 vitest + 后端 mvnw 相关类，全 PASS。
- [ ] **Step 3:（合并后）E2E** — `cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list`，Expected: `1 passed`，`加载中 final=0`，8 Tab 加载中=0，PUT/draft=0（渲染管线无回归）。
- [ ] **Step 4:（合并后）真机复核** — 打开 QT-1710 来料 tab，材料费列**小计 = Σ各行材料费**（含料9 行 0.259），与各行显示之和一致。

---

## Self-Review

- **Spec 覆盖**：根因（PASS1 列小计无 crossTabRows）→ 前端 buildCrossTabRows 回填(T2) + 后端 PASS2 回填(T4)；三视图（报价 edit T2 / 详情·核价 ReadonlyProductCard T3 / 后端快照 T4）；对拍+E2E(T5)。✓
- **DRY**：列小计统一从 resolvedRows 求和（前后端各一 helper，口径一致），不再 PASS1 脱离 crossTabRows 重算；保证「小计==显示行之和」。✓
- **拓扑序正确性**：回填发生在每组件 resolved 构建后、按拓扑序，下游 component_subtotal token 引用上游小计时已修正。✓
- **签名/契约**：`buildCrossTabRows` 增加「mutate allComponentSubtotals 回填列小计」副作用，已在注释声明；现有调用点（QuotationStep2 :1618 / ReadonlyProductCard :325）均在读取小计前调用（T2/T3 校验）。✓
- **范围外/限制**：若某组件 per-row 公式通过 component_subtotal token 引用「另一 cross_tab 列的小计」且二者构成环 → 模板保存层已拦环；非环但跨拓扑的二阶引用由拓扑序回填覆盖。无新增循环风险。
- **RECORD.md 纪律**：子代理**不提交 docs/RECORD.md**（避免上轮的合并并集麻烦）；记录由主线合并后追加到主工作区未提交。
