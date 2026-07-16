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
 * - 材质/工序候选改为模板限定 `selTemplateService.effective(customerNo)`（D6），不再是全量字典；
 *   工序候选 key 直接是 `process_master.process_no`，原样进 `PartRequest.processNos`（task-0712
 *   缺口1 已根治 process/process_master 双表 UUID 契约缺口，本组件不再需要 UUID 映射/禁选孤儿）。
 * - 明细表/组合工艺条件变化时防抖调用 `/lookup-fingerprint`（task-0712 缺口2·3a）做确认前实时预览
 *   （对齐原型 D3），命中时右侧 3D 切到料号 3D；提交后仍以 `ConfigureProductResponse.fingerprintMatched`
 *   的 toast 兜底最终结果，详见 `configure/SummaryFingerprintPanel.tsx` 头注。
 */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Drawer, Button, Spin, Tooltip, message } from 'antd';
import { configureProductService } from '../../services/configureProductService';
import { selTemplateService } from '../../services/selTemplateService';
import { materialRecipeService, type MaterialRecipeLite } from '../../services/materialRecipeService';
import { modelConfigService } from '../../services/modelConfigService';
import type {
  ProductType, PartRequest, CompositeProcessRequest,
  EffectiveTemplateDTO, SelDetailRow, CompositeSelectionState,
} from '../../types/configure';
import type { ModelConfigDTO } from '../../types/modelConfig';
import { genUUID } from '../../utils/uuid';
import SelDetailTable from './configure/SelDetailTable';
import AddPartSubDrawer from './configure/AddPartSubDrawer';
import CompositeProcessSection from './configure/CompositeProcessSection';
import { Preview3DPanel, FingerprintStatus, type PreviewMode } from './configure/SummaryFingerprintPanel';

interface Props {
  open: boolean;
  quotationId: string;
  /** 客户编码（`customer.code`），用于 `selTemplateService.effective(customerNo)`（D6）与
   * `/lookup-fingerprint` 的 customerNo（销售侧客户维度指纹隔离，缺口2·3a 必填）。 */
  customerNo: string | undefined;
  onCancel: () => void;
  onConfirm: (lineItems: any[]) => void;
}

/** 明细表 Σqty 判定 productType，与后端 `validateRequest`/`lookupFingerprint` 同口径（api.md §3.3）。 */
function sumQty(rows: SelDetailRow[]): number {
  return rows.reduce((s, r) => s + (r.quantity || 0), 0);
}

/** 组装 `parts` + `compositeProcesses`，提交请求（`configureProduct`）与预览请求（`lookupFingerprint`）
 * 共用同一份构造逻辑，保证两者形态一致（「预览命中」= 「提交命中」的前提）。 */
function buildPartsReq(
  rows: SelDetailRow[],
  compositeSelections: CompositeSelectionState[],
): { productType: ProductType; parts: PartRequest[]; compositeProcesses?: CompositeProcessRequest[] } {
  const productType: ProductType = sumQty(rows) >= 2 ? 'COMPOSITE' : 'SIMPLE';
  const parts: PartRequest[] = rows.map((r) => ({
    name: r.recipeLabel || r.recipeCode || '',
    partMode: 'custom',
    recipeCode: r.recipeCode!,
    elements: Object.entries(r.elementOverrides).map(([elementCode, pct]) => ({ elementCode, pct: Number(pct) })),
    processNos: r.processNos.length > 0 ? r.processNos : undefined,
    unitWeightGrams: r.unitWeightGrams ?? undefined,
    quantity: r.quantity ?? 1,
  }));
  const allPartIdx = rows.map((_, i) => i);
  const compositeProcesses: CompositeProcessRequest[] = compositeSelections.map((c) => ({
    defCode: c.defCode,
    participatingPartIndexes: allPartIdx,
    params: {},
  }));
  return {
    productType,
    parts,
    compositeProcesses: productType === 'COMPOSITE' ? compositeProcesses : undefined,
  };
}

const ConfigureProductDrawer: React.FC<Props> = ({ open, quotationId, customerNo, onCancel, onConfirm }) => {
  const [effective, setEffective] = useState<EffectiveTemplateDTO | null>(null);
  const [effectiveLoading, setEffectiveLoading] = useState(false);
  const [materialDict, setMaterialDict] = useState<MaterialRecipeLite[]>([]);

  const [rows, setRows] = useState<SelDetailRow[]>([]);
  const [compositeSelections, setCompositeSelections] = useState<CompositeSelectionState[]>([]);

  const [subOpen, setSubOpen] = useState(false);
  const [editingRowId, setEditingRowId] = useState<string | null>(null);

  // 材质预览：跟随最近一次操作的材质（D3/D15，来自 AddPartSubDrawer.onMaterialPreview）。
  const [previewMode, setPreviewMode] = useState<PreviewMode>(null);
  const [previewMaterialCode, setPreviewMaterialCode] = useState<string | null>(null);
  const [previewMaterialLabel, setPreviewMaterialLabel] = useState('');
  const [previewData, setPreviewData] = useState<ModelConfigDTO | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);

  // 指纹预览（task-0712 缺口2·3a）：明细表/组合工艺条件变化时防抖调用 `/lookup-fingerprint`。
  const [fingerprintChecking, setFingerprintChecking] = useState(false);
  const [fingerprintMatched, setFingerprintMatched] = useState(false);
  const [fingerprintPartNo, setFingerprintPartNo] = useState<string | undefined>(undefined);

  const [submitting, setSubmitting] = useState(false);

  const qtySum = rows.reduce((s, r) => s + (r.quantity || 0), 0);
  const editingRow = editingRowId ? rows.find((r) => r.rowId === editingRowId) ?? null : null;

  const resetState = () => {
    setEffective(null);
    setEffectiveLoading(false);
    setMaterialDict([]);
    setRows([]);
    setCompositeSelections([]);
    setSubOpen(false);
    setEditingRowId(null);
    setPreviewMode(null);
    setPreviewMaterialCode(null);
    setPreviewMaterialLabel('');
    setPreviewData(null);
    setPreviewLoading(false);
    setFingerprintChecking(false);
    setFingerprintMatched(false);
    setFingerprintPartNo(undefined);
    setSubmitting(false);
  };

  // 打开抽屉：重置 + 解析有效模板(D6) + 拉 materialDict 反查字典（见 AddPartSubDrawer 头注坑①）。
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
    ])
      .then(([eff, mats]) => {
        setEffective(eff);
        setMaterialDict(mats);
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

  // 指纹预览（task-0712 缺口2·3a，对齐原型 D3「确认前实时🆕新建/✅命中」）：明细表/组合工艺条件
  // 变化时防抖 500ms 调用 `/lookup-fingerprint`；fingerprintReqSeq 丢弃过期响应（连续编辑时旧请求
  // 晚到不得覆盖新状态，同 elementReqSeq 惯例见 AddPartSubDrawer.tsx）。
  const fingerprintReqSeq = useRef(0);
  useEffect(() => {
    if (rows.length === 0 || !customerNo) {
      setFingerprintChecking(false);
      setFingerprintMatched(false);
      setFingerprintPartNo(undefined);
      return;
    }
    const custNo = customerNo; // 局部 const 供内层闭包窄化（跨闭包对函数参数的 undefined 窄化不稳定）
    const seq = ++fingerprintReqSeq.current;
    setFingerprintChecking(true);
    const timer = window.setTimeout(() => {
      const { parts, compositeProcesses } = buildPartsReq(rows, compositeSelections);
      configureProductService
        .lookupFingerprint({ customerNo: custNo, parts, compositeProcesses })
        .then((res) => {
          if (fingerprintReqSeq.current !== seq) return; // 已被更新的编辑取代，丢弃过期响应
          setFingerprintChecking(false);
          setFingerprintMatched(!!res.matched);
          setFingerprintPartNo(res.matched ? (res.matchedPartNo || res.hfPartNo) : undefined);
        })
        .catch(() => {
          if (fingerprintReqSeq.current !== seq) return;
          setFingerprintChecking(false);
          setFingerprintMatched(false);
          setFingerprintPartNo(undefined);
        });
    }, 500);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, compositeSelections, customerNo]);

  // 3D 预览：指纹命中已有销售料号时优先切到料号 3D（对齐原型 D3 `fingerprintHit` 分支）；
  // 否则回落到最近一次操作的材质预览（D3/D15）。
  const effectivePreviewMode: PreviewMode = fingerprintMatched && fingerprintPartNo ? 'salespart' : previewMode;
  const effectivePreviewKey: string | null = fingerprintMatched && fingerprintPartNo ? fingerprintPartNo : previewMaterialCode;
  const effectivePreviewLabel: string = fingerprintMatched && fingerprintPartNo ? '' : previewMaterialLabel;

  // AbortController 丢弃过期响应防闪回旧值。
  useEffect(() => {
    if (!effectivePreviewMode || !effectivePreviewKey) { setPreviewData(null); return; }
    const controller = new AbortController();
    setPreviewLoading(true);
    modelConfigService
      .current({ subjectType: effectivePreviewMode === 'material' ? 'MATERIAL' : 'SALES_PART', subjectKey: effectivePreviewKey }, controller.signal)
      .then((d) => { if (!controller.signal.aborted) setPreviewData(d); })
      .catch((e: any) => {
        if (controller.signal.aborted || e?.code === 'ERR_CANCELED' || e?.name === 'CanceledError') return;
        setPreviewData(null);
      })
      .finally(() => { if (!controller.signal.aborted) setPreviewLoading(false); });
    return () => controller.abort();
  }, [effectivePreviewMode, effectivePreviewKey]);

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
      // 提交后一律按响应值消费 lineItems。与 `/lookup-fingerprint` 预览共用 buildPartsReq，
      // 仅补提交独有的 quotationLineItemId（工序隔离键）。
      const { productType: requestProductType, parts: baseParts, compositeProcesses } =
        buildPartsReq(rows, compositeSelections);
      const partsReq: PartRequest[] = baseParts.map((p) => ({
        ...p,
        quotationLineItemId: requestProductType === 'SIMPLE' ? tempId : genUUID(),
      }));
      const resp = await configureProductService.configureProduct(quotationId, {
        productType: requestProductType,
        tempId,
        parts: partsReq,
        compositeProcesses,
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
        <FingerprintStatus
          rowCount={rows.length}
          checking={fingerprintChecking}
          matched={fingerprintMatched}
          matchedPartNo={fingerprintPartNo}
        />
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
  }, [effectiveLoading, hasTemplate, rows, submitting, fingerprintChecking, fingerprintMatched, fingerprintPartNo]);

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
            缺少选配模板 —— 请先在「配置中心 → 选配模板管理」为该客户所属产品分类或默认分类配置选配参数。
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
              editingRow={editingRow}
              onConfirm={handleSubConfirm}
              onCancel={handleSubCancel}
              onMaterialPreview={handleMaterialPreview}
            />
          </div>
          <div style={{ width: '38%', paddingLeft: 20 }}>
            <Preview3DPanel
              mode={effectivePreviewMode}
              materialLabel={effectivePreviewLabel}
              materialCode={effectivePreviewKey}
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
