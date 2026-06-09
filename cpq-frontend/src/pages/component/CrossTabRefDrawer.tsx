import React, { useState, useEffect } from 'react';
import { Drawer, Select, Button, Space, Switch, InputNumber, Tag, message, Segmented } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import type { FormulaToken } from './types';
import { OPERATIONS, operationToAgg } from './crossTabText';

export interface SiblingComponent {
  id: string;
  code: string;
  name: string;
  fields: Array<{ name: string; label?: string }>;
}

/** 字段下拉显示文案：优先中文说明(label)，回退字段名；value 始终用 name（引擎按 name 匹配）。 */
const fieldText = (f: { name: string; label?: string }) => f.label || f.name;

interface MatchPair {
  a: string;
  b: string;
}

interface CrossTabToken {
  type: 'cross_tab_ref';
  source: string;
  sourceLabel: string;
  target: string;
  targetExpr?: FormulaToken[];
  match: Array<{ a: string; b: string }>;
  agg: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** Candidate source (A) components — same directory, excluding current component */
  siblingComponents: SiblingComponent[];
  /** Current (B) component fields, for B-side match column */
  currentFields: Array<{ name: string; label?: string }>;
  onConfirm: (token: CrossTabToken) => void;
}

/** Map a targetExpr token to a short readable string for chip/preview display. */
function tokenToText(tok: FormulaToken): string {
  switch (tok.type) {
    case 'field':
      return `A.${tok.label || tok.value}`;
    case 'b_field':
      return `本.${tok.label || tok.value}`;
    case 'operator':
      if (tok.value === '*') return '×';
      if (tok.value === '/') return '÷';
      return tok.value || '';
    case 'bracket_open':
      return '(';
    case 'bracket_close':
      return ')';
    case 'number':
      return tok.value || '';
    case 'global_variable':
      return tok.code || tok.label || '全局变量';
    default:
      return tok.value || '';
  }
}

/** Background/border/color for targetExpr chips inside the formula builder. */
function exprChipStyle(type: FormulaToken['type']): React.CSSProperties {
  switch (type) {
    case 'field':
      return { background: '#e1f0ff', border: '1px solid #c6e0ff', color: '#1677ff' };
    case 'b_field':
      return { background: '#e1f0ff', border: '1px solid #c6e0ff', color: '#1677ff' };
    case 'operator':
    case 'bracket_open':
    case 'bracket_close':
      return { background: '#f0f9eb', border: '1px solid #d5f0c2', color: '#52c41a' };
    case 'number':
      return { background: '#f5f5f5', border: '1px solid #d9d9d9', color: '#595959' };
    case 'global_variable':
      return { background: '#fff7e6', border: '1px solid #ffd591', color: '#d46b08' };
    default:
      return { background: '#f5f5f5', border: '1px solid #d9d9d9', color: '#595959' };
  }
}

const CrossTabRefDrawer: React.FC<Props> = ({
  open,
  onClose,
  siblingComponents,
  currentFields,
  onConfirm,
}) => {
  const [sourceId, setSourceId] = useState<string>('');
  const [agg, setAgg] = useState<string>('NONE');
  const [target, setTarget] = useState<string>('');
  const [matchPairs, setMatchPairs] = useState<MatchPair[]>([{ a: '', b: '' }]);

  // Formula mode state
  const [useFormula, setUseFormula] = useState<boolean>(false);
  const [targetExpr, setTargetExpr] = useState<FormulaToken[]>([]);
  // For inserting a number
  const [numInput, setNumInput] = useState<number | null>(null);
  // Controlled select values (reset after appending)
  const [aFieldSel, setAFieldSel] = useState<string | undefined>(undefined);
  const [bFieldSel, setBFieldSel] = useState<string | undefined>(undefined);
  const [mode, setMode] = useState<'simple' | 'advanced'>('simple');
  const [operation, setOperation] = useState<string>('single'); // OPERATIONS.key

  // Reset state when drawer opens or closes
  useEffect(() => {
    if (!open) {
      setSourceId('');
      setAgg('NONE');
      setTarget('');
      setMatchPairs([{ a: '', b: '' }]);
      setUseFormula(false);
      setTargetExpr([]);
      setNumInput(null);
      setAFieldSel(undefined);
      setBFieldSel(undefined);
      setMode('simple');
      setOperation('single');
    }
  }, [open]);

  const sourceComp = siblingComponents.find((c) => c.id === sourceId) ?? null;
  const sourceFields = sourceComp?.fields ?? [];

  const handleSourceChange = (id: string) => {
    setSourceId(id);
    // Reset target and match pairs when source changes
    setTarget('');
    setTargetExpr([]);
    setMatchPairs([{ a: '', b: '' }]);
  };

  const handleAddPair = () => {
    setMatchPairs((prev) => [...prev, { a: '', b: '' }]);
  };

  const handleRemovePair = (index: number) => {
    setMatchPairs((prev) => prev.filter((_, i) => i !== index));
  };

  const handlePairChange = (index: number, side: 'a' | 'b', value: string) => {
    setMatchPairs((prev) =>
      prev.map((p, i) => (i === index ? { ...p, [side]: value } : p))
    );
  };

  // ---- targetExpr formula builder helpers ----
  const appendToken = (tok: FormulaToken) => setTargetExpr((prev) => [...prev, tok]);
  const popToken = () => setTargetExpr((prev) => prev.slice(0, -1));
  const clearExpr = () => setTargetExpr([]);

  const handleConfirm = () => {
    if (!sourceId) {
      message.warning('请选择源页签 (A)');
      return;
    }
    const completePairs = matchPairs.filter((p) => p.a && p.b);
    if (completePairs.length === 0) {
      message.warning('请至少填写一组完整的匹配列对 (A.列 和 本.列 均需选择)');
      return;
    }
    if (agg !== 'COUNT') {
      if (useFormula) {
        if (targetExpr.length === 0) {
          message.warning('请选择目标列或填写目标公式');
          return;
        }
      } else {
        if (!target) {
          message.warning('请选择目标列 (A.b)，或将聚合方式改为"计数"');
          return;
        }
      }
    }

    const comp = siblingComponents.find((c) => c.id === sourceId);
    if (!comp) return;

    const token: CrossTabToken = {
      type: 'cross_tab_ref',
      source: sourceId,
      sourceLabel: comp.name,
      target: (useFormula && agg !== 'COUNT') ? '' : (agg === 'COUNT' ? '' : target),
      targetExpr: (useFormula && agg !== 'COUNT' && targetExpr.length > 0) ? targetExpr : undefined,
      match: completePairs,
      agg,
    };

    onConfirm(token);
    onClose();
  };

  const handleClose = () => {
    onClose();
  };

  // Preview text for the bottom preview box
  const targetPreviewText = () => {
    if (agg === 'COUNT') return '行数';
    if (useFormula) {
      if (targetExpr.length === 0) return '(未填写公式)';
      return targetExpr.map(tokenToText).join(' ');
    }
    return target || '(未选目标列)';
  };

  return (
    <Drawer
      title={
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <span>跨页签公式构建器</span>
          <Segmented
            size="small"
            value={mode}
            onChange={(v) => setMode(v as 'simple' | 'advanced')}
            options={[{ label: '简单', value: 'simple' }, { label: '高级', value: 'advanced' }]}
          />
        </div>
      }
      placement="right"
      width={720}
      open={open}
      onClose={handleClose}
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={handleClose}>取消</Button>
            <Button type="primary" onClick={handleConfirm}>
              确定
            </Button>
          </Space>
        </div>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
        {/* Source component (A) */}
        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13 }}>
            1. 源页签 (A)
            <span style={{ fontWeight: 400, color: '#8c8c8c', marginLeft: 8, fontSize: 12 }}>
              选择同产品模板内的另一个组件作为数据来源
            </span>
          </div>
          <Select
            style={{ width: '100%' }}
            placeholder="选择源组件"
            value={sourceId || undefined}
            onChange={handleSourceChange}
            options={siblingComponents.map((c) => ({
              label: c.name,
              value: c.id,
            }))}
            showSearch
            filterOption={(input, option) => {
              const c = siblingComponents.find((x) => x.id === option?.value);
              const t = input.toLowerCase();
              return !!c && (c.name.toLowerCase().includes(t) || c.code.toLowerCase().includes(t));
            }}
            allowClear
            onClear={() => handleSourceChange('')}
          />
          {siblingComponents.length === 0 && (
            <div style={{ color: '#faad14', fontSize: 12, marginTop: 4 }}>
              当前目录下暂无其他组件可供引用
            </div>
          )}
        </div>

        {/* Match pairs */}
        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13 }}>
            2. 匹配列对
            <span style={{ fontWeight: 400, color: '#8c8c8c', marginLeft: 8, fontSize: 12 }}>
              A 的某列值等于本组件 (B) 的某列值时行匹配
            </span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {matchPairs.map((pair, index) => (
              <div key={index} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Select
                  style={{ flex: 1 }}
                  placeholder="A.列"
                  value={pair.a || undefined}
                  onChange={(v) => handlePairChange(index, 'a', v)}
                  options={sourceFields.map((f) => ({ label: fieldText(f), value: f.name }))}
                  disabled={!sourceId}
                  showSearch
                  allowClear
                  onClear={() => handlePairChange(index, 'a', '')}
                />
                <span style={{ color: '#8c8c8c', fontWeight: 500 }}>=</span>
                <Select
                  style={{ flex: 1 }}
                  placeholder="本.列 (B)"
                  value={pair.b || undefined}
                  onChange={(v) => handlePairChange(index, 'b', v)}
                  options={currentFields.map((f) => ({ label: fieldText(f), value: f.name }))}
                  showSearch
                  allowClear
                  onClear={() => handlePairChange(index, 'b', '')}
                />
                <Button
                  type="text"
                  danger
                  size="small"
                  icon={<DeleteOutlined />}
                  onClick={() => handleRemovePair(index)}
                  disabled={matchPairs.length === 1}
                />
              </div>
            ))}
            {mode === 'advanced' && (
              <Button
                size="small"
                icon={<PlusOutlined />}
                onClick={handleAddPair}
                style={{ alignSelf: 'flex-start' }}
                disabled={!sourceId}
              >
                添加匹配条件
              </Button>
            )}
          </div>
        </div>

        {/* 1.5 要算什么（操作选择器，驱动 agg） */}
        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13 }}>
            要算什么
            <span style={{ fontWeight: 400, color: '#8c8c8c', marginLeft: 8, fontSize: 12 }}>
              从源页签按匹配行取值或汇总
            </span>
          </div>
          <Select
            style={{ width: 280 }}
            value={operation}
            onChange={(v) => {
              setOperation(v);
              const a = operationToAgg(v);
              setAgg(a);
              if (a === 'COUNT') setTarget('');
            }}
            options={OPERATIONS.filter((o) => mode === 'advanced' || o.simple).map((o) => ({
              label: o.label, value: o.key,
            }))}
          />
        </div>

        {/* Target field / formula — section 4 */}
        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13, display: 'flex', alignItems: 'center', gap: 8 }}>
            4. 目标列 (A.字段)
            {agg === 'COUNT' ? (
              <span style={{ fontWeight: 400, color: '#8c8c8c', fontSize: 12 }}>
                聚合方式为"计数"时无需选择目标列
              </span>
            ) : (
              <span style={{ fontWeight: 400, fontSize: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{ color: '#8c8c8c' }}>模式：</span>
                <Switch
                  checked={useFormula}
                  onChange={setUseFormula}
                  checkedChildren="公式"
                  unCheckedChildren="单列"
                  size="small"
                />
              </span>
            )}
          </div>

          {agg !== 'COUNT' && (
            <>
              {/* Single column mode */}
              {!useFormula && (
                <Select
                  style={{ width: '100%' }}
                  placeholder="选择要引用的 A 组件字段"
                  value={target || undefined}
                  onChange={setTarget}
                  options={sourceFields.map((f) => ({ label: f.name, value: f.name }))}
                  disabled={!sourceId}
                  showSearch
                  allowClear
                  onClear={() => setTarget('')}
                />
              )}

              {/* Formula mode */}
              {useFormula && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {/* Chip display of current expression */}
                  <div
                    style={{
                      border: '1px dashed #c0c4cc',
                      borderRadius: 4,
                      minHeight: 36,
                      padding: '4px 6px',
                      display: 'flex',
                      flexWrap: 'wrap',
                      gap: 4,
                      alignItems: 'center',
                      background: '#f9f9f9',
                    }}
                  >
                    {targetExpr.length === 0 ? (
                      <span style={{ color: '#c0c4cc', fontSize: 12, userSelect: 'none' }}>
                        使用下方控件构建目标公式
                      </span>
                    ) : (
                      targetExpr.map((tok, idx) => {
                        const cs = exprChipStyle(tok.type);
                        return (
                          <Tag
                            key={idx}
                            closable
                            onClose={(e) => {
                              e.preventDefault();
                              setTargetExpr((prev) => prev.filter((_, i) => i !== idx));
                            }}
                            style={{
                              ...cs,
                              borderRadius: 3,
                              fontSize: 12,
                              margin: 0,
                              padding: '1px 4px',
                            }}
                          >
                            {tokenToText(tok)}
                          </Tag>
                        );
                      })
                    )}
                  </div>

                  {/* Insert controls row 1: A 字段 / 本组件字段 */}
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                    <span style={{ fontSize: 12, color: '#595959', whiteSpace: 'nowrap' }}>A 字段：</span>
                    <Select
                      size="small"
                      style={{ width: 160 }}
                      placeholder="选择 A 字段"
                      value={aFieldSel}
                      disabled={!sourceId}
                      options={sourceFields.map((f) => ({ label: fieldText(f), value: f.name }))}
                      onChange={(v) => {
                        const f = sourceFields.find((x) => x.name === v);
                        if (f) {
                          appendToken({ type: 'field', value: f.name, label: fieldText(f) });
                        }
                        setAFieldSel(undefined);
                      }}
                      showSearch
                    />
                    <span style={{ fontSize: 12, color: '#595959', whiteSpace: 'nowrap' }}>本组件字段：</span>
                    <Select
                      size="small"
                      style={{ width: 160 }}
                      placeholder="选择本组件字段"
                      value={bFieldSel}
                      options={currentFields.map((f) => ({ label: fieldText(f), value: f.name }))}
                      onChange={(v) => {
                        const f = currentFields.find((x) => x.name === v);
                        if (f) {
                          appendToken({ type: 'b_field', value: f.name, label: fieldText(f) });
                        }
                        setBFieldSel(undefined);
                      }}
                      showSearch
                    />
                  </div>

                  {/* Insert controls row 2: 运算符 + 数字 */}
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                    <span style={{ fontSize: 12, color: '#595959', whiteSpace: 'nowrap' }}>运算符：</span>
                    {(['+', '-', '×', '÷', '(', ')'] as const).map((sym) => {
                      const opValue = sym === '×' ? '*' : sym === '÷' ? '/' : sym;
                      const isOp = sym === '+' || sym === '-' || sym === '×' || sym === '÷';
                      return (
                        <Button
                          key={sym}
                          size="small"
                          style={{ minWidth: 32, padding: '0 6px' }}
                          onClick={() => {
                            if (isOp) {
                              appendToken({ type: 'operator', value: opValue });
                            } else if (sym === '(') {
                              appendToken({ type: 'bracket_open', value: '(' });
                            } else {
                              appendToken({ type: 'bracket_close', value: ')' });
                            }
                          }}
                        >
                          {sym}
                        </Button>
                      );
                    })}
                    <span style={{ fontSize: 12, color: '#595959', whiteSpace: 'nowrap', marginLeft: 8 }}>数字：</span>
                    <InputNumber
                      size="small"
                      style={{ width: 90 }}
                      value={numInput}
                      onChange={(v) => setNumInput(v)}
                      placeholder="输入数字"
                    />
                    <Button
                      size="small"
                      disabled={numInput === null || numInput === undefined}
                      onClick={() => {
                        if (numInput !== null && numInput !== undefined) {
                          appendToken({ type: 'number', value: String(numInput) });
                          setNumInput(null);
                        }
                      }}
                    >
                      添加
                    </Button>
                    {/* TODO 第二期/后续: 全局变量插入 */}
                  </div>

                  {/* Delete last / clear */}
                  <div style={{ display: 'flex', gap: 8 }}>
                    <Button size="small" danger onClick={popToken} disabled={targetExpr.length === 0}>
                      删末
                    </Button>
                    <Button size="small" danger onClick={clearExpr} disabled={targetExpr.length === 0}>
                      清空
                    </Button>
                  </div>

                  {/* Preview line */}
                  {targetExpr.length > 0 && (
                    <div style={{ fontSize: 12, color: '#8c8c8c' }}>
                      表达式预览：<span style={{ color: '#1677ff' }}>{targetExpr.map(tokenToText).join(' ')}</span>
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </div>

        {/* Preview */}
        {sourceId && matchPairs.some((p) => p.a && p.b) && (
          <div
            style={{
              background: '#f6f0ff',
              border: '1px solid #d9b3ff',
              borderRadius: 6,
              padding: '10px 14px',
              fontSize: 12,
              color: '#531dab',
            }}
          >
            <strong>预览：</strong>
            <span>
              跨页签[{sourceComp?.name}].
              {agg !== 'NONE' ? `${agg}.` : ''}
              {targetPreviewText()}
              {' '}当[
              {matchPairs
                .filter((p) => p.a && p.b)
                .map((p) => `${p.a}=本.${p.b}`)
                .join(' 且 ')}
              ]
            </span>
          </div>
        )}
      </div>
    </Drawer>
  );
};

export default CrossTabRefDrawer;
