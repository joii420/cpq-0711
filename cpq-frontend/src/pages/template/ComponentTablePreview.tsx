import React from 'react';
import { Button } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { FIELD_TYPE_LABELS } from './types';
import './styles.css';

interface ComponentTablePreviewProps {
  fields: any[];
  formulas: any[];
  tabName: string;
  tcId: string;
  presetRows?: Record<string, any>[];
  onPresetRowsChange?: (rows: Record<string, any>[]) => void;
  isDraft?: boolean;
}

const ComponentTablePreview: React.FC<ComponentTablePreviewProps> = ({
  fields,
  formulas,
  tabName,
  tcId: _tcId,
  presetRows = [],
  onPresetRowsChange,
  isDraft = false,
}) => {
  if (!fields || fields.length === 0) {
    return (
      <div style={{ color: '#999', textAlign: 'center', padding: '20px 0', fontSize: 13 }}>
        {tabName} - 暂无字段
      </div>
    );
  }

  // Build formula name lookup: field name -> formula name
  const formulaNameMap: Record<string, string> = {};
  for (const f of fields) {
    if (f.field_type === 'FORMULA' && f.name) {
      const matched = (formulas || []).find((fm: any) => fm.name === f.name);
      if (matched) formulaNameMap[f.name] = matched.name;
    }
  }

  const handleAddRow = () => {
    const row: Record<string, any> = {};
    for (const f of fields) {
      if (f.field_type === 'FORMULA') continue;
      row[f.name] = f.field_type === 'FIXED_VALUE' ? (f.content ?? '') : '';
    }
    onPresetRowsChange?.([...presetRows, row]);
  };

  const handleRemoveRow = (rowIndex: number) => {
    onPresetRowsChange?.(presetRows.filter((_, i) => i !== rowIndex));
  };

  const handleCellChange = (rowIndex: number, fieldName: string, value: string) => {
    const updated = presetRows.map((row, i) =>
      i === rowIndex ? { ...row, [fieldName]: value } : row
    );
    onPresetRowsChange?.(updated);
  };

  return (
    <div>
      <div style={{ overflowX: 'auto' }}>
        <table className="tm-cost-table">
          <thead>
            <tr>
              {fields.map((field: any, i: number) => (
                <th key={i}>
                  {field.name || `字段${i + 1}`}
                  <div style={{ fontSize: 10, color: '#999', fontWeight: 400, marginTop: 2 }}>
                    {FIELD_TYPE_LABELS[field.field_type] || field.field_type}
                  </div>
                </th>
              ))}
              {isDraft && presetRows.length > 0 && <th style={{ width: 40 }} />}
            </tr>
          </thead>
          <tbody>
            {/* Preset rows - editable in DRAFT */}
            {presetRows.map((row, rowIdx) => (
              <tr key={`preset-${rowIdx}`}>
                {fields.map((field: any, fi: number) => {
                  if (field.field_type === 'FORMULA') {
                    return (
                      <td key={fi} className="tm-formula-cell has-formula">
                        <span className="tm-formula-text" style={{ fontSize: 12 }}>
                          {formulaNameMap[field.name] || field.name || '公式'}
                        </span>
                      </td>
                    );
                  }
                  return (
                    <td key={fi}>
                      {isDraft ? (
                        <input
                          type="text"
                          value={row[field.name] ?? ''}
                          onChange={(e) => handleCellChange(rowIdx, field.name, e.target.value)}
                          placeholder={field.field_type === 'FIXED_VALUE' ? field.content : ''}
                          style={{
                            width: '100%',
                            border: '1px solid #e8e8e8',
                            borderRadius: 3,
                            padding: '2px 6px',
                            fontSize: 12,
                            outline: 'none',
                          }}
                        />
                      ) : (
                        <span style={{ fontSize: 12, color: '#333' }}>
                          {row[field.name] || '—'}
                        </span>
                      )}
                    </td>
                  );
                })}
                {isDraft && (
                  <td>
                    <Button
                      type="text"
                      danger
                      size="small"
                      icon={<DeleteOutlined />}
                      onClick={() => handleRemoveRow(rowIdx)}
                      style={{ padding: '0 4px' }}
                    />
                  </td>
                )}
              </tr>
            ))}
            {/* Empty placeholder row when no preset rows */}
            {presetRows.length === 0 && (
              <tr>
                {fields.map((field: any, i: number) => (
                  <td key={i} style={{ color: field.field_type === 'FORMULA' ? '#67c23a' : '#bbb' }}>
                    {field.field_type === 'FORMULA' ? (formulaNameMap[field.name] || '公式') : '· · ·'}
                  </td>
                ))}
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {isDraft && (
        <div style={{ marginTop: 8 }}>
          <Button size="small" type="dashed" icon={<PlusOutlined />} onClick={handleAddRow}>
            添加固定行
          </Button>
          {presetRows.length > 0 && (
            <span style={{ marginLeft: 8, fontSize: 11, color: '#999' }}>
              已配置 {presetRows.length} 行固定数据，报价时默认带出且不可删除
            </span>
          )}
        </div>
      )}

      {!isDraft && presetRows.length > 0 && (
        <div style={{ marginTop: 4, fontSize: 11, color: '#999' }}>
          已配置 {presetRows.length} 行固定数据
        </div>
      )}
    </div>
  );
};

export default ComponentTablePreview;
