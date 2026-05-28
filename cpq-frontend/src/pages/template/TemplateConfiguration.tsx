import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  Typography,
  Form,
  Space,
  Tag,
  message,
  Modal,
  Table,
  Tabs,
} from 'antd';
import { useParams, useNavigate } from 'react-router-dom';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import { templateService } from '../../services/templateService';
import { componentService } from '../../services/componentService';
import { customerService } from '../../services/customerService';
import type { ProductAttribute, TemplateData, VersionHistoryItem, TemplateComponentItem } from './types';
import { STATUS_COLORS, STATUS_LABELS } from './types';
import ComponentPalette from './ComponentPalette';
import ProductAttributesGrid from './ProductAttributesGrid';
import TabComponentArea from './TabComponentArea';
import SubtotalDropBar from './SubtotalDropBar';
import TemplateConfigPanel from './TemplateConfigPanel';
import ViewToggle from './ViewToggle';
import ExcelViewConfigTab from './ExcelViewConfigTab';
import TemplateFormulasPanel from './TemplateFormulasPanel';
import TemplateSqlViewsTab from './TemplateSqlViewsTab';
import './styles.css';

const { Text } = Typography;

const TemplateConfiguration: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [template, setTemplate] = useState<TemplateData | null>(null);
  const [tcs, setTcs] = useState<TemplateComponentItem[]>([]);
  const [availableComponents, setAvailableComponents] = useState<any[]>([]);
  const [versionHistory, setVersionHistory] = useState<VersionHistoryItem[]>([]);
  const [productAttrs, setProductAttrs] = useState<ProductAttribute[]>([]);
  const [activeTabKey, setActiveTabKey] = useState('');
  const [viewMode, setViewMode] = useState<'detail' | 'simple' | 'excel'>('detail');
  const [centerTab, setCenterTab] = useState<'components' | 'formulas' | 'sql-views'>('components');
  const [excelViewConfig, setExcelViewConfig] = useState<any>(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);
  // presetRowsMap: tcId -> rows
  const [presetRowsMap, setPresetRowsMap] = useState<Record<string, any[]>>({});
  const [customers, setCustomers] = useState<{ id: string; name: string }[]>([]);
  const [form] = Form.useForm();
  const autoSaveTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } })
  );

  const isDraft = template?.status === 'DRAFT';

  // ---- Load template ----
  const loadTemplate = useCallback(async () => {
    if (!id) return;
    try {
      const res = await templateService.getById(id);
      const t = res.data;
      setTemplate(t);
      setTcs(t.components || []);
      if ((t.components || []).length > 0) {
        setActiveTabKey(t.components[0].id);
      }
      // Load preset rows for each template component
      const rowsMap: Record<string, any[]> = {};
      for (const tc of (t.components || [])) {
        const raw = tc.presetRows;
        if (raw) {
          rowsMap[tc.id] = typeof raw === 'string' ? JSON.parse(raw) : (Array.isArray(raw) ? raw : []);
        }
      }
      setPresetRowsMap(rowsMap);
      form.setFieldsValue({
        name: t.name,
        categoryId: t.categoryId,
        customerId: t.customerId ?? undefined,
        description: t.description,
        usageNote: t.usageNote,
      });
      // Load excel_view_config
      if (t.excelViewConfig || t.excel_view_config) {
        const raw = t.excelViewConfig || t.excel_view_config;
        setExcelViewConfig(typeof raw === 'string' ? JSON.parse(raw) : raw);
      }

      const attrs: ProductAttribute[] = (t.productAttributes || []).map(
        (a: any, idx: number) => ({
          key: a.key || String(idx),
          sort_order: idx,
          ...a,
        })
      );
      setProductAttrs(attrs);

      if (t.templateSeriesId) {
        const histRes = await templateService.getVersionHistory(t.templateSeriesId);
        setVersionHistory(histRes.data || []);
      }
    } catch (e: any) {
      message.error(e.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [id, form]);

  const loadComponents = useCallback(async () => {
    try {
      const res = await componentService.list({});
      const all = res.data || [];
      setAvailableComponents(all.filter((c: any) => c.status === 'ACTIVE'));
    } catch {
      // ignore
    }
  }, []);

  const loadCustomers = useCallback(async () => {
    try {
      const res = await customerService.list({ size: 500 });
      const list = res.data?.content ?? res.data ?? [];
      setCustomers(list.map((c: any) => ({ id: c.id, name: c.name })));
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => {
    loadTemplate();
    loadComponents();
    loadCustomers();
  }, [loadTemplate, loadComponents, loadCustomers]);

  // ---- Auto-save (30s) ----
  const doSave = useCallback(async () => {
    if (!id || !isDraft || saving) return;
    setSaving(true);
    try {
      const values = form.getFieldsValue();
      await templateService.update(id, {
        ...values,
        productAttributes: JSON.stringify(productAttrs),
      });
      setDirty(false);
    } catch {
      // silent auto-save error
    } finally {
      setSaving(false);
    }
  }, [id, isDraft, saving, form, productAttrs]);

  useEffect(() => {
    if (isDraft) {
      autoSaveTimer.current = setInterval(() => {
        if (dirty) doSave();
      }, 30000);
    }
    return () => {
      if (autoSaveTimer.current) clearInterval(autoSaveTimer.current);
    };
  }, [isDraft, dirty, doSave]);

  const markDirty = () => setDirty(true);

  // ---- Manual save ----
  const handleSave = async () => {
    if (!id || !isDraft) return;
    await doSave();
    message.success('保存成功');
  };

  // ---- Add component ----
  const handleAddComponent = async (componentId: string, componentName: string) => {
    if (!id || !isDraft) return;
    try {
      const res = await templateService.addComponent(id, {
        componentId,
        tabName: componentName,
      });
      const newTc: TemplateComponentItem = res.data;
      setTcs((prev) => {
        const next = [...prev, newTc];
        return next;
      });
      setActiveTabKey(newTc.id);
      message.success(`已添加组件: ${componentName}`);
    } catch (e: any) {
      message.error(e.message || '添加失败');
    }
  };

  // ---- Preset rows change handler ----
  const handlePresetRowsChange = async (tcId: string, rows: any[]) => {
    setPresetRowsMap((prev) => ({ ...prev, [tcId]: rows }));
    if (id) {
      try {
        await templateService.updatePresetRows(id, tcId, rows);
      } catch (e: any) {
        message.error(e.message || '保存固定行失败');
      }
    }
  };

  // ---- Drag state for DragOverlay ----
  const [activeDrag, setActiveDrag] = useState<{ id: string; type: string; label: string } | null>(null);

  const handleDragStart = (event: any) => {
    const { active } = event;
    const data = active.data.current;
    if (data?.type === 'component') {
      setActiveDrag({ id: active.id, type: 'component', label: data.componentName });
    } else if (data?.type === 'formula') {
      setActiveDrag({ id: active.id, type: 'formula', label: data.formulaName });
    }
  };

  // ---- Drag end handler ----
  const handleDragEnd = (event: any) => {
    setActiveDrag(null);
    const { active, over } = event;
    if (!over) return;

    // Component dropped on canvas
    if (over.id === 'canvas-dropzone' && active.data.current?.type === 'component') {
      const { componentId, componentName, componentType } = active.data.current;
      if (componentType === 'SUBTOTAL') return; // SUBTOTAL goes to subtotal-dropzone only
      handleAddComponent(componentId, componentName);
      return;
    }

    // Subtotal component dropped on subtotal bar — replace existing
    if (over.id === 'subtotal-dropzone' && active.data.current?.type === 'component') {
      const { componentId, componentName, componentType } = active.data.current;
      if (componentType !== 'SUBTOTAL') return;
      // Remove existing subtotal component(s) first
      const existingSubtotals = tcs.filter(tc => {
        const comp = availableComponents.find((c: any) => c.id === tc.componentId);
        return comp?.componentType === 'SUBTOTAL';
      });
      (async () => {
        for (const st of existingSubtotals) {
          await handleRemoveTab(st.id);
        }
        handleAddComponent(componentId, componentName);
      })();
      return;
    }

  };

  // ---- Remove component ----
  const handleRemoveTab = async (tcId: string) => {
    if (!id || !isDraft) return;
    try {
      await templateService.removeComponent(id, tcId);
      setTcs((prev) => {
        const next = prev.filter((tc) => tc.id !== tcId);
        if (activeTabKey === tcId && next.length > 0) {
          setActiveTabKey(next[0].id);
        }
        return next;
      });
      message.success('已移除组件');
    } catch (e: any) {
      message.error(e.message || '移除失败');
    }
  };

  // ---- Reorder ----
  const handleMoveUp = async (idx: number) => {
    if (idx === 0 || !id || !isDraft) return;
    const newTcs = [...tcs];
    [newTcs[idx - 1], newTcs[idx]] = [newTcs[idx], newTcs[idx - 1]];
    setTcs(newTcs);
    try {
      await templateService.reorderComponents(id, newTcs.map((tc) => tc.id));
    } catch {
      await loadTemplate();
    }
  };

  const handleMoveDown = async (idx: number) => {
    if (idx >= tcs.length - 1 || !id || !isDraft) return;
    const newTcs = [...tcs];
    [newTcs[idx], newTcs[idx + 1]] = [newTcs[idx + 1], newTcs[idx]];
    setTcs(newTcs);
    try {
      await templateService.reorderComponents(id, newTcs.map((tc) => tc.id));
    } catch {
      await loadTemplate();
    }
  };

  // ---- Publish ----
  const handlePublish = async (majorVersion?: number) => {
    if (!id) return;
    await doSave();
    try {
      await templateService.publish(id, majorVersion !== undefined ? { majorVersion } : {});
      message.success('发布成功');
      await loadTemplate();
    } catch (e: any) {
      message.error(e.message || '发布失败');
    }
  };

  // ---- Archive ----
  const handleArchive = async () => {
    if (!id) return;
    try {
      await templateService.archive(id, false);
      message.success('归档成功');
      await loadTemplate();
    } catch (e: any) {
      Modal.confirm({
        title: '确认强制归档',
        content: e.message + '\n\n是否强制归档？',
        onOk: async () => {
          try {
            await templateService.archive(id, true);
            message.success('强制归档成功');
            await loadTemplate();
          } catch (e2: any) {
            message.error(e2.message || '归档失败');
          }
        },
      });
    }
  };

  // ---- New Draft ----
  const handleNewDraft = async () => {
    if (!id) return;
    try {
      const res = await templateService.createNewDraft(id);
      message.success('新草稿已创建');
      navigate(`/templates/${res.data.id}`);
    } catch (e: any) {
      message.error(e.message || '创建失败');
    }
  };

  // ---- Simple view summary table ----
  const renderSimpleSummary = () => {
    const cols = [
      { title: '组件名', dataIndex: 'tabName', key: 'tabName' },
      {
        title: '字段数',
        key: 'fields',
        render: (_: any, tc: TemplateComponentItem) => {
          const comp = availableComponents.find((c) => c.id === tc.componentId);
          return comp ? (Array.isArray(comp.fields) ? comp.fields.length : 0) : '—';
        },
      },
      { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder' },
    ];
    return (
      <Table
        dataSource={tcs}
        columns={cols}
        rowKey="id"
        size="small"
        pagination={false}
        bordered
      />
    );
  };

  if (loading) return <div style={{ padding: 24 }}>加载中...</div>;
  if (!template) return <div style={{ padding: 24 }}>模板不存在</div>;

  return (
    <DndContext sensors={sensors} onDragStart={handleDragStart} onDragEnd={handleDragEnd}>
    <div className="tm-layout">
      {/* Left panel: Component Palette */}
      <div className="tm-left-panel">
        <ComponentPalette
          onAddComponent={isDraft ? handleAddComponent : () => {}}
        />
      </div>

      {/* Center: toolbar + canvas */}
      <div className="tm-center-panel">
        {/* Fixed toolbar */}
        <div className="tm-center-toolbar">
          <div className="tm-toolbar-title">
            <span>{template.name}</span>
            <Tag color={STATUS_COLORS[template.status]}>{STATUS_LABELS[template.status]}</Tag>
            {template.version && <Tag>{template.version}</Tag>}
            {saving && <Text className="tm-status-saving">保存中...</Text>}
            {dirty && !saving && <Text className="tm-status-saving">未保存</Text>}
          </div>
          <Space>
            {centerTab === 'components' && (
              <>
                <ViewToggle mode={viewMode as 'detail' | 'simple'} onChange={v => setViewMode(v)} />
                <button
                  className={`tm-view-btn${viewMode === 'excel' ? ' active' : ''}`}
                  style={{
                    padding: '4px 12px',
                    borderRadius: 6,
                    border: '1px solid #d9d9d9',
                    background: viewMode === 'excel' ? '#1890ff' : '#fff',
                    color: viewMode === 'excel' ? '#fff' : '#595959',
                    cursor: 'pointer',
                    fontSize: 13,
                  }}
                  onClick={() => setViewMode(viewMode === 'excel' ? 'detail' : 'excel')}
                >
                  Excel视图
                </button>
              </>
            )}
            {isDraft && (
              <button className="tm-save-btn" onClick={handleSave}>
                💾 保存模板
              </button>
            )}
          </Space>
        </div>

        {/* Center tabs: 组件配置 / 公式 */}
        <Tabs
          activeKey={centerTab}
          onChange={k => setCenterTab(k as 'components' | 'formulas' | 'sql-views')}
          style={{ padding: '0 16px' }}
          items={[
            {
              key: 'components',
              label: '组件配置',
              children: (
                <div className="tm-canvas-scroll" style={{ padding: 0 }}>
                  <div className="tm-product-card-container">
                    <ProductAttributesGrid
                      attributes={productAttrs}
                      disabled={!isDraft}
                      onChange={(a) => {
                        setProductAttrs(a);
                        markDirty();
                      }}
                    />

                    {viewMode === 'excel' ? (
                      id ? (
                        <ExcelViewConfigTab
                          templateId={id}
                          isDraft={isDraft || false}
                          excelViewConfig={excelViewConfig}
                          onChange={setExcelViewConfig}
                          productAttributes={productAttrs}
                          componentsSnapshot={availableComponents}
                        />
                      ) : null
                    ) : viewMode === 'detail' ? (
                      <TabComponentArea
                        tabComponents={tcs}
                        componentsSnapshot={availableComponents}
                        activeTabKey={activeTabKey}
                        onTabChange={setActiveTabKey}
                        onRemoveTab={handleRemoveTab}
                        onMoveUp={handleMoveUp}
                        onMoveDown={handleMoveDown}
                        isDraft={isDraft || false}
                        presetRowsMap={presetRowsMap}
                        onPresetRowsChange={handlePresetRowsChange}
                        templateId={id || ''}
                        onOverridesSaved={loadTemplate}
                      />
                    ) : (
                      renderSimpleSummary()
                    )}

                    <SubtotalDropBar
                      tcs={tcs}
                      availableComponents={availableComponents}
                      isDraft={isDraft || false}
                      onRemove={(tcId) => handleRemoveTab(tcId)}
                    />
                  </div>
                </div>
              ),
            },
            {
              key: 'formulas',
              label: '公式',
              children: id ? (
                <TemplateFormulasPanel
                  templateId={id}
                  templateStatus={template.status}
                  onChange={() => {/* 公式变更不影响主模板 dirty 状态 */}}
                />
              ) : null,
            },
            {
              key: 'sql-views',
              label: 'SQL 视图',
              children: id ? (
                <TemplateSqlViewsTab
                  templateId={id}
                  readonly={!isDraft}
                />
              ) : null,
            },
          ]}
        />
      </div>

      {/* Right panel: Config Panel */}
      <div className="tm-right-panel">
        <TemplateConfigPanel
          template={template}
          form={form}
          onFormValuesChange={markDirty}
          onPublish={handlePublish}
          onArchive={handleArchive}
          onNewDraft={handleNewDraft}
          onBack={() => navigate('/templates')}
          versionHistory={versionHistory}
          onVersionClick={(vid) => navigate(`/templates/${vid}`)}
          currentId={id || ''}
          customers={customers}
        />
      </div>
    </div>
    <DragOverlay dropAnimation={null}>
      {activeDrag ? (
        activeDrag.type === 'component' ? (
          <div style={{
            padding: '12px 16px',
            background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
            color: '#fff',
            borderRadius: 8,
            fontSize: 13,
            fontWeight: 600,
            boxShadow: '0 8px 24px rgba(102,126,234,0.4)',
            cursor: 'grabbing',
            width: 200,
          }}>
            {activeDrag.label}
          </div>
        ) : (
          <span style={{
            display: 'inline-flex',
            alignItems: 'center',
            padding: '4px 12px',
            background: '#f0f9eb',
            border: '1px solid #d5f0c2',
            borderRadius: 12,
            fontSize: 12,
            color: '#67c23a',
            boxShadow: '0 4px 12px rgba(103,194,58,0.3)',
            cursor: 'grabbing',
          }}>
            🧮 {activeDrag.label}
          </span>
        )
      ) : null}
    </DragOverlay>
    </DndContext>
  );
};

export default TemplateConfiguration;
