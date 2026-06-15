import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Alert,
  Button,
  Checkbox,
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
import { buildDraftSnapshot, rebuildFieldKeys, rebuildFormulaKeys } from './componentDraft';
import { readDraft, clearDraft, useDraftAutosave, listAllDrafts } from './useComponentDraft';
import { PlusOutlined, EditOutlined, DeleteOutlined, ExportOutlined, ImportOutlined } from '@ant-design/icons';
import { componentService } from '../../services/componentService';
import { datasourceService } from '../../services/datasourceService';
import { tabJoinFormulaService, type TabDef } from '../../services/tabJoinFormulaService';
import type { DirectoryNode, ComponentItem, FieldItem, ComponentType, FormulaItem, FormulaToken } from './types';
import { newFormulaRow } from './types';
import FieldConfigTable from './FieldConfigTable';
import ComponentImportDrawer from './ComponentImportDrawer';
import ConfigGuideDrawer from './ConfigGuideDrawer';
import PathPickerDrawer from './PathPickerDrawer';
import SqlViewListPanel from './SqlViewListPanel';
import TabJoinFormulaDrawer, { type TabJoinFormulaSavePayload } from '../template/TabJoinFormulaDrawer';
import { tokensToDrawerExpression } from './formulaSerialize';
import { SortableTable, DragHandle } from '../../components/SortableTable';
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
  onConfig: (formula: FormulaItem) => void;
  onAdd: () => void;
  onRename: (key: string, name: string) => void;
  onDelete: (key: string) => void;
  autoFocusKey?: string | null;
}> = ({ formulas, tabDefs, onConfig, onAdd, onRename, onDelete, autoFocusKey }) => {
  const renderExpression = (f: FormulaItem) => {
    const expr = tokensToDrawerExpression(f.expression || [], tabDefs);
    return expr
      ? <span style={{ fontFamily: 'Consolas, Monaco, monospace', fontSize: 12 }}>{expr}</span>
      : <span style={{ color: '#bfbfbf' }}>（空表达式，点「配置」编辑）</span>;
  };
  const columns = [
    { title: '公式名称', dataIndex: 'name', key: 'name', width: 200,
      render: (_: unknown, f: FormulaItem) => (
        <Input
          value={f.name}
          size="small"
          autoFocus={f.key === autoFocusKey}
          placeholder="公式名称（FORMULA 字段名）"
          onChange={(e) => onRename(f.key, e.target.value)}
        />
      ) },
    { title: '表达式', key: 'expr', render: (_: unknown, f: FormulaItem) => renderExpression(f) },
    {
      title: '操作', key: 'action', width: 110,
      render: (_: unknown, f: FormulaItem) => (
        <Space size={4}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => onConfig(f)}>配置</Button>
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
        「添加公式」直接新增一行（可直接命名）；点「配置」弹出统一「页签连表公式配置抽屉」编辑表达式
        （本组件字段引用 + 同目录跨页签引用，聚合可选）。名称随时点单元格改名。
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
    { key: 'drag', width: 40, align: 'center' as const, render: () => <DragHandle /> },
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
      <SortableTable
        rowKey="col_key"
        dataSource={columns}
        columns={tableColumns}
        onReorder={(next) => onChange(next)}
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
  onRefresh: () => void;
  draftCount: number;
  onSaveAllDrafts: () => void;
  draftIds: Set<string>;
}

const MasterList: React.FC<MasterListProps> = ({
  directories, loading, selectedId, checkedIds, searchKeyword, onSearchChange,
  onSelect, onToggleCheck, onCreate, onBatchToggleStatus, onBatchDelete, onRefresh,
  draftCount, onSaveAllDrafts, draftIds,
}) => {
  const [openDirs, setOpenDirs] = useState<Record<string, boolean>>({});
  // 分区折叠：key = `${dirId}:${type}`；读不到即视为折叠（默认全折叠，保持左栏紧凑）
  const [collapsedSecs, setCollapsedSecs] = useState<Record<string, boolean>>({});
  const sectionKey = (dirId: string, type: ComponentType) => `${dirId}:${type}`;
  const toggleSec = (key: string) =>
    setCollapsedSecs((p) => ({ ...p, [key]: !(p[key] ?? true) }));
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  // 任务4: 按目录导入/导出
  const [importTarget, setImportTarget] = useState<{ id: string; name: string } | null>(null);

  const handleExportDir = async (e: React.MouseEvent, dir: DirectoryNode) => {
    e.stopPropagation();
    try {
      await componentService.exportDirectory(dir.id);
      message.success(`已导出目录「${dir.name}」的组件`);
    } catch (err) {
      message.error((err as { message?: string }).message ?? '导出失败');
    }
  };

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
        className={`cmm-card ${cls}${comp.id === selectedId ? ' active' : ''}${checked ? ' checked' : ''}${comp.status === 'DISABLED' ? ' cmm-card-disabled' : ''}`}
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
          {draftIds.has(comp.id) && (
            <span title="有未保存的本地草稿" style={{
              display: 'inline-block', width: 8, height: 8, borderRadius: '50%',
              background: '#faad14', marginLeft: 6, verticalAlign: 'middle',
            }} />
          )}
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
    const key = sectionKey(dir.id, type);
    // 搜索激活时一律展开（不写 state）；否则取手动状态，默认折叠
    const collapsed = searchKeyword ? false : (collapsedSecs[key] ?? true);
    // 折叠态下点「＋」新建：先展开该分区，再走新建
    const createHere = () => { setCollapsedSecs((p) => ({ ...p, [key]: false })); onCreate(dir.id, type); };
    return (
      <div className={`cmm-sec${collapsed ? '' : ' open'}`}>
        <div
          className={`cmm-sec-title ${cls}`}
          onClick={() => { if (!searchKeyword) toggleSec(key); }}
        >
          <span className="cmm-sec-caret">▶</span>
          <span className="cmm-dot" />
          {title}
          <span className="cmm-add" onClick={(e) => { e.stopPropagation(); createHere(); }}>＋</span>
        </div>
        {!collapsed && (
          <>
            {comps.map((c) => renderCard(c, dir, cls === 'tab' ? '' : cls))}
            <div className="cmm-card-add" onClick={createHere}>＋ {addLabel}</div>
          </>
        )}
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
            <span className="cmm-dir-acts">
              <Tooltip title="导出本目录直属组件为 JSON">
                <Button type="text" size="small" icon={<ExportOutlined />}
                  onClick={(e) => handleExportDir(e, dir)} />
              </Tooltip>
              <Tooltip title="导入组件到本目录">
                <Button type="text" size="small" icon={<ImportOutlined />}
                  onClick={(e) => { e.stopPropagation(); setImportTarget({ id: dir.id, name: dir.name }); }} />
              </Tooltip>
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
        <div className="cmm-row" style={{ marginTop: 6 }}>
          <Tooltip title={draftCount === 0 ? '没有未保存的本地草稿' : `保存 ${draftCount} 个组件的本地草稿`}>
            <Button size="small" type="primary" ghost disabled={draftCount === 0} onClick={onSaveAllDrafts}
              style={{ flex: 1 }}>
              保存全部草稿{draftCount > 0 ? ` (${draftCount})` : ''}
            </Button>
          </Tooltip>
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

      {/* 任务4: 按目录导入组件抽屉 */}
      <ComponentImportDrawer
        open={!!importTarget}
        targetDirId={importTarget?.id ?? null}
        targetDirName={importTarget?.name}
        onClose={() => setImportTarget(null)}
        onImported={() => { setImportTarget(null); onRefresh(); }}
      />
    </div>
  );
};

// ─────────────────────────────────────────────────────────────
// Main Container
// ─────────────────────────────────────────────────────────────
/**
 * 最终行键字段 = 当前勾选 ∪ 存量锚定列（候选反查不到 resolvedColumn 的存量行键列）。
 * 勾选只覆盖"有字段可代表"的行键列，不能因勾选覆盖把无字段代表的锚定列丢掉。
 * handleSave（单组件）与草稿 snapshot（供批量保存同源使用）共用此函数，避免批量落库截断 rowKeyFields。
 */
function computeFinalRowKeyFields(
  checked: string[],
  serverRowKeyFields: string[] | undefined,
  candidates: Record<string, import('./types').RowKeyCandidate>,
): string[] {
  const reachableCols = new Set(
    (Object.values(candidates).map((c) => c.resolvedColumn).filter(Boolean) as string[]),
  );
  const preservedAnchors = (serverRowKeyFields ?? []).filter((c) => !reachableCols.has(c));
  return Array.from(new Set([...(checked ?? []), ...preservedAnchors]));
}

const ComponentManagement: React.FC = () => {
  const [directories, setDirectories] = useState<DirectoryNode[]>([]);
  const [selectedComponent, setSelectedComponent] = useState<ComponentItem | null>(null);
  const [fields, setFields] = useState<FieldItem[]>([]);
  const [formulas, setFormulas] = useState<FormulaItem[]>([]);
  const [focusFormulaKey, setFocusFormulaKey] = useState<string | null>(null);
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

  // ── 草稿自动写入 + 恢复 ──
  const baselineUpdatedAt = selectedComponent?.updatedAt;
  // draftListVersion 提前声明：autosave 写入后 bump 它，刷新左栏橙点徽标 + 「保存全部草稿(N)」计数。
  const [draftListVersion, setDraftListVersion] = useState(0);
  const { scheduleSave, flush: flushDraft } = useDraftAutosave(
    selectedComponent?.id, baselineUpdatedAt, 800,
    () => setDraftListVersion((v) => v + 1),
  );
  const restoringRef = useRef(false);
  const [draftBanner, setDraftBanner] = useState<{ kind: 'restored' | 'stale'; componentId: string } | null>(null);

  // ── 全局保存全部草稿 ──
  const allDrafts = useMemo(() => listAllDrafts(), [draftListVersion, selectedComponent?.id]);
  const [saveAllOpen, setSaveAllOpen] = useState(false);
  const [saveAllChecked, setSaveAllChecked] = useState<string[]>([]);

  const openSaveAll = () => {
    const ids = listAllDrafts().map((d) => d.componentId);
    setSaveAllChecked(ids);
    setSaveAllOpen(true);
  };

  const doSaveAll = async () => {
    const targets = listAllDrafts().filter((d) => saveAllChecked.includes(d.componentId));
    const res = await runBatch(
      targets,
      async (d) => {
        const fresh = (await componentService.getById(d.componentId)).data as ComponentItem;
        if (d.env.baselineUpdatedAt && fresh.updatedAt && d.env.baselineUpdatedAt !== fresh.updatedAt) {
          throw new Error('该组件已被他人更新（跳过，避免覆盖）');
        }
        const s = d.env.snapshot;
        const payload: any = { name: fresh.name, fields: s.fields, formulas: s.formulas };
        if (fresh.componentType === 'EXCEL') {
          payload.excelColumns = JSON.stringify(s.excelColumns ?? []);
        } else if (fresh.componentType === 'NORMAL') {
          payload.dataDriverPath = s.dataDriverPath ?? '';
          payload.rowKeyFields = (s.rowKeyFields ?? []).length > 0 ? s.rowKeyFields : undefined;
          payload.bomRecursiveExpand = s.bomRecursiveExpand;
        }
        await componentService.update(d.componentId, payload);
        clearDraft(d.componentId);
      },
      { concurrent: false, rowLabel: (d) => d.componentId },
    );
    setSaveAllOpen(false);
    setDraftListVersion((v) => v + 1);
    loadTree(searchKeyword || undefined);
    if (res.failed.length === 0) {
      message.success(`已保存 ${res.ok} 个组件草稿`);
    } else {
      message.warning(
        `成功 ${res.ok} · 失败/跳过 ${res.failed.length}：` +
        res.failed.map((f) => `${f.row.componentId}(${f.reason})`).join('；')
      );
    }
    if (selectedComponent && saveAllChecked.includes(selectedComponent.id)) {
      setDraftBanner(null);
    }
  };

  // 编辑态任一变化 → 防抖写草稿（恢复中/程序化加载跳过）
  useEffect(() => {
    if (!selectedComponent?.id) return;
    if (restoringRef.current) { restoringRef.current = false; return; }
    // 草稿存"最终行键字段"（含锚定列），与 handleSave 同源，使批量保存(doSaveAll)直接用 snapshot.rowKeyFields 不丢锚定列。
    const draftRowKeyFields = selectedComponent.componentType === 'NORMAL'
      ? computeFinalRowKeyFields(rowKeyFields, selectedComponent.rowKeyFields, rowKeyCandidates)
      : rowKeyFields;
    scheduleSave(buildDraftSnapshot({
      fields, formulas, dataDriverPath, rowKeyFields: draftRowKeyFields, excelColumns, bomRecursiveExpand,
    }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fields, formulas, dataDriverPath, rowKeyFields, excelColumns, bomRecursiveExpand]);

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
    /** 编辑已有公式时，传原始 FormulaToken[]（含 predicate）供抽屉拆分初始化 */
    initialTokens?: FormulaToken[];
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
    } catch (e) {
      // 不清空已有候选：一次瞬时刷新失败若把 map 清成 {}，会让全部字段行键复选框被禁用，
      // 用户感知为"改名后全禁用、重进才好"（重进走 handleSelectComponent 直接刷新重填）。
      // 保留上次候选 + 显式 warn（替代原静默 catch），避免 AP-43 式吞错。
      console.warn('[rowKeyCandidates] refresh failed, keep previous candidates:', e);
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
      restoringRef.current = true; // 跳过本次程序化加载触发的自动写草稿
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

      // ── 草稿自动恢复 ──
      const draft = readDraft(loaded.id);
      if (draft) {
        const stale = !!draft.baselineUpdatedAt && !!loaded.updatedAt
          && draft.baselineUpdatedAt !== loaded.updatedAt;
        if (!stale) {
          restoringRef.current = true;
          setFields(rebuildFieldKeys(draft.snapshot.fields));
          setFormulas(rebuildFormulaKeys(draft.snapshot.formulas));
          setExcelColumns(draft.snapshot.excelColumns ?? []);
          setDataDriverPath(draft.snapshot.dataDriverPath ?? '');
          setRowKeyFields(draft.snapshot.rowKeyFields ?? []);
          setBomRecursiveExpand(!!draft.snapshot.bomRecursiveExpand);
          setDraftBanner({ kind: 'restored', componentId: loaded.id });
        } else {
          setDraftBanner({ kind: 'stale', componentId: loaded.id });
        }
      } else {
        setDraftBanner(null);
      }

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
        // 不丢锚定列：勾选只覆盖"有字段可代表"的行键列；存量无候选列代表的锚定列并回（与 computeFinalRowKeyFields 同源）。
        const finalRowKeyFields = computeFinalRowKeyFields(
          rowKeyFields, selectedComponent.rowKeyFields, rowKeyCandidates,
        );
        payload.dataDriverPath = dataDriverPath ?? '';
        payload.rowKeyFields = finalRowKeyFields.length > 0 ? finalRowKeyFields : undefined;
        payload.bomRecursiveExpand = bomRecursiveExpand;
      }
      await componentService.update(selectedComponent.id, payload);
      message.success('保存成功');
      clearDraft(selectedComponent.id);
      flushDraft();
      setDraftBanner(null);
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

  // ── Formula list wiring (需求4: 添加=行内新增 / 配置=弹抽屉编辑表达式) ──
  // 「添加公式」直接在本地 formulas 状态追加一空表达式行（默认「公式N」、聚焦命名）；
  // 表达式留空待点「配置」进抽屉编辑；整行随组件「保存」入库。
  const addFormulaInline = () => {
    const row: FormulaItem = { ...newFormulaRow(), name: `公式${formulas.length + 1}` };
    setFormulas((prev) => [...prev, row]);
    setFocusFormulaKey(row.key);
  };
  const renameFormula = (key: string, name: string) => {
    setFormulas((prev) => prev.map((f) => (f.key === key ? { ...f, name } : f)));
  };

  // ── Formula drawer wiring ───────────────────────────────────
  const openFormulaForComponent = (formula: FormulaItem | null) => {
    // SUMIF predicate 修复：传原始 tokens 给抽屉，让抽屉内部拆分初始化（splitSumifTokens）。
    // 不再提前调 tokensToDrawerExpression，避免 predicate 在序列化时静默丢失。
    // column.expression 留空串（抽屉不再从 column.expression 回显 token 公式）。
    const column = { expression: '' };
    const initialTokens = formula?.expression && formula.expression.length > 0
      ? formula.expression
      : undefined;
    setFormulaDrawer({ open: true, formulaKey: formula?.key ?? null, excelColIndex: null, column, initialTokens });
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
    setFormulaDrawer({ open: false, formulaKey: null, excelColIndex: null, column: null, initialTokens: undefined });
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
                dataDriverPath={dataDriverPath}
              />
            ),
          },
          {
            key: 'formulas', label: '公式',
            children: (
              <FormulaListPanel
                formulas={formulas}
                tabDefs={tabDefs}
                onConfig={openFormulaForComponent}
                onAdd={addFormulaInline}
                onRename={renameFormula}
                onDelete={(key) => setFormulas((prev) => prev.filter((f) => f.key !== key))}
                autoFocusKey={focusFormulaKey}
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
      onConfig={openFormulaForComponent}
      onAdd={addFormulaInline}
      onRename={renameFormula}
      onDelete={(key) => setFormulas((prev) => prev.filter((f) => f.key !== key))}
      autoFocusKey={focusFormulaKey}
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
        onRefresh={() => loadTree(searchKeyword || undefined)}
        draftCount={allDrafts.length}
        onSaveAllDrafts={openSaveAll}
        draftIds={new Set(allDrafts.map(d => d.componentId))}
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
            {draftBanner?.componentId === selectedComponent.id && (
              <Alert
                style={{ margin: '0 0 8px' }}
                type={draftBanner.kind === 'restored' ? 'warning' : 'info'}
                showIcon
                message={draftBanner.kind === 'restored'
                  ? '检测到未保存的修改，已自动恢复'
                  : '该组件在别处已更新，本地草稿可能过期'}
                action={
                  <Space>
                    {draftBanner.kind === 'stale' && (
                      <Button size="small" onClick={() => {
                        const d = readDraft(selectedComponent.id);
                        if (d) {
                          restoringRef.current = true;
                          setFields(rebuildFieldKeys(d.snapshot.fields));
                          setFormulas(rebuildFormulaKeys(d.snapshot.formulas));
                          setExcelColumns(d.snapshot.excelColumns ?? []);
                          setDataDriverPath(d.snapshot.dataDriverPath ?? '');
                          setRowKeyFields(d.snapshot.rowKeyFields ?? []);
                          setBomRecursiveExpand(!!d.snapshot.bomRecursiveExpand);
                          setDraftBanner({ kind: 'restored', componentId: selectedComponent.id });
                        }
                      }}>仍恢复草稿</Button>
                    )}
                    <Button size="small" danger onClick={() => {
                      clearDraft(selectedComponent.id);
                      flushDraft();
                      setDraftBanner(null);
                      handleSelectComponent(selectedComponent);
                    }}>放弃草稿</Button>
                  </Space>
                }
              />
            )}
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
          initialTokens={formulaDrawer.initialTokens}
          onClose={() => setFormulaDrawer({ open: false, formulaKey: null, excelColIndex: null, column: null, initialTokens: undefined })}
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

      {/* 保存全部草稿确认 Modal */}
      <Modal
        title="保存全部草稿"
        open={saveAllOpen}
        onCancel={() => setSaveAllOpen(false)}
        onOk={doSaveAll}
        okText="确认保存"
        cancelText="取消"
        width={520}
        destroyOnClose
      >
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message="确认后将把以下组件的本地草稿逐个落库（会触发模板 snapshot 同步）。被他人改过的组件将自动跳过。" />
        <Checkbox.Group
          style={{ display: 'flex', flexDirection: 'column', gap: 6 }}
          value={saveAllChecked}
          onChange={(v) => setSaveAllChecked(v as string[])}
          options={listAllDrafts().map((d) => ({
            label: `${d.componentId} · 草稿于 ${new Date(d.env.savedAt).toLocaleString()}`,
            value: d.componentId,
          }))}
        />
      </Modal>
    </div>
  );
};

export default ComponentManagement;
