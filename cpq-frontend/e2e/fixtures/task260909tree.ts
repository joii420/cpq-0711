/**
 * task-260909「核价树骨架分档与轴口径统一」· **S1 只读片** 测试夹具
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 🚫 本文件与 4 个 spec 的全部断言，均从 `需求文档.md §③ 验收标准` 的 AC 原文派生。
 *    **不曾读过** `cpq-backend/src/main/java/**`、`cpq-frontend/src/**`、
 *    `backtask.md`、`fronttask.md`（派工 prompt 段 c 点名禁止）。
 *    选择器词汇取自**既有 e2e 测试代码**（`multi-product-flow.spec.ts`
 *    / `costing-bom-tree.spec.ts` / `task260908-s?.helpers.ts`）与
 *    `原型图/*.html`，两者都在允许范围内。
 *
 * ── S1 片的三条纪律（`test.md §3`）─────────────────────────────────────────
 *  1. **写入面：无。** 本片只做「打开页面看渲染结果 / 调只读端点 / 查库断言」。
 *     🚫 不 POST/PUT/DELETE 任何业务对象，🚫 不调 refresh-snapshot（那是 S-全局 的写入面），
 *     🚫 不碰 costing_bom_tree_config / 模板发布态。
 *  2. **不造数。** 只用既有单据 `QT-20260909-0661` 与既有账号。
 *  3. 🚫 **禁止全局计数断言。** 所有断言都锚定到具体对象
 *     （「S0001 卡片 BOM 页签 7 行」✅ / 「列表共 N 条」❌）——
 *     共享库 `cpq_db_0724` 上别的会话随时在造数，全局计数会红得像业务回归。
 *
 * ── 防四类假绿（`test.md §4`）─────────────────────────────────────────────
 *  A. **空验证** → 每处行数断言前先 `expect(rows).toBeGreaterThan(0)`，
 *     并且断言的是**具体行数 + 具体值集合**，🚫 不写 `≥0`。
 *  B. **测了旧代码** → `recordBackendIdentity()` 每轮打印 8081 监听进程的
 *     cwd + 启动时刻；`assertServingExpectedDb()` 用「totalElements ↔ count(*)」
 *     实测它连的确实是 `cpq_db_0724`（比读配置可靠）。
 *  C. **选择器坑** → 所有定位失败都抛「入口/选择器问题 ⇒ 判【未验证】」，
 *     与「产品缺陷」显式分开；两字按钮一律用 `/新\s*增/` 容忍 antd 插入的空格。
 *  D. **阳性对照** → `报价侧` 那条 spec（AC-21）在**修复前就应当是绿的**，
 *     它是整套定位器栈的正对照：它红 = 我的量具坏了，不是产品坏了。
 */

import { execSync } from 'child_process';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';
import { expect, Locator, Page } from '@playwright/test';

const __dirnameLocal = nodePath.dirname(fileURLToPath(import.meta.url));

// ──────────────────────────────────────────────────────────────────────────
// 0. 环境坐标
// ──────────────────────────────────────────────────────────────────────────

export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5174';
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

export const DB = {
  host: '10.177.152.12',
  port: '5432',
  user: 'postgres',
  db: 'cpq_db_0724',
  password: 'joii5231',
};

/** 统一测试单据（`需求文档.md §③` 前置环境逐字）。 */
export const QUOTATION_NO = 'QT-20260909-0661';
export const QUOTATION_ID = '3c38bfb7-6af3-4f39-9c6d-f127e02d8712';
export const CUSTOMER_CODE = 'CUST-0004';

/** 核价模板 `核价模板1 v1.0`（PUBLISHED，5 页签）。 */
export const COSTING_TEMPLATE_ID = '9d89e95d-f0e1-42eb-bf6d-0a815e531f0e';
/** 报价模板 `正泰测试模板1 v1.1`。 */
export const QUOTE_TEMPLATE_ID = '6dedb30b-a3f2-49db-ba11-9570613a66f7';

/** AC-13 点名的 3 个 COST_BASIC 组件 + AC-12 的加工费组件。 */
export const COMPONENTS = {
  产品: 'COMP-2298',
  BOM: 'COMP-2299',
  材质元素: 'COMP-2300',
  加工费: 'COMP-2319',
} as const;

/** 本单 4 行产品（`需求文档.md §③` 前置环境逐字）。 */
export const PRODUCTS = ['S0001', 'S0004', 'S0008', 'S0012'] as const;

/**
 * 卡片定位别名（销售料号 → 品名 / 客户料号），取自本单 `quotation_line_item` 快照。
 * 🚫 不是「放宽断言」—— 断言的仍是同一张卡片；这只是**量具**，
 *    防止「卡片头显示的是品名而不是料号」被误报成产品缺陷。
 */
export const PRODUCT_ALIASES: Record<string, string[]> = {
  S0001: ['铆钉', 'A002'],
  S0004: ['触桥组件A', 'RW-A004'],
  S0008: ['铆钉组件B', 'TC-B008'],
  S0012: ['端子组件C', 'ZT-C012'],
};

// ──────────────────────────────────────────────────────────────────────────
// 1. 只读 SQL（psql -X -A，绝不写库）
// ──────────────────────────────────────────────────────────────────────────

/** 🚫 只读守卫：出现任何写/DDL 关键字直接抛，防止本片意外写共享库。 */
const FORBIDDEN_SQL = /\b(insert|update|delete|drop|truncate|alter|create|grant|revoke|vacuum)\b/i;

/** 跑一条只读 SQL，返回 stdout 原文（`-A -F'|'`，首行是表头）。 */
export function sqlRaw(sql: string): string {
  if (FORBIDDEN_SQL.test(sql)) {
    throw new Error(`🚨 S1 是只读片，拒绝执行含写操作的 SQL：${sql.slice(0, 120)}`);
  }
  const cmd =
    `PGPASSWORD=${DB.password} psql -h ${DB.host} -p ${DB.port} -U ${DB.user} -d ${DB.db} ` +
    `-X -A -F'|' -c ${JSON.stringify(sql)}`;
  return execSync(cmd, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
}

/** 只读 SQL → 行对象数组（首行当表头）。 */
export function sqlRows(sql: string): Record<string, string>[] {
  const out = sqlRaw(sql).split('\n').filter((l) => l.length > 0);
  // psql -A 末行是 "(N rows)"，剔除
  const body = out.filter((l) => !/^\(\d+ rows?\)$/.test(l.trim()));
  if (body.length === 0) return [];
  const headers = body[0].split('|');
  return body.slice(1).map((line) => {
    const cells = line.split('|');
    const o: Record<string, string> = {};
    headers.forEach((h, i) => (o[h] = cells[i] ?? ''));
    return o;
  });
}

/** 只读 SQL → 单个标量（第一行第一列）。 */
export function sqlScalar(sql: string): string {
  const rows = sqlRaw(sql).split('\n').filter((l) => l.length > 0 && !/^\(\d+ rows?\)$/.test(l.trim()));
  if (rows.length < 2) throw new Error(`SQL 无结果（本该有值 ⇒ 夹具/环境问题，不是产品缺陷）：${sql}`);
  return rows[1].split('|')[0];
}

// ──────────────────────────────────────────────────────────────────────────
// 2. 环境正身校验（防「测了旧代码」「打在别人的库上」）
// ──────────────────────────────────────────────────────────────────────────

/**
 * 记录 8081 监听进程的 cwd 与启动时刻。
 *
 * 🚨 **为什么这条不可省**：本机 8081 是**全会话共享**的 dev server，
 * 2026-09-09 采样时它由 **主仓** `/home/joii/project/cpq/cpq-backend`（Sep 8 19:00 启动）
 * 提供服务，**不是 worktree**。若实现只落在 worktree 而没重启/合并，
 * 整套 S1 会在**改动前的 class** 上跑，然后「AC-6 仍红」被误报成「实现没做完」，
 * 或更糟——「AC-13 仍含 ds_quote_material」被误报成产品缺陷。
 *
 * 返回一段可直接贴进 `test-report.md` 的取证文本。
 */
export function recordBackendIdentity(): string {
  let pid = '', cwd = '', started = '';
  try {
    const ss = execSync(`ss -ltnp 2>/dev/null | /usr/bin/grep -a ':8081 ' || true`,
      { shell: '/bin/bash', encoding: 'utf-8' });
    pid = (ss.match(/pid=(\d+)/) || [])[1] || '';
    if (pid) {
      cwd = execSync(`readlink -f /proc/${pid}/cwd 2>/dev/null || true`,
        { shell: '/bin/bash', encoding: 'utf-8' }).trim();
      started = execSync(`ps -o lstart= -p ${pid} 2>/dev/null || true`,
        { shell: '/bin/bash', encoding: 'utf-8' }).trim();
    }
  } catch { /* 采样失败不阻断，但下面会打印空值，人一眼看得出来 */ }
  const line = `[backend-identity] url=${BACKEND_URL} pid=${pid || '?'} cwd=${cwd || '?'} started="${started || '?'}"`;
  console.log(line);
  // ⚠️「当前采样为 X」是瞬时量，不是状态（CLAUDE.md §5）——所以写进证据时带时刻。
  appendEvidence('00-backend-identity.txt', `${new Date().toISOString()} ${line}\n`);
  return line;
}

/**
 * 实测 8081 服务的确实是 `cpq_db_0724`：
 * 比对 `GET /api/cpq/quotations?page=1&size=1` 的 `totalElements` 与库里 `count(*)`。
 * （`CLAUDE.md §1` 记载的手法，比读配置可靠。）
 *
 * ⚠️ 这**不是**全局计数断言 —— 它不是业务断言，是「两个数字必须相等」的**一致性**校验，
 * 别的会话造数会让两边**同时**变，不会造成假红。
 */
export async function assertServingExpectedDb(cookie: string): Promise<void> {
  const res = await fetch(`${BACKEND_URL}/api/cpq/quotations?page=1&size=1`, {
    headers: { Cookie: cookie },
  });
  expect(res.status, '取 quotations 分页应 200（否则鉴权/服务异常，判【未验证】）').toBe(200);
  const body: any = await res.json();
  const api = Number(body?.data?.totalElements ?? body?.totalElements ?? NaN);
  const db = Number(sqlScalar('SELECT count(*) FROM quotation'));
  console.log(`[db-identity] api.totalElements=${api}  db.count(*)=${db}`);
  expect(Number.isFinite(api), 'totalElements 取不到 ⇒ 契约或鉴权问题，判【未验证】').toBe(true);
  expect(
    api,
    `8081 连的库与 ${DB.db} 对不上（api=${api} db=${db}）⇒ 后续断言会打在别人的库上，` +
      `这是**环境问题**不是产品缺陷`,
  ).toBe(db);
  appendEvidence('00-backend-identity.txt', `${new Date().toISOString()} [db-identity] api=${api} db=${db}\n`);
}

// ──────────────────────────────────────────────────────────────────────────
// 3. 账号
// ──────────────────────────────────────────────────────────────────────────

export const ADMIN = {
  username: process.env.PW_USER_ADMIN || 'admin',
  password: process.env.PW_PWD_ADMIN || 'Admin@2026',
};

/**
 * `SALES_MANAGER` 账号（AC-17 / AC-18 原文点名 `test1`）。
 *
 * 🚨 **2026-09-09 实测：`test1` 的口令拿不到**（实打 `/auth/login` 返 401；
 *    对照实验：同一命令打 `admin` 返 200 ⇒ 不是端点写错）。库里另外两个
 *    `SALES_MANAGER` 中 `test0806_bob` 是 `INACTIVE`、`fe-eval-tester` 同样 401。
 *
 * ⇒ 主线经**正规接口** `POST /api/cpq/users` 新建了专用账号
 *    `t260909tree_smgr`（`SALES_MANAGER` / `ACTIVE`），已列入**闸门 B 待回收清单**。
 *    🚫 没用裸 SQL、没重置任何既有账号口令。
 *    ⚠️ 该账号 `forceChangePassword=true`，实测**不挡 API**（登录返 200 且 `GET /components` 200）。
 *
 * 🚫 仍保留「缺口令即硬失败」这条路径（env 显式置空时）—— 账号会烂，
 *    烂掉时必须硬失败而不是 skip：skip 掉的权限断言会以「全部通过」混过去
 *    （`testing.md §3` 假绿之首）。且报错要写明它是**测试环境缺陷**而非产品缺陷。
 */
export function salesManagerCred(): { username: string; password: string } {
  const username = process.env.PW_USER_SMGR ?? 't260909tree_smgr';
  const password = process.env.PW_PWD_SMGR ?? 'WnT9$E@mg@';
  if (!username || !password) {
    throw new Error(
      '🚨 停下报告：AC-17 / AC-18 需要 SALES_MANAGER 账号口令，当前拿不到。\n' +
        '  · 已实测 test1 / Admin@2026 → 401；fe-eval-tester / Admin@2026 → 401\n' +
        '  · 请主线提供 PW_USER_SMGR / PW_PWD_SMGR（或指定一个可用的 SALES_MANAGER 账号）\n' +
        '  ⚠️ 这是**测试环境缺陷**，不是产品缺陷 —— 🚫 不得因此把用例改成 skip 或降级断言。',
    );
  }
  return { username, password };
}

/**
 * API 登录，返回可直接塞进 `Cookie` 头的会话串。
 *
 * 🚨 **2026-09-09 实证：本系统是 Cookie 会话鉴权，不是 Bearer token。**
 *    `POST /api/cpq/auth/login` 返回体里**没有** `token` / `accessToken` 字段，
 *    身份在响应头 `Set-Cookie: CPQ_SESSION=<uuid>; HttpOnly` 里
 *    （与 `global-setup.ts` 保存 `storageState.cookies` 的做法一致）。
 *    ⚠️ 本函数最初按 token 写，会在登录**成功**时抛「取不到 token」——
 *    那种失败长得像产品契约变了，实际是量具错了。已按实证改正。
 *
 * 带退避重试：登录限流 30 次/分/IP，打满时表现为「登录失败」，
 * **看起来像鉴权坏了**，实际是测试基础设施问题。
 */
export async function loginApi(username: string, password: string): Promise<string> {
  let last = 0, body = '';
  for (let i = 0; i < 4; i++) {
    const res = await fetch(`${BACKEND_URL}/api/cpq/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
      redirect: 'manual',
    });
    last = res.status;
    body = await res.text();
    if (res.ok) {
      // getSetCookie() 在 Node 20+ 的 undici 上可用；退化时回落单值 set-cookie
      const raw: string[] = (res.headers as any).getSetCookie?.()
        ?? (res.headers.get('set-cookie') ? [res.headers.get('set-cookie') as string] : []);
      const jar = raw
        .map((c) => c.split(';')[0].trim())
        .filter((c) => c.length > 0);
      if (jar.length === 0) {
        throw new Error(
          `登录 ${username} 返 200 但响应头没有 Set-Cookie（鉴权契约变了？）：${body.slice(0, 200)}\n` +
            '⚠️ 先确认不是量具问题再判产品缺陷。',
        );
      }
      return jar.join('; ');
    }
    if (last === 429) { await new Promise((r) => setTimeout(r, 3000 * (i + 1))); continue; }
    break;
  }
  throw new Error(
    `登录失败 ${username} → ${last} ${body.slice(0, 200)}\n` +
      '⚠️ 先判是**测试环境缺陷**（口令/限流）还是产品缺陷，🚫 不要默认后者。',
  );
}

// ──────────────────────────────────────────────────────────────────────────
// 4. 页面导航（选择器全部取自既有 e2e 代码与原型图）
// ──────────────────────────────────────────────────────────────────────────

/** antd 会在两个汉字之间插空格（历史坑：「保 存」）⇒ 两字按钮一律用容忍空白的正则。 */
export function cjkBtn(text: string): RegExp {
  return new RegExp(`^\\s*[＋+]?\\s*${text.split('').join('\\s*')}\\s*$`);
}

/** 打开报价单编辑页并停在 Step2（产品卡片所在步骤）。 */
export async function gotoStep2(page: Page): Promise<void> {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1800);
  const next = page.locator('button', { hasText: /下一步|继续/ }).first();
  if ((await next.count()) > 0 && (await next.isEnabled().catch(() => false))) {
    await next.click().catch(() => {});
    await page.waitForTimeout(1500);
  }
  const cards = page.locator('.qt-product-card');
  await expect(
    cards.first(),
    '进不到 Step2 产品卡片（.qt-product-card 一个都没有）⇒ **入口问题**，本条判【未验证】，' +
      '🚫 不得记成产品缺陷',
  ).toBeVisible({ timeout: 20_000 });
}

/** 切到「核价单」视图 + 「产品卡片」子视图。 */
export async function switchToCosting(page: Page): Promise<void> {
  const seg = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
  await expect(seg, '找不到「核价单」切换入口 ⇒ 入口问题，判【未验证】').toBeVisible({ timeout: 15_000 });
  await seg.click();
  await page.waitForTimeout(1500);
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if ((await cardSeg.count()) > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(1200); }
  await page.waitForTimeout(2500);
}

/** 切到「报价单」视图 + 「产品卡片」子视图（AC-21 用）。 */
export async function switchToQuote(page: Page): Promise<void> {
  const seg = page.locator('.ant-segmented-item', { hasText: '报价单' }).first();
  if ((await seg.count()) > 0) { await seg.click().catch(() => {}); await page.waitForTimeout(1500); }
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if ((await cardSeg.count()) > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(1200); }
  await page.waitForTimeout(2000);
}

/**
 * 按料号定位产品卡片。
 *
 * ⚠️ 🚫 不用 `.nth(i)` 的位置口径 —— 卡片顺序不是 AC 的一部分，靠位置定位会在
 * 排序变化时给出**错卡片上的正确断言**（最难查的一类假绿）。
 * 用「卡片头部含该料号」定位；命中 ≠ 1 张就硬失败并打印全部卡片头，
 * 让人一眼分清是「定位歧义」还是「卡片没渲染」。
 */
export async function cardOf(page: Page, partNo: string): Promise<Locator> {
  const all = page.locator('.qt-product-card');
  const n = await all.count();
  const heads: string[] = [];
  const hits: number[] = [];
  // 卡片头未必显示生产料号 —— 本单 4 行的别名（销售料号 / 客户料号 / 品名）取自
  // `quotation_line_item` 的快照字段，属**业务数据**不是实现代码。
  const alias = (PRODUCT_ALIASES[partNo] || []).concat(partNo);
  for (let i = 0; i < n; i++) {
    // 只看卡片头部区域，避免表格行里的同名料号造成误命中
    const head = (await all.nth(i).innerText().catch(() => '')).split('\n').slice(0, 6).join(' / ');
    heads.push(`[${i}] ${head.slice(0, 160)}`);
    if (alias.some((a) => head.includes(a))) hits.push(i);
  }
  if (hits.length !== 1) {
    throw new Error(
      `按料号 ${partNo}（别名 ${JSON.stringify(alias)}）定位产品卡片失败：命中 ${hits.length} 张（共 ${n} 张）。\n` +
        `卡片头部快照：\n${heads.join('\n')}\n` +
        '🚨 这是**定位/入口**问题（或卡片压根没渲染），本条判【未验证】。',
    );
  }
  const card = all.nth(hits[0]);
  await card.scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(600);
  return card;
}

/** 在指定卡片内切页签；找不到就硬失败（区分入口问题 vs 产品缺陷）。 */
export async function switchTabInCard(card: Locator, tabName: string): Promise<void> {
  const btn = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${tabName}\\s*$`) }).first();
  if (!(await btn.count())) {
    const all = await card.locator('button.qt-tab-btn').allInnerTexts();
    throw new Error(
      `卡片内找不到页签「${tabName}」。实有页签=${JSON.stringify(all.map((s) => s.trim()))}\n` +
        '🚨 入口/选择器问题 ⇒ 判【未验证】。',
    );
  }
  await btn.click();
  await card.page().waitForTimeout(2500);
}

// ──────────────────────────────────────────────────────────────────────────
// 5. 表格读取（表头名 → 列下标，🚫 不用位置硬编码）
// ──────────────────────────────────────────────────────────────────────────

export interface TableSnapshot {
  headers: string[];
  rows: string[][];
  /** 每行首列内容元素的左边距（px），用于推断树缩进层级。 */
  indentPx: number[];
  /** 树列下标：含展开箭头的那一列；无箭头时回落 0。 */
  treeCol: number;
}

/** 读当前卡片内活动页签的表格快照。 */
export async function readTable(card: Locator): Promise<TableSnapshot> {
  const table = card.locator('.qt-cost-table').first();
  await expect(table, '卡片内没有 .qt-cost-table ⇒ 入口/渲染问题，判【未验证】').toBeVisible({ timeout: 15_000 });

  const headers = (await table.locator('thead th').allInnerTexts()).map((s) => s.trim());

  // 树列 = 含 ▼/▶ 箭头按钮的那一列；无箭头（叶子单行表）时回落第 0 列
  // （核价卡片的第 0 列是系统固定列「料号」，即树列 —— 见 costing-bom-tree.spec.ts）。
  let treeCol = 0;
  const caret = table.locator('tbody button', { hasText: /[▼▶]/ }).first();
  if (await caret.count()) {
    treeCol = await caret.evaluate((b) => {
      const td = (b as HTMLElement).closest('td');
      if (!td) return 0;
      return Array.from(td.parentElement!.children).indexOf(td);
    }).catch(() => 0);
  }

  const trs = table.locator('tbody tr');
  const n = await trs.count();
  const rows: string[][] = [];
  const indentPx: number[] = [];
  for (let i = 0; i < n; i++) {
    const tds = trs.nth(i).locator('td');
    rows.push((await tds.allInnerTexts()).map((s) => s.trim()));
    const px = await tds.nth(treeCol).evaluate((td) => {
      const el = td as HTMLElement;
      const tdLeft = el.getBoundingClientRect().left;
      // 取首列里最靠左的**可见文本**节点的 x，作为缩进量度（与具体实现方式无关：
      // padding / margin / 占位 span 都能量到）
      const kids = Array.from(el.querySelectorAll('*')) as HTMLElement[];
      const cand = kids.filter((k) => (k.textContent || '').trim().length > 0);
      const target = cand.length ? cand[cand.length - 1] : el;
      return Math.round(target.getBoundingClientRect().left - tdLeft);
    }).catch(() => -1);
    indentPx.push(px);
  }

  return { headers, rows, indentPx, treeCol };
}

/**
 * 表头名 → 列下标。**多候选**，因为 AC 用的是业务说法、组件配的是字段名
 * （例：AC-9 说「组成数量」，`COMP-2299` 配的是「组成用量」）。
 *
 * 🚫 命中 0 个 → 硬失败并打印全部实有表头 + AC 编号，让人一眼判断
 *    「是 AC 写法要澄清」还是「列真的没渲染出来」。
 * 🚫 命中 ≥2 个 → 同样硬失败（歧义比缺失更危险：会在错列上得到正确断言）。
 */
export function colIdx(t: TableSnapshot, candidates: string[], acLabel: string): number {
  const hit = candidates
    .map((c) => t.headers.findIndex((h) => h === c))
    .map((i, k) => ({ i, name: candidates[k] }))
    .filter((x) => x.i >= 0);
  if (hit.length === 0) {
    throw new Error(
      `${acLabel}：表头里找不到任何一个候选列 ${JSON.stringify(candidates)}。\n` +
        `实有表头=${JSON.stringify(t.headers)}\n` +
        '🚨 两种成因请分开判：①AC 的列名说法与实配字段名不符 ⇒ **AC 需澄清**；' +
        '②该列真的没渲染 ⇒ 产品缺陷。🚫 不许猜。',
    );
  }
  if (hit.length > 1) {
    throw new Error(
      `${acLabel}：候选列命中 ${hit.length} 个（${JSON.stringify(hit)}），存在歧义。\n` +
        `实有表头=${JSON.stringify(t.headers)}\n🚨 在错列上做对断言是最难查的假绿 ⇒ 判【未验证】，请主线确认列名。`,
    );
  }
  console.log(`[colIdx] ${acLabel} → 列「${hit[0].name}」(#${hit[0].i})`);
  return hit[0].i;
}

/** 取某列全部单元格文本。 */
export function col(t: TableSnapshot, idx: number): string[] {
  return t.rows.map((r) => (r[idx] ?? '').trim());
}

/** 「加载中…」计数（限定在卡片内，避免跨卡片污染）。 */
export async function loadingCountIn(card: Locator): Promise<number> {
  return card.locator('text=加载中').count();
}

// ──────────────────────────────────────────────────────────────────────────
// 6. 证据归档
// ──────────────────────────────────────────────────────────────────────────

/**
 * 🚨 `test-results/` 每轮开跑前会被清空 ⇒ 留在那里的截图**不算证据**
 * （`testing.md §2`：下一轮会不会删掉它？会 → 不算）。
 * 一律复制到任务目录归档位。
 */
export const EVIDENCE_DIR = nodePath.resolve(
  __dirnameLocal,
  '../../../dev-docs/task-260909-核价树骨架分档与轴口径统一/证据/S1',
);

function ensureEvidenceDir() {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
}

export function appendEvidence(file: string, text: string) {
  ensureEvidenceDir();
  fs.appendFileSync(nodePath.join(EVIDENCE_DIR, file), text, 'utf-8');
}

export function writeEvidence(file: string, text: string) {
  ensureEvidenceDir();
  fs.writeFileSync(nodePath.join(EVIDENCE_DIR, file), text, 'utf-8');
  console.log(`[evidence] → ${nodePath.join(EVIDENCE_DIR, file)}`);
}

/** 截图并**直接落归档位**（不经 test-results，免得下一轮被清空）。 */
export async function shot(page: Page, name: string) {
  ensureEvidenceDir();
  const file = nodePath.join(EVIDENCE_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[shot] → ${file}`);
}

/** 卡片局部截图（比整页更能当「这张卡片没红框」的证据）。 */
export async function shotCard(card: Locator, name: string) {
  ensureEvidenceDir();
  const file = nodePath.join(EVIDENCE_DIR, `${name}.png`);
  await card.screenshot({ path: file }).catch(() => {});
  console.log(`[shot] → ${file}`);
}
