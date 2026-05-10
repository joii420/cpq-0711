import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Descriptions,
  message,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import {
  LockOutlined,
  ReloadOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { ProductImportLockDTO, DdlLockStatusDTO } from '../../types/lock-monitor';
import { lockMonitorService } from '../../services/lockMonitorService';
import ForceReleaseConfirm from './ForceReleaseConfirm';

const { Title, Text } = Typography;

const AUTO_REFRESH_INTERVAL = 30000; // 30 秒自动刷新

const LockMonitorPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('product-imports');

  // ---- 产品导入锁 ----
  const [importLocks, setImportLocks] = useState<ProductImportLockDTO[]>([]);
  const [importLoading, setImportLoading] = useState(false);
  const [importError, setImportError] = useState<string | null>(null);

  // ---- DDL 锁 ----
  const [ddlStatus, setDdlStatus] = useState<DdlLockStatusDTO | null>(null);
  const [ddlLoading, setDdlLoading] = useState(false);
  const [ddlError, setDdlError] = useState<string | null>(null);
  const [ddlReleasing, setDdlReleasing] = useState(false);

  const autoRefreshRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadImportLocks = useCallback(async () => {
    setImportLoading(true);
    setImportError(null);
    try {
      const res = await lockMonitorService.listProductImportLocks();
      setImportLocks(res.data || []);
    } catch (err: any) {
      const errMsg = err?.message || '未知错误';
      setImportError(errMsg.includes('Network') || errMsg.includes('404')
        ? '后端未连接，当前显示模拟数据。'
        : `加载失败：${errMsg}`);
      try {
        const mock = await lockMonitorService.listProductImportLocks();
        setImportLocks(mock.data || []);
      } catch { /* ignore */ }
    } finally {
      setImportLoading(false);
    }
  }, []);

  const loadDdlStatus = useCallback(async () => {
    setDdlLoading(true);
    setDdlError(null);
    try {
      const res = await lockMonitorService.getDdlLockStatus();
      setDdlStatus(res.data);
    } catch (err: any) {
      const errMsg = err?.message || '未知错误';
      setDdlError(errMsg.includes('Network') || errMsg.includes('404')
        ? '后端未连接，当前显示模拟数据。'
        : `加载失败：${errMsg}`);
      try {
        const mock = await lockMonitorService.getDdlLockStatus();
        setDdlStatus(mock.data);
      } catch { /* ignore */ }
    } finally {
      setDdlLoading(false);
    }
  }, []);

  const loadAll = useCallback(() => {
    loadImportLocks();
    loadDdlStatus();
  }, [loadImportLocks, loadDdlStatus]);

  useEffect(() => {
    loadAll();
    autoRefreshRef.current = setInterval(loadAll, AUTO_REFRESH_INTERVAL);
    return () => {
      if (autoRefreshRef.current) clearInterval(autoRefreshRef.current);
    };
  }, [loadAll]);

  const handleReleaseImportLock = async (lockId: string) => {
    try {
      await lockMonitorService.releaseProductImportLock(lockId);
      message.success('锁已强制释放');
      loadImportLocks();
    } catch (err: any) {
      message.error(err?.message || '释放失败');
    }
  };

  const handleReleaseDdlLock = async () => {
    setDdlReleasing(true);
    try {
      await lockMonitorService.releaseDdlLock();
      message.success('DDL 锁已强制释放');
      loadDdlStatus();
    } catch (err: any) {
      message.error(err?.message || '释放失败');
    } finally {
      setDdlReleasing(false);
    }
  };

  const formatTime = (iso: string | null): string => {
    if (!iso) return '—';
    return new Date(iso).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
  };

  const isExpired = (expiresAt: string | null): boolean => {
    if (!expiresAt) return false;
    return new Date(expiresAt) < new Date();
  };

  // ---- 产品导入锁列表列定义 ----
  const importLockColumns: ColumnsType<ProductImportLockDTO> = [
    {
      title: '客户ID',
      dataIndex: 'customerId',
      key: 'customerId',
      width: 200,
      ellipsis: true,
      render: (val: string) => <Text code style={{ fontSize: 11 }}>{val}</Text>,
    },
    {
      title: 'Part No',
      dataIndex: 'partNo',
      key: 'partNo',
      width: 160,
      render: (val: string | null) => val || <Text type="secondary">（全客户）</Text>,
    },
    {
      title: '粒度',
      dataIndex: 'granularity',
      key: 'granularity',
      width: 100,
      render: (val: string) => {
        const map: Record<string, { label: string; color: string }> = {
          PART: { label: '料号级', color: 'blue' },
          CUSTOMER: { label: '客户级', color: 'geekblue' },
          GLOBAL: { label: '全局', color: 'red' },
        };
        const cfg = map[val] || { label: val, color: 'default' };
        return <Tag color={cfg.color}>{cfg.label}</Tag>;
      },
    },
    {
      title: '锁定人',
      dataIndex: 'lockedBy',
      key: 'lockedBy',
      width: 160,
      ellipsis: true,
      render: (val: string) => <Text code style={{ fontSize: 11 }}>{val}</Text>,
    },
    {
      title: '最后心跳',
      dataIndex: 'lastHeartbeatAt',
      key: 'lastHeartbeatAt',
      width: 160,
      render: (val: string) => {
        const date = new Date(val);
        const diffMs = Date.now() - date.getTime();
        const isStale = diffMs > 60000; // 超过 60s 无心跳视为异常
        return (
          <span>
            <span style={{ color: isStale ? '#ff4d4f' : undefined }}>
              {formatTime(val)}
            </span>
            {isStale && <WarningOutlined style={{ color: '#ff4d4f', marginLeft: 4 }} />}
          </span>
        );
      },
    },
    {
      title: '到期时间',
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      width: 160,
      render: (val: string) => (
        <span style={{ color: isExpired(val) ? '#ff4d4f' : undefined }}>
          {formatTime(val)}
          {isExpired(val) && <Tag color="error" style={{ marginLeft: 4 }}>已过期</Tag>}
        </span>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (val: string) => (
        <Badge status={val === 'ACTIVE' ? 'processing' : 'default'} text={val} />
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 110,
      fixed: 'right',
      render: (_: unknown, record: ProductImportLockDTO) => (
        <ForceReleaseConfirm
          onConfirm={() => handleReleaseImportLock(record.id)}
          description={`强制释放客户 ${record.customerId}${record.partNo ? `/ ${record.partNo}` : ''} 的导入锁？`}
        />
      ),
    },
  ];

  // ---- DDL 锁卡片 ----
  const renderDdlTab = () => {
    if (ddlLoading && !ddlStatus) {
      return <Card loading style={{ maxWidth: 640 }} />;
    }
    if (!ddlStatus) {
      return <Text type="secondary">暂无 DDL 锁状态</Text>;
    }

    return (
      <Card
        style={{ maxWidth: 640 }}
        title={
          <Space>
            <LockOutlined style={{ color: ddlStatus.locked ? '#ff4d4f' : '#52c41a' }} />
            <span>DDL 全局锁</span>
            <Badge
              status={ddlStatus.locked ? 'error' : 'success'}
              text={ddlStatus.locked ? '已锁定' : '空闲'}
            />
          </Space>
        }
        extra={
          ddlStatus.locked && (
            <ForceReleaseConfirm
              onConfirm={handleReleaseDdlLock}
              description="强制释放 DDL 全局锁？可能导致正在进行的 DDL 操作异常。"
              loading={ddlReleasing}
            />
          )
        }
      >
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item label="锁状态">
            {ddlStatus.locked ? (
              <Tag color="error">已锁定</Tag>
            ) : (
              <Tag color="success">空闲</Tag>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="锁定人">
            {ddlStatus.lockedBy ? (
              <Text code style={{ fontSize: 12 }}>{ddlStatus.lockedBy}</Text>
            ) : (
              <Text type="secondary">—</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="锁定时间">
            {formatTime(ddlStatus.lockedAt)}
          </Descriptions.Item>
          <Descriptions.Item label="到期时间">
            <span style={{ color: isExpired(ddlStatus.expiresAt) ? '#ff4d4f' : undefined }}>
              {formatTime(ddlStatus.expiresAt)}
              {isExpired(ddlStatus.expiresAt) && <Tag color="error" style={{ marginLeft: 4 }}>已过期</Tag>}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label="操作描述">
            {ddlStatus.operationDesc || <Text type="secondary">—</Text>}
          </Descriptions.Item>
        </Descriptions>
      </Card>
    );
  };

  const tabItems = [
    {
      key: 'product-imports',
      label: (
        <span>
          产品导入锁
          {importLocks.length > 0 && (
            <Badge count={importLocks.length} size="small" style={{ marginLeft: 8 }} />
          )}
        </span>
      ),
      children: (
        <div>
          {importError && (
            <Alert type="warning" message={importError} showIcon closable style={{ marginBottom: 16 }} />
          )}
          <Table<ProductImportLockDTO>
            rowKey="id"
            columns={importLockColumns}
            dataSource={importLocks}
            loading={importLoading}
            pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (t) => `共 ${t} 条锁` }}
            scroll={{ x: 1200 }}
            size="middle"
            locale={{ emptyText: '当前无活跃导入锁' }}
          />
        </div>
      ),
    },
    {
      key: 'ddl',
      label: (
        <span>
          DDL 全局锁
          {ddlStatus?.locked && (
            <Badge status="error" style={{ marginLeft: 8 }} />
          )}
        </span>
      ),
      children: (
        <div>
          {ddlError && (
            <Alert type="warning" message={ddlError} showIcon closable style={{ marginBottom: 16 }} />
          )}
          {renderDdlTab()}
        </div>
      ),
    },
  ];

  return (
    <div>
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
          <LockOutlined style={{ fontSize: 22, color: '#1677ff' }} />
          <Title level={4} style={{ margin: 0 }}>
            锁监控
          </Title>
          <Text type="secondary" style={{ fontSize: 13 }}>
            每 30 秒自动刷新
          </Text>
        </Space>
        <Button icon={<ReloadOutlined />} onClick={loadAll}>
          立即刷新
        </Button>
      </div>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={tabItems}
      />
    </div>
  );
};

export default LockMonitorPage;
