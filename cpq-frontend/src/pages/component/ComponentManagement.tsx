import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Button,
  Modal,
  Form,
  Input,
  Space,
  Tag,
  message,
  Select,
  Switch,
  Table,
  Spin,
  Tabs,
  Tooltip,
  Empty,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { componentService } from '../../services/componentService';
import { datasourceService } from '../../services/datasourceService';
import { tabJoinFormulaService, type TabDef } from '../../services/tabJoinFormulaService';
import type { DirectoryNode, ComponentItem, FieldItem, ComponentType, FormulaItem, FormulaToken } from './types';
import { newFormulaRow } from './types';
import FieldConfigTable from './FieldConfigTable';
import ConfigGuideDrawer from './ConfigGuideDrawer';
import PathPickerDrawer from './PathPickerDrawer';
import SqlViewListPanel from './SqlViewListPanel';
import TabJoinFormulaDrawer, { type TabJoinFormulaSavePayload } from '../template/TabJoinFormulaDrawer';
import { tokensToDrawerExpression } from './formulaSerialize';
import { runBatch } from '../../components/SelectableTable';
import './styles.css';

// ---- DataSource binding modal (two-step) ----
interface DsItem {
  id: string;
  code: string;
  name: string;
  params?: Array<{ paramCode: string; paramName: string; sourceType: string }>;
}

// step: 'config' = view/edit current binding, 'select' = pick a datasource, 'bind' = bind params for newly selected ds
type DsModalStep = 'config' | 'select' | 'bind';

const DataSourceModal: React.FC<{
  visible: boolean;
  fieldName: string;
  // 接受 FieldItem.datasource_binding 同形态(datasource_id 可选 — BNF_PATH / GLOBAL_VARIABLE 类型字段没有)
  binding?: { datasource_id?: string; datasource_code?: string; datasource_name?: string; param_bindings?: any[] };
  componentFields: FieldItem[];
  onOk: (binding: { datasource_id: string; datasource_code: string; datasource_name: string; param_bindings: any[] }) => void;
  onCancel: () => void;
}> = ({ visible, fieldName, binding, componentFields, onOk, onCancel }) => {
  const [step, setStep] = useState<DsModalStep>('select');
  const [dsList, setDsList] = useState<DsItem[]>([]);
  const [dsLoading, setDsLoading] = useState(false);
  const [dsTotal, setDsTotal] = useState(0);
  const [dsPage, setDsPage] = useState(0);
  const [dsSearch, setDsSearch] = useState('');
  const [selectedDs, setSelectedDs] = useState<DsItem | null>(null);
  const [paramBindings, setParamBindings] = useState<Record<string, string>>({});

  const nonFormulaFields = componentFields.filter(f => f.field_type !== 'FORMULA');

  const hasExistingBinding = !!(binding?.datasource_id);

  useEffect(() => {
    if (!visible) return;
    if (hasExistingBinding) {
      // Load existing datasource details and show config step
      setStep('config');
      setParamBindings({});
      loadExistingDs();
    } else {
      setStep('select');
      setSelectedDs(null);
      setParamBindings({});
      setDsSearch('');
      setDsPage(0);
      loadDsList(0, '');
    }
  }, [visible]);

  const loadExistingDs = async () => {
    if (!binding?.datasource_id) return;
    setDsLoading(true);
    try {
      const res = await datasourceService.getById(binding.datasource_id);
      const ds = res.data;
      setSelectedDs(ds || null);
      // Restore existing param bindings
      const existing: Record<string, string> = {};
      (binding.param_bindings || []).forEach((pb: any) => {
        existing[pb.param_code] = pb.bound_field_name;
      });
      setParamBindings(existing);
    } catch {
      setSelectedDs(null);
    } finally {
      setDsLoading(false);
    }
  };

  const loadDsList = async (page: number, keyword: string) => {
    setDsLoading(true);
    try {
      const res = await datasourceService.list({ page, size: 10, keyword: keyword || undefined });
      const data = res.data;
      if (data?.content) {
        setDsList(data.content);
        setDsTotal(data.totalElements ?? 0);
      } else {
        const items = Array.isArray(data) ? data : [];
        setDsList(items);
        setDsTotal(items.length);
      }
    } catch {
      setDsList([]);
      setDsTotal(0);
    } finally {
      setDsLoading(false);
    }
  };

  const handleSearchChange = (value: string) => {
    setDsSearch(value);
    setDsPage(0);
    loadDsList(0, value);
  };

  const handlePageChange = (page: number) => {
    const p = page - 1; // antd pagination is 1-based
    setDsPage(p);
    loadDsList(p, dsSearch);
  };

  const handleSelectDs = async (record: DsItem) => {
    try {
      const res = await datasourceService.getById(record.id);
      setSelectedDs(res.data || record);
    } catch {
      setSelectedDs(record);
    }
    setParamBindings({});
    setStep('bind');
  };

  const handleSwitchDs = () => {
    setDsSearch('');
    setDsPage(0);
    loadDsList(0, '');
    setStep('select');
  };

  const handleConfirm = () => {
    if (!selectedDs) return;
    const userFieldParams = (selectedDs.params || []).filter((p: any) => p.sourceType === 'USER_FIELD');
    const pb = userFieldParams.map((p: any) => ({
      param_code: p.paramCode,
      param_name: p.paramName,
      bound_field_name: paramBindings[p.paramCode] || '',
    }));
    onOk({
      datasource_id: selectedDs.id,
      datasource_code: selectedDs.code,
      datasource_name: selectedDs.name,
      param_bindings: pb,
    });
  };

  const dsColumns = [
    { title: '编码', dataIndex: 'code', key: 'code' },
    { title: '名称', dataIndex: 'name', key: 'name' },
    {
      title: '操作', key: 'action',
      render: (_: any, record: DsItem) => (
        <Button size="small" type="primary" onClick={() => handleSelectDs(record)}>选择</Button>
      ),
    },
  ];

  const userFieldParams = (selectedDs?.params || []).filter((p: any) => p.sourceType === 'USER_FIELD');

  const renderParamForm = () => (
    <>
      {userFieldParams.length === 0 ? (
        <p style={{ color: '#888' }}>该数据源无需绑定字段参数</p>
      ) : (
        <Form layout="vertical">
          {userFieldParams.map((p: any) => (
            <Form.Item key={p.paramCode} label={`参数：${p.paramName}（${p.paramCode}）`}>
              <Select
                placeholder="选择组件字段"
                value={paramBindings[p.paramCode]}
                onChange={(v) => setParamBindings(prev => ({ ...prev, [p.paramCode]: v }))}
                options={nonFormulaFields.map(f => ({ label: f.name, value: f.name }))}
                allowClear
              />
            </Form.Item>
          ))}
        </Form>
      )}
    </>
  );

  const renderFooter = () => {
    if (step === 'select') {
      return (
        <Space>
          {hasExistingBinding && <Button onClick={() => { loadExistingDs(); setStep('config'); }}>返回当前配置</Button>}
          <Button onClick={onCancel}>取消</Button>
        </Space>
      );
    }
    if (step === 'bind') {
      return (
        <Space>
          <Button onClick={handleSwitchDs}>上一步</Button>
          <Button onClick={onCancel}>取消</Button>
          <Button type="primary" onClick={handleConfirm}>确定</Button>
        </Space>
      );
    }
    // step === 'config'
    return (
      <Space>
        <Button onClick={onCancel}>取消</Button>
        <Button type="primary" onClick={handleConfirm}>保存</Button>
      </Space>
    );
  };

  return (
    <Modal
      title={`配置数据源绑定 — ${fieldName}`}
      open={visible}
      onCancel={onCancel}
      footer={renderFooter()}
      width={600}
    >
      {/* Config step: show current binding with edit */}
      {step === 'config' && (
        <Spin spinning={dsLoading}>
          {selectedDs ? (
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                <span><strong>当前数据源：</strong>{selectedDs.name}（{selectedDs.code}）</span>
                <Button size="small" onClick={handleSwitchDs}>切换数据源</Button>
              </div>
              {renderParamForm()}
            </div>
          ) : (
            <div style={{ textAlign: 'center', padding: '24px 0', color: '#999' }}>
              <p>数据源加载失败或已被删除</p>
              <Button type="primary" size="small" onClick={handleSwitchDs}>重新选择数据源</Button>
            </div>
          )}
        </Spin>
      )}

      {/* Select step: datasource list with search and pagination */}
      {step === 'select' && (
        <Spin spinning={dsLoading}>
          <Input.Search
            placeholder="搜索数据源名称或编码"
            allowClear
            size="small"
            style={{ marginBottom: 12 }}
            value={dsSearch}
            onChange={(e) => handleSearchChange(e.target.value)}
            onSearch={(v) => handleSearchChange(v)}
          />
          <Table
            rowKey="id"
            columns={dsColumns}
            dataSource={dsList}
            size="small"
            pagination={{
              current: dsPage + 1,
              pageSize: 10,
              total: dsTotal,
              onChange: handlePageChange,
              showSizeChanger: false,
              size: 'small',
              showTotal: (total) => `共 ${total} 条`,
            }}
            locale={{ emptyText: '暂无数据源' }}
          />
        </Spin>
      )}

      {/* Bind step: configure params for newly selected datasource */}
      {step === 'bind' && selectedDs && (
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
            <span><strong>已选数据源：</strong>{selectedDs.name}（{selectedDs.code}）</span>
            <Button size="small" onClick={handleSwitchDs}>切换数据源</Button>
          </div>
          {renderParamForm()}
        </div>
      )}
    </Modal>
  );
};

// ---- Helpers ----
function flattenComponents(dirs: DirectoryNode[]): ComponentItem[] {
  const result: ComponentItem[] = [];
  function walk(ds: DirectoryNode[]) {
    for (const d of ds) {
      result.push(...d.components);
      walk(d.children);
    }
  }
  walk(dirs);
  return result;
}

const TYPE_TAG: Record<ComponentType, { label: string; color: string }> = {
  NORMAL: { label: '页签组件', color: 'blue' },
  EXCEL: { label: 'EXCEL 组件', color: 'cyan' },
  SUBTOTAL: { label: '小计组件', color: 'orange' },
};

// ─────────────────────────────────────────────────────────────
// EXCEL 列模型（excel_columns JSON 数组元素）
// ─────────────────────────────────────────────────────────────
interface ExcelColumn {
  col_key: string;
  title: string;
  source_type?: string;
  hidden?: boolean;
  formula?: string;
  fixed_value?: string;
  [k: string]: any;
}

function parseExcelColumns(raw?: string): ExcelColumn[] {
  if (!raw) return [];
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (Array.isArray(parsed)) return parsed;
    if (parsed && Array.isArray(parsed.columns)) return parsed.columns;
    return [];
  } catch {
    return [];
  }
}

// ─────────────────────────────────────────────────────────────
// 公式列表（页签 / 小计共用）—— 去「结果类型」列；点编辑/添加弹 TabJoinFormulaDrawer
// ─────────────────────────────────────────────────────────────
const FormulaListPanel: React.FC<{
  formulas: FormulaItem[];
  tabDefs: TabDef[];
  onEdit: (formula: FormulaItem) => void;
  onAdd: () => void;
  onDelete: (key: string) => void;
}> = ({ formulas, tabDefs, onEdit, onAdd, onDelete }) => {
  const renderExpression = (f: FormulaItem) => {
    const expr = tokensToDrawerExpression(f.expression || [], tabDefs);
    return expr
      ? <span style={{ fontFamily: 'Consolas, Monaco, monospace', fontSize: 12 }}>{expr}</span>
      : <span style={{ color: '#bfbfbf' }}>（空表达式）</span>;
  };
  const columns = [
    { title: '公式名称', dataIndex: 'name', key: 'name', width: 180,
      render: (v: string) => v || <span style={{ color: '#bfbfbf' }}>(未命名)</span> },
    { title: '表达式', key: 'expr', render: (_: unknown, f: FormulaItem) => renderExpression(f) },
    {
      title: '操作', key: 'action', width: 110,
      render: (_: unknown, f: FormulaItem) => (
        <Space size={4}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => onEdit(f)}>编辑</Button>
          <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => onDelete(f.key)} />
        </Space>
      ),
    },
  ];
  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 10 }}>
        <b>公式管理</b>
        <span style={{ marginLeft: 8, color: '#8c8c8c', fontSize: 12 }}>（共 {formulas.length} 个）</span>
        <Button type="primary" size="small" icon={<PlusOutlined />} style={{ marginLeft: 'auto' }} onClick={onAdd}>
          添加公式
        </Button>
      </div>
      <Table
        dataSource={formulas}
        columns={columns}
        rowKey="key"
        pagination={false}
        size="small"
        locale={{ emptyText: '暂无公式，点击"添加公式"' }}
      />
      <div className="cmm-removed-note">
        点「编辑/添加」弹出统一「页签连表公式配置抽屉」（本组件字段引用 + 同目录跨页签引用，聚合可选）。
        已移除：公式「结果类型」列、全局变量插入入口。
      </div>
    </>
  );
};

// ─────────────────────────────────────────────────────────────
// EXCEL 列配置面板 —— col_key / title / source_type / hidden(显示开关) + 列公式
// ─────────────────────────────────────────────────────────────
const ExcelColumnPanel: React.FC<{
  columns: ExcelColumn[];
  onChange: (cols: ExcelColumn[]) => void;
  onEditFormula: (colIndex: number) => void;
}> = ({ columns, onChange, onEditFormula }) => {
  const update = (idx: number, patch: Partial<ExcelColumn>) => {
    onChange(columns.map((c, i) => (i === idx ? { ...c, ...patch } : c)));
  };
  const remove = (idx: number) => onChange(columns.filter((_, i) => i !== idx));
  const add = () =>
    onChange([...columns, { col_key: `col_${columns.length + 1}`, title: '', source_type: 'FIXED_VALUE', hidden: false }]);

  const tableColumns = [
    {
      title: '列名(title)', key: 'title',
      render: (_: unknown, _r: ExcelColumn, idx: number) => (
        <Input size="small" value={columns[idx].title}
          onChange={(e) => update(idx, { title: e.target.value })} placeholder="显示列名" />
      ),
    },
    {
      title: '列键(col_key)', key: 'col_key', width: 150,
      render: (_: unknown, _r: ExcelColumn, idx: number) => (
        <Input size="small" value={columns[idx].col_key}
          onChange={(e) => update(idx, { col_key: e.target.value })}
          placeholder="col_key"
          style={{ fontFamily: 'Consolas, Monaco, monospace', fontSize: 12 }} />
      ),
    },
    {
      title: '来源 / 公式', key: 'source',
      render: (_: unknown, _r: ExcelColumn, idx: number) => {
        const col = columns[idx];
        return (
          <Space size={6} wrap>
            <Select
              size="small" style={{ width: 150 }} value={col.source_type ?? 'FIXED_VALUE'}
              onChange={(v) => update(idx, {
                source_type: v,
                ...(v === 'FIXED_VALUE' ? { formula: undefined } : { fixed_value: undefined }),
              })}
              options={[
                { label: '固定值', value: 'FIXED_VALUE' },
                { label: '页签连表公式', value: 'TAB_JOIN_FORMULA' },
              ]}
            />
            {col.source_type === 'FIXED_VALUE' && (
              <Input
                size="small"
                style={{ width: 180, marginLeft: 8 }}
                placeholder="固定值(留空则空白)"
                value={col.fixed_value ?? ''}
                onChange={(e) => update(idx, { fixed_value: e.target.value })}
              />
            )}
            {col.source_type === 'TAB_JOIN_FORMULA' && (
              <Button type="link" size="small" icon={<EditOutlined />} onClick={() => onEditFormula(idx)}
                style={{ color: '#08979c', fontFamily: 'Consolas, Monaco, monospace', fontSize: 12 }}>
                {col.formula ? `公式：${col.formula.slice(0, 40)}` : '配置公式'}
              </Button>
            )}
          </Space>
        );
      },
    },
    {
      title: '显示', key: 'hidden', width: 64, align: 'center' as const,
      render: (_: unknown, _r: ExcelColumn, idx: number) => (
        <Tooltip title="关闭=隐藏列（仍参与公式计算，但不在 Excel 视图/导出中呈现）">
          <Switch size="small" checked={!columns[idx].hidden}
            onChange={(on) => update(idx, { hidden: !on })} />
        </Tooltip>
      ),
    },
    {
      key: 'action', width: 40,
      render: (_: unknown, _r: ExcelColumn, idx: number) => (
        <Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={() => remove(idx)} />
      ),
    },
  ];

  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 10 }}>
        <b>Excel 列配置</b>
        <span style={{ marginLeft: 8, color: '#8c8c8c', fontSize: 12 }}>
          EXCEL 组件 = 一整张 Excel 表，列 + 公式都在此配（原 Excel 模板已收编）
        </span>
        <Button type="primary" size="small" icon={<PlusOutlined />} style={{ marginLeft: 'auto' }} onClick={add}>
          添加列
        </Button>
      </div>
      <Table
        dataSource={columns.map((c, i) => ({ ...c, _idx: i }))}
        columns={tableColumns}
        rowKey={(_r: any) => String(_r._idx)}
        pagination={false}
        size="small"
        locale={{ emptyText: '暂无列，点击"添加列"' }}
      />
      <div className="cmm-removed-note">
        「显示」开关 = 该列是否在最终 Excel 视图 / 导出中呈现。关闭即<b>隐藏列</b>：仍参与公式计算，但不显示（可作中间量）。
      </div>
    </>
  );
};

// ─────────────────────────────────────────────────────────────
// 左：master 列表（手风琴目录 → 三段卡片 + 批量动作工具栏）
// ─────────────────────────────────────────────────────────────
interface MasterListProps {
  directories: DirectoryNode[];
  loading: boolean;
  selectedId: string | null;
  checkedIds: string[];
  searchKeyword: string;
  onSearchChange: (v: string) => void;
  onSelect: (comp: ComponentItem, dir: DirectoryNode) => void;
  onToggleCheck: (id: string) => void;
  onCreate: (dirId?: string, type?: ComponentType) => void;
  onBatchToggleStatus: (rows: ComponentItem[]) => Promise<void>;
  onBatchDelete: (rows: ComponentItem[]) => Promise<void>;
}

const MasterList: React.FC<MasterListProps> = ({
  directories, loading, selectedId, checkedIds, searchKeyword, onSearchChange,
  onSelect, onToggleCheck, onCreate, onBatchToggleStatus, onBatchDelete,
}) => {
  const [openDirs, setOpenDirs] = useState<Record<string, boolean>>({});
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  // 默认展开第一个目录
  useEffect(() => {
    if (directories.length > 0) {
      setOpenDirs((prev) => (Object.keys(prev).length === 0 ? { [directories[0].id]: true } : prev));
    }
  }, [directories]);

  const allComps = useMemo(() => flattenComponents(directories), [directories]);
  const checkedRows = useMemo(
    () => allComps.filter((c) => checkedIds.includes(c.id)),
    [allComps, checkedIds],
  );

  const toggleDir = (id: string) => setOpenDirs((p) => ({ ...p, [id]: !p[id] }));

  const runToggle = async () => {
    setBusy('toggle');
    try { await onBatchToggleStatus(checkedRows); } finally { setBusy(null); }
  };
  const runDelete = async () => {
    setConfirmDelete(false);
    setBusy('delete');
    try { await onBatchDelete(checkedRows); } finally { setBusy(null); }
  };

  const renderCard = (comp: ComponentItem, dir: DirectoryNode, cls: string) => {
    const checked = checkedIds.includes(comp.id);
    return (
      <div
        key={comp.id}
        className={`cmm-card ${cls}${comp.id === selectedId ? ' active' : ''}${checked ? ' checked' : ''}`}
        onClick={() => onSelect(comp, dir)}
      >
        <span
          className="cmm-chk"
          onClick={(e) => { e.stopPropagation(); onToggleCheck(comp.id); }}
        >
          {checked ? '✓' : ''}
        </span>
        <div className="cmm-c-name">
          {comp.name}
          {comp.status === 'DISABLED' && (
            <span style={{ fontSize: 10, marginLeft: 6, opacity: 0.85 }}>（已停用）</span>
          )}
        </div>
        <div className="cmm-c-code">{comp.code}</div>
      </div>
    );
  };

  const renderSection = (dir: DirectoryNode, type: ComponentType, title: string, cls: string, addLabel: string) => {
    const comps = dir.components.filter((c) => c.componentType === type);
    return (
      <div className="cmm-sec">
        <div className={`cmm-sec-title ${cls}`}>
          <span className="cmm-dot" />
          {title}
          <span className="cmm-add" onClick={() => onCreate(dir.id, type)}>＋</span>
        </div>
        {comps.map((c) => renderCard(c, dir, cls === 'tab' ? '' : cls))}
        <div className="cmm-card-add" onClick={() => onCreate(dir.id, type)}>＋ {addLabel}</div>
      </div>
    );
  };

  const renderDir = (dir: DirectoryNode) => {
    const open = !!openDirs[dir.id];
    const counts = {
      tab: dir.components.filter((c) => c.componentType === 'NORMAL').length,
      excel: dir.components.filter((c) => c.componentType === 'EXCEL').length,
      sub: dir.components.filter((c) => c.componentType === 'SUBTOTAL').length,
    };
    return (
      <React.Fragment key={dir.id}>
        <div className={`cmm-dir${open ? ' open' : ''}`}>
          <div className="cmm-dir-head" onClick={() => toggleDir(dir.id)}>
            <span className="cmm-dir-caret">▶</span>
            <span className="cmm-dir-name">📁 {dir.name}</span>
            <span className="cmm-dir-counts">
              {counts.tab > 0 && <span className="cmm-pill">页签{counts.tab}</span>}
              {counts.excel > 0 && <span className="cmm-pill excel">XLS{counts.excel}</span>}
              {counts.sub > 0 && <span className="cmm-pill sub">小计{counts.sub}</span>}
            </span>
          </div>
          <div className="cmm-dir-body">
            {renderSection(dir, 'NORMAL', '页签组件', 'tab', '新建页签组件')}
            {renderSection(dir, 'EXCEL', 'EXCEL 组件', 'excel', '新建 EXCEL 组件')}
            {renderSection(dir, 'SUBTOTAL', '小计组件', 'sub', '新建小计组件')}
          </div>
        </div>
        {/* 子目录递归 */}
        {dir.children.map((child) => renderDir(child))}
      </React.Fragment>
    );
  };

  const n = checkedRows.length;
  // 选择驱动启用：toggle 任意选中即可；删除任意选中即可。禁用时给出原因。
  const toggleReason = n === 0 ? '请先勾选组件' : '';
  const deleteReason = n === 0 ? '请先勾选组件' : '';

  return (
    <div className="cmm-master">
      <div className="cmm-tools">
        <div className="cmm-row">
          <Input
            placeholder="🔍 搜索组件名 / 编码"
            size="small"
            allowClear
            value={searchKeyword}
            onChange={(e) => onSearchChange(e.target.value)}
            style={{ flex: 1 }}
          />
          <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => onCreate()}>
            新建
          </Button>
        </div>
        {/* 批量动作工具栏：选择驱动启用 + 危险动作 Modal 列项二次确认（runBatch 聚合部分失败） */}
        <div className="cmm-row">
          <Tooltip title={toggleReason}>
            <Button size="small" style={{ flex: 1 }} disabled={n === 0 || !!busy}
              loading={busy === 'toggle'} onClick={runToggle}>
              停用 / 启用
            </Button>
          </Tooltip>
          <Tooltip title={deleteReason}>
            <Button size="small" danger style={{ flex: 1 }} disabled={n === 0 || !!busy}
              loading={busy === 'delete'} onClick={() => setConfirmDelete(true)}>
              删除
            </Button>
          </Tooltip>
        </div>
        <div style={{ marginTop: 6, fontSize: 12, color: n > 0 ? '#0958d9' : '#8c8c8c' }}>
          {n > 0 ? `已选 ${n} 项` : '勾选卡片左侧框可批量操作'}
        </div>
      </div>

      {loading ? (
        <div style={{ padding: 24, textAlign: 'center' }}><Spin /></div>
      ) : directories.length === 0 ? (
        <Empty style={{ marginTop: 40 }} description="暂无目录 / 组件" />
      ) : (
        directories.map((dir) => renderDir(dir))
      )}

      {/* 危险动作：删除 Modal 列出所选项二次确认 */}
      <Modal
        title={`确认删除选中的 ${n} 个组件？`}
        open={confirmDelete}
        onCancel={() => setConfirmDelete(false)}
        onOk={runDelete}
        okText="删除"
        okButtonProps={{ danger: true }}
        cancelText="取消"
        destroyOnClose
      >
        <p style={{ color: '#ff4d4f' }}>删除后该组件的字段 / 公式 / SQL 视图配置将一并移除，且无法恢复。</p>
        <div style={{ color: '#666', marginBottom: 6 }}>所选项：</div>
        <ul style={{ maxHeight: 260, overflowY: 'auto', margin: 0, paddingLeft: 18,
          background: '#fafafa', border: '1px solid #f0f0f0', borderRadius: 4, padding: 12 }}>
          {checkedRows.slice(0, 50).map((r) => (
            <li key={r.id} style={{ marginBottom: 2 }}>{r.name}（{r.code}）</li>
          ))}
          {checkedRows.length > 50 && (
            <li style={{ color: '#999', listStyle: 'none', marginTop: 6 }}>…等共 {checkedRows.length} 项</li>
          )}
        </ul>
      </Modal>
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Main Container
// ─────────────────────────────────────────────────────────────
const ComponentManagement: React.FC = () => {
  const [directories, setDirectories] = useState<DirectoryNode[]>([]);
  const [selectedComponent, setSelectedComponent] = useState<ComponentItem | null>(null);
  const [fields, setFields] = useState<FieldItem[]>([]);
  const [formulas, setFormulas] = useState<FormulaItem[]>([]);
  const [excelColumns, setExcelColumns] = useState<ExcelColumn[]>([]);
  const [dataDriverPath, setDataDriverPath] = useState<string>('');
  const [rowKeyFields, setRowKeyFields] = useState<string[]>([]);
  const [bomRecursiveExpand, setBomRecursiveExpand] = useState<boolean>(false);
  const [rowKeyCandidates, setRowKeyCandidates] = useState<
    Record<string, import('./types').RowKeyCandidate>
  >({});
  const [driverPickerOpen, setDriverPickerOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loadingTree, setLoadingTree] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);

  // Left list selection (checkboxes) + search
  const [checkedIds, setCheckedIds] = useState<string[]>([]);
  const [searchKeyword, setSearchKeyword] = useState('');

  // DataSource modal
  const [dsModalVisible, setDsModalVisible] = useState(false);
  const [dsModalFieldIndex, setDsModalFieldIndex] = useState<number | null>(null);

  // Create-component modal
  const [createModal, setCreateModal] = useState<{ open: boolean; dirId?: string; type: ComponentType }>(
    { open: false, type: 'NORMAL' },
  );
  const [createForm] = Form.useForm();

  // Formula drawer (TabJoinFormulaDrawer) — shared editor
  const [tabDefs, setTabDefs] = useState<TabDef[]>([]);
  const [formulaDrawer, setFormulaDrawer] = useState<{
    open: boolean;
    // editing an existing component formula (NORMAL/SUBTOTAL) → key; null = new
    formulaKey: string | null;
    // editing an EXCEL column formula → index; null = not excel
    excelColIndex: number | null;
    column: any;
  }>({ open: false, formulaKey: null, excelColIndex: null, column: null });

  // Load directory tree
  const loadTree = useCallback(async (keyword?: string) => {
    setLoadingTree(true);
    try {
      const params = keyword ? { keyword } : undefined;
      const res = await componentService.listDirectories(params);
      setDirectories(res.data || []);
    } catch (e: unknown) {
      const err = e as { message?: string };
      message.error('加载目录失败: ' + (err.message ?? ''));
    } finally {
      setLoadingTree(false);
    }
  }, []);

  useEffect(() => {
    loadTree();
  }, [loadTree]);

  // Debounced search
  useEffect(() => {
    const timer = setTimeout(() => {
      loadTree(searchKeyword || undefined);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchKeyword, loadTree]);

  const refreshRowKeyCandidates = useCallback(async (
    compId: string,
    driverPath: string,
    curFields: FieldItem[],
  ) => {
    if (!compId) return;
    try {
      const cleanFields = curFields.map(({ key: _k, ...rest }) => rest);
      const res = await componentService.rowKeyCandidates(compId, {
        dataDriverPath: driverPath ?? '',
        fields: cleanFields,
      });
      const list = (res.data?.candidates ?? []) as import('./types').RowKeyCandidate[];
      const map: Record<string, import('./types').RowKeyCandidate> = {};
      for (const c of list) { if (c.fieldName) map[c.fieldName] = c; }
      setRowKeyCandidates(map);
    } catch {
      setRowKeyCandidates({});
    }
  }, []);

  // 行键候选只依赖字段名 + basic_data_path（反查列名的输入），与 notes/排序/数值等无关。
  const rowKeySignature = useMemo(
    () => fields.map((f) => `${f.name}|${f.basic_data_path ?? ''}`).join(','),
    [fields]
  );

  // Debounced row-key candidate refresh when relevant field signature / driverPath change
  useEffect(() => {
    if (!selectedComponent?.id) return;
    if (selectedComponent.componentType !== 'NORMAL') return;
    const t = setTimeout(() => {
      void refreshRowKeyCandidates(selectedComponent.id, dataDriverPath, fields);
    }, 400);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 故意用 rowKeySignature 代替 fields 引用
  }, [selectedComponent?.id, dataDriverPath, rowKeySignature, refreshRowKeyCandidates]);

  // Load tab-defs (同目录组件页签集) when a component is selected — drives formula drawer + expression rendering
  useEffect(() => {
    if (!selectedComponent?.id) { setTabDefs([]); return; }
    tabJoinFormulaService
      .tabDefsByComponent(selectedComponent.id)
      .then((res: any) => setTabDefs(Array.isArray(res?.data) ? res.data : []))
      .catch(() => setTabDefs([]));
  }, [selectedComponent?.id]);

  // Load component when selected from list
  const handleSelectComponent = async (comp: ComponentItem) => {
    try {
      const res = await componentService.getById(comp.id);
      const loaded = res.data as ComponentItem;
      setSelectedComponent(loaded);
      setFields(
        (loaded.fields || []).map((f: FieldItem, i: number) => ({
          ...f,
          key: `field-${i}-${Date.now()}`,
        }))
      );
      setFormulas(
        (loaded.formulas || []).map((f: FormulaItem, i: number) => ({
          ...f,
          key: `formula-${i}-${Date.now()}`,
        }))
      );
      setExcelColumns(parseExcelColumns(loaded.excelColumns));
      setDataDriverPath(loaded.dataDriverPath ?? '');
      setRowKeyFields(loaded.rowKeyFields ?? []);
      setBomRecursiveExpand((loaded as any).bomRecursiveExpand === true); // 默认关
      if (loaded.componentType === 'NORMAL') {
        void refreshRowKeyCandidates(
          loaded.id,
          loaded.dataDriverPath ?? '',
          (loaded.fields || []).map((f: FieldItem, i: number) => ({ ...f, key: `field-${i}-${Date.now()}` })),
        );
      } else {
        setRowKeyCandidates({});
      }
    } catch (e: unknown) {
      const err = e as { message?: string };
      message.error('加载组件失败: ' + (err.message ?? ''));
    }
  };

  // Save component
  const handleSave = async () => {
    if (!selectedComponent) return;
    setSaving(true);
    try {
      const cleanFields = fields.map(({ key: _k, ...rest }) => rest);
      const cleanFormulas = formulas.map(({ key: _k, ...rest }) => rest);
      const payload: any = {
        name: selectedComponent.name,
        fields: cleanFields,
        formulas: cleanFormulas,
      };
      if (selectedComponent.componentType === 'EXCEL') {
        payload.excelColumns = JSON.stringify(excelColumns);
      } else if (selectedComponent.componentType === 'NORMAL') {
        // 不丢锚定列：勾选只覆盖"有字段可代表"的行键列；存量无候选列代表的锚定列并回。
        const reachableCols = new Set(
          (Object.values(rowKeyCandidates)
            .map((c) => c.resolvedColumn)
            .filter(Boolean) as string[])
        );
        const preservedAnchors = (selectedComponent.rowKeyFields ?? []).filter(
          (c) => !reachableCols.has(c)
        );
        const finalRowKeyFields = Array.from(new Set([...rowKeyFields, ...preservedAnchors]));
        payload.dataDriverPath = dataDriverPath ?? '';
        payload.rowKeyFields = finalRowKeyFields.length > 0 ? finalRowKeyFields : undefined;
        payload.bomRecursiveExpand = bomRecursiveExpand;
      }
      await componentService.update(selectedComponent.id, payload);
      message.success('保存成功');
      loadTree(searchKeyword || undefined);
      const res = await componentService.getById(selectedComponent.id);
      setSelectedComponent(res.data);
    } catch (e: unknown) {
      const err = e as { message?: string };
      message.error('保存失败: ' + (err.message ?? ''));
    } finally {
      setSaving(false);
    }
  };

  // DataSource modal handlers
  const handleOpenDsModal = (fieldIndex: number) => {
    setDsModalFieldIndex(fieldIndex);
    setDsModalVisible(true);
  };

  const handleDsModalOk = (binding: { datasource_id: string; datasource_code: string; datasource_name: string; param_bindings: any[] }) => {
    if (dsModalFieldIndex === null) return;
    const updated = fields.map((f, i) =>
      i === dsModalFieldIndex ? { ...f, datasource_binding: binding } : f
    );
    setFields(updated);
    setDsModalVisible(false);
  };

  // ── Formula drawer wiring ───────────────────────────────────
  const openFormulaForComponent = (formula: FormulaItem | null) => {
    const column = formula
      ? { expression: tokensToDrawerExpression(formula.expression || [], tabDefs) }
      : { expression: '' };
    setFormulaDrawer({ open: true, formulaKey: formula?.key ?? null, excelColIndex: null, column });
  };
  const openFormulaForExcelColumn = (idx: number) => {
    const col = excelColumns[idx];
    setFormulaDrawer({
      open: true, formulaKey: null, excelColIndex: idx,
      column: { expression: col?.formula ?? '' },
    });
  };

  const handleFormulaSave = (payload: TabJoinFormulaSavePayload) => {
    if (payload.kind === 'tokens') {
      // NORMAL / SUBTOTAL: write FormulaToken[] into component.formulas
      setFormulas((prev) => {
        if (formulaDrawer.formulaKey) {
          return prev.map((f) =>
            f.key === formulaDrawer.formulaKey
              ? { ...f, expression: payload.tokens as FormulaToken[] }
              : f,
          );
        }
        // new formula — keep existing name if blank prompt later; default name
        const row = newFormulaRow();
        return [...prev, { ...row, name: `公式${prev.length + 1}`, expression: payload.tokens as FormulaToken[] }];
      });
    } else {
      // EXCEL: write the string expression into the excel column
      const idx = formulaDrawer.excelColIndex;
      if (idx !== null) {
        setExcelColumns((prev) =>
          prev.map((c, i) =>
            i === idx ? { ...c, source_type: 'TAB_JOIN_FORMULA', formula: payload.column?.expression ?? '' } : c,
          ),
        );
      }
    }
    setFormulaDrawer({ open: false, formulaKey: null, excelColIndex: null, column: null });
  };

  // ── Create component ────────────────────────────────────────
  const openCreate = (dirId?: string, type: ComponentType = 'NORMAL') => {
    setCreateModal({ open: true, dirId, type });
    createForm.resetFields();
    createForm.setFieldsValue({ componentType: type, directoryId: dirId });
  };
  const handleCreateOk = async () => {
    try {
      const values = await createForm.validateFields();
      const res = await componentService.create({
        name: values.name,
        directoryId: values.directoryId || createModal.dirId || null,
        componentType: values.componentType || 'NORMAL',
        fields: [],
        formulas: [],
        ...(values.componentType === 'EXCEL' ? { excelColumns: '[]' } : {}),
      });
      message.success('组件已创建');
      setCreateModal({ open: false, type: 'NORMAL' });
      await loadTree(searchKeyword || undefined);
      if (res?.data) handleSelectComponent(res.data);
    } catch (e: unknown) {
      const err = e as { message?: string };
      if (err.message) message.error(err.message);
    }
  };

  // ── Batch actions (runBatch + Modal-confirm semantics) ──────
  const handleBatchToggleStatus = async (rows: ComponentItem[]) => {
    await runBatch(
      rows,
      async (row) => { await componentService.toggleStatus(row.id); },
      { rowLabel: (r) => `${r.name}（${r.code}）`, successMsg: `已切换 ${rows.length} 项状态` },
    );
    setCheckedIds([]);
    loadTree(searchKeyword || undefined);
  };
  const handleBatchDelete = async (rows: ComponentItem[]) => {
    await runBatch(
      rows,
      async (row) => { await componentService.delete(row.id); },
      { rowLabel: (r) => `${r.name}（${r.code}）`, successMsg: `已删除 ${rows.length} 项` },
    );
    // 若删的是当前选中组件，清空详情
    if (selectedComponent && rows.some((r) => r.id === selectedComponent.id)) {
      setSelectedComponent(null);
    }
    setCheckedIds([]);
    loadTree(searchKeyword || undefined);
  };

  const toggleCheck = (id: string) =>
    setCheckedIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

  const dsField = dsModalFieldIndex !== null ? fields[dsModalFieldIndex] : undefined;

  // ── Detail panel renderers ──────────────────────────────────
  const renderNormalDetail = () => (
    <>
      {/* 合并配置行：数据驱动路径 + 核价 BOM 递归（树表纯展示已隐藏） */}
      <div className="cmm-cfg-row">
        <span className="cmm-lbl">数据驱动路径(可选)：</span>
        <Input
          value={dataDriverPath}
          onChange={(e) => setDataDriverPath(e.target.value)}
          placeholder="选 SQL 视图后自动生成 $view 路径"
          size="small"
          style={{ flex: 1, minWidth: 220, fontFamily: 'Consolas, Monaco, monospace', fontSize: 12 }}
          allowClear
        />
        <Button size="small" onClick={() => setDriverPickerOpen(true)}>选择路径</Button>
        <span className="cmm-divider-v" />
        <span className="cmm-lbl">核价 BOM 递归展开：</span>
        <Tooltip title="勾选=核价时按 material_bom_item 闭包递归展开子料号；不勾=按根料号普通取数。仅核价侧生效。">
          <Switch size="small" checked={bomRecursiveExpand} onChange={setBomRecursiveExpand} />
        </Tooltip>
      </div>
      <PathPickerDrawer
        open={driverPickerOpen}
        onClose={() => setDriverPickerOpen(false)}
        initialPath={dataDriverPath}
        componentId={selectedComponent?.id}
        onConfirm={(path) => { setDataDriverPath(path); setDriverPickerOpen(false); }}
      />
      <Tabs
        size="small"
        items={[
          {
            key: 'fields', label: '字段配置',
            children: (
              <FieldConfigTable
                fields={fields}
                formulas={formulas}
                componentId={selectedComponent?.id}
                onChange={(newFields) => {
                  // Detect field renames and sync formula token values
                  const renames: Record<string, string> = {};
                  for (const nf of newFields) {
                    const old = fields.find(f => f.key === nf.key);
                    if (old && old.name && nf.name && old.name !== nf.name) renames[old.name] = nf.name;
                  }
                  setFields(newFields);
                  if (Object.keys(renames).length > 0) {
                    setFormulas(prev => prev.map(f => ({
                      ...f,
                      name: renames[f.name] ?? f.name,
                      expression: f.expression.map(token =>
                        (token.type === 'field' && token.value && renames[token.value])
                          ? { ...token, value: renames[token.value], label: renames[token.value] }
                          : token),
                    })));
                  }
                }}
                onConfigDatasource={handleOpenDsModal}
                rowKeyFields={rowKeyFields}
                candidatesByField={rowKeyCandidates}
                onToggleRowKey={(col, checked) => {
                  setRowKeyFields((prev) => {
                    const set = new Set(prev);
                    if (checked) set.add(col); else set.delete(col);
                    return Array.from(set);
                  });
                }}
              />
            ),
          },
          {
            key: 'formulas', label: '公式',
            children: (
              <FormulaListPanel
                formulas={formulas}
                tabDefs={tabDefs}
                onEdit={openFormulaForComponent}
                onAdd={() => openFormulaForComponent(null)}
                onDelete={(key) => setFormulas((prev) => prev.filter((f) => f.key !== key))}
              />
            ),
          },
          {
            key: 'sql-views', label: 'SQL 视图',
            children: selectedComponent ? <SqlViewListPanel componentId={selectedComponent.id} /> : null,
          },
        ]}
      />
    </>
  );

  const renderSubtotalDetail = () => (
    <FormulaListPanel
      formulas={formulas}
      tabDefs={tabDefs}
      onEdit={openFormulaForComponent}
      onAdd={() => openFormulaForComponent(null)}
      onDelete={(key) => setFormulas((prev) => prev.filter((f) => f.key !== key))}
    />
  );

  const renderExcelDetail = () => (
    <ExcelColumnPanel
      columns={excelColumns}
      onChange={setExcelColumns}
      onEditFormula={openFormulaForExcelColumn}
    />
  );

  const componentType = selectedComponent?.componentType ?? 'NORMAL';

  return (
    <div className="cm-layout">
      {/* 左：master 列表 */}
      <MasterList
        directories={directories}
        loading={loadingTree}
        selectedId={selectedComponent?.id ?? null}
        checkedIds={checkedIds}
        searchKeyword={searchKeyword}
        onSearchChange={setSearchKeyword}
        onSelect={handleSelectComponent}
        onToggleCheck={toggleCheck}
        onCreate={openCreate}
        onBatchToggleStatus={handleBatchToggleStatus}
        onBatchDelete={handleBatchDelete}
      />

      {/* 右：详情（内嵌，非抽屉） */}
      <div className="cmm-detail">
        {selectedComponent ? (
          <div className="cmm-detail-wrap">
            <div className="cmm-detail-head">
              <span className="cmm-t">{selectedComponent.name}</span>
              <Tag color={TYPE_TAG[componentType].color}>
                {TYPE_TAG[componentType].label} · {selectedComponent.code}
              </Tag>
              <div className="cmm-acts">
                <Button size="small" onClick={() => setGuideOpen(true)}>配置帮助</Button>
                <Button type="primary" size="small" loading={saving} onClick={handleSave}>保存</Button>
              </div>
            </div>
            <div className="cmm-panel">
              {componentType === 'EXCEL'
                ? renderExcelDetail()
                : componentType === 'SUBTOTAL'
                  ? renderSubtotalDetail()
                  : renderNormalDetail()}
            </div>
          </div>
        ) : (
          <div className="cmm-detail-empty">
            <div className="cmm-ic">🗂️</div>
            <div>从左侧列表选择一个组件查看 / 编辑</div>
            <div style={{ fontSize: 12 }}>组件 = 报价/核价中的一个页签或 Excel 表；定义列结构与公式</div>
            <Button type="primary" ghost size="small" style={{ marginTop: 8 }} onClick={() => setGuideOpen(true)}>
              查看配置帮助
            </Button>
          </div>
        )}
      </div>

      <ConfigGuideDrawer open={guideOpen} onClose={() => setGuideOpen(false)} />

      {/* 公式编辑：统一 TabJoinFormulaDrawer（页签/小计 → token；EXCEL → 字符串列） */}
      {selectedComponent && (
        <TabJoinFormulaDrawer
          open={formulaDrawer.open}
          componentId={selectedComponent.id}
          componentType={componentType}
          selfRowKeyFields={rowKeyFields}
          column={formulaDrawer.column}
          onClose={() => setFormulaDrawer({ open: false, formulaKey: null, excelColIndex: null, column: null })}
          onSave={handleFormulaSave}
        />
      )}

      {/* DataSource binding modal */}
      <DataSourceModal
        visible={dsModalVisible}
        fieldName={dsField?.name || ''}
        binding={dsField?.datasource_binding}
        componentFields={fields}
        onOk={handleDsModalOk}
        onCancel={() => setDsModalVisible(false)}
      />

      {/* 新建组件 Modal */}
      <Modal
        title="新建组件"
        open={createModal.open}
        onOk={handleCreateOk}
        onCancel={() => setCreateModal({ open: false, type: 'NORMAL' })}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={createForm} layout="vertical">
          <Form.Item name="componentType" label="组件类型" initialValue="NORMAL">
            <Select
              options={[
                { value: 'NORMAL', label: '页签组件' },
                { value: 'EXCEL', label: 'EXCEL 组件' },
                { value: 'SUBTOTAL', label: '小计组件' },
              ]}
            />
          </Form.Item>
          <Form.Item name="name" label="组件名称" rules={[{ required: true, message: '请输入组件名称' }]}>
            <Input placeholder="例：投料成本表" />
          </Form.Item>
          {directories.length > 0 && (
            <Form.Item name="directoryId" label="所属目录">
              <Select placeholder="选择目录（可选）" allowClear
                options={directories.map((d) => ({ value: d.id, label: d.name }))} />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </div>
  );
};

export default ComponentManagement;
