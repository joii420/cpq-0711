/**
 * dataset-fixtures.ts — task-260902 E2E 的**夹具发现层**
 *
 * ── 为什么不用 `TEST-DS-` 前缀（2026-09-03 返工的直接原因）────────────────────
 * 第一版 spec 找的是 `TEST-DS-3120014539` 这类行，但那些行是后端 `@QuarkusTest` 造的，
 * `@AfterEach` 会清掉 ⇒ **E2E 单独跑时库里根本没有**，10 条挂 9 条，
 * 报错全是 `element(s) not found`，看起来像产品缺陷，其实是夹具策略错了。
 *
 * ── 现在的策略：用库里真实存在的数据，且**运行时发现**，不写死 ─────────────────
 * 共享库在漂移（`test.md §0.3`），所以：
 *   · 轴值不写死 → 调 `GET /parts` 挑「配置最全」的那一个
 *   · 空态料号不写死 → 挑 `configuredCount === 0` 的那一个
 *   · tab 数不写死 → 与 `GET /sheets` 返回的数量比
 * 🚨 但**必须先断言「发现到了」**：发现不到就以「前置不满足」硬失败，
 *    绝不让用例在空数据上静默跑成绿色（`testing.md §3.3`）。
 */

import { APIRequestContext, expect } from '@playwright/test';

export type DatasetKey = 'quote' | 'cost-basic' | 'cost-detail';

export interface PartRow {
  axisValue: string;
  materialName?: string | null;
  configuredCount?: number | null;
  totalSheetCount?: number | null;
}

/** 拉取某数据集的料号列表（走接口，不依赖 UI 是否已渲染）。 */
export async function fetchParts(api: APIRequestContext, dataset: DatasetKey): Promise<PartRow[]> {
  const r = await api.get(`/api/cpq/dataset/${dataset}/parts?page=0&size=200`);
  expect(r.status(), `前置不满足：GET /dataset/${dataset}/parts 不可用`).toBe(200);
  const items = (await r.json())?.data?.items ?? [];
  return items as PartRow[];
}

/** 该数据集的带版本 sheet 元数据（AC-26 的 tab 数判据来源）。 */
export async function fetchSheets(api: APIRequestContext, dataset: DatasetKey) {
  const r = await api.get(`/api/cpq/dataset/${dataset}/sheets`);
  expect(r.status(), `前置不满足：GET /dataset/${dataset}/sheets 不可用`).toBe(200);
  const sheets = (await r.json())?.data?.sheets ?? [];
  expect(sheets.length, `前置不满足：${dataset} 的 sheets 为空 ⇒ tab 断言会空跑`).toBeGreaterThan(0);
  return sheets as Array<{ sheetKey: string; sheetName: string; sortOrder?: number }>;
}

/**
 * 挑「配置最全」的料号 —— 数据越全，抽屉里能验到的东西越多。
 * 🚨 挑不出来就硬失败：那说明整个数据集没有任何已配置数据，后面的断言全是空跑。
 */
export async function pickRichestPart(api: APIRequestContext, dataset: DatasetKey): Promise<PartRow> {
  const parts = await fetchParts(api, dataset);
  expect(parts.length, `前置不满足：${dataset} 的料号列表为空，请先导入一份数据`).toBeGreaterThan(0);

  const sorted = [...parts].sort((a, b) => (b.configuredCount ?? 0) - (a.configuredCount ?? 0));
  const best = sorted[0];
  expect(
    best.configuredCount ?? 0,
    `前置不满足：${dataset} 里没有任何已配置数据的料号`
      + `（共 ${parts.length} 个料号，configuredCount 全为 0）⇒ 抽屉内的断言会空跑`
  ).toBeGreaterThan(0);
  console.log(`[fixture] ${dataset} 选中最全料号 = ${best.axisValue}`
    + `（已配置 ${best.configuredCount}/${best.totalSheetCount}）`);
  return best;
}

/**
 * 挑一个「一个带版本 tab 都没配」的料号 —— AC-32 空态用。
 * 挑不到就返回 null，由调用方 skip 并明说「未验证」，🚫 不许拿有数据的料号冒充空态。
 */
export async function pickEmptyPart(api: APIRequestContext, dataset: DatasetKey): Promise<PartRow | null> {
  const parts = await fetchParts(api, dataset);
  const empty = parts.find((p) => (p.configuredCount ?? 0) === 0);
  console.log(`[fixture] ${dataset} 空态料号 = ${empty ? empty.axisValue : '（无）'}`);
  return empty ?? null;
}
