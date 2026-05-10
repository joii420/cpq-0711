import React, { useCallback, useEffect, useState } from 'react';
import {
  Button, Card, Col, Row, Select, Space, Tag, Typography, Input, message, Pagination, Spin, Empty, Alert,
} from 'antd';
import { HistoryOutlined, DiffOutlined, EyeOutlined } from '@ant-design/icons';
import type { VersionHistoryItemDTO } from '../../types/versioning';
import { versioningService } from '../../services/versioningService';
import VersionDetailDrawer from './VersionDetailDrawer';
import VersionCompareDrawer from './VersionCompareDrawer';
import SelectableTable, { type ToolbarAction } from '../../components/SelectableTable';

const { Title, Text } = Typography;
const { Option } = Select;
const { Search } = Input;

const TABLE_OPTIONS = [
  { value: 'mat_process', label: '加工工序（mat_process）' },
  { value: 'mat_fee', label: '费用主档（mat_fee）' },
  { value: 'plating_fee', label: '电镀费用（plating_fee）' },
];

const MOCK_CUSTOMERS = [
  { id: 'cust-001', name: '华为技术有限公司' },
  { id: 'cust-002', name: '中兴通讯股份有限公司' },
  { id: 'cust-003', name: '小米科技有限公司' },
];

interface CompareState {
  open: boolean;
  tableName: string;
  recordIdA: string;
  recordIdB: string;
  versionA: number;
  versionB: number;
}

const DEFAULT_COMPARE: CompareState = {
  open: false,
  tableName: '',
  recordIdA: '',
  recordIdB: '',
  versionA: 0,
  versionB: 0,
};

const VersionHistoryPage: React.FC = () => {
  const [customerId, setCustomerId] = useState<string | undefined>();
  const [tableName, setTableName] = useState<string | undefined>();
  const [hfPartNo, setHfPartNo] = useState('');

  const [items, setItems] = useState<VersionHistoryItemDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [backendError, setBackendError] = useState<string | null>(null);

  const [detailRecord, setDetailRecord] = useState<VersionHistoryItemDTO | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [compare, setCompare] = useState<CompareState>(DEFAULT_COMPARE);

  const PAGE_SIZE = 20;

  const loadData = useCallback(async (pg: number) => {
    setLoading(true);
    setBackendError(null);
    try {
      const res = await versioningService.listHistory({
        customerId,
        tableName,
        hfPartNo: hfPartNo || undefined,
        page: pg,
        size: PAGE_SIZE,
      });
      setItems(res.items);
      setTotal(res.total);
    } catch (err: any) {
      const msg = err?.response?.data?.message || err?.message || '加载失败';
      setBackendError(msg);
    } finally {
      setLoading(false);
    }
  }, [customerId, tableName, hfPartNo]);

  useEffect(() => {
    setPage(0);
    loadData(0);
  }, [loadData]);

  const handleSearch = () => {
    setPage(0);
    loadData(0);
  };

  const handlePageChange = (p: number) => {
    const pg = p - 1;
    setPage(pg);
    loadData(pg);
  };

  const columns = [
    {
      title: '版本号', dataIndex: 'version', key: 'version', width: 100,
      render: (v: number, row: VersionHistoryItemDTO) => (
        <Space>
          <Text strong>v{v}</Text>
          {row.isCurrent && <Tag color="green">当前</Tag>}
        </Space>
      ),
    },
    {
      title: '数据表', dataIndex: 'tableName', key: 'tableName', width: 160,
      render: (v: string) => {
        const opt = TABLE_OPTIONS.find((o) => o.value === v);
        return opt ? <Tag>{opt.label.split('（')[0]}</Tag> : <Tag>{v}</Tag>;
      },
    },
    {
      title: '料号（HF）', dataIndex: 'hfPartNo', key: 'hfPartNo', width: 160,
      render: (v: string, r: VersionHistoryItemDTO) => (
        <a onClick={(e) => { e.stopPropagation(); setDetailRecord(r); setDetailOpen(true); }}>{v}</a>
      ),
    },
    {
      title: '业务键摘要', key: 'businessKey',
      render: (_: any, row: VersionHistoryItemDTO) => {
        const entries = Object.entries(row.businessKey ?? {}).slice(0, 3);
        return (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {entries.map(([k, v]) => `${k}: ${v}`).join(' | ')}
          </Text>
        );
      },
    },
    {
      title: '修改时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 180,
      render: (v: string) => v ? new Date(v).toLocaleString() : '—',
    },
    {
      title: '修改人', dataIndex: 'updatedByName', key: 'updatedByName', width: 120,
      render: (name: string, row: VersionHistoryItemDTO) => name || row.updatedBy || '—',
    },
  ];

  const actions: ToolbarAction<VersionHistoryItemDTO>[] = [
    {
      key: 'detail', label: '详情', icon: <EyeOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '查看详情一次只能选一行',
      onClick: (rows) => { setDetailRecord(rows[0]); setDetailOpen(true); },
    },
    {
      key: 'compare', label: '对比版本', icon: <DiffOutlined />,
      enabledWhen: (rows) => {
        if (rows.length !== 2) return '对比需要选择 2 行';
        if (rows[0].tableName !== rows[1].tableName) return '只能对比同一数据表的版本';
        return true;
      },
      onClick: (rows) => {
        const [rowA, rowB] = [...rows].sort((a, b) => a.version - b.version);
        setCompare({
          open: true,
          tableName: rowA.tableName,
          recordIdA: rowA.recordId,
          recordIdB: rowB.recordId,
          versionA: rowA.version,
          versionB: rowB.version,
        });
      },
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 24 }}>
        <Space align="center">
          <HistoryOutlined style={{ fontSize: 22, color: '#1677ff' }} />
          <Title level={4} style={{ margin: 0 }}>历史版本管理</Title>
        </Space>
        <div style={{ marginTop: 4 }}>
          <Text type="secondary">查看主数据表的历史版本记录，选择 2 个同表版本进行字段值对比</Text>
        </div>
      </div>

      <Card style={{ marginBottom: 16 }}>
        <Row gutter={[16, 12]} align="middle">
          <Col xs={24} sm={12} md={6}>
            <Select placeholder="选择客户" allowClear style={{ width: '100%' }} value={customerId} onChange={setCustomerId}>
              {MOCK_CUSTOMERS.map((c) => (<Option key={c.id} value={c.id}>{c.name}</Option>))}
            </Select>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Select placeholder="选择数据表" allowClear style={{ width: '100%' }} value={tableName} onChange={setTableName}>
              {TABLE_OPTIONS.map((o) => (<Option key={o.value} value={o.value}>{o.label}</Option>))}
            </Select>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Search
              placeholder="料号搜索（HF 料号）"
              allowClear
              value={hfPartNo}
              onChange={(e) => setHfPartNo(e.target.value)}
              onSearch={handleSearch}
            />
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Button type="primary" onClick={handleSearch} loading={loading}>查询</Button>
          </Col>
        </Row>
      </Card>

      {backendError && (
        <Alert type="error" message={`加载失败：${backendError}`} showIcon closable style={{ marginBottom: 16 }} />
      )}

      <Card>
        <Spin spinning={loading}>
          {items.length === 0 && !loading ? (
            <Empty description="暂无历史版本记录" />
          ) : (
            <>
              <SelectableTable<VersionHistoryItemDTO>
                rowKey="recordId"
                columns={columns}
                dataSource={items}
                pagination={false}
                size="middle"
                actions={actions}
                rowLabel={(r) => `${r.tableName} v${r.version} (${r.hfPartNo || '—'})`}
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

      <VersionDetailDrawer
        open={detailOpen}
        record={detailRecord}
        onClose={() => setDetailOpen(false)}
      />

      <VersionCompareDrawer
        open={compare.open}
        tableName={compare.tableName}
        recordIdA={compare.recordIdA}
        recordIdB={compare.recordIdB}
        versionA={compare.versionA}
        versionB={compare.versionB}
        onClose={() => setCompare(DEFAULT_COMPARE)}
      />
    </div>
  );
};

export default VersionHistoryPage;
