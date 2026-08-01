import React from 'react';
import { Button, Space, Typography } from 'antd';
import type { TabDef } from '../../../services/tabJoinFormulaService';
import FormulaRichInput, { type FormulaRichInputHandle } from './FormulaRichInput';
import type { ParenCheckResult } from './formulaBracketCheck';

const { Text } = Typography;

// ──────────────────────────────────────────────
// SUMIF 族函数名（与 TabJoinFormulaDrawer.SumifFunc 结构等价的本地类型，
// 避免对 Drawer 产生值/类型导入依赖 —— Drawer 引入本组件，本组件不反向引入 Drawer）
// ──────────────────────────────────────────────
type SumifFuncName = 'SUMIF' | 'COUNTIF' | 'AVGIF' | 'MINIF' | 'MAXIF';

const FUNCS = ['SUM', 'AVG', 'MIN', 'MAX', 'COUNT'];
const OPS = ['+', '-', '*', '/', '(', ')'];
const SUMIF_TEXT_FUNCS: SumifFuncName[] = ['SUMIF', 'COUNTIF', 'AVGIF', 'MINIF', 'MAXIF'];

// ──────────────────────────────────────────────
// 括号配对深度图例（与 FormulaRichInput 的 .p0~.p3 着色同源，逐字一致）
// ──────────────────────────────────────────────
const PAREN_DEPTH_COLORS = ['#d4820a', '#7d3ac1', '#0a9396', '#c2185b'];

// 引用语义图例（与 FormulaRichInput.BLOCK_STYLE 同色值）
const REF_LEGEND: { label: string; bg: string; border: string }[] = [
  { label: '普通引用', bg: '#e6f4ff', border: '#91caff' },
  { label: '小计', bg: '#fffbe6', border: '#ffd591' },
  { label: '总计', bg: '#f6ffed', border: '#b7eb8f' },
  { label: '本页签', bg: '#f9f0ff', border: '#d3adf7' },
  { label: '非法', bg: '#fff1f0', border: '#ffa39e' },
];

// ──────────────────────────────────────────────
// Props
// ──────────────────────────────────────────────
interface Props {
  expression: string;
  onChange: (next: string) => void;
  tabDefs: TabDef[];
  selfRowKeyFields?: string[];
  /** EXCEL→false(不按 match 红);NORMAL/SUBTOTAL→true */
  enforceMappable: boolean;
  componentType: 'NORMAL' | 'SUBTOTAL' | 'EXCEL';
  parenCheck: ParenCheckResult;
  /** FormulaRichInput 的 ref，由 Drawer 持有并透传 */
  inputRef: React.Ref<FormulaRichInputHandle>;
  /** 在公式框光标处插入文本（工具条运算符/函数按钮 + EXCEL 线 SUMIF 按钮用） */
  onInsert: (text: string, caretOffsetFromEnd?: number) => void;
  onClearExpression: () => void;
  /** 组件线（NORMAL/SUBTOTAL）点条件聚合按钮 → 展开 Drawer 底部 SUMIF 构造器并预选该函数 */
  onOpenSumif: (func: SumifFuncName) => void;
}

// ──────────────────────────────────────────────
// 主组件：右栏整体 = 标题行(公式表达式+副标题 / 括号状态+清空) + 公式框 + 错误提示
//         + 图例两行 + 工具条 + 规则提示。不持有 expression 状态，全部经 props 受控。
// ──────────────────────────────────────────────
const FormulaEditorPanel: React.FC<Props> = ({
  expression,
  onChange,
  tabDefs,
  selfRowKeyFields,
  enforceMappable,
  componentType,
  parenCheck,
  inputRef,
  onInsert,
  onClearExpression,
  onOpenSumif,
}) => {
  const hasExpr = expression.trim().length > 0;

  return (
    <div>
      {/* 标题行：左=公式表达式+副标题；右=括号状态+清空表达式 */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
        <div>
          <Text strong>公式表达式</Text>
          <div style={{ color: '#8a909a', fontSize: 12, marginTop: 2 }}>
            列来源：页签连表公式 · 单卡片单值 · 行键自动对齐(全外连·缺补0) · 明细默认按对齐行求和
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
          {hasExpr && (
            <Text style={{ fontSize: 12, color: parenCheck.ok ? '#389e0d' : '#cf1322', whiteSpace: 'nowrap' }}>
              ● {parenCheck.ok ? '括号匹配' : '括号不匹配'}
            </Text>
          )}
          <Button size="small" onClick={onClearExpression}>
            清空表达式
          </Button>
        </div>
      </div>

      <FormulaRichInput
        ref={inputRef}
        value={expression}
        onChange={onChange}
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

      {/* 图例两行 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, marginTop: 8, fontSize: 12, color: '#8a909a', alignItems: 'center' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <Text strong style={{ fontSize: 12 }}>块底色 = 引用语义</Text>
        </span>
        {REF_LEGEND.map((it) => (
          <span key={it.label} style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <i style={{ width: 12, height: 12, borderRadius: 3, display: 'inline-block', background: it.bg, border: `1px solid ${it.border}` }} />
            {it.label}
          </span>
        ))}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, marginTop: 4, fontSize: 12, color: '#8a909a', alignItems: 'center' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <Text strong style={{ fontSize: 12 }}>括号字色 = 配对深度</Text>
        </span>
        {PAREN_DEPTH_COLORS.map((c, idx) => (
          <span key={c} style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <span style={{ fontWeight: 800, color: c }}>( )</span>第{idx + 1}层
          </span>
        ))}
        <span style={{ color: '#874d00' }}>光标停在括号上 → 同一对加黄底</span>
      </div>

      {/* 工具条：运算符 / 函数 / 条件聚合 */}
      <Space style={{ marginTop: 10 }} wrap>
        <Text type="secondary" style={{ fontSize: 12 }}>
          运算符
        </Text>
        {OPS.map((op) => (
          <Button key={op} size="small" style={{ fontFamily: 'monospace', fontWeight: 600 }}
            onClick={() => onInsert(op)}>
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
            onClick={() => onInsert(`${fn}()`, 1)}
          >
            {fn}
          </Button>
        ))}
        <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
          条件聚合
        </Text>
        {SUMIF_TEXT_FUNCS.map((fn) => (
          <Button
            key={fn}
            size="small"
            title={
              componentType === 'EXCEL'
                ? `${fn}(条件, 取值表达式)，如 ${fn}([页签.类型]='管理费', [页签.金额])`
                : `点击展开下方「条件聚合」构造器配置 ${fn}（条件过滤后按行键聚合）`
            }
            style={{ color: '#722ed1', borderColor: '#d3adf7' }}
            onClick={() => {
              // EXCEL 线：文本可解析，直接插入；组件线：展开可视化构造器并预选该函数。
              if (componentType === 'EXCEL') {
                onInsert(`${fn}()`, 1);
              } else {
                onOpenSumif(fn);
              }
            }}
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
        <br />
        SUMIF 用法：<code style={{ background: '#fff', border: '1px solid #ffe58f', borderRadius: 3, padding: '0 4px' }}>SUMIF([页签.条件字段]='值', [页签.值字段])</code>，可与运算符自由组合。
      </div>
    </div>
  );
};

export default FormulaEditorPanel;
