import React, { useState } from 'react';
import { Table, Input, Select, Button, InputNumber, Popover, Tooltip } from 'antd';
import { DeleteOutlined, PlusOutlined, NumberOutlined } from '@ant-design/icons';
import FormulaZone from '../../components/formula/FormulaZone';
import type { FormulaItem, FormulaToken } from './types';
import { newFormulaRow } from './types';
import TreeRefDrawer from './TreeRefDrawer';
import { TREE_REF_FUNC_BUTTONS, treeAttrChipLabel, treeRefDisabledTooltip } from './crossTabText';
import './styles.css';

const RESULT_TYPE_OPTIONS = [
  { value: 'NUMBER', label: '数量' },
  { value: 'AMOUNT', label: '金额' },
  { value: 'STRING', label: '文本' },
];

const OPERATOR_BUTTONS: Array<{ label: string; token: FormulaToken }> = [
  { label: '＋', token: { type: 'operator', value: '+', label: '＋' } },
  { label: '－', token: { type: 'operator', value: '-', label: '－' } },
  { label: '×', token: { type: 'operator', value: '*', label: '×' } },
  { label: '÷', token: { type: 'operator', value: '/', label: '÷' } },
  { label: '（', token: { type: 'bracket_open', value: '(', label: '（' } },
  { label: '）', token: { type: 'bracket_close', value: ')', label: '）' } },
  { label: '%', token: { type: 'operator', value: '%', label: '%' } },
];

/** task-0803 F-6：树属性 chip 插入顺序（直接插入，无需抽屉）。 */
const TREE_ATTR_CHIPS: Array<{ attr: 'LVL' | 'IS_LEAF' | 'IS_ROOT' }> = [
  { attr: 'LVL' },
  { attr: 'IS_LEAF' },
  { attr: 'IS_ROOT' },
];

/** task-0803 F-3：「父子取值」抽屉当前正在配置/编辑的目标（null = 抽屉关闭）。 */
interface TreeRefDrawerState {
  dir: 'PARENT' | 'CHILD';
  agg: string;
  /** null = 插入新 chip（追加到表达式末尾）；number = 正在编辑已有 chip，回填该下标。 */
  editingIndex: number | null;
  initialTargetExpr: FormulaToken[];
}

interface FormulaBuilderProps {
  formulas: FormulaItem[];
  onChange: (formulas: FormulaItem[]) => void;
  availableFields: { name: string; type: string }[];
  availableSubtotals: { name: string; componentCode: string; componentName: string }[];
  activeFormulaKey: string | null;
  onActiveFormulaKeyChange: (key: string | null) => void;
  /**
   * task-0803 F-2：本组件的页签类型（'BOM' | '材质元素' | '零件' | '外购件' | '主件' | 未配置）。
   * 仅 tabType==='BOM' 才启用「父子取值」分区；其余情况分区置灰 + tooltip 说明原因，
   * 禁止用 `if (...) return null` 隐藏（项目 UI 规范硬性要求）。
   */
  tabType?: string;
}

const FormulaBuilder: React.FC<FormulaBuilderProps> = ({
  formulas,
  onChange,
  availableFields,
  availableSubtotals: _availableSubtotals,
  activeFormulaKey,
  onActiveFormulaKeyChange: setActiveFormulaKey,
  tabType,
}) => {
  const [numberPopoverOpen, setNumberPopoverOpen] = useState(false);
  const [numberInputValue, setNumberInputValue] = useState<number | null>(null);
  const [treeRefDrawer, setTreeRefDrawer] = useState<TreeRefDrawerState | null>(null);

  const updateFormula = (key: string, patch: Partial<FormulaItem>) => {
    onChange(formulas.map((f) => (f.key === key ? { ...f, ...patch } : f)));
  };

  const deleteFormula = (key: string) => {
    onChange(formulas.filter((f) => f.key !== key));
    if (activeFormulaKey === key) setActiveFormulaKey(null);
  };

  const appendToken = (token: FormulaToken) => {
    if (!activeFormulaKey) return;
    const formula = formulas.find((f) => f.key === activeFormulaKey);
    if (!formula) return;
    updateFormula(activeFormulaKey, {
      expression: [...formula.expression, token],
    });
  };

  const handleNumberConfirm = () => {
    if (numberInputValue === null || numberInputValue === undefined) return;
    const strVal = String(numberInputValue);
    appendToken({ type: 'number', value: strVal, label: strVal });
    setNumberInputValue(null);
    setNumberPopoverOpen(false);
  };

  // ── task-0803 F-1~F-8：BOM 页签「父子取值」（PGET/CSUM/CAVG/CMAX/CMIN/CCOUNT + 树属性）──
  // F-2：仅 BOM 组件启用；非 BOM 组件置灰 + tooltip 说明原因（不隐藏，见下方 JSX）。
  const isBomTab = tabType === 'BOM';
  const treeRefDisabledReason = isBomTab ? undefined : treeRefDisabledTooltip(tabType);
  const treeRefButtonsDisabled = !isBomTab || !activeFormulaKey;

  /** F-3：点函数按钮（PGET/CSUM/...）→ 打开配置抽屉，新建一个 tree_ref chip。 */
  const openTreeRefFunc = (dir: 'PARENT' | 'CHILD', agg: string) => {
    if (!activeFormulaKey) return;
    setTreeRefDrawer({ dir, agg, editingIndex: null, initialTargetExpr: [] });
  };

  /** F-6：树属性 chip（[层级]/[是否叶子]/[是否根]）直接插入，无需抽屉。 */
  const insertTreeAttr = (attr: 'LVL' | 'IS_LEAF' | 'IS_ROOT') => {
    appendToken({ type: 'tree_attr', attr });
  };

  /** F-5：点击已插入的 tree_ref chip → 重新打开抽屉编辑（回填其 dir/agg/targetExpr）。 */
  const handleTreeRefChipClick = (formulaKey: string, token: FormulaToken, index: number) => {
    setActiveFormulaKey(formulaKey);
    setTreeRefDrawer({
      dir: token.dir === 'PARENT' ? 'PARENT' : 'CHILD',
      agg: token.agg || 'NONE',
      editingIndex: index,
      initialTargetExpr: token.targetExpr || [],
    });
  };

  /** 抽屉确认：新建 → 追加到活动公式表达式末尾；编辑已有 chip → 原位替换（不改动其余 token 顺序）。 */
  const handleTreeRefConfirm = (token: FormulaToken) => {
    if (!activeFormulaKey || !treeRefDrawer) return;
    const formula = formulas.find((f) => f.key === activeFormulaKey);
    if (!formula) return;
    if (treeRefDrawer.editingIndex === null) {
      updateFormula(activeFormulaKey, { expression: [...formula.expression, token] });
    } else {
      const next = [...formula.expression];
      next[treeRefDrawer.editingIndex] = token;
      updateFormula(activeFormulaKey, { expression: next });
    }
    setTreeRefDrawer(null);
  };

  const columns = [
    {
      title: '公式名称',
      key: 'name',
      width: 160,
      render: (_: unknown, record: FormulaItem) => (
        <Input
          value={record.name}
          onChange={(e) => updateFormula(record.key, { name: e.target.value })}
          placeholder="公式名称（对应 FORMULA 字段名）"
          size="small"
          onClick={() => setActiveFormulaKey(record.key)}
        />
      ),
    },
    {
      title: '表达式',
      key: 'expression',
      render: (_: unknown, record: FormulaItem) => (
        <div onClick={() => setActiveFormulaKey(record.key)}>
          <FormulaZone
            tokens={record.expression}
            onChange={(tokens) => updateFormula(record.key, { expression: tokens })}
            onTokenClick={(token, idx) => handleTreeRefChipClick(record.key, token, idx)}
          />
          {activeFormulaKey === record.key && (
            <div
              style={{
                fontSize: 11,
                color: '#1677ff',
                marginTop: 2,
              }}
            >
              ● 活动中
            </div>
          )}
        </div>
      ),
    },
    {
      title: '结果类型',
      key: 'result_type',
      width: 90,
      render: (_: unknown, record: FormulaItem) => (
        <Select
          value={record.result_type || 'NUMBER'}
          onChange={(val) => updateFormula(record.key, { result_type: val })}
          options={RESULT_TYPE_OPTIONS}
          size="small"
          style={{ width: '100%' }}
        />
      ),
    },
    {
      key: 'action',
      width: 40,
      render: (_: unknown, record: FormulaItem) => (
        <Button
          type="text"
          size="small"
          danger
          icon={<DeleteOutlined />}
          onClick={() => deleteFormula(record.key)}
        />
      ),
    },
  ];

  return (
    <div className="cm-card-section">
      {/* Section header */}
      <div className="cm-card-section-header">
        <div className="cm-card-section-header-left">
          <span>🧮 公式管理</span>
          <span className="cm-section-badge">{formulas.length} 个</span>
        </div>
        <Button
          size="small"
          icon={<PlusOutlined />}
          onClick={() => {
            const row = newFormulaRow();
            onChange([...formulas, row]);
            setActiveFormulaKey(row.key);
          }}
        >
          添加公式
        </Button>
      </div>

      {/* Operator toolbar */}
      <div className="cm-formula-toolbar">
        <span className="cm-formula-toolbar-label">运算符:</span>
        {OPERATOR_BUTTONS.map((btn) => (
          <button
            key={btn.label}
            className="cm-op-chip"
            onClick={() => appendToken(btn.token)}
            disabled={!activeFormulaKey}
          >
            {btn.label}
          </button>
        ))}
        <Popover
          open={numberPopoverOpen}
          onOpenChange={(open) => {
            if (!activeFormulaKey) return;
            setNumberPopoverOpen(open);
            if (open) setNumberInputValue(null);
          }}
          trigger="click"
          placement="bottom"
          content={
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <InputNumber
                autoFocus
                size="small"
                placeholder="输入数字"
                value={numberInputValue}
                onChange={(v) => setNumberInputValue(v)}
                onPressEnter={handleNumberConfirm}
                style={{ width: 120 }}
              />
              <Button size="small" type="primary" onClick={handleNumberConfirm} disabled={numberInputValue === null}>
                确定
              </Button>
            </div>
          }
        >
          <button
            className="cm-op-chip"
            disabled={!activeFormulaKey}
            style={{ minWidth: 48 }}
          >
            <NumberOutlined style={{ fontSize: 12, marginRight: 2 }} />
            数字
          </button>
        </Popover>
        {!activeFormulaKey && (
          <span className="cm-op-chip-hint">（先点击公式行激活）</span>
        )}
      </div>

      {/* task-0803 F-1/F-2：父子取值分区 —— 仅 BOM 页签启用；非 BOM 置灰 + tooltip 说明原因（禁止隐藏） */}
      <div className="cm-formula-toolbar">
        <span className="cm-formula-toolbar-label">父子取值:</span>
        <Tooltip title={treeRefDisabledReason}>
          <span
            data-treeref-disabled-reason={treeRefDisabledReason ?? ''}
            style={{ display: 'inline-flex', flexWrap: 'wrap', gap: 6, alignItems: 'center' }}
          >
            {TREE_REF_FUNC_BUTTONS.map((btn) => (
              <button
                key={btn.key}
                className="cm-op-chip cm-treeref-chip"
                disabled={treeRefButtonsDisabled}
                onClick={() => openTreeRefFunc(btn.dir, btn.agg)}
              >
                {btn.key}
              </button>
            ))}
            {TREE_ATTR_CHIPS.map((c) => (
              <button
                key={c.attr}
                className="cm-op-chip cm-treeref-chip"
                disabled={treeRefButtonsDisabled}
                onClick={() => insertTreeAttr(c.attr)}
              >
                {treeAttrChipLabel(c.attr)}
              </button>
            ))}
          </span>
        </Tooltip>
        {isBomTab && !activeFormulaKey && (
          <span className="cm-op-chip-hint">（先点击公式行激活）</span>
        )}
      </div>

      {/* Formula table */}
      <Table
        dataSource={formulas}
        columns={columns}
        rowKey="key"
        pagination={false}
        size="small"
        rowClassName={(record) =>
          record.key === activeFormulaKey ? 'formula-row-active' : ''
        }
        onRow={(record) => ({
          onClick: () => setActiveFormulaKey(record.key),
          style: {
            background: record.key === activeFormulaKey ? '#f0f7ff' : undefined,
            cursor: 'pointer',
          },
        })}
        locale={{ emptyText: '暂无公式，点击"添加公式"' }}
      />

      {/* task-0803 F-3/F-4/F-7：父子取值配置抽屉 */}
      <TreeRefDrawer
        open={treeRefDrawer !== null}
        onClose={() => setTreeRefDrawer(null)}
        dir={treeRefDrawer?.dir ?? 'PARENT'}
        agg={treeRefDrawer?.agg ?? 'NONE'}
        availableFields={availableFields.map((f) => ({ name: f.name }))}
        initialTargetExpr={treeRefDrawer?.initialTargetExpr}
        onConfirm={handleTreeRefConfirm}
      />
    </div>
  );
};

export default FormulaBuilder;
