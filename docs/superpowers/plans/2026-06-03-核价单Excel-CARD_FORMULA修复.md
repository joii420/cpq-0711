# 核价单 Excel 视图 CARD_FORMULA 修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让核价单 Excel 视图能正确计算并渲染核价模板的 CARD_FORMULA 列（A/B/C），并提供公式静态校验强化 + 「试算」预览，帮用户验证公式。

**Architecture:** ① `getExcelView` 增加 `templateIdOverride` 让其能按核价模板算；② `CardDataProvider` 增加 `sortOrder` 回退索引解决"核价模板组件实例 id ≠ 卡片数据组件 id"；③ `validateCardFormula` 强化拦截（含 `[SUM_OVER…]` 外层括号）；④ 新增 dry-run 端点 + 前端「试算」按钮；⑤ 前端 `useBackendExcelRows/LinkedExcelView` 透传各视图的 templateId。

**Tech Stack:** Java 17/Quarkus（JUnit5+Mockito，`cpq-backend/src/test`）；React+AntD+Vite+Vitest；Playwright E2E。

> 设计依据：`docs/superpowers/specs/2026-06-03-核价单Excel视图CARD_FORMULA修复-design.md`
> 已有：`CardDataProvider`(43a397d) / `CardFormulaEvaluator`(1ca2cf2/f91dbaa) / `cardFormula.ts`(84ebbf1) / `ExcelViewService.getExcelView`(quote-only) / `useBackendExcelRows`(0785e11)

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `cpq-backend/.../quotation/service/card/CardDataProvider.java` | Modify | 加 `bySort` 回退索引 + rowsOf/subtotalOf 二级匹配 |
| `cpq-backend/.../quotation/service/ExcelViewService.java` | Modify | `getExcelView(id, templateIdOverride)` + 抽 `buildRows(li列表, columns, templateId,...)` + `dryRun(id, columns, templateId)` |
| `cpq-backend/.../quotation/resource/QuotationResource.java` | Modify | `GET /excel-view?templateId=` + `POST /excel-view/dry-run` |
| `cpq-frontend/src/pages/template/cardFormula.ts` | Modify | `validateCardFormula` 加：聚合外层 `[]` / 括号配平 / 未知函数 |
| `cpq-frontend/src/services/quotationService.ts` | Modify | `getExcelView(id, templateId?)` + `dryRunExcelView(id, body)` |
| `cpq-frontend/src/pages/quotation/useBackendExcelRows.ts` | Modify | 入参加 `templateId`，透传给 getExcelView |
| `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx` | Modify | 新模型分支把本视图 templateId 传 useBackendExcelRows |
| `cpq-frontend/src/pages/template/CardFormulaDrawer.tsx` | Modify | 加「试算」按钮 → dryRunExcelView → 显示值/错误 |
| `cpq-backend/src/test/.../card/*Test.java` + 前端 `cardFormula.test.ts` | Create/Modify | 单测 |

---

### Task 1: CardDataProvider 按 sortOrder 回退匹配（keying 修复）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/card/CardDataProvider.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/card/CardDataProviderFallbackTest.java`

- [ ] **Step 1: 写失败测试**
```java
package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.card.CardDataProvider;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CardDataProviderFallbackTest {
    private QuotationLineComponentData tab(String comp, int sort, String json, String sub) {
        var d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(comp); d.sortOrder = sort; d.rowData = json; d.subtotal = new BigDecimal(sub);
        return d;
    }
    @Test void falls_back_to_sortOrder_when_componentId_differs() {
        // 数据存在 d18ac7e4:2，引用用核价组件 b3359f70:2 → 应按 sortOrder=2 回退命中
        var dataComp = "d18ac7e4-24e9-4f87-867c-6350dd6067fe";
        var refComp  = "b3359f70-f830-40f5-ad0f-938d1ce3970c";
        var p = new CardDataProvider(List.of(tab(dataComp, 2, "[{\"类型\":\"非银点类\",\"含量\":3}]", "9")));
        assertEquals(1, p.rowsOf(refComp + ":2").size());
        assertEquals("非银点类", p.rowsOf(refComp + ":2").get(0).get("类型"));
        assertEquals(new BigDecimal("9"), p.subtotalOf(refComp + ":2"));
    }
    @Test void exact_match_still_preferred() {
        var c = "d18ac7e4-24e9-4f87-867c-6350dd6067fe";
        var p = new CardDataProvider(List.of(tab(c, 2, "[{\"含量\":5}]", "5")));
        assertEquals(new BigDecimal("5"), p.subtotalOf(c + ":2"));
    }
    @Test void unknown_sortOrder_returns_empty() {
        var p = new CardDataProvider(List.of(tab("d18ac7e4-24e9-4f87-867c-6350dd6067fe", 2, "[]", "0")));
        assertTrue(p.rowsOf("b3359f70-f830-40f5-ad0f-938d1ce3970c:9").isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=CardDataProviderFallbackTest`
Expected: FAIL（当前只精确匹配 componentId:sortOrder）

- [ ] **Step 3: 实现 — 加 bySort 索引 + 回退**

在 `CardDataProvider` 内（现有 `byTab` 旁）加：
```java
    private final java.util.Map<Integer, QuotationLineComponentData> bySort = new java.util.HashMap<>();
```
构造函数循环里补：`if (d.sortOrder != null) bySort.putIfAbsent(d.sortOrder, d);`
新增解析回退辅助 + 改 rowsOf/subtotalOf：
```java
    /** tabKey 形如 "componentId:sortOrder"；精确 byTab 命中优先，否则按末段 sortOrder 回退。 */
    private QuotationLineComponentData resolve(String tabKey) {
        QuotationLineComponentData d = byTab.get(tabKey);
        if (d != null) return d;
        int idx = tabKey.lastIndexOf(':');
        if (idx >= 0) {
            try { return bySort.get(Integer.parseInt(tabKey.substring(idx + 1).trim())); }
            catch (NumberFormatException ignore) { return null; }
        }
        return null;
    }

    public List<Map<String, Object>> rowsOf(String tabKey) {
        QuotationLineComponentData d = resolve(tabKey);
        if (d == null || d.rowData == null || d.rowData.isBlank()) return List.of();
        try {
            List<Map<String, Object>> rows =
                MAPPER.readValue(d.rowData, new TypeReference<List<Map<String, Object>>>() {});
            return rows != null ? rows : List.of();
        } catch (Exception e) { return List.of(); }
    }

    public BigDecimal subtotalOf(String tabKey) {
        QuotationLineComponentData d = resolve(tabKey);
        return d == null ? null : d.subtotal;
    }
    public boolean hasTab(String tabKey) { return resolve(tabKey) != null; }
```
（删除原 rowsOf/subtotalOf/hasTab 的旧实现，由上述替换。`MAPPER`/`byTab`/`keyOf` 保留不变。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest='CardDataProviderFallbackTest,CardDataProviderTest'`
Expected: PASS（新 3 + 旧 2）

- [ ] **Step 5: Commit**
```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/card/CardDataProvider.java cpq-backend/src/test/java/com/cpq/quotation/card/CardDataProviderFallbackTest.java
git commit -m "fix(quotation): CardDataProvider 按 sortOrder 回退匹配(核价模板组件实例id≠卡片数据id)"
```

---

### Task 2: ExcelViewService.getExcelView 支持 templateIdOverride

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`（约 399-401）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/card/GetExcelViewCostingIT.java`

**先 Read** `getExcelView`(本 spec 已贴前段) 全文，确认其结构：取 lineItems → templateId=lineItems[0].templateId → 加载 template → parse columns → 逐 li buildRowData → 组 rows。

- [ ] **Step 1: 写失败 IT**（@QuarkusTest + @TestTransaction，造 1 报价单 + 报价模板(无CARD_FORMULA) + 核价模板(含 CARD_FORMULA A 引用某页签小计) + line items + 该页签卡片数据；断言 `getExcelView(id, 核价模板id)` 返回 A 有值，而 `getExcelView(id, null)` 返回报价模板列）
```java
// @QuarkusTest @TestTransaction；造数参照现有 ExcelViewCardFormulaIT。
// 关键断言：
//   var costing = svc.getExcelView(quotId, costingTemplateId);
//   assert costing.columns 含 col_key=A(CARD_FORMULA); costing.rows[0].A != null 且 == 预期小计
//   var quote = svc.getExcelView(quotId, null);
//   assert quote.columns == 报价模板列(不含核价A)
```
> 完整造数按实体必填字段补全（参照 `ExcelViewCardFormulaIT`，commit 8352e50）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=GetExcelViewCostingIT`
Expected: FAIL（`getExcelView` 无 2 参重载）

- [ ] **Step 3: 实现重载**

把现有 `public Map<String,Object> getExcelView(UUID quotationId)` 改为委托：
```java
    public Map<String, Object> getExcelView(UUID quotationId) {
        return getExcelView(quotationId, null);
    }

    /** templateIdOverride 非空时按该模板的 excel_view_config 计算（核价单视图用核价模板）；否则用 lineItems[0].templateId（报价模板，向后兼容）。 */
    public Map<String, Object> getExcelView(UUID quotationId, UUID templateIdOverride) {
        List<QuotationLineItem> lineItems = QuotationLineItem.list("quotationId = ?1 ORDER BY sortOrder ASC", quotationId);
        if (lineItems.isEmpty()) return Map.of("columns", List.of(), "rows", List.of());
        UUID templateId = templateIdOverride != null ? templateIdOverride : lineItems.get(0).templateId;
        Template template = templateId != null ? (Template) Template.findById(templateId) : null;
        if (template == null || template.excelViewConfig == null) return Map.of("columns", List.of(), "rows", List.of());
        // ……以下沿用原方法体（parse columns / 预载模板公式 / 逐 li buildRowData(li, columns, templateId, ...) / 组 rows）……
    }
```
> 注意：原方法体里**所有用到 `templateId` 的地方**（findById / buildRowData 的 templateId 入参 / templateFormulaService.listByTemplate）都改用上面这个解析后的 `templateId`，确保 CARD_FORMULA / 模板公式都按 override 模板算。其余逻辑不动。

`QuotationResource` 端点（约 399-401）改：
```java
    @GET
    @Path("/{id}/excel-view")
    public ApiResponse<Map<String, Object>> getExcelView(@PathParam("id") UUID id,
                                                          @QueryParam("templateId") UUID templateId) {
        return ApiResponse.success(excelViewService.getExcelView(id, templateId));
    }
```

- [ ] **Step 4: 跑测试 + 后端起服自检**

Run:
```bash
cd cpq-backend && ./mvnw test -Dtest=GetExcelViewCostingIT
touch src/main/java/com/cpq/quotation/service/ExcelViewService.java; sleep 7
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8081/api/cpq/quotations/00000000-0000-0000-0000-000000000000/excel-view?templateId=00000000-0000-0000-0000-000000000000"
```
Expected: 测试 PASS；curl 返回 401（鉴权，非 500）

- [ ] **Step 5: Commit**
```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java cpq-backend/src/test/java/com/cpq/quotation/card/GetExcelViewCostingIT.java
git commit -m "feat(quotation): getExcelView 支持 templateId 覆盖(核价单视图按核价模板算 CARD_FORMULA)"
```

---

### Task 3: validateCardFormula 强化（前端 + 拦截 [SUM_OVER…] 外层括号）

**Files:**
- Modify: `cpq-frontend/src/pages/template/cardFormula.ts`
- Modify: `cpq-frontend/src/pages/template/cardFormula.test.ts`

- [ ] **Step 1: 追加失败测试**
```ts
  it('拦截聚合被方括号包裹', () => {
    const errs = validateCardFormula(
      { col_key:'B', formula:"=[SUM_OVER([元素] WHERE c0=='x', c1)]", refs:{ '元素':{tab:'t:2', cols:{c0:'类',c1:'量'}} } } as any,
      ['B'], {});
    expect(errs.some(e => e.includes('聚合'))).toBe(true);
  });
  it('拦截括号不配平', () => {
    const errs = validateCardFormula({ col_key:'A', formula:'=ROUND([投料.小计], 2', refs:{'投料.小计':{tab:'t:0',field:'__subtotal__'}} } as any, ['A'], {});
    expect(errs.some(e => e.includes('括号'))).toBe(true);
  });
  it('拦截未知函数', () => {
    const errs = validateCardFormula({ col_key:'A', formula:'=FOO([投料.小计])', refs:{'投料.小计':{tab:'t:0',field:'__subtotal__'}} } as any, ['A'], {});
    expect(errs.some(e => e.includes('未知函数') || e.includes('FOO'))).toBe(true);
  });
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/template/cardFormula.test.ts`
Expected: FAIL（3 新断言）

- [ ] **Step 3: 在 `validateCardFormula` 末尾(return errs 前)追加检查**
```ts
  // 聚合被方括号包裹：[ ... SUM_OVER(...) ... ]
  if (/\[[^\[\]]*\b(SUM|AVG|COUNT|MIN|MAX)_OVER\s*\(/.test(f)) {
    errs.push('聚合函数不能包在 [] 里。正确写法：SUM_OVER([页签] WHERE 条件, 表达式)');
  }
  // 括号/方括号配平
  const bal = (open: string, close: string) => {
    let n = 0; for (const ch of f) { if (ch === open) n++; else if (ch === close) { n--; if (n < 0) return false; } } return n === 0;
  };
  if (!bal('(', ')')) errs.push('圆括号 ( ) 不配平');
  if (!bal('[', ']')) errs.push('方括号 [ ] 不配平');
  // 未知函数名（白名单外的 标识符( ）
  const FN_WHITELIST = new Set(['IF','ROUND','ABS','SUM_OVER','AVG_OVER','COUNT_OVER','MIN_OVER','MAX_OVER']);
  const fnCall = /([A-Za-z_][A-Za-z0-9_]*)\s*\(/g;
  let fm: RegExpExecArray | null;
  while ((fm = fnCall.exec(f)) !== null) {
    if (!FN_WHITELIST.has(fm[1])) errs.push(`未知函数 ${fm[1]}（支持：IF/ROUND/ABS/SUM_OVER/AVG_OVER/COUNT_OVER/MIN_OVER/MAX_OVER）`);
  }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/template/cardFormula.test.ts`
Expected: PASS（原 5 + 新 3 = 8）

- [ ] **Step 5: Commit**
```bash
git add cpq-frontend/src/pages/template/cardFormula.ts cpq-frontend/src/pages/template/cardFormula.test.ts
git commit -m "feat(template): 公式校验强化(聚合外层括号/括号配平/未知函数)"
```

---

### Task 4: dry-run 后端端点（试算）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java`（加 `dryRun`）
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`（加端点）
- Create: `cpq-backend/src/main/java/com/cpq/quotation/dto/ExcelDryRunRequest.java`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/card/ExcelDryRunIT.java`

**契约**：`POST /api/cpq/quotations/{id}/excel-view/dry-run` body `{ "templateId": "<uuid>", "columns": [<临时列配置,同 excel_view_config 列对象>] }` → 返回 `{ columns, rows }`（与 getExcelView 同形态），用传入 columns（不读模板、不落库）按该报价单逐行算。

- [ ] **Step 1: DTO**
```java
package com.cpq.quotation.dto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
public class ExcelDryRunRequest {
    public UUID templateId;                 // 求值上下文模板(影响 SqlView/模板公式;CARD_FORMULA 不强依赖)
    public List<Map<String, Object>> columns;  // 临时列配置(含 CARD_FORMULA 的 formula/refs)
}
```

- [ ] **Step 2: 写失败 IT**（造同 Task2 数据；调 dryRun 传一个 CARD_FORMULA 列 `=[元素.小计]` → 断言 rows[0] 该列==预期；再传错误公式 `=[不存在.小计]` → 该列为 null/— 不抛异常）
```java
// @QuarkusTest @TestTransaction；svc.dryRun(quotId, columns, templateId) 返回 rows，断言列值。
```

- [ ] **Step 3: 实现 `dryRun`**（复用 getExcelView 的逐行逻辑，但用传入 columns）
```java
    public Map<String, Object> dryRun(UUID quotationId, List<Map<String, Object>> columns, UUID templateId) {
        List<QuotationLineItem> lineItems = QuotationLineItem.list("quotationId = ?1 ORDER BY sortOrder ASC", quotationId);
        if (lineItems.isEmpty() || columns == null || columns.isEmpty())
            return Map.of("columns", columns == null ? List.of() : columns, "rows", List.of());
        Quotation quotation = Quotation.findById(quotationId);
        UUID customerId = quotation != null ? quotation.customerId : null;
        List<TemplateFormulaDTO> tfs = templateId != null ? templateFormulaService.listByTemplate(templateId) : List.of();
        Map<String, TemplateFormulaDTO> byName = new LinkedHashMap<>();
        for (TemplateFormulaDTO t : tfs) byName.put(t.name, t);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QuotationLineItem li : lineItems) {
            Map<String, Object> row = buildRowData(li, columns, templateId, byName, customerId);
            row.put("_lineItemId", li.id.toString());
            rows.add(row);
        }
        return Map.of("columns", columns, "rows", rows);
    }
```
> `buildRowData` 已含 CARD_FORMULA 分支（commit 8352e50），异常被 CardFormulaEvaluator/求值层吞为 null/—，不会 500。

`QuotationResource` 加：
```java
    @POST
    @Path("/{id}/excel-view/dry-run")
    public ApiResponse<Map<String, Object>> dryRunExcelView(@PathParam("id") UUID id,
                                                            com.cpq.quotation.dto.ExcelDryRunRequest req) {
        return ApiResponse.success(excelViewService.dryRun(id, req != null ? req.columns : null,
                                                            req != null ? req.templateId : null));
    }
```

- [ ] **Step 4: 跑测试 + 重启自检**

Run:
```bash
cd cpq-backend && ./mvnw test -Dtest=ExcelDryRunIT
touch src/main/java/com/cpq/quotation/service/ExcelViewService.java; sleep 7
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:8081/api/cpq/quotations/00000000-0000-0000-0000-000000000000/excel-view/dry-run" -H "Content-Type: application/json" -d '{}'
```
Expected: 测试 PASS；curl 401（鉴权，非 500）

- [ ] **Step 5: Commit**
```bash
git add cpq-backend/src/main/java/com/cpq/quotation/dto/ExcelDryRunRequest.java cpq-backend/src/main/java/com/cpq/quotation/service/ExcelViewService.java cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java cpq-backend/src/test/java/com/cpq/quotation/card/ExcelDryRunIT.java
git commit -m "feat(quotation): Excel 公式试算 dry-run 端点(按临时列配置算,不落库)"
```

---

### Task 5: 前端透传 templateId（核价视图按核价模板取数）

**Files:**
- Modify: `cpq-frontend/src/services/quotationService.ts`（约 163）
- Modify: `cpq-frontend/src/pages/quotation/useBackendExcelRows.ts`
- Modify: `cpq-frontend/src/pages/quotation/LinkedExcelView.tsx`

- [ ] **Step 1: 改 service**
`quotationService.getExcelView` 改签名（向后兼容）：
```ts
  getExcelView: (id: string, templateId?: string) =>
    api.get(`/quotations/${id}/excel-view`, { params: templateId ? { templateId } : undefined }) as Promise<any>,
  dryRunExcelView: (id: string, body: { templateId?: string; columns: any[] }) =>
    api.post(`/quotations/${id}/excel-view/dry-run`, body) as Promise<any>,
```

- [ ] **Step 2: useBackendExcelRows 加 templateId 入参**
`UseBackendExcelRowsParams` 加 `templateId?: string | null;`；effect 里 `quotationService.getExcelView(quotationId, templateId || undefined)`；deps 加 `templateId`。

- [ ] **Step 3: LinkedExcelView 透传**
新模型分支调用 `useBackendExcelRows({ quotationId, lineItems, enabled: useBackend, templateId: templateId ?? linkedTemplateId ?? null })`。
（`templateId` prop：报价视图=报价模板、核价视图=`costingCardTemplateId`，已由 QuotationStep2 的两处 callsite 传入 — Task5.5 核对。）

- [ ] **Step 3.5: 核对 QuotationStep2 两处 callsite 都传了正确 templateId**
报价视图(约 2272)`templateId={customerTemplateId || null}`、核价视图(约 2213)`templateId={costingCardTemplateId || null}` —— 已在前序提交存在，确认未丢。

- [ ] **Step 4: 自检**
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/LinkedExcelView.tsx
```
Expected: tsc 0；Vite 200

- [ ] **Step 5: Commit**
```bash
git add cpq-frontend/src/services/quotationService.ts cpq-frontend/src/pages/quotation/useBackendExcelRows.ts cpq-frontend/src/pages/quotation/LinkedExcelView.tsx
git commit -m "feat(quotation): Excel 视图按各自模板取数(核价视图传核价模板id给 getExcelView)"
```

---

### Task 6: CardFormulaDrawer 「试算」按钮

**Files:**
- Modify: `cpq-frontend/src/pages/template/CardFormulaDrawer.tsx`

**说明**：试算需要一个"上下文报价单 id"来算真实数据。CardFormulaDrawer 当前在模板配置页（无报价单上下文）。方案：drawer 加可选 prop `dryRunQuotationId?: string`；有则显示「试算」按钮，点了调 `quotationService.dryRunExcelView(dryRunQuotationId, { templateId, columns:[当前列] })` 显示各行值/错误；无则按钮禁用并提示"试算需在带样例报价单的入口打开"。本任务先把按钮 + 调用 + 结果展示做好（入口传 id 由后续按需接）。

- [ ] **Step 1: 实现**（在 props 加 `dryRunQuotationId?`；底部加「试算」Button；点了 setTrialResult；用 Alert/Table 展示 rows 各行该 col_key 值或 message.error 错误）
```tsx
// props 增加: dryRunQuotationId?: string;
// 顶部 import: import { quotationService } from '../../services/quotationService';
// state: const [trial, setTrial] = useState<{loading?:boolean; rows?:any[]; err?:string}>({});
const handleTrial = async () => {
  const errs = validateCardFormula({ col_key: value.col_key, formula, refs }, allColKeys, { ...allFormulas, [value.col_key]: formula });
  if (errs.length) { message.error('请先修正：' + errs.join('；')); return; }
  if (!dryRunQuotationId) { message.warning('当前入口无样例报价单，无法试算'); return; }
  setTrial({ loading: true });
  try {
    const col = { col_key: value.col_key, title: value.title, source_type: 'CARD_FORMULA', formula, refs };
    const resp: any = await quotationService.dryRunExcelView(dryRunQuotationId, { templateId, columns: [col] });
    const body = resp?.data ?? resp;
    setTrial({ rows: Array.isArray(body?.rows) ? body.rows : [] });
  } catch (e: any) { setTrial({ err: e?.message || '试算失败' }); }
};
// 渲染：底部「试算」Button(onClick=handleTrial, loading=trial.loading, disabled=!dryRunQuotationId)
//      + trial.rows && 展示每行 row[value.col_key]（null→「—」）；trial.err && <Alert type="error">
```

- [ ] **Step 2: 自检**
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/CardFormulaDrawer.tsx
```
Expected: tsc 0；Vite 200

- [ ] **Step 3: Commit**
```bash
git add cpq-frontend/src/pages/template/CardFormulaDrawer.tsx
git commit -m "feat(template): 编辑公式抽屉加「试算」按钮(dry-run 预览各行值/错误)"
```

---

### Task 7: E2E — 核价单 Excel 视图 A/B/C 出值（非破坏性）

**Files:**
- Create: `cpq-frontend/e2e/costing-card-formula.spec.ts`

**沿用 `card-formula-flow.spec.ts` 非破坏范式**（注入-还原-页签数守卫；不改 template_id；不删组件数据）：
- 目标：一个绑定了核价模板的报价单（如 QT-20260603-1528，或新造干净夹具），核价模板临时配 A=`[元素.小计]` / B=`SUM_OVER([元素] WHERE c0=='非银点类', c1)` / C=`=[A]+[B]`（psql 注入核价模板 excel_view_config，存原值，afterAll 还原）。
- API 断言：`GET /quotations/{id}/excel-view?templateId=<核价模板>` 返回 A/B/C 有值（按该报价单元素页签数据算）。
- UI 断言：loginAsAdmin → `/quotations/{id}/edit` → 点「核价单」tab → 「Excel 视图」→ A/B/C 非「—」、加载中=0。
- 守卫：前后页签数不变。

- [ ] **Step 1: 写 spec**（参照 `card-formula-flow.spec.ts` commit 385a560；先 psql 查目标报价单核价模板 id + 元素页签 sortOrder + 期望值）
- [ ] **Step 2: 跑 E2E**
```bash
cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/costing-card-formula.spec.ts --reporter=list
```
Expected: passed；A/B/C 出值；页签数守卫一致
- [ ] **Step 3: RECORD.md 追加 + Commit**
```bash
git add cpq-frontend/e2e/costing-card-formula.spec.ts docs/RECORD.md
git commit -m "test(quotation): 核价单 Excel 视图 CARD_FORMULA 出值 E2E(非破坏)"
```

---

## Self-Review（计划 vs spec）
- **1 Spec 覆盖**：修复A=Task2+Task5；修复B(keying)=Task1；修复C(校验)=Task3，(试算)=Task4+Task6，(语法提示)=已存在的帮助块（前序提交）；验收=各 Task 单测 + Task7 E2E ✅
- **2 占位符扫描**：Task2/4/7 的 IT/E2E 造数标注"参照现有 IT/spec 补全实体必填字段"——与既有计划同口径（落库造数依赖实体字段）；纯逻辑(Task1/3)与端点/前端(Task2/4/5/6)均给完整代码 ✅
- **3 类型一致**：`getExcelView(id, templateIdOverride)` / `dryRun(id, columns, templateId)` / `ExcelDryRunRequest{templateId,columns}` / `getExcelView(id, templateId?)` / `dryRunExcelView(id, {templateId,columns})` / `useBackendExcelRows({...,templateId})` 前后一致 ✅
- **4 实现期先 Read**：`getExcelView` 原方法体、`CardDataProvider` 现状、`validateCardFormula` 现状、`QuotationResource` 端点、`LinkedExcelView` 新模型分支、QuotationStep2 两处 callsite。
