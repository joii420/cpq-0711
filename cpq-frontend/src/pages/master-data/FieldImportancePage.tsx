import React, { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Space,
  Table,
  Tag,
  Typography,
  Select,
  Switch,
  message,
  Alert,
  Empty,
  Spin,
} from 'antd';
import { EditOutlined, FieldTimeOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { FieldImportanceItem, ImportanceLevel } from '../../types/field-importance';
import type { BasicDataSheet } from '../../services/basicDataConfigService';
import { basicDataConfigService } from '../../services/basicDataConfigService';
import { fieldImportanceService } from '../../services/fieldImportanceService';
import { useAuthStore } from '../../stores/authStore';
import EditFieldImportanceDrawer from './EditFieldImportanceDrawer';

const { Title, Text } = Typography;
const { Option } = Select;

const IMPORTANCE_CONFIG: Record<ImportanceLevel, { label: string; color: string }> = {
  CRITICAL: { label: '关键', color: 'red' },
  IMPORTANT: { label: '重要', color: 'orange' },
  NORMAL: { label: '普通', color: 'default' },
};

const FieldImportancePage: React.FC = () => {
  const { user } = useAuthStore();
  const isSystemAdmin = user?.role === 'SYSTEM_ADMIN';

  const [sheets, setSheets] = useState<BasicDataSheet[]>([]);
  const [selectedSheetId, setSelectedSheetId] = useState<string | null>(null);
  const [attributes, setAttributes] = useState<FieldImportanceItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [sheetsLoading, setSheetsLoading] = useState(false);
  const [backendError, setBackendError] = useState<string | null>(null);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editRecord, setEditRecord] = useState<FieldImportanceItem | null>(null);

  // 加载 sheet 列表
  useEffect(() => {
    setSheetsLoading(true);
    basicDataConfigService
      .listSheets('ACTIVE')
      .then((res) => {
        const list = res.data || [];
        setSheets(list);
        if (list.length > 0 && !selectedSheetId) {
          setSelectedSheetId(list[0].id);
        }
      })
      .catch(() => {
        // 静默失败
      })
      .finally(() => setSheetsLoading(false));
  }, []);

  const loadAttributes = useCallback(async (sheetId: string) => {
    setLoading(true);
    setBackendError(null);
    try {
      const res = await fieldImportanceService.listBySheet(sheetId);
      setAttributes(res.data || []);
    } catch (err: any) {
      const errMsg = err?.message || '未知错误';
      if (errMsg.includes('404') || errMsg.includes('Network') || errMsg.includes('ECONNREFUSED')) {
        setBackendError(
          '后端字段重要性接口尚未联调（后端需扩展 BasicDataAttributeDTO 含 importanceLevel/affectsCalculation 字段）。当前显示模拟数据。',
        );
        try {
          const mockRes = await fieldImportanceService.listBySheet(sheetId);
          setAttributes(mockRes.data || []);
        } catch { /* ignore */ }
      } else {
        message.error(`加载字段失败：${errMsg}`);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (selectedSheetId) {
      loadAttributes(selectedSheetId);
    } else {
      setAttributes([]);
    }
  }, [selectedSheetId, loadAttributes]);

  const handleEdit = (record: FieldImportanceItem) => {
    setEditRecord(record);
    setDrawerOpen(true);
  };

  const columns: ColumnsType<FieldImportanceItem> = [
    {
      title: '排序',
      dataIndex: 'sortOrder',
      key: 'sortOrder',
      width: 60,
    },
    {
      title: '列字母',
      dataIndex: 'columnLetter',
      key: 'columnLetter',
      width: 70,
      render: (val: string) => <Text code>{val}</Text>,
    },
    {
      title: '字段标签',
      dataIndex: 'variableLabel',
      key: 'variableLabel',
      width: 130,
    },
    {
      title: '列标题',
      dataIndex: 'columnTitle',
      key: 'columnTitle',
      ellipsis: true,
    },
    {
      title: '变量代码',
      dataIndex: 'variableCode',
      key: 'variableCode',
      width: 160,
      render: (val: string) => <Text code style={{ fontSize: 12 }}>{val}</Text>,
    },
    {
      title: '数据类型',
      dataIndex: 'dataType',
      key: 'dataType',
      width: 90,
      render: (val: string) => (
        <Tag color={val === 'VALUE' ? 'green' : 'blue'}>{val}</Tag>
      ),
    },
    {
      title: '重要性级别',
      dataIndex: 'importanceLevel',
      key: 'importanceLevel',
      width: 110,
      render: (val: ImportanceLevel) => {
        const cfg = IMPORTANCE_CONFIG[val] || { label: val, color: 'default' };
        return <Tag color={cfg.color}>{cfg.label}</Tag>;
      },
    },
    {
      title: '影响计算',
      dataIndex: 'affectsCalculation',
      key: 'affectsCalculation',
      width: 90,
      render: (val: boolean) => (
        <Switch
          checked={val}
          size="small"
          disabled
          checkedChildren="是"
          unCheckedChildren="否"
        />
      ),
    },
    {
      title: '导入必填',
      dataIndex: 'isRequired',
      key: 'isRequired',
      width: 90,
      render: (val: boolean | undefined) => (
        <Switch
          checked={!!val}
          size="small"
          disabled
          checkedChildren="必填"
          unCheckedChildren="可选"
        />
      ),
    },
    {
      title: '备注',
      dataIndex: 'remark',
      key: 'remark',
      ellipsis: true,
      render: (val: string | null) => val || <Text type="secondary">—</Text>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 80,
      fixed: 'right',
      render: (_: unknown, record: FieldImportanceItem) => (
        <Button
          type="link"
          size="small"
          icon={<EditOutlined />}
          disabled={!isSystemAdmin}
          onClick={() => handleEdit(record)}
        >
          编辑
        </Button>
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
          <FieldTimeOutlined style={{ fontSize: 22, color: '#1677ff' }} />
          <Title level={4} style={{ margin: 0 }}>
            字段重要性配置
          </Title>
          {attributes.length > 0 && (
            <Text type="secondary" style={{ fontSize: 13 }}>
              共 {attributes.length} 个字段
            </Text>
          )}
        </Space>
        <Space>
          {sheetsLoading ? (
            <Spin size="small" />
          ) : (
            <Select
              value={selectedSheetId}
              onChange={(val) => setSelectedSheetId(val)}
              style={{ width: 220 }}
              placeholder="请选择数据表"
              showSearch
              filterOption={(input, option) =>
                String(option?.children ?? '').toLowerCase().includes(input.toLowerCase())
              }
            >
              {sheets.map((s) => (
                <Option key={s.id} value={s.id}>
                  {s.sheetName}
                </Option>
              ))}
            </Select>
          )}
          <Button
            icon={<ReloadOutlined />}
            disabled={!selectedSheetId}
            onClick={() => selectedSheetId && loadAttributes(selectedSheetId)}
          >
            刷新
          </Button>
        </Space>
      </div>

      {backendError && (
        <Alert
          type="warning"
          message={backendError}
          showIcon
          closable
          style={{ marginBottom: 16 }}
        />
      )}

      {!isSystemAdmin && (
        <Alert
          type="info"
          message="字段重要性配置仅系统管理员可修改。"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      {!selectedSheetId ? (
        <Empty description="请先选择一个数据表" />
      ) : (
        <Table<FieldImportanceItem>
          rowKey="id"
          columns={columns}
          dataSource={attributes}
          loading={loading}
          pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (t) => `共 ${t} 个字段` }}
          scroll={{ x: 1000 }}
          size="middle"
          locale={{ emptyText: '该数据表暂无字段配置' }}
        />
      )}

      <EditFieldImportanceDrawer
        open={drawerOpen}
        record={editRecord}
        onClose={() => setDrawerOpen(false)}
        onSuccess={() => selectedSheetId && loadAttributes(selectedSheetId)}
      />
    </div>
  );
};

export default FieldImportancePage;
