import React from 'react';
import { Badge, Input, Radio, Space, Tag, Tooltip, Typography } from 'antd';
import { CalculatorOutlined } from '@ant-design/icons';
import type { Decision, Importance } from '../../types/import-v5';

const { Text } = Typography;
const { TextArea } = Input;

interface DiffRowItemProps {
  importance: Importance;
  affectsCalculation: boolean;
  fieldLabel: string;
  oldValue: any;
  newValue: any;
  decision: Decision;
  note: string;
  onDecisionChange: (d: Decision) => void;
  onNoteChange: (n: string) => void;
}

const IMPORTANCE_CONFIG: Record<Importance, { color: string; label: string }> = {
  CRITICAL: { color: 'red', label: '关键' },
  IMPORTANT: { color: 'orange', label: '重要' },
  NORMAL: { color: 'default', label: '普通' },
};

function formatValue(v: any): string {
  if (v === null || v === undefined) return '(空)';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

const DiffRowItem: React.FC<DiffRowItemProps> = ({
  importance,
  affectsCalculation,
  fieldLabel,
  oldValue,
  newValue,
  decision,
  note,
  onDecisionChange,
  onNoteChange,
}) => {
  const isCritical = importance === 'CRITICAL';
  const noteRequired = isCritical && decision === 'ACCEPT_NEW';
  const noteMissing = noteRequired && !note.trim();
  const cfg = IMPORTANCE_CONFIG[importance];

  return (
    <div
      style={{
        padding: '12px 16px',
        border: `1px solid ${noteMissing ? '#ff4d4f' : '#f0f0f0'}`,
        borderRadius: 6,
        marginBottom: 8,
        background: '#fafafa',
      }}
    >
      {/* 字段名行 */}
      <Space size={6} style={{ marginBottom: 8 }}>
        <Text strong style={{ fontSize: 14 }}>
          {fieldLabel}
        </Text>
        <Tag color={cfg.color}>{cfg.label}</Tag>
        {affectsCalculation && (
          <Tooltip title="影响公式重算">
            <Badge
              count={
                <CalculatorOutlined
                  style={{ color: '#fa8c16', fontSize: 11 }}
                />
              }
              offset={[0, 0]}
            >
              <span
                style={{
                  display: 'inline-block',
                  width: 16,
                  height: 16,
                }}
              />
            </Badge>
          </Tooltip>
        )}
      </Space>

      {/* 值对比行 */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr 1fr',
          gap: 12,
          marginBottom: 10,
        }}
      >
        <div
          style={{
            padding: '6px 10px',
            background: '#fff1f0',
            borderRadius: 4,
            border: '1px solid #ffccc7',
          }}
        >
          <Text type="secondary" style={{ fontSize: 11 }}>
            原值
          </Text>
          <br />
          <Text style={{ fontSize: 13 }}>{formatValue(oldValue)}</Text>
        </div>
        <div
          style={{
            padding: '6px 10px',
            background: '#f6ffed',
            borderRadius: 4,
            border: '1px solid #b7eb8f',
          }}
        >
          <Text type="secondary" style={{ fontSize: 11 }}>
            新值
          </Text>
          <br />
          <Text strong style={{ fontSize: 13, color: '#389e0d' }}>
            {formatValue(newValue)}
          </Text>
        </div>
      </div>

      {/* 决策 Radio */}
      <Radio.Group
        value={decision}
        onChange={(e) => onDecisionChange(e.target.value as Decision)}
        style={{ marginBottom: noteRequired ? 8 : 0 }}
      >
        <Radio value="ACCEPT_NEW">采纳新值</Radio>
        <Radio value="KEEP_OLD">保留原值</Radio>
      </Radio.Group>

      {/* 备注（CRITICAL + 采纳新值时必填） */}
      {noteRequired && (
        <div style={{ marginTop: 8 }}>
          <TextArea
            value={note}
            onChange={(e) => onNoteChange(e.target.value)}
            placeholder="关键字段采纳新值时请填写变更原因（必填）"
            rows={2}
            status={noteMissing ? 'error' : ''}
            style={{ borderColor: noteMissing ? '#ff4d4f' : undefined }}
          />
          {noteMissing && (
            <Text type="danger" style={{ fontSize: 12 }}>
              关键字段采纳新值时备注不能为空
            </Text>
          )}
        </div>
      )}
    </div>
  );
};

export default DiffRowItem;
