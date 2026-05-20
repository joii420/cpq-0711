/**
 * ConfigTemplateManagement
 *
 * V203 / Phase B2: 配置模板的管理菜单 (一级菜单).
 *
 * 功能:
 *   - 模板列表 (SelectableTable + 工具栏操作: 新建/编辑/发布/归档/删除)
 *   - 编辑 Drawer: 元信息 + 大类 panel + 明细项 panel
 *   - 三态机操作: DRAFT 可编辑/发布/删除; PUBLISHED 可归档/查看; ARCHIVED 仅查看
 */
import React, { useEffect, useState } from 'react';
import {
  Drawer, Button, Form, Input, Modal, Tag, message, Space, Table, Empty, Popconfirm, Tooltip,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ArrowUpOutlined, InboxOutlined } from '@ant-design/icons';
import {
  configTemplateService,
  type ConfigTemplate, type ConfigCategory, type ConfigItem,
} from '../../services/configTemplateService';

const ConfigTemplateManagement: React.FC = () => {
  const [list, setList] = useState<ConfigTemplate[]>([]);
  const [loading, setLoading] = useState(false);

  // 新建/编辑模板
  const [editingTemplate, setEditingTemplate] = useState<ConfigTemplate | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [tplForm] = Form.useForm();

  // 编辑大类/明细项 Drawer
  const [drawerTemplate, setDrawerTemplate] = useState<ConfigTemplate | null>(null);

  const reload = async () => {
    setLoading(true);
    try {
      const r = await configTemplateService.list();
      setList(r.data || []);
    } catch (e: any) {
      message.error(e?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { reload(); }, []);

  const refreshDetail = async (id: string) => {
    const r = await configTemplateService.getById(id);
    setDrawerTemplate(r.data);
    return r.data;
  };

  const handleCreate = async () => {
    try {
      const v = await tplForm.validateFields();
      await configTemplateService.create(v);
      message.success('已创建 DRAFT 模板');
      setShowCreate(false);
      tplForm.resetFields();
      reload();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.response?.data?.message ?? e?.message ?? '创建失败');
    }
  };

  const handleUpdateMeta = async () => {
    if (!editingTemplate) return;
    try {
      const v = await tplForm.validateFields();
      await configTemplateService.update(editingTemplate.id, v);
      message.success('已更新');
      setEditingTemplate(null);
      tplForm.resetFields();
      reload();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.response?.data?.message ?? e?.message ?? '更新失败');
    }
  };

  const handlePublish = async (t: ConfigTemplate) => {
    try {
      await configTemplateService.publish(t.id);
      message.success(`已发布: ${t.name}`);
      reload();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? e?.message ?? '发布失败');
    }
  };
  const handleArchive = async (t: ConfigTemplate) => {
    try {
      await configTemplateService.archive(t.id);
      message.success(`已归档: ${t.name}`);
      reload();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? e?.message ?? '归档失败');
    }
  };
  const handleDelete = async (t: ConfigTemplate) => {
    try {
      await configTemplateService.delete(t.id);
      message.success(`已删除: ${t.name}`);
      reload();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? e?.message ?? '删除失败');
    }
  };

  const statusTag = (s: string) => {
    if (s === 'DRAFT') return <Tag color="default">草稿</Tag>;
    if (s === 'PUBLISHED') return <Tag color="green">已发布</Tag>;
    return <Tag color="orange">已归档</Tag>;
  };

  const columns: any[] = [
    { title: 'Code', dataIndex: 'code', key: 'code', width: 160, render: (v: string) => <code>{v}</code> },
    { title: '名称', dataIndex: 'name', key: 'name', width: 220 },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    { title: '状态', dataIndex: 'status', key: 'status', width: 90, render: statusTag },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 180, render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-' },
    {
      title: '操作',
      key: 'actions',
      width: 320,
      render: (_: any, t: ConfigTemplate) => (
        <Space size={4}>
          <Tooltip title={t.status === 'ARCHIVED' ? '已归档不可编辑结构, 仅查看' : '编辑大类/明细项'}>
            <Button size="small" type="link" icon={<EditOutlined />}
              onClick={() => refreshDetail(t.id)}>
              {t.status === 'ARCHIVED' ? '查看' : '编辑结构'}
            </Button>
          </Tooltip>
          {t.status !== 'ARCHIVED' && (
            <Button size="small" type="link" onClick={() => { setEditingTemplate(t); tplForm.setFieldsValue(t); }}>
              改元信息
            </Button>
          )}
          {t.status === 'DRAFT' && (
            <Tooltip title="发布后可被组件字段引用">
              <Button size="small" type="link" icon={<ArrowUpOutlined />} onClick={() => handlePublish(t)}>
                发布
              </Button>
            </Tooltip>
          )}
          {t.status === 'PUBLISHED' && (
            <Popconfirm title={`归档 ${t.name}?`} onConfirm={() => handleArchive(t)}>
              <Button size="small" type="link" icon={<InboxOutlined />} danger>归档</Button>
            </Popconfirm>
          )}
          {t.status === 'DRAFT' && (
            <Popconfirm title={`删除 ${t.name}?`} onConfirm={() => handleDelete(t)}>
              <Button size="small" type="link" icon={<DeleteOutlined />} danger>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 16 }}>
      <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>配置模板管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { tplForm.resetFields(); setShowCreate(true); }}>
          新建模板
        </Button>
      </div>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        size="middle"
        pagination={{ pageSize: 20 }}
      />

      {/* 新建 Modal */}
      <Modal
        title="新建配置模板"
        open={showCreate}
        onCancel={() => setShowCreate(false)}
        onOk={handleCreate}
        okText="创建为 DRAFT"
      >
        <Form form={tplForm} layout="vertical">
          <Form.Item label="Code" name="code" rules={[{ required: true, max: 50 }]}>
            <Input placeholder="例: STD_PROCESS_RULES" />
          </Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true, max: 200 }]}>
            <Input placeholder="例: 标准工序定价规则" />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 元信息 Edit Modal */}
      <Modal
        title={`修改元信息: ${editingTemplate?.code}`}
        open={!!editingTemplate}
        onCancel={() => setEditingTemplate(null)}
        onOk={handleUpdateMeta}
      >
        <Form form={tplForm} layout="vertical">
          <Form.Item label="Code" name="code" rules={[{ required: true, max: 50 }]}>
            <Input />
          </Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true, max: 200 }]}>
            <Input />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 编辑大类/明细项 Drawer */}
      <ConfigTemplateEditDrawer
        template={drawerTemplate}
        onClose={() => setDrawerTemplate(null)}
        onChange={() => drawerTemplate && refreshDetail(drawerTemplate.id).then(() => reload())}
      />
    </div>
  );
};

// ────────────────────────────────────────────────────────────────────────────
// 大类 + 明细项 编辑 Drawer
// ────────────────────────────────────────────────────────────────────────────

interface DrawerProps {
  template: ConfigTemplate | null;
  onClose: () => void;
  onChange: () => void;
}

const ConfigTemplateEditDrawer: React.FC<DrawerProps> = ({ template, onClose, onChange }) => {
  const [selectedCatId, setSelectedCatId] = useState<string | null>(null);

  // category create
  const [showAddCat, setShowAddCat] = useState(false);
  const [catForm] = Form.useForm();

  // category rename
  const [editingCat, setEditingCat] = useState<ConfigCategory | null>(null);

  // item form
  const [showAddItem, setShowAddItem] = useState(false);
  const [itemForm] = Form.useForm();
  const [editingItem, setEditingItem] = useState<ConfigItem | null>(null);

  useEffect(() => {
    if (template && template.categories.length > 0 && !selectedCatId) {
      setSelectedCatId(template.categories[0].id);
    }
    if (!template) setSelectedCatId(null);
  }, [template, selectedCatId]);

  if (!template) return null;
  const readOnly = template.status === 'ARCHIVED';
  const selectedCat = template.categories.find(c => c.id === selectedCatId);

  const addCategory = async () => {
    try {
      const v = await catForm.validateFields();
      await configTemplateService.createCategory(template.id, v);
      message.success('已加大类');
      catForm.resetFields();
      setShowAddCat(false);
      onChange();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.response?.data?.message ?? '创建失败');
    }
  };
  const renameCategory = async () => {
    if (!editingCat) return;
    try {
      const v = await catForm.validateFields();
      await configTemplateService.updateCategory(editingCat.id, v);
      message.success('已更新大类');
      catForm.resetFields();
      setEditingCat(null);
      onChange();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.response?.data?.message ?? '更新失败');
    }
  };
  const deleteCategory = async (c: ConfigCategory) => {
    try {
      await configTemplateService.deleteCategory(c.id);
      message.success('已删除大类');
      if (selectedCatId === c.id) setSelectedCatId(null);
      onChange();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '删除失败');
    }
  };

  const addItem = async () => {
    if (!selectedCat) return;
    try {
      const v = await itemForm.validateFields();
      await configTemplateService.createItem(selectedCat.id, v);
      message.success('已加明细项');
      itemForm.resetFields();
      setShowAddItem(false);
      onChange();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.response?.data?.message ?? '创建失败');
    }
  };
  const updateItem = async () => {
    if (!editingItem) return;
    try {
      const v = await itemForm.validateFields();
      await configTemplateService.updateItem(editingItem.id, v);
      message.success('已更新明细项');
      itemForm.resetFields();
      setEditingItem(null);
      onChange();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.response?.data?.message ?? '更新失败');
    }
  };
  const deleteItem = async (i: ConfigItem) => {
    try {
      await configTemplateService.deleteItem(i.id);
      message.success('已删除明细项');
      onChange();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '删除失败');
    }
  };

  return (
    <Drawer
      open={!!template}
      onClose={onClose}
      title={`${template.name} - 大类与明细项 ${readOnly ? '(已归档,只读)' : ''}`}
      placement="right"
      width={1100}
    >
      <div style={{ display: 'flex', gap: 12, height: 'calc(100vh - 130px)' }}>
        {/* 左栏: 大类列表 */}
        <div style={{ width: 260, borderRight: '0.5px solid #eee', paddingRight: 8, display: 'flex', flexDirection: 'column' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
            <strong>大类</strong>
            {!readOnly && (
              <Button size="small" icon={<PlusOutlined />}
                onClick={() => { catForm.resetFields(); setShowAddCat(true); }}>
                新建
              </Button>
            )}
          </div>
          <div style={{ flex: 1, overflow: 'auto' }}>
            {template.categories.length === 0 ? (
              <Empty description="暂无大类" />
            ) : template.categories.map(c => (
              <div
                key={c.id}
                onClick={() => setSelectedCatId(c.id)}
                style={{
                  padding: '8px 10px',
                  borderRadius: 6,
                  cursor: 'pointer',
                  marginBottom: 2,
                  background: c.id === selectedCatId ? '#e6f4ff' : 'transparent',
                  borderLeft: c.id === selectedCatId ? '3px solid #1677ff' : '3px solid transparent',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ fontWeight: 500 }}>{c.name}</span>
                  <Tag style={{ fontSize: 10 }}>{c.items.length}</Tag>
                </div>
                <div style={{ fontSize: 11, color: '#999', fontFamily: 'Consolas, Monaco, monospace' }}>{c.code}</div>
                {!readOnly && c.id === selectedCatId && (
                  <Space size={2} style={{ marginTop: 4 }}>
                    <Button size="small" type="link" icon={<EditOutlined />}
                      onClick={(e) => { e.stopPropagation(); setEditingCat(c); catForm.setFieldsValue(c); }}>
                      改名
                    </Button>
                    <Popconfirm
                      title={`删除大类 ${c.name}?(级联删除其下所有明细项)`}
                      onConfirm={(e) => { e?.stopPropagation(); deleteCategory(c); }}
                    >
                      <Button size="small" type="link" danger icon={<DeleteOutlined />}>删除</Button>
                    </Popconfirm>
                  </Space>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* 右栏: 选定大类的明细项 */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          {!selectedCat ? (
            <Empty description="请在左侧选择大类" style={{ marginTop: 80 }} />
          ) : (
            <>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <strong>明细项 - {selectedCat.name} ({selectedCat.items.length} 项)</strong>
                {!readOnly && (
                  <Button size="small" type="primary" icon={<PlusOutlined />}
                    onClick={() => { itemForm.resetFields(); setShowAddItem(true); }}>
                    新建明细项
                  </Button>
                )}
              </div>
              <Table
                rowKey="id"
                size="small"
                pagination={false}
                dataSource={selectedCat.items}
                columns={[
                  { title: 'Code', dataIndex: 'code', width: 160, render: (v) => <code>{v}</code> },
                  { title: '名称', dataIndex: 'name', width: 220 },
                  { title: '默认值', dataIndex: 'defaultValue', width: 200, render: (v) => v || <span style={{ color: '#bbb' }}>无</span> },
                  { title: '排序', dataIndex: 'sortOrder', width: 80 },
                  { title: '状态', dataIndex: 'status', width: 90, render: (s) => s === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag> },
                  ...(readOnly ? [] : [{
                    title: '操作', key: 'a', width: 130,
                    render: (_: any, i: ConfigItem) => (
                      <Space size={2}>
                        <Button size="small" type="link" icon={<EditOutlined />}
                          onClick={() => { setEditingItem(i); itemForm.setFieldsValue(i); }}>编辑</Button>
                        <Popconfirm title={`删除 ${i.name}?`} onConfirm={() => deleteItem(i)}>
                          <Button size="small" type="link" danger icon={<DeleteOutlined />} />
                        </Popconfirm>
                      </Space>
                    )
                  }] as any),
                ]}
                style={{ flex: 1 }}
                scroll={{ y: 'calc(100vh - 280px)' }}
              />
            </>
          )}
        </div>
      </div>

      {/* 新建大类 Modal */}
      <Modal title="新建大类" open={showAddCat}
        onCancel={() => setShowAddCat(false)} onOk={addCategory}>
        <Form form={catForm} layout="vertical">
          <Form.Item label="Code" name="code" rules={[{ required: true, max: 50 }]}>
            <Input placeholder="如 PROCESS / MATERIAL" />
          </Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true, max: 200 }]}>
            <Input />
          </Form.Item>
          <Form.Item label="排序" name="sortOrder" initialValue={0}>
            <Input type="number" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 改名大类 Modal */}
      <Modal title={`修改大类: ${editingCat?.code}`} open={!!editingCat}
        onCancel={() => setEditingCat(null)} onOk={renameCategory}>
        <Form form={catForm} layout="vertical">
          <Form.Item label="Code" name="code" rules={[{ required: true, max: 50 }]}><Input /></Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true, max: 200 }]}><Input /></Form.Item>
          <Form.Item label="排序" name="sortOrder"><Input type="number" /></Form.Item>
          <Form.Item label="状态" name="status">
            <Input placeholder="ACTIVE / INACTIVE" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 明细项 Modal */}
      <Modal title="新建明细项" open={showAddItem}
        onCancel={() => setShowAddItem(false)} onOk={addItem}>
        <ItemFormFields form={itemForm} />
      </Modal>
      <Modal title={`修改明细项: ${editingItem?.code}`} open={!!editingItem}
        onCancel={() => setEditingItem(null)} onOk={updateItem}>
        <ItemFormFields form={itemForm} />
      </Modal>
    </Drawer>
  );
};

const ItemFormFields: React.FC<{ form: any }> = ({ form }) => (
  <Form form={form} layout="vertical">
    <Form.Item label="Code" name="code" rules={[{ required: true, max: 50 }]}>
      <Input placeholder="如 RIVET" />
    </Form.Item>
    <Form.Item label="名称" name="name" rules={[{ required: true, max: 200 }]}>
      <Input />
    </Form.Item>
    <Form.Item label="默认值" name="defaultValue" extra="LIST_FORMULA 渲染时分支全不命中的兜底">
      <Input placeholder="可填字面值或公式, 例 100 / [基础工时]*1.2" />
    </Form.Item>
    <Form.Item label="排序" name="sortOrder" initialValue={0}><Input type="number" /></Form.Item>
    <Form.Item label="状态" name="status" initialValue="ACTIVE">
      <Input placeholder="ACTIVE / INACTIVE" />
    </Form.Item>
  </Form>
);

export default ConfigTemplateManagement;
