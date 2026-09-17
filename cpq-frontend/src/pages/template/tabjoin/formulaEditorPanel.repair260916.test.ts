/**
 * repair-260916 F-12（D-9 / AC-18⑤）与 F-5（AC-10）：公式工具条与图例、占位文字。
 *
 * FormulaEditorPanel 不含 hook，直接调用并遍历返回的 React 元素树：
 * 工具条每组 = 一个 key 为组标题的 div，内含该组按钮（Button 的 children 即按钮名）。
 */
import { describe, it, expect } from 'vitest';
import React from 'react';
import FormulaEditorPanel from './FormulaEditorPanel';

function textOf(node: React.ReactNode): string {
  if (node == null || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(textOf).join('');
  if (React.isValidElement(node)) return textOf((node.props as any).children);
  return '';
}

function findAll(node: React.ReactNode, pred: (el: React.ReactElement) => boolean, out: React.ReactElement[] = []) {
  if (node == null || typeof node === 'boolean') return out;
  if (Array.isArray(node)) { node.forEach((n) => findAll(n, pred, out)); return out; }
  if (!React.isValidElement(node)) return out;
  if (pred(node)) out.push(node);
  // 不用 React.Children.toArray：它会改写 key（加 '.$' 前缀），这里要按原始 key 找分组
  findAll((node.props as any).children, pred, out);
  return out;
}

const noop = () => {};
const render = (componentType: 'NORMAL' | 'SUBTOTAL' | 'EXCEL', tabType?: string) =>
  FormulaEditorPanel({
    expression: '',
    onChange: noop,
    tabDefs: [],
    selfRowKeyFields: [],
    enforceMappable: componentType !== 'EXCEL',
    componentType,
    parenCheck: { ok: true } as any,
    inputRef: { current: null },
    onInsert: noop,
    onClearExpression: noop,
    onOpenSumif: noop,
    tabType,
  }) as React.ReactElement;

/** group title → button labels */
function toolGroups(root: React.ReactElement): Record<string, string[]> {
  const groups: Record<string, string[]> = {};
  const groupDivs = findAll(root, (el) => el.type === 'div' && typeof el.key === 'string'
    && ['运算符', '函数', '条件聚合', '父子取值'].includes(el.key));
  for (const g of groupDivs) {
    const buttons = findAll(g, (el) => typeof el.type !== 'string' && (el.props as any).size === 'small'
      && 'disabled' in (el.props as any));
    groups[g.key as string] = buttons.map((b) => textOf((b.props as any).children));
  }
  return groups;
}

const SUMIF_FNS = ['SUMIF', 'COUNTIF', 'AVGIF', 'MINIF', 'MAXIF'];

describe('F-12 条件聚合按钮组', () => {
  it('前置：能收集到工具条分组（非空）', () => {
    const g = toolGroups(render('NORMAL', 'BOM'));
    console.log('[NORMAL groups]', JSON.stringify(g));
    expect(Object.keys(g).length).toBeGreaterThan(0);
    expect(g['运算符']).toEqual(['+', '-', '*', '/', '(', ')']);
  });

  it('AC-18⑤ Excel 组件：没有「条件聚合」组，也没有任何 SUMIF 类按钮', () => {
    const g = toolGroups(render('EXCEL'));
    console.log('[EXCEL groups]', JSON.stringify(g));
    expect(Object.keys(g)).toEqual(['运算符', '函数']);
    expect(Object.values(g).flat().filter((l) => SUMIF_FNS.includes(l))).toEqual([]);
  });

  it('对照：页签组件、小计组件仍有这组按钮', () => {
    expect(toolGroups(render('NORMAL'))['条件聚合']).toEqual(SUMIF_FNS);
    expect(toolGroups(render('SUBTOTAL'))['条件聚合']).toEqual(SUMIF_FNS);
  });
});

describe('F-5 图例与占位文字（AC-10，原型状态 A / F）', () => {
  const root = render('NORMAL');
  const allText = textOf(findAll(root, (el) => el.type === 'span' || el.type === 'div') as any);

  it('图例含「普通引用 · [页签.列]」与「小计 · [页签.列(小计)]」', () => {
    expect(allText).toContain('普通引用 · [页签.列]');
    expect(allText).toContain('小计 · [页签.列(小计)]');
  });

  it('公式框占位文字与原型逐字一致', () => {
    const input = findAll(root, (el) => typeof (el.props as any).placeholder === 'string')[0];
    expect((input.props as any).placeholder).toBe('例:[投料.金额] * [加工.工时] + [回料.金额(小计)] + [回料(总计)]');
  });
});
