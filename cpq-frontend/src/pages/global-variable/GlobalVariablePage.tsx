import React, { useEffect, useMemo, useState } from 'react';
import {
  Table,
  Typography,
  Tag,
  Drawer,
  Card,
  Space,
  Button,
  Empty,
  message,
  Tooltip,
  Alert,
  Modal,
  Form,
  InputNumber,
  Input,
  Tabs,
  Popconfirm,
} from 'antd';
import {
  ReloadOutlined,
  EditOutlined,
  DeleteOutlined,
  PlusOutlined,
  HistoryOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  globalVariableService,
  type GlobalVariableDefinition,
  type ChangeLogEntry,
} from '../../services/globalVariableService';
import { useAuthStore } from '../../stores/authStore';

const { Title, Text } = Typography;

const ACTION_TAG: Record<string, { color: string; label: string }> = {
  INSERT: { color: 'green',  label: '新增' },
  UPDATE: { color: 'blue',   label: '修改' },
  DELETE: { color: 'red',    label: '删除' },
};

/**
 * V104+V106 全局变量配置页:
 *  - 上方: 注册表(每条全局变量定义)
 *  - 行内"维护数据": 打开 drawer, 当前生效值表格 + 增删改 + 二次确认
 *  - 「变更历史」抽屉: 全局或按变量过滤的行级日志
 *
 * 决策依据:
 *  #1 行级粒度日志 / #2 PRICING_MANAGER+ 写 / #3 二次确认 modal / #7 直接生效
 */
const GlobalVariablePage: React.FC = () => {
  const { user } = useAuthStore();
  const canEdit = user?.role === 'PRICING_MANAGER'
    || user?.role === 'SYSTEM_ADMIN'
    || user?.role === 'SALES_MANAGER';

  const [defs, setDefs] = useState<GlobalVariableDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyCounts, setKeyCounts] = useState<Record<string, number>>({});

  // 维护抽屉
  const [maintainDef, setMaintainDef] = useState<GlobalVariableDefinition | null>(null);
  const [entries, setEntries] = useState<any[]>([]);
  const [entriesLoading, setEntriesLoading] = useState(false);

  // 编辑/新增 modal
  const [editOpen, setEditOpen] = useState(false);
  const [editMode, setEditMode] = useState<'CREATE' | 'UPDATE'>('CREATE');
  const [editingRow, setEditingRow] = useState<any>(null);
  const [form] = Form.useForm();

  // 变更历史抽屉
  const [logOpen, setLogOpen] = useState(false);
  const [logFilterCode, setLogFilterCode] = useState<string | undefined>(undefined);
  const [logs, setLogs] = useState<ChangeLogEntry[]>([]);
  const [logLoading, setLogLoading] = useState(false);

  // ─── 加载注册表 + 行数 ──────────────────────────────────────
  const loadDefs = async () => {
    setLoading(true);
    try {
      const res = await globalVariableService.list();
      const list: GlobalVariableDefinition[] = res?.data?.data || res?.data || [];
      setDefs(Array.isArray(list) ? list : []);
      const counts: Record<string, number> = {};
      await Promise.all(
        list.map(async (d) => {
          try {
            const r = await globalVariableService.listKeys(d.code, 5000);
            const arr: any[] = r?.data?.data || r?.data || [];
            counts[d.code] = Array.isArray(arr) ? arr.length : 0;
          } catch {
            counts[d.code] = 0;
          }
        }),
      );
      setKeyCounts(counts);
    } catch {
      message.error('加载全局变量失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadDefs(); }, []);

  // ─── 维护抽屉 ──────────────────────────────────────────────
  const openMaintain = async (def: GlobalVariableDefinition) => {
    setMaintainDef(def);
    await reloadEntries(def);
  };

  const reloadEntries = async (def: GlobalVariableDefinition) => {
    setEntriesLoading(true);
    try {
      const res = await globalVariableService.listKeys(def.code, 1000);
      const rows: any[] = res?.data?.data || res?.data || [];
      setEntries(Array.isArray(rows) ? rows : []);
    } catch {
      message.error('加载明细失败');
      setEntries([]);
    } finally {
      setEntriesLoading(false);
    }
  };

  // ─── 编辑 / 新增 ────────────────────────────────────────────
  const openCreate = () => {
    if (!maintainDef) return;
    setEditMode('CREATE');
    setEditingRow(null);
    form.resetFields();
    setEditOpen(true);
  };

  const openEdit = (row: any) => {
    if (!maintainDef) return;
    setEditMode('UPDATE');
    setEditingRow(row);
    const formValues: Record<string, any> = { value: row.value, note: '' };
    for (const col of maintainDef.keyColumns) {
      formValues[`key_${col}`] = row.key_values?.[col];
    }
    form.setFieldsValue(formValues);
    setEditOpen(true);
  };

  const submitEdit = async () => {
    if (!maintainDef) return;
    try {
      const values = await form.validateFields();
      const keyValues: Record<string, any> = {};
      for (const col of maintainDef.keyColumns) {
        keyValues[col] = values[`key_${col}`];
      }
      const oldVal = editMode === 'UPDATE' ? editingRow?.value : null;
      const newVal = values.value;
      const noChange = editMode === 'UPDATE' && oldVal != null && Number(oldVal) === Number(newVal);
      if (noChange) {
        message.info('值未变更, 已跳过');
        setEditOpen(false);
        return;
      }
      // 决策 #3 二次确认 modal
      Modal.confirm({
        title: editMode === 'CREATE' ? '确认新增明细?' : '确认修改取值?',
        content: (
          <div style={{ fontSize: 13 }}>
            <div>变量: <Text code>{maintainDef.code}</Text> ({maintainDef.name})</div>
            <div>Key: {Object.entries(keyValues).map(([k, v]) => `${k}=${v}`).join(', ')}</div>
            {editMode === 'UPDATE' && (
              <div>原值: <Text type="secondary">{oldVal != null ? String(oldVal) : '—'}</Text></div>
            )}
            <div style={{ color: '#cf1322' }}>
              新值: <Text strong>{newVal}</Text> {maintainDef.unit ? maintainDef.unit : ''}
            </div>
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 8 }}
              message="保存后立即生效"
              description="所有 DRAFT 报价单 / 核价单下次重算将使用新值; 已发布单不受影响。"
            />
          </div>
        ),
        okText: '确认保存',
        cancelText: '取消',
        onOk: async () => {
          try {
            await globalVariableService.upsertEntry(maintainDef.code, {
              keyValues,
              value: newVal,
              note: values.note || undefined,
            });
            message.success('保存成功');
            setEditOpen(false);
            await reloadEntries(maintainDef);
            // 行数缓存失效
            setKeyCounts((prev) => ({ ...prev, [maintainDef.code]: -1 }));
            loadDefs();
          } catch (e: any) {
            message.error(e?.response?.data?.message || e?.message || '保存失败');
          }
        },
      });
    } catch {
      // form validation error, ignore
    }
  };

  const handleDelete = (row: any) => {
    if (!maintainDef) return;
    Modal.confirm({
      title: '确认删除明细?',
      content: (
        <div>
          <div>变量: <Text code>{maintainDef.code}</Text></div>
          <div>Key: {Object.entries(row.key_values || {}).map(([k, v]) => `${k}=${v}`).join(', ')}</div>
          <div>当前值: {row.value != null ? String(row.value) : '—'}</div>
          <Alert
            type="error"
            showIcon
            style={{ marginTop: 8 }}
            message="删除后, 引用该 key 的公式下次计算将取不到值 (按 0 兜底)"
          />
        </div>
      ),
      okText: '确认删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await globalVariableService.deleteEntry(maintainDef.code, {
            keyValues: row.key_values,
          });
          message.success('删除成功');
          await reloadEntries(maintainDef);
          loadDefs();
        } catch (e: any) {
          message.error(e?.response?.data?.message || e?.message || '删除失败');
        }
      },
    });
  };

  // ─── 变更历史 ──────────────────────────────────────────────
  const openLog = async (code?: string) => {
    setLogFilterCode(code);
    setLogOpen(true);
    setLogLoading(true);
    try {
      const res = await globalVariableService.listChangeLog(code, 200);
      const rows: ChangeLogEntry[] = res?.data?.data || res?.data || [];
      setLogs(Array.isArray(rows) ? rows : []);
    } catch {
      message.error('加载变更日志失败');
      setLogs([]);
    } finally {
      setLogLoading(false);
    }
  };

  // ─── 注册表表格 ────────────────────────────────────────────
  const columns: ColumnsType<GlobalVariableDefinition> = useMemo(() => [
    { title: '变量代号', dataIndex: 'code', key: 'code', width: 130, render: (v) => <Text code>{v}</Text> },
    { title: '变量名称', dataIndex: 'name', key: 'name', width: 140 },
    {
      title: '类型', dataIndex: 'varType', key: 'varType', width: 100,
      render: (v: string) =>
        v === 'LOOKUP_TABLE' ? <Tag color="blue">查表型</Tag> : <Tag color="orange">标量</Tag>,
    },
    {
      title: '物理来源', key: 'source',
      render: (_, r) => (
        <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
          {r.sourceView}
          <span style={{ color: '#999' }}>[{r.keyColumns.join(', ')}].{r.valueColumn}</span>
        </span>
      ),
    },
    {
      title: '当前行数', key: 'count', width: 90,
      render: (_, r) =>
        keyCounts[r.code] !== undefined && keyCounts[r.code] >= 0
          ? <Tag>{keyCounts[r.code]}</Tag> : <Tag color="default">…</Tag>,
    },
    { title: '单位', dataIndex: 'unit', key: 'unit', width: 80 },
    {
      title: '操作', key: 'actions', width: 240,
      render: (_, r) => (
        <Space>
          <Button size="small" type="primary" icon={<EditOutlined />} onClick={() => openMaintain(r)}>
            维护数据
          </Button>
          <Button size="small" icon={<HistoryOutlined />} onClick={() => openLog(r.code)}>
            变更历史
          </Button>
        </Space>
      ),
    },
  ], [keyCounts]);

  // ─── 维护抽屉表格 ─────────────────────────────────────────
  const detailColumns: ColumnsType<any> = useMemo(() => {
    if (!maintainDef) return [];
    const cols: ColumnsType<any> = maintainDef.keyColumns.map((col) => ({
      title: col,
      key: col,
      render: (_, r) => String(r.key_values?.[col] ?? ''),
    }));
    cols.push({
      title: '取值',
      key: 'value',
      width: 140,
      render: (_, r) =>
        r.value !== null && r.value !== undefined
          ? <Text strong>{String(r.value)}</Text>
          : <Text type="secondary">—</Text>,
    });
    if (maintainDef.unit) {
      cols.push({ title: '单位', key: 'unit', width: 80, render: () => maintainDef.unit });
    }
    if (canEdit) {
      cols.push({
        title: '操作', key: 'ops', width: 160, fixed: 'right',
        render: (_, r) => (
          <Space size="small">
            <Button type="link" size="small" onClick={() => openEdit(r)}>编辑</Button>
            <Button type="link" size="small" danger onClick={() => handleDelete(r)}>删除</Button>
          </Space>
        ),
      });
    }
    return cols;
  }, [maintainDef, canEdit]);

  // ─── 变更日志列 ───────────────────────────────────────────
  const logColumns: ColumnsType<ChangeLogEntry> = [
    { title: '时间', dataIndex: 'changedAt', width: 170,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN') : '—' },
    { title: '变量', dataIndex: 'varCode', width: 130, render: (v) => <Text code>{v}</Text> },
    { title: 'Key', dataIndex: 'keyId', ellipsis: true },
    {
      title: '动作', dataIndex: 'action', width: 80,
      render: (v: string) => {
        const meta = ACTION_TAG[v] || { color: 'default', label: v };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: '原值 → 新值', key: 'change', width: 200,
      render: (_, r) => (
        <span>
          <Text type="secondary">{r.oldValue != null ? String(r.oldValue) : '—'}</Text>
          <span style={{ margin: '0 6px' }}>→</span>
          <Text strong>{r.newValue != null ? String(r.newValue) : '—'}</Text>
        </span>
      ),
    },
    { title: '操作人', dataIndex: 'changedByName', width: 100, render: (v) => v || '—' },
    { title: '备注', dataIndex: 'note', ellipsis: true, render: (v) => v || '—' },
  ];

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 16 }}>
        <div>
          <Title level={4} style={{ margin: 0 }}>全局变量配置</Title>
          <Text type="secondary">
            注册公式可引用的全局变量。值变更立即生效, 影响 DRAFT 报价单 / 新建核价单; 已发布单结果已冻结。
          </Text>
        </div>
        <Space>
          <Button icon={<HistoryOutlined />} onClick={() => openLog()}>全局变更历史</Button>
          <Button icon={<ReloadOutlined />} onClick={loadDefs} loading={loading}>刷新</Button>
        </Space>
      </div>

      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        style={{ marginBottom: 12 }}
        message="数据维护与公式引用集中在此"
        description={
          <div style={{ fontSize: 13 }}>
            点行内「维护数据」可直接增删改当前生效值。组件公式编辑器的「全局变量」面板从这里取候选值。
            {canEdit ? null : <span style={{ color: '#cf1322' }}>（您当前角色为只读, 仅 PRICING_MANAGER / SALES_MANAGER / SYSTEM_ADMIN 可编辑）</span>}
          </div>
        }
      />

      <Card>
        <Table
          rowKey="code"
          dataSource={defs}
          columns={columns}
          loading={loading}
          pagination={false}
          locale={{ emptyText: <Empty description="暂无全局变量" /> }}
          size="middle"
        />
      </Card>

      {/* ─── 维护抽屉 ─────────────────────────────────────── */}
      <Drawer
        title={maintainDef ? `${maintainDef.name} (${maintainDef.code}) — 当前生效明细` : ''}
        width={780}
        open={!!maintainDef}
        onClose={() => setMaintainDef(null)}
        extra={
          canEdit && maintainDef ? (
            <Space>
              <Button icon={<HistoryOutlined />} onClick={() => openLog(maintainDef.code)}>本变量变更历史</Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增明细</Button>
            </Space>
          ) : null
        }
      >
        {maintainDef && (
          <>
            <div style={{ marginBottom: 12 }}>
              <Text type="secondary">物理表:</Text>&nbsp;<Text code>{maintainDef.sourceView}</Text>
              &nbsp;&nbsp;<Text type="secondary">key 列:</Text>&nbsp;
              {maintainDef.keyColumns.map((c) => <Tag key={c}>{c}</Tag>)}
              &nbsp;<Text type="secondary">取值列:</Text>&nbsp;<Tag color="green">{maintainDef.valueColumn}</Tag>
              {maintainDef.description && (
                <div style={{ marginTop: 6, color: '#8c8c8c', fontSize: 12 }}>{maintainDef.description}</div>
              )}
            </div>
            <Table
              rowKey={(r, i) => `${i}-${JSON.stringify(r.key_values)}`}
              dataSource={entries}
              columns={detailColumns}
              loading={entriesLoading}
              size="small"
              pagination={{ pageSize: 30 }}
              scroll={{ x: 'max-content' }}
            />
          </>
        )}
      </Drawer>

      {/* ─── 编辑/新增 modal ──────────────────────────────── */}
      <Modal
        title={editMode === 'CREATE' ? '新增明细' : '编辑明细'}
        open={editOpen}
        onCancel={() => setEditOpen(false)}
        onOk={submitEdit}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        {maintainDef && (
          <Form form={form} layout="vertical">
            {maintainDef.keyColumns.map((col) => (
              <Form.Item
                key={col}
                label={col}
                name={`key_${col}`}
                rules={[{ required: true, message: `${col} 不能为空` }]}
              >
                <Input
                  placeholder={`输入 ${col}`}
                  disabled={editMode === 'UPDATE'} // 修改时 key 不能改, 改 key 应走删除+新增
                />
              </Form.Item>
            ))}
            <Form.Item
              label={`取值${maintainDef.unit ? ` (${maintainDef.unit})` : ''}`}
              name="value"
              rules={[{ required: true, message: '取值不能为空' }]}
            >
              <InputNumber
                style={{ width: '100%' }}
                step="0.0001"
                stringMode
                placeholder="数值"
              />
            </Form.Item>
            <Form.Item label="变更说明 (可选)" name="note">
              <Input.TextArea rows={2} placeholder="如: 5月调价, 来自采购通知" />
            </Form.Item>
          </Form>
        )}
      </Modal>

      {/* ─── 变更历史抽屉 ────────────────────────────────── */}
      <Drawer
        title={`变更历史${logFilterCode ? ` — ${logFilterCode}` : ' — 全部变量'}`}
        width={920}
        open={logOpen}
        onClose={() => setLogOpen(false)}
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => openLog(logFilterCode)} loading={logLoading}>
            刷新
          </Button>
        }
      >
        <Table
          rowKey="id"
          dataSource={logs}
          columns={logColumns}
          loading={logLoading}
          size="small"
          pagination={{ pageSize: 30 }}
          locale={{ emptyText: <Empty description="暂无变更记录" /> }}
        />
      </Drawer>
    </div>
  );
};

export default GlobalVariablePage;
