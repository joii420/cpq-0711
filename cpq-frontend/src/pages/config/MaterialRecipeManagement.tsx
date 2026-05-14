import React, { useEffect, useState } from 'react';
import { Card, Tag, Button, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import type { ToolbarAction } from '../../components/SelectableTable';
import {
  materialRecipeService,
  type MaterialRecipeLite,
  type MaterialRecipeDetail,
} from '../../services/materialRecipeService';
import MaterialRecipeEditDrawer from './MaterialRecipeEditDrawer';

const recipeTypeTag: Record<string, { label: string; color: string }> = {
  locked:   { label: '标准锁定', color: 'red' },
  editable: { label: '含量可调', color: 'green' },
  partial:  { label: '部分可调', color: 'orange' },
};

const MaterialRecipeManagement: React.FC = () => {
  const [list, setList] = useState<MaterialRecipeLite[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingDetail, setEditingDetail] = useState<MaterialRecipeDetail | null>(null);

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await materialRecipeService.list();
      setList(data);
    } catch (e: any) {
      message.error(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  const openCreate = () => {
    setEditingDetail(null);
    setDrawerOpen(true);
  };

  const openEdit = async (id: string) => {
    try {
      const detail = await materialRecipeService.detail(id);
      setEditingDetail(detail);
      setDrawerOpen(true);
    } catch (e: any) {
      message.error(e?.message ?? '加载详情失败');
    }
  };

  const columns = [
    {
      title: '代号',
      dataIndex: 'code',
      key: 'code',
      width: 120,
      render: (v: string, r: MaterialRecipeLite) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r.id); }}>{v}</a>
      ),
    },
    { title: '化学式', dataIndex: 'symbol', key: 'symbol', width: 100 },
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '配比', dataIndex: 'specLabel', key: 'specLabel', width: 100 },
    {
      title: '类型',
      dataIndex: 'recipeType',
      key: 'recipeType',
      width: 100,
      render: (t: string) => (
        <Tag color={recipeTypeTag[t]?.color}>{recipeTypeTag[t]?.label ?? t}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (s: string) => (
        <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag>
      ),
    },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 80 },
  ];

  const actions: ToolbarAction<MaterialRecipeLite>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openEdit(rows[0].id),
    },
    {
      key: 'delete',
      label: '停用',
      icon: <DeleteOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some(r => r.status !== 'ACTIVE')) return '仅启用状态可停用';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认停用选中的 {N} 项材质?',
      confirmDescription: '停用后选配抽屉将不再显示。可在后台手动恢复 status=ACTIVE。',
      onClick: async (rows) => {
        await runBatch(
          rows,
          (r) => materialRecipeService.deleteSoft(r.id).then(() => undefined),
          { rowLabel: (r) => `${r.code} ${r.name}`, successMsg: `已停用 ${rows.length} 项` },
        );
        refresh();
      },
    },
  ];

  return (
    <Card
      title="材质管理"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建材质
        </Button>
      }
    >
      <SelectableTable<MaterialRecipeLite>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        pagination={false}
        actions={actions}
        rowLabel={(r) => `${r.code} ${r.name}`}
      />
      <MaterialRecipeEditDrawer
        open={drawerOpen}
        editingDetail={editingDetail}
        onClose={() => setDrawerOpen(false)}
        onSaved={() => { setDrawerOpen(false); refresh(); }}
      />
    </Card>
  );
};

export default MaterialRecipeManagement;
