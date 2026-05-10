import React, { useEffect, useState } from 'react';
import {
  Drawer,
  Steps,
  Button,
  Form,
  Select,
  Input,
  InputNumber,
  Switch,
  Radio,
  Typography,
  Space,
  Alert,
  Popconfirm,
  Spin,
  message,
  Divider,
  Row,
  Col,
} from 'antd';
import { CheckCircleOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import type { ExtensibleTableDTO, ExtendColumnRequest } from '../../types/ddl-extension';
import {
  ddlExtensionService,
  generateMigrationSql,
} from '../../services/ddlExtensionService';

const { Text, Title } = Typography;
const { Option } = Select;
const { TextArea } = Input;

// snake_case 正则
const SNAKE_CASE_RE = /^[a-z][a-z0-9_]*$/;

// 支持的数据类型
const DATA_TYPES = [
  { value: 'VARCHAR', label: 'VARCHAR(N)' },
  { value: 'TEXT', label: 'TEXT' },
  { value: 'DECIMAL', label: 'DECIMAL(p,s)' },
  { value: 'INTEGER', label: 'INTEGER' },
  { value: 'BOOLEAN', label: 'BOOLEAN' },
  { value: 'DATE', label: 'DATE' },
  { value: 'TIMESTAMPTZ', label: 'TIMESTAMPTZ' },
];

interface Props {
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

const DdlExtensionWizardDrawer: React.FC<Props> = ({ open, onClose, onSuccess }) => {
  const [currentStep, setCurrentStep] = useState(0);
  const [form] = Form.useForm();

  // 步骤 1
  const [tablesLoading, setTablesLoading] = useState(false);
  const [tables, setTables] = useState<ExtensibleTableDTO[]>([]);
  const [selectedTable, setSelectedTable] = useState<string>('');

  // 步骤 2
  const [existingColumns, setExistingColumns] = useState<string[]>([]);
  const [columnsLoading, setColumnsLoading] = useState(false);
  const [baseType, setBaseType] = useState<string>('VARCHAR');
  const [varcharN, setVarcharN] = useState<number>(64);
  const [decimalP, setDecimalP] = useState<number>(10);
  const [decimalS, setDecimalS] = useState<number>(2);

  // 步骤 4
  const [submitting, setSubmitting] = useState(false);
  const [previewSql, setPreviewSql] = useState('');

  // 重置状态
  const resetAll = () => {
    setCurrentStep(0);
    setSelectedTable('');
    setExistingColumns([]);
    setBaseType('VARCHAR');
    setVarcharN(64);
    setDecimalP(10);
    setDecimalS(2);
    setPreviewSql('');
    form.resetFields();
  };

  // 打开时加载表列表
  useEffect(() => {
    if (open) {
      resetAll();
      loadTables();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const loadTables = async () => {
    setTablesLoading(true);
    try {
      const res = await ddlExtensionService.extensibleTables();
      setTables(res.data);
    } catch (err: any) {
      message.error(err.message || '加载表列表失败');
    } finally {
      setTablesLoading(false);
    }
  };

  const loadColumns = async (tableName: string) => {
    setColumnsLoading(true);
    try {
      const res = await ddlExtensionService.columns(tableName);
      setExistingColumns(res.data);
    } catch (err: any) {
      message.error(err.message || '加载字段列表失败');
    } finally {
      setColumnsLoading(false);
    }
  };

  // 组装最终 dataType 字符串
  const buildDataType = (): string => {
    if (baseType === 'VARCHAR') return `VARCHAR(${varcharN})`;
    if (baseType === 'DECIMAL') return `DECIMAL(${decimalP},${decimalS})`;
    return baseType;
  };

  // 生成预览 SQL
  const buildPreview = () => {
    const values = form.getFieldsValue();
    const req: ExtendColumnRequest = {
      tableName: selectedTable,
      columnName: values.columnName || '',
      dataType: buildDataType(),
      defaultValue: values.defaultValue ?? '',
      importance: values.importance || 'NORMAL',
      affectsCalculation: values.affectsCalculation ?? false,
      remark: values.remark,
    };
    setPreviewSql(generateMigrationSql(req));
    return req;
  };

  // 步骤 1 验证
  const handleStep1Next = async () => {
    if (!selectedTable) {
      message.warning('请选择目标表');
      return;
    }
    await loadColumns(selectedTable);
    setCurrentStep(1);
  };

  // 步骤 2 验证
  const handleStep2Next = async () => {
    try {
      await form.validateFields(['columnName', 'defaultValue']);
      const colName: string = form.getFieldValue('columnName');
      if (existingColumns.includes(colName)) {
        form.setFields([
          {
            name: 'columnName',
            errors: [`字段 "${colName}" 在表 "${selectedTable}" 中已存在`],
          },
        ]);
        return;
      }
      setCurrentStep(2);
    } catch {
      // validateFields 会自动显示错误
    }
  };

  // 步骤 3 验证
  const handleStep3Next = async () => {
    try {
      await form.validateFields(['importance']);
      buildPreview();
      setCurrentStep(3);
    } catch {
      // ignore
    }
  };

  // 最终提交
  const handleSubmit = async () => {
    setSubmitting(true);
    try {
      const values = form.getFieldsValue();
      const req: ExtendColumnRequest = {
        tableName: selectedTable,
        columnName: values.columnName,
        dataType: buildDataType(),
        defaultValue: values.defaultValue ?? '',
        importance: values.importance || 'NORMAL',
        affectsCalculation: values.affectsCalculation ?? false,
        remark: values.remark,
      };
      await ddlExtensionService.extend(req);
      message.success('扩列执行成功');
      onSuccess();
      onClose();
    } catch (err: any) {
      message.error(err.message || '扩列执行失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  const handleClose = () => {
    onClose();
  };

  // 步骤渲染
  const renderStep1 = () => (
    <div>
      <Title level={5} style={{ marginBottom: 16 }}>选择目标表</Title>
      <Alert
        type="info"
        message="仅可在白名单业务表中扩列，系统表不允许操作。"
        style={{ marginBottom: 20 }}
        showIcon
      />
      {tablesLoading ? (
        <Spin tip="加载表列表..." />
      ) : (
        <Form.Item label="目标表" required>
          <Select
            showSearch
            placeholder="请选择要扩列的物理表"
            style={{ width: '100%' }}
            value={selectedTable || undefined}
            onChange={(val) => setSelectedTable(val)}
            filterOption={(input, option) =>
              (option?.label as string)?.toLowerCase().includes(input.toLowerCase())
            }
          >
            {tables.map((t) => (
              <Option key={t.tableName} value={t.tableName} label={`${t.displayName} (${t.tableName})`}>
                <Space>
                  <Text strong>{t.displayName}</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {t.tableName}
                  </Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    现有 {t.columnCount} 列
                  </Text>
                </Space>
              </Option>
            ))}
          </Select>
        </Form.Item>
      )}
    </div>
  );

  const renderStep2 = () => (
    <div>
      <Title level={5} style={{ marginBottom: 8 }}>填写字段定义</Title>
      <Alert
        type="info"
        message={`目标表：${selectedTable}，现有 ${existingColumns.length} 个字段`}
        style={{ marginBottom: 20 }}
        showIcon
      />
      {columnsLoading ? (
        <Spin tip="加载现有字段..." />
      ) : (
        <Form form={form} layout="vertical">
          <Form.Item
            name="columnName"
            label="字段名（snake_case）"
            rules={[
              { required: true, message: '请填写字段名' },
              {
                validator: (_, val) => {
                  if (!val) return Promise.resolve();
                  if (!SNAKE_CASE_RE.test(val)) {
                    return Promise.reject(new Error('字段名须为 snake_case 格式，仅允许小写字母、数字和下划线，且以字母开头'));
                  }
                  if (existingColumns.includes(val)) {
                    return Promise.reject(new Error(`字段 "${val}" 在表 "${selectedTable}" 中已存在`));
                  }
                  return Promise.resolve();
                },
              },
            ]}
          >
            <Input placeholder="例：ext_project_no" />
          </Form.Item>

          <Form.Item label="数据类型" required>
            <Select
              value={baseType}
              onChange={(val) => setBaseType(val)}
              style={{ width: '100%', marginBottom: baseType === 'VARCHAR' || baseType === 'DECIMAL' ? 8 : 0 }}
            >
              {DATA_TYPES.map((dt) => (
                <Option key={dt.value} value={dt.value}>
                  {dt.label}
                </Option>
              ))}
            </Select>
            {baseType === 'VARCHAR' && (
              <Row gutter={8} align="middle">
                <Col>
                  <Text type="secondary">长度 N：</Text>
                </Col>
                <Col>
                  <InputNumber
                    min={1}
                    max={4096}
                    value={varcharN}
                    onChange={(val) => setVarcharN(val ?? 64)}
                  />
                </Col>
                <Col>
                  <Text type="secondary">（默认 64）</Text>
                </Col>
              </Row>
            )}
            {baseType === 'DECIMAL' && (
              <Row gutter={8} align="middle">
                <Col>
                  <Text type="secondary">精度 p：</Text>
                </Col>
                <Col>
                  <InputNumber
                    min={1}
                    max={38}
                    value={decimalP}
                    onChange={(val) => setDecimalP(val ?? 10)}
                  />
                </Col>
                <Col>
                  <Text type="secondary">小数位 s：</Text>
                </Col>
                <Col>
                  <InputNumber
                    min={0}
                    max={decimalP}
                    value={decimalS}
                    onChange={(val) => setDecimalS(val ?? 2)}
                  />
                </Col>
              </Row>
            )}
          </Form.Item>

          <Form.Item
            name="defaultValue"
            label="默认值"
            rules={[{ required: true, message: '默认值必填，可填空字符串 ""，但必须明确' }]}
            extra="新行写入时使用此默认值，历史行也会应用。必填。"
          >
            <Input placeholder='例：""（空字符串）或 "0"' />
          </Form.Item>
        </Form>
      )}
    </div>
  );

  const renderStep3 = () => (
    <div>
      <Title level={5} style={{ marginBottom: 16 }}>填写字段重要性</Title>
      <Form form={form} layout="vertical">
        <Form.Item
          name="importance"
          label="重要性等级"
          initialValue="NORMAL"
          rules={[{ required: true, message: '请选择重要性等级' }]}
        >
          <Radio.Group>
            <Space direction="vertical">
              <Radio value="CRITICAL">
                <Space>
                  <Text strong style={{ color: '#cf1322' }}>关键（CRITICAL）</Text>
                  <Text type="secondary">影响核心业务流程，变更需严格审批</Text>
                </Space>
              </Radio>
              <Radio value="IMPORTANT">
                <Space>
                  <Text strong style={{ color: '#d46b08' }}>重要（IMPORTANT）</Text>
                  <Text type="secondary">业务辅助字段，关注但不阻断流程</Text>
                </Space>
              </Radio>
              <Radio value="NORMAL">
                <Space>
                  <Text strong>普通（NORMAL）</Text>
                  <Text type="secondary">信息性字段，低风险</Text>
                </Space>
              </Radio>
            </Space>
          </Radio.Group>
        </Form.Item>

        <Form.Item
          name="affectsCalculation"
          label="影响计算"
          valuePropName="checked"
          initialValue={false}
          extra="开启后，该字段变更将触发相关报价单重新计算"
        >
          <Switch checkedChildren="是" unCheckedChildren="否" />
        </Form.Item>

        <Form.Item name="remark" label="备注说明">
          <TextArea rows={3} maxLength={500} showCount placeholder="可选，说明扩列用途" />
        </Form.Item>
      </Form>
    </div>
  );

  const renderStep4 = () => {
    const values = form.getFieldsValue();
    return (
      <div>
        <Title level={5} style={{ marginBottom: 16 }}>预览 + 确认执行</Title>
        <Alert
          type="warning"
          icon={<ExclamationCircleOutlined />}
          showIcon
          message={
            <Text strong style={{ color: '#d4380d' }}>
              此操作不可逆，DDL 变更将立即生效，建议在业务低峰期执行
            </Text>
          }
          style={{ marginBottom: 20 }}
        />
        <div style={{ marginBottom: 16 }}>
          <Text type="secondary">目标表：</Text>
          <Text code>{selectedTable}</Text>
          {'  '}
          <Text type="secondary">字段：</Text>
          <Text code>{values.columnName}</Text>
          {'  '}
          <Text type="secondary">类型：</Text>
          <Text code>{buildDataType()}</Text>
        </div>
        <div style={{ marginBottom: 8 }}>
          <Text strong>生成的 Migration SQL：</Text>
        </div>
        <div
          style={{
            background: '#1a1a1a',
            color: '#d4d4d4',
            padding: '12px 16px',
            borderRadius: 6,
            fontFamily: 'monospace',
            fontSize: 13,
            whiteSpace: 'pre',
            overflowX: 'auto',
            marginBottom: 20,
          }}
        >
          {previewSql}
        </div>
        <Divider />
        <Text type="secondary" style={{ fontSize: 12 }}>
          点击"执行扩列"后将在数据库中执行上述 DDL，并记录到 ddl_operation_history 表。
          如执行失败，历史记录中将标记 FAILED 状态并保存错误信息。
        </Text>
      </div>
    );
  };

  const steps = [
    { title: '选目标表' },
    { title: '字段定义' },
    { title: '重要性' },
    { title: '预览确认' },
  ];

  const renderFooter = () => {
    if (currentStep === 0) {
      return (
        <Space>
          <Button onClick={handleClose}>取消</Button>
          <Button type="primary" onClick={handleStep1Next} disabled={!selectedTable}>
            下一步
          </Button>
        </Space>
      );
    }
    if (currentStep === 1) {
      return (
        <Space>
          <Button onClick={() => setCurrentStep(0)}>上一步</Button>
          <Button type="primary" onClick={handleStep2Next}>
            下一步
          </Button>
        </Space>
      );
    }
    if (currentStep === 2) {
      return (
        <Space>
          <Button onClick={() => setCurrentStep(1)}>上一步</Button>
          <Button type="primary" onClick={handleStep3Next}>
            下一步
          </Button>
        </Space>
      );
    }
    // 步骤 4
    return (
      <Space>
        <Button onClick={() => setCurrentStep(2)}>上一步</Button>
        <Popconfirm
          title="确认执行扩列？"
          description={
            <span style={{ color: '#cf1322' }}>
              此操作不可逆，建议低峰期执行。确认继续？
            </span>
          }
          okText="确认执行"
          cancelText="取消"
          okButtonProps={{ danger: true }}
          onConfirm={handleSubmit}
          placement="topRight"
        >
          <Button type="primary" danger loading={submitting}>
            执行扩列
          </Button>
        </Popconfirm>
      </Space>
    );
  };

  return (
    <Drawer
      title="新建 DDL 扩列"
      placement="right"
      width={720}
      open={open}
      onClose={handleClose}
      maskClosable={false}
      footer={
        <div style={{ textAlign: 'right', padding: '8px 0' }}>
          {renderFooter()}
        </div>
      }
    >
      <Steps
        current={currentStep}
        items={steps}
        style={{ marginBottom: 32 }}
        size="small"
      />
      {currentStep === 0 && renderStep1()}
      {currentStep === 1 && renderStep2()}
      {currentStep === 2 && renderStep3()}
      {currentStep === 3 && renderStep4()}
    </Drawer>
  );
};

export default DdlExtensionWizardDrawer;
