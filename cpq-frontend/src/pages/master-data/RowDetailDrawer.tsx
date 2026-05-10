import React, { useEffect, useState, useCallback } from 'react';
import {
  Drawer,
  Descriptions,
  Tag,
  Typography,
  Button,
  Spin,
  Empty,
  message,
  Collapse,
  Space,
  Tooltip,
} from 'antd';
import { CopyOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { ColumnMetadataDTO } from '../../services/masterDataService';
import { masterDataService } from '../../services/masterDataService';

const { Text } = Typography;
const { Panel } = Collapse;

interface Props {
  open: boolean;
  onClose: () => void;
  tableName: string;
  rowId: string | null;
  columns: ColumnMetadataDTO[];
  // 如果父级已有行数据（已在列表中），直接传进来避免重复请求
  rowDataPreload?: Record<string, any> | null;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}[T\s]/;

// 格式化单个字段值 — 后端 dataType=IDENTIFIER/VALUE 不细分类型,按运行时值类型推断
function formatCellValue(value: any, _col: ColumnMetadataDTO): React.ReactNode {
  if (value === null || value === undefined || value === '') {
    return <Text type="secondary">—</Text>;
  }
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value === 'number') return value.toLocaleString();
  if (typeof value === 'object') {
    const json = JSON.stringify(value);
    return <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{json}</Text>;
  }
  const str = String(value);
  if (ISO_DATE_RE.test(str)) return dayjs(str).format('YYYY-MM-DD HH:mm:ss');
  if (UUID_RE.test(str)) {
    return (
      <Space size={4}>
        <Text style={{ fontFamily: 'monospace' }} title={str}>
          {str.slice(0, 8)}…
        </Text>
        <Tooltip title="复制完整 UUID">
          <Button
            type="text"
            size="small"
            icon={<CopyOutlined />}
            onClick={() => {
              navigator.clipboard.writeText(str);
              message.success('已复制');
            }}
          />
        </Tooltip>
      </Space>
    );
  }
  return str;
}

const IMPORTANCE_LABEL: Record<ColumnMetadataDTO['importanceLevel'], { label: string; color: string }> = {
  CRITICAL: { label: '关键字段', color: 'red' },
  IMPORTANT: { label: '重要字段', color: 'blue' },
  NORMAL: { label: '普通字段', color: 'default' },
};

const RowDetailDrawer: React.FC<Props> = ({
  open,
  onClose,
  tableName,
  rowId,
  columns,
  rowDataPreload,
}) => {
  const [rowData, setRowData] = useState<Record<string, any> | null>(null);
  const [loading, setLoading] = useState(false);

  const loadRowDetail = useCallback(async () => {
    if (!rowId || !tableName) return;
    if (rowDataPreload) {
      setRowData(rowDataPreload);
      return;
    }
    setLoading(true);
    try {
      const res = await masterDataService.getRowDetail(tableName, rowId);
      setRowData(res.data || null);
    } catch (err: any) {
      message.error(`加载行详情失败：${err?.message || '未知错误'}`);
      setRowData(null);
    } finally {
      setLoading(false);
    }
  }, [rowId, tableName, rowDataPreload]);

  useEffect(() => {
    if (open && rowId) {
      loadRowDetail();
    } else {
      setRowData(null);
    }
  }, [open, rowId, loadRowDetail]);

  const handleCopyJson = () => {
    if (!rowData) return;
    navigator.clipboard.writeText(JSON.stringify(rowData, null, 2));
    message.success('已复制 JSON');
  };

  const criticalCols = columns.filter((c) => c.importanceLevel === 'CRITICAL');
  const importantCols = columns.filter((c) => c.importanceLevel === 'IMPORTANT');
  const normalCols = columns.filter((c) => c.importanceLevel === 'NORMAL');

  const renderDescriptions = (cols: ColumnMetadataDTO[], level: ColumnMetadataDTO['importanceLevel']) => {
    if (cols.length === 0) return null;
    const { label, color } = IMPORTANCE_LABEL[level];
    return (
      <div style={{ marginBottom: 16 }}>
        <div style={{ marginBottom: 8 }}>
          <Tag color={color}>{label}</Tag>
        </div>
        <Descriptions
          bordered
          column={2}
          size="small"
          labelStyle={{ width: 120, fontWeight: 500 }}
        >
          {cols.map((col) => (
            <Descriptions.Item key={col.columnName} label={col.label}>
              {rowData ? formatCellValue(rowData[col.columnName], col) : <Text type="secondary">—</Text>}
            </Descriptions.Item>
          ))}
        </Descriptions>
      </div>
    );
  };

  return (
    <Drawer
      title={
        <Space>
          <span>行详情</span>
          {rowId && (
            <Text type="secondary" style={{ fontFamily: 'monospace', fontSize: 12 }}>
              {rowId.length > 16 ? `${rowId.slice(0, 16)}…` : rowId}
            </Text>
          )}
        </Space>
      }
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      extra={
        <Button icon={<CopyOutlined />} onClick={handleCopyJson} disabled={!rowData}>
          复制 JSON
        </Button>
      }
      styles={{ body: { padding: '16px 24px' } }}
    >
      {loading ? (
        <div style={{ textAlign: 'center', padding: '60px 0' }}>
          <Spin size="large" tip="加载中..." />
        </div>
      ) : !rowData ? (
        <Empty description="暂无数据" />
      ) : (
        <>
          {renderDescriptions(criticalCols, 'CRITICAL')}
          {renderDescriptions(importantCols, 'IMPORTANT')}
          {normalCols.length > 0 && (
            <Collapse defaultActiveKey={[]} ghost>
              <Panel
                header={
                  <Space>
                    <Tag color="default">普通字段</Tag>
                    <Text type="secondary" style={{ fontSize: 12 }}>（{normalCols.length} 个，默认收起）</Text>
                  </Space>
                }
                key="normal"
              >
                <Descriptions
                  bordered
                  column={2}
                  size="small"
                  labelStyle={{ width: 120, fontWeight: 500 }}
                >
                  {normalCols.map((col) => (
                    <Descriptions.Item key={col.columnName} label={col.label}>
                      {formatCellValue(rowData[col.columnName], col)}
                    </Descriptions.Item>
                  ))}
                </Descriptions>
              </Panel>
            </Collapse>
          )}
        </>
      )}
    </Drawer>
  );
};

export default RowDetailDrawer;
