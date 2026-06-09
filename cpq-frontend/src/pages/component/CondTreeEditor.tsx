import React from 'react';
import { Select, Input, Button, Space, Segmented } from 'antd';
import type { CondTree, CondOp } from '../../utils/condTree';

const OP_OPTIONS: { label: string; value: CondOp }[] = [
  { label: '=', value: 'eq' }, { label: '≠', value: 'ne' },
  { label: '>', value: 'gt' }, { label: '≥', value: 'gte' },
  { label: '<', value: 'lt' }, { label: '≤', value: 'lte' },
  { label: '∈ 在…中', value: 'in' },
];

export const emptyLeaf = (col: string): CondTree =>
  ({ kind: 'leaf', left: col, op: 'eq', rhs: { type: 'literal', value: '' } });
export const emptyGroup = (): CondTree => ({ kind: 'group', logic: 'and', children: [] });

interface Props {
  value: CondTree;
  onChange: (next: CondTree) => void;
  columnOptions: { label: string; value: string }[];
  onRemove?: () => void;
  depth?: number;
}

const CondTreeEditor: React.FC<Props> = ({ value, onChange, columnOptions, onRemove, depth = 0 }) => {
  if (value.kind === 'leaf') {
    const rhs = value.rhs;
    return (
      <Space wrap size={4} style={{ marginBottom: 4 }}>
        <Select size="small" style={{ minWidth: 120 }} placeholder="列" value={value.left || undefined}
          options={columnOptions} showSearch optionFilterProp="label"
          onChange={v => onChange({ ...value, left: v })} />
        <Select size="small" style={{ width: 96 }} value={value.op} options={OP_OPTIONS}
          onChange={(v) => onChange({ ...value, op: v as CondOp })} />
        <Segmented size="small" value={rhs.type}
          options={[{ label: '值', value: 'literal' }, { label: '列', value: 'column' }]}
          onChange={(t) => onChange({ ...value, rhs: { type: t as 'literal' | 'column', value: '' } })} />
        {rhs.type === 'literal'
          ? <Input size="small" style={{ width: 130 }} placeholder={value.op === 'in' ? '逗号分隔' : '值'}
              value={rhs.value} onChange={e => onChange({ ...value, rhs: { ...rhs, value: e.target.value } })} />
          : <Select size="small" style={{ minWidth: 120 }} placeholder="列" value={rhs.value || undefined}
              options={columnOptions} showSearch optionFilterProp="label"
              onChange={v => onChange({ ...value, rhs: { type: 'column', value: v } })} />}
        {onRemove && <Button size="small" type="text" danger onClick={onRemove}>✕</Button>}
      </Space>
    );
  }
  // group
  const children = value.children || [];
  return (
    <div style={{ border: '1px solid #eee', borderRadius: 6, padding: 8, marginBottom: 6, background: depth % 2 ? '#fafafa' : '#fff' }}>
      <Space style={{ marginBottom: 6 }} wrap>
        <Segmented size="small" value={value.logic}
          options={[{ label: '且 AND', value: 'and' }, { label: '或 OR', value: 'or' }]}
          onChange={(l) => onChange({ ...value, logic: l as 'and' | 'or' })} />
        <Button size="small" onClick={() => onChange({ ...value, children: [...children, emptyLeaf(columnOptions[0]?.value || '')] })}>+ 条件</Button>
        <Button size="small" onClick={() => onChange({ ...value, children: [...children, emptyGroup()] })}>+ 分组</Button>
        {onRemove && <Button size="small" type="text" danger onClick={onRemove}>删分组</Button>}
      </Space>
      <div style={{ paddingLeft: 12 }}>
        {children.length === 0 && <span style={{ color: '#999', fontSize: 12 }}>（空分组 = 恒真）</span>}
        {children.map((c, i) => (
          <CondTreeEditor key={i} value={c} columnOptions={columnOptions} depth={depth + 1}
            onChange={nc => { const next = [...children]; next[i] = nc; onChange({ ...value, children: next }); }}
            onRemove={() => onChange({ ...value, children: children.filter((_, j) => j !== i) })} />
        ))}
      </div>
    </div>
  );
};

export default CondTreeEditor;
