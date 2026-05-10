import React, { useCallback, useEffect, useState } from 'react';
import {
  Card,
  Col,
  Row,
  Select,
  Space,
  Typography,
  Spin,
  Empty,
  message,
  Alert,
} from 'antd';
import { DatabaseOutlined } from '@ant-design/icons';
import type { MasterDataOverviewDTO, TableSummaryDTO } from '../../services/masterDataService';
import { masterDataService } from '../../services/masterDataService';
import TableOverviewCard from './TableOverviewCard';
import TableDataDrawer from './TableDataDrawer';

const { Title, Text } = Typography;
const { Option } = Select;

interface CustomerOption {
  id: string;
  name: string;
}

const GROUP_CONFIG: {
  key: TableSummaryDTO['group'];
  label: string;
  description: string;
}[] = [
  { key: 'GLOBAL', label: '全局数据', description: '不关联特定客户的基础数据表' },
  { key: 'CUSTOMER', label: '客户数据', description: '与特定客户关联的数据表' },
  { key: 'ELEMENT', label: '元素数据', description: '配置元素相关数据表（v2 启用）' },
];

const MasterDataPage: React.FC = () => {
  const [customerId, setCustomerId] = useState<string | null>(null);
  const [customers, setCustomers] = useState<CustomerOption[]>([]);
  const [overview, setOverview] = useState<MasterDataOverviewDTO | null>(null);
  const [loading, setLoading] = useState(false);
  const [backendError, setBackendError] = useState<string | null>(null);

  // 一级抽屉
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [selectedSummary, setSelectedSummary] = useState<TableSummaryDTO | null>(null);

  // 加载客户列表（用于选择器）
  useEffect(() => {
    masterDataService.listCustomers().then((res) => {
      const data = res.data;
      // 兼容后端分页格式 { content: [...] } 或直接 [...]
      const list: CustomerOption[] = Array.isArray(data)
        ? data
        : Array.isArray((data as any)?.content)
          ? (data as any).content
          : [];
      setCustomers(list.map((c: any) => ({ id: c.id, name: c.name || c.fullName || c.customerName })));
    }).catch(() => {
      // 客户列表加载失败不影响主功能，静默处理
    });
  }, []);

  const loadOverview = useCallback(async (cid: string | null) => {
    setLoading(true);
    setBackendError(null);
    try {
      const res = await masterDataService.getOverview(cid);
      setOverview(res.data || null);
    } catch (err: any) {
      const errMsg = err?.message || '未知错误';
      // 后端未就绪时友好降级
      if (
        errMsg.includes('404') ||
        errMsg.includes('Network Error') ||
        errMsg.includes('ECONNREFUSED')
      ) {
        setBackendError('后端接口尚未就绪，当前显示模拟数据。联调时切换 VITE_USE_MOCK_MASTER_DATA=false。');
        // 仍然尝试加载 mock 数据
        try {
          const mockRes = await masterDataService.getOverview(cid);
          setOverview(mockRes.data || null);
        } catch {
          // mock 也失败就显示空
        }
      } else {
        message.error(`加载主数据概览失败：${errMsg}`);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadOverview(customerId);
  }, [customerId, loadOverview]);

  const handleCardClick = (table: TableSummaryDTO) => {
    setSelectedSummary(table);
    setDrawerOpen(true);
  };

  const renderGroupSection = (group: TableSummaryDTO['group'], label: string, description: string) => {
    const tables = overview?.tables.filter((t) => t.group === group) || [];
    if (tables.length === 0) return null;

    return (
      <Card
        key={group}
        title={
          <Space>
            <Title level={5} style={{ margin: 0 }}>{label}</Title>
            <Text type="secondary" style={{ fontSize: 13, fontWeight: 'normal' }}>{description}</Text>
          </Space>
        }
        style={{ marginBottom: 24 }}
        styles={{ body: { padding: '16px 24px' } }}
      >
        <Row gutter={[16, 16]}>
          {tables.map((table) => (
            <Col key={table.tableName} span={8} xs={24} sm={12} md={8}>
              <TableOverviewCard
                summary={table}
                onClick={() => handleCardClick(table)}
              />
            </Col>
          ))}
        </Row>
      </Card>
    );
  };

  return (
    <div>
      {/* 页面标题 + 客户选择器 */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 24,
          flexWrap: 'wrap',
          gap: 12,
        }}
      >
        <Space align="center">
          <DatabaseOutlined style={{ fontSize: 22, color: '#1677ff' }} />
          <Title level={4} style={{ margin: 0 }}>
            主数据维护
          </Title>
          {overview && (
            <Text type="secondary" style={{ fontSize: 13 }}>
              共 {overview.tables.length} 张表
            </Text>
          )}
        </Space>
        <Space>
          <Text>客户范围：</Text>
          <Select
            value={customerId}
            onChange={(val) => setCustomerId(val)}
            style={{ width: 220 }}
            showSearch
            allowClear
            placeholder="全部客户"
            filterOption={(input, option) =>
              String(option?.children ?? '').toLowerCase().includes(input.toLowerCase())
            }
          >
            <Option value={null}>全部客户</Option>
            {customers.map((c) => (
              <Option key={c.id} value={c.id}>
                {c.name}
              </Option>
            ))}
          </Select>
        </Space>
      </div>

      {/* 后端未就绪提示 */}
      {backendError && (
        <Alert
          type="warning"
          message={backendError}
          showIcon
          closable
          style={{ marginBottom: 16 }}
        />
      )}

      {/* 主内容 */}
      {loading ? (
        <div style={{ textAlign: 'center', padding: '80px 0' }}>
          <Spin size="large" tip="加载中..." />
        </div>
      ) : !overview || overview.tables.length === 0 ? (
        <Empty description="暂无主数据表" />
      ) : (
        GROUP_CONFIG.map(({ key, label, description }) =>
          renderGroupSection(key, label, description),
        )
      )}

      {/* 一级抽屉：表数据 */}
      <TableDataDrawer
        open={drawerOpen}
        onClose={() => {
          setDrawerOpen(false);
          setSelectedSummary(null);
        }}
        summary={selectedSummary}
        customerId={customerId}
      />
    </div>
  );
};

export default MasterDataPage;
