import React, { useEffect, useMemo, useState } from 'react';
import { Button, Modal, Form, Input, InputNumber, TreeSelect, Tag, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { productCategoryService } from '../../services/productCategoryService';
import type { ProductCategory } from '../../services/productCategoryService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

interface CategoryNode extends ProductCategory {
  children?: CategoryNode[];
}

function buildTree(rows: ProductCategory[]): CategoryNode[] {
  const map = new Map<string, CategoryNode>();
  rows.forEach((r) => map.set(r.id, { ...r, children: [] }));
  const roots: CategoryNode[] = [];
  map.forEach((node) => {
    if (node.parentId && map.has(node.parentId)) {
      map.get(node.parentId)!.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}

const ProductCategoryManagement: React.FC = () => {
  const [list, setList] = useState<ProductCategory[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ProductCategory | null>(null);
  const [form] = Form.useForm();

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await productCategoryService.list();
      setList(res.data || []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const tree = useMemo(() => buildTree(list), [list]);

  const treeSelectData = useMemo(() => {
    const toNodes = (rows: CategoryNode[]): any[] =>
      rows.map((r) => ({
        title: r.name,
        value: r.id,
        key: r.id,
        disabled: editing?.id === r.id,
        children: r.children && r.children.length ? toNodes(r.children) : undefined,
      }));
    return toNodes(tree);
  }, [tree, editing]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ status: 'ACTIVE', sortOrder: 0 });
    setModalOpen(true);
  };

  const openEdit = (record: ProductCategory) => {
    setEditing(record);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleSave = async (values: any) => {
    try {
      if (editing) {
        await productCategoryService.update(editing.id, values);
        message.success('更新成功');
      } else {
        await productCategoryService.create(values);
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

  const columns = [
    {
      title: '编码', dataIndex: 'code', key: 'code', width: 200,
      render: (v: string, r: ProductCategory) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a>
      ),
    },
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 80 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag>,
    },
  ];

  const actions: ToolbarAction<ProductCategory>[] = [
    {
      key: 'edit', label: '编辑', icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openEdit(rows[0]),
    },
    {
      key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => rows.length > 0 ? true : false,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个分类？',
      confirmDescription: '⚠️ 关联产品或子分类时无法删除（部分失败会列出原因）。',
      onClick: async (rows) => {
        await runBatch(rows, (r) => productCategoryService.delete(r.id).then(() => undefined), {
          rowLabel: (r) => `${r.code} ${r.name}`,
        });
        fetchData();
      },
    },
  ];

  const toolbar = (
    <>
      <h2 style={{ margin: 0 }}>产品分类管理</h2>
      <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增分类</Button>
    </>
  );

  return (
    <div>
      <SelectableTable<CategoryNode>
        rowKey="id"
        columns={columns}
        dataSource={tree}
        loading={loading}
        pagination={false}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r) => `${r.code} ${r.name}`}
      />
      <Modal
        title={editing ? '编辑分类' : '新增分类'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="code" label="编码" rules={[{ required: true, message: '请输入编码' }]}>
            <Input disabled={!!editing} placeholder="如 SILVER_POINT" />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 银点类" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="parentId" label="父级分类">
            <TreeSelect
              treeData={treeSelectData}
              allowClear
              placeholder="无父级（根分类）"
              treeDefaultExpandAll
            />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Input placeholder="ACTIVE / DISABLED" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ProductCategoryManagement;
