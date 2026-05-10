import React, { useEffect, useState } from 'react';
import { Button, Input, Select, Tag, message, Typography } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { datasourceService } from '../../services/datasourceService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const { Search } = Input;
const { Text } = Typography;

const typeTagMap: Record<string, { label: string; color: string }> = {
  SQL: { label: 'SQL', color: 'blue' },
  API: { label: 'API', color: 'green' },
};

const statusMap: Record<string, { label: string; color: string }> = {
  ACTIVE:   { label: '启用', color: 'success' },
  DISABLED: { label: '禁用', color: 'default' },
};

const DataSourceList: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<any>({ content: [], totalElements: 0 });
  const [loading, setLoading] = useState(false);
  const [params, setParams] = useState({ page: 0, size: 20, type: '', keyword: '' });

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await datasourceService.list({
        page: params.page,
        size: params.size,
        ...(params.type ? { type: params.type } : {}),
        ...(params.keyword ? { keyword: params.keyword } : {}),
      });
      setData(res.data || { content: [], totalElements: 0 });
    } catch (err: any) {
      message.error(err?.response?.data?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, [params]);

  const columns = [
    {
      title: '编码', dataIndex: 'code', key: 'code',
      render: (code: string, r: any) => (
        <a onClick={(e) => { e.stopPropagation(); navigate(`/datasources/${r.id}/edit`); }}>
          <Text code>{code}</Text>
        </a>
      ),
    },
    { title: '名称', dataIndex: 'name', key: 'name' },
    {
      title: '类型', dataIndex: 'type', key: 'type',
      render: (type: string) => {
        const t = typeTagMap[type] || { label: type, color: 'default' };
        return <Tag color={t.color}>{t.label}</Tag>;
      },
    },
    {
      title: '状态', dataIndex: 'status', key: 'status',
      render: (status: string) => {
        const s = statusMap[status] || { label: status, color: 'default' };
        return <Tag color={s.color}>{s.label}</Tag>;
      },
    },
    {
      title: '创建时间', dataIndex: 'createdAt', key: 'createdAt',
      render: (val: string) => val ? new Date(val).toLocaleString('zh-CN') : '-',
    },
  ];

  const actions: ToolbarAction<any>[] = [
    {
      key: 'edit', label: '编辑', icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => navigate(`/datasources/${rows[0].id}/edit`),
    },
    {
      key: 'test', label: '测试', icon: <PlayCircleOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '测试一次只能选一个数据源',
      onClick: (rows) => navigate(`/datasources/${rows[0].id}/edit`, { state: { openTest: true } }),
    },
    {
      key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => rows.length > 0 ? true : false,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个数据源？',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => datasourceService.delete(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.code} ${r.name}`,
          successMsg: `已删除 ${rows.length} 项`,
        });
        fetchData();
      },
    },
  ];

  const toolbar = (
    <>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/datasources/new')}>
        新建数据源
      </Button>
      <div style={{ display: 'flex', gap: 8 }}>
        <Select
          placeholder="类型筛选"
          allowClear
          style={{ width: 120 }}
          value={params.type || undefined}
          onChange={(val) => setParams(p => ({ ...p, type: val || '', page: 0 }))}
          options={[
            { label: 'SQL', value: 'SQL' },
            { label: 'API', value: 'API' },
          ]}
        />
        <Search
          placeholder="搜索编码或名称"
          allowClear
          style={{ width: 240 }}
          onSearch={(val) => setParams(p => ({ ...p, keyword: val, page: 0 }))}
        />
      </div>
    </>
  );

  return (
    <div>
      <SelectableTable<any>
        rowKey="id"
        columns={columns}
        dataSource={data.content || []}
        loading={loading}
        pagination={{
          current: params.page + 1,
          pageSize: params.size,
          total: data.totalElements || 0,
          showTotal: (total) => `共 ${total} 条`,
          onChange: (page) => setParams(p => ({ ...p, page: page - 1 })),
        }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r: any) => `${r.code} ${r.name}`}
      />
    </div>
  );
};

export default DataSourceList;
