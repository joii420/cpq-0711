import React, { useEffect, useState } from 'react';
import { Table, Select, DatePicker, Space, Card, message } from 'antd';
import api from '../../services/api';

const { RangePicker } = DatePicker;

const operationTypeOptions = [
  { label: '创建', value: 'CREATE' },
  { label: '更新', value: 'UPDATE' },
  { label: '删除', value: 'DELETE' },
  { label: '审批', value: 'APPROVE' },
  { label: '驳回', value: 'REJECT' },
  { label: '登录', value: 'LOGIN' },
  { label: '登出', value: 'LOGOUT' },
];

const targetTypeOptions = [
  { label: '用户', value: 'USER' },
  { label: '客户', value: 'CUSTOMER' },
  { label: '产品', value: 'PRODUCT' },
  { label: '报价单', value: 'QUOTATION' },
  { label: '模板', value: 'TEMPLATE' },
  { label: '组件', value: 'COMPONENT' },
  { label: '定价策略', value: 'PRICING_STRATEGY' },
];

const OperationLogList: React.FC = () => {
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [filters, setFilters] = useState<any>({
    operationType: '',
    targetType: '',
    startDate: '',
    endDate: '',
  });

  const fetchData = async (p = page, f = filters) => {
    setLoading(true);
    try {
      const params: any = { page: p, size };
      if (f.operationType) params.operationType = f.operationType;
      if (f.targetType) params.targetType = f.targetType;
      if (f.startDate) params.startDate = f.startDate;
      if (f.endDate) params.endDate = f.endDate;
      const res = await api.get('/operation-logs', { params }) as any;
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [page, filters]);

  const handleDateChange = (dates: any) => {
    if (dates && dates.length === 2) {
      setFilters((f: any) => ({
        ...f,
        startDate: dates[0].format('YYYY-MM-DD'),
        endDate: dates[1].format('YYYY-MM-DD'),
      }));
    } else {
      setFilters((f: any) => ({ ...f, startDate: '', endDate: '' }));
    }
    setPage(0);
  };

  const columns = [
    { title: '操作人ID', dataIndex: 'operatorId', key: 'operatorId', ellipsis: true },
    {
      title: '操作类型',
      dataIndex: 'operationType',
      key: 'operationType',
      render: (v: string) => operationTypeOptions.find(o => o.value === v)?.label || v,
    },
    {
      title: '目标类型',
      dataIndex: 'targetType',
      key: 'targetType',
      render: (v: string) => targetTypeOptions.find(o => o.value === v)?.label || v,
    },
    { title: '目标ID', dataIndex: 'targetId', key: 'targetId', ellipsis: true, render: (v: string) => v || '-' },
    { title: '摘要', dataIndex: 'summary', key: 'summary', ellipsis: true, render: (v: string) => v || '-' },
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: string) => v ? new Date(v).toLocaleString() : '-',
    },
  ];

  return (
    <Card title="操作日志">
      <Space style={{ marginBottom: 16 }} wrap>
        <Select
          placeholder="操作类型"
          allowClear
          style={{ width: 130 }}
          options={operationTypeOptions}
          onChange={v => { setFilters((f: any) => ({ ...f, operationType: v || '' })); setPage(0); }}
        />
        <Select
          placeholder="目标类型"
          allowClear
          style={{ width: 130 }}
          options={targetTypeOptions}
          onChange={v => { setFilters((f: any) => ({ ...f, targetType: v || '' })); setPage(0); }}
        />
        <RangePicker onChange={handleDateChange} />
      </Space>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          onChange: (p) => setPage(p - 1),
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </Card>
  );
};

export default OperationLogList;
