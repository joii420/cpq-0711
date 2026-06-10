# Plan 3b — 条件公式构建器 UI（FieldConfigTable 条件模式 + 嵌套 AND/OR 编辑器） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在「组件管理」里给 FORMULA 字段配置条件公式：选择式构建有序规则（每条 = 嵌套 AND/OR 条件树 + 命中公式）+ 默认公式。产出的 `conditional_formula` JSON 即被 Plan 3a 引擎求值。全程选择式、用户不手敲表达式（叶子右侧字面值可键入）。

**Architecture:** 两个新组件 + 一处接入。`CondTreeEditor`（递归，编辑一棵 `CondTree`：分组 AND/OR 切换 + 加条件/加分组 + 叶子=列·运算符·右值）。`ConditionalFormulaDrawer`（抽屉，编辑一个字段的 `conditional_formula`：规则列表 + 每条规则的条件树编辑器 + 命中公式 Select + 上移/下移/删除 + 默认公式 Select + 确认校验）。`FieldConfigTable` 的 FORMULA 分支加「单一/条件」模式切换：条件模式显示「条件公式 (N 规则)」+ 配置按钮开抽屉。遵守项目 Drawer 规范（不用 Modal）。

**Tech Stack:** React + Ant Design（`Drawer` / `Select` / `Input` / `Segmented` / `Button` / `Space`）+ TS。复用 `utils/condTree.ts` 的 `CondTree`/`CondOp` 类型（Plan 3a）。

**关联：** spec 设计 B；承接 Plan 3a（引擎 + 数据契约 `conditional_formula = { rules:[{when:CondTree, formula}], default }`）。

---

## 已核对的既有事实（勿重新发明）

- `FieldConfigTable.tsx`：props 含 `fields: FieldItem[]` + `formulas?: FormulaItem[]`（`:23`）；FORMULA 分支（`:280-312`）= formula_name `Select`（options 来自 `formulas`）；LIST_FORMULA 用「按钮开 Drawer」模式（state `listFormulaKey` `:58`，Drawer 元素 `:639-654`，`open={key!==null}` / `value=fields.find(...).list_formula_config` / `onConfirm={next => updateField(key, {...})}`）；`updateField(key, patch)`（`:64`）。
- `FieldItem.conditional_formula`（types.ts，Plan 3a 已加）= `{ rules: { when: any; formula: string }[]; default: string }`。
- `utils/condTree.ts`（Plan 3a）：`type CondTree = group{logic,children} | leaf{left,op,rhs{type,value}}`；`type CondOp`。
- 列选项来源 = 组件字段名（`fields.map(f => f.name)`），供条件树叶子的「列」与「列型右值」。
- Drawer 规范（CLAUDE.md）：弹出式交互一律 `Drawer placement="right"`。

---

## File Structure

- Create: `cpq-frontend/src/pages/component/CondTreeEditor.tsx`（递归条件树编辑器）。
- Create: `cpq-frontend/src/pages/component/ConditionalFormulaDrawer.tsx`（字段级条件公式抽屉）。
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`（FORMULA 分支模式切换 + 抽屉接线）。
- Test: `cpq-frontend/src/pages/component/condTreeEditor.test.tsx`（构建 helper + 渲染冒烟）。

---

## Task 1: CondTreeEditor 递归编辑器（TDD 构建 helper + 渲染冒烟）

**Files:**
- Create: `cpq-frontend/src/pages/component/CondTreeEditor.tsx`
- Test: `cpq-frontend/src/pages/component/condTreeEditor.test.tsx`

- [ ] **Step 1: 写组件**

```tsx
import React from 'react';
import { Select, Input, Button, Space, Segmented } from 'antd';
import type { CondTree, CondOp } from '../../utils/condTree';

const OP_OPTIONS: { label: string; value: CondOp }[] = [
  { label: '=', value: 'eq' }, { label: '≠', value: 'ne' },
  { label: '>', value: 'gt' }, { label: '≥', value: 'gte' },
  { label: '<', value: 'lt' }, { label: '≤', value: 'lte' },
  { label: '∈ 在…中', value: 'in' },
];

export const emptyLeaf = (col: string): CondTree =>
  ({ kind: 'leaf', left: col, op: 'eq', rhs: { type: 'literal', value: '' } });
export const emptyGroup = (): CondTree => ({ kind: 'group', logic: 'and', children: [] });

interface Props {
  value: CondTree;
  onChange: (next: CondTree) => void;
  columnOptions: { label: string; value: string }[];
  onRemove?: () => void;
  depth?: number;
}

const CondTreeEditor: React.FC<Props> = ({ value, onChange, columnOptions, onRemove, depth = 0 }) => {
  if (value.kind === 'leaf') {
    const rhs = value.rhs;
    return (
      <Space wrap size={4} style={{ marginBottom: 4 }}>
        <Select size="small" style={{ minWidth: 120 }} placeholder="列" value={value.left || undefined}
          options={columnOptions} showSearch optionFilterProp="label"
          onChange={v => onChange({ ...value, left: v })} />
        <Select size="small" style={{ width: 96 }} value={value.op} options={OP_OPTIONS}
          onChange={(v) => onChange({ ...value, op: v as CondOp })} />
        <Segmented size="small" value={rhs.type}
          options={[{ label: '值', value: 'literal' }, { label: '列', value: 'column' }]}
          onChange={(t) => onChange({ ...value, rhs: { type: t as 'literal' | 'column', value: '' } })} />
        {rhs.type === 'literal'
          ? <Input size="small" style={{ width: 130 }} placeholder={value.op === 'in' ? '逗号分隔' : '值'}
              value={rhs.value} onChange={e => onChange({ ...value, rhs: { ...rhs, value: e.target.value } })} />
          : <Select size="small" style={{ minWidth: 120 }} placeholder="列" value={rhs.value || undefined}
              options={columnOptions} showSearch optionFilterProp="label"
              onChange={v => onChange({ ...value, rhs: { type: 'column', value: v } })} />}
        {onRemove && <Button size="small" type="text" danger onClick={onRemove}>✕</Button>}
      </Space>
    );
  }
  // group
  const children = value.children || [];
  return (
    <div style={{ border: '1px solid #eee', borderRadius: 6, padding: 8, marginBottom: 6, background: depth % 2 ? '#fafafa' : '#fff' }}>
      <Space style={{ marginBottom: 6 }} wrap>
        <Segmented size="small" value={value.logic}
          options={[{ label: '且 AND', value: 'and' }, { label: '或 OR', value: 'or' }]}
          onChange={(l) => onChange({ ...value, logic: l as 'and' | 'or' })} />
        <Button size="small" onClick={() => onChange({ ...value, children: [...children, emptyLeaf(columnOptions[0]?.value || '')] })}>+ 条件</Button>
        <Button size="small" onClick={() => onChange({ ...value, children: [...children, emptyGroup()] })}>+ 分组</Button>
        {onRemove && <Button size="small" type="text" danger onClick={onRemove}>删分组</Button>}
      </Space>
      <div style={{ paddingLeft: 12 }}>
        {children.length === 0 && <span style={{ color: '#999', fontSize: 12 }}>（空分组 = 恒真）</span>}
        {children.map((c, i) => (
          <CondTreeEditor key={i} value={c} columnOptions={columnOptions} depth={depth + 1}
            onChange={nc => { const next = [...children]; next[i] = nc; onChange({ ...value, children: next }); }}
            onRemove={() => onChange({ ...value, children: children.filter((_, j) => j !== i) })} />
        ))}
      </div>
    </div>
  );
};

export default CondTreeEditor;
```

- [ ] **Step 2: 写测试（构建 helper + 渲染冒烟）**

```tsx
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import CondTreeEditor, { emptyLeaf, emptyGroup } from './CondTreeEditor';

describe('CondTreeEditor helpers', () => {
  it('emptyLeaf / emptyGroup 形状正确', () => {
    expect(emptyLeaf('类型')).toEqual({ kind: 'leaf', left: '类型', op: 'eq', rhs: { type: 'literal', value: '' } });
    expect(emptyGroup()).toEqual({ kind: 'group', logic: 'and', children: [] });
  });
  it('渲染嵌套组不报错', () => {
    const tree: any = { kind: 'group', logic: 'or', children: [
      { kind: 'group', logic: 'and', children: [emptyLeaf('类型'), emptyLeaf('数量')] },
      emptyLeaf('加急'),
    ] };
    const { container } = render(
      <CondTreeEditor value={tree} onChange={() => {}} columnOptions={[{ label: '类型', value: '类型' }]} />,
    );
    expect(container).toBeTruthy();
  });
});
```

> 若仓库未装 `@testing-library/react`，把渲染冒烟降级为仅测 helper（删第二个 it），渲染验证交给 Step 4 的 Vite 200 + 手工。先 `grep "@testing-library/react" cpq-frontend/package.json` 判断。

- [ ] **Step 3: 跑测试**

Run: `cd cpq-frontend && npx vitest run src/pages/component/condTreeEditor.test.tsx 2>&1 | tail -8`
Expected: passed（helper 形状 + 渲染不报错）。

- [ ] **Step 4: Vite transform**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/CondTreeEditor.tsx`
Expected: `200`。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/CondTreeEditor.tsx cpq-frontend/src/pages/component/condTreeEditor.test.tsx
git commit -m "feat(conditional-formula-ui): CondTreeEditor 递归 AND/OR 条件树编辑器"
```

---

## Task 2: ConditionalFormulaDrawer 字段级条件公式抽屉

**Files:**
- Create: `cpq-frontend/src/pages/component/ConditionalFormulaDrawer.tsx`

- [ ] **Step 1: 写组件**

```tsx
import React, { useEffect, useState } from 'react';
import { Drawer, Select, Button, Space, Typography, Divider, message } from 'antd';
import type { CondTree } from '../../utils/condTree';
import CondTreeEditor, { emptyGroup } from './CondTreeEditor';

const { Text } = Typography;

export interface ConditionalFormulaValue {
  rules: { when: CondTree; formula: string }[];
  default: string;
}

interface Props {
  open: boolean;
  value?: ConditionalFormulaValue;
  fieldName?: string;
  formulaOptions: { label: string; value: string }[]; // 组件公式名
  columnOptions: { label: string; value: string }[];   // 组件字段名（条件列）
  onClose: () => void;
  onConfirm: (next: ConditionalFormulaValue) => void;
}

const ConditionalFormulaDrawer: React.FC<Props> = ({
  open, value, fieldName, formulaOptions, columnOptions, onClose, onConfirm,
}) => {
  const [rules, setRules] = useState<{ when: CondTree; formula: string }[]>([]);
  const [def, setDef] = useState<string>('');

  useEffect(() => {
    if (open) {
      setRules(value?.rules?.length ? value.rules.map(r => ({ when: r.when || emptyGroup(), formula: r.formula })) : []);
      setDef(value?.default || '');
    }
  }, [open, value]);

  const addRule = () => setRules([...rules, { when: emptyGroup(), formula: '' }]);
  const updateRule = (i: number, patch: Partial<{ when: CondTree; formula: string }>) =>
    setRules(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const removeRule = (i: number) => setRules(rules.filter((_, j) => j !== i));
  const move = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= rules.length) return;
    const next = [...rules]; [next[i], next[j]] = [next[j], next[i]]; setRules(next);
  };

  const handleConfirm = () => {
    if (rules.length === 0) { message.error('至少需 1 条规则'); return; }
    if (rules.some(r => !r.formula)) { message.error('每条规则都要选命中公式'); return; }
    if (!def) { message.error('必须选默认公式（全不命中时执行）'); return; }
    onConfirm({ rules, default: def });
  };

  return (
    <Drawer
      title={`条件公式配置 · ${fieldName || ''}`}
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      extra={<Space><Button onClick={onClose}>取消</Button><Button type="primary" onClick={handleConfirm}>确定</Button></Space>}
    >
      <Text type="secondary">规则按顺序求值，第一条条件成立的执行其公式；全不成立走默认公式。</Text>
      {rules.map((r, i) => (
        <div key={i} style={{ border: '1px solid #d9d9d9', borderRadius: 8, padding: 12, marginTop: 12 }}>
          <Space style={{ marginBottom: 8 }} wrap>
            <Text strong>规则 {i + 1}</Text>
            <Button size="small" disabled={i === 0} onClick={() => move(i, -1)}>↑</Button>
            <Button size="small" disabled={i === rules.length - 1} onClick={() => move(i, 1)}>↓</Button>
            <Button size="small" danger onClick={() => removeRule(i)}>删除规则</Button>
          </Space>
          <div style={{ marginBottom: 8 }}>
            <Text type="secondary">当满足：</Text>
            <CondTreeEditor value={r.when} columnOptions={columnOptions}
              onChange={nc => updateRule(i, { when: nc })} />
          </div>
          <Space>
            <Text type="secondary">则执行公式：</Text>
            <Select size="small" style={{ minWidth: 200 }} placeholder="选命中公式"
              value={r.formula || undefined} options={formulaOptions} showSearch optionFilterProp="label"
              onChange={v => updateRule(i, { formula: v })} />
          </Space>
        </div>
      ))}
      <Button type="dashed" block style={{ marginTop: 12 }} onClick={addRule}>+ 加规则</Button>
      <Divider />
      <Space>
        <Text strong>默认公式（全不命中）：</Text>
        <Select style={{ minWidth: 220 }} placeholder="必选默认公式"
          value={def || undefined} options={formulaOptions} showSearch optionFilterProp="label"
          onChange={setDef} />
      </Space>
    </Drawer>
  );
};

export default ConditionalFormulaDrawer;
```

- [ ] **Step 2: tsc + Vite**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5` → 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/ConditionalFormulaDrawer.tsx` → `200`。

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/component/ConditionalFormulaDrawer.tsx
git commit -m "feat(conditional-formula-ui): ConditionalFormulaDrawer 规则列表 + 默认公式"
```

---

## Task 3: FieldConfigTable 接入（模式切换 + 抽屉）

**Files:**
- Modify: `cpq-frontend/src/pages/component/FieldConfigTable.tsx`

- [ ] **Step 1: import + state**

顶部 import 加：

```tsx
import ConditionalFormulaDrawer, { type ConditionalFormulaValue } from './ConditionalFormulaDrawer';
```

state 区（`listFormulaKey` 旁 `:58`）加：

```tsx
  // Plan 3b：条件公式配置 Drawer 的目标字段 key
  const [condFormulaKey, setCondFormulaKey] = useState<string | null>(null);
```

- [ ] **Step 2: FORMULA 分支加「单一/条件」模式切换**

把 FORMULA 分支（`:280-312`）整体替换为（保留原单一模式 Select，加 Segmented 模式切换 + 条件模式入口）：

```tsx
        if (record.field_type === 'FORMULA') {
          const options = (formulas || [])
            .map(f => ({ value: f.name || '', label: `${f.name || '(未命名)'}${f.result_type ? ` · ${f.result_type}` : ''}` }))
            .filter(o => o.value);
          const isCond = !!record.conditional_formula;
          return (
            <Space size={4} wrap>
              <Segmented
                size="small"
                value={isCond ? 'cond' : 'single'}
                options={[{ label: '单一', value: 'single' }, { label: '条件', value: 'cond' }]}
                onChange={(m) => {
                  if (m === 'cond') updateField(record.key, { conditional_formula: { rules: [], default: '' } });
                  else updateField(record.key, { conditional_formula: undefined });
                }}
              />
              {!isCond && (
                options.length === 0
                  ? <Tooltip title="先去左侧「公式」Tab 添加公式定义"><Text type="secondary" style={{ fontSize: 12 }}>暂无公式可绑 →</Text></Tooltip>
                  : <Select size="small" style={{ minWidth: 160, fontSize: 12 }} placeholder="选择绑定公式" allowClear
                      value={record.formula_name || undefined} options={options}
                      onChange={(v) => updateField(record.key, { formula_name: v || undefined })}
                      showSearch optionFilterProp="label" />
              )}
              {isCond && (
                <Button size="small" type="link" onClick={() => setCondFormulaKey(record.key)}>
                  条件公式（{record.conditional_formula?.rules?.length || 0} 规则）配置 →
                </Button>
              )}
            </Space>
          );
        }
```

> `Segmented` 需在本文件 antd import 里（grep `from 'antd'` 确认；缺则补 `Segmented`）。

- [ ] **Step 3: 抽屉元素接线**

在 `ListFormulaConfigDrawer`（`:640`）旁加：

```tsx
      {/* Plan 3b：条件公式配置 Drawer */}
      <ConditionalFormulaDrawer
        open={condFormulaKey !== null}
        value={condFormulaKey ? fields.find(f => f.key === condFormulaKey)?.conditional_formula as ConditionalFormulaValue | undefined : undefined}
        fieldName={condFormulaKey ? fields.find(f => f.key === condFormulaKey)?.name : undefined}
        formulaOptions={(formulas || []).map(f => ({ label: f.name || '', value: f.name || '' })).filter(o => o.value)}
        columnOptions={fields.map(f => ({ label: f.name, value: f.name })).filter(o => o.value)}
        onClose={() => setCondFormulaKey(null)}
        onConfirm={(next) => {
          if (condFormulaKey) updateField(condFormulaKey, { conditional_formula: next });
          setCondFormulaKey(null);
        }}
      />
```

- [ ] **Step 4: 验证**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5` → 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/FieldConfigTable.tsx` → `200`。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/FieldConfigTable.tsx
git commit -m "feat(conditional-formula-ui): FieldConfigTable 单一/条件模式切换 + 抽屉接线"
```

---

## Task 4: 端到端手工验证 + 自检

- [ ] **Step 1: 组件管理配条件公式（手工）**

在「组件管理」选一个组件 → 字段配置 → 某 FORMULA 字段 → 切「条件」→ 配置 → 加 2 条规则（如 `类型 = 车削 → f_turn`、`类型 = 铣削 → f_mill`）+ 嵌套一个 `(数量 > 10 且 类型 = 车削) 或 加急 = 是` → 选默认 `f_base` → 确定 → 保存组件。
验证：保存成功（后端 Plan 3a 校验：默认必填/规则非空通过）；重新打开抽屉规则/条件树回填正确。

- [ ] **Step 2: 报价单验证求值（端到端串 3a 引擎）**

把该组件放进模板 → 报价单加产品 → 改某行「类型」为车削/铣削/其它 → 确认该 FORMULA 列按规则取不同公式结果（与 Plan 3a 单测口径一致）。

- [ ] **Step 3: E2E 回归（改了 FieldConfigTable，组件管理链路）**

```bash
cd cpq-frontend && rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list 2>&1 | tail -6
```
Expected: `1 passed` + `'加载中'=0`（条件 UI 不影响报价渲染回归）。

- [ ] **Step 4: 自检声明（CLAUDE.md 强制）**

> `condTreeEditor.test.tsx` passed ✅；tsc 0 错误 ✅；CondTreeEditor / ConditionalFormulaDrawer / FieldConfigTable → Vite 200 ✅；组件管理配条件公式保存+回填 ✅；报价单按规则取不同公式 ✅；E2E quotation-flow 1 passed + 加载中=0 ✅。

---

## Self-Review（写后自检）

**Spec coverage（设计 B）：**
- 选择式条件构建（不手敲）→ CondTreeEditor 全 Select/Segmented，仅叶子右值字面量 Input ✅
- 完整 AND/OR 嵌套 → CondTreeEditor 递归 group + 加分组 ✅
- 左值/列型右值 = 本组件列 → columnOptions ✅
- 有序规则 + 默认必填 → ConditionalFormulaDrawer 上移/下移 + 默认 Select + 确认校验 ✅
- 产出 conditional_formula JSON 被 3a 求值 → Task 4 端到端验证 ✅

**Placeholder scan：** Task 1 Step 2 / Task 3 Step 2 含「grep 确认 @testing-library/react / Segmented import」——是依赖/导入核对手段（给了判定 + 降级方案），非占位。其余完整代码。

**Type consistency：** `ConditionalFormulaValue = { rules:[{when:CondTree, formula:string}], default:string }` 与 Plan 3a `FieldItem.conditional_formula` 同形；`CondTreeEditor` props `value: CondTree` 复用 3a 类型；formulaOptions/columnOptions 均 `{label,value}[]`。

**边界/兼容：** 默认单一模式（无 conditional_formula）= 现状 formula_name Select 不变；切「条件」即写空 `{rules:[],default:''}`，配置抽屉补全；切回「单一」清空 conditional_formula。

**后续（明确不在本 Plan）：** Plan 3c —— ReadonlyProductCard 等三视图 AP-44 核对（条件公式在详情/核价视图渲染一致）+ 保存期硬环检测 + 条件公式完整 E2E（含 composite-product-flow）。
