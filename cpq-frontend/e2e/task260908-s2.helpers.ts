/**
 * task-260908 · 分片 S2（配置器交互）· 共享 helper
 *
 * 服务 AC-13 / AC-14 / AC-15 / AC-16 / AC-17 / AC-18 / AC-19 / AC-25。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 🚫 本文件与配套 spec **不读实现源码**（`cpq-frontend/src/**`、`cpq-backend/src/main/java/**`）。
 *    断言一律派生自 `dev-docs/task-260908-取数配置器优化/需求文档.md §③` 的 AC 原文
 *    与 `原型图/01-组件绑定区.html` / `原型图/02-取数配置器-已选输出列.html` 的视觉基准。
 *    选择器来自**既有测试代码**（task260907-builder-gaps / task260819v9 / rowkey-fieldname-contract
 *    / tmp-task0729-screen8）与原型里点名的类名（`.svb-pgrp`）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 🧩 分片隔离（`test.md §1`「S2 的三条隔离约束」，缺一不可）
 *   1. 造的每个组件 / 目录名以 `T260908-S2-` 开头
 *   2. 只断言自己造的那批 —— 🚫 全片**不许**出现「组件列表共 N 条」这类**全局计数断言**：
 *      共库并行时别片造一条数据就把本片打红，而且**红得像业务回归**（testing.md §4.5）
 *   3. `finally` / `afterAll` 里清理自己造的组件 + 其 `component_sql_view` 行
 *
 * 🚨 清理只删自己前缀 / 自己 id 的行。**任何 TRUNCATE、无 WHERE 的 DELETE、清库都是
 *    `CLAUDE.md §3.2` 红线，停下来报主线**（本文件不含、也不许被改成含这类语句）。
 *
 * ─────────────────────────────────────────────────────────────────────
 * 🚨 已实证的选择器坑（逐条避开，全部来自本仓既有 spec 的注释）
 *   · antd 两字按钮渲染成「新 建」「创 建」「保 存」⇒ 一律 `/^新\s*建$/`
 *   · 组件管理页不是表格，是目录卡片列表（`.cmm-card` / `.cmm-c-code`），`.ant-table-tbody tr` 取不到
 *   · 字段面板分组默认折叠，折叠是 CSS `display:none` ⇒ 不先展开，`toBeVisible()` 报 hidden，
 *     那个失败**长得像「字段没渲染」**，其实是「没展开」
 *   · 分组标题 `.svb-grp-h`（不是 `.svb-grp-head`）；容器 `.svb-grp`；折叠态 `.svb-grp.collapsed`
 *   · `.ant-modal-wrap` 一旦打开会拦截所有 pointer 事件 ⇒ 切 Tab 前先 Escape
 *   · 必须 `channel:'chrome'`（config 里已设）—— 否则全部倒在启动，长得像业务回归但一个断言都没执行
 */
import { expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

// 🚩 本仓是 Vite ESM 项目，没有 __dirname（AP-43 同族：ESM 下用 CJS 全局会 ReferenceError）
const HERE = path.dirname(fileURLToPath(import.meta.url));

/** 本片造数前缀 —— 主线分配，🚫 不许改成「更自然」的命名（那是撞车的唯一原因）。 */
export const S2 = 'T260908-S2-';

export const BASE_URL = process.env.PW_BASE_URL || '';
export const BACKEND_URL = process.env.PW_BACKEND_URL || '';

/** 证据归档目录。🚨 `test-results/` 每轮开跑前会被清空 ⇒ 当证据的截图必须落到任务目录里。 */
export const EVIDENCE_DIR = path.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260908-取数配置器优化', '证据', 'e2e-s2',
);

// ═══════════════════════════════════════════════════════════════════════
// 环境守卫
// ═══════════════════════════════════════════════════════════════════════

/**
 * 🚨 拒绝跑在共享 dev server 上。
 *
 * 5174 / 8081 服务的是**主工作区已合并代码**，看不到本 worktree 内 F-2/F-3 的改动
 * ⇒ 拿它跑出来的绿是**假绿**；而且这两个端口保留给主线亲验，占了会互相干扰。
 *
 * 合并后若要拿它做回归，显式 `PW_ALLOW_DEFAULT_PORTS=1`，届时报告里必须写明
 * 「本轮跑在主工作区代码上」。
 */
export function assertIsolatedEnv() {
  const allow = process.env.PW_ALLOW_DEFAULT_PORTS === '1';
  expect(
    BASE_URL,
    'PW_BASE_URL 未设置 ⇒ 不知道在测哪套前端。执行前由主线给出隔离环境地址。',
  ).toBeTruthy();
  expect(
    BACKEND_URL,
    'PW_BACKEND_URL 未设置 ⇒ 不知道在测哪套后端。',
  ).toBeTruthy();
  if (allow) {
    console.warn(
      '[S2][env] ⚠️ PW_ALLOW_DEFAULT_PORTS=1：允许跑默认端口。'
      + '若地址是 5174/8081，本轮测的是**主工作区代码**，报告里必须写明，🚫 不得当作 worktree 的验收证据。',
    );
    return;
  }
  expect(
    /:5174(\/|$)/.test(BASE_URL),
    `PW_BASE_URL=${BASE_URL} 指向共享 dev 前端 5174 —— 它服务主工作区代码，`
    + '本 worktree 的 F-2/F-3 改动**不在里面**，跑出来的绿是假绿。'
    + '请用临时 Vite 端口；确需如此加 PW_ALLOW_DEFAULT_PORTS=1。',
  ).toBe(false);
  expect(
    /:8081(\/|$)/.test(BACKEND_URL),
    `PW_BACKEND_URL=${BACKEND_URL} 指向共享 dev 后端 8081（保留给主线亲验）。`
    + '请用临时后端端口；确需如此加 PW_ALLOW_DEFAULT_PORTS=1。',
  ).toBe(false);
  console.log(`[S2][env] base=${BASE_URL} backend=${BACKEND_URL} db=${DB_DESC}`);
}

// ═══════════════════════════════════════════════════════════════════════
// DB（只用于造「存量形态」与清理，命中面一律被自己的 id / 前缀限死）
// ═══════════════════════════════════════════════════════════════════════

const DB_HOST = process.env.PW_DB_HOST || '10.177.152.12';
const DB_PORT = process.env.PW_DB_PORT || '5432';
const DB_NAME = process.env.PW_DB_NAME || 'cpq_db_0724';
const DB_USER = process.env.PW_DB_USER || 'postgres';
const DB_PASS = process.env.PW_DB_PASS || 'joii5231';
export const DB_DESC = `${DB_HOST}:${DB_PORT}/${DB_NAME}`;

/**
 * 执行一条 SQL 并返回文本。
 *
 * 🚨 守卫：本片只允许**命中面被自己 id / 前缀限死**的语句。
 * 出现 TRUNCATE / DROP / 无 WHERE 的 DELETE·UPDATE ⇒ 直接抛错，🚫 不执行、🚫 不换写法重试，
 * 停下来报主线（`CLAUDE.md §3.2`：子代理没有批准权）。
 */
export function psql(sql: string): string {
  const flat = sql.replace(/\s+/g, ' ').trim();
  const upper = flat.toUpperCase();
  if (/\b(TRUNCATE|DROP)\b/.test(upper)) {
    throw new Error(`[S2][红线] 拒绝执行含 TRUNCATE/DROP 的语句：${flat}\n`
      + '这是 CLAUDE.md §3.2 不可逆操作，测试员没有批准权 —— 停下来报主线。');
  }
  if (/^\s*(DELETE|UPDATE)\b/.test(upper) && !/\bWHERE\b/.test(upper)) {
    throw new Error(`[S2][红线] 拒绝执行无 WHERE 的 DELETE/UPDATE：${flat}`);
  }
  return execSync(
    `PGPASSWORD=${DB_PASS} psql -h ${DB_HOST} -p ${DB_PORT} -U ${DB_USER} -d ${DB_NAME} -At -F '|' `
    + `-c "${sql.replace(/"/g, '\\"')}"`,
    { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'], shell: '/bin/bash' },
  ).trim();
}

// ═══════════════════════════════════════════════════════════════════════
// 造数与清理
// ═══════════════════════════════════════════════════════════════════════

export interface Fixture { id: string; name: string; code: string }

/** 本次运行造出来的东西，afterAll 逐个回收。 */
export const created: { components: Fixture[]; directoryId: string } =
  { components: [], directoryId: '' };

let cookieHeader = '';

export function setCookieHeader(v: string) { cookieHeader = v; }

export async function api(p: string, init: RequestInit = {}) {
  return fetch(`${BACKEND_URL}${p}`, {
    ...init,
    headers: { Cookie: cookieHeader, 'Content-Type': 'application/json', ...(init.headers || {}) },
  });
}

/** 建一个本片专属目录（名字带前缀），组件都挂进去 —— 建出来的组件在 UI 里搜得到，且好清理。 */
export async function ensureDirectory(): Promise<string> {
  if (created.directoryId) return created.directoryId;
  const r = await api('/api/cpq/component-directories', {
    method: 'POST',
    body: JSON.stringify({ name: `${S2}DIR`, parentId: null }),
  });
  // 🚨 body 只能读一次。`expect(ok, `...${await r.text()}`)` 的模板串是**先求值再传参**，
  //    断言通过时它照样把 body 消费掉 ⇒ 下一行 r.json() 抛
  //    `TypeError: Body is unusable`，10 条用例全红且长得像产品缺陷。
  //    ⇒ 先把 body 读成文本，再从文本解析。
  const body1 = await r.text();
  expect(r.ok, `建本片专属目录失败：${r.status} ${body1}`).toBeTruthy();
  created.directoryId = (JSON.parse(body1) as any).data.id;
  console.log(`[S2] 专属目录 ${S2}DIR = ${created.directoryId}`);
  return created.directoryId;
}

/**
 * 建一个空白页签组件（名字带前缀）并登记待清理。
 *
 * 🚨 前置守卫：断言 `componentType === 'NORMAL'`。
 * 不是 NORMAL 的组件没有「取数配置」Tab、也没有绑定区 —— 后面全部会以 timeout 收场，
 * 那种红**长得像产品缺陷**，其实是夹具建错了。
 */
export async function createFixtureComponent(suffix: string): Promise<Fixture> {
  const dir = await ensureDirectory();
  const name = `${S2}${suffix}-${Date.now()}`;
  const r = await api('/api/cpq/components', {
    method: 'POST',
    body: JSON.stringify({ name, directoryId: dir, componentType: 'NORMAL' }),
  });
  const body2 = await r.text();   // 同上：body 只能读一次
  expect(r.ok, `建组件 ${name} 失败：${r.status} ${body2}`).toBeTruthy();
  const d = (JSON.parse(body2) as any).data;
  const fx: Fixture = { id: d.id, name: d.name, code: d.code };
  created.components.push(fx);
  expect(
    d.componentType,
    `夹具 ${name} 的 componentType=${d.componentType}，不是 NORMAL ⇒ 它没有「取数配置」Tab / 绑定区，`
    + '后面的用例会以 timeout 收场并长得像产品缺陷。判【未验证】。',
  ).toBe('NORMAL');
  console.log(`[S2] 造出组件 ${fx.code} ${fx.name} (${fx.id})`);
  return fx;
}

/**
 * 回收本次造的组件 + 其 `component_sql_view` 行 + 专属目录。
 * 命中面全部被自己的 id 限死；再按前缀兜一次底（防中途崩溃漏登记）。
 */
export async function cleanupFixtures() {
  for (const c of created.components) {
    try {
      const before = psql(`SELECT count(*) FROM component_sql_view WHERE component_id='${c.id}'`);
      psql(`DELETE FROM component_sql_view WHERE component_id='${c.id}'`);
      psql(`DELETE FROM component WHERE id='${c.id}'`);
      console.log(`[S2][cleanup] 删组件 ${c.code} ${c.name}（其 component_sql_view ${before} 行）`);
    } catch (e) {
      console.warn(`[S2][cleanup] 删 ${c.id} 失败：`, e);
    }
  }
  // 兜底：本前缀下若还有残留（中途崩溃没登记的），一并清 —— WHERE 被前缀限死
  try {
    const left = psql(`SELECT count(*) FROM component WHERE name LIKE '${S2}%'`);
    if (left !== '0') {
      console.log(`[S2][cleanup] 前缀兜底：还剩 ${left} 个 ${S2}* 组件，一并清理`);
      psql(`DELETE FROM component_sql_view WHERE component_id IN `
        + `(SELECT id FROM component WHERE name LIKE '${S2}%')`);
      psql(`DELETE FROM component WHERE name LIKE '${S2}%'`);
    }
  } catch (e) {
    console.warn('[S2][cleanup] 前缀兜底失败：', e);
  }
  if (created.directoryId) {
    try {
      await api(`/api/cpq/component-directories/${created.directoryId}`, { method: 'DELETE' });
      console.log(`[S2][cleanup] 删专属目录 ${created.directoryId}`);
    } catch (e) {
      console.warn('[S2][cleanup] 删目录失败：', e);
    }
  }
  created.components.length = 0;
  created.directoryId = '';
}

// ═══════════════════════════════════════════════════════════════════════
// 页面导航
// ═══════════════════════════════════════════════════════════════════════

export async function closeAnyModal(page: Page) {
  for (let i = 0; i < 4; i++) {
    if (await page.locator('.ant-modal-wrap').filter({ visible: true }).count() === 0) return;
    await page.keyboard.press('Escape');
    await page.waitForTimeout(400);
  }
}

/**
 * 按 code 打开组件详情。
 *
 * ⚠️ 本仓既有 spec 用了**两条不同的路由**：`/components`（tmp-task0729 / task260819）与
 * `/components-raw`（rowkey-fieldname-contract）。哪条是当前 UI 不确定，
 * 🚫 不读实现去确认（本片禁读 `src/`）⇒ 两条都试，记下哪条生效。
 * 两条都打不开就**硬失败并说明这是入口问题**（判【未验证】），不要让它伪装成产品缺陷。
 */
export async function openComponentByCode(page: Page, code: string) {
  const routes = ['/components', '/components-raw'];
  const tried: string[] = [];
  for (const route of routes) {
    await page.goto(route);
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.waitForTimeout(4000);
    const search = page.locator('input[placeholder*="搜索"]').first();
    if (await search.count()) {
      await search.fill(code);
      await page.waitForTimeout(1800);
    }
    // 命中卡片：优先 .cmm-c-code，其次 .cmm-card（两种类名本仓都出现过）
    const byCode = page.locator(`.cmm-c-code:has-text("${code}")`).first();
    const byCard = page.locator('.cmm-card').filter({ hasText: code }).first();
    const target = (await byCode.count()) ? byCode : (await byCard.count()) ? byCard : null;
    tried.push(`${route}: cmm-c-code=${await byCode.count()} cmm-card=${await byCard.count()}`);
    if (!target) continue;
    await target.evaluate((el) => {
      const card = (el as HTMLElement).closest('[class*="cmm-c"]') as HTMLElement | null;
      (card ?? (el as HTMLElement)).click();
    });
    await page.waitForTimeout(3000);
    console.log(`[S2] 打开组件 ${code} —— 生效路由 ${route}`);
    return route;
  }
  throw new Error(
    `打不开组件 ${code}：两条路由都没命中卡片。探查=${JSON.stringify(tried)}\n`
    + '🚨 这是**入口/选择器**问题（组件管理页不是表格，是目录卡片列表），'
    + '本条 AC 判【未验证】，🚫 不得记成产品缺陷、也不得记成通过。',
  );
}

/** 切 Tab（弹层会拦 pointer 事件 ⇒ 先 Escape；role=tab 比 getByText 稳）。 */
export async function switchTab(page: Page, name: string) {
  await closeAnyModal(page);
  let tab = page.getByRole('tab', { name, exact: true }).first();
  if (!(await tab.count())) tab = page.getByText(name, { exact: true }).first();
  await expect(tab, `找不到 Tab「${name}」⇒ 入口问题，本条判【未验证】`).toBeVisible({ timeout: 15_000 });
  await tab.click();
  await page.waitForTimeout(3000);
}

/** 造一个组件 → 打开它 → 进「取数配置」Tab。返回夹具。 */
export async function newComponentInBuilder(page: Page, suffix: string): Promise<Fixture> {
  const fx = await createFixtureComponent(suffix);
  await openComponentByCode(page, fx.code);
  await switchTab(page, '取数配置');
  const bar = page.locator('.svb-recipe-bar').first();
  await expect(
    bar,
    '取数配置面板（.svb-recipe-bar）不可见 ⇒ 后面的断言全是空跑，本条判【未验证】',
  ).toBeVisible({ timeout: 20_000 });
  return fx;
}

// ═══════════════════════════════════════════════════════════════════════
// 配置器操作
// ═══════════════════════════════════════════════════════════════════════

/**
 * 顺手确认可能弹出的「切换会清空已选列」确认框（不是本片的断言点）。
 *
 * 🚨 范围必须收进弹层容器。整页找 `确定` 会命中工具栏 / 别的表单上的同名按钮，
 *    点下去做了别的事，而症状会出现在**后面某条断言**上，极难归因。
 */
async function confirmIfAsked(page: Page) {
  // 用 `.filter({ visible: true })`（本仓既有 spec 的写法），🚫 不用 `:visible` 伪类混在逗号选择器里
  const scope = page.locator('.ant-modal-wrap, .ant-popconfirm, .ant-modal-confirm')
    .filter({ visible: true });
  if (await scope.count() === 0) return;
  const btn = scope.locator('button').filter({ hasText: /^确\s*定$|^确\s*认$/ }).first();
  if (await btn.isVisible().catch(() => false)) {
    await btn.click();
    await page.waitForTimeout(800);
  }
}

/** 选数据集（报价 / 基础核价 / 明细核价）—— segmented 控件，不是 antd Select。 */
export async function selectDataset(page: Page, label: '报价' | '基础核价' | '明细核价') {
  // 🚨 范围收进取数配置面板。整页 getByText('报价') 会命中菜单 / 面包屑 / 标题上的同名文字，
  //    点下去可能导航走，而报错会出现在后面某条断言上，长得像产品缺陷。
  const bar = page.locator('.svb-recipe-bar');
  const scope = (await bar.count()) ? bar.first() : page.locator('body');
  const el = scope.getByText(label, { exact: true }).first();
  await expect(el, `数据集选择器里找不到「${label}」⇒ 入口问题，本条判【未验证】`).toBeVisible({ timeout: 10_000 });
  await el.click();
  await confirmIfAsked(page);
  await page.waitForTimeout(2000);
}

/** 选数据源（antd Select，虚拟滚动）。 */
export async function selectSource(page: Page, label: string) {
  const sel = page.locator('[data-role="builder-source"]').first();
  await expect(
    sel,
    '找不到「数据源」下拉（[data-role="builder-source"]）⇒ 入口问题，本条判【未验证】',
  ).toBeVisible({ timeout: 15_000 });
  await sel.click();
  await page.waitForTimeout(400);
  const opt = page
    .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
    .filter({ hasText: new RegExp(`^${label}$`) })
    .first();
  await expect(opt, `数据源下拉里找不到「${label}」`).toBeVisible({ timeout: 10_000 });
  await opt.click();
  await confirmIfAsked(page);
  await page.waitForTimeout(2500);
}

/** 展开左侧全部分组（折叠是 CSS display:none，不展开一律 hidden）。返回仍折叠的组数。 */
export async function expandAllGroups(page: Page): Promise<number> {
  for (let i = 0; i < 20; i++) {
    const collapsed = page.locator('.svb-grp.collapsed');
    if (await collapsed.count() === 0) break;
    await collapsed.first().locator('.svb-grp-h').click({ timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(200);
  }
  return page.locator('.svb-grp.collapsed').count();
}

/** 双击左侧字段名把它加入已选列（真实拖拽受虚拟滚动影响，本仓既有 spec 统一用双击）。 */
export async function addField(page: Page, displayName: string) {
  await expandAllGroups(page);
  const f = page.locator('.svb-grp').getByText(displayName, { exact: true }).first();
  await expect(
    f,
    `字段面板里找不到可见的「${displayName}」（报 hidden ⇒ 分组没展开，不是字段缺失）`,
  ).toBeVisible({ timeout: 10_000 });
  await f.dblclick();
  await page.waitForTimeout(1200);
}

// ═══════════════════════════════════════════════════════════════════════
// 已选输出列 —— 量具
// ═══════════════════════════════════════════════════════════════════════

export interface SelCol {
  /** 整行文本（归一化空白） */
  text: string;
  /** 全部叶子文本，用于**精确**匹配视图列名（🚫 别只靠 substring，`material_no` 是 `input_material_no` 的子串） */
  leaves: string[];
  /** 字段名输入框的 value（原型 §状态1：这里才是 F-3 改的东西） */
  fieldName: string;
  /** 行上是否带「行键」徽标 */
  hasRowKeyBadge: boolean;
  /** ✕ 是否存在 */
  hasRemove: boolean;
  /** ✕ 的禁用信号（disabled / aria-disabled / class / cursor:not-allowed / pointer-events:none 任一） */
  removeDisabled: boolean;
  /** ✕ 上能拿到的原因文案（title / aria-label） */
  removeReason: string;
}

const SEL_ROW = '[data-role="selected-column"], .selected-column-row';

/**
 * 读「已选输出列」。
 *
 * 🚨 取不到就**硬失败**并说清这是选择器问题，🚫 不静默返回空数组 ——
 * 空数组会让「不含某列」「不残留」这类断言**恒真通过**（假绿四类之一）。
 */
export async function readSelectedColumns(page: Page, when: string): Promise<SelCol[]> {
  const n = await page.locator(SEL_ROW).count();
  expect(
    n,
    `${when}：已选输出列一行都取不到（选择器 ${SEL_ROW}）。\n`
    + '🚨 这是**选择器与真实 UI 对不上**，不是「用户没选列」—— '
    + '空数组会让「不残留 / 不含某列」这类断言恒真通过。本条判【未验证】。',
  ).toBeGreaterThan(0);
  const rows = await page.$$eval(SEL_ROW, (els) => els.map((r) => {
    const el = r as HTMLElement;
    const leaves = Array.from(el.querySelectorAll('*'))
      .filter((e) => e.childElementCount === 0)
      .map((e) => (e.textContent || '').trim())
      .filter(Boolean);
    const nameInput = Array.from(el.querySelectorAll('input'))
      .find((i) => (i as HTMLInputElement).type !== 'checkbox') as HTMLInputElement | undefined;
    const rm = Array.from(el.querySelectorAll('*')).find((e) => {
      const t = (e.textContent || '').trim();
      const al = (e.getAttribute('aria-label') || '') + (e.getAttribute('title') || '');
      return (e.childElementCount === 0 && /^[✕✖×xX]$/.test(t)) || /移除|删除|remove/i.test(al);
    }) as HTMLElement | undefined;
    let disabled = false; let reason = '';
    if (rm) {
      const cs = getComputedStyle(rm);
      disabled = rm.hasAttribute('disabled')
        || rm.getAttribute('aria-disabled') === 'true'
        || /disabled|\boff\b/.test(rm.className || '')
        || cs.pointerEvents === 'none'
        || cs.cursor === 'not-allowed';
      reason = (rm.getAttribute('title') || rm.getAttribute('aria-label') || '').trim();
    }
    return {
      text: (el.innerText || '').replace(/\s+/g, ' ').trim(),
      leaves,
      fieldName: (nameInput?.value || '').trim(),
      hasRowKeyBadge: leaves.includes('行键'),
      hasRemove: !!rm,
      removeDisabled: disabled,
      removeReason: reason,
    };
  }));
  console.log(`[S2][已选列] ${when} → ${rows.length} 行`);
  for (const r of rows) {
    console.log(`    字段名=${JSON.stringify(r.fieldName)} 行键=${r.hasRowKeyBadge}`
      + ` ✕=${r.hasRemove}/禁用=${r.removeDisabled} 文案=${JSON.stringify(r.removeReason)}`
      + ` | ${r.text}`);
  }
  return rows;
}

/** 某行是否就是这个视图列（精确叶子优先，退化到整行 substring）。 */
export function isCol(row: SelCol, viewColumn: string): boolean {
  return row.leaves.includes(viewColumn) || row.text.includes(viewColumn);
}

/** 已选列里挑出视图列 = viewColumn 的那一行；找不到返回 undefined。 */
export function findCol(rows: SelCol[], viewColumn: string): SelCol | undefined {
  return rows.find((r) => isCol(r, viewColumn));
}

/** 点某一行的 ✕（force：禁用态也要点得到，才能验「点了也没用」）。 */
export async function clickRemove(page: Page, viewColumn: string) {
  const rows = page.locator(SEL_ROW);
  const n = await rows.count();
  for (let i = 0; i < n; i++) {
    const txt = (await rows.nth(i).innerText()) || '';
    if (!txt.includes(viewColumn)) continue;
    const rm = rows.nth(i).locator('xpath=.//*[normalize-space(text())="✕" or normalize-space(text())="×"'
      + ' or contains(@aria-label,"移除") or contains(@title,"移除")]').first();
    await expect(rm, `第 ${i} 行（${viewColumn}）上找不到 ✕ 控件`).toHaveCount(1);
    await rm.click({ force: true, timeout: 10_000 }).catch((e) => {
      console.log(`[S2] 点 ✕（${viewColumn}）被拦：${String(e).slice(0, 120)}`);
    });
    await page.waitForTimeout(1200);
    return;
  }
  throw new Error(`已选列里没有视图列 ${viewColumn} 这一行，点不了 ✕`);
}

// ═══════════════════════════════════════════════════════════════════════
// 服务端事实（现算，🚫 不写死「哪几列是行键」——那由语义图决定）
// ═══════════════════════════════════════════════════════════════════════

export interface ServerField {
  sourceColumn: string; displayName: string; viewColumn: string; roles: string[]; groupKey: string;
}

export async function fieldTree(
  page: Page, tabType: string, variantKey: string, dialect: string,
): Promise<ServerField[]> {
  const q = `tabType=${encodeURIComponent(tabType)}&variantKey=${encodeURIComponent(variantKey)}`
    + `&dialect=${dialect}`;
  const res = await page.request.get(`${BACKEND_URL}/api/cpq/config/semantic-graph/field-tree?${q}`);
  expect(res.ok(), `前置：field-tree 应 200，实际=${res.status()}（${tabType}/${variantKey}/${dialect}）`)
    .toBe(true);
  const j = await res.json();
  const out: ServerField[] = [];
  for (const g of j.groups ?? []) {
    for (const f of g.fields ?? []) {
      out.push({
        sourceColumn: f.sourceColumn, displayName: f.displayName, viewColumn: f.viewColumn,
        roles: f.roles ?? [], groupKey: g.groupKey,
      });
    }
  }
  expect(out.length, `前置：${tabType}/${variantKey}/${dialect} 的 field-tree 一个字段都没有 ⇒ 后面全是空跑`)
    .toBeGreaterThan(0);
  return out;
}

/** 该数据源全部带 `行键` 角色的列 —— AC-14 的「全部」按这个现算，不写死。 */
export async function serverRowKeys(
  page: Page, tabType: string, variantKey: string, dialect: string,
): Promise<ServerField[]> {
  const rk = (await fieldTree(page, tabType, variantKey, dialect))
    .filter((f) => f.roles.includes('ROW_KEY'));
  // 阳性对照：该源本来就没有行键列的话，「自动带出行键列」是空验证
  expect(
    rk.length,
    `【阳性对照】${tabType}/${variantKey}/${dialect} 的服务端 ROW_KEY 列数为 0 ⇒ `
    + '「自动带出全部行键列」验的是空集，恒真通过。本条判【未验证】，停下来报主线。',
  ).toBeGreaterThan(0);
  console.log(`[S2][服务端行键] ${tabType}/${variantKey}/${dialect} = `
    + JSON.stringify(rk.map((f) => `${f.displayName}(${f.viewColumn})`)));
  return rk;
}

// ═══════════════════════════════════════════════════════════════════════
// 绑定区（AC-13 / AC-25）
// ═══════════════════════════════════════════════════════════════════════

export const BIND_LABELS = ['料号列', '名称列', '元素列', '元素单价列', '货币列'] as const;
export type BindLabel = typeof BIND_LABELS[number];

/**
 * 读组件绑定区：哪些标签出现了、各自回填的值是什么。
 *
 * 判据只用「标签文案在不在 + 它旁边的 Select 显示什么」，都是 AC 原文里的可观测量。
 * 🚨 「元素单价列」包含「元素列」三个字 ⇒ 必须按**叶子精确文本**匹配，
 *    用 `page.getByText('元素列')` 会把「元素单价列」也算进来，让 AC-25 的「不出现元素列」恒红/恒绿。
 */
export async function readBindingArea(page: Page): Promise<Record<BindLabel, string | null>> {
  const raw = await page.evaluate((labels: readonly string[]) => {
    // 🚨 优先把扫描范围收进绑定区容器（`.cmm-acts`，见 tmp-task0729-screen8-verify.spec.ts）。
    //    不收范围的话，页面别处若也出现「元素列」这三个字（例如字段配置表格的表头），
    //    AC-25 / AC-13 状态B 的「不应出现元素列」会**假红**，看起来像产品缺陷。
    //    容器找不到就退回整页，并把这件事记进返回值让用例打印出来（🚫 不静默降级）。
    const scope: ParentNode = document.querySelector('.cmm-acts') ?? document;
    const leaves = Array.from(scope.querySelectorAll('*'))
      .filter((e) => e.childElementCount === 0);
    const out: Record<string, string | null> = {};
    for (const L of labels) out[L] = null;
    for (const e of leaves) {
      const t = (e.textContent || '').trim();
      if (!labels.includes(t)) continue;
      // 从标签往上找到同时含 .ant-select 的最近祖先，读它的选中值
      let p: HTMLElement | null = e.parentElement;
      let val = '';
      for (let i = 0; i < 6 && p; i++, p = p.parentElement) {
        const sel = p.querySelector('.ant-select');
        if (!sel) continue;
        const item = sel.querySelector('.ant-select-selection-item') as HTMLElement | null;
        const ph = sel.querySelector('.ant-select-selection-placeholder') as HTMLElement | null;
        val = item ? (item.getAttribute('title') || item.textContent || '').trim()
          : ph ? '' : '';
        break;
      }
      out[t] = val; // '' = 下拉存在但未选（placeholder 态）
    }
    out.__scope = scope === document ? 'document(整页退化)' : '.cmm-acts';
    return out;
  }, BIND_LABELS as readonly string[]);
  console.log('[S2][绑定区]', JSON.stringify(raw, null, 0));
  if (raw.__scope !== '.cmm-acts') {
    console.warn('[S2][绑定区] ⚠️ 未命中绑定区容器 `.cmm-acts`，已退回整页扫描。'
      + '若页面别处也出现「元素列」这三个字，本次结论不可信 —— 报告里请注明。');
  }
  delete raw.__scope;
  return raw as Record<BindLabel, string | null>;
}

/** 红屏 / 未捕获异常探针（AC-25 用）。 */
export function watchJsErrors(page: Page) {
  const errors: string[] = [];
  const libWarnings: string[] = [];
  const isLibWarning = (t: string) => /^Warning:/.test(t.trim());
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    const t = m.text();
    // antd/React 的 deprecation 告警走 console.error，任何页面都有；不过滤本条恒红，
    // 而那个红长得像本次引入的缺陷。🚫 但也不静默吞 —— 打印出来。
    if (isLibWarning(t)) libWarnings.push(t); else errors.push(`console.error: ${t}`);
  });
  return { errors, libWarnings };
}

export async function hasRedOverlay(page: Page): Promise<string> {
  return page.evaluate(() => {
    const probes = ['vite-error-overlay', '#vite-error-overlay', '.ant-result-error',
      '[class*="error-overlay"]', '[class*="ErrorBoundary"]'];
    for (const s of probes) {
      const el = document.querySelector(s);
      if (el) return `${s}: ${(el.textContent || '').slice(0, 200)}`;
    }
    return '';
  });
}

export async function shot(page: Page, name: string) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}
