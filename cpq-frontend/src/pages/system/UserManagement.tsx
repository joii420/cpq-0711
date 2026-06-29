import React, { useEffect, useMemo, useState } from 'react';
import { Table, Button, Drawer, Form, Input, Select, Space, Tag, Popconfirm, message, Modal, TreeSelect } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { userService } from '../../services/userService';
import { regionService } from '../../services/regionService';
import { departmentService } from '../../services/departmentService';

const roleMap: Record<string, { label: string; color: string }> = {
  SYSTEM_ADMIN: { label: '系统管理员', color: 'red' },
  SALES_MANAGER: { label: '销售经理', color: 'blue' },
  SALES_REP: { label: '销售代表', color: 'green' },
  PRICING_MANAGER: { label: '财务', color: 'orange' },
};

const UserManagement: React.FC = () => {
  const [data, setData] = useState<any>({ content: [], totalElements: 0 });
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<any>(null);
  const [form] = Form.useForm();
  const [params, setParams] = useState({ page: 0, size: 20, role: '', status: '', keyword: '' });
  const [regions, setRegions] = useState<any[]>([]);
  const [departments, setDepartments] = useState<any[]>([]);

  const deptTreeData = React.useMemo(() => {
    const buildTree = (list: any[], parentId: string | null = null): any[] => {
      return list
        .filter(d => (d.parentId || null) === parentId)
        .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
        .map(d => ({
          title: d.name,
          value: d.id,
          key: d.id,
          children: buildTree(list, d.id),
        }));
    };
    return buildTree(departments);
  }, [departments]);

  const fetchData = async () => {
    setLoading(true);
    try { const res = await userService.list(params); setData(res.data); }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchData(); }, [params]);

  useEffect(() => {
    regionService.list({ page: 0, size: 1000 }).then(res => {
      setRegions((res.data?.content || []).filter((r: any) => r.status === 'ACTIVE'));
    });
    departmentService.list({ page: 0, size: 1000 }).then(res => {
      setDepartments((res.data?.content || []).filter((d: any) => d.status === 'ACTIVE'));
    });
  }, []);

  const handleSave = async (values: any) => {
    try {
      if (editingUser) {
        await userService.update(editingUser.id, values);
        message.success('更新成功');
      } else {
        const res = await userService.create(values);
        Modal.success({ title: '用户创建成功', content: `初始密码：${res.data.initialPassword}` });
      }
      setDrawerOpen(false); form.resetFields(); setEditingUser(null); fetchData();
    } catch (err: any) { message.error(err.message); }
  };

  const columns = [
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '姓名', dataIndex: 'fullName', key: 'fullName' },
    { title: '邮箱', dataIndex: 'email', key: 'email' },
    { title: '角色', dataIndex: 'role', key: 'role', render: (r: string) => <Tag color={roleMap[r]?.color}>{roleMap[r]?.label || r}</Tag> },
    {
      title: '区域', dataIndex: 'regionId', key: 'region',
      render: (v: string) => regions.find(r => r.id === v)?.name || '-',
    },
    {
      title: '部门', dataIndex: 'departmentId', key: 'department',
      render: (v: string) => departments.find(d => d.id === v)?.name || '-',
    },
    { title: '状态', dataIndex: 'status', key: 'status', render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag> },
    {
      title: '操作', key: 'actions', render: (_: any, record: any) => (
        <Space>
          <a onClick={() => { setEditingUser(record); form.setFieldsValue(record); setDrawerOpen(true); }}>编辑</a>
          {record.status === 'ACTIVE' ? (
            <Popconfirm title="确认停用？" onConfirm={async () => { try { await userService.updateStatus(record.id, 'INACTIVE'); message.success('已停用'); fetchData(); } catch (e: any) { message.error(e.message); } }}>
              <a style={{ color: 'red' }}>停用</a>
            </Popconfirm>
          ) : (
            <a onClick={async () => { await userService.updateStatus(record.id, 'ACTIVE'); message.success('已启用'); fetchData(); }}>启用</a>
          )}
          <a onClick={async () => { try { const res = await userService.resetPassword(record.id); Modal.success({ title: '密码已重置', content: `新密码：${res.data.initialPassword}` }); } catch (e: any) { message.error(e.message); } }}>重置密码</a>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <Space>
          <Input.Search placeholder="搜索用户名/姓名/邮箱" onSearch={v => setParams(p => ({ ...p, keyword: v, page: 0 }))} allowClear style={{ width: 250 }} />
          <Select placeholder="角色" allowClear style={{ width: 130 }} onChange={v => setParams(p => ({ ...p, role: v || '', page: 0 }))}>
            {Object.entries(roleMap).map(([k, v]) => <Select.Option key={k} value={k}>{v.label}</Select.Option>)}
          </Select>
          <Select placeholder="状态" allowClear style={{ width: 100 }} onChange={v => setParams(p => ({ ...p, status: v || '', page: 0 }))}>
            <Select.Option value="ACTIVE">启用</Select.Option>
            <Select.Option value="INACTIVE">停用</Select.Option>
          </Select>
        </Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingUser(null); form.resetFields(); setDrawerOpen(true); }}>新增用户</Button>
      </div>
      <Table columns={columns} dataSource={data.content} rowKey="id" loading={loading}
        pagination={{ current: params.page + 1, pageSize: params.size, total: data.totalElements, onChange: (p, s) => setParams(prev => ({ ...prev, page: p - 1, size: s || 20 })) }} />
      <Drawer title={editingUser ? '编辑用户' : '新增用户'} open={drawerOpen} onClose={() => { setDrawerOpen(false); setEditingUser(null); }} size="large">
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="username" label="用户名" rules={[{ required: !editingUser, message: '请输入用户名' }]}><Input disabled={!!editingUser} /></Form.Item>
          <Form.Item name="fullName" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}><Input /></Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ required: !editingUser, type: 'email', message: '请输入有效邮箱' }]}><Input /></Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: !editingUser, message: '请选择角色' }]}>
            <Select>{Object.entries(roleMap).map(([k, v]) => <Select.Option key={k} value={k}>{v.label}</Select.Option>)}</Select>
          </Form.Item>
          <Form.Item name="regionId" label="所属区域">
            <Select placeholder="请选择区域" allowClear>
              {regions.map(r => <Select.Option key={r.id} value={r.id}>{r.name}</Select.Option>)}
            </Select>
          </Form.Item>
          <Form.Item name="departmentId" label="所属部门">
            <TreeSelect
              treeData={deptTreeData}
              placeholder="请选择部门"
              allowClear
              treeDefaultExpandAll
            />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>保存</Button>
        </Form>
      </Drawer>
    </div>
  );
};
export default UserManagement;
