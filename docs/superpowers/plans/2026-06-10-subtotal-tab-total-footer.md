# 报价小计体系调整（页签总计 footer + 产品小计默认求和 + 解除小计组件强制）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 卡片底部小计条由"每列一条"改为"每页签一条 `页签名 · 总计`"（三视图一致），产品小计在无 SUBTOTAL 组件时默认 = 各页签总计之和（修三重累加 bug），并解除"模板必须配小计组件"的发布强制。

**Architecture:** 抽一个纯函数 `buildTabTotalLines(componentData, subtotalMap)` 供编辑页 + 详情/核价页底部共用；修 `computeProductSubtotal` 最终兜底的多键三重累加；删 `TemplateService.publish` 的"必须配小计"throw；同步引导文案。产品小计纯前端（后端 `calculateProductSubtotal` 是死代码，不动）。

**Tech Stack:** React + TypeScript / Vitest / Playwright；Java 17 / Quarkus / JUnit 5。

**关联 spec：** `docs/superpowers/specs/2026-06-10-subtotal-tab-total-footer-design.md`（已评审 + §6 验证项已用代码核实定案）。

**已核实事实（勿重新发明）：**
- 编辑页底部多小计条：`QuotationStep2.tsx:2198-2228`（遍历每个非 SUBTOTAL 组件的每个 `is_subtotal` 字段各出一条 `comp.tabName · f.name`，值 `allComponentSubtotals[`${comp.componentCode}#${f.name}`] ?? allComponentSubtotals[`${comp.tabName}#${f.name}`] ?? 0`；末尾产品小计走 `computeProductSubtotal`）。`allComponentSubtotals` 在该作用域可用。
- `computeProductSubtotal`（`QuotationStep2.tsx:885-957`，导出，编辑页 + 详情页 `ReadonlyProductCard:337` 共用）：`componentSubtotals` 按 componentId/componentCode/tabName **3 键存同一值**（`:913-915`）；最终兜底 `Object.values(componentSubtotals).reduce(+)`（`:955-956`）→ **每组件重复累加 ~3 次**。SUBTOTAL 组件公式路径在 `:918-937`，命中即 return，不到兜底。
- 详情页底部：`ReadonlyProductCard.tsx:629-634` 仅单条「产品小计」。其 `compSubtotals`（`:273-310`）用与编辑页 `allComponentSubtotals` **相同的 per-column 键**（`${tabName}#${列}` / `${componentCode}#${列}` + 组件级 `tabName`/`code`）。`components` 为 enrich 后组件数组。
- 两视图页签内每列小计行（编辑 `:2150-2179` / 详情 `:599-620` `<tfoot>`）：**本期不动**。
- 列小计计算 `computeTabSubtotalsByColumn` / `computeTabSubtotal`（`:836-882`）：**本期不动**。
- 现有测试 `computeMultiSubtotal.test.ts` 注释已知三重累加 quirk（`:46`），只测 SUBTOTAL 路径；本期补测兜底路径，不影响其 `expect(...).toBe(62)`。
- 发布校验：`TemplateService.java:195-217`，无 subtotalFormula token 且无 SUBTOTAL 组件 → `:215` throw。无其他 SUBTOTAL 硬校验。
- 引导文案：`ConfigGuideDrawer.tsx:61 / :98 / :266`。
- CSS 类 `qt-subtotal-bar-multi` / `qt-subtotal-line` / `qt-subtotal-total` / `qt-subtotal-label` / `qt-subtotal-value` 定义在 `cpq-frontend/src/pages/quotation/quotation.css`（编辑页在用；详情页 `ReadonlyProductCard` 亦 import 同一 css）。

---

## File Structure

- Create: `cpq-frontend/src/pages/quotation/tabTotalLines.ts` — `buildTabTotalLines` 纯函数 + `TabTotalLine` 类型。
- Test: `cpq-frontend/src/pages/quotation/tabTotalLines.test.ts`
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` — 底部条改用 `buildTabTotalLines`（Task 2）；`computeProductSubtotal` 兜底修复（Task 3）。
- Modify: `cpq-frontend/src/pages/quotation/computeMultiSubtotal.test.ts` — 补兜底求和用例（Task 3）。
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` — 底部加页签总计多条（Task 4）。
- Modify: `cpq-backend/src/main/java/com/cpq/template/service/TemplateService.java` — 删发布校验 throw（Task 5）。
- Test: `cpq-backend/src/test/java/com/cpq/template/service/PublishWithoutSubtotalTest.java`（Task 5）
- Modify: `cpq-frontend/src/pages/component/ConfigGuideDrawer.tsx` — 文案（Task 6）。

---

## Task 1: `buildTabTotalLines` 纯函数（TDD vitest）

**Files:**
- Create: `cpq-frontend/src/pages/quotation/tabTotalLines.ts`
- Test: `cpq-frontend/src/pages/quotation/tabTotalLines.test.ts`

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest';
import { buildTabTotalLines } from './tabTotalLines';

const sub = {
  '投料#材料费': 40, '投料#加工费': 22, 'TOULIAO#材料费': 40, 'TOULIAO#加工费': 22,
  '投料': 62, 'TOULIAO': 62,
  '元素#小计': 0, 'ELEM#小计': 0, '元素': 0, 'ELEM': 0,
};

describe('buildTabTotalLines', () => {
  it('每个有小计列的组件出一条 = 该组件多列之和，标签 页签·总计', () => {
    const cd = [
      { componentType: 'NORMAL', tabName: '投料', componentCode: 'TOULIAO',
        fields: [{ name: '材料费', is_subtotal: true }, { name: '加工费', is_subtotal: true }, { name: '数量' }] },
      { componentType: 'NORMAL', tabName: '元素', componentCode: 'ELEM',
        fields: [{ name: '小计', is_subtotal: true }] },
    ];
    const lines = buildTabTotalLines(cd as any, sub);
    expect(lines).toEqual([
      { label: '投料 · 总计', value: 62 },
      { label: '元素 · 总计', value: 0 },
    ]);
  });

  it('跳过 SUBTOTAL 组件与无小计列组件', () => {
    const cd = [
      { componentType: 'SUBTOTAL', tabName: '产品小计', componentCode: 'ST', fields: [] },
      { componentType: 'NORMAL', tabName: '来料', componentCode: 'LL',
        fields: [{ name: '品名' }, { name: '规格' }] },
    ];
    expect(buildTabTotalLines(cd as any, sub)).toEqual([]);
  });

  it('componentCode 缺失时回退 tabName 键', () => {
    const cd = [{ componentType: 'NORMAL', tabName: '投料', fields: [{ name: '材料费', is_subtotal: true }] }];
    expect(buildTabTotalLines(cd as any, sub)).toEqual([{ label: '投料 · 总计', value: 40 }]);
  });

  it('键缺失时该列计 0', () => {
    const cd = [{ componentType: 'NORMAL', tabName: '未知', componentCode: 'X',
      fields: [{ name: '小计', is_subtotal: true }] }];
    expect(buildTabTotalLines(cd as any, sub)).toEqual([{ label: '未知 · 总计', value: 0 }]);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/tabTotalLines.test.ts 2>&1 | tail -12`
Expected: FAIL（模块/导出不存在）。

- [ ] **Step 3: 写实现**

```ts
// cpq-frontend/src/pages/quotation/tabTotalLines.ts
/** 卡片底部"页签总计"汇总线：每个非 SUBTOTAL 组件 → 其所有 is_subtotal 列的列小计之和 → 一条。 */
export interface TabTotalLine {
  label: string;
  value: number;
}

interface CompLike {
  componentType?: string;
  tabName: string;
  componentCode?: string;
  fields?: Array<{ name: string; is_subtotal?: boolean }>;
}

/**
 * @param componentData 卡片组件列表
 * @param subtotalMap   per-column 列小计字典，键 `${componentCode}#${列名}` / `${tabName}#${列名}`
 */
export function buildTabTotalLines(
  componentData: CompLike[],
  subtotalMap: Record<string, number>,
): TabTotalLine[] {
  const lines: TabTotalLine[] = [];
  if (!Array.isArray(componentData)) return lines;
  for (const comp of componentData) {
    if (!comp?.fields || comp.componentType === 'SUBTOTAL') continue;
    const subFields = comp.fields.filter((f) => f.is_subtotal);
    if (subFields.length === 0) continue;
    let total = 0;
    for (const f of subFields) {
      total += subtotalMap[`${comp.componentCode}#${f.name}`]
            ?? subtotalMap[`${comp.tabName}#${f.name}`] ?? 0;
    }
    lines.push({ label: `${comp.tabName} · 总计`, value: total });
  }
  return lines;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/tabTotalLines.test.ts 2>&1 | tail -12`
Expected: 4 用例全 PASS。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/tabTotalLines.ts cpq-frontend/src/pages/quotation/tabTotalLines.test.ts
git commit -m "feat(subtotal): buildTabTotalLines 页签总计汇总线纯函数 + vitest"
```

---

## Task 2: 编辑页底部条改用 buildTabTotalLines（按页签一条）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（底部条 `:2198-2217`）

- [ ] **Step 1: 顶部 import**

在已有 import 区（找 `from './rowDedup'` 或 `from './tabTotalLines'` 附近，若无则加一行）：

```tsx
import { buildTabTotalLines } from './tabTotalLines';
```

- [ ] **Step 2: 替换底部条的"按列遍历"为"按页签"**

定位 `:2200-2217`：

```tsx
        {item.componentData.length > 0 && (() => {
          const lines: { label: string; value: number }[] = [];
          for (const comp of item.componentData) {
            if (!comp?.fields || comp.componentType === 'SUBTOTAL') continue;
            for (const f of comp.fields) {
              if (!f.is_subtotal) continue;
              const v = allComponentSubtotals[`${comp.componentCode}#${f.name}`]
                ?? allComponentSubtotals[`${comp.tabName}#${f.name}`] ?? 0;
              lines.push({ label: `${comp.tabName} · ${f.name}`, value: v });
            }
          }
          return lines.map((ln, i) => (
            <div className="qt-subtotal-line" key={i}>
              <span className="qt-subtotal-label">{ln.label}</span>
              <span className="qt-subtotal-value">{formatCurrency(ln.value)}</span>
            </div>
          ));
        })()}
```

整体替换为：

```tsx
        {item.componentData.length > 0 &&
          buildTabTotalLines(item.componentData as any, allComponentSubtotals).map((ln, i) => (
            <div className="qt-subtotal-line" key={i}>
              <span className="qt-subtotal-label">{ln.label}</span>
              <span className="qt-subtotal-value">{formatCurrency(ln.value)}</span>
            </div>
          ))}
```

（末尾「产品小计」`qt-subtotal-total` 行 `:2218-2227` 不动。）

- [ ] **Step 3: TS 校验 + Vite transform**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx`
Expected: `200`。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(subtotal): 编辑页底部条改为按页签一条(页签·总计) 复用 buildTabTotalLines"
```

---

## Task 3: `computeProductSubtotal` 兜底修三重累加（TDD vitest）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`computeProductSubtotal` 兜底 `:955-956`）
- Test: `cpq-frontend/src/pages/quotation/computeMultiSubtotal.test.ts`（追加 describe）

- [ ] **Step 1: 写失败测试**（追加到 `computeMultiSubtotal.test.ts` 文件末尾）

```ts
describe('computeProductSubtotal 无 SUBTOTAL 组件兜底', () => {
  // 两个 NORMAL 组件、各有小计列；无 SUBTOTAL 组件、无 subtotalFormula → 默认 = 各页签总计之和（不重复累加）。
  it('= 各组件小计之和，且不三倍计', () => {
    const comp2: any = {
      componentId: 'c2', componentCode: 'YUANSU', tabName: '元素',
      fields: [
        { name: '量', field_type: 'INPUT_NUMBER' },
        { name: '价', field_type: 'INPUT_NUMBER' },
        { name: '元素小计', field_type: 'FORMULA', is_subtotal: true, formula_name: '元素小计' },
      ],
      formulas: [{ name: '元素小计', expression: [
        { type: 'field', value: '量' }, { type: 'operator', value: '*' }, { type: 'field', value: '价' },
      ] }],
      rows: [{ 量: 3, 价: 10 }],  // 30
    };
    const item: any = {
      productPartNo: 'P1',
      componentData: [
        { ...comp, componentType: 'NORMAL' },   // 投料 = 40 + 22 = 62
        { ...comp2, componentType: 'NORMAL' },  // 元素 = 30
      ],
      productAttributes: [], productAttributeValues: {},
    };
    // 期望 62 + 30 = 92（若三倍计会得 ~276）。
    expect(computeProductSubtotal(item)).toBe(92);
  });

  it('无任何小计列组件 → 0', () => {
    const item: any = {
      productPartNo: 'P1',
      componentData: [
        { componentId: 'c9', componentCode: 'X', tabName: '杂', componentType: 'NORMAL',
          fields: [{ name: '备注', field_type: 'INPUT_TEXT' }], formulas: [], rows: [{ 备注: 'a' }] },
      ],
      productAttributes: [], productAttributeValues: {},
    };
    expect(computeProductSubtotal(item)).toBe(0);
  });
});
```

（依赖文件顶部已 import 的 `comp` 与 `computeProductSubtotal`，沿用现有。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/computeMultiSubtotal.test.ts 2>&1 | tail -15`
Expected: 新 `= 各组件小计之和` 用例 FAIL（得 ~276，三倍计）；旧用例仍 PASS。

- [ ] **Step 3: 改兜底实现**

定位 `QuotationStep2.tsx:955-956`：

```tsx
  // Final fallback: sum of all component subtotals
  return Object.values(componentSubtotals).reduce((s, v) => s + v, 0);
```

替换为（逐组件只累加一次，避免 componentId/componentCode/tabName 三键重复）：

```tsx
  // Final fallback（无 SUBTOTAL 组件/公式）：各页签总计之和 —— 逐组件按 componentId 取一次，
  // 避免 componentSubtotals 同值三键(componentId/componentCode/tabName)被 Object.values 重复累加。
  let fallbackSum = 0;
  for (const comp of item.componentData) {
    if (!comp?.fields || comp.componentType === 'SUBTOTAL') continue;
    if (!comp.fields.some(f => f.is_subtotal)) continue;
    const key = comp.componentId ?? comp.componentCode ?? comp.tabName;
    fallbackSum += componentSubtotals[key] ?? 0;
  }
  return fallbackSum;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/computeMultiSubtotal.test.ts 2>&1 | tail -15`
Expected: 全部 PASS（旧 `toBe(62)` + 新 `toBe(92)` + `toBe(0)`）。

- [ ] **Step 5: TS 校验**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/computeMultiSubtotal.test.ts
git commit -m "fix(subtotal): computeProductSubtotal 无SUBTOTAL组件兜底改逐组件一次(修三重累加)"
```

---

## Task 4: 详情/核价页底部加页签总计多条（三视图一致 AP-50）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`（底部 `:629-634`）

- [ ] **Step 1: 顶部 import**

在已有 import 区加：

```tsx
import { buildTabTotalLines } from './tabTotalLines';
```

- [ ] **Step 2: 底部条改为多条 + 产品小计**

定位 `:629-634`：

```tsx
      <div className="qt-subtotal-bar">
        <span className="qt-subtotal-label">产品小计</span>
        <span className="qt-subtotal-value">
          {formatCurrency(productSubtotal || lineItem.subtotal || 0)}
        </span>
      </div>
```

整体替换为（与编辑页同构：先各页签总计多条，再产品小计）：

```tsx
      <div className="qt-subtotal-bar-multi">
        {buildTabTotalLines(components as any, compSubtotals).map((ln, i) => (
          <div className="qt-subtotal-line" key={i}>
            <span className="qt-subtotal-label">{ln.label}</span>
            <span className="qt-subtotal-value">{formatCurrency(ln.value)}</span>
          </div>
        ))}
        <div className="qt-subtotal-line qt-subtotal-total">
          <span className="qt-subtotal-label">产品小计</span>
          <span className="qt-subtotal-value">
            {formatCurrency(productSubtotal || lineItem.subtotal || 0)}
          </span>
        </div>
      </div>
```

> `components`（enrich 后组件数组）与 `compSubtotals`（`:273`）均在该 render 作用域可用。`buildTabTotalLines` 内部跳过 SUBTOTAL 组件。

- [ ] **Step 3: TS 校验 + Vite transform**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/ReadonlyProductCard.tsx`
Expected: `200`。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx
git commit -m "feat(subtotal): 详情/核价页底部加页签总计多条(三视图一致 AP-50)"
```

---

## Task 5: 解除"必须配小计"发布强制（后端，TDD）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/template/service/TemplateService.java`（`:204-217`）
- Test: `cpq-backend/src/test/java/com/cpq/template/service/PublishWithoutSubtotalTest.java`

- [ ] **Step 1: 写失败测试**（`@QuarkusTest`，播种一个无 SUBTOTAL 组件、无 subtotalFormula 的 DRAFT 模板 + 1 个 NORMAL 组件，断言 publish 不抛错）

```java
package com.cpq.template.service;

import com.cpq.component.entity.Component;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PublishWithoutSubtotalTest {

    @Inject TemplateService templateService;

    @Test
    @TestTransaction
    void publish_withoutSubtotalComponent_succeeds() {
        // 1) 一个 NORMAL 组件（发布要求至少 1 个组件）
        Component comp = new Component();
        comp.name = "投料-noSub";
        comp.code = "TOULIAO_NOSUB_" + UUID.randomUUID();
        comp.componentType = "NORMAL";
        comp.fields = "[]";
        comp.formulas = "[]";
        comp.persist();

        // 2) DRAFT 模板，无 subtotalFormula
        Template t = new Template();
        t.name = "无小计模板-" + UUID.randomUUID();
        t.status = "DRAFT";
        t.subtotalFormula = "[]";
        t.createdAt = OffsetDateTime.now();
        t.persist();

        TemplateComponent tc = new TemplateComponent();
        tc.templateId = t.id;
        tc.componentId = comp.id;
        tc.tabName = "投料";
        tc.sortOrder = 0;
        tc.persist();

        // 3) publish 不应抛"必须配置小计"
        assertDoesNotThrow(() -> templateService.publish(t.id));
        Template reloaded = Template.findById(t.id);
        assertEquals("PUBLISHED", reloaded.status);
    }
}
```

> 注：以实际实体属性名为准（grep `class Template` / `class TemplateComponent` / `class Component` 确认 `subtotalFormula` / `createdAt` / `tabName` / `sortOrder` / `componentType` / `fields` / `formulas` 字段名与可空性）。`publish` 方法签名以 `TemplateService` 实际为准（`publish(UUID id)`）。若 publish 内部还查别的必填项（如客户/分类）导致非小计原因失败，按实际补齐种子或在断言里只验"不含『必须配置小计』报错"。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=PublishWithoutSubtotalTest 2>&1 | tail -20`
Expected: FAIL —— `BusinessException: 模板发布前必须配置小计...`。

- [ ] **Step 3: 删除发布强制 throw**

定位 `TemplateService.java:204-217`：

```java
        boolean hasSubtotalComponent = false;
        if (subtotalList.isEmpty()) {
            List<TemplateComponent> tcsForCheck = TemplateComponent.list("templateId = ?1", id);
            for (TemplateComponent tc : tcsForCheck) {
                Component comp = Component.findById(tc.componentId);
                if (comp != null && "SUBTOTAL".equals(comp.componentType)) {
                    hasSubtotalComponent = true;
                    break;
                }
            }
            if (!hasSubtotalComponent) {
                throw new BusinessException("模板发布前必须配置小计:请拖入一个『小计』类型的组件,或在模板属性中填写小计公式");
            }
        }
```

整体删除这段（含 `hasSubtotalComponent` 变量）。把 `:195-198` 的注释更新为：

```java
        // 小计配置可选(2026-06-10 解除强制): 模板可不配 subtotalFormula、不拖 SUBTOTAL 组件而发布;
        // 此时产品小计运行期默认 = 各页签总计之和(前端 computeProductSubtotal 兜底)。
        // 配了 subtotalFormula token 或 SUBTOTAL 组件则照其公式算(行为不变)。
```

（`:199` 的 `List<?> subtotalList = parseJsonArray(template.subtotalFormula);` 若后续不再使用可一并删；若仍被引用则保留。`:201` 的"至少一个组件"校验保留。）

- [ ] **Step 4: 跑测试确认通过 + 触发重启验证编译**

Run: `cd cpq-backend && ./mvnw -q -o test -Dtest=PublishWithoutSubtotalTest 2>&1 | tail -20`
Expected: `BUILD SUCCESS`，测试过。
Run: `cd cpq-backend && touch src/main/java/com/cpq/template/service/TemplateService.java && sleep 7 && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/cpq/components`
Expected: `401`（编译通过 + 应用起来）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/template/service/TemplateService.java \
        cpq-backend/src/test/java/com/cpq/template/service/PublishWithoutSubtotalTest.java
git commit -m "feat(subtotal): 解除模板发布必须配小计强制(允许无SUBTOTAL组件发布) + @QuarkusTest"
```

---

## Task 6: 引导文案同步（SUBTOTAL 可选）

**Files:**
- Modify: `cpq-frontend/src/pages/component/ConfigGuideDrawer.tsx`（`:61` / `:266` 等）

- [ ] **Step 1: 读现文案定位**

Run: `grep -n "SUBTOTAL\|小计\|必须\|formulas" cpq-frontend/src/pages/component/ConfigGuideDrawer.tsx | head`
据实定位"必须拖 SUBTOTAL / 必须配置小计 / 否则发布校验阻塞"的措辞。

- [ ] **Step 2: 改措辞为"可选"**

把"模板发布前必须配置小计 / SUBTOTAL 必须有 formulas 否则发布阻塞"一类**强制**措辞，改为**可选**表述。示例（按实际句子就近替换，语义对齐即可）：

- `:266` 「⚠️ SUBTOTAL 组件每个模板只能 1 个,且必须有 formulas(否则发布校验阻塞)。」
  → 「SUBTOTAL（小计汇总）组件**可选**：每个模板最多 1 个；**不配则产品小计默认 = 各页签总计之和**。若配置 SUBTOTAL 组件则其 formulas 决定产品小计。」
- `:61`「模板里只允许 1 个,放在产品卡片底部」→ 末尾追加「（可选，不配走默认求和）」。

> 仅改文案字符串，不改逻辑。注意中文 UTF-8，避免字符串内嵌套引号破坏 Vite transform。

- [ ] **Step 3: TS 校验 + Vite transform**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/ConfigGuideDrawer.tsx`
Expected: `200`。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/component/ConfigGuideDrawer.tsx
git commit -m "docs(subtotal): 引导文案 SUBTOTAL 组件改为可选(不配走默认求和)"
```

---

## Task 7: E2E 回归 + 集成自检 + RECORD

**Files:**
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 前端全量自检**

Run:
```bash
cd cpq-frontend
npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5
npx vitest run src/pages/quotation/tabTotalLines.test.ts src/pages/quotation/computeMultiSubtotal.test.ts 2>&1 | tail -10
for f in src/pages/quotation/QuotationStep2.tsx src/pages/quotation/ReadonlyProductCard.tsx src/pages/component/ConfigGuideDrawer.tsx; do
  echo -n "$f "; curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:5174/$f"; done
```
Expected: TS 0 错误；vitest 全 PASS；三文件 Vite 200。

- [ ] **Step 2: 协议级 E2E 双 spec 回归**（改了 QuotationStep2 + ReadonlyProductCard，CLAUDE.md 强制）

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list 2>&1 | tail -30
```
Expected: `1 passed`，`'加载中' final count = 0`。`composite-product-flow.spec.ts` 若仍因预存 `v_composite_child_elements_mirror.unit_weight` 视图缺列失败，确认与本改动无关（本期未碰 SQL/视图）后照常记录。

- [ ] **Step 3: 手动核对底部条（编辑页 + 详情页）**

打开一份含「元素」(有 is_subtotal 列) 的报价单 Step2：
- 编辑页底部应为 `元素 · 总计` 一条（不再 `元素 · 小计`/按列多条）+「产品小计」。
- 切到详情/核价视图（比对视图/详情只读卡片），底部应同构（页签总计多条 + 产品小计）。
- 后端验证"无 SUBTOTAL 组件模板可发布"：对一个无 SUBTOTAL 组件的 DRAFT 模板调 `POST /api/cpq/templates/{id}/publish`，期望 200（非"必须配置小计"422）。

- [ ] **Step 4: 追加 RECORD.md**

```
[2026-06-10] 报价小计 - 底部汇总条按页签一条(页签·总计) + 产品小计无SUBTOTAL组件默认求和(修三重累加) + 解除模板必须配小计发布强制 | tabTotalLines.ts / QuotationStep2.tsx(footer+computeProductSubtotal) / ReadonlyProductCard.tsx(footer) / TemplateService.java(删发布throw) / ConfigGuideDrawer.tsx | 三视图一致复用buildTabTotalLines; computeProductSubtotal兜底原Object.values(3键map)三倍计休眠bug,解除强制后必踩故修为逐组件一次; 后端无运行期产品小计计算(calculateProductSubtotal死代码)故不动; 列小计计算与页签内每列小计行不变
```

- [ ] **Step 5: 自检声明 + Commit**

完成说明含（CLAUDE.md 强制）：
> tabTotalLines vitest + computeMultiSubtotal vitest 全过 ✅；TS 0 错误 ✅；QuotationStep2/ReadonlyProductCard/ConfigGuideDrawer → Vite 200 ✅；PublishWithoutSubtotalTest 过 + /api 401 ✅；E2E quotation-flow 1 passed + 加载中=0 ✅；编辑页/详情页底部「页签·总计」同构 ✅；无 SUBTOTAL 模板 publish 200 ✅。

```bash
git add docs/RECORD.md
git commit -m "docs(record): 报价小计体系调整(页签总计footer+产品小计默认求和+解除强制) 开发记录"
```

---

## Self-Review（写后自检）

**Spec coverage（对照 spec §2 / §4）：**
- §4.1 ① 底部按页签一条（编辑页）→ Task 2 ✅；（详情/核价页）→ Task 4 ✅；共享 `buildTabTotalLines` → Task 1 ✅
- §4.2 ② 产品小计修三重累加 + 默认求和 → Task 3 ✅（编辑+详情共用 `computeProductSubtotal`，一处修复两视图）
- §4.3 ③ 解除发布强制 → Task 5 ✅；文案 → Task 6 ✅
- §6 后端无产品小计运行期计算（不动）→ 计划未含后端产品小计任务 ✅
- §7 测试矩阵（vitest + 双视图 + 后端 publish + E2E）→ Task 1/3/5/7 ✅
- §5 YAGNI（列小计计算 / 页签内每列小计行 不动）→ 全程未触及 ✅

**Placeholder scan：** 无 TBD/TODO。Task 5 的"以实体实际属性名为准 / publish 可能因别的必填失败"是 @QuarkusTest 装配的实测确认点（给了 grep 手段），非占位。Task 6 文案就近替换给了具体改法示例。

**Type consistency：**
- `buildTabTotalLines(componentData, subtotalMap): TabTotalLine[]`、`TabTotalLine{label,value}` —— Task 1 定义，Task 2 / Task 4 调用一致（均传 per-column map：编辑页 `allComponentSubtotals`、详情页 `compSubtotals`，键格式相同）。
- `computeProductSubtotal(item, ...)` 兜底改动不变更签名 —— Task 3 内部实现修复，Task 2/4 footer 仍调原签名。
- CSS 类 `qt-subtotal-bar-multi/line/total/label/value` —— Task 2（编辑页既有）与 Task 4（详情页新增）一致复用同一 css。

**风险点：** Task 5 实体属性名 / publish 其他必填项 —— 给了 grep + 实测确认；Task 4 详情页 `components`/`compSubtotals` 作用域 —— 已在 spec §3 / 本计划"已核实事实"确认可用。
