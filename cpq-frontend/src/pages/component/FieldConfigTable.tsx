import React, { useState } from 'react';
import { Table, Input, Select, Checkbox, Button, Typography, Tooltip, Space } from 'antd';

const { Text } = Typography;
import { DeleteOutlined, PlusOutlined, LinkOutlined, EditOutlined } from '@ant-design/icons';
import type { FieldItem } from './types';
import { FIELD_TYPE_OPTIONS, newFieldRow } from './types';
import PathPickerDrawer from './PathPickerDrawer';
import GlobalVariablePickerDrawer from '../../components/GlobalVariablePickerDrawer';
import './styles.css';

interface FieldConfigTableProps {
  fields: FieldItem[];
  onChange: (fields: FieldItem[]) => void;
  onConfigDatasource: (fieldIndex: number) => void;
}

const FieldConfigTable: React.FC<FieldConfigTableProps> = ({
  fields,
  onChange,
  onConfigDatasource,
}) => {
  const [pathPickerKey, setPathPickerKey] = useState<string | null>(null);
  // V109: 全局变量选择器, 选完编译为 BNF path + 写入 global_variable_code 元数据
  const [gvPickerKey, setGvPickerKey] = useState<string | null>(null);

  const updateField = (key: string, patch: Partial<FieldItem>) => {
    onChange(fields.map((f) => (f.key === key ? { ...f, ...patch } : f)));
  };

  const handleSubtotalChange = (key: string, checked: boolean) => {
    if (checked) {
      onChange(fields.map((f) => ({ ...f, is_subtotal: f.key === key })));
    } else {
      updateField(key, { is_subtotal: false });
    }
  };

  const moveField = (index: number, direction: 'up' | 'down') => {
    const next = [...fields];
    const swapIndex = direction === 'up' ? index - 1 : index + 1;
    if (swapIndex < 0 || swapIndex >= next.length) return;
    [next[index], next[swapIndex]] = [next[swapIndex], next[index]];
    onChange(next);
  };

  const deleteField = (key: string) => {
    onChange(fields.filter((f) => f.key !== key));
  };

  const columns = [
    {
      key: 'drag',
      width: 32,
      render: () => <span className="cm-drag-handle">↕</span>,
    },
    {
      title: '字段名',
      key: 'name',
      render: (_: unknown, record: FieldItem) => (
        <Input
          value={record.name}
          onChange={(e) => updateField(record.key, { name: e.target.value })}
          placeholder="字段名称"
          size="small"
        />
      ),
    },
    {
      title: '字段类型',
      key: 'field_type',
      width: 130,
      render: (_: unknown, record: FieldItem) => (
        <Select
          value={record.field_type}
          onChange={(val) => updateField(record.key, { field_type: val })}
          options={FIELD_TYPE_OPTIONS}
          size="small"
          style={{ width: '100%' }}
        />
      ),
    },
    {
      title: '内容/配置',
      key: 'content',
      render: (_: unknown, record: FieldItem, index: number) => {
        if (record.field_type === 'FIXED_VALUE') {
          return (
            <Input
              value={record.content}
              onChange={(e) => updateField(record.key, { content: e.target.value })}
              placeholder="固定值"
              size="small"
            />
          );
        }
        if (record.field_type === 'DATA_SOURCE') {
          return (
            <Button size="small" type="link" onClick={() => onConfigDatasource(index)}>
              {record.datasource_binding?.datasource_id
                ? `${record.datasource_binding.datasource_name}(${record.datasource_binding.datasource_code})`
                : '配置数据源'}
            </Button>
          );
        }
        if (record.field_type === 'BASIC_DATA') {
          const configured = !!record.basic_data_path;
          const gvCode = record.global_variable_code;
          if (configured) {
            return (
              <Space size={2} wrap>
                {gvCode && (
                  <span
                    title={`取自全局变量 ${gvCode}`}
                    style={{
                      fontSize: 11,
                      padding: '1px 6px',
                      borderRadius: 10,
                      background: '#fff7e6',
                      color: '#d46b08',
                      border: '1px solid #ffd591',
                    }}
                  >
                    🌐 {gvCode}
                  </span>
                )}
                <Tooltip title={`点击修改:${record.basic_data_path}`}>
                  <Button
                    size="small"
                    type="link"
                    icon={<EditOutlined />}
                    onClick={() => setPathPickerKey(record.key)}
                    style={{ color: '#08979c', fontFamily: 'Consolas, Monaco, monospace' }}
                  >
                    {`{${record.basic_data_path}}`}
                  </Button>
                </Tooltip>
                <Tooltip title="改用全局变量">
                  <Button
                    size="small"
                    type="link"
                    onClick={() => setGvPickerKey(record.key)}
                    style={{ color: '#d46b08' }}
                  >
                    🌐
                  </Button>
                </Tooltip>
              </Space>
            );
          }
          return (
            <Space size={4}>
              <Button
                size="small"
                type="link"
                icon={<LinkOutlined />}
                onClick={() => setPathPickerKey(record.key)}
              >
                配置物理表路径
              </Button>
              <Button
                size="small"
                type="link"
                onClick={() => setGvPickerKey(record.key)}
                style={{ color: '#d46b08' }}
              >
                🌐 全局变量
              </Button>
            </Space>
          );
        }
        if (record.field_type === 'FORMULA') {
          return (
            <Text type="secondary" style={{ fontSize: 12 }}>
              在产品模板中拖入公式
            </Text>
          );
        }
        return null;
      },
    },
    {
      title: '金额',
      key: 'is_amount',
      width: 60,
      render: (_: unknown, record: FieldItem) => (
        <Checkbox
          checked={!!record.is_amount}
          onChange={(e) => updateField(record.key, { is_amount: e.target.checked })}
        />
      ),
    },
    {
      title: '小计',
      key: 'is_subtotal',
      width: 60,
      render: (_: unknown, record: FieldItem) => (
        <Checkbox
          checked={!!record.is_subtotal}
          onChange={(e) => handleSubtotalChange(record.key, e.target.checked)}
        />
      ),
    },
    {
      title: '备注',
      key: 'notes',
      render: (_: unknown, record: FieldItem) => (
        <Input
          value={record.notes}
          onChange={(e) => updateField(record.key, { notes: e.target.value })}
          placeholder="备注"
          size="small"
        />
      ),
    },
    {
      title: '排序',
      key: 'sort',
      width: 64,
      render: (_: unknown, _record: FieldItem, index: number) => (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
          <Button
            type="text"
            size="small"
            disabled={index === 0}
            onClick={() => moveField(index, 'up')}
            style={{ padding: '0 4px', height: 16, fontSize: 10 }}
          >
            ↑
          </Button>
          <Button
            type="text"
            size="small"
            disabled={index === fields.length - 1}
            onClick={() => moveField(index, 'down')}
            style={{ padding: '0 4px', height: 16, fontSize: 10 }}
          >
            ↓
          </Button>
        </div>
      ),
    },
    {
      key: 'action',
      width: 40,
      render: (_: unknown, record: FieldItem) => (
        <Button
          type="text"
          size="small"
          danger
          icon={<DeleteOutlined />}
          onClick={() => deleteField(record.key)}
        />
      ),
    },
  ];

  return (
    <div className="cm-card-section">
      <div className="cm-card-section-header">
        <div className="cm-card-section-header-left">
          <span>⚙️ 字段配置</span>
          <span className="cm-section-badge">{fields.length} 个字段</span>
        </div>
        <Button
          size="small"
          icon={<PlusOutlined />}
          onClick={() => onChange([...fields, newFieldRow()])}
        >
          添加字段
        </Button>
      </div>
      <Table
        dataSource={fields}
        columns={columns}
        rowKey="key"
        pagination={false}
        size="small"
        rowClassName={(record) => (record.is_subtotal ? 'cm-subtotal-table-row' : '')}
        locale={{ emptyText: '暂无字段，点击"添加字段"' }}
      />

      {/* BASIC_DATA 字段路径配置 */}
      <PathPickerDrawer
        open={pathPickerKey !== null}
        initialPath={pathPickerKey ? (fields.find(f => f.key === pathPickerKey)?.basic_data_path ?? '') : ''}
        onClose={() => setPathPickerKey(null)}
        onConfirm={(path) => {
          if (pathPickerKey) {
            // 手工选 BNF 路径 → 视作脱离全局变量, 清掉 global_variable_code 元数据
            updateField(pathPickerKey, { basic_data_path: path, global_variable_code: undefined });
          }
          setPathPickerKey(null);
        }}
      />

      {/* V109: 全局变量路径选择 — 编译为 BNF 路径 + 设 global_variable_code */}
      <GlobalVariablePickerDrawer
        open={gvPickerKey !== null}
        onClose={() => setGvPickerKey(null)}
        onPick={(result) => {
          if (gvPickerKey) {
            updateField(gvPickerKey, {
              basic_data_path: result.bnfPath,
              global_variable_code: result.code,
            });
          }
          setGvPickerKey(null);
        }}
        title="选择全局变量作为字段取值来源"
      />
    </div>
  );
};

export default FieldConfigTable;
