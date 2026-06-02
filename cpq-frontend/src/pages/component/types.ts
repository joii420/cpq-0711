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
  /**
   * Phase1-Snapshot: 组件级行键字段名列表。
   * 含可编辑字段的多行 driver 组件须声明；草稿重刷时按此键保留 editRows。
   * 特殊哨兵 ["__seq_no__"] = 按行号对齐（无稳定业务键时显式豁免）。
   */
  rowKeyFields?: string[];
}

export interface FieldItem {
  key: string;
  name: string;
  field_type: 'FIXED_VALUE' | 'DATA_SOURCE' | 'INPUT' | 'INPUT_TEXT' | 'INPUT_NUMBER' | 'FORMULA' | 'BASIC_DATA' | 'LIST_FORMULA';
  content?: string;
  is_amount?: boolean;
  is_subtotal?: boolean;
  notes?: string;
  /**
   * LIST_FORMULA 字段类型专用配置.
   * 该类型让组件按 source_table 每行 expand 1 行, 每行该列的值 = per_item_formulas[item_key] 在本行上下文求值.
   * 详见 docs/组件管理字段配置指南.md LIST_FORMULA 章节.
   */
  list_formula_config?: ListFormulaConfig;
  datasource_binding?: {
    /** V190+: 数据源类型枚举; 缺省值 = DATABASE_QUERY 兼容历史配置 */
    type?: 'DATABASE_QUERY' | 'GLOBAL_VARIABLE' | 'BNF_PATH' | 'HTTP_API';
    // DATABASE_QUERY 现状字段
    datasource_id?: string;
    datasource_name?: string;
    datasource_code?: string;
    params?: unknown;
    // GLOBAL_VARIABLE 配置
    global_variable_code?: string;
    key_field_refs?: Record<string, string>;
    // BNF_PATH 配置
    bnf_path?: string;
    // HTTP_API 配置 (Phase D follow-up)
    api_config?: Record<string, any>;
  };
  formula_name?: string;  // FORMULA fields: which formula definition to use
  /** BASIC_DATA 字段绑定的 BNF 路径(如 mat_part.unit_weight 或 元素BOM[元素='Ag'].组成含量) */
  basic_data_path?: string;
  /** V109: 标记此字段取值来自某全局变量 (e.g. 'ELEM_PRICE'). UI 显示徽章, 路径仍走 basic_data_path. */
  global_variable_code?: string;
  /**
   * V190: 统一的「默认值来源」结构, 替代 V184 散字段.
   * INPUT_NUMBER/TEXT 行值空时按此结构解析默认值; 用户输入即覆盖.
   *   type='GLOBAL_VARIABLE' → 查 global_variable_value 单表
   *   type='BNF_PATH'        → 走 BASIC_DATA 同款路径解析
   *   type='HTTP_API'        → 调外部 API (Phase D 引入)
   */
  default_source?: DefaultSource;
  /** 模板字段覆写元数据（仅 template_component.fields_override / template.components_snapshot 使用） */
  is_required?: boolean;
  /** 排序索引（模板 snapshot 持久化时使用，默认按数组下标） */
  sort_order?: number;
  /** V149 字段库 label 中文显示名（snapshot 透传，UI 渲染不依赖） */
  label?: string;
}

export interface DefaultSource {
  type: 'GLOBAL_VARIABLE' | 'BNF_PATH' | 'HTTP_API';
  /** GLOBAL_VARIABLE: 变量 code */
  code?: string;
  /** GLOBAL_VARIABLE: 静态 key (列名→字面值) */
  key_values?: Record<string, any>;
  /** GLOBAL_VARIABLE: 动态 key (列名→driver row 字段名; 空对象=同名映射) */
  key_field_refs?: Record<string, string>;
  /** BNF_PATH: BNF 路径字符串 */
  path?: string;
  /** HTTP_API: Phase D 引入, 暂占位 */
  api_config?: Record<string, any>;
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
    | 'global_variable'   // V104 全局变量(元素核价/材料核价/汇率) — 编译期转 BNF path
    | 'datasource_field'; // K1 引用同行 DATA_SOURCE 字段解析结果, token.name = 字段名
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
  /** datasource_field 专用 (K1): 引用的 DATA_SOURCE 字段名 */
  name?: string;
}

export const FIELD_TYPE_OPTIONS = [
  { value: 'FIXED_VALUE', label: '固定值' },
  { value: 'INPUT_TEXT', label: '文本输入' },
  { value: 'INPUT_NUMBER', label: '数字输入' },
  { value: 'BASIC_DATA', label: '基础数据' },
  { value: 'DATA_SOURCE', label: '数据源' },
  { value: 'FORMULA', label: '公式' },
  { value: 'LIST_FORMULA', label: '列表驱动公式' },
] as const;

/**
 * LIST_FORMULA 字段配置 (Phase B — 配置模板驱动).
 *
 * <p>语义: 组件按 config_template / category 的明细项数 expand 行,
 * 每行该字段的值 = per_item_rules[item.code] 按 branches 顺序求值的第一个 true 分支的公式,
 * 全不命中 → default_formula → 再无 → item.default_value (在 config_item 上).
 *
 * <p>变量作用域:
 * <ul>
 *   <li>[字段名] — 本行其他字段值 (条件 & 公式都可引用)</li>
 *   <li>{GV_CODE} — 全局变量 (Phase C 接入)</li>
 *   <li>'字符串' / 数字 — 字面值</li>
 * </ul>
 *
 * <p>同组件多个 LIST_FORMULA 字段必须绑同一 (config_template_id, category_code).
 */
export interface ListFormulaConfig {
  /** 配置模板 id (config_template.id) - 必填 */
  config_template_id: string;
  /** 模板 code (展示+冗余) */
  config_template_code?: string;
  /** 模板名 (展示用) */
  config_template_name?: string;
  /** 选定的大类 code (config_category.code) - 必填 */
  category_code: string;
  /** 大类名 (展示用) */
  category_name?: string;
  /** 每个明细项的规则: code → 分支 + 默认 */
  per_item_rules: Record<string, ListFormulaItemRule>;
}

export interface ListFormulaItemRule {
  /** IF-ELSE-IF 链: 按顺序求值, 第一个 condition=true 的 branch 取其 formula */
  branches: ListFormulaBranch[];
  /** branches 全不命中时兜底公式; 仍无 → 走 config_item.default_value */
  default_formula?: string;
}

export interface ListFormulaBranch {
  /** 条件表达式. 空 = 总是 true (相当于默认分支) */
  condition: string;
  /** 公式或字面值. 评估通过 [字段] [表.列] {GV} 求值 */
  formula: string;
}

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

/** 行键候选（后端 row-key-candidates 端点返回）。 */
export interface RowKeyCandidate {
  fieldName: string;
  displayName: string;
  /** 反查出的 driver 真实列名；不可解析时 null。 */
  resolvedColumn: string | null;
  /** true 才允许勾选为行键。 */
  eligible: boolean;
  /** 不可勾选原因（eligible=false 时 hover 提示）。 */
  reason: string | null;
}
