/**
 * lineItem093.ts — 最小可验证 LineItem 夹具
 *
 * 场景简化说明：
 *   为避免手搓过于复杂的夹具，0.93 精确值断言只在集成层面验证。
 *   本夹具的核心目的是验证恒等性不变量：
 *     buildExcelSnapshot(C列) == evalProductSubtotalFromSubtotals(item, getComponentSubtotals(item))
 *
 * 结构：
 *   - 来料 (NORMAL): 材料成本字段 (FORMULA, is_subtotal=true)，值通过 FIXED_VALUE 子字段累加
 *   - 报价小计 (SUBTOTAL): 公式 = 来料（component_subtotal）
 *
 * 断言会用 getComponentSubtotals + evalProductSubtotalFromSubtotals 计算期望值，
 * 与 buildExcelSnapshot 产出的 C 列对比（恒等性，不硬编码 0.93）。
 */

import type { LineItem } from '../QuotationStep2';

/** 来料组件: 单行，材料成本=10（简单固定值，足以验证公式路径） */
const materialComp = {
  componentId: 'comp-material',
  componentCode: '来料',
  tabName: '来料',
  componentType: 'NORMAL' as const,
  fields: [
    {
      name: '材料成本',
      field_type: 'FIXED_VALUE' as const,
      content: '10',
      is_subtotal: true,
    },
  ],
  formulas: [],
  rows: [{ 材料成本: 10 }],
  subtotal: 10,
};

/** 报价小计组件: 公式 = [来料(总计)] = component_subtotal('来料') */
const subtotalComp = {
  componentId: 'comp-subtotal',
  componentCode: '报价小计',
  tabName: '报价小计',
  componentType: 'SUBTOTAL' as const,
  fields: [
    {
      name: '总成本',
      field_type: 'FORMULA' as const,
      is_subtotal: true,
    },
  ],
  formulas: [
    {
      name: '总成本',
      expression: [
        {
          type: 'component_subtotal',
          component_code: '来料',
          tab_name: '来料',
          value: '',
        },
      ],
    },
  ],
  rows: [{}],
  subtotal: 0,
};

export const lineItem093: LineItem = {
  productId: 'prod-001',
  productName: '测试产品',
  productPartNo: 'HF-TEST-093',
  templateId: 'tmpl-001',
  templateName: '测试模板',
  productAttributeValues: {},
  productAttributes: [],
  componentData: [materialComp as any, subtotalComp as any],
  subtotal: 0,
  subtotalFormula: [
    {
      type: 'component_subtotal',
      component_code: '来料',
      tab_name: '来料',
      value: '',
    },
  ],
};

/**
 * 带 FORMULA 字段的来料组件夹具（测试 COMPONENT_FIELD source_type）
 * 来料材料成本 = 30（FIXED_VALUE），另有一个 FORMULA 字段引用材料成本
 */
const materialCompWithFormula = {
  componentId: 'comp-mat2',
  componentCode: '来料2',
  tabName: '来料2',
  componentType: 'NORMAL' as const,
  fields: [
    {
      name: '材料成本',
      field_type: 'FIXED_VALUE' as const,
      content: '30',
      is_subtotal: false,
    },
    {
      name: '含税成本',
      field_type: 'FORMULA' as const,
      is_subtotal: true,
    },
  ],
  formulas: [
    {
      name: '含税成本',
      expression: [
        { type: 'field', value: '材料成本' },
        { type: 'operator', value: '*' },
        { type: 'number', value: '1.13' },
      ],
    },
  ],
  rows: [{ 材料成本: 30 }],
  subtotal: 0,
};

const subtotalComp2 = {
  componentId: 'comp-sub2',
  componentCode: '报价小计2',
  tabName: '报价小计2',
  componentType: 'SUBTOTAL' as const,
  fields: [
    { name: '总成本', field_type: 'FORMULA' as const, is_subtotal: true },
  ],
  formulas: [
    {
      name: '总成本',
      expression: [
        {
          type: 'component_subtotal',
          component_code: '来料2',
          tab_name: '来料2',
          value: '含税成本',
        },
      ],
    },
  ],
  rows: [{}],
  subtotal: 0,
};

export const lineItemWithFormula: LineItem = {
  productId: 'prod-002',
  productName: '含税产品',
  productPartNo: 'HF-TEST-TAX',
  templateId: 'tmpl-001',
  templateName: '测试模板',
  productAttributeValues: { category: '普通件' },
  productAttributes: [{ name: 'category', field_type: 'TEXT', required: false }],
  componentData: [materialCompWithFormula as any, subtotalComp2 as any],
  subtotal: 0,
};
