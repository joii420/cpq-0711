import React, { useState, useEffect } from 'react';
import {
  Button, Select, Input, InputNumber, Space, message, Typography, Tag, Tooltip, Upload, Divider,
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, ArrowUpOutlined, ArrowDownOutlined, SaveOutlined,
  UploadOutlined, InboxOutlined,
} from '@ant-design/icons';
import { templateService } from '../../services/templateService';
import { customerService } from '../../services/customerService';

const { Text } = Typography;

type SourceType = 'PRODUCT_ATTRIBUTE' | 'COMPONENT_FIELD' | 'EXCEL_FORMULA' | 'FIXED_VALUE';

interface ExcelViewColumn {
  col_key: string;
  title: string;
  source_type: SourceType;
  source_name?: string;
  value?: string | { component_code: string; field_name: string; row_index: number };
}

interface ImportSettings {
  header_row_index: number;
  data_start_row_index: number;
  sheet_index: number;
  part_no_column_key?: string;
  sample_file_name?: string;
}

interface ExcelViewConfig {
  customer_id?: string;
  import_settings?: ImportSettings;
  columns: ExcelViewColumn[];
}

interface Props {
  templateId: string;
  isDraft: boolean;
  excelViewConfig: any;
  onChange: (config: any) => void;
  productAttributes: any[];
  componentsSnapshot: any[];
}

const COL_KEYS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');

/** Convert 0-based index to Excel column letter (0=A, 25=Z, 26=AA, 27=AB, ...) */
function indexToColKey(i: number): string {
  let result = '';
  let n = i;
  while (n >= 0) {
    result = String.fromCharCode(65 + (n % 26)) + result;
    n = Math.floor(n / 26) - 1;
  }
  return result;
}

const SOURCE_TYPE_OPTIONS: { label: string; value: SourceType }[] = [
  { label: '产品属性', value: 'PRODUCT_ATTRIBUTE' },
  { label: '组件字段', value: 'COMPONENT_FIELD' },
  { label: 'Excel公式', value: 'EXCEL_FORMULA' },
  { label: '固定值', value: 'FIXED_VALUE' },
];

function getNextColKey(columns: ExcelViewColumn[]): string {
  const used = new Set(columns.map(c => c.col_key));
  for (const k of COL_KEYS) {
    if (!used.has(k)) return k;
  }
  for (const k1 of COL_KEYS) {
    for (const k2 of COL_KEYS) {
      const key = k1 + k2;
      if (!used.has(key)) return key;
    }
  }
  return 'ZZ';
}

function parseConfig(raw: any): ExcelViewConfig {
  if (!raw) return { columns: [] };
  if (typeof raw === 'string') {
    try { return JSON.parse(raw); } catch { return { columns: [] }; }
  }
  return raw as ExcelViewConfig;
}

function buildComponentFieldOptions(componentsSnapshot: any[]): { label: string; value: string; raw: { component_code: string; field_name: string; row_index: number } }[] {
  const opts: { label: string; value: string; raw: { component_code: string; field_name: string; row_index: number } }[] = [];
  for (const comp of componentsSnapshot) {
    const tabName = comp.tab_name || comp.tabName || comp.name || '';
    const compCode = comp.component_code || comp.componentCode || comp.id || '';
    const fields = comp.fields || [];
    const rows = comp.rows || comp.presetRows || [];
    const rowCount = Math.max(1, rows.length);

    for (const field of fields) {
      const fType = (field.field_type || field.fieldType || '').toUpperCase();
      if (!['INPUT', 'FIXED_VALUE', 'INPUT_TEXT', 'INPUT_NUMBER'].includes(fType)) continue;
      const fName = field.name || field.key || '';
      for (let ri = 0; ri < rowCount; ri++) {
        const label = `${tabName}·${fName}·行${ri + 1}`;
        const rawVal = { component_code: compCode, field_name: fName, row_index: ri };
        opts.push({ label, value: JSON.stringify(rawVal), raw: rawVal });
      }
    }
  }
  return opts;
}

const ExcelViewConfigTab: React.FC<Props> = ({
  templateId,
  isDraft,
  excelViewConfig,
  onChange,
  productAttributes,
  componentsSnapshot,
}) => {
  const [config, setConfig] = useState<ExcelViewConfig>(() => parseConfig(excelViewConfig));
  const [saving, setSaving] = useState(false);
  const [customers, setCustomers] = useState<any[]>([]);
  const [customersLoading, setCustomersLoading] = useState(false);
  const [headerParsing, setHeaderParsing] = useState(false);

  // Sync external changes
  useEffect(() => {
    setConfig(parseConfig(excelViewConfig));
  }, [excelViewConfig]);

  // Load customers on mount
  useEffect(() => {
    setCustomersLoading(true);
    customerService.list({ size: 200 })
      .then(res => setCustomers(res.data?.content || []))
      .catch(() => { /* silently fail */ })
      .finally(() => setCustomersLoading(false));
  }, []);

  const componentFieldOptions = buildComponentFieldOptions(componentsSnapshot);

  const productAttrOptions = (productAttributes || []).map((a: any) => ({
    label: a.name || a.key || '',
    value: a.name || a.key || '',
  }));

  const importSettings: ImportSettings = config.import_settings || {
    header_row_index: 2,
    data_start_row_index: 3,
    sheet_index: 1,
  };

  const updateImportSettings = (patch: Partial<ImportSettings>) => {
    setConfig(prev => ({
      ...prev,
      import_settings: { ...importSettings, ...patch },
    }));
  };

  const updateColumn = (index: number, patch: Partial<ExcelViewColumn>) => {
    setConfig(prev => {
      const cols = prev.columns.map((c, i) => i === index ? { ...c, ...patch } : c);
      return { ...prev, columns: cols };
    });
  };

  const addColumn = () => {
    setConfig(prev => {
      const newCol: ExcelViewColumn = {
        col_key: getNextColKey(prev.columns),
        title: '',
        source_type: 'PRODUCT_ATTRIBUTE',
        source_name: '',
      };
      return { ...prev, columns: [...prev.columns, newCol] };
    });
  };

  const removeColumn = (index: number) => {
    setConfig(prev => ({
      ...prev,
      columns: prev.columns.filter((_, i) => i !== index),
    }));
  };

  const moveColumn = (index: number, direction: 'up' | 'down') => {
    setConfig(prev => {
      const cols = [...prev.columns];
      const target = direction === 'up' ? index - 1 : index + 1;
      if (target < 0 || target >= cols.length) return prev;
      [cols[index], cols[target]] = [cols[target], cols[index]];
      return { ...prev, columns: cols };
    });
  };

  const handleUploadSample = async (file: File) => {
    setHeaderParsing(true);
    try {
      const res = await templateService.parseHeader(
        templateId,
        file,
        importSettings.sheet_index,
        importSettings.header_row_index,
      );
      const headers: string[] = res.data?.headers || res.data || [];
      if (!headers.length) {
        message.warning('未解析到列标题，请检查工作表序号和表头行号');
        return;
      }
      // Auto-generate columns from parsed headers
      const newColumns: ExcelViewColumn[] = headers.map((title, i) => ({
        col_key: indexToColKey(i),
        title,
        source_type: 'PRODUCT_ATTRIBUTE' as SourceType,
        source_name: '',
      }));
      setConfig(prev => ({
        ...prev,
        import_settings: {
          ...importSettings,
          sample_file_name: file.name,
        },
        columns: newColumns,
      }));
      message.success(`已解析 ${headers.length} 列，列配置已自动填充`);
    } catch (e: any) {
      message.error('解析Excel表头失败: ' + (e.message || '未知错误'));
    } finally {
      setHeaderParsing(false);
    }
    return false; // Prevent antd Upload default behavior
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      const fullConfig: ExcelViewConfig = {
        ...config,
        import_settings: importSettings,
      };
      await templateService.updateExcelViewConfig(templateId, fullConfig);
      onChange(fullConfig);
      message.success('Excel视图配置已保存');
    } catch (e: any) {
      message.error(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  // Only PRODUCT_ATTRIBUTE columns as options for part_no column selector
  const productAttrColOptions = config.columns
    .filter(c => c.source_type === 'PRODUCT_ATTRIBUTE')
    .map(c => ({ label: `${c.col_key} - ${c.title || c.source_name || '(无标题)'}`, value: c.col_key }));

  const renderValueEditor = (col: ExcelViewColumn, index: number) => {
    switch (col.source_type) {
      case 'PRODUCT_ATTRIBUTE':
        return (
          <Select
            size="small"
            style={{ width: 200 }}
            value={col.source_name || undefined}
            placeholder="选择产品属性"
            options={productAttrOptions}
            onChange={v => updateColumn(index, { source_name: v })}
            disabled={!isDraft}
            showSearch
          />
        );
      case 'COMPONENT_FIELD': {
        const strVal = typeof col.value === 'object' ? JSON.stringify(col.value) : (col.value as string || undefined);
        return (
          <Select
            size="small"
            style={{ width: 280 }}
            value={strVal}
            placeholder="选择组件·字段·行"
            options={componentFieldOptions.map(o => ({ label: o.label, value: o.value }))}
            onChange={v => {
              try {
                const parsed = JSON.parse(v);
                updateColumn(index, { value: parsed });
              } catch {
                updateColumn(index, { value: v });
              }
            }}
            disabled={!isDraft}
            showSearch
            filterOption={(input, opt) =>
              String(opt?.label || '').toLowerCase().includes(input.toLowerCase())
            }
          />
        );
      }
      case 'EXCEL_FORMULA':
        return (
          <Input
            size="small"
            style={{ width: 200 }}
            value={typeof col.value === 'string' ? col.value : ''}
            placeholder="=B{row}*C{row}"
            onChange={e => updateColumn(index, { value: e.target.value })}
            disabled={!isDraft}
          />
        );
      case 'FIXED_VALUE':
        return (
          <Input
            size="small"
            style={{ width: 200 }}
            value={typeof col.value === 'string' ? col.value : ''}
            placeholder="输入固定值"
            onChange={e => updateColumn(index, { value: e.target.value })}
            disabled={!isDraft}
          />
        );
      default:
        return null;
    }
  };

  return (
    <div style={{ padding: '16px 0' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Typography.Title level={5} style={{ margin: 0 }}>Excel视图配置</Typography.Title>
        {isDraft && (
          <Button
            type="primary"
            size="small"
            icon={<SaveOutlined />}
            loading={saving}
            onClick={handleSave}
          >
            保存配置
          </Button>
        )}
      </div>

      {/* Section 1: Customer */}
      <div style={{ marginBottom: 16 }}>
        <div style={{ marginBottom: 6, fontWeight: 500 }}>关联客户</div>
        <Select
          style={{ width: 300 }}
          placeholder="请选择客户"
          loading={customersLoading}
          value={config.customer_id || undefined}
          onChange={v => setConfig(prev => ({ ...prev, customer_id: v }))}
          showSearch
          disabled={!isDraft}
          filterOption={(input, opt) =>
            String(opt?.label || '').toLowerCase().includes(input.toLowerCase())
          }
          options={customers.map((c: any) => ({ label: c.name, value: c.id }))}
        />
      </div>

      <Divider style={{ margin: '12px 0' }} />

      {/* Section 2: Import parameters */}
      <div style={{ marginBottom: 16 }}>
        <div style={{ marginBottom: 8, fontWeight: 500 }}>导入参数</div>
        <Space size={16} wrap>
          <div>
            <span style={{ marginRight: 8, fontSize: 13 }}>表头行号</span>
            <InputNumber
              size="small"
              min={1}
              value={importSettings.header_row_index}
              onChange={v => updateImportSettings({ header_row_index: v ?? 2 })}
              disabled={!isDraft}
              style={{ width: 70 }}
            />
          </div>
          <div>
            <span style={{ marginRight: 8, fontSize: 13 }}>数据起始行</span>
            <InputNumber
              size="small"
              min={1}
              value={importSettings.data_start_row_index}
              onChange={v => updateImportSettings({ data_start_row_index: v ?? 3 })}
              disabled={!isDraft}
              style={{ width: 70 }}
            />
          </div>
          <div>
            <span style={{ marginRight: 8, fontSize: 13 }}>工作表序号</span>
            <InputNumber
              size="small"
              min={1}
              value={importSettings.sheet_index}
              onChange={v => updateImportSettings({ sheet_index: v ?? 1 })}
              disabled={!isDraft}
              style={{ width: 70 }}
            />
          </div>
        </Space>
      </div>

      <Divider style={{ margin: '12px 0' }} />

      {/* Section 3: Upload sample Excel */}
      {isDraft && (
        <div style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 8, fontWeight: 500 }}>
            上传样例Excel
            <Text type="secondary" style={{ fontWeight: 400, marginLeft: 8, fontSize: 12 }}>
              上传后自动解析表头并填充列配置
            </Text>
          </div>
          <Upload
            accept=".xlsx,.xls"
            maxCount={1}
            showUploadList={false}
            beforeUpload={file => { handleUploadSample(file); return false; }}
          >
            <Button icon={<UploadOutlined />} loading={headerParsing}>
              {importSettings.sample_file_name
                ? `重新上传 (当前: ${importSettings.sample_file_name})`
                : '选择样例Excel文件'}
            </Button>
          </Upload>
          {importSettings.sample_file_name && (
            <div style={{ marginTop: 4, fontSize: 12, color: '#52c41a' }}>
              样例文件: {importSettings.sample_file_name}
            </div>
          )}
        </div>
      )}

      {/* Section 4: Part no column */}
      <div style={{ marginBottom: 16 }}>
        <div style={{ marginBottom: 6, fontWeight: 500 }}>
          客户料号列
          <Text type="secondary" style={{ fontWeight: 400, marginLeft: 8, fontSize: 12 }}>
            指定哪列包含客户料号（用于导入时匹配物料）
          </Text>
        </div>
        <Select
          style={{ width: 280 }}
          placeholder="从产品属性列中选择"
          value={importSettings.part_no_column_key || undefined}
          onChange={v => updateImportSettings({ part_no_column_key: v })}
          disabled={!isDraft}
          allowClear
          options={productAttrColOptions}
        />
      </div>

      <Divider style={{ margin: '12px 0' }} />

      {/* Section 5: Column config table */}
      <div style={{ marginBottom: 8, fontWeight: 500 }}>列配置表格</div>

      {config.columns.length === 0 ? (
        <div style={{ color: '#999', textAlign: 'center', padding: '32px 0', background: '#fafafa', borderRadius: 8, border: '1px dashed #d9d9d9' }}>
          <InboxOutlined style={{ fontSize: 24, marginBottom: 8, display: 'block' }} />
          <div style={{ marginBottom: 8 }}>暂无列配置</div>
          {isDraft && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              上传样例Excel自动生成，或点击下方"添加列"手动配置
            </Text>
          )}
        </div>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ background: '#fafafa', borderBottom: '1px solid #e8e8e8' }}>
                <th style={thStyle}>列键</th>
                <th style={thStyle}>列标题</th>
                <th style={thStyle}>数据来源</th>
                <th style={thStyle}>值/公式/映射目标</th>
                {isDraft && <th style={{ ...thStyle, width: 100 }}>操作</th>}
              </tr>
            </thead>
            <tbody>
              {config.columns.map((col, index) => (
                <tr key={`${col.col_key}-${index}`} style={{ borderBottom: '1px solid #f0f0f0' }}>
                  <td style={tdStyle}>
                    <Tag color="purple" style={{ fontFamily: 'monospace', fontWeight: 600 }}>{col.col_key}</Tag>
                  </td>
                  <td style={tdStyle}>
                    <Input
                      size="small"
                      style={{ width: 120 }}
                      value={col.title}
                      placeholder="列标题"
                      onChange={e => updateColumn(index, { title: e.target.value })}
                      disabled={!isDraft}
                    />
                  </td>
                  <td style={tdStyle}>
                    <Select
                      size="small"
                      style={{ width: 130 }}
                      value={col.source_type}
                      options={SOURCE_TYPE_OPTIONS}
                      onChange={v => updateColumn(index, { source_type: v as SourceType, value: '', source_name: '' })}
                      disabled={!isDraft}
                    />
                  </td>
                  <td style={tdStyle}>
                    {renderValueEditor(col, index)}
                  </td>
                  {isDraft && (
                    <td style={tdStyle}>
                      <Space size={4}>
                        <Tooltip title="上移">
                          <Button
                            type="text"
                            size="small"
                            icon={<ArrowUpOutlined />}
                            onClick={() => moveColumn(index, 'up')}
                            disabled={index === 0}
                          />
                        </Tooltip>
                        <Tooltip title="下移">
                          <Button
                            type="text"
                            size="small"
                            icon={<ArrowDownOutlined />}
                            onClick={() => moveColumn(index, 'down')}
                            disabled={index === config.columns.length - 1}
                          />
                        </Tooltip>
                        <Tooltip title="删除">
                          <Button
                            type="text"
                            size="small"
                            danger
                            icon={<DeleteOutlined />}
                            onClick={() => removeColumn(index)}
                          />
                        </Tooltip>
                      </Space>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {isDraft && (
        <Button
          type="dashed"
          size="small"
          icon={<PlusOutlined />}
          onClick={addColumn}
          style={{ marginTop: 12 }}
        >
          添加列
        </Button>
      )}

      {config.columns.length > 0 && (
        <div style={{ marginTop: 16, padding: '8px 12px', background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 6, fontSize: 12, color: '#52c41a' }}>
          <b>公式提示：</b>在Excel公式中使用 <code>{'{row}'}</code> 表示当前行号，例如 <code>=B{'{row}'}*C{'{row}'}</code>
        </div>
      )}
    </div>
  );
};

const thStyle: React.CSSProperties = {
  padding: '8px 12px',
  textAlign: 'left',
  fontWeight: 600,
  fontSize: 13,
  color: '#595959',
};

const tdStyle: React.CSSProperties = {
  padding: '8px 12px',
  verticalAlign: 'middle',
};

export default ExcelViewConfigTab;
