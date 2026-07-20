import { describe, it, expect } from 'vitest';
import {
  ensureColumns,
  makeDefaultColumn,
  formatMetricLabel,
  buildTabPairLabel,
  columnDecimals,
  formatComparisonNumber,
  formatDiffNumber,
  getColumnValue,
  computeDiff,
  classifyDiff,
  rowIsDiff,
  sortRowsDiffFirst,
  filterRowsByPartNo,
  paginateRows,
  buildTabPairColumns,
  nextSortOrder,
  TAB_TOTAL_KEY,
  PRODUCT_TOTAL_COLUMN_ID,
} from './comparisonMapping';
import type { ColumnDef, ComparisonRowDTO } from '../../services/comparisonViewService';

const tabPairCol = (overrides: Partial<ColumnDef> = {}): ColumnDef => ({
  id: 'col-1',
  kind: 'TAB_PAIR',
  sortOrder: 1,
  threshold: 0,
  quoteComponentId: 'q-comp',
  quoteMetric: 'material_subtotal',
  quoteLabel: '投料·材料小计',
  costingComponentId: 'c-comp',
  costingMetric: '__TAB_TOTAL__',
  costingLabel: '投料成本·页签合计',
  ...overrides,
});

const row = (overrides: Partial<ComparisonRowDTO> = {}): ComparisonRowDTO => ({
  partNo: '3120018220',
  presence: 'BOTH',
  quote: { productTotal: 15500, tabs: { 'q-comp': { tabTotal: 8000, subtotals: { material_subtotal: 8000 } } } },
  costing: { productTotal: 14500, tabs: { 'c-comp': { tabTotal: 7500, subtotals: {} } } },
  ...overrides,
});

describe('ensureColumns', () => {
  it('columns=null → 种入默认列（PRODUCT_TOTAL，恒第一列）', () => {
    const cols = ensureColumns(null);
    expect(cols).toHaveLength(1);
    expect(cols[0].kind).toBe('PRODUCT_TOTAL');
    expect(cols[0].id).toBe(PRODUCT_TOTAL_COLUMN_ID);
  });

  it('缺 PRODUCT_TOTAL 时自动补齐在最前', () => {
    const cols = ensureColumns([tabPairCol()]);
    expect(cols[0].kind).toBe('PRODUCT_TOTAL');
    expect(cols[1].kind).toBe('TAB_PAIR');
  });

  it('PRODUCT_TOTAL 不在第一位时前置，不丢用户列 + 保留 threshold', () => {
    const pt = { ...makeDefaultColumn(), threshold: 100 };
    const cols = ensureColumns([tabPairCol(), pt]);
    expect(cols).toHaveLength(2);
    expect(cols[0].kind).toBe('PRODUCT_TOTAL');
    expect(cols[0].threshold).toBe(100);
    expect(cols[1].kind).toBe('TAB_PAIR');
  });

  it('已是 [PRODUCT_TOTAL, ...] → 原样返回', () => {
    const cols = ensureColumns([makeDefaultColumn(), tabPairCol()]);
    expect(cols[0].kind).toBe('PRODUCT_TOTAL');
    expect(cols[1].kind).toBe('TAB_PAIR');
  });
});

describe('formatMetricLabel / buildTabPairLabel', () => {
  it('末尾"小计"前插入间隔点', () => {
    expect(formatMetricLabel('加工费小计')).toBe('加工费·小计');
  });
  it('末尾"合计"前插入间隔点', () => {
    expect(formatMetricLabel('页签合计')).toBe('页签·合计');
  });
  it('非末尾出现的"小计"不处理', () => {
    expect(formatMetricLabel('小计说明')).toBe('小计说明');
  });
  it('空值兜底为空字符串', () => {
    expect(formatMetricLabel(undefined)).toBe('');
    expect(formatMetricLabel(null)).toBe('');
  });
  it('buildTabPairLabel 拼接 tab 名 + 格式化后的比对值名', () => {
    expect(buildTabPairLabel('投料', '材料小计')).toBe('投料·材料·小计');
    expect(buildTabPairLabel('投料', '页签合计')).toBe('投料·页签·合计');
  });
});

describe('columnDecimals', () => {
  it('PRODUCT_TOTAL 列 2 位', () => {
    expect(columnDecimals(makeDefaultColumn())).toBe(2);
  });
  it('TAB_PAIR 列 4 位', () => {
    expect(columnDecimals(tabPairCol())).toBe(4);
  });
});

describe('formatComparisonNumber / formatDiffNumber', () => {
  it('空值显示 —', () => {
    expect(formatComparisonNumber(undefined, 2)).toBe('—');
    expect(formatComparisonNumber(null, 4)).toBe('—');
    expect(formatDiffNumber(undefined, 2)).toBe('—');
  });
  it('固定小数位 + 千分位', () => {
    expect(formatComparisonNumber(15500, 2)).toBe('15,500.00');
    expect(formatComparisonNumber(8000, 4)).toBe('8,000.0000');
  });
  it('差异值正数带 + 号', () => {
    expect(formatDiffNumber(1000, 2)).toBe('+1,000.00');
  });
  it('差异值负数保留 - 号，不重复加号', () => {
    expect(formatDiffNumber(-500, 2)).toBe('-500.00');
  });
  it('差异值 0 不带符号', () => {
    expect(formatDiffNumber(0, 2)).toBe('0.00');
  });
});

describe('getColumnValue', () => {
  it('PRODUCT_TOTAL 取 side.productTotal', () => {
    const r = row();
    expect(getColumnValue(r, makeDefaultColumn(), 'quote')).toBe(15500);
    expect(getColumnValue(r, makeDefaultColumn(), 'costing')).toBe(14500);
  });
  it('TAB_PAIR 取 subtotals[metric]', () => {
    const r = row();
    expect(getColumnValue(r, tabPairCol(), 'quote')).toBe(8000);
  });
  it('TAB_PAIR metric=__TAB_TOTAL__ 取 tabTotal', () => {
    const r = row();
    const col = tabPairCol({ costingMetric: TAB_TOTAL_KEY });
    expect(getColumnValue(r, col, 'costing')).toBe(7500);
  });
  it('该侧 side 为 null（单边料号缺失侧）→ undefined', () => {
    const r = row({ costing: null, presence: 'QUOTE_ONLY' });
    expect(getColumnValue(r, makeDefaultColumn(), 'costing')).toBeUndefined();
  });
  it('componentId 命中但字段不存在 → undefined', () => {
    const r = row();
    const col = tabPairCol({ quoteMetric: 'not_exist_field' });
    expect(getColumnValue(r, col, 'quote')).toBeUndefined();
  });
  it('componentId 未命中该页签 → undefined', () => {
    const r = row();
    const col = tabPairCol({ quoteComponentId: 'no-such-tab' });
    expect(getColumnValue(r, col, 'quote')).toBeUndefined();
  });
});

describe('computeDiff / classifyDiff', () => {
  it('diff = quote - costing', () => {
    expect(computeDiff(15500, 14500)).toBe(1000);
  });
  it('任一侧 undefined → undefined', () => {
    expect(computeDiff(undefined, 14500)).toBeUndefined();
    expect(computeDiff(15500, undefined)).toBeUndefined();
  });
  it('diff < 0 → red（优先于阈值）', () => {
    expect(classifyDiff(-1, 100)).toBe('red');
  });
  it('diff >= 0 且 < threshold → orange', () => {
    expect(classifyDiff(50, 100)).toBe('orange');
  });
  it('diff >= threshold → none', () => {
    expect(classifyDiff(200, 100)).toBe('none');
  });
  it('diff = undefined → none（不参与着色）', () => {
    expect(classifyDiff(undefined, 100)).toBe('none');
  });
  it('threshold=0 默认场景：diff=0 → none（0 不小于 0）', () => {
    expect(classifyDiff(0, 0)).toBe('none');
  });
});

describe('rowIsDiff', () => {
  it('单边料号（QUOTE_ONLY）恒为差异料号，优先级最高', () => {
    const r = row({ presence: 'QUOTE_ONLY', costing: null });
    expect(rowIsDiff(r, [makeDefaultColumn()])).toBe(true);
  });
  it('BOTH 且所有列 diff>=threshold → 非差异料号', () => {
    const r = row({
      quote: { productTotal: 100, tabs: {} },
      costing: { productTotal: 100, tabs: {} },
    });
    expect(rowIsDiff(r, [makeDefaultColumn()])).toBe(false);
  });
  it('BOTH 但某列 diff<0 → 差异料号', () => {
    const r = row({
      quote: { productTotal: 100, tabs: {} },
      costing: { productTotal: 200, tabs: {} },
    });
    expect(rowIsDiff(r, [makeDefaultColumn()])).toBe(true);
  });
});

describe('sortRowsDiffFirst', () => {
  it('onlyDiff=false → 原样返回', () => {
    const rows = [row({ partNo: 'A' }), row({ partNo: 'B' })];
    expect(sortRowsDiffFirst(rows, [makeDefaultColumn()], false)).toEqual(rows);
  });
  it('onlyDiff=true → 差异料号前置，稳定排序（同优先级保持原相对顺序）', () => {
    const normal = row({ partNo: 'N1', quote: { productTotal: 100, tabs: {} }, costing: { productTotal: 100, tabs: {} } });
    const diffA = row({ partNo: 'D1', presence: 'QUOTE_ONLY', costing: null });
    const normal2 = row({ partNo: 'N2', quote: { productTotal: 100, tabs: {} }, costing: { productTotal: 100, tabs: {} } });
    const diffB = row({ partNo: 'D2', presence: 'COSTING_ONLY', quote: null });
    const rows = [normal, diffA, normal2, diffB];
    const sorted = sortRowsDiffFirst(rows, [makeDefaultColumn()], true);
    expect(sorted.map((r) => r.partNo)).toEqual(['D1', 'D2', 'N1', 'N2']);
  });
});

describe('filterRowsByPartNo', () => {
  const rows = [row({ partNo: '3120018220' }), row({ partNo: '3120018221' }), row({ partNo: 'ABC123' })];
  it('空过滤词 → 全部返回', () => {
    expect(filterRowsByPartNo(rows, '')).toHaveLength(3);
  });
  it('子串模糊匹配', () => {
    expect(filterRowsByPartNo(rows, '312').map((r) => r.partNo)).toEqual(['3120018220', '3120018221']);
  });
  it('无命中 → 空数组', () => {
    expect(filterRowsByPartNo(rows, 'ZZZ')).toHaveLength(0);
  });
});

describe('paginateRows', () => {
  const rows = Array.from({ length: 25 }, (_, i) => row({ partNo: `P${i}` }));
  it('第 1 页取前 pageSize 条', () => {
    expect(paginateRows(rows, 1, 10).map((r) => r.partNo)).toEqual(rows.slice(0, 10).map((r) => r.partNo));
  });
  it('最后一页可能不足 pageSize', () => {
    expect(paginateRows(rows, 3, 10)).toHaveLength(5);
  });
});

describe('buildTabPairColumns / nextSortOrder', () => {
  it('按连线顺序生成 TAB_PAIR 列，sortOrder 从 startSortOrder 递增', () => {
    const cols = buildTabPairColumns(
      [
        {
          quoteComponentId: 'q1', quoteMetric: 'm1', quoteTabName: '投料', quoteMetricLabel: '材料小计',
          costingComponentId: 'c1', costingMetric: TAB_TOTAL_KEY, costingTabName: '投料成本', costingMetricLabel: '页签合计',
          threshold: 0,
        },
        {
          quoteComponentId: 'q2', quoteMetric: 'm2', quoteTabName: '加工', quoteMetricLabel: '工时小计',
          costingComponentId: 'c2', costingMetric: 'm3', costingTabName: '加工成本', costingMetricLabel: '工时费小计',
          threshold: 500,
        },
      ],
      1,
    );
    expect(cols).toHaveLength(2);
    expect(cols[0].sortOrder).toBe(1);
    expect(cols[1].sortOrder).toBe(2);
    expect(cols[0].kind).toBe('TAB_PAIR');
    expect(cols[0].quoteLabel).toBe('投料·材料·小计');
    expect(cols[0].costingLabel).toBe('投料成本·页签·合计');
    expect(cols[1].threshold).toBe(500);
    // 每条生成不同 id
    expect(cols[0].id).not.toBe(cols[1].id);
  });

  it('nextSortOrder：空列表从 1 开始', () => {
    expect(nextSortOrder([])).toBe(1);
  });
  it('nextSortOrder：取现有最大 sortOrder + 1', () => {
    expect(nextSortOrder([makeDefaultColumn(), tabPairCol({ sortOrder: 3 })])).toBe(4);
  });
});
