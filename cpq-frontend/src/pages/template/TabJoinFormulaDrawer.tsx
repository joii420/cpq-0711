import React, { useState, useEffect, useRef, useMemo } from 'react';
import {
  Drawer, Button, Space, message, Table, Typography, Tooltip,
  Select, Form, Input, Divider,
} from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { tabJoinFormulaService, type TabDef } from '../../services/tabJoinFormulaService';
import TabFieldMatrix from './tabjoin/TabFieldMatrix';
import FormulaRichInput, { type FormulaRichInputHandle } from './tabjoin/FormulaRichInput';
import SampleCardPicker from './tabjoin/SampleCardPicker';
import {
  expressionToTokens,
  checkMappable,
} from '../component/formulaSerialize';
import type { FormulaToken } from '../component/types';
import { checkParenBalance } from './tabjoin/formulaBracketCheck';
import type { ExpressionToken, ConditionPredicate, PredicateOperand } from '../../utils/formulaEngine';

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
  /** 正在编辑公式的组件 id（页签集 / 样本卡 / 试算均以此组件为作用域） */
  componentId: string;
  /** 组件类型 — 决定保存形态：EXCEL → string column；NORMAL/SUBTOTAL → token[] */
  componentType: ComponentFormulaType;
  /** 本组件行键字段，供跨页签引用构建 match[] 对齐对（仅 token 形态需要） */
  selfRowKeyFields?: string[];
  column: any;
  onClose: () => void;
  onSave: (payload: TabJoinFormulaSavePayload) => void;
}

const FUNCS = ['SUM', 'AVG', 'MIN', 'MAX', 'COUNT'];
const OPS = ['+', '-', '*', '/', '(', ')'];

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
  column,
  onClose,
  onSave,
}) => {
  const [expression, setExpression] = useState<string>(column?.expression ?? '');
  const [tabDefs, setTabDefs] = useState<TabDef[]>([]);
  const exprRef = useRef<FormulaRichInputHandle | null>(null);
  const enforceMappable = componentType !== 'EXCEL';

  const parenCheck = useMemo(() => checkParenBalance(expression), [expression]);

  // 试算相关状态
  const [sampleLi, setSampleLi] = useState<string | undefined>(undefined);
  const [dryRunValue, setDryRunValue] = useState<string | number | null>(null);
  const [dryRunRows, setDryRunRows] = useState<{ rowKey: string; value: number | null }[] | null>(null);
  const [dryRunErrors, setDryRunErrors] = useState<string[]>([]);
  const [dryRunLoading, setDryRunLoading] = useState(false);

  // ── SUMIF 配置区状态 ──────────────────────────────────────────────────────
  /** 待插入的 SUMIF token 列表（独立于字符串表达式，保存时拼入 tokens 末尾） */
  const [sumifTokens, setSumifTokens] = useState<ExpressionToken[]>([]);
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
  const sourceFields = useMemo(
    () => [...(sumifSourceTab?.detailFields ?? []), ...(sumifSourceTab?.subtotalCols ?? [])],
    [sumifSourceTab],
  );
  // ─────────────────────────────────────────────────────────────────────────

  // 列切换时重置表达式
  useEffect(() => {
    setExpression(column?.expression ?? '');
  }, [column]);

  // Drawer 打开时拉页签定义（同目录组件集）
  useEffect(() => {
    if (!open || !componentId) return;
    tabJoinFormulaService
      .tabDefsByComponent(componentId)
      .then((res: any) => {
        // api 拦截器返回 {code, message, data}，需手动解包 .data
        setTabDefs(Array.isArray(res?.data) ? res.data : []);
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
      setSumifTokens([]);
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

  /** 从当前表达式解析 tabs，组装 column payload（save 和 dryRun 共用） */
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
      // 引用串可能是页签名称(优先)或编号(兜底)，与序列化 findTabByRef 同语义
      .map((a) => tabDefs.find((d) => d.componentName === a) ?? tabDefs.find((d) => d.alias === a))
      .filter(Boolean)
      .map((d: any) => ({ alias: d.alias, tabKey: d.tabKey, rowKeyFields: d.rowKeyFields }));
    return { source_type: 'TAB_JOIN_FORMULA' as const, expression: expr, tabs };
  };

  // ── SUMIF 配置区：插入 token ─────────────────────────────────────────────

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
    const valueExprTokens: ExpressionToken[] = validValueFields.map((r) => ({
      type: 'field' as const,
      value: r.fieldName,
      source: sumifSourceId,
    }));

    const token = buildSumifToken({
      func: sumifFunc,
      source: sumifSourceId,
      sourceLabel: sumifSourceTab?.componentName ?? sumifSourceId,
      predicate,
      valueExprTokens,
    });

    setSumifTokens((prev) => [...prev, token]);
    message.success(`已追加 ${sumifFunc} token，将在保存时生效`);

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

    // EXCEL 组件：沿用原行为 —— 保存为 TAB_JOIN_FORMULA string column（不支持 SUMIF side-token）
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
    // 允许纯 SUMIF token（expr 为空）或 expr + SUMIF 混合。
    if (!expr && sumifTokens.length === 0) {
      message.error('表达式不能为空，请填写表达式或配置 SUMIF 公式');
      return;
    }

    let exprTokens: FormulaToken[] = [];
    if (expr) {
      // 防御性冗余：正常路径下保存按钮已因 !parenCheck.ok 被 disabled、点不到这里；
      // 此守卫兜住程序化/绕过 disabled 的调用。括号检查对空白不敏感，复用 parenCheck 即可。
      if (!parenCheck.ok) {
        message.error(parenCheck.error);
        return;
      }
      try {
        exprTokens = expressionToTokens(expr, tabDefs, selfRowKeyFields, componentId);
      } catch (e: any) {
        // 解析错误（未知别名 / 括号不匹配 / 非法字符等）→ 拦截保存
        message.error(e?.message ?? '表达式解析失败，请检查语法');
        return;
      }
      const mappable = checkMappable(exprTokens);
      if (!mappable.mappable) {
        message.error(`${mappable.reason ?? '该公式无法映射为组件公式'}，请改用 Excel 组件`);
        return;
      }
    }

    // 合并：字符串表达式 tokens + SUMIF side-tokens
    const allTokens = [...exprTokens, ...(sumifTokens as FormulaToken[])];
    onSave({ kind: 'tokens', tokens: allTokens });
  };

  const runDryRun = async () => {
    const expr = expression.trim();
    if (!expr) {
      message.warning('请先填表达式');
      return;
    }
    if (!sampleLi) {
      message.warning('请先选样本卡片');
      return;
    }
    setDryRunLoading(true);
    try {
      if (componentType === 'EXCEL') {
        // EXCEL 路径：沿用旧端点，返回单值
        setDryRunRows(null);
        const col = buildColumn(expr);
        const res: any = await tabJoinFormulaService.dryRunByComponent(componentId, sampleLi, col);
        const data = res?.data ?? res;
        setDryRunValue(data?.value ?? null);
        setDryRunErrors(data?.errors ?? []);
        if (data?.errors?.length) {
          message.warning(data.errors.join('; '));
        }
      } else {
        // NORMAL / SUBTOTAL 路径：走 token 试算端点，返逐行结果
        setDryRunValue(null);
        const tokens = expressionToTokens(expr, tabDefs, selfRowKeyFields, componentId);
        const res: any = await tabJoinFormulaService.dryRunToken(
          componentId,
          sampleLi,
          tokens,
          selfRowKeyFields ?? [],
        );
        const data = res?.data ?? res;
        setDryRunRows(data?.rows ?? []);
        setDryRunErrors(data?.errors ?? []);
        if (data?.errors?.length) {
          message.warning(data.errors.join('; '));
        }
      }
    } catch (e: any) {
      setDryRunValue(null);
      setDryRunRows(null);
      setDryRunErrors([]);
      message.error('试算失败: ' + (e?.message ?? String(e)));
    } finally {
      setDryRunLoading(false);
    }
  };

  // 保存按钮是否可点击：EXCEL 模式跟括号校验；NORMAL 模式还需有内容（expr 或 sumifTokens）
  const saveDisabled = componentType === 'EXCEL'
    ? !parenCheck.ok
    : (!parenCheck.ok && expression.trim().length > 0);

  return (
    <Drawer
      title="配置页签连表公式"
      width={1100}
      placement="right"
      open={open}
      onClose={onClose}
      destroyOnClose
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
      {/* 试算条 */}
      <div style={{ marginBottom: 16, padding: '10px 12px', background: '#f0f5ff', border: '1px solid #adc6ff', borderRadius: 6 }}>
        <Space wrap align="center">
          <Text style={{ fontSize: 13 }}>试算：</Text>
          <SampleCardPicker
            componentId={componentId}
            value={sampleLi}
            onChange={(li) => {
              setSampleLi(li || undefined);
              setDryRunValue(null);
              setDryRunRows(null);
              setDryRunErrors([]);
            }}
          />
          <Button
            type="default"
            loading={dryRunLoading}
            onClick={runDryRun}
          >
            试算
          </Button>
          {/* EXCEL 单值结果 */}
          {componentType === 'EXCEL' && dryRunValue !== null && dryRunErrors.length === 0 && (
            <Text strong style={{ color: '#1677ff' }}>
              试算结果：{String(dryRunValue)}
            </Text>
          )}
          {/* 错误显示（所有类型公用） */}
          {dryRunErrors.length > 0 && (
            <Text style={{ color: '#cf1322', fontSize: 12 }}>
              错误：{dryRunErrors.join('; ')}
            </Text>
          )}
        </Space>
        {/* NORMAL / SUBTOTAL 逐行试算结果小表 */}
        {componentType !== 'EXCEL' && dryRunRows !== null && dryRunErrors.length === 0 && dryRunRows.length > 0 && (
          <Table
            size="small"
            pagination={false}
            style={{ marginTop: 8 }}
            rowKey={(_, i) => String(i)}
            dataSource={dryRunRows}
            columns={[
              { title: '行键', dataIndex: 'rowKey', key: 'rowKey' },
              {
                title: '试算值',
                dataIndex: 'value',
                key: 'value',
                render: (v: number | null) => (v == null ? '—' : String(v)),
              },
            ]}
          />
        )}
        {/* SUBTOTAL 语义是单值；若 rows 多行，仅供参考（取首行即为合计行） */}
        {componentType !== 'EXCEL' && dryRunRows !== null && dryRunErrors.length === 0 && dryRunRows.length === 0 && (
          <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
            试算无行（样本卡该组件 0 行）
          </Text>
        )}
      </div>

      {/* 公式表达式 */}
      <Text strong>公式表达式</Text>
      <div style={{ color: '#8a909a', fontSize: 12, marginBottom: 6 }}>
        列来源：页签连表公式 · 单卡片单值 · 行键自动对齐(全外连·缺补0) · 明细默认按对齐行求和
      </div>
      <FormulaRichInput
        ref={exprRef}
        value={expression}
        onChange={setExpression}
        tabDefs={tabDefs}
        selfRowKeyFields={selfRowKeyFields}
        enforceMappable={enforceMappable}
        placeholder="例:[投料.金额] * [加工.工时] + [回料(总计)]"
      />
      {!parenCheck.ok && (
        <Text type="danger" style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
          {parenCheck.error}
        </Text>
      )}

      {/* 运算符 + 函数工具条 */}
      <Space style={{ marginTop: 10 }} wrap>
        <Text type="secondary" style={{ fontSize: 12 }}>
          运算符
        </Text>
        {OPS.map((op) => (
          <Button key={op} size="small" style={{ fontFamily: 'monospace', fontWeight: 600 }}
            onClick={() => insertAtCursor(op)}>
            {op}
          </Button>
        ))}
        <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
          函数
        </Text>
        {FUNCS.map((fn) => (
          <Button
            key={fn}
            size="small"
            style={{ color: '#fa8c16', borderColor: '#ffd591' }}
            onClick={() => insertAtCursor(`${fn}()`, 1)}
          >
            {fn}
          </Button>
        ))}
      </Space>

      {/* 规则提示 */}
      <div
        style={{
          marginTop: 10,
          padding: '8px 12px',
          background: '#fffbe6',
          border: '1px solid #ffe58f',
          borderRadius: 6,
          fontSize: 12,
          color: '#874d00',
          lineHeight: 1.7,
        }}
      >
        明细字段默认按对齐行自动求和；套 <code style={{ background: '#fff', border: '1px solid #ffe58f', borderRadius: 3, padding: '0 4px' }}>AVG/MIN/MAX/COUNT</code> 改聚合方式。
        按顶层 +/- 拆项：含裸明细的项逐行求和，纯标量/总计项算一次。
        引用格式：<code style={{ background: '#fff', border: '1px solid #ffe58f', borderRadius: 3, padding: '0 4px' }}>[页签名称.字段名]</code> 或{' '}
        <code style={{ background: '#fff', border: '1px solid #ffe58f', borderRadius: 3, padding: '0 4px' }}>[页签名称(总计)]</code>。
        <br />
        <strong>行级聚合（粗 host × 细 source）</strong>：写{' '}
        <code style={{ background: '#fff', border: '1px solid #ffe58f', borderRadius: 3, padding: '0 4px' }}>SUM([宿主别名.列] * [细页签名称.列])</code>{' '}
        —— 按行键对齐(LEFT JOIN)后<strong>逐行</strong>算括号内表达式，再按宿主行键聚合(SUMPRODUCT)；宿主列在每个对齐行广播为同值。
      </div>

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
                （生成的 token 将追加在保存的公式末尾）
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
                            {sourceFields.map((f) => (
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
                  插入 {sumifFunc} token
                </Button>
              </Form>

              {/* 已追加的 SUMIF token 预览 */}
              {sumifTokens.length > 0 && (
                <div style={{ marginTop: 12 }}>
                  <Text strong style={{ fontSize: 12, color: '#531dab' }}>
                    已追加 {sumifTokens.length} 个 SUMIF token（保存时生效）：
                  </Text>
                  {sumifTokens.map((tk, i) => (
                    <div
                      key={i}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        marginTop: 4,
                        padding: '4px 8px',
                        background: '#f9f0ff',
                        border: '1px solid #d3adf7',
                        borderRadius: 4,
                        fontSize: 12,
                      }}
                    >
                      <Text style={{ fontSize: 12, color: '#531dab' }}>
                        [{i + 1}] {tk.agg}({tk.sourceLabel ?? tk.source})
                        {tk.predicate ? ' — 含过滤条件' : ''}
                        {tk.targetExpr && tk.targetExpr.length > 0
                          ? ` → ${tk.targetExpr.map((t) => t.value ?? '?').join(', ')}`
                          : ''}
                      </Text>
                      <Button
                        size="small"
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                        onClick={() => setSumifTokens((prev) => prev.filter((_, j) => j !== i))}
                      />
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* 页签字段矩阵 + 置灰锁定 */}
      <div style={{ marginTop: 14 }}>
        <div style={{ fontSize: 12, color: '#8a909a', fontWeight: 600, marginBottom: 6 }}>
          页签字段（全部展示 · 点明细锁定行键类 · 行键不同则该页签明细置灰，仅留总计可选）
        </div>
        <TabFieldMatrix
          tabDefs={tabDefs}
          expression={expression}
          onInsert={(token) => insertAtCursor(token)}
          onClearExpression={() => setExpression('')}
          selfRowKeyFields={selfRowKeyFields}
        />
      </div>

    </Drawer>
  );
};

export default TabJoinFormulaDrawer;
