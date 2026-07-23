import React, { useEffect, useState } from 'react';
import { Drawer, DatePicker, Button, Table, Space, Alert, message } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { elementPriceStrategyService } from '../../../services/elementPriceStrategyService';
import { formatMethod } from './strategyFormat';
import type { SimulateDraft, SimulateRowDTO } from '../../../types/element-price-strategy';

/**
 * 策略试算抽屉（720） —— task-0722 · F7
 * 按当前页面配置试算（含未保存改动）；只读不落库；无价元素整行标黄（§11.5、§11.14C）。
 */
interface Props {
  open: boolean;
  onClose: () => void;
  customerNo: string;
  customerLabel: string;
  /** 调用方在点击「试算」的瞬间提供当前页面（含未保存改动）的策略草稿 */
  getDraft: () => SimulateDraft;
}

const StrategySimulateDrawer: React.FC<Props> = ({ open, onClose, customerNo, customerLabel, getDraft }) => {
  const [baseDate, setBaseDate] = useState<Dayjs>(dayjs());
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<SimulateRowDTO[]>([]);
  const [hasRun, setHasRun] = useState(false);

  useEffect(() => {
    if (!open) return;
    setBaseDate(dayjs());
    setRows([]);
    setHasRun(false);
  }, [open]);

  const runSimulate = async () => {
    setLoading(true);
    try {
      const res = await elementPriceStrategyService.simulate({
        customerNo,
        baseDate: baseDate.format('YYYY-MM-DD'),
        draft: getDraft(),
      });
      setRows(res);
      setHasRun(true);
    } catch (e: any) {
      message.error(e?.message ?? '试算失败');
    } finally {
      setLoading(false);
    }
  };

  const num = (v: number | null) => (v === null || v === undefined ? '—' : v.toFixed(4));

  return (
    <Drawer
      title={<div><div>策略试算 · {customerLabel}</div><div style={{ fontSize: 12, fontWeight: 400, color: 'rgba(0,0,0,.45)', marginTop: 2 }}>按当前策略配置，模拟指定基准日的取价结果</div></div>}
      open={open}
      onClose={onClose}
      width={720}
      placement="right"
      destroyOnClose
      footer={<div style={{ textAlign: 'right' }}><Button onClick={onClose}>关闭</Button></div>}
    >
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, marginBottom: 16 }}>
        <div style={{ width: 200 }}>
          <div style={{ marginBottom: 6 }}>基准日</div>
          <DatePicker style={{ width: '100%' }} value={baseDate} onChange={(d) => d && setBaseDate(d)} allowClear={false} />
          <div style={{ marginTop: 5, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>实际报价时 = 报价单创建日期</div>
        </div>
        <Space>
          <Button type="primary" loading={loading} onClick={runSimulate}>🧮 试算</Button>
        </Space>
      </div>

      <Table<SimulateRowDTO>
        size="small"
        rowKey="elementCode"
        loading={loading}
        dataSource={rows}
        pagination={false}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: hasRun ? '当前客户未配到策略的元素不参与试算' : '点击「试算」查看结果' }}
        columns={[
          { title: '元素', key: 'elementCode', render: (_: unknown, r) => <span>{r.elementCode} {r.elementName}</span> },
          {
            title: '命中规则',
            dataIndex: 'hitRule',
            render: (v: SimulateRowDTO['hitRule']) => (
              <span style={{
                display: 'inline-block', padding: '0 7px', borderRadius: 4, fontSize: 12,
                color: v === 'EXCEPTION' ? '#722ed1' : '#1677ff',
                background: v === 'EXCEPTION' ? '#f9f0ff' : '#e6f4ff',
                border: `1px solid ${v === 'EXCEPTION' ? '#d3adf7' : '#91caff'}`,
              }}>{v === 'EXCEPTION' ? '元素例外' : '客户默认'}</span>
            ),
          },
          { title: '源', dataIndex: 'sourceName' },
          { title: '取值方式', dataIndex: 'method', render: (v: SimulateRowDTO['method']) => formatMethod(v) },
          {
            title: '取值结果', dataIndex: 'rawValue', align: 'right' as const,
            render: (v: number | null, r) => r.hasPrice ? num(v) : <span style={{ color: 'rgba(0,0,0,.45)' }}>—</span>,
          },
          {
            title: '× 系数', dataIndex: 'factor', align: 'right' as const,
            render: (v: number, r) => r.hasPrice ? v.toFixed(2) : <span style={{ color: 'rgba(0,0,0,.45)' }}>—</span>,
          },
          {
            title: '+ 加价', dataIndex: 'premium', align: 'right' as const,
            render: (v: number, r) => r.hasPrice ? v.toFixed(2) : <span style={{ color: 'rgba(0,0,0,.45)' }}>—</span>,
          },
          {
            title: '最终单价', dataIndex: 'finalPrice', align: 'right' as const,
            render: (v: number | null, r) => r.hasPrice
              ? <b>{num(v)}</b>
              : <b style={{ color: '#d46b08' }}>无价</b>,
          },
          {
            title: '参与天数', dataIndex: 'sampleDays',
            render: (v: number, r) => (
              <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
                {r.hasPrice ? `${v} 天有价` : '窗口内 0 天有价'}
              </span>
            ),
          },
        ]}
        onRow={(r) => (!r.hasPrice ? { style: { background: '#fffbe6' } } : {})}
      />

      <Alert
        style={{ marginTop: 14 }}
        type="warning"
        showIcon
        message="标黄行 = 策略窗口内一条价格都没有 → 报价单上该元素单价留空，销售手填。不会往窗口外找旧价顶上。"
      />
    </Drawer>
  );
};

export default StrategySimulateDrawer;
