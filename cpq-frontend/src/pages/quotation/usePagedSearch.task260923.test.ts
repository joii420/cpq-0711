/**
 * task-260923 单内搜索：回车触发 + 生产料号 —— usePagedSearch 纯函数单测。
 *
 * 本项目无 @testing-library/react / jsdom，无法渲染 hook；hook 内部的状态迁移全部委托给这里测的纯函数
 * （applySearchInput / applySearchSubmit / matchSearchPositions / isSubmitEnterKey），测试直接 import 实现，不手抄副本。
 * 样本数据 = 任务.md ③「样本数据」表 + 原型图 index.html「示例数据」的 14 个产品与顺序。
 */
import { describe, it, expect } from 'vitest';
import {
  INITIAL_PAGED_SEARCH_STATE,
  applySearchInput,
  applySearchSubmit,
  matchSearchPositions,
  isSubmitEnterKey,
  type PagedSearchState,
} from './usePagedSearch';

interface Li {
  productPartNo: string;
  customerProductNo?: string;
  customerPartName?: string;
  hfPartInfo?: { partNo?: string };
}

// [销售料号, 生产料号, 客户产品编号, 客户料号名称]，顺序同样本单 A
const ROWS: Array<[string, string, string, string]> = [
  ['S0001', '300001', 'A002', '示例客户料号'],
  ['S0002', '300002', '', ''],
  ['S0003', '300003', '', ''],
  ['S0008', '300031', 'TC-B008', '测试客户铆钉组件B'],
  ['S0009', '300032', '', ''],
  ['S0010', '300033', '', ''],
  ['S0011', '300034', '', ''],
  ['S0012', '300041', 'ZT-C012', '正泰端子组件C'],
  ['S0013', '300042', '', ''],
  ['S0014', '300043', '', ''],
  ['S0004', '300021', 'RW-A004', '罗克韦尔触桥组件A'],
  ['S0005', '300022', '', ''],
  ['S0006', '300023', '', ''],
  ['S0007', '300024', '', ''],
];
const ITEMS: Li[] = ROWS.map(([sale, prod, cpn, cname]) => ({
  productPartNo: sale,
  customerProductNo: cpn || undefined,
  customerPartName: cname || undefined,
  hfPartInfo: { partNo: prod },
}));
// 与 QuotationStep2 / ProductDetailViews 的 getSearchFields 同形（四个字段）
const FIELDS = (li: Li) => [li.productPartNo, li.customerProductNo, li.customerPartName, li.hfPartInfo?.partNo];

const term = (st: PagedSearchState) => st.submitted.toLowerCase();
const hitSales = (st: PagedSearchState) =>
  matchSearchPositions(ITEMS, FIELDS, term(st)).map(i => ITEMS[i].productPartNo);

describe('task-260923 · 回车才生效（F-1 / AC-2 AC-3 AC-9⑤）', () => {
  it('打字只改草稿，生效查询保持不变', () => {
    const st = applySearchInput(INITIAL_PAGED_SEARCH_STATE, '30002');
    expect(st).toEqual({ input: '30002', submitted: '' });
    expect(hitSales(st)).toHaveLength(14);
  });

  it('回车后按生产料号命中 S0004~S0007（第 11~14 位）', () => {
    const st = applySearchSubmit(applySearchInput(INITIAL_PAGED_SEARCH_STATE, '30002'));
    expect(st.submitted).toBe('30002');
    expect(hitSales(st)).toEqual(['S0004', 'S0005', 'S0006', 'S0007']);
    // 下标指向原数组（AP-54）：第 11~14 位
    expect(matchSearchPositions(ITEMS, FIELDS, term(st))).toEqual([10, 11, 12, 13]);
  });

  it('已生效后改草稿不回车，结果不变；再回车才切换', () => {
    let st = applySearchSubmit(applySearchInput(INITIAL_PAGED_SEARCH_STATE, '30002'));
    st = applySearchInput(st, '30003');
    expect(hitSales(st)).toEqual(['S0004', 'S0005', 'S0006', 'S0007']);
    st = applySearchSubmit(st);
    expect(hitSales(st)).toEqual(['S0008', 'S0009', 'S0010', 'S0011']);
  });

  it('submit 显式传入 raw 时以 raw 为准（PagingBar 传 e.currentTarget.value）', () => {
    const st = applySearchSubmit({ input: 'stale', submitted: '' }, '  300034  ');
    expect(st).toEqual({ input: '  300034  ', submitted: '300034' });
    expect(hitSales(st)).toEqual(['S0011']);
  });
});

describe('task-260923 · 变空即恢复（F-1 / AC-5 AC-6）', () => {
  const searched = applySearchSubmit(applySearchInput(INITIAL_PAGED_SEARCH_STATE, '30002'));

  it('✕ / 删光：草稿变空串立即清空生效查询', () => {
    const st = applySearchInput(searched, '');
    expect(st).toEqual({ input: '', submitted: '' });
    expect(hitSales(st)).toHaveLength(14);
  });

  it('只剩 3 个空格：立即清空生效查询，草稿保留原样', () => {
    const st = applySearchInput(searched, '   ');
    expect(st).toEqual({ input: '   ', submitted: '' });
    expect(hitSales(st)).toHaveLength(14);
  });

  it('删光后再输入并回车，重新生效', () => {
    const st = applySearchSubmit(applySearchInput(applySearchInput(searched, ''), '30002'));
    expect(hitSales(st)).toEqual(['S0004', 'S0005', 'S0006', 'S0007']);
  });
});

describe('task-260923 · 空态引用最近一次提交的原文（F-1 / AC-7 AC-12）', () => {
  it('保留大小写、去首尾空格；追加字符不回车时不变', () => {
    let st = applySearchSubmit(applySearchInput(INITIAL_PAGED_SEARCH_STATE, '  XYZ-T260923 '));
    expect(st.submitted).toBe('XYZ-T260923');
    expect(hitSales(st)).toEqual([]);
    st = applySearchInput(st, '  XYZ-T260923 -9');
    expect(st.submitted).toBe('XYZ-T260923');
  });

  it('120 字符原样保留', () => {
    const long = 'T260923-' + 'Z'.repeat(112);
    expect(long).toHaveLength(120);
    const st = applySearchSubmit(applySearchInput(INITIAL_PAGED_SEARCH_STATE, long));
    expect(st.submitted).toBe(long);
    expect(hitSales(st)).toEqual([]);
  });
});

describe('task-260923 · 匹配字段含生产料号，原三字段照常（F-3 F-4 / AC-4 AC-10）', () => {
  const run = (q: string) => hitSales(applySearchSubmit(applySearchInput(INITIAL_PAGED_SEARCH_STATE, q)));
  it('销售料号', () => expect(run('S0011')).toEqual(['S0011']));
  it('客户产品编号大小写不敏感', () => expect(run('zt-c012')).toEqual(['S0012']));
  it('客户料号名称', () => expect(run('正泰端子')).toEqual(['S0012']));
  it('生产料号 300034 只命中 S0011', () => expect(run('300034')).toEqual(['S0011']));
  it('生产料号缺失（hfPartInfo 为空）不报错、不命中', () => {
    const items: Li[] = [{ productPartNo: 'S0099' }, { productPartNo: 'S0098', hfPartInfo: {} }];
    expect(matchSearchPositions(items, FIELDS, '300')).toEqual([]);
  });
});

describe('task-260923 · 输入法组字中的回车不提交（F-2 / AC-8）', () => {
  it('普通回车提交', () => {
    expect(isSubmitEnterKey({ key: 'Enter', isComposing: false, keyCode: 13 })).toBe(true);
  });
  it('AC-8 合成事件形态：key=Enter + isComposing=true（keyCode 0）不提交', () => {
    expect(isSubmitEnterKey({ key: 'Enter', isComposing: true, keyCode: 0 })).toBe(false);
  });
  it('Safari 组字确认：isComposing=false 但 keyCode=229 不提交', () => {
    expect(isSubmitEnterKey({ key: 'Enter', isComposing: false, keyCode: 229 })).toBe(false);
  });
  it('非回车键不提交', () => {
    expect(isSubmitEnterKey({ key: 'a', isComposing: false, keyCode: 65 })).toBe(false);
    expect(isSubmitEnterKey({ key: 'Process', isComposing: true, keyCode: 229 })).toBe(false);
  });
});
