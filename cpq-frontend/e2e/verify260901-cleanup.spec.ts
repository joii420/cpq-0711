/**
 * 独立验收会话 · 清理 J-2 造出的那一行选配产品（用 UI 删除，用户正常操作，不打 SQL DELETE）。
 * 目标行：料号 0028-2609000008 / id 2260e83a-0f5d-4ac4-b02f-15d6519199b2
 */
import { test, expect, Page } from '@playwright/test';
import { execFileSync } from 'child_process';

function sql(q: string): string[] {
  const out = execFileSync('psql',
    ['-h', '10.177.152.12', '-U', 'postgres', '-d', 'cpq_db_0724', '-tAF', '\t', '-c', q],
    { env: { ...process.env, PGPASSWORD: 'joii5231' }, encoding: 'utf-8' });
  return out.split('\n').map(s => s.trim()).filter(Boolean);
}
const sqlOne = (q: string) => { const r = sql(q); return r.length ? r[0] : null; };

const TARGET_PART = '0028-2609000008';
const TARGET_ID = '2260e83a-0f5d-4ac4-b02f-15d6519199b2';
const QNO = 'QT-20260901-0234';

test('CLEANUP 删掉 J-2 加的那行选配产品', async ({ page }) => {
  for (let i = 0; i < 4; i++) {
    const r = await page.request.post('/api/cpq/auth/login',
      { data: { username: 'admin', password: 'Admin@2026' } });
    if (r.ok()) break;
    await page.waitForTimeout(20_000);
  }
  const qid = sqlOne(`SELECT id FROM quotation WHERE quotation_number='${QNO}'`)!;
  const before = sqlOne(`SELECT count(*) FROM quotation_line_item WHERE quotation_id='${qid}'`)!;
  console.log(`[CLEANUP] 删除前行项数 = ${before}`);
  expect(sqlOne(`SELECT count(*) FROM quotation_line_item WHERE id='${TARGET_ID}'`),
    '目标行应存在，否则本清理是空跑').toBe('1');

  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  const next = page.getByRole('button', { name: /下一步/ }).first();
  if (await next.isVisible().catch(() => false) && await next.isEnabled().catch(() => false)) {
    await next.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2500);
  }

  // 逐个「删除」按钮，找它所属卡片含目标料号的那个
  const dels = page.getByRole('button', { name: /^删除$/ });
  const n = await dels.count();
  console.log(`[CLEANUP] 「删除」按钮共 ${n} 个`);
  let hit = -1;
  for (let i = 0; i < n; i++) {
    const scope = dels.nth(i).locator(
      'xpath=ancestor::*[contains(@class,"ant-card") or contains(@class,"ant-collapse-item")][1]');
    const t = await scope.innerText().catch(() => '');
    if (t.includes(TARGET_PART)) { hit = i; break; }
  }
  console.log(`[CLEANUP] 命中第 ${hit} 个删除按钮`);
  expect(hit, `应能定位到含 ${TARGET_PART} 的产品卡片的删除按钮`).toBeGreaterThanOrEqual(0);

  await dels.nth(hit).click();
  await page.waitForTimeout(1000);
  const ok = page.getByRole('button', { name: /确 ?定|确认|OK|删 ?除/ }).last();
  if (await ok.isVisible().catch(() => false)) { await ok.click(); await page.waitForTimeout(1200); }

  const save = page.getByRole('button', { name: /保存草稿/ }).first();
  await expect(save, '应有「保存草稿」按钮').toBeVisible({ timeout: 10_000 });
  await save.click();
  await page.waitForTimeout(10_000);
  await page.waitForLoadState('networkidle');

  const after = sqlOne(`SELECT count(*) FROM quotation_line_item WHERE quotation_id='${qid}'`)!;
  const gone = sqlOne(`SELECT count(*) FROM quotation_line_item WHERE id='${TARGET_ID}'`)!;
  console.log(`[CLEANUP] 删除后行项数 = ${after}（删除前 ${before}）；目标行剩 ${gone} 条`);
  expect(gone, '目标行应已被 UI 删除').toBe('0');
});
