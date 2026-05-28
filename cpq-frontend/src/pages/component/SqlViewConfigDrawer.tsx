/**
 * SqlViewConfigDrawer — SQL 视图新建 / 编辑抽屉
 *
 * 规范：
 *   - Drawer placement="right" width=960（内容复杂，含 SQL 编辑区）
 *   - UI 文案全中文
 *   - 保存前必须通过 Dry-Run 校验
 */
import React, { useEffect, useState } from 'react';
import {
  Drawer,
  Form,
  Input,
  Radio,
  Button,
  Alert,
  Tag,
  Space,
  Spin,
  Typography,
  Divider,
  message,
} from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import {
  componentSqlViewService,
  type ComponentSqlView,
  type ComponentSqlViewPayload,
  type DryRunResult,
  type SqlViewColumn,
} from '../../services/componentSqlViewService';

const { Text, Paragraph } = Typography;

// 占位符白名单（文案提示用）
const ALLOWED_PLACEHOLDERS = [
  ':customerId',
  ':partVersion',
  ':templateKind',
  ':userId',
  ':quotationId',
  ':costingSummaryId',
];

// SQL 视图名校验正则
const SQL_VIEW_NAME_REGEX = /^[a-z_][a-z0-9_]*$/;

interface Props {
  open: boolean;
  componentId: string;
  /** 编辑模式时传入现有 SQL 视图，新建时传 null */
  editingView: ComponentSqlView | null;
  onClose: () => void;
  onSaved: () => void;
}

const SqlViewConfigDrawer: React.FC<Props> = ({
  open,
  componentId,
  editingView,
  onClose,
  onSaved,
}) => {
  const [form] = Form.useForm<ComponentSqlViewPayload>();

  const [dryRunResult, setDryRunResult] = useState<DryRunResult | null>(null);
  const [dryRunLoading, setDryRunLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // 打开时回填
  useEffect(() => {
    if (!open) return;
    if (editingView) {
      form.setFieldsValue({
        sqlViewName: editingView.sqlViewName,
        sqlTemplate: editingView.sqlTemplate,
        scope: editingView.scope,
        status: editingView.status,
        description: editingView.description,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ scope: 'COMPONENT', status: 'ACTIVE' });
    }
    setDryRunResult(null);
  }, [open, editingView, form]);

  const handleDryRun = async () => {
    let values: ComponentSqlViewPayload;
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
      const res = await componentSqlViewService.dryRun(componentId, sql);
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
    let values: ComponentSqlViewPayload;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }

    // 必须先 dry-run 通过才能保存
    if (!dryRunResult?.success) {
      message.warning('请先点击"Dry-Run 测试"并通过校验，再保存');
      return;
    }

    setSaving(true);
    try {
      if (editingView) {
        await componentSqlViewService.update(componentId, editingView.id, values);
        message.success('SQL 视图已更新');
      } else {
        await componentSqlViewService.create(componentId, values);
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
      title={editingView ? `编辑 SQL 视图 — ${editingView.sqlViewName}` : '新建 SQL 视图'}
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
            <Button
              type="primary"
              onClick={handleSave}
              loading={saving}
              disabled={!dryRunResult?.success}
            >
              保存
            </Button>
          </Space>
        </div>
      }
    >
      <Alert
        type="info"
        showIcon
        message="SQL 视图作为 BNF path 数据源"
        description={
          <div>
            <div>
              在此定义 SQL 后，字段配置中可通过{' '}
              <Text code>$视图名[谓词].列名</Text>{' '}
              形式引用，后端将 SQL 作为 inline subquery 执行，与物理表 BNF path 等价。
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

      <Form
        form={form}
        layout="vertical"
        requiredMark="optional"
      >
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
                只含 a-z / 0-9 / _，引用时写 <Text code>$视图名</Text>
              </Text>
            }
          >
            <Input
              placeholder="如 element_view / assembly_fee"
              style={{ fontFamily: 'Consolas, Monaco, monospace' }}
            />
          </Form.Item>

          <Form.Item
            name="scope"
            label="作用域"
            rules={[{ required: true, message: '请选择作用域' }]}
            extra={
              <Text type="secondary" style={{ fontSize: 12 }}>
                GLOBAL 允许其他组件通过 <Text code>$$组件编码.视图名</Text> 跨组件引用
              </Text>
            }
          >
            <Radio.Group>
              <Radio value="COMPONENT">COMPONENT（本组件内）</Radio>
              <Radio value="GLOBAL">GLOBAL（可跨组件引用）</Radio>
            </Radio.Group>
          </Form.Item>
        </div>

        <Form.Item
          name="description"
          label="描述（可选）"
        >
          <Input placeholder="简要说明此 SQL 视图的用途" />
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
                const forbiddenKeywords = ['INSERT', 'UPDATE', 'DELETE', 'CREATE', 'DROP', 'ALTER', 'TRUNCATE'];
                for (const kw of forbiddenKeywords) {
                  if (new RegExp(`\\b${kw}\\b`).test(upperVal)) {
                    return Promise.reject(new Error(`SQL 中禁止使用 ${kw} 语句`));
                  }
                }
                if (/\:hfPartNo\b/.test(value)) {
                  return Promise.reject(
                    new Error('禁止使用 :hfPartNo（标量），批量料号由外层 ANY(:hfPartNos) 自动注入'),
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
                  <Tag key={p} color="geekblue" style={{ marginBottom: 2, fontFamily: 'monospace' }}>
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
            placeholder={`SELECT hf_part_no, element_name, composition_pct
FROM mat_bom
WHERE bom_type = 'ELEMENT'
  AND customer_id = :customerId
UNION ALL
SELECT hf_part_no, element_name, composition_pct
FROM mat_bom_legacy
WHERE customer_id = :customerId`}
            style={{
              fontFamily: 'Consolas, Monaco, monospace',
              fontSize: 13,
              lineHeight: 1.6,
            }}
            onChange={() => {
              // SQL 变更后清除上次 dry-run 结果，强制重新校验
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
            点击右下角「Dry-Run 测试」按钮，系统将用空 RuntimeContext 执行 EXPLAIN 校验，
            并自动提取列签名（declaredColumns）和占位符（requiredVariables）。
            校验通过后「保存」按钮才会启用。
          </Paragraph>
        )}
      </Spin>
    </Drawer>
  );
};

export default SqlViewConfigDrawer;
