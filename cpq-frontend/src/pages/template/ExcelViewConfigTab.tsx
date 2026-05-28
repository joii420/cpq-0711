import React, { useState, useEffect, useRef } from 'react';
import {
  Button, Input, Space, message, Typography, Tag, Tooltip, Switch, Select, Drawer, Form, Dropdown,
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, ArrowUpOutlined, ArrowDownOutlined, SaveOutlined,
  EditOutlined, FunctionOutlined, DownOutlined,
} from '@ant-design/icons';
import { templateService } from '../../services/templateService';
import VariableLabelPickerDrawer from '../../components/VariableLabelPickerDrawer';
import { variableLabelService, type VariableLabel } from '../../services/variableLabelService';
import PathPickerDrawer from '../component/PathPickerDrawer';

const { Text } = Typography;

// V149 Stage 3+D 清理: Excel 视图配置只做"配 Excel 列引用视图数据"一件事.
// VARIABLE 取数(绑 $view.col 或 {code}); FORMULA 模板层公式(=[X]+[Y] 等); 老 4 种保留向后兼容.
type SourceType = 'VARIABLE' | 'FORMULA' | 'PRODUCT_ATTRIBUTE' | 'COMPONENT_FIELD' | 'EXCEL_FORMULA' | 'FIXED_VALUE';

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
  // PathPickerDrawer 状态（SQL 视图路径选择）
  const [pathPickerOpen, setPathPickerOpen] = useState(false);
  const [pathPickerColIdx, setPathPickerColIdx] = useState<number | null>(null);
  // 公式编辑 Drawer 状态（FORMULA 列）
  const [formulaDrawerOpen, setFormulaDrawerOpen] = useState(false);
  const [formulaDrawerColIdx, setFormulaDrawerColIdx] = useState<number | null>(null);
  const [formulaDraft, setFormulaDraft] = useState<string>('');
  const formulaTextAreaRef = useRef<any>(null);

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

  const addColumn = (sourceType: 'VARIABLE' | 'FORMULA' = 'VARIABLE') => {
    setColumns(prev => {
      const k = getNextColKey(prev);
      const base: ExcelViewColumn = {
        col_key: k,
        title: '新列',
        source_type: sourceType,
      };
      if (sourceType === 'VARIABLE') base.variable_path = '';
      else base.formula = '';
      return [...prev, base];
    });
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

  const openPathPicker = (idx: number) => {
    setPathPickerColIdx(idx);
    setPathPickerOpen(true);
  };

  const handlePathPickerConfirm = (path: string) => {
    if (pathPickerColIdx != null) {
      updateColumn(pathPickerColIdx, { variable_path: path, source_type: 'VARIABLE' });
    }
    setPathPickerOpen(false);
    setPathPickerColIdx(null);
  };

  // 公式编辑 Drawer
  const openFormulaDrawer = (idx: number) => {
    setFormulaDrawerColIdx(idx);
    setFormulaDraft(columns[idx]?.formula || '');
    setFormulaDrawerOpen(true);
  };
  const saveFormulaDrawer = () => {
    if (formulaDrawerColIdx == null) return;
    updateColumn(formulaDrawerColIdx, { formula: formulaDraft, source_type: 'FORMULA' });
    setFormulaDrawerOpen(false);
    setFormulaDrawerColIdx(null);
  };
  const insertFormulaToken = (token: string) => {
    const el = formulaTextAreaRef.current?.resizableTextArea?.textArea as HTMLTextAreaElement | undefined;
    if (!el) {
      setFormulaDraft(prev => (prev || '') + token);
      return;
    }
    const start = el.selectionStart ?? formulaDraft.length;
    const end = el.selectionEnd ?? formulaDraft.length;
    const next = formulaDraft.slice(0, start) + token + formulaDraft.slice(end);
    setFormulaDraft(next);
    // 光标移到插入之后
    requestAnimationFrame(() => {
      el.focus();
      const pos = start + token.length;
      el.setSelectionRange(pos, pos);
    });
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
    if (col.source_type === 'FORMULA') {
      const f = col.formula || '';
      return (
        <div style={{ width: '100%' }}>
          <Space.Compact style={{ width: '100%' }}>
            <Input
              size="small"
              readOnly
              value={f}
              placeholder="点击右侧按钮编辑公式（如 =[I]+[J]）"
              disabled={!isDraft}
              onClick={() => isDraft && openFormulaDrawer(index)}
              style={{
                cursor: isDraft ? 'pointer' : 'not-allowed',
                fontFamily: 'Consolas, Monaco, monospace',
              }}
            />
            <Button
              size="small"
              disabled={!isDraft}
              icon={<EditOutlined />}
              onClick={() => openFormulaDrawer(index)}
              title="编辑公式表达式（用 [X] 引用其他列）"
            >
              编辑公式
            </Button>
          </Space.Compact>
          {f && (
            <div style={{ marginTop: 4, fontSize: 11, lineHeight: '18px' }}>
              <Tag color="purple" icon={<FunctionOutlined />}>FORMULA</Tag>
              <span style={{ color: '#888', fontSize: 10 }}>
                模板层计算 · 第二遍求值 · 不查 SQL 视图
              </span>
            </div>
          )}
        </div>
      );
    }
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
              placeholder="点击右侧按钮选择路径"
              title={path || undefined}
              disabled={!isDraft}
              onClick={() => isDraft && openLabelPicker(index)}
              style={{
                cursor: isDraft ? 'pointer' : 'not-allowed',
                fontFamily: label ? undefined : 'Consolas, Monaco, monospace',
                fontWeight: label ? 500 : undefined,
              }}
            />
            <Button
              size="small"
              disabled={!isDraft}
              onClick={() => openLabelPicker(index)}
              title="从已命名字段库选（按业务分类显示中文名），适合普通用户"
            >
              📚 字段库
            </Button>
            <Button
              size="small"
              disabled={!isDraft}
              onClick={() => openPathPicker(index)}
              title="从本模板 SQL 视图选择路径（$视图名.列名 格式）"
            >
              🗄 SQL 视图
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
                <th style={{ ...thStyle, width: 120 }}>类型</th>
                <th style={thStyle}>值 / 公式</th>
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
                  <td style={tdStyle}>
                    {(col.source_type === 'VARIABLE' || col.source_type === 'FORMULA') ? (
                      <Select
                        size="small"
                        style={{ width: 110 }}
                        value={col.source_type}
                        disabled={!isDraft}
                        onChange={v => {
                          if (v === 'FORMULA') {
                            updateColumn(index, { source_type: 'FORMULA', variable_path: undefined, formula: col.formula || '' });
                          } else {
                            updateColumn(index, { source_type: 'VARIABLE', formula: undefined, variable_path: col.variable_path || '' });
                          }
                        }}
                        options={[
                          { label: '变量 VARIABLE', value: 'VARIABLE' },
                          { label: '公式 FORMULA', value: 'FORMULA' },
                        ]}
                      />
                    ) : (
                      <Tag color="default" style={{ fontSize: 11 }}>
                        {LEGACY_TYPE_LABEL[col.source_type] || col.source_type}
                      </Tag>
                    )}
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
        <Dropdown
          menu={{
            items: [
              {
                key: 'VARIABLE',
                label: '变量列（从 SQL 视图取数）',
                icon: <PlusOutlined />,
                onClick: () => addColumn('VARIABLE'),
              },
              {
                key: 'FORMULA',
                label: '公式列（基于其他列计算，如 =[I]+[J]）',
                icon: <FunctionOutlined />,
                onClick: () => addColumn('FORMULA'),
              },
            ],
          }}
        >
          <Button type="dashed" size="small" style={{ marginTop: 12 }}>
            <Space>
              <PlusOutlined />
              添加列
              <DownOutlined />
            </Space>
          </Button>
        </Dropdown>
      )}

      <VariableLabelPickerDrawer
        open={labelPickerOpen}
        onClose={() => { setLabelPickerOpen(false); setLabelPickerColIdx(null); }}
        onPick={handleLabelPick}
        initialPath={labelPickerColIdx != null ? (columns[labelPickerColIdx]?.variable_path || '') : ''}
      />

      <PathPickerDrawer
        open={pathPickerOpen}
        onClose={() => { setPathPickerOpen(false); setPathPickerColIdx(null); }}
        initialPath={pathPickerColIdx != null ? (columns[pathPickerColIdx]?.variable_path || '') : ''}
        onConfirm={handlePathPickerConfirm}
        ownerContext={{ type: 'TEMPLATE', templateId }}
        defaultTab="sql-view"
        legacyPathPolicy={isDraft ? 'WARN_WITH_MIGRATION_SUGGEST' : 'BLOCK'}
      />

      {/* 公式编辑 Drawer (FORMULA 列才会打开) */}
      <Drawer
        title={
          formulaDrawerColIdx != null
            ? `编辑公式 —— 列 ${columns[formulaDrawerColIdx]?.col_key}（${columns[formulaDrawerColIdx]?.title}）`
            : '编辑公式'
        }
        placement="right"
        width={720}
        open={formulaDrawerOpen}
        onClose={() => { setFormulaDrawerOpen(false); setFormulaDrawerColIdx(null); }}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Button onClick={() => setFormulaDrawerOpen(false)} style={{ marginRight: 8 }}>取消</Button>
            <Button type="primary" onClick={saveFormulaDrawer}>保存</Button>
          </div>
        }
      >
        <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
          示例：<code>=[I]+[J]</code>(总成本=材料成本+加工费)、<code>=[I]*1.13</code>(含税)、<code>=[F]&gt;10 ? [I]*0.9 : [I]</code>(阶梯折扣)。
          <br/>语法：<code>[X]</code> 引用本模板其他列;<code>{'{code}'}</code> 引用 lineItem 简写;支持 <code>+ - * / ( )</code> 四则、<code>&lt; &gt; == != &amp;&amp; || ? :</code> 比较 / 逻辑 / 三元。
          <br/>限制:**只能引用本行其他 VARIABLE 列**(单遍依赖),不支持 SUM / IF / VLOOKUP 等 Excel 函数,不支持字符串字面量。
        </Typography.Paragraph>
        <Form layout="vertical">
          <Form.Item label="公式表达式">
            <Input.TextArea
              ref={formulaTextAreaRef}
              rows={4}
              value={formulaDraft}
              onChange={(e) => setFormulaDraft(e.target.value)}
              placeholder="如 =[I]+[J]"
              style={{ fontFamily: 'Consolas, Monaco, monospace' }}
            />
          </Form.Item>
          <Form.Item label="快速插入 列引用（点击追加到光标位置）">
            <Space size={[4, 4]} wrap>
              {columns
                .filter((_, i) => i !== formulaDrawerColIdx)
                .map((c) => (
                  <Tag
                    key={c.col_key}
                    color={c.source_type === 'FORMULA' ? 'purple' : 'blue'}
                    style={{ cursor: 'pointer', userSelect: 'none' }}
                    onClick={() => insertFormulaToken(`[${c.col_key}]`)}
                  >
                    [{c.col_key}] {c.title}
                  </Tag>
                ))}
            </Space>
          </Form.Item>
        </Form>
      </Drawer>
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
