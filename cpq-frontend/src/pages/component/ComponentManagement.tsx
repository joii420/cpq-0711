import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Button,
  Modal,
  Form,
  Input,
  Space,
  Tag,
  Badge,
  message,
  Steps,
  Select,
  Switch,
  Table,
  Spin,
  Tabs,
} from 'antd';
import { FileOutlined, PlusOutlined, QuestionCircleOutlined, BulbOutlined } from '@ant-design/icons';
import { componentService } from '../../services/componentService';
import { datasourceService } from '../../services/datasourceService';
import { compileGlobalVariableToPath } from '../../services/globalVariableService';
import type { DirectoryNode, ComponentItem, FieldItem, FormulaToken } from './types';
import { newFieldRow } from './types';
import ComponentTree from './ComponentTree';
import HeaderPreview from './HeaderPreview';
import FieldConfigTable from './FieldConfigTable';
import FormulaBuilder from './FormulaBuilder';
import FieldPanel from './FieldPanel';
import ConfigGuideDrawer from './ConfigGuideDrawer';
import PathPickerDrawer from './PathPickerDrawer';
import SqlViewListPanel from './SqlViewListPanel';
import CrossTabRefDrawer from './CrossTabRefDrawer';
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

// ---- Main Container ----
const ComponentManagement: React.FC = () => {
  const [directories, setDirectories] = useState<DirectoryNode[]>([]);
  const [selectedComponent, setSelectedComponent] = useState<ComponentItem | null>(null);
  const [fields, setFields] = useState<FieldItem[]>([]);
  const [formulas, setFormulas] = useState<import('./types').FormulaItem[]>([]);
  const [dataDriverPath, setDataDriverPath] = useState<string>('');
  const [rowKeyFields, setRowKeyFields] = useState<string[]>([]);
  const [treeConfig, setTreeConfig] = useState<import('./types').TreeConfig | null>(null);
  const [rowKeyCandidates, setRowKeyCandidates] = useState<
    Record<string, import('./types').RowKeyCandidate>
  >({});
  const [driverPickerOpen, setDriverPickerOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loadingTree, setLoadingTree] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const [crossTabDrawerOpen, setCrossTabDrawerOpen] = useState(false);

  // Active formula key (shared with FormulaBuilder)
  const [activeFormulaKey, setActiveFormulaKey] = useState<string | null>(null);

  // Search
  const [searchKeyword, setSearchKeyword] = useState('');

  // DataSource modal
  const [dsModalVisible, setDsModalVisible] = useState(false);
  const [dsModalFieldIndex, setDsModalFieldIndex] = useState<number | null>(null);

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

  // Debounced search
  useEffect(() => {
    const timer = setTimeout(() => {
      loadTree(searchKeyword || undefined);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchKeyword, loadTree]);

  // 行键候选只依赖字段名 + basic_data_path（反查列名的输入），与 notes/排序/数值等无关。
  // 用稳定签名做依赖，避免无关字段编辑触发对 DB 端点的候选刷新（code-review Important）。
  const rowKeySignature = useMemo(
    () => fields.map((f) => `${f.name}|${f.basic_data_path ?? ''}`).join(','),
    [fields]
  );

  // Debounced row-key candidate refresh when relevant field signature / driverPath change
  useEffect(() => {
    if (!selectedComponent?.id) return;
    const t = setTimeout(() => {
      void refreshRowKeyCandidates(selectedComponent.id, dataDriverPath, fields);
    }, 400);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 故意用 rowKeySignature 代替 fields 引用，避免无关编辑刷新
  }, [selectedComponent?.id, dataDriverPath, rowKeySignature, refreshRowKeyCandidates]);

  // Load component when selected from tree
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
        (loaded.formulas || []).map((f: import('./types').FormulaItem, i: number) => ({
          ...f,
          key: `formula-${i}-${Date.now()}`,
        }))
      );
      setDataDriverPath(loaded.dataDriverPath ?? '');
      setRowKeyFields(loaded.rowKeyFields ?? []);
      setTreeConfig((loaded as any).treeConfig
        ? {
            idField: (loaded as any).treeConfig.idField,
            parentField: (loaded as any).treeConfig.parentField,
            defaultExpanded: (loaded as any).treeConfig.defaultExpanded ?? true,
          }
        : null);
      void refreshRowKeyCandidates(
        loaded.id,
        loaded.dataDriverPath ?? '',
        (loaded.fields || []).map((f: FieldItem, i: number) => ({ ...f, key: `field-${i}-${Date.now()}` })),
      );
    } catch (e: unknown) {
      const err = e as { message?: string };
      message.error('加载组件失败: ' + (err.message ?? ''));
    }
  };

  // Save component
  const handleSave = async () => {
    if (!selectedComponent) return;
    if (treeConfig && (!treeConfig.idField || !treeConfig.parentField)) {
      message.error('树表已开启:请选择 ID 列与父 ID 列'); return;
    }
    if (treeConfig && treeConfig.idField === treeConfig.parentField) {
      message.error('树表:ID 列与父 ID 列不能相同'); return;
    }
    setSaving(true);
    try {
      const cleanFields = fields.map(({ key: _k, ...rest }) => rest);
      const cleanFormulas = formulas.map(({ key: _k, ...rest }) => rest);
      // Phase B 决策1（不丢锚定列）: 勾选只覆盖"有字段可代表"的行键列；存量行键里
      // 那些"当前无任何候选列能代表"的锚定列（如 material_code / child_hf_part_no —
      // 在 driver 视图里但没有对应字段、无勾选框）必须并回，避免保存时静默丢失。
      const reachableCols = new Set(
        (Object.values(rowKeyCandidates)
          .map((c) => c.resolvedColumn)
          .filter(Boolean) as string[])
      );
      const preservedAnchors = (selectedComponent.rowKeyFields ?? []).filter(
        (c) => !reachableCols.has(c)
      );
      const finalRowKeyFields = Array.from(new Set([...rowKeyFields, ...preservedAnchors]));
      await componentService.update(selectedComponent.id, {
        name: selectedComponent.name,
        fields: cleanFields,
        formulas: cleanFormulas,
        // Y1.5: 显式传(空串=清空, 非空=设置)
        dataDriverPath: dataDriverPath ?? '',
        // Phase1-Snapshot: 行键配置，空数组时传 undefined（不覆盖已有值）
        rowKeyFields: finalRowKeyFields.length > 0 ? finalRowKeyFields : undefined,
        // 树表配置:开启传对象,关闭传空对象(后端按缺字段=清空)
        treeConfig: treeConfig
          ? { idField: treeConfig.idField, parentField: treeConfig.parentField, defaultExpanded: treeConfig.defaultExpanded ?? true }
          : {},
      });
      message.success('保存成功');
      loadTree();
      const res = await componentService.getById(selectedComponent.id);
      setSelectedComponent(res.data);
    } catch (e: unknown) {
      const err = e as { message?: string };
      message.error('保存失败: ' + (err.message ?? ''));
    } finally {
      setSaving(false);
    }
  };

  // Handle component rename — update cross-component formula references
  const handleRenameComponent = async (compId: string, oldName: string, newName: string) => {
    // Update all other components that reference this component in their formulas
    const allComps = flattenComponents(directories);
    const renamedComp = allComps.find(c => c.id === compId);
    const renamedCode = renamedComp?.code;

    for (const comp of allComps) {
      if (comp.id === compId) continue;
      if (!comp.formulas || comp.formulas.length === 0) continue;

      let changed = false;
      const updatedFormulas = comp.formulas.map((f: any) => {
        if (!f.expression) return f;
        const updatedExpr = f.expression.map((token: any) => {
          if (token.type !== 'component_subtotal') return token;
          // Match by component_code or by old name in label
          const matchByCode = renamedCode && token.component_code === renamedCode;
          const matchByLabel = token.label && token.label.startsWith(oldName + '·');
          if (matchByCode || matchByLabel) {
            changed = true;
            const fieldPart = token.label?.includes('·') ? token.label.split('·').slice(1).join('·') : (token.tab_name || token.value || '小计');
            return { ...token, label: `${newName}·${fieldPart}` };
          }
          return token;
        });
        return { ...f, expression: updatedExpr };
      });

      if (changed) {
        try {
          await componentService.update(comp.id, { name: comp.name, formulas: updatedFormulas });
        } catch {
          // silent — best effort
        }
      }
    }

    // If the currently selected component is the renamed one, update local state
    if (selectedComponent?.id === compId) {
      setSelectedComponent({ ...selectedComponent, name: newName });
    }

    loadTree(searchKeyword || undefined);
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

  // FieldPanel: add field token to active formula
  const handleFieldClick = (fieldName: string) => {
    if (formulas.length === 0) {
      message.info('请先添加一个公式，再点击字段');
      return;
    }
    setPendingToken({ type: 'field', value: fieldName, label: fieldName });
  };

  const handleSubtotalClick = (subtotal: { name: string; componentCode: string }) => {
    if (formulas.length === 0) {
      message.info('请先添加一个公式，再点击字段');
      return;
    }
    // Find the component name for display
    const comp = allComponents.find(c => c.code === subtotal.componentCode);
    const compName = comp?.name || subtotal.componentCode;
    setPendingToken({
      type: 'component_subtotal',
      value: subtotal.name,
      label: `${compName}·${subtotal.name}`,
      component_code: subtotal.componentCode,
      tab_name: subtotal.name,
    });
  };

  // Pending token: FormulaBuilder consumes this via useEffect
  const [pendingToken, setPendingToken] = useState<FormulaToken | null>(null);

  // Other components' subtotals — 2026-05-20: 限制为"同目录下其他组件"的小计字段.
  // 避免跨目录引用导致模板组装时漏配 (不同业务目录的组件常常没在同一模板里出现).
  // 当 selectedComponent 还没选中或没目录归属时, 返空 (避免初始化阶段误显示全量).
  const allComponents = flattenComponents(directories);
  const currentDirId = selectedComponent?.directoryId;
  const otherCompSubtotals = currentDirId
    ? allComponents
        .filter((c) => c.directoryId === currentDirId && c.id !== selectedComponent?.id)
        .flatMap((c) =>
          (c.fields || [])
            .filter((f: FieldItem) => f.is_subtotal)
            .map((f: FieldItem) => ({
              name: f.name,
              componentCode: c.code,
              componentName: c.name,
            }))
        )
    : [];

  // Cross-tab sources: same-directory sibling components WITH their fields (AP-37: use stable componentId)
  const crossTabSources = currentDirId
    ? allComponents
        .filter((c) => c.directoryId === currentDirId && c.id !== selectedComponent?.id)
        .map((c) => ({
          id: c.id,
          code: c.code,
          name: c.name,
          fields: (c.fields || []).map((f: FieldItem) => ({ name: f.name, label: f.label })).filter((f) => f.name),
        }))
    : [];

  const availableFields = fields.map((f) => ({ name: f.name, type: f.field_type, label: f.label }));

  // Consume pending token into the active formula
  useEffect(() => {
    if (!pendingToken) return;
    if (formulas.length === 0) return;
    // Use active formula, fallback to last formula
    const targetKey = activeFormulaKey || formulas[formulas.length - 1].key;
    setFormulas((prev) =>
      prev.map((f) =>
        f.key === targetKey ? { ...f, expression: [...f.expression, pendingToken] } : f
      )
    );
    setPendingToken(null);
  }, [pendingToken]); // eslint-disable-line react-hooks/exhaustive-deps

  const dsField = dsModalFieldIndex !== null ? fields[dsModalFieldIndex] : undefined;

  return (
    <div className="cm-layout">
      {/* Left panel: Component Tree */}
      <ComponentTree
        directories={directories}
        selectedComponentId={selectedComponent?.id ?? null}
        onSelectComponent={handleSelectComponent}
        onRenameComponent={handleRenameComponent}
        onRefresh={() => loadTree(searchKeyword || undefined)}
        loading={loadingTree}
        searchKeyword={searchKeyword}
        onSearchChange={setSearchKeyword}
      />

      {/* Center panel: Component Editor */}
      <div className="cm-center-panel">
        {/* Center toolbar */}
        <div className="cm-center-toolbar">
          <div className="cm-center-title">
            {selectedComponent ? (
              <Space align="center">
                <span>{selectedComponent.name}</span>
                <Tag color="blue" style={{ fontSize: 11 }}>{selectedComponent.code}</Tag>
                {selectedComponent.componentType === 'SUBTOTAL' ? (
                  <Tag color="orange" style={{ fontSize: 11 }}>小计组件</Tag>
                ) : (
                  <Badge
                    count={selectedComponent.columnCount}
                    showZero
                    color="geekblue"
                    title="列数"
                  />
                )}
              </Space>
            ) : (
              <span>组件管理</span>
            )}
          </div>
          <div className="cm-toolbar-btn-group">
            <Button
              icon={<QuestionCircleOutlined />}
              size="small"
              onClick={() => setGuideOpen(true)}
              style={{ marginRight: 8 }}
            >
              配置帮助
            </Button>
            {selectedComponent && (
              <Button type="primary" onClick={handleSave} loading={saving} size="small">
                保存
              </Button>
            )}
          </div>
        </div>

        <ConfigGuideDrawer open={guideOpen} onClose={() => setGuideOpen(false)} />

        {/* Canvas scroll area */}
        <div className="cm-canvas-scroll">
          {selectedComponent ? (
            <>
              {/* Header preview and field config — only for NORMAL components */}
              {selectedComponent.componentType !== 'SUBTOTAL' && (
                <>
                  {/* Y1.5: 数据驱动路径配置(可选) */}
                  <div
                    style={{
                      background: '#f5faff',
                      border: '1px solid #d4e4f7',
                      borderRadius: 6,
                      padding: '10px 12px',
                      marginBottom: 12,
                      display: 'flex',
                      alignItems: 'center',
                      gap: 8,
                    }}
                  >
                    <span
                      style={{ fontSize: 13, color: '#1f4e96', fontWeight: 500, whiteSpace: 'nowrap' }}
                      title="非空 → 报价单按此 BNF 路径返回的 N 行展开组件,字段路径自动隐式 JOIN driver 行字段"
                    >
                      数据驱动路径(可选):
                    </span>
                    <Input
                      value={dataDriverPath}
                      onChange={(e) => setDataDriverPath(e.target.value)}
                      placeholder="如 mat_bom[bom_type='INCOMING']  ←  非空时组件展开为 N 行"
                      size="small"
                      style={{ flex: 1, fontFamily: 'Consolas, Monaco, monospace', fontSize: 12 }}
                      allowClear
                    />
                    <Button size="small" onClick={() => setDriverPickerOpen(true)}>
                      选择路径
                    </Button>
                  </div>
                  <PathPickerDrawer
                    open={driverPickerOpen}
                    onClose={() => setDriverPickerOpen(false)}
                    initialPath={dataDriverPath}
                    componentId={selectedComponent?.id}
                    onConfirm={(path) => {
                      setDataDriverPath(path);
                      setDriverPickerOpen(false);
                    }}
                  />
                  {/* 树表配置(纯展示):指定 ID 列/父 ID 列 → 渲染时按父子关系折叠 */}
                  <div
                    style={{
                      background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 6,
                      padding: '10px 12px', marginBottom: 12, display: 'flex',
                      alignItems: 'center', gap: 8, flexWrap: 'wrap',
                    }}
                  >
                    <span style={{ fontSize: 13, color: '#237804', fontWeight: 500, whiteSpace: 'nowrap' }}>
                      树表(纯展示):
                    </span>
                    <Switch
                      size="small"
                      checked={!!treeConfig}
                      onChange={(on) =>
                        setTreeConfig(on ? { idField: '', parentField: '', defaultExpanded: true } : null)
                      }
                    />
                    {treeConfig && (
                      <>
                        <span style={{ fontSize: 12, color: '#555' }}>ID 列:</span>
                        <Select
                          size="small" style={{ minWidth: 140 }} placeholder="选 ID 列(料号)"
                          value={treeConfig.idField || undefined}
                          options={fields.filter(f => f.name).map(f => ({ label: f.name, value: f.name }))}
                          onChange={(v) => setTreeConfig({ ...treeConfig, idField: v })}
                        />
                        <span style={{ fontSize: 12, color: '#555' }}>父 ID 列:</span>
                        <Select
                          size="small" style={{ minWidth: 140 }} placeholder="选父 ID 列(父料号)"
                          value={treeConfig.parentField || undefined}
                          options={fields.filter(f => f.name).map(f => ({ label: f.name, value: f.name }))}
                          onChange={(v) => setTreeConfig({ ...treeConfig, parentField: v })}
                        />
                        <span style={{ fontSize: 12, color: '#555' }}>默认展开:</span>
                        <Switch
                          size="small"
                          checked={treeConfig.defaultExpanded ?? true}
                          onChange={(v) => setTreeConfig({ ...treeConfig, defaultExpanded: v })}
                        />
                        {treeConfig.idField && treeConfig.parentField && treeConfig.idField === treeConfig.parentField && (
                          <span style={{ color: '#cf1322', fontSize: 12 }}>ID 列与父 ID 列不能相同</span>
                        )}
                      </>
                    )}
                  </div>
                  <HeaderPreview fields={fields} />
                  <Tabs
                    size="small"
                    style={{ marginTop: 8 }}
                    items={[
                      {
                        key: 'fields',
                        label: '字段配置',
                        children: (
                          <FieldConfigTable
                            fields={fields}
                            formulas={formulas}
                            componentId={selectedComponent?.id}
                            onChange={(newFields) => {
                              // Detect field renames and sync formula expressions
                              const renames: Record<string, string> = {};
                              for (const nf of newFields) {
                                const old = fields.find(f => f.key === nf.key);
                                if (old && old.name && nf.name && old.name !== nf.name) {
                                  renames[old.name] = nf.name;
                                }
                              }
                              setFields(newFields);
                              if (Object.keys(renames).length > 0) {
                                setFormulas(prev => prev.map(f => ({
                                  ...f,
                                  name: renames[f.name] ?? f.name,
                                  expression: f.expression.map(token => {
                                    if (token.type === 'field' && token.value && renames[token.value]) {
                                      return { ...token, value: renames[token.value], label: renames[token.value] };
                                    }
                                    return token;
                                  }),
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
                        key: 'formulas',
                        label: '公式',
                        children: (
                          <FormulaBuilder
                            formulas={formulas}
                            onChange={setFormulas}
                            availableFields={availableFields}
                            activeFormulaKey={activeFormulaKey}
                            onActiveFormulaKeyChange={setActiveFormulaKey}
                            availableSubtotals={otherCompSubtotals}
                          />
                        ),
                      },
                      {
                        key: 'sql-views',
                        label: 'SQL 视图',
                        children: (
                          <SqlViewListPanel componentId={selectedComponent.id} />
                        ),
                      },
                    ]}
                  />
                </>
              )}

              {/* Subtotal component hint */}
              {selectedComponent.componentType === 'SUBTOTAL' && (
                <div style={{ background: '#fff8e6', border: '1px solid #ffe4a0', borderRadius: 6, padding: '12px 16px', color: '#d48806', fontSize: 13 }}>
                  <strong>小计组件</strong> — 仅需配置公式，用于汇总其他组件的小计值。拖入产品模板后作为产品小计使用。
                </div>
              )}

              {/* Formula builder (SUBTOTAL component only — NORMAL uses Tabs above) */}
              {selectedComponent.componentType === 'SUBTOTAL' && (
                <FormulaBuilder
                  formulas={formulas}
                  onChange={setFormulas}
                  availableFields={availableFields}
                  activeFormulaKey={activeFormulaKey}
                  onActiveFormulaKeyChange={setActiveFormulaKey}
                  availableSubtotals={otherCompSubtotals}
                />
              )}
            </>
          ) : (
            <div className="cm-empty-state">
              <FileOutlined className="cm-empty-state-icon" />
              <div className="cm-empty-state-text">请从左侧目录中选择一个组件进行编辑</div>
              <div style={{ color: '#8c8c8c', fontSize: 13, margin: '12px 0', textAlign: 'center', maxWidth: 540 }}>
                组件 = 报价单产品卡片中的一个标签页(投料/费用/工艺等),
                定义表格的列结构(fields)和计算公式(formulas)。
                配置后可在「模板配置」中拖入使用。
              </div>
              <Space>
                <Button
                  icon={<PlusOutlined />}
                  size="small"
                  onClick={() => {
                    message.info('请在左侧目录树上右键新建目录或组件');
                  }}
                >
                  新建目录
                </Button>
                <Button
                  type="primary"
                  ghost
                  icon={<BulbOutlined />}
                  size="small"
                  onClick={() => setGuideOpen(true)}
                >
                  查看配置帮助
                </Button>
              </Space>
            </div>
          )}
        </div>
      </div>

      {/* Cross-tab reference drawer */}
      <CrossTabRefDrawer
        open={crossTabDrawerOpen}
        onClose={() => setCrossTabDrawerOpen(false)}
        siblingComponents={crossTabSources}
        currentFields={availableFields}
        onConfirm={(token) => setPendingToken(token as any)}
      />

      {/* Right panel: Field/Reference panel */}
      <FieldPanel
        fields={fields}
        otherComponentSubtotals={otherCompSubtotals}
        onFieldClick={handleFieldClick}
        onSubtotalClick={handleSubtotalClick}
        onCrossTabRefClick={() => {
          if (formulas.length === 0) {
            message.info('请先添加一个公式，再配置跨页签引用');
            return;
          }
          setCrossTabDrawerOpen(true);
        }}
        onQuotationFieldClick={(qf) => {
          if (formulas.length === 0) {
            message.info('请先添加一个公式，再点击字段');
            return;
          }
          setPendingToken({
            type: 'quotation_field' as any,
            value: qf.value,
            label: qf.label,
          });
        }}
        onGlobalVariableClick={(pick) => {
          if (formulas.length === 0) {
            message.info('请先添加一个公式，再点击字段');
            return;
          }
          // V104: 静态 key 编译期固化为 BNF path; 动态 key 留空 path, 求值期重写
          let path = '';
          try {
            if (pick.key_values) {
              path = compileGlobalVariableToPath(pick.def, pick.key_values);
            }
          } catch (e: any) {
            message.error(e?.message || '全局变量 key 不完整');
            return;
          }
          setPendingToken({
            type: 'global_variable' as any,
            label: pick.label,
            code: pick.def.code,
            key_values: pick.key_values,
            key_field_refs: pick.key_field_refs,
            path,
          });
        }}
        hasSelection={!!selectedComponent}
      />

      {/* DataSource binding modal */}
      <DataSourceModal
        visible={dsModalVisible}
        fieldName={dsField?.name || ''}
        binding={dsField?.datasource_binding}
        componentFields={fields}
        onOk={handleDsModalOk}
        onCancel={() => setDsModalVisible(false)}
      />
    </div>
  );
};

export default ComponentManagement;
