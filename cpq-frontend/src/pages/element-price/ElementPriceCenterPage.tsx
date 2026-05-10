import React, { useEffect, useState, useCallback } from 'react';
import {
  Table,
  Button,
  Space,
  Select,
  DatePicker,
  Typography,
  Tag,
  message,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { elementPriceService } from '../../services/elementPriceService';
import { useAuthStore } from '../../stores/authStore';
import ManualPriceEntryDrawer from './ManualPriceEntryDrawer';
import type { ElementPriceHistoryItem, AvailableElementDTO } from '../../types/element-price';

const { Title } = Typography;
const { RangePicker } = DatePicker;

const CURRENCY_COLOR: Record<string, string> = {
  RMB: 'blue',
  USD: 'green',
  EUR: 'purple',
};

const ElementPriceCenterPage: React.FC = () => {
  const { user } = useAuthStore();
  const isAdmin = user?.role === 'SYSTEM_ADMIN';

  // 筛选状态
  const [filterElement, setFilterElement] = useState<string | undefined>(undefined);
  const [filterFrom, setFilterFrom] = useState<string | undefined>(undefined);
  const [filterTo, setFilterTo] = useState<string | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // 数据状态
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<ElementPriceHistoryItem[]>([]);
  const [total, setTotal] = useState(0);
  const [availableElements, setAvailableElements] = useState<AvailableElementDTO[]>([]);

  // 抽屉状态
  const [drawerOpen, setDrawerOpen] = useState(false);

  // 加载可用元素列表（用于筛选器）
  useEffect(() => {
    elementPriceService.listAvailableElements().then(setAvailableElements).catch(() => {});
  }, []);

  // 加载历史列表
  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const result = await elementPriceService.listHistory({
        elementName: filterElement,
        from: filterFrom,
        to: filterTo,
        page,
        size: pageSize,
      });
      setItems(result.data ?? []);
      setTotal(result.total ?? 0);
    } catch (e: any) {
      message.error(e?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [filterElement, filterFrom, filterTo, page, pageSize]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleRangeChange = (dates: any) => {
    if (!dates) {
      setFilterFrom(undefined);
      setFilterTo(undefined);
    } else {
      setFilterFrom(dates[0]?.format('YYYY-MM-DD'));
      setFilterTo(dates[1]?.format('YYYY-MM-DD'));
    }
    setPage(0);
  };

  const handleElementChange = (val: string | undefined) => {
    setFilterElement(val);
    setPage(0);
  };

  const handleDrawerSuccess = () => {
    setPage(0);
    loadData();
  };

  const columns: ColumnsType<ElementPriceHistoryItem> = [
    {
      title: '元素',
      dataIndex: 'elementName',
      key: 'elementName',
      width: 100,
      render: (v: string) => <Tag color="gold">{v}</Tag>,
    },
    {
      title: '参考价格',
      dataIndex: 'price',
      key: 'price',
      width: 140,
      align: 'right',
      render: (v: number) => (
        <strong>{v?.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}</strong>
      ),
    },
    {
      title: '货币',
      dataIndex: 'currency',
      key: 'currency',
      width: 90,
      render: (v: string) => <Tag color={CURRENCY_COLOR[v] ?? 'default'}>{v}</Tag>,
    },
    {
      title: '单位',
      dataIndex: 'unit',
      key: 'unit',
      width: 80,
    },
    {
      title: '价格日期',
      dataIndex: 'priceDate',
      key: 'priceDate',
      width: 120,
      render: (v: string) => v ?? '—',
    },
    {
      title: '录入时间',
      dataIndex: 'enteredAt',
      key: 'enteredAt',
      width: 170,
      render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—'),
    },
    {
      title: '录入人',
      dataIndex: 'enteredByName',
      key: 'enteredByName',
      width: 100,
    },
    {
      title: '备注',
      dataIndex: 'note',
      key: 'note',
      ellipsis: true,
      render: (v: string) => v || '—',
    },
  ];

  return (
    <div>
      {/* 页头 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>元素价格中心</Title>
        {isAdmin && (
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setDrawerOpen(true)}
          >
            录入新参考价
          </Button>
        )}
      </div>

      {/* 筛选栏 */}
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="选择元素"
          style={{ width: 180 }}
          value={filterElement}
          onChange={handleElementChange}
          options={availableElements.map(e => ({
            value: e.elementName,
            label: e.elementName,
          }))}
          showSearch
          optionFilterProp="label"
        />
        <RangePicker
          onChange={handleRangeChange}
          placeholder={['开始日期', '结束日期']}
          format="YYYY-MM-DD"
        />
        <Button icon={<ReloadOutlined />} onClick={() => { setPage(0); loadData(); }}>
          刷新
        </Button>
      </Space>

      {/* 历史价格列表 */}
      <Table<ElementPriceHistoryItem>
        rowKey="id"
        columns={columns}
        dataSource={items}
        loading={loading}
        scroll={{ x: 900 }}
        pagination={{
          current: page + 1,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, ps) => {
            setPage(p - 1);
            setPageSize(ps);
          },
        }}
        locale={{ emptyText: '暂无参考价记录' }}
      />

      {/* 录入抽屉 */}
      <ManualPriceEntryDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        onSuccess={handleDrawerSuccess}
        availableElements={availableElements}
      />
    </div>
  );
};

export default ElementPriceCenterPage;
