import React, { useEffect, useState } from 'react';
import {
  Drawer,
  Table,
  Tag,
  Typography,
  Space,
  Spin,
  Empty,
  message,
  Tabs,
} from 'antd';
import type { VersionCompareDTO, FieldDiff } from '../../types/versioning';
import { versioningService } from '../../services/versioningService';

const { Title, Text } = Typography;

interface VersionCompareDrawerProps {
  open: boolean;
  tableName: string;
  recordIdA: string; // 旧版本
  recordIdB: string; // 新版本
  versionA: number;
  versionB: number;
  onClose: () => void;
}

const VersionCompareDrawer: React.FC<VersionCompareDrawerProps> = ({
  open,
  tableName,
  recordIdA,
  recordIdB,
  versionA,
  versionB,
  onClose,
}) => {
  const [loading, setLoading] = useState(false);
  const [compareData, setCompareData] = useState<VersionCompareDTO | null>(null);

  useEffect(() => {
    if (!open || !tableName || !recordIdA || !recordIdB) return;
    setLoading(true);
    versioningService
      .compareVersions({ tableName, recordIdA, recordIdB })
      .then((data) => setCompareData(data))
      .catch(() => message.error('加载版本对比数据失败'))
      .finally(() => setLoading(false));
  }, [open, tableName, recordIdA, recordIdB]);

  const columns = [
    {
      title: '字段',
      dataIndex: 'fieldLabel',
      key: 'fieldLabel',
      width: 180,
      render: (label: string, row: FieldDiff) => (
        <Space direction="vertical" size={0}>
          <Text>{label || row.fieldName}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>{row.fieldName}</Text>
        </Space>
      ),
    },
    {
      title: `v${versionA}（旧）`,
      dataIndex: 'valueA',
      key: 'valueA',
      render: (v: any, row: FieldDiff) => (
        <Text
          style={{
            color: !row.sameValue ? '#d46b08' : undefined,
            background: !row.sameValue ? '#fff7e6' : undefined,
            padding: !row.sameValue ? '2px 6px' : undefined,
            borderRadius: !row.sameValue ? 4 : undefined,
            display: 'inline-block',
          }}
        >
          {v == null ? <Text type="secondary">—</Text> : String(v)}
        </Text>
      ),
    },
    {
      title: `v${versionB}（新）`,
      dataIndex: 'valueB',
      key: 'valueB',
      render: (v: any, row: FieldDiff) => (
        <Text
          style={{
            color: !row.sameValue ? '#389e0d' : undefined,
            background: !row.sameValue ? '#f6ffed' : undefined,
            padding: !row.sameValue ? '2px 6px' : undefined,
            borderRadius: !row.sameValue ? 4 : undefined,
            display: 'inline-block',
          }}
        >
          {v == null ? <Text type="secondary">—</Text> : String(v)}
        </Text>
      ),
    },
    {
      title: '状态',
      key: 'status',
      width: 100,
      render: (_: any, row: FieldDiff) =>
        row.sameValue ? (
          <Tag color="default">未变更</Tag>
        ) : (
          <Tag color="warning">已变更</Tag>
        ),
    },
  ];

  const rowClassName = (row: FieldDiff) =>
    !row.sameValue ? 'version-diff-row-changed' : '';

  const drawerTitle = (
    <Space size="middle">
      <Title level={5} style={{ margin: 0 }}>版本对比</Title>
      <Tag color="orange">v{versionA}</Tag>
      <span style={{ color: '#8c8c8c' }}>→</span>
      <Tag color="green">v{versionB}</Tag>
    </Space>
  );

  const renderContent = () => {
    if (loading) return <Spin tip="加载对比数据..." style={{ display: 'block', padding: '60px 0' }} />;
    if (!compareData) return <Empty description="暂无对比数据" />;

    const diffs = compareData.fieldDiffs ?? [];
    const changedCount = diffs.filter((d) => !d.sameValue).length;

    return (
      <>
        <div style={{ marginBottom: 16 }}>
          <Space>
            <Tag color="warning">{changedCount} 个字段有变更</Tag>
            <Tag color="default">{diffs.length - changedCount} 个字段未变更</Tag>
          </Space>
        </div>
        <Table
          rowKey="fieldName"
          columns={columns}
          dataSource={diffs}
          pagination={false}
          size="small"
          bordered
          rowClassName={rowClassName}
          scroll={{ y: 560 }}
        />
        <style>{`
          .version-diff-row-changed {
            background: #fffbe6 !important;
          }
          .version-diff-row-changed:hover > td {
            background: #fff3bf !important;
          }
        `}</style>
      </>
    );
  };

  return (
    <Drawer
      title={drawerTitle}
      placement="right"
      width={1200}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {renderContent()}
    </Drawer>
  );
};

export default VersionCompareDrawer;
