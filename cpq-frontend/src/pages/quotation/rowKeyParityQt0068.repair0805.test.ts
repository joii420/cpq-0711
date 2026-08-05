/**
 * repair-0805 / BL-0112 —— T7（阶段二 · 根因 B：前后端 rowKey 口径对拍，AC-7）
 *
 * ⚠️ **T7.1 本轮预期为红**。它守的是阶段二 F5（`useCardSnapshots.computeRowKey` 兼容
 * `f.defaultSource ?? f.default_source`），前端本轮只做阶段一（根因 A），F5 尚未开工。
 * 保持红、不许 skip —— skip 掉就等于本轮把这条验收标准悄悄抹掉了。
 *
 * 缺陷本身（问题分析报告 §4.1）：
 *   `computeRowKey` 按 camelCase `f.defaultSource` 解析行键字段；
 *   渲染模型里的字段却是 `enrichComponentData.ts:289` 写出的 snake_case `default_source`；
 *   调用点（QuotationStep2.tsx:2089/:2092、ReadonlyProductCard.tsx:660）又用 `as any` 抹掉了类型差异
 *   → 解析恒落空 → rowKey 退化成行号 → 与后端的内容键永久对不上 → 快照永远命中不了。
 *
 *     后端 3120011203/3110520422::AgNi10/Cu触点
 *     前端 3120011203/3110520422::1               ❌
 *
 * 它单独存在无害（查不到快照就走本地兜底），但它拆掉了根因 A 的安全网 —— 两个凑一起才炸。
 */
import { describe, it, expect } from 'vitest';
import { buildUniqueRowKeys } from './useCardSnapshots';
import {
  QT0068, structureTab, valuesTab, buildQt0068ComponentData, deepClone,
} from './__fixtures__/qt20260804-0068';

const backendTab = valuesTab(QT0068.wuliaoTabName);
/** 后端 `FormulaCalculator#buildRawRowKeys` 落库的 6 个 rowKey（`nodeId::内容键`）。 */
const BACKEND_ROW_KEYS: string[] = backendTab.formulaResults.map((r: any) => r.rowKey);
const BASE_ROWS = backendTab.baseRows as any[];

/** 渲染模型（snake_case `default_source`）—— QuotationStep2 / ReadonlyProductCard 真实传进去的那份。 */
function renderModelFields() {
  const cd = buildQt0068ComponentData();
  return cd.find(c => c.componentId === QT0068.wuliaoComponentId)!.fields as any[];
}

/** 结构快照页签字段（camelCase `defaultSource`，`CardStructureTab.fields`）—— 另一批调用方传的形状。 */
function structureFields() {
  return structureTab(QT0068.wuliaoTabName).fields as any[];
}

describe('repair-0805 T7 · 前后端 rowKey 对拍（AC-7，阶段二 F5）', () => {
  it('T7.0 夹具自检：后端 6 个 rowKey 是「nodeId::内容键」，不是行号', () => {
    expect(BACKEND_ROW_KEYS).toHaveLength(6);
    expect(BASE_ROWS).toHaveLength(6);
    // 第 0 行料件为空，前后端都退化成行号 "0"（问题分析报告 §2.2 说的"偶然撞上"那一行）
    expect(BACKEND_ROW_KEYS[0]).toBe('3120011203::0');
    // 其余 5 行是内容键
    expect(BACKEND_ROW_KEYS[1]).toBe('3120011203/3110520422::AgNi10/Cu触点');
    expect(BACKEND_ROW_KEYS.slice(1).every(k => !/::\d+$/.test(k))).toBe(true);
  });

  it('T7.1 buildUniqueRowKeys(comp.fields, ["料件"], baseRows, true) 逐字等于后端 rowKey（6/6）', () => {
    // 🔴 阶段一交付时这条**预期失败**：comp.fields 是 snake_case，computeRowKey 只认 camelCase，
    //    → 全部落空 → 退化成行号。等阶段二 F5 落地后转绿。
    const keys = buildUniqueRowKeys(renderModelFields(), QT0068.wuliaoRowKeyFields, BASE_ROWS, true);
    expect(keys).toEqual(BACKEND_ROW_KEYS);
  });

  it('T7.2 字段用 camelCase defaultSource（CardStructureTab.fields）时结果不变 —— 兼容不能踩坏原调用方', () => {
    const keys = buildUniqueRowKeys(structureFields(), QT0068.wuliaoRowKeyFields, BASE_ROWS, true);
    expect(keys).toEqual(BACKEND_ROW_KEYS);
  });

  it('T7.3 反向门禁：删掉字段的 default_source / defaultSource → 退化成行号', () => {
    // 证明 T7.1/T7.2 的相等是「解析链真的跑通了」，不是碰巧撞上。
    for (const [label, fields] of [
      ['camelCase 结构字段', structureFields()],
      ['snake_case 渲染模型字段', renderModelFields()],
    ] as const) {
      // 非空性自检：只对「本来能解析出内容键」的形状做门禁才有意义。
      const baseline = buildUniqueRowKeys(fields, QT0068.wuliaoRowKeyFields, BASE_ROWS, true);
      const baselineIsContentKeys = baseline.slice(1).every(k => !/::\d+$/.test(k));

      const stripped = deepClone(fields).map((f: any) => {
        delete f.defaultSource;
        delete f.default_source;
        return f;
      });
      const keys = buildUniqueRowKeys(stripped, QT0068.wuliaoRowKeyFields, BASE_ROWS, true);
      // driverRow 里没有名为「料件」的键（它叫 `_料件`），default_source 一删就全空 → 行号兜底
      expect(keys, `${label}：删掉 default_source 后应退化成 nodeId::行号`).toEqual([
        '3120011203::0',
        '3120011203/3110520422::1',
        '3120011203/00144::2',
        '3120011203/3110520422/00255::3',
        '3120011203/3110520422/00256::4',
        '3120011203/3110520422/00257::5',
      ]);
      if (baselineIsContentKeys) {
        expect(keys, `${label}：门禁前后必须不同，否则这条门禁是空转`).not.toEqual(baseline);
      }
    }
  });
});
