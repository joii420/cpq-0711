// 取数配置器（task-260819）· 「取数配置」Tab
//
// 服务 F-1 ~ F-13（F-14 是自检声明，不在代码里）。任务书：dev-docs/task-260819-取数配置器/fronttask.md
// AC 原文：dev-docs/task-260819-取数配置器/需求文档.md §3。1:1 还原基准：原型图/原型-取数配置器.html
//
// 🚨 api.md §0：编译器只在后端。本文件不实现任何一份 SQL 生成 / 粒度判定逻辑 ——
//    右侧 SQL 面板的文本、粒度条的文案、体检结论、AC-16 拖拽期置灰的冲突标记全部原样取自后端响应
//    （GET /field-tree 带 selectedConfig 时返回 groups[].conflict），前端只读展示、不自行判定。
//    详见 sqlViewBuilderService.ts 顶部注释（含与 api.md §2.1a 的对齐记录）。
import React, { forwardRef, useEffect, useImperativeHandle, useMemo, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Drawer, Dropdown, Input, Segmented, Select, Space, message, Modal, Tooltip } from 'antd';
import type { MenuProps } from 'antd';
import {
  fetchFieldTree, getBuilder, compileBuilder, previewBuilder, inspectBuilder, saveBuilder, detachBuilder,
  type FieldTreeResponse, type FieldTreeColumn, type FieldTreeGroup, type BuilderConfigPayload, type CompileResponse,
  type CompileErrorBody, type PreviewResponse, type InspectResponse, type FieldRole, type SavedBuilderColumn,
  type BuilderDataset, type FieldTreeSource,
} from '../../services/sqlViewBuilderService';
import { customerService } from '../../services/customerService';

// ── 常量 ────────────────────────────────────────────────────────────────

/**
 * F-30（v9 · S-27，AC-115）：三套数据集。**顺序、显示名与原型 `原型-v9-数据集与字段面板.html` 的
 * `DS` 常量逐字一致**（报价 / 基础核价 / 明细核价）。
 *
 * 📌 这里为什么可以是本地常量（而「数据来源」下拉的选项绝对不可以）：
 *    数据集是 D-77 裁死的三值枚举（= 后端 `CompileDialect` 的三个枚举名），属于**协议常量**；
 *    而费用类的「数据来源」变体数随数据集变（报价 8 / 基础核价 7 / 明细核价 15，§9.2），是**数据**，
 *    只能来自 `GET /field-tree` 的 `variants`（见下方渲染处，本次改动一个数字都没往里写）。
 *
 * `axis` / `tablePrefix` 同属数据集身份（需求文档 §9.1.1 的轴列定义），只用于顶部说明行的文案；
 * 🚫 不参与任何过滤/编译判定 —— 真正的「本数据集有哪些表」由服务端字段树给出。
 */
const DATASETS: ReadonlyArray<{
  key: BuilderDataset; label: string; tablePrefix: string; axis: string; note: string;
}> = [
  {
    key: 'QUOTE', label: '报价', tablePrefix: 'ds_quote_', axis: '销售料号 material_no',
    note: '版本切换是核价侧独有功能，报价侧不建全版本视图',
  },
  {
    key: 'COST_BASIC', label: '基础核价', tablePrefix: 'ds_cost_basic_', axis: '生产料号 production_no',
    note: '带版本表指向 v_<主表>_all 全版本视图',
  },
  {
    key: 'COST_DETAIL', label: '明细核价', tablePrefix: 'ds_cost_detail_', axis: '生产料号 production_no',
    note: '带版本表指向 v_<主表>_all 全版本视图',
  },
];
const DEFAULT_DATASET: BuilderDataset = 'QUOTE';
const datasetLabel = (k: BuilderDataset) => DATASETS.find((d) => d.key === k)?.label ?? k;

/**
 * F-30：明确**不进语义图**的表（配置器里拖不到），照原型的 `deadBlock()` 渲染成一块警示。
 * 原依据是 task-260819 需求文档 §9.2 的「不进图」行 + N-18 / N-19 两条明确不做项 —— 这些表按定义
 * 不会出现在 `GET /field-tree` 的响应里，所以只能由前端说明「它为什么不在这儿」，否则配置人员会一直找它。
 * ⚠️ task-260907 起 QUOTE 的 4 张已全部接入，清单为空（见下方常量注释）。
 * 🚫 这不是「本数据集有哪些表」的清单（那由服务端给），只是缺席原因的说明文案。
 */
const EXCLUDED_TABLES: Partial<Record<BuilderDataset, Array<{ table: string; label: string; reason: string }>>> = {
  /**
   * task-260907（AC-1③ / api.md §1.4 / 原型 `取数配置Tab-F1-物料双表.html` 的 warnbox）：
   * QUOTE 原有的 4 张「不进图」表**本次全部接入语义图** ⇒ 清单清空 ⇒
   * `renderExcludedTables()` 返回 null ⇒ **该提示整条消失**。
   *   · ds_quote_customer_part      → F-1/B-1 并入「物料」数据源（CUSTOMER_PART / AUX 组）
   *   · ds_quote_annual_discount    → F-4/B-2 独立数据源「年降系数」
   *   · ds_quote_assembly_fee_annual→ F-4/B-2 独立数据源「组装加工费年降」
   *   · ds_quote_incoming_annual    → F-4/B-2 独立数据源「来料年降」
   * ⚠️ 原 N-18 / N-19 两条「不做」裁决已被用户 2026-09-07 的新裁决推翻，不要照旧文档把它们加回来。
   * 📌 机制本身保留（这块警示是**前端硬编码的缺席原因说明**，不是服务端清单）——
   *    以后若又有表明确不进图，在此登记即可。
   *
   * 🚨 **合并前置（本条只存在于代码里，文档没有对应耦合，别删）**：
   *    本清单是**前端硬编码**，与后端是否真的把那 4 张表接进语义图**没有任何耦合** ——
   *    清空它，这条提示就**无条件消失**，不管 B-1 / B-2 有没有落地。
   *    ⇒ 若 B-1 / B-2 滑期，界面会**既没有这 4 张表的字段、也没有「它为什么不在这儿」的解释，
   *      且没有任何信号**（不报错、不告警，只是安静地少一块）。
   *    ⇒ **合并前必须先确认 AC-1①（物料源真的出两张表的列）与 AC-11①（下拉真的到 14 项）实测通过。**
   *    （由 frontend-engineer 2026-09-07 提出、主线当日裁决钉为合并前置；api.md §1.4 的归属已同步更正。）
   */
  QUOTE: [],
};

/**
 * F-30（AC-116）：一个字段分组属于哪套数据集 —— **只认服务端给的 `group.dialect`**。
 *
 * 🚨 **前端不可能自己推断出来，别再试**（2026-09-03 实测 V410 种子迁移）：
 *    `semantic_node.node_key` 跨方言重名 —— `MATERIAL` / `MATERIAL_BOM` / `ELEMENT_BOM` 等
 *    **10 个键在三套数据集里各有一份**，真正区分它们的是 `physical_table`
 *    （`ds_quote_material` vs `ds_cost_basic_material` vs `ds_cost_detail_material`）与
 *    `semantic_node.dialect`，而字段树只给 `sourceNodeKey`（= node_key）。
 *    ⇒ 曾经写过的「按 sourceNodeKey 的 ds_* 前缀推断」是**永远不命中的死代码**，已删除。
 *
 * 🚫 返回 null（服务端没给）时**保留该分组**，不藏：把面板变空是比多显示更难诊断的失败形态。
 *    这意味着 **AC-116 的达成取决于服务端**（见 sqlViewBuilderService.ts `FieldTreeGroup.dialect`
 *    的注释：`GET /field-tree` 目前既不收方言入参也不回该字段，缺口已报主线）。
 */
function detectGroupDataset(g: FieldTreeGroup): BuilderDataset | null {
  return g.dialect ?? null;
}

/** 把存量 / 异常的方言值归一到三值之一（旧值 `"COSTING"`、缺省、拼错都落到 QUOTE，与后端 resolveDialect 同口径）。 */
function normalizeDataset(v: unknown): BuilderDataset {
  return DATASETS.some((d) => d.key === v) ? (v as BuilderDataset) : DEFAULT_DATASET;
}

/**
 * task-260904 F-1：**页签类型的 6 值硬编码常量（`TAB_TYPES` / `TAB_TYPE_LABEL`）已整体删除。**
 *
 * 用户面上不再有「页签类型」这个概念 —— 顶部只有一个「数据源」下拉，选项**全部**来自
 * `GET /field-tree` 的 `availableSources`（api.md §1.2）。前端手上的 `tabType` / `variantKey` /
 * `dialect` 退化为**不透明的三段定位串**：从 `availableSources[]` 里取出来、原样存进 state、
 * 原样回传给 field-tree / compile / preview / inspect / save，🚫 中途不做任何本地比较、归一或改写。
 *
 * ⚠️ **唯一保留的一个 tabType 字面量就在下面**，它不是「类型清单」而是**冷启动种子**：
 *    `GET /field-tree` 的 `tabType` 是必填入参（api.md §1.1，本次不改），而 `availableSources`
 *    本身正是该接口的返回值 —— 拿不到清单就发不出第一个请求。因此新组件（没有 `initialTabType`、
 *    也没有已保存的 `builder_config`）需要一个坐标把第一次请求发出去。
 *    取值 `'主件'` = 改动前 `TAB_TYPES[0]` 的同一个值，冷启动行为与改动前逐字一致；
 *    §9.2 映射表里三套数据集都有主件，是唯一一个必定可选的落点。
 *    🚫 不许拿它派生任何下拉选项 / 语义判定 —— 一旦 `availableSources` 到手，它就再无作用。
 *    📌 已报主线：若 B-1 后续允许「不传 tabType 时只回 availableSources」，本常量即可删除。
 */
const BOOTSTRAP_TAB_TYPE = '主件';

/**
 * F-2/F-3：数据源语义的**只读回显**文案（原型 `原型图/取数配置Tab.html` 的 `SEM_TEXT`，逐字一致）。
 * 🚫 键是后端给的 `semantic` 枚举值，不是 label/sourceKey —— F-2 明令不得按名字硬编码判语义。
 */
const SEM_TEXT: Record<'TREE' | 'MATERIAL_ELEMENT', string> = {
  TREE: 'BOM 树 · 递归展开',
  MATERIAL_ELEMENT: '材质元素 · 可配价格策略',
};
/** F-2：TREE 语义的附加提示，文案取自 api.md §1.2 的「前端行为」列。 */
const TREE_HINT = '本页签为树形，只读、不参与回填';
/**
 * 服务端还没给 `availableSources` 时，下拉里那一项**退化选项**的 value。
 * 只是个占位 key（`handleSourceChange` 在 `visibleSources` 里查不到它，直接 return，点了不出事），
 * 🚫 它不是数据源标识，也不会被发给后端。
 */
const FALLBACK_SOURCE_KEY = '__current__';
const ROLE_LABEL: Record<FieldRole, string> = { PART_NO: '料号', PART_NAME: '名称', ROW_KEY: '行键', SORT: '排序' };
const DATA_TYPE_LABEL: Record<string, string> = { TEXT: '文本', NUMBER: '数字', MONEY: '金额' };

/**
 * task-260909 F-1（AC-1）：字段类型的值域 —— **恰好 3 个**，与 `api.md §1.2` 逐字一致。
 *
 * 🚫 **不含 `FORMULA` / `DATA_SOURCE` / `FIXED_VALUE`**：它们分别需要 `formula_id` / `binding` /
 *    `content`，取数配置器**根本不收集这三样** ⇒ 放进选项只会让人配出必然坏的字段（落库后
 *    渲染层取不到值、静默回退成空）。后端本次也加了同一份白名单（`api.md §1.4`，非法值 400）。
 */
type BuilderFieldType = 'BASIC_DATA' | 'INPUT_TEXT' | 'INPUT_NUMBER';
/** 选项文案逐字取自原型 `原型图/02-选择器展开态.html`（含 `基础数据` 的副标「只读展示」）。 */
const FIELD_TYPE_OPTIONS: ReadonlyArray<{ value: BuilderFieldType; label: string; hint?: string }> = [
  { value: 'BASIC_DATA', label: '基础数据', hint: '只读展示' },
  { value: 'INPUT_TEXT', label: '文本输入' },
  { value: 'INPUT_NUMBER', label: '数字输入' },
];

/**
 * task-260909 F-4（AC-3 / AC-4 / AC-5）：**新拖入列的默认字段类型 —— 按数据集方言分派。**
 *
 * | dialect | 默认 |
 * |---|---|
 * | `COST_BASIC` / `COST_DETAIL` | `BASIC_DATA`（核价侧的列是从 SQL 视图取的展示值，不是用户输入） |
 * | `QUOTE` | 按数据类型推 `INPUT_TEXT` / `INPUT_NUMBER`（**现状不变**，报价侧用户确实要填数） |
 *
 * 🚨 **本函数是前端侧「默认字段类型」的唯一实现，三个调用点共用**（task-260909 F-7 追加第 3 个）：
 *    1. `toSelColumn`  —— 新拖入的列的初值（F-4）
 *    2. `fromSavedColumn` —— 已保存列回填、且**后端没给出** `fieldType` 时（F-7；下同）
 *    3. rehydrate 分支 —— 同 2，只是这条能拿到字段树里真实的 `dataType`
 *    🚫 **三处必须同规则** —— 分叉过一次就会出现「新建的列和打开后看到的列类型不一样」，
 *       而这种不一致不报错、只在保存后才显形。
 *
 * 🔑 **前后端各自算一次默认值是有意的双写，🚫 不要"顺手收敛到后端一处"**：
 *    · 前端算 → 用户在界面上**一眼看到的就是将要保存的值**（选择器不是必填项，默认即正确）；
 *      收敛到后端就意味着"界面上先显示一个假值、保存后才变成真值"，那正是本任务要消灭的静默面。
 *    · 后端算（`BuilderService#defaultFieldType`）→ **旧客户端不传 `fieldType` 时仍然正确**（AC-9）。
 *    两边规则必须逐位一致，改一边必须同步改另一边（`api.md §1.3` 是两边共同的契约）。
 *
 * 🚨 **`QUOTE` 分支的判据是 `dataType !== 'TEXT'` 而不是"是不是 MONEY"**（2026-09-09 修正）：
 *    后端原文是 `"TEXT".equals(col.resolvedDataType) ? "INPUT_TEXT" : "INPUT_NUMBER"` ⇒ **`NUMBER` 走
 *    `INPUT_NUMBER`**。而本文件此前的本地初值写的是 `money ? 'INPUT_NUMBER' : 'INPUT_TEXT'`
 *    （`money` = `dataType === 'MONEY'`），把 `NUMBER` 判成了 `INPUT_TEXT` —— 改动前它**从不发给后端**，
 *    所以这个分歧一直是死的；本次开始发送后，若照抄旧式就会把 `NUMBER` 列由 `INPUT_NUMBER`
 *    悄悄改成 `INPUT_TEXT`，正好踩掉 AC-5「与改动前逐位一致」。
 */
function defaultFieldTypeFor(dataset: BuilderDataset, dataType: string | undefined): BuilderFieldType {
  if (dataset === 'COST_BASIC' || dataset === 'COST_DETAIL') return 'BASIC_DATA';
  return dataType === 'TEXT' ? 'INPUT_TEXT' : 'INPUT_NUMBER';
}

/**
 * task-260909 F-7（AC-15）：**回填时后端没给 `fieldType`** 该显示什么。
 *
 * 🚨 **权威值来自后端，不是这里**：`api.md §1.6`（B-5）规定 `GET /builder` 在
 *    `builder_config.columns[].fieldType` 为空时，**按字段名回填 `component.fields[].field_type`
 *    的真实值**再返回 —— 不变量是「界面回填显示的 == 实际生效的」。
 *    ⇒ 正常路径下本兜底**不会触发**；它只兜「builder_config 有这一列、而 component.fields 里没有」
 *    这类漂移。
 *
 * 🚫 **改动前这里是无条件 `|| 'INPUT_TEXT'`，那是一颗雷**：后端一旦返空，`NUMBER` 列会被
 *    静默降级成文本，而用户"什么都没改、只是打开看了一眼再保存"。
 * ✅ 现在与新列走**同一个** `defaultFieldTypeFor`（F-7 要求的三处同规则）。
 *
 * ⚠️ **本兜底的正确性依赖 B-5 已落地**：B-5 缺席时，存量**核价**组件（`builder_config.fieldType`
 *    全为 null、而库里是 `INPUT_*`）会被这条兜底显示成「基础数据」，不改就保存即翻转 —— 已报主线。
 */

const colKey = (sourceNodeKey: string, sourceColumn: string) => `${sourceNodeKey}::${sourceColumn}`;

// ── 已选输出列的本地展示态 ──────────────────────────────────────────────

interface SelColumn {
  /** 前端本地稳定 key，拖拽排序/删除/改名一律按此定位，不用数组下标（AP-54 教训）。 */
  _uid: string;
  sourceNodeKey: string;
  sourceColumn: string;
  fieldName: string;
  /** 保存前的原字段名快照，用于 AC-12 的「改名影响」提示；保存成功后清空重置。 */
  origFieldName: string;
  /**
   * D-12/D-13：视图列名，后端按 (Sheet,列) 纯函数生成，只读展示，不参与本地拼接。
   * 新拖入、尚未编译过一次的列此值为空——由最近一次 /compile 的 declaredColumns 按位置回填
   * （见 syncViewColumnsFromCompile；这是"最佳努力"关联，非后端逐列显式返回，已知假设见文件尾注）。
   */
  viewColumn: string;
  fieldType: string;
  dataType: 'TEXT' | 'NUMBER' | 'MONEY';
  isAmount: boolean;
  inSubtotal: boolean;
  roles: FieldRole[];
  groupLabel?: string | null;
  groupKind?: string;
  lookupLib?: string | null;
  /** 价格策略原子组核心/外围列（元素单价=core、货币=非core），无 _ 前缀。 */
  raw?: boolean;
  isCore?: boolean;
  /** 元素符号列（价格策略左键）。 */
  elemKey?: boolean;
  /** 拖「元素单价」时自动带出的元素列（非用户手选）——AC-24 判定"是否可被回收"的依据；写请求体的 userAdded 取反。 */
  autoElem?: boolean;
}

let uidSeq = 0;
const nextUid = () => `svb-${Date.now()}-${uidSeq++}`;

/**
 * task-260908 F-2（AC-15）：这一列是不是行键列。
 * 🚫 判据只认字段树声明的 `ROW_KEY` 角色（与 ROLE_LABEL 同源），不按字段名/下标猜。
 */
const isRowKeyCol = (s: SelColumn) => s.roles.includes('ROW_KEY');
/** F-2（AC-15）：✕ 禁用态的 hover 原因文案（frontend.md §1.2：禁用但可见 + 说明原因）。原型 02 状态 1 逐字。 */
const ROW_KEY_LOCK_TIP = '行键列决定行的身份（删行、回填、保存都靠它匹配），不可移除';
const ROW_KEY_LOCK_GROUP_TIP = '该价格策略组里含行键列，整组移除会连带删掉行键列，因此不可移除';
/**
 * task-260909 F-2：「应用到全部列」在**一列都没选**时的禁用原因（frontend.md §1.2：禁用但可见 + 说明原因）。
 * 与保存按钮的同款空态文案（`saveDisabledReason` 的第一分支）保持一致口径。
 * 📌 D-20 同款可测性：Tooltip 走 portal，量具在按钮元素上读不到文案 ⇒ 同时挂 title + aria-label，三者逐字一致。
 */
const BULK_FIELD_TYPE_EMPTY_TIP = '尚未选择任何输出列，请从左侧拖入字段';

/**
 * task-260908 F-3（AC-17 / AC-18 / AC-19）：**料号列拖入「已选输出列」后的默认字段名**。
 *
 * 规则（需求文档 §S-6）：带 `PART_NO` 角色的列，除了本数据集的**轴列**之外，默认名一律统一成「料号」——
 * 同一个概念在不同页签叫「投入料号 / 组成料号 / 材质料号 / 来料料号」，做出来的模板列名不一致。
 *   · 报价（QUOTE）：「销售料号」保持原名，其余 → 「料号」
 *   · 核价（COST_BASIC / COST_DETAIL）：「生产料号」保持原名，其余 → 「料号」
 *
 * 📌 **两个判据的选型（fronttask F-3「方言判据」要求二选一并写明理由）**：
 *   ① 方言 → 用**当前 `dataset` state**（`DATASETS` 的 key，= 后端 CompileDialect 枚举），
 *      🚫 不从 displayName 反推。dataset 是本地权威三值枚举，不会漂移。
 *   ② 例外列 → 用 **`displayName`**（「销售料号」/「生产料号」）而**不是** `sourceColumn`
 *      （material_no / production_no）。理由：规则本身就是按**用户在面板上看到的名字**表述的
 *      （AC-18/19 的断言原文就是「面板显示 X → 字段名保持 X」），且默认名本来就取自 displayName，
 *      两者同源；若改判 sourceColumn，会出现「面板写着销售料号、字段名却被改成料号」这种
 *      与 AC 字面不符的形态。代价：displayName 来自语义图 `display_name`（DB 数据），
 *      若哪天有人把它改了，本例外会失效 —— 那时改这里一处即可。
 *
 * 🚫 只改**默认值**，不是锁定：用户仍可在字段名输入框里改回去（renameColumn 行为不变）。
 * 🚫 不影响左侧「可用字段」面板的显示名（D-8）—— 那里渲染的是 `col.displayName` 本身。
 */
function defaultFieldName(col: FieldTreeColumn, dataset: BuilderDataset): string {
  if (!(col.roles || []).includes('PART_NO')) return col.displayName;
  const axisName = dataset === 'QUOTE' ? '销售料号' : '生产料号';
  return col.displayName === axisName ? col.displayName : '料号';
}

function toSelColumn(col: FieldTreeColumn, group: FieldTreeGroup, dataset: BuilderDataset, opts?: { autoElem?: boolean }): SelColumn {
  const money = col.dataType === 'MONEY';
  const unitLike = /单价|汇率|费率|比例|系数|基准值/.test(col.displayName);
  // F-3：默认字段名走 defaultFieldName（料号列统一改名）；其余列仍是 col.displayName，行为不变。
  const initialName = defaultFieldName(col, dataset);
  return {
    _uid: nextUid(),
    sourceNodeKey: col.sourceNodeKey,
    sourceColumn: col.sourceColumn,
    fieldName: initialName,
    origFieldName: initialName,
    viewColumn: col.viewColumn || '',
    // F-4（AC-3/4/5）：按方言分派，见 defaultFieldTypeFor 的注释（含"为什么判据是 !== TEXT"）。
    fieldType: defaultFieldTypeFor(dataset, col.dataType),
    dataType: col.dataType || 'TEXT',
    isAmount: money,
    // 小计默认值仅是 UI 便利预填（用户可改），量纲类列不预勾——与体检区 WARN 文案（R7）口径一致，
    // 真正的阻断/告警判定仍由后端 /inspect 给出，这里不新增任何业务规则。
    inSubtotal: money && !unitLike,
    roles: col.roles || [],
    lookupLib: col.lookupLib ?? null,
    groupLabel: group.groupName,
    groupKind: group.groupKind,
    raw: group.groupKind === 'PRICE',
    isCore: group.groupKind === 'PRICE' ? !!col.isCore : undefined,
    elemKey: !!col.elemKey,
    autoElem: !!opts?.autoElem,
  };
}

/** GET /builder 返回的已保存列 → 本地展示态（AC-39 刷新后原样回填）。字段树尚未加载完成时先用最小信息占位。 */
function fromSavedColumn(bc: SavedBuilderColumn, dataset: BuilderDataset): SelColumn {
  const roles: FieldRole[] = [];
  if (bc.isPartNo) roles.push('PART_NO');
  if (bc.isPartName) roles.push('PART_NAME');
  if (bc.isRowKey) roles.push('ROW_KEY');
  if (bc.isSort) roles.push('SORT');
  return {
    _uid: nextUid(),
    sourceNodeKey: bc.sourceNodeKey,
    sourceColumn: bc.sourceColumn,
    fieldName: bc.fieldName,
    origFieldName: bc.fieldName,
    viewColumn: bc.viewColumn,
    // F-7（AC-15）：后端给了就用后端的（api.md §1.6 的真实 field_type）；没给才按方言 + dataType 推。
    // 🚫 不再无条件兜 'INPUT_TEXT'。本分支是"字段树还没加载完"的最小信息占位，dataType 只能先当 TEXT。
    fieldType: bc.fieldType || defaultFieldTypeFor(dataset, 'TEXT'),
    dataType: 'TEXT',
    isAmount: !!bc.isAmount,
    inSubtotal: !!bc.inSubtotal,
    roles,
    autoElem: bc.userAdded === false ? true : undefined,
  };
}

// ── SQL 高亮（纯 cosmetic 正则着色，不改变文本内容，不做任何 SQL 解析/生成）────

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
function highlightSql(sql: string): string {
  const esc = escapeHtml(sql);
  return esc
    .replace(/\b(WITH RECURSIVE|SELECT|FROM|WHERE|AND|OR|LEFT JOIN|JOIN|LIMIT|UNION ALL|UNION|NOT EXISTS|AS|DISTINCT|GROUP BY|MIN|COALESCE|ANY|IN)\b/g, '<span class="k">$1</span>')
    .replace(/'([^']*)'/g, `<span class="s">'$1'</span>`);
}

// ── Props ───────────────────────────────────────────────────────────────

export interface SqlViewBuilderTabProps {
  componentId: string;
  /** 组件当前 tabType，仅用于首次挂载/切换组件时初始化本 Tab 的草稿——后续变化不回灌，避免打断编辑中状态。 */
  initialTabType?: string;
  /** 组件已有字段名候选（含手填字段），供价格策略「元素键取自」在形态 B 场景选手填字段用（AC-23）。 */
  manualFieldOptions: { value: string; label: string }[];
  /** 保存成功后回调：通知父组件重新拉取组件详情——tabType / rowKeyFields / 三项绑定等组件级属性由保存事务原子回填（AC-2/AC-22）。 */
  onSaved?: () => void;
  /**
   * 双保存按钮问题修复（2026-08-22 紧急，主线方案；D-55① 后改为快照比对判据）：本 Tab 是否存在
   * 「未通过本 Tab 保存按钮落库」的编辑——供组件详情外层判断"用户点外层保存时要不要弹提示"。
   * 定义 = 当前配置（tabType/variantKey/columns/priceStrategy）与上一次成功 GET/PUT 时的快照是否
   * 一致，不一致即 true。
   */
  onDirtyChange?: (dirty: boolean) => void;
}

/** 供外层（ComponentManagement.tsx）通过 ref 触发本 Tab 的保存——外层保存按钮点击时，若本 Tab 有未保存编辑，直接调用它。 */
export interface SqlViewBuilderTabHandle {
  save: () => void;
}

// ── 主组件 ──────────────────────────────────────────────────────────────

const SqlViewBuilderTab = forwardRef<SqlViewBuilderTabHandle, SqlViewBuilderTabProps>(function SqlViewBuilderTab(
  { componentId, initialTabType, manualFieldOptions, onSaved, onDirtyChange }, ref,
) {
  const [initLoading, setInitLoading] = useState(true);
  /** AC-32：true = 存量手写视图——显示引导页，不进拖拽态。D-43 后由 GET /builder 的 viewState==='LEGACY_HANDWRITTEN' 推导（不再直接等同 isLegacyHandwritten，那正是本次误判的根因）。 */
  const [guideMode, setGuideMode] = useState(false);
  const [hasDriver, setHasDriver] = useState(false);

  /** F-30（AC-115）：当前数据集。它决定字段面板出哪些表，所以在页签类型**之前**选。 */
  const [dataset, setDataset] = useState<BuilderDataset>(DEFAULT_DATASET);
  /**
   * task-260904 F-1：`tabType` / `variantKey` 仍是 state，但**语义降级为内部坐标**（api.md §0：
   * 内部坐标一个字段名都不改，变的只是用户面）。它们的值只有两个来源：① 用户选中的
   * `availableSources[]` 项；② 已保存的 `builder_config`。🚫 不再有任何本地枚举、比较或改写。
   */
  const [tabType, setTabType] = useState<string>(BOOTSTRAP_TAB_TYPE);
  const [variantKey, setVariantKey] = useState<string | null>(null);
  /**
   * F-1：数据源下拉的选项来源 —— **服务端 `availableSources` 的最近一份快照**，不是本地常量。
   *
   * 为什么要单独存一份而不是每次直接读 `fieldTree.availableSources`：切数据源会先把 `fieldTree`
   * 置空再重拉（见 handleSourceChange），期间下拉会瞬间空掉、用户刚选的那项从界面上消失
   * （AC-10「切走再切回 / 刷新后选择仍在」直接观感变差）。这里留住上一份清单，重拉回来再覆盖。
   * 切数据集（方言）时必须清空 —— 清单是按方言过滤出来的，跨方言不通用。
   */
  const [sources, setSources] = useState<FieldTreeSource[]>([]);
  const [sel, setSel] = useState<SelColumn[]>([]);
  const [elemKeyOverrideField, setElemKeyOverrideField] = useState<string | null>(null);
  /**
   * task-260909 F-2（AC-2）：「整列批量设为…」下拉**当前选中的值**（还没应用）。
   * 🚫 它不进 `configPayloadFor` / dirty 快照 —— 只是个待应用的输入，点了「应用到全部列」
   *    才会写进 `sel`，那一步才算改动。
   */
  const [bulkFieldType, setBulkFieldType] = useState<BuilderFieldType>(defaultFieldTypeFor(DEFAULT_DATASET, 'TEXT'));

  const [fieldTree, setFieldTree] = useState<FieldTreeResponse | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  /**
   * 折叠态**已为哪个坐标初始化过**（`dataset|tabType|variantKey`），null = 尚未初始化。
   *
   * 🚨 2026-09-07 用户报缺陷：「每拖一个字段，左侧分组就全折回去，得重新展开再拖」。
   * 根因是原实现用 `collapsed.size === 0` 当「尚未初始化」的判据 —— 但**用户把分组全部展开时，
   * `collapsed` 也正好是空集**。两种完全不同的语义共用同一个状态值 ⇒ 拖入字段触发字段树重拉
   * （本组件的 fetch 依赖里含 `sel`），重拉就把用户展开的分组全部折回去。
   * ⇒ 用独立的 ref 记「初始化过没有」，不再从 `collapsed` 的形状反推意图。
   */
  const collapseInitedRef = useRef<string | null>(null);

  const [compileResult, setCompileResult] = useState<CompileResponse | null>(null);
  const [compileError, setCompileError] = useState<CompileErrorBody | null>(null);
  const [inspectResult, setInspectResult] = useState<InspectResponse | null>(null);
  const [compiling, setCompiling] = useState(false);

  const [sqlZoomOpen, setSqlZoomOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  /** 用户问题 2 修复：保存失败要有「非控制台」的可见反馈——常驻 Alert，不只是 3 秒自动消失的 toast。 */
  const [saveError, setSaveError] = useState<string | null>(null);

  // AC-34：过期提醒——isStale/currentCompilerVersion 直接取自 GET /builder（api.md §2.1a），不本地比较版本号。
  const [staleInfo, setStaleInfo] = useState<{ builderVersion: number; currentVersion: number } | null>(null);
  const [staleDismissed, setStaleDismissed] = useState(false);
  const [oldSqlTemplate, setOldSqlTemplate] = useState<string | null>(null);
  const [diffOpen, setDiffOpen] = useState(false);

  // AC-50：真实预览默认展开、常驻，切页签类型也不收起
  const [previewOpen] = useState(true);
  const [previewCustomerCode, setPreviewCustomerCode] = useState<string | null>(null);
  const [customerOptions, setCustomerOptions] = useState<{ value: string; label: string }[]>([]);
  const [customerSearching, setCustomerSearching] = useState(false);
  const [previewPartNo, setPreviewPartNo] = useState('');
  const [previewResult, setPreviewResult] = useState<PreviewResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);

  const pendingRehydrateRef = useRef<import('../../services/sqlViewBuilderService').SavedBuilderConfig | null>(null);
  const dragRef = useRef<
    | { type: 'new'; col: FieldTreeColumn; group: FieldTreeGroup }
    | { type: 'reorder'; uid: string }
    | { type: 'group'; headUid: string }
    | null
  >(null);
  /**
   * 拖拽插入位置指示（用户问题 1 修复）：`uid === null` = 悬浮在列表容器空白处 → 放到整个已选列表末尾；
   * 否则 `uid` 是被悬浮的「可视块」代表 uid（普通行=自身 _uid，价格策略组=组内首个成员 uid，
   * 与 renderSelected() 的分组渲染口径一致），`pos` 是相对该块插入到上方还是下方。
   */
  const [dropIndicator, setDropIndicator] = useState<{ uid: string | null; pos: 'before' | 'after' } | null>(null);
  /**
   * D-55①（2026-08-24 主线裁决）：dirty 判据从"每个改列表入口手动标记 flag"改为"整份配置快照比对"。
   * 旧方案（hasUnsavedEdits + setSelEdited 包装器）要求逐个入口记得调用带标记的 setter，实测已漏两处
   * ——① 勾闭包开关（已随 F-16 删除该开关，问题随之消失）；② 元素键切回取数列时的 early return
   * （原 :831 `if (isColDriven) { setElemKeyOverrideField(null); return; }`，改的是
   * elemKeyOverrideField 而不是 sel，根本不会经过 setSelEdited）。两处改动都会进 buildConfigPayload()
   * 却不置位 → 外层保存不拦截 → 配置静默丢失。
   * 快照比对是单一判据、覆盖全部配置项（新增配置项自动纳入，不需要逐个入口记得标记）：
   * dirty = 当前 `buildConfigPayload()` 序列化 ≠ `savedSnapshot`（上次 load/save 成功时留存的序列化快照）。
   * ⚠️ buildConfigPayload() 的 columns 映射本就不含 viewColumn（那是编译回填产物，非用户编辑，
   * 见 configPayloadFor 内 columns 映射——只取 sourceNodeKey/sourceColumn/fieldName/角色位/isAmount/
   * inSubtotal/userAdded），所以"编译回填 viewColumn 误报 dirty"这个已知坑天然被排除在快照口径外，
   * 不需要额外过滤逻辑。
   * 快照刷新点（=「与服务端一致」的三个时刻）：loadBuilderState 的 NEW 分支 / rehydrate 完成后 /
   * doSave 成功后——「取消」按钮复跑 loadBuilderState，天然复用同一套刷新点。
   */
  const [savedSnapshot, setSavedSnapshot] = useState<string | null>(null);

  // ── 初始读取 / 「取消」复用的同一份状态装配逻辑（GET /builder）─────────────
  // 抽成函数是为了「取消」按钮能原样复跑一遍——丢弃本地未保存编辑、回到上次持久化状态，
  // 而不是误调 onSaved（那会让父组件误以为发生了保存）。
  async function loadBuilderState(signal?: { cancelled: boolean }) {
    setInitLoading(true);
    setGuideMode(false);
    setHasDriver(false);
    pendingRehydrateRef.current = null;
    setSel([]);
    setSavedSnapshot(null); // D-55①：基线未知（NEW/BUILDER 分支各自补上；BUILDER 要等 rehydrate 完成 sel 才算数）
    setDataset(DEFAULT_DATASET);
    setSources([]); // F-1：清单按方言过滤而来，重新装配状态时一律作废，等新的 field-tree 回来再填
    setElemKeyOverrideField(null);
    setStaleInfo(null);
    setStaleDismissed(false);
    try {
      const res = await getBuilder(componentId);
      if (signal?.cancelled) return;
      const { builderConfig, isLegacyHandwritten, isStale, currentCompilerVersion, builderVersion, sqlTemplate, viewState: viewStateRaw } = res || ({} as any);
      // D-43（紧急修复）：三态判据，不能再用 `isLegacyHandwritten || !builderConfig`（那正是
      // 「全新组件被误判成存量手写、配置器打不开」的根因——它把 NEW 和 LEGACY_HANDWRITTEN 压成了
      // 同一个 truthy 分支）。`viewState` 缺失（后端热重载还没跟上）时按旧字段退化推导，不崩不误判。
      const viewState: 'NEW' | 'LEGACY_HANDWRITTEN' | 'BUILDER' =
        viewStateRaw ?? (isLegacyHandwritten ? 'LEGACY_HANDWRITTEN' : (builderConfig ? 'BUILDER' : 'NEW'));
      if (viewState === 'LEGACY_HANDWRITTEN') {
        setGuideMode(true);
        setHasDriver(true);
      } else if (viewState === 'NEW') {
        // 全新组件，尚无任何 SQL 视图：直接进入空白拖拽态（数据源可选、字段面板可用、已选列为空）
        //
        // task-260904 F-1：原来这里拿 `TAB_TYPES.includes(initialTabType)` 做白名单校验 —— 6 值常量
        // 删除后**不再校验**，理由是前端已无权威的合法值集合（那份权威在服务端语义图里）。
        // `initialTabType` 是存量组件的 `component.tab_type`，直接当冷启动坐标用：
        //   · 值有效 → 第一次 field-tree 就落在该组件本来的数据源上（AC-10 重开后回显不变）
        //   · 值失效（如已退役的「零件/外购件」）→ 服务端回 404 COMPILE_TABVIEW_NOT_FOUND，
        //     用户看到的是「该页签类型已停用，请改选数据源」这句**有指向的报错**，
        //     而不是被前端悄悄改写成「主件」后拿着别人的字段面板一路配下去（静默错配更难查）。
        const initT = initialTabType || BOOTSTRAP_TAB_TYPE;
        setDataset(DEFAULT_DATASET);
        setTabType(initT);
        setVariantKey(null);
        setSavedSnapshot(JSON.stringify(configPayloadFor(DEFAULT_DATASET, initT, null, [], null))); // D-55①：新组件的基线 = 空配置
      } else {
        // BUILDER：回填已有配置（savedSnapshot 留到下面的 rehydrate useEffect 里补——那时 sel 才真正建好）
        //
        // 2026-09-01：显式早退，把 `viewState==='BUILDER' ⇒ builderConfig 非空` 这个不变量补给 TS。
        // 上面 :257 的三元判断确实保证了这一点，但结果被存进 viewState 变量后类型收窄信息就丢了，
        // 于是 :270/:271/:275 三处报 TS18047。纯类型收窄，不改运行时行为（理论上不可达）。
        // ⚠️ 这三条错误此前长期存在却没人发现，因为 frontend.md §2.1 的自检命令
        //    `tsc -p tsconfig.json` 是空验证（solution-style 配置 + tsc -p 不跟进 references）。
        if (!builderConfig) {
          message.error('读取取数配置失败：服务端返回 BUILDER 态但配置为空');
          return;
        }
        setHasDriver(true);
        // F-30：存量 builder_config 可能没有 dialect 键、或带着已作废的旧值 "COSTING" ——
        // 一律归一到三值（按 QUOTE 兜底）而不是报错，老配置照常打开；
        // 用户一旦切数据集，走的就是带确认的 handleDatasetChange。
        setDataset(normalizeDataset(builderConfig.dialect));
        setTabType(builderConfig.tabType);
        setVariantKey(builderConfig.variantKey ?? null);
        setOldSqlTemplate(sqlTemplate ?? null);
        pendingRehydrateRef.current = builderConfig;
        if (isStale) {
          setStaleInfo({ builderVersion: builderVersion ?? builderConfig.builderVersion, currentVersion: currentCompilerVersion });
        }
      }
    } catch (e: any) {
      message.error('读取取数配置失败：' + (e?.message ?? '未知错误'));
    } finally {
      if (!signal?.cancelled) setInitLoading(false);
    }
  }
  useEffect(() => {
    const signal = { cancelled: false };
    void loadBuilderState(signal);
    return () => { signal.cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [componentId]);
  function handleCancel() {
    if (!dirty) return; // 没有未保存的编辑，无需二次确认（D-55①：快照比对判据）
    Modal.confirm({
      title: '放弃未保存的修改？', content: '将丢弃本次编辑，恢复为上次保存的状态。', okText: '放弃修改', okButtonProps: { danger: true }, cancelText: '继续编辑',
      onOk: () => { void loadBuilderState(); },
    });
  }

  // ── 派生：元素键列（价格策略是否启用可由 elemKeyCol 是否存在或 sel.some(s=>s.raw) 反推，此处不再单独存变量）
  const elemKeyCol = sel.find((s) => s.elemKey || s.autoElem);
  const isUsed = (sourceNodeKey: string, sourceColumn: string) => sel.some((s) => s.sourceNodeKey === sourceNodeKey && s.sourceColumn === sourceColumn);

  // F-2：数据集变了，批量下拉的**待应用值**跟着回到该方言的默认（原型 01 的基础核价页画的就是
  // 「整列批量设为 [基础数据]」，原型 03 的报价页对照 [文本输入]）。切数据集本就会清空已选列
  // （handleDatasetChange 的 Modal.confirm），所以这里不会与用户手选的值打架。
  useEffect(() => {
    setBulkFieldType(defaultFieldTypeFor(dataset, 'TEXT'));
  }, [dataset]);

  // ── 编译请求体（BuilderConfigPayload：扁平角色布尔位，见 sqlViewBuilderService.ts 头注） ──
  // D-51：不再写 switches 字段（子件闭包开关整体移除，AC-60——builder_config.switches 中不再写入
  // 内部枚举名或 includeChildParts 这一类键）。
  // 拆成纯函数 configPayloadFor + 薄封装 buildConfigPayload：D-55① 的快照比对需要在"值刚被算出、
  // 尚未等一轮 re-render 提交进 state"的时刻（loadBuilderState 的 NEW 分支、rehydrate 完成后）就地
  // 算一次等价 payload 当基线，不依赖组件 state 闭包此刻是否已提交完成。
  function configPayloadFor(ds: BuilderDataset, t: string, vk: string | null, selCols: SelColumn[], override: string | null): BuilderConfigPayload {
    const payload: BuilderConfigPayload = {
      // F-30（AC-115）：数据集随 config 走全链路（compile / preview / inspect / save 共用同一份请求体），
      // 因此也天然进了 D-55① 的 dirty 快照 —— 切数据集算未保存改动，不需要额外埋点。
      // 🚨 键名是 `dialect`（后端 BuilderConfig 的字段名），发成 `dataset` 会被静默忽略并按 QUOTE 编译。
      dialect: ds,
      tabType: t,
      variantKey: vk,
      columns: selCols.map((s) => ({
        sourceNodeKey: s.sourceNodeKey,
        sourceColumn: s.sourceColumn,
        fieldName: s.fieldName,
        isPartNo: s.roles.includes('PART_NO') || undefined,
        isPartName: s.roles.includes('PART_NAME') || undefined,
        isRowKey: s.roles.includes('ROW_KEY') || undefined,
        isSort: s.roles.includes('SORT') || undefined,
        isAmount: s.isAmount || undefined,
        inSubtotal: s.inSubtotal || undefined,
        // task-260909 F-3（AC-3/4/5/8/9，api.md §1.2）：本次起**显式发送** fieldType。
        // 🚫 `viewColumn` 仍然不发（后端按 (Sheet,列) 纯函数生成，前端只读展示）——本次只解禁这一个。
        // 📌 恒发不省略：值本来就恒为三个合法值之一，省略会退回后端默认（AC-9 的旧客户端路径），
        //    那样用户在界面上选的东西就静默不生效了。
        fieldType: s.fieldType,
        // AC-24：元素符号列若非自动带出（用户手动先拖），显式标记 userAdded，价格策略回收时后端不删它
        userAdded: (s.elemKey && !s.autoElem) || undefined,
      })),
    };
    // 形态 B（AC-23）：只有真的改绑了手填字段才带 priceStrategy；正常路径完全不传该键（省略优于传 null，贴合 Sec34 用例字面）
    if (override) {
      payload.priceStrategy = { elementCodeSource: 'MANUAL_FIELD', elementCodeField: override };
    }
    return payload;
  }
  function buildConfigPayload(): BuilderConfigPayload {
    return configPayloadFor(dataset, tabType, variantKey, sel, elemKeyOverrideField);
  }
  // D-55①：单一快照判据，天然覆盖 tabType/variantKey/columns/priceStrategy 全部配置项——
  // savedSnapshot === null（尚未确立基线，如 initLoading/guideMode 期间）时一律判定不 dirty。
  const dirty = savedSnapshot !== null && JSON.stringify(buildConfigPayload()) !== savedSnapshot;

  // ── 字段树：随 tabType / variantKey / 当前已选列变化重新拉取（AC-14 + AC-16 冲突标记）───
  useEffect(() => {
    if (initLoading || guideMode) return;
    let cancelled = false;
    setTreeLoading(true);
    const selectedConfig = sel.length ? buildConfigPayload() : undefined;
    (async () => {
      try {
        const res = await fetchFieldTree(dataset, tabType, variantKey, selectedConfig);
        if (cancelled) return;
        setFieldTree(res);
        // F-1：数据源清单只在服务端**真的给了**时才覆盖本地快照——B-1 上线前该键缺失，
        // 覆盖成空数组会让下拉在每次重拉时闪成空。给了空数组也照收（那是「本方言确实没有数据源」
        // 这一真实状态，不能当成"没给"来兜底）。
        if (res.availableSources) setSources(res.availableSources);
        // 默认折叠态照原型（原型 .grp 默认带 collapsed class）——**每个坐标只设一次**。
        // 🚫 判据不能是 `collapsed.size === 0`：用户手动展开全部分组时它同样为 0，
        //    会被误判成"尚未初始化"而把分组全折回去（2026-09-07 用户报的那个缺陷）。
        const coordKey = `${dataset}|${tabType}|${variantKey ?? ''}`;
        if (collapseInitedRef.current !== coordKey) {
          collapseInitedRef.current = coordKey;
          setCollapsed(new Set(res.groups.map((g) => g.groupName)));
        }
      } catch (e: any) {
        message.error('加载字段面板失败：' + (e?.message ?? '未知错误'));
        setFieldTree(null);
      } finally {
        if (!cancelled) setTreeLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dataset, tabType, variantKey, initLoading, guideMode,
    sel.map((s) => `${s.sourceNodeKey}.${s.sourceColumn}`).join('|')]);

  // 🗑️ 2026-09-07 移除：这里原有第二个"默认全折叠"的 useEffect，判据同样是 `collapsed.size === 0`，
  //    与上面那处犯同一个错（把"用户全展开"误当"尚未初始化"），是同一缺陷的第二个执行点。
  //    初始化职责已由上面的 `collapseInitedRef` 单点承担 —— 🚫 不要再加第三处。

  // ── 字段树就绪后，若有待恢复的 builderConfig，重建 sel（AC-39：刷新后拖拽态与保存前一致）───
  useEffect(() => {
    const pending = pendingRehydrateRef.current;
    if (!fieldTree || !pending) return;
    const allCols: Array<{ col: FieldTreeColumn; group: FieldTreeGroup }> = [];
    // F-30：这里**故意**用未过滤的 fieldTree.groups（而 addColumn 用 visibleGroups）——两者目的相反：
    // addColumn 是「往已选里加新列」，必须受 AC-116 约束；这里是「把已保存的列找回它的角色信息」，
    // 找不到就退化成 fromSavedColumn（丢角色但不丢列）。若这里也过滤，一旦 detectGroupDataset 误判
    // 就会让存量配置静默丢失角色信息，属于更糟的失败形态。dataset 已在 fetch 前按 builderConfig 设好，
    // 正常路径下服务端返回的本就只有本数据集的分组，两者等价。
    fieldTree.groups.forEach((g) => g.fields.forEach((c) => allCols.push({ col: c, group: g })));
    const rebuilt: SelColumn[] = pending.columns.map((bc) => {
      const found = allCols.find((x) => x.col.sourceNodeKey === bc.sourceNodeKey && x.col.sourceColumn === bc.sourceColumn);
      if (found) {
        const s = toSelColumn(found.col, found.group, normalizeDataset(pending.dialect), { autoElem: found.col.elemKey && bc.userAdded === false });
        return { ...s, fieldName: bc.fieldName, origFieldName: bc.fieldName, viewColumn: bc.viewColumn, isAmount: !!bc.isAmount, inSubtotal: !!bc.inSubtotal, fieldType: bc.fieldType || defaultFieldTypeFor(normalizeDataset(pending.dialect), s.dataType) };
      }
      // 字段树里找不到对应列（罕见：图或存量数据漂移）——仍原样展示，避免保存态丢失，只是缺角色信息。
      return fromSavedColumn(bc, normalizeDataset(pending.dialect));
    });
    setSel(rebuilt);
    // F-17（D-61 / AC-23 形态 B）：elemKeyOverrideField 随 builderConfig.priceStrategy 回填——
    // 没有该配置（正常路径 / 未手填覆盖）时保持 null。必须先算出 override 局部量，
    // 再用它（而不是闭包里恒为 null 的 state 变量 elemKeyOverrideField）去建快照——
    // setState 是异步的，此刻读 state 仍是回填前的旧值，直接用会把"回填后的真值"漏出快照之外，
    // 导致下一次 dirty 比对（用户还没碰过）就与刚回填的 elemKeyOverrideField 产生分歧而误报未保存改动。
    const override = pending.priceStrategy?.elementCodeField ?? null;
    setElemKeyOverrideField(override);
    // D-55①：这一刻 sel（+ 上面回填的 override）才真正等于"服务端已保存的样子"——用 pending 自带的
    // tabType/variantKey（不依赖 tabType/variantKey state 此刻是否已提交完成的时序假设）。
    setSavedSnapshot(JSON.stringify(configPayloadFor(normalizeDataset(pending.dialect), pending.tabType, pending.variantKey ?? null, rebuilt, override)));
    pendingRehydrateRef.current = null;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldTree]);

  // ── 增删列 ──────────────────────────────────────────────────────────────
  // 用户问题 1 修复：返回新增的 SelColumn[]（含它们的 _uid），供拖拽落点逻辑把「刚追加到末尾的新行」
  // 再挪到用户实际悬浮的插入位置——价格策略列可能一次带出 2 行（自动元素列 + 元素单价列），
  // 之前的实现只挪「最后一行」，第二行会被落下（AP-54 同类下标错位）。
  function addColumn(col: FieldTreeColumn, group: FieldTreeGroup): SelColumn[] {
    if (isUsed(col.sourceNodeKey, col.sourceColumn)) return [];
    const additions: SelColumn[] = [];
    if (group.groupKind === 'PRICE' && !elemKeyCol) {
      // AC-20：拖价格策略列时若无元素列，自动带出元素符号列（取价函数 JOIN 左键，缺它接不上）
      let ek: { col: FieldTreeColumn; group: FieldTreeGroup } | null = null;
      // F-30（AC-116）：自动带出的元素列也必须来自**当前数据集可见的分组**。
      // 用未过滤的 fieldTree.groups 会在服务端漏过滤时，把另一套数据集的元素列悄悄塞进"已选输出列"——
      // 那列在左侧面板里根本看不见（AC-116 要求不出现），用户无从解释它是哪来的，也删不掉整组的来源。
      for (const g of visibleGroups) {
        for (const c of g.fields) if (c.elemKey) { ek = { col: c, group: g }; break; }
        if (ek) break;
      }
      if (ek && !isUsed(ek.col.sourceNodeKey, ek.col.sourceColumn)) additions.push(toSelColumn(ek.col, ek.group, dataset, { autoElem: true }));
    }
    additions.push(toSelColumn(col, group, dataset));
    setSel((prev) => [...prev, ...additions]);
    return additions;
  }

  function removeColumn(uid: string) {
    const target = sel.find((s) => s._uid === uid);
    if (!target) return;
    // task-260908 F-2（AC-15）：行键列不可移除 —— 它决定行的身份（删行 / 回填 / 保存都靠它匹配）。
    // 渲染层已把 ✕ 置成禁用态（见 renderSelRowBody / 价格策略组头），这里是第二道防线：
    // 禁用态只挡住鼠标，挡不住别的调用路径。
    if (isRowKeyCol(target)) return;
    const killsGroup = !!(target.isCore || target.elemKey || target.autoElem);
    // 价格策略原子组是**整组移除**（下面 onOk 里 filter 掉所有 raw/elemKey/autoElem 成员）——
    // 组里若混进了行键列，从这个入口删就绕过了单行禁用。整组一并挡住（原型 02 状态 5）。
    if (killsGroup && sel.some((s) => (s.raw || s.elemKey || s.autoElem) && isRowKeyCol(s))) return;
    const extra = killsGroup ? '\n\n⚠ 这是价格策略原子组的核心列，将同时移除整组（元素列 + 元素单价 + 货币）。' : '';
    Modal.confirm({
      title: `移除「${target.fieldName}」？`,
      content: `视图列 ${target.viewColumn || '（尚未编译）'}。将同时删除：视图输出列 + 组件字段 + 绑定路径（三件套同步）。${extra}`,
      okText: '移除', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: () => {
        setSel((prev) => (killsGroup ? prev.filter((s) => !s.raw && !s.elemKey && !s.autoElem) : prev.filter((s) => s._uid !== uid)));
      },
    });
  }

  function renameColumn(uid: string, value: string) {
    const v = value.trim();
    if (!v) return;
    setSel((prev) => prev.map((s) => (s._uid === uid ? { ...s, fieldName: v } : s)));
  }
  function toggleAmount(uid: string, checked: boolean) {
    setSel((prev) => prev.map((s) => (s._uid === uid ? { ...s, isAmount: checked } : s)));
  }
  function toggleSubtotal(uid: string, checked: boolean) {
    setSel((prev) => prev.map((s) => (s._uid === uid ? { ...s, inSubtotal: checked } : s)));
  }
  /** task-260909 F-1（AC-1/AC-12）：改单列的字段类型。按 `_uid` 定位，不用下标（AP-54 教训）。 */
  function setFieldType(uid: string, value: BuilderFieldType) {
    setSel((prev) => prev.map((s) => (s._uid === uid ? { ...s, fieldType: value } : s)));
  }
  /**
   * task-260909 F-2（AC-2）：整列批量设置 —— 应用到当前**全部**列（含价格策略原子组的成员），
   * 逐列选择器随之同步变更。两步交互（先选值、再点"应用到全部列"），避免下拉一动就改掉全部。
   */
  function applyFieldTypeToAll(value: BuilderFieldType) {
    setSel((prev) => prev.map((s) => ({ ...s, fieldType: value })));
  }

  // ── 拖拽重排（原生 HTML5 DnD，与项目内既有页面同款手法）───────────────────
  // 用户问题 1 修复（2026-08-22 紧急）：原实现的 drop 目标只有「已有行自身」，行与行之间/末尾的空白
  // 完全没有 drop handler——拖到第二行往后必然落空。改成：① 列表容器本身也是 drop 区（覆盖空白），
  // ② 每个可视块（普通行 / 价格策略组）按鼠标 Y 相对该块的位置分「插到上方」还是「插到下方」并画指示线，
  // ③ 所有移动（新增字段落位 / 单行重排 / 整组重排）统一走 moveItemsToTarget，按 _uid 集合定位、
  //    不依赖裸下标（AP-54 教训——价格策略组是「一块占多行」的结构，裸下标最容易算错）。
  function handleFieldDragStart(e: React.DragEvent, col: FieldTreeColumn, group: FieldTreeGroup) {
    dragRef.current = { type: 'new', col, group };
    e.dataTransfer.effectAllowed = 'copy';
  }
  function handleRowDragStart(e: React.DragEvent, uid: string) {
    dragRef.current = { type: 'reorder', uid };
  }
  function handleGroupDragStart(e: React.DragEvent, headUid: string) {
    dragRef.current = { type: 'group', headUid };
  }
  /** 鼠标 Y 落在该块上半/下半 → 插到它上方还是下方；dragover 和 drop 共用同一份计算，drop 不依赖 state 时序。 */
  function computeDropPos(e: React.DragEvent): 'before' | 'after' {
    const rect = e.currentTarget.getBoundingClientRect();
    return e.clientY - rect.top < rect.height / 2 ? 'before' : 'after';
  }
  /** 悬浮在某个可视块（行/组）上：更新指示线状态（纯展示用，落点判定见 handleBlockDrop）。 */
  function handleBlockDragOver(e: React.DragEvent, blockUid: string) {
    e.preventDefault();
    e.stopPropagation(); // 不让事件再冒泡到列表容器，避免容器的「末尾」指示把这里的精确指示线覆盖掉
    const pos = computeDropPos(e);
    setDropIndicator((cur) => (cur && cur.uid === blockUid && cur.pos === pos ? cur : { uid: blockUid, pos }));
  }
  /** 落到某个可视块（行/组）上：当场按事件自身的 clientY 重算 before/after，不读 dropIndicator state
   *  （dragover 是连续事件，state 更新与 drop 触发之间理论上仍有一帧竞态窗口——直接算，零依赖更稳）。 */
  function handleBlockDrop(e: React.DragEvent, blockUid: string) {
    handleDrop(e, { uid: blockUid, pos: computeDropPos(e) });
  }
  /** 悬浮在列表容器空白处（现有行下方的空档）：等价于「插到列表末尾」。 */
  function handleListDragOver(e: React.DragEvent) {
    e.preventDefault();
    setDropIndicator((cur) => (cur && cur.uid === null ? cur : { uid: null, pos: 'after' }));
  }
  function handleListDragLeave(e: React.DragEvent) {
    // relatedTarget 仍在容器内（比如移到某一行上）不算真正离开——那会被该行自己的 dragover 接管，
    // 这里清空只处理「彻底移出整个已选列区域」的情况，避免指示线在行与行之间来回闪烁。
    if (!e.currentTarget.contains(e.relatedTarget as Node)) setDropIndicator(null);
  }
  /**
   * 把 `movingUids` 这一批行（可能是新增列，也可能是被拖动的既有行/整组）挪到 `target` 指定的插入位置。
   * AP-54 铁律：全程按 `_uid` 定位，不用裸下标；目标若落在价格策略组内，组内其它成员的边界一并纳入
   * 计算（组必须整体挪动，不能被插入操作拆散）。找不到落点时兜底放到末尾——不丢数据。
   */
  function moveItemsToTarget(movingUids: string[], target: { uid: string | null; pos: 'before' | 'after' } | null) {
    if (!movingUids.length) return;
    setSel((prev) => {
      const movingSet = new Set(movingUids);
      const moving = prev.filter((x) => movingSet.has(x._uid));
      if (!moving.length) return prev;
      // 目标就是自己（重排/整组重排时，鼠标悬浮回自己身上）：真正的原地不动，不能默认落进「末尾」分支。
      if (target && target.uid !== null && movingSet.has(target.uid)) return prev;
      const rest = prev.filter((x) => !movingSet.has(x._uid));
      let insertIdx = rest.length; // 默认插到末尾：target 为空 / 目标在 rest 里找不到时的兜底，不丢数据
      if (target && target.uid !== null) {
        const pgMembersInRest = rest.filter((s) => s.raw || s.autoElem);
        const inGroup = pgMembersInRest.some((m) => m._uid === target.uid);
        const targetMemberUids = inGroup ? new Set(pgMembersInRest.map((m) => m._uid)) : new Set([target.uid]);
        const idxs: number[] = [];
        rest.forEach((x, i) => { if (targetMemberUids.has(x._uid)) idxs.push(i); });
        if (idxs.length) insertIdx = target.pos === 'before' ? Math.min(...idxs) : Math.max(...idxs) + 1;
      }
      return [...rest.slice(0, insertIdx), ...moving, ...rest.slice(insertIdx)];
    });
  }
  function handleDrop(e: React.DragEvent, target: { uid: string | null; pos: 'before' | 'after' } | null) {
    e.preventDefault();
    e.stopPropagation();
    setDropIndicator(null);
    const drag = dragRef.current;
    dragRef.current = null;
    if (!drag) return;
    if (drag.type === 'new') {
      if (isUsed(drag.col.sourceNodeKey, drag.col.sourceColumn)) return;
      const additions = addColumn(drag.col, drag.group); // 先原样追加到末尾（可能 1~2 行）
      if (additions.length) moveItemsToTarget(additions.map((a) => a._uid), target); // 再整体挪到落点
    } else if (drag.type === 'reorder') {
      moveItemsToTarget([drag.uid], target);
    } else if (drag.type === 'group') {
      const groupUids = sel.filter((s) => s.raw || s.autoElem).map((s) => s._uid);
      moveItemsToTarget(groupUids, target);
    }
  }

  // ── tabType / variant 切换：换主源 Sheet → 已选列全部失效 → 二次确认后清空（F-1）───
  // D-51：不再有「switch 切换」这回事——子件闭包开关整体移除（AC-60），toggleSwitch 已删除。
  /**
   * F-30（AC-115 ④）：切数据集的破坏性**比切页签类型更强** —— 跨数据集的表与列完全不通
   * （报价轴是 material_no、核价两套轴是 production_no，物理表都不是同一批），已选列一个都保不住。
   * 所以确认文案照原型逐字写清「切到哪」「几个列会没」，而不是复用页签类型那句笼统的「继续？」。
   */
  function handleDatasetChange(v: BuilderDataset) {
    if (v === dataset) return;
    const doSwitch = () => {
      setDataset(v);
      // task-260904 F-1：数据源回到冷启动种子坐标——新数据集的 `availableSources` 要等下一次
      // field-tree 才知道，此刻手上没有任何合法选项，只能用种子把请求发出去（见 BOOTSTRAP_TAB_TYPE）。
      setTabType(BOOTSTRAP_TAB_TYPE);
      setVariantKey(null);
      setSources([]); // 清单按方言过滤而来，跨数据集不通用
      setSel([]);
      setElemKeyOverrideField(null);
      setFieldTree(null);
      setCollapsed(new Set());
      collapseInitedRef.current = null; // 换坐标 ⇒ 新分组要重新按默认态折叠一次
    };
    if (sel.length) {
      Modal.confirm({
        title: '切换数据集会清空已选输出列',
        content: (
          <div>
            <div>
              从 <b>{datasetLabel(dataset)}</b> 切到 <b>{datasetLabel(v)}</b>，当前已选的 <b>{sel.length}</b> 个输出列将被清空。
            </div>
            <div style={{ marginTop: 10, background: '#fff8c5', border: '1px solid #eed888', borderRadius: 6, padding: '6px 10px', fontSize: 12 }}>
              {/* task-260904 F-1：原文写「不像切页签类型还可能留下同名列」——「页签类型」这个
                  用户面概念已被数据源取代，文案随之改口，否则界面上会出现一个再也找不到的名词。 */}
              跨数据集的表与列<b>完全不通</b>——不像在同一数据集里换数据源还可能留下同名列，这里已选的列一个都保不住。
            </div>
          </div>
        ),
        okText: '确认切换', okButtonProps: { danger: true }, cancelText: '取消', onOk: doSwitch,
      });
    } else doSwitch();
  }
  // ── task-260904 F-1/F-2/F-3：数据源下拉（原「页签类型」+「数据来源」两个下拉合并而来）────
  //
  // 🚫 原 handleTabTypeChange / handleVariantChange 已删除：用户面上不再存在「页签类型」和
  //    「数据来源」这两个独立选择——费用类由 2 次选择降为 1 次，其余由「选抽象类型」变为「选表名」
  //    （需求文档 §2.1 S-2）。两者的清空语义合并进下面这一个入口。

  /** 两段坐标的相等判据：后端可能把「无变体」写成 `null` 或 `""`，一律按空串比。 */
  const vkEq = (a?: string | null, b?: string | null) => (a ?? '') === (b ?? '');

  /**
   * 本数据集可选的数据源。第二道防线同 `visibleGroups`：服务端已按 `dialect` 过滤（api.md §1.3），
   * 这里再挡一次「服务端漏过滤 / 种子把别的方言挂错」的情况。`dialect` 缺省的项一律保留（不藏）。
   */
  const visibleSources = useMemo(
    () => sources.filter((s) => s.dialect == null || s.dialect === dataset),
    [sources, dataset],
  );
  /**
   * 当前坐标对应的数据源项。三段全比 —— `dialect` 缺省视为匹配（B-1 上线前的宽松档）。
   * 找不到 = 服务端还没给清单、或当前坐标已从清单里退役（如「零件/外购件」）。
   */
  const selectedSource = useMemo(
    () => visibleSources.find(
      (s) => s.tabType === tabType && vkEq(s.variantKey, variantKey) && (s.dialect == null || s.dialect === dataset),
    ) ?? null,
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [visibleSources, tabType, variantKey, dataset],
  );
  /**
   * F-2/F-3：当前数据源的语义。
   * `undefined` = **还不知道**（服务端未给清单 / 当前坐标不在清单里）——与 `null`（已知是普通数据源）
   * 严格区分：不知道时一切按「保持现状」处理，🚫 不许拿"不知道"去关掉任何既有能力
   * （utils/tabSemantic.ts 头注同款三态纪律）。
   */
  const semantic: 'TREE' | 'MATERIAL_ELEMENT' | null | undefined =
    selectedSource ? (selectedSource.semantic ?? null) : undefined;

  /**
   * F-1：选中一项数据源 —— **`(tabType, variantKey, dialect)` 三段坐标原样落进 state**，
   * 随后由 configPayloadFor / fetchFieldTree 原样回传。
   * 🚨 三段缺一不可：`semantic_tab_view` 的唯一约束是 `(tab_type, variant_key, dialect)`，
   *    少传 `dialect` 会在服务端 `findFirst()` 处静默命中另外两个方言里的同名声明（api.md §0）。
   */
  function handleSourceChange(sourceKey: string) {
    const o = visibleSources.find((s) => s.sourceKey === sourceKey);
    if (!o) return;
    if (o.tabType === tabType && vkEq(o.variantKey, variantKey)) return;
    const doSwitch = () => {
      setTabType(o.tabType);
      setVariantKey(o.variantKey ?? null);
      // 第三段：正常路径下与当前 dataset 相同（选项已按 dataset 过滤），显式跟随是为了让
      // 「三段原样回传」在代码里成立，而不是靠"它俩碰巧一致"这个隐含假设。
      if (o.dialect) setDataset(o.dialect);
      setSel([]);
      setElemKeyOverrideField(null);
      setFieldTree(null);
      setCollapsed(new Set());
      collapseInitedRef.current = null; // 换坐标 ⇒ 新分组要重新按默认态折叠一次
    };
    if (sel.length) {
      // 原型 `取数配置Tab.html`：confirm('切换数据源会清空已选输出列。继续？')
      Modal.confirm({ title: '切换数据源会清空已选输出列', content: '继续？', okText: '继续切换', cancelText: '取消', onOk: doSwitch });
    } else doSwitch();
  }

  // ── debounce 300ms：拖拽变化 → 重新编译 + 体检（api.md §3：不得每帧发请求）──────
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (initLoading || guideMode) return;
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      if (!sel.length) { setCompileResult(null); setCompileError(null); setInspectResult(null); return; }
      const payload = buildConfigPayload();
      setCompiling(true);
      try {
        const res = await compileBuilder(componentId, payload);
        setCompileResult(res);
        setCompileError(null);
        // declaredColumns 与请求 columns[] 同序（后端按输入顺序 SELECT），按位置回填 viewColumn 供已选列展示（AC-11 展示用途）。
        if (res.declaredColumns && res.declaredColumns.length === sel.length) {
          setSel((prev) => prev.map((s, i) => (s.viewColumn ? s : { ...s, viewColumn: res.declaredColumns[i] })));
        }
      } catch (e: any) {
        setCompileResult(null);
        setCompileError({ code: e?.payload?.code ?? 'UNKNOWN', message: e?.message || e?.payload?.message || '编译失败', paths: e?.payload?.paths, suggestion: e?.payload?.suggestion });
      } finally {
        setCompiling(false);
      }
      try {
        const insRes = await inspectBuilder(componentId, payload);
        setInspectResult(insRes);
      } catch (e: any) {
        // 2026-09-01 修真 bug（不只是类型错误）：这里原本塞的是 `{ checks: [...] }`，
        // 而 InspectResponse 的字段是 `{ blocked, items }`（sqlViewBuilderService.ts:292）。
        // 渲染层读 `items` ⇒ **体检请求失败时，这条「体检请求失败」的警告根本显示不出来**。
        // 来历：上一个提交 faa01cd7「D-49 /inspect 响应体契约对齐」把 checks 改成了 items，漏了这个 catch 分支。
        // blocked=false 的取值依据：请求失败 ≠ 配置有错，不应据此拦住保存（真有 ERR 项时后端会拒）。
        setInspectResult({ blocked: false, items: [{ level: 'WARN', message: '体检请求失败：' + (e?.message ?? '未知错误') }] });
      }
    }, 300);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [componentId, tabType, variantKey, elemKeyOverrideField, initLoading, guideMode,
    sel.map((s) => `${s.sourceNodeKey}.${s.sourceColumn}:${s.fieldName}:${s.isAmount ? 1 : 0}:${s.inSubtotal ? 1 : 0}`).join('|')]);

  // ── 预览 ──────────────────────────────────────────────────────────────
  async function runPreview() {
    if (!sel.length) { setPreviewResult(null); return; }
    if (!previewCustomerCode) { message.warning('请先选择预览客户'); return; }
    setPreviewLoading(true);
    try {
      const res = await previewBuilder(componentId, { ...buildConfigPayload(), customerCode: previewCustomerCode, partNo: previewPartNo.trim() || undefined });
      setPreviewResult(res);
    } catch (e: any) {
      message.error('预览失败：' + (e?.message ?? '未知错误'));
      setPreviewResult(null);
    } finally {
      setPreviewLoading(false);
    }
  }
  useEffect(() => {
    // 已选列变化后预览结果视为陈旧，清空等待用户重新执行（避免展示过期行）
    setPreviewResult(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sel.length]);

  async function searchCustomers(keyword: string) {
    setCustomerSearching(true);
    try {
      const res: any = await customerService.list({ page: 0, size: 20, keyword: keyword || '' });
      const content = res?.data?.content ?? res?.data ?? [];
      setCustomerOptions((content as any[]).map((c) => ({ value: c.code, label: `${c.name}（${c.code}）` })));
    } catch {
      // 客户搜索失败不阻断配置流程，静默即可（预览本身会在点「重新执行」时报错）
    } finally {
      setCustomerSearching(false);
    }
  }
  useEffect(() => { searchCustomers(''); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);

  // ── 保存 ──────────────────────────────────────────────────────────────
  // D-42：PUT / 请求体是「config 本身 + 平级 confirmedImpact」，不是 { builderConfig, confirmedImpact }
  // 嵌套一层（后端 SaveRequest extends BuilderConfig 是继承不是持有；包一层会让 tabType/variantKey
  // 在后端读成 null，报出与真因无关的错）。
  async function doSave(confirmedImpact?: boolean) {
    setSaving(true);
    setSaveError(null); // 用户问题 2：每次重新保存先清掉上一轮的错误横幅，不留过期提示
    // D-55①：先固定住"本次实际发出去的 payload"，成功后原样拿它当新快照——不要等 await 回来后再重新调
    // buildConfigPayload()，那样读到的是网络请求这段时间里可能已被用户改动过的、更新的 state，
    // 会让快照与"服务端真正落库的内容"不一致。
    const payload = buildConfigPayload();
    try {
      const res = await saveBuilder(componentId, { ...payload, confirmedImpact });
      message.success(`保存成功，共 ${sel.length} 列${res.affectedTemplateCount != null ? `，影响 ${res.affectedTemplateCount} 个模板` : ''}`);
      setSel((prev) => prev.map((s) => ({ ...s, origFieldName: s.fieldName })));
      setSavedSnapshot(JSON.stringify(payload)); // D-55①：保存成功 = 新基线
      setStaleDismissed(true);
      setStaleInfo(null);
      onSaved?.();
    } catch (e: any) {
      if (e?.httpStatus === 409 && (e?.payload?.code === 'IMPACT_CONFIRM_REQUIRED')) {
        const templates: Array<{ id: string; name: string }> = e.payload?.detail?.affectedTemplates || [];
        Modal.confirm({
          title: '删除的列被以下模板引用', width: 520,
          content: (
            <div>
              <p>{e.message || '继续保存将同步从这些模板的 snapshot 中移除该列。'}</p>
              <ul style={{ maxHeight: 240, overflow: 'auto', paddingLeft: 18 }}>
                {templates.map((t) => <li key={t.id}>{t.name}</li>)}
                {!templates.length && <li>（后端未返回具体模板名单）</li>}
              </ul>
            </div>
          ),
          okText: '确认保存', cancelText: '取消',
          onOk: () => doSave(true),
        });
      } else {
        // 用户问题 2 修复：400 INSPECT_BLOCKED / 其它保存失败——除了 toast，再加一条常驻 Alert
        // （toast 3 秒自动消失，用户离开鼠标/切走视线就可能真的没看见；这是本次 COMP-0311 配置
        // 完全没落库、但不确定用户是没点保存还是点了没看到反馈的直接应对）。
        const msg = e?.message || '未知错误';
        message.error('保存失败：' + msg);
        setSaveError(msg);
      }
    } finally {
      setSaving(false);
    }
  }

  // 双保存按钮问题修复（2026-08-22 紧急，主线方案 2）：组件详情外层也有一个「保存」按钮（走
  // PUT /components/{id}，只存组件基本信息，完全不覆盖本 Tab 的取数配置）。真实事故：用户点了外层
  // 保存、看到"保存成功"提示，以为取数配置也存了，实际上取数配置一个字节都没落库，刷新就没了。
  // ① dirty 是精确判据（D-55①：快照比对，见 savedSnapshot 声明处的完整说明）——不用 sel.length>0，
  //   否则只要配过列就永远 true，哪怕早已保存过，外层每次保存都会被拦，那是另一种"狼来了"式的伤害。
  // ② 通过 onDirtyChange 把这个判据上抛给 ComponentManagement.tsx，供它决定外层保存按钮点击时要不要拦截。
  useEffect(() => {
    onDirtyChange?.(dirty);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dirty]);
  // ③ 通过 ref 把本 Tab 的保存动作暴露给外层——外层拦截后弹出的提示可以直接调这个，不用重新实现一遍保存逻辑。
  useImperativeHandle(ref, () => ({ save: () => { void doSave(false); } }));

  // ── 转为手写 SQL（AC-33：不可逆）─────────────────────────────────────────
  function handleDetach() {
    Modal.confirm({
      title: '转为手写 SQL',
      content: '转为手写 SQL 后将无法恢复为可视化配置（不可逆）。SQL 视图 Tab 会变为可编辑的手写编辑器。确定继续？',
      okText: '确认转为手写', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: async () => {
        try {
          await detachBuilder(componentId);
          message.success('已转为手写 SQL');
          setGuideMode(true);
          setSel([]);
          setSavedSnapshot(null); // 转手写后本 Tab 不再有可编辑的取数配置态，dirty 判据回到"基线未知"
          onSaved?.();
        } catch (e: any) {
          message.error('转为手写失败：' + (e?.message ?? '未知错误'));
        }
      },
    });
  }
  const moreMenuItems: MenuProps['items'] = [
    { key: 'detach', label: '转为手写 SQL', danger: true, disabled: !hasDriver || guideMode },
  ];
  const handleMoreMenuClick: MenuProps['onClick'] = ({ key }) => { if (key === 'detach') handleDetach(); };

  // ── 渲染：字段面板 ────────────────────────────────────────────────────
  function toggleGroupCollapse(key: string) {
    setCollapsed((prev) => { const n = new Set(prev); if (n.has(key)) n.delete(key); else n.add(key); return n; });
  }
  function renderFld(col: FieldTreeColumn, group: FieldTreeGroup) {
    // F-16（AC-60，D-51）：原判据 `col.closureOnly && !switches.includeChildParts` 依赖已删除的
    // switches state，不能照搬。核实后未替换为新判据——见本文件改动的回报说明：
    // ① FieldTreeBuilder.Field（cpq-backend）当前没有 closureOnly 属性，后端从未把它置为 true，
    //    这个门在改动前就是死代码（col.closureOnly 恒 falsy，不影响任何已渲染的列）；
    // ② D-50 之后子件带出完全由页签类型自动决定，不再存在"某些列只在用户勾了某开关时才出现"的场景，
    //    该字段树画像已经不成立。因此直接不再做这层过滤，而不是发明一个新的门槛条件。
    if ((col.onlyVariant ?? null) && col.onlyVariant !== variantKey) return null;
    const used = isUsed(col.sourceNodeKey, col.sourceColumn);
    // AC-16：置灰判据 = 该列所属分组的 conflict 标记，来自 GET /field-tree?...&selectedConfig=（服务端算好，前端只读）。
    const blocked = !used && !!group.conflict;
    const draggableAllowed = !used && !blocked;
    const reason = group.conflict ? `与已选内容冲突，两者只能选一类。要改用本组，请先移除冲突的那组列` : '';
    return (
      <div
        key={colKey(col.sourceNodeKey, col.sourceColumn)}
        className={`svb-fld${used ? ' used' : ''}${blocked ? ' blocked' : ''}`}
        draggable={draggableAllowed}
        onDragStart={draggableAllowed ? (e) => handleFieldDragStart(e, col, group) : undefined}
        onDragEnd={() => setDropIndicator(null)}
        onDoubleClick={draggableAllowed ? () => addColumn(col, group) : undefined}
        title={used ? '已在输出列中' : blocked ? reason : '拖到右侧，或双击添加'}
      >
        <span className="svb-drag">{blocked ? '🚫' : '⋮⋮'}</span>
        <span>{col.displayName}</span>
        {(col.roles || []).map((r) => <span key={r} className="svb-rmark">{ROLE_LABEL[r]}</span>)}
        {col.lookupLib && <span className="svb-lookup-tag">{col.lookupLib}</span>}
        {col.dataType && <span className="svb-t">{DATA_TYPE_LABEL[col.dataType]}</span>}
      </div>
    );
  }
  function renderGroup(g: FieldTreeGroup) {
    const isCollapsed = collapsed.has(g.groupName);
    const dimTxt = g.dims && g.dims.length ? `按 ${g.dims.join('+')} 展开` : '';
    return (
      <div key={g.groupName} className={`svb-grp${isCollapsed ? ' collapsed' : ''}`}>
        <div className="svb-grp-h" onClick={() => toggleGroupCollapse(g.groupName)}>
          <span className="svb-caret">▾</span> {g.groupName}
          {dimTxt && <span className="svb-dim-tag">{dimTxt}</span>}
          {g.groupKind === 'PRICE' && elemKeyCol && <span className="svb-dim-tag">元素键：{elemKeyCol.fieldName}</span>}
        </div>
        <div className="svb-grp-b">
          {g.note && <div className="svb-sheet-note">{g.note}</div>}
          {g.fields.map((c) => renderFld(c, g))}
        </div>
      </div>
    );
  }
  /**
   * F-30：本数据集里**明确不进语义图**的表（配置器拖不到）单列一块警示，照原型 `deadBlock()`。
   * 不写它的话，配置人员会在字段面板里反复找 `ds_quote_customer_part` 这类表却得不到任何解释。
   */
  function renderExcludedTables() {
    const dead = EXCLUDED_TABLES[dataset];
    if (!dead || !dead.length) return null;
    return (
      <div className="svb-dead-block">
        <b>本数据集有 {dead.length} 张表不进语义图</b>（配置器里拖不到）：
        {dead.map((d, i) => (
          <span key={d.table}>
            {i > 0 && '　·　'}
            <code>{d.table}</code>（{d.label}，{d.reason}）
          </span>
        ))}
      </div>
    );
  }

  /**
   * F-30（AC-116）：字段面板只出**当前数据集**的表，另两套一张都不出现（不是置灰，是不出现）。
   * 第一道防线在服务端（`GET /field-tree?dataset=`）；这里是第二道，防「服务端漏过滤 / 还没改完」时
   * 界面上真的把别套数据集的表画出来。`detectGroupDataset` 推不出归属的分组一律保留，见其函数注释。
   */
  const visibleGroups = useMemo(() => {
    if (!fieldTree) return [] as FieldTreeGroup[];
    return fieldTree.groups.filter((g) => {
      const d = detectGroupDataset(g);
      if (!(d === null || d === dataset)) return false;
      // task-260904 F-2（AC-2③ / AC-3③）：价格策略原子组**只在 semantic==='MATERIAL_ELEMENT' 时出现**。
      // 🚫 判据是服务端给的 `semantic`，不是 label / sourceKey / groupName 的字面。
      // ⚠️ `semantic === undefined`（清单还没到手 / 当前坐标不在清单里）时**不隐藏** —— 那是
      //    「还不知道」，不是「已知不是材质元素」。拿"不知道"去藏组会在 B-1 上线前把存量
      //    材质元素组件的价格策略整块弄没（比多显示一组难查得多）。真正的权威在服务端：
      //    PRICE 边只挂在材质元素锚点上，普通数据源本就不会带 PRICE 组，这里是第二道防线。
      if (g.groupKind === 'PRICE' && semantic !== undefined && semantic !== 'MATERIAL_ELEMENT') return false;
      return true;
    });
  }, [fieldTree, dataset, semantic]);
  const totalFieldCount = useMemo(
    () => visibleGroups.reduce((sum, g) => sum + g.fields.length, 0),
    [visibleGroups],
  );

  /**
   * task-260908 F-2（AC-14 / AC-16）：**选中 / 切换数据源后，自动带出该源全部行键列**。
   *
   * 为什么落在这里而不是 `handleSourceChange` 里：切源时 `fieldTree` 被置空、要等下一轮
   * `GET /field-tree` 回来才知道新源有哪些列 —— 在 `handleSourceChange` 里填，读到的是**上一个源**
   * 的字段树（或空）。所以判据是「字段树到手 + 已选列为空」，与「用户是怎么走到这一步的」无关：
   * 切数据源、切数据集、打开一个还没配过列的新组件，三条路径共用同一份逻辑。
   *
   * 🔑 **替换而非追加**（AC-16）：`handleSourceChange` / `handleDatasetChange` 已经 `setSel([])`，
   *    本效果只在 `sel.length === 0` 时补，所以往返切源恒等于「该源的行键列集合」，不会累积成 4 个。
   *    填完 sel 非空 ⇒ 后续字段树重拉（本文件的 fetch 依赖里含 sel）不会再次触发。
   *
   * 🚫 `pendingRehydrateRef` 非空时必须让路：那是「已保存配置正在回填」，此刻 sel 也是空的，
   *    抢先填会和 rehydrate 的 `setSel(rebuilt)` 打架（后者整份覆盖，行键列反而丢了角色信息）。
   * 🚫 行键判据只认 `roles.includes('ROW_KEY')`（服务端字段树声明的角色，ROLE_LABEL 同源），
   *    不按字段名/下标猜。
   * 📌 用 `visibleGroups` 而不是 `fieldTree.groups`：与 addColumn 同口径（AC-116），
   *    不把别套数据集的列塞进已选。
   */
  useEffect(() => {
    if (initLoading || guideMode) return;
    if (!fieldTree) return;
    if (pendingRehydrateRef.current) return;
    if (sel.length) return;
    const rowKeys: SelColumn[] = [];
    for (const g of visibleGroups) {
      for (const c of g.fields) {
        if ((c.roles || []).includes('ROW_KEY')) rowKeys.push(toSelColumn(c, g, dataset));
      }
    }
    if (!rowKeys.length) return;
    // 🚨 必须用**函数式更新**，不能 `setSel(rowKeys)`：本效果与上面的 rehydrate 效果都挂在
    //    `fieldTree` 上，同一轮 commit 里按声明顺序执行，而 rehydrate 声明在前 ——
    //    它 `setSel(rebuilt)` 之后，本效果闭包里的 `sel` 仍是**这一轮渲染的旧值（空数组）**，
    //    `pendingRehydrateRef.current` 也已被它同步清成 null ⇒ 上面两个 guard 全部失效。
    //    直接覆盖会把刚回填好的存量配置整份冲掉（AC-39 回归）。函数式更新拿到的 `prev` 是
    //    队列里前一个更新的结果（= rebuilt），非空就原样返回，React 也会因引用不变而不重渲染。
    setSel((prev) => (prev.length ? prev : rowKeys));
  }, [fieldTree, visibleGroups, dataset, sel.length, initLoading, guideMode]);
  /**
   * F-1：数据源下拉的选项 —— **全部来自服务端 `availableSources`**，本地一个都不造。
   *
   * 服务端还没给清单（B-1 未上线 / 该方言无数据源）时的退化形态：只放**当前坐标自己**一项，
   * label 用现有信息拼（费用类走 variant label，否则用 anchorDesc / 内部坐标串兜底）。
   * 🚫 **不回退去消费 `availableTabTypes`**：那份清单里还有已退役的「零件 / 外购件」，
   *    拿它当选项等于把本任务刚收缩掉的东西原样放回给用户选（S-4）。
   * 用户此时**看得见自己现在配的是哪个源、但改不了**（下拉只有一项），比一个空下拉可诊断。
   */
  const sourceOptions = useMemo(() => {
    if (visibleSources.length) {
      return visibleSources.map((s) => ({ value: s.sourceKey, label: s.label }));
    }
    const variantLabel = fieldTree?.variants?.find((v) => vkEq(v.key, variantKey))?.label;
    return [{ value: FALLBACK_SOURCE_KEY, label: variantLabel || fieldTree?.anchorDesc || tabType }];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visibleSources, fieldTree, tabType, variantKey]);
  /** 下拉当前值：清单在 → 选中项的 sourceKey；清单不在 → 那唯一一项退化选项。 */
  const sourceSelectValue = selectedSource?.sourceKey ?? FALLBACK_SOURCE_KEY;
  const datasetMeta = DATASETS.find((d) => d.key === dataset)!;

  // ── 渲染：已选输出列（含价格策略原子组块，F-5）──────────────────────────
  function renderRoleBadges(s: SelColumn) {
    if (!s.roles.length) return null;
    return <span className="svb-badges">{s.roles.map((r) => <span key={r} className="svb-rbadge rbadge" title={`${ROLE_LABEL[r]}（角色来自字段树声明，配置器只读，不提供修改入口）`}>{ROLE_LABEL[r]}</span>)}</span>;
  }
  function renderSelRowBody(s: SelColumn) {
    // task-260908 F-2（AC-15，原型 02 状态 1/2）：行键列锁定态 —— 绿底 + 拖拽手柄置灰 + ✕ 禁用但可见。
    const locked = isRowKeyCol(s);
    return (
      <>
        <div className="svb-sr-1">
          <span className={`svb-hd${locked ? ' off' : ''}`}>⋮⋮</span>
          <Input
            size="small" className="svb-fname" value={s.fieldName}
            onChange={(e) => renameColumn(s._uid, e.target.value)}
            title="字段名 = 模板/报价单上这一列显示的名字。改它同步引用，但不动 SQL、不重新编译视图"
          />
          <code className="svb-viewcol" title={s.viewColumn ? `视图列名由系统按【数据来源】自动生成，用户不可改。绑定路径 $view.${s.viewColumn} 跟着它，永远逐字对齐` : '拖拽变化后 300ms 内自动编译并回填视图列名'}>
            {s.viewColumn || '（编译后可见）'}
          </code>
          {/* task-260909 F-1（AC-1，原型 01/02）：字段类型选择器 —— 位置紧挨「数据类型」之前，
              宽度固定 132px（原型注 ⑤：🚫 不跟内容伸缩，否则三种标签宽度不同会让整列左右跳动）。 */}
          <Select<BuilderFieldType>
            size="small" className="svb-ftype" value={s.fieldType as BuilderFieldType}
            onChange={(v) => setFieldType(s._uid, v)}
            /* 稳定测试钩子（主线 2026-09-09 批）：测试看不到实现代码，只能靠约定定位；
               靠 DOM 结构兜底的选择器一旦失效，症状是 timeout —— 长得像产品坏了。 */
            data-role="field-type-select"
            aria-label="字段类型"
            data-field-name={s.fieldName}
            options={FIELD_TYPE_OPTIONS.map((o) => ({ value: o.value, label: o.label }))}
            optionRender={(opt) => {
              const hint = FIELD_TYPE_OPTIONS.find((o) => o.value === opt.value)?.hint;
              return <span>{opt.label}{hint && <span className="svb-ftype-hint">{hint}</span>}</span>;
            }}
            title="字段类型：基础数据=只读展示（落库写顶层 basic_data_path）；文本/数字输入=可填写（落库写 default_source.path）"
          />
          <span className="svb-t2">{DATA_TYPE_LABEL[s.dataType]}</span>
          {s.groupLabel && s.groupKind === 'SUB' && <span className="svb-badge-aux" title={`来自「${s.groupLabel}」，编译为相关标量子查询`}>⚠{s.groupLabel}</span>}
          {s.groupLabel && ['GRAIN', 'JOIN', 'SAME'].includes(s.groupKind || '') && <span className="svb-badge-join" title={`来自「${s.groupLabel}」`}>{s.groupLabel}</span>}
          {s.lookupLib && <span className="svb-badge-join">{s.lookupLib}</span>}
        </div>
        <div className="svb-sr-2">
          {renderRoleBadges(s)}
          <span className="svb-amt">
            <label><Checkbox checked={s.isAmount} onChange={(e) => toggleAmount(s._uid, e.target.checked)} />金额</label>
            <label><Checkbox checked={s.inSubtotal} onChange={(e) => toggleSubtotal(s._uid, e.target.checked)} />小计</label>
          </span>
          {locked ? (
            /* D-20（主线 2026-09-08 追加 · 可测性）：Tooltip 走 portal 渲染，量具在 ✕ 元素自身上
               读不到原因文案 ⇒ 同时挂 `title` + `aria-label`，两者与 Tooltip 文案逐字一致。
               🚫 不是二选一：Tooltip 给人看（有样式、跟随光标），title/aria-label 给量具和读屏。 */
            <Tooltip title={ROW_KEY_LOCK_TIP}>
              <span className="svb-rm off" aria-disabled title={ROW_KEY_LOCK_TIP} aria-label={ROW_KEY_LOCK_TIP} data-role="remove-column-disabled">✕</span>
            </Tooltip>
          ) : (
            <span className="svb-rm" onClick={() => removeColumn(s._uid)} title="移除">✕</span>
          )}
        </div>
      </>
    );
  }
  function renderSelected() {
    if (!sel.length) {
      return (
        <div className="svb-empty"
          onDragOver={(e) => { e.preventDefault(); e.currentTarget.classList.add('svb-drop-hot'); }}
          onDragLeave={(e) => e.currentTarget.classList.remove('svb-drop-hot')}
          onDrop={(e) => handleDrop(e, null)}
        >把左侧字段拖到这里</div>
      );
    }
    const pgMembers = sel.filter((s) => s.raw || s.autoElem);
    const pgHeadUid = pgMembers[0]?._uid;
    const nodes: React.ReactNode[] = [];
    sel.forEach((s) => {
      const inPg = !!(s.raw || s.autoElem);
      if (inPg && s._uid !== pgHeadUid) return;
      if (inPg) {
        const priceCol = pgMembers.find((m) => m.raw && m.isCore);
        const dropCls = dropIndicator?.uid === s._uid ? ` drop-${dropIndicator.pos}` : '';
        nodes.push(
          <div key={s._uid} className={`svb-pgrp${dropCls}`} draggable onDragStart={(e) => handleGroupDragStart(e, s._uid)} onDragEnd={() => setDropIndicator(null)} onDragOver={(e) => handleBlockDragOver(e, s._uid)} onDrop={(e) => handleBlockDrop(e, s._uid)}>
            <div className="svb-pgrp-h">⋮⋮ 元素单价（接价格策略） <span className="svb-pgrp-note">f_material_element_price</span>
              {manualFieldOptions.length > 0 && (
                <span style={{ marginLeft: 8, display: 'flex', alignItems: 'center', gap: 4, fontWeight: 400 }} onClick={(e) => e.stopPropagation()}>
                  元素键取自
                  <Select
                    size="small" style={{ width: 130 }}
                    value={elemKeyOverrideField ?? (elemKeyCol?.fieldName ?? undefined)}
                    onChange={(v) => {
                      const isColDriven = sel.some((x) => (x.elemKey || x.autoElem) && x.fieldName === v);
                      if (isColDriven) { setElemKeyOverrideField(null); return; }
                      // 形态 B（AC-23）：改绑手填字段后不再输出元素业务列
                      setElemKeyOverrideField(v);
                      setSel((prev) => prev.filter((x) => !x.elemKey && !x.autoElem));
                    }}
                    options={[
                      ...(elemKeyCol ? [{ value: elemKeyCol.fieldName, label: `${elemKeyCol.fieldName}（取数列）` }] : []),
                      ...manualFieldOptions.filter((o) => o.value !== elemKeyCol?.fieldName),
                    ]}
                  />
                </span>
              )}
              {/* task-260908 F-2（AC-15 / 原型 02 状态 5）：整组移除会 filter 掉全部 raw/elemKey/autoElem
                  成员 —— 组里若混进行键列，这个入口就绕过了单行的禁用态。同样置成禁用但可见。 */}
              {pgMembers.some(isRowKeyCol) ? (
                <Tooltip title={ROW_KEY_LOCK_GROUP_TIP}>
                  {/* D-20：同上，title/aria-label 与 Tooltip 文案逐字一致 */}
                  <span className="svb-rm off" aria-disabled title={ROW_KEY_LOCK_GROUP_TIP} aria-label={ROW_KEY_LOCK_GROUP_TIP} data-role="remove-group-disabled">✕</span>
                </Tooltip>
              ) : (
                <span className="svb-rm" onClick={() => removeColumn(priceCol ? priceCol._uid : s._uid)} title="移除整组">✕</span>
              )}
            </div>
            {pgMembers.map((m) => <div className={`svb-sel-row in-pg${isRowKeyCol(m) ? ' svb-locked' : ''}`} data-role="selected-column" key={m._uid}>{renderSelRowBody(m)}</div>)}
          </div>,
        );
        return;
      }
      const dropCls = dropIndicator?.uid === s._uid ? ` drop-${dropIndicator.pos}` : '';
      // task-260908 F-2（AC-15，原型 02 状态 1）：行键列 = 锁定态（绿底）且自身不可拖动 ——
      // 手柄已置灰，draggable 一并关掉，避免"看着不能拖、拖起来却动了"。
      // 📌 仍是 drop 目标：别的列可以插到它前后，所以任意相对顺序依然做得到，没有能力被拿走。
      const locked = isRowKeyCol(s);
      nodes.push(
        <div key={s._uid} className={`svb-sel-row${locked ? ' svb-locked' : ''}${dropCls}`} data-role="selected-column" draggable={!locked} onDragStart={(e) => handleRowDragStart(e, s._uid)} onDragEnd={() => setDropIndicator(null)} onDragOver={(e) => handleBlockDragOver(e, s._uid)} onDrop={(e) => handleBlockDrop(e, s._uid)}>
          {renderSelRowBody(s)}
        </div>,
      );
    });
    // 用户问题 1 修复：整个列表再包一层 drop 区——覆盖行与行之间、末尾的全部空白，不再只有「行自身」能接收
    // drop；容器自己的 dragover/drop 等价于「插到列表末尾」，且行级 handler 已 stopPropagation，
    // 悬浮在具体行上时不会被容器的「末尾」指示打架。
    return (
      <div
        className={`svb-sel-list${dropIndicator?.uid === null ? ' drop-end-hot' : ''}`}
        onDragOver={handleListDragOver}
        onDragLeave={handleListDragLeave}
        onDrop={(e) => handleDrop(e, { uid: null, pos: 'after' })}
      >
        {nodes}
      </div>
    );
  }

  // ── 渲染：粒度条（F-4，纯取自 /compile 的 grain，不本地推导）────────────
  const grainText = compileResult ? (compileResult.grain.length ? `成品 + ${compileResult.grain.join(' + ')}` : '每个成品 1 行') : (sel.length ? '（拖拽后重新计算…）' : '每个成品 1 行');

  // ── 渲染：体检区（F-8：只显示阻断/告警，全通过时一行「检查通过」）──────
  // D-49（紧急修复）：字段名是 `items` 不是 `checks`（api.md §2.3a 补），且双重可选链保护到字段本身——
  // 契约缺字段时降级成空数组，不再硬抛 `Cannot read properties of undefined (reading 'filter')`。
  function renderHealth() {
    if (!sel.length) return <div className="svb-hitem ok"><span className="ic">✓</span><span>尚未选择输出列</span></div>;
    if (!inspectResult) return <div className="svb-hitem ok"><span className="ic">…</span><span>体检中</span></div>;
    const blocking = (inspectResult.items ?? []).filter((it) => it.level !== 'INFO');
    if (!blocking.length) return <div className="svb-hitem ok"><span className="ic">✓</span><span>检查通过</span></div>;
    return blocking.map((it, i) => (
      <div key={i} className={`svb-hitem ${it.level === 'ERR' ? 'err' : 'warn'}`}>
        <span className="ic">{it.level === 'WARN' ? '!' : '✕'}</span>
        <span dangerouslySetInnerHTML={{ __html: it.message }} />
      </div>
    ));
  }
  const errCount = inspectResult?.items?.filter((i) => i.level === 'ERR').length ?? 0;
  const warnCount = inspectResult?.items?.filter((i) => i.level === 'WARN').length ?? 0;
  // D-49 顺带：后端 `blocked` 是权威标志（有 ERR 项就是 true），比前端自己数 errCount 更可靠；
  // `blocked` 本身缺失时（契约过渡期）才退化用 errCount>0 兜底，不再只认自己数出来的那一份。
  const inspectBlocked = inspectResult ? (inspectResult.blocked ?? errCount > 0) : false;
  // 用户问题 2 修复：canSave 从「几个条件与出来的布尔值」改成从统一的 saveDisabledReason 推导——
  // 灰按钮旁边/悬浮必须能看出具体是哪一条不满足，不能只知道"不能点"却不知道为什么。
  const saveDisabledReason: string | null =
    !sel.length ? '尚未选择任何输出列，请从左侧拖入字段'
      : compileError ? `SQL 编译失败：${compileError.message}`
      : !compileResult ? '正在编译，请稍候'
      : inspectBlocked ? `保存前体检存在 ${errCount} 项阻断，需先解决（见下方"保存前体检"红色项）`
      : null;
  const canSave = !saveDisabledReason;

  // ── 渲染：真实预览（F-9）─────────────────────────────────────────────
  function renderPreview() {
    return (
      <div className="svb-preview">
        <div className="svb-pv-h">
          <span>客户</span>
          <Select
            size="small" style={{ width: 220 }} showSearch filterOption={false}
            placeholder="选择预览客户" value={previewCustomerCode ?? undefined}
            loading={customerSearching} onSearch={searchCustomers} onChange={(v) => setPreviewCustomerCode(v)}
            options={customerOptions} notFoundContent={customerSearching ? '搜索中…' : '无匹配客户'}
          />
          <span>料号</span>
          <Input size="small" style={{ width: 160 }} value={previewPartNo} onChange={(e) => setPreviewPartNo(e.target.value)} placeholder="料号（可空）" />
          <Button size="small" loading={previewLoading} onClick={runPreview}>重新执行</Button>
          {previewResult && <span className="rescount">返回 {previewResult.rowCount} 行 · {previewResult.elapsedMs}ms</span>}
        </div>
        {!previewResult && <div style={{ padding: '14px 16px', color: 'var(--svb-sub)', fontSize: 12 }}>选择客户后点「重新执行」查看真实取数结果</div>}
        {previewResult && previewResult.rowCount === 0 && (
          <div className="svb-diag">
            <b>返回 0 行</b>
            {previewResult.diagnostics.length
              ? previewResult.diagnostics.map((d, i) => <div key={i}>· {d.message}</div>)
              : <div>后端未给出具体诊断原因</div>}
          </div>
        )}
        {previewResult && previewResult.rowCount > 0 && (
          <>
            <div style={{ overflow: 'auto' }}>
              <table className="svb-pv-table">
                <thead><tr>{previewResult.columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
                <tbody>
                  {previewResult.rows.map((r, i) => (
                    <tr key={i}>{previewResult.columns.map((c) => {
                      const v = r[c];
                      return v === null || v === undefined ? <td key={c} className="null">NULL</td> : <td key={c}>{String(v)}</td>;
                    })}</tr>
                  ))}
                </tbody>
              </table>
            </div>
            {previewResult.diagnostics.length > 0 && (
              <div className="svb-diag">
                {previewResult.diagnostics.map((d, i) => <div key={i}><b>{d.column ? `「${d.column}」` : ''}{d.level === 'WARN' ? '告警' : '错误'}</b>{d.message}</div>)}
              </div>
            )}
          </>
        )}
      </div>
    );
  }

  // ── 引导页（AC-32：存量手写视图）────────────────────────────────────────
  if (initLoading) return <div className="svb-guide">加载中…</div>;
  if (guideMode) {
    return (
      <div className="svb-root">
        <div className="svb-guide" data-role="builder-guide">
          <p style={{ fontSize: 14, marginBottom: 8 }}>该组件<b>尚未使用取数配置器</b>——SQL 视图是存量手写视图</p>
          <p>取数配置器只支持从零可视化配置，不支持接管已有的手写 SQL 并「转为可视化配置」。</p>
          <p>如需迁移，请到「SQL 视图」Tab 查看当前 SQL，联系开发或 Agent 处理；本引导页不提供自动迁移入口。</p>
        </div>
      </div>
    );
  }

  return (
    <div className="svb-root">
      {staleInfo && !staleDismissed && (
        <div className="svb-stale-bar">
          本视图由旧版规则生成（builder_version {staleInfo.builderVersion} → 当前编译器 v{staleInfo.currentVersion}），重新保存即可升级。
          <a onClick={() => setDiffOpen(true)}>查看新旧 SQL 差异</a>
          <span className="x" style={{ marginLeft: 'auto', cursor: 'pointer' }} onClick={() => setStaleDismissed(true)}>✕</span>
        </div>
      )}

      <div className="svb-recipe-bar">
        <div className="svb-rb-line">
          {/* F-30（AC-115）：数据集三选一——**位置必须在「页签类型」之前**，它决定字段面板出哪些表。 */}
          <span className="svb-lbl">数据集</span>
          <Segmented
            size="small"
            value={dataset}
            onChange={(v) => handleDatasetChange(v as BuilderDataset)}
            options={DATASETS.map((d) => ({ value: d.key, label: d.label }))}
          />
          {/* task-260904 F-1（AC-1① / AC-2① / AC-3①）：原「页签类型」+「数据来源」两个下拉
              合并为这一个「数据源」下拉 —— 界面上**不再出现「页签类型」四个字**。
              选项来自服务端 availableSources；选中项的 (tabType, variantKey, dialect) 三段坐标
              原样回传（handleSourceChange）。原型 `原型图/取数配置Tab.html` 的 #srcSel。 */}
          <span className="svb-lbl" style={{ marginLeft: 14 }}>数据源</span>
          <Select
            size="small" style={{ width: 200 }} data-role="builder-source"
            value={sourceSelectValue}
            onChange={handleSourceChange}
            options={sourceOptions}
            disabled={!visibleSources.length}
          />
          {/* F-2/F-3：语义**只读回显**，用户不可改。原型 #semTag。
              🚫 分支判据是服务端的 `semantic`，不是 label/sourceKey（F-2 明令）。 */}
          {semantic === 'TREE' || semantic === 'MATERIAL_ELEMENT' ? (
            <span className="svb-src-pill" data-role="builder-source-semantic">{SEM_TEXT[semantic]}</span>
          ) : (
            <span className="svb-hint-i" data-role="builder-source-semantic">普通数据源</span>
          )}
          {/* F-2：TREE 追加树形只读提示（api.md §1.2「前端行为」列）。 */}
          {semantic === 'TREE' && <span className="svb-hint-i">{TREE_HINT}</span>}
          {/* 原型 #varHint：费用类等有变体的源，把它的一句说明跟在语义标记后面（原「数据来源」
              下拉的 hint，下拉本身已并入数据源，说明文字保留）。 */}
          {(() => {
            const vh = fieldTree?.variants?.find((v) => vkEq(v.key, variantKey))?.hint;
            return vh ? <span className="svb-hint-i">{vh}</span> : null;
          })()}
        </div>
        <div className="svb-rb-line">
          {/* F-30：数据集身份说明（轴列 / 表前缀 / 版本口径），照原型的 axisInfo 行。
              🚫 不含任何「本数据集有几张表」的写死数字——那是数据，只能来自服务端字段树。 */}
          <span className="svb-hint-i">
            轴列 <b>{datasetMeta.axis}</b>{'　·　'}表前缀 <code>{datasetMeta.tablePrefix}</code>{'　·　'}{datasetMeta.note}
          </span>
        </div>
        <div className="svb-rb-line">
          <span>取数：<b>{fieldTree?.anchorDesc ?? (treeLoading ? '加载中…' : '—')}</b>　·　行粒度：<b>{grainText}</b></span>
        </div>
        {/* F-16（AC-60，D-51）：「选项」行整体删除——不再渲染任何开关（此前把内部枚举名直接
            印在界面上，且勾了不生效）；子件数据带出与否由页签类型自动决定，用户无入口。 */}
      </div>

      <div className="svb-cols">
        <div className="svb-pane left">
          <div className="svb-pane-h"><b>可用字段</b><span>{treeLoading ? '加载中…' : `共 ${totalFieldCount} 个字段`}</span></div>
          <div className="svb-pane-b">
            {/* AC-116：只渲染 visibleGroups——别套数据集的分组连 DOM 都不生成（不是置灰） */}
            {visibleGroups.map((g) => renderGroup(g))}
            {/* 空态文案（AC-115 ③ 的后继形态）：task-260904 F-1 后不再有「页签类型不可选/置灰」
                这回事 —— 下拉里就只有本数据集真实存在的数据源，所以空态只剩「这个源没有可用字段」
                一种可能。数据源名优先用服务端 label，退化态用 anchorDesc / 内部坐标。 */}
            {!treeLoading && fieldTree && visibleGroups.length === 0 && (
              <div className="svb-empty-tip">
                {`「${datasetLabel(dataset)}」数据集下，数据源「${selectedSource?.label ?? fieldTree.anchorDesc ?? tabType}」没有可用字段。`}
              </div>
            )}
            {/* 原型 render()：deadBlock 只跟在有卡片的 grid 后面，空态分支只渲染 .empty —— 照此对齐 */}
            {visibleGroups.length > 0 && renderExcludedTables()}
          </div>
        </div>
        <div className="svb-pane right">
          {/* task-260908 F-2（原型 `02-取数配置器-已选输出列.html` 状态 1/2 的 pane-h 副标题）：
              右侧计数。全部是行键列时点明「均为必选行键」，与 ✕ 的禁用态相互印证 —— 用户看到
              「删不掉」时，标题栏已经把原因说在前面了。 */}
          <div className="svb-pane-h">
            <span className="svb-ph-l">
              <b>已选输出列</b>
              {sel.length > 0 && (
                <span className="svb-sel-count">
                  {sel.length} 列{sel.every(isRowKeyCol) ? ' · 均为必选行键' : ''}
                </span>
              )}
            </span>
            {/* task-260909 F-2（AC-2，原型 01 的 toolbar）：整列批量设置。两步 —— 先选值，再点
                「应用到全部列」。🚫 一列都没有时按钮**禁用但可见 + hover 说明原因**
                （frontend.md §1.2），不许 return null 藏掉：藏了用户就不知道有这个能力。 */}
            <span className="svb-ftype-bulk">
              <span className="svb-hint-i">整列批量设为</span>
              <Select<BuilderFieldType>
                size="small" className="svb-ftype" value={bulkFieldType}
                onChange={(v) => setBulkFieldType(v)}
                data-role="bulk-field-type"
                aria-label="整列批量设为"
                options={FIELD_TYPE_OPTIONS.map((o) => ({ value: o.value, label: o.label }))}
                optionRender={(opt) => {
                  const hint = FIELD_TYPE_OPTIONS.find((o) => o.value === opt.value)?.hint;
                  return <span>{opt.label}{hint && <span className="svb-ftype-hint">{hint}</span>}</span>;
                }}
              />
              <Tooltip title={sel.length ? undefined : BULK_FIELD_TYPE_EMPTY_TIP}>
                <span>
                  <Button
                    size="small" disabled={!sel.length}
                    title={sel.length ? undefined : BULK_FIELD_TYPE_EMPTY_TIP}
                    aria-label={sel.length ? undefined : BULK_FIELD_TYPE_EMPTY_TIP}
                    data-role="apply-field-type-all"
                    onClick={() => applyFieldTypeToAll(bulkFieldType)}
                  >应用到全部列</Button>
                </span>
              </Tooltip>
            </span>
          </div>
          <div className="svb-pane-b">{renderSelected()}</div>
        </div>
        <div className="svb-pane sql">
          <div className="svb-pane-h"><b>生成的 SQL（实时·只读）</b><span className="svb-zoom" onClick={() => setSqlZoomOpen(true)} title="放大查看">⤢</span></div>
          <div className="svb-pane-b">
            {compileError && <Alert type="error" showIcon style={{ marginBottom: 8 }} message={compileError.message} description={compileError.suggestion} />}
            <pre className="svb-livesql" dangerouslySetInnerHTML={{ __html: compileResult ? highlightSql(compileResult.sql) : (compiling ? '编译中…' : (sel.length ? '' : '拖入字段后生成')) }} />
          </div>
        </div>
      </div>

      <div className="svb-health">
        <div className="svb-health-h"><b style={{ color: '#1f2329' }}>保存前体检</b>
          <span>{errCount ? <span style={{ color: 'var(--svb-danger)' }}>{errCount} 项阻断</span> : warnCount ? <span style={{ color: 'var(--svb-gold)' }}>{warnCount} 项告警（可保存）</span> : <span style={{ color: 'var(--svb-green)' }}>全部通过</span>}</span>
        </div>
        {renderHealth()}
      </div>

      {previewOpen && renderPreview()}

      {/* 用户问题 2 修复：保存失败常驻 Alert——不依赖 3 秒自动消失的 toast，closable 由用户自己收起。 */}
      {saveError && (
        <Alert
          type="error" showIcon closable style={{ marginTop: 10 }}
          message="保存失败" description={saveError}
          onClose={() => setSaveError(null)}
        />
      )}

      <div className="builder-footer" data-role="builder-actions" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 14 }}>
        <Dropdown menu={{ items: moreMenuItems, onClick: handleMoreMenuClick }} trigger={['click']}>
          <Button>⋯</Button>
        </Dropdown>
        <Space align="center">
          {/* 用户问题 2 修复：常驻显示禁用原因（不只是 hover 才看得到），Tooltip 再给一遍同样的文案兜底
              （disabled 按钮本身不总能可靠触发 hover 事件，外面包一层 span 承接）。 */}
          {saveDisabledReason && <span className="svb-hint-i">{saveDisabledReason}</span>}
          <Button onClick={handleCancel}>取消</Button>
          <Tooltip title={saveDisabledReason ?? undefined}>
            <span>
              <Button type="primary" disabled={!canSave} loading={saving} onClick={() => doSave(false)}>保存</Button>
            </span>
          </Tooltip>
        </Space>
      </div>

      <Drawer title="生成的 SQL（只读）" placement="right" width={860} open={sqlZoomOpen} onClose={() => setSqlZoomOpen(false)}>
        <div className="ro-note" style={{ background: '#fffbe6', border: '1px solid #ffe58f', borderRadius: 6, padding: '8px 12px', fontSize: 12, color: '#874d00', marginBottom: 10 }}>
          ⚠ 本视图由取数配置生成，SQL 不可直接编辑。需要手改请先「转为手写 SQL」（不可逆）。
        </div>
        <pre className="svb-livesql" style={{ background: '#0f1720', color: '#d6e2f0', borderRadius: 8, padding: '14px 16px', whiteSpace: 'pre-wrap' }}
          dangerouslySetInnerHTML={{ __html: compileResult ? highlightSql(compileResult.sql) : '（暂无）' }} />
      </Drawer>

      <Drawer title="新旧 SQL 差异" placement="right" width={960} open={diffOpen} onClose={() => setDiffOpen(false)}>
        <p style={{ fontSize: 12, color: 'var(--svb-sub)' }}>左：当前已保存的旧版本 SQL（sql_template）；右：按当前编译器重新编译的新版本 SQL（与本次若点「保存」将落库的文本一致）。</p>
        <div style={{ display: 'flex', gap: 12 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 4 }}>旧（builder_version {staleInfo?.builderVersion}）</div>
            <pre className="svb-livesql" style={{ background: '#f6f6f6', border: '1px solid #e5e7eb', borderRadius: 6, maxHeight: 560 }}>{oldSqlTemplate ?? '（无）'}</pre>
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 4 }}>新（当前编译器 v{staleInfo?.currentVersion}）</div>
            <pre className="svb-livesql" style={{ background: '#f6f6f6', border: '1px solid #e5e7eb', borderRadius: 6, maxHeight: 560 }} dangerouslySetInnerHTML={{ __html: compileResult ? highlightSql(compileResult.sql) : '（拖拽当前配置以生成）' }} />
          </div>
        </div>
      </Drawer>
    </div>
  );
});

export default SqlViewBuilderTab;
