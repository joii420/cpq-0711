import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Drawer,
  Table,
  Input,
  Space,
  Typography,
  Tag,
  Button,
  Empty,
  Spin,
  message,
  Tooltip,
} from 'antd';
import { SearchOutlined, CopyOutlined } from '@ant-design/icons';
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table';
import dayjs from 'dayjs';
import type {
  TableSummaryDTO,
  ColumnMetadataDTO,
  PagedTableDataDTO,
} from '../../services/masterDataService';
import { masterDataService } from '../../services/masterDataService';
import RowDetailDrawer from './RowDetailDrawer';

const { Text } = Typography;
const { Search } = Input;

interface Props {
  open: boolean;
  onClose: () => void;
  summary: TableSummaryDTO | null;
  customerId: string | null;
}

// UUID 字符串检测(后端把 UUID/Timestamp 序列化成 String)
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}[T\s]/;

// 格式化单元格值 — 后端 dataType=IDENTIFIER/VALUE 不细分类型,按运行时值类型推断渲染
function renderCellValue(value: any, _col: ColumnMetadataDTO): React.ReactNode {
  if (value === null || value === undefined || value === '') {
    return <Text type="secondary">—</Text>;
  }
  if (typeof value === 'boolean') {
    return value ? <Tag color="green">是</Tag> : <Tag color="default">否</Tag>;
  }
  if (typeof value === 'number') {
    return value.toLocaleString();
  }
  // 嵌套对象/数组 → JSON
  if (typeof value === 'object') {
    const json = JSON.stringify(value);
    return json.length > 40 ? (
      <Tooltip title={json}>
        <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{json.slice(0, 40)}…</Text>
      </Tooltip>
    ) : (
      <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{json}</Text>
    );
  }
  const str = String(value);
  // ISO 日期格式
  if (ISO_DATE_RE.test(str)) {
    return dayjs(str).format('YYYY-MM-DD HH:mm');
  }
  // UUID 截断显示 + 复制按钮
  if (UUID_RE.test(str)) {
    return (
      <Space size={4}>
        <Text style={{ fontFamily: 'monospace', fontSize: 12 }} title={str}>
          {str.slice(0, 8)}…
        </Text>
        <Tooltip title="复制完整 UUID">
          <Button
            type="text"
            size="small"
            icon={<CopyOutlined />}
            style={{ padding: '0 2px', height: 18 }}
            onClick={(e) => {
              e.stopPropagation();
              navigator.clipboard.writeText(str);
              message.success('已复制');
            }}
          />
        </Tooltip>
      </Space>
    );
  }
  // 长文本截断
  return str.length > 40 ? (
    <Tooltip title={str}>
      <Text>{str.slice(0, 40)}…</Text>
    </Tooltip>
  ) : (
    str
  );
}

const TableDataDrawer: React.FC<Props> = ({ open, onClose, summary, customerId }) => {
  const [tableData, setTableData] = useState<PagedTableDataDTO | null>(null);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [pagination, setPagination] = useState({ page: 0, size: 50 });

  // 二级抽屉（行详情）
  const [detailOpen, setDetailOpen] = useState(false);
  const [selectedRowId, setSelectedRowId] = useState<string | null>(null);
  const [selectedRowData, setSelectedRowData] = useState<Record<string, any> | null>(null);

  // AbortController：tableName/customerId 变化时取消上一个请求
  const abortRef = useRef<AbortController | null>(null);

  const loadData = useCallback(
    async (tableName: string, page: number, size: number, searchStr: string) => {
      // 取消上一个请求
      if (abortRef.current) {
        abortRef.current.abort();
      }
      abortRef.current = new AbortController();

      setLoading(true);
      try {
        const res = await masterDataService.queryTable(tableName, {
          customerId,
          page,
          size,
          search: searchStr || undefined,
        });
        setTableData(res.data || null);
      } catch (err: any) {
        if (err?.name === 'AbortError' || err?.message === 'canceled') {
          return; // 被取消，不处理
        }
        message.error(`加载数据失败：${err?.message || '未知错误'}`);
      } finally {
        setLoading(false);
      }
    },
    [customerId],
  );

  // 打开/表名变化时重置并加载
  useEffect(() => {
    if (open && summary) {
      setSearch('');
      setPagination({ page: 0, size: 50 });
      setTableData(null);
      loadData(summary.tableName, 0, 50, '');
    } else {
      setTableData(null);
    }
    return () => {
      if (abortRef.current) abortRef.current.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, summary?.tableName, customerId]);

  const handleSearch = (value: string) => {
    setSearch(value);
    setPagination((prev) => ({ ...prev, page: 0 }));
    if (summary) {
      loadData(summary.tableName, 0, pagination.size, value);
    }
  };

  const handleTableChange = (pag: TablePaginationConfig) => {
    const page = (pag.current ?? 1) - 1;
    const size = pag.pageSize ?? 50;
    setPagination({ page, size });
    if (summary) {
      loadData(summary.tableName, page, size, search);
    }
  };

  const handleRowClick = (row: Record<string, any>) => {
    if (!tableData) return;
    // 后端 ColumnMetadataDTO 无 isPrimaryKey 字段,直接用 summary.primaryKeyField
    const pk = summary?.primaryKeyField;
    const pkValue = pk ? String(row[pk]) : null;
    setSelectedRowId(pkValue);
    setSelectedRowData(row);
    setDetailOpen(true);
  };

  // 动态生成列:优先按 CRITICAL + IMPORTANT 过滤;若该表未配过字段重要性(全 NORMAL),回退显示全部列
  // 否则会出现"行有但无列",抽屉看似空白
  const allColumns: ColumnMetadataDTO[] = tableData?.columns ?? [];
  const importantColumns = allColumns.filter((c) => c.importanceLevel !== 'NORMAL');
  const isFiltered = importantColumns.length > 0;
  const visibleColumns: ColumnMetadataDTO[] = isFiltered ? importantColumns : allColumns;

  const pkField = summary?.primaryKeyField;
  const antColumns: ColumnsType<Record<string, any>> = visibleColumns.map((col) => ({
    key: col.columnName,
    dataIndex: col.columnName,
    title: (
      <Space size={4}>
        {col.label}
        {col.columnName === pkField && (
          <Tag color="gold" style={{ fontSize: 10, padding: '0 4px' }}>PK</Tag>
        )}
        {col.importanceLevel === 'CRITICAL' && (
          <Tag color="red" style={{ fontSize: 10, padding: '0 4px' }}>关键</Tag>
        )}
      </Space>
    ),
    ellipsis: true,
    render: (value: any) => renderCellValue(value, col),
  }));

  const isV1Disabled = summary?.v1Disabled || tableData?.v1Disabled;

  return (
    <>
      <Drawer
        title={
          summary ? (
            <Space>
              <span>{summary.displayName}</span>
              <Text type="secondary" style={{ fontFamily: 'monospace', fontSize: 13 }}>
                {summary.tableName}
              </Text>
              {summary.primaryKeyField && (
                <Tag color="orange">主键: {summary.primaryKeyField}</Tag>
              )}
            </Space>
          ) : (
            '表数据'
          )
        }
        placement="right"
        width={1200}
        open={open}
        onClose={onClose}
        styles={{ body: { padding: '16px 24px', display: 'flex', flexDirection: 'column', gap: 12 } }}
      >
        {isV1Disabled ? (
          <Empty
            description={
              <span>
                v1 暂未启用，请等待 v2 上线
                <br />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  此表将在下一版本中开放
                </Text>
              </span>
            }
            style={{ marginTop: 80 }}
          />
        ) : (
          <>
            {/* 工具栏 */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
              <Space>
                <Search
                  placeholder={`搜索 ${summary?.primaryKeyField || '主键'}…`}
                  allowClear
                  onSearch={handleSearch}
                  style={{ width: 280 }}
                  enterButton={<SearchOutlined />}
                />
                {summary?.primaryKeyField && (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    按主键字段 <code>{summary.primaryKeyField}</code> 模糊搜索
                  </Text>
                )}
              </Space>
              <Space>
                {tableData && (
                  <Text type="secondary" style={{ fontSize: 13 }}>
                    共 <strong>{tableData.total.toLocaleString()}</strong> 条记录
                  </Text>
                )}
                {visibleColumns.length > 0 && (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {isFiltered
                      ? `显示 ${visibleColumns.length} / ${allColumns.length} 列(仅 CRITICAL + IMPORTANT)`
                      : `显示全部 ${allColumns.length} 列(尚未配置字段重要性)`}
                  </Text>
                )}
              </Space>
            </div>

            {/* 数据表格 */}
            {loading && !tableData ? (
              <div style={{ textAlign: 'center', padding: '80px 0' }}>
                <Spin size="large" tip="加载中..." />
              </div>
            ) : (
              <Table
                rowKey={(row) => {
                  const pk = summary?.primaryKeyField || 'id';
                  return String(row[pk] ?? Math.random());
                }}
                columns={antColumns}
                dataSource={tableData?.rows || []}
                loading={loading}
                size="middle"
                scroll={{ x: 'max-content' }}
                pagination={{
                  current: (tableData?.page ?? 0) + 1,
                  pageSize: tableData?.size ?? 50,
                  total: tableData?.total ?? 0,
                  showSizeChanger: true,
                  pageSizeOptions: ['50', '100', '200'],
                  showTotal: (total, range) =>
                    `第 ${range[0]}-${range[1]} 条，共 ${total.toLocaleString()} 条`,
                }}
                onChange={handleTableChange}
                onRow={(row) => ({
                  onClick: () => handleRowClick(row),
                  style: { cursor: 'pointer' },
                })}
                locale={{
                  emptyText: (
                    <Empty description="暂无数据" />
                  ),
                }}
              />
            )}
          </>
        )}
      </Drawer>

      {/* 二级抽屉：行详情 */}
      <RowDetailDrawer
        open={detailOpen}
        onClose={() => {
          setDetailOpen(false);
          setSelectedRowId(null);
          setSelectedRowData(null);
        }}
        tableName={summary?.tableName || ''}
        rowId={selectedRowId}
        columns={tableData?.columns || []}
        rowDataPreload={selectedRowData}
      />
    </>
  );
};

export default TableDataDrawer;
