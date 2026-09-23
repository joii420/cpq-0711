/**
 * E2E · task-260922 报价单管理：料号搜索加上生产料号 —— **S-UI 分片**
 *
 * 覆盖 AC：AC-1 / AC-9 / AC-10（AC-11 = 跑 `task260914-quote-list.spec.ts` 整套，不在本文件）
 * （AC-2 ~ AC-8 属 S-API 片，本文件一律不碰）
 *
 * 🚫 派生来源：全部断言取自
 *      dev-docs/task-260914-报价单列表料号搜索与扩列/task-260922-料号搜索加生产料号/任务.md §④ AC 原文
 *    + §⑦ 基准 SQL（Q0 / Q1′ / ∧草稿 / ∧左框关键字）。
 *    **不曾读过** cpq-frontend/src/** 与 cpq-backend/src/main/**。
 *    选择器词汇取自既有 e2e 代码（task260914.helpers.ts / r260910detail.helpers.ts）
 *    + 一次对 master 5174 的只读 DOM 探针（徽标 `.qt-card-header .qt-part-badge`、浮层 `.ant-popover`、
 *    单号列是 `<a>`、页签是 `.ant-tabs-tab`、默认每页 20 条）。
 *
 * 🚦 分片纪律（`docs/rules/testing.md §4.5`）
 *  - 写入面 = **纯只读**：只打开列表 / 输入搜索 / 切页签 / 翻页 / 进详情页点徽标。
 *    🚫 不建单、不保存、不提交、不删除；SQL 只许 SELECT（helpers 的 guardReadOnly 硬拒写语句）。
 *  - ⚠️ 本片存在「共 N 条」全局计数断言 —— 这是 AC-9 原文要求。对策 = **SQL 三明治**：
 *    UI 读数前后各采一次基准 SQL，两次不等 ⇒ 判「采样窗口内共享库被并发写入」，是基础设施条件，
 *    🚫 不当产品缺陷。🚫 一律不硬编码立项日采样值（33 / 28 / 30 / 35 / 210 …）。
 *
 * 🚦 开跑前置（执行轮）
 *  1) 本文件 beforeAll 采样 `pgrep -f "node.*[p]laywright test"`（排除自身进程树），有别的就硬失败。
 *  2) worktree 内起临时栈：后端 8323 + 前端 5323（`VITE_PORT=5323 VITE_API_TARGET=http://localhost:8323`），
 *     以 `PW_BASE_URL=http://localhost:5323 PW_BACKEND_URL=http://localhost:8323` 运行。
 *     本文件会**断言**两个端口的监听进程 cwd 在本 worktree 下（防「测了 master 旧代码」的假绿）；
 *     证伪/负对照轮（故意打 master 5174/8081 证明断言会变红）才设 `T260922_FOREIGN_STACK=1` 放行，
 *     该轮结果**不作为验收证据**。
 *  3) 证据写到任务目录 `证据/s-ui/<RUN_TAG>/`（`T260922_RUN` 可指定，缺省为开跑时间戳），
 *     每轮独立子目录，复跑不覆盖上一轮（Playwright 的 test-results/ 每轮被清空，不算证据）。
 */
import { test, expect, Page, Locator } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';
import {
  activePageNo,
  bodyRows,
  columnCells,
  gotoPage,
  keywordInput,
  openQuotationList,
  otherPlaywrightPids,
  partNoInput,
  rows,
  scalar,
  setPartNo,
  switchStatusTab,
  totalCount,
  withListRequest,
} from './task260914.helpers';

const __filename_ = fileURLToPath(import.meta.url);
const __dirname_ = path.dirname(__filename_);

// ───────────────────────────── 常量（全部取自 AC 原文） ─────────────────────────────

/** AC-1 ①：placeholder 逐字 */
const NEW_PLACEHOLDER = '搜索销售/客户/生产料号';
/** AC-1 ③ 对照值（立项日旧文案；只作量具自检：证明「宽度判据」确实能判出截断） */
const OLD_PLACEHOLDER = '按料号搜索（销售料号/客户料号）';
/** AC-1 ②：框宽 */
const BOX_WIDTH = 240;
const VIEWPORTS = [
  { width: 1280, height: 900 },
  { width: 1920, height: 1080 },
];

/** AC-10 样本（AC 原文点名） */
const AC10_HIT_NO = 'QT-20260914-0867';
const AC10_HIT_SALES = 'S0001';
const AC10_HIT_PROD = '300001';
const AC10_MISS_NO = 'QT-20260910-0806';
const AC10_MISS_SALES = 'S0004';
const AC10_MISS_KW = '300021';
const UNBOUND_TEXT = '未绑定生产料号';

const WORKTREE_MARK = 'task-260922-partno-production-search';
const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5174';
const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

// ───────────────────────────── 证据落盘（任务目录，不落 test-results/） ─────────────────────────────

const RUN_TAG = process.env.T260922_RUN || new Date().toISOString().replace(/[-:]/g, '').replace(/\..*$/, '');
const EVIDENCE_DIR = path.join(
  __dirname_,
  '..',
  '..',
  'dev-docs',
  'task-260914-报价单列表料号搜索与扩列',
  'task-260922-料号搜索加生产料号',
  '证据',
  's-ui',
  RUN_TAG
);
fs.mkdirSync(EVIDENCE_DIR, { recursive: true });

function writeEvidence(name: string, content: string) {
  const file = path.join(EVIDENCE_DIR, name);
  fs.writeFileSync(file, content, 'utf-8');
  console.log(`[证据] ${file}`);
}
function appendEvidence(name: string, line: string) {
  fs.appendFileSync(path.join(EVIDENCE_DIR, name), line + '\n', 'utf-8');
  console.log(line);
}
async function shot(page: Page, name: string, fullPage = false) {
  const file = path.join(EVIDENCE_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage });
  console.log(`[证据] ${file}`);
  return file;
}

// ───────────────────────────── 基准 SQL（§⑦，只读，现场重算） ─────────────────────────────

function lit(s: string): string {
  return s.toLowerCase().replace(/'/g, "''");
}

/** 条件叠加片段（§⑦「叠加条件」原文） */
const AND_DRAFT = "AND q.status = 'DRAFT'";
function andLeftKeyword(k2: string): string {
  const k = lit(k2);
  return (
    `AND (lower(q.name) LIKE '%${k}%' OR lower(q.quotation_number) LIKE '%${k}%' ` +
    `OR lower(q.snapshot_customer_name) LIKE '%${k}%')`
  );
}

/** Q1′ 的 WHERE 体（新口径：销售 + 客户 + 生产料号[按本单客户]）。extra 为叠加条件。 */
function q1pWhere(kw: string, extra = ''): string {
  const k = lit(kw);
  return (
    `FROM quotation q WHERE 1=1 ${extra} AND EXISTS (` +
    `SELECT 1 FROM quotation_line_item li WHERE li.quotation_id = q.id ` +
    `AND (lower(li.product_part_no_snapshot) LIKE '%${k}%' ` +
    `OR lower(li.customer_part_no) LIKE '%${k}%' ` +
    `OR EXISTS (SELECT 1 FROM customer c JOIN ds_quote_material m ON m.customer_no = c.code ` +
    `WHERE c.id = q.customer_id AND m.material_no = li.product_part_no_snapshot ` +
    `AND lower(m.production_no) LIKE '%${k}%')))`
  );
}

function sqlQ0(): number {
  return scalar('SELECT count(*) FROM quotation');
}
function sqlQ1p(kw: string, extra = ''): number {
  return scalar(`SELECT count(*) ${q1pWhere(kw, extra)}`);
}
function sqlQ1pNumbers(kw: string, extra = ''): Set<string> {
  return new Set(rows(`SELECT q.quotation_number ${q1pWhere(kw, extra)}`).map((r) => r[0]));
}

/**
 * SQL 三明治：UI 读数前后各采一次基准 SQL。
 * 两次不等 ⇒ 共享库在窗口内被并发写入 ⇒ 本条结论无效（基础设施条件），🚫 不判产品缺陷。
 */
async function expectTotalEqualsSql(page: Page, sqlFn: () => number, label: string, logFile: string) {
  const before = sqlFn();
  const ui = await totalCount(page);
  const after = sqlFn();
  appendEvidence(logFile, `[${label}] ${new Date().toISOString()} SQL(before)=${before}  UI「共 N 条」=${ui}  SQL(after)=${after}`);
  expect(
    before,
    `[${label}] 共享库在采样窗口内被并发写入（${before} → ${after}），本条结论无效，需重跑。🚫 不要据此判产品缺陷`
  ).toBe(after);
  expect(ui, `[${label}] UI「共 N 条」应等于同一分钟基准 SQL 现场重算值`).toBe(before);
  return ui;
}

// ───────────────────────────── 环境正身（防测了 master 旧代码 / 打在别人的库上） ─────────────────────────────

function listenerCwd(url: string): { port: string; pid: string; cwd: string } {
  const port = (url.match(/:(\d+)/) || [])[1] || '';
  let pid = '';
  let cwd = '';
  try {
    const ss = execSync(`ss -ltnp 2>/dev/null | /usr/bin/grep -a ':${port} ' || true`, {
      shell: '/bin/bash',
      encoding: 'utf-8',
    });
    pid = (ss.match(/pid=(\d+)/) || [])[1] || '';
    if (pid) {
      cwd = execSync(`readlink -f /proc/${pid}/cwd 2>/dev/null || true`, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
    }
  } catch {
    /* 采样失败 → 空值，下方断言会给出可读信息 */
  }
  return { port, pid, cwd };
}

/** 每条用例开头调用：① 前后端监听进程 cwd 在本 worktree；② 8323 连的库 = cpq_db_0724（API 总数 = SQL 总数）。 */
async function assertStackIdentity(page: Page) {
  const fe = listenerCwd(BASE_URL);
  const be = listenerCwd(BACKEND_URL);
  const line = `[identity ${new Date().toISOString()}] FE=${BASE_URL} pid=${fe.pid || '?'} cwd=${fe.cwd || '?'} | BE=${BACKEND_URL} pid=${be.pid || '?'} cwd=${be.cwd || '?'}`;
  appendEvidence('00-环境正身.txt', line);
  if (process.env.T260922_FOREIGN_STACK === '1') {
    appendEvidence('00-环境正身.txt', '[⚠️ 负对照轮] 跳过 worktree 正身断言 —— 本轮结果不作为验收证据，只用于证明断言能变红');
  } else {
    expect(fe.cwd, `🚨 前端 ${BASE_URL} 的监听进程 cwd 不在本 worktree（${fe.cwd || '取不到'}）⇒ 可能测的是 master 旧代码，属**环境问题**`).toContain(
      WORKTREE_MARK
    );
    expect(be.cwd, `🚨 后端 ${BACKEND_URL} 的监听进程 cwd 不在本 worktree（${be.cwd || '取不到'}）⇒ 可能测的是 master 旧代码，属**环境问题**`).toContain(
      WORKTREE_MARK
    );
  }
  // 库正身：经前端代理打列表接口（同时验证了 5323 → 8323 的代理链路是通的）
  const before = sqlQ0();
  const res = await page.request.get('/api/cpq/quotations?page=1&size=1');
  expect(res.status(), '库正身：列表接口应 200').toBe(200);
  const body: any = await res.json();
  const api = Number(body?.data?.totalElements);
  const after = sqlQ0();
  appendEvidence('00-环境正身.txt', `[db-identity] api.totalElements=${api} SQL Q0(before)=${before} SQL Q0(after)=${after}`);
  expect(before, '库正身采样窗口内共享库被并发写入，重跑即可（基础设施条件）').toBe(after);
  expect(api, `被测后端连的库与 cpq_db_0724 对不上（api=${api} db=${before}）⇒ **环境问题**，🚫 不判产品缺陷`).toBe(before);
}

// ───────────────────────────── 页面小工具 ─────────────────────────────

/** 料号框自己的 affix wrapper（页面上有两个带 allowClear 的框，必须框定范围）。 */
function partNoWrap(page: Page): Locator {
  return page.locator('.ant-input-affix-wrapper').filter({ has: partNoInput(page) }).first();
}
function keywordWrap(page: Page): Locator {
  return page.locator('.ant-input-affix-wrapper').filter({ has: keywordInput(page) }).first();
}
function clearIconIn(wrap: Locator): Locator {
  return wrap.locator('.ant-input-clear-icon, .anticon-close-circle').first();
}

/** 列表里按单号精确取 `<a>` 链接（单号列是 <a>，探针实测）。 */
function quotationLink(page: Page, no: string): Locator {
  return page
    .locator('.ant-table-tbody tr.ant-table-row a')
    .filter({ hasText: new RegExp(`^\\s*${no.replace(/[-]/g, '\\-')}\\s*$`) })
    .first();
}

/** 把当前结果所有页的单号收齐（从第 1 页翻到最后一页）。返回单号数组（按出现顺序）。 */
async function collectAllNumbers(page: Page): Promise<string[]> {
  if ((await activePageNo(page)) !== 1) await gotoPage(page, 1);
  const out: string[] = [];
  for (let guard = 0; guard < 60; guard++) {
    const n = await bodyRows(page).count();
    if (n === 0) break;
    out.push(...(await columnCells(page, '报价单号')));
    const next = page.locator('.ant-pagination-next').first();
    if (!(await next.count())) break;
    const cls = (await next.getAttribute('class')) || '';
    const aria = await next.getAttribute('aria-disabled');
    if (cls.includes('disabled') || aria === 'true') break;
    await withListRequest(page, async () => {
      await next.click();
    });
  }
  return out;
}

/** 在当前结果里逐页找某单号，找到就停在那一页。找不到返回 false。 */
async function findOnPages(page: Page, no: string): Promise<boolean> {
  if ((await activePageNo(page)) !== 1) await gotoPage(page, 1);
  for (let guard = 0; guard < 60; guard++) {
    if (await quotationLink(page, no).count()) return true;
    const next = page.locator('.ant-pagination-next').first();
    if (!(await next.count())) return false;
    const cls = (await next.getAttribute('class')) || '';
    const aria = await next.getAttribute('aria-disabled');
    if (cls.includes('disabled') || aria === 'true') return false;
    await withListRequest(page, async () => {
      await next.click();
    });
  }
  return false;
}

/** 从列表点单号进详情页（只读），等产品卡片渲染。 */
async function enterDetailFromList(page: Page, no: string) {
  const link = quotationLink(page, no);
  await expect(link, `列表当前页应能看到单号链接 ${no}`).toHaveCount(1);
  await link.click();
  await page.waitForURL(/\/quotations\/[0-9a-f-]{36}(\?.*)?$/, { timeout: 30_000 });
  expect(page.url(), `点单号应进**详情页**（不是 /edit），实际 URL=${page.url()}`).not.toContain('/edit');
  await page
    .waitForFunction(() => document.querySelectorAll('.qt-product-card').length > 0, undefined, { timeout: 90_000 })
    .catch(() => {});
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(3000); // 卡片内异步取数落定（r260910detail 既有经验值 6s，这里等 networkidle 后再补 3s）
  const n = await page.locator('.qt-product-card').count();
  expect(n, `详情页 ${no} 未渲染出产品卡片（0 张）⇒ 后续徽标断言会空跑，判【未验证】`).toBeGreaterThan(0);
}

/** 点卡片头部「销售料号: <sales>」徽标，返回可见浮层的原文。🚫 只在 .qt-card-header 内找。 */
async function openSalesBadge(page: Page, sales: string): Promise<{ badgeTexts: string[]; raw: string; lines: string[] }> {
  const badgeTexts = await page
    .locator('.qt-card-header .qt-part-badge')
    .evaluateAll((els) => els.map((e) => (e as HTMLElement).innerText.replace(/\s+/g, ' ').trim()));
  const badge = page
    .locator('.qt-card-header .qt-part-badge')
    .filter({ hasText: new RegExp(`销售料号\\s*[:：]\\s*${sales}\\s*$`) })
    .first();
  await expect(
    badge,
    `卡片头部应有徽标「销售料号: ${sales}」；实际头部徽标 = ${JSON.stringify(badgeTexts)}`
  ).toBeVisible({ timeout: 15_000 });
  await badge.scrollIntoViewIfNeeded();
  await badge.click();
  const pop = page.locator('.ant-popover:visible').last();
  await expect(pop, '点徽标后应弹出浮层').toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(1500); // 浮层内容可能异步取数
  const raw = await page.evaluate(() =>
    Array.from(document.querySelectorAll('.ant-popover'))
      .filter((e) => (e as HTMLElement).offsetParent !== null)
      .map((e) => (e as HTMLElement).innerText)
      .join('\n---\n')
  );
  const lines = raw
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
  return { badgeTexts, raw, lines };
}

/** 浮层行配对：label 与 value 是两个 span，innerText 会分行（r260910 既有量具坑）。 */
function popoverRow(lines: string[], label: string): string | undefined {
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(new RegExp(`^${label}\\s*[：:]\\s*(.*)$`));
    if (m) return m[1].trim() !== '' ? m[1].trim() : (lines[i + 1] ?? '').trim();
  }
  return undefined;
}

// ───────────────────────────── 全局前置 ─────────────────────────────

let backendUp = false;

test.beforeAll(async () => {
  const others = otherPlaywrightPids();
  expect(
    others,
    `检测到另有 playwright 进程在跑（pid=${others.join(',')}）。workers:1 不跨进程互斥，` +
      `global-setup 会写共享库 user 表；请等对方跑完再开跑`
  ).toEqual([]);
  backendUp = await isBackendUp();
  writeEvidence(
    '00-run.txt',
    `RUN_TAG=${RUN_TAG}\nstart=${new Date().toISOString()}\nPW_BASE_URL=${BASE_URL}\nPW_BACKEND_URL=${BACKEND_URL}\nbackendUp=${backendUp}\nQ0(开跑时，仅供人读)=${sqlQ0()}\n`
  );
});

// 🚫 后端没起来时**硬失败**而不是 skip —— skip 在报告里是「没红」，会被误读成通过（假绿）
function requireBackend() {
  expect(backendUp, `后端 ${BACKEND_URL} 未启动（/api/cpq/health 不通）⇒ 本条【未验证】，不是通过`).toBe(true);
}

// ───────────────────────────── AC-1 ─────────────────────────────

test('T-1 · AC-1：两档视口下料号框 placeholder 逐字 / 框宽 240 / 带清除按钮 / 提示文字完整不截断', async ({ page }) => {
  // run1 教训：两档视口各开一次列表页，默认 30s 不够（第二档 goto 被整体超时打断，断言未执行）
  test.setTimeout(120_000);
  requireBackend();
  await loginAsAdmin(page);
  await assertStackIdentity(page);
  const LOG = 'AC-1-取证.txt';

  for (const vp of VIEWPORTS) {
    const tag = `${vp.width}x${vp.height}`;
    await page.setViewportSize(vp);
    await openQuotationList(page);

    // ① placeholder 逐字
    const input = partNoInput(page);
    await expect(input, `AC-1[${tag}]: 应能按新 placeholder 定位到料号框（恰好 1 个）`).toHaveCount(1);
    await expect(input, `AC-1[${tag}]: 料号框应可见`).toBeVisible();
    const ph = await input.getAttribute('placeholder');
    appendEvidence(LOG, `[${tag}] placeholder=${JSON.stringify(ph)}`);
    expect(ph, `AC-1①[${tag}]: placeholder 应逐字为「${NEW_PLACEHOLDER}」`).toBe(NEW_PLACEHOLDER);

    // ② 框宽 240：antd Search 外层 .ant-input-search 带 width；同时记录 5 层祖先宽度供复核
    const widthInfo = await input.evaluate((el) => {
      const chain: { cls: string; w: number }[] = [];
      let cur: HTMLElement | null = el as HTMLElement;
      for (let i = 0; i < 5 && cur; i++) {
        chain.push({ cls: String(cur.className).split(' ')[0] || cur.tagName, w: cur.getBoundingClientRect().width });
        cur = cur.parentElement;
      }
      const search = (el as HTMLElement).closest('.ant-input-search') as HTMLElement | null;
      return { searchW: search ? search.getBoundingClientRect().width : null, chain };
    });
    appendEvidence(LOG, `[${tag}] .ant-input-search 宽=${widthInfo.searchW}  祖先链=${JSON.stringify(widthInfo.chain)}`);
    const boxW =
      widthInfo.searchW ?? widthInfo.chain.map((c) => c.w).find((w) => Math.abs(w - BOX_WIDTH) <= 1) ?? widthInfo.chain[0].w;
    expect(Math.abs(boxW - BOX_WIDTH), `AC-1②[${tag}]: 框宽应为 ${BOX_WIDTH}px，实测 ${boxW}`).toBeLessThanOrEqual(1);

    // ③ 提示文字完整显示：计算字体测宽 ≤ clientWidth − 左右内边距
    const m = await input.evaluate(
      (el, texts) => {
        const inp = el as HTMLInputElement;
        const cs = getComputedStyle(inp);
        let phFont = '';
        try {
          phFont = getComputedStyle(inp, '::placeholder').font;
        } catch {
          phFont = '(取不到)';
        }
        const font = [cs.fontStyle, cs.fontVariant, cs.fontWeight, cs.fontSize, cs.fontFamily]
          .filter((s) => s && s !== 'normal')
          .join(' ');
        const ctx = document.createElement('canvas').getContext('2d')!;
        ctx.font = font;
        const ls = parseFloat(cs.letterSpacing);
        const measure = (t: string) => ctx.measureText(t).width + (Number.isFinite(ls) ? ls * t.length : 0);
        return {
          font,
          fontApplied: ctx.font,
          fontSize: cs.fontSize,
          phFont,
          letterSpacing: cs.letterSpacing,
          clientWidth: inp.clientWidth,
          paddingLeft: parseFloat(cs.paddingLeft) || 0,
          paddingRight: parseFloat(cs.paddingRight) || 0,
          newW: measure(texts.newT),
          oldW: measure(texts.oldT),
        };
      },
      { newT: NEW_PLACEHOLDER, oldT: OLD_PLACEHOLDER }
    );
    const avail = m.clientWidth - m.paddingLeft - m.paddingRight;
    appendEvidence(
      LOG,
      `[${tag}] 计算字体=${m.font}（canvas 实际采用=${m.fontApplied}；::placeholder 字体=${m.phFont}；letter-spacing=${m.letterSpacing}）\n` +
        `[${tag}] clientWidth=${m.clientWidth} padding=${m.paddingLeft}/${m.paddingRight} ⇒ 内容区宽=${avail}\n` +
        `[${tag}] 新文案「${NEW_PLACEHOLDER}」测宽=${m.newW.toFixed(2)}  ≤ ${avail} ?  ${m.newW <= avail}\n` +
        `[${tag}] (量具对照) 旧文案「${OLD_PLACEHOLDER}」测宽=${m.oldW.toFixed(2)}  > ${avail} ?  ${m.oldW > avail}`
    );
    // 量具自检（非 AC 断言本身，防量具空跑）：
    //  a) canvas 真的用上了输入框字号（字体串解析失败会静默退回 10px sans-serif，测宽偏小 ⇒ 假绿）
    expect(m.fontApplied, `量具自检[${tag}]: canvas 未采用输入框字号 ${m.fontSize}（实际 ${m.fontApplied}）⇒ 测宽无效`).toContain(m.fontSize);
    //  b) 内容区宽度是正数
    expect(avail, `量具自检[${tag}]: 内容区宽度应 > 0`).toBeGreaterThan(0);
    //  c) 同一把尺子能判出旧文案被截断（AC-1 原文对照：旧文案 > 内容区）——量不出来说明尺子是钝的
    expect(m.oldW, `量具自检[${tag}]: 旧文案应量得 > 内容区宽（AC-1 原文对照），否则判据判不出截断`).toBeGreaterThan(avail);
    // AC-1 ③ 本体
    expect(m.newW, `AC-1③[${tag}]: 提示文字测宽 ${m.newW.toFixed(2)} 应 ≤ 内容区宽 ${avail}（否则被截断）`).toBeLessThanOrEqual(avail);

    await partNoWrap(page).screenshot({ path: path.join(EVIDENCE_DIR, `AC-1-${tag}-料号框空态.png`) });
    await shot(page, `AC-1-${tag}-列表页`);

    // ② 仍带清除按钮：输入后出现（只填不回车；点清除即使触发一次查询也是只读）
    await input.fill('S0004');
    const clear = clearIconIn(partNoWrap(page));
    await expect(clear, `AC-1②[${tag}]: 输入后应出现清除按钮（allowClear）`).toBeVisible({ timeout: 5_000 });
    await partNoWrap(page).screenshot({ path: path.join(EVIDENCE_DIR, `AC-1-${tag}-料号框有值带清除按钮.png`) });
    await withListRequest(page, async () => {
      await clear.click();
    });
    await expect(input, `AC-1②[${tag}]: 点清除按钮后输入框应为空`).toHaveValue('');
    appendEvidence(LOG, `[${tag}] 清除按钮：可见=true，点击后值=''`);
  }
});

// ───────────────────────────── AC-9 ─────────────────────────────

test('T-9 · AC-9：七步序列（料号→草稿→改料号→全部→左框关键字→清空→刷新）每步「共 N 条」= 同分钟基准 SQL，页码回第 1 页', async ({ page }) => {
  test.setTimeout(240_000);
  requireBackend();
  await page.setViewportSize({ width: 1280, height: 900 });
  await loginAsAdmin(page);
  await assertStackIdentity(page);
  const LOG = 'AC-9-七步取证.txt';
  const vacuousPageChecks: string[] = [];

  await openQuotationList(page);
  // 统一前置：状态页签停在「全部」
  const activeTab = (await page.locator('.ant-tabs-tab-active').first().innerText().catch(() => '')).replace(/\s+/g, '');
  appendEvidence(LOG, `[前置] 当前激活页签=${JSON.stringify(activeTab)}`);
  expect(activeTab, 'AC-9 前置：进入列表时状态页签应停在「全部」').toBe('全部');
  // 前置：默认态非空（否则后续「共 N 条」断言没有意义）
  expect(await bodyRows(page).count(), 'AC-9 前置：默认态列表应有数据行').toBeGreaterThan(0);

  /**
   * 「每次条件变化后页码都回到第 1 页」—— 若条件变化前本就在第 1 页，这条断言恒真（空验证）。
   * ⇒ 每次改条件前先翻到第 2 页（结果不足 2 页时无法翻，记为该步页码断言空跑）。
   */
  async function leaveFirstPage(step: string) {
    const hasPage2 = (await page.locator('.ant-pagination-item[title="2"]').count()) > 0;
    if (!hasPage2) {
      vacuousPageChecks.push(step);
      appendEvidence(LOG, `[${step}] ⚠️ 条件变化前结果不足 2 页，页码回第 1 页的断言在本步为空验证`);
      return;
    }
    await gotoPage(page, 2);
    const cur = await activePageNo(page);
    expect(cur, `[${step}] 前置：改条件前应已翻到第 2 页`).toBe(2);
    appendEvidence(LOG, `[${step}] 改条件前已翻到第 ${cur} 页`);
  }

  /** 每步的共同断言：共 N 条 = SQL（三明治）；页码 = 1；当前页每一行的单号都在同条件 SQL 结果集里。 */
  async function checkStep(step: string, expectedDesc: string, countFn: () => number, numbersFn: () => Set<string>) {
    const ui = await expectTotalEqualsSql(page, countFn, `${step} ${expectedDesc}`, LOG);
    const pageNo = await activePageNo(page);
    appendEvidence(LOG, `[${step}] 当前页码=${pageNo}`);
    expect(pageNo, `AC-9[${step}]: 条件变化后页码应回到第 1 页`).toBe(1);
    const nums = await columnCells(page, '报价单号');
    const set = numbersFn();
    const outside = nums.filter((n) => !set.has(n));
    appendEvidence(LOG, `[${step}] 当前页行数=${nums.length}  不在同条件 SQL 结果集里的单号=${JSON.stringify(outside)}`);
    if (ui > 0) expect(nums.length, `AC-9[${step}]: 共 ${ui} 条时当前页应有数据行（0 行 = 列表与分页数对不上）`).toBeGreaterThan(0);
    expect(outside, `AC-9[${step}]: 列表行应全部属于同条件基准 SQL 的结果集`).toEqual([]);
    await shot(page, `AC-9-${step}`);
    return ui;
  }

  // ① 料号框输入 300001 回车 → Q1′(300001)
  await leaveFirstPage('①');
  await setPartNo(page, '300001');
  await checkStep('①', "Q1′('300001')", () => sqlQ1p('300001'), () => sqlQ1pNumbers('300001'));

  // ② 点「草稿」页签 → Q1′(300001) ∧ 草稿；料号框仍是 300001
  await leaveFirstPage('②');
  await switchStatusTab(page, '草稿');
  const pnAfterTab = await partNoInput(page).inputValue();
  appendEvidence(LOG, `[②] 切到草稿后料号框值=${JSON.stringify(pnAfterTab)}`);
  expect(pnAfterTab, 'AC-9②: 切页签后料号框应仍显示 300001').toBe('300001');
  await checkStep('②', "Q1′('300001') ∧ 草稿", () => sqlQ1p('300001', AND_DRAFT), () => sqlQ1pNumbers('300001', AND_DRAFT));

  // ③ 料号框改成 S0001 回车 → Q1′(S0001) ∧ 草稿
  await leaveFirstPage('③');
  await setPartNo(page, 'S0001');
  await checkStep('③', "Q1′('s0001') ∧ 草稿", () => sqlQ1p('S0001', AND_DRAFT), () => sqlQ1pNumbers('S0001', AND_DRAFT));

  // ④ 点回「全部」 → Q1′(S0001)
  await leaveFirstPage('④');
  await switchStatusTab(page, '全部');
  await checkStep('④', "Q1′('s0001')", () => sqlQ1p('S0001'), () => sqlQ1pNumbers('S0001'));

  // ⑤ 左框输入「正泰」回车 → Q1′(S0001) ∧ 左框关键字「正泰」
  await leaveFirstPage('⑤');
  await withListRequest(page, async () => {
    await keywordInput(page).fill('正泰');
    await keywordInput(page).press('Enter');
  });
  const K2 = andLeftKeyword('正泰');
  await checkStep('⑤', "Q1′('s0001') ∧ 左框「正泰」", () => sqlQ1p('S0001', K2), () => sqlQ1pNumbers('S0001', K2));

  // ⑥ 点清除按钮清空两个搜索框 → Q0
  await leaveFirstPage('⑥');
  const pnClear = clearIconIn(partNoWrap(page));
  await expect(pnClear, 'AC-9⑥: 料号框应有清除按钮').toBeVisible({ timeout: 5_000 });
  await withListRequest(page, async () => {
    await pnClear.click();
  });
  const midTotal = await totalCount(page).catch((e) => `读不到(${String(e).slice(0, 80)})`);
  appendEvidence(
    LOG,
    `[⑥-中间态·仅记录不断言] 清掉料号框、左框仍为「正泰」时 共 N 条=${midTotal}  页码=${await activePageNo(page)}`
  );
  const kwClear = clearIconIn(keywordWrap(page));
  await expect(kwClear, 'AC-9⑥: 左侧搜索框应有清除按钮').toBeVisible({ timeout: 5_000 });
  await withListRequest(page, async () => {
    await kwClear.click();
  });
  expect(await partNoInput(page).inputValue(), 'AC-9⑥: 料号框应已清空').toBe('');
  expect(await keywordInput(page).inputValue(), 'AC-9⑥: 左侧搜索框应已清空').toBe('');
  const allNums = () => new Set(rows('SELECT quotation_number FROM quotation').map((r) => r[0]));
  await checkStep('⑥', 'Q0 全量', sqlQ0, allNums);

  // ⑦ 刷新浏览器 → 料号框为空、列表 = Q0（既有行为）
  await withListRequest(page, async () => {
    await page.reload();
  });
  await page.waitForSelector('.ant-table-thead th', { timeout: 60_000 });
  await expect(partNoInput(page), 'AC-9⑦: 刷新后料号框应可见').toBeVisible();
  const pnAfterReload = await partNoInput(page).inputValue();
  appendEvidence(LOG, `[⑦] 刷新后料号框值=${JSON.stringify(pnAfterReload)}  页码=${await activePageNo(page)}（⑦ 不属「条件变化」，页码只记录）`);
  expect(pnAfterReload, 'AC-9⑦: 刷新后料号框应为空').toBe('');
  await expectTotalEqualsSql(page, sqlQ0, '⑦ 刷新后 Q0 全量', LOG);
  await shot(page, 'AC-9-⑦');

  // 页码断言不许空跑：任一步因不足 2 页而无法先翻页，本条结论不完整
  appendEvidence(LOG, `[汇总] 页码断言空跑的步骤=${JSON.stringify(vacuousPageChecks)}`);
  expect(
    vacuousPageChecks,
    `AC-9「每次条件变化后页码回第 1 页」在这些步骤无法构造「先在第 2 页」的前置（结果不足 2 页），这些步骤的页码断言是空验证，判【未验证】而非通过`
  ).toEqual([]);
});

// ───────────────────────────── AC-10 ─────────────────────────────

test('T-10 · AC-10：搜得到的单卡片徽标显示该生产料号；搜不到的跨客户单徽标显示「未绑定生产料号」', async ({ page }) => {
  test.setTimeout(240_000);
  requireBackend();
  await page.setViewportSize({ width: 1280, height: 900 });
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(String(e).slice(0, 300)));
  await loginAsAdmin(page);
  await assertStackIdentity(page);
  const LOG = 'AC-10-取证.txt';

  // ── 前置（SQL 现场取证，样本失效就硬失败而不是空跑） ──
  // ① 命中样本：AC 原文 QT-20260914-0867；不存在时按 AC 原文改用 Q1′(300001) 结果中任一单并写明替换
  let hitNo = AC10_HIT_NO;
  const hitSet = sqlQ1pNumbers(AC10_HIT_PROD);
  appendEvidence(LOG, `[前置] Q1′('${AC10_HIT_PROD}') 结果集大小=${hitSet.size}`);
  expect(hitSet.size, `AC-10 前置：Q1′('${AC10_HIT_PROD}') 应非空`).toBeGreaterThan(0);
  if (!rows(`SELECT 1 FROM quotation WHERE quotation_number='${AC10_HIT_NO}'`).length) {
    // 替换样本也必须带 S0001 行（徽标要按 S0001 找）
    const alt = rows(
      `SELECT q.quotation_number ${q1pWhere(AC10_HIT_PROD)} AND EXISTS (SELECT 1 FROM quotation_line_item l2 ` +
        `WHERE l2.quotation_id=q.id AND l2.product_part_no_snapshot='${AC10_HIT_SALES}') ORDER BY q.quotation_number DESC LIMIT 1`
    );
    expect(alt.length, `AC-10 前置：${AC10_HIT_NO} 已不存在，且 Q1′('${AC10_HIT_PROD}') 里找不到带 ${AC10_HIT_SALES} 的替换单`).toBe(1);
    hitNo = alt[0][0];
    appendEvidence(LOG, `[⚠️ 样本替换] ${AC10_HIT_NO} 已不存在，按 AC-10 原文改用 Q1′('${AC10_HIT_PROD}') 结果中的 ${hitNo}`);
  }
  const hitChain = rows(
    `SELECT c.code, li.product_part_no_snapshot, m.production_no FROM quotation q JOIN customer c ON c.id=q.customer_id ` +
      `JOIN quotation_line_item li ON li.quotation_id=q.id ` +
      `LEFT JOIN ds_quote_material m ON m.customer_no=c.code AND m.material_no=li.product_part_no_snapshot ` +
      `WHERE q.quotation_number='${hitNo}' AND li.product_part_no_snapshot='${AC10_HIT_SALES}'`
  );
  appendEvidence(LOG, `[前置] ${hitNo} 的 ${AC10_HIT_SALES} 链路（客户, 销售料号, 生产料号）=${JSON.stringify(hitChain)}`);
  expect(hitChain.length, `AC-10 前置：${hitNo} 应含销售料号 ${AC10_HIT_SALES} 的产品行`).toBeGreaterThan(0);
  expect(hitChain[0][2], `AC-10 前置：${hitNo} 的 ${AC10_HIT_SALES} 按本单客户应绑生产料号 ${AC10_HIT_PROD}（AC 原文期望值）`).toBe(AC10_HIT_PROD);
  expect(hitSet.has(hitNo), `AC-10 前置：${hitNo} 应在 Q1′('${AC10_HIT_PROD}') 里`).toBe(true);

  // ② 未命中样本：QT-20260910-0806 含 S0004，但其客户名下无该绑定；且它确实是「漏客户过滤会被错搜出来」的单
  const missChain = rows(
    `SELECT c.code, li.product_part_no_snapshot, coalesce(m.production_no,'') FROM quotation q JOIN customer c ON c.id=q.customer_id ` +
      `JOIN quotation_line_item li ON li.quotation_id=q.id ` +
      `LEFT JOIN ds_quote_material m ON m.customer_no=c.code AND m.material_no=li.product_part_no_snapshot ` +
      `WHERE q.quotation_number='${AC10_MISS_NO}' AND li.product_part_no_snapshot='${AC10_MISS_SALES}'`
  );
  const missLeak = scalar(
    `SELECT count(*) FROM quotation q JOIN quotation_line_item li ON li.quotation_id=q.id ` +
      `JOIN ds_quote_material m ON m.material_no=li.product_part_no_snapshot ` +
      `WHERE q.quotation_number='${AC10_MISS_NO}' AND lower(m.production_no) LIKE '%${AC10_MISS_KW}%'`
  );
  appendEvidence(LOG, `[前置] ${AC10_MISS_NO} 的 ${AC10_MISS_SALES} 链路（客户, 销售料号, 本客户生产料号）=${JSON.stringify(missChain)}；漏客户过滤时能命中 '${AC10_MISS_KW}' 的行数=${missLeak}`);
  expect(missChain.length, `AC-10 前置：${AC10_MISS_NO} 应存在且含销售料号 ${AC10_MISS_SALES}（样本失效须换样本，🚫 不当通过）`).toBeGreaterThan(0);
  expect(missChain[0][2], `AC-10 前置：${AC10_MISS_NO} 的客户名下 ${AC10_MISS_SALES} 应**未绑定**生产料号`).toBe('');
  expect(missLeak, `AC-10 前置：${AC10_MISS_NO} 应是「漏客户过滤就会被 '${AC10_MISS_KW}' 错搜出来」的跨客户样本`).toBeGreaterThan(0);

  // ── 步骤 ①：料号框 300001 回车 → 单在结果里 → 进详情 → S0001 徽标浮层显示 300001 ──
  await openQuotationList(page);
  await setPartNo(page, AC10_HIT_PROD);
  await expectTotalEqualsSql(page, () => sqlQ1p(AC10_HIT_PROD), `① Q1′('${AC10_HIT_PROD}')`, LOG);
  const found = await findOnPages(page, hitNo);
  appendEvidence(LOG, `[①] ${hitNo} 在料号 '${AC10_HIT_PROD}' 的结果列表里（逐页找）=${found}，所在页=${await activePageNo(page)}`);
  expect(found, `AC-10①: ${hitNo} 应出现在料号 '${AC10_HIT_PROD}' 的搜索结果列表里`).toBe(true);
  await shot(page, `AC-10-①-列表-料号${AC10_HIT_PROD}-含${hitNo}`);

  await enterDetailFromList(page, hitNo);
  const popHit = await openSalesBadge(page, AC10_HIT_SALES);
  const hitRowVal = popoverRow(popHit.lines, '料号');
  appendEvidence(LOG, `[①] ${hitNo} 详情页头部徽标=${JSON.stringify(popHit.badgeTexts)}\n[①] ${AC10_HIT_SALES} 浮层原文=\n${popHit.raw}\n[①] 解析「料号」行=${JSON.stringify(hitRowVal)}`);
  await shot(page, `AC-10-①-${hitNo}-${AC10_HIT_SALES}-徽标浮层`);
  await page
    .locator('.ant-popover:visible')
    .last()
    .screenshot({ path: path.join(EVIDENCE_DIR, `AC-10-①-${hitNo}-${AC10_HIT_SALES}-浮层特写.png`) })
    .catch(() => {});
  // 浮层形态（master 探针实测）：标题「生产料号」，其下「料号：<生产料号>」一行
  expect(popHit.lines[0], `AC-10①: 浮层标题应为「生产料号」\n原文=${popHit.raw}`).toBe('生产料号');
  expect(popHit.raw.includes(UNBOUND_TEXT), `AC-10①: 命中单的浮层不应显示「${UNBOUND_TEXT}」\n原文=${popHit.raw}`).toBe(false);
  expect(hitRowVal, `AC-10①: 浮层生产料号应显示 ${AC10_HIT_PROD}\n原文=${popHit.raw}`).toBe(AC10_HIT_PROD);
  await page.keyboard.press('Escape').catch(() => {});

  // ── 步骤 ②：回列表 → 料号 300021 → 0806 不在结果里 → 清空料号框、左框按单号找到它 → 进详情 → S0004 徽标「未绑定生产料号」 ──
  await withListRequest(page, async () => {
    await page.goBack();
  });
  await page.waitForSelector('.ant-table-thead th', { timeout: 60_000 });
  // run1 教训：回列表触发的初始查询晚到，被下一步 setPartNo 的等待误认 ⇒ 读到 210（error-context 快照显示最终为「共 40 条」）。
  // 先等回列表的初始查询彻底落定，再改条件。
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(1500);
  appendEvidence(LOG, `[②] 回列表后 URL=${page.url()}  料号框值=${JSON.stringify(await partNoInput(page).inputValue())}（仅记录）`);
  await setPartNo(page, AC10_MISS_KW);
  const missTotal = await expectTotalEqualsSql(page, () => sqlQ1p(AC10_MISS_KW), `② Q1′('${AC10_MISS_KW}')`, LOG);
  expect(missTotal, `AC-10② 前置：'${AC10_MISS_KW}' 应有搜索结果（空结果下「不在结果里」是空验证）`).toBeGreaterThan(0);
  const missAll = await collectAllNumbers(page);
  appendEvidence(LOG, `[②] '${AC10_MISS_KW}' 全部结果（逐页收齐 ${missAll.length} 条）=${JSON.stringify(missAll)}`);
  expect(missAll.length, `AC-10②: 逐页收齐的单号数应等于「共 N 条」${missTotal}（收不齐则「不在结果里」不可信）`).toBe(missTotal);
  expect(missAll.includes(AC10_MISS_NO), `AC-10②: ${AC10_MISS_NO}（客户名下无绑定）不应出现在 '${AC10_MISS_KW}' 的结果里`).toBe(false);
  await gotoPage(page, 1);
  await shot(page, `AC-10-②-列表-料号${AC10_MISS_KW}-第1页`);

  await withListRequest(page, async () => {
    await clearIconIn(partNoWrap(page)).click();
  });
  if ((await partNoInput(page).inputValue()) !== '') {
    // 清除按钮未清空时退回 fill('')（AC 只要求「清空料号框」，不限手段）
    await withListRequest(page, async () => {
      await partNoInput(page).fill('');
      await partNoInput(page).press('Enter');
    });
  }
  expect(await partNoInput(page).inputValue(), 'AC-10②: 料号框应已清空').toBe('');
  await withListRequest(page, async () => {
    await keywordInput(page).fill(AC10_MISS_NO);
    await keywordInput(page).press('Enter');
  });
  await expect(quotationLink(page, AC10_MISS_NO), `AC-10②: 左侧搜索框按单号应能找到 ${AC10_MISS_NO}`).toHaveCount(1, { timeout: 15_000 });
  await shot(page, `AC-10-②-左框按单号找到-${AC10_MISS_NO}`);

  await enterDetailFromList(page, AC10_MISS_NO);
  const popMiss = await openSalesBadge(page, AC10_MISS_SALES);
  appendEvidence(LOG, `[②] ${AC10_MISS_NO} 详情页头部徽标=${JSON.stringify(popMiss.badgeTexts)}\n[②] ${AC10_MISS_SALES} 浮层原文=\n${popMiss.raw}`);
  await shot(page, `AC-10-②-${AC10_MISS_NO}-${AC10_MISS_SALES}-徽标浮层`);
  await page
    .locator('.ant-popover:visible')
    .last()
    .screenshot({ path: path.join(EVIDENCE_DIR, `AC-10-②-${AC10_MISS_NO}-${AC10_MISS_SALES}-浮层特写.png`) })
    .catch(() => {});
  expect(popMiss.raw.includes(UNBOUND_TEXT), `AC-10②: ${AC10_MISS_NO} 的 ${AC10_MISS_SALES} 徽标浮层应显示「${UNBOUND_TEXT}」\n原文=${popMiss.raw}`).toBe(true);
  expect(
    popMiss.raw.includes(AC10_MISS_KW),
    `AC-10②: 浮层不应出现别的客户名下的生产料号 ${AC10_MISS_KW}（跨客户串号）\n原文=${popMiss.raw}`
  ).toBe(false);

  appendEvidence(LOG, `[pageerror] ${JSON.stringify(errs)}（仅记录，非 AC-10 断言）`);
});
