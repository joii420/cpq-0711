/**
 * buildExcelSnapshot.test.ts
 *
 * TDD 验证：
 * 1. 恒等性（核心不变量）：C 列 (TAB_JOIN_FORMULA 引用产品小计) ==
 *    evalProductSubtotalFromSubtotals(item, getComponentSubtotals(item))
 * 2. 逐 source_type 验证：各类型列均能产出非空、不抛错的结果
 */

import { describe, it, expect } from 'vitest';
import { buildExcelSnapshot } from './buildExcelSnapshot';
import {
  getComponentSubtotals,
  evalProductSubtotalFromSubtotals,
} from './QuotationStep2';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import { lineItem093, lineItemWithFormula } from './__fixtures__/lineItem093';

// ─── 列定义夹具 ────────────────────────────────────────────────────────────────

/**
 * A 列：TAB_JOIN_FORMULA，引用来料组件的材料成本列小计（`[来料(总计)]`）。
 * tabs 包含 来料组件的 alias/tabKey/rowKeyFields，使 expressionToTokens 能找到对应 componentId。
 */
const colA: CostingTemplateColumn & { expression: string; tabs: any[] } = {
  col_key: 'A',
  title: '来料材料成本',
  source_type: 'TAB_JOIN_FORMULA',
  expression: '[来料(总计)]',
  tabs: [
    {
      alias: '来料',
      tabKey: 'comp-material',
      componentId: 'comp-material',
      componentName: '来料',
      rowKeyFields: [],
      subtotalCols: ['材料成本'],
      detailFields: ['材料成本'],
    },
  ],
} as any;

/**
 * B 列：FIXED_VALUE = 5
 */
const colB: CostingTemplateColumn = {
  col_key: 'B',
  title: '固定费用',
  source_type: 'FIXED_VALUE',
  fixed_value: '5',
};

/**
 * C 列：TAB_JOIN_FORMULA，引用报价小计整体（`[报价小计(总计)]`）。
 * 对应 evalProductSubtotalFromSubtotals 的结果（恒等性验证列）。
 */
const colC: CostingTemplateColumn & { expression: string; tabs: any[] } = {
  col_key: 'C',
  title: '产品小计',
  source_type: 'TAB_JOIN_FORMULA',
  expression: '[报价小计(总计)]',
  tabs: [
    {
      alias: '报价小计',
      tabKey: 'comp-subtotal',
      componentId: 'comp-subtotal',
      componentName: '报价小计',
      rowKeyFields: [],
      subtotalCols: ['总成本'],
      detailFields: [],
    },
  ],
} as any;

/**
 * D 列：PRODUCT_ATTRIBUTE，引用产品属性 `category`
 */
const colD: CostingTemplateColumn = {
  col_key: 'D',
  title: '产品类别',
  source_type: 'PRODUCT_ATTRIBUTE',
  field_key: 'category',
};

/**
 * E 列：FORMULA，引用 B 列（= [B] * 2）
 */
const colE: CostingTemplateColumn = {
  col_key: 'E',
  title: '费用×2',
  source_type: 'FORMULA',
  formula: '=[B]*2',
};

/**
 * F 列：COMPONENT_FIELD，取来料组件第一行 material_code 字段
 */
const colF: CostingTemplateColumn = {
  col_key: 'F',
  title: '组件字段',
  source_type: 'COMPONENT_FIELD',
  field_key: '材料成本',
};

/**
 * G 列：VARIABLE，老 {CODE} 格式，引用 lineItem.productPartNo
 */
const colG: CostingTemplateColumn = {
  col_key: 'G',
  title: '料号',
  source_type: 'VARIABLE',
  variable_path: '{hf_part_no}',
};

/**
 * H 列：hidden=true 的 TAB_JOIN_FORMULA（应仍参与求值不报错）
 */
const colH: CostingTemplateColumn & { expression: string; tabs: any[] } = {
  col_key: 'H',
  title: '隐藏列',
  source_type: 'TAB_JOIN_FORMULA',
  hidden: true,
  expression: '[来料(总计)]',
  tabs: colA.tabs,
} as any;

const allColumns = [colA, colB, colC, colD, colE, colF, colG, colH] as CostingTemplateColumn[];

// ─── 测试 ──────────────────────────────────────────────────────────────────────

describe('buildExcelSnapshot', () => {
  // ── 恒等性（核心不变量）────────────────────────────────────────────────────
  describe('恒等性：C 列 == evalProductSubtotalFromSubtotals', () => {
    it('lineItem093: C 列与 getComponentSubtotals+evalProductSubtotalFromSubtotals 结果相同', () => {
      const { rows } = buildExcelSnapshot(lineItem093, allColumns, undefined, undefined, {});
      expect(rows).toHaveLength(1);
      const row = rows[0];

      // 计算期望值（同引擎同入参）
      const componentSubtotals = getComponentSubtotals(lineItem093, undefined, undefined);
      const expectedSubtotal = evalProductSubtotalFromSubtotals(lineItem093, componentSubtotals);

      // 恒等性：C 列 == footer 产品小计
      expect(row.C).toBeCloseTo(expectedSubtotal, 6);
    });

    it('lineItemWithFormula: C 列恒等性（含税成本 = 材料成本 * 1.13）', () => {
      // 构建对应此 lineItem 的列定义
      const colC2: CostingTemplateColumn & { expression: string; tabs: any[] } = {
        col_key: 'C2',
        title: '产品小计',
        source_type: 'TAB_JOIN_FORMULA',
        expression: '[报价小计2(总计)]',
        tabs: [
          {
            alias: '报价小计2',
            tabKey: 'comp-sub2',
            componentId: 'comp-sub2',
            componentName: '报价小计2',
            rowKeyFields: [],
            subtotalCols: ['总成本'],
            detailFields: [],
          },
        ],
      } as any;

      const { rows } = buildExcelSnapshot(lineItemWithFormula, [colC2 as CostingTemplateColumn], undefined, undefined, {});
      expect(rows).toHaveLength(1);

      const cs = getComponentSubtotals(lineItemWithFormula, undefined, undefined);
      const expected = evalProductSubtotalFromSubtotals(lineItemWithFormula, cs);

      // 含税成本 = 30 * 1.13 = 33.9，恒等性验证
      expect(rows[0].C2).toBeCloseTo(expected, 6);
      expect(rows[0].C2).toBeCloseTo(33.9, 4);
    });
  });

  // ── 逐 source_type 验证 ────────────────────────────────────────────────────
  describe('逐 source_type 验证', () => {
    let row: Record<string, any>;

    beforeAll(() => {
      const result = buildExcelSnapshot(lineItem093, allColumns, undefined, undefined, {});
      row = result.rows[0];
    });

    it('A 列 (TAB_JOIN_FORMULA 引用来料总计): 非空数字', () => {
      expect(typeof row.A).toBe('number');
      expect(isNaN(row.A as number)).toBe(false);
      // 来料组件材料成本 FIXED_VALUE=10，期望 A 列 = 10
      expect(row.A).toBeCloseTo(10, 4);
    });

    it('B 列 (FIXED_VALUE=5): 返回数字 5', () => {
      expect(row.B).toBe(5);
    });

    it('C 列 (TAB_JOIN_FORMULA 产品小计): 不抛错，为有限数', () => {
      expect(typeof row.C).toBe('number');
      expect(Number.isFinite(row.C as number)).toBe(true);
    });

    it('D 列 (PRODUCT_ATTRIBUTE): 返回 null（lineItem093 属性值为空）', () => {
      // lineItem093 productAttributeValues 为空对象，category 字段不存在 → null
      expect(row.D).toBeNull();
    });

    it('E 列 (FORMULA = [B]*2): B 列结果的 2 倍', () => {
      // B=5，E=[B]*2=10
      expect(row.E).toBe(10);
    });

    it('F 列 (COMPONENT_FIELD 材料成本): 取来料组件第一行材料成本', () => {
      // 来料组件第一行 rows[0] = {材料成本: 10}，但 FIXED_VALUE 字段 content='10' 存于 fields
      // COMPONENT_FIELD 读 comp.rows[0][field_key]
      expect(row.F).toBeDefined();
    });

    it('G 列 (VARIABLE {hf_part_no}): 返回 lineItem.productPartNo', () => {
      expect(row.G).toBe('HF-TEST-093');
    });

    it('H 列 (hidden=true TAB_JOIN_FORMULA): hidden 列仍参与求值，不为 undefined', () => {
      expect(row.H).not.toBeUndefined();
      // H 与 A 同表达式，期望相同值
      expect(row.H).toBeCloseTo(row.A, 6);
    });
  });

  // ── 元数据字段 ────────────────────────────────────────────────────────────
  describe('输出元数据', () => {
    it('rows[0] 包含 __hfPartNo 和 _lineItemId', () => {
      const { rows } = buildExcelSnapshot(lineItem093, allColumns, undefined, undefined, {});
      expect(rows[0].__hfPartNo).toBe('HF-TEST-093');
      expect(rows[0]._lineItemId).toBeDefined();
    });

    it('rows 长度恒为 1（报价侧单行语义）', () => {
      const { rows } = buildExcelSnapshot(lineItem093, allColumns, undefined, undefined, {});
      expect(rows).toHaveLength(1);
    });
  });

  // ── 边界场景 ───────────────────────────────────────────────────────────────
  describe('边界场景', () => {
    it('空列定义不抛错，返回 rows=[{__hfPartNo, _lineItemId}]', () => {
      expect(() => {
        const { rows } = buildExcelSnapshot(lineItem093, [], undefined, undefined, {});
        expect(rows).toHaveLength(1);
      }).not.toThrow();
    });

    it('TAB_JOIN_FORMULA 无 expression 时返回 0 不抛错', () => {
      const badCol: any = {
        col_key: 'BAD',
        title: '无表达式',
        source_type: 'TAB_JOIN_FORMULA',
        // 故意不提供 expression
      };
      const { rows } = buildExcelSnapshot(lineItem093, [badCol], undefined, undefined, {});
      expect(rows[0].BAD).toBe(0);
    });

    it('FIXED_VALUE 非数字字符串返回原始字符串', () => {
      const textCol: CostingTemplateColumn = {
        col_key: 'TEXT',
        title: '文字',
        source_type: 'FIXED_VALUE',
        fixed_value: 'ABC',
      };
      const { rows } = buildExcelSnapshot(lineItem093, [textCol], undefined, undefined, {});
      expect(rows[0].TEXT).toBe('ABC');
    });

    it('VARIABLE 无 pathCache 时 BNF 路径列返回 __loading__（哨兵，与 useLinkedExcelRows 源行为对齐）', () => {
      const bnfCol: CostingTemplateColumn = {
        col_key: 'BNF',
        title: 'BNF路径',
        source_type: 'VARIABLE',
        variable_path: 'v_costing.unit_price',
      };
      const { rows } = buildExcelSnapshot(lineItem093, [bnfCol], undefined, undefined, {});
      // pathCache 不含该路径 → '__loading__'（不再是 null）
      expect(rows[0].BNF).toBe('__loading__');
    });

    it('componentData 为空时不抛错，C 列返回 0', () => {
      const emptyItem = { ...lineItem093, componentData: [] };
      expect(() => {
        const { rows } = buildExcelSnapshot(emptyItem as any, [colC as CostingTemplateColumn], undefined, undefined, {});
        expect(typeof rows[0].C).toBe('number');
      }).not.toThrow();
    });

    it('VARIABLE BNF 路径 pathCache miss → 返回 __loading__ 哨兵', () => {
      // 不提供 pathCache（ctx = {}），cache-miss 应返回 '__loading__' 而非 null
      const bnfCol: CostingTemplateColumn = {
        col_key: 'BNF2',
        title: 'BNF路径哨兵',
        source_type: 'VARIABLE',
        variable_path: 'v_costing.unit_price',
      };
      const { rows } = buildExcelSnapshot(lineItem093, [bnfCol], undefined, undefined, {});
      expect(rows[0].BNF2).toBe('__loading__');
    });
  });

  // ── twin 用例：锁定 CARD_FORMULA / EXCEL_FORMULA 与孪生类型共享 switch case ──
  describe('twin 用例：CARD_FORMULA 与 EXCEL_FORMULA 共享分支', () => {
    it('CARD_FORMULA 与 TAB_JOIN_FORMULA 共走 evalTabJoinOrCard，无 expression 时返回 0', () => {
      // CARD_FORMULA 与 TAB_JOIN_FORMULA 同走 case 'TAB_JOIN_FORMULA': case 'CARD_FORMULA':
      // 验证：有 expression 时走同一路径，与 TAB_JOIN_FORMULA 行为一致
      const cardFormulaCol: CostingTemplateColumn & { expression: string; tabs: any[] } = {
        col_key: 'CF',
        title: 'CARD_FORMULA列',
        source_type: 'CARD_FORMULA',
        expression: '[来料(总计)]',
        tabs: [
          {
            alias: '来料',
            tabKey: 'comp-material',
            componentId: 'comp-material',
            componentName: '来料',
            rowKeyFields: [],
            subtotalCols: ['材料成本'],
            detailFields: ['材料成本'],
          },
        ],
      } as any;
      const { rows } = buildExcelSnapshot(lineItem093, [cardFormulaCol as CostingTemplateColumn], undefined, undefined, {});
      // 与 A 列（同 expression 同 tabs 的 TAB_JOIN_FORMULA）结果相同
      const { rows: rowsA } = buildExcelSnapshot(lineItem093, [colA as CostingTemplateColumn], undefined, undefined, {});
      expect(typeof rows[0].CF).toBe('number');
      expect(rows[0].CF).toBeCloseTo(rowsA[0].A as number, 6);
    });

    it('CARD_FORMULA 无 expression 时返回 0（与 TAB_JOIN_FORMULA 无 expression 行为一致）', () => {
      const noExprCol: any = {
        col_key: 'CF_NOEXPR',
        title: 'CARD_FORMULA无表达式',
        source_type: 'CARD_FORMULA',
      };
      const { rows } = buildExcelSnapshot(lineItem093, [noExprCol], undefined, undefined, {});
      expect(rows[0].CF_NOEXPR).toBe(0);
    });

    it('EXCEL_FORMULA 与 FORMULA 共走 evaluateFormula second-pass，=[B]*3 引用 FIXED_VALUE 列', () => {
      // EXCEL_FORMULA 与 FORMULA 同走 second pass（evaluateFormula）
      // B 列 FIXED_VALUE=5，EXCEL_FORMULA =[B]*3 期望 15
      const excelFormulaCol: CostingTemplateColumn = {
        col_key: 'EF',
        title: 'EXCEL_FORMULA列',
        source_type: 'EXCEL_FORMULA',
        formula: '=[B]*3',
      };
      const { rows } = buildExcelSnapshot(lineItem093, [colB, excelFormulaCol], undefined, undefined, {});
      expect(rows[0].EF).toBe(15);
    });
  });
});

// vitest 需要 beforeAll（来自 vitest 全局）
import { beforeAll } from 'vitest';
