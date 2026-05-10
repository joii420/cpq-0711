import React, { useState } from 'react';
import { Drawer, Table, Tag, Typography, Segmented, Space, Descriptions, Spin, Empty } from 'antd';
import type { VersionHistoryItemDTO } from '../../types/versioning';

const { Text, Title } = Typography;

interface VersionDetailDrawerProps {
  open: boolean;
  record: VersionHistoryItemDTO | null;
  onClose: () => void;
}

type ViewMode = '表格' | 'JSON';

const TABLE_DISPLAY_NAMES: Record<string, string> = {
  mat_fee: '费用主档',
  mat_process: '加工工序',
  plating_fee: '电镀费用',
};

/** 将 businessKey 对象展平为可渲染的行数组 */
function flattenBusinessKey(bk: Record<string, any>): { key: string; label: string; value: string }[] {
  return Object.entries(bk).map(([k, v]) => ({
    key: k,
    label: k,
    value: v == null ? '—' : String(v),
  }));
}

const VersionDetailDrawer: React.FC<VersionDetailDrawerProps> = ({ open, record, onClose }) => {
  const [viewMode, setViewMode] = useState<ViewMode>('表格');

  if (!record) return null;

  const tableDisplayName = TABLE_DISPLAY_NAMES[record.tableName] ?? record.tableName;
  const rows = flattenBusinessKey(record.businessKey ?? {});

  const columns = [
    {
      title: '字段名',
      dataIndex: 'key',
      key: 'key',
      width: 200,
      render: (v: string) => <Text type="secondary">{v}</Text>,
    },
    {
      title: '字段标签',
      dataIndex: 'label',
      key: 'label',
      width: 200,
    },
    {
      title: '值',
      dataIndex: 'value',
      key: 'value',
      render: (v: string) => <Text>{v}</Text>,
    },
  ];

  const drawerTitle = (
    <Space size="middle">
      <Title level={5} style={{ margin: 0 }}>版本详情</Title>
      <Tag color="blue">{tableDisplayName}</Tag>
      <Tag color={record.isCurrent ? 'green' : 'default'}>
        v{record.version}{record.isCurrent ? '（当前）' : ''}
      </Tag>
    </Space>
  );

  return (
    <Drawer
      title={drawerTitle}
      placement="right"
      width={1200}
      open={open}
      onClose={onClose}
      extra={
        <Segmented
          options={['表格', 'JSON']}
          value={viewMode}
          onChange={(v) => setViewMode(v as ViewMode)}
        />
      }
    >
      {/* 基本信息区 */}
      <Descriptions
        bordered
        size="small"
        column={3}
        style={{ marginBottom: 24 }}
      >
        <Descriptions.Item label="数据表">{tableDisplayName}（{record.tableName}）</Descriptions.Item>
        <Descriptions.Item label="版本号">v{record.version}</Descriptions.Item>
        <Descriptions.Item label="状态">
          {record.isCurrent
            ? <Tag color="green">当前版本</Tag>
            : <Tag color="default">历史版本</Tag>}
        </Descriptions.Item>
        <Descriptions.Item label="料号">{record.hfPartNo || '—'}</Descriptions.Item>
        <Descriptions.Item label="修改人">{record.updatedByName || record.updatedBy}</Descriptions.Item>
        <Descriptions.Item label="修改时间">
          {record.updatedAt ? new Date(record.updatedAt).toLocaleString() : '—'}
        </Descriptions.Item>
      </Descriptions>

      {/* 业务数据区 */}
      {viewMode === '表格' ? (
        rows.length > 0 ? (
          <Table
            rowKey="key"
            columns={columns}
            dataSource={rows}
            pagination={false}
            size="small"
            bordered
          />
        ) : (
          <Empty description="暂无业务数据" />
        )
      ) : (
        <div
          style={{
            background: '#f5f5f5',
            borderRadius: 6,
            padding: '16px 20px',
            fontFamily: 'monospace',
            fontSize: 13,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
            border: '1px solid #e8e8e8',
            maxHeight: 600,
            overflow: 'auto',
          }}
        >
          {JSON.stringify(record.businessKey ?? {}, null, 2)}
        </div>
      )}
    </Drawer>
  );
};

export default VersionDetailDrawer;
