/**
 * ConfigureProductDrawer — 报价单 Step2「添加产品 ▾ → 选配添加」抽屉（task-0712 F5 重构，D11）。
 *
 * 1:1 复刻 dev-docs/task-0712-选配模板和报价单选配功能/prototypes/原型-报价单-选配添加.html：
 * 打开即解析有效模板(D6) → 无模板空态 / 有模板进单屏明细表（左：明细表+新增子框+组合工艺条件区，
 * 右：3D 预览常驻）→ 底部指纹状态提示 + 取消/确认加入。
 *
 * 整体模型变化（对齐 fronttask.md F5 §5.1，取代旧 globalStep×subStep 逐配件向导）：
 * - 不再预选产品类型：`productType` 由明细表 Σqty 实时判定（Σqty==1→SIMPLE，≥2→COMPOSITE，
 *   api.md §3.3），且**后端按 Σqty 兜底裁决为准**——本组件按 `ConfigureProductResponse.productType`
 *   （而非请求里声明的 productType）消费返回的 `lineItems`，原样追加，不自行按 productType 重算行
 *   （交接方要求：见任务说明"前端必须消费返回的 line_items 原样追加"）。
 * - 材质/工序候选改为模板限定 `selTemplateService.effective(customerNo)`（D6），不再是全量字典。
 * - 指纹匹配挪到整份提交后展示真实结果（而非逐配件"料号匹配"步骤），详见
 *   `configure/SummaryFingerprintPanel.tsx` 头注——已核实 `/lookup-fingerprint` 端点对本功能
 *   新建的材质组合结构性恒返 matched=false，不做误导性的"确认前实时预览"。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Drawer, Button, Spin, Tooltip, message } from 'antd';
import { configureProductService } from '../../services/configureProductService';
import { selTemplateService } from '../../services/selTemplateService';
import { materialRecipeService, type MaterialRecipeLite } from '../../services/materialRecipeService';
import { modelConfigService } from '../../services/modelConfigService';
import api from '../../services/api';
import type {
  ProductType, PartRequest, CompositeProcessRequest,
  EffectiveTemplateDTO, SelDetailRow, CompositeSelectionState,
} from '../../types/configure';
import type { ModelConfigDTO } from '../../types/modelConfig';
import { genUUID } from '../../utils/uuid';
import SelDetailTable from './configure/SelDetailTable';
import AddPartSubDrawer, { type LegacyProcessLite } from './configure/AddPartSubDrawer';
import CompositeProcessSection from './configure/CompositeProcessSection';
import { Preview3DPanel, FingerprintStatus, type PreviewMode } from './configure/SummaryFingerprintPanel';

interface Props {
  open: boolean;
  quotationId: string;
  /** 客户编码（`customer.code`），用于 `selTemplateService.effective(customerNo)`（D6）。 */
  customerNo: string | undefined;
  onCancel: () => void;
  onConfirm: (lineItems: any[]) => void;
}

async function fetchLegacyProcesses(): Promise<LegacyProcessLite[]> {
  try {
    const res: any = await api.get('/processes', { params: { status: 'ACTIVE', size: 200 } });
    const list = Array.isArray(res) ? res : (res?.data ?? res?.content ?? []);
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

const ConfigureProductDrawer: React.FC<Props> = ({ open, quotationId, customerNo, onCancel, onConfirm }) => {
  const [effective, setEffective] = useState<EffectiveTemplateDTO | null>(null);
  const [effectiveLoading, setEffectiveLoading] = useState(false);
  const [materialDict, setMaterialDict] = useState<MaterialRecipeLite[]>([]);
  const [legacyProcesses, setLegacyProcesses] = useState<LegacyProcessLite[]>([]);

  const [rows, setRows] = useState<SelDetailRow[]>([]);
  const [compositeSelections, setCompositeSelections] = useState<CompositeSelectionState[]>([]);

  const [subOpen, setSubOpen] = useState(false);
  const [editingRowId, setEditingRowId] = useState<string | null>(null);

  const [previewMode, setPreviewMode] = useState<PreviewMode>(null);
  const [previewMaterialCode, setPreviewMaterialCode] = useState<string | null>(null);
  const [previewMaterialLabel, setPreviewMaterialLabel] = useState('');
  const [previewData, setPreviewData] = useState<ModelConfigDTO | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);

  const [submitting, setSubmitting] = useState(false);

  const qtySum = rows.reduce((s, r) => s + (r.quantity || 0), 0);
  const editingRow = editingRowId ? rows.find((r) => r.rowId === editingRowId) ?? null : null;

  const resetState = () => {
    setEffective(null);
    setEffectiveLoading(false);
    setMaterialDict([]);
    setLegacyProcesses([]);
    setRows([]);
    setCompositeSelections([]);
    setSubOpen(false);
    setEditingRowId(null);
    setPreviewMode(null);
    setPreviewMaterialCode(null);
    setPreviewMaterialLabel('');
    setPreviewData(null);
    setPreviewLoading(false);
    setSubmitting(false);
  };

  // 打开抽屉：重置 + 解析有效模板(D6) + 拉两个 id 反查字典（materialDict / legacyProcesses，见
  // AddPartSubDrawer 头注坑①②）。
  useEffect(() => {
    if (!open) return;
    resetState();
    if (!customerNo) {
      // 无客户上下文（理论不可达，QuotationStep2 Dropdown 已按 customerTemplateId 兜底）——
      // 双保险：视同无模板空态,不崩溃。
      setEffective({ customerNo: '', usedDefault: false, hasTemplate: false, params: [] });
      return;
    }
    setEffectiveLoading(true);
    Promise.all([
      selTemplateService.effective(customerNo),
      materialRecipeService.list(),
      fetchLegacyProcesses(),
    ])
      .then(([eff, mats, procs]) => {
        setEffective(eff);
        setMaterialDict(mats);
        setLegacyProcesses(procs);
      })
      .catch((e: any) => {
        message.error(e?.message || '加载选配模板失败');
        setEffective({ customerNo, usedDefault: false, hasTemplate: false, params: [] });
      })
      .finally(() => setEffectiveLoading(false));
  }, [open, customerNo]);

  // Σqty 跌破 2 时组合工艺不再适用，清空已选（对齐原型 renderComboSection 行为）。
  useEffect(() => {
    if (qtySum < 2 && compositeSelections.length > 0) setCompositeSelections([]);
  }, [qtySum, compositeSelections.length]);

  // 3D 预览：跟随最近一次操作的材质实时刷新（D3/D15）；AbortController 丢弃过期响应防闪回旧值。
  useEffect(() => {
    if (!previewMode) { setPreviewData(null); return; }
    const subjectKey = previewMode === 'material' ? previewMaterialCode : null;
    if (!subjectKey) { setPreviewData(null); return; }
    const controller = new AbortController();
    setPreviewLoading(true);
    modelConfigService
      .current({ subjectType: previewMode === 'material' ? 'MATERIAL' : 'SALES_PART', subjectKey }, controller.signal)
      .then((d) => { if (!controller.signal.aborted) setPreviewData(d); })
      .catch((e: any) => {
        if (controller.signal.aborted || e?.code === 'ERR_CANCELED' || e?.name === 'CanceledError') return;
        setPreviewData(null);
      })
      .finally(() => { if (!controller.signal.aborted) setPreviewLoading(false); });
    return () => controller.abort();
  }, [previewMode, previewMaterialCode]);

  const handleAddClick = () => { setEditingRowId(null); setSubOpen(true); };
  const handleEditClick = (rowId: string) => { setEditingRowId(rowId); setSubOpen(true); };
  const handleDeleteRow = (rowId: string) => setRows((prev) => prev.filter((r) => r.rowId !== rowId));
  const handleQuantityChange = (rowId: string, qty: number) =>
    setRows((prev) => prev.map((r) => (r.rowId === rowId ? { ...r, quantity: qty } : r)));

  const handleSubConfirm = (row: SelDetailRow) => {
    setRows((prev) => {
      const idx = prev.findIndex((r) => r.rowId === row.rowId);
      if (idx >= 0) { const next = [...prev]; next[idx] = row; return next; }
      return [...prev, row];
    });
    setSubOpen(false);
    setEditingRowId(null);
  };
  const handleSubCancel = () => { setSubOpen(false); setEditingRowId(null); };
  const handleMaterialPreview = (code: string | null, label: string) => {
    setPreviewMode(code ? 'material' : previewMode);
    setPreviewMaterialCode(code);
    setPreviewMaterialLabel(label);
  };

  const handleClose = () => { resetState(); onCancel(); };

  const submit = async () => {
    if (rows.length === 0) return;
    if (!quotationId) { message.error('报价单尚未创建，无法选配'); return; }
    setSubmitting(true);
    try {
      const tempId = genUUID();
      // Σqty 判定与后端同口径（api.md §3.3，D11+D12）：Σqty==1→SIMPLE；Σqty>=2→COMPOSITE。
      // 后端仍会按 Σqty 兜底裁决（ConfigureProductResponse.productType 可能与此处不同），
      // 提交后一律按响应值消费 lineItems。
      const requestProductType: ProductType = qtySum >= 2 ? 'COMPOSITE' : 'SIMPLE';
      const partsReq: PartRequest[] = rows.map((r) => ({
        name: r.recipeLabel || r.recipeCode || '',
        partMode: 'custom',
        recipeCode: r.recipeCode!,
        elements: Object.entries(r.elementOverrides).map(([elementCode, pct]) => ({ elementCode, pct: Number(pct) })),
        processIds: r.processIds.length > 0 ? r.processIds : undefined,
        unitWeightGrams: r.unitWeightGrams ?? undefined,
        quotationLineItemId: requestProductType === 'SIMPLE' ? tempId : genUUID(),
        quantity: r.quantity ?? 1,
      }));
      const allPartIdx = rows.map((_, i) => i);
      const compProcs: CompositeProcessRequest[] = compositeSelections.map((c) => ({
        defCode: c.defCode,
        participatingPartIndexes: allPartIdx,
        params: {},
      }));
      const resp = await configureProductService.configureProduct(quotationId, {
        productType: requestProductType,
        tempId,
        parts: partsReq,
        compositeProcesses: requestProductType === 'COMPOSITE' ? compProcs : undefined,
      });
      if (resp.fingerprintMatched) {
        message.success(`已复用 ${resp.reusedHfPartNos.length} 个料号`);
      } else {
        message.success('已加入选配产品');
      }
      onConfirm(resp.lineItems);
      resetState();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? e?.message ?? '选配失败');
    } finally {
      setSubmitting(false);
    }
  };

  const hasTemplate = !!effective?.hasTemplate;

  const footer = useMemo(() => {
    if (effectiveLoading || !hasTemplate) {
      return (
        <div style={{ textAlign: 'right' }}>
          <Button onClick={handleClose}>取消</Button>
        </div>
      );
    }
    return (
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
        <FingerprintStatus rowCount={rows.length} />
        <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
          <Button onClick={handleClose}>取消</Button>
          {rows.length === 0 ? (
            <Tooltip title="请至少新增一个材质料号">
              <Button type="primary" disabled>确认加入</Button>
            </Tooltip>
          ) : (
            <Button type="primary" loading={submitting} onClick={submit}>确认加入</Button>
          )}
        </div>
      </div>
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effectiveLoading, hasTemplate, rows, submitting]);

  return (
    <Drawer
      title="添加产品 — 选配"
      open={open}
      onClose={handleClose}
      width={960}
      placement="right"
      destroyOnClose
      footer={footer}
    >
      {effectiveLoading ? (
        <div style={{ textAlign: 'center', padding: '80px 0' }}>
          <Spin size="large" />
        </div>
      ) : !hasTemplate ? (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '60px 40px', textAlign: 'center', color: '#606266', minHeight: 420 }}>
          <div style={{ fontSize: 52, marginBottom: 16, color: '#c0c4cc' }}>🗂️</div>
          <div style={{ fontSize: 14, lineHeight: 1.8, maxWidth: 460, marginBottom: 16 }}>
            缺少选配模板 —— 请先在「配置中心 → 选配模板管理」为该客户所属行业或默认模板配置选配参数。
          </div>
          <a
            style={{ color: '#1890ff', cursor: 'pointer', fontWeight: 500 }}
            onClick={() => window.open('/config/sel-templates', '_blank')}
          >
            → 去配置选配模板
          </a>
        </div>
      ) : (
        <div style={{ display: 'flex', minHeight: 520 }}>
          <div style={{ width: '62%', paddingRight: 20, borderRight: '1px solid #f0f0f0', position: 'relative' }}>
            <SelDetailTable
              rows={rows}
              onAdd={handleAddClick}
              onEdit={handleEditClick}
              onDelete={handleDeleteRow}
              onQuantityChange={handleQuantityChange}
            />
            <CompositeProcessSection sumQty={qtySum} selections={compositeSelections} onChange={setCompositeSelections} />
            <AddPartSubDrawer
              open={subOpen}
              effective={effective!}
              materialDict={materialDict}
              legacyProcesses={legacyProcesses}
              editingRow={editingRow}
              onConfirm={handleSubConfirm}
              onCancel={handleSubCancel}
              onMaterialPreview={handleMaterialPreview}
            />
          </div>
          <div style={{ width: '38%', paddingLeft: 20 }}>
            <Preview3DPanel
              mode={previewMode}
              materialLabel={previewMaterialLabel}
              materialCode={previewMaterialCode}
              loading={previewLoading}
              modelData={previewData}
            />
            <div style={{ fontSize: 12, color: '#909399', marginTop: 8, lineHeight: 1.6 }}>
              选材质后实时预览材质 3D；「⤢ 交互查看」为增强项占位。
            </div>
          </div>
        </div>
      )}
    </Drawer>
  );
};

export default ConfigureProductDrawer;
