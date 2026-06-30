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
      is_amount: true, // BL-0017: 材料成本是金额列 → 计入 [来料(总计)]
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

// ─── Driver 展开端到端夹具 ─────────────────────────────────────────────────────
/**
 * makeLineItemWithDriver — 带 dataDriverPath + BASIC_DATA 字段的 LineItem 夹具。
 *
 * 场景：
 *   - 来料D 组件有 dataDriverPath='$v_material_driver'，字段包含：
 *     - 材料单价 (BASIC_DATA)：值来自 driver 行 basicDataValues
 *     - 用量 (INPUT_NUMBER, is_subtotal=false)：固定值 2（存于 rows）
 *     - 料件成本 (FORMULA, is_subtotal=true)：= 材料单价 * 用量
 *   - 报价小计D：= component_subtotal('来料D') 即料件成本列小计
 *
 * 用法：
 *   const item = makeLineItemWithDriver();
 *   -- 命中时：提供 driverExpansion，basicDataValues.材料单价=50，料件成本=50*2=100
 *   -- miss 时：不提供 driverExpansion，basicDataValues 缺失，材料单价=0，料件成本=0
 */

const materialCompDriver = {
  componentId: 'comp-mat-driver',
  componentCode: '来料D',
  tabName: '来料D',
  componentType: 'NORMAL' as const,
  dataDriverPath: '$v_material_driver',
  fields: [
    {
      name: '材料单价',
      field_type: 'BASIC_DATA' as const,
      basic_data_path: 'unit_price',
      is_subtotal: false,
    },
    {
      name: '用量',
      field_type: 'INPUT_NUMBER' as const,
      is_subtotal: false,
    },
    {
      name: '料件成本',
      field_type: 'FORMULA' as const,
      is_subtotal: true,
      is_amount: true, // BL-0017: 料件成本是金额列 → 计入 [来料D(总计)]
    },
  ],
  formulas: [
    {
      name: '料件成本',
      expression: [
        { type: 'field', value: '材料单价' },
        { type: 'operator', value: '*' },
        { type: 'field', value: '用量' },
      ],
    },
  ],
  // comp.rows：1 行，用量=2，材料单价留 0（driver expansion 命中时会被 basicDataValues 覆盖）
  rows: [{ 用量: 2, 材料单价: 0 }],
  subtotal: 0,
};

const subtotalCompDriver = {
  componentId: 'comp-sub-driver',
  componentCode: '报价小计D',
  tabName: '报价小计D',
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
          component_code: '来料D',
          tab_name: '来料D',
          value: '',
        },
      ],
    },
  ],
  rows: [{}],
  subtotal: 0,
};

export function makeLineItemWithDriver(): LineItem {
  return {
    id: 'li-driver-001',
    productId: 'prod-driver',
    productName: 'Driver产品',
    productPartNo: 'HF-DRIVER-001',
    templateId: 'tmpl-driver',
    templateName: 'Driver模板',
    productAttributeValues: {},
    productAttributes: [],
    componentData: [materialCompDriver as any, subtotalCompDriver as any],
    subtotal: 0,
  } as LineItem;
}
