import React, { useCallback, useEffect, useState } from 'react';
import { Drawer, Button, Steps, message, notification } from 'antd';
import { configureProductService } from '../../services/configureProductService';
import type {
  ProductType, PartMode, PartRequest, CompositeProcessRequest,
  LookupFingerprintSnapshot,
} from '../../types/configure';
import Step0ProductType from './configure/Step0ProductType';
import Step1SearchPart from './configure/Step1SearchPart';
import Step2Material from './configure/Step2Material';
import Step3Process from './configure/Step3Process';
import Step4CompositeProcess from './configure/Step4CompositeProcess';
import Step5Summary from './configure/Step5Summary';
import { genUUID } from '../../utils/uuid';

export interface PartState {
  name: string;
  partMode: PartMode | null;
  selectedHfPartNo: string | null;
  selectedRecipeCode: string | null;
  selectedRecipeSymbol: string | null;
  elementOverrides: { [code: string]: number };
  matLocked: boolean;
  processIds: string[];
  unitWeightGrams: number | null;
  reusedFromExisting: { hfPartNo: string; snapshot?: LookupFingerprintSnapshot } | null;
}

export interface CompositeProcessAdded {
  defCode: string;
  participatingPartIndexes: number[];
  params: Record<string, any>;
}

interface Props {
  open: boolean;
  quotationId: string;
  onCancel: () => void;
  onConfirm: (lineItems: any[]) => void;
}

const ConfigureProductDrawer: React.FC<Props> = ({ open, quotationId, onCancel, onConfirm }) => {
  const [globalStep, setGlobalStep] = useState<0 | 1 | 2 | 3>(0);
  const [subStep, setSubStep] = useState<0 | 1 | 2>(0);
  const [productType, setProductType] = useState<ProductType>('SIMPLE');
  const [initPartCount, setInitPartCount] = useState(2);
  const [parts, setParts] = useState<PartState[]>([]);
  const [ci, setCi] = useState(0);
  const [addedCProcs, setAddedCProcs] = useState<CompositeProcessAdded[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const updateCurrentPart = useCallback((patch: Partial<PartState>) => {
    setParts(prev => prev.map((p, i) => (i === ci ? { ...p, ...patch } : p)));
  }, [ci]);

  const updatePart = useCallback((idx: number, patch: Partial<PartState>) => {
    setParts(prev => prev.map((p, i) => (i === idx ? { ...p, ...patch } : p)));
  }, []);

  const initParts = useCallback((type: ProductType, n: number): PartState[] => {
    const count = type === 'COMPOSITE' ? n : 1;
    return Array.from({ length: count }, (_, i) => ({
      name: type === 'COMPOSITE' ? `配件 ${i + 1}` : '产品',
      partMode: null,
      selectedHfPartNo: null,
      selectedRecipeCode: null,
      selectedRecipeSymbol: null,
      elementOverrides: {},
      matLocked: false,
      processIds: [],
      unitWeightGrams: null,
      reusedFromExisting: null,
    }));
  }, []);

  // 自定义路径配件的"完整性"校验:返回 null=OK,字符串=失败原因(单条 toast 内容)
  const validateCustomPart = (p: PartState, label: string): string | null => {
    if (p.partMode !== 'custom') return null;
    if (!p.selectedRecipeCode) return `${label}:请先选择材质`;
    const sum = Object.values(p.elementOverrides).reduce((a, b) => a + (Number(b) || 0), 0);
    if (Math.abs(sum - 100) > 0.01) {
      return `${label}:元素含量之和 ${sum.toFixed(2)}%,必须 = 100%`;
    }
    return null;
  };

  /**
   * 指纹检查 — 仅提示, 不阻塞流程.
   *
   * 2026-05-19 设计调整 (用户选择 "默认不跳, 只提示发现重复料号, 已可继续选工序"):
   *   - 命中匹配时弹 notification.info (非阻塞), 默认 wizard 继续走 subStep=2 选工序
   *   - notification 里给一个 "复用此料号 (跳过工序)" 的快捷按钮 — 用户主动点击才走老分支
   *   - 与之前 Modal.confirm 比: 不再 "默认跳过工序" — 工序选择是默认路径
   *
   * 函数总返回 false (= 不打断 goNext, 让外层继续 setSubStep(2)).
   */
  const checkFingerprintAndAdvance = useCallback(async (): Promise<boolean> => {
    const cur = parts[ci];
    if (!cur || cur.partMode !== 'custom' || !cur.selectedRecipeCode) return false;
    const elements = Object.entries(cur.elementOverrides).map(([elementCode, pct]) => ({
      elementCode,
      pct: Number(pct),
    }));
    try {
      const resp = await configureProductService.lookupFingerprint({
        productType: 'SIMPLE',
        recipeCode: cur.selectedRecipeCode,
        elements,
      });
      if (resp.matched && resp.hfPartNo) {
        const key = `fingerprint-match-${ci}`;
        /**
         * 复用现有料号 — 仅锁定料号身份(hfPartNo + 材质 + 元素), 工序仍走 subStep=2 让用户选.
         *
         * 2026-05-19 设计调整: 工序是"报价单层选择", 不是料号身份的一部分.
         *   点击此按钮 ≠ 跳过工序; 而是:
         *     - partMode 切到 'existing' (告诉后端用老 hfPartNo)
         *     - selectedHfPartNo 设到匹配的料号
         *     - reusedFromExisting 留作 UI 展示标记 + Step3Process 预填工序的种子
         *     - 继续 subStep=2 (工序选择, 预填该料号现有工序作为起点)
         *     - 提交时后端走 resolvePart `existing+processIds` 分支:
         *       保留老 hfPartNo, 用用户选的工序覆盖**当前客户**的 mat_process
         */
        const reuseExistingPart = () => {
          updateCurrentPart({
            partMode: 'existing',
            selectedHfPartNo: resp.hfPartNo!,
            matLocked: true,
            reusedFromExisting: { hfPartNo: resp.hfPartNo!, snapshot: resp.snapshot },
          });
          // 继续 subStep=2 工序选择 — 不跳!
          setSubStep(2);
          notification.destroy(key);
        };
        notification.info({
          key,
          message: '发现重复料号',
          description: (
            <div>
              <p style={{ marginBottom: 4 }}>
                系统已存在材质+元素完全相同的料号: <code>{resp.hfPartNo}</code>
              </p>
              <p style={{ marginBottom: 4, fontSize: 12, color: '#666' }}>
                参考工序: {resp.snapshot?.processes?.map(p => p.processCode).join(' → ') || '无'}
                {resp.snapshot?.unitWeightGrams != null && ` · 单重: ${resp.snapshot.unitWeightGrams} g/件`}
              </p>
              <p style={{ margin: 0, fontSize: 12, color: '#666' }}>
                工序为本报价单的工艺路径,与料号身份分离 — 复用料号后您仍可在下一步选/改工序.
              </p>
            </div>
          ),
          btn: (
            <Button type="primary" size="small" onClick={reuseExistingPart}>
              复用此料号 → 继续选工序
            </Button>
          ),
          duration: 0,
          placement: 'topRight',
        });
      }
    } catch (e: any) {
      message.warning('指纹查询失败,继续走未命中流程');
    }
    // 总返 false — 让 goNext 继续 setSubStep(2) 进工序选择
    return false;
  }, [parts, ci, productType, updateCurrentPart]);

  // 清理残留的指纹 notification.
  // ⚠ 故意不依赖 subStep — notification 恰好在 goNext() 把 subStep 1→2 的同一 tick 里弹出,
  // 若把 subStep 放进 deps, 上一轮 effect 的 cleanup 会在 subStep 切换时立即销毁刚弹出的 notification
  // (用户看到的"一闪即灭"). 只在 ci 切换 / globalStep 跳转 / 组件卸载 时清.
  useEffect(() => {
    const key = `fingerprint-match-${ci}`;
    return () => {
      notification.destroy(key);
    };
  }, [ci, globalStep]);

  const goNext = useCallback(async () => {
    if (globalStep === 0) {
      setParts(initParts(productType, initPartCount));
      setCi(0);
      setSubStep(0);
      setGlobalStep(1);
      return;
    }
    if (globalStep === 1) {
      if (subStep === 0) { setSubStep(1); return; }
      if (subStep === 1) {
        const cur = parts[ci];
        const partLabel = productType === 'COMPOSITE' ? `配件 ${ci + 1}` : '产品';
        const err = cur ? validateCustomPart(cur, partLabel) : null;
        if (err) { message.warning(err); return; }
        const matched = await checkFingerprintAndAdvance();
        if (matched) return;
        setSubStep(2);
        return;
      }
      if (subStep === 2) {
        if (ci < parts.length - 1) { setCi(ci + 1); setSubStep(0); return; }
        if (productType === 'COMPOSITE') { setGlobalStep(2); return; }
        setGlobalStep(3);
        return;
      }
    }
    if (globalStep === 2) { setGlobalStep(3); return; }
    if (globalStep === 3) { await submitConfigure(); }
  }, [globalStep, subStep, productType, parts, ci, initPartCount, checkFingerprintAndAdvance, initParts]);

  const goPrev = useCallback(() => {
    if (globalStep === 3) {
      if (productType === 'COMPOSITE') setGlobalStep(2);
      else { setSubStep(2); setCi(0); setGlobalStep(1); }
      return;
    }
    if (globalStep === 2) {
      setCi(parts.length - 1);
      setSubStep(2);
      setGlobalStep(1);
      return;
    }
    if (globalStep === 1) {
      if (subStep > 0) { setSubStep((subStep - 1) as 0 | 1); return; }
      if (ci > 0) { setCi(ci - 1); setSubStep(2); return; }
      setGlobalStep(0);
    }
  }, [globalStep, subStep, ci, parts.length, productType]);

  const submitConfigure = async () => {
    // 提交前最后一道防线:全量配件逐个扫,任一不合规则列原因不发请求
    for (let i = 0; i < parts.length; i++) {
      const label = productType === 'COMPOSITE' ? `配件 ${i + 1}` : '产品';
      const err = validateCustomPart(parts[i], label);
      if (err) { message.warning(err); return; }
    }
    setSubmitting(true);
    try {
      // 生成顶层 tempId（主 lineItem.id UUID），SIMPLE/COMPOSITE 共用
      const tempId = genUUID();
      const partsReq: PartRequest[] = parts.map(p => ({
        name: p.name,
        partMode: p.partMode!,
        existingHfPartNo: p.partMode === 'existing' ? p.selectedHfPartNo! : undefined,
        recipeCode: p.partMode === 'custom' ? p.selectedRecipeCode! : undefined,
        elements: p.partMode === 'custom'
          ? Object.entries(p.elementOverrides).map(([elementCode, pct]) => ({ elementCode, pct: Number(pct) }))
          : undefined,
        // hotfix: existing 模式 + processIds 非空时也要传给后端 — 物质相同但工序不同 = 不同商品,
        // 后端 ConfigureProductService.resolvePart 在 existing+processIds 分支会生成新 hfPartNo
        processIds: (
          (p.partMode === 'custom' && !p.reusedFromExisting) ||
          (p.partMode === 'existing' && p.processIds && p.processIds.length > 0)
        ) ? p.processIds : undefined,
        unitWeightGrams: (p.partMode === 'custom' && !p.reusedFromExisting && p.unitWeightGrams !== null)
          ? p.unitWeightGrams
          : undefined,
        // SIMPLE: 与顶层 tempId 同值；COMPOSITE: 每个子件独立 UUID（工序隔离键）
        quotationLineItemId: productType === 'SIMPLE' ? tempId : genUUID(),
      }));
      const compProcs: CompositeProcessRequest[] = addedCProcs.map(a => ({
        defCode: a.defCode,
        participatingPartIndexes: a.participatingPartIndexes,
        params: a.params,
      }));
      const resp = await configureProductService.configureProduct(quotationId, {
        productType,
        tempId,
        parts: partsReq,
        compositeProcesses: productType === 'COMPOSITE' ? compProcs : undefined,
      });
      if (resp.fingerprintMatched) {
        message.success(`已复用 ${resp.reusedHfPartNos.length} 个料号`);
      } else {
        message.success('选配成功');
      }
      onConfirm(resp.lineItems);
      resetState();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? e?.message ?? '选配失败');
    } finally {
      setSubmitting(false);
    }
  };

  const resetState = () => {
    setGlobalStep(0);
    setSubStep(0);
    setProductType('SIMPLE');
    setInitPartCount(2);
    setParts([]);
    setCi(0);
    setAddedCProcs([]);
  };

  const stepLabels = productType === 'COMPOSITE'
    ? ['产品类型', `配件选配 ×${parts.length || initPartCount}`, '组合工艺', '完成选配']
    : ['产品类型', '料号匹配', '材质选配', '工序选择', '完成选配'];
  const activeIdx = productType === 'COMPOSITE'
    ? (globalStep === 0 ? 0 : globalStep === 1 ? 1 : globalStep === 2 ? 2 : 3)
    : (globalStep === 0 ? 0 : globalStep === 3 ? 4 : subStep + 1);

  return (
    <Drawer
      title="添加产品 — 选配"
      open={open}
      onClose={() => { resetState(); onCancel(); }}
      width={960}
      placement="right"
      maskClosable={false}
      keyboard={false}
      destroyOnClose
      footer={
        <div style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 0' }}>
          <Button onClick={() => { resetState(); onCancel(); }}>取消</Button>
          <div>
            {globalStep > 0 && (
              <Button onClick={goPrev} style={{ marginRight: 8 }}>
                上一步
              </Button>
            )}
            <Button type="primary" onClick={goNext} loading={submitting}>
              {globalStep === 3 ? '确认添加' : '下一步'}
            </Button>
          </div>
        </div>
      }
    >
      <Steps
        current={activeIdx}
        items={stepLabels.map(l => ({ title: l }))}
        style={{ marginBottom: 24 }}
      />

      {globalStep === 0 && (
        <Step0ProductType
          productType={productType}
          onChangeType={setProductType}
          initPartCount={initPartCount}
          onChangePartCount={setInitPartCount}
        />
      )}

      {globalStep === 1 && parts[ci] && (
        <>
          {subStep === 0 && <Step1SearchPart part={parts[ci]} onUpdate={updateCurrentPart} />}
          {subStep === 1 && <Step2Material part={parts[ci]} onUpdate={updateCurrentPart} />}
          {subStep === 2 && <Step3Process part={parts[ci]} onUpdate={updateCurrentPart} />}
        </>
      )}

      {globalStep === 2 && (
        <Step4CompositeProcess
          parts={parts}
          addedCProcs={addedCProcs}
          onChangeAdded={setAddedCProcs}
        />
      )}

      {globalStep === 3 && (
        <Step5Summary
          productType={productType}
          parts={parts}
          addedCProcs={addedCProcs}
          onUpdatePart={updatePart}
        />
      )}
    </Drawer>
  );
};

export default ConfigureProductDrawer;
