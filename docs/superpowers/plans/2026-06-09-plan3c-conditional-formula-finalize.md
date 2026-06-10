# Plan 3c — 条件公式收尾（三视图一致性 + 引用校验 + 硬环检测 + E2E） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收尾条件公式：①保存期把条件公式引用的公式（rules[].formula + default）纳入"必须存在"校验 + 硬环检测（含条件依赖）；②证明三视图（报价编辑 / 核价 / 详情）条件公式渲染一致；③条件公式端到端集成测试。

**Architecture:** 三视图一致性**已由 Plan 3a 达成**——详情/核价视图（`ReadonlyProductCard`）FORMULA 值优先读 snapshot `formulaResults`（后端 `FormulaCalculator` 3a 条件感知冻结），缺时 fallback `computeAllFormulas`（3a 条件感知）；无 `resolveFormula` 单一模式直用绕过。故 3c 以**验证 + 补校验**为主，新增代码集中在保存期校验（`ComponentService` + 复用 `FormulaCalculator` 3a 依赖图做环检测）+ 快照级集成测试。

**Tech Stack:** Java 17 / Quarkus（`ComponentService` / `FormulaCalculator` / `CardSnapshotService`）；E2E Playwright（回归）。

**关联：** spec 设计 A/B/C；承接 Plan 3a（引擎 + 数据契约）+ 3b（UI）。

---

## 已核对的既有事实（勿重新发明）

- `ReadonlyProductCard.tsx`：详情/核价视图 FORMULA 值优先读快照 `formulaResults[rowKey]`（`:213/:242`，后端 3a 条件感知冻结）；缺失 fallback `buildFormulaCache`（`:69-92`）→ `computeAllFormulas`（3a 条件感知）。无 `resolveFormula` 直用（grep 空）。→ **三视图条件渲染已通**，3c 仅验证。
- `ComponentService.validateFormulas(fields, formulas)`（`:437`）：已校验公式名非空/不重复 + `formula_name` 引用存在。**本 Plan 在此加 conditional_formula 引用校验 + 环检测**。
- `FormulaCalculator`（3a 后）：`collectFormulaFields(fields, formulas, assignments)` 识别 conditional；`topoOrder` 已含条件并集依赖（条件树列 ∪ 候选公式列）；运行期对环已兜底（追加尾部不崩）。`new FormulaCalculator()` 可独立构造（Plan 1 验证）。
- `CardSnapshotService` 快照值由 `FormulaCalculator.calculate`/computeRows 产出（3a 条件感知）；`assembleTabsWithFormulaResultsForTest`（`CardSnapshotSubtotalTest` 用）可测快照 formulaResults。
- `CondTreeEvaluator.columns(JsonNode)`（3a，public static）：抽条件树引用列。

---

## File Structure

- Modify: `cpq-backend/.../quotation/service/FormulaCalculator.java`：抽 `buildFormulaDeps` + 加 public `cyclicFormulaNodes(fields, formulas)`（复用 3a 依赖图）。
- Modify: `cpq-backend/.../component/service/ComponentService.java`：`validateFormulas` 加 conditional 引用校验 + 调 `cyclicFormulaNodes` 环检测。
- Test: `cpq-backend/.../component/ComponentServiceConditionalValidationTest.java`（引用缺失/环 抛错）。
- Test: `cpq-backend/.../quotation/service/CardSnapshotConditionalTest.java`（快照 formulaResults 含条件结果 → 详情视图源验证）。
- E2E：复用 `quotation-flow.spec.ts` 回归。

---

## Task 1: 条件公式引用校验（rules[].formula + default 必须存在）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java`（`validateFormulas` `:437`）
- Test: `cpq-backend/src/test/java/com/cpq/component/ComponentServiceConditionalValidationTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cpq.component;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.service.ComponentService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComponentServiceConditionalValidationTest {

    @Inject ComponentService svc;

    private Map<String, Object> formulaField(String name, Object condFormula) {
        return Map.of("name", name, "field_type", "FORMULA", "conditional_formula", condFormula);
    }

    @Test
    void missingRuleFormula_throws() {
        var fields = List.<Map<String, Object>>of(formulaField("加工费", Map.of(
            "rules", List.of(Map.of("when", Map.of("kind", "group", "logic", "and", "children", List.of()), "formula", "不存在的公式")),
            "default", "f_base")));
        var formulas = List.<Map<String, Object>>of(Map.of("name", "f_base", "expression", List.of()));
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.validateFormulas(fields, formulas));
        assertTrue(ex.getMessage().contains("不存在的公式") || ex.getMessage().contains("不存在"), ex.getMessage());
    }

    @Test
    void missingDefaultFormula_throws() {
        var fields = List.<Map<String, Object>>of(formulaField("加工费", Map.of(
            "rules", List.of(Map.of("when", Map.of("kind", "group", "logic", "and", "children", List.of()), "formula", "f_base")),
            "default", "缺失默认")));
        var formulas = List.<Map<String, Object>>of(Map.of("name", "f_base", "expression", List.of()));
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.validateFormulas(fields, formulas));
        assertTrue(ex.getMessage().contains("缺失默认") || ex.getMessage().contains("不存在"), ex.getMessage());
    }

    @Test
    void validConditional_passes() {
        var fields = List.<Map<String, Object>>of(formulaField("加工费", Map.of(
            "rules", List.of(Map.of("when", Map.of("kind", "group", "logic", "and", "children", List.of()), "formula", "f_turn")),
            "default", "f_base")));
        var formulas = List.<Map<String, Object>>of(
            Map.of("name", "f_turn", "expression", List.of()),
            Map.of("name", "f_base", "expression", List.of()));
        assertDoesNotThrow(() -> svc.validateFormulas(fields, formulas));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=ComponentServiceConditionalValidationTest 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -3`
Expected: `missingRuleFormula_throws` / `missingDefaultFormula_throws` 失败（当前 validateFormulas 不校验 conditional 引用）。

- [ ] **Step 3: validateFormulas 加 conditional 引用校验**

在 `validateFormulas` 的 FORMULA 字段循环里（`formula_name` 校验旁），加：

```java
            // Plan 3c：条件公式引用校验 —— rules[].formula + default 必须存在。
            Object cf = field.get("conditional_formula");
            if (cf instanceof Map<?, ?> cfm) {
                Object rules = cfm.get("rules");
                if (rules instanceof java.util.List<?> rl) {
                    for (Object r : rl) {
                        if (r instanceof Map<?, ?> rm) {
                            Object fn = rm.get("formula");
                            if (fn != null && !fn.toString().isBlank() && !formulaNames.contains(fn.toString())) {
                                throw new BusinessException("字段「" + field.get("name") + "」条件规则引用的公式 '" + fn + "' 不存在");
                            }
                        }
                    }
                }
                Object def = cfm.get("default");
                if (def != null && !def.toString().isBlank() && !formulaNames.contains(def.toString())) {
                    throw new BusinessException("字段「" + field.get("name") + "」默认公式 '" + def + "' 不存在");
                }
            }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=ComponentServiceConditionalValidationTest 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -3`
Expected: 3 passed（仍缺环检测的 case 在 Task 2）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java cpq-backend/src/test/java/com/cpq/component/ComponentServiceConditionalValidationTest.java
git commit -m "feat(conditional-formula): 保存期条件公式引用校验(rules/default 必须存在)"
```

---

## Task 2: 硬环检测（含条件依赖，复用 3a 依赖图）

**Files:**
- Modify: `cpq-backend/.../quotation/service/FormulaCalculator.java`（抽 `buildFormulaDeps` + public `cyclicFormulaNodes`）
- Modify: `cpq-backend/.../component/service/ComponentService.java`（`validateFormulas` 末尾调用）
- Test: 追加到 `ComponentServiceConditionalValidationTest`

- [ ] **Step 1: FormulaCalculator 抽依赖图 + 暴露环检测**

把 `topoOrder`（3a 后）的 deps 构建抽成私有方法 `buildFormulaDeps(List<FormulaField> ffs, Set<String> nameSet)`，`topoOrder` 改为调它。新增 public：

```java
    /** Plan 3c：返回构成环的公式字段名（空 = 无环）。复用 3a 并集依赖图（含条件树列）。 */
    public List<String> cyclicFormulaNodes(JsonNode fields, JsonNode formulas) {
        List<FormulaField> ffs = collectFormulaFields(fields, formulas, null);
        java.util.Set<String> nameSet = new java.util.HashSet<>();
        for (FormulaField ff : ffs) nameSet.add(ff.name);
        Map<String, List<String>> deps = buildFormulaDeps(ffs, nameSet);
        // Kahn
        Map<String, Integer> indeg = new LinkedHashMap<>();
        for (FormulaField ff : ffs) indeg.put(ff.name, deps.get(ff.name).size());
        List<String> queue = new ArrayList<>();
        for (FormulaField ff : ffs) if (indeg.get(ff.name) == 0) queue.add(ff.name);
        int emitted = 0;
        while (!queue.isEmpty()) {
            String cur = queue.remove(0); emitted++;
            for (FormulaField ff : ffs) if (deps.get(ff.name).contains(cur)) {
                indeg.put(ff.name, indeg.get(ff.name) - 1);
                if (indeg.get(ff.name) == 0) queue.add(ff.name);
            }
        }
        if (emitted == ffs.size()) return List.of();
        List<String> cyclic = new ArrayList<>();
        for (FormulaField ff : ffs) if (indeg.get(ff.name) > 0) cyclic.add(ff.name);
        return cyclic;
    }
```

`buildFormulaDeps`（从 topoOrder 抽出，逻辑不变 —— 即 3a 的并集依赖）：

```java
    private Map<String, List<String>> buildFormulaDeps(List<FormulaField> formulaFields, java.util.Set<String> nameSet) {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (FormulaField ff : formulaFields) {
            List<String> d = new ArrayList<>();
            if (ff.isConditional()) {
                for (CondRule r : ff.rules) {
                    for (String c : com.cpq.formula.CondTreeEvaluator.columns(r.when)) if (nameSet.contains(c)) d.add(c);
                    addExprFieldDeps(r.expression, nameSet, d);
                }
                addExprFieldDeps(ff.defaultExpression, nameSet, d);
            } else {
                addExprFieldDeps(ff.expression, nameSet, d);
            }
            deps.put(ff.name, d);
        }
        return deps;
    }
```

并把 `topoOrder` 中原 deps 构建块替换为 `Map<String, List<String>> deps = buildFormulaDeps(formulaFields, nameSet);`（保留其后的 Kahn 不变）。

- [ ] **Step 2: ComponentService 调环检测**

`validateFormulas` 末尾（FORMULA 循环后）加：

```java
        // Plan 3c：硬环检测（含条件依赖）。把 fields/formulas 转 JsonNode 复用引擎依赖图。
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        List<String> cyclic = new com.cpq.quotation.service.FormulaCalculator()
            .cyclicFormulaNodes(m.valueToTree(fields), m.valueToTree(formulas));
        if (!cyclic.isEmpty()) {
            throw new BusinessException("公式存在循环引用: " + String.join(", ", cyclic));
        }
```

> 注：`new FormulaCalculator()` 仅用 `cyclicFormulaNodes`（纯 JsonNode 逻辑，无注入依赖），Plan 1 已验证可独立构造。

- [ ] **Step 3: 加环检测测试**

追加到 `ComponentServiceConditionalValidationTest`：

```java
    @Test
    void formulaCycle_throws() {
        // A 公式引用 B，B 公式引用 A（字段名==公式名走名称匹配）。
        var fields = List.<Map<String, Object>>of(
            Map.of("name", "A", "field_type", "FORMULA"),
            Map.of("name", "B", "field_type", "FORMULA"));
        var formulas = List.<Map<String, Object>>of(
            Map.of("name", "A", "expression", List.of(Map.of("type", "field", "value", "B"))),
            Map.of("name", "B", "expression", List.of(Map.of("type", "field", "value", "A"))));
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.validateFormulas(fields, formulas));
        assertTrue(ex.getMessage().contains("循环引用"), ex.getMessage());
    }

    @Test
    void conditionCycle_throws() {
        // A 的条件引用 B(列)，B 的公式引用 A → 并集依赖成环。
        var condA = Map.of(
            "rules", List.of(Map.of("when",
                Map.of("kind", "leaf", "left", "B", "op", "gt", "rhs", Map.of("type", "literal", "value", "1")),
                "formula", "f0")),
            "default", "f0");
        var fields = List.<Map<String, Object>>of(
            Map.of("name", "A", "field_type", "FORMULA", "conditional_formula", condA),
            Map.of("name", "B", "field_type", "FORMULA"));
        var formulas = List.<Map<String, Object>>of(
            Map.of("name", "f0", "expression", List.of()),
            Map.of("name", "B", "expression", List.of(Map.of("type", "field", "value", "A"))));
        BusinessException ex = assertThrows(BusinessException.class, () -> svc.validateFormulas(fields, formulas));
        assertTrue(ex.getMessage().contains("循环引用"), ex.getMessage());
    }
```

- [ ] **Step 4: 跑测试 + 回归**

Run: `cd cpq-backend && ./mvnw -o test -Dtest='ComponentServiceConditionalValidationTest,FormulaCalculator*' 2>&1 | grep -E "Tests run:|BUILD" | grep -v Shutdown | tail -4`
Expected: 全绿（5 验证 + FormulaCalculator 回归不变 —— buildFormulaDeps 抽取不改逻辑）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java cpq-backend/src/test/java/com/cpq/component/ComponentServiceConditionalValidationTest.java
git commit -m "feat(conditional-formula): 保存期硬环检测(含条件依赖,复用引擎依赖图)"
```

---

## Task 3: 三视图一致性 —— 快照条件结果集成测试

**Files:**
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/CardSnapshotConditionalTest.java`

> 详情/核价视图源 = 快照 `formulaResults`。本 Task 证明快照按条件正确冻结条件结果（与编辑视图 computeAllFormulas、Plan 3a 单测同口径），即三视图一致。

- [ ] **Step 1: 写快照条件集成测试（仿 CardSnapshotSubtotalTest）**

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Plan 3c：详情/核价视图源(快照 formulaResults)按条件正确冻结条件公式结果。 */
@QuarkusTest
class CardSnapshotConditionalTest {

    @Inject CardSnapshotService svc;
    private static final ObjectMapper M = new ObjectMapper();

    // 加工费：类型==车削 → 单价*1.2；默认 → 单价。
    private static final String SNAPSHOT = """
        [ { "componentId":"c1", "componentCode":"C1", "tabName":"工序", "componentType":"NORMAL", "sortOrder":1,
            "fields":[
              {"name":"类型","field_type":"INPUT","sort_order":1},
              {"name":"单价","field_type":"INPUT_NUMBER","sort_order":2},
              {"name":"加工费","field_type":"FORMULA","sort_order":3,
               "conditional_formula":{
                 "rules":[{"when":{"kind":"leaf","left":"类型","op":"eq","rhs":{"type":"literal","value":"车削"}},"formula":"f_turn"}],
                 "default":"f_base"}}
            ],
            "formulas":[
              {"name":"f_turn","expression":[{"type":"field","value":"单价"},{"type":"operator","value":"*"},{"type":"number","value":"1.2"}]},
              {"name":"f_base","expression":[{"type":"field","value":"单价"}]}
            ],
            "formula_assignments":[] } ]
        """;

    @Test
    void snapshotFreezesConditionalResult() throws Exception {
        JsonNode snapshot = M.readTree(SNAPSHOT);
        var baseRowsByComp = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.node.ArrayNode>();
        var baseRows = M.createArrayNode();
        // 行：类型=车削、单价=100 → 加工费 = 120
        var r = M.createObjectNode();
        var dr = M.createObjectNode(); dr.put("类型", "车削"); dr.put("单价", 100); r.set("driverRow", dr);
        r.set("basicDataValues", M.createObjectNode());
        baseRows.add(r);
        baseRowsByComp.put("c1", baseRows);

        JsonNode root = M.readTree(svc.assembleTabsWithFormulaResultsForTest(snapshot, baseRowsByComp, null));
        JsonNode tab0 = root.path("tabs").get(0);
        JsonNode fr = tab0.path("formulaResults");
        assertTrue(fr.isArray() && fr.size() >= 1, "应有 formulaResults");
        double 加工费 = fr.get(0).path("values").path("加工费").asDouble();
        assertEquals(120.0, 加工费, 1e-9, "车削行条件命中 f_turn=单价*1.2=120");
    }
}
```

> 注：`assembleTabsWithFormulaResultsForTest` 签名见 `CardSnapshotSubtotalTest`；若该测试桩对 INPUT 字段取值口径不同（driverRow vs basicDataValues），按其约定调整 baseRow（参照 CardSnapshotSubtotalTest 用 basicDataValues 放值）。执行时先读 `CardSnapshotSubtotalTest` 确认取值通道。

- [ ] **Step 2: 跑测试**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=CardSnapshotConditionalTest 2>&1 | grep -E "Tests run:|BUILD|加工费|expected" | grep -v Shutdown | tail -4`
Expected: `Tests run: 1, Failures: 0`（快照冻结 120）。若取值通道不符，按 Step 1 注调整 baseRow 后再跑。

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/test/java/com/cpq/quotation/service/CardSnapshotConditionalTest.java
git commit -m "test(conditional-formula): 快照 formulaResults 按条件冻结(三视图源一致)"
```

---

## Task 4: 三视图前端验证 + E2E + 自检

- [ ] **Step 1: 三视图前端验证（说明 + 检查无绕过）**

确认 `ReadonlyProductCard` 无 `resolveFormula` 单一模式直用、FORMULA 值走 snapshot formulaResults（条件已冻）或 fallback computeAllFormulas（条件感知）：
Run: `grep -rnE "resolveFormula\(|\.formula\.expression" cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx cpq-frontend/src/pages/quotation/components/ComponentCell.tsx`
Expected: 无命中（无单一模式绕过）。→ 详情/核价视图条件渲染与编辑视图一致，无需新增前端代码。

- [ ] **Step 2: E2E 回归（改了 ComponentService/FormulaCalculator 协议级）**

```bash
cd cpq-backend && touch src/main/java/com/cpq/quotation/service/FormulaCalculator.java && sleep 9
cd ../cpq-frontend && rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list 2>&1 | tail -6
```
Expected: `1 passed` + `'加载中'=0`。

- [ ] **Step 3: 自检声明（CLAUDE.md 强制）**

> `ComponentServiceConditionalValidationTest` 5（引用缺失×2/有效/公式环/条件环）+ `CardSnapshotConditionalTest` 1（快照冻 120）+ `FormulaCalculator*` 回归绿 ✅；ReadonlyProductCard 无单一模式绕过（grep 空）✅；E2E quotation-flow 1 passed + 加载中=0 ✅。

---

## Self-Review（写后自检）

**Spec coverage：**
- 保存期默认必填（3a）+ 规则引用存在（Task 1）+ 硬环检测（Task 2）→ 配置完整性闭环 ✅
- 条件引用公式列的环（并集依赖）→ Task 2 `conditionCycle_throws` ✅
- 三视图一致（编辑/核价/详情）→ Task 3 快照集成 + Task 4 Step 1 无绕过验证 ✅

**Placeholder scan：** Task 3 Step 1 "先读 CardSnapshotSubtotalTest 确认取值通道"——是测试桩取值口径核对手段（给了参照 + 调整方向），非占位。其余完整代码。

**Type/复用一致：** 环检测复用 `FormulaCalculator` 3a 依赖图（`buildFormulaDeps` 抽取，topoOrder 与 cyclicFormulaNodes 共用 → 无漂移）；`CondTreeEvaluator.columns` 复用；引用校验与 3a `validateFields` 默认必填互补（一在 validateFields 形态校验、一在 validateFormulas 引用校验）。

**边界：** 无 conditional_formula 字段不触发新校验；运行期 topoOrder 仍对漏网环兜底（双保险）。

**至此 Plan 3（3a 引擎 + 3b UI + 3c 收尾）完成**：条件公式可配（3b）、可算（3a 双引擎）、三视图一致（3a+3c）、保存期校验完整（3a+3c）。整批 multi-subtotal + conditional 工作（Plan 1/2/2b/2c/3a/3b/3c）就绪。
