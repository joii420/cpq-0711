import React, { useEffect, useState } from 'react';
import {
  Table, Button, Select, Card, Space, Tag, Drawer, Descriptions,
  DatePicker, message, Typography,
} from 'antd';
import { DownloadOutlined, EyeOutlined, CloudUploadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { importService } from '../../services/importService';
import { customerService } from '../../services/customerService';
import BasicDataImportV5Wizard from '../quotation/BasicDataImportV5Wizard';

const { RangePicker } = DatePicker;
const { Text } = Typography;

const statusMap: Record<string, { label: string; color: string }> = {
  SUCCESS: { label: '成功', color: 'green' },
  PARTIAL: { label: '部分成功', color: 'orange' },
  FAILED: { label: '失败', color: 'red' },
  PROCESSING: { label: '处理中', color: 'processing' },
};

const ImportHistoryList: React.FC = () => {
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [customers, setCustomers] = useState<any[]>([]);
  const [customerFilter, setCustomerFilter] = useState<string>('');
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);
  const [detailDrawerOpen, setDetailDrawerOpen] = useState(false);
  const [detailRecord, setDetailRecord] = useState<any>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  // V5 增强导入向导
  const [v5WizardOpen, setV5WizardOpen] = useState(false);

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await importService.list({
        page,
        size: 20,
        customerId: customerFilter || undefined,
        status: statusFilter || undefined,
        startDate: dateRange?.[0]?.format('YYYY-MM-DD') || undefined,
        endDate: dateRange?.[1]?.format('YYYY-MM-DD') || undefined,
      });
      setData(res.data?.content || res.data || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  const fetchCustomers = async () => {
    try {
      const res = await customerService.list({ size: 200 });
      setCustomers(res.data?.content || []);
    } catch {
      // silently fail
    }
  };

  useEffect(() => { fetchCustomers(); }, []);
  useEffect(() => { fetchData(); }, [page, customerFilter, statusFilter, dateRange]);

  const openDetail = async (record: any) => {
    setDetailRecord(record);
    setDetailDrawerOpen(true);
    if (!record.rows) {
      setDetailLoading(true);
      try {
        const res = await importService.getById(record.id);
        setDetailRecord(res.data || res);
      } catch (e: any) {
        message.error(e.message);
      } finally {
        setDetailLoading(false);
      }
    }
  };

  const handleDownload = async (id: string) => {
    try {
      const res = await importService.download(id);
      const blob = res.data || res;
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `import-${id}.xlsx`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      message.error('下载失败: ' + e.message);
    }
  };

  const columns = [
    {
      title: '导入时间', dataIndex: 'createdAt', key: 'createdAt', width: 160,
      render: (v: string) => v ? new Date(v).toLocaleString() : '-',
    },
    { title: '操作人', dataIndex: 'operatorName', key: 'operatorName', width: 100 },
    { title: '客户', dataIndex: 'customerName', key: 'customerName', ellipsis: true },
    { title: 'CPQ模板', dataIndex: 'templateName', key: 'templateName', ellipsis: true },
    { title: '文件名', dataIndex: 'fileName', key: 'fileName', ellipsis: true },
    {
      title: '行数统计', key: 'rowStats', width: 140,
      render: (_: any, record: any) => (
        <Space size={4}>
          <Tag color="blue">{record.totalRows ?? 0} 总</Tag>
          <Tag color="green">{record.successRows ?? 0} 成</Tag>
          {record.failedRows > 0 && <Tag color="red">{record.failedRows} 失</Tag>}
        </Space>
      ),
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (v: string) => {
        const s = statusMap[v] || { label: v, color: 'default' };
        return <Tag color={s.color}>{s.label}</Tag>;
      },
    },
    {
      title: '操作', key: 'actions', width: 90,
      render: (_: any, record: any) => (
        <Space size={4}>
          <Button type="text" size="small" icon={<EyeOutlined />} onClick={() => openDetail(record)} title="详情" />
          <Button type="text" size="small" icon={<DownloadOutlined />} onClick={() => handleDownload(record.id)} title="下载" />
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="导入历史"
      extra={
        <Button
          type="primary"
          icon={<CloudUploadOutlined />}
          onClick={() => setV5WizardOpen(true)}
        >
          V5 增强导入
        </Button>
      }
    >
      <div style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <Select
          placeholder="筛选客户"
          allowClear
          style={{ width: 180 }}
          onChange={v => { setCustomerFilter(v || ''); setPage(0); }}
          showSearch
          filterOption={(input, option) =>
            String(option?.children || '').toLowerCase().includes(input.toLowerCase())
          }
        >
          {customers.map((c: any) => (
            <Select.Option key={c.id} value={c.id}>{c.name}</Select.Option>
          ))}
        </Select>
        <Select
          placeholder="筛选状态"
          allowClear
          style={{ width: 140 }}
          onChange={v => { setStatusFilter(v || ''); setPage(0); }}
        >
          <Select.Option value="SUCCESS">成功</Select.Option>
          <Select.Option value="PARTIAL">部分成功</Select.Option>
          <Select.Option value="FAILED">失败</Select.Option>
          <Select.Option value="PROCESSING">处理中</Select.Option>
        </Select>
        <RangePicker
          onChange={dates => {
            setDateRange(dates as [dayjs.Dayjs | null, dayjs.Dayjs | null] | null);
            setPage(0);
          }}
        />
      </div>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{
          current: page + 1,
          pageSize: 20,
          total,
          showTotal: t => `共 ${t} 条`,
          onChange: p => setPage(p - 1),
        }}
      />

      <Drawer
        title="导入详情"
        open={detailDrawerOpen}
        onClose={() => { setDetailDrawerOpen(false); setDetailRecord(null); }}
        width={560}
      >
        {detailLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}>加载中...</div>
        ) : detailRecord ? (
          <Descriptions bordered column={1} size="small">
            <Descriptions.Item label="导入时间">
              {detailRecord.createdAt ? new Date(detailRecord.createdAt).toLocaleString() : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="操作人">{detailRecord.operatorName || '-'}</Descriptions.Item>
            <Descriptions.Item label="客户">{detailRecord.customerName || '-'}</Descriptions.Item>
            <Descriptions.Item label="CPQ模板">{detailRecord.templateName || '-'}</Descriptions.Item>
            <Descriptions.Item label="文件名">{detailRecord.fileName || '-'}</Descriptions.Item>
            <Descriptions.Item label="总行数">{detailRecord.totalRows ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="成功行数">
              <Text type="success">{detailRecord.successRows ?? '-'}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="失败行数">
              <Text type="danger">{detailRecord.failedRows ?? '-'}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              {(() => {
                const s = statusMap[detailRecord.status] || { label: detailRecord.status, color: 'default' };
                return <Tag color={s.color}>{s.label}</Tag>;
              })()}
            </Descriptions.Item>
            {detailRecord.errorMessage && (
              <Descriptions.Item label="错误信息">
                <Text type="danger">{detailRecord.errorMessage}</Text>
              </Descriptions.Item>
            )}
            {detailRecord.quotationId && (
              <Descriptions.Item label="关联报价单">
                <a href={`/quotations/${detailRecord.quotationId}`}>查看报价单</a>
              </Descriptions.Item>
            )}
          </Descriptions>
        ) : null}
      </Drawer>

      {/* V5 增强导入向导 */}
      <BasicDataImportV5Wizard
        open={v5WizardOpen}
        customers={customers.map((c: any) => ({ id: c.id, name: c.name }))}
        onClose={() => setV5WizardOpen(false)}
        onSuccess={(recordId) => {
          setV5WizardOpen(false);
          message.success(`导入成功，记录 ID：${recordId}`);
          fetchData();
        }}
      />
    </Card>
  );
};

export default ImportHistoryList;
