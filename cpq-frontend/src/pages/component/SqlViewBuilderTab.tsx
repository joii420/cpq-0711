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
import { Alert, Button, Checkbox, Drawer, Dropdown, Input, Select, Space, message, Modal, Tooltip } from 'antd';
import type { MenuProps } from 'antd';
import {
  fetchFieldTree, getBuilder, compileBuilder, previewBuilder, inspectBuilder, saveBuilder, detachBuilder,
  type FieldTreeResponse, type FieldTreeColumn, type FieldTreeGroup, type BuilderConfigPayload, type CompileResponse,
  type CompileErrorBody, type PreviewResponse, type InspectResponse, type FieldRole, type SavedBuilderColumn,
} from '../../services/sqlViewBuilderService';
import { customerService } from '../../services/customerService';

// ── 常量 ────────────────────────────────────────────────────────────────

/**
 * AC-25：页签类型下拉含 6 项（新增「费用类」，D-34 分立建模）。字段树 availableTabTypes 缺失时的兜底常量。
 * 📌 D-39（存储值与显示名故意不同，与 D-12「列名=来源、字段名=显示」同源）：
 *    `TAB_TYPES` 装的是**存储值**（提交给后端 / 写进 builder_config.tabType 的那个字符串，
 *    与组件详情头部 Select 的 value、后端 VALID_TAB_TYPES 三处口径必须逐字一致），第 6 项是 `'BOM'`。
 *    渲染给用户看的显示名走 `TAB_TYPE_LABEL`——BOM 显示为「BOM 树」，其余 5 类显示名与存储值相同。
 *    🚫 `includes()` / 默认值 / 回填匹配一律用 `TAB_TYPES`（值），不要错拿 label 去比对，
 *    否则会复现「BOM 组件首次打开被误判成主件」那个 bug（F-15 修过一次）。
 */
const TAB_TYPES = ['主件', '材质元素', '零件', '外购件', '费用类', 'BOM'] as const;
/** D-39：仅 BOM 的显示名与存储值不同；其余 5 类未列出时 Select 渲染逻辑回退用存储值本身当显示名。 */
const TAB_TYPE_LABEL: Record<string, string> = { BOM: 'BOM 树' };
const ROLE_LABEL: Record<FieldRole, string> = { PART_NO: '料号', PART_NAME: '名称', ROW_KEY: '行键', SORT: '排序' };
const DATA_TYPE_LABEL: Record<string, string> = { TEXT: '文本', NUMBER: '数字', MONEY: '金额' };

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

function toSelColumn(col: FieldTreeColumn, group: FieldTreeGroup, opts?: { autoElem?: boolean }): SelColumn {
  const money = col.dataType === 'MONEY';
  const unitLike = /单价|汇率|费率|比例|系数|基准值/.test(col.displayName);
  return {
    _uid: nextUid(),
    sourceNodeKey: col.sourceNodeKey,
    sourceColumn: col.sourceColumn,
    fieldName: col.displayName,
    origFieldName: col.displayName,
    viewColumn: col.viewColumn || '',
    fieldType: money ? 'INPUT_NUMBER' : 'INPUT_TEXT',
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
function fromSavedColumn(bc: SavedBuilderColumn): SelColumn {
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
    fieldType: bc.fieldType || 'INPUT_TEXT',
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

  const [tabType, setTabType] = useState<string>(TAB_TYPES[0]);
  const [variantKey, setVariantKey] = useState<string | null>(null);
  const [sel, setSel] = useState<SelColumn[]>([]);
  const [elemKeyOverrideField, setElemKeyOverrideField] = useState<string | null>(null);

  const [fieldTree, setFieldTree] = useState<FieldTreeResponse | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

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
        // 全新组件，尚无任何 SQL 视图：直接进入空白拖拽态（页签类型可选、字段面板可用、已选列为空）
        const initT = initialTabType && (TAB_TYPES as readonly string[]).includes(initialTabType) ? initialTabType : TAB_TYPES[0];
        setTabType(initT);
        setVariantKey(null);
        setSavedSnapshot(JSON.stringify(configPayloadFor(initT, null, [], null))); // D-55①：新组件的基线 = 空配置
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

  // ── 编译请求体（BuilderConfigPayload：扁平角色布尔位，见 sqlViewBuilderService.ts 头注） ──
  // D-51：不再写 switches 字段（子件闭包开关整体移除，AC-60——builder_config.switches 中不再写入
  // 内部枚举名或 includeChildParts 这一类键）。
  // 拆成纯函数 configPayloadFor + 薄封装 buildConfigPayload：D-55① 的快照比对需要在"值刚被算出、
  // 尚未等一轮 re-render 提交进 state"的时刻（loadBuilderState 的 NEW 分支、rehydrate 完成后）就地
  // 算一次等价 payload 当基线，不依赖组件 state 闭包此刻是否已提交完成。
  function configPayloadFor(t: string, vk: string | null, selCols: SelColumn[], override: string | null): BuilderConfigPayload {
    const payload: BuilderConfigPayload = {
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
    return configPayloadFor(tabType, variantKey, sel, elemKeyOverrideField);
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
        const res = await fetchFieldTree(tabType, variantKey, selectedConfig);
        if (cancelled) return;
        setFieldTree(res);
        // 默认折叠态照原型（原型 .grp 默认带 collapsed class）——仅首次拿到该 tabType 的分组时设置，
        // 避免每次因 selectedConfig 变化重拉时把用户手动展开的分组又折回去。
        setCollapsed((prev) => {
          const known = new Set(prev);
          let changed = false;
          res.groups.forEach((g) => { if (!known.has(g.groupName) && prev.size === 0) { known.add(g.groupName); changed = true; } });
          return changed ? known : prev;
        });
      } catch (e: any) {
        message.error('加载字段面板失败：' + (e?.message ?? '未知错误'));
        setFieldTree(null);
      } finally {
        if (!cancelled) setTreeLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabType, variantKey, initLoading, guideMode,
    sel.map((s) => `${s.sourceNodeKey}.${s.sourceColumn}`).join('|')]);

  // 首次进入某 tabType 时字段面板默认全折叠（原型默认态）；后续 selectedConfig 触发的重拉不重置折叠态。
  useEffect(() => {
    if (fieldTree && collapsed.size === 0 && !sel.length) {
      setCollapsed(new Set(fieldTree.groups.map((g) => g.groupName)));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldTree === null]);

  // ── 字段树就绪后，若有待恢复的 builderConfig，重建 sel（AC-39：刷新后拖拽态与保存前一致）───
  useEffect(() => {
    const pending = pendingRehydrateRef.current;
    if (!fieldTree || !pending) return;
    const allCols: Array<{ col: FieldTreeColumn; group: FieldTreeGroup }> = [];
    fieldTree.groups.forEach((g) => g.fields.forEach((c) => allCols.push({ col: c, group: g })));
    const rebuilt: SelColumn[] = pending.columns.map((bc) => {
      const found = allCols.find((x) => x.col.sourceNodeKey === bc.sourceNodeKey && x.col.sourceColumn === bc.sourceColumn);
      if (found) {
        const s = toSelColumn(found.col, found.group, { autoElem: found.col.elemKey && bc.userAdded === false });
        return { ...s, fieldName: bc.fieldName, origFieldName: bc.fieldName, viewColumn: bc.viewColumn, isAmount: !!bc.isAmount, inSubtotal: !!bc.inSubtotal, fieldType: bc.fieldType || s.fieldType };
      }
      // 字段树里找不到对应列（罕见：图或存量数据漂移）——仍原样展示，避免保存态丢失，只是缺角色信息。
      return fromSavedColumn(bc);
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
    setSavedSnapshot(JSON.stringify(configPayloadFor(pending.tabType, pending.variantKey ?? null, rebuilt, override)));
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
      for (const g of fieldTree?.groups || []) {
        for (const c of g.fields) if (c.elemKey) { ek = { col: c, group: g }; break; }
        if (ek) break;
      }
      if (ek && !isUsed(ek.col.sourceNodeKey, ek.col.sourceColumn)) additions.push(toSelColumn(ek.col, ek.group, { autoElem: true }));
    }
    additions.push(toSelColumn(col, group));
    setSel((prev) => [...prev, ...additions]);
    return additions;
  }

  function removeColumn(uid: string) {
    const target = sel.find((s) => s._uid === uid);
    if (!target) return;
    const killsGroup = !!(target.isCore || target.elemKey || target.autoElem);
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
  function handleTabTypeChange(v: string) {
    if (v === tabType) return;
    const doSwitch = () => {
      setTabType(v);
      setVariantKey(null);
      setSel([]);
      setElemKeyOverrideField(null);
      setFieldTree(null);
      setCollapsed(new Set());
    };
    if (sel.length) {
      Modal.confirm({ title: '切换页签类型会清空已选输出列', content: '继续？', okText: '继续切换', cancelText: '取消', onOk: doSwitch });
    } else doSwitch();
  }
  function handleVariantChange(v: string) {
    if (v === variantKey) return;
    const doSwitch = () => {
      setVariantKey(v);
      setSel([]);
      setElemKeyOverrideField(null);
      setFieldTree(null);
      setCollapsed(new Set());
    };
    if (sel.length) {
      Modal.confirm({ title: '切换数据来源会清空已选输出列', content: '继续？', okText: '继续切换', cancelText: '取消', onOk: doSwitch });
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
  const totalFieldCount = useMemo(() => {
    if (!fieldTree) return 0;
    return fieldTree.groups.reduce((sum, g) => sum + g.fields.length, 0);
  }, [fieldTree]);

  // ── 渲染：已选输出列（含价格策略原子组块，F-5）──────────────────────────
  function renderRoleBadges(s: SelColumn) {
    if (!s.roles.length) return null;
    return <span className="svb-badges">{s.roles.map((r) => <span key={r} className="svb-rbadge rbadge" title={`${ROLE_LABEL[r]}（角色来自字段树声明，配置器只读，不提供修改入口）`}>{ROLE_LABEL[r]}</span>)}</span>;
  }
  function renderSelRowBody(s: SelColumn) {
    return (
      <>
        <div className="svb-sr-1">
          <span className="svb-hd">⋮⋮</span>
          <Input
            size="small" className="svb-fname" value={s.fieldName}
            onChange={(e) => renameColumn(s._uid, e.target.value)}
            title="字段名 = 模板/报价单上这一列显示的名字。改它同步引用，但不动 SQL、不重新编译视图"
          />
          <code className="svb-viewcol" title={s.viewColumn ? `视图列名由系统按【数据来源】自动生成，用户不可改。绑定路径 $view.${s.viewColumn} 跟着它，永远逐字对齐` : '拖拽变化后 300ms 内自动编译并回填视图列名'}>
            {s.viewColumn || '（编译后可见）'}
          </code>
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
          <span className="svb-rm" onClick={() => removeColumn(s._uid)} title="移除">✕</span>
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
              <span className="svb-rm" onClick={() => removeColumn(priceCol ? priceCol._uid : s._uid)} title="移除整组">✕</span>
            </div>
            {pgMembers.map((m) => <div className="svb-sel-row in-pg" data-role="selected-column" key={m._uid}>{renderSelRowBody(m)}</div>)}
          </div>,
        );
        return;
      }
      const dropCls = dropIndicator?.uid === s._uid ? ` drop-${dropIndicator.pos}` : '';
      nodes.push(
        <div key={s._uid} className={`svb-sel-row${dropCls}`} data-role="selected-column" draggable onDragStart={(e) => handleRowDragStart(e, s._uid)} onDragEnd={() => setDropIndicator(null)} onDragOver={(e) => handleBlockDragOver(e, s._uid)} onDrop={(e) => handleBlockDrop(e, s._uid)}>
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
          <span className="svb-lbl">页签类型</span>
          <Select size="small" style={{ width: 140 }} value={tabType} onChange={handleTabTypeChange} options={(fieldTree?.availableTabTypes ?? TAB_TYPES as unknown as string[]).map((t) => ({ value: t, label: TAB_TYPE_LABEL[t] ?? t }))} />
          {fieldTree?.variants && fieldTree.variants.length > 0 && (
            <>
              <span className="svb-lbl" style={{ marginLeft: 14 }}>数据来源</span>
              <Select size="small" style={{ width: 200 }} value={variantKey ?? fieldTree.variants[0].key} onChange={handleVariantChange} options={fieldTree.variants.map((v) => ({ value: v.key, label: v.label }))} />
              <span className="svb-hint-i">{fieldTree.variants.find((v) => v.key === (variantKey ?? fieldTree.variants![0].key))?.hint}</span>
            </>
          )}
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
          <div className="svb-pane-b">{fieldTree?.groups.map((g) => renderGroup(g))}</div>
        </div>
        <div className="svb-pane right">
          <div className="svb-pane-h"><b>已选输出列</b></div>
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
