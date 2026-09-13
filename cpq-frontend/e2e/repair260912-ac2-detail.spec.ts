/**
 * AC-2（问题说明 ⑥ 原文）：QT-20260912-0011 详情页 → 产品明细 → 核价单 → Excel 视图：
 *   同样 4 行、同样的值，且**不再出现 BOM 节点行**
 *   （页面上不得出现 300012 / 00081 这类子件料号作为独立行）。
 *
 * 🔑 本用例同时充当 AC-8① 的还原实验：
 *   R260912_STAGE=pre-refresh  —— 主线刷存量前（落库仍是 costingTree=true 的 7/6/6/5 行 + treeMode）
 *                                 ⇒ 必须红：断言树形行确实存在，证明用例看得见「树形」这件事
 *   R260912_STAGE=post-refresh —— 刷完后 ⇒ 必须绿：4 行、无子件料号行、值等于期望表
 *   同一段观测代码跑两种期望 ⇒ post 绿不可能是观测手段失灵造成的假绿。
 * 纯只读：不点保存、不改数据。
 */
import { test, expect, Page } from '@playwright/test';
import { QID, EXPECT, login, switchSeg, excelTable, dump, num, shot, saveJson, Dump } from './repair260912-helpers';

const STAGE = (process.env.R260912_STAGE || 'post-refresh') as 'pre-refresh' | 'post-refresh';
const CHILD_PART_NOS = ['300012', '300013', '300014', '300015', '00003', '00081', '300001'];

function idx(d: Dump, name: string): number {
  const i = d.headers.findIndex(h => h.replace(/\s+/g, '').endsWith(name));
  if (i < 0) throw new Error(`表头缺「${name}」，实际=${JSON.stringify(d.headers)}`);
  return i;
}
/** 首列表头：编辑页为「料号」，详情页为「产品/节点」—— 两者都接受。 */
function partColIdx(d: Dump): number {
  const i = d.headers.findIndex(h => {
    const t = h.replace(/\s+/g, '');
    return t.endsWith('料号') || t.includes('产品/节点');
  });
  if (i < 0) throw new Error(`表头缺料号/产品列，实际=${JSON.stringify(d.headers)}`);
  return i;
}
function rowOf(d: Dump, sku: string): string[] {
  const i = partColIdx(d);
  const hit = d.rows.filter(r => r[i].trim() === sku);
  if (hit.length !== 1) throw new Error(`料号 ${sku} 命中 ${hit.length} 行（期望恰好 1）；料号列=${JSON.stringify(d.rows.map(r => r[i]))}`);
  return hit[0];
}

test(`AC-2 详情页核价 Excel 视图（stage=${STAGE}）`, async ({ page }) => {
  await login(page);
  await page.goto(`/quotations/${QID}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  await switchSeg(page, '核价单');
  await switchSeg(page, 'Excel 视图');
  await expect(page.getByText('元素小计').first(), '应已切到核价 Excel 视图').toBeVisible({ timeout: 30_000 });

  const t = await excelTable(page);
  const d = await dump(t);
  console.log(`[AC-2/${STAGE} 表头]`, JSON.stringify(d.headers));
  d.rows.forEach((r, i) => console.log(`[AC-2/${STAGE} 数据行${i}]`, JSON.stringify(r)));
  saveJson(`ac2-detail-${STAGE}.json`, d);
  await shot(page, `AC-2-详情页核价Excel视图-${STAGE}`);

  const partCol = partColIdx(d);
  const parts = d.rows.map(r => r[partCol].trim());
  console.log(`[AC-2/${STAGE} 料号列]`, JSON.stringify(parts));
  const childHits = parts.filter(p => CHILD_PART_NOS.includes(p));

  if (STAGE === 'pre-refresh') {
    // 还原实验（AC-8①）：存量仍是 costingTree=true 的产物 ⇒ 必须能看到子件料号行。
    // 看不到 = 观测手段失灵，AC-2 的「绿」将毫无意义。
    expect(childHits.length,
      `刷存量前应能观测到 BOM 子件行（证明本用例看得见树形）；实际料号列=${JSON.stringify(parts)}`).toBeGreaterThan(0);
    expect(d.rows.length, `刷存量前行数应 > 4（树形展开）；实际 ${d.rows.length}`).toBeGreaterThan(4);
    console.log(`[AC-8① 还原实验] 刷前观测到 ${d.rows.length} 行、子件行 ${childHits.length} 个 ⇒ 用例有鉴别力`);
  } else {
    expect(d.rows.length, `每产品一行 ⇒ 应恰好 4 行，实际 ${d.rows.length}`).toBe(4);
    // 阴性断言（用户明确要的「不要树状」）
    expect(childHits, `详情页不得出现 BOM 子件料号行；实际料号列=${JSON.stringify(parts)}`).toEqual([]);
    expect(parts.slice().sort(), '料号列应恰好是四个产品').toEqual(['S0001', 'S0004', 'S0008', 'S0012']);
    // 整页文本级阴性兜底：连 300012 / 00081 这些字样都不该出现在 Excel 视图表里
    const tableText = (await t.innerText()).replace(/\s+/g, '');
    for (const p of ['300012', '00081', '300015']) {
      expect(tableText.includes(p), `Excel 视图表内不得出现子件料号 ${p}`).toBe(false);
    }
    // 与 AC-1 同样的四列逐格断言
    const seen: Record<string, Record<string, number>> = {};
    for (const sku of ['S0001', 'S0004', 'S0008', 'S0012']) {
      const row = rowOf(d, sku);
      seen[sku] = {};
      for (const c of ['元素小计', '物料小计', '加工费', '单价']) seen[sku][c] = num(row[idx(d, c)]);
    }
    console.log('[AC-2 实际观测值]', JSON.stringify(seen, null, 2));
    for (const sku of Object.keys(seen))
      for (const c of ['元素小计', '物料小计', '加工费', '单价'])
        expect(seen[sku][c], `${sku} 的「${c}」`).toBe(EXPECT[sku][c]);
  }
});
