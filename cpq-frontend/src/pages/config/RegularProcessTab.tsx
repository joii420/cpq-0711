import React, { useEffect, useState } from 'react';
import { Button, Tag, message } from 'antd';
import { PlusOutlined, EditOutlined, StopOutlined } from '@ant-design/icons';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import type { ToolbarAction } from '../../components/SelectableTable';
import {
  processService,
  PROCESS_CATEGORIES,
  type Process,
} from '../../services/processService';
import RegularProcessEditDrawer from './RegularProcessEditDrawer';

const categoryLabel = (value: string) =>
  PROCESS_CATEGORIES.find((c) => c.value === value)?.label ?? value;

const categoryColor: Record<string, string> = {
  SURFACE_TREATMENT: 'blue',
  MACHINING:         'purple',
  HEAT_TREATMENT:    'orange',
  ASSEMBLY:          'cyan',
  INSPECTION:        'green',
  PACKAGING:         'geekblue',
};

const RegularProcessTab: React.FC = () => {
  const [list, setList] = useState<Process[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingDetail, setEditingDetail] = useState<Process | null>(null);

  const refresh = async () => {
    setLoading(true);
    try {
      const data = await processService.list();
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
      const detail = await processService.detail(id);
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
      render: (v: string, r: Process) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r.id); }}>{v}</a>
      ),
    },
    { title: '名称', dataIndex: 'name', key: 'name', width: 160 },
    {
      title: '分类',
      dataIndex: 'category',
      key: 'category',
      width: 120,
      render: (v: string) => (
        <Tag color={categoryColor[v] ?? 'default'}>{categoryLabel(v)}</Tag>
      ),
    },
    {
      title: '是否必选',
      dataIndex: 'isRequired',
      key: 'isRequired',
      width: 90,
      render: (v: boolean) => (
        <Tag color={v ? 'blue' : 'default'}>{v ? '必选' : '可选'}</Tag>
      ),
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
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  ];

  const actions: ToolbarAction<Process>[] = [
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
      confirmTitle: '确认停用选中的 {N} 项工序?',
      confirmDescription: '停用后产品绑定界面将不再显示该工序。可手动恢复 status=ACTIVE。',
      onClick: async (rows) => {
        await runBatch(
          rows,
          (r) => processService.deleteSoft(r.id).then(() => undefined),
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
          新建工序
        </Button>
      </div>
      <SelectableTable<Process>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        pagination={false}
        actions={actions}
        rowLabel={(r) => `${r.code} ${r.name}`}
      />
      <RegularProcessEditDrawer
        open={drawerOpen}
        editingDetail={editingDetail}
        onClose={() => setDrawerOpen(false)}
        onSaved={() => { setDrawerOpen(false); refresh(); }}
      />
    </>
  );
};

export default RegularProcessTab;
