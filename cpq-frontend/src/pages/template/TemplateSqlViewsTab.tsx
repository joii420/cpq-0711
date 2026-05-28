/**
 * TemplateSqlViewsTab — Template 实体的独立 SQL 视图列表 + CRUD
 *
 * 设计：与 CostingTemplateSqlViewsTab 同构，但：
 *   - owner 是 template（template 实体，不是 costing_template）
 *   - 路由前缀 /api/cpq/templates/...
 *   - prop 名 templateId（替代 costingTemplateId）
 *   - 底层 service 换成 templateSqlViewService
 *
 * 规范：SelectableTable + 工具栏动作（CLAUDE.md 列表操作规范）
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Divider,
  Drawer,
  Form,
  Input,
  Space,
  Spin,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExperimentOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import {
  templateSqlViewService,
  type TemplateSqlView,
  type TemplateSqlViewPayload,
  type DryRunResult,
  type SqlViewColumn,
} from '../../services/templateSqlViewService';

const { Text, Paragraph } = Typography;

// SQL 视图名校验正则（与组件管理保持一致）
const SQL_VIEW_NAME_REGEX = /^[a-z_][a-z0-9_]*$/;

// 允许的占位符（文案提示用）
const ALLOWED_PLACEHOLDERS = [
  ':customerId',
  ':partVersion',
  ':templateKind',
  ':userId',
  ':quotationId',
  ':costingSummaryId',
];

interface Props {
  templateId: string;
  /** 模板非 DRAFT 状态时设为 true，禁止 CUD 操作 */
  readonly: boolean;
}

// ── 编辑抽屉 ─────────────────────────────────────────────────────────

interface ConfigDrawerProps {
  open: boolean;
  templateId: string;
  editingView: TemplateSqlView | null;
  readonly: boolean;
  onClose: () => void;
  onSaved: () => void;
}

const parseDeclaredColumnsLocal = (raw: unknown): SqlViewColumn[] => {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw as SqlViewColumn[];
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
};

const TemplateSqlViewConfigDrawer: React.FC<ConfigDrawerProps> = ({
  open,
  templateId,
  editingView,
  readonly,
  onClose,
  onSaved,
}) => {
  const [form] = Form.useForm<TemplateSqlViewPayload>();
  const [dryRunResult, setDryRunResult] = useState<DryRunResult | null>(null);
  const [dryRunLoading, setDryRunLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    if (editingView) {
      form.setFieldsValue({
        sqlViewName: editingView.sqlViewName,
        sqlTemplate: editingView.sqlTemplate,
        description: editingView.description ?? undefined,
      });
    } else {
      form.resetFields();
    }
    setDryRunResult(null);
  }, [open, editingView, form]);

  const handleDryRun = async () => {
    let values: TemplateSqlViewPayload;
    try {
      values = await form.validateFields(['sqlTemplate']);
    } catch {
      return;
    }
    const sql = values.sqlTemplate?.trim();
    if (!sql) {
      message.warning('请先填写 SQL 模板');
      return;
    }
    setDryRunLoading(true);
    setDryRunResult(null);
    try {
      const res = await templateSqlViewService.dryRun({ templateId, sqlTemplate: sql });
      setDryRunResult(res.data);
    } catch (e: any) {
      setDryRunResult({
        success: false,
        error: e?.response?.data?.message ?? e?.message ?? '网络错误',
      });
    } finally {
      setDryRunLoading(false);
    }
  };

  const handleSave = async () => {
    if (readonly) return;
    let values: TemplateSqlViewPayload;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    if (!dryRunResult?.success) {
      message.warning('请先点击"Dry-Run 测试"并通过校验，再保存');
      return;
    }
    setSaving(true);
    try {
      if (editingView) {
        await templateSqlViewService.update(templateId, editingView.id, values);
        message.success('SQL 视图已更新');
      } else {
        await templateSqlViewService.create(templateId, values);
        message.success('SQL 视图已创建');
      }
      onSaved();
      onClose();
    } catch (e: any) {
      const msg = e?.response?.data?.message ?? e?.message ?? '保存失败';
      message.error(msg);
    } finally {
      setSaving(false);
    }
  };

  const handleClose = () => {
    form.resetFields();
    setDryRunResult(null);
    onClose();
  };

  const renderDryRunResult = () => {
    if (!dryRunResult) return null;
    if (!dryRunResult.success) {
      return (
        <Alert
          type="error"
          showIcon
          icon={<CloseCircleOutlined />}
          message="Dry-Run 失败"
          description={dryRunResult.error ?? '未知错误'}
          style={{ marginTop: 8 }}
        />
      );
    }
    const cols: SqlViewColumn[] = dryRunResult.declaredColumns ?? [];
    const vars: string[] = dryRunResult.requiredVariables ?? [];
    return (
      <Alert
        type="success"
        showIcon
        icon={<CheckCircleOutlined />}
        message="Dry-Run 通过"
        description={
          <div>
            <div style={{ marginBottom: 6 }}>
              <Text strong>列签名（{cols.length} 列）：</Text>
              <div style={{ marginTop: 4 }}>
                {cols.map((c) => (
                  <Tag key={c.name} color="blue" style={{ marginBottom: 4 }}>
                    {c.name} ({c.dataType}){c.nullable ? '' : ' NOT NULL'}
                  </Tag>
                ))}
                {cols.length === 0 && <Text type="secondary">（无列）</Text>}
              </div>
            </div>
            {vars.length > 0 && (
              <div>
                <Text strong>使用的占位符：</Text>
                <div style={{ marginTop: 4 }}>
                  {vars.map((v) => (
                    <Tag key={v} color="purple" style={{ marginBottom: 4 }}>
                      :{v}
                    </Tag>
                  ))}
                </div>
              </div>
            )}
          </div>
        }
        style={{ marginTop: 8 }}
      />
    );
  };

  return (
    <Drawer
      title={
        editingView
          ? `${readonly ? '查看' : '编辑'} SQL 视图 — ${editingView.sqlViewName}`
          : '新建 SQL 视图'
      }
      placement="right"
      width={960}
      open={open}
      onClose={handleClose}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={handleClose}>取消</Button>
            <Button
              icon={<ExperimentOutlined />}
              onClick={handleDryRun}
              loading={dryRunLoading}
            >
              Dry-Run 测试
            </Button>
            {!readonly && (
              <Button
                type="primary"
                onClick={handleSave}
                loading={saving}
                disabled={!dryRunResult?.success}
              >
                保存
              </Button>
            )}
          </Space>
        </div>
      }
    >
      <Alert
        type="info"
        showIcon
        message="模板独立 SQL 视图（隔离设计）"
        description={
          <div>
            <div>
              在此定义 SQL 后，列配置中可通过{' '}
              <Text code>$视图名.列名</Text>{' '}
              形式引用，仅限本模板内使用（与组件 SQL 视图完全隔离）。
            </div>
            <div style={{ marginTop: 4, color: '#d48806' }}>
              ⚠️ 禁止在 SQL 中使用 <Text code>:hfPartNo</Text>（标量）——
              批量料号由外层 <Text code>ANY(:hfPartNos)</Text> 注入。
              禁止 INSERT / UPDATE / DELETE / DDL 语句。
            </div>
          </div>
        }
        style={{ marginBottom: 16 }}
      />

      <Form form={form} layout="vertical" requiredMark="optional">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 24px' }}>
          <Form.Item
            name="sqlViewName"
            label="视图名称（BNF 引用名）"
            rules={[
              { required: true, message: '请输入视图名称' },
              {
                pattern: SQL_VIEW_NAME_REGEX,
                message: '只允许小写字母、数字、下划线，且必须以字母或下划线开头',
              },
              { max: 80, message: '最长 80 个字符' },
            ]}
            extra={
              <Text type="secondary" style={{ fontSize: 12 }}>
                只含 a-z / 0-9 / _，引用时写{' '}
                <Text code>$视图名.列名</Text>
              </Text>
            }
          >
            <Input
              placeholder="如 summary_full / element_bom"
              style={{ fontFamily: 'Consolas, Monaco, monospace' }}
              disabled={readonly}
            />
          </Form.Item>

          <Form.Item label="作用域">
            <Tag color="default" style={{ lineHeight: '32px' }}>
              LOCAL（本模板内，不可跨模板引用）
            </Tag>
          </Form.Item>
        </div>

        <Form.Item name="description" label="描述（可选）">
          <Input
            placeholder="简要说明此 SQL 视图的用途"
            disabled={readonly}
          />
        </Form.Item>

        <Form.Item
          name="sqlTemplate"
          label="SQL 模板"
          rules={[
            { required: true, message: '请输入 SQL 模板' },
            {
              validator: (_, value: string) => {
                if (!value) return Promise.resolve();
                const upperVal = value.toUpperCase();
                const forbiddenKeywords = [
                  'INSERT',
                  'UPDATE',
                  'DELETE',
                  'CREATE',
                  'DROP',
                  'ALTER',
                  'TRUNCATE',
                ];
                for (const kw of forbiddenKeywords) {
                  if (new RegExp(`\\b${kw}\\b`).test(upperVal)) {
                    return Promise.reject(new Error(`SQL 中禁止使用 ${kw} 语句`));
                  }
                }
                if (/\:hfPartNo\b/.test(value)) {
                  return Promise.reject(
                    new Error(
                      '禁止使用 :hfPartNo（标量），批量料号由外层 ANY(:hfPartNos) 自动注入',
                    ),
                  );
                }
                return Promise.resolve();
              },
            },
          ]}
          extra={
            <div style={{ marginTop: 4 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                允许的占位符（系统级 RuntimeContext）：
              </Text>
              <div style={{ marginTop: 4 }}>
                {ALLOWED_PLACEHOLDERS.map((p) => (
                  <Tag
                    key={p}
                    color="geekblue"
                    style={{ marginBottom: 2, fontFamily: 'monospace' }}
                  >
                    {p}
                  </Tag>
                ))}
              </div>
              <div style={{ marginTop: 4, color: '#d48806', fontSize: 12 }}>
                建议在 SELECT 列表中包含 <Tag color="warning">hf_part_no</Tag> 列，
                以便外层 batch filter 正常工作
              </div>
            </div>
          }
        >
          <Input.TextArea
            rows={14}
            placeholder={`SELECT hf_part_no, material_cost, processing_cost, profit\nFROM v_costing_summary_v6\nWHERE customer_id = :customerId`}
            style={{
              fontFamily: 'Consolas, Monaco, monospace',
              fontSize: 13,
              lineHeight: 1.6,
            }}
            disabled={readonly}
            onChange={() => {
              setDryRunResult(null);
            }}
          />
        </Form.Item>
      </Form>

      <Divider style={{ margin: '8px 0 12px' }} />

      <Spin spinning={dryRunLoading} tip="正在 Dry-Run 校验...">
        {renderDryRunResult()}
        {!dryRunResult && !dryRunLoading && (
          <Paragraph type="secondary" style={{ fontSize: 12, margin: 0 }}>
            点击右下角「Dry-Run 测试」按钮，系统将用空 RuntimeContext 执行 EXPLAIN
            校验，并自动提取列签名（declaredColumns）和占位符（requiredVariables）。
            校验通过后「保存」按钮才会启用。
          </Paragraph>
        )}
      </Spin>
    </Drawer>
  );
};

// ── 主面板 ─────────────────────────────────────────────────────────

const TemplateSqlViewsTab: React.FC<Props> = ({ templateId, readonly }) => {
  const [views, setViews] = useState<TemplateSqlView[]>([]);
  const [loading, setLoading] = useState(false);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingView, setEditingView] = useState<TemplateSqlView | null>(null);

  const loadViews = useCallback(async () => {
    if (!templateId) return;
    setLoading(true);
    try {
      const res = await templateSqlViewService.list(templateId);
      setViews(res.data ?? []);
    } catch (e: any) {
      message.error('加载 SQL 视图失败: ' + (e?.message ?? ''));
    } finally {
      setLoading(false);
    }
  }, [templateId]);

  useEffect(() => {
    loadViews();
  }, [loadViews]);

  const handleOpenCreate = () => {
    setEditingView(null);
    setDrawerOpen(true);
  };

  const handleOpenEdit = (view: TemplateSqlView) => {
    setEditingView(view);
    setDrawerOpen(true);
  };

  const handleDelete = async (rows: TemplateSqlView[]) => {
    await runBatch(
      rows,
      async (row) => {
        await templateSqlViewService.delete(templateId, row.id);
      },
      {
        rowLabel: (r) => r.sqlViewName,
        successMsg: '已删除',
      },
    );
    loadViews();
  };

  const handleDryRun = async (rows: TemplateSqlView[]) => {
    const view = rows[0];
    try {
      message.loading({ content: '正在 Dry-Run...', key: 'dry-run' });
      const res = await templateSqlViewService.dryRun({
        templateId,
        sqlTemplate: view.sqlTemplate,
      });
      const result = res.data;
      if (result.success) {
        const cols = result.declaredColumns ?? [];
        message.success({
          content: `Dry-Run 通过 — ${cols.length} 列: ${cols.map((c) => c.name).join(', ')}`,
          key: 'dry-run',
          duration: 5,
        });
      } else {
        message.error({
          content: `Dry-Run 失败: ${result.error ?? '未知'}`,
          key: 'dry-run',
          duration: 8,
        });
      }
    } catch (e: any) {
      message.error({
        content: `Dry-Run 异常: ${e?.message ?? ''}`,
        key: 'dry-run',
        duration: 8,
      });
    }
  };

  const columns: ColumnsType<TemplateSqlView> = [
    {
      title: '视图名称',
      dataIndex: 'sqlViewName',
      key: 'sqlViewName',
      render: (name: string, record) => (
        <a
          onClick={() => handleOpenEdit(record)}
          style={{ fontFamily: 'Consolas, Monaco, monospace' }}
        >
          {name}
        </a>
      ),
    },
    {
      title: '引用路径',
      key: 'refPath',
      width: 200,
      render: (_: unknown, record: TemplateSqlView) => (
        <Text code style={{ fontSize: 12 }}>
          ${record.sqlViewName}.&lt;列名&gt;
        </Text>
      ),
    },
    {
      title: '列签名',
      key: 'columns',
      width: 100,
      render: (_: unknown, record: TemplateSqlView) => {
        const count = parseDeclaredColumnsLocal(record.declaredColumns).length;
        return <Text type="secondary">{count} 列</Text>;
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (status: string) =>
        status === 'ACTIVE' ? (
          <Tag color="success">启用</Tag>
        ) : (
          <Tag color="default">停用</Tag>
        ),
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (v: string) => v || <Text type="secondary">—</Text>,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 180,
      render: (v: string) =>
        v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—',
    },
  ];

  return (
    <>
      {readonly && (
        <Alert
          type="warning"
          showIcon
          message="模板已发布，SQL 视图为只读。如需修改，请先派生新草稿。"
          style={{ marginBottom: 12 }}
        />
      )}

      <SelectableTable<TemplateSqlView>
        rowKey="id"
        columns={columns}
        dataSource={views}
        loading={loading}
        rowLabel={(r) => r.sqlViewName}
        toolbar={
          !readonly && (
            <Button icon={<PlusOutlined />} type="primary" size="small" onClick={handleOpenCreate}>
              新建 SQL 视图
            </Button>
          )
        }
        actions={[
          {
            key: 'edit',
            label: readonly ? '查看' : '编辑',
            enabledWhen: (rows) =>
              rows.length === 1 ? true : rows.length === 0 ? false : '只能单选编辑',
            onClick: (rows) => handleOpenEdit(rows[0]),
          },
          {
            key: 'dry-run',
            label: 'Dry-Run 校验',
            icon: <ExperimentOutlined />,
            enabledWhen: (rows) =>
              rows.length === 1 ? true : rows.length === 0 ? false : '只能单选校验',
            onClick: handleDryRun,
          },
          ...(readonly
            ? []
            : [
                {
                  key: 'delete',
                  label: '删除',
                  danger: true,
                  enabledWhen: (rows: TemplateSqlView[]) => rows.length > 0,
                  needsConfirm: true,
                  confirmTitle: '确认删除选中的 {N} 个 SQL 视图？',
                  confirmDescription:
                    '删除后列配置中引用此视图的 BNF path 将失效，请确认没有列正在使用。',
                  onClick: handleDelete,
                },
              ]),
        ]}
        pagination={{ pageSize: 10, size: 'small', showTotal: (t: number) => `共 ${t} 条` }}
        size="small"
      />

      <TemplateSqlViewConfigDrawer
        open={drawerOpen}
        templateId={templateId}
        editingView={editingView}
        readonly={readonly}
        onClose={() => setDrawerOpen(false)}
        onSaved={loadViews}
      />
    </>
  );
};

export default TemplateSqlViewsTab;
