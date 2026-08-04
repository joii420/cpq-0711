import React, { useState, useEffect, useRef, useMemo } from 'react';
import {
  Drawer, Button, Space, message, Typography, Tooltip,
  Select, Form, Input, Divider,
} from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { tabJoinFormulaService, type TabDef } from '../../services/tabJoinFormulaService';
import TabFieldPanel from './tabjoin/TabFieldPanel';
import FormulaEditorPanel from './tabjoin/FormulaEditorPanel';
import type { FormulaRichInputHandle } from './tabjoin/FormulaRichInput';
import {
  expressionToTokens,
  tokensToDrawerExpression,
  checkMappable,
  containsTreeToken,
  validateTreeRefWhitelist,
} from '../component/formulaSerialize';
import type { FormulaToken } from '../component/types';
import { checkParenBalance } from './tabjoin/formulaBracketCheck';
import type { ExpressionToken, ConditionPredicate, PredicateOperand } from '../../utils/formulaEngine';
import { serializePredicate } from '../../utils/predicateText';

const { Text } = Typography;
const { Option } = Select;

export type ComponentFormulaType = 'NORMAL' | 'SUBTOTAL' | 'EXCEL';

/**
 * onSave payload — discriminated union by the editing component's type.
 *
 *  - EXCEL 组件：保存为 Excel 视图列定义（TAB_JOIN_FORMULA string column），
 *    {@code column} = buildColumn() 的产物 {source_type, expression, tabs}。
 *  - NORMAL / SUBTOTAL 组件：保存为组件公式 token 数组（FormulaToken[]），
 *    由 expressionToTokens() 从抽屉字符串表达式转换得到。
 *
 * 下游 caller（Task 5.1 ComponentManagement）按 kind 分流落库。
 */
export type TabJoinFormulaSavePayload =
  | { kind: 'excel'; column: any }
  | { kind: 'tokens'; tokens: FormulaToken[] };

interface Props {
  open: boolean;
  /** 正在编辑公式的组件 id（页签集以此组件为作用域） */
  componentId: string;
  /** 组件类型 — 决定保存形态：EXCEL → string column；NORMAL/SUBTOTAL → token[] */
  componentType: ComponentFormulaType;
  /** 本组件行键字段，供跨页签引用构建 match[] 对齐对（仅 token 形态需要） */
  selfRowKeyFields?: string[];
  /**
   * task-0803 F-2：正在编辑公式的组件的页签类型属性(ComponentItem.tabType，与
   * ComponentManagement 表单同源)。父子取值函数（PGET/CSUM/CAVG/CMAX/CMIN/CCOUNT）
   * 仅 BOM 类型页签可用，保存前据此拦截（见 save() 内 checkTreeRefTabTypeGate）。
   * EXCEL 组件不做该项拦截（EXCEL 走 buildColumn 字符串路径，不解析 tree_ref），传或不传均可。
   */
  tabType?: string;
  column: any;
  /**
   * NORMAL/SUBTOTAL 模式下，编辑已有公式时传入原始 FormulaToken[]。
   * 打开时经 tokensToDrawerExpression 转为字符串填入表达式区（SUMIF token 因
   * Phase 2 tokensToDrawerExpression 已支持 predicate→文本，会直接内联进表达式串）。
   */
  initialTokens?: FormulaToken[];
  onClose: () => void;
  onSave: (payload: TabJoinFormulaSavePayload) => void;
}

// ── SUMIF 族函数 ──────────────────────────────────────────────────────────────

type SumifFunc = 'SUMIF' | 'COUNTIF' | 'AVGIF' | 'MINIF' | 'MAXIF';

const FUNC_TO_AGG: Record<SumifFunc, ExpressionToken['agg']> = {
  SUMIF: 'SUM',
  COUNTIF: 'COUNT',
  AVGIF: 'AVG',
  MINIF: 'MIN',
  MAXIF: 'MAX',
};

/**
 * 纯函数：把 SUMIF 向导的用户输入转为带 predicate 的 cross_tab_ref ExpressionToken。
 * match 始终为 []（SUMIF 族通过 predicate 过滤，不依赖行键 match 对齐）。
 * targetExpr 非空时优先于 target 字段，作为聚合表达式。
 */
export function buildSumifToken(input: {
  func: SumifFunc;
  source: string;
  sourceLabel?: string;
  predicate: ConditionPredicate | null;
  valueExprTokens: ExpressionToken[];
}): ExpressionToken {
  return {
    type: 'cross_tab_ref',
    source: input.source,
    sourceLabel: input.sourceLabel,
    agg: FUNC_TO_AGG[input.func],
    match: [],                          // SUMIF 族通过 predicate 过滤；match 留空（全量 + predicate）
    predicate: input.predicate,
    targetExpr: input.valueExprTokens.length > 0 ? input.valueExprTokens : undefined,
  };
}

/**
 * 纯函数：把 SUMIF 构造器输入转为内联文本，供插入表达式框。
 *
 * 产出：
 *   SUMIF([源别名.条件字段] op '值', [源别名.值字段1] + [源别名.值字段2])
 *   COUNTIF([源别名.条件字段] op '值')   ← 无值表达式
 *
 * @param input.func         SUMIF / COUNTIF / AVGIF / MINIF / MAXIF
 * @param input.sourceAlias  来源页签别名（显示在 [...] 里）
 * @param input.hostAlias    宿主页签别名（predicate 中 hostField 使用；可选）
 * @param input.predicate    过滤条件，由 serializePredicate 序列化
 * @param input.valueFieldRefs  值字段列表（alias + field），COUNTIF 时可为空
 */
export function buildSumifText(input: {
  func: SumifFunc;
  sourceAlias: string;
  hostAlias?: string;
  predicate: ConditionPredicate;
  valueFieldRefs: { alias: string; field: string }[];
}): string {
  const condText = serializePredicate(input.predicate, {
    sourceAlias: input.sourceAlias,
    hostAlias: input.hostAlias ?? '',
  });

  // COUNTIF 单参，不输出值字段部分
  if (input.func === 'COUNTIF') {
    return `COUNTIF(${condText})`;
  }

  const valueText = input.valueFieldRefs
    .map((r) => `[${r.alias}.${r.field}]`)
    .join(' + ');

  return `${input.func}(${condText}, ${valueText})`;
}

// ── task-0803 Task 8b: 父子取值（PGET/C*）保存前拦截 —— 纯函数化，供 save() 调用 + 单测直接覆盖 ──

/**
 * F-2：父子取值函数（PGET/CSUM/CAVG/CMAX/CMIN/CCOUNT）与树属性（[层级]/[是否叶子]/[是否根]）
 * 仅 BOM 类型页签可用。正在编辑的组件页签类型（tabType）不是 'BOM' 且解析出的 tokens 里出现过
 * tree_ref **或** tree_attr（containsTreeToken 递归扫描，含嵌套场景）→ 返回拦截文案；合规返回
 * null。未配置 tabType 时人类可读标签显示「未配置」，不显示内部 code。
 *
 * 2026-08-03 评审订正：此前误用只测 tree_ref 的 containsTreeRef，导致非 BOM 页签的 [层级] 被
 * 前端放行、保存时才收到后端 ComponentService.assertTreeTokenGates 的 400（该方法对 tree_ref/
 * tree_attr 一视同仁）。改用 containsTreeToken 使前后端判据口径一致，见需求 §4.3.8 闸②。
 */
export function checkTreeRefTabTypeGate(
  tokens: FormulaToken[],
  tabType: string | undefined,
): string | null {
  if (tabType === 'BOM') return null;
  if (!containsTreeToken(tokens)) return null;
  const label = tabType ?? '未配置';
  return `父子取值（PGET/CSUM/CAVG/CMAX/CMIN/CCOUNT）与树属性（[层级]/[是否叶子]/[是否根]）仅 BOM 类型页签可用（当前页签类型：${label}）`;
}

/**
 * F-7：tree_ref.targetExpr 内层白名单校验的保存前拦截文案。
 * 直接复用 formulaSerialize.validateTreeRefWhitelist（禁止另写一套校验规则）；这里只包一层
 * 固定的终端用户文案 —— validateTreeRefWhitelist 返回的 reason 是排障用的技术性描述
 * （如 "tree_ref.targetExpr 内出现不允许的 token 类型「cross_tab_ref」…"），不直接展示给用户。
 */
export const TREE_REF_INNER_VIOLATION_TEXT =
  '父子取值的目标表达式内不能再引用跨页签数据或其他父子取值，请改用本页签的列';

export function checkTreeRefInnerWhitelist(tokens: FormulaToken[]): string | null {
  return validateTreeRefWhitelist(tokens).valid ? null : TREE_REF_INNER_VIOLATION_TEXT;
}

// ── SUMIF 条件行编辑器内部类型 ─────────────────────────────────────────────

type CondOp = '=' | '!=' | '<>' | '>' | '<' | '>=' | '<=';
type CondLogic = 'AND' | 'OR';

interface CondRow {
  id: number;
  /** source 页签字段名 */
  lhsField: string;
  op: CondOp;
  /** rhs 类型：literal=字面量；hostField=宿主字段 */
  rhsKind: 'literal' | 'hostField';
  rhsValue: string;
  /** 与下一条的逻辑连接（最后一行无效，但保留字段避免条件链断裂） */
  logic: CondLogic;
}

type SumifValueExprRow = {
  id: number;
  /** 引用的 source 字段名 */
  fieldName: string;
};

// ── 辅助：把 CondRow[] 转为 ConditionPredicate ────────────────────────────

function condRowsToPredicate(rows: CondRow[]): ConditionPredicate | null {
  if (rows.length === 0) return null;
  const comparisons = rows.map((r): ConditionPredicate => {
    const lhs: PredicateOperand = { kind: 'sourceField', field: r.lhsField };
    const rhs: PredicateOperand = r.rhsKind === 'literal'
      ? { kind: 'literal', value: r.rhsValue }
      : { kind: 'hostField', field: r.rhsValue };
    return { op: r.op, lhs, rhs };
  });
  if (comparisons.length === 1) return comparisons[0];
  // 多行：用第一行的 logic（所有行共享同一个 AND/OR 策略）
  const logic: CondLogic = rows[0].logic ?? 'AND';
  return { bool: logic, children: comparisons };
}

// 自增 id 生成器
let _idSeq = 0;
const nextId = () => ++_idSeq;

// ────────────────────────────────────────────────────────────────────────────

const TabJoinFormulaDrawer: React.FC<Props> = ({
  open,
  componentId,
  componentType,
  selfRowKeyFields,
  tabType,
  column,
  initialTokens,
  onClose,
  onSave,
}) => {
  const [expression, setExpression] = useState<string>(column?.expression ?? '');
  const [tabDefs, setTabDefs] = useState<TabDef[]>([]);
  const exprRef = useRef<FormulaRichInputHandle | null>(null);
  const enforceMappable = componentType !== 'EXCEL';

  const parenCheck = useMemo(() => checkParenBalance(expression), [expression]);

  // 窄屏降级：视口 < 1100px 时两栏改单栏（AC-18 附带项）
  const [isNarrow, setIsNarrow] = useState(
    () => typeof window !== 'undefined' && window.matchMedia('(max-width: 1100px)').matches,
  );
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const mql = window.matchMedia('(max-width: 1100px)');
    const handler = (e: MediaQueryListEvent) => setIsNarrow(e.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);

  // ── SUMIF 配置区状态 ──────────────────────────────────────────────────────
  /** SUMIF 配置区是否展开 */
  const [sumifPanelOpen, setSumifPanelOpen] = useState(false);
  /** SUMIF 函数选择 */
  const [sumifFunc, setSumifFunc] = useState<SumifFunc>('SUMIF');
  /** 来源页签 componentId */
  const [sumifSourceId, setSumifSourceId] = useState<string>('');
  /** 条件行列表 */
  const [condRows, setCondRows] = useState<CondRow[]>([
    { id: nextId(), lhsField: '', op: '=', rhsKind: 'literal', rhsValue: '', logic: 'AND' },
  ]);
  /** 值字段列表（聚合表达式中的字段，COUNTIF 可为空） */
  const [valueFieldRows, setValueFieldRows] = useState<SumifValueExprRow[]>([
    { id: nextId(), fieldName: '' },
  ]);
  /** 当前所选来源页签的字段列表 */
  const sumifSourceTab = useMemo(
    () => tabDefs.find((d) => d.componentId === sumifSourceId),
    [tabDefs, sumifSourceId],
  );
  // 聚合值字段：仅数值可聚合字段（detailFields + 小计列）
  const sourceFields = useMemo(
    () => [...(sumifSourceTab?.detailFields ?? []), ...(sumifSourceTab?.subtotalCols ?? [])],
    [sumifSourceTab],
  );
  // 过滤条件字段：源页签全部字段（含文本字段，如 类型='管理费'）；后端无 allFields 时回退数值字段
  const conditionFields = useMemo(() => {
    const all = sumifSourceTab?.allFields;
    if (all && all.length > 0) return all;
    return sourceFields;
  }, [sumifSourceTab, sourceFields]);
  // 宿主页签别名（self===true 的那个）
  const hostAlias = useMemo(
    () => tabDefs.find((d) => d.self)?.alias ?? '',
    [tabDefs],
  );
  // ─────────────────────────────────────────────────────────────────────────

  // 列切换时重置表达式（EXCEL 模式或无 initialTokens 时直接用 column.expression 字符串）
  useEffect(() => {
    // 有 initialTokens 时不直接用 column.expression —— 等 tabDefs 拉到后再初始化（下方 useEffect）
    if (!initialTokens || initialTokens.length === 0) {
      setExpression(column?.expression ?? '');
    }
  }, [column, initialTokens]);

  // Drawer 打开时拉页签定义（同目录组件集），加载完后若有 initialTokens 则执行初始化
  useEffect(() => {
    if (!open || !componentId) return;
    tabJoinFormulaService
      .tabDefsByComponent(componentId)
      .then((res: any) => {
        // api 拦截器返回 {code, message, data}，需手动解包 .data
        const defs = Array.isArray(res?.data) ? res.data : [];
        setTabDefs(defs);

        // reopen 时：tokensToDrawerExpression 已支持 predicate→SUMIF 文本，
        // 直接把所有 token（含 SUMIF）转成表达式串填入表达式框，无需侧状态拆分。
        if (initialTokens && initialTokens.length > 0) {
          const exprStr = tokensToDrawerExpression(initialTokens, defs, componentId);
          setExpression(exprStr);
        }
      })
      .catch(() => {
        message.error('页签定义加载失败，引用补全不可用');
        setTabDefs([]);
      });
  }, [open, componentId]);

  // Drawer 关闭时重置 SUMIF 面板
  useEffect(() => {
    if (!open) {
      setSumifPanelOpen(false);
      setSumifFunc('SUMIF');
      setSumifSourceId('');
      setCondRows([{ id: nextId(), lhsField: '', op: '=', rhsKind: 'literal', rhsValue: '', logic: 'AND' }]);
      setValueFieldRows([{ id: nextId(), fieldName: '' }]);
    }
  }, [open]);

  /** 在富文本光标处插入文本(转发给 FormulaRichInput),caretOffsetFromEnd 用于 fn() 光标落括号内 */
  const insertAtCursor = (text: string, caretOffsetFromEnd = 0) => {
    exprRef.current?.insertAtCursor(text, caretOffsetFromEnd);
  };

  /** 从当前表达式解析 tabs，组装 column payload（save 时使用） */
  const buildColumn = (expr: string) => {
    const refAliases = Array.from(
      new Set(
        (expr.match(/\[([^\[\]]+)\]/g) || []).map((t) => {
          const body = t.slice(1, -1).replace(/\(总计\)$/, '');
          return body.includes('.') ? body.slice(0, body.indexOf('.')) : body;
        }),
      ),
    );
    const tabs = refAliases
      // 引用串 a 可能是页签名称(优先)或编号(兜底)，与序列化 findTabByRef 同语义。
      // 关键(Bug2 续)：tabs[].alias 必须存"表达式里实际用的引用串 a"(如「来料」)，而非 d.alias(组件 code)——
      // 否则后端 validateTabJoinConfig / TabJoinPlanEvaluator 从表达式解析出的 alias 在 tabs 里查不到，
      // 报"引用了未声明的页签"且运行时 tabKeyOf.get(alias)=null 取不到数据。页签真身仍由 d.tabKey 定位。
      .map((a) => {
        const d = tabDefs.find((x) => x.componentName === a) ?? tabDefs.find((x) => x.alias === a);
        return d ? { alias: a, tabKey: d.tabKey, rowKeyFields: d.rowKeyFields } : null;
      })
      .filter(Boolean);
    return { source_type: 'TAB_JOIN_FORMULA' as const, expression: expr, tabs };
  };

  // ── SUMIF 配置区：插入文本到表达式框 ────────────────────────────────────

  const handleInsertSumifToken = () => {
    if (!sumifSourceId) {
      message.error('请选择来源页签');
      return;
    }
    // 校验条件行
    const validCondRows = condRows.filter((r) => r.lhsField && r.rhsValue);
    if (validCondRows.length === 0) {
      message.error('请至少配置一条有效的过滤条件（字段和值均需填写）');
      return;
    }
    // SUMIF / AVGIF / MINIF / MAXIF 需要值字段
    const needsValueField = sumifFunc !== 'COUNTIF';
    const validValueFields = valueFieldRows.filter((r) => r.fieldName);
    if (needsValueField && validValueFields.length === 0) {
      message.error('请至少选择一个聚合值字段');
      return;
    }

    const predicate = condRowsToPredicate(validCondRows);
    if (!predicate) {
      message.error('条件配置有误');
      return;
    }

    const sourceAlias = sumifSourceTab?.componentName ?? sumifSourceTab?.alias ?? sumifSourceId;
    const text = buildSumifText({
      func: sumifFunc,
      sourceAlias,
      hostAlias: hostAlias || undefined,
      predicate,
      valueFieldRefs: validValueFields.map((r) => ({ alias: sourceAlias, field: r.fieldName })),
    });

    insertAtCursor(text);
    message.success('已插入 SUMIF，可在表达式框继续编辑或加运算符');

    // 重置配置区（保留页签选择，方便连续添加）
    setCondRows([{ id: nextId(), lhsField: '', op: '=', rhsKind: 'literal', rhsValue: '', logic: 'AND' }]);
    setValueFieldRows([{ id: nextId(), fieldName: '' }]);
  };

  // ── 条件行操作 ────────────────────────────────────────────────────────────

  const addCondRow = () => {
    setCondRows((prev) => [
      ...prev,
      { id: nextId(), lhsField: '', op: '=', rhsKind: 'literal', rhsValue: '', logic: 'AND' },
    ]);
  };

  const removeCondRow = (id: number) => {
    setCondRows((prev) => prev.filter((r) => r.id !== id));
  };

  const updateCondRow = (id: number, patch: Partial<CondRow>) => {
    setCondRows((prev) => prev.map((r) => (r.id === id ? { ...r, ...patch } : r)));
  };

  // ── 值字段行操作 ──────────────────────────────────────────────────────────

  const addValueFieldRow = () => {
    setValueFieldRows((prev) => [...prev, { id: nextId(), fieldName: '' }]);
  };

  const removeValueFieldRow = (id: number) => {
    setValueFieldRows((prev) => prev.filter((r) => r.id !== id));
  };

  // ─────────────────────────────────────────────────────────────────────────

  const save = () => {
    const expr = expression.trim();

    // EXCEL 组件：沿用原行为 —— 保存为 TAB_JOIN_FORMULA string column
    if (componentType === 'EXCEL') {
      if (!expr) {
        message.error('表达式不能为空');
        return;
      }
      if (!parenCheck.ok) {
        message.error(parenCheck.error);
        return;
      }
      const col = buildColumn(expr);
      // I-1：表达式中引用的 alias 都没匹配到已知页签时，拒绝保存
      if (col.tabs.length === 0) {
        message.error('表达式中未识别到有效页签引用，请检查别名拼写');
        return;
      }
      onSave({ kind: 'excel', column: col });
      return;
    }

    // NORMAL / SUBTOTAL 组件：转 FormulaToken[] 落组件公式。
    // SUMIF 现已内联在表达式串里，expressionToTokens 会正确解析为带 predicate 的 cross_tab_ref。
    if (!expr) {
      message.error('表达式不能为空，请填写表达式或通过 SUMIF 构造器插入条件聚合');
      return;
    }

    // 防御性冗余：正常路径下保存按钮已因 !parenCheck.ok 被 disabled、点不到这里；
    // 此守卫兜住程序化/绕过 disabled 的调用。
    if (!parenCheck.ok) {
      message.error(parenCheck.error);
      return;
    }

    let tokens: FormulaToken[];
    try {
      tokens = expressionToTokens(expr, tabDefs, selfRowKeyFields, componentId);
    } catch (e: any) {
      // 解析错误（未知别名 / 括号不匹配 / 非法字符等）→ 拦截保存
      message.error(e?.message ?? '表达式解析失败，请检查语法');
      return;
    }

    // F-2（task-0803）：父子取值仅 BOM 类型页签可用
    const treeRefGateMsg = checkTreeRefTabTypeGate(tokens, tabType);
    if (treeRefGateMsg) {
      message.error(treeRefGateMsg);
      return;
    }
    // F-7（task-0803）：tree_ref.targetExpr 内层白名单（复用 formulaSerialize.validateTreeRefWhitelist）
    const treeRefInnerMsg = checkTreeRefInnerWhitelist(tokens);
    if (treeRefInnerMsg) {
      message.error(treeRefInnerMsg);
      return;
    }

    const mappable = checkMappable(tokens);
    if (!mappable.mappable) {
      message.error(`${mappable.reason ?? '该公式无法映射为组件公式'}，请改用 Excel 组件`);
      return;
    }

    onSave({ kind: 'tokens', tokens });
  };

  // 保存按钮是否可点击：表达式非空 + 括号合法
  const saveDisabled = !parenCheck.ok && expression.trim().length > 0;

  return (
    <Drawer
      title="配置页签连表公式"
      width={'min(1520px, 92vw)'}
      placement="right"
      open={open}
      onClose={onClose}
      destroyOnClose
      styles={{ body: { padding: 0 } }}
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Tooltip title={saveDisabled ? parenCheck.error : undefined}>
            <Button type="primary" onClick={save} disabled={saveDisabled}>
              保存
            </Button>
          </Tooltip>
        </Space>
      }
    >
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: isNarrow ? '1fr' : 'minmax(430px, 42fr) minmax(520px, 58fr)',
        }}
      >
        {/* 左栏：页签字段面板（搜索 + 上下卡片列表，各自独立滚动） */}
        <div
          style={{
            borderRight: isNarrow ? 'none' : '1px solid #f0f0f0',
            padding: '14px 16px',
            overflow: 'auto',
            maxHeight: '78vh',
          }}
        >
          <TabFieldPanel
            tabDefs={tabDefs}
            selfRowKeyFields={selfRowKeyFields}
            onInsert={(token) => insertAtCursor(token)}
          />
        </div>

        {/* 右栏：公式配置（表达式框 + 图例 + 工具条 + 规则提示）+ SUMIF 折叠区，各自独立滚动 */}
        <div style={{ padding: '14px 16px', overflow: 'auto', maxHeight: '78vh' }}>
          <FormulaEditorPanel
            expression={expression}
            onChange={setExpression}
            tabDefs={tabDefs}
            selfRowKeyFields={selfRowKeyFields}
            enforceMappable={enforceMappable}
            componentType={componentType}
            parenCheck={parenCheck}
            inputRef={exprRef}
            onInsert={insertAtCursor}
            onClearExpression={() => setExpression('')}
            onOpenSumif={(fn) => {
              setSumifFunc(fn);
              setSumifPanelOpen(true);
            }}
          />

          {/* ── SUMIF 条件聚合配置区（仅 NORMAL/SUBTOTAL 组件） ── */}
          {componentType !== 'EXCEL' && (
            <div style={{ marginTop: 16 }}>
              <Divider style={{ margin: '0 0 10px 0' }}>
                <Button
                  type="link"
                  style={{ fontSize: 13, padding: 0 }}
                  onClick={() => setSumifPanelOpen((v) => !v)}
                >
                  {sumifPanelOpen ? '收起 SUMIF 条件聚合' : '展开 SUMIF 条件聚合（按条件过滤后聚合）'}
                </Button>
              </Divider>

              {sumifPanelOpen && (
                <div
                  style={{
                    padding: '14px 16px',
                    background: '#f6f0ff',
                    border: '1px solid #d3adf7',
                    borderRadius: 8,
                    marginBottom: 12,
                  }}
                >
                  <Text strong style={{ fontSize: 13, color: '#531dab' }}>
                    SUMIF 条件聚合构造器
                  </Text>
                  <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                    （配置完成后点「插入 SUMIF 到表达式」，SUMIF 文本将插入到表达式框光标处）
                  </Text>

                  <Form layout="vertical" style={{ marginTop: 12 }}>
                    {/* 函数选择 + 来源页签 */}
                    <Space align="start" wrap>
                      <Form.Item label="函数" style={{ marginBottom: 8, minWidth: 120 }}>
                        <Select<SumifFunc>
                          value={sumifFunc}
                          onChange={setSumifFunc}
                          style={{ width: 120 }}
                        >
                          {(Object.keys(FUNC_TO_AGG) as SumifFunc[]).map((fn) => (
                            <Option key={fn} value={fn}>{fn}</Option>
                          ))}
                        </Select>
                      </Form.Item>
                      <Form.Item label="来源页签" style={{ marginBottom: 8, minWidth: 200 }}>
                        <Select
                          value={sumifSourceId || undefined}
                          onChange={(v) => {
                            setSumifSourceId(v);
                            setCondRows([{ id: nextId(), lhsField: '', op: '=', rhsKind: 'literal', rhsValue: '', logic: 'AND' }]);
                            setValueFieldRows([{ id: nextId(), fieldName: '' }]);
                          }}
                          placeholder="选择来源页签"
                          style={{ width: 200 }}
                        >
                          {tabDefs
                            .filter((d) => d.componentId !== componentId)
                            .map((d) => (
                              <Option key={d.componentId} value={d.componentId}>
                                {d.componentName ?? d.alias}
                              </Option>
                            ))}
                        </Select>
                      </Form.Item>
                    </Space>

                    {/* 过滤条件行编辑器 */}
                    <Form.Item label="过滤条件" style={{ marginBottom: 8 }}>
                      <div style={{ background: '#fff', border: '1px solid #e6d5ff', borderRadius: 6, padding: '8px 10px' }}>
                        {condRows.map((row, idx) => (
                          <div key={row.id} style={{ marginBottom: idx < condRows.length - 1 ? 8 : 0 }}>
                            <Space align="center" wrap>
                              {/* AND/OR 连接符（第一行不显示） */}
                              {idx > 0 && (
                                <Select<CondLogic>
                                  value={condRows[0].logic}
                                  onChange={(v) =>
                                    setCondRows((prev) => prev.map((r) => ({ ...r, logic: v })))
                                  }
                                  style={{ width: 70 }}
                                  size="small"
                                >
                                  <Option value="AND">AND</Option>
                                  <Option value="OR">OR</Option>
                                </Select>
                              )}
                              {idx === 0 && (
                                <Text type="secondary" style={{ width: 70, display: 'inline-block', fontSize: 12 }}>
                                  条件
                                </Text>
                              )}
                              {/* 左侧字段（source 页签字段） */}
                              <Select
                                value={row.lhsField || undefined}
                                onChange={(v) => updateCondRow(row.id, { lhsField: v })}
                                placeholder="来源字段"
                                style={{ width: 140 }}
                                size="small"
                                showSearch
                                disabled={!sumifSourceId}
                              >
                                {conditionFields.map((f) => (
                                  <Option key={f} value={f}>{f}</Option>
                                ))}
                              </Select>
                              {/* 运算符 */}
                              <Select<CondOp>
                                value={row.op}
                                onChange={(v) => updateCondRow(row.id, { op: v })}
                                style={{ width: 70 }}
                                size="small"
                              >
                                {(['=', '!=', '<>', '>', '<', '>=', '<='] as CondOp[]).map((op) => (
                                  <Option key={op} value={op}>{op}</Option>
                                ))}
                              </Select>
                              {/* 右侧类型 */}
                              <Select<'literal' | 'hostField'>
                                value={row.rhsKind}
                                onChange={(v) => updateCondRow(row.id, { rhsKind: v, rhsValue: '' })}
                                style={{ width: 90 }}
                                size="small"
                              >
                                <Option value="literal">字面量</Option>
                                <Option value="hostField">宿主字段</Option>
                              </Select>
                              {/* 右侧值 */}
                              {row.rhsKind === 'literal' ? (
                                <Input
                                  value={row.rhsValue}
                                  onChange={(e) => updateCondRow(row.id, { rhsValue: e.target.value })}
                                  placeholder="值（如 管理费）"
                                  style={{ width: 140 }}
                                  size="small"
                                />
                              ) : (
                                <Input
                                  value={row.rhsValue}
                                  onChange={(e) => updateCondRow(row.id, { rhsValue: e.target.value })}
                                  placeholder="宿主字段名"
                                  style={{ width: 140 }}
                                  size="small"
                                />
                              )}
                              {/* 删除行 */}
                              {condRows.length > 1 && (
                                <Button
                                  size="small"
                                  type="text"
                                  danger
                                  icon={<DeleteOutlined />}
                                  onClick={() => removeCondRow(row.id)}
                                />
                              )}
                            </Space>
                          </div>
                        ))}
                        <Button
                          size="small"
                          type="dashed"
                          icon={<PlusOutlined />}
                          onClick={addCondRow}
                          style={{ marginTop: 8 }}
                        >
                          添加条件行
                        </Button>
                      </div>
                    </Form.Item>

                    {/* 聚合值字段（COUNTIF 可不填） */}
                    {sumifFunc !== 'COUNTIF' && (
                      <Form.Item
                        label={`聚合值字段（${sumifFunc} 的目标列）`}
                        style={{ marginBottom: 8 }}
                      >
                        <div style={{ background: '#fff', border: '1px solid #e6d5ff', borderRadius: 6, padding: '8px 10px' }}>
                          {valueFieldRows.map((row, idx) => (
                            <div key={row.id} style={{ marginBottom: idx < valueFieldRows.length - 1 ? 8 : 0 }}>
                              <Space align="center">
                                <Select
                                  value={row.fieldName || undefined}
                                  onChange={(v) =>
                                    setValueFieldRows((prev) =>
                                      prev.map((r) => (r.id === row.id ? { ...r, fieldName: v } : r)),
                                    )
                                  }
                                  placeholder="选择字段"
                                  style={{ width: 200 }}
                                  size="small"
                                  showSearch
                                  disabled={!sumifSourceId}
                                >
                                  {sourceFields.map((f) => (
                                    <Option key={f} value={f}>{f}</Option>
                                  ))}
                                </Select>
                                {valueFieldRows.length > 1 && (
                                  <Button
                                    size="small"
                                    type="text"
                                    danger
                                    icon={<DeleteOutlined />}
                                    onClick={() => removeValueFieldRow(row.id)}
                                  />
                                )}
                              </Space>
                            </div>
                          ))}
                          <Button
                            size="small"
                            type="dashed"
                            icon={<PlusOutlined />}
                            onClick={addValueFieldRow}
                            style={{ marginTop: 8 }}
                          >
                            添加值字段
                          </Button>
                        </div>
                      </Form.Item>
                    )}

                    {/* 插入按钮 */}
                    <Button
                      type="primary"
                      style={{ background: '#722ed1', borderColor: '#722ed1' }}
                      onClick={handleInsertSumifToken}
                    >
                      插入 {sumifFunc} 到表达式
                    </Button>
                  </Form>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </Drawer>
  );
};

export default TabJoinFormulaDrawer;
