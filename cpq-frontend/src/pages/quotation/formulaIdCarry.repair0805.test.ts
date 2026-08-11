/**
 * repair-0805 / BL-0112 —— T1 · T2 · T6
 *
 * 守的缺陷：BL-0098 把「字段 → 公式」的解析主键从公式名换成公式 id 之后，
 * 渲染侧 `enrichComponentData` 的两条白名单式搬运只搬了字段侧的 `formula_id`，
 * **没搬公式自己的 `id`** → `resolveFormula` 按 id 查恒不命中 → 该字段整个不进计算列表
 * → 那一列静默显示 '—'（不报错、不告警、列小计还残留旧值）。
 *
 *   T1  两条组装路径都必须把 `id` 搬过来（AC-3）
 *   T2  `resolveFormula` 按 id 解析，且「绑了查不到」不许回落（AC-3，设计守卫）
 *   T6  三处 id 查找不许用 `as any` 绕过类型系统（AC-6）
 *
 * 用例书：dev-docs/repair-0803-BL0098-公式绑定改绑ID/repair-0805-… 目录下的 test.md
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

import { buildComponentDataFromStructure, enrichComponentData } from './enrichComponentData';
import { buildComponentDataFromTemplate } from './BulkImportPartsDrawer';
import { computeRowsCachesForTest } from './QuotationStep2';
import type { ComponentDataItem } from './QuotationStep2';
import {
  QT0068,
  QT0068_QUOTE_CARD_STRUCTURE,
  QT0068_TEMPLATE_COMPONENTS_SNAPSHOT,
  QT0068_SAVED_COMPONENT_DATA,
  buildQt0068ComponentData,
} from './__fixtures__/qt20260804-0068';

// ── T1.2 的模板快照路径会真的发 GET /templates/{id}；这里 mock 掉 service 层 ───────────
// mock 数据不是手搓的：`QT0068_TEMPLATE_COMPONENTS_SNAPSHOT` 逐字取自
// `GET /api/cpq/templates/88d5d815-…` 的 `data.componentsSnapshot`（2026-08-05 导出），
// 公式带 `id`、字段带 `formula_id`，与线上完全同形。
const getByIdCached = vi.fn();
vi.mock('../../services/templateService', () => ({
  templateService: {
    getByIdCached: (id: string) => getByIdCached(id),
  },
}));

beforeEach(() => {
  getByIdCached.mockReset();
  getByIdCached.mockResolvedValue({
    data: { componentsSnapshot: JSON.stringify(QT0068_TEMPLATE_COMPONENTS_SNAPSHOT) },
  });
});

/** 取「物料」页签的渲染模型（本缺陷的报障页签：11 个 FORMULA 列全空）。 */
function wuliaoOf(cd: ComponentDataItem[]): ComponentDataItem {
  const c = cd.find(x => x.componentId === QT0068.wuliaoComponentId);
  if (!c) throw new Error('物料页签没被组装出来');
  return c;
}

/** 结构快照里「物料」页签 16 条公式的 id（对拍基准，逐条同序比较）。 */
const STRUCTURE_FORMULA_IDS: string[] = (QT0068_QUOTE_CARD_STRUCTURE.tabs as any[])
  .find((t: any) => t.tabName === QT0068.wuliaoTabName)!
  .formulas.map((f: any) => f.id);

describe('repair-0805 T1 · enrich 两条路径都必须搬公式 id（AC-3）', () => {
  it('T1.1 结构快照路径 buildComponentDataFromStructure：每条 formula 的 id 非空且与结构快照逐条相等', () => {
    const wuliao = wuliaoOf(buildQt0068ComponentData());

    expect(wuliao.formulas).toHaveLength(16);
    // 逐条同序相等 —— 不只是"有值"，还必须是原来那一条的 id
    expect(wuliao.formulas.map(f => f.id)).toEqual(STRUCTURE_FORMULA_IDS);
    for (const f of wuliao.formulas) {
      expect(typeof f.id, `公式「${f.name}」的 id 丢了`).toBe('string');
      expect(f.id!.length).toBeGreaterThan(0);
    }
  });

  it('T1.2 模板快照路径 enrichComponentData（mock GET /templates）：同 T1.1', async () => {
    // ⚠️ 这条不可省。只测结构快照路径 = 只覆盖了报价编辑页那半条链路；
    //    模板快照路径是详情页 / 选配创建 / 结构缺失回退走的另一条，
    //    漏测正是 AP-41「一个视图正常、另一个失效」的成因。
    //
    // ⚠️ 入参不能传 `[]`：savedCompData 为空时 enrichComponentData 会分流到
    //    BulkImportPartsDrawer.buildComponentDataFromTemplate（见下方 T1.4），
    //    根本走不到 :166 那段 `.map()`。这里传真实 saved 行走主路径。
    const cd = await enrichComponentData(QT0068.templateId, QT0068_SAVED_COMPONENT_DATA);
    expect(getByIdCached).toHaveBeenCalledWith(QT0068.templateId);

    const wuliao = wuliaoOf(cd);
    expect(wuliao.formulas).toHaveLength(16);
    expect(wuliao.formulas.map(f => f.id)).toEqual(STRUCTURE_FORMULA_IDS);
    for (const f of wuliao.formulas) {
      expect(typeof f.id, `公式「${f.name}」的 id 丢了`).toBe('string');
      expect(f.id!.length).toBeGreaterThan(0);
    }
  });

  it('T1.3 两条路径产出的每个 field.formula_id 都能在 comp.formulas 里 find 到（防回归核心断言）', async () => {
    const fromStructure = buildQt0068ComponentData();
    const fromTemplate = await enrichComponentData(QT0068.templateId, QT0068_SAVED_COMPONENT_DATA);

    for (const [label, cd] of [['结构快照路径', fromStructure], ['模板快照路径', fromTemplate]] as const) {
      let checked = 0;
      for (const comp of cd) {
        const ids = new Set(comp.formulas.map(f => f.id).filter(Boolean));
        for (const f of comp.fields) {
          // 单一模式
          if (f.formula_id) {
            checked++;
            expect(ids.has(f.formula_id), `${label} · ${comp.tabName}.${f.name} 绑的 formula_id=${f.formula_id} 在 comp.formulas 里查不到`).toBe(true);
          }
          // 条件模式：rules[].formula_id + default_formula_id
          const cf: any = (f as any).conditional_formula;
          for (const r of (cf?.rules ?? [])) {
            if (r?.formula_id) {
              checked++;
              expect(ids.has(r.formula_id), `${label} · ${comp.tabName}.${f.name} 的条件规则 formula_id=${r.formula_id} 查不到`).toBe(true);
            }
          }
          if (cf?.default_formula_id) {
            checked++;
            expect(ids.has(cf.default_formula_id), `${label} · ${comp.tabName}.${f.name} 的 default_formula_id=${cf.default_formula_id} 查不到`).toBe(true);
          }
        }
      }
      // 夹具自检：本单据「物料」页签 10 个单一绑定 + 1 条 rule + 1 条 default = 12 处，
      // 「产品」等页签不在裁剪范围内。checked=0 说明夹具被人改空了，那这条断言就成了摆设。
      expect(checked, `${label} 一处 formula_id 都没检到——夹具被改坏了`).toBe(12);
    }
  });

  it('T1.4 第三条搬运层 buildComponentDataFromTemplate：键的两端必须同时迁移（同构白名单守卫）', () => {
    // `enrichComponentData(templateId, [])` 会分流到这里（选配创建 / 无 saved 数据）。
    // 现状：这条路径**字段侧的 formula_id 和公式侧的 id 都没搬**，两端一起缺 → 退回按名字解析，
    // 恰好自洽，所以没被 BL-0098 打坏。危险的是「只补一端」——那才是本次事故的形状。
    // 本断言钉的就是这个不变量：两端要么都在，要么都不在。
    const cd = buildComponentDataFromTemplate({
      componentsSnapshot: JSON.stringify(QT0068_TEMPLATE_COMPONENTS_SNAPSHOT),
    });
    const wuliao = wuliaoOf(cd);

    const anyFormulaHasId = wuliao.formulas.some(f => !!f.id);
    const anyFieldHasFormulaId = wuliao.fields.some(f => !!f.formula_id);
    expect(
      anyFormulaHasId,
      'buildComponentDataFromTemplate 只搬了一端：公式侧有 id 而字段侧无 formula_id（或反之）——这正是 repair-0805 的形状',
    ).toBe(anyFieldHasFormulaId);

    // T1.3 的不变量在这条路径上同样必须成立（当前两端皆空，故为真空真；补齐时自动升级为实质断言）
    const ids = new Set(wuliao.formulas.map(f => f.id).filter(Boolean));
    for (const f of wuliao.fields) {
      if (f.formula_id) expect(ids.has(f.formula_id)).toBe(true);
    }
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// T2 · resolveFormula 按 id 解析（AC-3）
//
// resolveFormula 不导出，走导出的 computeRowsCachesForTest（内部 → computeAllFormulas
// → resolveFormula）观察：命中 = 结果 map 里有该列且值等于命中公式；
// 不命中 = 该字段整个不进 formulaFields → **结果 map 里连 key 都没有**（这正是线上 '—' 的成因）。
//
// 这三条是**设计守卫**（不随本次修复由红转绿）：它们钉住 BL-0098「绑了 id 查不到就不回落」
// 的设计，防止有人以"修复"之名把回落加回来。
// ─────────────────────────────────────────────────────────────────────────────

const NUM = (v: string) => [{ type: 'number', value: v }];

describe('repair-0805 T2 · resolveFormula 按 id 解析（AC-3，设计守卫）', () => {
  it('T2.1 字段绑 formula_id 且公式列表里有该 id → 返回那一条（不是同名那条）', () => {
    const comp = {
      componentId: 'C', componentCode: 'C', tabName: 'C', componentType: 'NORMAL',
      fields: [{ name: '成本', field_type: 'FORMULA', formula_id: 'F-1' }],
      formulas: [
        { id: 'F-1', name: '甲公式', expression: NUM('42') },
        // 陷阱：这条既同名（成本）又排在位置 0 之后，若代码回落名字/位置会算出 999
        { id: 'F-2', name: '成本', expression: NUM('999') },
      ],
      rows: [{}], subtotal: 0,
    } as unknown as ComponentDataItem;

    const [cache] = computeRowsCachesForTest(comp, [{}]);
    expect(cache['成本']).toBe('42');
  });

  it('T2.2 字段绑 formula_id 但公式列表里没有该 id（公式被删）→ 该列整个不进结果，且不回落名字/位置', () => {
    const comp = {
      componentId: 'C', componentCode: 'C', tabName: 'C', componentType: 'NORMAL',
      fields: [{ name: '成本', field_type: 'FORMULA', formula_id: 'F-已删除' }],
      formulas: [
        // 同名 + 位置 0：只要代码回落任意一档，都会算出 999
        { id: 'F-2', name: '成本', expression: NUM('999') },
      ],
      rows: [{}], subtotal: 0,
    } as unknown as ComponentDataItem;

    const [cache] = computeRowsCachesForTest(comp, [{}]);
    expect(Object.prototype.hasOwnProperty.call(cache, '成本'), '绑了 id 查不到时不许回落到名字/位置').toBe(false);
    expect(cache['成本']).toBeUndefined();
  });

  it('T2.3 字段无 formula_id、有 formula_name → 按名字命中（存量路径不变）', () => {
    const comp = {
      componentId: 'C', componentCode: 'C', tabName: 'C', componentType: 'NORMAL',
      fields: [{ name: '成本', field_type: 'FORMULA', formula_name: '甲公式' }],
      formulas: [
        { id: 'F-1', name: '甲公式', expression: NUM('42') },
        { id: 'F-2', name: '成本', expression: NUM('999') },
      ],
      rows: [{}], subtotal: 0,
    } as unknown as ComponentDataItem;

    const [cache] = computeRowsCachesForTest(comp, [{}]);
    expect(cache['成本']).toBe('42');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// T6 · 类型门禁（AC-6）
//
// `npx tsc --noEmit -p tsconfig.json` 由 CI / 自检命令跑（本轮已跑，0 错误）。
// 这里补一条 tsc 查不到的**源码级**守卫：三处 id 查找一旦重新写成 `(x as any).id`，
// 类型系统就再也守不住这条协议了 —— 本次事故 tsc 之所以没拦住，就是因为它们写成了 as any。
// ─────────────────────────────────────────────────────────────────────────────

describe('repair-0805 T6 · 类型门禁（AC-6）', () => {
  const SRC_DIR = path.resolve(__dirname);

  it('T6.1a 三处 `comp.formulas.find(… .id === …)` 都不许用 as any 绕过类型系统', () => {
    const src = fs.readFileSync(path.join(SRC_DIR, 'QuotationStep2.tsx'), 'utf-8');
    // 抓每一处 `formulas.find(` / `formulas!.find(` 之后的表达式片段
    const snippets: string[] = [];
    const re = /formulas!?\s*\.\s*find\s*\(/g;
    for (let m = re.exec(src); m; m = re.exec(src)) {
      snippets.push(src.slice(m.index, m.index + 100));
    }
    const idLookups = snippets.filter(s => /\.\s*id\s*===/.test(s));
    // 夹具自检：resolveFormula(:389) + computeAllFormulas.byRef(:503) + collectFormulaFieldDefsForTree.byRef(:807)
    expect(idLookups.length, '按 id 查公式的地方少于 3 处，说明代码结构变了、这条守卫已失效').toBeGreaterThanOrEqual(3);
    const casted = idLookups.filter(s => /\bas\s+any\b/.test(s));
    expect(casted, `以下 id 查找仍用 as any 绕过类型：${JSON.stringify(casted)}`).toEqual([]);
  });

  it('T6.1b 渲染侧 ComponentFormula 接口必须声明 id（否则搬运层随时可能再把它漏掉）', () => {
    const src = fs.readFileSync(path.join(SRC_DIR, 'QuotationStep2.tsx'), 'utf-8');
    const iface = src.slice(src.indexOf('export interface ComponentFormula'));
    const body = iface.slice(0, iface.indexOf('}'));
    expect(body).toMatch(/\bid\??\s*:\s*string/);
  });
});
