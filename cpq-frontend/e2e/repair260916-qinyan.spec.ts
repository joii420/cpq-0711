/**
 * repair-260916 · 主线亲验（CLAUDE.md §4.5 步骤 4）。
 * 环境：临时前端 5295 → 临时后端 8295 → 一次性库 cpq_db_rp0916d（真实数据，克隆自开发库）。
 * 走用户视角完整路径，证据落 证据/亲验/。
 *
 * A：组件管理改公式（AC-1 / AC-2 / AC-4 / AC-13② 真实数据 + AC-11⑤ 第 1 步）
 * B：模板新建草稿并发布（AC-11⑤ 第 2 步）
 * C：新建报价单读 H85「材料成本」（AC-11⑤ 第 3 步）
 */
import { test, expect, type Page, type Locator } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import {
  PALETTE, readEditor, drawerLoc, editorOf, leftCards, cardOf, chip, clearEditor,
  formulaRows, formulaListExpr, messageTexts,
} from './repair260916-subtotal-suffix.helpers';

const API = process.env.PW_BACKEND_URL || 'http://localhost:8295';
const OUT = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '亲验');
fs.mkdirSync(OUT, { recursive: true });

const COMP_WULIAO = 'b3445979-e3d6-4cdb-ab2d-53758b5f2eb2';   // COMP-0002「物料」
const COMP_JGF = '4db28822-85c6-4522-ac62-61ef2393a99c';      // COMP-0004「来料固定加工费」
const DIR_NAME = '施耐德成环检测';
const FORMULA_NAME_HINT = '来料固定加工费.加工费';             // 目标公式（H85 材料成本）里的这一项
const TEXT_BEFORE = '[材料毛重] * (SUM([材料成本.组成含量（%）] / 100 * [材料成本.税后单价]) * (1 + [来料损耗率] / 100) + [来料固定加工费.加工费(小计)] / [产品.税率(小计)])';
const TEXT_AFTER = '[材料毛重] * (SUM([材料成本.组成含量（%）] / 100 * [材料成本.税后单价]) * (1 + [来料损耗率] / 100) + [来料固定加工费.加工费] / [产品.税率(小计)])';
const PREFIX = '[材料毛重] * (SUM([材料成本.组成含量（%）] / 100 * [材料成本.税后单价]) * (1 + [来料损耗率] / 100) + ';
const SUFFIX = ' / [产品.税率(小计)])';

const note = (s: string) => { console.log(`[亲验] ${s}`); fs.appendFileSync(path.join(OUT, '亲验-运行日志.txt'), `${new Date().toISOString()} ${s}\n`); };
const w = (n: string, c: unknown) => fs.writeFileSync(path.join(OUT, n), typeof c === 'string' ? c : JSON.stringify(c, null, 2), 'utf8');
const shot = async (t: Page | Locator, n: string) => { await t.screenshot({ path: path.join(OUT, `${n}.png`), ...(('goto' in t) ? { fullPage: true } : {}) }); };

let COOKIE = '';
async function apiLogin() {
  const r = await fetch(`${API}/api/cpq/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'Admin@2026' }),
  });
  const setCookie = r.headers.get('set-cookie') ?? '';
  const m = setCookie.match(/CPQ_SESSION=([^;]+)/);
  expect(r.ok && !!m, `接口登录失败：${r.status}`).toBeTruthy();
  COOKIE = `CPQ_SESSION=${m![1]}`;
}
async function api(p: string) {
  const r = await fetch(`${API}${p}`, { headers: { Cookie: COOKIE } });
  return { status: r.status, json: await r.json().catch(() => null) as any };
}

async function uiLogin(page: Page) {
  const user = page.locator('input[placeholder="用户名或邮箱"]');
  await page.goto('/login');
  await expect(user).toBeVisible({ timeout: 60_000 });
  await user.fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 60_000 });
  if (page.url().includes('/change-password')) await page.goto('/dashboard');
}

async function openWuliao(page: Page) {
  await page.goto('/components');
  await page.waitForLoadState('networkidle').catch(() => {});
  const search = page.getByPlaceholder('🔍 搜索组件名 / 编码');
  if (!(await search.isVisible({ timeout: 15_000 }).catch(() => false))) {
    note(`openWuliao：搜索框未出现，url=${page.url()}，重载一次`);
    await page.reload();
    await page.waitForLoadState('networkidle').catch(() => {});
  }
  if (!(await search.isVisible({ timeout: 20_000 }).catch(() => false))) {
    await shot(page, `诊断-组件页未就绪-${Date.now()}`);
    w('诊断-组件页正文.txt', (await page.locator('body').innerText().catch(() => '(读不到)')).slice(0, 3000));
    note(`openWuliao：仍未出现，url=${page.url()}`);
  }
  await expect(search).toBeVisible({ timeout: 30_000 });
  await search.fill('COMP-0002');
  await page.waitForTimeout(1500);
  const dirs = page.locator('.cmm-dir');
  let hit = false;
  const deadline = Date.now() + 20_000;
  while (!hit && Date.now() < deadline) {
    for (let i = 0; i < await dirs.count(); i++) {
      const d = dirs.nth(i);
      const name = ((await d.locator('.cmm-dir-name').first().innerText().catch(() => '')) || '').replace(/^📁\s*/, '').trim();
      if (name === DIR_NAME) {
        if (!(await d.evaluate((el) => el.classList.contains('open')))) { await d.locator('.cmm-dir-head').first().click(); await page.waitForTimeout(500); }
        hit = true; break;
      }
    }
    if (!hit) await page.waitForTimeout(500);
  }
  expect(hit, `组件页应看到目录「${DIR_NAME}」`).toBe(true);
  const card = page.locator('.cmm-card').filter({ hasText: 'COMP-0002' }).first();
  await expect(card, '应看到 COMP-0002 卡片').toBeVisible({ timeout: 20_000 });
  await card.click();
  await page.waitForTimeout(1200);
  const tab = page.getByRole('tab', { name: '公式', exact: true });
  if (await tab.count()) { await tab.first().click(); await page.waitForTimeout(800); }
}

/** 公式列表里目标公式所在行下标（按「表达式」列文字含 hint 定位）。 */
async function findRowIdx(page: Page, hint: string) {
  const n = await formulaRows(page).count();
  expect(n, '公式列表应有行').toBeGreaterThan(0);
  for (let i = 0; i < n; i++) {
    const t = await formulaListExpr(page, i);
    if (t && t.includes(hint)) return { idx: i, text: t };
  }
  throw new Error(`公式列表里找不到含「${hint}」的行（共 ${n} 行）`);
}

test.describe.configure({ mode: 'serial' });

test('亲验 A · 组件公式：把整列小计改成按料号取本行值（AC-1/2/4/13② + AC-11⑤ 第1步）', async ({ page }) => {
  await apiLogin();
  const before = await api(`/api/cpq/components/${COMP_WULIAO}`);
  expect(before.status).toBe(200);
  w('A0-改前-COMP-0002.json', before.json);

  await uiLogin(page);
  await openWuliao(page);

  // ① 列表「表达式」列：存量公式回显带 (小计)（AC-4 / AC-13② 真实数据）
  let { idx, text } = await findRowIdx(page, FORMULA_NAME_HINT);
  if (text === TEXT_AFTER) {
    // 本脚本可重复运行：上一轮已改过 → 先手输还原成带 (小计) 的原写法（顺带验证手输 (小计) 能存下）
    note('A① 目标公式已是改后写法，先还原成带 (小计) 的原写法');
    await formulaRows(page).nth(idx).getByRole('button', { name: '配置' }).click();
    const d0 = drawerLoc(page);
    await expect(d0).toBeVisible({ timeout: 15_000 });
    await expect.poll(() => leftCards(d0).count(), { timeout: 20_000 }).toBeGreaterThan(0);
    await clearEditor(page, editorOf(d0));
    await editorOf(d0).click();
    await page.keyboard.insertText(TEXT_BEFORE);
    await page.waitForTimeout(600);
    await d0.locator('button.ant-btn-primary').filter({ hasText: /保\s*存/ }).first().click();
    await expect(d0).toBeHidden({ timeout: 15_000 });
    await page.locator('button').filter({ hasText: /^\s*保\s*存\s*$/ }).filter({ visible: true }).first().click();
    await page.waitForTimeout(2500);
    await openWuliao(page);
    ({ idx, text } = await findRowIdx(page, FORMULA_NAME_HINT));
    note(`A① 还原后列表文字=「${text}」`);
  }
  note(`A① 目标公式在第 ${idx} 行，列表文字=「${text}」`);
  expect(text, 'AC-4/AC-13②：列表应逐字显示带 (小计) 的写法').toBe(TEXT_BEFORE);
  await shot(page, 'A1-公式列表-改前');

  // ② 抽屉：小计块黄色（AC-2 / AC-10 真实数据）
  await formulaRows(page).nth(idx).getByRole('button', { name: '配置' }).click();
  const drawer = drawerLoc(page);
  await expect(drawer).toBeVisible({ timeout: 15_000 });
  await expect.poll(() => leftCards(drawer).count(), { timeout: 20_000 }).toBeGreaterThan(0);
  const ed = editorOf(drawer);
  const r0 = await readEditor(ed);
  w('A2-改前-公式框块.json', r0.blocks);
  const sub = r0.blocks.find((b) => b.display.includes('加工费(小计)'));
  expect(sub, 'AC-2：公式框里应有「加工费(小计)」块').toBeTruthy();
  expect(sub!.bg, 'AC-2：小计块应为原型黄色').toBe(PALETTE.yellow.bg);
  note(`A② 小计块 display=「${sub!.display}」 bg=${sub!.bg} border=${sub!.border}`);
  await shot(drawer, 'A2-抽屉-改前');

  // ③ 改写：清空 → 输前半段 → 点「来料固定加工费」卡片「明细」组的「加工费」→ 输后半段（AC-1）
  await clearEditor(page, ed);
  await ed.click();
  await page.keyboard.insertText(PREFIX);
  await page.waitForTimeout(300);
  const feeCard = cardOf(drawer, '来料固定加工费');
  await expect(feeCard, '左栏应有「来料固定加工费」卡片').toBeVisible({ timeout: 10_000 });
  const detailChip = chip(feeCard, '加工费');
  await expect(detailChip, '「明细」组应有「加工费」芯片').toBeVisible({ timeout: 10_000 });
  await detailChip.click();
  await page.waitForTimeout(400);
  await ed.click();
  await page.keyboard.press('Control+End');
  await page.keyboard.insertText(SUFFIX);
  await page.waitForTimeout(500);
  const r1 = await readEditor(ed);
  w('A3-改后-公式框块.json', r1.blocks);
  const det = r1.blocks.find((b) => /加工费$/.test(b.display) && b.display.includes('来料固定加工费'));
  expect(det, 'AC-1：公式框里应有不带后缀的「来料固定加工费·加工费」块').toBeTruthy();
  expect(det!.bg, 'AC-1：明细块应为原型蓝色').toBe(PALETTE.blue.bg);
  note(`A③ 明细块 display=「${det!.display}」 raw=「${det!.raw}」 bg=${det!.bg}`);
  await shot(drawer, 'A3-抽屉-改后');

  // ④ 保存抽屉 + 保存组件
  await drawer.locator('button.ant-btn-primary').filter({ hasText: /保\s*存/ }).first().click();
  await page.waitForTimeout(1200);
  note(`A④ 抽屉保存后提示=${JSON.stringify(await messageTexts(page))}`);
  await expect(drawer, '抽屉应关闭').toBeHidden({ timeout: 15_000 });
  const saveBtn = page.locator('button').filter({ hasText: /^\s*保\s*存\s*$/ }).filter({ visible: true }).first();
  await expect(saveBtn, '组件页应有「保存」按钮').toBeVisible({ timeout: 10_000 });
  await saveBtn.click();
  await page.waitForTimeout(2500);
  note(`A④ 组件保存后提示=${JSON.stringify(await messageTexts(page))}`);

  // ⑤ 落库校验（AC-11⑤②：该项应为 cross_tab_ref / NONE）
  const after = await api(`/api/cpq/components/${COMP_WULIAO}`);
  w('A5-改后-COMP-0002.json', after.json);
  const fml = (after.json?.data?.formulas ?? []).find((f: any) => f.name === '非银点类材料成本公式');
  expect(fml, '应能在库里找到「非银点类材料成本公式」').toBeTruthy();
  const tokensOf = (f: any) => { const o: any[] = []; (function walk(v: any) { if (Array.isArray(v)) v.forEach(walk); else if (v && typeof v === 'object') { if (v.type) o.push(v); Object.values(v).forEach(walk); } })(f.expression); return o; };
  const feeTok = tokensOf(fml).find((t: any) => t.source === COMP_JGF || (t.type === 'component_subtotal' && t.component_code === 'COMP-0004'));
  // 无涟漪：其他引用同一列小计的公式不受影响
  const others = (after.json?.data?.formulas ?? []).filter((f: any) => ['回收成本', '铆钉额外费用', '零件材料成本'].includes(f.name));
  const otherKinds = others.map((f: any) => ({ name: f.name, kinds: tokensOf(f).filter((t: any) => t.component_code === 'COMP-0004' || t.source === COMP_JGF).map((t: any) => t.type) }));
  w('A5-其他公式-加工费引用类型.json', otherKinds);
  note(`A⑤ 其他公式的加工费引用=${JSON.stringify(otherKinds)}`);
  expect(otherKinds.every((o: any) => o.kinds.length > 0 && o.kinds.every((k: string) => k === 'component_subtotal')),
    '无涟漪：其他三条公式对加工费的引用应仍是整列小计').toBe(true);
  w('A5-加工费token.json', feeTok ?? null);
  note(`A⑤ 加工费 token=${JSON.stringify(feeTok)}`);
  expect(feeTok?.type, 'AC-11⑤②：应存为按行匹配的跨页签引用').toBe('cross_tab_ref');
  expect(feeTok?.agg, 'AC-11⑤②：聚合应为 NONE').toBe('NONE');
  expect(String(feeTok?.source ?? ''), '来源应为来料固定加工费组件').toContain(COMP_JGF);

  // ⑥ 重新进页面，列表文字应为不带后缀的写法（AC-4）
  await openWuliao(page);
  const again = await findRowIdx(page, FORMULA_NAME_HINT);
  note(`A⑥ 改后列表文字=「${again.text}」`);
  expect(again.text, 'AC-4：改后列表应逐字为不带 (小计) 的写法').toBe(TEXT_AFTER);
  await shot(page, 'A6-公式列表-改后');
});

test('亲验 B · 模板：从 v1.9 创建新草稿并发布（AC-11⑤ 第2步）', async ({ page }) => {
  await apiLogin();
  await uiLogin(page);
  const TPL_V19 = 'eb761e77-9fda-4000-88cd-3f3e96f2d91f'; // 施耐德5.4模板 v1.9（一次性库实查）
  await page.goto(`/templates/${TPL_V19}`);
  await page.waitForTimeout(5000);
  await shot(page, 'B1-模板配置页-v1.9');

  const draftBtn = page.locator('button:visible').filter({ hasText: /创建新草稿/ }).first();
  await expect(draftBtn, '模板配置页应有「创建新草稿」按钮').toBeVisible({ timeout: 20_000 });
  await draftBtn.click();
  await page.waitForTimeout(3000);
  // 可能弹确认框
  const ok = page.locator('.ant-modal-confirm-btns button.ant-btn-primary, .ant-modal-footer button.ant-btn-primary').filter({ visible: true }).first();
  if (await ok.count()) { note(`B 创建草稿确认框按钮=${await ok.innerText()}`); await ok.click(); await page.waitForTimeout(4000); }
  note(`B 创建草稿后 url=${page.url()} 提示=${JSON.stringify(await messageTexts(page))}`);
  await shot(page, 'B2-创建草稿后');
  w('B2-草稿页按钮.txt', (await page.locator('button:visible').allInnerTexts()).join('\n'));

  const pub = page.locator('button:visible').filter({ hasText: /^\s*发\s*布\s*$|发布模板/ }).first();
  await expect(pub, '草稿页应有「发布」按钮').toBeVisible({ timeout: 20_000 });
  await pub.click();
  await page.waitForTimeout(2500);
  const ok2 = page.locator('.ant-modal-confirm-btns button.ant-btn-primary, .ant-modal-footer button.ant-btn-primary').filter({ visible: true }).first();
  if (await ok2.count()) { note(`B 发布确认框按钮=${await ok2.innerText()}`); await ok2.click(); await page.waitForTimeout(4000); }
  note(`B 发布后提示=${JSON.stringify(await messageTexts(page))}`);
  await shot(page, 'B3-发布后');

  // 落库校验：新版本已发布，且其组件快照里的公式已是按行匹配
  const newId = (page.url().match(/templates\/([0-9a-f-]{36})/) || [])[1];
  expect(newId, '发布后应能从 URL 拿到新模板 id').toBeTruthy();
  const t = await api(`/api/cpq/templates/${newId}`);
  const td = t.json?.data ?? {};
  w('B4-新模板.json', { id: newId, version: td.version, status: td.status, name: td.name });
  note(`B 新模板 id=${newId} version=${td.version} status=${td.status}`);
  expect(td.status, '新版本应为已发布').toBe('PUBLISHED');
  expect(String(td.version), '新版本号应不同于 v1.9').not.toBe('v1.9');
  process.env.RP_NEW_TPL = String(newId);
  fs.writeFileSync(path.join(OUT, 'B4-新模板id.txt'), String(newId), 'utf8');
});

/** 当前可见的下拉弹层（antd 会把旧弹层留在 DOM，必须限定 visible 的那个）。 */
function liveDropdown(page: Page) {
  return page.locator('.ant-select-dropdown:visible').last();
}
async function selectByLabel(page: Page, label: string, search: string, optionText?: string) {
  const item = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: label }) }).first();
  await item.locator('.ant-select').first().click();
  await page.waitForTimeout(500);
  if (search) { await page.keyboard.type(search, { delay: 50 }); await page.waitForTimeout(1000); }
  const dd = liveDropdown(page);
  await dd.locator('.ant-select-item-option').filter({ hasText: optionText || search }).first().click();
  await page.waitForTimeout(500);
}

test('亲验 C · 新建报价单：H85「材料成本」按料号取本行加工费（AC-11⑤ 第3步）', async ({ page }) => {
  await apiLogin();
  await uiLogin(page);
  const name = `RP0916-亲验-${Date.now()}`;

  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');
  await selectByLabel(page, '客户', '正泰');
  await page.waitForTimeout(800);
  await page.locator('input[placeholder*="报价单名称"]').first().fill(name);
  // 产品分类：选定客户后由系统自动带出且不可改（探测 260917：其下拉 0 项）——已是目标值就跳过
  const catItem = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: '产品分类' }) }).first();
  const catText = (await catItem.innerText().catch(() => '')).replace(/\s+/g, ' ');
  note(`C 产品分类当前=「${catText}」`);
  if (!catText.includes('默认分类')) await selectByLabel(page, '产品分类', '默认分类');
  await page.waitForTimeout(400);
  // 报价模板：选刚发布的最新版
  const tplItem = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: '报价模板' }) }).first();
  await tplItem.locator('.ant-select').first().click();
  await page.waitForTimeout(800);
  await page.keyboard.type('施耐德5.4', { delay: 50 });
  await page.waitForTimeout(1500);
  const dd = liveDropdown(page);
  const opts = await dd.locator('.ant-select-item-option').allInnerTexts();
  w('C1-报价模板候选.txt', opts.join('\n'));
  const verOf = (s: string) => { const m = s.match(/v(\d+)\.(\d+)/); return m ? Number(m[1]) * 1000 + Number(m[2]) : -1; };
  const want = opts.filter((o) => o.includes('施耐德5.4模板')).sort((a, b) => verOf(b) - verOf(a))[0];
  expect(want, '应有「施耐德5.4模板」候选').toBeTruthy();
  note(`C 报价模板选最高版=「${want}」（候选 ${opts.length} 个）`);
  await dd.locator('.ant-select-item-option').filter({ hasText: want }).first().click();
  await page.waitForTimeout(700);
  await shot(page, 'C1-step1-填好');

  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next, 'Step1「下一步」应可点').toBeEnabled({ timeout: 60_000 });
  await next.click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  note(`C 进入 Step2，url=${page.url()}`);

  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(900);
  const menu = await page.locator('.ant-dropdown-menu-item:visible, .ant-dropdown:visible li').allInnerTexts();
  w('C2-添加产品入口.txt', menu.join('\n'));
  note(`C 添加产品入口=${JSON.stringify(menu)}`);
  await shot(page, 'C2-添加产品下拉');

  await page.locator('.ant-dropdown:visible').last().getByText('从已有产品添加', { exact: false }).first().click();
  await page.waitForTimeout(2500);
  const dr = page.locator('.ant-drawer').last();
  await expect(dr, '应打开「从已有产品添加」抽屉').toBeVisible({ timeout: 20_000 });
  w('C3-抽屉正文.txt', (await dr.innerText()).slice(0, 3000));
  w('C3-抽屉按钮.txt', (await dr.locator('button:visible').allInnerTexts()).join('\n'));
  await shot(dr, 'C3-添加产品抽屉');

  // 搜料号 S3120011203
  const dsearch = dr.locator('input[placeholder*="搜索"], input[type="search"], input[placeholder*="料号"]').first();
  if (await dsearch.count()) { await dsearch.fill('S3120011203'); await page.keyboard.press('Enter'); await page.waitForTimeout(2500); }
  w('C3-搜索后正文.txt', (await dr.innerText()).slice(0, 3000));
  await shot(dr, 'C3-抽屉-搜索后');
  const row = dr.locator('.ant-table-tbody tr').filter({ hasText: 'S3120011203' }).first();
  await expect(row, '抽屉里应能搜到产品 S3120011203').toBeVisible({ timeout: 20_000 });
  // antd 勾选框：真实 input 透明且被 wrapper 遮挡，必须点 wrapper（否则「已选 0 项」）
  const wrap = row.locator('.ant-checkbox-wrapper, .ant-radio-wrapper, .ant-checkbox, .ant-radio').first();
  if (await wrap.count()) await wrap.click(); else await row.click();
  await page.waitForTimeout(800);
  const selTxt = (await dr.innerText()).match(/已选\s*(\d+)\s*项/)?.[0] ?? '(未找到「已选 N 项」)';
  note(`C 抽屉勾选状态=${selTxt}`);
  expect(selTxt, '勾选后应显示已选 1 项').toContain('1');
  await shot(dr, 'C3-抽屉-已选');
  const confirm = dr.locator('button:visible').filter({ hasText: /加入报价单/ }).last();
  await expect(confirm, '抽屉应有确认按钮').toBeVisible({ timeout: 10_000 });
  await confirm.click();
  await page.waitForTimeout(6000);
  await page.waitForLoadState('networkidle').catch(() => {});
  note(`C 加产品后提示=${JSON.stringify(await messageTexts(page))}`);
  await shot(page, 'C4-加产品后');
  const qid = (page.url().match(/quotations\/([0-9a-f-]{36})/) || [])[1];
  fs.writeFileSync(path.join(OUT, 'C-报价单id.txt'), `${qid}\n${name}\n`, 'utf8');
  note(`C 报价单 id=${qid} 名称=${name}`);
  expect(qid, '应能拿到报价单 id').toBeTruthy();
  // 产品卡片应真的出现（防「点了确认但没加进去」的假通过）
  await expect(page.locator('.qt-product-card').first(), '加产品后应渲染出产品卡片').toBeVisible({ timeout: 60_000 });
  const tabNames = await page.locator('.qt-product-card').first().locator('button.qt-tab-btn').allInnerTexts();
  note(`C 产品卡片页签=${JSON.stringify(tabNames)}`);
  w('C4-页签清单.json', tabNames);

  // 加产品只存在于页面上，必须先「保存草稿」才会落库并由后端物化取数行（探测 260917：不存则库里 0 行产品）
  const saveDraft = async (why: string) => {
    const b = page.locator('button:visible').filter({ hasText: /保存草稿/ }).first();
    await expect(b, '第二步应有「保存草稿」按钮').toBeVisible({ timeout: 20_000 });
    await b.click();
    await page.waitForTimeout(9000);
    note(`C 保存草稿(${why}) 提示=${JSON.stringify(await messageTexts(page))}`);
  };
  await saveDraft('加产品后');
  const li = await api(`/api/cpq/quotations/${qid}`);
  const items = li.json?.data?.lineItems ?? li.json?.data?.items ?? [];
  note(`C 保存后产品行数=${Array.isArray(items) ? items.length : '(结构未知)'}`);
  expect(Array.isArray(items) && items.length > 0, '保存草稿后应有产品行落库').toBe(true);

  // ── 读数：产品页签填税率 1.13 → 逐页签取 H85(料号 00144) 的实际值
  const card = () => page.locator('.qt-product-card').first();
  const cardTab = async (n: string) => {
    const t = card().locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${n}\\s*$`) }).first();
    await expect(t, `页签「${n}」应存在`).toBeVisible({ timeout: 30_000 });
    await t.click();
    await page.waitForTimeout(4000);
  };
  const readTable = async () => card().locator('table.qt-cost-table').locator('visible=true').first().evaluate((tbl) => ({
    headers: Array.from(tbl.querySelectorAll('thead th')).map((th) => (th as HTMLElement).innerText.replace(/\s+/g, ' ').trim()),
    rows: Array.from(tbl.querySelectorAll('tbody tr')).map((tr) => Array.from(tr.querySelectorAll('td')).map((td) => {
      const inp = td.querySelector('input, textarea') as HTMLInputElement | null;
      return inp ? (inp.value ?? '').trim() : (td as HTMLElement).innerText.replace(/\s+/g, ' ').trim();
    })),
  }));

  // ① 产品页签：税率填 1.13
  await cardTab('产品');
  let t = await readTable();
  w('C5-产品页签-填前.json', t);
  const iRate = t.headers.findIndex((h) => h.includes('税率'));
  expect(iRate, '产品页签应有「税率」列').toBeGreaterThanOrEqual(0);
  const rateCell = card().locator('table.qt-cost-table').locator('visible=true').first()
    .locator('tbody tr').first().locator('td').nth(iRate).locator('input').first();
  await expect(rateCell, '税率应是可输入单元格').toBeVisible({ timeout: 15_000 });
  await rateCell.fill('1.13');
  await rateCell.blur();
  await page.waitForTimeout(6000);
  await saveDraft('填税率后');
  await page.waitForTimeout(3000);
  t = await readTable();
  w('C5-产品页签-填后.json', t);
  note(`C 产品页签税率列=${t.headers[iRate]} 值=${t.rows[0]?.[iRate]}`);
  await shot(page, 'C5-产品页签');

  // ② 各页签取数
  const grab: Record<string, any> = {};
  for (const tab of ['物料', '材料成本', '来料固定加工费', '来料其他费用']) {
    await cardTab(tab);
    const tt = await readTable();
    grab[tab] = tt;
    w(`C6-${tab}.json`, tt);
    await shot(page, `C6-${tab}`);
    note(`C 页签「${tab}」列=${JSON.stringify(tt.headers)} 行数=${tt.rows.length}`);
  }
  w('C6-全部页签.json', grab);

  // ③ 关键值：物料页签 H85(00144) 行的「材料成本」
  const wl = grab['物料'];
  const iPart = wl.headers.findIndex((h: string) => h === '料号');
  const iCost = wl.headers.findIndex((h: string) => h.includes('材料成本'));
  expect(iPart >= 0 && iCost >= 0, `物料页签应有 料号/材料成本 列：${JSON.stringify(wl.headers)}`).toBe(true);
  const h85 = wl.rows.filter((r: string[]) => r[iPart] === '00144');
  expect(h85.length, '物料页签应有料号 00144 的行').toBeGreaterThan(0);
  const cost = h85[0][iCost];
  note(`C ★ 物料页签 00144(H85)「材料成本」= ${cost}`);
  w('C7-H85材料成本.json', { quotationId: qid, name, 料号: '00144', 材料成本: cost, 行: h85[0], 表头: wl.headers });
  expect(cost && !['—', '-', '', '加载中…'].includes(cost), '材料成本应有非空数值').toBe(true);
});
