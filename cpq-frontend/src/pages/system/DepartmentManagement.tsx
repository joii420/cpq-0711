import React, { useEffect, useState, useMemo } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Space, Tag, TreeSelect, Popconfirm, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { departmentService } from '../../services/departmentService';

interface DeptNode {
  id: string;
  code: string;
  name: string;
  parentId: string | null;
  sortOrder: number;
  status: string;
  children?: DeptNode[];
}

/** Build tree from flat list */
function buildTree(list: DeptNode[]): DeptNode[] {
  const map = new Map<string, DeptNode>();
  const roots: DeptNode[] = [];
  list.forEach(d => map.set(d.id, { ...d, children: [] }));
  map.forEach(node => {
    if (node.parentId && map.has(node.parentId)) {
      map.get(node.parentId)!.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  // Sort children by sortOrder
  const sortChildren = (nodes: DeptNode[]) => {
    nodes.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
    nodes.forEach(n => n.children && sortChildren(n.children));
  };
  sortChildren(roots);
  return roots;
}

/** Build TreeSelect data, excluding a node and its descendants */
function buildTreeSelectData(tree: DeptNode[], excludeId?: string): any[] {
  return tree
    .filter(n => n.id !== excludeId)
    .map(n => ({
      title: n.name,
      value: n.id,
      key: n.id,
      disabled: n.status !== 'ACTIVE',
      children: n.children ? buildTreeSelectData(n.children, excludeId) : [],
    }));
}

const DepartmentManagement: React.FC = () => {
  const [flatData, setFlatData] = useState<DeptNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form] = Form.useForm();

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await departmentService.list({ size: 1000 });
      setFlatData(res.data?.content || []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const treeData = useMemo(() => buildTree(flatData), [flatData]);

  const handleSave = async (values: any) => {
    try {
      const payload = { ...values, parentId: values.parentId || null };
      if (editing) {
        await departmentService.update(editing.id, payload);
        message.success('更新成功');
      } else {
        await departmentService.create(payload);
        message.success('创建成功');
      }
      setModalOpen(false);
      form.resetFields();
      setEditing(null);
      fetchData();
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const treeSelectOptions = useMemo(
    () => buildTreeSelectData(treeData, editing?.id),
    [treeData, editing]
  );

  const columns = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '编码', dataIndex: 'code', key: 'code', width: 150 },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 80 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 160,
      render: (_: any, record: any) => (
        <Space>
          <a onClick={() => {
            setEditing(record);
            form.setFieldsValue({ ...record, parentId: record.parentId || undefined });
            setModalOpen(true);
          }}>编辑</a>
          <a onClick={() => {
            setEditing(null);
            form.resetFields();
            form.setFieldsValue({ parentId: record.id });
            setModalOpen(true);
          }}>添加子部门</a>
          {record.status === 'ACTIVE' ? (
            <Popconfirm title="确认停用？" onConfirm={async () => {
              try { await departmentService.updateStatus(record.id, 'DISABLED'); message.success('已停用'); fetchData(); }
              catch (e: any) { message.error(e.message); }
            }}>
              <a style={{ color: 'red' }}>停用</a>
            </Popconfirm>
          ) : (
            <a onClick={async () => {
              try { await departmentService.updateStatus(record.id, 'ACTIVE'); message.success('已启用'); fetchData(); }
              catch (e: any) { message.error(e.message); }
            }}>启用</a>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); form.resetFields(); setModalOpen(true); }}>
          新增部门
        </Button>
      </div>
      <Table
        columns={columns}
        dataSource={treeData}
        rowKey="id"
        loading={loading}
        pagination={false}
        defaultExpandAllRows
        childrenColumnName="children"
      />
      <Modal
        title={editing ? '编辑部门' : '新增部门'}
        open={modalOpen}
        onCancel={() => { setModalOpen(false); setEditing(null); }}
        onOk={() => form.submit()}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="parentId" label="上级部门">
            <TreeSelect
              treeData={treeSelectOptions}
              placeholder="无（顶级部门）"
              allowClear
              treeDefaultExpandAll
            />
          </Form.Item>
          <Form.Item name="code" label="编码" rules={[{ required: !editing, message: '请输入编码' }]}>
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default DepartmentManagement;
