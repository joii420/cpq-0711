import React from 'react';
import { Button, Space, Tooltip, Typography } from 'antd';
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

// task-0803 F-5（2026-08-04 补）：父子取值函数 / 树属性保留字的一键插入。
// 此前只有下方紫色框的文字说明、没有按钮，用户在 BOM 页签打开抽屉「看不到这几个选项」，
// 只能照着说明手打——与 FUNCS/SUMIF 族有按钮的体验不一致。
// tuple: [函数名, 悬浮说明]
const TREE_FUNCS: [string, string][] = [
  ['PGET', '子取父：取父行该字段的值（唯一无需聚合）'],
  ['CSUM', '父取子：直接子行该字段求和'],
  ['CAVG', '父取子：直接子行该字段平均（分母只数有值的行）'],
  ['CMAX', '父取子：直接子行该字段最大值'],
  ['CMIN', '父取子：直接子行该字段最小值'],
  ['CCOUNT', '父取子：直接子行该字段的有值行数'],
];
// 树属性是保留字（含方括号），整体插入、光标落末尾
const TREE_ATTRS: [string, string][] = [
  ['[层级]', '当前行的树层级，根节点为 1，逐层 +1'],
  ['[是否叶子]', '没有子行 → 1，否则 0'],
  ['[是否根]', '没有父行 → 1，否则 0'],
];

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
  /**
   * task-0803：正在编辑公式的组件的页签类型。父子取值按钮组据此启用/置灰
   * （AC-16：非 BOM 页签必须**可见但置灰 + hover 有原因**，不得隐藏）。
   */
  tabType?: string;
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
  tabType,
}) => {
  // task-0803 F-5：父子取值仅 BOM 类型页签可用。口径与保存前的 checkTreeRefTabTypeGate
  // （TabJoinFormulaDrawer.tsx）保持一致 —— 那边是硬闸（返 400/拦保存），这里是软提示（置灰）。
  // 两处都改时务必同步，否则会出现「按钮可点但保存被拒」或反之。
  const treeDisabled = tabType !== 'BOM';
  const treeDisabledReason = treeDisabled
    ? `父子取值与树属性仅 BOM 类型页签可用（当前页签类型：${tabType ?? '未配置'}）`
    : null;
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

        {/* task-0803 F-5：父子取值（仅 BOM 页签）。AC-16 要求非 BOM 时**可见但置灰 + hover 说明原因**，
            故这里用 disabled 而非条件隐藏；EXCEL 视图列不解析 tree_ref，整组不出现。 */}
        {componentType !== 'EXCEL' && (
          <>
            <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
              父子取值
            </Text>
            {TREE_FUNCS.map(([fn, desc]) => (
              <Tooltip key={fn} title={treeDisabledReason ?? `${fn}(字段名) —— ${desc}`}>
                {/* disabled 的 Button 不触发鼠标事件，Tooltip 需外面这层 span 才能显示原因 */}
                <span style={{ display: 'inline-block' }}>
                  <Button
                    size="small"
                    disabled={treeDisabled}
                    style={treeDisabled ? undefined : { color: '#531dab', borderColor: '#b37feb' }}
                    onClick={() => onInsert(`${fn}()`, 1)}
                  >
                    {fn}
                  </Button>
                </span>
              </Tooltip>
            ))}
            {TREE_ATTRS.map(([attr, desc]) => (
              <Tooltip key={attr} title={treeDisabledReason ?? desc}>
                <span style={{ display: 'inline-block' }}>
                  <Button
                    size="small"
                    disabled={treeDisabled}
                    style={treeDisabled ? undefined : { color: '#531dab', borderColor: '#b37feb' }}
                    onClick={() => onInsert(attr)}
                  >
                    {attr}
                  </Button>
                </span>
              </Tooltip>
            ))}
          </>
        )}
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

      {/* task-0803 F-4：父子取值语法提示（仅 NORMAL/SUBTOTAL 组件公式；EXCEL 视图列不解析 tree_ref） */}
      {componentType !== 'EXCEL' && (
        <div
          style={{
            marginTop: 8,
            padding: '8px 12px',
            background: '#f6f0ff',
            border: '1px solid #d3adf7',
            borderRadius: 6,
            fontSize: 12,
            color: '#531dab',
            lineHeight: 1.7,
          }}
        >
          <strong>父子取值（仅 BOM 类型页签可用）</strong>：括号内字段名裸写，不加方括号（父子取值永远指向本页签字段，无需跨页签消歧义）。
          <br />
          <code style={{ background: '#fff', border: '1px solid #d3adf7', borderRadius: 3, padding: '0 4px' }}>PGET(字段名)</code>
          {' '}取父行该字段的值（子取父，唯一无需聚合）；
          <code style={{ background: '#fff', border: '1px solid #d3adf7', borderRadius: 3, padding: '0 4px', marginLeft: 4 }}>CSUM(字段名)</code>
          {' / '}
          <code style={{ background: '#fff', border: '1px solid #d3adf7', borderRadius: 3, padding: '0 4px' }}>CAVG(字段名)</code>
          {' / '}
          <code style={{ background: '#fff', border: '1px solid #d3adf7', borderRadius: 3, padding: '0 4px' }}>CMAX(字段名)</code>
          {' / '}
          <code style={{ background: '#fff', border: '1px solid #d3adf7', borderRadius: 3, padding: '0 4px' }}>CMIN(字段名)</code>
          {' / '}
          <code style={{ background: '#fff', border: '1px solid #d3adf7', borderRadius: 3, padding: '0 4px' }}>CCOUNT(字段名)</code>
          {' '}取「直接子」行该字段的求和/平均/最大/最小/计数（父取子，需聚合），如 <code style={{ background: '#fff', border: '1px solid #d3adf7', borderRadius: 3, padding: '0 4px' }}>CSUM(用量 * 单价)</code>。
          <br />
          树属性保留字（与同名字段无关，优先解析为保留字；同样仅 BOM 类型页签可用）：
          <code style={{ background: '#fff', border: '1px solid #d3adf7', borderRadius: 3, padding: '0 4px' }}>[层级]</code>
          {'（根为 1，逐层 +1） '}
          <code style={{ background: '#fff', border: '1px solid #d3adf7', borderRadius: 3, padding: '0 4px' }}>[是否叶子]</code>
          {' '}
          <code style={{ background: '#fff', border: '1px solid #d3adf7', borderRadius: 3, padding: '0 4px' }}>[是否根]</code>
        </div>
      )}
    </div>
  );
};

export default FormulaEditorPanel;
