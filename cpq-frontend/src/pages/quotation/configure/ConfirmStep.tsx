/**
 * ConfirmStep — 步骤 4「确认并添加」+ 提交结果（task-260902 · F-9，服务 AC-7~AC-10）。
 * 1:1 对齐 `原型图/6-组合工序与指纹结果.html` 状态 B（未命中）/ C（命中复用）/ D（提交失败）。
 *
 * 对应用户原话：「添加到报价单的时候先根据指纹比对本次添加的产品是否在产品库已经存在，
 * 存在则带出销售产品信息，不存在则新建一个销售料号」。
 *
 * 🚧 **契约缺口（已报主线，本组件的两段式渲染就是为它兜的）**：
 *    原型状态 C 把「带出的销售产品信息」（销售料号 / 品名规格尺寸 / 单重 / 材质构成 / 历史报价）
 *    画在**点提交之前**的确认页上；但 `api.md` 里 **`reusedProductInfo` 只存在于提交响应**
 *    （§1.3），提交前能拿到的 `POST /lookup-fingerprint` 只返回 `matched` + `hfPartNo` + `snapshot`。
 *    ⇒ 处置：**提交前**按预览结果渲染绿/蓝提示条（含命中的销售料号）；**提交后**再把
 *      `reusedProductInfo` 的完整信息展开成结果页。两段都在这个组件里，靠 `result` 是否非空切换。
 *      🚫 没有编造字段，也没有把「拿不到」渲染成空白。
 */
import React from 'react';
import { Alert, Button, Table, Tag } from 'antd';
import type { ConfigureProductResponse, CompositeProcessItem, ConfigurePart } from '../../../types/configure';
import { trimTrailingZeros } from '../../../utils/precision';
import { materialTags, partTypeTags, processText } from './PartCardList';
import { partDisplayName } from './configurePartsRequest';
import { Mono, NoteBlock } from './configureUi';

export interface FingerprintPreview {
  checking: boolean;
  matched: boolean;
  matchedPartNo?: string;
}

export interface SubmitFailure {
  message: string;
  code?: string;
}

interface Props {
  customerProductNo: string;
  customerProductName: string;
  parts: ConfigurePart[];
  composites: CompositeProcessItem[];
  preview: FingerprintPreview;
  /** 提交成功后的响应；非空时本组件切到「结果页」。 */
  result: ConfigureProductResponse | null;
  failure: SubmitFailure | null;
  onOpenExistingProducts: (productNo: string) => void;
}

interface SummaryRow { key: string; label: string; content: React.ReactNode }

/**
 * 错误码 → 指路（原型状态 D 的「→ 打开材质管理」那颗按钮）。
 * 🚨 按**错误码**分派，不按 message 文案 —— 文案后端会改，错误码不会。
 */
function errorGuide(
  code: string | undefined,
  productNo: string,
  onOpenExistingProducts: (n: string) => void,
): { hint: string; action?: React.ReactNode } {
  switch (code) {
    case 'RECIPE_HAS_NO_CONFIG':
      return {
        hint: '该材质在材质库里没有任何启用的含量配置，无法确定元素构成。请到材质管理页为它添加配置，或改选其他材质。',
        action: <Button size="small" onClick={() => window.open('/config/material-recipes', '_blank')}>→ 打开材质管理</Button>,
      };
    case 'RECIPE_CUSTOM_NOT_ALLOWED':
      return {
        hint: '该材质没有打开「支持自定义含量」，不能提交自定义含量。请改选标准配置，或到材质管理页打开该开关。',
        action: <Button size="small" onClick={() => window.open('/config/material-recipes', '_blank')}>→ 打开材质管理</Button>,
      };
    case 'CUSTOMER_PRODUCT_NO_TAKEN':
      return {
        hint: '这个客户产品编号在你填完之后被别人占用了（并发提交）。同一个编号只能对应一个销售料号。',
        action: <Button size="small" type="primary" onClick={() => onOpenExistingProducts(productNo)}>→ 打开「从产品库添加」</Button>,
      };
    case 'OUTSOURCED_PART_REQUIRED':
      return {
        hint: '外购件配件必须选一个料号。请回到步骤 2 补选，或到料号维护里录入外购件。',
        action: <Button size="small" onClick={() => window.open('/materials', '_blank')}>→ 打开料号维护</Button>,
      };
    case 'MATERIAL_RATIO_SUM_INVALID':
      return { hint: '材质占比合计必须正好 100%。请回到步骤 2 修正占比。' };
    case 'MATERIAL_DUPLICATED':
      return { hint: '同一个零件里出现了重复的材质。请回到步骤 2 移除重复项。' };
    case 'MATERIAL_SOURCE_AMBIGUOUS':
      return { hint: '每个材质必须**恰好**给一种含量来源：标准配置或自定义含量，不能都给也不能都不给。' };
    case 'PART_HAS_NO_MATERIAL':
      return { hint: '新建的零件至少要有一个材质。请回到步骤 2 补上。' };
    case 'PART_WEIGHT_REQUIRED':
      return { hint: '零件总重必须填写且大于 0。请回到步骤 2 补上。' };
    case 'PART_TEXT_TOO_LONG':
      return { hint: '品名 / 规格 / 尺寸最多 100 个字符。请回到步骤 2 缩短。' };
    case 'PART_TEXT_INVALID_CHAR':
      return { hint: '品名 / 规格 / 尺寸不能包含 「|」「=」「,」「:」—— 这几个字符是产品指纹的分隔符。请回到步骤 2 修改。' };
    default:
      return { hint: '请检查配置后重试；若持续失败，请把下面的错误码提供给管理员。' };
  }
}

const ConfirmStep: React.FC<Props> = ({
  customerProductNo, customerProductName, parts, composites, preview, result, failure, onOpenExistingProducts,
}) => {
  // ── 提交成功：结果页（原型状态 B / C 的 alert + 状态 C 的「带出的销售产品信息」表）──
  if (result) {
    const info = result.reusedProductInfo ?? null;
    return (
      <div>
        {result.fingerprintMatched ? (
          <Alert
            type="info"
            showIcon
            message={<b>产品库里已有相同配置的产品，已直接复用它的销售料号</b>}
            description={(
              <div>
                销售料号 {result.reusedHfPartNos.map((n) => <Mono key={n}>{n}</Mono>)}
                {info?.firstCreatedAt ? ` · 首次创建于 ${String(info.firstCreatedAt).slice(0, 10)}` : ''}
                <div style={{ color: '#909399', fontSize: 12, marginTop: 4 }}>
                  没有新建料号。你的客户产品编号 {customerProductNo} 已作为新的映射关系记录下来。
                </div>
              </div>
            )}
          />
        ) : (
          <Alert
            type="success"
            showIcon
            message={<b>产品库里没有相同配置的产品，已新建一个销售料号</b>}
            description={(
              <div>
                {result.reusedHfPartNos.length > 0
                  ? <>销售料号 {result.reusedHfPartNos.map((n) => <Mono key={n}>{n}</Mono>)}</>
                  : <span style={{ color: '#909399' }}>新料号已生成，可在报价单行里查看。</span>}
              </div>
            )}
          />
        )}

        {info && (
          <div style={{ marginTop: 16 }}>
            <Table<SummaryRow>
              rowKey="key"
              size="small"
              pagination={false}
              showHeader
              columns={[
                { title: '带出的销售产品信息', dataIndex: 'label', key: 'label', width: 180, render: (v: string) => <span style={{ color: '#909399' }}>{v}</span> },
                { title: '内容', dataIndex: 'content', key: 'content' },
              ]}
              dataSource={[
                { key: 'no', label: '销售料号', content: <Mono>{info.hfPartNo}</Mono> },
                {
                  key: 'name',
                  label: '品名 / 规格 / 尺寸',
                  content: [info.partName, info.specification, info.dimension].filter(Boolean).join(' · ') || '—',
                },
                { key: 'w', label: '单重', content: info.unitWeight ? `${trimTrailingZeros(info.unitWeight)} g` : '—' },
                {
                  key: 'mat',
                  label: '材质构成',
                  content: (info.materials ?? []).length > 0
                    ? <>{(info.materials ?? []).map((m, i) => (
                        <Tag key={`${m.recipeCode}-${i}`}>{m.name || m.recipeCode}{m.ratio ? ` ${trimTrailingZeros(m.ratio)}%` : ''}</Tag>
                      ))}</>
                    : '—',
                },
                {
                  key: 'price',
                  label: '历史报价',
                  content: info.lastQuotedPrice ? `¥ ${trimTrailingZeros(info.lastQuotedPrice)} / 件` : '—',
                },
              ]}
            />
          </div>
        )}

        {result.fingerprintMatched && (
          <NoteBlock>
            <b>为什么这次算「相同」</b>：材质、占比、总重、品名规格尺寸、工序全部一致 ⇒ 指纹相同。
            <br />
            ⚠️ 如果两次选的<b>配方编号不同</b>但含量逐字相同，<b>仍然判为相同</b>、仍然复用 ——
            这是用户裁决 D-5 的<b>有意为之</b>（含量相同即同一种材料），<b>不是 bug</b>。
            <br />
            ⚠️ 工序<b>顺序</b>不影响复用判定，所以复用时工序顺序沿用已有产品的
            {result.reusedHfPartNos.length > 0 ? <>（{result.reusedHfPartNos[0]}）</> : null}，不会按本次的排列重排。
          </NoteBlock>
        )}
        {result.structureVersion ? (
          <div style={{ fontSize: 12, color: '#c0c4cc', marginTop: 8 }}>指纹结构版本：{result.structureVersion}</div>
        ) : null}
      </div>
    );
  }

  // ── 提交前：预览 + 摘要 ──
  const summary: SummaryRow[] = [
    { key: 'cpno', label: '客户产品编号', content: <Mono>{customerProductNo || '—'}</Mono> },
    { key: 'cpname', label: '客户产品名称', content: customerProductName || '—' },
    ...parts.map((p, i) => ({
      key: p.uid,
      label: `配件 ${i + 1}`,
      content: (
        <span>
          {partDisplayName(p)} {partTypeTags(p)}
          {p.partType === 'PART' && p.partMode === 'new' && p.unitWeightGrams
            ? <> · 总重 {trimTrailingZeros(p.unitWeightGrams)} g</> : null}
          {' · '}{materialTags(p)}
          {' · '}{processText(p)}
        </span>
      ),
    })),
    {
      key: 'combo',
      label: '组合工序',
      content: composites.length === 0
        ? <span style={{ color: '#c0c4cc' }}>—</span>
        : composites.map((c) => c.name).join(' / '),
    },
  ];

  return (
    <div>
      {preview.checking ? (
        <Alert type="info" message="正在与产品库比对指纹…" showIcon />
      ) : preview.matched ? (
        <Alert
          type="info"
          showIcon
          message={<b>产品库里已有相同配置的产品，将直接复用它的销售料号</b>}
          description={(
            <div>
              销售料号 <Mono>{preview.matchedPartNo || '（提交时确定）'}</Mono>
              <div style={{ color: '#909399', fontSize: 12, marginTop: 4 }}>
                不会新建料号。你的客户产品编号 {customerProductNo} 会作为新的映射关系记录下来。
              </div>
            </div>
          )}
        />
      ) : (
        <Alert
          type="success"
          showIcon
          message={<b>产品库里没有相同配置的产品，将新建一个销售料号</b>}
          description={<span style={{ color: '#909399', fontSize: 12 }}>新料号在点「添加到报价单」时才会真正生成。</span>}
        />
      )}

      {failure && (
        <Alert
          type="error"
          showIcon
          style={{ marginTop: 12 }}
          message={<b>添加失败：{failure.message}</b>}
          description={(() => {
            const guide = errorGuide(failure.code, customerProductNo, onOpenExistingProducts);
            return (
              <div>
                {guide.hint}
                {failure.code ? (
                  <div style={{ color: '#909399', fontSize: 12, marginTop: 4 }}>错误码 {failure.code}</div>
                ) : null}
                {guide.action ? <div style={{ marginTop: 10 }}>{guide.action}</div> : null}
              </div>
            );
          })()}
        />
      )}

      <div style={{ marginTop: 16 }}>
        <Table<SummaryRow>
          rowKey="key"
          size="small"
          pagination={false}
          columns={[
            { title: '项', dataIndex: 'label', key: 'label', width: 180, render: (v: string) => <span style={{ color: '#909399' }}>{v}</span> },
            { title: '内容', dataIndex: 'content', key: 'content' },
          ]}
          dataSource={summary}
        />
      </div>
    </div>
  );
};

export default ConfirmStep;
