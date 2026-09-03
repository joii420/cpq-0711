/**
 * ConfigureProductDrawer — 报价单 Step2「添加产品 ▾ → 选配添加」抽屉。
 * task-260902 · F-2 / F-9 / F-13（在 task-0712 F5 / task-260901 的基础上**整体重做**）。
 *
 * 🔄 **模型变化（本次重构的核心）**：
 *   旧：产品 → 配件（配件 ≡ 一个材质料号，`PartRequest.recipeCode` 单值）
 *   新：产品 → **配件（零件 / 外购件）** → 零件挂 1~N 个材质（带占比） → 每个材质选含量配置
 *   ⇒ 交互从「单屏明细表」改为 **4 步向导**：客户产品编号 → 添加配件 → 组合工序 → 确认并添加。
 *
 * 视觉基准 = `dev-docs/task-260902-选配流程重构/原型图/` 下的 **6 份独立原型**
 * （`选配流程-交互原型.html` 是汇报展示用，不是验收基准）。
 *
 * ── F-13 选配模板下线（AC-25 / AC-26）────────────────────────────────────────
 *   🚫 **移除了 `hasTemplate` 门禁**：旧版 `if (effectiveLoading || !hasTemplate)` 会让无模板的客户
 *      只剩一个「取消」按钮 + 「缺少选配模板」空态，**整个选配功能对他不可用**。
 *      实测选配模板唯一真正生效的就是这道门禁（三个参数开关与值域限定全部空转，见需求文档 §4.3c）
 *      ⇒ 本期下线：**无模板也能正常走完 4 步并提交**。
 *   ✅ **`effective` 的候选限定用法保持不动**（fronttask F-13 明确要求）：仍然调
 *      `selTemplateService.effective`，若 `effectiveValues` 非空就据此**收窄**候选集合；
 *      为空则全放行（实测 `sel_template_item_value` 现网 0 行 ⇒ 两条分支今天等价）。
 *      🚨 但它**不再能阻断渲染** —— 解析失败/无模板一律按「不限」处理，绝不回到空态。
 *   📌 路由 `/config/sel-templates` 与页面文件**保留不删**，只在 `MainLayout` 隐藏菜单入口
 *      （AC-26 有反向断言：直接访问 URL 仍要能打开）。
 *
 * ── 选择器数据源（api.md §0 / §3）──────────────────────────────────────────────
 *   材质：`GET /material-recipes`（**裸数组**）—— F-6 要的 `configCount` / `allowCustomContent`
 *         只有这里有，`effectiveValues` 只给得出 `{key,label}`。
 *   工序：`GET /sel-param-types/PROCESS/candidates`（**信封 `{code,data}`**）。
 *   🚨 同一个后端两种包装格式，🚫 不要假设统一（2026-09-02 实调 8081 确认）。
 */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Drawer, Steps, message } from 'antd';
import { configureProductService } from '../../services/configureProductService';
import { selTemplateService } from '../../services/selTemplateService';
import { materialRecipeService, type MaterialRecipeLite } from '../../services/materialRecipeService';
import { selParamCandidateService, type SelParamCandidate } from '../../services/selParamCandidateService';
import type {
  CompositeProcessItem, ConfigurePart, ConfigureProductResponse, EffectiveTemplateDTO, PartRequest,
} from '../../types/configure';
import { genUUID } from '../../utils/uuid';
import AddPartSubDrawer from './configure/AddPartSubDrawer';
import CompositeProcessStep from './configure/CompositeProcessStep';
import ConfirmStep, { type FingerprintPreview, type SubmitFailure } from './configure/ConfirmStep';
import CustomerProductNoStep, {
  IDLE_CHECK, productNoStepReason, type ProductNoCheckState,
} from './configure/CustomerProductNoStep';
import PartCardList from './configure/PartCardList';
import { buildConfigureParts } from './configure/configurePartsRequest';
import { ReasonedButton } from './configure/configureUi';

interface Props {
  open: boolean;
  quotationId: string;
  /** 客户编码（`customer.code`）。用于编号占用校验、模板解析与指纹的客户维度隔离。 */
  customerNo: string | undefined;
  customerLabel?: string;
  onCancel: () => void;
  onConfirm: (lineItems: any[]) => void;
  /** AC-2：编号已被占用时跳去「从产品库添加」并定位到该产品。 */
  onOpenExistingProducts?: (productNo: string) => void;
}

const STEP_TITLES = ['客户产品编号', '添加配件', '组合工序', '确认并添加'];

const ConfigureProductDrawer: React.FC<Props> = ({
  open, quotationId, customerNo, customerLabel, onCancel, onConfirm, onOpenExistingProducts,
}) => {
  const [step, setStep] = useState(0);

  // 步骤 1
  const [productNo, setProductNo] = useState('');
  const [productName, setProductName] = useState('');
  const [check, setCheck] = useState<ProductNoCheckState>(IDLE_CHECK);

  // 步骤 2
  const [parts, setParts] = useState<ConfigurePart[]>([]);
  const [subOpen, setSubOpen] = useState(false);
  const [editingUid, setEditingUid] = useState<string | null>(null);

  // 步骤 3
  const [composites, setComposites] = useState<CompositeProcessItem[]>([]);

  // 候选数据
  const [materials, setMaterials] = useState<MaterialRecipeLite[]>([]);
  const [materialsLoading, setMaterialsLoading] = useState(false);
  const [materialsError, setMaterialsError] = useState<string | null>(null);
  const [processCandidates, setProcessCandidates] = useState<SelParamCandidate[]>([]);
  const [processLoading, setProcessLoading] = useState(false);
  const [processError, setProcessError] = useState<string | null>(null);

  // 步骤 4
  const [preview, setPreview] = useState<FingerprintPreview>({ checking: false, matched: false });
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<ConfigureProductResponse | null>(null);
  const [failure, setFailure] = useState<SubmitFailure | null>(null);

  const resetState = () => {
    setStep(0);
    setProductNo(''); setProductName(''); setCheck(IDLE_CHECK);
    setParts([]); setSubOpen(false); setEditingUid(null);
    setComposites([]);
    setPreview({ checking: false, matched: false });
    setSubmitting(false); setResult(null); setFailure(null);
  };

  // 打开抽屉：重置 + 拉候选。
  // 🚨 **候选拉取失败不阻断渲染** —— 各选择器自己渲染错误态，用户仍能操作其他步骤。
  useEffect(() => {
    if (!open) return;
    resetState();

    setMaterialsLoading(true);
    setMaterialsError(null);
    setProcessLoading(true);
    setProcessError(null);

    // `effective` 只用来**收窄**候选；它失败 / 无模板一律按「不限」处理（F-13：不再有门禁）
    const effectivePromise: Promise<EffectiveTemplateDTO | null> = customerNo
      ? selTemplateService.effective(customerNo).catch(() => null)
      : Promise.resolve(null);

    Promise.all([effectivePromise, materialRecipeService.list()])
      .then(([eff, mats]) => {
        // 只列 ACTIVE 材质（AC-18 口径）。客户端过滤而不是加查询参数 —— 后端若已只返 ACTIVE 则是 no-op。
        let list = (mats ?? []).filter((m) => (m.status ?? 'ACTIVE') === 'ACTIVE');
        const allowed = eff?.params.find((p) => p.paramTypeCode === 'MATERIAL')?.effectiveValues ?? [];
        if (allowed.length > 0) {
          const keys = new Set(allowed.map((v) => v.key));
          list = list.filter((m) => keys.has(m.code));
        }
        setMaterials(list);
      })
      .catch((e: any) => { setMaterials([]); setMaterialsError(e?.message || '加载材质库失败'); })
      .finally(() => setMaterialsLoading(false));

    Promise.all([effectivePromise, selParamCandidateService.list('PROCESS')])
      .then(([eff, cands]) => {
        let list = cands ?? [];
        const allowed = eff?.params.find((p) => p.paramTypeCode === 'PROCESS')?.effectiveValues ?? [];
        if (allowed.length > 0) {
          const keys = new Set(allowed.map((v) => v.key));
          list = list.filter((c) => keys.has(c.key));
        }
        setProcessCandidates(list);
      })
      .catch((e: any) => { setProcessCandidates([]); setProcessError(e?.message || '加载工序候选失败'); })
      .finally(() => setProcessLoading(false));
  }, [open, customerNo]);

  // ── 指纹预览（确认页的绿/蓝提示条）──
  // 只在进到步骤 4 时跑，防抖 500ms；`seq` 丢弃过期响应。
  const previewSeq = useRef(0);
  useEffect(() => {
    if (step !== 3 || parts.length === 0 || !customerNo || result) {
      return;
    }
    const custNo = customerNo;
    const seq = ++previewSeq.current;
    setPreview({ checking: true, matched: false });
    const timer = window.setTimeout(() => {
      const { parts: partsReq, compositeProcesses } = buildConfigureParts(parts, composites);
      configureProductService.lookupFingerprint({ customerNo: custNo, parts: partsReq, compositeProcesses })
        .then((res) => {
          if (previewSeq.current !== seq) return;
          setPreview({
            checking: false,
            matched: !!res.matched,
            matchedPartNo: res.matched ? (res.matchedPartNo || res.hfPartNo) : undefined,
          });
        })
        .catch(() => {
          if (previewSeq.current !== seq) return;
          // 预览失败按「未命中」渲染 —— 它只是提示，最终以提交响应为准
          setPreview({ checking: false, matched: false });
        });
    }, 500);
    return () => window.clearTimeout(timer);
  }, [step, parts, composites, customerNo, result]);

  // ── 步骤放行判据（每一步的「下一步」禁用原因；null = 放行）──
  const stepReasons = useMemo<(string | null)[]>(() => [
    productNoStepReason(productNo, check),                              // AC-1 / AC-2
    parts.length === 0 ? '请至少添加一个配件' : null,                     // AC-14 的产品层对应物
    null,                                                               // 组合工序不是必填
    null,
  ], [productNo, check, parts.length]);

  /** 可跳到的最远步骤：从头往后扫，遇到第一个不放行的就停在那儿。 */
  const maxReachableStep = useMemo(() => {
    for (let i = 0; i < stepReasons.length; i++) {
      if (stepReasons[i]) return i;
    }
    return stepReasons.length - 1;
  }, [stepReasons]);

  const editingPart = editingUid ? parts.find((p) => p.uid === editingUid) ?? null : null;

  const upsertPart = (part: ConfigurePart) => {
    setParts((prev) => {
      const idx = prev.findIndex((p) => p.uid === part.uid);
      if (idx >= 0) { const next = [...prev]; next[idx] = part; return next; }
      return [...prev, part];
    });
    setSubOpen(false);
    setEditingUid(null);
  };

  const submit = async () => {
    if (!quotationId) { message.error('报价单尚未创建，无法选配'); return; }
    setSubmitting(true);
    setFailure(null);
    try {
      const tempId = genUUID();
      const { productType, parts: baseParts, compositeProcesses } = buildConfigureParts(parts, composites);
      // 工序隔离键：SIMPLE 与顶层 tempId 同值；COMPOSITE 每个子件独立 UUID（沿用 task-0712 语义）
      const partsReq: PartRequest[] = baseParts.map((p) => ({
        ...p,
        quotationLineItemId: productType === 'SIMPLE' ? tempId : genUUID(),
      }));
      const resp = await configureProductService.configureProduct(quotationId, {
        productType,
        tempId,
        customerProductNo: productNo.trim(),
        customerProductName: productName.trim() || undefined,
        parts: partsReq,
        compositeProcesses,
      });
      setResult(resp);
    } catch (e: any) {
      // 错误码从 `ApiError.code` 取（`services/api.ts` 已把信封的 code/detail 带出来）
      setFailure({ message: e?.message || '选配失败', code: e?.code });
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * 关闭抽屉。
   * 🚨 **已提交成功但还没交给宿主的 lineItems 必须在这里补交** —— 否则用户点 ✕ 而不是「完成」
   *    就会出现「后端已经建好料号、报价单里却没有这一行」的静默丢数据。
   */
  const handleClose = () => {
    if (result) {
      const items = result.lineItems;
      resetState();
      onConfirm(items);
      return;
    }
    resetState();
    onCancel();
  };

  const jumpToProductLibrary = (no: string) => {
    resetState();
    if (onOpenExistingProducts) onOpenExistingProducts(no);
    else onCancel();
  };

  // ── footer ──
  const footer = (() => {
    if (result) {
      return (
        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button type="primary" onClick={handleClose}>完成</Button>
        </div>
      );
    }
    const reason = stepReasons[step];
    return (
      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', alignItems: 'center' }}>
        <Button onClick={handleClose}>取消</Button>
        {step > 0 ? <Button onClick={() => setStep((s) => s - 1)}>上一步</Button> : null}
        {step < 3 ? (
          /* §1.2：禁用但可见 + tooltip 写明原因 */
          <ReasonedButton type="primary" reason={reason} onClick={() => setStep((s) => s + 1)}>下一步</ReasonedButton>
        ) : (
          <Button type="primary" loading={submitting} onClick={submit}>
            {preview.matched && preview.matchedPartNo
              ? `添加到报价单（复用 ${preview.matchedPartNo}）`
              : '添加到报价单'}
          </Button>
        )}
      </div>
    );
  })();

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
      <div style={{ position: 'relative', minHeight: 520 }}>
        <Steps
          size="small"
          current={result ? 3 : step}
          style={{ marginBottom: 20 }}
          onChange={(next) => {
            // 已完成的步骤可点回跳；🚫 不可跳到还没走完的步骤（跳过去只会看到一个填不了的表单）
            if (result) return;
            if (next <= maxReachableStep) setStep(next);
          }}
          items={STEP_TITLES.map((title, i) => ({
            title,
            disabled: !!result || i > maxReachableStep,
          }))}
        />

        {step === 0 && (
          <CustomerProductNoStep
            customerNo={customerNo}
            customerLabel={customerLabel}
            productNo={productNo}
            productName={productName}
            check={check}
            onProductNoChange={setProductNo}
            onProductNameChange={setProductName}
            onCheckChange={setCheck}
            onOpenExistingProducts={jumpToProductLibrary}
          />
        )}

        {step === 1 && (
          <PartCardList
            parts={parts}
            onAdd={() => { setEditingUid(null); setSubOpen(true); }}
            onEdit={(uid) => { setEditingUid(uid); setSubOpen(true); }}
            onRemove={(uid) => setParts((prev) => prev.filter((p) => p.uid !== uid))}
          />
        )}

        {step === 2 && (
          <CompositeProcessStep parts={parts} value={composites} onChange={setComposites} />
        )}

        {step === 3 && (
          <ConfirmStep
            customerProductNo={productNo.trim()}
            customerProductName={productName.trim()}
            parts={parts}
            composites={composites}
            preview={preview}
            result={result}
            failure={failure}
            onOpenExistingProducts={jumpToProductLibrary}
          />
        )}

        {/* 内层局部面板覆盖抽屉正文（🚫 不嵌套 Drawer，避免层级 / ESC 冲突） */}
        <AddPartSubDrawer
          key={editingUid ?? '__new__'}
          open={subOpen}
          editing={editingPart}
          materials={materials}
          materialsLoading={materialsLoading}
          materialsError={materialsError}
          processCandidates={processCandidates}
          processLoading={processLoading}
          processError={processError}
          onConfirm={upsertPart}
          onCancel={() => { setSubOpen(false); setEditingUid(null); }}
        />
      </div>
    </Drawer>
  );
};

export default ConfigureProductDrawer;
