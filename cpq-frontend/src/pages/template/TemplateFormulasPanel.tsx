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
  Mentions,
  List,
  Row,
  Col,
  Divider,
} from 'antd';
import type { MentionProps } from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ExperimentOutlined,
  QuestionCircleOutlined,
  FunctionOutlined,
  CheckCircleOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import {
  templateFormulaService,
  type TemplateFormula,
  type FormulaCompletionsResponse,
  type FunctionDef,
  type EvaluateError,
} from '../../services/templateFormulaService';

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

// ---- 错误码中文映射 ----
const ERROR_CODE_LABELS: Record<string, string> = {
  PARSE_ERROR: '语法错误',
  UNKNOWN_REF: '未知引用',
  TYPE_MISMATCH: '类型不匹配',
  CIRCULAR_DEP: '循环依赖',
  RUNTIME_ERROR: '运行时错误',
};

// ---- 结构化错误展示 ----
interface StructuredErrorAlertProps {
  error: EvaluateError;
  onApplyFix: (replacement: string) => void;
}

const StructuredErrorAlert: React.FC<StructuredErrorAlertProps> = ({ error, onApplyFix }) => {
  const codeLabel = ERROR_CODE_LABELS[error.code] || error.code;
  const locationText =
    error.line
      ? `第 ${error.line} 行${error.column ? `第 ${error.column} 列` : ''}`
      : null;

  return (
    <Alert
      type={error.severity === 'WARNING' ? 'warning' : 'error'}
      showIcon
      icon={error.severity === 'WARNING' ? <WarningOutlined /> : undefined}
      message={
        <Space size={8}>
          <Tag color={error.severity === 'WARNING' ? 'orange' : 'red'} style={{ margin: 0 }}>
            {codeLabel}
          </Tag>
          <Text>{error.message}</Text>
        </Space>
      }
      description={
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          {locationText && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              位置：{locationText}
            </Text>
          )}
          {error.suggestions && error.suggestions.length > 0 && (
            <div>
              <Text strong style={{ fontSize: 12 }}>修复建议：</Text>
              <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
                {error.suggestions.map((s, i) => (
                  <li key={i} style={{ fontSize: 12 }}>
                    <Space size={4}>
                      <span>{s.description}</span>
                      {s.replacement && (
                        <Button
                          size="small"
                          type="link"
                          style={{ padding: 0, height: 'auto', fontSize: 12 }}
                          onClick={() => onApplyFix(s.replacement!)}
                        >
                          应用修复
                        </Button>
                      )}
                    </Space>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </Space>
      }
      style={{ marginBottom: 12 }}
    />
  );
};

// ---- 函数选择器 Modal ----
interface FunctionSelectorModalProps {
  open: boolean;
  onInsert: (signature: string) => void;
  onClose: () => void;
}

const CATEGORY_ORDER: FunctionDef['category'][] = ['聚合', '条件', '算术', '数学'];
const CATEGORY_ICONS: Record<string, string> = {
  '聚合': '📊',
  '条件': '🔀',
  '算术': '➕',
  '数学': '🔢',
};

const FunctionSelectorModal: React.FC<FunctionSelectorModalProps> = ({
  open,
  onInsert,
  onClose,
}) => {
  const [functions, setFunctions] = useState<FunctionDef[]>([]);
  const [loadingFns, setLoadingFns] = useState(false);
  const [selectedFn, setSelectedFn] = useState<FunctionDef | null>(null);

  useEffect(() => {
    if (!open) return;
    setLoadingFns(true);
    templateFormulaService.getFunctions().then(fns => {
      setFunctions(fns);
      const firstFn = fns.find(f => f.category === '聚合') || fns[0] || null;
      setSelectedFn(firstFn);
      setLoadingFns(false);
    });
  }, [open]);

  const categorized = CATEGORY_ORDER.reduce<Record<string, FunctionDef[]>>((acc, cat) => {
    acc[cat] = functions.filter(f => f.category === cat);
    return acc;
  }, {} as Record<string, FunctionDef[]>);

  const categoryList = CATEGORY_ORDER.filter(cat => (categorized[cat]?.length ?? 0) > 0);

  const handleInsert = () => {
    if (!selectedFn) return;
    onInsert(selectedFn.signature);
    onClose();
  };

  return (
    <Modal
      title="选择函数"
      open={open}
      onCancel={onClose}
      width={900}
      destroyOnClose
      footer={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" disabled={!selectedFn} onClick={handleInsert}>
            插入
          </Button>
        </Space>
      }
    >
      <Spin spinning={loadingFns}>
        <Row style={{ minHeight: 400 }}>
          {/* 左侧：分类 + 函数列表 */}
          <Col span={8} style={{ borderRight: '1px solid #f0f0f0', paddingRight: 12 }}>
            {categoryList.map(cat => (
              <div key={cat} style={{ marginBottom: 8 }}>
                <Text
                  type="secondary"
                  style={{ fontSize: 12, display: 'block', padding: '4px 0' }}
                >
                  {CATEGORY_ICONS[cat]} {cat} ({categorized[cat].length})
                </Text>
                <List
                  size="small"
                  dataSource={categorized[cat]}
                  renderItem={fn => (
                    <List.Item
                      style={{
                        padding: '4px 8px',
                        cursor: 'pointer',
                        borderRadius: 4,
                        background:
                          selectedFn?.name === fn.name ? '#e6f4ff' : 'transparent',
                        border:
                          selectedFn?.name === fn.name
                            ? '1px solid #91caff'
                            : '1px solid transparent',
                      }}
                      onClick={() => setSelectedFn(fn)}
                    >
                      <Text style={{ fontSize: 13 }}>
                        {selectedFn?.name === fn.name && (
                          <CheckCircleOutlined
                            style={{ color: '#1677ff', marginRight: 4, fontSize: 12 }}
                          />
                        )}
                        {fn.name}
                      </Text>
                    </List.Item>
                  )}
                />
              </div>
            ))}
          </Col>

          {/* 右侧：函数详情 */}
          <Col span={16} style={{ paddingLeft: 16 }}>
            {selectedFn ? (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                <div>
                  <Text strong style={{ fontSize: 16 }}>{selectedFn.name}</Text>
                  <Tag color="blue" style={{ marginLeft: 8 }}>{selectedFn.category}</Tag>
                </div>

                <div>
                  <Text type="secondary" style={{ fontSize: 12 }}>签名</Text>
                  <div
                    style={{
                      background: '#f5f5f5',
                      padding: '6px 10px',
                      borderRadius: 4,
                      fontFamily: 'monospace',
                      fontSize: 13,
                      marginTop: 4,
                    }}
                  >
                    {selectedFn.signature}
                  </div>
                </div>

                <div>
                  <Text type="secondary" style={{ fontSize: 12 }}>描述</Text>
                  <div style={{ marginTop: 4, fontSize: 13 }}>{selectedFn.description}</div>
                </div>

                {selectedFn.params.length > 0 && (
                  <div>
                    <Text type="secondary" style={{ fontSize: 12 }}>参数</Text>
                    <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
                      {selectedFn.params.map(p => (
                        <li key={p.name} style={{ fontSize: 12, marginBottom: 2 }}>
                          <Text code style={{ fontSize: 11 }}>{p.name}</Text>
                          <Tag
                            style={{ marginLeft: 4, fontSize: 10 }}
                            color={p.required ? 'red' : 'default'}
                          >
                            {p.type} {p.required ? '必填' : '可选'}
                          </Tag>
                          <Text type="secondary"> — {p.description}</Text>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                {selectedFn.examples.length > 0 && (
                  <div>
                    <Divider style={{ margin: '8px 0' }} />
                    <Text type="secondary" style={{ fontSize: 12 }}>示例</Text>
                    {selectedFn.examples.map((ex, i) => (
                      <div key={i} style={{ marginTop: 8 }}>
                        <div
                          style={{
                            background: '#f0f9ff',
                            border: '1px solid #bae6fd',
                            padding: '6px 10px',
                            borderRadius: 4,
                            fontFamily: 'monospace',
                            fontSize: 12,
                          }}
                        >
                          {ex.expression}
                        </div>
                        <Text type="secondary" style={{ fontSize: 11 }}>
                          {ex.explanation}
                        </Text>
                      </div>
                    ))}
                  </div>
                )}
              </Space>
            ) : (
              <div style={{ textAlign: 'center', paddingTop: 80, color: '#ccc' }}>
                请在左侧选择函数
              </div>
            )}
          </Col>
        </Row>
      </Spin>
    </Modal>
  );
};

// ---- 构建 Mentions options ----
type MentionOption = NonNullable<MentionProps['options']>[number];

function buildMentionOptions(
  prefix: string,
  completions: FormulaCompletionsResponse | null
): MentionOption[] {
  if (!completions) return [];

  if (prefix === '@') {
    return completions.globalVariables.map(v => ({
      value: v.name,
      label: `@${v.name}${v.dataType ? ` (${v.dataType})` : ''}${
        v.currentValue !== undefined && v.currentValue !== null
          ? ` 当前值: ${v.currentValue}`
          : ''
      }`,
    }));
  }

  // prefix === '['
  const formulaOptions: MentionOption[] = completions.templateFormulas.map(f => ({
    value: f.name,
    label: `${f.name} (${f.dataType})`,
  }));

  const componentOptions: MentionOption[] = completions.components.map(c => ({
    value: c.code,
    label: `${c.code} — ${c.name}`,
  }));

  const fieldOptions: MentionOption[] = completions.components.flatMap(c =>
    c.fields.map(field => ({
      value: `${c.code}.${field.name}`,
      label: `${c.code}.${field.name}${field.dataType ? ` (${field.dataType})` : ''}${
        field.label ? ` — ${field.label}` : ''
      }`,
    }))
  );

  return [...formulaOptions, ...componentOptions, ...fieldOptions];
}

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
                      dataSource={traceEntries.map(([k, v]) => ({
                        key: k,
                        varName: k,
                        varValue: String(v),
                      }))}
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

  // 结构化错误（来自 EvaluateResultExtended.error）
  const [liveStructuredError, setLiveStructuredError] = useState<EvaluateError | null>(null);
  const [liveValue, setLiveValue] = useState<string | null>(null);
  const [liveLoading, setLiveLoading] = useState(false);

  // 函数选择器
  const [fnModalOpen, setFnModalOpen] = useState(false);

  // 自动补全候选
  const [completions, setCompletions] = useState<FormulaCompletionsResponse | null>(null);
  // 当前激活的 mentions options（由 onSearch 驱动）
  const [mentionOptions, setMentionOptions] = useState<MentionOption[]>([]);

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isEdit = !!editingFormula;

  // 打开抽屉时重置状态并加载补全候选
  useEffect(() => {
    if (open) {
      if (editingFormula) {
        form.setFieldsValue(editingFormula);
      } else {
        form.resetFields();
        form.setFieldsValue({ dataType: 'DECIMAL(18,4)' });
      }
      setLiveStructuredError(null);
      setLiveValue(null);
      setMentionOptions([]);

      templateFormulaService.getCompletions(templateId).then(data => {
        setCompletions(data);
      });
    }
  }, [open, editingFormula, form, templateId]);

  const triggerLiveEval = useCallback(() => {
    if (!isEdit || !editingFormula) {
      setLiveStructuredError(null);
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
        const data = res.data as any;
        // 兼容 EvaluateResultExtended（有 error 字段）和旧格式
        if (data?.error) {
          setLiveStructuredError(data.error as EvaluateError);
          setLiveValue(null);
        } else {
          setLiveStructuredError(null);
          setLiveValue(String(data?.value ?? '(null)'));
        }
      } catch (e: any) {
        // HTTP 错误时构造基础 EvaluateError
        setLiveStructuredError({
          severity: 'ERROR',
          code: 'RUNTIME_ERROR',
          message: e.message || '编译/求值错误',
        });
        setLiveValue(null);
      } finally {
        setLiveLoading(false);
      }
    }, 200);
  }, [templateId, isEdit, editingFormula]);

  // Mentions onSearch：根据 prefix 更新候选项
  const handleMentionsSearch = useCallback(
    (_text: string, prefix: string) => {
      setMentionOptions(buildMentionOptions(prefix, completions));
    },
    [completions]
  );

  // 插入函数 signature 到 expression 字段（追加到末尾）
  const handleInsertFunction = (signature: string) => {
    const current = form.getFieldValue('expression') || '';
    const newValue = current ? `${current}\n${signature}` : signature;
    form.setFieldValue('expression', newValue);
    triggerLiveEval();
  };

  // 应用修复建议（替换整段公式）
  const handleApplyFix = (replacement: string) => {
    form.setFieldValue('expression', replacement);
    triggerLiveEval();
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      if (isEdit && editingFormula) {
        await templateFormulaService.update(
          templateId,
          editingFormula.name,
          values as TemplateFormula
        );
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
    <>
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
              <Collapse size="small" accordion items={SYNTAX_HELP_ITEMS} />
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
            label={
              <Space>
                <span>公式表达式</span>
                <Button
                  size="small"
                  icon={<FunctionOutlined />}
                  onClick={() => setFnModalOpen(true)}
                >
                  插入函数
                </Button>
              </Space>
            }
            rules={[{ required: true, message: '请输入公式表达式' }]}
          >
            <Mentions
              prefix={['[', '@']}
              rows={8}
              style={{ fontFamily: 'monospace', fontSize: 13 }}
              placeholder="输入 [ 引用公式/组件字段，输入 @ 引用全局变量"
              options={mentionOptions}
              filterOption={() => true}
              onSearch={handleMentionsSearch}
              onChange={triggerLiveEval}
            />
          </Form.Item>

          <Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: -16, marginBottom: 12 }}>
            提示：输入 <code>[</code> 触发公式/组件字段补全，输入 <code>@</code> 触发全局变量补全
            {!completions && '（候选加载中...）'}
          </Text>

          {liveLoading && (
            <div style={{ marginBottom: 12 }}>
              <Spin size="small" />
              <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>编译中...</Text>
            </div>
          )}

          {liveStructuredError && (
            <StructuredErrorAlert
              error={liveStructuredError}
              onApplyFix={handleApplyFix}
            />
          )}

          {liveValue !== null && !liveStructuredError && (
            <Alert
              type="success"
              showIcon
              message={`试算结果（partNo=3100080003）：${liveValue}`}
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

      <FunctionSelectorModal
        open={fnModalOpen}
        onInsert={handleInsertFunction}
        onClose={() => setFnModalOpen(false)}
      />
    </>
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
      <div
        style={{
          marginBottom: 12,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
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
