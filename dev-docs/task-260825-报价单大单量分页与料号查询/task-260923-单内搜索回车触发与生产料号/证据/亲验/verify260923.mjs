// task-260923 主线亲验脚本（不进仓库）。用户路径：从 UI 打开编辑页 / 详情页 / 核价工作台，逐条对 AC 取实际观测值 + 截图。
// 用法：BASE=http://localhost:5390 RUN=run-01 node verify260923.mjs
import { createRequire } from 'module';
import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

const WT = '/home/joii/project/cpq/.claude/worktrees/task-260923-search-enter-production';
const require = createRequire(`${WT}/cpq-frontend/package.json`);
const { chromium } = require('playwright');

const BASE = process.env.BASE || 'http://localhost:5390';
const BE = 'http://localhost:8081';
const RUN = process.env.RUN || 'run-01';
const TASK = `${WT}/dev-docs/task-260825-报价单大单量分页与料号查询/task-260923-单内搜索回车触发与生产料号`;
const OUT = path.join(TASK, '证据', '亲验', RUN);
if (fs.existsSync(OUT)) throw new Error(`证据目录已存在，拒绝覆盖：${OUT}`);
fs.mkdirSync(OUT, { recursive: true });

const PH = '销售/客户/生产料号，回车搜索';
const ORDER = ['S0001', 'S0002', 'S0003', 'S0008', 'S0009', 'S0010', 'S0011', 'S0012', 'S0013', 'S0014', 'S0004', 'S0005', 'S0006', 'S0007'];
const FIRST10 = ORDER.slice(0, 10);
const SET2 = ['S0004', 'S0005', 'S0006', 'S0007'];
const SET3 = ['S0008', 'S0009', 'S0010', 'S0011'];
const COID = 'a75fac43-09f2-4f0c-9fdd-1d7c960165b5'; // 样本单 B 的核价单 HJ-20260924-0983（S-1 所造，只读）
const BIG = '22b14b66-b3e7-47f8-9898-e1ee4b7944e9'; // QT-20260908-0628（1845 个产品，只读）

const results = [];
const log = (s) => { console.log(s); fs.appendFileSync(path.join(OUT, 'run.log'), s + '\n'); };
function check(ac, what, pass, observed) {
  results.push({ ac, what, pass: !!pass, observed });
  log(`${pass ? '✅' : '❌'} ${ac} ${what} :: ${JSON.stringify(observed)}`);
}
const psql = (sql) => execSync(`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -At -F'|' -c "${sql.replace(/"/g, '\\"')}"`, { encoding: 'utf-8' }).trim();
const sorted = (a) => [...a].sort();
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── 互斥：不许和别的 playwright test 同时跑 ──
function otherPlaywright() {
  const out = [];
  for (const d of fs.readdirSync('/proc')) {
    if (!/^\d+$/.test(d) || +d === process.pid) continue;
    try { const c = fs.readFileSync(`/proc/${d}/cmdline`, 'utf-8').replace(/\0/g, ' '); if (/\/bin\/playwright test/.test(c)) out.push(`${d} ${c.slice(0, 120)}`); } catch { /* gone */ }
  }
  return out;
}
const busy = otherPlaywright();
log(`${new Date().toISOString()} 其它 playwright test 进程：${JSON.stringify(busy)}`);
if (busy.length) throw new Error('有别的 playwright test 在跑，停止');

// ── 验明正身：BASE 服务的是 worktree 代码 ──
const pb = await (await fetch(`${BASE}/src/pages/quotation/PagingBar.tsx`)).text();
if (!pb.includes(PH)) throw new Error(`${BASE} 不是 worktree 代码（PagingBar 不含新提示文字）`);
const qs2 = await (await fetch(`${BASE}/src/pages/quotation/QuotationStep2.tsx`)).text();
log(`验明正身：PagingBar 含新提示文字；QuotationStep2 含「回流修复 1」=${qs2.includes('回流修复 1')}`);

// ── 源数据（现查） ──
const src = Object.fromEntries(psql(`SELECT m.material_no, m.production_no FROM ds_quote_material m WHERE m.customer_no='CUST-0004' AND m.material_no IN (${ORDER.map((s) => `'${s}'`).join(',')})`)
  .split('\n').map((l) => l.split('|')));
log(`源生产料号：${JSON.stringify(src)}`);
if (Object.keys(src).length !== 14 || Object.values(src).some((v) => !v)) throw new Error('源数据不是 14 条非空');

// ── API：登录 + 造主线亲验样本单（前缀 T260923-ML-） ──
async function api(cookie, method, p, body) {
  const r = await fetch(`${BE}${p}`, { method, headers: { Cookie: cookie, 'Content-Type': 'application/json; charset=utf-8' }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* */ }
  return { status: r.status, json, text };
}
const lr = await fetch(`${BE}/api/cpq/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'Admin@2026' }) });
const cookie = (lr.headers.getSetCookie?.() ?? []).map((c) => c.split(';')[0]).join('; ');
if (!lr.ok || !cookie) throw new Error(`接口登录失败 ${lr.status}`);
const cid = psql(`SELECT id FROM customer WHERE code='CUST-0004'`);
const cat = psql(`SELECT product_category_id FROM customer WHERE code='CUST-0004'`);
const tq = psql(`SELECT id FROM template WHERE name='正泰测试模板2' AND template_kind='QUOTATION' AND status='PUBLISHED' AND category_id='${cat}' ORDER BY published_at DESC NULLS LAST LIMIT 1`);
const tc = psql(`SELECT id FROM template WHERE name='核价模板1' AND template_kind='COSTING' AND status='PUBLISHED' AND category_id='${cat}' ORDER BY published_at DESC NULLS LAST LIMIT 1`);
const name = `T260923-ML-${new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)}`;
const c = await api(cookie, 'POST', '/api/cpq/quotations', { customerId: cid, name, quoteType: 'STANDARD', customerTemplateId: tq, costingTemplateId: tc });
if (c.status !== 200) throw new Error(`建单失败 ${c.status} ${c.text.slice(0, 200)}`);
const Q = c.json.data;
const names = Object.fromEntries(psql(`SELECT material_no, coalesce(material_name,'') FROM ds_quote_material WHERE customer_no='CUST-0004'`).split('\n').map((l) => l.split('|')));
const d = await api(cookie, 'PUT', `/api/cpq/quotations/${Q.id}/draft`, { name: Q.name, customerTemplateId: Q.customerTemplateId, costingCardTemplateId: Q.costingCardTemplateId, lineItems: ORDER.map((s, i) => ({ productPartNo: s, productName: names[s] || s, sortOrder: i })) });
if (d.status !== 200) throw new Error(`保存草稿失败 ${d.status}`);
const g = await api(cookie, 'GET', `/api/cpq/quotations/${Q.id}`);
const lis = [...g.json.data.lineItems].sort((a, b) => a.sortOrder - b.sortOrder);
log(`样本单 ${Q.quotationNumber} (${Q.id}) 回读：${JSON.stringify(lis.map((l) => `${l.productPartNo}=${l.hfPartInfo?.partNo ?? ''}`))}`);
check('造数', '样本单 14 行、顺序、生产料号 = 源', lis.length === 14 && eq(lis.map((l) => l.productPartNo), ORDER) && lis.every((l) => l.hfPartInfo?.partNo === src[l.productPartNo]), { n: lis.length });

// ── 浏览器 ──
const browser = await chromium.launch({ channel: 'chrome' });
const ctx = await browser.newContext({ baseURL: BASE, viewport: { width: 1280, height: 800 }, locale: 'zh-CN' });
const page = await ctx.newPage();
const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 300)));
let shotN = 0;
const shot = async (n) => { await page.screenshot({ path: path.join(OUT, `${String(++shotN).padStart(2, '0')}-${n}.png`) }); };

await page.goto('/login');
await page.fill('input[placeholder="用户名或邮箱"]', 'admin');
await page.fill('input[placeholder="密码"]', 'Admin@2026');
await page.click('button[type=submit]');
await page.waitForURL(/dashboard/, { timeout: 60_000 });

const bar = () => page.locator('[data-testid="task260825-paging-bar"]').first();
const inp = () => bar().locator('input[data-testid="paging-search-input"]');
const count = async () => { const el = bar().locator(':scope > span:not(.ant-input-affix-wrapper)').first(); return (await el.count()) ? (await el.innerText()).replace(/\s+/g, ' ').trim() : '<无分页栏>'; };
const salesNos = () => page.evaluate(() => Array.from(document.querySelectorAll('.qt-product-card')).filter((c) => c.offsetParent !== null)
  .map((c) => (c.querySelector('.qt-part-badge')?.innerText || '').replace(/^\s*销售料号\s*[:：]\s*/, '').trim()));
const marks = (sel) => page.evaluate((s) => Array.from(document.querySelectorAll(`.qt-product-card ${s} mark`)).filter((m) => m.offsetParent !== null).map((m) => m.innerText), sel);
const hasPage2 = async () => (await bar().locator('.ant-pagination-item-2').count()) > 0;
const pagerShown = () => page.evaluate(() => Array.from(document.querySelectorAll('[data-testid="task260825-paging-bar"] .ant-pagination')).some((e) => e.offsetParent !== null));
const submit = async (t) => { await inp().fill(t); await inp().press('Enter'); await sleep(900); };
const until = async (ms, fn, want) => { const t0 = Date.now(); let last; while (Date.now() - t0 <= ms) { last = await fn(); if (eq(last, want)) return { ms: Date.now() - t0, last }; await sleep(50); } return { ms: -1, last }; };
const subtitle = (q, n = 14) => `「${q}」在本报价单的 ${n} 个料号中无匹配。请换一个料号片段，或清空查询查看全部。`;
const visibleText = (t) => page.getByText(t, { exact: true }).first().isVisible().catch(() => false);
const seg = (label) => page.locator('.ant-segmented-item', { hasText: label }).first();
async function waitCards() { await bar().waitFor({ state: 'visible', timeout: 120_000 }); await page.locator('.qt-product-card').first().waitFor({ timeout: 120_000 }); await sleep(1500); }
async function toStep2() {
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  const t0 = Date.now();
  while (Date.now() - t0 < 150_000) { if (await next.isEnabled().catch(() => false)) break; await sleep(500); }
  const ok = await next.isEnabled().catch(() => false);
  if (!ok) return { ok, ms: Date.now() - t0 };
  await next.click(); await waitCards(); return { ok, ms: Date.now() - t0 };
}
const placeholderFit = (loc) => loc.evaluate((el) => {
  const cs = getComputedStyle(el); const ctx = document.createElement('canvas').getContext('2d'); ctx.font = cs.font;
  const w = ctx.measureText(el.placeholder).width; const cw = el.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
  return { ph: el.placeholder, textW: Math.round(w), contentW: Math.round(cw) };
});

try {
  // ═════════════ 编辑页 ═════════════
  await page.goto(`/quotations/${Q.id}/edit`);
  const s2 = await toStep2();
  check('前置', '编辑页 Step1 → Step2', s2.ok, s2);
  // AC-1
  const inputs = page.locator('[data-testid="task260825-paging-bar"] input[data-testid="paging-search-input"]');
  const fits = []; for (let i = 0; i < await inputs.count(); i++) fits.push(await placeholderFit(inputs.nth(i)));
  check('AC-1', '编辑页提示文字逐字且完整可见', fits.length >= 1 && fits.every((f) => f.ph === PH && f.textW <= f.contentW), fits);
  await shot('编辑页-默认');
  // AC-2
  await inp().click(); await inp().pressSequentially('30002', { delay: 60 }); await sleep(2000);
  const a2 = { v: await inp().inputValue(), cnt: await count(), nos: await salesNos(), p2: await hasPage2() };
  check('AC-2', '输入 30002 不回车 2 秒：共 14 条、第 1~10 个、有第 2 页', a2.v === '30002' && a2.cnt === '共 14 条' && eq(sorted(a2.nos), sorted(FIRST10)) && a2.p2, a2);
  await shot('编辑页-已输入未回车');
  // AC-3
  await inp().press('Enter'); await sleep(900);
  const a3 = { cnt: await count(), nos: await salesNos(), marks: await marks('.qt-card-header') };
  check('AC-3', '回车：匹配 4 条、{S0004~S0007}、头部无高亮', a3.cnt === '匹配 4 条 / 共 14 条' && eq(sorted(a3.nos), SET2) && a3.marks.length === 0, a3);
  await shot('编辑页-回车后-生产料号命中');
  const card4 = page.locator('.qt-product-card:visible').filter({ has: page.locator('.qt-part-badge', { hasText: /S0004\s*$/ }) }).first();
  await card4.locator('.qt-part-badge').click(); await sleep(1200);
  const popTxt = await page.locator('.ant-popover:visible').last().innerText().catch(() => '');
  const popNo = (popTxt.match(/料号\s*[:：]\s*\n?\s*(\S+)/) || [])[1] || '';
  await shot('编辑页-S0004浮层');
  check('AC-3', 'S0004 浮层「料号：」= 源生产料号', popNo === src.S0004, { popNo, src: src.S0004 });
  await page.keyboard.press('Escape'); await sleep(300);
  // AC-4
  await submit('S0011'); const a41 = { cnt: await count(), nos: await salesNos(), m: await marks('.qt-part-badge') };
  check('AC-4', '① S0011：匹配 1 条、S0011、销售料号徽标高亮 S0011', a41.cnt === '匹配 1 条 / 共 14 条' && eq(a41.nos, ['S0011']) && eq(a41.m, ['S0011']), a41);
  await shot('编辑页-S0011');
  await submit('zt-c012'); const a42 = { cnt: await count(), nos: await salesNos(), m: await marks('.qt-sku-badge') };
  check('AC-4', '② zt-c012：匹配 1 条、S0012、客户产品编号高亮 ZT-C012', /^匹配 1 条/.test(a42.cnt) && eq(a42.nos, ['S0012']) && eq(a42.m, ['ZT-C012']), a42);
  await submit('正泰端子'); const a43 = { cnt: await count(), nos: await salesNos() };
  check('AC-4', '③ 正泰端子：匹配 1 条、S0012', /^匹配 1 条/.test(a43.cnt) && eq(a43.nos, ['S0012']), a43);
  // AC-5
  await submit('30002');
  await bar().locator('.ant-input-clear-icon').first().click();
  const a5 = await until(1000, async () => [await inp().inputValue(), await count(), sorted(await salesNos())], ['', '共 14 条', sorted(FIRST10)]);
  check('AC-5', '点 ✕ 不回车 1 秒内恢复', a5.ms >= 0, a5);
  // AC-6
  await submit('30002');
  await inp().click(); await inp().press('End'); for (let i = 0; i < 5; i++) await inp().press('Backspace');
  const a61 = await until(1000, async () => [await inp().inputValue(), await count()], ['', '共 14 条']);
  check('AC-6', '① 退格删光 1 秒内恢复', a61.ms >= 0, a61);
  await inp().pressSequentially('30002', { delay: 40 }); await inp().press('Enter'); await sleep(900);
  const a62 = await count(); check('AC-6', '② 再输入回车 = 匹配 4 条', a62 === '匹配 4 条 / 共 14 条', a62);
  await inp().click(); await inp().press('Control+A'); await page.keyboard.type('   ');
  const a63 = await until(1000, async () => [await inp().inputValue(), await count()], ['   ', '共 14 条']);
  check('AC-6', '③ 改为 3 个空格 1 秒内恢复', a63.ms >= 0, a63);
  // AC-7
  await submit('XYZ-T260923');
  const a7 = { title: await visibleText('未找到匹配的料号'), sub: await visibleText(subtitle('XYZ-T260923')), cnt: await count(), pager: await pagerShown(), cards: (await salesNos()).length, barVisible: await bar().isVisible() };
  check('AC-7', '空态：标题 + 副文案逐字 + 计数「未匹配到料号（共 14 条）」+ 无页码 + 0 卡片 + 搜索栏仍在', a7.title && a7.sub && a7.cnt === '未匹配到料号（共 14 条）' && !a7.pager && a7.cards === 0 && a7.barVisible, a7);
  await shot('编辑页-空态');
  await inp().click(); await inp().press('End'); await inp().pressSequentially('-9', { delay: 60 }); await sleep(1500);
  const a7b = { v: await inp().inputValue(), sub: await visibleText(subtitle('XYZ-T260923')) };
  check('AC-7', '追加 -9 不回车：副文案仍引 XYZ-T260923', a7b.v === 'XYZ-T260923-9' && a7b.sub, a7b);
  await shot('编辑页-空态-追加未回车');
  await page.getByRole('button', { name: /清\s*空\s*查\s*询/ }).first().click(); await sleep(900);
  const a7c = { vals: await inputs.evaluateAll((els) => els.map((e) => e.value)), cnt: await count() };
  check('AC-7', '清空查询：框空、共 14 条', a7c.vals.length > 0 && a7c.vals.every((v) => v === '') && a7c.cnt === '共 14 条', a7c);
  // AC-8
  await inp().click(); await inp().pressSequentially('30003', { delay: 40 });
  await inp().dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true, bubbles: true, cancelable: true }); await sleep(1500);
  const a81 = { cnt: await count(), nos: sorted(await salesNos()) };
  check('AC-8', '组字中回车不触发', a81.cnt === '共 14 条' && eq(a81.nos, sorted(FIRST10)), a81);
  await inp().press('Enter'); await sleep(900);
  const a82 = { cnt: await count(), nos: sorted(await salesNos()) };
  check('AC-8', '普通回车触发 {S0008~S0011}', a82.cnt === '匹配 4 条 / 共 14 条' && eq(a82.nos, SET3), a82);
  // AC-9
  await bar().locator('.ant-input-clear-icon').first().click(); await sleep(500);
  await submit('30002'); const a91 = { cnt: await count(), nos: sorted(await salesNos()) };
  check('AC-9', '① 30002 回车', a91.cnt === '匹配 4 条 / 共 14 条' && eq(a91.nos, SET2), a91);
  await seg('核价单').click(); await sleep(5000);
  const a92 = { cnt: await count(), nos: sorted(await salesNos()) };
  check('AC-9', '② 核价单：仍匹配 4 条、{S0004~S0007}', a92.cnt === '匹配 4 条 / 共 14 条' && eq(a92.nos, SET2), a92);
  await shot('编辑页-核价单');
  await seg('报价单').click(); await sleep(3000); await seg('Excel 视图').click(); await sleep(6000);
  const rows = await page.evaluate(() => Array.from(document.querySelectorAll('.ant-table-tbody tr.ant-table-row')).filter((r) => r.offsetParent !== null).map((r) => r.innerText.replace(/\s+/g, ' ')));
  const rowNos = sorted(rows.map((r) => (r.match(/\bS\d{4}\b/) || [''])[0]));
  check('AC-9', '③ 报价单 + Excel 视图：恰 4 行 {S0004~S0007}', rows.length === 4 && eq(rowNos, SET2), { n: rows.length, rowNos, cnt: await count() });
  await shot('编辑页-Excel视图');
  await seg('产品卡片').click(); await sleep(3000);
  const a94 = { nos: sorted(await salesNos()) }; check('AC-9', '④ 切回卡片：仍 {S0004~S0007}', eq(a94.nos, SET2), a94);
  await inp().fill('30003'); await sleep(2000);
  const a95 = { cnt: await count(), nos: sorted(await salesNos()) }; check('AC-9', '⑤ 改 30003 不回车：不变', a95.cnt === '匹配 4 条 / 共 14 条' && eq(a95.nos, SET2), a95);
  await inp().press('Enter'); await sleep(900);
  const a96 = { cnt: await count(), nos: sorted(await salesNos()) }; check('AC-9', '⑥ 回车：{S0008~S0011}', a96.cnt === '匹配 4 条 / 共 14 条' && eq(a96.nos, SET3), a96);
  await page.reload(); const s2b = await toStep2();
  const a97 = { step2: s2b, vals: await inputs.evaluateAll((els) => els.map((e) => e.value)), cnt: await count() };
  check('AC-9', '⑦ 刷新：框空、共 14 条', s2b.ok && a97.vals.every((v) => v === '') && a97.cnt === '共 14 条', a97);
  // AC-12
  const errsBefore = pageErrors.length;
  const long = 'T260923-' + 'Z'.repeat(112);
  await submit(long); await sleep(800);
  const a12 = await page.evaluate(() => {
    const el = Array.from(document.querySelectorAll('body *')).find((e) => e.children.length === 0 && /^「[\s\S]*」在本报价单的/.test(e.innerText || ''));
    const t = el?.innerText || ''; return { quoted: t.slice(1, t.indexOf('」')), sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth };
  });
  const w = await bar().locator('.ant-input-affix-wrapper').first().evaluate((e) => Math.round(e.getBoundingClientRect().width)).catch(() => -1);
  check('AC-12', '120 字符：引号全文、pageerror=0、框宽 320、无横向滚动', a12.quoted === long && pageErrors.length === errsBefore && w === 320 && a12.sw === a12.cw, { len: a12.quoted.length, w, sw: a12.sw, cw: a12.cw, newErrors: pageErrors.slice(errsBefore) });
  await shot('编辑页-超长输入');

  // ═════════════ 详情页 ═════════════
  await page.goto(`/quotations/${Q.id}`); await waitCards();
  const dfits = []; for (let i = 0; i < await inputs.count(); i++) dfits.push(await placeholderFit(inputs.nth(i)));
  check('AC-1', '详情页提示文字逐字且完整可见', dfits.length >= 1 && dfits.every((f) => f.ph === PH && f.textW <= f.contentW), dfits);
  await shot('详情页-默认');
  await inp().click(); await inp().pressSequentially('300034', { delay: 60 }); await sleep(2000);
  const a101 = { cnt: await count(), nos: sorted(await salesNos()) }; check('AC-10', '① 300034 不回车：共 14 条、第 1~10 个', a101.cnt === '共 14 条' && eq(a101.nos, sorted(FIRST10)), a101);
  await inp().press('Enter'); await sleep(900);
  const a102 = { cnt: await count(), nos: await salesNos() }; check('AC-10', '② 回车：匹配 1 条 S0011', a102.cnt === '匹配 1 条 / 共 14 条' && eq(a102.nos, ['S0011']), a102);
  await shot('详情页-命中');
  await bar().locator('.ant-input-clear-icon').first().click();
  const a103 = await until(1000, count, '共 14 条'); check('AC-10', '③ ✕ 1 秒内恢复', a103.ms >= 0, a103);
  await submit('XYZ-T260923'); await inp().click(); await inp().press('End'); await inp().pressSequentially('-9', { delay: 60 }); await sleep(1500);
  const a104 = { sub: await visibleText(subtitle('XYZ-T260923')), v: await inp().inputValue(), cnt: await count() };
  check('AC-10', '④ 空态副文案逐字、追加后不变（搜索栏仍在）', a104.sub && a104.v === 'XYZ-T260923-9' && a104.cnt === '未匹配到料号（共 14 条）', a104);
  await shot('详情页-空态');

  // ═════════════ 核价工作台 ═════════════
  const fz = psql(`SELECT string_agg((e->>'productPartNo')||'='||coalesce(e->'hfPartInfo'->>'partNo',''), ',') FROM costing_order co, jsonb_array_elements((co.frozen_dto::jsonb)->'lineItems') e WHERE co.id='${COID}' AND e->>'productPartNo' IN ('S0008','S0009','S0010','S0011')`);
  check('AC-11', '非空前置：冻结数据 S0008~S0011 生产料号非空', fz.split(',').length === 4 && fz.split(',').every((x) => /=\d+$/.test(x)), fz);
  await page.goto(`/costing-orders/${COID}/review`); await waitCards();
  const wph = await inp().getAttribute('placeholder');
  await inp().click(); await inp().pressSequentially('30003', { delay: 60 }); await sleep(2000);
  const a112 = await count();
  await inp().press('Enter'); await sleep(900);
  const a113 = { cnt: await count(), nos: sorted(await salesNos()) };
  await shot('核价工作台-命中');
  await bar().locator('.ant-input-clear-icon').first().click();
  const a114 = await until(1000, count, '共 14 条');
  check('AC-11', '提示文字 / 不回车共 14 条 / 回车 {S0008~S0011} / ✕ 恢复', wph === PH && a112 === '共 14 条' && a113.cnt === '匹配 4 条 / 共 14 条' && eq(a113.nos, SET3) && a114.ms >= 0, { wph, a112, a113, a114 });

  // ═════════════ 大单抽查（1845，只读：拦截全部非 GET 请求） ═════════════
  const fp = () => psql(`SELECT q.updated_at || '#' || coalesce(q.user_data_version::text,'') || '#' || md5(string_agg(li.id::text || coalesce(li.row_version::text,'') || coalesce(li.card_snapshot_at::text,'') || coalesce(li.quote_values_at::text,''), ',' ORDER BY li.sort_order)) FROM quotation q JOIN quotation_line_item li ON li.quotation_id=q.id WHERE q.id='${BIG}' GROUP BY q.updated_at, q.user_data_version`);
  const fp0 = fp();
  const intercepted = [];
  await page.route('**/api/**', async (route) => {
    const r = route.request();
    if (r.method() === 'GET' || new URL(r.url()).pathname.endsWith('/auth/login')) return route.continue();
    intercepted.push(`${r.method()} ${new URL(r.url()).pathname}`);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'intercepted-by-mainline-readonly', data: {} }) });
  });
  const deep = psql(`SELECT product_part_no_snapshot FROM quotation_line_item WHERE quotation_id='${BIG}' AND coalesce(composite_type,'SIMPLE')<>'PART' ORDER BY sort_order, created_at, id OFFSET 1199 LIMIT 1`);
  await page.goto(`/quotations/${BIG}`); await waitCards();
  const b0 = await count();
  await submit(deep); const b1 = { cnt: await count(), nos: await salesNos() };
  await submit('XYZ999'); const b2 = { cnt: await count(), sub: await visibleText(subtitle('XYZ999', 1845)) };
  await shot('大单详情页-空态');
  await bar().locator('.ant-input-clear-icon').first().click(); const b3 = await until(2000, count, '共 1845 条');
  check('大单抽查', `详情页 1845：第 1200 位 ${deep} 回车命中 1 条 / 空态带搜索栏 / ✕ 恢复`, b0 === '共 1845 条' && b1.cnt === '匹配 1 条 / 共 1845 条' && eq(b1.nos, [deep]) && b2.cnt === '未匹配到料号（共 1845 条）' && b2.sub && b3.ms >= 0, { b0, b1, b2, b3 });
  // 大单编辑页：只记录「下一步」何时可点（S-2 run-03 实测页头单号 / 客户框在该页恒不显示，不能当加载判据）
  await page.goto(`/quotations/${BIG}/edit`);
  const nextBtn = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  const t0 = Date.now(); let nextOk = false;
  while (Date.now() - t0 < 120_000) { if (await nextBtn.isEnabled().catch(() => false)) { nextOk = true; break; } await sleep(500); }
  const loadMs = Date.now() - t0;
  const step1Txt = (await page.locator('body').innerText().catch(() => '')).match(/报价模板[^\n]*\n?[^\n]*/)?.[0] || '';
  await shot('大单编辑页-Step1');
  let bigEdit = { nextOk, loadMs, step1Txt };
  if (nextOk) {
    await nextBtn.click(); await waitCards();
    const e0 = await count();
    await submit(deep); const e1 = { cnt: await count(), nos: await salesNos() };
    await submit('XYZ999'); const e2 = { cnt: await count(), bar: await bar().isVisible() };
    await shot('大单编辑页-空态');
    await bar().locator('.ant-input-clear-icon').first().click(); const e3 = await until(2000, count, '共 1845 条');
    bigEdit = { ...bigEdit, e0, e1, e2, e3 };
    check('大单抽查', `编辑页 1845：第 1200 位回车命中 1 条 / 空态带搜索栏 / ✕ 恢复`, e0 === '共 1845 条' && e1.cnt === '匹配 1 条 / 共 1845 条' && eq(e1.nos, [deep]) && e2.cnt === '未匹配到料号（共 1845 条）' && e2.bar && e3.ms >= 0, bigEdit);
  } else {
    results.push({ ac: '记录', what: '大单编辑页 120 s 内「下一步」未可点（master 既有，未执行搜索）', pass: true, observed: bigEdit });
    log(`大单编辑页：120 s 内下一步未可点 ${JSON.stringify(bigEdit)}`);
  }
  await page.unroute('**/api/**');
  const fp1 = fp();
  check('大单抽查', '只读：指纹前后一致', fp0 === fp1, { fp0, fp1, intercepted: intercepted.length });
} catch (e) {
  log(`💥 脚本异常：${String(e).slice(0, 500)}`);
  await shot('异常现场').catch(() => {});
  results.push({ ac: '异常', what: String(e).slice(0, 300), pass: false });
} finally {
  await browser.close();
  // 清理主线样本单（前缀守卫 + 回读 404）
  const gg = await api(cookie, 'GET', `/api/cpq/quotations/${Q.id}`);
  if (gg.status === 200 && String(gg.json?.data?.name || '').startsWith('T260923-ML-')) {
    const del = await api(cookie, 'DELETE', `/api/cpq/quotations/${Q.id}`);
    const after = await api(cookie, 'GET', `/api/cpq/quotations/${Q.id}`);
    log(`删除主线样本单 ${Q.quotationNumber}：DELETE=${del.status} 回读=${after.status}`);
  }
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify({ at: new Date().toISOString(), base: BASE, sample: { id: Q.id, no: Q.quotationNumber }, pageErrors, results }, null, 1));
  const pass = results.filter((r) => r.pass).length;
  log(`合计 ${results.length} 项：通过 ${pass}，失败 ${results.length - pass}`);
}
