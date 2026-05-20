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
  Switch,
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
  /** V190: 默认隐藏 visibility=COSTING_INTERNAL 的变量 (核价 3 张); toggle on 可查看 */
  const [showCostingInternal, setShowCostingInternal] = useState(false);

  // G2: 新建变量 Modal
  const [createDefOpen, setCreateDefOpen] = useState(false);
  const [createDefForm] = Form.useForm();

  const openCreateDef = () => {
    createDefForm.resetFields();
    createDefForm.setFieldsValue({ varType: 'LOOKUP_TABLE', keyColumnsStr: 'process_code' });
    setCreateDefOpen(true);
  };

  const submitCreateDef = async () => {
    try {
      const values = await createDefForm.validateFields();
      const keyColumns = String(values.varType === 'SCALAR'
          ? '' : (values.keyColumnsStr ?? ''))
        .split(',').map((s: string) => s.trim()).filter(Boolean);
      if (values.varType === 'LOOKUP_TABLE' && keyColumns.length === 0) {
        message.error('LOOKUP_TABLE 必须填至少一个 key 列名');
        return;
      }
      await globalVariableService.create({
        code: values.code,
        name: values.name,
        varType: values.varType,
        keyColumns,
        valueColumn: 'value_number',
        unit: values.unit,
        description: values.description,
        sortOrder: 100,
      });
      message.success('新建成功');
      setCreateDefOpen(false);
      await loadDefs();
    } catch (e: any) {
      if (e?.errorFields) return;  // form validation
      message.error(e?.response?.data?.message || e?.message || '新建失败');
    }
  };

  const handleDeleteDef = (def: GlobalVariableDefinition) => {
    Modal.confirm({
      title: `确认删除变量 ${def.code}?`,
      content: (
        <Alert
          type="error"
          showIcon
          message="级联清除该变量的所有值; 公式 / 字段引用立即失效"
        />
      ),
      okText: '确认删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await globalVariableService.remove(def.code);
          message.success('已删除');
          await loadDefs();
        } catch (e: any) {
          message.error(e?.response?.data?.message || e?.message || '删除失败');
        }
      },
    });
  };

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
      title: '形态', key: 'shape', width: 110,
      render: (_, r) => {
        if (r.valueSourceType === 'COSTING_VIEW') {
          return <Tag color="purple">核价</Tag>;
        }
        return <Tag color="green">KV 单表</Tag>;
      },
    },
    {
      title: '操作', key: 'actions', width: 320,
      render: (_, r) => {
        const isCosting = r.valueSourceType === 'COSTING_VIEW';
        return (
          <Space>
            <Tooltip title={isCosting ? '核价价格表请到核价模块页面维护，此处只读' : undefined}>
              <Button
                size="small"
                type="primary"
                icon={<EditOutlined />}
                disabled={isCosting}
                onClick={() => !isCosting && openMaintain(r)}
              >
                维护数据
              </Button>
            </Tooltip>
            <Button size="small" icon={<HistoryOutlined />} onClick={() => openLog(r.code)}>
              变更历史
            </Button>
            {canEdit && !isCosting && (
              <Button size="small" danger icon={<DeleteOutlined />} onClick={() => handleDeleteDef(r)}>
                删除
              </Button>
            )}
          </Space>
        );
      },
    },
  ], [keyCounts, canEdit]);

  /** V190: 过滤显示 — visibility=COSTING_INTERNAL 默认隐藏 */
  const visibleDefs = useMemo(
    () => showCostingInternal ? defs : defs.filter(d => d.visibility !== 'COSTING_INTERNAL'),
    [defs, showCostingInternal],
  );

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
          {canEdit && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreateDef}>
              新建变量
            </Button>
          )}
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
        <Space style={{ marginBottom: 12 }}>
          <Switch
            checked={showCostingInternal}
            onChange={setShowCostingInternal}
            size="small"
          />
          <Text type="secondary" style={{ fontSize: 13 }}>
            显示核价价格变量（ELEM_PRICE / MAT_PRICE / EXCHANGE_RATE — 仅查看, 维护去核价模块）
          </Text>
        </Space>
        <Table
          rowKey="code"
          dataSource={visibleDefs}
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

      {/* G2: 新建变量 Modal — KV_TABLE + PUBLIC 形态, 后端自动设值 */}
      <Modal
        title="新建全局变量"
        open={createDefOpen}
        onCancel={() => setCreateDefOpen(false)}
        onOk={submitCreateDef}
        okText="创建"
        cancelText="取消"
        width={520}
      >
        <Form form={createDefForm} layout="vertical" preserve={false}>
          <Form.Item
            label="变量代号"
            name="code"
            rules={[
              { required: true, message: 'code 必填' },
              { pattern: /^[A-Z][A-Z0-9_]{2,63}$/, message: '大写字母开头, 仅含 A-Z 0-9 _, 3~64 长度' },
            ]}
            extra="公式 / 字段引用按此 code 识别, 创建后不可改"
          >
            <Input placeholder="如 SYSTEM_DEFAULT_MARGIN" />
          </Form.Item>
          <Form.Item label="变量名称" name="name" rules={[{ required: true, message: '名称必填' }]}>
            <Input placeholder="如 系统默认毛利率" />
          </Form.Item>
          <Form.Item label="变量类型" name="varType" rules={[{ required: true }]} initialValue="LOOKUP_TABLE">
            <Input.Group compact>
              <Form.Item name="varType" noStyle>
                <select
                  style={{ width: '100%', height: 32, padding: '4px 8px', border: '1px solid #d9d9d9', borderRadius: 4 }}
                >
                  <option value="LOOKUP_TABLE">查表型 (按 key 查)</option>
                  <option value="SCALAR">标量 (单一值)</option>
                </select>
              </Form.Item>
            </Input.Group>
          </Form.Item>
          <Form.Item
            label="key 列名 (LOOKUP 必填; SCALAR 留空)"
            name="keyColumnsStr"
            extra="多个 key 列用逗号分隔, 如 from_currency,to_currency. 命名建议与 driver 行物理列名一致 (默认同名映射)"
          >
            <Input placeholder="如 process_code" />
          </Form.Item>
          <Form.Item label="单位" name="unit">
            <Input placeholder="如 % / CNY/KG / 元/件" />
          </Form.Item>
          <Form.Item label="说明" name="description">
            <Input.TextArea rows={2} placeholder="变量用途 / 业务场景" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default GlobalVariablePage;
