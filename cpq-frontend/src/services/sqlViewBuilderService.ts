// 取数配置器（task-260819）· 前端 API 客户端
//
// 契约来源：dev-docs/task-260819-取数配置器/api.md（前后端唯一协调物）§0/§1/§2。
// 🚨 api.md §0：编译器只在后端，本文件不实现任何一份 SQL 生成/粒度判定/冲突判定逻辑，
//    全部字段/冲突标记/SQL 文本均来自后端响应，前端只读展示。
//
// 2026-08-20 22:xx 补充核对：api.md 本身未给出 field-tree / compile / preview / inspect / save 的精确
// JSON 形状（只给了「有这几个概念」），写到一半时发现后端在本 worktree 已落地 B-1~B-4（语义图 CRUD）+
// 一批 Sec3x 集成测试（`cpq-backend/src/test/java/com/cpq/semanticgraph/Sec31~Sec36*.java`，
// TDD 先行、B-5~B-16 编译器/builder 端点尚未实现，测试当前为红）。这些测试用 RestAssured 对着
// 具体 JSON path 断言，是目前能拿到的最接近「真源」的证据，本文件已按它们逐字段核对对齐：
//   · GET /field-tree 响应 = { groups: [{ groupName, conflict?, fields: [{ displayName, ... }] }] }
//     （不是 api.md 字面暗示的扁平 nodes 数组；旧版 SemanticGraphResource.fieldTree() 返回的
//       `ApiResponse<List<NodeDTO>>` 是 B-4 阶段的过渡实现，B-7 落地后预期会替换/包一层）
//   · compile/preview/inspect/save 的请求体一律是 { tabType, variantKey?, switches?, columns: [...] }
//     包在顶层，**不带 envelope**（不是 { data: ... }，是响应体本身）；列用扁平角色布尔位
//     isPartNo/isPartName/isRowKey/isSort/isAmount/inSubtotal，不是 roles 数组；不传 viewColumn/fieldType
//     （后端算，AC-11）；priceStrategy 只在「形态 B 手填字段覆盖」时才需要显式传
//     { elementCodeSource:'MANUAL_FIELD', elementCodeField }，正常路径完全由 columns 内容反推，不传此键
//   · GET /builder 直接给 isLegacyHandwritten / isStale / currentCompilerVersion 三个布尔/数字，
//     不需要前端自己比较版本号推导
// ⚠️ 仍然只是「目前证据所支持的最佳猜测」——B-5~B-16 尚未实现，联调前请求主线与后端对齐一次。
//
// F-16（2026-08-21，api.md §1.5③）：本文件的请求**不走** services/api.ts 的全局 `api` 单例。
// 原因：全局 `buildApiError` 假设错误体套了 `{code,message,data}` 信封，读 `error.response.data.data`；
// 但本任务后端错误体一律「裸体」——`code`/`failedCheck`/`detail`/`paths`/`suggestion` 直接在响应根，
// 没有 `.data` 这层。套用全局函数会让 `err.payload` 恒为 null，SqlViewBuilderTab.tsx 里
// `e.payload.code` / `e.payload.paths` / `e.payload.suggestion` / `e.payload.detail.affectedTemplates`
// 全部读不到值，结构化错误（COMPILE_PATH_AMBIGUOUS 的候选路径、IMPACT_CONFIRM_REQUIRED 的模板名单）
// 静默降级成只显示一句 message。
// 🚫 不改全站公共的 api.ts（会改变其它模块的信封语义，越界）——本文件建一个平行的、不挂全局
// 拦截器的 axios 客户端，`payload` 直接取 `error.response.data`（裸体响应本身）。401 重定向登录页
// 的副作用与全局口径保持一致（手动补一份，避免 builder 页面在会话过期时退化成看不懂的报错）。
import axios from 'axios';

const builderHttp = axios.create({
  baseURL: '/api/cpq',
  timeout: 30000,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
});

export interface BuilderApiError extends Error {
  /** 裸体响应体本身（api.md §1.5③）——不是 `.data.data`。COMPILE_PATH_AMBIGUOUS 的 code/paths/suggestion、
   *  IMPACT_CONFIRM_REQUIRED 的 code/detail 等结构化字段都在这一层，直接 `payload.xxx` 取。 */
  payload: unknown;
  httpStatus?: number;
}

function buildBuilderApiError(error: any): BuilderApiError {
  const body = error?.response?.data;
  const err = new Error(body?.message || error?.message || 'Network error') as BuilderApiError;
  err.payload = body ?? null;
  err.httpStatus = error?.response?.status;
  return err;
}

builderHttp.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error?.response?.status === 401) {
      window.location.href = '/login';
    }
    return Promise.reject(buildBuilderApiError(error));
  },
);

// ── 字段树（GET /config/semantic-graph/field-tree）────────────────────────

/**
 * F-30（v9 · S-27 / D-77，AC-115）：三套数据集 = 编译方言 `CompileDialect` 的三个值。
 * 需求文档 §9.8 D-77 裁决：不另加 `dataset` 维度，`dialect` 直接扩到三值 —— 所以这里的字符串
 * 必须与后端 `com.cpq.builder.compiler.CompileDialect` 的枚举名**逐字一致**（大写下划线）。
 * ⚠️ 后端 v9 改造（S-21）落地前 `CompileDialect` 仍是 `{QUOTE, COSTING}` 两值 —— 前端先按目标契约写，
 *    联调时由主线对齐（本文件顶部同款「目前证据所支持的最佳猜测」纪律）。
 */
export type BuilderDataset = 'QUOTE' | 'COST_BASIC' | 'COST_DETAIL';

export type FieldRole = 'PART_NO' | 'PART_NAME' | 'ROW_KEY' | 'SORT';
export type FieldDataType = 'TEXT' | 'NUMBER' | 'MONEY';

/** 单个可用字段（叶子）。roles 为空数组 = 该列无声明角色。 */
export interface FieldTreeColumn {
  sourceNodeKey: string;
  sourceColumn: string;
  displayName: string;
  dataType?: FieldDataType;
  /** D-25：角色来自字段树声明，配置器只读展示，不提供任何修改入口（AC-47/AC-48）。 */
  roles?: FieldRole[];
  /**
   * 视图列名（若后端在字段树阶段即预算好，会带上；未带则等 /compile 的 declaredColumns 里再对号入座）。
   * (Sheet,列) 纯函数，前端只读展示，不得自行拼接（AC-11）。
   */
  viewColumn?: string;
  /** 该列所属的展开维度，纯展示（真正的粒度/冲突判定权威在服务端）。 */
  dims?: string[];
  /**
   * 非空 = 查名列，值为维表简称，UI 显示 lookup-tag（字段是否天然带此标记待联调确认，缺失时按普通列渲染）。
   * F-18（api.md §1.4 三方形状裁决）：后端实际返回字段名为 `lookupLib`，不是 `lookupOf`——
   * 此前按 `lookupOf` 声明会导致本字段恒为 undefined，此处以 api.md 为准更正类型名。
   */
  lookupLib?: string | null;
  /** 非空 = 仅在指定 variant 下出现（D-34）。 */
  onlyVariant?: string | null;
  /** true = 价格策略元素符号列（左键）。 */
  elemKey?: boolean;
  /** PRICE 分组内核心列（删除即整组消失）。 */
  isCore?: boolean;
  /**
   * F-16（AC-60，D-51，2026-08-24）：旧语义"仅子件闭包开启时才出现"已废弃——后端 FieldTreeBuilder.Field
   * 目前未声明此属性、从未把它置为 true（改动前已是死代码）；子件闭包开关整体从界面移除后，前端不再
   * 对本字段做任何过滤判断。保留该字段声明仅为兼容可能残留的旧响应/旧类型引用，不建议新写代码消费它。
   */
  closureOnly?: boolean;
}

/** 一个 Sheet/附属源/价格策略分组。 */
export interface FieldTreeGroup {
  groupName: string;
  /** true = 与当前已选列冲突（拖拽期整组置灰，AC-16）；需要带 selectedConfig 查询参数才会算出。 */
  conflict?: boolean;
  dims?: string[];
  note?: string | null;
  fields: FieldTreeColumn[];
  /**
   * PRICE / SUB / GRAIN / JOIN / SAME / MAIN，用于渲染徽章与冲突提示文案；不影响是否可拖。
   * F-18（api.md §1.4 三方形状裁决）：后端实际返回字段名为 `groupKind`，不是 `kind`——
   * 此前按 `kind` 声明会导致本字段恒为 undefined，`group.kind === 'PRICE'` 恒 false，
   * 价格策略分组永不成块、元素列不会自动带出（AC-20/AC-21 失效）。此处以 api.md 为准更正类型名。
   */
  groupKind?: string;
  /**
   * F-30（AC-116）：本分组属于哪套数据集（= 方言）。**只能由服务端给，前端无法自行推断。**
   *
   * 🚨 实测依据（V410 种子迁移，2026-09-03）：`semantic_node.node_key` **跨方言重名** ——
   *    `MATERIAL` / `MATERIAL_BOM` / `ELEMENT_BOM` 等 10 个键在三套数据集里各有一份，
   *    区分它们的是 `physical_table`（`ds_quote_material` vs `ds_cost_basic_material`）和
   *    `semantic_node.dialect`，而 `fields[].sourceNodeKey` 给的是 node_key。
   *    ⇒ 靠 sourceNodeKey 猜数据集是做不到的，AC-116 的过滤**必须**由服务端完成。
   *
   * ✅ **入参与本字段都已就位**（2026-09-06 主线在跑 worktree 代码的临时后端上实测，与 `fetchFieldTree`
   *    的参数注释同一份证据）：`SemanticGraphResource.fieldTree()` 已声明 `@QueryParam("dialect")`
   *    且三方言各自收窄（QUOTE 11 · COST_BASIC 10 · COST_DETAIL 18）；后端 `FieldTreeBuilder.Group.dialect`
   *    也已赋值 —— 实测 `tabType=主件&dialect=QUOTE` 时 `groups[0].dialect === 'QUOTE'`（有值，非 null、非缺键）。
   *    🚫 本条**取代**旧注释里「既不接受方言入参、也不回本字段 / 缺口待后端补齐」的说法，那已过期。
   *
   * ⚠️ **但兜底保留，不要因为字段到位了就删掉它** —— 理由变了、结论没变：本字段声明为可空，
   *    `detectGroupDataset` 推不出归属（`null`）时前端**保留该分组，不藏**（见 `visibleGroups` 的
   *    `d === null || d === dataset`）。把面板变空是比多显示一组更难诊断的失败形态，
   *    所以「推不出来就不臆断」这条一直成立，与服务端做没做过滤无关。
   */
  dialect?: BuilderDataset | null;
}

/**
 * task-260904 F-1（api.md §1.2）：**一个可选的数据源**。取数配置器顶部下拉的唯一数据来源。
 *
 * 🚨 `(tabType, variantKey, dialect)` 是**三段坐标，缺一不可**（api.md §0）：`semantic_tab_view` 的
 *    唯一约束实测为 `UNIQUE (tab_type, variant_key, dialect)`，只用前两段反查会命中 3 行
 *    （QUOTE / COST_BASIC / COST_DETAIL 各一），`findFirst()` 处会静默取到错误方言的声明。
 *    ⇒ 前端选中一项后必须把三段**原样**回传给 field-tree / compile / preview / inspect / save，
 *    🚫 不许只回传前两段、也不许对任何一段做本地归一或改写（它们对前端是不透明的定位串）。
 */
export interface FieldTreeSource {
  /** 数据源稳定标识（如 `MATERIAL_BOM` / `SELF_PROCESS_FEE`），仅用作下拉 option 的 value 与 React key。 */
  sourceKey: string;
  /** 用户可见的数据源名（如「物料BOM」「自制加工费」），🚫 前端不得据它判语义。 */
  label: string;
  /** 三段坐标之一 —— 内部定位串，原样回传。 */
  tabType: string;
  /** 三段坐标之一，缺省 `""`/null。 */
  variantKey?: string | null;
  /** 三段坐标之一（= 数据集/方言）。 */
  dialect?: BuilderDataset | null;
  /**
   * api.md §1.2 的三态语义：`'TREE'`（BOM 树）/ `'MATERIAL_ELEMENT'`（材质元素）/ `null`（普通平铺）。
   * 🚫 **前端不得按 `label` 或 `sourceKey` 硬编码判断语义**（F-2 明令），一律读本字段。
   */
  semantic?: 'TREE' | 'MATERIAL_ELEMENT' | null;
}

export interface FieldTreeResponse {
  groups: FieldTreeGroup[];
  /**
   * task-260904 F-1（api.md §1.2 新增）：**本数据集下可选的数据源清单**，取代 `availableTabTypes`
   * 成为顶部下拉的数据源。后端 B-1 提供；缺失（B-1 尚未上线 / 旧响应）时前端退化为「只显示当前坐标
   * 一项」的只读态，🚫 不回退去消费 `availableTabTypes` —— 那会把已退役的「零件 / 外购件」重新放给用户选。
   */
  availableSources?: FieldTreeSource[] | null;
  /**
   * ⚠️ **已退役字段（task-260904 F-1）**：api.md §1.2 明确「availableTabTypes 移除」。
   * 保留声明只为兼容后端 B-1 上线前仍带此键的响应，**前端一行代码都不再消费它**
   * （消费它 = 把「页签类型」这个已收缩掉的概念重新暴露给用户）。
   */
  availableTabTypes?: string[];
  /** 费用类等有 variants 的页签，可选数据来源列表；未提供时「数据来源」下拉不出现。 */
  variants?: Array<{ key: string; label: string; hint?: string }> | null;
  /**
   * F-16（AC-60，D-51，2026-08-24）：前端不再渲染「选项」行，本字段即使后端仍返回也不消费——
   * 子件闭包开关已整体从界面移除（含内部枚举名不再出现在界面上）。保留字段声明仅为兼容
   * 可能仍带此键的旧响应。
   */
  switches?: string[];
  anchorDesc?: string | null;
}

/**
 * @param selectedConfig 当前已选列（同 compile 的 builderConfig.columns 形状），JSON 字符串。
 *   带上后 groups[].conflict 才会被服务端算出（AC-16 拖拽期置灰的数据依据，Sec33 测试已验证）。
 */
export const fetchFieldTree = (
  dialect: BuilderDataset,
  tabType: string,
  variantKey?: string | null,
  selectedConfig?: unknown,
): Promise<FieldTreeResponse> =>
  builderHttp.get('/config/semantic-graph/field-tree', {
    params: {
      // F-30（AC-116）：方言（=数据集）决定字段面板出哪些表。过滤只能在服务端做
      // （`semantic_tab_view` 的唯一键就是 (tab_type, variant_key, dialect)，§9.2），前端只负责传。
      // 🚨 **参数名必须是 `dialect`，不是 `dataset`**：全工程（`semantic_node.dialect` /
      //    `semantic_tab_view.dialect` / `BuilderConfig.dialect`）以及 D-77「不另加 dataset 维度，
      //    dialect 直接扩到三值」都用这个词。JAX-RS **静默忽略未知查询参数**，所以发成 `dataset`
      //    不会报错，只会让三个方言全部按缺省的 QUOTE 返回同一份清单 —— 一个没有任何信号的错。
      //    （2026-09-06 主线实测踩过一次：按 `dataset=` 打，三方言回了完全相同的 11 条 QUOTE 项。）
      // ✅ 本入参**已在服务端声明并生效**：`SemanticGraphResource.fieldTree()` 有
      //    `@QueryParam("dialect") String dialect`。2026-09-06 主线在跑 worktree 代码的临时后端上实测，
      //    三个方言各自收窄、结果不同：QUOTE 11 条 · COST_BASIC 10 条 · COST_DETAIL 18 条，
      //    且每份的 `availableSources[].dialect` 恒等于入参。
      //    ⇒ 🚫 **不要在前端再加一层本地方言过滤** —— 过滤在服务端已经做掉了；
      //       前端 `visibleSources` / `visibleGroups` 里那层同名判断是**第二道防线**
      //       （防种子把节点挂错方言），不是因为服务端没做。
      dialect,
      tabType,
      variantKey: variantKey || undefined,
      selectedConfig: selectedConfig ? JSON.stringify(selectedConfig) : undefined,
    },
  }) as Promise<any>;

// ── 取数配置（builder）────────────────────────────────────────────────────

/** 已选输出列——写请求体（compile/preview/inspect/save 共用），角色用扁平布尔位而非 roles 数组。 */
export interface BuilderColumnInput {
  sourceNodeKey: string;
  sourceColumn: string;
  fieldName: string;
  isPartNo?: boolean;
  isPartName?: boolean;
  isRowKey?: boolean;
  isSort?: boolean;
  isAmount?: boolean;
  inSubtotal?: boolean;
  /** AC-24：true = 用户手动拖入（非价格策略自动带出）——删除元素单价时不回收该列。 */
  userAdded?: boolean;
}

/** 形态 B（AC-23）：元素键改绑手填字段时才需要显式传本结构；正常路径完全不传（省略整个 priceStrategy 键）。 */
export interface PriceStrategyOverride {
  elementCodeSource: 'MANUAL_FIELD';
  elementCodeField: string;
}

export interface BuilderConfigPayload {
  /**
   * F-30（AC-115）：本视图取自哪套数据集 —— compile / preview / inspect / save 四个写端点共用同一份
   * config，所以只在这里声明一次即可全链路带上。
   *
   * 🚨 **字段名必须是 `dialect`，不能自造 `dataset`**：后端 `BuilderConfig.dialect`（B-22/D-59 引入，
   *    D-77 由两值扩到三值）就是这个字段，且 `BuilderService#resolveDialect` 对**缺省或不认识的值
   *    一律按 QUOTE 处理、不报错** ⇒ 发错名字的后果不是 400，而是**静默按报价侧编译**。
   * ⚠️ 可选而非必填：`GET /builder` 回来的存量 `builderConfig` 可能没有这个键、或带着已作废的
   *    旧值 `"COSTING"`；读回时统一过 `normalizeDataset()` 归到 `QUOTE`，不让老配置打不开。
   */
  dialect?: BuilderDataset;
  tabType: string;
  variantKey?: string | null;
  /**
   * F-16（AC-60，D-51，2026-08-24）：SqlViewBuilderTab.tsx 不再写这个键（子件闭包开关整体移除，
   * `builder_config.switches` 中不应再出现内部枚举名或 includeChildParts 这一类键）。字段留作可选，
   * 只是为了兼容 `GetBuilderResponse.builderConfig` 里可能仍带旧值的既有已保存行——不代表还有
   * 写路径会用到它。
   */
  switches?: Record<string, boolean>;
  columns: BuilderColumnInput[];
  priceStrategy?: PriceStrategyOverride | null;
}

/** GET /builder 返回的已保存列（多了后端生成的 viewColumn/fieldType，供 AC-39 刷新后原样回填）。 */
export interface SavedBuilderColumn extends BuilderColumnInput {
  viewColumn: string;
  fieldType?: string;
}
export interface SavedBuilderConfig extends Omit<BuilderConfigPayload, 'columns'> {
  builderVersion: number;
  columns: SavedBuilderColumn[];
}

/**
 * D-43（2026-08-21 主线裁决，紧急修复：`isLegacyHandwritten === (builderConfig === null)` 曾把三态
 * 压成两态，导致全新组件也被误判成「存量手写」进引导页、配置器打不开）。
 * - `NEW`：组件没有任何 component_sql_view 行 —— 前端应进空白配置器（可直接开始配）
 * - `LEGACY_HANDWRITTEN`：有 sql_view 行但 builder_config 为 NULL —— 前端显示引导页
 * - `BUILDER`：builder_config 非空 —— 前端回填已有配置
 */
export type BuilderViewState = 'NEW' | 'LEGACY_HANDWRITTEN' | 'BUILDER';

export interface GetBuilderResponse {
  /** null = 未保存过 builder 配置（全新组件，或已转手写）。 */
  builderConfig: SavedBuilderConfig | null;
  /** 与 builderConfig.builderVersion 同值的顶层冗余字段（api.md §2.1a 2026-08-20 固化）。 */
  builderVersion: number | null;
  /**
   * D-43：权威判据，三态，取代下面 `isLegacyHandwritten` 的两态语义。
   * 🚫 后端热重载可能滞后于本次前端改动——字段缺失时前端按旧判据兜底推导，不崩不误判。
   */
  viewState?: BuilderViewState;
  /**
   * 🚫 D-43 后语义收窄为 `viewState === 'LEGACY_HANDWRITTEN'`——不再是「非 BUILDER 即 true」。
   * 三态判断一律用 `viewState`，这个字段只在 `viewState` 缺失时的兜底路径里参与推导。
   */
  isLegacyHandwritten: boolean;
  /** true = builderConfig.builderVersion 低于当前编译器版本（AC-34）。后端直接给出，不用前端比较版本号。 */
  isStale: boolean;
  currentCompilerVersion: number;
  sqlTemplate?: string | null;
}

export const getBuilder = (componentId: string): Promise<GetBuilderResponse> =>
  builderHttp.get(`/components/${componentId}/builder`) as Promise<any>;

export interface CompileResponse {
  sql: string;
  declaredColumns: string[];
  requiredVariables: string[];
  grain: string[];
  rewriterCompatible: boolean;
  warnings: string[];
}

export interface CompileErrorBody {
  code: string;
  message: string;
  paths?: string[][];
  suggestion?: string;
}

export const compileBuilder = (componentId: string, req: BuilderConfigPayload): Promise<CompileResponse> =>
  builderHttp.post(`/components/${componentId}/builder/compile`, req) as Promise<any>;

export interface PreviewRequest extends BuilderConfigPayload {
  customerCode: string;
  partNo?: string;
}

export interface PreviewDiagnostic {
  level: 'WARN' | 'ERROR';
  code?: string;
  column?: string;
  message: string;
}

export interface PreviewResponse {
  rowCount: number;
  columns: string[];
  rows: Array<Record<string, unknown>>;
  elapsedMs: number;
  diagnostics: PreviewDiagnostic[];
}

export const previewBuilder = (componentId: string, req: PreviewRequest): Promise<PreviewResponse> =>
  builderHttp.post(`/components/${componentId}/builder/preview`, req) as Promise<any>;

/**
 * D-49（2026-08-21 主线裁决，紧急修复：api.md 此前从未定义 /inspect 响应体，前后端各填一套——
 * 后端实际字段名是 `items` 不是 `checks`，且顶层带一个 `blocked` 布尔标志。已补进 api.md §2.3a。
 */
export interface InspectItem {
  /** 后端用大写 'ERR'/'WARN'（Sec33 测试逐字确认），非小写。 */
  level: 'ERR' | 'WARN' | 'INFO';
  code?: string;
  message: string;
}
/** @deprecated D-49：字段名已改为 InspectItem，仅保留别名防止漏改的引用炸掉编译。 */
export type InspectCheck = InspectItem;

export interface InspectResponse {
  /** true = 存在 ERR 级项，保存会被后端拒绝——比前端自己数 level==='ERR' 的条数更权威，直接用它判保存按钮禁用态。 */
  blocked: boolean;
  items: InspectItem[];
}

export const inspectBuilder = (componentId: string, req: BuilderConfigPayload): Promise<InspectResponse> =>
  builderHttp.post(`/components/${componentId}/builder/inspect`, req) as Promise<any>;

/**
 * D-42（2026-08-21 主线裁决，api.md §2.4）：`PUT /` 请求体是「config 本身 + 平级 confirmedImpact」，
 * 不是 `{ builderConfig, confirmedImpact }` 嵌套一层——后端 `SaveRequest extends BuilderConfig`
 * 是**继承**不是**持有**，包一层会让后端把 tabType/variantKey 读成 null，报出一个跟真因无关的错
 * （如「页签视图不存在」）。三个写端点（compile/inspect 纯 config；PUT / 与 POST /preview 都是
 * config + 平级附加字段）形状必须对齐，不要在这里加嵌套层。
 */
export type SaveBuilderRequest = BuilderConfigPayload & {
  confirmedImpact?: boolean;
};

export interface SaveBuilderResponse {
  builderVersion?: number;
  affectedTemplateCount?: number;
}

export interface ImpactConfirmBody {
  code: 'IMPACT_CONFIRM_REQUIRED';
  message: string;
  detail?: { affectedTemplates?: Array<{ id: string; name: string }> };
}

export const saveBuilder = (componentId: string, req: SaveBuilderRequest): Promise<SaveBuilderResponse> =>
  builderHttp.put(`/components/${componentId}/builder`, req) as Promise<any>;

export const detachBuilder = (componentId: string): Promise<{ success?: boolean }> =>
  builderHttp.post(`/components/${componentId}/builder/detach`, {}) as Promise<any>;
