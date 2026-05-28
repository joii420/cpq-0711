import React, { useState, useEffect, useCallback } from 'react';
import { Select, Radio, Button, Space, Tooltip, Alert, Typography, Tag } from 'antd';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import SelectableTable from '../../components/SelectableTable';
import {
  listBomItems,
  listBomCustomerNos,
  listBomMaterialNos,
} from '../../services/v6MasterDataService';
import type { MaterialBomItemDTO } from '../../services/v6MasterDataService';
import V6BomItemDetailDrawer from './V6BomItemDetailDrawer';

const { Text } = Typography;

type SystemType = 'ALL' | 'QUOTE' | 'PRICING' | 'BOTH';

const SYSTEM_TYPE_OPTIONS: Array<{ label: string; value: SystemType }> = [
  { label: '全部', value: 'ALL' },
  { label: '报价', value: 'QUOTE' },
  { label: '核价', value: 'PRICING' },
  { label: '共用', value: 'BOTH' },
];

const SYSTEM_TYPE_TAG: Record<string, { color: string; label: string }> = {
  QUOTE:   { color: 'blue',   label: '报价' },
  PRICING: { color: 'orange', label: '核价' },
  BOTH:    { color: 'green',  label: '共用' },
};

const PAGE_SIZE = 20;

const V6BomQueryTab: React.FC = () => {
  // 过滤条件
  const [customerNos, setCustomerNos] = useState<string[]>([]);
  const [materialNos, setMaterialNos] = useState<string[]>([]);
  const [selectedCustomer, setSelectedCustomer] = useState<string | undefined>();
  const [selectedMaterial, setSelectedMaterial] = useState<string | undefined>();
  const [systemType, setSystemType] = useState<SystemType>('ALL');

  // 加载状态
  const [customerLoading, setCustomerLoading] = useState(false);
  const [materialLoading, setMaterialLoading] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);
  const [materialTruncated, setMaterialTruncated] = useState(false);

  // 查询结果
  const [hasQueried, setHasQueried] = useState(false);
  const [data, setData] = useState<MaterialBomItemDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);

  // 详情 Drawer
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailRecord, setDetailRecord] = useState<MaterialBomItemDTO | null>(null);

  // 挂载时拉客户编号列表
  useEffect(() => {
    setCustomerLoading(true);
    listBomCustomerNos()
      .then((nos) => setCustomerNos(nos))
      .catch(() => setCustomerNos([]))
      .finally(() => setCustomerLoading(false));
  }, []);

  // 客户变化时拉料号列表
  useEffect(() => {
    if (!selectedCustomer) {
      setMaterialNos([]);
      setSelectedMaterial(undefined);
      setMaterialTruncated(false);
      return;
    }
    setMaterialLoading(true);
    setSelectedMaterial(undefined);
    setMaterialNos([]);
    listBomMaterialNos(selectedCustomer)
      .then((nos) => {
        setMaterialNos(nos);
        setMaterialTruncated(nos.length >= 500);
      })
      .catch(() => setMaterialNos([]))
      .finally(() => setMaterialLoading(false));
  }, [selectedCustomer]);

  const doQuery = useCallback(async (pg: number) => {
    if (!selectedCustomer) return;
    setTableLoading(true);
    setHasQueried(true);
    try {
      const result = await listBomItems({
        customerNo: selectedCustomer,
        materialNo: selectedMaterial || undefined,
        systemType: systemType === 'ALL' ? undefined : systemType,
        page: pg - 1,
        size: PAGE_SIZE,
      });
      setData(result.content);
      setTotal(result.totalElements);
    } catch {
      setData([]);
      setTotal(0);
    } finally {
      setTableLoading(false);
    }
  }, [selectedCustomer, selectedMaterial, systemType]);

  const handleQuery = () => {
    setPage(1);
    doQuery(1);
  };

  const handlePageChange = (pg: number) => {
    setPage(pg);
    doQuery(pg);
  };

  const openDetail = (record: MaterialBomItemDTO) => {
    setDetailRecord(record);
    setDetailOpen(true);
  };

  const columns: ColumnsType<MaterialBomItemDTO> = [
    {
      title: '序号',
      dataIndex: 'seqNo',
      width: 70,
      render: (val: number) => val !== undefined && val !== null ? val : '—',
    },
    {
      title: '系统类型',
      dataIndex: 'systemType',
      width: 90,
      render: (val: string) => {
        const t = SYSTEM_TYPE_TAG[val];
        return t ? <Tag color={t.color}>{t.label}</Tag> : val || '—';
      },
    },
    {
      title: '客户编号',
      dataIndex: 'customerNo',
      width: 130,
      render: (val: string) => val || '—',
    },
    {
      title: '料号',
      dataIndex: 'materialNo',
      width: 140,
      render: (val: string, record) => (
        <Typography.Link onClick={() => openDetail(record)}>{val || '—'}</Typography.Link>
      ),
    },
    {
      title: '特征码',
      dataIndex: 'characteristic',
      width: 120,
      render: (val: string) => val || '—',
    },
    {
      title: '组件编号',
      dataIndex: 'componentNo',
      width: 120,
      render: (val: string) => val || '—',
    },
    {
      title: '零件号',
      dataIndex: 'partNo',
      width: 120,
      render: (val: string) => val || '—',
    },
    {
      title: '工序号',
      dataIndex: 'operationNo',
      width: 90,
      render: (val: string) => val || '—',
    },
    {
      title: '组成数量',
      dataIndex: 'compositionQty',
      width: 90,
      render: (val: number) => val !== undefined && val !== null ? val : '—',
    },
    {
      title: '发料单位',
      dataIndex: 'issueUnit',
      width: 90,
      render: (val: string) => val || '—',
    },
    {
      title: '损耗率',
      dataIndex: 'scrapRate',
      width: 80,
      render: (val: number) => val !== undefined && val !== null ? val : '—',
    },
  ];

  const emptyText = !hasQueried
    ? '请先选择客户编号并点击查询'
    : '该条件下无 BOM 数据';

  const queryDisabled = !selectedCustomer;

  return (
    <>
      {/* 顶部过滤区 */}
      <div
        style={{
          background: '#fafafa',
          border: '1px solid #f0f0f0',
          borderRadius: 6,
          padding: '12px 16px',
          marginBottom: 16,
        }}
      >
        <Space wrap size={[12, 8]} align="end">
          <div>
            <div style={{ fontSize: 12, color: '#666', marginBottom: 4 }}>
              客户编号 <span style={{ color: '#ff4d4f' }}>*</span>
            </div>
            <Select
              style={{ width: 200 }}
              placeholder="请选择客户编号"
              loading={customerLoading}
              options={customerNos.map((no) => ({ label: no, value: no }))}
              value={selectedCustomer}
              onChange={(val) => {
                setSelectedCustomer(val);
                setHasQueried(false);
                setData([]);
                setTotal(0);
              }}
              showSearch
              filterOption={(input, opt) =>
                String(opt?.value ?? '').toLowerCase().includes(input.toLowerCase())
              }
              allowClear
            />
          </div>

          <div>
            <div style={{ fontSize: 12, color: '#666', marginBottom: 4 }}>料号</div>
            <div>
              <Select
                style={{ width: 220 }}
                placeholder={selectedCustomer ? '请选择料号（可选）' : '请先选择客户编号'}
                disabled={!selectedCustomer}
                loading={materialLoading}
                options={materialNos.map((no) => ({ label: no, value: no }))}
                value={selectedMaterial}
                onChange={(val) => setSelectedMaterial(val)}
                showSearch
                filterOption={(input, opt) =>
                  String(opt?.value ?? '').toLowerCase().includes(input.toLowerCase())
                }
                allowClear
              />
              {materialTruncated && (
                <div style={{ fontSize: 11, color: '#fa8c16', marginTop: 2 }}>
                  结果已截断，显示前 500 条，请用搜索精确定位
                </div>
              )}
            </div>
          </div>

          <div>
            <div style={{ fontSize: 12, color: '#666', marginBottom: 4 }}>系统类型</div>
            <Radio.Group
              options={SYSTEM_TYPE_OPTIONS}
              value={systemType}
              onChange={(e) => setSystemType(e.target.value as SystemType)}
              optionType="button"
              buttonStyle="solid"
              size="small"
            />
          </div>

          <Tooltip title={queryDisabled ? '请先选择客户编号' : ''}>
            <Button
              type="primary"
              icon={<SearchOutlined />}
              disabled={queryDisabled}
              onClick={handleQuery}
            >
              查询
            </Button>
          </Tooltip>

          <Button
            icon={<ReloadOutlined />}
            onClick={() => {
              if (hasQueried) doQuery(page);
            }}
            disabled={!hasQueried}
          >
            刷新
          </Button>
        </Space>
      </div>

      {materialTruncated && selectedMaterial === undefined && (
        <Alert
          type="warning"
          showIcon
          message="料号列表已截断（仅显示前 500 条），建议在料号搜索框输入关键词精确定位"
          style={{ marginBottom: 12 }}
          closable
        />
      )}

      <SelectableTable<MaterialBomItemDTO>
        rowKey={(r) => `${r.systemType}__${r.customerNo}__${r.materialNo}__${r.seqNo}__${r.partNo}__${r.componentNo}`}
        columns={columns}
        dataSource={data}
        loading={tableLoading}
        actions={[]}
        locale={{ emptyText }}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total,
          showSizeChanger: false,
          showTotal: (t) => `共 ${t} 条`,
          onChange: handlePageChange,
        }}
        scroll={{ x: 1100 }}
      />

      <V6BomItemDetailDrawer
        open={detailOpen}
        record={detailRecord}
        onClose={() => setDetailOpen(false)}
      />
    </>
  );
};

export default V6BomQueryTab;
