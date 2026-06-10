# Plan 2c — `[页签#SUBTOTAL]` 引用按列名显式选 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Excel 模板卡片公式里引用某页签小计时，若该页签有多个小计列，把它们按名字列出来让用户选；引用解析到具体那一列的总计（如 `[投料.材料费小计]`）。老的 `[页签.小计]`（`__subtotal__`）保持 = 各列之和。

**Architecture:** per-column 小计数据**已在管线上游**——Plan 2 Task 3 让 `CardSnapshotService` PASS1 的 `componentSubtotals` 带上 `${cid|code|tabName}#${列名}` 键。2c 把它在值快照里另存 `tab.subtotalByColumn`，贯穿 `CardEffectiveRows` → `CardDataProvider` → `CardFormulaEvaluator` 的读取；`CardRef.field` 用 `__subtotal__:列名` 编码具体列；前端 `CardFormulaDrawer` 加列子选择器。**Excel 卡片公式只在后端求值**（dry-run 走服务端），前端只构建 ref，无前端评估器镜像。

**Tech Stack:** Java 17 / Quarkus（`CardRef` / `CardSnapshotService` / `CardEffectiveRows` / `CardDataProvider` / `CardFormulaEvaluator`）；React + TS（`CardFormulaDrawer.tsx`）。

**关联：** spec 设计 D / §2 结论；承接 Plan 2-核心边界（2c）。

---

## 已核对的既有事实（勿重新发明）

- `CardRef`（`cpq-backend/.../quotation/service/card/CardRef.java`）：`field == "__subtotal__"`（常量 `SUBTOTAL`）表示引用页签小计；`isSubtotal()` = `SUBTOTAL.equals(field)`；`fromMap` 从 `m.get("field")` 读。
- `CardFormulaEvaluator`（`:221-224`）：`if (ref.isSubtotal()) { BigDecimal s = provider.subtotalOf(ref.tab); literal = s != null ? s.toPlainString() : "0"; }`。
- `CardDataProvider`（`:93-97`）：`subtotalOf(tabKey)` = `effSubtotal.get(tabKey)`（effective-rows 路径）或 `resolve(tabKey).subtotal`（持久化路径）。`effSubtotal`（`:29`）来自 `CardEffectiveRows.TabRows.subtotal`（`fromEffectiveRows` `:52`）。
- `CardEffectiveRows`（`:95-97`）：`subtotal = tab.has("subtotal") ? tab.path("subtotal").decimalValue() : null;` → `new TabRows(rows, subtotal)`。`TabRows`（`:20-27`）字段 `rows` + `subtotal`。
- `CardSnapshotService`（`:802-807`）：`Double sub = componentSubtotals.get(cid) ?? get(code) ?? get(tabName); if (sub != null) tabNode.put("subtotal", sub);`。**`componentSubtotals` 已含 per-column 键 `${cid|code|tabName}#${列名}`**（Plan 2 Task 3，`:709-713`）。
- 前端 `CardFormulaDrawer.tsx`：`buildInsertResult`（`:174-181`）`refType==='subtotal'` → `{ placeholder: \`${tabName}.小计\`, ref: { tab: tab.tabKey, field: '__subtotal__' } }`；UI radio `页签小计`（`:790`）；字段 `Select`（`:799-812`，`fieldOptions` 来自所选页签字段）。
- 前端卡片公式无后端 evaluator 镜像（dry-run 走服务端 `dryRunQuotationId`）。

## 边界

- **持久化路径**（`resolve(tabKey).subtotal`，非 effective-rows）无 per-column 快照 → `subtotalOfColumn` 返回 null → evaluator 回退到 tab 各列之和（`subtotalOf`）。即老报价/无值快照场景，`[页签.列名]` 优雅退化为 tab 总和，不报错。
- 单小计列页签：UI 不显示列子选择器，行为同现状（`__subtotal__`）。

---

## File Structure

- Modify 后端 `CardRef.java`：`__subtotal__:列名` 编码 + `subtotalColumn()` 访问器；`isSubtotal()` 改前缀匹配。
- Modify 后端 `CardSnapshotService.java`（`:807` 附近）：写 `tab.subtotalByColumn`。
- Modify 后端 `CardEffectiveRows.java`：`TabRows` 加 `subtotalByColumn`，从快照读。
- Modify 后端 `CardDataProvider.java`：`effSubtotalByColumn` + `subtotalOfColumn(tab, col)`。
- Modify 后端 `CardFormulaEvaluator.java`（`:221`）：有列 → `subtotalOfColumn`，回退 `subtotalOf`。
- Modify 前端 `CardFormulaDrawer.tsx`：列子选择器 + `buildInsertResult` per-column。
- Test 后端 `CardRefSubtotalColumnTest.java`（新，纯 JUnit）。

---

## Task 1: CardRef 支持 `__subtotal__:列名`（TDD）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/card/CardRef.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/card/CardRefSubtotalColumnTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.quotation.service.card;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CardRefSubtotalColumnTest {

    @Test
    void legacySubtotal_noColumn() {
        CardRef r = CardRef.fromMap(Map.of("tab", "c1:1", "field", "__subtotal__"));
        assertTrue(r.isSubtotal());
        assertNull(r.subtotalColumn());
    }

    @Test
    void subtotalWithColumn() {
        CardRef r = CardRef.fromMap(Map.of("tab", "c1:1", "field", "__subtotal__:材料费小计"));
        assertTrue(r.isSubtotal());
        assertEquals("材料费小计", r.subtotalColumn());
    }

    @Test
    void normalField_notSubtotal() {
        CardRef r = CardRef.fromMap(Map.of("tab", "c1:1", "field", "单价"));
        assertFalse(r.isSubtotal());
        assertNull(r.subtotalColumn());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=CardRefSubtotalColumnTest 2>&1 | grep -E "cannot find|Tests run|BUILD" | grep -v Shutdown | head`
Expected: 编译失败 `cannot find symbol ... subtotalColumn`。

- [ ] **Step 3: 改 CardRef**

把 `isSubtotal()`：

```java
    public boolean isSubtotal() { return SUBTOTAL.equals(field); }
```

改为（前缀匹配，兼容裸 `__subtotal__` 与 `__subtotal__:列名`）：

```java
    public boolean isSubtotal() { return field != null && field.startsWith(SUBTOTAL); }

    /** 具体小计列名（`__subtotal__:列名`）；裸 `__subtotal__` 或非小计 → null。 */
    public String subtotalColumn() {
        if (field == null || !field.startsWith(SUBTOTAL)) return null;
        int i = field.indexOf(':');
        return i >= 0 && i + 1 < field.length() ? field.substring(i + 1) : null;
    }
```

> `isAggregateSource()`（`field == null || isBlank`）不受影响；`__subtotal__` 前缀不与中文字段名冲突。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=CardRefSubtotalColumnTest 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -3`
Expected: `Tests run: 3, Failures: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/card/CardRef.java cpq-backend/src/test/java/com/cpq/quotation/service/card/CardRefSubtotalColumnTest.java
git commit -m "feat(subtotal-ref): CardRef 支持 __subtotal__:列名 编码 + TDD"
```

---

## Task 2: 值快照写 `tab.subtotalByColumn`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`（`:802-807`）

- [ ] **Step 1: 在写 subtotal 后追加 subtotalByColumn**

把（`:802-807`）：

```java
        // 值快照带上本 tab 小计（供 Excel CARD_FORMULA 的 __subtotal__ 引用，见 CardEffectiveRows）
        String code = tab.path("componentCode").asText(null);
        Double sub = componentSubtotals.get(cid);
        if (sub == null && code != null) sub = componentSubtotals.get(code);
        if (sub == null) sub = componentSubtotals.get(tab.path("tabName").asText(""));
        if (sub != null) tabNode.put("subtotal", sub);
```

改为：

```java
        // 值快照带上本 tab 小计（供 Excel CARD_FORMULA 的 __subtotal__ 引用，见 CardEffectiveRows）
        String code = tab.path("componentCode").asText(null);
        String tabName = tab.path("tabName").asText("");
        Double sub = componentSubtotals.get(cid);
        if (sub == null && code != null) sub = componentSubtotals.get(code);
        if (sub == null) sub = componentSubtotals.get(tabName);
        if (sub != null) tabNode.put("subtotal", sub);

        // Plan 2c：per-column 小计（供 [页签.列名] 引用）。从 componentSubtotals 的
        // `${cid|code|tabName}#${列名}` 键提取（Plan 2 Task 3 已写入）。
        ObjectNode byColNode = MAPPER.createObjectNode();
        for (String prefix : new String[]{ cid, code, tabName }) {
            if (prefix == null || prefix.isBlank()) continue;
            String keyPrefix = prefix + "#";
            for (Map.Entry<String, Double> en : componentSubtotals.entrySet()) {
                if (en.getKey().startsWith(keyPrefix) && en.getValue() != null) {
                    String col = en.getKey().substring(keyPrefix.length());
                    if (!byColNode.has(col)) byColNode.put(col, en.getValue());
                }
            }
        }
        if (byColNode.size() > 0) tabNode.set("subtotalByColumn", byColNode);
```

> `Map` 已在该文件 import（`componentSubtotals` 即 `Map<String, Double>`）。

- [ ] **Step 2: 编译 + 回归**

Run: `cd cpq-backend && ./mvnw -o test -Dtest='CardSnapshot*' 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -4`
Expected: 全绿 `BUILD SUCCESS`。

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java
git commit -m "feat(subtotal-ref): 值快照写 tab.subtotalByColumn(per-column)"
```

---

## Task 3: 贯穿 TabRows → Provider → Evaluator 的 per-column 读取

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/card/CardEffectiveRows.java`（`TabRows` `:20-27`、读 `:95-97`、filter `:127`）
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/card/CardDataProvider.java`（`:29` 字段、`:44-55` fromEffectiveRows、`:93-97` subtotalOf）
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java`（`:221-224`）

- [ ] **Step 1: TabRows 加 subtotalByColumn**

`CardEffectiveRows.TabRows`（`:20-27`）：

```java
    public static final class TabRows {
        public final List<Map<String, Object>> rows;
        public final BigDecimal subtotal; // 值快照 tab.subtotal；缺失为 null
        public TabRows(List<Map<String, Object>> rows, BigDecimal subtotal) {
            this.rows = rows != null ? rows : List.of();
            this.subtotal = subtotal;
        }
```

改为（加 `subtotalByColumn` + 兼容旧 2 参构造）：

```java
    public static final class TabRows {
        public final List<Map<String, Object>> rows;
        public final BigDecimal subtotal; // 值快照 tab.subtotal；缺失为 null
        public final Map<String, BigDecimal> subtotalByColumn; // Plan 2c：列名→该列总计；缺失为空 Map
        public TabRows(List<Map<String, Object>> rows, BigDecimal subtotal) {
            this(rows, subtotal, java.util.Map.of());
        }
        public TabRows(List<Map<String, Object>> rows, BigDecimal subtotal, Map<String, BigDecimal> subtotalByColumn) {
            this.rows = rows != null ? rows : List.of();
            this.subtotal = subtotal;
            this.subtotalByColumn = subtotalByColumn != null ? subtotalByColumn : java.util.Map.of();
        }
```

- [ ] **Step 2: 从快照读 subtotalByColumn**

把（`:95-97`）：

```java
            BigDecimal subtotal = tab.has("subtotal") && !tab.path("subtotal").isNull()
                    ? tab.path("subtotal").decimalValue() : null;
            out.put(tabKey, new TabRows(rows, subtotal));
```

改为：

```java
            BigDecimal subtotal = tab.has("subtotal") && !tab.path("subtotal").isNull()
                    ? tab.path("subtotal").decimalValue() : null;
            Map<String, BigDecimal> byCol = new java.util.LinkedHashMap<>();
            JsonNode byColNode = tab.path("subtotalByColumn");
            if (byColNode.isObject()) {
                byColNode.fields().forEachRemaining(en -> {
                    if (en.getValue() != null && !en.getValue().isNull())
                        byCol.put(en.getKey(), en.getValue().decimalValue());
                });
            }
            out.put(tabKey, new TabRows(rows, subtotal, byCol));
```

把 filter 路径（`:127`）`new TabRows(kept, e.getValue().subtotal)` 改为 `new TabRows(kept, e.getValue().subtotal, e.getValue().subtotalByColumn)`（保留 per-column）。

- [ ] **Step 3: CardDataProvider 加 effSubtotalByColumn + subtotalOfColumn**

字段（`:29` 后）加：

```java
    private Map<String, Map<String, BigDecimal>> effSubtotalByColumn; // Plan 2c
```

`fromEffectiveRows`（`:46-55`）在 `p.effSubtotal = new HashMap<>();` 后加 `p.effSubtotalByColumn = new HashMap<>();`，并在循环里 `p.effSubtotal.put(...)` 旁加：

```java
                p.effSubtotalByColumn.put(e.getKey(),
                    e.getValue().subtotalByColumn != null ? e.getValue().subtotalByColumn : Map.of());
```

`subtotalOf`（`:93`）后加新方法：

```java
    /** Plan 2c：某页签某小计列的总计；无 per-column 数据（持久化路径/未命中）→ null。 */
    public BigDecimal subtotalOfColumn(String tabKey, String column) {
        if (effSubtotalByColumn == null) return null;
        Map<String, BigDecimal> m = effSubtotalByColumn.get(tabKey);
        return m == null ? null : m.get(column);
    }
```

- [ ] **Step 4: CardFormulaEvaluator 按列解析**

把（`:221-224`）：

```java
            if (ref.isSubtotal()) {
                BigDecimal s = provider.subtotalOf(ref.tab);
                if (s != null) anyNonEmpty[0] = true;
                literal = s != null ? s.toPlainString() : "0";
            } else {
```

改为：

```java
            if (ref.isSubtotal()) {
                // Plan 2c：指定列 → 取该列总计；无列名或 per-column 缺失 → 回退页签各列之和。
                String col = ref.subtotalColumn();
                BigDecimal s = col != null ? provider.subtotalOfColumn(ref.tab, col) : null;
                if (s == null) s = provider.subtotalOf(ref.tab);
                if (s != null) anyNonEmpty[0] = true;
                literal = s != null ? s.toPlainString() : "0";
            } else {
```

- [ ] **Step 5: 编译 + 回归**

Run: `cd cpq-backend && ./mvnw -o test -Dtest='CardSnapshot*,CardRefSubtotalColumnTest,FormulaCalculator*' 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -5`
Expected: 全绿 `BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/card/CardEffectiveRows.java cpq-backend/src/main/java/com/cpq/quotation/service/card/CardDataProvider.java cpq-backend/src/main/java/com/cpq/quotation/service/CardFormulaEvaluator.java
git commit -m "feat(subtotal-ref): 贯穿 TabRows/Provider/Evaluator 的 per-column 小计读取"
```

---

## Task 4: 前端 CardFormulaDrawer 列子选择器

**Files:**
- Modify: `cpq-frontend/src/pages/template/CardFormulaDrawer.tsx`（`buildInsertResult` `:174-181`、UI `:798-812`、state `:288` 附近）

- [ ] **Step 1: 加列选择 state + 派生选项**

在 `refType` state（`:288` `const [refType, setRefType] = useState...`）附近加：

```tsx
  // Plan 2c：所选页签的小计列（field.is_subtotal）；多于 1 列时显示子选择器。
  const [selSubtotalCol, setSelSubtotalCol] = useState<string>('');
```

在 `fieldOptions` 派生处旁（同源 selTab 字段）加派生：

```tsx
  const subtotalColOptions = (selTab?.fields ?? [])
    .filter((f: any) => f.is_subtotal)
    .map((f: any) => ({ label: f.name, value: f.name }));
```

> `selTab` 的字段来源与 `fieldOptions` 同；执行时先读 `fieldOptions` 定义（在本文件内 grep `fieldOptions`）确认 `selTab.fields` 字段路径，按实际取 is_subtotal 列。

- [ ] **Step 2: subtotal 选中且多列时显示子选择器**

在字段 Select（`:798-812`）后追加：

```tsx
              {/* Plan 2c：页签小计 + 多小计列 → 选具体列 */}
              {refType === 'subtotal' && subtotalColOptions.length > 1 && (
                <Form.Item label="小计列">
                  <Select
                    style={{ width: 220 }}
                    placeholder="选具体小计列（不选=各列之和）"
                    allowClear
                    value={selSubtotalCol || undefined}
                    onChange={(v) => setSelSubtotalCol(v ?? '')}
                    options={subtotalColOptions}
                  />
                </Form.Item>
              )}
```

切 refType / 切页签时重置：`setRefType` 的 onChange（`:788`）里加 `setSelSubtotalCol('');`；选页签的 onChange 同样重置（grep `setSelTab` 找到处）。

- [ ] **Step 3: buildInsertResult 按列产出 ref**

把（`:174-181`）：

```tsx
  if (refType === 'subtotal') {
    const placeholder = `${tabName}.小计`;
    return {
      placeholder,
      refKey: placeholder,
      ref: { tab: tab.tabKey, field: '__subtotal__' },
    };
  }
```

改为（buildInsertResult 需能拿到 selSubtotalCol —— 通过新增形参传入；其调用方 `handleInsertRef` 把 `selSubtotalCol` 传进来）：

```tsx
  if (refType === 'subtotal') {
    // Plan 2c：选了具体小计列 → __subtotal__:列名，placeholder 用列名；否则裸 __subtotal__（各列之和）。
    const col = subtotalCol && subtotalCol.trim() ? subtotalCol.trim() : '';
    const placeholder = col ? `${tabName}.${col}` : `${tabName}.小计`;
    return {
      placeholder,
      refKey: placeholder,
      ref: { tab: tab.tabKey, field: col ? `__subtotal__:${col}` : '__subtotal__' },
    };
  }
```

给 `buildInsertResult` 加形参 `subtotalCol: string`（在现有参数表末尾），并在其调用方（`handleInsertRef`，grep `buildInsertResult(` 定位）传入 `selSubtotalCol`。

- [ ] **Step 4: tsc + Vite**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/CardFormulaDrawer.tsx`
Expected: `200`。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/template/CardFormulaDrawer.tsx
git commit -m "feat(subtotal-ref): CardFormulaDrawer 多小计列子选择器 + __subtotal__:列名"
```

---

## Task 5: 集成验证 + 自检

- [ ] **Step 1: 后端回归全绿**

Run: `cd cpq-backend && ./mvnw -o test -Dtest='Card*,FormulaCalculator*,RowKey*' 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -4`
Expected: `BUILD SUCCESS`，无 Failures。

- [ ] **Step 2: 手工验证（Excel 模板多小计列引用）**

前置：一个含 2 小计列（材料费小计/加工费小计）的组件 + Excel 模板。
- 在 Excel 列配置打开 CardFormulaDrawer，选页签小计 → 出现"小计列"子选择器，列出两列；
- 选"材料费小计" → 占位变 `[投料.材料费小计]`，ref.field=`__subtotal__:材料费小计`；
- dry-run 该列 → 值 = 材料费小计列总计（非两列之和）；
- 不选列（清空）→ `[投料.小计]` = 两列之和（回退兼容）。

- [ ] **Step 3: E2E（若改动触达报价渲染才需；本 Plan 仅 Excel 模板 + 快照写入）**

因改了 `CardSnapshotService`（快照写入），按 CLAUDE.md 跑一次报价 E2E 确认快照链路未坏：

```bash
cd cpq-backend && touch src/main/java/com/cpq/quotation/service/CardSnapshotService.java && sleep 9
cd ../cpq-frontend && rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list 2>&1 | tail -8
```
Expected: `1 passed`，`'加载中' final count = 0`。

- [ ] **Step 4: 自检声明（CLAUDE.md 强制）**

形如：
> 后端 `CardRefSubtotalColumnTest` 3 + `Card*`/`FormulaCalculator*` 回归绿 ✅；tsc 0 错误 ✅；CardFormulaDrawer Vite 200 ✅；E2E quotation-flow 1 passed + 加载中=0 ✅；多小计列 Excel 引用选列 dry-run 取单列、不选回退各列之和 ✅。

---

## Self-Review（写后自检）

**Spec coverage：** 多小计列 Excel 引用按列名选 → Task 1（编码）+ Task 2/3（解析链路）+ Task 4（UI 列出供选）；老 `__subtotal__` = 各列之和 → 前缀匹配 + 回退保留。

**Placeholder scan：** Task 4 Step 1/3 含"先读 fieldOptions 定义确认 selTab.fields 路径""grep buildInsertResult 调用方传 selSubtotalCol"——非占位，是该文件内函数定位的明确指引（已给 grep 手段 + 精确改法）。其余步骤完整代码。

**Type consistency：** `subtotalColumn()`（后端）/`subtotalByColumn`（TabRows/Provider/快照键）/`__subtotal__:列名`（CardRef field 编码，前后端一致）/`subtotalOfColumn(tab,col)` 签名贯穿一致。

**向后兼容 + 边界：** 裸 `__subtotal__` 前缀匹配仍 isSubtotal → 各列之和；持久化路径无 per-column → subtotalOfColumn 返 null → evaluator 回退 subtotalOf；单小计列页签 UI 不显示子选择器。均在边界声明。

**复用：** per-column 数据复用 Plan 2 Task 3 已写入 componentSubtotals 的 `#列名` 键，不重复计算。
