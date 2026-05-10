import React, { useState } from 'react';
import { Table, Input, Select, Button, InputNumber, Popover } from 'antd';
import { DeleteOutlined, PlusOutlined, NumberOutlined } from '@ant-design/icons';
import FormulaZone from '../../components/formula/FormulaZone';
import type { FormulaItem, FormulaToken } from './types';
import { newFormulaRow } from './types';
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

interface FormulaBuilderProps {
  formulas: FormulaItem[];
  onChange: (formulas: FormulaItem[]) => void;
  availableFields: { name: string; type: string }[];
  availableSubtotals: { name: string; componentCode: string; componentName: string }[];
  activeFormulaKey: string | null;
  onActiveFormulaKeyChange: (key: string | null) => void;
}

const FormulaBuilder: React.FC<FormulaBuilderProps> = ({
  formulas,
  onChange,
  availableFields: _availableFields,
  availableSubtotals: _availableSubtotals,
  activeFormulaKey,
  onActiveFormulaKeyChange: setActiveFormulaKey,
}) => {
  const [numberPopoverOpen, setNumberPopoverOpen] = useState(false);
  const [numberInputValue, setNumberInputValue] = useState<number | null>(null);

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
    </div>
  );
};

export default FormulaBuilder;
