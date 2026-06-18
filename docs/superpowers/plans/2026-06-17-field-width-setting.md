# 字段宽度设置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在组件管理「页签字段配置」为每个字段增加可设置的展示宽度（像素 + 窄/中/宽快捷档位）并就地预览，宽度在报价单与核价单的详情页、编辑页统一生效。

**Architecture:** 字段宽度作为 `FieldItem` 上一个新的可选键 `width?: number`（纯像素，空=默认 120）。沿用现有持久化链路：`component.fields`(JSONB) → 模板 `componentsSnapshot` → 前端 `enrichComponentData` / `buildComponentDataFromStructure` → 渲染层 `<th>`。后端保存与 `componentsSnapshot` 因按完整字段 Map 透传，`width` 自动随行，无需改动；唯一后端改动点是 `CardSnapshotService`（结构快照按白名单挑键，需补 `width`）。前端在 `FieldConfigTable` 加一列编辑控件 + 表下方横向预览条；渲染层两处 `<th>` 应用宽度。

**Tech Stack:** React 18 + Ant Design + TypeScript（Vite）；Quarkus + Jackson（后端 `CardSnapshotService`）；Vitest（前端单测）；Playwright（协议级 E2E）。

---

## 关键事实（实现前必读，已核查）

- **存储/快照自动透传**：`ComponentService.create/update` 用 `List<Map<String,Object>>` 整体序列化 `component.fields`；`TemplateService` 的 `componentsSnapshot` 与 `refreshSnapshotsByComponent` 用 `parseJsonArray(comp.fields)` 整体冻结。三处都不按键白名单，故 `width` 自动落库、自动进 snapshot，**无需改动**。
- **必须显式补 `width` 的白名单点（共 3 处）**：
  1. `cpq-frontend/src/pages/quotation/enrichComponentData.ts` 的 `enrichComponentData` 字段映射（snapshot snake_case，约 L131-157）。
  2. 同文件 `buildComponentDataFromStructure` 字段映射（结构 camelCase，约 L251-271）。
  3. `cpq-backend/.../quotation/service/CardSnapshotService.java` 结构字段序列化（约 L248-290）。
- **渲染落点（2 处 `<th>`）**：
  - 编辑页（报价单 + 核价单共用同一 `ProductCard`）：`QuotationStep2.tsx:2179`。
  - 详情页：`ReadonlyProductCard.tsx:449`。
- **默认值语义**：`width` 为 `undefined` / `null` / `0` → 视为未设置 → 渲染 **120px**。

## File Structure

| 文件 | 责任 | 改动类型 |
|---|---|---|
| `cpq-frontend/src/pages/component/types.ts` | `FieldItem` 加 `width`；导出宽度常量 + `resolveFieldWidth` 纯函数（单一真源） | Modify |
| `cpq-frontend/src/pages/component/fieldWidth.test.ts` | `resolveFieldWidth` 单元测试 | Create |
| `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | `ComponentField` 加 `width`；编辑页 `<th>` 应用宽度 | Modify |
| `cpq-frontend/src/pages/component/FieldConfigTable.tsx` | 新增「宽度」编辑列 + 表下方横向预览条 | Modify |
| `cpq-frontend/src/pages/quotation/enrichComponentData.ts` | 两个字段映射器透传 `width` | Modify |
| `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` | 详情页 `<th>` 应用宽度 | Modify |
| `cpq-backend/.../quotation/service/CardSnapshotService.java` | 结构快照序列化补 `width` | Modify |
| `docs/RECORD.md` | 开发记录 | Modify |

---

### Task 1: 宽度常量 + `resolveFieldWidth` 纯函数（单一真源，TDD）

**Files:**
- Modify: `cpq-frontend/src/pages/component/types.ts`（在文件末尾、`FieldItem` 之后追加导出）
- Test: `cpq-frontend/src/pages/component/fieldWidth.test.ts`

- [ ] **Step 1: 写失败测试**

Create `cpq-frontend/src/pages/component/fieldWidth.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { resolveFieldWidth, DEFAULT_FIELD_WIDTH, FIELD_WIDTH_PRESETS } from './types';

describe('resolveFieldWidth', () => {
  it('未设置(undefined)→默认 120', () => {
    expect(resolveFieldWidth(undefined)).toBe(120);
    expect(DEFAULT_FIELD_WIDTH).toBe(120);
  });

  it('null / 0 / 负数 视为未设置→默认 120', () => {
    expect(resolveFieldWidth(null as unknown as number)).toBe(120);
    expect(resolveFieldWidth(0)).toBe(120);
    expect(resolveFieldWidth(-50)).toBe(120);
  });

  it('正像素值原样返回', () => {
    expect(resolveFieldWidth(80)).toBe(80);
    expect(resolveFieldWidth(160)).toBe(160);
    expect(resolveFieldWidth(200)).toBe(200);
  });

  it('档位常量为 窄80/中120/宽200', () => {
    expect(FIELD_WIDTH_PRESETS.map((p) => p.value)).toEqual([80, 120, 200]);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/fieldWidth.test.ts`
Expected: FAIL（`resolveFieldWidth` / 常量 未导出）

- [ ] **Step 3: 实现（在 `types.ts` `FieldItem` 接口之后追加）**

在 `cpq-frontend/src/pages/component/types.ts` 中，`export interface FieldItem { ... }` 闭合花括号之后插入：

```ts
/** 字段列在报价单/核价单表格渲染时的默认展示宽度(px)，未设置时使用。 */
export const DEFAULT_FIELD_WIDTH = 120;

/** 字段宽度预设档位：窄/中/宽。仅作 UI 快捷，最终只存像素值。 */
export const FIELD_WIDTH_PRESETS = [
  { label: '窄', value: 80 },
  { label: '中', value: 120 },
  { label: '宽', value: 200 },
] as const;

/**
 * 解析字段展示宽度(px)。width 为空 / 0 / 负数 一律视为未设置 → 返回 DEFAULT_FIELD_WIDTH。
 * 报价单/核价单详情页与编辑页渲染列宽的唯一真源。
 */
export function resolveFieldWidth(width?: number | null): number {
  return typeof width === 'number' && width > 0 ? width : DEFAULT_FIELD_WIDTH;
}
```

并在 `FieldItem` 接口内（如 `unit_source_field?: string;` 之后、闭合 `}` 之前）加上字段声明：

```ts
  /** 报价单/核价单渲染时该字段列的展示宽度(px)。空/0 = 默认 120。仅展示用，不参与计算。 */
  width?: number;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/fieldWidth.test.ts`
Expected: PASS（4 个用例全绿）

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/component/types.ts cpq-frontend/src/pages/component/fieldWidth.test.ts
git commit -m "feat(field-width): 字段宽度常量 + resolveFieldWidth 纯函数 + FieldItem.width"
```

---

### Task 2: `ComponentField` 渲染类型加 `width`

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:55`（`ComponentField` 接口内）

- [ ] **Step 1: 加字段声明**

在 `export interface ComponentField {` 内部、`is_subtotal?: boolean;` 之后插入：

```ts
  /** 列展示宽度(px)，空/0=默认 120。来自 component.fields，经 snapshot/结构透传。 */
  width?: number;
```

- [ ] **Step 2: 类型检查**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

- [ ] **Step 3: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(field-width): ComponentField 渲染类型补 width"
```

---

### Task 3: FieldConfigTable 新增「宽度」编辑列

**Files:**
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（import；columns 数组）

- [ ] **Step 1: 引入 InputNumber 与宽度常量**

把第 2 行 antd import 中加入 `InputNumber`：

```ts
import { Input, InputNumber, Select, Checkbox, Button, Typography, Tooltip, Space, Modal, Form, Alert, Segmented, Tag } from 'antd';
```

把第 9 行 types import 改为同时引入宽度常量与函数：

```ts
import { FIELD_TYPE_OPTIONS, newFieldRow, FIELD_WIDTH_PRESETS, resolveFieldWidth } from './types';
```

- [ ] **Step 2: 在 columns 数组中「小计」列之后插入「宽度」列**

在 `FieldConfigTable.tsx` columns 数组里，`小计`（`key: 'is_subtotal'`，约 L424-434）对象之后、`...(onToggleRowKey ? [{ ... 行键 ...`（约 L435）之前，插入：

```tsx
    {
      title: '宽度',
      key: 'width',
      width: 150,
      render: (_: unknown, record: FieldItem) => (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <InputNumber
            size="small"
            min={1}
            value={record.width}
            onChange={(val) =>
              updateField(record.key, { width: typeof val === 'number' && val > 0 ? val : undefined })
            }
            placeholder="默认120"
            addonAfter="px"
            style={{ width: '100%' }}
          />
          <Space size={2}>
            {FIELD_WIDTH_PRESETS.map((p) => (
              <Button
                key={p.value}
                size="small"
                type={record.width === p.value ? 'primary' : 'default'}
                style={{ padding: '0 6px', height: 18, fontSize: 11 }}
                onClick={() => updateField(record.key, { width: p.value })}
              >
                {p.label}
              </Button>
            ))}
          </Space>
        </div>
      ),
    },
```

> 说明：清空输入框 → `val` 为 null → 写入 `undefined` → 渲染回退默认 120。点档位按钮即把 80/120/200 写入 `record.width`。

- [ ] **Step 3: 类型检查**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

- [ ] **Step 4: Vite 解析自检**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/FieldConfigTable.tsx`
Expected: 200

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/component/FieldConfigTable.tsx
git commit -m "feat(field-width): 字段配置表新增宽度列(InputNumber + 窄中宽档位)"
```

---

### Task 4: FieldConfigTable 表下方横向预览条

**Files:**
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（`<SortableTable .../>` 之后）

- [ ] **Step 1: 在 SortableTable 之后插入预览条**

在 `FieldConfigTable.tsx` 的 `return (...)` 内，`<SortableTable ... />`（约 L548-557）闭合之后、`{/* 路径配置 ... */}`（约 L559）之前，插入：

```tsx
      {/* 字段宽度就地预览：按各字段 width 横排成模拟列头，接近报价单真实列排布 */}
      <div className="cm-field-width-preview">
        <div className="cm-field-width-preview-title">宽度预览（模拟报价单列头）</div>
        {fields.length === 0 ? (
          <Text type="secondary" style={{ fontSize: 12 }}>暂无字段</Text>
        ) : (
          <div className="cm-field-width-preview-row">
            {fields.map((f) => {
              const w = resolveFieldWidth(f.width);
              return (
                <div
                  key={f.key}
                  className="cm-field-width-preview-cell"
                  style={{ width: w, minWidth: w }}
                  title={`${f.name || f.key || '(未命名)'} · ${w}px`}
                >
                  <span className="cm-fwp-name">{f.name || f.key || '(未命名)'}</span>
                  <span className="cm-fwp-size">{w}px</span>
                </div>
              );
            })}
          </div>
        )}
      </div>
```

- [ ] **Step 2: 在 styles.css 末尾追加预览条样式**

向 `cpq-frontend/src/pages/component/styles.css` 末尾追加：

```css
/* 字段宽度就地预览条 */
.cm-field-width-preview {
  margin-top: 12px;
  padding: 8px 12px;
  background: #fafafa;
  border: 1px dashed #d9d9d9;
  border-radius: 4px;
}
.cm-field-width-preview-title {
  font-size: 12px;
  color: #8c8c8c;
  margin-bottom: 6px;
}
.cm-field-width-preview-row {
  display: flex;
  gap: 2px;
  overflow-x: auto;
  padding-bottom: 4px;
}
.cm-field-width-preview-cell {
  box-sizing: border-box;
  flex: 0 0 auto;
  height: 40px;
  border: 1px solid #bfbfbf;
  background: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  overflow: hidden;
  padding: 0 4px;
}
.cm-fwp-name {
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #262626;
}
.cm-fwp-size {
  color: #8c8c8c;
}
```

- [ ] **Step 3: 类型检查 + Vite 自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/FieldConfigTable.tsx`
Expected: 200

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/component/FieldConfigTable.tsx cpq-frontend/src/pages/component/styles.css
git commit -m "feat(field-width): 字段配置表下方横向宽度预览条 + 样式"
```

---

### Task 5: enrichComponentData 两个映射器透传 `width`

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/enrichComponentData.ts`

- [ ] **Step 1: snapshot 映射器补 width（约 L131-157）**

在 `enrichComponentData` 中 `const fields: ComponentField[] = (snapshotComp.fields || []).map((f: any) => ({ ... }))` 对象里，`is_subtotal: f.is_subtotal,` 之后插入：

```ts
        // 字段展示宽度：snapshot 存 component.fields 原样(snake/camel 同名 width)，透传供渲染列宽
        width: f.width,
```

- [ ] **Step 2: 结构映射器补 width（约 L251-271）**

在 `buildComponentDataFromStructure` 中 `const fields: ComponentField[] = (tab.fields || []).map((f: any) => ({ ... }))` 对象里，`is_subtotal: f.isSubtotal,` 之后插入：

```ts
        // 字段展示宽度：结构快照(camelCase width，见 CardSnapshotService)透传
        width: f.width,
```

- [ ] **Step 3: 类型检查 + Vite 自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/enrichComponentData.ts`
Expected: 200

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/quotation/enrichComponentData.ts
git commit -m "feat(field-width): enrich/结构两映射器透传 width(防 AP-44 白名单丢键)"
```

---

### Task 6: 后端 CardSnapshotService 结构快照补 `width`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`（约 L254 `isSubtotal` 之后）

- [ ] **Step 1: 在字段序列化里补 width**

在 fieldNode 序列化处（约 L254 `fieldNode.put("isSubtotal", f.path("is_subtotal").asBoolean(false));` 之后）插入：

```java
                    // 字段列展示宽度(px)。组件 fields 中无 width 或 <=0 时存 0，前端 resolveFieldWidth 回退默认 120。
                    fieldNode.put("width", f.path("width").asInt(0));
```

> 注意：`f.path("width")` 对缺失键返回 MissingNode，`.asInt(0)` 安全回退 0；前端 `resolveFieldWidth(0)` → 120，语义一致。

- [ ] **Step 2: 触发 Quarkus 重启并健康检查**

Run: `touch cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`（保存即触发 dev 热重载；等待 5-7 秒）
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health`
Expected: 200

- [ ] **Step 3: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java
git commit -m "feat(field-width): CardSnapshotService 结构快照序列化补 width"
```

---

### Task 7: 渲染层两处 `<th>` 应用宽度

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:2178-2180`（编辑页，报价单+核价单共用）
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx:448-450`（详情页）

- [ ] **Step 1: QuotationStep2 导入 resolveFieldWidth**

确认 `QuotationStep2.tsx` 顶部已从 `'../component/types'` 引入；若未引入则在该 import 中加上 `resolveFieldWidth`。例如：

```ts
import { resolveFieldWidth } from '../component/types';
```

（若该文件已有从 `./types` 或 `../component/types` 的导入语句，则把 `resolveFieldWidth` 并入既有语句，避免重复 import。）

- [ ] **Step 2: 改编辑页 `<th>`（L2178-2180）**

把：

```tsx
                    {activeComponent.fields.map(field => (
                      <th key={field.name || field.key}>{field.label || field.name}</th>
                    ))}
```

改为：

```tsx
                    {activeComponent.fields.map(field => {
                      const w = resolveFieldWidth(field.width);
                      return (
                        <th key={field.name || field.key} style={{ width: w, minWidth: w }}>
                          {field.label || field.name}
                        </th>
                      );
                    })}
```

- [ ] **Step 3: ReadonlyProductCard 导入并改详情页 `<th>`（L448-450）**

在 `ReadonlyProductCard.tsx` 顶部 import 区加上（或并入既有 `../component/types` 导入）：

```ts
import { resolveFieldWidth } from '../component/types';
```

把：

```tsx
                    {activeComp.fields.map(field => (
                      <th key={field.name}>{field.label || field.name}</th>
                    ))}
```

改为：

```tsx
                    {activeComp.fields.map(field => {
                      const w = resolveFieldWidth(field.width);
                      return (
                        <th key={field.name} style={{ width: w, minWidth: w }}>
                          {field.label || field.name}
                        </th>
                      );
                    })}
```

- [ ] **Step 4: 类型检查 + Vite 自检（两文件）**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx`
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/ReadonlyProductCard.tsx`
Expected: 两个均 200

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx
git commit -m "feat(field-width): 报价/核价 编辑页+详情页 <th> 按 width 渲染列宽"
```

---

### Task 8: 协议级 E2E + 全量自检 + 开发记录

**Files:**
- Modify: `docs/RECORD.md`

- [ ] **Step 1: 跑 Playwright E2E（触碰了 QuotationStep2 / ReadonlyProductCard / enrichComponentData / FieldConfigTable / types.ts，属 AP-44/渲染协议相关，强制）**

Run（PowerShell，按 CLAUDE.md 自检规范）:

```powershell
cd cpq-frontend
Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```

Expected: 所有 test `passed`；日志含 `'加载中' final count = 0`；全部 8 Tab `'加载中'=0`。
（证明新增 `width` 键未破坏 enrich / snapshot / 结构透传链路。）

- [ ] **Step 2: 全量编译自检 + 单测**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
Run: `cd cpq-frontend && npx vitest run src/pages/component/fieldWidth.test.ts` → PASS
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health` → 200

- [ ] **Step 3: 人工功能验收（在已运行的 dev server 上）**

确认：
- 组件管理 → 字段配置：改某字段宽度 / 点窄中宽档位 → 表下方预览条对应格实时变宽变窄。
- 报价单编辑页 + 详情页：该字段列宽随设置生效；未设宽度的字段列约 120px。
- 核价单编辑页 + 详情页：同上（与报价单共用 ProductCard / 同结构 `<th>`）。

- [ ] **Step 4: 写开发记录**

向 `docs/RECORD.md` 追加一行（格式：`[日期] 模块 - 描述 | 文件 | 关键决策`）：

```
[2026-06-17] 组件管理/报价渲染 - 字段宽度设置(px+窄80/中120/宽200档位)+字段配置表下方横向预览;报价单/核价单详情页+编辑页<th>按width渲染 | types.ts(FieldItem.width+resolveFieldWidth) / FieldConfigTable.tsx(宽度列+预览条) / QuotationStep2.tsx(ComponentField.width+编辑页th) / ReadonlyProductCard.tsx(详情页th) / enrichComponentData.ts(两映射器透传) / CardSnapshotService.java(结构补width) / styles.css | 决策:只存px(档位仅UI快捷,方案A);空/0→默认120;后端save+componentsSnapshot按Map整体透传无需改,仅结构快照CardSnapshotService白名单需补width(AP-44纪律)
```

- [ ] **Step 5: 提交**

```bash
git add docs/RECORD.md
git commit -m "docs(record): 字段宽度设置开发记录"
```

---

## Self-Review

- **Spec coverage：**
  - 形式=px+档位 → Task 1（常量/函数）+ Task 3（编辑列控件）✅
  - 编辑位置=字段配置表加一列 → Task 3 ✅
  - 默认 120 / 空值语义 → Task 1 `resolveFieldWidth` ✅
  - 档位 窄80/中120/宽200 + 任意像素 → Task 1 常量 + Task 3 InputNumber ✅
  - 预览=表下方横向条 → Task 4 ✅
  - 生效=报价单+核价单 详情页+编辑页 → Task 7（编辑页共用 + 详情页）✅
  - 所有字段类型一视同仁 → `<th>` 对 `fields.map` 全量应用，不分类型 ✅
  - 系统列不动 → 仅改字段 `<th>`，料号/版本/操作列未触碰 ✅
  - 传播不丢键（AP-44）→ Task 5（前端两映射器）+ Task 6（后端结构快照）✅
  - 协议级 E2E → Task 8 ✅
- **Placeholder scan：** 无 TBD/TODO；每个改动步骤均含完整代码。
- **Type consistency：** `width?: number` 在 `FieldItem`(Task1) 与 `ComponentField`(Task2) 命名一致；`resolveFieldWidth` 在 Task1 定义、Task4/Task7 引用一致；`FIELD_WIDTH_PRESETS` 元素 `{label,value}` 在 Task1 定义、Task3 消费一致。
