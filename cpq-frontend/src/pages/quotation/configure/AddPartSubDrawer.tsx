/**
 * AddPartSubDrawer — 选配添加·新增/编辑材质料号 子框（task-0712 F5，D11/D14）。
 *
 * 1:1 对齐原型 `.sub-panel`：内层局部覆盖面板（非独立 AntD Drawer——fronttask.md F5 §5.2
 * 明确建议"内层局部切换，避免嵌套 Drawer 层级/ESC 冲突"，覆盖宿主 `detail-left` 列，
 * `position:absolute; inset:0`）。三段子步骤：① 材质(带过滤) → ② 元素含量(微调) → ③ 工序(带过滤)。
 *
 * 候选来源（api.md §1.4，D6）：材质/工序候选均来自 `effective.params[MATERIAL|PROCESS].effectiveValues`
 * （模板限定，留空=不限时后端已回填全量），不再是全量字典/`/processes`裸端点。
 *
 * ⚠️ 已核实的候选值语义 id/code 映射坑（写代码前用只读子代理 + 真实 DB 查证，非猜测）：
 * MATERIAL `effectiveValues[].key` = `material_recipe.code`（非 UUID id），而元素详情端点
 * `GET /material-recipes/{id}` 严格要 UUID——故本组件用 `materialDict`(`materialRecipeService.list()`
 * 全量字典，与候选同源 `material_recipe` 表，code 值域是全量字典的严格子集，可放心反查，
 * 已用子代理核实 `SelParamCandidateService` MATERIAL 分支与 `MaterialRecipeResource.list()` 同表)
 * 建 code→id 索引后再调 `materialRecipeService.detail(id)`。
 *
 * PROCESS `effectiveValues[].key` = `process_master.process_no`：F5 落地时提交端 `PartRequest.processIds`
 * 曾严格按旧 `process`(V4) 表 UUID 处理，前端一度靠 `/processes` 字典反查 + 禁选孤儿 code（TP10/TP20）做
 * 防御性缝合。**task-0712 缺口1 已在后端根治**（V336 迁移 + `PartRequest.processIds→processNos`）：
 * `quotation_line_process` 现直接落 `process_no`，孤儿 code 也能正常提交。本组件不再需要
 * `process`(V4) 字典/UUID 映射/禁选分支，候选 `key`（= process_no）原样即为提交值。
 */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Input, InputNumber, Empty, Alert, Button, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import {
  materialRecipeService,
  type MaterialRecipeLite,
  type MaterialRecipeElement,
} from '../../../services/materialRecipeService';
import type { EffectiveTemplateDTO, SelDetailRow } from '../../../types/configure';
import { genUUID } from '../../../utils/uuid';

interface Props {
  open: boolean;
  effective: EffectiveTemplateDTO;
  materialDict: MaterialRecipeLite[];
  /** null = 新增；非空 = 编辑该行 */
  editingRow: SelDetailRow | null;
  onConfirm: (row: SelDetailRow) => void;
  onCancel: () => void;
  onMaterialPreview: (recipeCode: string | null, label: string) => void;
}

type SubStep = 1 | 2 | 3;

const swatchColors = [
  '#b9c4d1', '#a9b6c8', '#e0c68a', '#e6cf94', '#d5dbe3',
  '#c7ced9', '#9aa5b1', '#f0b7b7', '#b7e0c9', '#c9b7e0',
];
function swatchColor(code: string): string {
  let h = 0;
  for (let i = 0; i < code.length; i++) h = (h * 31 + code.charCodeAt(i)) >>> 0;
  return swatchColors[h % swatchColors.length];
}

const AddPartSubDrawer: React.FC<Props> = ({
  open, effective, materialDict, editingRow, onConfirm, onCancel, onMaterialPreview,
}) => {
  const [step, setStep] = useState<SubStep>(1);
  const [materialCode, setMaterialCode] = useState<string | null>(null);
  const [materialLabel, setMaterialLabel] = useState('');
  const [elementDefs, setElementDefs] = useState<MaterialRecipeElement[]>([]);
  const [elementValues, setElementValues] = useState<Record<string, number>>({});
  const [elementLoading, setElementLoading] = useState(false);
  const [selectedProcesses, setSelectedProcesses] = useState<Array<{ id: string; label: string }>>([]);
  const [materialFilter, setMaterialFilter] = useState('');
  const [processFilter, setProcessFilter] = useState('');

  // 打开/切换编辑目标时重置或回填种子状态。
  useEffect(() => {
    if (!open) return;
    if (editingRow) {
      setMaterialCode(editingRow.recipeCode);
      setMaterialLabel(editingRow.recipeLabel);
      setElementValues({ ...editingRow.elementOverrides });
      setSelectedProcesses(
        editingRow.processNos.map((no, i) => ({ id: no, label: editingRow.processLabels[i] ?? no })),
      );
      onMaterialPreview(editingRow.recipeCode, editingRow.recipeLabel);
    } else {
      setMaterialCode(null);
      setMaterialLabel('');
      setElementValues({});
      setSelectedProcesses([]);
    }
    setStep(1);
    setMaterialFilter('');
    setProcessFilter('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, editingRow?.rowId]);

  // 材质选定后拉元素详情（code→id 反查 materialDict，见文件头注释坑①）。
  // requestSeq 防连续切材质时旧请求晚到覆盖新选择（老 Step2Material.tsx 同类调用无此防护，
  // 属本次顺手加固，非回归）。
  const elementReqSeq = useRef(0);
  useEffect(() => {
    if (!open || !materialCode) { setElementDefs([]); return; }
    const rec = materialDict.find((m) => m.code === materialCode);
    if (!rec) { setElementDefs([]); return; }
    const seq = ++elementReqSeq.current;
    setElementLoading(true);
    materialRecipeService.detail(rec.id)
      .then((d) => {
        if (elementReqSeq.current !== seq) return; // 已被更新的选择取代，丢弃过期响应
        setElementDefs(d.elements);
        setElementValues((prev) => {
          if (Object.keys(prev).length > 0) return prev; // 已有值(编辑态/已微调过)不覆盖
          const init: Record<string, number> = {};
          d.elements.forEach((e) => { init[e.elementCode] = Number(e.defaultPct); });
          return init;
        });
      })
      .catch(() => { if (elementReqSeq.current === seq) setElementDefs([]); })
      .finally(() => { if (elementReqSeq.current === seq) setElementLoading(false); });
  }, [open, materialCode, materialDict]);

  const materialCandidates = useMemo(
    () => effective.params.find((p) => p.paramTypeCode === 'MATERIAL')?.effectiveValues ?? [],
    [effective],
  );
  const processCandidates = useMemo(
    () => effective.params.find((p) => p.paramTypeCode === 'PROCESS')?.effectiveValues ?? [],
    [effective],
  );
  const filteredMaterials = useMemo(() => {
    const kw = materialFilter.trim().toLowerCase();
    if (!kw) return materialCandidates;
    return materialCandidates.filter(
      (c) => c.label.toLowerCase().includes(kw) || c.key.toLowerCase().includes(kw),
    );
  }, [materialCandidates, materialFilter]);

  const filteredProcesses = useMemo(() => {
    const kw = processFilter.trim().toLowerCase();
    if (!kw) return processCandidates;
    return processCandidates.filter(
      (c) => c.label.toLowerCase().includes(kw) || c.key.toLowerCase().includes(kw),
    );
  }, [processCandidates, processFilter]);

  const selectMaterial = (code: string, label: string) => {
    const changed = code !== materialCode;
    setMaterialCode(code);
    setMaterialLabel(label);
    if (changed) setElementValues({});
    onMaterialPreview(code, label);
  };

  const setElem = (code: string, v: number) => {
    setElementValues((prev) => ({ ...prev, [code]: v }));
  };

  const isProcessSelected = (id: string) => selectedProcesses.some((p) => p.id === id);
  const toggleProcess = (id: string, label: string) => {
    if (isProcessSelected(id)) setSelectedProcesses((prev) => prev.filter((p) => p.id !== id));
    else setSelectedProcesses((prev) => [...prev, { id, label }]);
  };
  const removeProcess = (id: string) => setSelectedProcesses((prev) => prev.filter((p) => p.id !== id));

  const sumPct = Object.values(elementValues).reduce((a, b) => a + (Number(b) || 0), 0);
  const sumOk = Math.abs(sumPct - 100) < 0.01;

  const goNext = () => {
    if (step === 1) {
      if (!materialCode) { message.warning('请先选择材质'); return; }
      setStep(2);
      return;
    }
    if (step === 2) {
      setStep(3);
      return;
    }
    confirmAdd();
  };
  const goPrev = () => { if (step > 1) setStep((step - 1) as SubStep); };
  const goStepIfAllowed = (target: SubStep) => {
    if (target >= 2 && !materialCode) return;
    setStep(target);
  };

  const confirmAdd = () => {
    if (!materialCode) { message.warning('请先选择材质'); return; }
    if (!sumOk) { message.warning(`元素含量之和 ${sumPct.toFixed(2)}%，必须 = 100%`); return; }
    const row: SelDetailRow = {
      rowId: editingRow?.rowId ?? genUUID(),
      recipeCode: materialCode,
      recipeLabel: materialLabel,
      elementOverrides: { ...elementValues },
      processNos: selectedProcesses.map((p) => p.id),
      processLabels: selectedProcesses.map((p) => p.label),
      quantity: editingRow?.quantity ?? 1,
      unitWeightGrams: editingRow?.unitWeightGrams ?? null,
    };
    onConfirm(row);
  };

  if (!open) return null;

  const stepLabels: Record<SubStep, string> = { 1: '① 材质', 2: '② 元素含量', 3: '③ 工序' };

  return (
    <div
      style={{
        position: 'absolute', inset: 0, zIndex: 5, background: '#fff', display: 'flex',
        flexDirection: 'column', boxShadow: '-6px 0 16px rgba(0,0,0,.06)',
      }}
    >
      <div style={{ padding: '14px 20px 12px', borderBottom: '1px solid #f0f0f0', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0 }}>
        <span style={{ fontSize: 14, fontWeight: 600 }}>{editingRow ? '编辑材质料号' : '新增材质料号'}</span>
        <span style={{ cursor: 'pointer', color: '#909399', fontSize: 18, lineHeight: 1 }} onClick={onCancel}>✕</span>
      </div>

      <div style={{ display: 'flex', padding: '12px 20px 0', flexShrink: 0 }}>
        {([1, 2, 3] as SubStep[]).map((s) => (
          <div
            key={s}
            onClick={() => goStepIfAllowed(s)}
            style={{
              flex: 1, textAlign: 'center', paddingBottom: 10,
              borderBottom: `2px solid ${s === step ? '#1890ff' : '#f0f0f0'}`,
              color: s === step ? '#1890ff' : s < step ? '#606266' : '#909399',
              fontSize: 12.5, fontWeight: s === step ? 600 : 400,
              cursor: materialCode || s === 1 ? 'pointer' : 'not-allowed',
            }}
          >
            {stepLabels[s]}
          </div>
        ))}
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '16px 20px' }}>
        {step === 1 && (
          <>
            <Input
              prefix={<SearchOutlined />}
              placeholder="搜索材质名称 / 代号，如「不锈钢」「H62」…"
              value={materialFilter}
              onChange={(e) => setMaterialFilter(e.target.value)}
              style={{ marginBottom: 14 }}
              allowClear
            />
            {materialCandidates.length === 0 ? (
              <Empty description="该模板未启用材质参数，请联系管理员配置" />
            ) : filteredMaterials.length === 0 ? (
              <Empty description="未找到匹配的材质，请调整搜索词" />
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
                {filteredMaterials.map((c) => {
                  const sel = c.key === materialCode;
                  return (
                    <div
                      key={c.key}
                      onClick={() => selectMaterial(c.key, c.label)}
                      style={{
                        border: `1.5px solid ${sel ? '#1890ff' : '#e4e7ed'}`,
                        background: sel ? '#e6f7ff' : '#fff',
                        borderRadius: 8, padding: '12px 8px', textAlign: 'center', cursor: 'pointer',
                      }}
                    >
                      <div style={{ width: '100%', height: 34, borderRadius: 6, marginBottom: 6, background: swatchColor(c.key) }} />
                      <div style={{ fontSize: 12.5, fontWeight: 500, color: '#303133' }}>{c.label}</div>
                      <div style={{ fontSize: 11, color: '#909399', marginTop: 1 }}>{c.key}</div>
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}

        {step === 2 && (
          <>
            <div style={{ fontSize: 12.5, color: '#909399', marginBottom: 14 }}>
              以下为所选材质 <b>{materialLabel || materialCode}</b> 派生的元素含量，允许在合理范围内微调
            </div>
            {elementLoading ? (
              <div style={{ textAlign: 'center', padding: '24px 0', color: '#c0c4cc' }}>加载中…</div>
            ) : elementDefs.length === 0 && Object.keys(elementValues).length === 0 ? (
              <Empty description="该材质无元素配比数据" />
            ) : (
              <>
                {(elementDefs.length > 0 ? elementDefs : Object.keys(elementValues).map((code) => ({ elementCode: code, elementName: code } as MaterialRecipeElement))).map((e) => (
                  <div key={e.elementCode} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 0', borderBottom: '1px solid #f5f5f5' }}>
                    <div style={{ width: 110, flexShrink: 0, fontSize: 13, color: '#303133' }}>
                      <span style={{ display: 'inline-block', width: 22, height: 22, lineHeight: '22px', textAlign: 'center', background: '#f0f5ff', color: '#1890ff', borderRadius: 4, fontWeight: 600, marginRight: 6, fontSize: 11.5 }}>
                        {e.elementCode}
                      </span>
                      {e.elementName || e.elementCode}
                    </div>
                    <InputNumber
                      value={elementValues[e.elementCode] ?? Number((e as MaterialRecipeElement).defaultPct ?? 0)}
                      step={0.1}
                      addonAfter="%"
                      onChange={(v) => setElem(e.elementCode, Number(v ?? 0))}
                    />
                  </div>
                ))}
                <Alert
                  style={{ marginTop: 12 }}
                  type={sumOk ? 'success' : 'warning'}
                  showIcon
                  message={sumOk ? `含量之和 ${sumPct.toFixed(2)}%，配比正确` : `含量之和 ${sumPct.toFixed(2)}%，必须调整至 100%`}
                />
              </>
            )}
          </>
        )}

        {step === 3 && (
          <>
            <div style={{ fontSize: 12.5, color: '#909399', marginBottom: 14 }}>多选工序，按选中顺序记录加工顺序</div>
            <Input
              prefix={<SearchOutlined />}
              placeholder="搜索工序名称，如「车」「电镀」…"
              value={processFilter}
              onChange={(e) => setProcessFilter(e.target.value)}
              style={{ marginBottom: 14 }}
              allowClear
            />
            {processCandidates.length === 0 ? (
              <Empty description="该模板未启用工序参数，可跳过此步" />
            ) : filteredProcesses.length === 0 ? (
              <Empty description="未找到匹配的工序，请调整搜索词" />
            ) : (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
                {filteredProcesses.map((c) => {
                  const checked = isProcessSelected(c.key);
                  return (
                    <label
                      key={c.key}
                      style={{
                        display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px',
                        border: '1px solid #e4e7ed', borderRadius: 16, fontSize: 12.5, cursor: 'pointer',
                      }}
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggleProcess(c.key, c.label)}
                      />
                      <span>{c.label}</span>
                    </label>
                  );
                })}
              </div>
            )}
            <div style={{ marginTop: 10 }}>
              {selectedProcesses.length === 0 ? (
                <div style={{ color: '#c0c4cc', fontSize: 12 }}>尚未选择工序</div>
              ) : (
                <>
                  <div style={{ fontSize: 12, color: '#909399', marginBottom: 4 }}>已选顺序：</div>
                  {selectedProcesses.map((p, i) => (
                    <span
                      key={p.id}
                      style={{
                        display: 'inline-flex', alignItems: 'center', gap: 5, background: '#f0f5ff', color: '#1890ff',
                        padding: '4px 10px', borderRadius: 12, fontSize: 12, margin: '4px 6px 0 0',
                      }}
                    >
                      {i + 1}. {p.label}
                      <b style={{ cursor: 'pointer', fontWeight: 400, color: '#909399' }} onClick={() => removeProcess(p.id)} title="移除">✕</b>
                    </span>
                  ))}
                </>
              )}
            </div>
          </>
        )}
      </div>

      <div style={{ padding: '12px 20px', borderTop: '1px solid #f0f0f0', display: 'flex', gap: 8, justifyContent: 'flex-end', flexShrink: 0 }}>
        <Button onClick={onCancel}>取消</Button>
        <Button onClick={goPrev} disabled={step === 1}>上一步</Button>
        <Button type="primary" onClick={goNext}>{step === 3 ? '确认添加' : '下一步'}</Button>
      </div>
    </div>
  );
};

export default AddPartSubDrawer;
