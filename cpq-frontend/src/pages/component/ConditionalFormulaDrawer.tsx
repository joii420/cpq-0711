import React, { useEffect, useState } from 'react';
import { Drawer, Select, Button, Space, Typography, Divider, message } from 'antd';
import type { CondTree } from '../../utils/condTree';
import CondTreeEditor, { emptyGroup } from './CondTreeEditor';

const { Text } = Typography;

export interface ConditionalFormulaValue {
  /**
   * BL-0098：`formula_id` 是解析主键（公式的稳定 id），`formula` 保留公式名作展示冗余 +
   * 存量兼容读。求值端优先按 id 认，公式改名不再静默丢规则。
   */
  rules: { when: CondTree; formula: string; formula_id?: string }[];
  default: string;
  default_formula_id?: string;
}

interface Props {
  open: boolean;
  value?: ConditionalFormulaValue;
  fieldName?: string;
  /** BL-0098：value = 公式稳定 id（无 id 的极老存量回退为公式名），label = 公式名 */
  formulaOptions: { label: string; value: string }[];
  /** 条件列候选：组件字段名 + task-0803 追加的树属性保留字（disabled=不可选，title=hover 原因） */
  columnOptions: { label: string; value: string; disabled?: boolean; title?: string }[];
  onClose: () => void;
  onConfirm: (next: ConditionalFormulaValue) => void;
}

const ConditionalFormulaDrawer: React.FC<Props> = ({
  open, value, fieldName, formulaOptions, columnOptions, onClose, onConfirm,
}) => {
  const [rules, setRules] = useState<{ when: CondTree; formula: string; formula_id?: string }[]>([]);
  const [def, setDef] = useState<string>('');
  const [defId, setDefId] = useState<string | undefined>(undefined);

  // BL-0098：下拉的选中值 = 公式 id；存量只有名字时按名字反查 id 回显，避免显示空白。
  const optValueOf = (id?: string, name?: string): string | undefined => {
    if (id && formulaOptions.some(o => o.value === id)) return id;
    if (name) {
      const byLabel = formulaOptions.find(o => o.label === name);
      if (byLabel) return byLabel.value;
    }
    return undefined;
  };
  const labelOf = (v?: string) => formulaOptions.find(o => o.value === v)?.label ?? '';

  useEffect(() => {
    if (open) {
      setRules(value?.rules?.length
        ? value.rules.map(r => ({ when: r.when || emptyGroup(), formula: r.formula, formula_id: r.formula_id }))
        : []);
      setDef(value?.default || '');
      setDefId(value?.default_formula_id);
    }
  }, [open, value]);

  const addRule = () => setRules([...rules, { when: emptyGroup(), formula: '' }]);
  const updateRule = (i: number, patch: Partial<{ when: CondTree; formula: string; formula_id?: string }>) =>
    setRules(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const removeRule = (i: number) => setRules(rules.filter((_, j) => j !== i));
  const move = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= rules.length) return;
    const next = [...rules]; [next[i], next[j]] = [next[j], next[i]]; setRules(next);
  };

  const handleConfirm = () => {
    if (rules.length === 0) { message.error('至少需 1 条规则'); return; }
    if (rules.some(r => !r.formula)) { message.error('每条规则都要选命中公式'); return; }
    if (!def) { message.error('必须选默认公式（全不命中时执行）'); return; }
    onConfirm({ rules, default: def, default_formula_id: defId });
  };

  return (
    <Drawer
      title={`条件公式配置 · ${fieldName || ''}`}
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      extra={<Space><Button onClick={onClose}>取消</Button><Button type="primary" onClick={handleConfirm}>确定</Button></Space>}
    >
      <Text type="secondary">规则按顺序求值，第一条条件成立的执行其公式；全不成立走默认公式。</Text>
      {rules.map((r, i) => (
        <div key={i} style={{ border: '1px solid #d9d9d9', borderRadius: 8, padding: 12, marginTop: 12 }}>
          <Space style={{ marginBottom: 8 }} wrap>
            <Text strong>规则 {i + 1}</Text>
            <Button size="small" disabled={i === 0} onClick={() => move(i, -1)}>↑</Button>
            <Button size="small" disabled={i === rules.length - 1} onClick={() => move(i, 1)}>↓</Button>
            <Button size="small" danger onClick={() => removeRule(i)}>删除规则</Button>
          </Space>
          <div style={{ marginBottom: 8 }}>
            <Text type="secondary">当满足：</Text>
            <CondTreeEditor value={r.when} columnOptions={columnOptions}
              onChange={nc => updateRule(i, { when: nc })} />
          </div>
          <Space>
            <Text type="secondary">则执行公式：</Text>
            <Select size="small" style={{ minWidth: 200 }} placeholder="选命中公式"
              value={optValueOf(r.formula_id, r.formula)} options={formulaOptions}
              showSearch optionFilterProp="label"
              onChange={v => updateRule(i, { formula_id: v, formula: labelOf(v) })} />
          </Space>
        </div>
      ))}
      <Button type="dashed" block style={{ marginTop: 12 }} onClick={addRule}>+ 加规则</Button>
      <Divider />
      <Space>
        <Text strong>默认公式（全不命中）：</Text>
        <Select style={{ minWidth: 220 }} placeholder="必选默认公式"
          value={optValueOf(defId, def)} options={formulaOptions} showSearch optionFilterProp="label"
          onChange={v => { setDefId(v); setDef(labelOf(v)); }} />
      </Space>
    </Drawer>
  );
};

export default ConditionalFormulaDrawer;
