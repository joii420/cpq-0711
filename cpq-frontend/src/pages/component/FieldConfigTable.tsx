import React, { useEffect, useState } from 'react';
import { Input, Select, Checkbox, Button, Typography, Tooltip, Space, Modal, Form, Alert, Segmented, Tag } from 'antd';
import { dataSourceResolverService, RESOLVER_TYPE_LABEL } from '../../services/dataSourceResolverService';
import { SortableTable, DragHandle } from '../../components/SortableTable';

const { Text } = Typography;
import { DeleteOutlined, PlusOutlined, LinkOutlined, EditOutlined } from '@ant-design/icons';
import type { FieldItem, FormulaItem } from './types';
import { FIELD_TYPE_OPTIONS, newFieldRow } from './types';
import PathPickerDrawer from './PathPickerDrawer';
import GlobalVariablePickerDrawer from '../../components/GlobalVariablePickerDrawer';
import DefaultSourceEditor from './DefaultSourceEditor';
import ListFormulaConfigDrawer from './ListFormulaConfigDrawer';
import ConditionalFormulaDrawer, { type ConditionalFormulaValue } from './ConditionalFormulaDrawer';
import { extractSqlViewName } from './sqlViewPath';
import './styles.css';

interface FieldConfigTableProps {
  fields: FieldItem[];
  /**
   * 2026-05-20: 当前组件的公式列表 (component.formulas / componentDefaultFormulas).
   * FORMULA 字段的"内容/配置"列需要展示下拉选择, 让用户显式绑定 field.formula_name → 公式 name.
   * 父组件: ComponentManagement 传自身 formulas state; OverridesDrawer 传 componentDefaultFormulas.
   * 未传 = 兼容老调用方, 该列回退到提示文案.
   */
  formulas?: FormulaItem[];
  onChange: (fields: FieldItem[]) => void;
  onConfigDatasource: (fieldIndex: number) => void;
  /** 可选：当前组件 ID，传入后 PathPickerDrawer 会显示"SQL 视图"Tab */
  componentId?: string;
  /** 当前 rowKeyFields（真实列名数组）。传入即渲染"行键"勾选列。 */
  rowKeyFields?: string[];
  /** 按字段名索引的行键候选（来自 row-key-candidates 端点）。 */
  candidatesByField?: Record<string, import('./types').RowKeyCandidate>;
  /** 勾选/取消某字段作行键：传该字段反查出的真实列名 + 选中态。 */
  onToggleRowKey?: (resolvedColumn: string, checked: boolean) => void;
  /** 组件 driver 路径（$视图…）；用于行键判定 + 字段路径选择器只列 driver 视图列。 */
  dataDriverPath?: string;
}

const FieldConfigTable: React.FC<FieldConfigTableProps> = ({
  fields,
  formulas,
  onChange,
  onConfigDatasource,
  componentId,
  rowKeyFields,
  candidatesByField,
  onToggleRowKey,
  dataDriverPath,
}) => {
  const [pathPickerKey, setPathPickerKey] = useState<string | null>(null);
  // V109: 全局变量选择器, 选完编译为 BNF path + 写入 global_variable_code 元数据
  const [gvPickerKey, setGvPickerKey] = useState<string | null>(null);
  // G3: 统一默认值来源编辑抽屉
  const [defaultSourceKey, setDefaultSourceKey] = useState<string | null>(null);
  // J2: DATA_SOURCE.HTTP_API inline 配置 Modal
  const [httpApiKey, setHttpApiKey] = useState<string | null>(null);
  const [httpApiForm] = Form.useForm();
  // K2: 动态 resolver type 列表, 加 type 时前端零改动
  // BNF_PATH 已从 UI 创建入口移除（Task 6.1）；历史配置仍可读取，不再允许新建。
  const [resolverTypes, setResolverTypes] = useState<string[]>(
    ['DATABASE_QUERY', 'GLOBAL_VARIABLE', 'HTTP_API']
  );
  // LIST_FORMULA 配置 Drawer
  const [listFormulaKey, setListFormulaKey] = useState<string | null>(null);
  // Plan 3b：条件公式配置 Drawer 的目标字段 key
  const [condFormulaKey, setCondFormulaKey] = useState<string | null>(null);
  useEffect(() => {
    dataSourceResolverService.listTypes().then(setResolverTypes).catch(() => {/* 用默认 */});
  }, []);

  const updateField = (key: string, patch: Partial<FieldItem>) => {
    onChange(fields.map((f) => (f.key === key ? { ...f, ...patch } : f)));
  };

  const handleSubtotalChange = (key: string, checked: boolean) => {
    // 多小计列（Plan 2-核心）：每个字段独立勾选，不再互斥。
    updateField(key, { is_subtotal: checked });
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
      width: 40,
      align: 'center' as const,
      render: () => <DragHandle />,
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
          options={[...FIELD_TYPE_OPTIONS]}
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
          // V190+: 兼容老配置 — datasource_binding 无 type 但有 datasource_id 视为 DATABASE_QUERY
          const binding = record.datasource_binding;
          const dsType = binding?.type
            ?? (binding?.datasource_id ? 'DATABASE_QUERY' : 'DATABASE_QUERY');
          const updateBinding = (patch: Partial<NonNullable<FieldItem['datasource_binding']>>) => {
            updateField(record.key, {
              datasource_binding: { ...(binding || {}), ...patch },
            });
          };
          return (
            <Space size={4} wrap>
              <Select
                value={dsType}
                onChange={(t) => updateBinding({
                  type: t,
                  // 切换 type 时清掉其他类型的配置, 避免脏数据
                  datasource_id: t === 'DATABASE_QUERY' ? binding?.datasource_id : undefined,
                  global_variable_code: t === 'GLOBAL_VARIABLE' ? binding?.global_variable_code : undefined,
                  bnf_path: t === 'BNF_PATH' ? binding?.bnf_path : undefined,
                })}
                size="small"
                style={{ width: 130 }}
                options={resolverTypes.map((t) => ({
                  label: RESOLVER_TYPE_LABEL[t] || t,
                  value: t,
                }))}
              />
              {dsType === 'DATABASE_QUERY' && (
                <Button size="small" type="link" onClick={() => onConfigDatasource(index)}>
                  {binding?.datasource_id
                    ? `${binding.datasource_name}(${binding.datasource_code})`
                    : '配置数据源'}
                </Button>
              )}
              {dsType === 'GLOBAL_VARIABLE' && (
                <Tooltip title="改用全局变量">
                  <Button
                    size="small"
                    type="link"
                    onClick={() => setGvPickerKey(record.key)}
                    style={{ color: '#d46b08', fontFamily: 'Consolas, Monaco, monospace' }}
                  >
                    🌐 {binding?.global_variable_code || '选择变量'}
                  </Button>
                </Tooltip>
              )}
              {dsType === 'HTTP_API' && (
                <Tooltip title="点击配置 url_template / response_path / auth_token_env">
                  <Button
                    size="small"
                    type="link"
                    icon={<EditOutlined />}
                    onClick={() => {
                      httpApiForm.setFieldsValue({
                        url_template: binding?.api_config?.url_template || '',
                        response_path: binding?.api_config?.response_path || '',
                        auth_token_env: binding?.api_config?.auth_token_env || '',
                      });
                      setHttpApiKey(record.key);
                    }}
                    style={{ color: '#722ed1', fontFamily: 'Consolas, Monaco, monospace' }}
                  >
                    {binding?.api_config?.url_template ? `🌐 ${String(binding.api_config.url_template).slice(0, 40)}…` : '配置 HTTP API'}
                  </Button>
                </Tooltip>
              )}
            </Space>
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
                {(() => {
                  const driverView = extractSqlViewName(dataDriverPath);
                  const fieldView = extractSqlViewName(record.basic_data_path);
                  const hasPredicate = (record.basic_data_path ?? '').includes('[');
                  const nonDriver = hasPredicate || !fieldView || (!!driverView && fieldView !== driverView);
                  return nonDriver ? (
                    <Tooltip title="该路径非 driver 视图列（含谓词或指向别的表），建议重新配置为 driver 列">
                      <Tag color="warning" style={{ marginLeft: 4 }}>⚠ 非driver列</Tag>
                    </Tooltip>
                  ) : null;
                })()}
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
          // 2026-05-20: 单一模式从公式列表选一个绑定 (field.formula_name)
          // Plan 3b: 条件模式 (field.conditional_formula) — 按规则逐行选公式，配置抽屉编辑。
          const options = (formulas || [])
            .map(f => ({
              value: f.name || '',
              label: `${f.name || '(未命名)'}${f.result_type ? ` · ${f.result_type}` : ''}`,
            }))
            .filter(o => o.value);
          const isCond = !!record.conditional_formula;
          return (
            <Space size={4} wrap>
              <Segmented
                size="small"
                value={isCond ? 'cond' : 'single'}
                options={[{ label: '单一', value: 'single' }, { label: '条件', value: 'cond' }]}
                onChange={(m) => {
                  if (m === 'cond') updateField(record.key, { conditional_formula: { rules: [], default: '' } });
                  else updateField(record.key, { conditional_formula: undefined });
                }}
              />
              {!isCond && (
                options.length === 0
                  ? <Tooltip title="先去左侧「公式」Tab 添加公式定义, 再回此处绑定">
                      <Text type="secondary" style={{ fontSize: 12 }}>暂无公式可绑 →</Text>
                    </Tooltip>
                  : <Select
                      size="small" style={{ minWidth: 160, fontSize: 12 }} placeholder="选择绑定公式" allowClear
                      value={record.formula_name || undefined} options={options}
                      onChange={(v) => updateField(record.key, { formula_name: v || undefined })}
                      showSearch optionFilterProp="label" />
              )}
              {isCond && (
                <Button size="small" type="link" onClick={() => setCondFormulaKey(record.key)}>
                  条件公式（{record.conditional_formula?.rules?.length || 0} 规则）配置 →
                </Button>
              )}
            </Space>
          );
        }
        if (record.field_type === 'LIST_FORMULA') {
          const cfg = record.list_formula_config;
          const itemCount = cfg?.per_item_rules ? Object.keys(cfg.per_item_rules).length : 0;
          const isConfigured = !!cfg?.config_template_id && !!cfg?.category_code;
          return (
            <Space size={4} wrap>
              {isConfigured ? (
                <Tooltip title={`点击修改: ${cfg!.config_template_name || cfg!.config_template_code} / ${cfg!.category_name || cfg!.category_code}, 已配 ${itemCount} 项`}>
                  <Button
                    size="small"
                    type="link"
                    icon={<EditOutlined />}
                    onClick={() => setListFormulaKey(record.key)}
                    style={{ color: '#1677ff', fontFamily: 'Consolas, Monaco, monospace' }}
                  >
                    📋 {cfg!.category_name || cfg!.category_code} ({itemCount} 项)
                  </Button>
                </Tooltip>
              ) : (
                <Button
                  size="small"
                  type="link"
                  icon={<LinkOutlined />}
                  onClick={() => setListFormulaKey(record.key)}
                  style={{ color: '#1677ff' }}
                >
                  配置模板 + 大类 + 逐项公式
                </Button>
              )}
            </Space>
          );
        }
        // G3: INPUT_TEXT / INPUT_NUMBER — content 静态兜底 + default_source 来源
        if (record.field_type === 'INPUT_TEXT' || record.field_type === 'INPUT_NUMBER') {
          const ds = record.default_source;
          return (
            <Space size={4} wrap>
              <Input
                value={record.content}
                onChange={(e) => updateField(record.key, { content: e.target.value })}
                placeholder={record.field_type === 'INPUT_NUMBER' ? '静态默认值 (如 100)' : '静态默认值'}
                size="small"
                style={{ width: 140 }}
              />
              {ds ? (
                <Tooltip
                  title={
                    ds.type === 'GLOBAL_VARIABLE'
                      ? `点击修改: 全局变量 ${ds.code}`
                      : ds.type === 'BNF_PATH'
                      ? `点击修改: ${ds.path}`
                      : '点击修改'
                  }
                >
                  <Button
                    size="small"
                    type="link"
                    icon={<EditOutlined />}
                    onClick={() => setDefaultSourceKey(record.key)}
                    style={{
                      color: ds.type === 'GLOBAL_VARIABLE' ? '#d46b08' : '#08979c',
                      fontFamily: 'Consolas, Monaco, monospace',
                    }}
                  >
                    {ds.type === 'GLOBAL_VARIABLE' ? `🌐 ${ds.code}` : `{${ds.path}}`}
                  </Button>
                </Tooltip>
              ) : (
                <Button
                  size="small"
                  type="link"
                  onClick={() => setDefaultSourceKey(record.key)}
                  style={{ color: '#8c8c8c' }}
                >
                  + 默认值来源
                </Button>
              )}
            </Space>
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
    ...(onToggleRowKey ? [{
      title: '行键',
      key: 'is_row_key',
      width: 64,
      render: (_: unknown, record: FieldItem) => {
        const cand = candidatesByField?.[record.name];
        const eligible = !!cand?.eligible;
        const col = cand?.resolvedColumn ?? null;
        const checked = !!(col && (rowKeyFields ?? []).includes(col));
        const tip = eligible
          ? (cand?.source === 'input' ? `行键列（手填）：${col}` : `行键列（driver）：${col}`)
          : (cand?.reason ?? '该字段无 driver 列，不能作行键');
        return (
          <Tooltip title={tip}>
            <Checkbox
              checked={checked}
              disabled={!eligible}
              onChange={(e) => { if (col) onToggleRowKey(col, e.target.checked); }}
            />
          </Tooltip>
        );
      },
    }] : []),
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
      <SortableTable
        rowKey="key"
        dataSource={fields}
        columns={columns}
        pagination={false}
        size="small"
        rowClassName={(record) => (record.is_subtotal ? 'cm-subtotal-table-row' : '')}
        locale={{ emptyText: '暂无字段，点击"添加字段"' }}
        onReorder={(next) => onChange(next.map((f, i) => ({ ...f, sort_order: i })))}
      />

      {/* 路径配置: BASIC_DATA → basic_data_path; DATA_SOURCE → datasource_binding.bnf_path */}
      <PathPickerDrawer
        open={pathPickerKey !== null}
        componentId={componentId}
        driverViewPath={dataDriverPath}
        disablePredicate
        initialPath={pathPickerKey ? (() => {
          const f = fields.find(x => x.key === pathPickerKey);
          if (!f) return '';
          if (f.field_type === 'DATA_SOURCE') return f.datasource_binding?.bnf_path ?? '';
          return f.basic_data_path ?? '';
        })() : ''}
        onClose={() => setPathPickerKey(null)}
        onConfirm={(path) => {
          if (pathPickerKey) {
            const f = fields.find(x => x.key === pathPickerKey);
            if (f?.field_type === 'DATA_SOURCE') {
              updateField(pathPickerKey, {
                datasource_binding: {
                  ...(f.datasource_binding || {}),
                  type: 'BNF_PATH',
                  bnf_path: path,
                },
              });
            } else {
              // BASIC_DATA: 手工选 BNF 路径 → 视作脱离全局变量
              updateField(pathPickerKey, { basic_data_path: path, global_variable_code: undefined });
            }
          }
          setPathPickerKey(null);
        }}
      />

      {/* 全局变量选择: BASIC_DATA → basic_data_path + global_variable_code (V109); DATA_SOURCE → datasource_binding.global_variable_code (H2) */}
      <GlobalVariablePickerDrawer
        open={gvPickerKey !== null}
        onClose={() => setGvPickerKey(null)}
        onPick={(result) => {
          if (gvPickerKey) {
            const f = fields.find(x => x.key === gvPickerKey);
            if (f?.field_type === 'DATA_SOURCE') {
              updateField(gvPickerKey, {
                datasource_binding: {
                  ...(f.datasource_binding || {}),
                  type: 'GLOBAL_VARIABLE',
                  global_variable_code: result.code,
                  key_field_refs: {},  // 同名映射兜底
                },
              });
            } else {
              updateField(gvPickerKey, {
                basic_data_path: result.bnfPath,
                global_variable_code: result.code,
              });
            }
          }
          setGvPickerKey(null);
        }}
        title="选择全局变量作为字段取值来源"
      />

      {/* J2: DATA_SOURCE.HTTP_API inline 配置 Modal */}
      <Modal
        title="配置 HTTP API 数据源"
        open={httpApiKey !== null}
        onCancel={() => setHttpApiKey(null)}
        onOk={async () => {
          try {
            const v = await httpApiForm.validateFields();
            if (httpApiKey) {
              const f = fields.find(x => x.key === httpApiKey);
              const apiConfig: Record<string, any> = { url_template: v.url_template.trim() };
              if (v.response_path?.trim()) apiConfig.response_path = v.response_path.trim();
              if (v.auth_token_env?.trim()) apiConfig.auth_token_env = v.auth_token_env.trim();
              updateField(httpApiKey, {
                datasource_binding: {
                  ...(f?.datasource_binding || {}),
                  type: 'HTTP_API',
                  api_config: apiConfig,
                },
              });
            }
            setHttpApiKey(null);
          } catch {/* validation error */}
        }}
        width={520}
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="HTTP_API 默认完全关闭"
          description={<Text type="secondary" style={{ fontSize: 12 }}>必须在 application.properties 配 <code>cpq.http-api.allowed-hosts=...</code>. 详见 <code>docs/HTTP_API_安全配置.md</code></Text>}
        />
        <Form form={httpApiForm} layout="vertical" preserve={false}>
          <Form.Item label="URL 模板" name="url_template" rules={[{ required: true, message: 'URL 必填' }]} extra="{字段名} 占位由 driver row 替换并 URL-encode">
            <Input placeholder="https://api.example.com/price/{partNo}" />
          </Form.Item>
          <Form.Item label="response_path (JSON dot-path)" name="response_path" extra="留空 = 返完整 body 字符串. 例: data.unit_price">
            <Input placeholder="data.unit_price" />
          </Form.Item>
          <Form.Item label="auth_token_env (环境变量名)" name="auth_token_env" extra="留空 = 不带鉴权; 严禁明文 token 入 DB">
            <Input placeholder="EXAMPLE_API_TOKEN" />
          </Form.Item>
        </Form>
      </Modal>

      {/* G3: 默认值来源编辑器 (INPUT_NUMBER/TEXT 字段) */}
      <DefaultSourceEditor
        open={defaultSourceKey !== null}
        value={defaultSourceKey ? fields.find(f => f.key === defaultSourceKey)?.default_source : undefined}
        fieldName={defaultSourceKey ? fields.find(f => f.key === defaultSourceKey)?.name : undefined}
        componentId={componentId}
        onClose={() => setDefaultSourceKey(null)}
        onConfirm={(next) => {
          if (defaultSourceKey) {
            updateField(defaultSourceKey, { default_source: next });
          }
          setDefaultSourceKey(null);
        }}
      />

      {/* LIST_FORMULA 配置 Drawer — 源列表选择 + 逐项公式编辑 */}
      <ListFormulaConfigDrawer
        open={listFormulaKey !== null}
        value={listFormulaKey ? fields.find(f => f.key === listFormulaKey)?.list_formula_config : undefined}
        fieldName={listFormulaKey ? fields.find(f => f.key === listFormulaKey)?.name : undefined}
        otherFieldNames={listFormulaKey
          ? fields.filter(f => f.key !== listFormulaKey).map(f => f.name).filter(Boolean)
          : []}
        onClose={() => setListFormulaKey(null)}
        onConfirm={(next) => {
          if (listFormulaKey) {
            updateField(listFormulaKey, { list_formula_config: next });
          }
          setListFormulaKey(null);
        }}
      />

      {/* Plan 3b：条件公式配置 Drawer */}
      <ConditionalFormulaDrawer
        open={condFormulaKey !== null}
        value={condFormulaKey ? fields.find(f => f.key === condFormulaKey)?.conditional_formula as ConditionalFormulaValue | undefined : undefined}
        fieldName={condFormulaKey ? fields.find(f => f.key === condFormulaKey)?.name : undefined}
        formulaOptions={(formulas || []).map(f => ({ label: f.name || '', value: f.name || '' })).filter(o => o.value)}
        columnOptions={fields.map(f => ({ label: f.name, value: f.name })).filter(o => o.value)}
        onClose={() => setCondFormulaKey(null)}
        onConfirm={(next) => {
          if (condFormulaKey) updateField(condFormulaKey, { conditional_formula: next });
          setCondFormulaKey(null);
        }}
      />
    </div>
  );
};

export default FieldConfigTable;
