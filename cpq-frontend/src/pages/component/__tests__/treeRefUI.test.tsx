/**
 * treeRefUI.test.tsx — task-0803 Task 8：公式配置 UI（父子取值分区 + TreeRefDrawer + chip 文案）
 *
 * 覆盖范围（需求 §4.2 F-1~F-8）：
 *   1. crossTabText.ts 新增纯函数（chip 文案 / 禁用 tooltip 文案）——逐字比对定稿文案。
 *   2. FormulaZone 渲染 tree_ref/tree_attr token 时的 chip 文案（走真实渲染管线，非孤立测函数）。
 *   3. FormulaBuilder 的「父子取值」分区：BOM 组件按钮 enabled；非 BOM 组件按钮 disabled
 *      **且元素仍在渲染输出里**（证明是置灰不是 `return null` 隐藏）。
 *   4. TreeRefDrawer 导出的 checkTreeRefTargetExpr（F-7 保存门禁，直接复用 Task 6 的
 *      validateTreeRefWhitelist，不重写校验规则）。
 *
 * 测试手段说明（重要）：本项目未安装 `@testing-library/react` / `jsdom`（见同目录
 * `RowKeyConflictDrawer.test.tsx` 顶部注释），且 `node_modules` 是跨多个并发 worktree 共享的
 * 目录，不能为了本任务临时新增依赖（会影响其他并发会话）。因此：
 *   - 对"是否渲染到输出、disabled 属性是否存在"这类断言，使用 `react-dom/server` 的
 *     `renderToStaticMarkup`（Node 环境可跑，不需要 DOM/jsdom）直接渲染真实组件树取 HTML
 *     字符串断言——这是"真实渲染输出"而非手抄结构体，能如实证明"按钮还在输出里、
 *     只是带 disabled 属性"（AntD `Tooltip` 的浮层内容本身走 Portal，SSR 不输出，
 *     所以 tooltip 文案改用 `data-treeref-disabled-reason` 属性桥接验证——该属性与
 *     Tooltip 的 title 用的是同一个变量，见 FormulaBuilder.tsx）。
 *   - 对 AntD `Drawer`（内部用 `createPortal` 挂 `document.body`，SSR 环境下恒渲染为空
 *     字符串，实测见开发自检）不做渲染断言，改为直接单测其导出的纯函数
 *     `checkTreeRefTargetExpr`——这与组件内部实际调用路径完全一致，非另一套测试专用逻辑。
 */
import { describe, it, expect } from 'vitest';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import FormulaZone from '../../../components/formula/FormulaZone';
import FormulaBuilder from '../FormulaBuilder';
import TreeRefDrawer, { checkTreeRefTargetExpr, TREE_REF_TARGET_EXPR_VIOLATION_TEXT } from '../TreeRefDrawer';
import {
  treeRefDisabledTooltip,
  treeAttrChipLabel,
  treeRefChipLabel,
  treeRefFuncKey,
  TAB_TYPE_UNSET_LABEL,
} from '../crossTabText';
import type { FormulaItem, FormulaToken } from '../types';

// ─────────────────────────────────────────────
// 小工具：从 renderToStaticMarkup 输出里摘出某个 <button>...</button> 整段（判 disabled 用）
// ─────────────────────────────────────────────
function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
function findButtonTag(html: string, text: string): string | undefined {
  const re = new RegExp(`<button([^>]*)>${escapeRegExp(text)}</button>`);
  const m = html.match(re);
  return m ? m[0] : undefined;
}

function mkFormula(key: string, expression: FormulaToken[] = []): FormulaItem {
  return { key, id: key, name: key, expression, result_type: 'NUMBER' };
}

const FIELDS = [
  { name: '用量', type: 'INPUT_NUMBER' },
  { name: '单价', type: 'INPUT_NUMBER' },
  { name: '成本', type: 'FORMULA' },
];

// ─────────────────────────────────────────────
// 1. crossTabText.ts 纯函数 —— chip 文案 / tooltip 文案逐字定稿比对（需求 §11.5.3）
// ─────────────────────────────────────────────
describe('crossTabText —— tree_ref/tree_attr chip 文案 + 禁用 tooltip 文案（F-5/F-6/F-2，逐字定稿）', () => {
  it('F-6：树属性 chip 文案', () => {
    expect(treeAttrChipLabel('LVL')).toBe('[层级]');
    expect(treeAttrChipLabel('IS_LEAF')).toBe('[是否叶子]');
    expect(treeAttrChipLabel('IS_ROOT')).toBe('[是否根]');
  });

  it('F-5：6 个函数的 chip 文案（函数名(表达式)）—— 逐字比对定稿表格', () => {
    const expr: FormulaToken[] = [{ type: 'field', value: '累计用量', label: '累计用量' }];
    expect(treeRefFuncKey('PARENT', 'NONE')).toBe('PGET');
    expect(treeRefChipLabel('PARENT', 'NONE', expr)).toBe('父行(累计用量)');

    const mulExpr: FormulaToken[] = [
      { type: 'field', value: '用量', label: '用量' },
      { type: 'operator', value: '*' },
      { type: 'field', value: '单价', label: '单价' },
    ];
    expect(treeRefChipLabel('CHILD', 'SUM', mulExpr)).toBe('子行合计(用量 × 单价)');
    expect(treeRefChipLabel('CHILD', 'AVG', mulExpr)).toBe('子行均值(用量 × 单价)');
    expect(treeRefChipLabel('CHILD', 'MAX', mulExpr)).toBe('子行最大(用量 × 单价)');
    expect(treeRefChipLabel('CHILD', 'MIN', mulExpr)).toBe('子行最小(用量 × 单价)');
    expect(treeRefChipLabel('CHILD', 'COUNT', mulExpr)).toBe('子行计数(用量 × 单价)');
  });

  it('F-2：非 BOM 禁用 tooltip 文案 —— 含人类可读标签，逐字定稿', () => {
    expect(treeRefDisabledTooltip('零件')).toBe('仅 BOM 类型页签可用（当前页签类型：零件）');
    expect(treeRefDisabledTooltip('材质元素')).toBe('仅 BOM 类型页签可用（当前页签类型：材质元素）');
    expect(treeRefDisabledTooltip('外购件')).toBe('仅 BOM 类型页签可用（当前页签类型：外购件）');
    expect(treeRefDisabledTooltip('主件')).toBe('仅 BOM 类型页签可用（当前页签类型：主件）');
  });

  it('F-2：页签类型未配置（undefined/空串）→ 兜底显示"未配置"，不显示内部枚举 code', () => {
    expect(TAB_TYPE_UNSET_LABEL).toBe('未配置');
    expect(treeRefDisabledTooltip(undefined)).toBe('仅 BOM 类型页签可用（当前页签类型：未配置）');
    expect(treeRefDisabledTooltip(null)).toBe('仅 BOM 类型页签可用（当前页签类型：未配置）');
    expect(treeRefDisabledTooltip('')).toBe('仅 BOM 类型页签可用（当前页签类型：未配置）');
  });
});

// ─────────────────────────────────────────────
// 2. FormulaZone 渲染 tree_ref/tree_attr token —— 走真实渲染管线（非孤立测函数）
// ─────────────────────────────────────────────
describe('FormulaZone —— tree_ref/tree_attr token 的 chip 渲染（真实渲染输出）', () => {
  it('tree_ref(PGET) token 渲染为「父行(累计用量)」chip，且带 data-token-type 标记', () => {
    const tokens: FormulaToken[] = [
      { type: 'tree_ref', dir: 'PARENT', agg: 'NONE', targetExpr: [{ type: 'field', value: '累计用量', label: '累计用量' }] },
    ];
    const html = renderToStaticMarkup(<FormulaZone tokens={tokens} onChange={() => {}} />);
    expect(html).toContain('父行(累计用量)');
    expect(html).toContain('data-token-type="tree_ref"');
  });

  it('tree_ref(CSUM) 混合表达式渲染为「子行合计(用量 × 单价)」', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'tree_ref', dir: 'CHILD', agg: 'SUM',
        targetExpr: [
          { type: 'field', value: '用量', label: '用量' },
          { type: 'operator', value: '*' },
          { type: 'field', value: '单价', label: '单价' },
        ],
      },
    ];
    const html = renderToStaticMarkup(<FormulaZone tokens={tokens} onChange={() => {}} />);
    expect(html).toContain('子行合计(用量 × 单价)');
  });

  it('tree_attr(IS_LEAF) 渲染为「[是否叶子]」chip', () => {
    const tokens: FormulaToken[] = [{ type: 'tree_attr', attr: 'IS_LEAF' }];
    const html = renderToStaticMarkup(<FormulaZone tokens={tokens} onChange={() => {}} />);
    expect(html).toContain('[是否叶子]');
    expect(html).toContain('data-token-type="tree_attr"');
  });
});

// ─────────────────────────────────────────────
// 3. FormulaBuilder —— 「父子取值」分区：BOM 启用 / 非 BOM 置灰不隐藏
// ─────────────────────────────────────────────
describe('FormulaBuilder —— 父子取值分区（F-1/F-2，禁用不隐藏）', () => {
  it('BOM 组件 + 有活动公式 → 6 个函数按钮 + 3 个树属性按钮均 enabled（无 disabled 属性）', () => {
    const formulas = [mkFormula('f1')];
    const html = renderToStaticMarkup(
      <FormulaBuilder
        formulas={formulas}
        onChange={() => {}}
        availableFields={FIELDS}
        availableSubtotals={[]}
        activeFormulaKey="f1"
        onActiveFormulaKeyChange={() => {}}
        tabType="BOM"
      />,
    );
    for (const label of ['PGET', 'CSUM', 'CAVG', 'CMAX', 'CMIN', 'CCOUNT']) {
      const tag = findButtonTag(html, label);
      expect(tag, `按钮「${label}」应存在于渲染输出`).toBeTruthy();
      expect(tag).not.toMatch(/disabled/);
    }
    for (const label of ['[层级]', '[是否叶子]', '[是否根]']) {
      const tag = findButtonTag(html, label);
      expect(tag, `按钮「${label}」应存在于渲染输出`).toBeTruthy();
      expect(tag).not.toMatch(/disabled/);
    }
    // BOM 场景下不应触发"仅 BOM 类型页签可用"禁用原因
    expect(html).toContain('data-treeref-disabled-reason=""');
  });

  it('非 BOM 组件（零件）→ 分区按钮 disabled 且元素仍在渲染输出里（不是 return null 隐藏）', () => {
    const formulas = [mkFormula('f1')];
    const html = renderToStaticMarkup(
      <FormulaBuilder
        formulas={formulas}
        onChange={() => {}}
        availableFields={FIELDS}
        availableSubtotals={[]}
        activeFormulaKey="f1"
        onActiveFormulaKeyChange={() => {}}
        tabType="零件"
      />,
    );
    for (const label of ['PGET', 'CSUM', 'CAVG', 'CMAX', 'CMIN', 'CCOUNT', '[层级]', '[是否叶子]', '[是否根]']) {
      const tag = findButtonTag(html, label);
      // 关键断言：元素必须"存在"（不是 undefined / 不是被 return null 抹掉），且带 disabled 属性
      expect(tag, `按钮「${label}」应仍存在于渲染输出（禁用≠隐藏）`).toBeTruthy();
      expect(tag).toMatch(/disabled=""/);
    }
  });

  it('非 BOM 组件 —— tooltip 文案通过 data-treeref-disabled-reason 桥接验证，逐字比对', () => {
    const formulas = [mkFormula('f1')];
    const html = renderToStaticMarkup(
      <FormulaBuilder
        formulas={formulas}
        onChange={() => {}}
        availableFields={FIELDS}
        availableSubtotals={[]}
        activeFormulaKey="f1"
        onActiveFormulaKeyChange={() => {}}
        tabType="零件"
      />,
    );
    expect(html).toContain('data-treeref-disabled-reason="仅 BOM 类型页签可用（当前页签类型：零件）"');
  });

  it('tabType 未配置（undefined）—— tooltip 兜底"未配置"', () => {
    const formulas = [mkFormula('f1')];
    const html = renderToStaticMarkup(
      <FormulaBuilder
        formulas={formulas}
        onChange={() => {}}
        availableFields={FIELDS}
        availableSubtotals={[]}
        activeFormulaKey="f1"
        onActiveFormulaKeyChange={() => {}}
      />,
    );
    expect(html).toContain('data-treeref-disabled-reason="仅 BOM 类型页签可用（当前页签类型：未配置）"');
  });

  it('BOM 组件但无活动公式 → 按钮仍 disabled（另一独立原因：无落点），但不应出现"仅 BOM 可用"提示', () => {
    const formulas = [mkFormula('f1')];
    const html = renderToStaticMarkup(
      <FormulaBuilder
        formulas={formulas}
        onChange={() => {}}
        availableFields={FIELDS}
        availableSubtotals={[]}
        activeFormulaKey={null}
        onActiveFormulaKeyChange={() => {}}
        tabType="BOM"
      />,
    );
    const tag = findButtonTag(html, 'PGET');
    expect(tag).toMatch(/disabled=""/);
    // 原因是"未激活公式行"而非"非 BOM"，data-treeref-disabled-reason 应为空
    expect(html).toContain('data-treeref-disabled-reason=""');
    expect(html).not.toContain('仅 BOM 类型页签可用');
  });
});

// ─────────────────────────────────────────────
// 4. TreeRefDrawer —— F-7 保存门禁（直接复用 Task 6 validateTreeRefWhitelist）
// ─────────────────────────────────────────────
describe('TreeRefDrawer —— checkTreeRefTargetExpr（F-7 内层白名单保存门禁）', () => {
  it('默认导出是一个 React 函数组件', () => {
    expect(typeof TreeRefDrawer).toBe('function');
  });

  it('F-7 红字文案定稿（逐字）', () => {
    expect(TREE_REF_TARGET_EXPR_VIOLATION_TEXT).toBe(
      '父子取值的目标表达式内不能再引用跨页签数据或其他父子取值，请改用本页签的列',
    );
  });

  it('表达式全合法（field/operator/number/tree_attr）→ canSave=true', () => {
    const legal: FormulaToken[] = [
      { type: 'field', value: '用量' },
      { type: 'operator', value: '*' },
      { type: 'field', value: '单价' },
      { type: 'operator', value: '+' },
      { type: 'tree_attr', attr: 'LVL' },
    ];
    expect(checkTreeRefTargetExpr('CHILD', 'SUM', legal).canSave).toBe(true);
  });

  it('表达式含 cross_tab_ref → canSave=false（保存按钮应 disabled + 显示红字）', () => {
    const illegal: FormulaToken[] = [
      { type: 'field', value: '用量' },
      {
        type: 'cross_tab_ref',
        source: '回料',
        agg: 'SUM',
        match: [{ a: '料号', b: '料号' }],
      },
    ];
    expect(checkTreeRefTargetExpr('CHILD', 'SUM', illegal).canSave).toBe(false);
  });

  it('表达式含嵌套 tree_ref → canSave=false（禁止 PGET/C* 套 PGET/C*）', () => {
    const illegal: FormulaToken[] = [
      { type: 'field', value: '用量' },
      { type: 'tree_ref', dir: 'PARENT', agg: 'NONE', targetExpr: [{ type: 'field', value: '累计用量' }] },
    ];
    expect(checkTreeRefTargetExpr('CHILD', 'SUM', illegal).canSave).toBe(false);
  });

  it('空表达式 → 白名单校验本身放行（canSave=true）；抽屉另有"请先构建表达式"的确认期拦截，非本函数职责', () => {
    expect(checkTreeRefTargetExpr('PARENT', 'NONE', []).canSave).toBe(true);
  });
});
