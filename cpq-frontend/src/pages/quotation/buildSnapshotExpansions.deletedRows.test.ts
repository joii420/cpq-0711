/**
 * buildSnapshotExpansions 墓碑过滤单测
 *
 * 验证 AP-54 头号不变量：
 *   1. effKey 在完整集 baseRows 上算（不受过滤影响）
 *   2. 按墓碑双命中（effKey+fp）剔除整行
 *   3. 过滤后不重算 key / 不重排剩余行
 *   4. COSTING 侧绝不过滤
 *   5. deletedRowKeys 不进 driverExpansionKey
 */
import { describe, it, expect } from 'vitest';
import { buildSnapshotExpansions } from './QuotationStep2';
import { rowFingerprint } from './deletedRows';
import { driverExpansionKey } from './useDriverExpansions';

// ── 测试夹具构造器 ─────────────────────────────────────────────────────────────

/** 构造一个最小 driverRow（使 rowFingerprint 可计算） */
function makeDriverRow(partNo: string, qty: number) {
  return { 料件: partNo, 数量: qty };
}

/** 构造 baseRow（快照 vtab.baseRows 中的单行） */
function makeBaseRow(partNo: string, qty: number) {
  return {
    driverRow: makeDriverRow(partNo, qty),
    basicDataValues: { price: qty * 10 },
  };
}

const ROW_KEY_FIELDS = ['料件'];
const COMP_ID = 'comp-001';
const PART_NO = 'TEST-001';
const LINE_ITEM_ID = 'li-001';
const CUSTOMER_ID = 'cust-001';

/**
 * 最小 fields：只需足够让 buildUniqueRowKeys → computeRowKey 能直读 driverRow['料件']。
 * 字段名与 rowKeyFields 一致，defaultSource 留 undefined（直读 driverRow 路径）。
 */
const FIELDS = [{ name: '料件', fieldType: 'INPUT', defaultSource: undefined }];

/** 构造一个 LineItem（仅包含 buildSnapshotExpansions 需要的字段） */
function makeLineItem(baseRows: ReturnType<typeof makeBaseRow>[], deletedRowKeys?: string) {
  const vtab = {
    componentId: COMP_ID,
    baseRows,
  };
  const quoteCardValues = JSON.stringify({ tabs: [vtab] });

  const comp = {
    componentId: COMP_ID,
    componentCode: 'C001',
    tabName: '料件',
    fields: FIELDS,
    formulas: [],
    rows: [],
    subtotal: 0,
    dataDriverPath: '$view.料件视图',
    deletedRowKeys,
  };

  return {
    productId: 'prod-001',
    productName: '测试产品',
    productPartNo: PART_NO,
    id: LINE_ITEM_ID,
    quoteCardValues,
    componentData: [comp],
  } as any;
}

// ── 测试用例 ──────────────────────────────────────────────────────────────────

describe('buildSnapshotExpansions 墓碑过滤', () => {
  const rowA = makeBaseRow('P1', 1);  // effKey='P1', fp 由 rowFingerprint 算
  const rowB = makeBaseRow('P2', 2);  // effKey='P2'
  const rowC = makeBaseRow('P3', 3);  // effKey='P3'

  const fpA = rowFingerprint(ROW_KEY_FIELDS, rowA.driverRow);
  const fpB = rowFingerprint(ROW_KEY_FIELDS, rowB.driverRow);

  it('无墓碑时返回全部 3 行', () => {
    const item = makeLineItem([rowA, rowB, rowC]);
    const rowKeyFieldsByComp = new Map([[COMP_ID, ROW_KEY_FIELDS]]);
    const map = buildSnapshotExpansions([item], 'QUOTE', CUSTOMER_ID, rowKeyFieldsByComp);

    const key = driverExpansionKey(LINE_ITEM_ID, PART_NO, COMP_ID, CUSTOMER_ID, '$view.料件视图', map[Object.keys(map)[0]] ? undefined : undefined);
    const expansion = Object.values(map)[0];

    expect(expansion).toBeDefined();
    expect(expansion!.rowCount).toBe(3);
    expect(expansion!.rows).toHaveLength(3);
  });

  it('墓碑命中 rowB → rowCount=2，被删行 driverRow 不在 rows 里，剩余行顺序不变', () => {
    const tomb = JSON.stringify([{ effKey: 'P2', fp: fpB }]);
    const item = makeLineItem([rowA, rowB, rowC], tomb);
    const rowKeyFieldsByComp = new Map([[COMP_ID, ROW_KEY_FIELDS]]);
    const map = buildSnapshotExpansions([item], 'QUOTE', CUSTOMER_ID, rowKeyFieldsByComp);

    const expansion = Object.values(map)[0];
    expect(expansion).toBeDefined();
    // effectiveRowCount = 2（P2 被过滤）
    expect(expansion!.rowCount).toBe(2);
    expect(expansion!.rows).toHaveLength(2);

    // 被删行（P2）的 driverRow 不在结果里
    const driverRows = expansion!.rows.map((r: any) => r.driverRow['料件']);
    expect(driverRows).not.toContain('P2');

    // 剩余两行顺序：P1 在前，P3 在后
    expect(driverRows[0]).toBe('P1');
    expect(driverRows[1]).toBe('P3');
  });

  it('effKey 命中但 fp 不同 → 不删（双命中才删）', () => {
    // 墓碑里 fp 是 rowA 的 fp，但 effKey 是 P2 → 不命中
    const tomb = JSON.stringify([{ effKey: 'P2', fp: fpA }]);
    const item = makeLineItem([rowA, rowB, rowC], tomb);
    const rowKeyFieldsByComp = new Map([[COMP_ID, ROW_KEY_FIELDS]]);
    const map = buildSnapshotExpansions([item], 'QUOTE', CUSTOMER_ID, rowKeyFieldsByComp);

    const expansion = Object.values(map)[0];
    expect(expansion!.rowCount).toBe(3);
  });

  it('COSTING 侧绝不过滤（spec §3.7 隔离）', () => {
    const tomb = JSON.stringify([{ effKey: 'P2', fp: fpB }]);
    // 构造带 costingCardValues 的 LineItem
    const vtab = { componentId: COMP_ID, baseRows: [rowA, rowB, rowC] };
    const comp = {
      componentId: COMP_ID, componentCode: 'C001', tabName: '料件',
      fields: FIELDS, formulas: [], rows: [], subtotal: 0,
      dataDriverPath: '$view.料件视图',
      deletedRowKeys: tomb,
    };
    const item = {
      productId: 'prod-001', productName: '测试产品', productPartNo: PART_NO,
      id: LINE_ITEM_ID,
      costingCardValues: JSON.stringify({ tabs: [vtab] }),
      componentData: [comp],
    } as any;

    const rowKeyFieldsByComp = new Map([[COMP_ID, ROW_KEY_FIELDS]]);
    const map = buildSnapshotExpansions([item], 'COSTING', CUSTOMER_ID, rowKeyFieldsByComp);

    const expansion = Object.values(map)[0];
    expect(expansion).toBeDefined();
    // COSTING 侧不过滤，全 3 行
    expect(expansion!.rowCount).toBe(3);
  });

  it('rowKeyFieldsByComp 未传时退化为不过滤（向后兼容）', () => {
    const tomb = JSON.stringify([{ effKey: 'P2', fp: fpB }]);
    const item = makeLineItem([rowA, rowB, rowC], tomb);
    // 不传 rowKeyFieldsByComp
    const map = buildSnapshotExpansions([item], 'QUOTE', CUSTOMER_ID, undefined);

    const expansion = Object.values(map)[0];
    expect(expansion).toBeDefined();
    // 无 rowKeyFieldsByComp → rkf=[] → buildUniqueRowKeys 返回行号 → effKey='0','1','2'
    // 墓碑里是 'P2'，不会匹配 '0','1','2' → 不删任何行
    expect(expansion!.rowCount).toBe(3);
  });

  it('deletedRowKeys 不进 driverExpansionKey（key 与是否删行无关）', () => {
    // 同一 item，有无墓碑，key 应相同
    const itemNoTomb = makeLineItem([rowA, rowB, rowC]);
    const itemWithTomb = makeLineItem([rowA, rowB, rowC], JSON.stringify([{ effKey: 'P2', fp: fpB }]));

    const rowKeyFieldsByComp = new Map([[COMP_ID, ROW_KEY_FIELDS]]);
    const mapNo = buildSnapshotExpansions([itemNoTomb], 'QUOTE', CUSTOMER_ID, rowKeyFieldsByComp);
    const mapWith = buildSnapshotExpansions([itemWithTomb], 'QUOTE', CUSTOMER_ID, rowKeyFieldsByComp);

    const keyNo = Object.keys(mapNo)[0];
    const keyWith = Object.keys(mapWith)[0];

    // key 完全相同（deletedRowKeys 未参与 key 计算）
    expect(keyNo).toBe(keyWith);
    // 但 rowCount 不同（一个 3 行，一个 2 行）
    expect(mapNo[keyNo]!.rowCount).toBe(3);
    expect(mapWith[keyWith]!.rowCount).toBe(2);
  });
});

// ── AP-54 C3：撞键场景 __effKey 不变量（Fix C3 回归保护） ─────────────────────
//
// 撞键：同一组件 3 行的 rowKeyFields 值相同（都是 '料件'='P1'）。
// buildUniqueRowKeys 产物：['P1#0', 'P1#1', 'P1#2']（追加 #序号消歧）。
// 删中间行后，剩余 2 行的 __effKey 必须仍是完整集键 P1#0 / P1#2，
// 而不是在过滤后子集上重算成 P1#0 / P1#1（这是 C3 修复的核心 bug）。

describe('buildSnapshotExpansions 撞键 __effKey 不变量 (AP-54 C3)', () => {
  // 3 行全部 rowKeyFields 值相同（料件='P1'），qty 不同只为区分 fp
  const rowX0 = { driverRow: { 料件: 'P1', 数量: 1 }, basicDataValues: { price: 10 } };
  const rowX1 = { driverRow: { 料件: 'P1', 数量: 2 }, basicDataValues: { price: 20 } };
  const rowX2 = { driverRow: { 料件: 'P1', 数量: 3 }, basicDataValues: { price: 30 } };

  const fpX0 = rowFingerprint(ROW_KEY_FIELDS, rowX0.driverRow);
  const fpX1 = rowFingerprint(ROW_KEY_FIELDS, rowX1.driverRow);
  const fpX2 = rowFingerprint(ROW_KEY_FIELDS, rowX2.driverRow);

  const rowKeyFieldsByComp = new Map([[COMP_ID, ROW_KEY_FIELDS]]);

  it('断言①：无墓碑时，rows[i].__effKey 是完整集键 [P1#0, P1#1, P1#2]', () => {
    const item = makeLineItem([rowX0, rowX1, rowX2]);
    const map = buildSnapshotExpansions([item], 'QUOTE', CUSTOMER_ID, rowKeyFieldsByComp);
    const expansion = Object.values(map)[0]!;

    expect(expansion.rowCount).toBe(3);
    expect(expansion.rows).toHaveLength(3);
    expect((expansion.rows[0] as any).__effKey).toBe('P1#0');
    expect((expansion.rows[1] as any).__effKey).toBe('P1#1');
    expect((expansion.rows[2] as any).__effKey).toBe('P1#2');
  });

  it('断言②：墓碑删中间行(P1#1)后，剩余 2 行的 __effKey 仍是完整集原键 P1#0 / P1#2（不重算成 P1#0/P1#1）', () => {
    // 墓碑：完整集中间键 P1#1 + 其 fp
    const tomb = JSON.stringify([{ effKey: 'P1#1', fp: fpX1 }]);
    const item = makeLineItem([rowX0, rowX1, rowX2], tomb);
    const map = buildSnapshotExpansions([item], 'QUOTE', CUSTOMER_ID, rowKeyFieldsByComp);
    const expansion = Object.values(map)[0]!;

    expect(expansion.rowCount).toBe(2);
    expect(expansion.rows).toHaveLength(2);
    // 核心断言：__effKey 必须是完整集原键，绝不重新编号
    expect((expansion.rows[0] as any).__effKey).toBe('P1#0');
    expect((expansion.rows[1] as any).__effKey).toBe('P1#2');  // 不是 P1#1！
    // 并且剩余行的 driverRow 确实是 P1#0（数量=1）和 P1#2（数量=3）
    expect(expansion.rows[0].driverRow['数量']).toBe(1);
    expect(expansion.rows[1].driverRow['数量']).toBe(3);
  });

  it('断言③：对剩余的 P1#2 行再删，能命中过滤（第二次删撞键行生效，rowCount 再 −1）', () => {
    // 同时放入两条墓碑：P1#1（先删）+ P1#2（再删）
    const tombs = JSON.stringify([
      { effKey: 'P1#1', fp: fpX1 },
      { effKey: 'P1#2', fp: fpX2 },
    ]);
    const item = makeLineItem([rowX0, rowX1, rowX2], tombs);
    const map = buildSnapshotExpansions([item], 'QUOTE', CUSTOMER_ID, rowKeyFieldsByComp);
    const expansion = Object.values(map)[0]!;

    // P1#1 和 P1#2 都被过滤，只剩 P1#0
    expect(expansion.rowCount).toBe(1);
    expect(expansion.rows).toHaveLength(1);
    expect((expansion.rows[0] as any).__effKey).toBe('P1#0');
    expect(expansion.rows[0].driverRow['数量']).toBe(1);
  });
});
