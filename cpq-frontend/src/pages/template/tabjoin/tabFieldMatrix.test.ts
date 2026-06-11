/**
 * 单元测试: TabFieldMatrix.tabComparable — 宿主可比置灰判定纯函数（v4-D/v4-M 新机制）
 *
 * 旧机制 parseActiveRowKeySig（"取表达式首个明细令牌锁行键类签名"）已废除（spec §206 作废），
 * 置灰基准改为"与宿主组件行键 selfRowKeyFields 集合包含(⊆/⊇，顺序无关)"：
 *   - 可比（任一方 ⊆ 另一方）→ 明细可点
 *   - 不可比 / 空行键 source（SUBTOTAL）→ 明细置灰（仅整页签小计可用）
 */
import { describe, it, expect } from 'vitest';
import { tabComparable } from './TabFieldMatrix';
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
  rowKeyFields: ['child_hf_part_no', 'material_code'], // 与投料同行键集
  detailFields: ['回收量', '回收额'],
  subtotalCols: [],
};

const TAB_SUBTOTAL: TabDef = {
  alias: '产品小计',
  tabKey: 'ddd:3',
  rowKeyFields: [], // SUBTOTAL 组件无行键
  detailFields: [],
  subtotalCols: ['合计'],
};

// ──────────────────────────────────────────────────────────────────────────────
// tabComparable — 宿主可比判定（核心纯函数）
// ──────────────────────────────────────────────────────────────────────────────

describe('tabComparable — 宿主可比判定（替代 parseActiveRowKeySig）', () => {
  it('宿主[子件] vs 粗source[子件] → 可比(同级)', () => {
    expect(tabComparable(['子件'], ['子件'])).toBe(true);
  });
  it('宿主[子件] vs 细source[子件,工序] → 可比(host ⊆ source)', () => {
    expect(tabComparable(['子件'], ['子件', '工序'])).toBe(true);
  });
  it('宿主[子件,工序] vs 粗source[子件] → 可比(source ⊆ host)', () => {
    expect(tabComparable(['子件', '工序'], ['子件'])).toBe(true);
  });
  it('同集乱序 → 可比(顺序无关)', () => {
    expect(tabComparable(['A', 'B'], ['B', 'A'])).toBe(true);
  });
  it('宿主[子件] vs 不相交[料号] → 不可比', () => {
    expect(tabComparable(['子件'], ['料号'])).toBe(false);
  });
  it('部分交集但互不包含 {hf,mat} vs {hf,proc} → 不可比', () => {
    expect(tabComparable(['child_hf_part_no', 'material_code'],
      ['child_hf_part_no', 'process_code'])).toBe(false);
  });
  it('空行键 source（SUBTOTAL rowKeyFields=[]） → 不可比（只留总计）', () => {
    expect(tabComparable(['子件'], [])).toBe(false);
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// 置灰判定逻辑（与 TabFieldMatrix render 一致：disabled = !tabComparable(self, def.rowKeyFields)）
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 从 TabFieldMatrix 渲染逻辑提取的新置灰判定（v4-D/v4-M）：
 *   isComparable = tabComparable(selfRowKeyFields, def.rowKeyFields)
 *   明细 chip disabled = !isComparable
 */
function isDetailDisabled(selfRowKeyFields: string[], def: TabDef): boolean {
  return !tabComparable(selfRowKeyFields, def.rowKeyFields ?? []);
}

describe('置灰判定 isDetailDisabled（宿主基准，集合包含）', () => {
  const HOST_INVEST = TAB_INVEST.rowKeyFields; // [child_hf_part_no, material_code]

  it('宿主=投料 → 投料本身可比，不置灰', () => {
    expect(isDetailDisabled(HOST_INVEST, TAB_INVEST)).toBe(false);
  });
  it('宿主=投料 → 回料(同行键集)不置灰', () => {
    expect(isDetailDisabled(HOST_INVEST, TAB_RECYCLE)).toBe(false);
  });
  it('宿主=投料 → 工序(交集仅 hf_part_no，互不包含)置灰', () => {
    expect(isDetailDisabled(HOST_INVEST, TAB_PROCESS)).toBe(true);
  });
  it('宿主=投料 → 产品小计(空行键 SUBTOTAL)置灰', () => {
    expect(isDetailDisabled(HOST_INVEST, TAB_SUBTOTAL)).toBe(true);
  });

  it('宿主=单键[child_hf_part_no] → 投料(细，含该键)可比，不置灰(host ⊆ source)', () => {
    expect(isDetailDisabled(['child_hf_part_no'], TAB_INVEST)).toBe(false);
  });
  it('宿主=单键[child_hf_part_no] → 工序(细，含该键)可比，不置灰', () => {
    expect(isDetailDisabled(['child_hf_part_no'], TAB_PROCESS)).toBe(false);
  });
  it('宿主=三键[child_hf_part_no,material_code,extra] → 投料(粗，⊆宿主)不置灰', () => {
    expect(isDetailDisabled(['child_hf_part_no', 'material_code', 'extra'], TAB_INVEST)).toBe(false);
  });
  it('宿主=无关键[料号] → 所有有行键页签置灰', () => {
    for (const def of [TAB_INVEST, TAB_PROCESS, TAB_RECYCLE]) {
      expect(isDetailDisabled(['料号'], def)).toBe(true);
    }
  });
});
