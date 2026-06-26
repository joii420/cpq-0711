/**
 * stableDraftDedupKey 回归测试(2026-06-26 P0)。
 *
 * 守护「首存 draft 三连发」修复:去重键必须对「随 driverExpansions live→snap 翻转而重算的派生字段
 * (subtotal / quoteExcelValues / rowData)」**不变**,对「用户输入(产品/字段/客户等)」**敏感**。
 */
import { describe, it, expect } from 'vitest';
import { stableDraftDedupKey } from './draftPayloadDedup';

function basePayload() {
  return {
    name: '报价单A',
    customerTemplateId: 'tpl-1',
    finalDiscountRate: 0.9,
    lineItems: [
      {
        id: 'li-1',
        productId: 'p-1',
        productPartNo: '10110002',
        processIds: ['proc-a'],
        subtotal: 100,                 // 派生
        quoteExcelValues: '{"rows":[{"a":1}]}',  // 派生
        componentData: [
          { componentId: 'c-1', tabName: '投料', sortOrder: 0,
            rowData: '[{"x":1}]', subtotal: 50 },  // 派生
        ],
      },
    ],
  };
}

describe('stableDraftDedupKey', () => {
  it('派生字段(subtotal/quoteExcelValues/rowData)变化 → 去重键不变(churn 不触发再存)', () => {
    const a = basePayload();
    const b = basePayload();
    // 模拟首存后 syncLineItemsFromResponse 翻转快照模式:派生字段全部重算成不同串
    b.lineItems[0].subtotal = 999;
    b.lineItems[0].quoteExcelValues = '{"rows":[{"a":2},{"a":3}]}';
    b.lineItems[0].componentData[0].rowData = '[{"x":1},{"x":2},{"x":3}]';
    b.lineItems[0].componentData[0].subtotal = 777;
    expect(stableDraftDedupKey(a)).toBe(stableDraftDedupKey(b));
  });

  it('首存回填 id(null→uuid)→ 去重键不变(实测三连发真凶,不再补发)', () => {
    const a = basePayload();
    (a.lineItems[0] as any).id = null;       // 首存时新行 id=null
    const b = basePayload();
    (b.lineItems[0] as any).id = 'db-generated-uuid';  // 首存返回后回填 DB id
    expect(stableDraftDedupKey(a)).toBe(stableDraftDedupKey(b));
  });

  it('用户改了产品料号 → 去重键变化(正常再存)', () => {
    const a = basePayload();
    const b = basePayload();
    b.lineItems[0].productPartNo = '99999999';
    expect(stableDraftDedupKey(a)).not.toBe(stableDraftDedupKey(b));
  });

  it('用户加了一个产品 → 去重键变化', () => {
    const a = basePayload();
    const b = basePayload();
    b.lineItems.push({ ...basePayload().lineItems[0], id: 'li-2' });
    expect(stableDraftDedupKey(a)).not.toBe(stableDraftDedupKey(b));
  });

  it('用户改了表头字段(客户模板/折扣)→ 去重键变化', () => {
    const a = basePayload();
    const b = basePayload();
    b.finalDiscountRate = 0.8;
    expect(stableDraftDedupKey(a)).not.toBe(stableDraftDedupKey(b));
  });

  it('用户改了工序选配 → 去重键变化(processIds 非派生)', () => {
    const a = basePayload();
    const b = basePayload();
    b.lineItems[0].processIds = ['proc-a', 'proc-b'];
    expect(stableDraftDedupKey(a)).not.toBe(stableDraftDedupKey(b));
  });

  it('空/异常入参不抛', () => {
    expect(stableDraftDedupKey(null)).toBe('');
    expect(stableDraftDedupKey(undefined)).toBe('');
    expect(() => stableDraftDedupKey({ lineItems: 'oops' })).not.toThrow();
  });
});
