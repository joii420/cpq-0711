import React, { useEffect, useState } from 'react';
import { Drawer, Select, Button, Space, Typography, Divider, message } from 'antd';
import type { CondTree } from '../../utils/condTree';
import CondTreeEditor, { emptyGroup } from './CondTreeEditor';

const { Text } = Typography;

export interface ConditionalFormulaValue {
  rules: { when: CondTree; formula: string }[];
  default: string;
}

interface Props {
  open: boolean;
  value?: ConditionalFormulaValue;
  fieldName?: string;
  formulaOptions: { label: string; value: string }[]; // 组件公式名
  columnOptions: { label: string; value: string }[];   // 组件字段名（条件列）
  onClose: () => void;
  onConfirm: (next: ConditionalFormulaValue) => void;
}

const ConditionalFormulaDrawer: React.FC<Props> = ({
  open, value, fieldName, formulaOptions, columnOptions, onClose, onConfirm,
}) => {
  const [rules, setRules] = useState<{ when: CondTree; formula: string }[]>([]);
  const [def, setDef] = useState<string>('');

  useEffect(() => {
    if (open) {
      setRules(value?.rules?.length ? value.rules.map(r => ({ when: r.when || emptyGroup(), formula: r.formula })) : []);
      setDef(value?.default || '');
    }
  }, [open, value]);

  const addRule = () => setRules([...rules, { when: emptyGroup(), formula: '' }]);
  const updateRule = (i: number, patch: Partial<{ when: CondTree; formula: string }>) =>
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
    onConfirm({ rules, default: def });
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
              value={r.formula || undefined} options={formulaOptions} showSearch optionFilterProp="label"
              onChange={v => updateRule(i, { formula: v })} />
          </Space>
        </div>
      ))}
      <Button type="dashed" block style={{ marginTop: 12 }} onClick={addRule}>+ 加规则</Button>
      <Divider />
      <Space>
        <Text strong>默认公式（全不命中）：</Text>
        <Select style={{ minWidth: 220 }} placeholder="必选默认公式"
          value={def || undefined} options={formulaOptions} showSearch optionFilterProp="label"
          onChange={setDef} />
      </Space>
    </Drawer>
  );
};

export default ConditionalFormulaDrawer;
