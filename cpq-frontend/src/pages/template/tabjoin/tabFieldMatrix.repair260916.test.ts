/**
 * repair-260916 F-4 — 左栏选项插入的文字（AC-1 / AC-2 / AC-7 / AC-9）。
 *
 * 本项目未引入 @testing-library/react；TabFieldMatrix 是无 hook 的纯函数组件，
 * 这里直接调用它并遍历返回的 React 元素树，收集「分组标签 → 选项显示文字 → 点击插入的文字」。
 * 插入文字经 onClick 真实调用 onInsert 取得（不是读源码字面量）。
 */
import { describe, it, expect } from 'vitest';
import React from 'react';
import TabFieldMatrix from './TabFieldMatrix';
import type { TabDef } from '../../../services/tabJoinFormulaService';

type Chip = { group: string; label: string; inserted: string | null; disabledTip: string | null };

function textOf(node: React.ReactNode): string {
  if (node == null || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(textOf).join('');
  if (React.isValidElement(node)) return textOf((node.props as any).children);
  return '';
}

/** Walk the element tree; a "group" is a Space whose first child is the group-label span. */
function collectChips(root: React.ReactNode): Chip[] {
  const chips: Chip[] = [];
  const walk = (node: React.ReactNode, group: string, tip: string | null) => {
    if (node == null || typeof node === 'boolean') return;
    if (Array.isArray(node)) { node.forEach((n) => walk(n, group, tip)); return; }
    if (!React.isValidElement(node)) return;
    const props = node.props as any;
    const kids = React.Children.toArray(props.children);
    // group container: first child is a <span> label like 明细 / 小计列 / 页签总计
    let g = group;
    const first = kids[0];
    if (React.isValidElement(first) && first.type === 'span') {
      const t = textOf((first.props as any).children);
      if (/^(明细|小计列|页签总计)/.test(t)) g = t;
    }
    const isTooltip = typeof props.title === 'string' && kids.length === 1 && !props.onClick
      && React.isValidElement(kids[0]) && (kids[0] as any).type !== 'span';
    if (isTooltip) { walk(kids[0], g, props.title); return; }
    if ((node.type as any)?.displayName === 'Tag' || (node.type as any)?.name === 'Tag' || (props.style && 'userSelect' in props.style && props.style.cursor)) {
      let inserted: string | null = null;
      if (typeof props.onClick === 'function') props.onClick();
      inserted = lastInserted;
      lastInserted = null;
      chips.push({ group: g, label: textOf(props.children), inserted, disabledTip: props.onClick ? null : tip });
      return;
    }
    kids.forEach((k) => walk(k, g, tip));
  };
  walk(root, '', null);
  return chips;
}

let lastInserted: string | null = null;
const render = (tabDefs: TabDef[], selfRowKeyFields: string[]) =>
  collectChips(TabFieldMatrix({ tabDefs, selfRowKeyFields, onInsert: (s: string) => { lastInserted = s; } }) as any);

const HOST: TabDef = {
  alias: 'COMP-9001', tabKey: 'cid-host', componentId: 'cid-host', componentName: 'RP0916宿主', self: true,
  rowKeyFields: ['销售料号', '料号'], detailFields: ['数量'], subtotalCols: ['数量'],
};
const FEE: TabDef = {
  alias: 'COMP-9002', tabKey: 'cid-fee', componentId: 'cid-fee', componentName: 'RP0916加工费',
  rowKeyFields: ['销售料号', '料号'], detailFields: ['加工费', '备注数'], subtotalCols: ['加工费'],
};
const PROC: TabDef = {
  alias: 'COMP-9003', tabKey: 'cid-proc', componentId: 'cid-proc', componentName: 'RP0916工序',
  rowKeyFields: ['工序'], detailFields: ['工时'], subtotalCols: [],
};
const RKF = ['销售料号', '料号'];

describe('TabFieldMatrix — repair-260916 插入文字', () => {
  const chips = render([HOST, FEE, PROC], RKF);

  it('前置：收集到了选项（非空）', () => {
    expect(chips.length).toBeGreaterThan(0);
    console.log('[chips]', JSON.stringify(chips));
  });

  it('AC-1 「明细」组「加工费」→ [RP0916加工费.加工费]', () => {
    const c = chips.find((x) => x.group === '明细' && x.label === '加工费');
    expect(c?.inserted).toBe('[RP0916加工费.加工费]');
  });

  it('AC-2 「小计列」组显示「加工费(小计)」→ 插入 [RP0916加工费.加工费(小计)]', () => {
    const c = chips.find((x) => x.group === '小计列');
    expect(c).toEqual({ group: '小计列', label: '加工费(小计)', inserted: '[RP0916加工费.加工费(小计)]', disabledTip: null });
  });

  it('AC-7① 本页签卡片没有「小计列」组；② 其明细「数量」插入 [数量]', () => {
    const hostOnly = render([HOST], RKF);
    expect(hostOnly.some((x) => x.group === '小计列')).toBe(false);
    expect(hostOnly.find((x) => x.label === '数量')?.inserted).toBe('[数量]');
    expect(hostOnly.map((x) => x.group)).toEqual(['明细(本页签·同行)', '页签总计']);
  });

  it('全局只有一张非本页签卡片有小计列组（即 RP0916加工费）', () => {
    expect(chips.filter((x) => x.group === '小计列').map((x) => x.inserted)).toEqual(['[RP0916加工费.加工费(小计)]']);
  });

  it('页签总计插入不变', () => {
    expect(chips.filter((x) => x.group === '页签总计').map((x) => x.inserted)).toEqual([
      '[RP0916宿主(总计)]', '[RP0916加工费(总计)]', '[RP0916工序(总计)]',
    ]);
  });

  it('AC-8a 不可比页签明细置灰不可点，悬停文案不变', () => {
    const c = chips.find((x) => x.label === '工时');
    expect(c?.inserted).toBeNull();
    expect(c?.disabledTip).toBe('行键 [工序] 与宿主 [销售料号+料号] 不可比；可改用「RP0916工序(总计)」');
  });

  it('AC-9 搜索态（TabFieldPanel 传入过滤后的 def 副本）插入文字与非搜索态逐字相同', () => {
    const filtered = [{ ...FEE, detailFields: ['加工费'], subtotalCols: ['加工费'] }];
    const s = render(filtered, RKF);
    expect(s.find((x) => x.group === '明细')?.inserted).toBe('[RP0916加工费.加工费]');
    expect(s.find((x) => x.group === '小计列')?.inserted).toBe('[RP0916加工费.加工费(小计)]');
  });

  it('名称缺失时引用串回退编号，小计仍带后缀', () => {
    const s = render([{ ...FEE, componentName: undefined }], RKF);
    expect(s.find((x) => x.group === '小计列')?.inserted).toBe('[COMP-9002.加工费(小计)]');
  });
});
