import React, { useState, useEffect } from 'react';
import {
  Button, Input, Space, message, Typography, Tag, Tooltip, Switch,
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, ArrowUpOutlined, ArrowDownOutlined, SaveOutlined,
} from '@ant-design/icons';
import { templateService } from '../../services/templateService';
import VariableLabelPickerDrawer from '../../components/VariableLabelPickerDrawer';
import { variableLabelService, type VariableLabel } from '../../services/variableLabelService';

const { Text } = Typography;

// V149 Stage 3+D 清理: Excel 视图配置只做"配 Excel 列引用视图数据"一件事.
// 老的 PRODUCT_ATTRIBUTE / COMPONENT_FIELD / EXCEL_FORMULA / FIXED_VALUE
// source_type 数据保留 (向后兼容历史报价模板) 但只读, 新增列默认走 VARIABLE.
type SourceType = 'VARIABLE' | 'PRODUCT_ATTRIBUTE' | 'COMPONENT_FIELD' | 'EXCEL_FORMULA' | 'FIXED_VALUE';

interface ExcelViewColumn {
  col_key: string;
  title: string;
  source_type: SourceType;
  variable_path?: string;
  hidden?: boolean;
  // 老字段, 仅做透传保存, UI 不编辑
  source_name?: string;
  value?: string | { component_code: string; field_name: string; row_index: number };
  col_name?: string;
  visible?: boolean;
  formula?: string;
  comparison_tag?: string;
}

interface Props {
  templateId: string;
  isDraft: boolean;
  excelViewConfig: any;
  onChange: (config: any) => void;
  // 老 props (Caller 仍传, 不再使用)
  productAttributes?: any[];
  componentsSnapshot?: any[];
}

const COL_KEYS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');

function indexToColKey(i: number): string {
  let result = '';
  let n = i;
  while (n >= 0) {
    result = String.fromCharCode(65 + (n % 26)) + result;
    n = Math.floor(n / 26) - 1;
  }
  return result;
}

function getNextColKey(columns: ExcelViewColumn[]): string {
  const used = new Set(columns.map(c => c.col_key));
  let i = 0;
  while (true) {
    const k = indexToColKey(i);
    if (!used.has(k)) return k;
    i++;
    if (i > 700) return 'ZZ';
  }
}

/** 兼容 V150 前后的数据格式 — string / array / object 三种 */
function parseColumns(raw: any): ExcelViewColumn[] {
  if (!raw) return [];
  let parsed: any = raw;
  if (typeof raw === 'string') {
    try { parsed = JSON.parse(raw); } catch { return []; }
  }
  if (!parsed) return [];
  if (Array.isArray(parsed)) return parsed;
  if (Array.isArray(parsed.columns)) return parsed.columns;
  return [];
}

const LEGACY_TYPE_LABEL: Record<string, string> = {
  PRODUCT_ATTRIBUTE: '产品属性',
  COMPONENT_FIELD: '组件字段',
  EXCEL_FORMULA: 'Excel公式',
  FIXED_VALUE: '固定值',
};

const ExcelViewConfigTab: React.FC<Props> = ({ templateId, isDraft, excelViewConfig, onChange }) => {
  const [columns, setColumns] = useState<ExcelViewColumn[]>(() => parseColumns(excelViewConfig));
  const [saving, setSaving] = useState(false);
  const [labelMap, setLabelMap] = useState<Record<string, VariableLabel>>({});
  const [labelPickerOpen, setLabelPickerOpen] = useState(false);
  const [labelPickerColIdx, setLabelPickerColIdx] = useState<number | null>(null);

  // 父组件 excelViewConfig 变更时同步
  useEffect(() => {
    setColumns(parseColumns(excelViewConfig));
  }, [excelViewConfig]);

  // 拉 V149 字段库, 用于显示已注册中文名
  useEffect(() => {
    variableLabelService.list().then(list => {
      const m: Record<string, VariableLabel> = {};
      for (const v of list) m[v.variablePath] = v;
      setLabelMap(m);
    });
  }, []);

  const updateColumn = (index: number, patch: Partial<ExcelViewColumn>) => {
    setColumns(prev => prev.map((c, i) => (i === index ? { ...c, ...patch } : c)));
  };

  const addColumn = () => {
    setColumns(prev => [
      ...prev,
      { col_key: getNextColKey(prev), title: '新列', source_type: 'VARIABLE', variable_path: '' },
    ]);
  };

  const removeColumn = (index: number) => {
    setColumns(prev => prev.filter((_, i) => i !== index));
  };

  const moveColumn = (index: number, dir: 'up' | 'down') => {
    setColumns(prev => {
      const next = [...prev];
      const target = dir === 'up' ? index - 1 : index + 1;
      if (target < 0 || target >= next.length) return prev;
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  };

  const openLabelPicker = (idx: number) => {
    setLabelPickerColIdx(idx);
    setLabelPickerOpen(true);
  };

  const handleLabelPick = (path: string, label: VariableLabel) => {
    if (labelPickerColIdx != null) {
      const c = columns[labelPickerColIdx];
      const newTitle = !c.title || c.title === '新列' ? label.displayName : c.title;
      updateColumn(labelPickerColIdx, { variable_path: path, title: newTitle, source_type: 'VARIABLE' });
    }
    setLabelPickerOpen(false);
    setLabelPickerColIdx(null);
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      await templateService.updateExcelViewConfig(templateId, columns);
      onChange(columns);
      message.success('Excel 视图配置已保存');
    } catch (e: any) {
      message.error(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const renderValueCell = (col: ExcelViewColumn, index: number) => {
    if (col.source_type === 'VARIABLE') {
      const path = col.variable_path || '';
      const label = path ? labelMap[path] : undefined;
      return (
        <div style={{ width: '100%' }}>
          <Space.Compact style={{ width: '100%' }}>
            <Input
              size="small"
              readOnly
              value={label ? label.displayName : path}
              placeholder="点击右侧 📚 从字段库选"
              title={path || undefined}
              disabled={!isDraft}
              onClick={() => isDraft && openLabelPicker(index)}
              style={{
                cursor: isDraft ? 'pointer' : 'not-allowed',
                fontFamily: label ? undefined : 'Consolas, Monaco, monospace',
                fontWeight: label ? 500 : undefined,
              }}
            />
            <Button size="small" disabled={!isDraft} onClick={() => openLabelPicker(index)}>
              📚 选
            </Button>
          </Space.Compact>
          {(label || path) && (
            <div style={{ marginTop: 4, fontSize: 11, lineHeight: '18px' }}>
              {label && (label.dataType || label.unit) && (
                <Tag color="blue" style={{ marginRight: 6 }}>
                  {label.dataType ?? ''}{label.unit ? ` ${label.unit}` : ''}
                </Tag>
              )}
              {path && (
                <span style={{ color: '#bbb', fontFamily: 'Consolas, Monaco, monospace', fontSize: 10 }}>
                  {path}
                </span>
              )}
            </div>
          )}
        </div>
      );
    }
    // 老的 4 种 source_type — 只读展示, 用户可改 col_key/title/删除, 不可编辑值
    return (
      <Space direction="vertical" size={2} style={{ fontSize: 12 }}>
        <Tag color="default">{LEGACY_TYPE_LABEL[col.source_type] || col.source_type}</Tag>
        <Text type="secondary" style={{ fontSize: 11 }}>
          老格式列 · 数据保留 · 如需改请删除后新增
        </Text>
      </Space>
    );
  };

  return (
    <div style={{ padding: '16px 0' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Typography.Title level={5} style={{ margin: 0 }}>Excel 视图配置</Typography.Title>
          <Text type="secondary" style={{ fontSize: 12 }}>
            配置 Excel 导出时的列结构,引用 V149 字段库的已命名视图列
          </Text>
        </div>
        {isDraft && (
          <Button type="primary" size="small" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
            保存配置
          </Button>
        )}
      </div>

      {columns.length === 0 ? (
        <div style={{ padding: 24, textAlign: 'center', color: '#999', background: '#fafafa', borderRadius: 6 }}>
          暂无列配置,点击下方按钮添加第一列
        </div>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead style={{ background: '#fafafa' }}>
              <tr>
                <th style={thStyle}>列 Key</th>
                <th style={thStyle}>列标题</th>
                <th style={thStyle}>值 / 字段引用</th>
                <th style={{ ...thStyle, width: 80, textAlign: 'center' }}>隐藏</th>
                {isDraft && <th style={{ ...thStyle, width: 120 }}>操作</th>}
              </tr>
            </thead>
            <tbody>
              {columns.map((col, index) => (
                <tr key={index} style={{ borderBottom: '1px solid #f0f0f0' }}>
                  <td style={tdStyle}>
                    <Input
                      size="small"
                      style={{ width: 90 }}
                      value={col.col_key}
                      onChange={e => updateColumn(index, { col_key: e.target.value })}
                      disabled={!isDraft}
                    />
                  </td>
                  <td style={tdStyle}>
                    <Input
                      size="small"
                      style={{ width: 180 }}
                      value={col.title}
                      onChange={e => updateColumn(index, { title: e.target.value })}
                      disabled={!isDraft}
                    />
                  </td>
                  <td style={tdStyle}>{renderValueCell(col, index)}</td>
                  <td style={{ ...tdStyle, textAlign: 'center' }}>
                    <Switch
                      size="small"
                      checked={!!col.hidden}
                      disabled={!isDraft}
                      onChange={v => updateColumn(index, { hidden: v })}
                    />
                  </td>
                  {isDraft && (
                    <td style={tdStyle}>
                      <Space size={4}>
                        <Tooltip title="上移">
                          <Button type="text" size="small" icon={<ArrowUpOutlined />}
                                  onClick={() => moveColumn(index, 'up')} disabled={index === 0} />
                        </Tooltip>
                        <Tooltip title="下移">
                          <Button type="text" size="small" icon={<ArrowDownOutlined />}
                                  onClick={() => moveColumn(index, 'down')} disabled={index === columns.length - 1} />
                        </Tooltip>
                        <Tooltip title="删除">
                          <Button type="text" size="small" danger icon={<DeleteOutlined />}
                                  onClick={() => removeColumn(index)} />
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
        <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addColumn} style={{ marginTop: 12 }}>
          添加列
        </Button>
      )}

      <VariableLabelPickerDrawer
        open={labelPickerOpen}
        onClose={() => { setLabelPickerOpen(false); setLabelPickerColIdx(null); }}
        onPick={handleLabelPick}
        initialPath={labelPickerColIdx != null ? (columns[labelPickerColIdx]?.variable_path || '') : ''}
      />
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
