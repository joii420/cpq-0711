// ============================================================
// components/FieldTracePopover.tsx
// SUBMITTED 状态下字段追溯 Popover 内容（width=480）
// ============================================================

import React, { useState } from 'react';
import {
  Descriptions, Tag, Typography, Space, Button, Spin, Divider,
} from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import type { FieldTraceDTO } from '../../../types/quotation-snapshot';
import { SOURCE_TYPE_LABEL, SOURCE_TYPE_COLOR } from '../../../types/quotation-snapshot';
import FieldTraceDrawer from './FieldTraceDrawer';

const { Text } = Typography;

interface FieldTracePopoverContentProps {
  trace: FieldTraceDTO | null;
  loading: boolean;
  /** 关闭外层 Popover 的回调 */
  onClosePopover?: () => void;
}

const FieldTracePopoverContent: React.FC<FieldTracePopoverContentProps> = ({
  trace,
  loading,
  onClosePopover,
}) => {
  const [drawerOpen, setDrawerOpen] = useState(false);

  const handleViewDetail = () => {
    onClosePopover?.();
    setDrawerOpen(true);
  };

  if (loading) {
    return (
      <div style={{ width: 480, textAlign: 'center', padding: '20px 0' }}>
        <Spin size="small" />
        <div style={{ marginTop: 8, color: '#999', fontSize: 12 }}>加载追溯数据...</div>
      </div>
    );
  }

  if (!trace) {
    return (
      <div style={{ width: 480, color: '#999', padding: '12px 0' }}>
        暂无追溯数据
      </div>
    );
  }

  return (
    <div style={{ width: 480 }}>
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label="字段名称">
          {trace.fieldLabel || trace.fieldPath}
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
        {trace.formula && (
          <Descriptions.Item label="公式">
            <Text code style={{ fontSize: 11 }}>{trace.formula}</Text>
          </Descriptions.Item>
        )}
        {trace.formulaInputs && Object.keys(trace.formulaInputs).length > 0 && (
          <Descriptions.Item label="公式输入值">
            <Space direction="vertical" size={2}>
              {Object.entries(trace.formulaInputs).map(([k, v]) => (
                <Text key={k} style={{ fontSize: 12 }}>
                  <Text code style={{ fontSize: 11 }}>{k}</Text>
                  {' = '}
                  <Text strong>{String(v)}</Text>
                </Text>
              ))}
            </Space>
          </Descriptions.Item>
        )}
        {trace.lastModifiedBy && (
          <Descriptions.Item label="最后修改人">
            {trace.lastModifiedBy}
          </Descriptions.Item>
        )}
        {trace.lastModifiedAt && (
          <Descriptions.Item label="修改时间">
            {new Date(trace.lastModifiedAt).toLocaleString('zh-CN')}
          </Descriptions.Item>
        )}
      </Descriptions>

      {trace.isComplex && (
        <>
          <Divider style={{ margin: '12px 0' }} />
          <div style={{ textAlign: 'right' }}>
            <Button
              size="small"
              icon={<SearchOutlined />}
              type="link"
              onClick={handleViewDetail}
              style={{ padding: 0 }}
            >
              查看详情（多步追溯）
            </Button>
          </div>
        </>
      )}

      {/* 复杂追溯 Drawer（从此 Popover 触发，独立于外层 Popover） */}
      <FieldTraceDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        trace={trace}
        loading={false}
      />
    </div>
  );
};

export default FieldTracePopoverContent;
