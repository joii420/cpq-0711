// ============================================================
// components/FieldTraceDrawer.tsx
// 复杂公式追溯抽屉（width=720）
// 当 FieldTracePopover 判断 isComplex=true 时切换到此
// ============================================================

import React from 'react';
import {
  Drawer, Descriptions, Tag, Typography, Space, Divider,
  Table, Empty, Spin,
} from 'antd';
import type { FieldTraceDTO } from '../../../types/quotation-snapshot';
import { SOURCE_TYPE_LABEL, SOURCE_TYPE_COLOR } from '../../../types/quotation-snapshot';

const { Text, Paragraph } = Typography;

interface FieldTraceDrawerProps {
  open: boolean;
  onClose: () => void;
  trace: FieldTraceDTO | null;
  loading?: boolean;
}

const FieldTraceDrawer: React.FC<FieldTraceDrawerProps> = ({
  open, onClose, trace, loading,
}) => {
  const formulaInputRows = trace?.formulaInputs
    ? Object.entries(trace.formulaInputs).map(([key, val]) => ({ key, val }))
    : [];

  const inputColumns = [
    { title: '变量名', dataIndex: 'key', key: 'key', width: 200 },
    {
      title: '输入值',
      dataIndex: 'val',
      key: 'val',
      render: (v: any) => (
        <Text code>{String(v)}</Text>
      ),
    },
  ];

  return (
    <Drawer
      title="字段追溯详情"
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {loading ? (
        <div style={{ textAlign: 'center', padding: 60 }}>
          <Spin size="large" />
        </div>
      ) : !trace ? (
        <Empty description="暂无追溯数据" />
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size="large">
          {/* 基本信息 */}
          <Descriptions bordered column={1} size="small" title="字段信息">
            <Descriptions.Item label="字段名称">
              {trace.fieldLabel || trace.fieldPath}
            </Descriptions.Item>
            <Descriptions.Item label="字段路径">
              <Text code style={{ fontSize: 12 }}>{trace.fieldPath}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="当前值">
              <Text strong>{String(trace.currentValue ?? '-')}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="来源类型">
              <Tag color={SOURCE_TYPE_COLOR[trace.sourceType]}>
                {SOURCE_TYPE_LABEL[trace.sourceType]}
              </Tag>
            </Descriptions.Item>
            {trace.referencedVersion && (
              <Descriptions.Item label="引用版本">
                <Tag color="blue">{trace.referencedVersion}</Tag>
              </Descriptions.Item>
            )}
            {trace.lastModifiedBy && (
              <Descriptions.Item label="最后修改人">
                {trace.lastModifiedBy}
              </Descriptions.Item>
            )}
            {trace.lastModifiedAt && (
              <Descriptions.Item label="最后修改时间">
                {new Date(trace.lastModifiedAt).toLocaleString('zh-CN')}
              </Descriptions.Item>
            )}
          </Descriptions>

          {/* 公式定义 */}
          {trace.formula && (
            <>
              <Divider orientation="left">公式定义</Divider>
              <Paragraph
                style={{
                  background: '#f6f8fa',
                  padding: '12px 16px',
                  borderRadius: 6,
                  fontFamily: 'monospace',
                  fontSize: 13,
                  border: '1px solid #e1e4e8',
                  marginBottom: 0,
                }}
              >
                {trace.formula}
              </Paragraph>
            </>
          )}

          {/* 公式输入值 */}
          {formulaInputRows.length > 0 && (
            <>
              <Divider orientation="left">公式输入值</Divider>
              <Table
                dataSource={formulaInputRows}
                columns={inputColumns}
                rowKey="key"
                pagination={false}
                size="small"
                bordered
              />
            </>
          )}
        </Space>
      )}
    </Drawer>
  );
};

export default FieldTraceDrawer;
