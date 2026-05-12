import React, { useState } from 'react';
import {
  Alert,
  Button,
  Radio,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { DownOutlined, RightOutlined } from '@ant-design/icons';
import type { PartVersionDecisionItem, PartVersionAction } from '../../types/import-v6';

const { Text } = Typography;

// Sheet 名称友好标签
const SHEET_LABELS: Record<string, string> = {
  bom: 'BOM',
  process: '工艺',
  fee: '费用',
  plating_fee: '镀层费用',
  plating_plan: '镀层方案',
};

function sheetLabel(key: string): string {
  return SHEET_LABELS[key] ?? key;
}

interface Props {
  items: PartVersionDecisionItem[];
  onChange: (key: string, action: PartVersionAction) => void;
}

const PartVersionDecisionList: React.FC<Props> = ({ items, onChange }) => {
  // 展开的 key 集合（展示 row-level diff）
  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(new Set());

  function toggleExpand(key: string) {
    setExpandedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  // 全部升版（不影响 isNew 条目，它们强制 NEW）
  function handleBumpAll() {
    items.forEach((item) => {
      if (!item.isNew) onChange(item.key, 'BUMP');
    });
  }

  // 全部不升版（不影响 isNew 条目）
  function handleNoBumpAll() {
    items.forEach((item) => {
      if (!item.isNew) onChange(item.key, 'NO_BUMP');
    });
  }

  if (items.length === 0) {
    return (
      <Alert
        type="success"
        showIcon
        message="本次 Excel 无料号版本变更"
        description="所有料号数据与当前 DB 版本一致，无需处理。"
      />
    );
  }

  // row-level diff 表列定义
  const diffColumns = [
    { title: '行号', dataIndex: 'rowKey', key: 'rowKey', width: 80 },
    { title: '字段', dataIndex: 'field', key: 'field', width: 120 },
    {
      title: '旧值',
      dataIndex: 'oldValue',
      key: 'oldValue',
      render: (v: string) => <Text type="secondary">{v ?? '—'}</Text>,
    },
    {
      title: '新值',
      dataIndex: 'newValue',
      key: 'newValue',
      render: (v: string) => <Text style={{ color: '#389e0d' }}>{v ?? '—'}</Text>,
    },
  ];

  return (
    <div>
      {/* 批量操作按钮 */}
      <div style={{ marginBottom: 12 }}>
        <Space>
          <Button size="small" onClick={handleBumpAll}>全部升版</Button>
          <Button size="small" onClick={handleNoBumpAll}>全部不升版</Button>
          <Text type="secondary" style={{ fontSize: 12 }}>
            （新料号不受影响，强制以 v2000 创建）
          </Text>
        </Space>
      </div>

      {/* 每个料号一张卡 */}
      <Space direction="vertical" style={{ width: '100%' }} size={10}>
        {items.map((item) => {
          const isExpanded = expandedKeys.has(item.key);
          const totalDiffRows = Object.values(item.sheetDiffs).reduce((a, b) => a + b, 0);
          const hasRowDiff = Object.values(item.rowLevelDiff).some((rows) => rows.length > 0);

          return (
            <div
              key={item.key}
              style={{
                border: '1px solid #d9d9d9',
                borderRadius: 6,
                padding: '12px 16px',
                background: '#fafafa',
              }}
            >
              {/* 卡片主行 */}
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  gap: 12,
                  flexWrap: 'wrap',
                }}
              >
                {/* 左侧：料号 + 版本信息 */}
                <div style={{ flex: 1, minWidth: 200 }}>
                  <Space wrap>
                    <Text strong style={{ fontSize: 13 }}>
                      {item.customerProductNo}
                    </Text>
                    <Text type="secondary">/</Text>
                    <Text style={{ fontFamily: 'Consolas, monospace', fontSize: 12 }}>
                      {item.hfPartNo}
                    </Text>
                  </Space>
                  <div style={{ marginTop: 4 }}>
                    {item.isNew ? (
                      <Tag color="blue">新料号 · 将以 v2000 创建</Tag>
                    ) : (
                      <Space size={4}>
                        <Tag color="default">v{item.currentVersion}</Tag>
                        <Text type="secondary" style={{ fontSize: 12 }}>→</Text>
                        <Tag color="green">v{item.suggestedVersion}</Tag>
                      </Space>
                    )}
                  </div>
                </div>

                {/* 右侧：BUMP / NO_BUMP 选择 */}
                <div>
                  <Radio.Group
                    value={item.action}
                    onChange={(e) => onChange(item.key, e.target.value as PartVersionAction)}
                    disabled={item.isNew}
                    optionType="button"
                    buttonStyle="solid"
                    size="small"
                  >
                    <Radio.Button value="BUMP">升版</Radio.Button>
                    <Radio.Button value="NO_BUMP">不升版</Radio.Button>
                  </Radio.Group>
                </div>
              </div>

              {/* Sheet 差异概览 + 展开按钮 */}
              {!item.isNew && (
                <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>涉及 sheet：</Text>
                  {Object.entries(item.sheetDiffs).map(([sheet, count]) => (
                    <Tag key={sheet} color={count > 0 ? 'orange' : 'default'} style={{ fontSize: 11 }}>
                      {sheetLabel(sheet)}({count})
                    </Tag>
                  ))}
                  {totalDiffRows === 0 && (
                    <Text type="secondary" style={{ fontSize: 12 }}>（无 sheet 差异）</Text>
                  )}
                  {hasRowDiff && (
                    <Button
                      type="link"
                      size="small"
                      style={{ padding: 0, fontSize: 12 }}
                      icon={isExpanded ? <DownOutlined /> : <RightOutlined />}
                      onClick={() => toggleExpand(item.key)}
                    >
                      {isExpanded ? '收起详情' : '查看详情'}
                    </Button>
                  )}
                </div>
              )}

              {/* 展开：按 sheet 分组的 row-level diff 表 */}
              {isExpanded && (
                <div style={{ marginTop: 12 }}>
                  {Object.entries(item.rowLevelDiff).map(([sheet, rows]) => {
                    if (rows.length === 0) return null;
                    return (
                      <div key={sheet} style={{ marginBottom: 12 }}>
                        <Text strong style={{ fontSize: 12 }}>
                          {sheetLabel(sheet)}（{rows.length} 行变更）
                        </Text>
                        <Table
                          size="small"
                          columns={diffColumns}
                          dataSource={rows.map((r, i) => ({ ...r, key: i }))}
                          pagination={false}
                          style={{ marginTop: 4 }}
                          bordered
                        />
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </Space>
    </div>
  );
};

export default PartVersionDecisionList;
