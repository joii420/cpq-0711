import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Button,
  Drawer,
  Form,
  Input,
  Select,
  Space,
  Tag,
  Tooltip,
  Modal,
  message,
  Collapse,
  Typography,
  Spin,
  Table,
  Descriptions,
  Alert,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ExperimentOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import {
  templateFormulaService,
  type TemplateFormula,
} from '../../services/templateFormulaService';

const { TextArea } = Input;
const { Text, Paragraph } = Typography;

interface Props {
  templateId: string;
  templateStatus: 'DRAFT' | 'PUBLISHED' | string;
  onChange?: () => void;
}

const DATA_TYPE_OPTIONS = [
  { value: 'DECIMAL(18,4)', label: 'DECIMAL(18,4) — 小数' },
  { value: 'INTEGER', label: 'INTEGER — 整数' },
  { value: 'STRING', label: 'STRING — 字符串' },
];

const SYNTAX_HELP_ITEMS = [
  {
    key: 'arithmetic',
    label: '标量算术',
    children: (
      <Paragraph style={{ margin: 0, fontSize: 12 }}>
        <code>+ - * / ( )</code>，支持标准优先级。<br />
        示例：<code>[材料成本] * 1.05 + [加工费]</code>
      </Paragraph>
    ),
  },
  {
    key: 'colref',
    label: '列引用',
    children: (
      <Paragraph style={{ margin: 0, fontSize: 12 }}>
        <code>[公式名]</code> — 引用同模板内另一个公式<br />
        <code>[组件code.字段]</code> — 引用组件字段值<br />
        示例：<code>[COMP-BOM.单价] * [COMP-BOM.数量]</code>
      </Paragraph>
    ),
  },
  {
    key: 'global',
    label: '全局变量',
    children: (
      <Paragraph style={{ margin: 0, fontSize: 12 }}>
        <code>@变量名</code> — 引用系统全局变量<br />
        示例：<code>@汇率 * [材料成本]</code>
      </Paragraph>
    ),
  },
  {
    key: 'aggregate',
    label: '聚合函数',
    children: (
      <Paragraph style={{ margin: 0, fontSize: 12 }}>
        <code>{'SUM_OVER([组件code] WHERE 条件, 行表达式)'}</code><br />
        <code>COUNT_OVER / AVG_OVER / MIN_OVER / MAX_OVER</code><br />
        示例：<code>{'SUM_OVER([COMP-BOM] WHERE 类型="原材料", [单价]*[数量])'}</code>
      </Paragraph>
    ),
  },
  {
    key: 'functions',
    label: '行内函数',
    children: (
      <Paragraph style={{ margin: 0, fontSize: 12 }}>
        <code>{'IF(条件, 真值, 假值)'}</code><br />
        <code>{'COALESCE(a, b, c)'}</code> — 返回第一个非 null<br />
        <code>{'NULLIF(a, b)'}</code> — a==b 时返回 null<br />
        <code>ABS(x)</code> — 绝对值<br />
        示例：<code>{'IF([数量] > 0, [单价]*[数量], 0)'}</code>
      </Paragraph>
    ),
  },
];

// ---- Evaluate Modal ----
interface EvaluateModalProps {
  open: boolean;
  formula: TemplateFormula | null;
  templateId: string;
  onClose: () => void;
}

const EvaluateModal: React.FC<EvaluateModalProps> = ({ open, formula, templateId, onClose }) => {
  const [partNo, setPartNo] = useState('3100080003');
  const [customerId, setCustomerId] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<{ value: any; trace?: Record<string, any> } | null>(null);
  const [evalError, setEvalError] = useState<string | null>(null);

  const handleEvaluate = async () => {
    if (!formula) return;
    setLoading(true);
    setResult(null);
    setEvalError(null);
    try {
      const res = await templateFormulaService.evaluate(templateId, formula.name, {
        customerId,
        partNo,
      });
      setResult(res.data);
    } catch (e: any) {
      setEvalError(e.message || '试算失败');
    } finally {
      setLoading(false);
    }
  };

  const traceEntries = result?.trace ? Object.entries(result.trace) : [];

  return (
    <Modal
      title={`试算：${formula?.name ?? ''}`}
      open={open}
      onCancel={onClose}
      footer={null}
      width={600}
      destroyOnClose
    >
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Form layout="vertical" size="small">
          <Form.Item label="物料编号 (partNo)" required>
            <Input
              value={partNo}
              onChange={e => setPartNo(e.target.value)}
              placeholder="如：3100080003"
            />
          </Form.Item>
          <Form.Item label="客户 ID (customerId)">
            <Input
              value={customerId}
              onChange={e => setCustomerId(e.target.value)}
              placeholder="留空则不过滤客户"
            />
          </Form.Item>
        </Form>
        <Button type="primary" onClick={handleEvaluate} loading={loading} block>
          执行试算
        </Button>
        {evalError && <Alert type="error" message={evalError} showIcon />}
        {result && (
          <>
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label="计算结果">
                <Text strong style={{ fontSize: 16, color: '#52c41a' }}>
                  {result.value !== null && result.value !== undefined
                    ? String(result.value)
                    : '(null)'}
                </Text>
              </Descriptions.Item>
            </Descriptions>
            {traceEntries.length > 0 && (
              <Collapse
                size="small"
                items={[{
                  key: 'trace',
                  label: '中间值追踪',
                  children: (
                    <Table
                      size="small"
                      pagination={false}
                      dataSource={traceEntries.map(([k, v]) => ({ key: k, varName: k, varValue: String(v) }))}
                      columns={[
                        { title: '变量', dataIndex: 'varName', key: 'varName' },
                        { title: '值', dataIndex: 'varValue', key: 'varValue' },
                      ]}
                    />
                  ),
                }]}
              />
            )}
          </>
        )}
      </Space>
    </Modal>
  );
};

// ---- Formula Drawer ----
interface FormulaDrawerProps {
  open: boolean;
  templateId: string;
  editingFormula: TemplateFormula | null;
  existingNames: string[];
  onClose: () => void;
  onSaved: () => void;
}

const FormulaDrawer: React.FC<FormulaDrawerProps> = ({
  open,
  templateId,
  editingFormula,
  existingNames,
  onClose,
  onSaved,
}) => {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [liveError, setLiveError] = useState<string | null>(null);
  const [liveValue, setLiveValue] = useState<string | null>(null);
  const [liveLoading, setLiveLoading] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isEdit = !!editingFormula;

  useEffect(() => {
    if (open) {
      if (editingFormula) {
        form.setFieldsValue(editingFormula);
      } else {
        form.resetFields();
        form.setFieldsValue({ dataType: 'DECIMAL(18,4)' });
      }
      setLiveError(null);
      setLiveValue(null);
    }
  }, [open, editingFormula, form]);

  const triggerLiveEval = useCallback(() => {
    if (!isEdit || !editingFormula) {
      // 新增公式还未保存，无法试算
      setLiveError(null);
      setLiveValue(null);
      return;
    }
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      setLiveLoading(true);
      try {
        const res = await templateFormulaService.evaluate(templateId, editingFormula.name, {
          customerId: '',
          partNo: '3100080003',
        });
        setLiveValue(String(res.data?.value ?? '(null)'));
        setLiveError(null);
      } catch (e: any) {
        setLiveError(e.message || '编译/求值错误');
        setLiveValue(null);
      } finally {
        setLiveLoading(false);
      }
    }, 200);
  }, [templateId, isEdit, editingFormula]);

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      if (isEdit && editingFormula) {
        await templateFormulaService.update(templateId, editingFormula.name, values as TemplateFormula);
        message.success('公式已更新');
      } else {
        await templateFormulaService.add(templateId, values as TemplateFormula);
        message.success('公式已新建');
      }
      onSaved();
      onClose();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={isEdit ? `编辑公式：${editingFormula?.name}` : '新增公式'}
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        </div>
      }
    >
      <Collapse
        size="small"
        style={{ marginBottom: 16 }}
        items={[{
          key: 'syntax',
          label: (
            <Space>
              <QuestionCircleOutlined />
              <span>语法帮助</span>
            </Space>
          ),
          children: (
            <Collapse
              size="small"
              accordion
              items={SYNTAX_HELP_ITEMS}
            />
          ),
        }]}
      />

      <Form form={form} layout="vertical" size="middle">
        <Form.Item
          name="name"
          label="公式名称"
          rules={[
            { required: true, message: '请输入公式名称' },
            {
              validator: (_, val) => {
                if (!isEdit && val && existingNames.includes(val)) {
                  return Promise.reject(new Error('公式名已存在'));
                }
                return Promise.resolve();
              },
            },
          ]}
        >
          <Input disabled={isEdit} placeholder="如：总成本(CNY/KG)" />
        </Form.Item>

        <Form.Item
          name="dataType"
          label="数据类型"
          rules={[{ required: true, message: '请选择数据类型' }]}
        >
          <Select options={DATA_TYPE_OPTIONS} />
        </Form.Item>

        <Form.Item name="description" label="描述">
          <Input placeholder="可选，简述公式用途" />
        </Form.Item>

        <Form.Item
          name="expression"
          label="公式表达式"
          rules={[{ required: true, message: '请输入公式表达式' }]}
        >
          <TextArea
            rows={8}
            style={{ fontFamily: 'monospace', fontSize: 13 }}
            placeholder={'如：[材料成本] + [加工费] + [管理费]'}
            onChange={triggerLiveEval}
          />
        </Form.Item>

        {liveLoading && (
          <div style={{ marginBottom: 12 }}>
            <Spin size="small" />
            <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>编译中...</Text>
          </div>
        )}
        {liveError && (
          <Alert
            type="error"
            message="表达式错误"
            description={liveError}
            showIcon
            style={{ marginBottom: 12 }}
          />
        )}
        {liveValue !== null && (
          <Alert
            type="success"
            message={`试算结果（partNo=3100080003）：${liveValue}`}
            showIcon
            style={{ marginBottom: 12 }}
          />
        )}
        {!isEdit && (
          <Alert
            type="info"
            message="新增公式保存后可在列表点击「试算」验证结果"
            showIcon
            style={{ marginBottom: 12 }}
          />
        )}
      </Form>
    </Drawer>
  );
};

// ---- Main Panel ----
const TemplateFormulasPanel: React.FC<Props> = ({ templateId, templateStatus, onChange }) => {
  const isDraft = templateStatus === 'DRAFT';
  const [formulas, setFormulas] = useState<TemplateFormula[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingFormula, setEditingFormula] = useState<TemplateFormula | null>(null);
  const [evaluateTarget, setEvaluateTarget] = useState<TemplateFormula | null>(null);
  const [evaluateOpen, setEvaluateOpen] = useState(false);

  const loadFormulas = useCallback(async () => {
    setLoading(true);
    try {
      const res = await templateFormulaService.list(templateId);
      setFormulas(res.data || []);
    } catch (e: any) {
      message.error(e.message || '加载公式失败');
    } finally {
      setLoading(false);
    }
  }, [templateId]);

  useEffect(() => {
    loadFormulas();
  }, [loadFormulas]);

  const handleAdd = () => {
    setEditingFormula(null);
    setDrawerOpen(true);
  };

  const handleEdit = (f: TemplateFormula) => {
    setEditingFormula(f);
    setDrawerOpen(true);
  };

  const handleDelete = (f: TemplateFormula) => {
    const dependents = formulas.filter(
      other => other.name !== f.name && (other.dependsOn || []).includes(f.name)
    );

    if (dependents.length > 0) {
      Modal.error({
        title: '无法删除',
        content: (
          <div>
            <p>公式 <strong>{f.name}</strong> 被以下公式引用，不可删除：</p>
            <ul style={{ paddingLeft: 20 }}>
              {dependents.map(d => <li key={d.name}>{d.name}</li>)}
            </ul>
          </div>
        ),
      });
      return;
    }

    Modal.confirm({
      title: '确认删除公式',
      content: (
        <div>
          <p>即将删除公式：<strong>{f.name}</strong></p>
          <p style={{ color: '#ff4d4f' }}>此操作不可撤销，请确认。</p>
        </div>
      ),
      okText: '确认删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await templateFormulaService.delete(templateId, f.name);
          message.success(`已删除公式：${f.name}`);
          await loadFormulas();
          onChange?.();
        } catch (e: any) {
          message.error(e.message || '删除失败');
        }
      },
    });
  };

  const handleEvaluate = (f: TemplateFormula) => {
    setEvaluateTarget(f);
    setEvaluateOpen(true);
  };

  const columns = [
    {
      title: '公式名',
      dataIndex: 'name',
      key: 'name',
      width: 180,
      render: (v: string) => <Text strong>{v}</Text>,
    },
    {
      title: '数据类型',
      dataIndex: 'dataType',
      key: 'dataType',
      width: 140,
      render: (v: string) => <Tag color="blue">{v}</Tag>,
    },
    {
      title: '表达式',
      dataIndex: 'expression',
      key: 'expression',
      ellipsis: true,
      render: (v: string) => (
        <Tooltip title={v} placement="topLeft">
          <code style={{ fontSize: 11, color: '#595959' }}>
            {v.length > 60 ? v.slice(0, 60) + '...' : v}
          </code>
        </Tooltip>
      ),
    },
    {
      title: '依赖',
      dataIndex: 'dependsOn',
      key: 'dependsOn',
      width: 200,
      render: (deps: string[] | undefined) =>
        deps && deps.length > 0 ? (
          <Space wrap size={4}>
            {deps.map(d => (
              <Tag key={d} style={{ fontSize: 10 }}>{d}</Tag>
            ))}
          </Space>
        ) : (
          <Text type="secondary" style={{ fontSize: 11 }}>无</Text>
        ),
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (v: string) => v ? <Text type="secondary">{v}</Text> : '—',
    },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      render: (_: any, record: TemplateFormula) => (
        <Space size={4}>
          <Tooltip title={isDraft ? '编辑' : '已发布模板不可编辑公式，请新建草稿'}>
            <Button
              size="small"
              icon={<EditOutlined />}
              disabled={!isDraft}
              onClick={() => handleEdit(record)}
            />
          </Tooltip>
          <Tooltip title={isDraft ? '删除' : '已发布模板不可删除公式，请新建草稿'}>
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              disabled={!isDraft}
              onClick={() => handleDelete(record)}
            />
          </Tooltip>
          <Tooltip title="试算">
            <Button
              size="small"
              icon={<ExperimentOutlined />}
              onClick={() => handleEvaluate(record)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: '16px 0' }}>
      <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          共 {formulas.length} 条公式
          {!isDraft && (
            <span style={{ marginLeft: 8, color: '#faad14' }}>
              （已发布模板，公式只读）
            </span>
          )}
        </Text>
        <Tooltip title={isDraft ? '' : '已发布模板不可改公式，请新建草稿'}>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={!isDraft}
            onClick={handleAdd}
          >
            新增公式
          </Button>
        </Tooltip>
      </div>

      <Table
        loading={loading}
        dataSource={formulas}
        columns={columns}
        rowKey="name"
        size="small"
        pagination={false}
        scroll={{ x: 900 }}
        locale={{ emptyText: '暂无公式，点击「新增公式」开始配置' }}
      />

      <FormulaDrawer
        open={drawerOpen}
        templateId={templateId}
        editingFormula={editingFormula}
        existingNames={formulas.map(f => f.name)}
        onClose={() => setDrawerOpen(false)}
        onSaved={() => {
          loadFormulas();
          onChange?.();
        }}
      />

      <EvaluateModal
        open={evaluateOpen}
        formula={evaluateTarget}
        templateId={templateId}
        onClose={() => {
          setEvaluateOpen(false);
          setEvaluateTarget(null);
        }}
      />
    </div>
  );
};

export default TemplateFormulasPanel;
