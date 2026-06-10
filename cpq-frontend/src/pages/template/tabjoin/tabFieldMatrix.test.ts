/**
 * 单元测试: TabFieldMatrix.parseActiveRowKeySig — 置灰行键类判定纯函数
 *
 * 覆盖范围:
 *   1. 空表达式 → null (未锁定)
 *   2. 仅总计令牌 [alias(总计)] → null (不触发行键锁)
 *   3. 仅明细列总计令牌 [alias.field(总计)] → null (不触发行键锁)
 *   4. 首个明细令牌 [alias.field] → 取对应 tabDef.rowKeyFields 签名
 *   5. 多页签明细混合 → 锁到首个明细令牌的行键类签名
 *   6. 多 rowKeyFields → 用 '+' 拼接
 *   7. alias 找不到对应 tabDef → null
 *   8. alias 对应 tabDef.rowKeyFields 为空 → null
 *   9. 置灰判定: 同类页签 sameClass=true / 不同类 sameClass=false
 */
import { describe, it, expect } from 'vitest';
import { parseActiveRowKeySig } from './TabFieldMatrix';
import type { TabDef } from '../../../services/tabJoinFormulaService';

// ──────────────────────────────────────────────────────────────────────────────
// 测试用 fixture
// ──────────────────────────────────────────────────────────────────────────────

const TAB_INVEST: TabDef = {
  alias: '投料',
  tabKey: 'aaa:0',
  rowKeyFields: ['child_hf_part_no', 'material_code'],
  detailFields: ['金额', '重量', '单价'],
  subtotalCols: ['金额'],
};

const TAB_PROCESS: TabDef = {
  alias: '工序',
  tabKey: 'bbb:1',
  rowKeyFields: ['child_hf_part_no', 'process_code'],
  detailFields: ['工时', '工费'],
  subtotalCols: ['工费'],
};

const TAB_RECYCLE: TabDef = {
  alias: '回料',
  tabKey: 'ccc:2',
  rowKeyFields: ['child_hf_part_no', 'material_code'], // 与投料同行键类
  detailFields: ['回收量', '回收额'],
  subtotalCols: [],
};

const TAB_SUBTOTAL: TabDef = {
  alias: '产品小计',
  tabKey: 'ddd:3',
  rowKeyFields: [],   // SUBTOTAL 组件无行键
  detailFields: [],
  subtotalCols: ['合计'],
};

const ALL_TABS: TabDef[] = [TAB_INVEST, TAB_PROCESS, TAB_RECYCLE, TAB_SUBTOTAL];

// ──────────────────────────────────────────────────────────────────────────────
// parseActiveRowKeySig 测试
// ──────────────────────────────────────────────────────────────────────────────

describe('parseActiveRowKeySig', () => {
  it('TC-01: 空表达式 → null (未锁定)', () => {
    expect(parseActiveRowKeySig('', ALL_TABS)).toBeNull();
  });

  it('TC-02: 仅空白 → null', () => {
    expect(parseActiveRowKeySig('   ', ALL_TABS)).toBeNull();
  });

  it('TC-03: 仅页签总计令牌 [alias(总计)] → null (不触发行键锁)', () => {
    expect(parseActiveRowKeySig('[投料(总计)]', ALL_TABS)).toBeNull();
  });

  it('TC-04: 仅小计列总计令牌 [alias.field(总计)] → null (不触发行键锁)', () => {
    expect(parseActiveRowKeySig('[投料.金额(总计)]', ALL_TABS)).toBeNull();
  });

  it('TC-05: 多个总计令牌混合 → null', () => {
    const expr = '[投料(总计)] + [工序(总计)] * 2';
    expect(parseActiveRowKeySig(expr, ALL_TABS)).toBeNull();
  });

  it('TC-06: 首个明细令牌 → 锁到该 tab 的 rowKeyFields 签名', () => {
    // 重量 是投料的明细字段（非小计列），触发行键锁
    const expr = '[投料.重量]';
    const sig = parseActiveRowKeySig(expr, ALL_TABS);
    expect(sig).toBe('child_hf_part_no+material_code');
  });

  it('TC-06b: 小计列引用 [alias.subtotalCol] 不触发行键锁（component_subtotal 标量）', () => {
    // 金额 是投料的 subtotalCol → 组件小计标量引用，不锁行键
    expect(parseActiveRowKeySig('[投料.金额]', ALL_TABS)).toBeNull();
  });

  it('TC-07: 多 rowKeyFields → 用 "+" 连接', () => {
    const expr = '[工序.工时]';
    const sig = parseActiveRowKeySig(expr, ALL_TABS);
    expect(sig).toBe('child_hf_part_no+process_code');
  });

  it('TC-08: 明细令牌 + 总计令牌 混合 → 取首个明细令牌签名', () => {
    const expr = '[投料.重量] + [工序(总计)]';
    const sig = parseActiveRowKeySig(expr, ALL_TABS);
    expect(sig).toBe('child_hf_part_no+material_code');
  });

  it('TC-09: 多个不同行键类明细令牌 → 锁到"首个"出现的明细 alias', () => {
    // 投料在前 → 锁投料行键类；工序是不同类
    const expr = '[投料.重量] + [工序.工时]';
    const sig = parseActiveRowKeySig(expr, ALL_TABS);
    expect(sig).toBe('child_hf_part_no+material_code');
  });

  it('TC-10: alias 在 tabDefs 中找不到 → null', () => {
    const expr = '[不存在页签.某字段]';
    expect(parseActiveRowKeySig(expr, ALL_TABS)).toBeNull();
  });

  it('TC-11: alias 对应 tabDef.rowKeyFields 为空(如 SUBTOTAL) → null', () => {
    const expr = '[产品小计.合计]';  // SUBTOTAL tab rowKeyFields=[]
    expect(parseActiveRowKeySig(expr, ALL_TABS)).toBeNull();
  });

  it('TC-12: 空 tabDefs 数组 → null', () => {
    expect(parseActiveRowKeySig('[投料.金额]', [])).toBeNull();
  });

  it('TC-13: 表达式含数学运算符 → 正确解析令牌', () => {
    // 投料.金额 是小计列（跳过不锁），首个真正明细是 回料.回收额
    const expr = '[投料.金额] * 0.9 + [回料.回收额]';
    const sig = parseActiveRowKeySig(expr, ALL_TABS);
    expect(sig).toBe('child_hf_part_no+material_code');
  });

  it('TC-14: 单一 rowKeyField(无 +) → 返回该字段名本身', () => {
    const singleKeyTab: TabDef = {
      alias: '费用',
      tabKey: 'eee:4',
      rowKeyFields: ['fee_code'],
      detailFields: ['金额'],
      subtotalCols: [],
    };
    const sig = parseActiveRowKeySig('[费用.金额]', [singleKeyTab]);
    expect(sig).toBe('fee_code');
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// 置灰判定逻辑测试（直接实现 sameClass 逻辑，与 TabFieldMatrix render 一致）
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 从 TabFieldMatrix 渲染逻辑提取的置灰判定：
 *   activeSig = parseActiveRowKeySig(expression, tabDefs)
 *   defSig = def.rowKeyFields.join('+')
 *   sameClass = activeSig === null || defSig === activeSig
 *   disabled (明细chip) = !sameClass
 */
function isDetailDisabled(def: TabDef, expression: string, tabDefs: TabDef[]): boolean {
  const activeSig = parseActiveRowKeySig(expression, tabDefs);
  if (activeSig === null) return false; // 未锁定，全部可用
  const defSig = def.rowKeyFields.join('+');
  return defSig !== activeSig;
}

describe('置灰判定 isDetailDisabled', () => {
  it('TC-20: 空表达式 → 所有页签明细均不置灰', () => {
    for (const def of ALL_TABS) {
      expect(isDetailDisabled(def, '', ALL_TABS)).toBe(false);
    }
  });

  it('TC-21: 仅总计令牌 → 所有页签明细均不置灰', () => {
    const expr = '[投料(总计)]';
    for (const def of ALL_TABS) {
      expect(isDetailDisabled(def, expr, ALL_TABS)).toBe(false);
    }
  });

  it('TC-22: 选投料.重量(行键 child_hf_part_no+material_code) → 投料本身不置灰', () => {
    const expr = '[投料.重量]';
    expect(isDetailDisabled(TAB_INVEST, expr, ALL_TABS)).toBe(false);
  });

  it('TC-23: 选投料.重量 → 工序(不同行键类)置灰', () => {
    const expr = '[投料.重量]';
    expect(isDetailDisabled(TAB_PROCESS, expr, ALL_TABS)).toBe(true);
  });

  it('TC-24: 选投料.重量 → 回料(同行键类 child_hf_part_no+material_code)不置灰', () => {
    const expr = '[投料.重量]';
    expect(isDetailDisabled(TAB_RECYCLE, expr, ALL_TABS)).toBe(false);
  });

  it('TC-25: 选工序.工时(行键 child_hf_part_no+process_code) → 投料置灰', () => {
    const expr = '[工序.工时]';
    expect(isDetailDisabled(TAB_INVEST, expr, ALL_TABS)).toBe(true);
  });

  it('TC-26: 选工序.工时 → 工序本身不置灰', () => {
    const expr = '[工序.工时]';
    expect(isDetailDisabled(TAB_PROCESS, expr, ALL_TABS)).toBe(false);
  });

  it('TC-27: 选工序.工时 → 回料(material_code行键类)置灰', () => {
    const expr = '[工序.工时]';
    expect(isDetailDisabled(TAB_RECYCLE, expr, ALL_TABS)).toBe(true);
  });

  it('TC-28: SUBTOTAL tab rowKeyFields=[] → 空签名; 任意明细表达式下 defSig="" 与 activeSig 不匹配 → 置灰', () => {
    const expr = '[投料.重量]';
    // SUBTOTAL defSig = '' (join of empty), activeSig = 'child_hf_part_no+material_code'
    // '' !== 'child_hf_part_no+material_code' → disabled=true
    expect(isDetailDisabled(TAB_SUBTOTAL, expr, ALL_TABS)).toBe(true);
  });

  it('TC-29: 表达式中首个明细是工序，后续有投料总计 → 工序同类不置灰、投料被锁后不影响总计按钮', () => {
    const expr = '[工序.工时] + [投料(总计)]';
    // activeSig = process_code 签名
    expect(isDetailDisabled(TAB_PROCESS, expr, ALL_TABS)).toBe(false);  // 同类
    expect(isDetailDisabled(TAB_INVEST, expr, ALL_TABS)).toBe(true);    // 不同类
  });
});
