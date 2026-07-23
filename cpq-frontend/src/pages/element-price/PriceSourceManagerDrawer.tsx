import React, { useEffect, useState } from 'react';
import { Drawer, Button, Space, Tag, message } from 'antd';
import { PlusOutlined, EditOutlined, StopOutlined } from '@ant-design/icons';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import type { ToolbarAction } from '../../components/SelectableTable';
import { elementPriceStrategyService } from '../../services/elementPriceStrategyService';
import type { PriceSourceDTO } from '../../types/element-price-strategy';
import PriceSourceEditDrawer from './PriceSourceEditDrawer';

/**
 * 价格源管理抽屉（720） —— task-0722 · F3
 * 本期不做自动抓价（§11.13）：网址只作人工参考记录；不提供物理删除（§11.13.1）。
 */
interface Props {
  open: boolean;
  onClose: () => void;
}

const PriceSourceManagerDrawer: React.FC<Props> = ({ open, onClose }) => {
  const [list, setList] = useState<PriceSourceDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [editing, setEditing] = useState<PriceSourceDTO | null>(null);

  const refresh = async () => {
    setLoading(true);
    try {
      setList(await elementPriceStrategyService.listSources());
    } catch (e: any) {
      message.error(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { if (open) refresh(); }, [open]);

  const openCreate = () => { setEditing(null); setEditOpen(true); };
  const openEdit = (row: PriceSourceDTO) => { setEditing(row); setEditOpen(true); };

  const columns = [
    {
      title: '源名称',
      dataIndex: 'sourceName',
      key: 'sourceName',
      render: (v: string, r: PriceSourceDTO) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a>
      ),
    },
    {
      title: '网址',
      dataIndex: 'sourceUrl',
      key: 'sourceUrl',
      render: (v?: string) => <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{v || '—'}</span>,
    },
    {
      title: '类型',
      dataIndex: 'sourceType',
      key: 'sourceType',
      render: () => <Tag>手工录入</Tag>,
    },
    {
      title: '备注',
      dataIndex: 'description',
      key: 'description',
      render: (v?: string) => <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>{v || '—'}</span>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (s: string) => (
        <Space size={4}>
          <span
            style={{
              display: 'inline-block', width: 6, height: 6, borderRadius: 3,
              background: s === 'ACTIVE' ? '#52c41a' : '#d9d9d9',
            }}
          />
          {s === 'ACTIVE' ? '启用' : '停用'}
        </Space>
      ),
    },
  ];

  const actions: ToolbarAction<PriceSourceDTO>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openEdit(rows[0]),
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
      confirmTitle: '确认停用选中的 {N} 个价格源？',
      confirmDescription: '停用后不可再被新策略/新导入选用；历史价格与存量策略照常保留、继续取价，不影响历史报价单。',
      onClick: async (rows) => {
        await runBatch(
          rows,
          (r) => elementPriceStrategyService.setSourceStatus(r.id, 'DISABLED').then(() => undefined),
          { rowLabel: (r) => r.sourceName, successMsg: `已停用 ${rows.length} 个价格源` },
        );
        refresh();
      },
    },
  ];

  return (
    <Drawer
      title="价格源管理"
      open={open}
      onClose={onClose}
      width={720}
      placement="right"
      destroyOnClose
      footer={<div style={{ textAlign: 'right' }}><Button onClick={onClose}>关闭</Button></div>}
    >
      <div style={{ marginBottom: 8, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>维护元素行情的来源渠道</div>
      <SelectableTable<PriceSourceDTO>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        pagination={false}
        actions={actions}
        rowLabel={(r) => r.sourceName}
        toolbar={
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建源
          </Button>
        }
      />
      <PriceSourceEditDrawer
        open={editOpen}
        editing={editing}
        onClose={() => setEditOpen(false)}
        onSaved={() => { setEditOpen(false); refresh(); }}
      />
    </Drawer>
  );
};

export default PriceSourceManagerDrawer;
