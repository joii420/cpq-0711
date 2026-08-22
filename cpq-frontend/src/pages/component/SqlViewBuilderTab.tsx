// 取数配置器（task-260819）· 「取数配置」Tab
//
// 服务 F-1 ~ F-13（F-14 是自检声明，不在代码里）。任务书：dev-docs/task-260819-取数配置器/fronttask.md
// AC 原文：dev-docs/task-260819-取数配置器/需求文档.md §3。1:1 还原基准：原型图/原型-取数配置器.html
//
// 🚨 api.md §0：编译器只在后端。本文件不实现任何一份 SQL 生成 / 粒度判定逻辑 ——
//    右侧 SQL 面板的文本、粒度条的文案、体检结论、AC-16 拖拽期置灰的冲突标记全部原样取自后端响应
//    （GET /field-tree 带 selectedConfig 时返回 groups[].conflict），前端只读展示、不自行判定。
//    详见 sqlViewBuilderService.ts 顶部注释（含与 api.md §2.1a 的对齐记录）。
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Drawer, Dropdown, Input, Select, Space, message, Modal } from 'antd';
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
const SWITCH_LABEL: Record<string, string> = { includeChildParts: '子件数据也要' };

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
  lookupOf?: string | null;
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
    lookupOf: col.lookupOf ?? null,
    groupLabel: group.groupName,
    groupKind: group.kind,
    raw: group.kind === 'PRICE',
    isCore: group.kind === 'PRICE' ? !!col.isCore : undefined,
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
}

// ── 主组件 ──────────────────────────────────────────────────────────────

const SqlViewBuilderTab: React.FC<SqlViewBuilderTabProps> = ({ componentId, initialTabType, manualFieldOptions, onSaved }) => {
  const [initLoading, setInitLoading] = useState(true);
  /** AC-32：true = 存量手写视图——显示引导页，不进拖拽态。直接取自 GET /builder 的 isLegacyHandwritten（api.md §2.1a）。 */
  const [guideMode, setGuideMode] = useState(false);
  const [hasDriver, setHasDriver] = useState(false);

  const [tabType, setTabType] = useState<string>(TAB_TYPES[0]);
  const [variantKey, setVariantKey] = useState<string | null>(null);
  const [switches, setSwitchesState] = useState<Record<string, boolean>>({});
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

  // ── 初始读取 / 「取消」复用的同一份状态装配逻辑（GET /builder）─────────────
  // 抽成函数是为了「取消」按钮能原样复跑一遍——丢弃本地未保存编辑、回到上次持久化状态，
  // 而不是误调 onSaved（那会让父组件误以为发生了保存）。
  async function loadBuilderState(signal?: { cancelled: boolean }) {
    setInitLoading(true);
    setGuideMode(false);
    setHasDriver(false);
    pendingRehydrateRef.current = null;
    setSel([]);
    setElemKeyOverrideField(null);
    setStaleInfo(null);
    setStaleDismissed(false);
    try {
      const res = await getBuilder(componentId);
      if (signal?.cancelled) return;
      const { builderConfig, isLegacyHandwritten, isStale, currentCompilerVersion, builderVersion, sqlTemplate } = res || ({} as any);
      if (isLegacyHandwritten || !builderConfig) {
        if (isLegacyHandwritten) {
          setGuideMode(true);
          setHasDriver(true);
        } else {
          // 全新组件，尚无任何视图：直接进入拖拽态
          const initT = initialTabType && (TAB_TYPES as readonly string[]).includes(initialTabType) ? initialTabType : TAB_TYPES[0];
          setTabType(initT);
          setVariantKey(null);
          setSwitchesState({});
        }
      } else {
        setHasDriver(true);
        setTabType(builderConfig.tabType);
        setVariantKey(builderConfig.variantKey ?? null);
        setSwitchesState(builderConfig.switches || {});
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
    if (!sel.length) return; // 没有未保存的编辑，无需二次确认
    Modal.confirm({
      title: '放弃未保存的修改？', content: '将丢弃本次编辑，恢复为上次保存的状态。', okText: '放弃修改', okButtonProps: { danger: true }, cancelText: '继续编辑',
      onOk: () => { void loadBuilderState(); },
    });
  }

  // ── 派生：元素键列（价格策略是否启用可由 elemKeyCol 是否存在或 sel.some(s=>s.raw) 反推，此处不再单独存变量）
  const elemKeyCol = sel.find((s) => s.elemKey || s.autoElem);
  const isUsed = (sourceNodeKey: string, sourceColumn: string) => sel.some((s) => s.sourceNodeKey === sourceNodeKey && s.sourceColumn === sourceColumn);

  // ── 编译请求体（BuilderConfigPayload：扁平角色布尔位，见 sqlViewBuilderService.ts 头注） ──
  function buildConfigPayload(): BuilderConfigPayload {
    const payload: BuilderConfigPayload = {
      tabType,
      variantKey,
      switches,
      columns: sel.map((s) => ({
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
    if (elemKeyOverrideField) {
      payload.priceStrategy = { elementCodeSource: 'MANUAL_FIELD', elementCodeField: elemKeyOverrideField };
    }
    return payload;
  }

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
    pendingRehydrateRef.current = null;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldTree]);

  // ── 增删列 ──────────────────────────────────────────────────────────────
  function addColumn(col: FieldTreeColumn, group: FieldTreeGroup) {
    if (isUsed(col.sourceNodeKey, col.sourceColumn)) return;
    const additions: SelColumn[] = [];
    if (group.kind === 'PRICE' && !elemKeyCol) {
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
  function handleRowDragOver(e: React.DragEvent) {
    e.preventDefault();
    e.currentTarget.classList.add('over');
  }
  function handleRowDragLeave(e: React.DragEvent) {
    e.currentTarget.classList.remove('over');
  }
  function handleDropAtUid(e: React.DragEvent, overUid: string | null) {
    e.preventDefault();
    e.currentTarget.classList.remove('over', 'svb-drop-hot');
    const drag = dragRef.current;
    dragRef.current = null;
    if (!drag) return;
    if (drag.type === 'new') {
      if (isUsed(drag.col.sourceNodeKey, drag.col.sourceColumn)) return;
      addColumn(drag.col, drag.group);
      if (overUid) {
        setSel((prev) => {
          const idx = prev.length - 1;
          const item = prev[idx];
          const target = prev.findIndex((x) => x._uid === overUid);
          if (target < 0 || target === idx) return prev;
          const next = prev.slice(0, idx);
          next.splice(target, 0, item);
          return next;
        });
      }
    } else if (drag.type === 'reorder' && overUid && drag.uid !== overUid) {
      setSel((prev) => {
        const from = prev.findIndex((x) => x._uid === drag.uid);
        const to = prev.findIndex((x) => x._uid === overUid);
        if (from < 0 || to < 0) return prev;
        const next = prev.slice();
        const [item] = next.splice(from, 1);
        next.splice(to, 0, item);
        return next;
      });
    } else if (drag.type === 'group' && overUid) {
      setSel((prev) => {
        const groupUids = new Set(prev.filter((s) => s.raw || s.autoElem).map((s) => s._uid));
        if (groupUids.has(overUid)) return prev;
        const groupItems = prev.filter((s) => groupUids.has(s._uid));
        const rest = prev.filter((s) => !groupUids.has(s._uid));
        const overIdx = rest.findIndex((x) => x._uid === overUid);
        if (overIdx < 0) return prev;
        const next = rest.slice();
        next.splice(overIdx, 0, ...groupItems);
        return next;
      });
    }
  }

  // ── tabType / variant / switch 切换：换主源 Sheet → 已选列全部失效 → 二次确认后清空（F-1）───
  function handleTabTypeChange(v: string) {
    if (v === tabType) return;
    const doSwitch = () => {
      setTabType(v);
      setVariantKey(null);
      setSwitchesState({});
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
      setSwitchesState({});
      setSel([]);
      setElemKeyOverrideField(null);
      setFieldTree(null);
      setCollapsed(new Set());
    };
    if (sel.length) {
      Modal.confirm({ title: '切换数据来源会清空已选输出列', content: '继续？', okText: '继续切换', cancelText: '取消', onOk: doSwitch });
    } else doSwitch();
  }
  function toggleSwitch(key: string) {
    setSwitchesState((prev) => {
      const next = { ...prev, [key]: !prev[key] };
      if (key === 'includeChildParts' && !next[key]) {
        // 关闭子件闭包时，closureOnly 列（如「归属料号」）随之失效
        setSel((s) => s.filter((c) => !fieldTree?.groups.some((g) => g.fields.some((fc) => fc.closureOnly && fc.sourceColumn === c.sourceColumn && fc.sourceNodeKey === c.sourceNodeKey))));
      }
      return next;
    });
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
        setInspectResult({ checks: [{ level: 'WARN', message: '体检请求失败：' + (e?.message ?? '未知错误') }] });
      }
    }, 300);
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [componentId, tabType, variantKey, JSON.stringify(switches), elemKeyOverrideField, initLoading, guideMode,
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
    try {
      const res = await saveBuilder(componentId, { ...buildConfigPayload(), confirmedImpact });
      message.success(`保存成功，共 ${sel.length} 列${res.affectedTemplateCount != null ? `，影响 ${res.affectedTemplateCount} 个模板` : ''}`);
      setSel((prev) => prev.map((s) => ({ ...s, origFieldName: s.fieldName })));
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
        message.error('保存失败：' + (e?.message ?? '未知错误'));
      }
    } finally {
      setSaving(false);
    }
  }

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
    if (col.closureOnly && !switches.includeChildParts) return null;
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
        onDoubleClick={draggableAllowed ? () => addColumn(col, group) : undefined}
        title={used ? '已在输出列中' : blocked ? reason : '拖到右侧，或双击添加'}
      >
        <span className="svb-drag">{blocked ? '🚫' : '⋮⋮'}</span>
        <span>{col.displayName}</span>
        {(col.roles || []).map((r) => <span key={r} className="svb-rmark">{ROLE_LABEL[r]}</span>)}
        {col.lookupOf && <span className="svb-lookup-tag">{col.lookupOf}</span>}
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
          {g.kind === 'PRICE' && elemKeyCol && <span className="svb-dim-tag">元素键：{elemKeyCol.fieldName}</span>}
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
          {s.lookupOf && <span className="svb-badge-join">{s.lookupOf}</span>}
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
          onDrop={(e) => handleDropAtUid(e, null)}
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
        nodes.push(
          <div key={s._uid} className="svb-pgrp" draggable onDragStart={(e) => handleGroupDragStart(e, s._uid)} onDragOver={handleRowDragOver} onDragLeave={handleRowDragLeave} onDrop={(e) => handleDropAtUid(e, s._uid)}>
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
      nodes.push(
        <div key={s._uid} className="svb-sel-row" data-role="selected-column" draggable onDragStart={(e) => handleRowDragStart(e, s._uid)} onDragOver={handleRowDragOver} onDragLeave={handleRowDragLeave} onDrop={(e) => handleDropAtUid(e, s._uid)}>
          {renderSelRowBody(s)}
        </div>,
      );
    });
    return nodes;
  }

  // ── 渲染：粒度条（F-4，纯取自 /compile 的 grain，不本地推导）────────────
  const grainText = compileResult ? (compileResult.grain.length ? `成品 + ${compileResult.grain.join(' + ')}` : '每个成品 1 行') : (sel.length ? '（拖拽后重新计算…）' : '每个成品 1 行');

  // ── 渲染：体检区（F-8：只显示阻断/告警，全通过时一行「检查通过」）──────
  function renderHealth() {
    if (!sel.length) return <div className="svb-hitem ok"><span className="ic">✓</span><span>尚未选择输出列</span></div>;
    if (!inspectResult) return <div className="svb-hitem ok"><span className="ic">…</span><span>体检中</span></div>;
    const blocking = inspectResult.checks.filter((it) => it.level !== 'INFO');
    if (!blocking.length) return <div className="svb-hitem ok"><span className="ic">✓</span><span>检查通过</span></div>;
    return blocking.map((it, i) => (
      <div key={i} className={`svb-hitem ${it.level === 'ERR' ? 'err' : 'warn'}`}>
        <span className="ic">{it.level === 'WARN' ? '!' : '✕'}</span>
        <span dangerouslySetInnerHTML={{ __html: it.message }} />
      </div>
    ));
  }
  const errCount = inspectResult?.checks.filter((i) => i.level === 'ERR').length ?? 0;
  const warnCount = inspectResult?.checks.filter((i) => i.level === 'WARN').length ?? 0;
  const canSave = sel.length > 0 && errCount === 0 && !!compileResult && !compileError;

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
        {fieldTree?.switches && fieldTree.switches.length > 0 && (
          <div className="svb-rb-line">
            <span className="svb-lbl">选项</span>
            {fieldTree.switches.map((k) => (
              <label key={k} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, cursor: 'pointer' }} onClick={() => toggleSwitch(k)}>
                <Checkbox checked={!!switches[k]} /> {SWITCH_LABEL[k] ?? k}
              </label>
            ))}
          </div>
        )}
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

      <div className="builder-footer" data-role="builder-actions" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 14 }}>
        <Dropdown menu={{ items: moreMenuItems, onClick: handleMoreMenuClick }} trigger={['click']}>
          <Button>⋯</Button>
        </Dropdown>
        <Space align="center">
          {!canSave && sel.length > 0 && <span className="svb-hint-i">{errCount > 0 ? '存在阻断项，无法保存' : ''}</span>}
          <Button onClick={handleCancel}>取消</Button>
          <Button type="primary" disabled={!canSave} loading={saving} onClick={() => doSave(false)}>保存</Button>
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
};

export default SqlViewBuilderTab;
