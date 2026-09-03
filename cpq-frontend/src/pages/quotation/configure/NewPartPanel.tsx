/**
 * NewPartPanel — 新建零件（品名/规格/尺寸/总重 + 1~N 个材质 + 工序）。
 * task-260902 · F-4 / F-5 / F-6 / F-11，服务 AC-3 / AC-4 / AC-6 / AC-13 / AC-14 /
 * AC-15a / AC-15b / AC-17 / AC-18 / AC-23。
 *
 * 1:1 对齐 `原型图/3-新建零件与多材质.html` 状态 A / A2 / B / C / D / E / F / F-b / G / H。
 *
 * 🔥 **本次重构的核心页**：现状一个配件只能有一种材质（`PartRequest.recipeCode` 是单值），
 *    这里把它变成 1~N 个，每个材质带占比。
 *
 * 三个硬判据，改本文件前先确认没破坏：
 *  1. **占比合计走定点整数**（`ratioRules.ts`），🚫 不许 `Number` 累加。
 *     AC-15a 那组三等分在浮点下**恰好等于 100**，拦不住错误实现；AC-15b 那组极小值才有分辨力。
 *  2. **折合克重 = 总重 × 占比 ÷ 100，实时计算、只读展示**（用户裁决 D-2：不单独录入克重）。
 *  3. **零材质时「确定」禁用但可见 + tooltip 写原因**（AC-14），🚫 不许把按钮藏起来。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Input, Table, Tag, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  materialRecipeService,
  type MaterialRecipeConfig,
  type MaterialRecipeDetail,
  type MaterialRecipeLite,
} from '../../../services/materialRecipeService';
import type { SelParamCandidate } from '../../../services/selParamCandidateService';
import type { ConfigurePart, ConfigurePartMaterial, SelectedProcess } from '../../../types/configure';
import { genUUID } from '../../../utils/uuid';
import { formatPctText, trimTrailingZeros, type DecimalString } from '../../../utils/precision';
import { isPctLegal, isSumOk, pctIllegalText, sumDisplayPct, sumNotOneText, sumPct } from '../../config/recipeContentRules';
import MaterialPicker from './MaterialPicker';
import ProcessSection from './ProcessSection';
import { computeGramsByRatio, isWeightValid, ratioErrorText, ratioSumMessage, sumRatios } from './ratioRules';
import { PART_TEXT_MAX_LENGTH, validatePartText } from './partTextRules';
import { EmptyBlock, Mono, NoteBlock, ReasonedButton, hintStyle, sectionTitleStyle } from './configureUi';

interface Props {
  /** null = 新增；非空 = 编辑该配件 */
  initial: ConfigurePart | null;
  materials: MaterialRecipeLite[];
  materialsLoading?: boolean;
  materialsError?: string | null;
  processCandidates: SelParamCandidate[];
  processLoading?: boolean;
  processError?: string | null;
  onConfirm: (part: ConfigurePart) => void;
  onBack: () => void;
  onCancel: () => void;
}

/** `00006-01（Ag 90% / Ni 10%）` —— 含量去尾随零（F-12 口径，字符串正则，🚫 不过 `Number`）。 */
function configOptionLabel(cfg: MaterialRecipeConfig, order: string[]): string {
  const byNo = new Map(cfg.elements.map((e) => [e.elementNo ?? e.elementCode, e]));
  const keys = order.length > 0 ? order : cfg.elements.map((e) => e.elementNo ?? e.elementCode);
  const parts = keys
    .map((k) => byNo.get(k))
    .filter((e): e is NonNullable<typeof e> => !!e)
    .map((e) => `${e.elementCode} ${formatPctText(e.defaultPct)}%`);
  return parts.length > 0 ? `${cfg.configNo}（${parts.join(' / ')}）` : cfg.configNo;
}

function emptyPart(): ConfigurePart {
  return {
    uid: genUUID(),
    partType: 'PART',
    partMode: 'new',
    name: '',
    spec: '',
    dimension: '',
    unitWeightGrams: '',
    materials: [],
    processes: [],
  };
}

const NewPartPanel: React.FC<Props> = ({
  initial, materials, materialsLoading, materialsError,
  processCandidates, processLoading, processError,
  onConfirm, onBack, onCancel,
}) => {
  const [draft, setDraft] = useState<ConfigurePart>(() => (initial ? { ...initial } : emptyPart()));
  const [pickerOpen, setPickerOpen] = useState(false);
  /** recipeCode → 详情（元素组成 + ACTIVE 配置）。只为**已添加**的材质拉，数量很少。 */
  const [details, setDetails] = useState<Record<string, MaterialRecipeDetail>>({});
  const [expandedUids, setExpandedUids] = useState<string[]>([]);

  useEffect(() => { setDraft(initial ? { ...initial } : emptyPart()); }, [initial]);

  /**
   * 详情落地的**唯一入口**：写进缓存的同时，顺手给还没选配置的材质补上第一条 ACTIVE 配置。
   * 🚫 不要改回「effect 里监听 details 再 setDraft」的写法 —— 那是 setState-in-effect 的
   *    级联渲染，而且默认值只在详情刚到那一刻需要算一次，本来就不该是个持续同步关系。
   */
  const applyDetails = (patchMap: Record<string, MaterialRecipeDetail>) => {
    if (Object.keys(patchMap).length === 0) return;
    setDetails((prev) => ({ ...prev, ...patchMap }));
    setDraft((prev) => ({
      ...prev,
      materials: prev.materials.map((m) => {
        if (m.contentMode !== 'config' || m.configNo) return m;
        const d = patchMap[m.recipeCode];
        const first = d?.configs?.[0];
        if (!first) return m;
        return {
          ...m,
          configNo: first.configNo,
          configLabel: configOptionLabel(first, (d.composition ?? []).map((c) => c.elementNo)),
        };
      }),
    }));
  };

  // 为已添加但还没有详情的材质补拉（编辑回填场景一次拉齐）。
  useEffect(() => {
    const todo = draft.materials
      .filter((m) => !details[m.recipeCode])
      .map((m) => materials.find((x) => x.code === m.recipeCode))
      .filter((x): x is MaterialRecipeLite => !!x);
    if (todo.length === 0) return;
    let cancelled = false;
    Promise.all(todo.map((lite) => materialRecipeService.detail(lite.id).then(
      (d) => [lite.code, d] as const,
      () => null,
    ))).then((rows) => {
      if (cancelled) return;
      const patchMap: Record<string, MaterialRecipeDetail> = {};
      rows.forEach((r) => { if (r) patchMap[r[0]] = r[1]; });
      applyDetails(patchMap);
    });
    return () => { cancelled = true; };
  }, [draft.materials, materials, details]);

  const patch = (p: Partial<ConfigurePart>) => setDraft((prev) => ({ ...prev, ...p }));
  const patchMaterial = (uid: string, p: Partial<ConfigurePartMaterial>) =>
    setDraft((prev) => ({
      ...prev,
      materials: prev.materials.map((m) => (m.uid === uid ? { ...m, ...p } : m)),
    }));

  const addedCodes = useMemo(() => new Set(draft.materials.map((m) => m.recipeCode)), [draft.materials]);

  const addMaterial = (lite: MaterialRecipeLite) => {
    setDraft((prev) => ({
      ...prev,
      materials: [...prev.materials, {
        uid: genUUID(),
        recipeCode: lite.code,
        recipeName: lite.symbol || lite.name || lite.code,
        allowCustomContent: !!lite.allowCustomContent,
        contentMode: 'config',
        configNo: null,
        elements: [],
        // 第一个材质默认 100（单材质是最常见形态，AC-13）；之后新增的留空由用户填。
        ratio: prev.materials.length === 0 ? '100' : '',
      }],
    }));
    setPickerOpen(false);
    // 详情拉回来后由 applyDetails 自动选中第一条 ACTIVE 配置（对齐原型 A：加进来就带着配置）
    materialRecipeService.detail(lite.id)
      .then((d) => applyDetails({ [lite.code]: d }))
      .catch(() => undefined);
  };

  const removeMaterial = (uid: string) =>
    setDraft((prev) => ({ ...prev, materials: prev.materials.filter((m) => m.uid !== uid) }));

  /** 切到自定义含量：用当前配置（或第一条）的含量做种子；元素只能改含量、不能增删。 */
  const switchToCustom = (m: ConfigurePartMaterial) => {
    const detail = details[m.recipeCode];
    if (!detail) return;
    const src = detail.configs.find((c) => c.configNo === m.configNo) ?? detail.configs[0];
    const composition = [...(detail.composition ?? [])].sort((a, b) => a.sortOrder - b.sortOrder);
    const elements = composition.map((c) => {
      const hit = src?.elements.find((e) => (e.elementNo ?? e.elementCode) === c.elementNo);
      return {
        elementNo: c.elementNo,
        elementCode: c.elementCode,
        elementName: c.elementName,
        pct: trimTrailingZeros(hit?.defaultPct ?? '') as DecimalString,
      };
    });
    patchMaterial(m.uid, { contentMode: 'custom', configNo: null, elements });
    setExpandedUids((prev) => (prev.includes(m.uid) ? prev : [...prev, m.uid]));
  };

  const switchToConfig = (m: ConfigurePartMaterial) => {
    const detail = details[m.recipeCode];
    const first = detail?.configs?.[0];
    patchMaterial(m.uid, {
      contentMode: 'config',
      elements: [],
      configNo: m.configNo ?? first?.configNo ?? null,
      configLabel: first ? configOptionLabel(first, (detail?.composition ?? []).map((c) => c.elementNo)) : undefined,
    });
    setExpandedUids((prev) => prev.filter((u) => u !== m.uid));
  };

  // ── 占比合计（🚨 定点整数，🚫 不许 Number 累加）──
  const ratioResult = useMemo(
    () => sumRatios(draft.materials.map((m) => m.ratio)),
    [draft.materials],
  );

  /** 「确定」的禁用原因（null = 放行）。逐条对应 AC，改动请同步 AC 编号。 */
  const confirmReason = useMemo<string | null>(() => {
    if (!draft.name.trim()) return '请填写品名';
    for (const [label, value] of [['品名', draft.name], ['规格', draft.spec], ['尺寸', draft.dimension]] as const) {
      const issue = validatePartText(label, value);
      if (issue) return issue.message;                                   // AC-23 / api.md §4.3
    }
    if (!isWeightValid(draft.unitWeightGrams)) return '请填写零件总重（克），必须大于 0';  // AC-3
    if (draft.materials.length === 0) return '请至少添加一个材质';         // AC-14
    for (let i = 0; i < draft.materials.length; i++) {
      const m = draft.materials[i];
      const err = ratioResult.rowErrors[i];
      if (err) return `${m.recipeName}：${ratioErrorText(err)}`;
      if (m.contentMode === 'config' && !m.configNo) return `请为材质 ${m.recipeName} 选择含量配置`;
      if (m.contentMode === 'custom') {
        const bad = m.elements.find((e) => !isPctLegal(e.pct));
        if (bad) return `${m.recipeName}：${pctIllegalText(bad.elementCode)}`;
        if (!isSumOk(sumPct(m.elements))) return `${m.recipeName}：${sumNotOneText(sumPct(m.elements))}`;
      }
    }
    // AC-4：提示必须写出**实际合计值**，不许「合计不正确」这种形容词
    if (!ratioResult.ok) return ratioSumMessage(ratioResult, false);
    return null;
  }, [draft, ratioResult]);

  const confirm = () => {
    if (confirmReason) return;
    onConfirm({
      ...draft,
      partType: 'PART',
      partMode: 'new',
      name: draft.name.trim(),
      spec: draft.spec.trim(),
      dimension: draft.dimension.trim(),
    });
  };

  // ── 材质表 ──
  const materialColumns: ColumnsType<ConfigurePartMaterial> = [
    {
      title: '材质',
      key: 'material',
      width: 190,
      render: (_v, m) => (
        <span><Mono muted>{m.recipeCode}</Mono> {m.recipeName}</span>
      ),
    },
    {
      title: '含量配置',
      key: 'config',
      render: (_v, m) => {
        const detail = details[m.recipeCode];
        const order = (detail?.composition ?? []).map((c) => c.elementNo);
        if (m.contentMode === 'custom') {
          return (
            <div>
              <span style={{ color: 'rgba(0,0,0,.45)', fontSize: 13 }}>已切换到自定义含量</span>
              <div style={{ marginTop: 4 }}>
                <Button size="small" type="link" onClick={() => switchToConfig(m)}>← 改回选择标准配置</Button>
              </div>
            </div>
          );
        }
        if (!detail) {
          // 详情还没回来：这是**真的在加载**，不是空数据 —— 与 AP-31 的「把空渲染成加载中」是两回事
          return <span style={{ color: '#c0c4cc', fontSize: 12 }}>正在读取该材质的含量配置…</span>;
        }
        if (detail.configs.length === 0) {
          return <Alert type="warning" showIcon message="该材质尚未配置含量" style={{ padding: '2px 8px' }} />;
        }
        return (
          <div>
            <select
              value={m.configNo ?? ''}
              style={{ width: '100%', maxWidth: 320, height: 28, borderRadius: 6, border: '1px solid #d9d9d9', padding: '0 6px' }}
              onChange={(e) => {
                const cfg = detail.configs.find((c) => c.configNo === e.target.value);
                patchMaterial(m.uid, {
                  configNo: e.target.value || null,
                  configLabel: cfg ? configOptionLabel(cfg, order) : undefined,
                });
              }}
            >
              <option value="">请选择含量配置</option>
              {detail.configs.map((c) => (
                <option key={c.configNo} value={c.configNo}>{configOptionLabel(c, order)}</option>
              ))}
            </select>
            <div style={{ marginTop: 4 }}>
              {/* AC-6：不支持自定义时**禁用但可见** + tooltip 写明原因，🚫 不隐藏 */}
              <ReasonedButton
                size="small"
                type="link"
                reason={m.allowCustomContent ? null : '该材质不支持自定义含量'}
                onClick={() => switchToCustom(m)}
              >
                切换到自定义含量
              </ReasonedButton>
            </div>
          </div>
        );
      },
    },
    {
      title: '占比 %',
      key: 'ratio',
      width: 170,
      align: 'right',
      render: (_v, m, index) => {
        const err = ratioResult.rowErrors[index];
        return (
          <div>
            <Input
              // 🚨 纯文本输入：12 位小数不能过 InputNumber 的数值通道，提交时原样发送
              value={m.ratio}
              status={err ? 'error' : undefined}
              style={{ textAlign: 'right', fontFamily: 'Consolas, Menlo, monospace' }}
              placeholder="如 70"
              onChange={(e) => patchMaterial(m.uid, { ratio: e.target.value })}
            />
            {err ? <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>{ratioErrorText(err)}</div> : null}
          </div>
        );
      },
    },
    {
      title: '折合克重',
      key: 'grams',
      width: 130,
      align: 'right',
      render: (_v, m) => {
        // 🚨 合计 ≠ 100 时**逐行也显示「—」**，对齐原型 3 状态 B（那一屏的折合克重列整列是「—」）。
        //    理由：占比合计不对时，按各自占比算出来的克重是**误导** —— 它们加起来不等于总重，
        //    用户会照着这个数去核对，然后困惑为什么对不上。宁可不显示。
        const g = ratioResult.ok ? computeGramsByRatio(draft.unitWeightGrams, m.ratio) : null;
        return g === null
          ? <span style={{ color: '#c0c4cc' }}>—</span>
          : <span style={{ color: '#909399' }}>{g} g</span>;
      },
    },
    {
      title: '',
      key: 'ops',
      width: 70,
      align: 'right',
      render: (_v, m) => (
        <Button type="link" size="small" danger onClick={() => removeMaterial(m.uid)}>移除</Button>
      ),
    },
  ];

  const totalGrams = useMemo(() => {
    if (!isWeightValid(draft.unitWeightGrams) || !ratioResult.ok) return null;
    return trimTrailingZeros(draft.unitWeightGrams);
  }, [draft.unitWeightGrams, ratioResult.ok]);

  const nameIssue = validatePartText('品名', draft.name);
  const specIssue = validatePartText('规格', draft.spec);
  const dimIssue = validatePartText('尺寸', draft.dimension);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ flex: 1, overflow: 'auto', padding: '16px 20px' }}>
        <h3 style={{ fontSize: 14, fontWeight: 600, margin: '0 0 12px' }}>零件信息</h3>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <div style={{ flex: '1 1 200px', minWidth: 180 }}>
            <label style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>
              <span style={{ color: '#ff4d4f', marginRight: 4 }}>*</span>品名
            </label>
            <Input
              value={draft.name}
              maxLength={PART_TEXT_MAX_LENGTH}
              status={nameIssue ? 'error' : undefined}
              placeholder="如 动触头"
              onChange={(e) => patch({ name: e.target.value })}
            />
            {nameIssue ? <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>{nameIssue.message}</div> : null}
          </div>
          <div style={{ flex: '1 1 160px', minWidth: 150 }}>
            <label style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>规格</label>
            <Input
              value={draft.spec}
              maxLength={PART_TEXT_MAX_LENGTH}
              status={specIssue ? 'error' : undefined}
              placeholder="如 φ12×3"
              onChange={(e) => patch({ spec: e.target.value })}
            />
            {specIssue ? <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>{specIssue.message}</div> : null}
          </div>
          <div style={{ flex: '1 1 160px', minWidth: 150 }}>
            <label style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>尺寸</label>
            <Input
              value={draft.dimension}
              maxLength={PART_TEXT_MAX_LENGTH}
              status={dimIssue ? 'error' : undefined}
              placeholder="如 12×8×3"
              onChange={(e) => patch({ dimension: e.target.value })}
            />
            {dimIssue ? <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>{dimIssue.message}</div> : null}
          </div>
          <div style={{ flex: '0 0 170px' }}>
            <label style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>
              <span style={{ color: '#ff4d4f', marginRight: 4 }}>*</span>总重
            </label>
            <Input
              value={draft.unitWeightGrams}
              status={draft.unitWeightGrams !== '' && !isWeightValid(draft.unitWeightGrams) ? 'error' : undefined}
              suffix="克"
              style={{ textAlign: 'right' }}
              placeholder="如 10"
              onChange={(e) => patch({ unitWeightGrams: e.target.value })}
            />
          </div>
        </div>

        <h3 style={sectionTitleStyle}>
          材质构成
          {draft.materials.length > 0 ? <Tag color="blue">{draft.materials.length} 项</Tag> : null}
        </h3>
        <p style={hintStyle}>各材质的克重 = 总重 × 占比，不单独录入（用户裁决 D-2）。</p>

        {draft.materials.length === 0 ? (
          /* AC-14：零材质时的空态 —— 空是空，不是「加载中…」 */
          <div style={{ border: '1px solid #f0f0f0', borderRadius: 8 }}>
            <EmptyBlock
              icon="🧪"
              title="还没有材质"
              hint="一个零件至少要有一个材质才能确定"
              actions={<Button type="primary" onClick={() => setPickerOpen(true)}>+ 添加第一个材质</Button>}
            />
          </div>
        ) : (
          <Table<ConfigurePartMaterial>
            rowKey="uid"
            size="small"
            pagination={false}
            dataSource={draft.materials}
            columns={materialColumns}
            // 合计 ≠ 100 时**整表行级红底**（原型状态 B 的 `.rowerr`）
            rowClassName={() => (ratioResult.ok ? '' : 'cfg-ratio-row-error')}
            expandable={{
              expandedRowKeys: expandedUids,
              showExpandColumn: false,
              rowExpandable: (m) => m.contentMode === 'custom',
              expandedRowRender: (m) => (
                <CustomContentEditor
                  material={m}
                  onChange={(elements) => patchMaterial(m.uid, { elements })}
                  onBackToConfig={() => switchToConfig(m)}
                />
              ),
            }}
            summary={() => (
              <Table.Summary.Row>
                <Table.Summary.Cell index={0} colSpan={2}>
                  <span style={{ color: '#909399' }}>合计</span>
                </Table.Summary.Cell>
                <Table.Summary.Cell index={2} align="right">
                  {/* AC-4：写出**实际合计值** */}
                  {ratioResult.ok
                    ? <Tag color="green">{ratioResult.sumText}%</Tag>
                    : <Tag color="red">{ratioResult.sumText}%</Tag>}
                </Table.Summary.Cell>
                <Table.Summary.Cell index={3} align="right">
                  {totalGrams === null ? <span style={{ color: '#c0c4cc' }}>—</span> : <span>{totalGrams} g</span>}
                </Table.Summary.Cell>
                <Table.Summary.Cell index={4} />
              </Table.Summary.Row>
            )}
          />
        )}

        {draft.materials.length > 0 && !ratioResult.ok && (
          <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 8 }}>
            {ratioSumMessage(ratioResult)}
          </div>
        )}

        <div style={{ marginTop: 12 }}>
          <Button onClick={() => setPickerOpen((v) => !v)}>{pickerOpen ? '收起选择器' : '+ 添加材质'}</Button>
        </div>

        {pickerOpen && (
          <MaterialPicker
            materials={materials}
            loading={materialsLoading}
            loadError={materialsError}
            addedCodes={addedCodes}
            onSelect={addMaterial}
            onClose={() => setPickerOpen(false)}
          />
        )}

        <ProcessSection
          value={draft.processes}
          onChange={(processes: SelectedProcess[]) => patch({ processes })}
          candidates={processCandidates}
          loading={processLoading}
          loadError={processError}
          labels={{
            title: '工序',
            hint: <>添加方式<b>与材质完全一致</b>：选择器选中 → 加入列表。按列表从上到下的顺序执行，可用 ↑↓ 调序。</>,
            addButton: '+ 添加工序',
            pickerTitle: '选择工序',
            searchPlaceholder: '输入工序编号或工序名过滤，如 Z100 / 焊接',
            emptyTitle: '还没有工序',
            emptyHint: '工序不是必填 —— 没有工序的零件可以直接确定',
            unit: '道',
          }}
        />

        <NoteBlock>
          品名 / 规格 / 尺寸 / 总重 分别落 <code>material_master</code> 的
          <code>material_name / specification / dimension / unit_weight</code>；材质占比落
          <code>material_bom_item.material_ratio</code>。本页<b>零新增字段</b>。
        </NoteBlock>
      </div>

      <div style={{ padding: '12px 20px', borderTop: '1px solid #f0f0f0', display: 'flex', gap: 8, justifyContent: 'flex-end', flexShrink: 0 }}>
        <Button onClick={onCancel}>取消</Button>
        <Button onClick={onBack}>上一步</Button>
        {/* AC-14 / AC-4：禁用但可见 + tooltip 写明真实原因 */}
        <ReasonedButton type="primary" reason={confirmReason} onClick={confirm}>确定</ReasonedButton>
      </div>
    </div>
  );
};

/** 自定义含量编辑器（原型 3 状态 C）：元素**只能改含量、不能增删**。 */
const CustomContentEditor: React.FC<{
  material: ConfigurePartMaterial;
  onChange: (elements: ConfigurePartMaterial['elements']) => void;
  onBackToConfig: () => void;
}> = ({ material, onChange, onBackToConfig }) => {
  const sum = sumPct(material.elements);
  const ok = isSumOk(sum);
  const setPct = (elementNo: string, pct: string) =>
    onChange(material.elements.map((e) => (e.elementNo === elementNo ? { ...e, pct } : e)));

  return (
    <div style={{ padding: '4px 0 8px' }}>
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 12 }}
        message={<>正在为 <b>{material.recipeName}</b> 使用<b>自定义含量</b>，该配比只用于本次选配，<b>不会回流材质库</b>。</>}
        description="元素种类由材质的元素组成决定，只能改含量、不能增删元素。"
      />
      <Table
        rowKey="elementNo"
        size="small"
        pagination={false}
        style={{ maxWidth: 520 }}
        dataSource={material.elements}
        columns={[
          {
            title: '元素',
            key: 'element',
            // 🔒 元素列只读：自定义含量只能改含量，不能增删元素
            render: (_v: unknown, e: ConfigurePartMaterial['elements'][number]) => (
              <span><Mono muted>{e.elementNo}</Mono> {e.elementCode} {e.elementName}</span>
            ),
          },
          {
            title: '含量 %',
            key: 'pct',
            width: 200,
            align: 'right' as const,
            render: (_v: unknown, e: ConfigurePartMaterial['elements'][number]) => {
              const bad = !isPctLegal(e.pct);
              return (
                <div>
                  <Input
                    value={e.pct}
                    status={bad ? 'error' : undefined}
                    style={{ textAlign: 'right', fontFamily: 'Consolas, Menlo, monospace' }}
                    onChange={(ev) => setPct(e.elementNo, ev.target.value)}
                  />
                  {bad ? <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>{pctIllegalText()}</div> : null}
                </div>
              );
            },
          },
        ]}
        summary={() => (
          <Table.Summary.Row>
            <Table.Summary.Cell index={0}><span style={{ color: '#909399' }}>合计</span></Table.Summary.Cell>
            <Table.Summary.Cell index={1} align="right">
              <Tag color={ok ? 'green' : 'red'}>{sumDisplayPct(sum)}%</Tag>
            </Table.Summary.Cell>
          </Table.Summary.Row>
        )}
      />
      {!ok ? <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 6 }}>{sumNotOneText(sum)}</div> : null}
      <div style={{ marginTop: 10 }}>
        <Tooltip title="改回后自定义的含量会被丢弃">
          <Button size="small" onClick={onBackToConfig}>← 改回选择标准配置</Button>
        </Tooltip>
      </div>
    </div>
  );
};

export default NewPartPanel;
