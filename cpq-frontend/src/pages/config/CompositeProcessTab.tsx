import React, { useEffect, useState } from 'react';
import { Button, Tag, message } from 'antd';
import { PlusOutlined, EditOutlined, StopOutlined } from '@ant-design/icons';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import type { ToolbarAction } from '../../components/SelectableTable';
import {
  compositeProcessService,
  type CompositeProcessDef,
} from '../../services/compositeProcessService';
import CompositeProcessEditDrawer from './CompositeProcessEditDrawer';

const CompositeProcessTab: React.FC = () => {
  const [list, setList] = useState<CompositeProcessDef[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingDetail, setEditingDetail] = useState<CompositeProcessDef | null>(null);

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await compositeProcessService.list();
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
      const detail = await compositeProcessService.detail(id);
      setEditingDetail(detail);
      setDrawerOpen(true);
    } catch (e: any) {
      message.error(e?.message ?? '加载详情失败');
    }
  };

  const columns = [
    {
      title: '代码',
      dataIndex: 'code',
      key: 'code',
      width: 120,
      render: (v: string, r: CompositeProcessDef) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r.id); }}>{v}</a>
      ),
    },
    {
      title: '图标',
      dataIndex: 'icon',
      key: 'icon',
      width: 60,
      render: (v: string) => <span style={{ fontSize: 20 }}>{v}</span>,
    },
    { title: '名称', dataIndex: 'name', key: 'name', width: 160 },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
    },
    {
      title: '参数数量',
      key: 'paramCount',
      width: 90,
      render: (_: unknown, r: CompositeProcessDef) =>
        compositeProcessService.parseParamSchema(r.paramSchema).length,
    },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 70 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (s: string) => (
        <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag>
      ),
    },
  ];

  const actions: ToolbarAction<CompositeProcessDef>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openEdit(rows[0].id),
    },
    {
      key: 'disable',
      label: '停用',
      icon: <StopOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r) => r.status !== 'ACTIVE')) return '仅启用状态可停用';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认停用选中的 {N} 项组合工序?',
      confirmDescription: '停用后选配抽屉将不再显示该组合工序。可手动恢复 status=ACTIVE。',
      onClick: async (rows) => {
        await runBatch(
          rows,
          (r) => compositeProcessService.deleteSoft(r.id).then(() => undefined),
          { rowLabel: (r) => `${r.code} ${r.name}`, successMsg: `已停用 ${rows.length} 项` },
        );
        refresh();
      },
    },
  ];

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'flex-end', padding: '12px 0 8px' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建组合工序
        </Button>
      </div>
      <SelectableTable<CompositeProcessDef>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        pagination={false}
        actions={actions}
        rowLabel={(r) => `${r.code} ${r.name}`}
      />
      <CompositeProcessEditDrawer
        open={drawerOpen}
        editingDetail={editingDetail}
        onClose={() => setDrawerOpen(false)}
        onSaved={() => { setDrawerOpen(false); refresh(); }}
      />
    </>
  );
};

export default CompositeProcessTab;
