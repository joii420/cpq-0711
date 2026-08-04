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
const OP_TITLES: Record<string, string> = {
  '+': '加', '-': '减', '*': '乘', '/': '除（除数为 0 → 整式返 0，不报错）',
  '(': '左括号', ')': '右括号',
};
const FUNC_TITLES: Record<string, string> = {
  SUM: 'SUM(明细列) 按对齐行求和；裸写明细列默认即为求和',
  AVG: 'AVG(明细列) 按对齐行求平均',
  MIN: 'MIN(明细列) 取最小',
  MAX: 'MAX(明细列) 取最大',
  COUNT: 'COUNT(明细列) 计数',
};

const TREE_FUNCS: [string, string][] = [
  ['PGET', '子取父：取父行该字段的值（唯一无需聚合）'],
  ['CSUM', '父取子：直接子行该字段求和'],
  ['CAVG', '父取子：直接子行该字段平均（分母只数有值的行）'],
  ['CMAX', '父取子：直接子行该字段最大值'],
  ['CMIN', '父取子：直接子行该字段最小值'],
  ['CCOUNT', '父取子：直接子行该字段的有值行数'],
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
//         + 图例两行 + 分组工具条。不持有 expression 状态，全部经 props 受控。
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

  // 工具条分组：一组 = 一行标题 + 一行按钮。EXCEL 视图列不解析 tree_ref，故不出「父子取值」组。
  type ToolItem = {
    label: string; title: string; onClick: () => void;
    style?: React.CSSProperties; disabledReason?: string | null;
  };
  const TOOL_GROUPS: { title: string; items: ToolItem[] }[] = [
    {
      title: '运算符',
      items: OPS.map(op => ({
        label: op,
        title: OP_TITLES[op] ?? op,
        style: { fontFamily: 'monospace', fontWeight: 600 },
        onClick: () => onInsert(op),
      })),
    },
    {
      title: '函数',
      items: FUNCS.map(fn => ({
        label: fn,
        title: FUNC_TITLES[fn] ?? fn,
        style: { color: '#fa8c16', borderColor: '#ffd591' },
        onClick: () => onInsert(`${fn}()`, 1),
      })),
    },
    {
      title: '条件聚合',
      items: SUMIF_TEXT_FUNCS.map(fn => ({
        label: fn,
        title: componentType === 'EXCEL'
          ? `${fn}(条件, 取值表达式)，如 ${fn}([页签.类型]='管理费', [页签.金额])`
          : `点击展开下方「条件聚合」构造器配置 ${fn}（条件过滤后按行键聚合）`,
        style: { color: '#722ed1', borderColor: '#d3adf7' },
        onClick: () => {
          if (componentType === 'EXCEL') onInsert(`${fn}()`, 1);
          else onOpenSumif(fn);
        },
      })),
    },
    ...(componentType === 'EXCEL' ? [] : [{
      title: '父子取值',
      // 2026-08-04：树属性 [层级]/[是否叶子]/[是否根] 的按钮已移除——它们的用途是当判据，
      // 现在可在「条件公式配置」的判据下拉里直接选，不必再塞进表达式。
      // 语言层仍解析这三个保留字（存量公式与夹具用例照常工作），只是不再从这里引导。
      items: TREE_FUNCS.map(([fn, desc]) => ({
        label: fn,
        title: `${fn}(字段名) —— ${desc}`,
        style: { color: '#531dab', borderColor: '#b37feb' },
        disabledReason: treeDisabledReason,
        onClick: () => onInsert(`${fn}()`, 1),
      })),
    }]),
  ];
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

      {/* 工具条：分组展示 —— 每组「一行标题 + 一行选项」（2026-08-04 按用户要求由单行 wrap 改为分组）。
          说明文案不再单独占框（原黄色规则提示 + 紫色父子取值提示已移除），改为逐按钮 title 悬浮，
          信息不丢但不占版面。完整规则见 dev-docs/rule-0724-组件模板配置/5-公式与Excel列.md。 */}
      <div style={{ marginTop: 12 }}>
        {TOOL_GROUPS.map((g) => (
          <div key={g.title} style={{ marginBottom: 8 }}>
            <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
              {g.title}
            </Text>
            <Space wrap size={4}>
              {g.items.map((it) => (
                <Tooltip key={it.label} title={it.disabledReason ?? it.title}>
                  {/* disabled 的 Button 不触发鼠标事件，Tooltip 需外面这层 span 才显示得出来 */}
                  <span style={{ display: 'inline-block' }}>
                    <Button
                      size="small"
                      disabled={!!it.disabledReason}
                      style={it.disabledReason ? undefined : it.style}
                      onClick={it.onClick}
                    >
                      {it.label}
                    </Button>
                  </span>
                </Tooltip>
              ))}
            </Space>
          </div>
        ))}
      </div>
    </div>
  );
};

export default FormulaEditorPanel;
