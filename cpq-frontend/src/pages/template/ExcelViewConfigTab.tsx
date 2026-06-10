import React, { useState, useEffect, useMemo } from 'react';
import {
  Button, Space, message, Typography, Tag, Select, Switch, Empty, Alert, InputNumber,
} from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { templateService } from '../../services/templateService';
import { componentService } from '../../services/componentService';
import type { ComponentItem } from '../component/types';

const { Text } = Typography;

// Task 3.1 Excel 配置归属迁移:
// 模板的 Excel 视图不再内联编辑列定义, 而是 *引用* 一个 componentType==='EXCEL' 组件.
// 真正的列定义存在该 EXCEL 组件的 excelColumns 字段(JSON 数组); 本视图只持有:
//   { version:2, import_settings, excel_component_id, column_overrides:[{col_key, hidden?, display_format?}] }
// column_overrides 是稀疏的 —— 只在某列覆写值与组件基线不同时才有条目, 重置即移除条目.
// 后端 getEffectiveColumns 负责合并 component.excelColumns + column_overrides.

type SourceType = 'VARIABLE' | 'FORMULA' | 'CARD_FORMULA' | 'TAB_JOIN_FORMULA' | 'PRODUCT_ATTRIBUTE' | 'COMPONENT_FIELD' | 'EXCEL_FORMULA' | 'FIXED_VALUE';

interface ExcelBaseColumn {
  col_key: string;
  title: string;
  source_type: SourceType;
  hidden?: boolean;
  display_format?: { type?: 'PERCENT' | 'NUMBER'; decimals?: number };
  [k: string]: any;
}

/** 模板侧对单列的稀疏覆写。只携带与组件基线不同的键。 */
interface ColumnOverride {
  col_key: string;
  hidden?: boolean;
  display_format?: { type?: 'PERCENT' | 'NUMBER'; decimals?: number };
}

interface ExcelViewConfigV2 {
  version: 2;
  import_settings?: any;
  excel_component_id?: string | null;
  column_overrides: ColumnOverride[];
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

const SOURCE_TYPE_LABEL: Record<string, string> = {
  VARIABLE: '变量',
  FORMULA: '公式',
  CARD_FORMULA: '卡片公式',
  TAB_JOIN_FORMULA: '页签连表公式',
  PRODUCT_ATTRIBUTE: '产品属性',
  COMPONENT_FIELD: '组件字段',
  EXCEL_FORMULA: 'Excel公式',
  FIXED_VALUE: '固定值',
};

/** 把传入的任意格式 config 规整为 v2 对象。兼容老 bare-array / string。 */
function normalizeConfig(raw: any): ExcelViewConfigV2 {
  let parsed: any = raw;
  if (typeof raw === 'string') {
    try { parsed = JSON.parse(raw); } catch { parsed = null; }
  }
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    return {
      version: 2,
      import_settings: parsed.import_settings ?? undefined,
      excel_component_id: parsed.excel_component_id ?? null,
      column_overrides: Array.isArray(parsed.column_overrides) ? parsed.column_overrides : [],
    };
  }
  // 老格式(bare array / null) → 尚未迁移, 无引用组件
  return { version: 2, import_settings: undefined, excel_component_id: null, column_overrides: [] };
}

/** 解析 EXCEL 组件的 excelColumns(JSON 字符串)为基线列数组。 */
function parseExcelColumns(raw: any): ExcelBaseColumn[] {
  if (!raw) return [];
  let parsed: any = raw;
  if (typeof raw === 'string') {
    try { parsed = JSON.parse(raw); } catch { return []; }
  }
  if (Array.isArray(parsed)) return parsed;
  if (parsed && Array.isArray(parsed.columns)) return parsed.columns;
  return [];
}

function isLegacyBareArray(raw: any): boolean {
  let parsed: any = raw;
  if (typeof raw === 'string') {
    try { parsed = JSON.parse(raw); } catch { return false; }
  }
  return Array.isArray(parsed) && parsed.length > 0;
}

const ExcelViewConfigTab: React.FC<Props> = ({ templateId, isDraft, excelViewConfig, onChange }) => {
  const [config, setConfig] = useState<ExcelViewConfigV2>(() => normalizeConfig(excelViewConfig));
  const [saving, setSaving] = useState(false);
  const [excelComponents, setExcelComponents] = useState<ComponentItem[]>([]);
  const [loadingComponents, setLoadingComponents] = useState(false);
  const legacyArray = useMemo(() => isLegacyBareArray(excelViewConfig), [excelViewConfig]);

  // 父组件 excelViewConfig 变更时同步
  useEffect(() => {
    setConfig(normalizeConfig(excelViewConfig));
  }, [excelViewConfig]);

  // 拉取所有 EXCEL 组件供选择
  useEffect(() => {
    setLoadingComponents(true);
    componentService.list({})
      .then((resp: any) => {
        const list: ComponentItem[] = resp?.data ?? resp ?? [];
        const arr = Array.isArray(list) ? list : [];
        setExcelComponents(arr.filter(c => c.componentType === 'EXCEL' && c.status === 'ACTIVE'));
      })
      .catch(() => setExcelComponents([]))
      .finally(() => setLoadingComponents(false));
  }, []);

  const selectedComponent = useMemo(
    () => excelComponents.find(c => c.id === config.excel_component_id) || null,
    [excelComponents, config.excel_component_id],
  );

  const baseColumns = useMemo(
    () => parseExcelColumns(selectedComponent?.excelColumns),
    [selectedComponent],
  );

  // col_key → 覆写条目, 便于读取
  const overrideMap = useMemo(() => {
    const m: Record<string, ColumnOverride> = {};
    for (const o of config.column_overrides) m[o.col_key] = o;
    return m;
  }, [config.column_overrides]);

  const handleSelectComponent = (componentId: string | undefined) => {
    setConfig(prev => ({
      ...prev,
      excel_component_id: componentId ?? null,
      // 换组件时清空旧覆写(col_key 体系可能不同)
      column_overrides: componentId === prev.excel_component_id ? prev.column_overrides : [],
    }));
  };

  /**
   * 写一个覆写键。值与组件基线相同则移除该键; 若该列再无任何覆写键则整条移除(保持稀疏)。
   */
  const setOverride = (col: ExcelBaseColumn, key: 'hidden' | 'display_format', value: any) => {
    setConfig(prev => {
      const existing = prev.column_overrides.find(o => o.col_key === col.col_key);
      const baseVal = (col as any)[key];
      const next: ColumnOverride = existing
        ? { ...existing }
        : { col_key: col.col_key };

      const sameAsBase =
        key === 'hidden'
          ? (!!value === !!baseVal)
          : JSON.stringify(value ?? null) === JSON.stringify(baseVal ?? null);

      if (sameAsBase || value === undefined || value === null) {
        delete (next as any)[key];
      } else {
        (next as any)[key] = value;
      }

      // 重新组装 column_overrides; 移除空条目(只剩 col_key)
      const hasAnyOverride = Object.keys(next).some(k => k !== 'col_key');
      const others = prev.column_overrides.filter(o => o.col_key !== col.col_key);
      return {
        ...prev,
        column_overrides: hasAnyOverride ? [...others, next] : others,
      };
    });
  };

  // 列的有效值(基线 + 覆写)
  const effectiveHidden = (col: ExcelBaseColumn): boolean => {
    const ov = overrideMap[col.col_key];
    return ov && 'hidden' in ov ? !!ov.hidden : !!col.hidden;
  };
  const effectiveFormat = (col: ExcelBaseColumn): { type?: 'PERCENT' | 'NUMBER'; decimals?: number } | undefined => {
    const ov = overrideMap[col.col_key];
    return ov && 'display_format' in ov ? ov.display_format : col.display_format;
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      const payload: ExcelViewConfigV2 = {
        version: 2,
        import_settings: config.import_settings,
        excel_component_id: config.excel_component_id ?? null,
        column_overrides: config.column_overrides,
      };
      await templateService.updateExcelViewConfig(templateId, payload);
      onChange(payload);
      message.success('Excel 视图配置已保存');
    } catch (e: any) {
      message.error(e?.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{ padding: '16px 0' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Typography.Title level={5} style={{ margin: 0 }}>Excel 视图配置</Typography.Title>
          <Text type="secondary" style={{ fontSize: 12 }}>
            引用一个 EXCEL 组件作为列定义来源, 本模板仅做列覆写(隐藏 / 显示格式)
          </Text>
        </div>
        {isDraft && (
          <Button type="primary" size="small" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
            保存配置
          </Button>
        )}
      </div>

      {legacyArray && !config.excel_component_id && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="检测到旧格式内联列配置"
          description="该模板曾使用内联 Excel 列定义(已弃用)。请在下方选择或先到「组件管理」创建一个 EXCEL 组件，保存后将切换为组件引用模式。旧配置在选择并保存前仍向后兼容。"
        />
      )}

      <div style={{ marginBottom: 16 }}>
        <Text strong style={{ marginRight: 8 }}>引用 EXCEL 组件:</Text>
        <Select
          showSearch
          allowClear
          style={{ width: 360 }}
          placeholder="选择一个 EXCEL 组件"
          loading={loadingComponents}
          disabled={!isDraft}
          value={config.excel_component_id ?? undefined}
          optionFilterProp="label"
          onChange={handleSelectComponent}
          options={excelComponents.map(c => ({
            label: `${c.name}${c.code ? `（${c.code}）` : ''}`,
            value: c.id,
          }))}
        />
        {selectedComponent && (
          <Text type="secondary" style={{ marginLeft: 12, fontSize: 12 }}>
            共 {baseColumns.length} 列
          </Text>
        )}
      </div>

      {!config.excel_component_id ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={
            <span style={{ color: '#999' }}>
              请选择一个 EXCEL 组件作为列定义来源；如尚未创建，请先在「组件管理」中创建 EXCEL 组件。
            </span>
          }
          style={{ padding: 24, background: '#fafafa', borderRadius: 6 }}
        />
      ) : baseColumns.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={<span style={{ color: '#999' }}>该 EXCEL 组件尚未配置列定义，请到「组件管理」中为其添加列。</span>}
          style={{ padding: 24, background: '#fafafa', borderRadius: 6 }}
        />
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 8 }}
            message="列定义为只读（来源于 EXCEL 组件）。本模板仅可覆写「隐藏」与「显示格式」，覆写后右侧出现“已覆写”标记。"
          />
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead style={{ background: '#fafafa' }}>
              <tr>
                <th style={{ ...thStyle, width: 80 }}>列 Key</th>
                <th style={thStyle}>列标题</th>
                <th style={{ ...thStyle, width: 140 }}>类型</th>
                <th style={{ ...thStyle, width: 100, textAlign: 'center' }}>隐藏</th>
                <th style={{ ...thStyle, width: 220 }}>显示格式</th>
                <th style={{ ...thStyle, width: 90 }}>状态</th>
              </tr>
            </thead>
            <tbody>
              {baseColumns.map((col) => {
                const ov = overrideMap[col.col_key];
                const overridden = !!ov && Object.keys(ov).some(k => k !== 'col_key');
                const fmt = effectiveFormat(col);
                return (
                  <tr key={col.col_key} style={{ borderBottom: '1px solid #f0f0f0' }}>
                    <td style={tdStyle}>
                      <Text code>{col.col_key}</Text>
                    </td>
                    <td style={tdStyle}>
                      <Text>{col.title || <Text type="secondary">—</Text>}</Text>
                    </td>
                    <td style={tdStyle}>
                      <Tag color="default" style={{ fontSize: 11 }}>
                        {SOURCE_TYPE_LABEL[col.source_type] || col.source_type}
                      </Tag>
                    </td>
                    <td style={{ ...tdStyle, textAlign: 'center' }}>
                      <Switch
                        size="small"
                        checked={effectiveHidden(col)}
                        disabled={!isDraft}
                        onChange={v => setOverride(col, 'hidden', v)}
                      />
                    </td>
                    <td style={tdStyle}>
                      <Space size={4}>
                        <Select
                          size="small"
                          style={{ width: 110 }}
                          disabled={!isDraft}
                          value={fmt?.type ?? '__base__'}
                          onChange={v => {
                            if (v === '__base__') {
                              setOverride(col, 'display_format', undefined);
                            } else {
                              setOverride(col, 'display_format', { type: v, decimals: fmt?.decimals });
                            }
                          }}
                          options={[
                            { label: '默认', value: '__base__' },
                            { label: '数值', value: 'NUMBER' },
                            { label: '百分比', value: 'PERCENT' },
                          ]}
                        />
                        {fmt?.type && (
                          <InputNumber
                            size="small"
                            style={{ width: 90 }}
                            min={0}
                            max={6}
                            placeholder="小数位"
                            disabled={!isDraft}
                            value={fmt?.decimals}
                            onChange={v => setOverride(col, 'display_format', { type: fmt.type, decimals: v ?? undefined })}
                          />
                        )}
                      </Space>
                    </td>
                    <td style={tdStyle}>
                      {overridden ? <Tag color="orange">已覆写</Tag> : <Text type="secondary" style={{ fontSize: 11 }}>基线</Text>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
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
