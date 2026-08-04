import { describe, it, expect } from 'vitest';
import { isKeyUnset, rowsHaveUserData } from './keyPresenceAuthority';

describe('isKeyUnset —— 键存在即权威（spec 2026-08-03）', () => {
  it('键不存在 → 未定值，允许烘一次', () => {
    expect(isKeyUnset({}, '损耗率')).toBe(true);
    expect(isKeyUnset({ 其他列: 1 }, '损耗率')).toBe(true);
  });

  it('键存在且值为空串（用户清空）→ 已定值，禁止烘', () => {
    expect(isKeyUnset({ 损耗率: '' }, '损耗率')).toBe(false);
  });

  it('键存在且有值 → 已定值，禁止烘', () => {
    expect(isKeyUnset({ 损耗率: 1.05 }, '损耗率')).toBe(false);
    expect(isKeyUnset({ 损耗率: 0 }, '损耗率')).toBe(false);
  });

  it('键存在但值为 null → 按键缺失处理', () => {
    // 空值物理表示只认空串；null 不引入第三种语义（后端 mergeRowDataInputsIntoEdits 会跳过 null）
    expect(isKeyUnset({ 损耗率: null }, '损耗率')).toBe(true);
  });

  it('undefined 行对象 → 未定值（新行）', () => {
    expect(isKeyUnset(undefined, '损耗率')).toBe(true);
  });
});

describe('保存回填复用同一判据（§1.5 / §1.6）', () => {
  it('用户清空的格子在保存时不得被静态 content 填回', () => {
    // snapshotRows §1.5/§1.6 的守卫条件必须等价于「键未定值」
    const enriched: Record<string, any> = { 税率: '' };
    expect(isKeyUnset(enriched, '税率')).toBe(false);
  });

  it('从未定值的格子在保存时仍应填入静态 content', () => {
    const enriched: Record<string, any> = {};
    expect(isKeyUnset(enriched, '税率')).toBe(true);
  });
});

describe('rowsHaveUserData —— 清空也是用户数据', () => {
  it('有非空值 → true', () => {
    expect(rowsHaveUserData([{ 损耗率: 1.05 }])).toBe(true);
  });

  it('只有空串（用户清空过）→ true，不得退回默认行', () => {
    expect(rowsHaveUserData([{ 损耗率: '' }])).toBe(true);
  });

  it('只有 row_index → false', () => {
    expect(rowsHaveUserData([{ row_index: 0 }])).toBe(false);
  });

  it('全空对象 → false', () => {
    expect(rowsHaveUserData([{}, {}])).toBe(false);
  });

  it('null / 空数组 → false', () => {
    expect(rowsHaveUserData(null)).toBe(false);
    expect(rowsHaveUserData([])).toBe(false);
  });
});

// ─── 回归护栏：建行方式必须与「键存在即权威」一致 ───────────────────────────────
// 2026-08-03 合并后发现的真回归：buildEmptyRow 曾给每个 INPUT_* 写 ''，用它表达
// 「还没值、请填默认值」；而本次把 '' 定义成「用户已定值」→ 新加产品/批量导入的首行
// 默认值永不被烘（汇率/损耗率等被公式引用的列因此算 0）。键缺失才是「从未定值」。
import { buildLineItemFromTemplate } from './BulkImportPartsDrawer';

describe('buildEmptyRow 不得给 INPUT_* 预置空串（否则默认值永不烘）', () => {
  const tmpl: any = {
    id: 't1',
    componentsSnapshot: [
      {
        componentId: 'c1',
        componentCode: 'C1',
        tabName: 'T',
        componentType: 'NORMAL',
        fields: [
          { name: '汇率', field_type: 'INPUT_NUMBER', content: '1' },
          { name: '备注', field_type: 'INPUT_TEXT' },
          { name: '损耗', field_type: 'FIXED_VALUE', content: '0.05' },
          { name: '成本', field_type: 'FORMULA' },
        ],
        formulas: [],
      },
    ],
  };

  it('INPUT_NUMBER / INPUT_TEXT 的键必须缺失 → isKeyUnset 为 true → 默认值可烘', () => {
    const li: any = buildLineItemFromTemplate(tmpl, { partNo: 'P1' } as any);
    const row = li.componentData[0].rows[0];
    expect(isKeyUnset(row, '汇率')).toBe(true);
    expect(isKeyUnset(row, '备注')).toBe(true);
  });

  it('FIXED_VALUE 仍写入真实常量（那是值，不是占位）', () => {
    const li: any = buildLineItemFromTemplate(tmpl, { partNo: 'P1' } as any);
    expect(li.componentData[0].rows[0]['损耗']).toBe('0.05');
  });
});
