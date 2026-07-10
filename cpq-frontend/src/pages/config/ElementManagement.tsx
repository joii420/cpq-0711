import React, { useEffect, useRef, useState } from 'react';
import { Card, Tag, Button, Space, Input, Tooltip, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, LockOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import type { ToolbarAction } from '../../components/SelectableTable';
import { elementService, type ElementItem } from '../../services/elementService';
import ElementEditDrawer from './ElementEditDrawer';

/** 时间格式化 YYYY-MM-DD HH:mm；空值回退 '—' */
const fmtTime = (v?: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—');

const ElementManagement: React.FC = () => {
  const [list, setList] = useState<ElementItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<ElementItem | null>(null);
  const [keyword, setKeyword] = useState('');
  const debounceRef = useRef<number | undefined>(undefined);

  // 排序由后端定(启用优先→改时倒序→建时倒序)，前端不本地 sort。
  const refresh = async (kw?: string) => {
    setLoading(true);
    try {
      setList(await elementService.list(kw));
    } catch (e: any) {
      message.error(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  // 搜索防抖 ~300ms；清空拉全量
  const onKeywordChange = (v: string) => {
    setKeyword(v);
    window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => refresh(v.trim() || undefined), 300);
  };
  useEffect(() => () => window.clearTimeout(debounceRef.current), []);

  const openCreate = () => { setEditing(null); setDrawerOpen(true); };
  const openEdit = (row: ElementItem) => { setEditing(row); setDrawerOpen(true); };

  const columns = [
    {
      title: '元素编号',
      dataIndex: 'elementNo',
      key: 'elementNo',
      width: 120,
      render: (v: string, r: ElementItem) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a>
      ),
    },
    {
      title: '符号',
      dataIndex: 'elementCode',
      key: 'elementCode',
      width: 120,
      render: (v: string, r: ElementItem) => (
        <Space size={4}>
          <span>{v}</span>
          {r.codeLocked && (
            <Tooltip title={`已被 ${r.referencedCount} 个材质引用，符号不可修改`}>
              <LockOutlined style={{ color: '#8c8c8c' }} />
            </Tooltip>
          )}
        </Space>
      ),
    },
    { title: '中文名', dataIndex: 'elementName', key: 'elementName', width: 140 },
    {
      title: '被引用材质数',
      dataIndex: 'referencedCount',
      key: 'referencedCount',
      width: 130,
      render: (n: number) => <Tag color={n > 0 ? 'blue' : 'default'}>{n ?? 0}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (s: string) => (
        <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 150,
      render: (v?: string) => fmtTime(v),
    },
    {
      title: '修改时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 150,
      render: (v?: string) => fmtTime(v),
    },
  ];

  const actions: ToolbarAction<ElementItem>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openEdit(rows[0]),
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
      confirmTitle: '确认停用选中的 {N} 个元素?',
      confirmDescription: '停用后不再可被新材质/新导入选用；历史材质靠元素编号照常显示。可在编辑抽屉重新启用。',
      onClick: async (rows) => {
        await runBatch(
          rows,
          (r) => elementService.deleteSoft(r.elementNo).then(() => undefined),
          { rowLabel: (r) => `${r.elementNo} ${r.elementCode}`, successMsg: `已停用 ${rows.length} 个元素` },
        );
        refresh();
      },
    },
  ];

  return (
    <Card
      title="元素管理"
      extra={
        <Space>
          <Input.Search
            placeholder="搜索 元素编号 / 符号 / 中文名"
            allowClear
            style={{ width: 260 }}
            value={keyword}
            onChange={(e) => onKeywordChange(e.target.value)}
            onSearch={(v) => refresh(v.trim() || undefined)}
          />
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建元素
          </Button>
        </Space>
      }
    >
      <SelectableTable<ElementItem>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        pagination={false}
        actions={actions}
        rowLabel={(r) => `${r.elementNo} ${r.elementCode}`}
      />
      <ElementEditDrawer
        open={drawerOpen}
        editing={editing}
        onClose={() => setDrawerOpen(false)}
        onSaved={() => { setDrawerOpen(false); refresh(); }}
      />
    </Card>
  );
};

export default ElementManagement;
