/**
 * repair-260830：`handleSaveDraft` 两层保存闸所依赖的三个纯函数的回归测试。
 *
 * 背景（实测链路）：导入建单后后端已服务端建好 1845 行、并花 97.5s 算完所有值
 * （`[create-quotation-timing] 总计=97484ms`），用户点一下「下一步」，`next()` 无条件
 * 整单回写 → `[draft-profile] total=61184ms`（S1.saveDraft=55530ms），超过前端 60s
 * 超时 ⇒ 数据其实存进去了、前端却已掉头，用户看到的是「保存失败」。
 *
 * 守护的不变量：
 *   1. 单头变化不能被误判成明细变化（否则又退回全删全建 1845 行）
 *   2. 明细变化必须被认出来（否则真丢用户编辑）
 *   3. 派生字段（id / subtotal / quoteExcelValues / rowData）翻转不算变化
 *      —— 与既有 `stableDraftDedupKey` 同口径，AP 前科见 draftPayloadDedup.ts 头注释
 *   4. `headerOnlyDraftPayload` 必须把 lineItems 置为 **null**（不是 [] 也不是 undefined）
 *      —— 后端 `QuotationService.java:420` 判的是 `request.lineItems != null`，
 *         传 `[]` 会被当成「用户删光了所有行」而真的全删。
 */
import { describe, it, expect } from 'vitest';
import {
  headerDedupKey,
  lineItemsDedupKey,
  headerOnlyDraftPayload,
  stableDraftDedupKey,
} from './draftPayloadDedup';

const basePayload = () => ({
  name: '正泰报价单',
  projectName: 'P-001',
  expiryDate: '2026-12-31',
  finalDiscountRate: '100',
  lineItems: [
    {
      id: 'db-uuid-1',
      productPartNo: 'PN-001',
      annualVolume: 1,
      subtotal: '123.456',
      quoteExcelValues: '{"a":1}',
      componentData: [
        { componentId: 'c1', rowData: '[{"live":true}]', subtotal: '10' },
      ],
    },
  ],
});

describe('headerDedupKey', () => {
  it('单头字段变化时 key 变化', () => {
    const a = basePayload();
    const b = { ...basePayload(), projectName: 'P-002' };
    expect(headerDedupKey(a)).not.toBe(headerDedupKey(b));
  });

  it('只有明细变化时单头 key 不变 —— 否则单头会被误判成脏', () => {
    const a = basePayload();
    const b = basePayload();
    b.lineItems[0].annualVolume = 999;
    expect(headerDedupKey(a)).toBe(headerDedupKey(b));
  });

  it('空 payload 不抛异常', () => {
    expect(headerDedupKey(null)).toBe('');
    expect(headerDedupKey(undefined)).toBe('');
  });
});

describe('lineItemsDedupKey', () => {
  it('明细的用户输入变化时 key 变化', () => {
    const a = basePayload();
    const b = basePayload();
    b.lineItems[0].annualVolume = 999;
    expect(lineItemsDedupKey(a)).not.toBe(lineItemsDedupKey(b));
  });

  it('只有单头变化时明细 key 不变 —— 这正是「只发轻量单头保存」的判据', () => {
    const a = basePayload();
    const b = { ...basePayload(), projectName: 'P-002' };
    expect(lineItemsDedupKey(a)).toBe(lineItemsDedupKey(b));
  });

  it('派生字段翻转不算明细变化（与 stableDraftDedupKey 同口径）', () => {
    const a = basePayload();
    const b = basePayload();
    // 首存回填后 driverExpansions 从 live 切 snapshot，这些值会重算成不同字符串
    b.lineItems[0].id = 'db-uuid-NEW';
    b.lineItems[0].subtotal = '999.999';
    b.lineItems[0].quoteExcelValues = '{"a":2}';
    b.lineItems[0].componentData[0].rowData = '[{"live":false}]';
    b.lineItems[0].componentData[0].subtotal = '20';
    expect(lineItemsDedupKey(a)).toBe(lineItemsDedupKey(b));
  });

  it('无 lineItems 时返回空串，不抛', () => {
    expect(lineItemsDedupKey({ name: 'x' })).toBe('');
    expect(lineItemsDedupKey(null)).toBe('');
  });
});

describe('headerOnlyDraftPayload', () => {
  it('lineItems 必须是 null —— 后端判的是 != null，传 [] 会被当成删光所有行', () => {
    const out = headerOnlyDraftPayload(basePayload());
    expect(out.lineItems).toBeNull();
    expect(out.lineItems).not.toEqual([]);
  });

  it('单头字段原样保留', () => {
    const out = headerOnlyDraftPayload(basePayload());
    expect(out.name).toBe('正泰报价单');
    expect(out.projectName).toBe('P-001');
    expect(out.finalDiscountRate).toBe('100');
  });

  it('不改动入参（纯函数）', () => {
    const src = basePayload();
    headerOnlyDraftPayload(src);
    expect(Array.isArray(src.lineItems)).toBe(true);
    expect(src.lineItems).toHaveLength(1);
  });
});

describe('与既有 stableDraftDedupKey 的口径一致性', () => {
  it('派生字段剔除口径不漂移：两者对同一组翻转都判定为「未变」', () => {
    const a = basePayload();
    const b = basePayload();
    b.lineItems[0].id = 'db-uuid-NEW';
    b.lineItems[0].subtotal = '999.999';
    b.lineItems[0].componentData[0].rowData = '[]';
    expect(stableDraftDedupKey(a)).toBe(stableDraftDedupKey(b));
    expect(lineItemsDedupKey(a)).toBe(lineItemsDedupKey(b));
  });
});
