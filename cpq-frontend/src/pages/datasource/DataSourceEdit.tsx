import React, { useEffect, useState } from 'react';
import {
  Form, Input, Select, Button, Space, Table, Checkbox, Divider, Card,
  message, Spin, Alert, Typography, Row, Col, InputNumber
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, ArrowUpOutlined, ArrowDownOutlined, PlayCircleOutlined
} from '@ant-design/icons';
import { useNavigate, useParams, useLocation } from 'react-router-dom';
import { datasourceService } from '../../services/datasourceService';

const { TextArea } = Input;
const { Text } = Typography;

const SYSTEM_PARAM_CODES = [
  { label: '当前用户ID', value: 'CURRENT_USER_ID' },
  { label: '当前组织ID', value: 'CURRENT_ORG_ID' },
  { label: '当前日期', value: 'CURRENT_DATE' },
  { label: '当前年份', value: 'CURRENT_YEAR' },
];

interface ParamRow {
  key: string;
  paramCode: string;
  paramName: string;
  sourceType: string;
  systemParamCode?: string;
  isRequired: boolean;
  description?: string;
}

interface HeaderRow {
  key: string;
  headerKey: string;
  headerValue: string;
}

const DataSourceEdit: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const isNew = !id;
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dsType, setDsType] = useState<string>('SQL');
  const [params, setParams] = useState<ParamRow[]>([]);
  const [headers, setHeaders] = useState<HeaderRow[]>([]);
  const [testParams, setTestParams] = useState<Record<string, string>>({});
  const [testResult, setTestResult] = useState<any>(null);
  const [testing, setTesting] = useState(false);
  const [testPanelOpen, setTestPanelOpen] = useState(false);

  useEffect(() => {
    if (!isNew && id) {
      setLoading(true);
      datasourceService.getById(id).then((res: any) => {
        const ds = res.data;
        setDsType(ds.type);
        form.setFieldsValue({
          code: ds.code,
          name: ds.name,
          type: ds.type,
          status: ds.status,
          description: ds.description,
          sqlQuery: ds.sqlQuery,
          sqlResultColumn: ds.sqlResultColumn,
          apiUrl: ds.apiUrl,
          apiMethod: ds.apiMethod || 'GET',
          apiBodyTemplate: ds.apiBodyTemplate,
          apiResultPath: ds.apiResultPath,
          apiTimeoutSeconds: ds.apiTimeoutSeconds || 5,
        });

        // Parse headers from JSONB string
        if (ds.apiHeaders) {
          try {
            const parsed = JSON.parse(ds.apiHeaders);
            if (Array.isArray(parsed)) {
              setHeaders(parsed.map((h: any, i: number) => ({
                key: String(i),
                headerKey: h.key || '',
                headerValue: h.value || '',
              })));
            }
          } catch {}
        }

        // Set params
        if (ds.params && ds.params.length > 0) {
          setParams(ds.params.map((p: any, i: number) => ({
            key: p.id || String(i),
            paramCode: p.paramCode,
            paramName: p.paramName,
            sourceType: p.sourceType,
            systemParamCode: p.systemParamCode,
            isRequired: p.isRequired !== false,
            description: p.description,
          })));
        }

        // Auto-open test panel if navigated with state
        if ((location.state as any)?.openTest) {
          setTestPanelOpen(true);
        }
      }).catch((err: any) => {
        message.error(err?.response?.data?.message || '加载数据源失败');
      }).finally(() => setLoading(false));
    }
  }, [id, isNew]);

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);

      // Build headers JSON
      const headersJson = JSON.stringify(
        headers.filter(h => h.headerKey.trim()).map(h => ({ key: h.headerKey, value: h.headerValue }))
      );

      const payload = {
        ...values,
        apiHeaders: headersJson,
        params: params.map((p, i) => ({
          paramCode: p.paramCode,
          paramName: p.paramName,
          sourceType: p.sourceType,
          systemParamCode: p.systemParamCode,
          isRequired: p.isRequired,
          description: p.description,
        })),
      };

      if (isNew) {
        await datasourceService.create(payload);
        message.success('数据源创建成功');
      } else {
        await datasourceService.update(id!, payload);
        message.success('数据源更新成功');
      }
      navigate('/datasources');
    } catch (err: any) {
      if (err?.errorFields) return; // form validation error
      message.error(err?.response?.data?.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const addParam = () => {
    setParams(prev => [...prev, {
      key: Date.now().toString(),
      paramCode: '',
      paramName: '',
      sourceType: 'USER_FIELD',
      isRequired: true,
    }]);
  };

  const removeParam = (key: string) => {
    setParams(prev => prev.filter(p => p.key !== key));
  };

  const moveParam = (key: string, direction: 'up' | 'down') => {
    setParams(prev => {
      const idx = prev.findIndex(p => p.key === key);
      if (direction === 'up' && idx > 0) {
        const arr = [...prev];
        [arr[idx - 1], arr[idx]] = [arr[idx], arr[idx - 1]];
        return arr;
      }
      if (direction === 'down' && idx < prev.length - 1) {
        const arr = [...prev];
        [arr[idx], arr[idx + 1]] = [arr[idx + 1], arr[idx]];
        return arr;
      }
      return prev;
    });
  };

  const updateParam = (key: string, field: keyof ParamRow, value: any) => {
    setParams(prev => prev.map(p => p.key === key ? { ...p, [field]: value } : p));
  };

  const addHeader = () => {
    setHeaders(prev => [...prev, { key: Date.now().toString(), headerKey: '', headerValue: '' }]);
  };

  const removeHeader = (key: string) => {
    setHeaders(prev => prev.filter(h => h.key !== key));
  };

  const updateHeader = (key: string, field: 'headerKey' | 'headerValue', value: string) => {
    setHeaders(prev => prev.map(h => h.key === key ? { ...h, [field]: value } : h));
  };

  const handleTest = async () => {
    if (!id) { message.warning('请先保存数据源再进行测试'); return; }
    setTesting(true);
    setTestResult(null);
    try {
      const res = await datasourceService.test(id, { testParams });
      setTestResult(res.data);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '测试请求失败');
    } finally {
      setTesting(false);
    }
  };

  const paramColumns = [
    {
      title: '顺序',
      key: 'order',
      width: 80,
      render: (_: any, record: ParamRow) => (
        <Space>
          <Button size="small" icon={<ArrowUpOutlined />} onClick={() => moveParam(record.key, 'up')} />
          <Button size="small" icon={<ArrowDownOutlined />} onClick={() => moveParam(record.key, 'down')} />
        </Space>
      ),
    },
    {
      title: '参数编码',
      key: 'paramCode',
      render: (_: any, record: ParamRow) => (
        <Input
          size="small"
          value={record.paramCode}
          onChange={e => updateParam(record.key, 'paramCode', e.target.value)}
          placeholder="param_code"
        />
      ),
    },
    {
      title: '参数名称',
      key: 'paramName',
      render: (_: any, record: ParamRow) => (
        <Input
          size="small"
          value={record.paramName}
          onChange={e => updateParam(record.key, 'paramName', e.target.value)}
          placeholder="参数名称"
        />
      ),
    },
    {
      title: '来源类型',
      key: 'sourceType',
      width: 140,
      render: (_: any, record: ParamRow) => (
        <Select
          size="small"
          value={record.sourceType}
          onChange={val => updateParam(record.key, 'sourceType', val)}
          style={{ width: '100%' }}
          options={[
            { label: '用户字段', value: 'USER_FIELD' },
            { label: '系统参数', value: 'SYSTEM_PARAM' },
          ]}
        />
      ),
    },
    {
      title: '系统参数',
      key: 'systemParamCode',
      render: (_: any, record: ParamRow) => (
        record.sourceType === 'SYSTEM_PARAM' ? (
          <Select
            size="small"
            value={record.systemParamCode}
            onChange={val => updateParam(record.key, 'systemParamCode', val)}
            style={{ width: '100%' }}
            placeholder="选择系统参数"
            options={SYSTEM_PARAM_CODES}
          />
        ) : <Text type="secondary">-</Text>
      ),
    },
    {
      title: '必填',
      key: 'isRequired',
      width: 60,
      render: (_: any, record: ParamRow) => (
        <Checkbox
          checked={record.isRequired}
          onChange={e => updateParam(record.key, 'isRequired', e.target.checked)}
        />
      ),
    },
    {
      title: '描述',
      key: 'description',
      render: (_: any, record: ParamRow) => (
        <Input
          size="small"
          value={record.description}
          onChange={e => updateParam(record.key, 'description', e.target.value)}
          placeholder="可选描述"
        />
      ),
    },
    {
      title: '',
      key: 'remove',
      width: 50,
      render: (_: any, record: ParamRow) => (
        <Button
          size="small"
          danger
          icon={<DeleteOutlined />}
          onClick={() => removeParam(record.key)}
        />
      ),
    },
  ];

  if (loading) return <Spin style={{ display: 'block', marginTop: 48 }} />;

  return (
    <div style={{ maxWidth: 900 }}>
      <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 12 }}>
        <Button onClick={() => navigate('/datasources')}>返回列表</Button>
        <span style={{ fontSize: 18, fontWeight: 600 }}>
          {isNew ? '新建数据源' : '编辑数据源'}
        </span>
      </div>

      <Form form={form} layout="vertical">
        {/* Section 1: Basic Info */}
        <Card title="基本信息" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="code"
                label="编码"
                rules={[
                  { required: true, message: '请输入编码' },
                  { pattern: /^[A-Za-z0-9_-]+$/, message: '只能包含字母、数字、下划线和连字符' },
                ]}
              >
                <Input disabled={!isNew} placeholder="DS_CUSTOMER_PRICE" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
                <Input placeholder="数据源名称" />
              </Form.Item>
            </Col>
            <Col span={4}>
              <Form.Item name="type" label="类型" rules={[{ required: true }]} initialValue="SQL">
                <Select
                  disabled={!isNew}
                  onChange={(val) => setDsType(val)}
                  options={[
                    { label: 'SQL', value: 'SQL' },
                    { label: 'API', value: 'API' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={4}>
              <Form.Item name="status" label="状态" initialValue="ACTIVE">
                <Select
                  options={[
                    { label: '启用', value: 'ACTIVE' },
                    { label: '禁用', value: 'DISABLED' },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="可选描述" />
          </Form.Item>
        </Card>

        {/* Section 2: Query Config */}
        <Card title="查询配置" style={{ marginBottom: 16 }}>
          {dsType === 'SQL' ? (
            <>
              <Form.Item name="sqlQuery" label="SQL 查询">
                <TextArea
                  rows={5}
                  placeholder="SELECT column FROM table WHERE field = ?"
                  style={{ fontFamily: 'monospace' }}
                />
              </Form.Item>
              <Form.Item name="sqlResultColumn" label="结果列名">
                <Input placeholder="column_name（不填则取第一列）" style={{ width: 300 }} />
              </Form.Item>
            </>
          ) : (
            <>
              <Row gutter={16}>
                <Col span={16}>
                  <Form.Item name="apiUrl" label="请求 URL">
                    <Input placeholder="https://api.example.com/price?code={productCode}" />
                  </Form.Item>
                </Col>
                <Col span={4}>
                  <Form.Item name="apiMethod" label="请求方法" initialValue="GET">
                    <Select options={[
                      { label: 'GET', value: 'GET' },
                      { label: 'POST', value: 'POST' },
                    ]} />
                  </Form.Item>
                </Col>
                <Col span={4}>
                  <Form.Item name="apiTimeoutSeconds" label="超时(秒)" initialValue={5}>
                    <InputNumber min={1} max={60} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
              </Row>

              <Form.Item label="请求头">
                {headers.map((h, idx) => (
                  <Space key={h.key} style={{ display: 'flex', marginBottom: 8 }}>
                    <Input
                      value={h.headerKey}
                      onChange={e => updateHeader(h.key, 'headerKey', e.target.value)}
                      placeholder="Header-Name"
                      style={{ width: 200 }}
                    />
                    <Input
                      value={h.headerValue}
                      onChange={e => updateHeader(h.key, 'headerValue', e.target.value)}
                      placeholder="Value"
                      style={{ width: 300 }}
                    />
                    <Button
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() => removeHeader(h.key)}
                    />
                  </Space>
                ))}
                <Button size="small" icon={<PlusOutlined />} onClick={addHeader}>添加请求头</Button>
              </Form.Item>

              <Form.Item name="apiBodyTemplate" label="请求体模板">
                <TextArea
                  rows={4}
                  placeholder='{"productCode": "{productCode}"}'
                  style={{ fontFamily: 'monospace' }}
                />
              </Form.Item>

              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item name="apiResultPath" label="结果路径">
                    <Input placeholder="$.data.price" />
                  </Form.Item>
                </Col>
              </Row>
            </>
          )}
        </Card>

        {/* Section 3: Params */}
        <Card
          title="参数配置"
          style={{ marginBottom: 16 }}
          extra={
            <Button size="small" icon={<PlusOutlined />} onClick={addParam}>添加参数</Button>
          }
        >
          <Table
            rowKey="key"
            columns={paramColumns}
            dataSource={params}
            pagination={false}
            size="small"
            locale={{ emptyText: '暂无参数' }}
          />
        </Card>

        {/* Test Panel */}
        <Card
          title={
            <Space>
              <PlayCircleOutlined />
              测试面板
              <Button
                type="link"
                size="small"
                onClick={() => setTestPanelOpen(v => !v)}
              >
                {testPanelOpen ? '收起' : '展开'}
              </Button>
            </Space>
          }
          style={{ marginBottom: 16, display: isNew ? 'none' : 'block' }}
        >
          {testPanelOpen && (
            <>
              {params.filter(p => p.sourceType === 'USER_FIELD').map(p => (
                <Form.Item key={p.key} label={p.paramName || p.paramCode}>
                  <Input
                    placeholder={`输入 ${p.paramCode}`}
                    value={testParams[p.paramCode] || ''}
                    onChange={e => setTestParams(prev => ({ ...prev, [p.paramCode]: e.target.value }))}
                    style={{ width: 300 }}
                  />
                </Form.Item>
              ))}
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                loading={testing}
                onClick={handleTest}
              >
                执行测试
              </Button>
              {testResult && (
                <div style={{ marginTop: 16 }}>
                  <Alert
                    type={testResult.success ? 'success' : 'error'}
                    message={testResult.success ? '测试成功' : '测试失败'}
                    description={
                      <div>
                        {testResult.success ? (
                          <>
                            <div><strong>提取值：</strong>{testResult.extractedValue ?? '-'}</div>
                            <div><strong>原始响应：</strong><Text code>{testResult.rawResponse}</Text></div>
                          </>
                        ) : (
                          <div><strong>错误：</strong>{testResult.errorMessage}</div>
                        )}
                        <div style={{ marginTop: 8 }}>
                          <Text type="secondary">耗时：{testResult.executionTimeMs}ms</Text>
                        </div>
                      </div>
                    }
                  />
                </div>
              )}
            </>
          )}
        </Card>

        <Space>
          <Button type="primary" loading={saving} onClick={handleSave}>
            {isNew ? '创建' : '保存'}
          </Button>
          <Button onClick={() => navigate('/datasources')}>取消</Button>
        </Space>
      </Form>
    </div>
  );
};

export default DataSourceEdit;
