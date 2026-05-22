/**
 * MasterDataTableViewerPage — 主数据表单页查看 (2026-05-20)
 *
 * 用户场景: 一张页面 + 下拉框切表 + 查看数据.
 * 区别于 MasterDataPage 的"多卡片概览 + Drawer 查详情"模式 — 这是更简单的"单表深入查看".
 *
 * 字段可见性策略:
 *   - 默认隐藏系统字段 (importanceLevel=NORMAL + 硬编码黑名单)
 *   - 提供"显示系统字段"开关 (默认关)
 *
 * 默认表范围 (业务核心 4 表):
 *   - mat_part / mat_bom / mat_process / mat_composite_process
 *   - 其他表 (字典 / 客户 / 全局变量) 有专门管理页, 此处不重复
 */
import React, { useCallback, useEffect, useState } from 'react';
import { Card, Select, Switch, Input, Table, Space, Typography, Tag, Alert } from 'antd';
import { DatabaseOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { masterDataService, type PagedTableDataDTO, type ColumnMetadataDTO } from '../../services/masterDataService';

const { Title, Text } = Typography;

/** 业务核心 4 表 (用户 2026-05-20 拍板, 字典/客户/全局变量有专门管理页不重复) */
const CORE_TABLES = [
  { value: 'mat_part', label: '料号主表 (mat_part)', description: '料号基础信息: 料号/名称/规格/单重/材质ID' },
  { value: 'mat_bom', label: '料号 BOM (mat_bom)', description: '料号元素 + 子件 BOM (bom_type=ELEMENT/ASSEMBLY)' },
  { value: 'mat_process', label: '料号工序 (mat_process)', description: '料号绑定的工序序列 (按 process_code)' },
  { value: 'mat_composite_process', label: '组合工艺 (mat_composite_process)', description: '组合产品的跨子件工艺 (铆接/焊接等)' },
];

/** 系统字段黑名单 — 默认隐藏, 开"显示系统字段"开关才显示 */
const SYSTEM_FIELDS = new Set([
  'id', 'created_at', 'updated_at', 'created_by', 'updated_by',
  'version', 'import_record_id',
]);

/** 判断字段是否系统字段
 * 规则: 仅黑名单 (id/created_at/updated_at/...) — NORMAL 是默认重要性而非系统字段, 不能借此过滤
 */
function isSystemField(col: ColumnMetadataDTO): boolean {
  return SYSTEM_FIELDS.has(col.columnName);
}

const MasterDataTableViewerPage: React.FC = () => {
  const [selectedTable, setSelectedTable] = useState<string>(CORE_TABLES[0].value);
  const [search, setSearch] = useState('');
  const [showSystemFields, setShowSystemFields] = useState(false);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<PagedTableDataDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadData = useCallback(async (tableName: string, p: number, sz: number, sch?: string) => {
    setLoading(true);
    setError(null);
    try {
      const res = await masterDataService.queryTable(tableName, {
        page: p,
        size: sz,
        search: sch || undefined,
        customerId: null,
      });
      setData(res.data);
    } catch (err: any) {
      setError(err?.message || '查询失败');
      setData(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData(selectedTable, page, pageSize, search);
  }, [selectedTable, page, pageSize, loadData]);

  // 搜索 debounce — 300ms 后触发
  useEffect(() => {
    const t = setTimeout(() => {
      setPage(0);  // 搜索时回到首页
      loadData(selectedTable, 0, pageSize, search);
    }, 300);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  // 构造表格 columns - 按 showSystemFields 过滤
  const tableColumns: ColumnsType<Record<string, any>> = (data?.columns || [])
    .filter(col => showSystemFields || !isSystemField(col))
    .map(col => ({
      title: (
        <Space size={4}>
          <span>{col.label}</span>
          {col.importanceLevel === 'CRITICAL' && <Tag color="red" style={{ marginLeft: 0 }}>关键</Tag>}
          {col.importanceLevel === 'IMPORTANT' && <Tag color="orange" style={{ marginLeft: 0 }}>重要</Tag>}
          {isSystemField(col) && <Tag color="default" style={{ marginLeft: 0 }}>系统</Tag>}
        </Space>
      ),
      dataIndex: col.columnName,
      key: col.columnName,
      ellipsis: true,
      width: col.dataType === 'IDENTIFIER' ? 160 : 140,
      render: (v: any) => {
        if (v == null || v === '') return <Text type="secondary">—</Text>;
        if (typeof v === 'boolean') return v ? '是' : '否';
        if (typeof v === 'object') return <Text code style={{ fontSize: 11 }}>{JSON.stringify(v).slice(0, 80)}</Text>;
        const s = String(v);
        // 时间戳判断
        if (col.columnName.endsWith('_at') && s.length >= 10) {
          return <Text type="secondary" style={{ fontSize: 12 }}>{s.replace('T', ' ').slice(0, 19)}</Text>;
        }
        // UUID 简短显示
        if (col.dataType === 'IDENTIFIER' && /^[0-9a-f]{8}-/.test(s)) {
          return <Text code style={{ fontSize: 11 }}>{s.slice(0, 8)}...</Text>;
        }
        return s;
      },
    }));

  const currentTableInfo = CORE_TABLES.find(t => t.value === selectedTable);
  const hiddenCount = (data?.columns || []).filter(isSystemField).length;

  return (
    <div style={{ padding: 24 }}>
      <Title level={4} style={{ marginTop: 0 }}>
        <DatabaseOutlined /> 主数据表查看
      </Title>
      <Text type="secondary">
        通过下拉选择业务核心 4 表, 默认隐藏系统字段 (id / 时间戳 / 操作人等). 字典/全局变量/客户表请使用对应专门页面.
      </Text>

      <Card size="small" style={{ marginTop: 16 }}>
        <Space wrap size={16} style={{ width: '100%' }}>
          <Space>
            <Text strong>选择表:</Text>
            <Select
              style={{ width: 320 }}
              value={selectedTable}
              onChange={(v) => { setSelectedTable(v); setPage(0); }}
              options={CORE_TABLES.map(t => ({
                value: t.value,
                label: t.label,
              }))}
              data-testid="table-select"
            />
          </Space>
          <Input
            prefix={<SearchOutlined />}
            placeholder="搜索关键词 (按表主键 / 业务字段)"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            style={{ width: 280 }}
            allowClear
          />
          <Space>
            <Text>显示系统字段:</Text>
            <Switch checked={showSystemFields} onChange={setShowSystemFields} size="small" />
            {!showSystemFields && hiddenCount > 0 && (
              <Text type="secondary" style={{ fontSize: 12 }}>(已隐藏 {hiddenCount} 列)</Text>
            )}
          </Space>
        </Space>
        {currentTableInfo && (
          <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
            ℹ️ {currentTableInfo.description}
          </Text>
        )}
      </Card>

      {error && (
        <Alert
          type="error"
          showIcon
          message={`查询失败: ${error}`}
          style={{ marginTop: 16 }}
          closable
          onClose={() => setError(null)}
        />
      )}

      <Card size="small" style={{ marginTop: 16 }}>
        <Table
          loading={loading}
          dataSource={data?.rows || []}
          columns={tableColumns}
          rowKey={(record, idx) => {
            // 选 IDENTIFIER 列作 rowKey, 没有就用 index
            const idCol = data?.columns.find(c => c.dataType === 'IDENTIFIER');
            return idCol ? String(record[idCol.columnName] ?? idx) : String(idx);
          }}
          size="small"
          scroll={{ x: 'max-content' }}
          pagination={{
            current: page + 1,
            pageSize,
            total: data?.total || 0,
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50', '100'],
            showTotal: (total) => `共 ${total} 行`,
            onChange: (p, sz) => {
              setPage(p - 1);
              setPageSize(sz);
            },
          }}
        />
      </Card>
    </div>
  );
};

export default MasterDataTableViewerPage;
