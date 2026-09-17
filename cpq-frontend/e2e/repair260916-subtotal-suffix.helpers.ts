/**
 * repair-260916（小计列无法选为字段引用）· 测试分片 S-B（私有写片）共用助手。
 *
 * 纪律（test.md §3 / 派工 prompt c、d 段）：
 *  - 用例只从 问题说明.md ⑥ 的 AC 原文 + 原型图/公式抽屉.html 派生；🚫 未读 cpq-frontend/src/**、cpq-backend/src/main/**。
 *  - 造数：经共享后端 8081 的业务接口，在开发库建目录 `RP0916B-E2E`，组件按 6.1 表建，全部挂在该目录内。
 *  - 只读/只断言本片自建对象（按 id）；🚫 不读其他组件；🚫 无全局计数断言。
 *  - 清理：afterAll 按 id 调 DELETE 业务接口（组件 → 目录），🚫 不按名称批量删、🚫 不直连 SQL。
 *  - 证据：一律写到 任务目录/证据/测试/S-B/（Playwright 的 test-results 每轮会被清空，不算证据）。
 */
import { expect, type Locator, type Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

// 本机 shell 带 http_proxy，访问本机一律绕开代理
process.env.NO_PROXY = 'localhost,127.0.0.1';
process.env.no_proxy = 'localhost,127.0.0.1';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';
export const TASK_DIR = path.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260801-页签连表公式配置优化', 'repair-260916-小计列无法选为字段引用',
);
export const EVID = path.join(TASK_DIR, '证据', '测试', 'S-B');

/** 本片专属目录名（主线分配，不许改）。 */
export const DIR_NAME = 'RP0916B-E2E';
export const N = {
  host: 'RP0916宿主',
  fee: 'RP0916加工费',
  proc: 'RP0916工序',
  sub: 'RP0916小计',
  excel: 'RP0916Excel',
} as const;

// ───────────────────────────────────────────── 原型色值（原型图/公式抽屉.html .blk.*）
export const PALETTE: Record<string, { bg: string; border: string }> = {
  blue: { bg: 'rgb(230, 244, 255)', border: 'rgb(145, 202, 255)' },     // #e6f4ff / #91caff
  yellow: { bg: 'rgb(255, 251, 230)', border: 'rgb(255, 213, 145)' },   // #fffbe6 / #ffd591
  green: { bg: 'rgb(246, 255, 237)', border: 'rgb(183, 235, 143)' },    // #f6ffed / #b7eb8f
  purple: { bg: 'rgb(249, 240, 255)', border: 'rgb(211, 173, 247)' },   // #f9f0ff / #d3adf7
  red: { bg: 'rgb(255, 241, 240)', border: 'rgb(255, 163, 158)' },      // #fff1f0 / #ffa39e
};

// ───────────────────────────────────────────── 证据
export function evidPath(name: string) {
  fs.mkdirSync(EVID, { recursive: true });
  return path.join(EVID, name);
}
export function writeEvidence(name: string, content: unknown) {
  const p = evidPath(name);
  fs.writeFileSync(p, typeof content === 'string' ? content : JSON.stringify(content, null, 2) + '\n', 'utf8');
  console.log(`[S-B][evidence] ${p}`);
  return p;
}
export async function shot(target: Page | Locator, name: string) {
  const p = evidPath(name.endsWith('.png') ? name : `${name}.png`);
  await target.screenshot({ path: p });
  console.log(`[S-B][shot] ${p}`);
  return p;
}

// ───────────────────────────────────────────── API
let cookie = '';
export async function apiLogin() {
  const r = await fetch(`${BACKEND_URL}/api/cpq/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'Admin@2026' }),
  });
  const setCookie = r.headers.get('set-cookie') ?? '';
  const m = setCookie.match(/CPQ_SESSION=([^;]+)/);
  expect(r.ok && !!m, `API 登录失败：${r.status} set-cookie=${setCookie.slice(0, 80)}`).toBeTruthy();
  cookie = `CPQ_SESSION=${m![1]}`;
}

export async function api(p: string, init: { method?: string; body?: unknown } = {}) {
  const r = await fetch(`${BACKEND_URL}${p}`, {
    method: init.method ?? 'GET',
    headers: { Cookie: cookie, 'Content-Type': 'application/json; charset=utf-8' },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  const text = await r.text(); // body 只能读一次：先读文本再解析
  let json: any = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON */ }
  return { status: r.status, ok: r.ok, text, json };
}

/** 读组件原始 JSON（AC 的存储断言口）。返回 { raw 文本, data }。 */
export async function getComponent(id: string) {
  const r = await api(`/api/cpq/components/${id}`);
  expect(r.status, `GET /components/${id} 应 200：${r.text.slice(0, 300)}`).toBe(200);
  const data = r.json?.data ?? r.json;
  expect(data?.id, `GET /components/${id} 返回体没有 data.id：${r.text.slice(0, 300)}`).toBe(id);
  return { raw: r.text, data };
}

// ───────────────────────────────────────────── 夹具（问题说明 6.1）
export interface Fx { id: string; code: string; name: string }
export const fx: { dirId: string; host?: Fx; fee?: Fx; proc?: Fx; sub?: Fx; excel?: Fx } = { dirId: '' };

function field(name: string, type: 'INPUT_TEXT' | 'INPUT_NUMBER' | 'FORMULA', sort: number, subtotal = false) {
  return {
    name, notes: '', content: '', is_amount: false, field_type: type,
    sort_order: sort, is_subtotal: subtotal,
  };
}

async function createComponent(name: string, componentType: 'NORMAL' | 'SUBTOTAL' | 'EXCEL'): Promise<Fx> {
  const r = await api('/api/cpq/components', {
    method: 'POST', body: { name, directoryId: fx.dirId, componentType },
  });
  const d = r.json?.data;
  if (d?.id) created.push({ id: d.id, code: d.code, name: d.name }); // 先登记再断言，防孤儿
  expect(r.ok, `建组件 ${name}(${componentType}) 失败：${r.status} ${r.text.slice(0, 300)}`).toBeTruthy();
  expect(d.componentType,
    `夹具 ${name} 的 componentType=${d.componentType}，不是 ${componentType} ⇒ 后续用例前提不成立，判【未验证】`)
    .toBe(componentType);
  console.log(`[S-B][fixture] 建 ${componentType} ${d.code} ${d.name} (${d.id})`);
  return { id: d.id, code: d.code, name: d.name };
}

async function putComponent(c: Fx, patch: { fields: any[]; rowKeyFields: string[] | null; componentType: string }) {
  const { data } = await getComponent(c.id);
  const body = {
    name: data.name, code: data.code, status: data.status ?? 'ACTIVE',
    componentType: patch.componentType,
    dataDriverPath: data.dataDriverPath ?? '',
    fields: patch.fields, formulas: data.formulas ?? [],
    rowKeyFields: patch.rowKeyFields,
    excelColumns: data.excelColumns ?? [],
  };
  const r = await api(`/api/cpq/components/${c.id}`, { method: 'PUT', body });
  expect(r.ok, `写组件 ${c.name} 字段失败：${r.status} ${r.text.slice(0, 300)}`).toBeTruthy();
  const back = (await getComponent(c.id)).data;
  // 前置守卫：字段 / 行键 / 小计勾选逐一照 6.1 表落库，否则后续断言建立在错误夹具上
  const got = (back.fields ?? []).map((f: any) => `${f.name}:${f.field_type}:${!!f.is_subtotal}`);
  const want = patch.fields.map((f) => `${f.name}:${f.field_type}:${!!f.is_subtotal}`);
  expect(got, `${c.name} 字段未按 6.1 落库`).toEqual(want);
  expect(back.rowKeyFields ?? null, `${c.name} 行键未按 6.1 落库`).toEqual(patch.rowKeyFields);
}

export const created: Fx[] = [];

const FIXTURE_FILE = 'fixture-ids.json';

/**
 * worker 重建防护（主线审核 260917 第 4 条，选「按 id 清理再建」，不用 serial）：
 * 上一轮留下的 fixture-ids.json 若未标 cleaned，先按其中 id 逐个 DELETE（仍存在才删），再删目录。
 */
async function purgeLeftovers() {
  const p = evidPath(FIXTURE_FILE);
  if (!fs.existsSync(p)) return;
  const prev = JSON.parse(fs.readFileSync(p, 'utf8'));
  if (prev.cleaned) return;
  for (const c of (prev.components ?? []) as Fx[]) {
    const g = await api(`/api/cpq/components/${c.id}`);
    if (g.status !== 200 || (g.json?.data?.id ?? '') !== c.id) continue;
    const d = await api(`/api/cpq/components/${c.id}`, { method: 'DELETE' });
    console.log(`[S-B][purge] 残留组件 ${c.code} ${c.name}(${c.id}) → DELETE ${d.status}`);
  }
  if (prev.dir?.id) {
    const d = await api(`/api/cpq/component-directories/${prev.dir.id}`, { method: 'DELETE' });
    console.log(`[S-B][purge] 残留目录 ${prev.dir.id} → DELETE ${d.status} ${d.text.slice(0, 120)}`);
  }
  fs.writeFileSync(p, JSON.stringify({ ...prev, cleaned: true, purgedAt: new Date().toISOString() }, null, 2) + '\n');
}

export async function setupFixtures() {
  await apiLogin();
  await purgeLeftovers();
  const d = await api('/api/cpq/component-directories', { method: 'POST', body: { name: DIR_NAME, parentId: null } });
  expect(d.ok, `建本片目录 ${DIR_NAME} 失败：${d.status} ${d.text.slice(0, 300)}`).toBeTruthy();
  fx.dirId = d.json.data.id;
  console.log(`[S-B][fixture] 目录 ${DIR_NAME} = ${fx.dirId}`);

  fx.host = await createComponent(N.host, 'NORMAL');
  await putComponent(fx.host, {
    componentType: 'NORMAL', rowKeyFields: ['销售料号', '料号'],
    fields: [field('销售料号', 'INPUT_TEXT', 0), field('料号', 'INPUT_TEXT', 1),
      field('数量', 'INPUT_NUMBER', 2), field('成本', 'FORMULA', 3, true)], // 6.1 修订：成本勾小计（让 AC-7① 有判别力）
  });
  fx.fee = await createComponent(N.fee, 'NORMAL');
  await putComponent(fx.fee, {
    componentType: 'NORMAL', rowKeyFields: ['销售料号', '料号'],
    fields: [field('销售料号', 'INPUT_TEXT', 0), field('料号', 'INPUT_TEXT', 1),
      field('加工费', 'INPUT_NUMBER', 2, true), field('备注数', 'INPUT_NUMBER', 3, false)],
  });
  fx.proc = await createComponent(N.proc, 'NORMAL');
  await putComponent(fx.proc, {
    componentType: 'NORMAL', rowKeyFields: ['工序'],
    fields: [field('工序', 'INPUT_TEXT', 0), field('工时', 'INPUT_NUMBER', 1)],
  });
  fx.sub = await createComponent(N.sub, 'SUBTOTAL');
  fx.excel = await createComponent(N.excel, 'EXCEL');

  // 前置守卫：本目录内恰好是本次造的 5 个（针对性断言，只看本片目录）
  const ls = await api(`/api/cpq/components?directoryId=${fx.dirId}`);
  const mine = (ls.json?.data ?? []).filter((c: any) => c.directoryId === fx.dirId).map((c: any) => c.id).sort();
  expect(mine, '本片目录内组件应恰为本次造的 5 个（若多出 = 目录串了，停下报主线）')
    .toEqual(created.map((c) => c.id).sort());
  // 回读核对：宿主确有小计列（AC-7① 的判别前提）
  const hostBack = (await getComponent(fx.host.id)).data;
  const hostSubs = (hostBack.fields ?? []).filter((f: any) => f.is_subtotal).map((f: any) => f.name);
  expect(hostSubs, '宿主应确有小计列「成本」（6.1 修订）').toEqual(['成本']);
  writeEvidence(FIXTURE_FILE, { dir: { id: fx.dirId, name: DIR_NAME }, components: created, cleaned: false });
}

export async function cleanupFixtures() {
  if (!cookie) return;
  let allGone = true;
  for (const c of [...created].reverse()) {
    const r = await api(`/api/cpq/components/${c.id}`, { method: 'DELETE' });
    const g = await api(`/api/cpq/components/${c.id}`);
    const gone = g.status !== 200 || (g.json?.data?.id ?? '') !== c.id;
    if (!gone) allGone = false;
    console.log(`[S-B][cleanup] DELETE 组件 ${c.code} ${c.name} → ${r.status}；复查 GET → ${g.status}（已删=${gone}）`);
  }
  if (fx.dirId) {
    const r = await api(`/api/cpq/component-directories/${fx.dirId}`, { method: 'DELETE' });
    console.log(`[S-B][cleanup] DELETE 目录 ${DIR_NAME}(${fx.dirId}) → ${r.status} ${r.text.slice(0, 120)}`);
  }
  writeEvidence(FIXTURE_FILE, { dir: { id: fx.dirId, name: DIR_NAME }, components: [...created], cleaned: allGone,
    cleanedAt: new Date().toISOString() });
  created.length = 0;
  fx.dirId = '';
}

// ───────────────────────────────────────────── 页面导航
/** 进组件管理 → 展开本片目录 → 点开组件卡片。 */
export async function openComponent(page: Page, c: Fx) {
  await page.goto('/components');
  const search = page.getByPlaceholder('🔍 搜索组件名 / 编码');
  await expect(search).toBeVisible({ timeout: 20_000 });
  await search.fill(c.code);
  await page.waitForTimeout(700);
  // 只展开本片目录（名字带「📁 」前缀，精确匹配；已展开的不再点）
  const dirs = page.locator('.cmm-dir');
  const n = await dirs.count();
  let found = false;
  for (let i = 0; i < n; i++) {
    const d = dirs.nth(i);
    const name = ((await d.locator('.cmm-dir-name').first().innerText().catch(() => '')) || '')
      .replace(/^📁\s*/, '').trim();
    if (name !== DIR_NAME) continue;
    found = true;
    if (!(await d.evaluate((el) => el.classList.contains('open')))) {
      await d.locator('.cmm-dir-head').first().click();
      await page.waitForTimeout(300);
    }
  }
  expect(found, `组件页没找到目录「${DIR_NAME}」（入口问题，判【未验证】）`).toBe(true);
  const card = page.locator('.cmm-card').filter({ hasText: c.code }).first();
  await expect(card, `应能看到组件卡片 ${c.code}`).toBeVisible({ timeout: 10_000 });
  await card.click();
  await page.waitForTimeout(800);
}

export async function gotoFormulaTab(page: Page) {
  await page.getByRole('tab', { name: '公式' }).click();
  await page.waitForTimeout(300);
}

/** 公式表的行（含「配置」按钮的行；字段表行在 DOM 中隐藏但仍在，不能直接数 tr）。 */
export function formulaRows(page: Page) {
  return page.locator('.ant-table-tbody tr')
    .filter({ has: page.getByRole('button', { name: '配置' }) })
    .filter({ visible: true });
}

export function drawerLoc(page: Page) {
  return page.locator('.ant-drawer').filter({ hasText: '配置页签连表公式' }).last();
}

/** 新增公式并打开其抽屉。 */
export async function addFormulaAndOpen(page: Page) {
  await gotoFormulaTab(page);
  const before = await formulaRows(page).count();
  await page.getByRole('button', { name: '添加公式' }).click();
  await expect.poll(() => formulaRows(page).count(), { message: '点「添加公式」后公式表应多一行' })
    .toBe(before + 1);
  await formulaRows(page).last().getByRole('button', { name: '配置' }).click();
  return waitDrawer(page);
}

/** 打开公式表第 idx 行（与 GET formulas 顺序一致）的抽屉。 */
export async function openFormulaAt(page: Page, idx: number) {
  await gotoFormulaTab(page);
  await expect.poll(() => formulaRows(page).count(), { message: '公式表行数不足' }).toBeGreaterThan(idx);
  await formulaRows(page).nth(idx).getByRole('button', { name: '配置' }).click();
  return waitDrawer(page);
}

async function waitDrawer(page: Page) {
  const drawer = drawerLoc(page);
  await expect(drawer).toBeVisible({ timeout: 10_000 });
  // 左栏页签卡来自异步 tab-defs，必须等到出现
  await expect.poll(() => leftCards(drawer).count(), { timeout: 15_000, message: '左栏页签卡片未加载' })
    .toBeGreaterThan(0);
  return drawer;
}

export function editorOf(drawer: Locator) {
  return drawer.locator('.tabjoin-formula-rich-input');
}

export function leftCol(drawer: Locator) {
  return drawer.locator('.ant-drawer-body > div').first().locator('> div').first();
}
export function leftCards(drawer: Locator) {
  return leftCol(drawer).locator('div[style*="border-radius: 8px"]');
}
/** 左栏某页签卡（按卡头名称精确过滤：名称后接空白/「[」/行键徽标）。 */
export function cardOf(drawer: Locator, tabName: string) {
  return leftCards(drawer).filter({ hasText: new RegExp(`(^|\\s)${tabName}(\\s|\\[|$)`) }).first();
}
/** 卡内精确文案的芯片。 */
export function chip(card: Locator, text: string) {
  return card.locator('.ant-tag').filter({ hasText: new RegExp(`^\\s*${escapeRe(text)}\\s*$`) }).first();
}
export function escapeRe(s: string) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

export async function clearEditor(page: Page, editor: Locator) {
  await editor.click();
  await page.keyboard.press('Control+A');
  await page.keyboard.press('Backspace');
  await page.waitForTimeout(200);
  expect((await readEditor(editor)).raw.trim(), '清空后公式框应为空').toBe('');
}

/** 光标移到公式框末尾。 */
export async function caretEnd(page: Page, editor: Locator) {
  await editor.click();
  await page.keyboard.press('Control+End');
  await page.waitForTimeout(100);
}

/** 手输一段文字（一次性 insertText，避免逐键触发补全）并核对确实进了公式框。 */
export async function typeExpr(page: Page, editor: Locator, expr: string) {
  await clearEditor(page, editor);
  await editor.click();
  await page.keyboard.insertText(expr);
  await page.waitForTimeout(400);
  const r = await readEditor(editor);
  console.log(`[S-B] 手输「${expr}」→ 公式框 raw=「${r.raw}」 display=「${r.display}」`);
  // 主线审核：公式框文字不作逐字判据（只看颜色与块数）；此处只守「确实输进去了」
  expect(noWs(r.display).length, '手输文字应进入公式框（前置守卫）').toBeGreaterThan(0);
  return r;
}

export function noWs(s: string) { return s.replace(/\s+/g, ''); }

export interface Block { display: string; raw: string; bg: string; border: string; color: string }
/**
 * 读公式框：按原型，块显示为「页签·列」「页签(总计)」「列」，插入文字为「[页签.列]」等。
 * 取法（均来自原型，不来自实现）：
 *  - 块 = 公式框内计算背景色落在原型 5 色之一的最外层元素；
 *  - 块的 raw：若自身文字已带方括号则原样，否则 `[` + 显示文字(首个「·」换成「.」) + `]`；
 *  - 块外文字原样拼接。
 */
export async function readEditor(editor: Locator): Promise<{ raw: string; display: string; blocks: Block[] }> {
  const palette = PALETTE;
  return editor.evaluate((root, pal) => {
    const bgToName: Record<string, string> = {};
    for (const [k, v] of Object.entries(pal as any)) bgToName[(v as any).bg] = k;
    const blocks: any[] = [];
    let raw = '';
    const walk = (node: Node) => {
      if (node.nodeType === Node.TEXT_NODE) { raw += node.textContent ?? ''; return; }
      if (node.nodeType !== Node.ELEMENT_NODE) return;
      const el = node as HTMLElement;
      if (el !== root) {
        const cs = getComputedStyle(el);
        const name = bgToName[cs.backgroundColor];
        if (name) {
          const display = (el.textContent ?? '').replace(/​/g, '').trim();
          const r = /^\[.*\]$/.test(display) ? display : `[${display.replace('·', '.')}]`;
          blocks.push({ display, raw: r, bg: cs.backgroundColor, border: cs.borderTopColor, color: name });
          raw += r;
          return;
        }
      }
      el.childNodes.forEach(walk);
    };
    walk(root);
    return { raw: raw.replace(/​/g, ''), display: (root.textContent ?? '').replace(/​/g, ''), blocks };
  }, palette);
}

export function expectBlock(b: Block | undefined, color: keyof typeof PALETTE, what: string) {
  expect(b, `${what}：公式框里应有这个块`).toBeTruthy();
  console.log(`[S-B] ${what}：块 display=「${b!.display}」 raw=「${b!.raw}」 bg=${b!.bg} border=${b!.border}`);
  expect(b!.bg, `${what}：块底色应为原型 ${color}`).toBe(PALETTE[color].bg);
  expect(b!.border, `${what}：块边框色应为原型 ${color}`).toBe(PALETTE[color].border);
}

/** 点芯片，返回本次插入的 raw 文字（点前清空公式框）。 */
export async function insertedByChip(page: Page, editor: Locator, c: Locator) {
  await clearEditor(page, editor);
  await expect(c).toBeVisible();
  await c.click();
  await page.waitForTimeout(300);
  return readEditor(editor);
}

/** 抽屉内「保存」（坑：两字按钮带空格，用 primary 类名）。 */
export async function drawerSave(page: Page, drawer: Locator) {
  const btn = drawer.locator('button.ant-btn-primary').filter({ hasText: /保\s*存/ }).first();
  await expect(btn).toBeEnabled();
  await btn.click();
  await page.waitForTimeout(800);
}

/** 当前可见的 antd 全局提示文字（逐条 trim）。 */
export async function messageTexts(page: Page) {
  return (await page.locator('.ant-message-notice').allInnerTexts()).map((s) => s.trim()).filter(Boolean);
}
export async function expectMessage(page: Page, text: string, what: string) {
  await expect.poll(() => messageTexts(page), { timeout: 8_000, message: `${what}：应弹出提示「${text}」` })
    .toContain(text);
  console.log(`[S-B] ${what}：提示=${JSON.stringify(await messageTexts(page))}`);
}

/** 页面级「保存」（抽屉外）。 */
export async function pageSave(page: Page) {
  const btn = page.locator('button:not(.ant-drawer button)')
    .filter({ hasText: /^\s*保\s*存\s*$/ }).filter({ visible: true }).first();
  await expect(btn, '组件页应有「保存」按钮').toBeVisible({ timeout: 10_000 });
  await btn.click();
  await page.waitForTimeout(1500);
}

/**
 * 抽屉保存成功后落库：抽屉应关闭；再点组件页「保存」；轮询 GET 直到公式数 = expectCount。
 * 返回最新组件 JSON。
 */
export async function persistFormulas(page: Page, c: Fx, drawer: Locator, expectCount: number) {
  await expect(drawer, '抽屉保存成功后应关闭').toBeHidden({ timeout: 8_000 });
  await pageSave(page);
  let last: any;
  await expect.poll(async () => {
    last = await getComponent(c.id);
    return (last.data.formulas ?? []).length;
  }, { timeout: 10_000, message: `${c.name} 公式应落库为 ${expectCount} 条` }).toBe(expectCount);
  return last as { raw: string; data: any };
}

/** 递归收集 token（含 targetExpr 等嵌套数组）。 */
export function allTokens(expr: any): any[] {
  const out: any[] = [];
  const walk = (v: any) => {
    if (Array.isArray(v)) v.forEach(walk);
    else if (v && typeof v === 'object') {
      if (typeof v.type === 'string') out.push(v);
      Object.values(v).forEach(walk);
    }
  };
  walk(expr);
  return out;
}
/** 顶层引用类 token（去掉运算符/括号/数字/函数骨架）。 */
export function refTokens(expr: any[]): any[] {
  return (expr ?? []).filter((t) => ['cross_tab_ref', 'component_subtotal', 'field'].includes(t.type));
}

/** 公式表「表达式」列的文字（按表头定位列）。 */
export async function formulaListExpr(page: Page, idx: number) {
  return page.evaluate((i) => {
    const tables = Array.from(document.querySelectorAll('.ant-table')).filter((t) =>
      (t as HTMLElement).offsetParent !== null && t.querySelector('.ant-table-tbody button'));
    for (const t of tables) {
      const ths = Array.from(t.querySelectorAll('.ant-table-thead th'));
      const col = ths.findIndex((th) => (th.textContent || '').trim() === '表达式');
      if (col < 0) continue;
      const rows = Array.from(t.querySelectorAll('.ant-table-tbody tr')).filter((r) =>
        Array.from(r.querySelectorAll('button')).some((b) => (b.textContent || '').replace(/\s/g, '') === '配置'));
      const td = rows[i]?.querySelectorAll('td')[col] as HTMLElement | undefined;
      return td ? (td.innerText || '').trim() : null;
    }
    return null;
  }, idx);
}

/**
 * 公式列表「表达式」列文字（主线审核：逐字比较的唯一口径）。
 * 重新进组件页读，按 GET 顺序定位该公式所在行。
 */
export async function listTextOf(page: Page, c: Fx, formulaId: string) {
  const { data } = await getComponent(c.id);
  const idx = (data.formulas ?? []).findIndex((f: any) => f.id === formulaId);
  expect(idx, `公式 ${formulaId} 应在 ${c.name} 的库内公式中`).toBeGreaterThanOrEqual(0);
  await openComponent(page, c);
  await gotoFormulaTab(page);
  await expect.poll(() => formulaRows(page).count(), { message: '公式列表行数应等于库内条数' })
    .toBe((data.formulas ?? []).length);
  const text = await formulaListExpr(page, idx);
  console.log(`[S-B] 列表表达式 ${c.name}#${idx}(${formulaId}) =「${text}」`);
  expect(text, '列表「表达式」列应读到非空文字').toBeTruthy();
  return { text: text as string, idx };
}
