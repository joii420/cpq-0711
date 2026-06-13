import React, { useState, useEffect, useRef, useMemo } from 'react';
import { Drawer, Button, Space, message, Table, Typography, Tooltip } from 'antd';
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

const { Text } = Typography;

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

  const save = () => {
    const expr = expression.trim();
    if (!expr) {
      message.error('表达式不能为空');
      return;
    }

    const pc = checkParenBalance(expr);
    if (!pc.ok) {
      message.error(pc.error);
      return;
    }

    // EXCEL 组件：沿用原行为 —— 保存为 TAB_JOIN_FORMULA string column。
    if (componentType === 'EXCEL') {
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
    let tokens: FormulaToken[];
    try {
      tokens = expressionToTokens(expr, tabDefs, selfRowKeyFields, componentId);
    } catch (e: any) {
      // 解析错误（未知别名 / 括号不匹配 / 非法字符等）→ 拦截保存
      message.error(e?.message ?? '表达式解析失败，请检查语法');
      return;
    }

    const mappable = checkMappable(tokens);
    if (!mappable.mappable) {
      message.error(`${mappable.reason ?? '该公式无法映射为组件公式'}，请改用 Excel 组件`);
      return;
    }

    onSave({ kind: 'tokens', tokens });
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
          <Tooltip title={parenCheck.ok ? '' : parenCheck.error}>
            <Button type="primary" onClick={save} disabled={!parenCheck.ok}>
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
        <Text style={{ color: '#cf1322', fontSize: 12, display: 'block', marginTop: 4 }}>
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
