/**
 * repair-260911 · S1 · AC-9（序列，最后跑）
 *
 * ⚠️ AC-9 原文的第一步「把 00081 行『组成用量』由 12.3 改为 10 并失焦」在本单**不可执行**：
 *    核价模板『核价通用1』的三个组件（COMP-0016/0017/0018）**全部字段都是 BASIC_DATA/FORMULA**，
 *    页面上渲染为 <span class="qt-ds-value">，没有任何可编辑输入框（探针实证见 test-report.md）。
 *    ⇒ 本 spec 只跑 AC-9 剩余可执行的部分（保存 → 切视图 → 刷新 → 落库一致），
 *      「改值」那一步标记为**未执行**，等主线裁决，🚫 不自行改写成别的可测语义。
 *
 * 写入面：仅 `保存草稿`（写本单 line_item 的 card_values）。不改任何全局状态。
 */
import { test, expect, Page } from '@playwright/test';
import { login, openStep2, switchView, cardOf, clickTab, dumpTable, colIdx, rowByPartNo, num, shot, saveJson } from './repair260911-helpers';

const EXP_00003 = 233922.5;
const EXP_00081 = 5204745;
const EXP_SUBTOTAL = 5438667.5;

async function readBom(page: Page, label: string) {
  const card = cardOf(page);
  await expect(card, 'S0001 卡片应可见').toBeVisible({ timeout: 30_000 });
  await clickTab(card, 'BOM');
  const d = await dumpTable(card);
  const ci = colIdx(d, '物料成本');
  const v = {
    label,
    r00003: num(rowByPartNo(d, '00003')[ci]),
    r00081: num(rowByPartNo(d, '00081')[ci]),
    footer: d.footer,
    footNums: (d.footer.match(/-?[\d,]+(\.\d+)?/g) || []).map((s) => Number(s.replace(/,/g, ''))),
    mismatchWarn: await page.locator('text=前后端算值不一致').count(),
  };
  console.log(`[${label}] ${JSON.stringify(v)}`);
  return v;
}

test('AC-9 序列：保存 → 切报价单/核价单 → 刷新整页，数值稳定且与落库一致', async ({ page }) => {
  test.setTimeout(300_000);
  await login(page);
  await openStep2(page);
  await switchView(page, '核价单');

  const before = await readBom(page, 'T0-保存前');
  await shot(page, 'ac9-01-before-save');
  expect(before.r00003, 'AC-9 前置：00003 应已是修复后的值').toBeCloseTo(EXP_00003, 4);
  expect(before.r00081, 'AC-9 前置：00081 应已是修复后的值').toBeCloseTo(EXP_00081, 4);

  // ── 保存草稿 ──（antd 两字按钮会渲染成「保 存」；这里是四字「保存草稿」，仍用正则兜住空格）
  const saveBtn = page.locator('button').filter({ hasText: /保\s*存\s*草\s*稿/ }).first();
  await expect(saveBtn, '应有「保存草稿」按钮').toBeVisible({ timeout: 20_000 });
  await saveBtn.click();
  await page.waitForTimeout(12_000);
  await shot(page, 'ac9-02-after-save');

  // ── 切「报价单」再切回「核价单」──
  await switchView(page, '报价单');
  await page.waitForTimeout(3000);
  await switchView(page, '核价单');
  const afterSwitch = await readBom(page, 'T1-切走再切回');
  await shot(page, 'ac9-03-after-switch');

  // ── 刷新整页 ──
  await page.reload();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  const next = page.locator('button', { hasText: '下一步' }).first();
  if (await next.count() > 0 && (await next.isEnabled().catch(() => false))) {
    await next.click();
    await page.waitForTimeout(6000);
  }
  await switchView(page, '核价单');
  const afterReload = await readBom(page, 'T2-刷新整页');
  await shot(page, 'ac9-04-after-reload');

  saveJson('ac9-observations.json', { before, afterSwitch, afterReload });

  for (const s of [afterSwitch, afterReload]) {
    expect(s.r00003, `${s.label}：00003 应仍为 ${EXP_00003}`).toBeCloseTo(EXP_00003, 4);
    expect(s.r00081, `${s.label}：00081 应仍为 ${EXP_00081}`).toBeCloseTo(EXP_00081, 4);
    expect(s.footNums.some((n) => Math.abs(n - EXP_SUBTOTAL) < 1e-4),
      `${s.label}：tfoot 应仍含小计 ${EXP_SUBTOTAL}，实际 ${JSON.stringify(s.footNums)}`).toBe(true);
    expect(s.mismatchWarn, `${s.label}：不应出现「前后端算值不一致」提示`).toBe(0);
  }
});
