import React, { useEffect, useState } from 'react';
import { Card, Table, Tag, Button, Input, Select, Space, message, Drawer, Form, Popconfirm } from 'antd';
import { useNavigate } from 'react-router-dom';
import { featureLibraryService } from '../../services/featureLibraryService';
import type { FeatureGroup, FeatureGroupStatus } from '../../types/feature-library';

const FeatureLibraryList: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<FeatureGroup[]>([]);
  const [refMap, setRefMap] = useState<Record<number, number>>({});
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState<string | undefined>();
  const [category, setCategory] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<FeatureGroup | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await featureLibraryService.listGroups({ page, size, status, category, keyword });
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);

      try {
        const refRes: any = await featureLibraryService.templateRefsByGroup();
        setRefMap(refRes.data || {});
      } catch { /* 引用统计失败不阻塞列表 */ }
    } catch (e: any) {
      message.error('加载特征群组失败：' + (e?.message || ''));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); /* eslint-disable-next-line */ }, [page, size, status, category]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ status: 'DRAFT' });
    setDrawerOpen(true);
  };

  const openEdit = (g: FeatureGroup) => {
    setEditing(g);
    form.setFieldsValue(g);
    setDrawerOpen(true);
  };

  const save = async () => {
    try {
      const values = await form.validateFields();
      if (editing) {
        await featureLibraryService.updateGroup(editing.id, values);
        message.success('已更新');
      } else {
        await featureLibraryService.createGroup(values);
        message.success('已创建');
      }
      setDrawerOpen(false);
      load();
    } catch (e: any) {
      if (e?.errorFields) return; // form validation
      message.error('保存失败：' + (e?.message || ''));
    }
  };

  const archive = async (id: number) => {
    try {
      await featureLibraryService.archiveGroup(id);
      message.success('已归档');
      load();
    } catch (e: any) {
      message.error('归档失败：' + (e?.message || ''));
    }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card title="📚 特征库管理" extra={
        <Space>
          <Input.Search placeholder="编号 / 名称" allowClear style={{ width: 220 }}
                        value={keyword}
                        onChange={e => setKeyword(e.target.value)}
                        onSearch={() => { setPage(0); load(); }} />
          <Select placeholder="品类" allowClear style={{ width: 140 }}
                  options={[
                    { value: '接触片', label: '接触片' },
                    { value: '接触簧片', label: '接触簧片' },
                    { value: '端子', label: '端子' },
                    { value: '电机', label: '电机' },
                  ]}
                  value={category} onChange={setCategory} />
          <Select placeholder="状态" allowClear style={{ width: 130 }}
                  options={[
                    { value: 'DRAFT', label: 'DRAFT' },
                    { value: 'ACTIVE', label: 'ACTIVE' },
                    { value: 'ARCHIVED', label: 'ARCHIVED' },
                  ]}
                  value={status} onChange={setStatus} />
          <Button type="primary" onClick={openCreate}>+ 新建群组</Button>
        </Space>
      }>
        <Table<FeatureGroup>
          rowKey="id"
          loading={loading}
          dataSource={data}
          pagination={{
            current: page + 1, pageSize: size, total,
            onChange: (p, s) => { setPage(p - 1); setSize(s); },
            showSizeChanger: true,
          }}
          columns={[
            { title: '群组编号', dataIndex: 'code', width: 160 },
            { title: '名称', dataIndex: 'name', render: (v, r) => (
              <a onClick={() => navigate(`/system/feature-library/${r.id}`)}>{v}</a>
            )},
            { title: '品类', dataIndex: 'category', width: 120 },
            { title: '描述', dataIndex: 'description', ellipsis: true },
            { title: '引用模板', dataIndex: 'id', width: 100, align: 'center',
              render: (id: number) => {
                const n = refMap[id] || 0;
                return n > 0
                  ? <a onClick={() => navigate(`/system/configurator-templates?source_feature_group_id=${id}`)}>{n}</a>
                  : <span style={{ color: '#999' }}>0</span>;
              } },
            { title: '状态', dataIndex: 'status', width: 100, render: (s: FeatureGroupStatus) => {
              const color = s === 'ACTIVE' ? 'green' : s === 'DRAFT' ? 'default' : 'red';
              return <Tag color={color}>{s}</Tag>;
            }},
            { title: '更新时间', dataIndex: 'updatedAt', width: 170,
              render: (v: string) => v ? new Date(v).toLocaleString() : '-' },
            { title: '操作', width: 200, render: (_, r) => (
              <Space>
                <a onClick={() => navigate(`/system/feature-library/${r.id}`)}>📂 详情</a>
                <a onClick={() => openEdit(r)}>✏️ 编辑</a>
                {r.status !== 'ARCHIVED' && (
                  <Popconfirm title="归档此群组？已加载到模板的快照不受影响。" onConfirm={() => archive(r.id)}>
                    <a style={{ color: '#f5222d' }}>🗄 归档</a>
                  </Popconfirm>
                )}
              </Space>
            )},
          ]}
        />
      </Card>

      <Drawer
        title={editing ? `✏️ 编辑群组 · ${editing.code}` : '+ 新建特征群组'}
        width={560}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        extra={
          <Space>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" onClick={save}>✓ 保存</Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Form.Item label="群组编号" name="code" rules={[{ required: true, max: 40 }]}>
            <Input placeholder="如 FG-CTC-01" disabled={!!editing} />
          </Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true, max: 255 }]}>
            <Input placeholder="如 接触片特征群组" />
          </Form.Item>
          <Form.Item label="品类" name="category">
            <Select allowClear placeholder="选择品类"
                    options={[
                      { value: '接触片', label: '接触片' },
                      { value: '接触簧片', label: '接触簧片' },
                      { value: '端子', label: '端子' },
                      { value: '电机', label: '电机' },
                    ]} />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} placeholder="群组用途说明..." />
          </Form.Item>
          <Form.Item label="状态" name="status" initialValue="DRAFT">
            <Select options={[
              { value: 'DRAFT', label: 'DRAFT 草稿' },
              { value: 'ACTIVE', label: 'ACTIVE 启用' },
              { value: 'ARCHIVED', label: 'ARCHIVED 归档' },
            ]} />
          </Form.Item>
          <Form.Item label="ERP 同步编号 (imsba01)" name="erpRefCode">
            <Input placeholder="留空 = 不同步" />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
};

export default FeatureLibraryList;
