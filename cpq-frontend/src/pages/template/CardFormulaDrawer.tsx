/**
 * CardFormulaDrawer — 卡片引用可视化选择器 + 公式编辑器
 *
 * 用于 CARD_FORMULA 类型列的公式编辑:
 *  - 引用页签小计 / 字段首行 / 字段按条件 / 聚合
 *  - 可视化条件构建 (JEXL 算符)
 *  - 别名自动生成 (genAlias)
 *  - 光标插入占位文本
 *  - 底部 display_format 配置
 *  - 保存时调 validateCardFormula 校验
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  Drawer, Button, Form, Input, Select, Radio, Space, Switch, InputNumber,
  Divider, Typography, Tag, message, Spin, Tooltip, Alert, Segmented,
} from 'antd';
import { PlusOutlined, DeleteOutlined, FunctionOutlined } from '@ant-design/icons';
import { templateService } from '../../services/templateService';
import { componentService } from '../../services/componentService';
import { quotationService } from '../../services/quotationService';
import { genAlias, expandIn, validateCardFormula, buildCondRows, parseCondToRows, nextAggRefKey } from './cardFormula';
// CardRefSpec 是纯类型，必须 import type（否则 Vite/esbuild 运行时 ESM 链接报错 → 整个 SPA 白屏）
import type { CardRefSpec, CondRhsType, CondRowSpec } from './cardFormula';
import { CARD_OPERATIONS, opToRefType, refTypeToOp } from './cardFormulaOps';

const { Text, Paragraph } = Typography;

// ─── 外部契约类型 ────────────────────────────────────────────────────────────

export interface ExcelViewColumn {
  col_key: string;
  title: string;
  source_type: 'CARD_FORMULA' | string;
  formula?: string;
  refs?: Record<string, CardRefSpec>;
  display_format?: { type?: 'PERCENT' | 'NUMBER'; decimals?: number };
}

export interface CardFormulaDrawerProps {
  open: boolean;
  templateId: string;
  allColKeys: string[];
  allFormulas: Record<string, string>;
  value: ExcelViewColumn;
  onSave: (patch: {
    formula: string;
    refs: Record<string, CardRefSpec>;
    display_format?: { type?: 'PERCENT' | 'NUMBER'; decimals?: number };
  }) => void;
  onClose: () => void;
  dryRunQuotationId?: string;
  /** col_key → source_type，用于「本行卡片公式列」RHS 下拉只列 CARD_FORMULA 列。 */
  colSourceTypes?: Record<string, string>;
}

// ─── 内部类型 ────────────────────────────────────────────────────────────────

type RefType = 'subtotal' | 'first_row' | 'row_where' | 'aggregate';

type AggFunc = 'SUM' | 'AVG' | 'COUNT' | 'MIN' | 'MAX';

type CondOperator =
  | 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in';

type CondLogic = 'and' | 'or';

interface CondRow {
  field: string;
  op: CondOperator;
  value: string;      // 文本，IN 时是逗号分隔；product/column 时是字段名/列号
  logic: CondLogic;   // 与下一行的连接符（最后一行不用）
  rhsType: CondRhsType; // 值来源：字面量 / 本行产品字段 / 本行卡片公式列
}

interface TabInfo {
  tabKey: string;       // componentId:sortOrder
  tabName: string;
  componentId: string;
  sortOrder: number;
  fields: string[];     // 中文字段名列表
}

// ─── 工具函数 ────────────────────────────────────────────────────────────────

function opLabel(op: CondOperator): string {
  const MAP: Record<CondOperator, string> = {
    eq: '等于', ne: '不等', gt: '大于', gte: '不小于',
    lt: '小于', lte: '不大于', in: '包含IN',
  };
  return MAP[op] ?? op;
}

function opToJexl(op: CondOperator): string {
  const MAP: Record<CondOperator, string> = {
    eq: '==', ne: '!=', gt: '>', gte: '>=', lt: '<', lte: '<=', in: 'in',
  };
  return MAP[op] ?? '==';
}

/** 从 cond 行列表生成 JEXL 条件字符串（用别名） */
function buildCondJexl(conds: CondRow[], fieldToAlias: Record<string, string>): string {
  if (!conds.length) return '';
  const parts: string[] = [];
  for (let i = 0; i < conds.length; i++) {
    const c = conds[i];
    if (!c.field) continue;
    const alias = fieldToAlias[c.field] ?? c.field;
    let expr: string;
    if (c.op === 'in') {
      const vals = c.value.split(',').map(v => v.trim()).filter(Boolean);
      expr = vals.length ? expandIn(alias, vals) : `${alias}=='__IN_EMPTY__'`;
    } else {
      const valLiteral = isNaN(Number(c.value)) ? `'${c.value}'` : c.value;
      expr = `${alias}${opToJexl(c.op)}${valLiteral}`;
    }
    if (i < conds.length - 1) {
      const logic = c.logic === 'or' ? '||' : '&&';
      parts.push(`${expr} ${logic} `);
    } else {
      parts.push(expr);
    }
  }
  return parts.join('').trim();
}

/** 从字段数组和行内表达式提取唯一中文字段（保序），返回别名映射 */
function buildAliasMap(fields: string[]): Record<string, string> {
  const seen: string[] = [];
  for (const f of fields) {
    if (f && !seen.includes(f)) seen.push(f);
  }
  const out: Record<string, string> = {};
  seen.forEach((f, i) => { out[f] = genAlias(i); });
  return out;
}

/** 把行内表达式里的中文字段替换成别名 */
function replaceFieldsWithAlias(expr: string, aliasMap: Record<string, string>): string {
  let result = expr;
  // 按字段名长度降序替换，避免短名匹配到长名的子串
  const entries = Object.entries(aliasMap).sort((a, b) => b[0].length - a[0].length);
  for (const [field, alias] of entries) {
    result = result.split(field).join(alias);
  }
  return result;
}

/** 根据现有 refs 推断 tabKey -> displayName 映射（方便反显） */
function tabKeyFromRefKey(refKey: string, tabs: TabInfo[]): TabInfo | undefined {
  // ref key 是占位文本（如"投料.小计"）里的 tabName，需要在 tabs 里找
  return tabs.find(t => t.tabName === refKey || t.tabKey === refKey);
}

// ─── 占位文本 & ref 生成 ────────────────────────────────────────────────────

interface InsertResult {
  placeholder: string;    // 插入到公式的占位文本（不含外层括号）
  refKey: string;         // refs 的 key（与 placeholder 内文本一致）
  ref: CardRefSpec;
}

function buildInsertResult(
  refType: RefType,
  tab: TabInfo,
  field: string,
  conds: CondRow[],
  aggFunc: AggFunc,
  aggExpr: string,          // 用户填的行内别名表达式
  aggRefKey: string,        // 聚合唯一 refKey（页签名#N，由 handleInsertRef 算好传入）
): InsertResult | null {
  const tabName = tab.tabName;

  if (refType === 'subtotal') {
    const placeholder = `${tabName}.小计`;
    return {
      placeholder,
      refKey: placeholder,
      ref: { tab: tab.tabKey, field: '__subtotal__' },
    };
  }

  if (refType === 'first_row') {
    if (!field) return null;
    const placeholder = `${tabName}.${field}`;
    return {
      placeholder,
      refKey: placeholder,
      ref: { tab: tab.tabKey, field, mode: 'FIRST_ROW' },
    };
  }

  if (refType === 'row_where') {
    if (!field) return null;
    const usedFields = conds.filter(c => c.field).map(c => c.field);
    const aliasMap = buildAliasMap(usedFields);
    const cols: Record<string, string> = {};
    for (const [f, a] of Object.entries(aliasMap)) cols[a] = f;
    const condRows = buildCondRows(conds);
    // 仅当全部 RHS=字面量时才生成旧式 cond（向后兼容 + 占位展示）；含动态 RHS 时 cond 留空，后端用 condRows
    const allLiteral = condRows.every(c => c.rhs.type === 'literal');
    const cond = allLiteral ? buildCondJexl(conds, aliasMap) : '';
    const hasDynamic = condRows.length > 0 && !allLiteral;
    const condSummary = condRows.length ? (hasDynamic ? '动态条件' : '条件') : '无条件';
    const placeholder = `${tabName}.${field}(${condSummary})`;
    return {
      placeholder,
      refKey: placeholder,
      ref: { tab: tab.tabKey, field, mode: 'ROW_WHERE', cond, cols, condRows },
    };
  }

  if (refType === 'aggregate') {
    // 聚合：收集 conds 里的字段 + aggExpr 里的字段（用于别名映射）
    const usedFieldsInConds = conds.filter(c => c.field).map(c => c.field);
    // aggExpr 里的中文字段：取 tab.fields 里出现在 aggExpr 里的
    const usedFieldsInExpr = tab.fields.filter(f => aggExpr.includes(f));
    const allUsedFields = [...usedFieldsInConds, ...usedFieldsInExpr].filter(Boolean);
    const aliasMap = buildAliasMap(allUsedFields);
    const aliasExpr = replaceFieldsWithAlias(aggExpr, aliasMap);
    const cols: Record<string, string> = {};
    for (const [f, a] of Object.entries(aliasMap)) cols[a] = f;

    const refKey = aggRefKey || tabName; // 唯一 key（页签名#N）
    const condRows = buildCondRows(conds);
    const hasDynamic = condRows.length > 0 && condRows.some(c => c.rhs.type !== 'literal');
    if (hasDynamic) {
      // 动态：省略公式 WHERE（条件存 condRows，后端按本产品行算谓词）
      const placeholder = `${aggFunc}_OVER([${refKey}], ${aliasExpr || '1'})`;
      return { placeholder, refKey, ref: { tab: tab.tabKey, cols, condRows } };
    }
    // 全字面量：保持 WHERE 烤入公式（行为不变）
    const condJexl = buildCondJexl(conds, aliasMap);
    const condPart = condJexl ? ` WHERE ${condJexl}` : '';
    const placeholder = `${aggFunc}_OVER([${refKey}]${condPart}, ${aliasExpr || '1'})`;
    return { placeholder, refKey, ref: { tab: tab.tabKey, cols } };
  }

  return null;
}

// ─── 组件 ───────────────────────────────────────────────────────────────────

const COND_OP_OPTIONS: { label: string; value: CondOperator }[] = [
  { label: '等于', value: 'eq' },
  { label: '不等', value: 'ne' },
  { label: '大于', value: 'gt' },
  { label: '不小于', value: 'gte' },
  { label: '小于', value: 'lt' },
  { label: '不大于', value: 'lte' },
  { label: '包含IN', value: 'in' },
];

const AGG_FUNC_OPTIONS: { label: string; value: AggFunc }[] = [
  { label: 'SUM（求和）', value: 'SUM' },
  { label: 'AVG（平均）', value: 'AVG' },
  { label: 'COUNT（计数）', value: 'COUNT' },
  { label: 'MIN（最小）', value: 'MIN' },
  { label: 'MAX（最大）', value: 'MAX' },
];

const DEFAULT_COND_ROW: CondRow = { field: '', op: 'eq', value: '', logic: 'and', rhsType: 'literal' };

const CardFormulaDrawer: React.FC<CardFormulaDrawerProps> = ({
  open,
  templateId,
  allColKeys,
  allFormulas,
  value,
  onSave,
  onClose,
  dryRunQuotationId,
  colSourceTypes,
}) => {
  // ── 公式状态 ─────────────────────────────────────────────────────
  const [formula, setFormula] = useState<string>(value.formula || '');
  const [refs, setRefs] = useState<Record<string, CardRefSpec>>(value.refs || {});
  const [displayFormat, setDisplayFormat] = useState<{ type?: 'PERCENT' | 'NUMBER'; decimals?: number }>(
    value.display_format || {},
  );

  // ── 页签加载 ─────────────────────────────────────────────────────
  const [tabs, setTabs] = useState<TabInfo[]>([]);
  const [tabsLoading, setTabsLoading] = useState(false);

  // ── 插入引用面板状态 ─────────────────────────────────────────────
  const [selTabKey, setSelTabKey] = useState<string>('');
  const [refType, setRefType] = useState<RefType>('subtotal');
  const [selField, setSelField] = useState<string>('');
  const [conds, setConds] = useState<CondRow[]>([{ ...DEFAULT_COND_ROW }]);
  const [aggFunc, setAggFunc] = useState<AggFunc>('SUM');
  const [aggExpr, setAggExpr] = useState<string>('');
  // 正在编辑回填的 ref key（点标签回填时设置）；为 null 表示新建插入
  const [editingRefKey, setEditingRefKey] = useState<string | null>(null);

  // ── 简单/高级模式 ────────────────────────────────────────────────
  const [mode, setMode] = useState<'simple' | 'advanced'>('simple');

  // ── 配置说明展开态 ───────────────────────────────────────────────
  const [showHelp, setShowHelp] = useState(false);

  // ── 试算状态 ─────────────────────────────────────────────────────
  const [trial, setTrial] = useState<{ loading?: boolean; rows?: any[]; err?: string }>({});

  // ── TextArea ref (光标插入) ──────────────────────────────────────
  const textAreaRef = useRef<any>(null);

  // ── Drawer 打开时，重置状态并加载页签 ───────────────────────────
  useEffect(() => {
    if (!open) return;
    setFormula(value.formula || '');
    setRefs(value.refs || {});
    setDisplayFormat(value.display_format || {});
    setSelTabKey('');
    setRefType('subtotal');
    setSelField('');
    setConds([{ ...DEFAULT_COND_ROW }]);
    setAggFunc('SUM');
    setAggExpr('');
    setEditingRefKey(null);
    setTrial({});
    loadTabs();
  }, [open, templateId]); // eslint-disable-line react-hooks/exhaustive-deps

  const loadTabs = useCallback(async () => {
    setTabsLoading(true);
    try {
      const res = await templateService.listComponents(templateId);
      const tcList: any[] = res.data || [];
      // 每个 tc 含 componentId / sortOrder / tabName / fields?
      // 若 fields 不在 tc 里，则 getById component 补充
      const result: TabInfo[] = [];
      await Promise.all(
        tcList.map(async (tc: any) => {
          let fields: string[] = [];
          if (Array.isArray(tc.fields) && tc.fields.length > 0) {
            fields = tc.fields.map((f: any) => f.field_name || f.name || '');
          } else if (Array.isArray(tc.fieldsOverride) && tc.fieldsOverride.length > 0) {
            fields = (tc.fieldsOverride as any[]).map((f: any) => f.field_name || f.name || '');
          } else {
            // 从 component 详情获取
            try {
              const compRes = await componentService.getById(tc.componentId);
              const comp = compRes.data;
              const rawFields: any[] = Array.isArray(comp?.fields) ? comp.fields : [];
              fields = rawFields.map((f: any) => f.field_name || f.name || '').filter(Boolean);
            } catch {
              fields = [];
            }
          }
          result.push({
            tabKey: `${tc.componentId}:${tc.sortOrder}`,
            tabName: tc.tabName || tc.name || '页签',
            componentId: tc.componentId,
            sortOrder: tc.sortOrder ?? 0,
            fields,
          });
        }),
      );
      result.sort((a, b) => a.sortOrder - b.sortOrder);
      setTabs(result);
      if (result.length > 0 && !selTabKey) {
        setSelTabKey(result[0].tabKey);
      }
    } catch (e: any) {
      message.error('加载页签列表失败：' + (e.message || '未知错误'));
    } finally {
      setTabsLoading(false);
    }
  }, [templateId]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── 当前选中页签 ─────────────────────────────────────────────────
  const selTab = tabs.find(t => t.tabKey === selTabKey);

  // ── 光标插入函数 ─────────────────────────────────────────────────
  const insertAtCursor = useCallback((token: string) => {
    const el = textAreaRef.current?.resizableTextArea?.textArea as HTMLTextAreaElement | undefined;
    if (!el) {
      setFormula(prev => (prev || '') + token);
      return;
    }
    const start = el.selectionStart ?? formula.length;
    const end = el.selectionEnd ?? formula.length;
    const next = formula.slice(0, start) + token + formula.slice(end);
    setFormula(next);
    requestAnimationFrame(() => {
      el.focus();
      const pos = start + token.length;
      el.setSelectionRange(pos, pos);
    });
  }, [formula]);

  // 把已有 ROW_WHERE ref 回填到构建器（编辑）。condRows 优先；缺则反解析旧 cond。
  const loadRefIntoBuilder = (refKey: string, ref: CardRefSpec) => {
    if (ref.mode !== 'ROW_WHERE') {
      message.info('仅「字段·按条件取行」引用可回填编辑；其它类型请删除后重建');
      return;
    }
    setSelTabKey(ref.tab);
    setRefType('row_where');
    setSelField(ref.field || '');
    const cols = ref.cols || {};
    const rows: CondRowSpec[] = (ref.condRows && ref.condRows.length)
      ? ref.condRows
      : parseCondToRows(ref.cond || '', cols);
    setConds(
      rows.length
        ? rows.map(r => ({
            field: r.left, op: r.op as CondOperator, value: r.rhs?.value ?? '',
            logic: r.logic as CondLogic, rhsType: r.rhs?.type ?? 'literal',
          }))
        : [{ ...DEFAULT_COND_ROW }],
    );
    setEditingRefKey(refKey);
    message.success(`已载入引用「${refKey}」到下方构建器，可编辑后重新插入（同名覆盖）`);
  };

  // ── 插入引用 ─────────────────────────────────────────────────────
  const handleInsertRef = () => {
    if (!selTab) {
      message.warning('请先选择页签');
      return;
    }
    if (refType === 'row_where' || refType === 'aggregate') {
      // spec §6：rhs.type=product 字段非空；op=in 值非空（column 已由下拉约束为 CARD_FORMULA 列）
      const bad = conds.filter(c => c.field).find(c =>
        (c.rhsType === 'product' && !c.value) ||
        (c.rhsType === 'column' && !c.value) ||
        (c.rhsType === 'literal' && c.op === 'in' && !c.value.trim()));
      if (bad) { message.warning(`条件「${bad.field}」的值未填完整`); return; }
    }
    // 聚合用唯一 refKey 页签名#N（同页签多聚合不冲突；行内插入故 editingRefKey 恒为 null）
    const aggRefKey = refType === 'aggregate'
      ? nextAggRefKey(selTab.tabName, Object.keys(refs))
      : '';
    const result = buildInsertResult(refType, selTab, selField, conds, aggFunc, aggExpr, aggRefKey);
    if (!result) {
      message.warning('请补全引用信息（字段不能为空）');
      return;
    }
    // 把占位文本写入公式：聚合 placeholder 本身已是完整 SUM_OVER(...) 调用，不能再包 []；
    // 其余(小计/字段首行/按条件)是裸引用 token，需要包 [] 成占位。
    const token = refType === 'aggregate' ? result.placeholder : `[${result.placeholder}]`;
    if (editingRefKey) {
      // 编辑模式：替换公式里旧占位 + 改写 refs（key 变了则删旧增新）
      const oldToken = `[${editingRefKey}]`;
      setFormula(prev => prev.includes(oldToken) ? prev.split(oldToken).join(token) : prev + token);
      setRefs(prev => {
        const n = { ...prev };
        if (editingRefKey !== result.refKey) delete n[editingRefKey];
        n[result.refKey] = result.ref;
        return n;
      });
      setEditingRefKey(null);
      message.success(`已更新引用：${token}`);
    } else {
      insertAtCursor(token);
      // 把 ref 写入 refs 状态
      setRefs(prev => ({ ...prev, [result.refKey]: result.ref }));
      message.success(`已插入引用：${token}`);
    }
  };

  // ── 插入函数字面量 ───────────────────────────────────────────────
  const FUNC_SNIPPETS = [
    { label: 'IF(,,)', value: 'IF(条件, 真值, 假值)' },
    { label: 'ROUND(,2)', value: 'ROUND(值, 2)' },
    { label: 'ABS()', value: 'ABS(值)' },
    { label: '12%', value: '0.12' },
    { label: '+ - * /', value: ' + ' },
  ];

  // ── 条件行操作 ───────────────────────────────────────────────────
  const addCondRow = () => setConds(prev => [...prev, { ...DEFAULT_COND_ROW }]);
  const removeCondRow = (i: number) => setConds(prev => prev.filter((_, idx) => idx !== i));
  const updateCondRow = (i: number, patch: Partial<CondRow>) =>
    setConds(prev => prev.map((c, idx) => (idx === i ? { ...c, ...patch } : c)));

  // ── 试算 ─────────────────────────────────────────────────────────
  const handleTrial = async () => {
    const errs = validateCardFormula(
      { col_key: value.col_key, formula, refs },
      allColKeys,
      { ...allFormulas, [value.col_key]: formula },
    );
    if (errs.length) { message.error('请先修正：' + errs.join('；')); return; }
    if (!dryRunQuotationId) {
      message.warning('当前入口无样例报价单，无法试算（可在报价单内编辑时使用）');
      return;
    }
    setTrial({ loading: true });
    try {
      const col = { col_key: value.col_key, title: value.title, source_type: 'CARD_FORMULA', formula, refs };
      const resp: any = await quotationService.dryRunExcelView(dryRunQuotationId, { templateId, columns: [col] });
      const body = resp?.data ?? resp;
      setTrial({ rows: Array.isArray(body?.rows) ? body.rows : [] });
    } catch (e: any) {
      setTrial({ err: e?.message || '试算失败' });
    }
  };

  // ── 保存 ─────────────────────────────────────────────────────────
  const handleSave = () => {
    const errs = validateCardFormula(
      { col_key: value.col_key, formula, refs },
      allColKeys,
      { ...allFormulas, [value.col_key]: formula },
    );
    if (errs.length > 0) {
      message.error(
        <ul style={{ margin: 0, paddingLeft: 20 }}>
          {errs.map((e, i) => <li key={i}>{e}</li>)}
        </ul>,
        6,
      );
      return;
    }
    onSave({ formula, refs, display_format: displayFormat });
    onClose();
  };

  // ── 渲染 ─────────────────────────────────────────────────────────
  const fieldOptions = (selTab?.fields || []).map(f => ({ label: f, value: f }));

  // RHS=产品字段 候选：所有页签 fields 并集 + 料号(__partNo__)（决策A：纯前端拼，不含组件外属性）
  const productFieldOptions = (() => {
    const seen = new Set<string>();
    const opts: { label: string; value: string }[] = [{ label: '料号(__partNo__)', value: '__partNo__' }];
    for (const t of tabs) for (const f of t.fields) {
      if (f && !seen.has(f)) { seen.add(f); opts.push({ label: f, value: f }); }
    }
    return opts;
  })();

  // RHS=本行卡片公式列 候选：其它 CARD_FORMULA 列（按 colSourceTypes 判定）
  const cardColumnOptions = allColKeys
    .filter(k => k !== value.col_key && colSourceTypes?.[k] === 'CARD_FORMULA')
    .map(k => ({ label: `[${k}]`, value: k }));

  const needCondBuilder = refType === 'row_where' || refType === 'aggregate';

  return (
    <Drawer
      title={
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
          <span>{`编辑卡片公式 — 列 ${value.col_key}（${value.title}）`}</span>
          <Segmented
            size="small"
            value={mode}
            onChange={(v) => {
              const next = v as 'simple' | 'advanced';
              setMode(next);
              if (next === 'simple' && refType === 'aggregate' && aggFunc !== 'SUM') {
                setAggFunc('SUM');
              }
            }}
            options={[{ label: '简单', value: 'simple' }, { label: '高级', value: 'advanced' }]}
          />
        </div>
      }
      placement="right"
      width={960}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Button onClick={onClose} style={{ marginRight: 8 }}>取消</Button>
          <Button
            onClick={handleTrial}
            loading={trial.loading}
            disabled={!dryRunQuotationId}
            title={!dryRunQuotationId ? '试算需在报价单内编辑公式时使用' : undefined}
            style={{ marginRight: 8 }}
          >
            试算
          </Button>
          <Button type="primary" onClick={handleSave}>保存</Button>
        </div>
      }
    >
      <div style={{ display: 'flex', gap: 24, flexDirection: 'column' }}>
        {/* ── 公式输入区 ── */}
        <div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
            <Text strong>公式表达式</Text>
            <Space size={4} wrap>
              {FUNC_SNIPPETS.map(s => (
                <Tag
                  key={s.label}
                  color="purple"
                  style={{ cursor: 'pointer', userSelect: 'none' }}
                  icon={<FunctionOutlined />}
                  onClick={() => insertAtCursor(s.value)}
                >
                  {s.label}
                </Tag>
              ))}
            </Space>
          </div>
          <Input.TextArea
            ref={textAreaRef}
            rows={4}
            value={formula}
            onChange={e => setFormula(e.target.value)}
            placeholder="公式示例：[投料.小计] + [加工.小计]  或  SUM_OVER([投料] WHERE c0=='镀铜', c0*c1)"
            style={{ fontFamily: 'Consolas, Monaco, monospace', fontSize: 13 }}
          />
          <Paragraph type="secondary" style={{ marginTop: 6, marginBottom: 0, fontSize: 12 }}>
            占位语法：
            <code>[页签名.小计]</code>（小计）、
            <code>[页签名.字段名]</code>（首行/按条件单值）、
            <code>SUM_OVER([页签名] WHERE 条件, 行内别名表达式)</code>（聚合）；
            裸 <code>[A]</code> 引用本表其他列。{mode === 'advanced' && <>WHERE/cond 用 JEXL 算符（<code>==  !=  &gt;  &lt;  &amp;&amp;  ||</code>）。</>}
          </Paragraph>

          {/* ── 配置说明（可展开）── */}
          <a
            onClick={() => setShowHelp(v => !v)}
            style={{ fontSize: 12, display: 'inline-block', marginTop: 6 }}
          >
            📖 公式配置说明与示例（{showHelp ? '收起' : '点击展开'}）
          </a>
          {showHelp && (
            <div
              style={{
                marginTop: 8, padding: '12px 14px', background: '#f6f8fa',
                border: '1px solid #e4e8ee', borderRadius: 6, fontSize: 12, lineHeight: 1.9,
              }}
            >
              <div style={{ fontWeight: 600, marginBottom: 4 }}>① 引用产品卡片"页签"的值</div>
              <div>· 页签小计：<code>[投料.小计]</code></div>
              <div>· 字段（取该页签首行）：<code>[加工.加工费]</code></div>
              <div>· 字段（按条件取某一行）：<code>[加工.加工费(工序=镀铜)]</code></div>
              <div style={{ color: '#888' }}>　以上都用下方「插入卡片引用」面板点选生成，无需手写中文字段名。</div>

              <div style={{ fontWeight: 600, margin: '8px 0 4px' }}>② 函数</div>
              <div>· 条件分支：<code>IF([投料.小计] &gt; 100, [A]*0.9, [A])</code></div>
              <div>· 四舍五入：<code>ROUND([A], 2)</code>（保留 2 位）</div>
              <div>· 绝对值：<code>ABS([A] - [B])</code></div>
              <div>· 百分比字面量：<code>[A] * 12%</code>（<code>12%</code> = 0.12）</div>

              <div style={{ fontWeight: 600, margin: '8px 0 4px' }}>③ 聚合（对页签内所有行求和等）</div>
              <div>· 语法：<code>SUM_OVER([页签] WHERE 条件, 行内表达式)</code></div>
              <div>· 也支持 <code>AVG_OVER / COUNT_OVER / MIN_OVER / MAX_OVER</code></div>
              <div>· 行内表达式可逐行计算：<code>SUM_OVER([加工] WHERE c0=='镀铜', c1*c2)</code></div>
              <div style={{ color: '#888' }}>　选「聚合」类型后，条件与字段同样在下方面板点选，系统自动把中文字段转成别名 c0/c1…</div>

              <div style={{ fontWeight: 600, margin: '8px 0 4px' }}>④ 过滤条件（WHERE / 按条件取行）</div>
              <div>· 比较：等于 <code>==</code>、不等 <code>!=</code>、大于 <code>&gt;</code>、不大于 <code>&lt;=</code>、小于 <code>&lt;</code></div>
              <div>· 多值包含（IN）：选「包含IN」，值用逗号分隔 <code>镀铜,镀镍</code> → 自动生成 <code>(c0=='镀铜' || c0=='镀镍')</code></div>
              <div>· 多条件组合：行间用「且」<code>&amp;&amp;</code> / 「或」<code>||</code></div>
              <div>· 示例：<code>工序=='镀铜' &amp;&amp; 数量&gt;0</code></div>

              <div style={{ fontWeight: 600, margin: '8px 0 4px' }}>⑤ 引用本表其他列</div>
              <div>· 用列号：<code>[A] * 1.13</code>（A 是同表其它列的列号；支持列间引用，系统自动按依赖顺序计算）</div>

              <div style={{ fontWeight: 600, margin: '8px 0 4px' }}>⑥ 综合示例</div>
              <div style={{ fontFamily: 'Consolas, Monaco, monospace', background: '#fff', padding: '6px 8px', border: '1px dashed #ccc', borderRadius: 4 }}>
                =ROUND( ([投料.小计] + SUM_OVER([加工] WHERE (c0=='镀铜' || c0=='镀镍'), c1)) * 12%, 2 )
              </div>
              <div style={{ color: '#888', marginTop: 4 }}>
                含义：投料页签小计 + 加工页签中工序∈{'{'}镀铜,镀镍{'}'}的行的加工费之和，乘 12%，结果保留 2 位小数。
              </div>

              <div style={{ fontWeight: 600, margin: '8px 0 4px' }}>⑦ 空值规则</div>
              <div>· 单值引用为空 / 页签 0 行 → 显示「—」；聚合 0 行命中 → 按 0 计；公式中个别引用为空 → 当 0 继续算，全部为空才显示「—」。</div>

              <div style={{ color: '#c41d7f', marginTop: 8 }}>
                ⚠️ 小贴士：条件/聚合里**不要手写中文字段名**（公式引擎不识别中文标识符）。请用下方「插入卡片引用」面板点选字段，系统会自动生成稳定别名（c0/c1…）。改字段中文显示名不会影响已配公式。
              </div>
            </div>
          )}
        </div>

        {/* ── 试算结果 ── */}
        {trial.err && (
          <Alert type="error" showIcon style={{ marginTop: 8 }} message={'试算失败：' + trial.err} />
        )}
        {trial.rows && (
          <div style={{ marginTop: 8, fontSize: 12 }}>
            <Text strong>试算结果（{value.col_key} 列各行）：</Text>
            {trial.rows.length === 0 && <span style={{ color: '#999' }}> 无数据行</span>}
            {trial.rows.map((r, i) => {
              const v = r[value.col_key];
              const isErr = typeof v === 'string' && v.startsWith('#ERROR');
              return (
                <div key={i} style={{ fontFamily: 'monospace', color: isErr ? '#c41d7f' : '#1677ff' }}>
                  行{i + 1}: {v === null || v === undefined || v === '' ? '—' : String(v)}
                </div>
              );
            })}
          </div>
        )}

        {/* ── 当前 refs 预览 ── */}
        {Object.keys(refs).length > 0 && (
          <div>
            <Text strong style={{ marginBottom: 8, display: 'block' }}>
              已定义引用 refs
              <Text type="secondary" style={{ fontSize: 12, fontWeight: 400, marginLeft: 8 }}>
                （点标签可回填到下方构建器编辑；× 删除）
              </Text>
            </Text>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {Object.entries(refs).map(([key, ref]) => (
                <Tooltip
                  key={key}
                  title={
                    <div style={{ fontSize: 12 }}>
                      <div>tab: {ref.tab}</div>
                      {ref.field && <div>field: {ref.field}</div>}
                      {ref.mode && <div>mode: {ref.mode}</div>}
                      {ref.cond && <div>cond: {ref.cond}</div>}
                      {ref.cols && <div>cols: {JSON.stringify(ref.cols)}</div>}
                    </div>
                  }
                >
                  <Tag
                    closable
                    color="blue"
                    style={{ cursor: 'pointer' }}
                    onClick={() => loadRefIntoBuilder(key, ref)}
                    onClose={() => setRefs(prev => { const n = { ...prev }; delete n[key]; return n; })}
                  >
                    {key}
                  </Tag>
                </Tooltip>
              ))}
            </div>
          </div>
        )}

        <Divider style={{ margin: '4px 0' }} />

        {/* ── 插入引用面板 ── */}
        <div>
          <Text strong style={{ marginBottom: 12, display: 'block', fontSize: 14 }}>
            插入卡片引用
          </Text>
          <Spin spinning={tabsLoading}>
            <Form layout="vertical" size="small">
              {/* 页签选择 */}
              <Form.Item label="选择页签">
                <Select
                  style={{ width: 280 }}
                  placeholder="请选择页签"
                  value={selTabKey || undefined}
                  onChange={v => { setSelTabKey(v); setSelField(''); setConds([{ ...DEFAULT_COND_ROW }]); }}
                  options={tabs.map(t => ({
                    label: `${t.tabName}（${t.fields.length} 个字段）`,
                    value: t.tabKey,
                  }))}
                  notFoundContent="暂无页签"
                />
              </Form.Item>

              {/* 引用类型 */}
              <Form.Item label="引用类型">
                {mode === 'simple' ? (
                  <Select
                    style={{ width: 280 }}
                    value={refTypeToOp(refType, aggFunc)}
                    onChange={(opKey) => {
                      const { refType: rt, aggFunc: af } = opToRefType(opKey);
                      setRefType(rt as RefType);
                      if (af) setAggFunc(af as AggFunc);
                      setSelField('');
                      setConds([{ ...DEFAULT_COND_ROW }]);
                    }}
                    options={CARD_OPERATIONS.filter((o) => o.simple).map((o) => ({ label: o.label, value: o.key }))}
                  />
                ) : (
                  <Radio.Group
                    value={refType}
                    onChange={e => { setRefType(e.target.value); setSelField(''); setConds([{ ...DEFAULT_COND_ROW }]); }}
                  >
                    <Radio.Button value="subtotal">页签小计</Radio.Button>
                    <Radio.Button value="first_row">字段·首行</Radio.Button>
                    <Radio.Button value="row_where">字段·按条件取行</Radio.Button>
                    <Radio.Button value="aggregate">聚合（SUM/AVG…）</Radio.Button>
                  </Radio.Group>
                )}
              </Form.Item>

              {/* 字段选择（非小计时显示） */}
              {refType !== 'subtotal' && refType !== 'aggregate' && (
                <Form.Item label="字段">
                  <Select
                    style={{ width: 220 }}
                    placeholder="请选择字段"
                    value={selField || undefined}
                    onChange={setSelField}
                    options={fieldOptions}
                    notFoundContent={selTab ? '该页签无字段' : '请先选择页签'}
                    showSearch
                    optionFilterProp="label"
                  />
                </Form.Item>
              )}

              {/* 聚合函数 + 行内表达式 */}
              {refType === 'aggregate' && (
                <>
                  {mode === 'advanced' && (
                    <Form.Item label="聚合函数">
                      <Select
                        style={{ width: 200 }}
                        value={aggFunc}
                        onChange={setAggFunc}
                        options={AGG_FUNC_OPTIONS}
                      />
                    </Form.Item>
                  )}
                  <Form.Item
                    label={mode === 'advanced' ? '行内聚合表达式（用中文字段名，别名将自动替换）' : '行内聚合表达式'}
                    tooltip={mode === 'advanced' ? '示例：单价*数量；系统会自动把中文字段名替换成别名（c0/c1...）再写入 cols' : '示例：单价*数量'}
                  >
                    <Input
                      style={{ width: 360, fontFamily: 'monospace' }}
                      placeholder="如：单价*数量  或  1（计数时填1）"
                      value={aggExpr}
                      onChange={e => setAggExpr(e.target.value)}
                    />
                  </Form.Item>
                </>
              )}

              {/* 条件构建器（按条件取行 / 聚合时显示） */}
              {needCondBuilder && (
                <Form.Item label="条件构建器（WHERE 子句）">
                  <div style={{ border: '1px solid #f0f0f0', borderRadius: 6, padding: 12, background: '#fafafa' }}>
                    {conds.map((c, i) => (
                      <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, flexWrap: 'wrap' }}>
                        {/* 逻辑连接符（非第一行时） */}
                        {i > 0 && (
                          <Select
                            size="small"
                            style={{ width: 68 }}
                            value={conds[i - 1].logic}
                            onChange={v => updateCondRow(i - 1, { logic: v as CondLogic })}
                            options={[{ label: '且(&&)', value: 'and' }, { label: '或(||)', value: 'or' }]}
                          />
                        )}
                        {i === 0 && <div style={{ width: 68 }} />}

                        {/* 字段 */}
                        <Select
                          size="small"
                          style={{ width: 150 }}
                          placeholder="字段"
                          value={c.field || undefined}
                          onChange={v => updateCondRow(i, { field: v })}
                          options={fieldOptions}
                          showSearch
                          optionFilterProp="label"
                          notFoundContent={selTab ? '无字段' : '请先选页签'}
                        />

                        {/* 运算符 */}
                        <Select
                          size="small"
                          style={{ width: 100 }}
                          value={c.op}
                          onChange={v => updateCondRow(i, { op: v as CondOperator })}
                          options={COND_OP_OPTIONS}
                        />

                        {/* 值来源 */}
                        <Select
                          size="small"
                          style={{ width: 104 }}
                          value={c.rhsType}
                          onChange={v => updateCondRow(i, { rhsType: v as CondRhsType, value: '' })}
                          options={[
                            { label: '字面量', value: 'literal' },
                            { label: '产品字段', value: 'product' },
                            { label: '本行列', value: 'column' },
                          ]}
                        />

                        {/* 值（按来源渲染） */}
                        {c.rhsType === 'literal' && (
                          <Input
                            size="small"
                            style={{ width: 160 }}
                            placeholder={c.op === 'in' ? '值1,值2,值3' : '值'}
                            value={c.value}
                            onChange={e => updateCondRow(i, { value: e.target.value })}
                          />
                        )}
                        {c.rhsType === 'product' && (
                          <Select
                            size="small"
                            style={{ width: 200 }}
                            placeholder="选产品字段"
                            value={c.value || undefined}
                            onChange={v => updateCondRow(i, { value: v })}
                            options={productFieldOptions}
                            showSearch
                            optionFilterProp="label"
                          />
                        )}
                        {c.rhsType === 'column' && (
                          <Select
                            size="small"
                            style={{ width: 160 }}
                            placeholder="选本行列"
                            value={c.value || undefined}
                            onChange={v => updateCondRow(i, { value: v })}
                            options={cardColumnOptions}
                            notFoundContent="无其它卡片公式列"
                          />
                        )}

                        {/* 删除行 */}
                        <Button
                          size="small"
                          type="text"
                          danger
                          icon={<DeleteOutlined />}
                          onClick={() => removeCondRow(i)}
                          disabled={conds.length <= 1}
                        />
                      </div>
                    ))}
                    <Button size="small" type="dashed" icon={<PlusOutlined />} onClick={addCondRow}>
                      添加条件
                    </Button>

                    {/* 预览生成的条件 */}
                    {(() => {
                      const valid = conds.filter(c => c.field);
                      if (!valid.length) return null;
                      const dynamic = valid.some(c => c.rhsType !== 'literal');
                      const rhsLabel = (c: typeof valid[number]) =>
                        c.rhsType === 'product' ? `本行产品字段[${c.value || '?'}]`
                        : c.rhsType === 'column' ? `本行列[${c.value || '?'}]`
                        : `'${c.value}'`;
                      if (dynamic) {
                        const text = valid
                          .map((c, idx) => `${idx > 0 ? (valid[idx - 1].logic === 'or' ? '或 ' : '且 ') : ''}${c.field} ${opLabel(c.op)} ${rhsLabel(c)}`)
                          .join('  ');
                        return (
                          <div style={{ marginTop: 8, fontSize: 12, color: '#666' }}>
                            <Text type="secondary" style={{ fontSize: 11 }}>动态条件预览：</Text>
                            <code style={{ fontSize: 11, background: '#fff7e6', padding: '2px 6px', borderRadius: 3 }}>
                              {text}
                            </code>
                            <div style={{ marginTop: 4, fontSize: 11, color: '#aaa' }}>
                              动态 RHS 由后端按本产品行求值（料号/产品字段/已算列）
                            </div>
                          </div>
                        );
                      }
                      const usedFields = valid.map(c => c.field);
                      const aliasMap = buildAliasMap(usedFields);
                      const preview = buildCondJexl(conds, aliasMap);
                      if (!preview) return null;
                      if (mode === 'simple') {
                        const simpleText = valid
                          .map((c, idx) => `${idx > 0 ? (valid[idx - 1].logic === 'or' ? '或 ' : '且 ') : ''}${c.field} ${opLabel(c.op)} ${isNaN(Number(c.value)) ? `'${c.value}'` : c.value}`)
                          .join('  ');
                        return (
                          <div style={{ marginTop: 8, fontSize: 12, color: '#666' }}>
                            <Text type="secondary" style={{ fontSize: 11 }}>条件预览：</Text>
                            <code style={{ fontSize: 11, background: '#f5f5f5', padding: '2px 6px', borderRadius: 3 }}>
                              {simpleText}
                            </code>
                          </div>
                        );
                      }
                      return (
                        <div style={{ marginTop: 8, fontSize: 12, color: '#666' }}>
                          <Text type="secondary" style={{ fontSize: 11 }}>生成条件预览：</Text>
                          <code style={{ fontSize: 11, background: '#f5f5f5', padding: '2px 6px', borderRadius: 3 }}>
                            {preview}
                          </code>
                          <div style={{ marginTop: 4, fontSize: 11, color: '#aaa' }}>
                            别名映射：{Object.entries(aliasMap).map(([f, a]) => `${a}=${f}`).join('，')}
                          </div>
                        </div>
                      );
                    })()}
                  </div>
                </Form.Item>
              )}

              {/* 插入按钮 */}
              <Form.Item>
                <Button
                  type="primary"
                  onClick={handleInsertRef}
                  disabled={!selTab}
                >
                  插入到公式光标处
                </Button>
                <Text type="secondary" style={{ marginLeft: 12, fontSize: 12 }}>
                  将在光标位置插入占位文本并注册 ref
                </Text>
              </Form.Item>
            </Form>
          </Spin>
        </div>

        <Divider style={{ margin: '4px 0' }} />

        {/* ── 本表其他列引用（快捷插入） ── */}
        {allColKeys.length > 1 && (
          <div>
            <Text strong style={{ marginBottom: 8, display: 'block' }}>
              快捷插入本表列引用
            </Text>
            <Space size={[6, 6]} wrap>
              {allColKeys
                .filter(k => k !== value.col_key)
                .map(k => (
                  <Tag
                    key={k}
                    color="geekblue"
                    style={{ cursor: 'pointer', userSelect: 'none' }}
                    onClick={() => insertAtCursor(`[${k}]`)}
                  >
                    [{k}]
                  </Tag>
                ))}
            </Space>
          </div>
        )}

        <Divider style={{ margin: '4px 0' }} />

        {/* ── display_format ── */}
        <div>
          <Text strong style={{ marginBottom: 12, display: 'block' }}>显示格式</Text>
          <Form layout="inline" size="small">
            <Form.Item label="百分比显示">
              <Switch
                checked={displayFormat.type === 'PERCENT'}
                onChange={v =>
                  setDisplayFormat(prev => ({ ...prev, type: v ? 'PERCENT' : 'NUMBER' }))
                }
              />
            </Form.Item>
            <Form.Item label="小数位数">
              <InputNumber
                min={0}
                max={10}
                style={{ width: 80 }}
                value={displayFormat.decimals ?? 2}
                onChange={v =>
                  setDisplayFormat(prev => ({ ...prev, decimals: v ?? 2 }))
                }
              />
            </Form.Item>
          </Form>
        </div>
      </div>
    </Drawer>
  );
};

export default CardFormulaDrawer;
