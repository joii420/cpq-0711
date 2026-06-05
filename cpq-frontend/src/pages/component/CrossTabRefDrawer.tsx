import React, { useState, useEffect } from 'react';
import { Drawer, Select, Button, Space, message } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';

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

const AGG_OPTIONS = [
  { value: 'NONE', label: '无 (取单行值)' },
  { value: 'SUM', label: '求和 (SUM)' },
  { value: 'AVG', label: '平均 (AVG)' },
  { value: 'COUNT', label: '计数 (COUNT)' },
  { value: 'MAX', label: '最大 (MAX)' },
  { value: 'MIN', label: '最小 (MIN)' },
];

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

  // Reset state when drawer opens or closes
  useEffect(() => {
    if (!open) {
      setSourceId('');
      setAgg('NONE');
      setTarget('');
      setMatchPairs([{ a: '', b: '' }]);
    }
  }, [open]);

  const sourceComp = siblingComponents.find((c) => c.id === sourceId) ?? null;
  const sourceFields = sourceComp?.fields ?? [];

  const handleSourceChange = (id: string) => {
    setSourceId(id);
    // Reset target and match pairs when source changes
    setTarget('');
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
    if (agg !== 'COUNT' && !target) {
      message.warning('请选择目标列 (A.b)，或将聚合方式改为"计数"');
      return;
    }

    const comp = siblingComponents.find((c) => c.id === sourceId);
    if (!comp) return;

    const token: CrossTabToken = {
      type: 'cross_tab_ref',
      source: sourceId,
      sourceLabel: comp.name,
      target: agg === 'COUNT' ? '' : target,
      match: completePairs,
      agg,
    };

    onConfirm(token);
    onClose();
  };

  const handleClose = () => {
    onClose();
  };

  return (
    <Drawer
      title="插入跨页签引用"
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
            <Button
              size="small"
              icon={<PlusOutlined />}
              onClick={handleAddPair}
              style={{ alignSelf: 'flex-start' }}
              disabled={!sourceId}
            >
              添加匹配条件
            </Button>
          </div>
        </div>

        {/* Aggregation */}
        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13 }}>
            3. 聚合方式
          </div>
          <Select
            style={{ width: 240 }}
            value={agg}
            onChange={(v) => {
              setAgg(v);
              if (v === 'COUNT') setTarget('');
            }}
            options={AGG_OPTIONS}
          />
        </div>

        {/* Target field */}
        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13 }}>
            4. 目标列 (A.字段)
            {agg === 'COUNT' && (
              <span style={{ fontWeight: 400, color: '#8c8c8c', marginLeft: 8, fontSize: 12 }}>
                聚合方式为"计数"时无需选择目标列
              </span>
            )}
          </div>
          <Select
            style={{ width: '100%' }}
            placeholder={agg === 'COUNT' ? '（计数模式，无需目标列）' : '选择要引用的 A 组件字段'}
            value={target || undefined}
            onChange={setTarget}
            options={sourceFields.map((f) => ({ label: f.name, value: f.name }))}
            disabled={!sourceId || agg === 'COUNT'}
            showSearch
            allowClear
            onClear={() => setTarget('')}
          />
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
              {agg === 'COUNT' ? '行数' : (target || '(未选目标列)')}
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
