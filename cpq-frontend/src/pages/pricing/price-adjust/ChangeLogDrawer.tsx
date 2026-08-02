import React, { useEffect, useState } from 'react';
import { Drawer, Table, Tag, Button, message } from 'antd';
import dayjs from 'dayjs';
import { priceAdjustService } from '../../../services/priceAdjustService';
import type { StrategyLogDTO, ChangeType } from '../../../types/price-adjust';

const PAGE_SIZE = 20;

const changeTypeTag: Record<ChangeType, { color: string; label: string }> = {
  STRATEGY: { color: 'blue', label: '策略字段' },
  MATERIAL_SCOPE: { color: 'purple', label: '料号范围' },
  ELEMENT_LIST: { color: 'gold', label: '调价元素' },
  COMPARISON_COLUMN: { color: 'green', label: '比对列' },
};

export interface ChangeLogDrawerProps {
  open: boolean;
  onClose: () => void;
  customerNo: string;
  customerLabel: string;
}

/** 屏 1 ·「🕘 变更历史」抽屉（fronttask §1.1 / api.md §1.7）。只读，无编辑/回滚入口。 */
const ChangeLogDrawer: React.FC<ChangeLogDrawerProps> = ({ open, onClose, customerNo, customerLabel }) => {
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<StrategyLogDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);

  const load = async (p = 1) => {
    setLoading(true);
    try {
      const res = await priceAdjustService.getLogs(customerNo, { page: p, size: PAGE_SIZE });
      setRows(res.content || []);
      setTotal(res.totalElements || 0);
      setPage(p);
    } catch (e: any) {
      message.error(e?.message || '加载变更历史失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) load(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, customerNo]);

  return (
    <Drawer
      title={`调价策略变更历史 · ${customerLabel}`}
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={<div style={{ textAlign: 'right' }}><Button onClick={onClose}>关闭</Button></div>}
    >
      <Table<StrategyLogDTO>
        size="small"
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={{
          current: page, pageSize: PAGE_SIZE, total, size: 'small',
          onChange: (p) => load(p), showTotal: (t) => `共 ${t} 条`,
        }}
        columns={[
          { title: '变更时间', dataIndex: 'changedAt', width: 150, render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—' },
          { title: '变更人', dataIndex: 'changedBy', width: 90 },
          {
            title: '类型', dataIndex: 'changeType', width: 96,
            render: (v: ChangeType) => <Tag color={changeTypeTag[v]?.color}>{changeTypeTag[v]?.label || v}</Tag>,
          },
          { title: '摘要', dataIndex: 'summary' },
        ]}
      />
    </Drawer>
  );
};

export default ChangeLogDrawer;
