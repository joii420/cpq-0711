import React, { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Space,
  Table,
  Tag,
  Typography,
  Select,
  Popconfirm,
  message,
  Alert,
  Tooltip,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ReloadOutlined,
  SettingOutlined,
  RollbackOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { SystemConfigDTO, ConfigCategory, DataType } from '../../types/system-config';
import { systemConfigService } from '../../services/systemConfigService';
import EditConfigDrawer from './EditConfigDrawer';
import { useAuthStore } from '../../stores/authStore';

const { Title, Text } = Typography;
const { Option } = Select;

const CATEGORY_LABELS: Record<ConfigCategory, string> = {
  validation: '校验规则',
  import: '导入配置',
  retention: '数据保留',
  element_price: '元素价格',
  business: '业务配置',
};

const DATA_TYPE_COLORS: Record<DataType, string> = {
  STRING: 'blue',
  NUMBER: 'green',
  BOOLEAN: 'orange',
  JSON: 'purple',
};

const SystemConfigPage: React.FC = () => {
  const { user } = useAuthStore();
  const isSystemAdmin = user?.role === 'SYSTEM_ADMIN';

  const [configs, setConfigs] = useState<SystemConfigDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [backendError, setBackendError] = useState<string | null>(null);
  const [selectedCategory, setSelectedCategory] = useState<ConfigCategory | undefined>(undefined);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editMode, setEditMode] = useState<'create' | 'edit'>('create');
  const [editRecord, setEditRecord] = useState<SystemConfigDTO | null>(null);

  const loadConfigs = useCallback(async (category?: ConfigCategory) => {
    setLoading(true);
    setBackendError(null);
    try {
      const res = await systemConfigService.list(category);
      setConfigs(res.data || []);
    } catch (err: any) {
      const errMsg = err?.message || '未知错误';
      if (errMsg.includes('404') || errMsg.includes('Network') || errMsg.includes('ECONNREFUSED')) {
        setBackendError('后端接口尚未连接，当前显示模拟数据。请确保 VITE_USE_MOCK_SYSTEM_CONFIG=true 或后端已启动。');
        // fallback to mock
        try {
          const mockRes = await systemConfigService.list(category);
          setConfigs(mockRes.data || []);
        } catch { /* ignore */ }
      } else {
        message.error(`加载系统配置失败：${errMsg}`);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadConfigs(selectedCategory);
  }, [selectedCategory, loadConfigs]);

  const handleCreate = () => {
    setEditMode('create');
    setEditRecord(null);
    setDrawerOpen(true);
  };

  const handleEdit = (record: SystemConfigDTO) => {
    setEditMode('edit');
    setEditRecord(record);
    setDrawerOpen(true);
  };

  const handleDelete = async (configKey: string) => {
    try {
      await systemConfigService.delete(configKey);
      message.success('配置项已删除');
      loadConfigs(selectedCategory);
    } catch (err: any) {
      message.error(err?.message || '删除失败');
    }
  };

  const handleReset = async (record: SystemConfigDTO) => {
    if (!record.defaultValue) {
      message.warning('该配置项没有默认值');
      return;
    }
    try {
      await systemConfigService.update(record.configKey, {
        configValue: record.defaultValue,
        description: record.description || undefined,
        modifiableBy: record.modifiableBy,
      });
      message.success('已恢复默认值');
      loadConfigs(selectedCategory);
    } catch (err: any) {
      message.error(err?.message || '重置失败');
    }
  };

  const canEdit = (record: SystemConfigDTO): boolean => {
    if (isSystemAdmin) return true;
    if (user?.role === 'SALES_MANAGER' && record.modifiableBy === 'SALES_MANAGER') return true;
    return false;
  };

  const columns: ColumnsType<SystemConfigDTO> = [
    {
      title: '配置键',
      dataIndex: 'configKey',
      key: 'configKey',
      width: 240,
      render: (val: string) => (
        <Text code style={{ fontSize: 12 }}>{val}</Text>
      ),
    },
    {
      title: '配置值',
      dataIndex: 'configValue',
      key: 'configValue',
      width: 160,
      ellipsis: true,
      render: (val: string) => (
        <Tooltip title={val}>
          <Text style={{ fontFamily: 'monospace', fontSize: 13 }}>{val}</Text>
        </Tooltip>
      ),
    },
    {
      title: '类型',
      dataIndex: 'dataType',
      key: 'dataType',
      width: 90,
      render: (val: DataType) => (
        <Tag color={DATA_TYPE_COLORS[val] || 'default'}>{val}</Tag>
      ),
    },
    {
      title: '分类',
      dataIndex: 'category',
      key: 'category',
      width: 100,
      render: (val: ConfigCategory) => CATEGORY_LABELS[val] || val,
    },
    {
      title: '说明',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (val: string | null) => val || <Text type="secondary">—</Text>,
    },
    {
      title: '可修改权限',
      dataIndex: 'modifiableBy',
      key: 'modifiableBy',
      width: 120,
      render: (val: string) => {
        const map: Record<string, string> = { SYSTEM_ADMIN: '系统管理员', SALES_MANAGER: '销售经理', ANYONE: '所有用户' };
        return <Text type="secondary">{map[val] || val}</Text>;
      },
    },
    {
      title: '最后修改',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 160,
      render: (val: string | null) =>
        val ? new Date(val).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) : '—',
    },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      fixed: 'right',
      render: (_: unknown, record: SystemConfigDTO) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            disabled={!canEdit(record)}
            onClick={() => handleEdit(record)}
          >
            编辑
          </Button>
          {record.defaultValue !== null && record.defaultValue !== record.configValue && (
            <Popconfirm
              title={`确认恢复默认值 "${record.defaultValue}"？`}
              okText="确认"
              cancelText="取消"
              onConfirm={() => handleReset(record)}
            >
              <Button
                type="link"
                size="small"
                icon={<RollbackOutlined />}
                disabled={!canEdit(record)}
              >
                重置
              </Button>
            </Popconfirm>
          )}
          {isSystemAdmin && (
            <Popconfirm
              title="确认删除该配置项？此操作不可撤销。"
              okText="确认删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => handleDelete(record.configKey)}
            >
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24, flexWrap: 'wrap', gap: 12 }}>
        <Space align="center">
          <SettingOutlined style={{ fontSize: 22, color: '#1677ff' }} />
          <Title level={4} style={{ margin: 0 }}>系统配置中心</Title>
          <Text type="secondary" style={{ fontSize: 13 }}>共 {configs.length} 项</Text>
        </Space>
        <Space>
          <Select
            value={selectedCategory}
            onChange={(val) => setSelectedCategory(val as ConfigCategory | undefined)}
            style={{ width: 150 }}
            allowClear
            placeholder="全部分类"
          >
            {(Object.keys(CATEGORY_LABELS) as ConfigCategory[]).map((key) => (
              <Option key={key} value={key}>{CATEGORY_LABELS[key]}</Option>
            ))}
          </Select>
          <Button icon={<ReloadOutlined />} onClick={() => loadConfigs(selectedCategory)}>
            刷新
          </Button>
          {isSystemAdmin && (
            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
              新建配置
            </Button>
          )}
        </Space>
      </div>

      {backendError && (
        <Alert type="warning" message={backendError} showIcon closable style={{ marginBottom: 16 }} />
      )}

      <Table<SystemConfigDTO>
        rowKey="configKey"
        columns={columns}
        dataSource={configs}
        loading={loading}
        pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (t) => `共 ${t} 项` }}
        scroll={{ x: 1100 }}
        size="middle"
        locale={{ emptyText: '暂无系统配置' }}
      />

      <EditConfigDrawer
        open={drawerOpen}
        mode={editMode}
        record={editRecord}
        onClose={() => setDrawerOpen(false)}
        onSuccess={() => loadConfigs(selectedCategory)}
      />
    </div>
  );
};

export default SystemConfigPage;
