/**
 * AddPartSubDrawer — 选配添加·新增/编辑材质料号 子框（task-0712 F5，D11/D14；task-260901 F-8/F-9/F-10 改版）。
 *
 * 1:1 对齐原型 `.sub-panel`：内层局部覆盖面板（非独立 AntD Drawer——fronttask.md F5 §5.2
 * 明确建议"内层局部切换，避免嵌套 Drawer 层级/ESC 冲突"，覆盖宿主 `detail-left` 列，
 * `position:absolute; inset:0`）。
 *
 * ── task-260901（对照 `原型图/5-选配含量配置选择.html` 状态 A / B / C / D）──
 *   ⚠️ **子步骤由 3 段并为 2 段**：`① 材质与含量` → `② 工序`。
 *      原型 5 的四个状态都把「材质」与「含量配置」画在**同一屏**上（状态 D 里材质还没选时，
 *      「含量配置」一栏就已经在，显示「请先选择材质」）—— 原 task-0712 的 `② 元素含量` 微调步骤
 *      正是本次被「选标准配置 / 自定义含量」取代的那一步，不再单独成步。
 *   F-8  材质改 **AntD Select**（258 项走虚拟滚动，必须靠搜索；可按材质编号 / 材质名过滤）；
 *        选中后「含量配置」下拉可用，选项文案 `00006-01（Ag 90% / Ni 10%）`，只列 ACTIVE 配置，
 *        可按配置编号搜索。
 *   F-9  `allowCustomContent=true` 时可切到自定义：元素行**只能改含量、不能增删**（元素来自材质的
 *        元素组成）；合计实时校验，不为 100% 时行级红框 + 「下一步」禁用且 tooltip 写实际值。
 *   F-10 `configCount=0` 的材质在下拉里**灰显不可选**，右侧直接写原因「该材质尚未配置含量」。
 *        🚫 **不许从列表中过滤掉** —— 用户会以为材质丢了。
 *
 * 🚨 含量一律按字符串处理，禁止 `Number()` / `parseFloat`（`numeric(16,12)`，12 位小数）；
 *    显示与输入框回填都去尾随 0（AC-30），提交时原样发送。
 *
 * 候选来源（api.md §1.4，D6）：材质/工序候选均来自 `effective.params[MATERIAL|PROCESS].effectiveValues`
 * （模板限定，留空=不限时后端已回填全量），不再是全量字典/`/processes`裸端点。
 *
 * ⚠️ 已核实的候选值语义 id/code 映射坑：MATERIAL `effectiveValues[].key` = `material_recipe.code`
 * （非 UUID id），而详情端点 `GET /material-recipes/{id}` 严格要 UUID——故本组件用 `materialDict`
 * （`materialRecipeService.list()` 全量字典，与候选同源 `material_recipe` 表）建 code→id 索引后
 * 再调 `materialRecipeService.detail(id)`。PROCESS `effectiveValues[].key` = `process_master.process_no`，
 * 原样即为提交值（task-0712 缺口1 已在后端根治）。
 */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Input, InputNumber, Empty, Alert, Button, Select, Form, Table, Tooltip, Tag, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import {
  materialRecipeService,
  type MaterialRecipeLite,
  type MaterialRecipeConfig,
  type MaterialRecipeDetail,
} from '../../../services/materialRecipeService';
import type { EffectiveTemplateDTO, SelDetailRow } from '../../../types/configure';
import { genUUID } from '../../../utils/uuid';
import { formatPctText, trimTrailingZeros, type DecimalString } from '../../../utils/precision';
import {
  isPctLegal, isSumOk, pctIllegalText, sumDisplayPct, sumNotOneText, sumPct,
} from '../../config/recipeContentRules';

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

type SubStep = 1 | 2;
type ContentMode = 'config' | 'custom';

const swatchColors = [
  '#b9c4d1', '#a9b6c8', '#e0c68a', '#e6cf94', '#d5dbe3',
  '#c7ced9', '#9aa5b1', '#f0b7b7', '#b7e0c9', '#c9b7e0',
];
function swatchColor(code: string): string {
  let h = 0;
  for (let i = 0; i < code.length; i++) h = (h * 31 + code.charCodeAt(i)) >>> 0;
  return swatchColors[h % swatchColors.length];
}

/** `00006-01（Ag 90% / Ni 10%）` —— 含量一律去尾随 0（AC-30） */
function configOptionLabel(cfg: MaterialRecipeConfig, order: string[]): string {
  const byNo = new Map(cfg.elements.map((e) => [e.elementNo ?? e.elementCode, e]));
  const parts = (order.length > 0 ? order : cfg.elements.map((e) => e.elementNo ?? e.elementCode))
    .map((no) => byNo.get(no))
    .filter((e): e is NonNullable<typeof e> => !!e)
    .map((e) => `${e.elementCode} ${formatPctText(e.defaultPct)}%`);
  return `${cfg.configNo}（${parts.join(' / ')}）`;
}

const AddPartSubDrawer: React.FC<Props> = ({
  open, effective, materialDict, editingRow, onConfirm, onCancel, onMaterialPreview,
}) => {
  const [step, setStep] = useState<SubStep>(1);
  const [materialCode, setMaterialCode] = useState<string | null>(null);
  const [materialLabel, setMaterialLabel] = useState('');
  const [detail, setDetail] = useState<MaterialRecipeDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [contentMode, setContentMode] = useState<ContentMode>('config');
  const [configNo, setConfigNo] = useState<string | null>(null);
  const [elementValues, setElementValues] = useState<Record<string, DecimalString>>({});
  const [selectedProcesses, setSelectedProcesses] = useState<Array<{ id: string; label: string }>>([]);
  const [processFilter, setProcessFilter] = useState('');

  // 打开/切换编辑目标时重置或回填种子状态。
  useEffect(() => {
    if (!open) return;
    if (editingRow) {
      setMaterialCode(editingRow.recipeCode);
      setMaterialLabel(editingRow.recipeLabel);
      setContentMode(
        editingRow.contentMode
          ?? (Object.keys(editingRow.elementOverrides).length > 0 ? 'custom' : 'config'),
      );
      setConfigNo(editingRow.configNo ?? null);
      setElementValues({ ...editingRow.elementOverrides });
      setSelectedProcesses(
        editingRow.processNos.map((no, i) => ({ id: no, label: editingRow.processLabels[i] ?? no })),
      );
      onMaterialPreview(editingRow.recipeCode, editingRow.recipeLabel);
    } else {
      setMaterialCode(null);
      setMaterialLabel('');
      setContentMode('config');
      setConfigNo(null);
      setElementValues({});
      setSelectedProcesses([]);
    }
    setDetail(null);
    setStep(1);
    setProcessFilter('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, editingRow?.rowId]);

  // 材质选定后拉详情（code→id 反查 materialDict，见文件头注释）。
  // requestSeq 防连续切材质时旧请求晚到覆盖新选择。
  const detailReqSeq = useRef(0);
  useEffect(() => {
    if (!open || !materialCode) { setDetail(null); return; }
    const rec = materialDict.find((m) => m.code === materialCode);
    if (!rec) { setDetail(null); return; }
    const seq = ++detailReqSeq.current;
    setDetailLoading(true);
    materialRecipeService.detail(rec.id)
      .then((d) => {
        if (detailReqSeq.current !== seq) return; // 已被更新的选择取代，丢弃过期响应
        setDetail(d);
        // 未选配置时默认选第一条 ACTIVE 配置
        setConfigNo((prev) => prev ?? d.configs[0]?.configNo ?? null);
      })
      .catch(() => { if (detailReqSeq.current === seq) setDetail(null); })
      .finally(() => { if (detailReqSeq.current === seq) setDetailLoading(false); });
  }, [open, materialCode, materialDict]);

  const materialCandidates = useMemo(
    () => effective.params.find((p) => p.paramTypeCode === 'MATERIAL')?.effectiveValues ?? [],
    [effective],
  );
  const processCandidates = useMemo(
    () => effective.params.find((p) => p.paramTypeCode === 'PROCESS')?.effectiveValues ?? [],
    [effective],
  );
  /** code → 列表项，取 configCount / allowCustomContent（F-10 灰显判据） */
  const byCode = useMemo(() => new Map(materialDict.map((m) => [m.code, m])), [materialDict]);

  /**
   * 材质下拉选项。
   * 🚨 258 项走虚拟滚动 —— 没渲染的选项在 DOM 里不存在，用户滚不到就会以为没有，
   *    所以必须支持按**材质编号 / 材质名**搜索（AC-35 ③）。
   * 🚫 0 配置的材质**灰显不可选**但仍在列表里（AC-17 ④）。
   */
  const materialOptions = useMemo(() => materialCandidates.map((c) => {
    const lite = byCode.get(c.key);
    const noConfig = !!lite && (lite.configCount ?? 0) === 0;
    const right = noConfig
      ? '该材质尚未配置含量'
      : lite
        ? `${lite.configCount ?? 0} 组配置${lite.allowCustomContent ? ' · 可自定义' : ''}`
        : '';
    return {
      value: c.key,
      disabled: noConfig,
      /** 供 filterOption 用的可搜文本：材质编号 + 材质名 */
      searchText: `${c.key} ${c.label}`.toLowerCase(),
      rawLabel: c.label,
      label: (
        <span style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
            <span style={{ width: 12, height: 12, borderRadius: 3, flex: 'none', background: swatchColor(c.key) }} />
            <b style={{ fontFamily: 'Consolas, monospace' }}>{c.key}</b>
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.label}</span>
          </span>
          <span style={{ fontSize: 12, color: noConfig ? '#d48806' : 'rgba(0,0,0,.45)', flex: 'none' }}>
            {right}
          </span>
        </span>
      ),
    };
  }), [materialCandidates, byCode]);

  const filteredProcesses = useMemo(() => {
    const kw = processFilter.trim().toLowerCase();
    if (!kw) return processCandidates;
    return processCandidates.filter(
      (c) => c.label.toLowerCase().includes(kw) || c.key.toLowerCase().includes(kw),
    );
  }, [processCandidates, processFilter]);

  const selectMaterial = (code: string) => {
    const opt = materialOptions.find((o) => o.value === code);
    const label = opt?.rawLabel ?? code;
    const changed = code !== materialCode;
    setMaterialCode(code);
    setMaterialLabel(label);
    if (changed) {
      setConfigNo(null);
      setElementValues({});
      setContentMode('config');
    }
    onMaterialPreview(code, label);
  };

  // ── 含量 ──
  const allowCustom = !!detail?.allowCustomContent;
  const composition = useMemo(
    () => [...(detail?.composition ?? [])].sort((a, b) => a.sortOrder - b.sortOrder),
    [detail],
  );
  const compositionOrder = useMemo(() => composition.map((c) => c.elementNo), [composition]);
  const activeConfigs = useMemo(() => detail?.configs ?? [], [detail]);
  const selectedConfig = activeConfigs.find((c) => c.configNo === configNo) ?? null;

  const configOptions = useMemo(
    () => activeConfigs.map((c) => ({
      value: c.configNo,
      label: configOptionLabel(c, compositionOrder),
    })),
    [activeConfigs, compositionOrder],
  );

  /** 切到自定义时，用当前选中配置（或第一条）的含量做种子 —— 元素只能改含量不能增删 */
  const switchToCustom = () => {
    if (!allowCustom) return;
    setContentMode('custom');
    setElementValues((prev) => {
      if (Object.keys(prev).length > 0) return prev;
      const seed: Record<string, DecimalString> = {};
      const src = selectedConfig ?? activeConfigs[0];
      composition.forEach((c) => {
        const hit = src?.elements.find((e) => (e.elementNo ?? e.elementCode) === c.elementNo);
        seed[c.elementCode] = trimTrailingZeros(hit?.defaultPct ?? '');
      });
      return seed;
    });
  };

  const setElem = (code: string, v: string) => setElementValues((prev) => ({ ...prev, [code]: v }));

  const customRows = useMemo(
    () => composition.map((c) => ({
      elementNo: c.elementNo,
      elementCode: c.elementCode,
      elementName: c.elementName,
      pct: elementValues[c.elementCode] ?? '',
    })),
    [composition, elementValues],
  );
  const customSum = useMemo(() => sumPct(customRows), [customRows]);
  const customSumOk = isSumOk(customSum);
  const customIllegal = customRows.filter((r) => !isPctLegal(r.pct));

  /** 步骤 ① 的放行判据；返回禁用原因（null = 放行） */
  const step1Reason = useMemo<string | null>(() => {
    if (!materialCode) return '请先选择材质';
    if (detailLoading) return '材质含量加载中…';
    if (contentMode === 'config') {
      if (activeConfigs.length === 0) return '该材质尚未配置含量';
      if (!configNo) return '请选择含量配置';
      return null;
    }
    if (customIllegal.length > 0) return pctIllegalText(customIllegal[0].elementCode);
    if (!customSumOk) return sumNotOneText(customSum);
    return null;
  }, [materialCode, detailLoading, contentMode, activeConfigs.length, configNo,
      customIllegal, customSumOk, customSum]);

  const goNext = () => {
    if (step === 1) {
      if (step1Reason) { message.warning(step1Reason); return; }
      setStep(2);
      return;
    }
    confirmAdd();
  };
  const goPrev = () => { if (step > 1) setStep((step - 1) as SubStep); };
  const goStepIfAllowed = (target: SubStep) => {
    if (target >= 2 && step1Reason) return;
    setStep(target);
  };

  const confirmAdd = () => {
    if (step1Reason) { message.warning(step1Reason); return; }
    const overrides: Record<string, DecimalString> = {};
    if (contentMode === 'custom') {
      customRows.forEach((r) => { overrides[r.elementCode] = r.pct; });
    }
    const row: SelDetailRow = {
      rowId: editingRow?.rowId ?? genUUID(),
      recipeCode: materialCode,
      recipeLabel: materialLabel,
      contentMode,
      configNo: contentMode === 'config' ? configNo : null,
      configLabel: contentMode === 'config' && selectedConfig
        ? configOptionLabel(selectedConfig, compositionOrder)
        : undefined,
      elementOverrides: overrides,
      processNos: selectedProcesses.map((p) => p.id),
      processLabels: selectedProcesses.map((p) => p.label),
      quantity: editingRow?.quantity ?? '1',
      unitWeightGrams: editingRow?.unitWeightGrams ?? null,
    };
    onConfirm(row);
  };

  const isProcessSelected = (id: string) => selectedProcesses.some((p) => p.id === id);
  const toggleProcess = (id: string, label: string) => {
    if (isProcessSelected(id)) setSelectedProcesses((prev) => prev.filter((p) => p.id !== id));
    else setSelectedProcesses((prev) => [...prev, { id, label }]);
  };
  const removeProcess = (id: string) => setSelectedProcesses((prev) => prev.filter((p) => p.id !== id));

  if (!open) return null;

  const stepLabels: Record<SubStep, string> = { 1: '① 材质与含量', 2: '② 工序' };
  const nextDisabledReason = step1Reason;

  const nextBtn = (
    <Button type="primary" disabled={!!nextDisabledReason} onClick={goNext}>
      {step === 2 ? '确认添加' : '下一步'}
    </Button>
  );

  const customColumns = [
    {
      title: '元素',
      key: 'element',
      width: 240,
      // 🔒 元素列只读：自定义含量只能改含量，不能增删元素
      render: (_: unknown, r: typeof customRows[number]) => (
        <span style={{ fontFamily: 'Consolas, monospace' }}>
          {r.elementNo} / {r.elementCode} / {r.elementName}
        </span>
      ),
    },
    {
      title: '含量（%）',
      key: 'pct',
      render: (_: unknown, r: typeof customRows[number]) => {
        const bad = !isPctLegal(r.pct);
        return (
          <div>
            <InputNumber<string>
              stringMode
              // 🚫 不设 min/max：越界值要能被输入并当场标红（原型 5 状态 C）
              status={bad ? 'error' : undefined}
              value={r.pct === '' ? null : r.pct}
              style={{ width: 200 }}
              onChange={(v) => setElem(r.elementCode, v ?? '')}
            />
            {bad && (
              <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>{pctIllegalText()}</div>
            )}
          </div>
        );
      },
    },
  ];

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
        {([1, 2] as SubStep[]).map((s) => (
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
            {materialCandidates.length === 0 ? (
              <Empty description="该模板未启用材质参数，请联系管理员配置" />
            ) : (
              <Form
                component={false}
                layout="horizontal"
                labelCol={{ flex: '110px' }}
                labelAlign="right"
                colon={false}
              >
                <Form.Item label="材质" required>
                  <Select<string>
                    showSearch
                    style={{ width: '100%', maxWidth: 520 }}
                    value={materialCode ?? undefined}
                    placeholder="请选择材质（可按材质编号 / 材质名搜索）"
                    optionFilterProp="searchText"
                    options={materialOptions}
                    filterOption={(input, option: any) =>
                      String(option?.searchText ?? '').includes(input.trim().toLowerCase())}
                    notFoundContent="无匹配的材质"
                    onChange={selectMaterial}
                  />
                  <div style={{ fontSize: 12, color: '#909399', marginTop: 4 }}>
                    未配置含量的材质<b>灰显不可选</b>并写明原因 —— 不从列表里消失，否则会以为材质丢了。
                  </div>
                </Form.Item>

                <Form.Item label="含量配置" required>
                  {!materialCode ? (
                    <Input readOnly value="请先选择材质" style={{ maxWidth: 520, color: 'rgba(0,0,0,.45)' }} />
                  ) : detailLoading ? (
                    <Input readOnly value="加载中…" style={{ maxWidth: 520, color: 'rgba(0,0,0,.45)' }} />
                  ) : contentMode === 'custom' ? (
                    <span style={{ color: 'rgba(0,0,0,.45)', fontSize: 13 }}>
                      已切换到自定义含量，不使用标准配置
                    </span>
                  ) : activeConfigs.length === 0 ? (
                    <Alert type="warning" showIcon message="该材质尚未配置含量" style={{ maxWidth: 520 }} />
                  ) : (
                    <>
                      <Select<string>
                        showSearch
                        style={{ width: '100%', maxWidth: 520 }}
                        value={configNo ?? undefined}
                        placeholder="请选择含量配置"
                        options={configOptions}
                        // AC-35 ④：可按配置编号搜索（输入 -02 能筛出 00006-02）
                        filterOption={(input, option) =>
                          String(option?.value ?? '').toLowerCase().includes(input.trim().toLowerCase())}
                        notFoundContent="无匹配的含量配置"
                        onChange={(v) => setConfigNo(v)}
                      />
                      <div style={{ fontSize: 12, color: '#909399', marginTop: 4 }}>
                        只列该材质<b>启用中</b>的配置，可按配置编号搜索。已停用的配置不出现在这里。
                      </div>
                    </>
                  )}
                </Form.Item>
              </Form>
            )}

            {/* 自定义含量入口 —— 🚫 禁用但可见，不隐藏。
                ⚠️ 这一行**刻意不用 Form.Item**：Form.Item 会渲染一个 <label>自定义含量</label>，
                   与下方按钮同名，定位「那个可禁用的控件」时会先命中 label。 */}
            {materialCode && !detailLoading && detail && (
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginTop: 4, marginBottom: 16 }}>
                <span style={{ width: 110, flex: 'none', textAlign: 'right', paddingTop: 5, fontSize: 14 }}>
                  {allowCustom ? '含量来源' : '自定义含量'}
                </span>
                <div>
                  {allowCustom ? (
                    <div style={{ display: 'flex', gap: 8 }}>
                      <Button
                        type={contentMode === 'config' ? 'primary' : 'default'}
                        onClick={() => setContentMode('config')}
                      >
                        选标准配置
                      </Button>
                      <Button
                        type={contentMode === 'custom' ? 'primary' : 'default'}
                        onClick={switchToCustom}
                      >
                        自定义含量
                      </Button>
                    </div>
                  ) : (
                    <Tooltip title="该材质不支持自定义含量">
                      <span>
                        <Button disabled>切换到自定义含量</Button>
                      </span>
                    </Tooltip>
                  )}
                  <div style={{ fontSize: 12, color: '#909399', marginTop: 4 }}>
                    是否允许自定义由材质属性决定，可在「主数据维护 → 材质」中开启。
                  </div>
                </div>
              </div>
            )}

            {/* 自定义含量明细（F-9）：元素只能改含量、不能增删 */}
            {materialCode && !detailLoading && detail && contentMode === 'custom' && (
              <div style={{ marginTop: 4 }}>
                <div style={{ fontSize: 13, marginBottom: 6 }}>
                  <span style={{ color: '#ff4d4f', marginRight: 4 }}>*</span>元素含量
                </div>
                <Table
                  rowKey="elementNo"
                  size="small"
                  pagination={false}
                  dataSource={customRows}
                  columns={customColumns as any}
                />
                <div style={{ marginTop: 10 }}>
                  合计 <b style={{ fontFamily: 'Consolas, monospace' }}>{sumDisplayPct(customSum)}%</b>{' '}
                  {customSumOk ? <Tag color="green">符合</Tag> : <Tag color="red">应为 100%</Tag>}
                </div>
                {!customSumOk && (
                  <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>
                    {sumNotOneText(customSum)}
                  </div>
                )}
                <div style={{ fontSize: 12, color: '#909399', marginTop: 6 }}>
                  元素组成来自材质本身，<b>不能增删</b>，只能改含量。
                </div>
                <Alert
                  style={{ marginTop: 12 }}
                  type="info"
                  showIcon
                  message="自定义含量不会写回材质库 —— 它只作用于本次配置出来的料号，材质库仍只保留标准配置。"
                />
              </div>
            )}
          </>
        )}

        {step === 2 && (
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
        {/* 禁用但可见 + tooltip 写实际原因（F-9） */}
        {nextDisabledReason
          ? <Tooltip title={nextDisabledReason}><span>{nextBtn}</span></Tooltip>
          : nextBtn}
      </div>
    </div>
  );
};

export default AddPartSubDrawer;
