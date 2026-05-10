// Shared types for Component Management module

export interface DirectoryNode {
  id: string;
  parentId?: string;
  name: string;
  sortOrder: number;
  children: DirectoryNode[];
  components: ComponentItem[];
}

export type ComponentType = 'NORMAL' | 'SUBTOTAL';

export interface ComponentItem {
  id: string;
  directoryId?: string;
  name: string;
  code: string;
  columnCount: number;
  fields: FieldItem[];
  formulas: FormulaItem[];
  componentType: ComponentType;
  status: string;
  /**
   * Y1.5 行驱动 BNF 路径(可选)。
   * 非空 → 报价单端按此路径返回的 N 行展开组件,字段路径自动隐式 JOIN driver 行字段。
   */
  dataDriverPath?: string;
}

export interface FieldItem {
  key: string;
  name: string;
  field_type: 'FIXED_VALUE' | 'DATA_SOURCE' | 'INPUT' | 'INPUT_TEXT' | 'INPUT_NUMBER' | 'FORMULA' | 'BASIC_DATA';
  content?: string;
  is_amount?: boolean;
  is_subtotal?: boolean;
  notes?: string;
  datasource_binding?: { datasource_id: string; params?: unknown };
  formula_name?: string;  // FORMULA fields: which formula definition to use
  /** BASIC_DATA 字段绑定的 BNF 路径(如 mat_part.unit_weight 或 元素BOM[元素='Ag'].组成含量) */
  basic_data_path?: string;
  /** V109: 标记此字段取值来自某全局变量 (e.g. 'ELEM_PRICE'). UI 显示徽章, 路径仍走 basic_data_path. */
  global_variable_code?: string;
}

export interface FormulaItem {
  key: string;
  name: string;
  expression: FormulaToken[];
  result_type?: string;
}

export interface FormulaToken {
  type:
    | 'field'
    | 'operator'
    | 'bracket_open'
    | 'bracket_close'
    | 'component_subtotal'
    | 'product_attribute'
    | 'quotation_field'
    | 'number'
    | 'path'              // V5 BNF 物理表路径,直接引用基础数据(mat_part / mat_bom / mat_fee 等)
    | 'global_variable';  // V104 全局变量(元素核价/材料核价/汇率) — 编译期转 BNF path
  value?: string;
  label?: string;
  component_code?: string;
  tab_name?: string;
  attribute_name?: string;
  /** path token 专用:BNF 路径原始字符串(如 mat_part.unit_weight 或 元素BOM[元素='Ag'].组成含量) */
  path?: string;
  /** global_variable 专用:注册表 code (ELEM_PRICE / MAT_PRICE / EXCHANGE_RATE) */
  code?: string;
  /** global_variable 专用:静态 key 值, key 列名 → 字面值 (如 {element_code:'Cu'}) */
  key_values?: Record<string, any>;
  /** global_variable 专用:动态 key 引用, key 列名 → 同行字段名 (如 {element_code:'电镀元素'}) */
  key_field_refs?: Record<string, string>;
}

export const FIELD_TYPE_OPTIONS = [
  { value: 'FIXED_VALUE', label: '固定值' },
  { value: 'INPUT_TEXT', label: '文本输入' },
  { value: 'INPUT_NUMBER', label: '数字输入' },
  { value: 'BASIC_DATA', label: '基础数据' },
  { value: 'DATA_SOURCE', label: '数据源' },
  { value: 'FORMULA', label: '公式' },
] as const;

export function newFieldRow(): FieldItem {
  return {
    key: `field-${Date.now()}-${Math.random()}`,
    name: '',
    field_type: 'INPUT_TEXT',
    content: '',
    is_amount: false,
    is_subtotal: false,
    notes: '',
  };
}

export function newFormulaRow(): FormulaItem {
  return {
    key: `formula-${Date.now()}-${Math.random()}`,
    name: '',
    expression: [],
    result_type: 'NUMBER',
  };
}
