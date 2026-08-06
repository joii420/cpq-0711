// Shared types for Component Management module

export interface DirectoryNode {
  id: string;
  parentId?: string;
  name: string;
  sortOrder: number;
  children: DirectoryNode[];
  components: ComponentItem[];
}

export type ComponentType = 'NORMAL' | 'SUBTOTAL' | 'EXCEL';

/**
 * task-0721：页签类型属性(tabType)的展示颜色映射，供组件列表(ComponentManagement.tsx)
 * 与模板管理组件卡片(ComponentPalette.tsx)共享，保证两处展示一致。
 * BOM 是结构页签，用主色最醒目；其余按业务语义分色，无强制含义，仅供快速目视区分。
 */
export const TAB_TYPE_COLOR: Record<string, string> = {
  BOM: 'blue',
  材质元素: 'green',
  零件: 'default',
  外购件: 'orange',
  主件: 'purple',
};

export interface TreeConfig {
  /** 本行 ID 列字段 key(= 字段 name,如「料号」) */
  idField: string;
  /** 父 ID 列字段 key(如「父料号」) */
  parentField: string;
  /** 默认全展开(true,缺省)/全折叠(false) */
  defaultExpanded?: boolean;
}

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
  /**
   * 树表配置(可选)。非空 → 渲染时按邻接表(idField/parentField)重排成树。纯展示,不改 rowData/rowCount/行序。
   */
  treeConfig?: TreeConfig;
  /** 核价 BOM 递归展开开关(默认 true,仅核价侧生效;与 treeConfig 正交) */
  bomRecursiveExpand?: boolean;
  /**
   * task-0721 F2：页签类型属性(可空,存量组件无此属性)。
   * 声明该页签的料号列/料号名称列所属的业务语义,供后端树上加叶子的类型判定链使用(§4.3 规则二)。
   * 2026-07-21 契约变更：与 bomRecursiveExpand 后端联动派生(选 BOM → bomRecursiveExpand 自动置 true；
   * 其余值/清空 → 自动置 false)，前端只维护 tabType 一个字段，不再单独暴露 bomRecursiveExpand 开关。
   */
  tabType?: 'BOM' | '材质元素' | '零件' | '外购件' | '主件';
  /**
   * task-0721 F2（2026-07-23 修订，需求说明 §4.3 规则一「匹配标识放宽」）：该页签哪个字段是料号列。
   * 从该组件已有字段中选（取字段 name，不是自由输入）。类型判定与加叶子候选采集
   * 依据此字段显式取值（优先），不靠字段名/label 含"料号"启发式猜测。
   * BOM 树页签（tabType=BOM）可不配（取系统列 __hfPartNo）；
   * 非树页签（材质元素/零件/外购件/主件）与 partNameField **至少配一个**——不强制要求料号列，
   * 有些页签只有名称没有料号（如"外购件/费用"类页签用"料件名称=组成件1"标识）。
   */
  partNoField?: string;
  /**
   * task-0721 F2（2026-07-23 修订）：该页签哪个字段是料号名称列（从字段中选）。
   * 取值口径：候选采集/类型判定按 partNoField 优先，partNoField 为空则用本字段兜底。
   * 与 partNoField 至少配一个作为该页签的匹配标识；两者都配时以 partNoField 为准。
   */
  partNameField?: string;
  /**
   * task-0729 屏 8（需求说明 §11.15.3.1 组件级三个角色字段）：该组件的 SQL 视图若接了
   * 客户取价函数（f_customer_element_price / f_material_element_price），须显式指定
   * 元素列 / 元素单价列 / 货币列（从字段中选，取字段 name）。
   * 🔒 组件级属性，与 partNoField/partNameField 平级——不放进字段 config JSON，
   * 不触发 AP-44（前端渲染层不消费这三个字段，只用于组件管理侧的绑定校验/预填）。
   * 保存期校验（后端）：检测到取价函数 → elementCodeField + elementPriceField 必填，
   * 否则 400 COMPONENT_ELEMENT_BINDING_REQUIRED；未接取价函数的组件三项留空可正常保存。
   */
  elementCodeField?: string;
  /** 同上，元素单价列（与 elementCodeField 同必填条件） */
  elementPriceField?: string;
  /** 同上，货币列（可空） */
  elementCurrencyField?: string;
  /**
   * Task 3.1: EXCEL 组件持有的列定义(JSON 数组字符串).
   * 仅 componentType==='EXCEL' 的组件非空; 模板 Excel 视图通过 excel_component_id 引用本字段 + column_overrides 合并.
   * 数组元素形如 { col_key, title, source_type, hidden, formula, ... }.
   */
  excelColumns?: string;
  /** 后端 ComponentDTO.updatedAt（ISO 字符串）；草稿陈旧检测用。getById 返回。 */
  updatedAt?: string;
  /**
   * task-0805 R3/§1.4：派生标记（非数据库列，后端 ComponentDTO 按 fields 计算，不加迁移）。
   * true = 该组件存在 field_type=FORMULA 且无 conditional_formula 且无 formula_id 的字段——
   * 含 ignoreUnboundFormulas=true 放行导入的组件，也含 BL-0098 之前遗留的坏配置。
   * 引导用户使用「固化绑定」（FormulaBindingConsolidateDrawer）或在字段配置中手工显式选择公式。
   */
  hasUnboundFormula?: boolean;
}

export interface FieldItem {
  key: string;
  name: string;
  field_type: 'FIXED_VALUE' | 'DATA_SOURCE' | 'INPUT' | 'INPUT_TEXT' | 'INPUT_NUMBER' | 'FORMULA' | 'BASIC_DATA' | 'LIST_FORMULA';
  content?: string;
  is_amount?: boolean;
  is_subtotal?: boolean;
  /** Plan 3a：条件公式。存在即走条件模式（优先于 formula_name）。when 为 CondTree（见 utils/condTree）。 */
  /**
   * Plan 3a：条件公式。存在即走条件模式（优先于 formula_name）。
   * BL-0098：`formula_id` / `default_formula_id` 是解析主键（稳定不变），
   * `formula` / `default` 降级为展示冗余 + 存量兼容读 —— 公式改名后名字会失配，id 不会。
   */
  conditional_formula?: {
    rules: { when: any; formula: string; formula_id?: string }[];
    default: string;
    default_formula_id?: string;
  };
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
  formula_name?: string;  // FORMULA fields: which formula definition to use（BL-0098 后降级为展示冗余）
  /**
   * BL-0098：FORMULA 字段绑定的公式**稳定 id**，解析主键，优先级高于 formula_name。
   * 后端 FormulaCalculator.resolveFormula 按 formula_id → formula_name → … 顺序解析。
   */
  formula_id?: string;
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
  /**
   * 单位换算来源：指向同组件内单位文本字段的 name；非空则该数值列在公式计算前按同行单位归一到 KG/PCS。
   * 存储值 = 被引用字段的 name（字符串）。留空 = 不换算。
   */
  unit_source_field?: string;
  /** 报价单/核价单渲染时该字段列的展示宽度(px)。空/0 = 默认 120。仅展示用，不参与计算。 */
  width?: number;
  /** 显示小数位数（卡片/Excel/导出共用）。未配 → 计算列兜底 2 位、输入/取数列保留原精度。 */
  decimals?: number | null;
}

export interface DefaultSource {
  type: 'GLOBAL_VARIABLE' | 'BNF_PATH' | 'HTTP_API' | 'BASIC_DATA';
  /** GLOBAL_VARIABLE: 变量 code */
  code?: string;
  /** GLOBAL_VARIABLE: 静态 key (列名→字面值) */
  key_values?: Record<string, any>;
  /** GLOBAL_VARIABLE: 动态 key (列名→driver row 字段名; 空对象=同名映射) */
  key_field_refs?: Record<string, string>;
  /** BNF_PATH / BASIC_DATA: 路径字符串。BASIC_DATA 时为 "$view.列" 形态(如 "$cp_view.品名"),支持中文列 */
  path?: string;
  /** HTTP_API: Phase D 引入, 暂占位 */
  api_config?: Record<string, any>;
}

export interface FormulaItem {
  key: string;
  /**
   * BL-0098：公式的不可变稳定 id（作用域 = 组件内，不要求全局唯一）。
   * 🚨 加载时必须原样保留、保存时必须原样送回 —— 丢了会让所有字段绑定失配、那些列静默不出值。
   * 现有链路已天然满足：ComponentManagement 加载 `{...f, key}`、保存 `({key: _k, ...rest})`、
   * componentDraft.stripFieldKeys 只剥 key。新建公式由 newFormulaRow() 就地生成，
   * 后端 FormulaIdBinder.ensureFormulaIds 只做兜底。
   */
  id?: string;
  name: string;
  expression: FormulaToken[];
  result_type?: string;
}

export interface FormulaToken {
  type:
    | 'field'
    | 'b_field'           // 本组件 (B) 字段引用 — 用于 cross_tab_ref targetExpr 内
    | 'operator'
    | 'bracket_open'
    | 'bracket_close'
    | 'component_subtotal'
    | 'product_attribute'
    | 'quotation_field'
    | 'number'
    | 'path'              // V5 BNF 物理表路径,直接引用基础数据(mat_part / mat_bom / mat_fee 等)
    | 'global_variable'   // V104 全局变量(元素核价/材料核价/汇率) — 编译期转 BNF path
    | 'datasource_field'  // K1 引用同行 DATA_SOURCE 字段解析结果, token.name = 字段名
    | 'cross_tab_ref'     // 跨页签引用(聚合/条件匹配另一页签字段)
    | 'tree_ref'          // task-0803: BOM 树父子取值引用 —— dir=PARENT(子取父,agg 恒 NONE) / dir=CHILD(父取直接子,agg=SUM|AVG|MAX|MIN|COUNT); targetExpr 为取值表达式
    | 'tree_attr';        // task-0803: BOM 树属性引用 —— attr=LVL(层级) | IS_LEAF(是否叶子) | IS_ROOT(是否根节点)
  value?: string;
  label?: string;
  component_code?: string;
  tab_name?: string;
  /**
   * component_subtotal 专用 (2026-06-30, WYSIWYG)：标记该 token 为「整页签总计」引用 [页签(总计)]，
   * 以区别于「具体小计列」引用 [页签.列]（两者 value 可能同形）。仅供序列化/显示回显 [页签(总计)]，
   * **求值器一律不读此标记**（只读 value/tab_name/component_code），故对公式计算零影响。
   */
  is_tab_total?: boolean;
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
  // ---- cross_tab_ref 专用字段 ----
  /** 源组件 componentId (UUID, AP-37 稳定 ID) */
  source?: string;
  /** 源组件显示名（用于 chip 显示） */
  sourceLabel?: string;
  /** 源组件目标字段名；COUNT 聚合时为空字符串 */
  target?: string;
  /** 行匹配条件：a = 源 (A) 字段名，b = 本组件 (B) 字段名 */
  match?: Array<{ a: string; b: string }>;
  /** 聚合方式：NONE / SUM / AVG / COUNT / MAX / MIN */
  agg?: string;
  /**
   * 目标公式（可选）：非空时优先于 target 单列，用于计算派生指标。
   * 支持 field(A列) / b_field(B本组件列) / operator / bracket / number / global_variable tokens。
   */
  targetExpr?: FormulaToken[];
  /** v1 多 source 有序链 (最细→更粗); source 镜像为最细 sources[0] */
  sources?: Array<{ source: string; sourceLabel?: string; match: Array<{ a: string; b: string }> }>;
  /** v2 KSUM: true = 按宿主结果行键塌缩成宿主粒度标量 (区别外层 join-set 聚合); 缺省 false */
  projectToHostKey?: boolean;
  /**
   * SUMIF 族专用：条件过滤谓词（与 ExpressionToken.predicate 同构）。
   * 运行时由 buildSumifToken 填入，落库后随 FormulaToken[] 持久化。
   * 类型使用 unknown 避免循环依赖 formulaEngine；求值侧转 ExpressionToken 后正常访问。
   */
  predicate?: unknown;
  // ---- tree_ref 专用字段（task-0803：BOM 树父子取值）----
  /**
   * tree_ref 专用：引用方向。
   * - 'PARENT' = PGET，子行取父行的值（agg 恒为 'NONE'，父行唯一，无需聚合）。
   * - 'CHILD'  = C* 族，父行取其「直接子」行的聚合值（agg 取 'SUM'|'AVG'|'MAX'|'MIN'|'COUNT'，见本接口既有的 agg 字段）。
   */
  dir?: 'PARENT' | 'CHILD';
  // ---- tree_attr 专用字段（task-0803：BOM 树属性）----
  /**
   * tree_attr 专用：树属性名。
   * - 'LVL'     = 当前行在树中的层级（根为 0，逐层 +1）。
   * - 'IS_LEAF' = 当前行是否为叶子节点（布尔）。
   * - 'IS_ROOT' = 当前行是否为根节点（布尔）。
   */
  attr?: 'LVL' | 'IS_LEAF' | 'IS_ROOT';
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
    // BL-0098：新建即生成稳定 id —— 否则条件公式抽屉/字段下拉在「公式尚未保存」时没有 id 可绑，
    // 只能退回绑名字，等于绕开本次修复。后端 ensureFormulaIds 仍会兜底（防绕过 UI 的调用）。
    id: newFormulaId(),
    name: '',
    expression: [],
    result_type: 'NUMBER',
  };
}

/** 生成公式稳定 id；优先用 crypto.randomUUID，老浏览器回退时间戳+随机数。 */
function newFormulaId(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `f-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
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
  /** 行键来源："driver" | "input"；eligible=false 时可能为 undefined。 */
  source?: 'driver' | 'input' | null;
}

/** 字段列在报价单/核价单表格渲染时的默认展示宽度(px)，未设置时使用。 */
export const DEFAULT_FIELD_WIDTH = 120;

/** 字段宽度预设档位：窄/中/宽。仅作 UI 快捷，最终只存像素值。 */
export const FIELD_WIDTH_PRESETS = [
  { label: '窄', value: 80 },
  { label: '中', value: 120 },
  { label: '宽', value: 200 },
] as const;

/**
 * 解析字段展示宽度(px)。width 为空 / 0 / 负数 一律视为未设置 → 返回 DEFAULT_FIELD_WIDTH。
 * 报价单/核价单详情页与编辑页渲染列宽的唯一真源。
 */
export function resolveFieldWidth(width?: number | null): number {
  return typeof width === 'number' && width > 0 ? width : DEFAULT_FIELD_WIDTH;
}
