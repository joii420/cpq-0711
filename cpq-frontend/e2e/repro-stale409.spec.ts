import { test } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

const QID = process.env.PROBE_QID!;
const IDX = Number(process.env.REPRO_INPUT_IDX || '8');
const TAG = process.env.REPRO_TAG || 'x';
// 门控毫秒：edit 请求在后端已完成（route.fetch 已 resolve = 事务已提交、版本已 +1），
// 但响应先扣在 route 里不交付前端 —— 这就是根因描述的那个窗口。
const GATE_MS = Number(process.env.REPRO_GATE_MS || '4000');

test('repro stale 409 (gated)', async ({ page }) => {
  test.setTimeout(300000);
  const log: string[] = [];
  const stamp = () => new Date().toISOString().slice(11, 23);

  page.on('request', r => {
    const u = r.url();
    if (/quote-card-edit|\/draft\b/.test(u)) {
      log.push(`[${stamp()}] REQ  ${r.method()} ${u.replace(/^https?:\/\/[^/]+/, '')}  body=${(r.postData() || '').slice(0, 170)}`);
    }
  });
  page.on('response', async r => {
    const u = r.url();
    if (/\/draft\b/.test(u)) {
      let body = ''; try { body = (await r.text()).slice(0, 400); } catch { body = '<unreadable>'; }
      log.push(`[${stamp()}] RES  ${r.status()} /draft  ${body}`);
    } else if (/quote-card-edit/.test(u)) {
      log.push(`[${stamp()}] RES  ${r.status()} /quote-card-edit  (响应已交付前端)`);
    }
  });

  await loginAsAdmin(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(5000);

  const next = page.getByRole('button', { name: /下\s*一\s*步/ });
  if (await next.count() && await next.first().isEnabled().catch(() => false)) {
    await next.first().click();
    await page.waitForTimeout(8000);
  }

  // ── 门控：edit 请求照常打到后端并提交，响应扣住 GATE_MS 后再交付 ──
  let committedResolve: () => void = () => {};
  const committed = new Promise<void>(r => { committedResolve = r; });
  let gated = 0;
  const NOGATE = process.env.REPRO_NOGATE === '1';
  if (!NOGATE) await page.route('**/quote-card-edit', async route => {
    const res = await route.fetch();                       // 后端事务已提交 → user_data_version 已 +1
    gated++;
    log.push(`[${stamp()}] GATE 后端已提交 edit#${gated}（版本已 +1），响应扣住 ${GATE_MS}ms 不交付`);
    committedResolve();
    await new Promise(r => setTimeout(r, GATE_MS));
    log.push(`[${stamp()}] GATE 释放 edit#${gated} 响应`);
    await route.fulfill({ response: res });
  });

  const inputs = page.locator('table tbody input:not([disabled]):not([readonly])');
  const target = inputs.nth(IDX);
  const before = await target.inputValue();
  const newVal = String((Number(before) || 0) + 1);
  console.log(`[REPRO] 目标 input#${IDX}: "${before}" → "${newVal}"`);

  await target.click();
  await target.fill(newVal);
  log.push(`[${stamp()}] --- blur（Tab）---`);
  await target.press('Tab');

  // 等到「后端已提交、前端还没收到响应」这一刻
  if (!NOGATE) await Promise.race([committed, new Promise(r => setTimeout(r, 20000))]);
  log.push(`[${stamp()}] --- 窗口已到，点「保存草稿」---`);
  await page.getByRole('button', { name: /保\s*存\s*草\s*稿/ }).click({ noWaitAfter: true, force: true });

  await page.waitForTimeout(GATE_MS + 20000);

  const bodyText = await page.evaluate(() => document.body.innerText);
  console.log('\n========== 网络时序 ==========');
  log.forEach(l => console.log(l));
  console.log('========== END ==========\n');
  const draftLines = log.filter(l => /RES  \d+ \/draft/.test(l));
  const has409 = draftLines.some(l => /RES  409/.test(l) && /STALE_VERSION/.test(l));
  const has200 = draftLines.some(l => /RES  200/.test(l));
  console.log(`[REPRO] draft 响应行 = ${JSON.stringify(draftLines)}`);
  console.log(`[REPRO] === 409 STALE_VERSION = ${has409} ；200 = ${has200} ===`);
  console.log(`[REPRO] 页面出现"已被他人修改" = ${/已被他人修改/.test(bodyText)}`);
  await page.screenshot({ path: `e2e/screenshots/repro-260909/gated-${TAG}.png`, fullPage: true });
});
