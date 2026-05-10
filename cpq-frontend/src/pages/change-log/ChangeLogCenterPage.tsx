import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Pagination,
  Popover,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  AuditOutlined,
  DownloadOutlined,
  DownOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons';
import type { ChangeLogEntryDTO } from '../../types/versioning';
import { changeLogService } from '../../services/changeLogService';
import type { ChangeLogFiltersValue } from './ChangeLogFilters';
import ChangeLogFilters from './ChangeLogFilters';

const { Title, Text } = Typography;

const IMPORTANCE_CONFIG: Record<string, { label: string; color: string }> = {
  CRITICAL: { label: '关键', color: 'red' },
  IMPORTANT: { label: '重要', color: 'orange' },
  NORMAL: { label: '普通', color: 'default' },
};

const CHANGE_SOURCE_CONFIG: Record<string, { label: string; color: string }> = {
  V5_IMPORT: { label: 'V5 导入', color: 'blue' },
  MANUAL_EDIT: { label: '手动编辑', color: 'purple' },
  SYSTEM_INIT: { label: '系统初始化', color: 'cyan' },
  SYNC: { label: '数据同步', color: 'geekblue' },
};

const TABLE_DISPLAY: Record<string, string> = {
  mat_fee: '费用主档',
  mat_process: '加工工序',
  plating_fee: '电镀费用',
};

const PAGE_SIZE = 20;

const ChangeLogCenterPage: React.FC = () => {
  const [filters, setFilters] = useState<ChangeLogFiltersValue>({
    importanceList: [],
    changeSourceList: [],
  });
  const [items, setItems] = useState<ChangeLogEntryDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [backendError, setBackendError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);

  // 详情抽屉
  const [detailEntry, setDetailEntry] = useState<ChangeLogEntryDTO | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const buildParams = useCallback(() => ({
    customerId: filters.customerId,
    hfPartNo: filters.hfPartNo || undefined,
    tableName: filters.tableName,
    fieldName: filters.fieldName || undefined,
    importanceList: filters.importanceList && filters.importanceList.length > 0
      ? filters.importanceList : undefined,
    changeSourceList: filters.changeSourceList && filters.changeSourceList.length > 0
      ? filters.changeSourceList : undefined,
    startTime: filters.timeRange?.[0]?.toISOString(),
    endTime: filters.timeRange?.[1]?.toISOString(),
  }), [filters]);

  const loadData = useCallback(async (pg: number) => {
    setLoading(true);
    setBackendError(null);
    try {
      const res = await changeLogService.search({ ...buildParams(), page: pg, size: PAGE_SIZE });
      setItems(res.items);
      setTotal(res.total);
    } catch (err: any) {
      const msg = err?.response?.data?.message || err?.message || '加载失败';
      setBackendError(msg);
    } finally {
      setLoading(false);
    }
  }, [buildParams]);

  useEffect(() => {
    setPage(0);
    loadData(0);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSearch = () => {
    setPage(0);
    loadData(0);
  };

  const handlePageChange = (p: number) => {
    const pg = p - 1;
    setPage(pg);
    loadData(pg);
  };

  const handleExport = async (format: 'csv' | 'xlsx') => {
    setExporting(true);
    try {
      await changeLogService.export({ ...buildParams(), format });
      message.success(`${format.toUpperCase()} 导出已触发，请稍候`);
    } catch {
      message.error('导出失败，请稍后重试');
    } finally {
      setExporting(false);
    }
  };

  /** 渲染值（带省略 Popover） */
  const renderValue = (v: any, maxLen = 30) => {
    if (v == null) return <Text type="secondary">—</Text>;
    const str = String(v);
    if (str.length <= maxLen) return <Text>{str}</Text>;
    return (
      <Popover
        content={
          <div style={{ maxWidth: 400, wordBreak: 'break-all', maxHeight: 200, overflow: 'auto' }}>
            {str}
          </div>
        }
        title="完整值"
        trigger="hover"
      >
        <Text style={{ cursor: 'help', borderBottom: '1px dashed #999' }}>
          {str.slice(0, maxLen)}…
        </Text>
      </Popover>
    );
  };

  const columns = [
    {
      title: '字段',
      key: 'field',
      width: 160,
      render: (_: any, row: ChangeLogEntryDTO) => (
        <Space direction="vertical" size={0}>
          <Text strong>{row.fieldLabel || row.fieldName}</Text>
          <Text type="secondary" style={{ fontSize: 11 }}>
            {TABLE_DISPLAY[row.tableName] ?? row.tableName}
          </Text>
        </Space>
      ),
    },
    {
      title: '旧值',
      dataIndex: 'oldValue',
      key: 'oldValue',
      width: 160,
      render: (v: any) => (
        <span style={{ color: '#cf1322' }}>
          {renderValue(v)}
        </span>
      ),
    },
    {
      title: '',
      key: 'arrow',
      width: 30,
      render: () => <Text type="secondary">→</Text>,
    },
    {
      title: '新值',
      dataIndex: 'newValue',
      key: 'newValue',
      width: 160,
      render: (v: any) => (
        <span style={{ color: '#389e0d' }}>
          {renderValue(v)}
        </span>
      ),
    },
    {
      title: '重要性',
      dataIndex: 'importance',
      key: 'importance',
      width: 100,
      render: (v: string, row: ChangeLogEntryDTO) => {
        const cfg = IMPORTANCE_CONFIG[v] ?? { label: v, color: 'default' };
        return (
          <Space size={4}>
            <Tag color={cfg.color}>{cfg.label}</Tag>
            {row.affectsCalculation && (
              <Tooltip title="影响计算">
                <Tag color="volcano" style={{ fontSize: 11 }}>计算</Tag>
              </Tooltip>
            )}
          </Space>
        );
      },
    },
    {
      title: '变更来源',
      dataIndex: 'changeSource',
      key: 'changeSource',
      width: 120,
      render: (v: string) => {
        const cfg = CHANGE_SOURCE_CONFIG[v] ?? { label: v, color: 'default' };
        return <Tag color={cfg.color}>{cfg.label}</Tag>;
      },
    },
    {
      title: '料号',
      dataIndex: 'hfPartNo',
      key: 'hfPartNo',
      width: 130,
    },
    {
      title: '变更人',
      key: 'changedBy',
      width: 110,
      render: (_: any, row: ChangeLogEntryDTO) => row.changedByName || row.changedBy || '—',
    },
    {
      title: '变更时间',
      dataIndex: 'changedAt',
      key: 'changedAt',
      width: 180,
      render: (v: string) => v ? new Date(v).toLocaleString() : '—',
    },
    {
      title: '备注',
      dataIndex: 'note',
      key: 'note',
      width: 160,
      render: (v: string) => v ? (
        <Tooltip title={v}>
          <Text style={{ color: '#595959', fontSize: 12 }}>
            {v.length > 20 ? v.slice(0, 20) + '…' : v}
          </Text>
        </Tooltip>
      ) : '—',
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      fixed: 'right' as const,
      render: (_: any, row: ChangeLogEntryDTO) => (
        <Button
          type="link"
          size="small"
          icon={<InfoCircleOutlined />}
          onClick={() => { setDetailEntry(row); setDetailOpen(true); }}
        >
          详情
        </Button>
      ),
    },
  ];

  const exportMenu = {
    items: [
      { key: 'csv', label: '导出 CSV' },
      { key: 'xlsx', label: '导出 Excel' },
    ],
    onClick: ({ key }: { key: string }) => handleExport(key as 'csv' | 'xlsx'),
  };

  return (
    <div>
      {/* 页面标题 */}
      <div style={{ marginBottom: 24, display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <Space align="center">
            <AuditOutlined style={{ fontSize: 22, color: '#1677ff' }} />
            <Title level={4} style={{ margin: 0 }}>变更日志中心</Title>
          </Space>
          <div style={{ marginTop: 4 }}>
            <Text type="secondary">追踪主数据字段的每一次变更，支持多维筛选与导出</Text>
          </div>
        </div>
        <Dropdown menu={exportMenu} disabled={exporting}>
          <Button icon={<DownloadOutlined />} loading={exporting}>
            导出 <DownOutlined />
          </Button>
        </Dropdown>
      </div>

      {/* 筛选区 */}
      <ChangeLogFilters
        value={filters}
        onChange={setFilters}
        onSearch={handleSearch}
        loading={loading}
      />

      {/* 错误提示 */}
      {backendError && (
        <Alert
          type="error"
          message={`加载失败：${backendError}`}
          showIcon
          closable
          style={{ marginBottom: 16 }}
        />
      )}

      {/* 列表 */}
      <Card>
        <Spin spinning={loading}>
          {items.length === 0 && !loading ? (
            <Empty description="暂无变更日志记录" />
          ) : (
            <>
              <Table
                rowKey="id"
                columns={columns}
                dataSource={items}
                pagination={false}
                size="small"
                scroll={{ x: 1400 }}
                bordered={false}
              />
              <div style={{ marginTop: 16, textAlign: 'right' }}>
                <Pagination
                  current={page + 1}
                  pageSize={PAGE_SIZE}
                  total={total}
                  onChange={handlePageChange}
                  showSizeChanger={false}
                  showTotal={(t) => `共 ${t} 条`}
                />
              </div>
            </>
          )}
        </Spin>
      </Card>

      {/* 详情抽屉 */}
      <Drawer
        title="变更详情"
        placement="right"
        width={720}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        destroyOnClose
      >
        {detailEntry && (
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label="数据表">
              {TABLE_DISPLAY[detailEntry.tableName] ?? detailEntry.tableName}（{detailEntry.tableName}）
            </Descriptions.Item>
            <Descriptions.Item label="记录 ID">{detailEntry.recordId}</Descriptions.Item>
            <Descriptions.Item label="料号">{detailEntry.hfPartNo || '—'}</Descriptions.Item>
            <Descriptions.Item label="字段名">
              {detailEntry.fieldLabel || detailEntry.fieldName}（{detailEntry.fieldName}）
            </Descriptions.Item>
            <Descriptions.Item label="旧值">
              <Text style={{ color: '#cf1322' }}>
                {detailEntry.oldValue == null ? '—' : String(detailEntry.oldValue)}
              </Text>
            </Descriptions.Item>
            <Descriptions.Item label="新值">
              <Text style={{ color: '#389e0d' }}>
                {detailEntry.newValue == null ? '—' : String(detailEntry.newValue)}
              </Text>
            </Descriptions.Item>
            <Descriptions.Item label="重要性">
              <Tag color={IMPORTANCE_CONFIG[detailEntry.importance]?.color}>
                {IMPORTANCE_CONFIG[detailEntry.importance]?.label ?? detailEntry.importance}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="影响计算">
              {detailEntry.affectsCalculation
                ? <Tag color="volcano">是</Tag>
                : <Tag color="default">否</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label="变更来源">
              <Tag color={CHANGE_SOURCE_CONFIG[detailEntry.changeSource]?.color}>
                {CHANGE_SOURCE_CONFIG[detailEntry.changeSource]?.label ?? detailEntry.changeSource}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="变更人">
              {detailEntry.changedByName || detailEntry.changedBy || '—'}
            </Descriptions.Item>
            <Descriptions.Item label="变更时间">
              {detailEntry.changedAt ? new Date(detailEntry.changedAt).toLocaleString() : '—'}
            </Descriptions.Item>
            {detailEntry.importRecordId && (
              <Descriptions.Item label="关联导入记录 ID">
                {detailEntry.importRecordId}
              </Descriptions.Item>
            )}
            <Descriptions.Item label="备注">
              {detailEntry.note || <Text type="secondary">无</Text>}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </div>
  );
};

export default ChangeLogCenterPage;
