/**
 * MappingEditor v2 — simplified column-to-column mapping
 *
 * Left:  CPQ template's excel_view_config columns (PRODUCT_ATTRIBUTE / COMPONENT_FIELD only)
 * Right: Customer Excel template columns (excel_columns)
 *
 * EXCEL_FORMULA and FIXED_VALUE view columns are shown as "自动" — no mapping needed.
 */
import React, { useEffect, useState } from 'react';
import {
  Card, Form, Input, Select, Button, Space, message, Typography, Divider, Spin, Tag,
} from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { importMappingService } from '../../services/importMappingService';
import { excelTemplateService } from '../../services/excelTemplateService';
import { templateService } from '../../services/templateService';

const { Title, Text } = Typography;

interface ViewColumn {
  col_key: string;
  title: string;
  source_type: 'PRODUCT_ATTRIBUTE' | 'COMPONENT_FIELD' | 'EXCEL_FORMULA' | 'FIXED_VALUE';
  value: any;
}

interface ColumnMapping {
  excel_column: string;      // customer Excel column name
  target_view_column: string; // col_key in the view config
}

const WRITABLE_SOURCE_TYPES = new Set(['PRODUCT_ATTRIBUTE', 'COMPONENT_FIELD']);

const SOURCE_TYPE_LABELS: Record<string, string> = {
  PRODUCT_ATTRIBUTE: '产品属性',
  COMPONENT_FIELD: '组件字段',
  EXCEL_FORMULA: 'Excel公式',
  FIXED_VALUE: '固定值',
};

const SOURCE_TYPE_COLORS: Record<string, string> = {
  PRODUCT_ATTRIBUTE: 'blue',
  COMPONENT_FIELD: 'green',
  EXCEL_FORMULA: 'orange',
  FIXED_VALUE: 'default',
};

function parseJson<T>(val: any, fallback: T): T {
  if (!val) return fallback;
  if (typeof val === 'string') {
    try { return JSON.parse(val); } catch { return fallback; }
  }
  return val;
}

const MappingEditor: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const isNew = !id || id === 'new';
  const excelTemplateIdFromUrl = searchParams.get('excelTemplateId') || '';

  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const [excelTemplate, setExcelTemplate] = useState<any>(null);
  const [excelColumns, setExcelColumns] = useState<string[]>([]);

  // Only templates that have excelViewConfig configured
  const [cpqTemplates, setCpqTemplates] = useState<any[]>([]);
  const [viewColumns, setViewColumns] = useState<ViewColumn[]>([]);
  const [cpqTemplateLoading, setCpqTemplateLoading] = useState(false);

  // column_mappings: one entry per writable view column
  const [columnMappings, setColumnMappings] = useState<ColumnMapping[]>([]);

  useEffect(() => {
    loadCpqTemplates();
    if (excelTemplateIdFromUrl) loadExcelTemplate(excelTemplateIdFromUrl);
    if (!isNew) loadExistingMapping();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadCpqTemplates = async () => {
    try {
      const res = await templateService.list({ size: 200 });
      const all: any[] = res.data?.content || res.data || [];
      // Only show published templates (or drafts) that have excelViewConfig
      // We show all and let the user pick; we'll filter on select to only those with excelViewConfig
      setCpqTemplates(all.filter((t: any) => t.status === 'PUBLISHED' || t.status === 'DRAFT'));
    } catch {
      // silently fail
    }
  };

  const loadExcelTemplate = async (tid: string) => {
    try {
      const res = await excelTemplateService.getById(tid);
      const tpl = res.data || res;
      setExcelTemplate(tpl);
      const cols: string[] = parseJson(tpl.excelColumns, []);
      setExcelColumns(cols);
    } catch (e: any) {
      message.error('加载Excel模板失败: ' + e.message);
    }
  };

  const loadExistingMapping = async () => {
    if (!id || isNew) return;
    setLoading(true);
    try {
      const res = await importMappingService.getById(id);
      const record = res.data || res;
      form.setFieldsValue({ name: record.name, cpqTemplateId: record.cpqTemplateId });
      if (record.excelTemplateId) await loadExcelTemplate(record.excelTemplateId);
      if (record.cpqTemplateId) await loadCpqTemplateViewConfig(record.cpqTemplateId);
      // Restore saved column_mappings
      if (record.columnMappings) {
        setColumnMappings(parseJson(record.columnMappings, []));
      }
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  const loadCpqTemplateViewConfig = async (templateId: string) => {
    setCpqTemplateLoading(true);
    try {
      // Try to load the excel_view_config from the template
      let viewCols: ViewColumn[] = [];
      try {
        const cfgRes = await templateService.getExcelViewConfig(templateId);
        const cfg = cfgRes.data || cfgRes;
        viewCols = parseJson(cfg, { columns: [] }).columns || [];
      } catch {
        // If the endpoint doesn't exist yet, try to read from the template itself
        const res = await templateService.getById(templateId);
        const tpl = res.data || res;
        const cfg = parseJson(tpl.excelViewConfig || tpl.excel_view_config, { columns: [] });
        viewCols = cfg.columns || [];
      }

      setViewColumns(viewCols);

      // Initialize column_mappings for writable columns
      setColumnMappings(
        viewCols
          .filter(c => WRITABLE_SOURCE_TYPES.has(c.source_type))
          .map(c => ({ excel_column: '', target_view_column: c.col_key }))
      );
    } catch (e: any) {
      message.error('加载CPQ模板视图配置失败: ' + e.message);
    } finally {
      setCpqTemplateLoading(false);
    }
  };

  const handleCpqTemplateChange = (templateId: string) => {
    loadCpqTemplateViewConfig(templateId);
  };

  const setMappingForColumn = (colKey: string, excelColumn: string) => {
    setColumnMappings(prev =>
      prev.map(m => m.target_view_column === colKey ? { ...m, excel_column: excelColumn } : m)
    );
  };

  const handleSave = async () => {
    try {
      await form.validateFields();
      const values = form.getFieldsValue();
      const excelTemplateId = isNew ? excelTemplateIdFromUrl : excelTemplate?.id;
      // Only save mappings that have a value
      const finalMappings = columnMappings.filter(m => m.excel_column);
      const payload = {
        ...values,
        excelTemplateId,
        columnMappings: finalMappings,
        // Legacy fields kept empty for backward-compat
        productAttrMappings: [],
        componentMappings: [],
      };
      setSaving(true);
      if (isNew) {
        await importMappingService.create(payload);
        message.success('创建成功');
      } else {
        await importMappingService.update(id!, payload);
        message.success('更新成功');
      }
      navigate('/import-config');
    } catch (e: any) {
      if (e.message) message.error(e.message);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 60 }}>
        <Spin size="large" />
      </div>
    );
  }

  const writableColumns = viewColumns.filter(c => WRITABLE_SOURCE_TYPES.has(c.source_type));
  const autoColumns = viewColumns.filter(c => !WRITABLE_SOURCE_TYPES.has(c.source_type));

  return (
    <Card
      title={isNew ? '新建映射配置 (v2)' : '编辑映射配置 (v2)'}
      extra={
        <Space>
          <Button onClick={() => navigate('/import-config')}>取消</Button>
          <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
            保存
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" style={{ maxWidth: 600 }}>
        <Form.Item name="name" label="映射名称" rules={[{ required: true, message: '请输入映射名称' }]}>
          <Input />
        </Form.Item>
        {excelTemplate && (
          <Form.Item label="客户Excel模板">
            <Input value={excelTemplate.name} disabled />
          </Form.Item>
        )}
        <Form.Item name="cpqTemplateId" label="CPQ产品模板" rules={[{ required: true, message: '请选择CPQ模板' }]}>
          <Select
            placeholder="选择CPQ产品模板（需已配置Excel视图）"
            onChange={handleCpqTemplateChange}
            showSearch
            filterOption={(input, option) =>
              String(option?.children || option?.label || '').toLowerCase().includes(input.toLowerCase())
            }
          >
            {cpqTemplates.map((t: any) => (
              <Select.Option key={t.id} value={t.id}>
                {t.name}
                {t.excelViewConfig ? (
                  <Tag color="green" style={{ marginLeft: 8, fontSize: 10 }}>已配置视图</Tag>
                ) : null}
              </Select.Option>
            ))}
          </Select>
        </Form.Item>
      </Form>

      {cpqTemplateLoading && (
        <div style={{ padding: 24, textAlign: 'center' }}>
          <Spin /> <span style={{ marginLeft: 8 }}>加载模板Excel视图配置...</span>
        </div>
      )}

      {viewColumns.length > 0 && !cpqTemplateLoading && (
        <>
          <Divider />
          <Title level={5} style={{ marginBottom: 16 }}>列映射配置</Title>

          {viewColumns.length === 0 && (
            <Text type="secondary">该CPQ模板未配置Excel视图，请先在模板配置页设置Excel视图列</Text>
          )}

          {/* Mapping table */}
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr style={{ background: '#fafafa', borderBottom: '2px solid #e8e8e8' }}>
                  <th style={thStyle}>CPQ模板Excel视图列</th>
                  <th style={{ ...thStyle, width: 80, textAlign: 'center', color: '#999' }}>← 映射 ←</th>
                  <th style={thStyle}>客户Excel列</th>
                </tr>
              </thead>
              <tbody>
                {writableColumns.map(col => {
                  const mapping = columnMappings.find(m => m.target_view_column === col.col_key);
                  return (
                    <tr key={col.col_key} style={{ borderBottom: '1px solid #f0f0f0' }}>
                      <td style={tdStyle}>
                        <Space>
                          <Tag color="purple" style={{ fontFamily: 'monospace', fontWeight: 600 }}>{col.col_key}</Tag>
                          <span style={{ fontWeight: 500 }}>{col.title || col.col_key}</span>
                          <Tag color={SOURCE_TYPE_COLORS[col.source_type]} style={{ fontSize: 11 }}>
                            {SOURCE_TYPE_LABELS[col.source_type]}
                          </Tag>
                        </Space>
                      </td>
                      <td style={{ ...tdStyle, textAlign: 'center', color: '#1890ff', fontWeight: 600 }}>
                        →
                      </td>
                      <td style={tdStyle}>
                        <Select
                          size="small"
                          style={{ width: 260 }}
                          placeholder="选择客户Excel列"
                          allowClear
                          value={mapping?.excel_column || undefined}
                          onChange={v => setMappingForColumn(col.col_key, v || '')}
                          options={excelColumns.map(c => ({ label: c, value: c }))}
                          showSearch
                          filterOption={(input, opt) =>
                            String(opt?.label || '').toLowerCase().includes(input.toLowerCase())
                          }
                        />
                      </td>
                    </tr>
                  );
                })}

                {autoColumns.map(col => (
                  <tr key={col.col_key} style={{ borderBottom: '1px solid #f0f0f0', opacity: 0.6 }}>
                    <td style={tdStyle}>
                      <Space>
                        <Tag color="purple" style={{ fontFamily: 'monospace', fontWeight: 600 }}>{col.col_key}</Tag>
                        <span style={{ fontWeight: 500 }}>{col.title || col.col_key}</span>
                        <Tag color={SOURCE_TYPE_COLORS[col.source_type]} style={{ fontSize: 11 }}>
                          {SOURCE_TYPE_LABELS[col.source_type]}
                        </Tag>
                      </Space>
                    </td>
                    <td style={{ ...tdStyle, textAlign: 'center' }}>
                      <Tag color="default">自动</Tag>
                    </td>
                    <td style={tdStyle}>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {col.source_type === 'EXCEL_FORMULA' ? '(公式自动计算)' : '(固定值)'}
                      </Text>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {writableColumns.length === 0 && (
            <div style={{ padding: '16px', color: '#999', textAlign: 'center' }}>
              该模板的Excel视图中没有可映射的列（产品属性/组件字段）
            </div>
          )}
        </>
      )}

      {!cpqTemplateLoading && viewColumns.length === 0 && form.getFieldValue('cpqTemplateId') && (
        <div style={{
          marginTop: 16, padding: 16, background: '#fffbe6', border: '1px solid #ffe58f',
          borderRadius: 6, color: '#d48806',
        }}>
          该CPQ模板尚未配置Excel视图列，请先在模板配置页的"Excel视图"标签页中设置列配置。
        </div>
      )}
    </Card>
  );
};

const thStyle: React.CSSProperties = {
  padding: '10px 12px',
  textAlign: 'left',
  fontWeight: 600,
  fontSize: 13,
  color: '#595959',
};

const tdStyle: React.CSSProperties = {
  padding: '10px 12px',
  verticalAlign: 'middle',
};

export default MappingEditor;
