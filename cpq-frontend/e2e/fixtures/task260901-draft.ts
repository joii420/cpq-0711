/**
 * 共享夹具 · task-260901 保存草稿增量协议与并发保护
 *
 * 🚫 本文件不 import 任何 src/ 下的实现代码，也不从实现代码派生任何判据。
 *    全部断言口径来自：
 *      - dev-docs/task-260901-保存草稿增量协议与并发保护/需求文档.md §③ AC-1 ~ AC-24
 *      - dev-docs/task-260901-保存草稿增量协议与并发保护/api.md（前后端唯一契约）
 *      - dev-docs/task-260901-保存草稿增量协议与并发保护/原型图/冲突提示.html（AC-12 弹窗形态）
 *
 * 🚨 只读 SQL 纪律：本文件里的 psql 调用一律是 SELECT。测试用例本身会通过 UI 写业务数据
 *    （这是 AC 要求的用户操作），但**绝不**执行 DELETE/TRUNCATE/DDL。清库属 CLAUDE.md §3.2 红线。
 *
 * 🚨 全局状态登记（testing.md 共享库纪律）——本套用例会改动以下**业务数据**（非全局配置）：
 *      · BASELINE 单（QT-20260901-0218）的「物料」页签「材料净重」单元格值 → 每次跑会写一个新值，
 *        用例自身在 finally 里还原为进入时读到的原值。
 *      · SANDBOX 单（QT-20260830-0213）会被增行 / 删行 / 提交-撤回 → 仅限本套用例使用。
 *      · 🚫 不动：用户启停用、角色权限、模板发布态、系统开关、公共基础数据。
 */
import { execSync } from 'child_process';
import { Page, Locator, expect, Request, Response } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __filenameLocal = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filenameLocal);

// ──────────────────────────────────────────────────────────────────────────
// 0. 固定样本（dev 库 cpq_db_0724，2026-09-01 只读 SQL 实测确认）
// ──────────────────────────────────────────────────────────────────────────

/** 基准单：QT-20260901-0218，1845 行 / 9225 条 componentData，DRAFT。需求文档 §③ 统一前置指定。 */
export const BASELINE_QUOTATION_ID = process.env.TASK260901_BASELINE_ID
  || '46e5428f-f0ee-4dbb-93cb-cd6a4db9da6b';
export const BASELINE_QUOTATION_NO = 'QT-20260901-0218';
export const BASELINE_LINE_COUNT = 1845;
export const BASELINE_COMPONENT_DATA_COUNT = 9225;

/**
 * 沙箱单：QT-20260830-0213，同样 1845 行 DRAFT。
 * 🔑 增行 / 删行 / 提交 这类**破坏基准单形状**的用例一律打这张，避免把基准单的 1845/9225
 *    判据打漂（那会让后续所有用例假红，且症状看起来像业务回归）。
 */
export const SANDBOX_QUOTATION_ID = process.env.TASK260901_SANDBOX_ID
  || '77ffe749-7518-4c2e-9c92-167cced0dd07';
export const SANDBOX_QUOTATION_NO = 'QT-20260830-0213';

/** 0 行空单（AC-24 用）。dev 库现有 5 张 0 行 DRAFT，取其一。 */
export const EMPTY_QUOTATION_ID = process.env.TASK260901_EMPTY_ID
  || '4e05cb06-9142-4c69-8caf-54b59e7e0c81';
export const EMPTY_QUOTATION_NO = 'QT-20260830-0214';

/**
 * AC-1 指定的目标单元格 =「物料」页签 / 第 2 行 / 「材料净重」列 / 值 3.5。
 *
 * ✅ 与 `需求文档.md` AC-1（2026-09-01 修正版）逐字一致。
 *    修正背景（AC-1 的 📌 注已记）：原写「材料成本」页签有误 —— SQL 实测该页签不含「材料净重」列
 *    （列集为 元素/料件/料号/毛重/项次/净用量/元素单价/…），且最多 6 行；「材料净重」(INPUT_NUMBER)
 *    在「物料」页签，该页签恒 2 行，故「第 2 行」成立。
 */
export const AC1_TAB_NAME = '物料';
export const AC1_ROW_INDEX = 1;      // 0-based，即 AC-1 说的「第 2 行」
export const AC1_COLUMN_NAME = '材料净重';
export const AC1_TARGET_VALUE = '3.5';

export const DB_HOST = '10.177.152.12';
export const DB_NAME = process.env.TASK260901_DB || 'cpq_db_0724';
export const DB_USER = 'postgres';
export const DB_PASSWORD = 'joii5231';

/** 证据归档目录 —— Playwright 每轮开跑会清空 test-results/，要留证的一律落这里。 */
export const EVIDENCE_DIR = path.resolve(
  __dirnameLocal,
  '../../../dev-docs/task-260901-保存草稿增量协议与并发保护/证据/screenshots'
);

// ──────────────────────────────────────────────────────────────────────────
// 1. 只读 SQL
// ──────────────────────────────────────────────────────────────────────────

/** 🚨 只读：调用方只许传 SELECT。 */
export function psql(sql: string): string {
  const trimmed = sql.trim().toUpperCase();
  if (!(trimmed.startsWith('SELECT') || trimmed.startsWith('WITH'))) {
    throw new Error(`[task260901] 夹具只允许只读 SQL（SELECT/WITH），拒绝执行：${sql}`);
  }
  const cmd = `PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -t -A -F'|' -c "${sql.replace(/"/g, '\\"')}"`;
  return execSync(cmd, { encoding: 'utf-8', shell: '/bin/bash', maxBuffer: 64 * 1024 * 1024 }).trim();
}

export function psqlRows(sql: string): string[][] {
  const out = psql(sql);
  if (!out) return [];
  return out.split('\n').filter(Boolean).map((l) => l.split('|'));
}

export function psqlScalar(sql: string): string {
  return psql(sql);
}

/** 该单行数。 */
export function queryLineItemCount(qid: string): number {
  return Number(psqlScalar(`SELECT count(*) FROM quotation_line_item WHERE quotation_id='${qid}';`));
}

/** 该单 componentData 条数（AC-6 的 9225 分母）。 */
export function queryComponentDataCount(qid: string): number {
  return Number(psqlScalar(
    `SELECT count(*) FROM quotation_line_component_data cd JOIN quotation_line_item li ON li.id=cd.line_item_id WHERE li.quotation_id='${qid}';`
  ));
}

/**
 * AC-6 的整表指纹：逐条 componentData 的 row_data md5。
 * 返回 Map<componentDataId, md5>。比"整表一个 md5"更有分辨力 —— 能指出**具体是哪几条**变了。
 */
export function queryRowDataFingerprints(qid: string): Map<string, string> {
  const rows = psqlRows(
    `SELECT cd.id::text, md5(coalesce(cd.row_data::text,'')) FROM quotation_line_component_data cd ` +
    `JOIN quotation_line_item li ON li.id=cd.line_item_id WHERE li.quotation_id='${qid}';`
  );
  return new Map(rows.map((r) => [r[0], r[1]] as [string, string]));
}

/**
 * 物理层「这一行到底有没有被 UPDATE」的权威判据：PG 的 xmin 系统列。
 * 任何一次 UPDATE 都会换掉行版本 → xmin 变化。内容比对只能证明"内容没变"，
 * xmin 才能证明"没写过"（需求文档 §② ①「未变的行不 UPDATE」）。
 */
export function queryComponentDataXmin(qid: string): Map<string, string> {
  const rows = psqlRows(
    `SELECT cd.id::text, cd.xmin::text FROM quotation_line_component_data cd ` +
    `JOIN quotation_line_item li ON li.id=cd.line_item_id WHERE li.quotation_id='${qid}';`
  );
  return new Map(rows.map((r) => [r[0], r[1]] as [string, string]));
}

/** AC-7：卡片值被置 NULL 的行数。返回 [quoteNull, costingNull, total]。 */
export function queryNullCardValueCounts(qid: string): { quoteNull: number; costingNull: number; total: number } {
  const r = psqlRows(
    `SELECT count(*) FILTER (WHERE quote_card_values IS NULL), ` +
    `count(*) FILTER (WHERE costing_card_values IS NULL), count(*) ` +
    `FROM quotation_line_item WHERE quotation_id='${qid}';`
  )[0];
  return { quoteNull: Number(r[0]), costingNull: Number(r[1]), total: Number(r[2]) };
}

/**
 * AC-11 / AC-13 / AC-14：库中的 user_data_version。
 * ⚠️ 列不存在时**显式抛错**（不返回 0 兜底）—— 兜底会让 AC-11「N → N+1」在列缺失时静默变成 0→0 的假绿。
 */
export function queryUserDataVersion(qid: string): number {
  const exists = psqlScalar(
    `SELECT count(*) FROM information_schema.columns WHERE table_name='quotation' AND column_name='user_data_version';`
  );
  if (exists !== '1') {
    throw new Error('[task260901] quotation.user_data_version 列不存在 —— ③ 版本指纹尚未落库（迁移未跑？），AC-11/12/13/14 无从验证');
  }
  const v = psqlScalar(`SELECT user_data_version FROM quotation WHERE id='${qid}';`);
  if (!v) throw new Error(`[task260901] 报价单 ${qid} 不存在或 user_data_version 为空`);
  return Number(v);
}

/** AC-3 的 4 张子表当前计数（按 line item 维度）。 */
export function queryLineSubTableCounts(lineItemId: string) {
  const r = psqlRows(
    `SELECT (SELECT count(*) FROM quotation_line_item WHERE id='${lineItemId}'),` +
    ` (SELECT count(*) FROM quotation_line_process WHERE line_item_id='${lineItemId}'),` +
    ` (SELECT count(*) FROM quotation_line_component_data WHERE line_item_id='${lineItemId}'),` +
    ` (SELECT count(*) FROM quotation_line_item_snapshot WHERE line_item_id='${lineItemId}');`
  )[0];
  return {
    lineItem: Number(r[0]),
    process: Number(r[1]),
    componentData: Number(r[2]),
    lineItemSnapshot: Number(r[3]),
  };
}

/** AC-20：工序 / 选配组合工艺的整单条数 + 内容指纹。 */
export function queryProcessFingerprint(qid: string) {
  const r = psqlRows(
    `SELECT (SELECT count(*) FROM quotation_line_process p JOIN quotation_line_item li ON li.id=p.line_item_id WHERE li.quotation_id='${qid}'),` +
    ` (SELECT coalesce(md5(string_agg(p.line_item_id::text||coalesce(p.process_no,'')||coalesce(p.process_id::text,''), ',' ORDER BY p.id)),'<empty>') FROM quotation_line_process p JOIN quotation_line_item li ON li.id=p.line_item_id WHERE li.quotation_id='${qid}'),` +
    ` (SELECT count(*) FROM quotation_line_composite_process cp JOIN quotation_line_item li ON li.id=cp.line_item_id WHERE li.quotation_id='${qid}'),` +
    ` (SELECT coalesce(md5(string_agg(cp.line_item_id::text||cp.def_code||coalesce(cp.param_values::text,''), ',' ORDER BY cp.id)),'<empty>') FROM quotation_line_composite_process cp JOIN quotation_line_item li ON li.id=cp.line_item_id WHERE li.quotation_id='${qid}');`
  )[0];
  return { processCount: Number(r[0]), processMd5: r[1], compositeCount: Number(r[2]), compositeMd5: r[3] };
}

export function queryQuotationHeader(qid: string) {
  const r = psqlRows(
    `SELECT status, coalesce(project_name,''), coalesce(total_amount::text,''), coalesce(original_amount::text,'') FROM quotation WHERE id='${qid}';`
  )[0];
  if (!r) throw new Error(`[task260901] 报价单 ${qid} 不存在`);
  return { status: r[0], projectName: r[1], totalAmount: r[2], originalAmount: r[3] };
}

/** 该单按 sort_order 的第 n 行（0-based）的 id + 料号。用于精确定位一张产品卡。 */
export function queryLineAt(qid: string, n: number): { id: string; partNo: string; sortOrder: number } {
  const r = psqlRows(
    `SELECT id::text, coalesce(product_part_no_snapshot,''), sort_order FROM quotation_line_item ` +
    `WHERE quotation_id='${qid}' ORDER BY sort_order LIMIT 1 OFFSET ${n};`
  )[0];
  if (!r) throw new Error(`[task260901] 报价单 ${qid} 没有第 ${n + 1} 行`);
  return { id: r[0], partNo: r[1], sortOrder: Number(r[2]) };
}

/** 读某行某组件的 row_data 原文（用于还原 / 比对）。 */
export function queryRowDataOf(lineItemId: string, tabName: string): string {
  return psqlScalar(
    `SELECT coalesce(row_data::text,'') FROM quotation_line_component_data ` +
    `WHERE line_item_id='${lineItemId}' AND tab_name='${tabName}' LIMIT 1;`
  );
}

/**
 * AC-17（2026-09-01 修正版）的判据：某个 `line_item id` 在库中是否**唯一存在**。
 * 原判据「该产品只有 1 行」在本库不可判定 —— `CUST-0004` 的 1845 个映射料号已被基准单用满，
 * 空闲料号为 0，任何新增都必然与既有行同料号。改按 id 计数后既能防住重复插入，又不受料号占满影响。
 */
export function queryLineItemCountById(lineItemId: string): number {
  return Number(psqlScalar(`SELECT count(*) FROM quotation_line_item WHERE id='${lineItemId}';`));
}

/** 该单的 annual_volume（AC-21）。 */
export function queryAnnualVolumes(qid: string): number[] {
  return psqlRows(`SELECT coalesce(annual_volume,-1) FROM quotation_line_item WHERE quotation_id='${qid}' ORDER BY sort_order;`)
    .map((r) => Number(r[0]));
}

// ──────────────────────────────────────────────────────────────────────────
// 2. 页面操作
// ──────────────────────────────────────────────────────────────────────────

/**
 * 直接走 UI 登录，**不用** `fixtures/auth.ts` 的 storageState 路径。
 *
 * 🚨 2026-09-01 实测教训：`auth.ts` 的 storageState 分支里有一句无超时的
 * `await page.waitForLoadState('networkidle')`。本项目的页面持续有后台请求，
 * networkidle 可能永远不达成 —— 于是它会一直挂到 context 的默认超时，
 * 现象是「用例卡住、日志停在最后一行 console.log、不报错」，极易被误判成产品卡死。
 * 一个不含框架的最小探针走 UI 登录只要 **1.3 s**，那才是这条链路真实的成本。
 */
/**
 * 带重试的后端就绪探测。
 *
 * 🚨 2026-09-01 实测教训：项目既有的 `isBackendUp()` 只探一次、超时 3 s。
 * 而 worktree 的 `quarkus:dev` 每次热重载要 **9.5 s**（实测
 * `Live reload total time: 9.593s`），期间 `/api/cpq/health` 不可达 ⇒ 探测失败 ⇒
 * `test.skip(!backendUp)` 把用例**静默跳过**。
 * 🚫 skip 在报告里长得像「不适用」，但它既不是通过也不是失败 ——
 *    一次热重载抖动就能让一条用例凭空消失，而且不会有人发现。
 * 故这里最多等 90 s、每 3 s 一次，把「正在重载」和「真的没起来」区分开。
 */
export async function waitBackendUp(timeoutMs = 90_000): Promise<boolean> {
  const backend = process.env.PW_BACKEND_URL || 'http://localhost:8099';
  const deadline = Date.now() + timeoutMs;
  let attempts = 0;
  while (Date.now() < deadline) {
    attempts++;
    try {
      const res = await fetch(`${backend}/api/cpq/health`, { signal: AbortSignal.timeout(3000) });
      if (res.ok) {
        if (attempts > 1) console.log(`[task260901] 后端在第 ${attempts} 次探测就绪（期间应是热重载）`);
        return true;
      }
    } catch { /* 重载期间连不上属正常，继续等 */ }
    await new Promise((r) => setTimeout(r, 3000));
  }
  console.error(`[task260901] 🚨 后端 ${backend} 在 ${timeoutMs}ms 内未就绪 —— 用例将被 skip，而 skip 不是通过`);
  return false;
}

export async function loginAdmin(page: Page) {
  await page.goto('/login');
  const user = page.locator('input[placeholder="用户名或邮箱"]');
  // ⚠️ 已处于登录态时 /login 会被重定向走，登录表单根本不渲染 —— 直接 fill 会白等到超时
  //    （2026-09-01 T-5 实测：locator.fill 等了 120 s，前三条用例却都登录成功）。
  //    两种态都接受：有表单就登录，没表单但已不在 /login 就视为已登录。
  const formShown = await user.waitFor({ state: 'visible', timeout: 20_000 }).then(() => true).catch(() => false);
  if (!formShown) {
    if (!/\/login/.test(page.url())) {
      console.log(`[task260901] 已处于登录态（URL=${page.url()}），跳过登录`);
      return;
    }
    throw new Error(`登录页未渲染出用户名输入框，当前 URL=${page.url()}`);
  }
  await user.fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 60_000 });
  if (page.url().includes('/change-password')) await page.goto('/dashboard');
}

/**
 * 打开编辑页并进入 Step2。
 *
 * 🚨 **只用 DOM 信号，绝不等 networkidle。**
 * 2026-09-01 用最小探针实测这条路径的真实成本（1845 行基准单、worktree 冷启动后端）：
 *   navigation committed 1.4 s → 「下一步」可见 2.2 s → getById 200 回来 9.2 s
 *   → 点下一步 → Segmented 可见、产品卡 10 张 11.3 s → 全流程 **11.3 秒**。
 * 而 `waitForLoadState('networkidle')` 在这张页上**永远不会达成**（持续后台请求），
 * 早先版本因此挂死且不报错。task-260825 的夹具注释其实已经记过这个坑，我照抄了它的坏模式。
 */
export async function openEditStep2(page: Page, qid: string) {
  await page.goto(`/quotations/${qid}/edit`, { timeout: 240_000, waitUntil: 'commit' });
  const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  await expect(nextBtn, '编辑向导应就绪（「下一步」可见）').toBeVisible({ timeout: 240_000 });
  await expect(nextBtn, '「下一步」应可点').toBeEnabled({ timeout: 240_000 });
  await nextBtn.click();
  await expect(page.locator('.ant-segmented').first(), 'Step2 应渲染出主 Segmented')
    .toBeVisible({ timeout: 240_000 });
  // 产品卡是 Step2 真正就绪的信号（0 行单没有卡片，故容忍缺席）
  await page.locator('.qt-product-card').first().waitFor({ state: 'visible', timeout: 120_000 })
    .catch(() => console.log('[task260901] 该单无产品卡（0 行单属正常）'));
  await page.waitForTimeout(800);
}

function escapeRegExp(v: string): string {
  return v.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * 按料号定位唯一产品卡。
 *
 * 🚨 **不要用 `.qt-sku-badge` 里的「料号: xxx」正则**（`fixtures/precision.ts` /
 * `fixtures/task260825-paging.ts` 至今仍是那个写法，但它对当前 UI 已经失效）。
 * 2026-09-01 探针实测该徽标的真实文本是 **「客户产品编号: A1409」** —— 里面根本没有「料号:」，
 * 严格正则命中 0 张，而宽松包含命中 1 张。表现是 `toHaveCount(1)` 超时 30 s，
 * 极易被误读成「页面没渲染出来」。
 *
 * 改按整卡文本匹配料号：本项目料号形如 `202601\d{6}`，**定长 12 位**，
 * 同一张单内互不为前缀（实测基准单 1845 行 / 1845 个不同料号），故不存在子串误命中。
 */
export async function cardByPartNo(page: Page, partNo: string): Promise<Locator> {
  const cards = page.locator('.qt-product-card').filter({
    hasText: new RegExp(`(?<!\\d)${escapeRegExp(partNo)}(?!\\d)`),
  });
  await expect(cards, `料号 ${partNo} 应唯一对应一张可见产品卡`).toHaveCount(1, { timeout: 60_000 });
  const card = cards.first();
  await card.scrollIntoViewIfNeeded();
  return card;
}

/** 切到产品卡内的某个页签。 */
export async function openCardTab(page: Page, card: Locator, tabName: string) {
  const tab = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^${escapeRegExp(tabName)}$`) });
  await expect(tab, `产品卡内页签「${tabName}」必须唯一存在`).toHaveCount(1, { timeout: 20_000 });
  await tab.click();
  await expect(page.getByText('加载中', { exact: false })).toHaveCount(0, { timeout: 30_000 });
  await page.waitForTimeout(400);
}

async function activeTable(card: Locator): Promise<Locator> {
  const tables = card.locator('table.qt-cost-table:visible');
  await expect(tables, '当前产品卡应恰有一张可见卡片表格').toHaveCount(1, { timeout: 20_000 });
  return tables.first();
}

/** 按表头文本解析列下标；找不到就**硬失败并打印实际表头**（不静默跳过）。 */
async function columnIndexOf(card: Locator, columnName: string): Promise<number> {
  const table = await activeTable(card);
  const headers = await table.locator('thead tr').last().locator('th').allInnerTexts();
  const idx = headers.findIndex((t) => t.replace(/\s+/g, ' ').trim() === columnName);
  if (idx < 0) {
    throw new Error(`当前页签缺少列「${columnName}」；实际表头 = ${JSON.stringify(headers)}`);
  }
  return idx;
}

/** 读某行某列的当前输入值（用于"改成一个确实不同的值"以及还原）。 */
export async function readCellInputValue(card: Locator, rowIndex: number, columnName: string): Promise<string> {
  const table = await activeTable(card);
  const col = await columnIndexOf(card, columnName);
  const input = table.locator('tbody tr').nth(rowIndex).locator('td').nth(col).locator('input');
  await expect(input, `第 ${rowIndex + 1} 行「${columnName}」应是可编辑输入框`).toHaveCount(1, { timeout: 20_000 });
  return input.inputValue();
}

/**
 * 修改某行某列的值并失焦，**并等这次单元格写入真正落库**。
 *
 * 🚨 2026-09-01 实测（T-1 三跑）：失焦会触发 `PUT /quote-card-edit`，该端点写 row_data 属用户数据，
 *    按 `api.md §4.1` 会把 `user_data_version` +1。在 1845 行单上这个往返要 **5~7 秒**。
 *    早先只 `waitForTimeout(1200)` 就点「保存草稿」，前端本地版本基线还停在旧值 →
 *    `baseVersion=0` 打到 `user_data_version=1` 的库上 → **409 STALE_VERSION**，
 *    现象酷似「并发冲突」，实则是自己跟自己抢。证据：
 *      07:34:07 [subtotal-single-source]（编辑写入）→ 07:34:10 [saveDraft-stale] baseVersion=0
 *    对照 T-14（显式 await 了编辑响应）：07:36:05 编辑 → 07:36:12 Saved draft userDataVersion=3，200。
 *
 * ⚠️ 这同时是一个**产品侧的真实竞态**，已单独上报：真人若在单元格失焦后数秒内点保存，
 *    会看到「这张报价单已被他人修改」——而根本没有第二个人。本函数只负责让用例可判定，
 *    **不掩盖该问题**（专门的复现见 T-14 的对照与报告中的记录）。
 */
export async function editCell(page: Page, card: Locator, rowIndex: number, columnName: string, value: string) {
  const table = await activeTable(card);
  const col = await columnIndexOf(card, columnName);
  const input = table.locator('tbody tr').nth(rowIndex).locator('td').nth(col).locator('input');
  await expect(input, `第 ${rowIndex + 1} 行「${columnName}」应是可编辑输入框`).toHaveCount(1, { timeout: 20_000 });

  // 先挂上等待，再失焦 —— 反过来会漏掉快速返回的响应
  const editWaiter = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && r.url().includes('/quote-card-edit'),
    { timeout: 120_000 }
  ).catch(() => null);

  await input.fill(value);
  await input.blur();

  const resp = await editWaiter;
  if (resp) {
    let ver: unknown = undefined;
    try { ver = (await resp.json())?.data?.userDataVersion; } catch { /* ignore */ }
    console.log(`[task260901] 单元格写入 PUT /quote-card-edit → ${resp.status()}，userDataVersion=${ver}`);
    expect(resp.status(), '单元格写入应成功，否则后续保存的前提就不成立').toBe(200);
  } else {
    console.log(`[task260901] 「${columnName}」未触发 quote-card-edit（该字段不走单元格级端点），改由 saveDraft 承载`);
  }
  // 给前端应用新版本号 / 重算留一点时间
  await page.waitForTimeout(1500);

  const after = await input.inputValue();
  expect(after.trim(), `「${columnName}」失焦后输入框应保留新值 ${value}（回退 = 受控 input 假死，见 AP-54）`).toBe(value);
}

/** 给一个"确定与当前值不同"的新值 —— 防止第二次跑用例时因值相同触发 AC-5「无改动不发请求」而假红。 */
export function distinctFrom(current: string, preferred = AC1_TARGET_VALUE): string {
  const norm = (s: string) => String(s ?? '').trim();
  if (norm(current) !== norm(preferred)) return preferred;
  return '3.6';
}

/**
 * 通过「添加产品 ▾ → 从已有产品添加」抽屉加入第 1 个产品，返回它的销售料号。
 *
 * 🚨 两个必须知道的点（2026-09-01 截图实测）：
 *  1. **不能走「选配添加」** —— dev 库 `sel_template` 全库 0 行，那个抽屉必然空态「缺少选配模板」。
 *  2. **抽屉打开后列表是空的，必须先点「查询」** —— 打开时表格区显示
 *     「未查到匹配的产品，请调整过滤条件后重试」。早先直接等 `tbody tr input[type=checkbox]`
 *     会白等 30 s 然后失败，现象酷似「抽屉没数据」，其实只是没触发查询。
 *
 * 数据来源是当前报价单客户的 `material_customer_map`（CUST-0004 实测 1845 行），不会为空。
 */
export async function addFirstExistingProduct(page: Page): Promise<string> {
  const addBtn = page.getByRole('button', { name: /添加产品/ }).first();
  await expect(addBtn, '「添加产品」按钮应可见').toBeVisible({ timeout: 60_000 });
  await addBtn.click();
  await page.waitForTimeout(800);

  const menuItems = await page.locator('.ant-dropdown-menu-item:visible').allInnerTexts();
  console.log(`[task260901] 「添加产品」下拉项 = ${JSON.stringify(menuItems)}`);
  const entry = page.locator('.ant-dropdown-menu-item:visible').filter({ hasText: /已有产品/ }).first();
  await expect(entry, `未找到「从已有产品添加」菜单项；实际 = ${JSON.stringify(menuItems)}`)
    .toBeVisible({ timeout: 15_000 });
  await entry.click();

  const drawer = page.locator('.ant-drawer').last();
  await expect(drawer, '「从已有产品添加」抽屉应打开').toBeVisible({ timeout: 60_000 });

  // 🔑 关键一步：点「查询」把列表拉出来
  const searchBtn = drawer.getByRole('button', { name: /^查\s*询$/ }).first();
  await expect(searchBtn, '抽屉应有「查询」按钮').toBeVisible({ timeout: 30_000 });
  await searchBtn.click();

  // antd Table 的数据行是 .ant-table-row（不是裸 tbody tr —— 还有 measure-row / placeholder 行）
  const firstRow = drawer.locator('.ant-table-row').first();
  await expect(firstRow,
    '点「查询」后抽屉产品列表应出现数据行（该客户 material_customer_map 实测 1845 行，不应为空）'
  ).toBeVisible({ timeout: 60_000 });

  const rowText = await firstRow.innerText();
  const partNo = rowText.match(/\d{12}/)?.[0] ?? '';
  console.log(`[task260901] 抽屉第 1 行 = ${JSON.stringify(rowText.replace(/\n/g, ' | ').slice(0, 120))}，解析料号 = ${partNo || '<失败>'}`);

  // 🚨 antd 的行选择框：真正可点的是 .ant-checkbox-input（外面裹着 .ant-checkbox-wrapper），
  //    裸 input[type=checkbox] 被样式盖住，check() 会判定不可交互。
  //    依次退化：.ant-checkbox-input → .ant-checkbox → 点行本身（部分表格配了 onRow.onClick 选中）。
  const selected = async () => (await drawer.innerText()).match(/已选\s*(\d+)\s*项/)?.[1] ?? '0';
  const cbInput = firstRow.locator('.ant-checkbox-input').first();
  if (await cbInput.count() > 0) {
    await cbInput.check({ force: true }).catch(async () => {
      await firstRow.locator('.ant-checkbox').first().click({ force: true });
    });
  } else {
    await firstRow.click();
  }
  await page.waitForTimeout(600);
  let n = await selected();
  if (n === '0') {   // 再退化一次：直接点行
    console.log('[task260901] 勾选框未生效，退化为点击整行');
    await firstRow.click();
    await page.waitForTimeout(600);
    n = await selected();
  }
  console.log(`[task260901] 抽屉底部「已选 N 项」= ${n}`);
  expect(Number(n), '勾选后抽屉底部应显示「已选 1 项」，否则「加入报价单」不会启用').toBeGreaterThan(0);

  const confirm = drawer.getByRole('button', { name: /加入报价单/ }).last();
  await expect(confirm, '抽屉应有「加入报价单」按钮且可点（已选 ≥1 项）').toBeEnabled({ timeout: 30_000 });
  await confirm.click();
  await expect(drawer, '加入后抽屉应关闭').toBeHidden({ timeout: 60_000 });
  await page.waitForTimeout(3000);
  return partNo;
}

// ──────────────────────────────────────────────────────────────────────────
// 3. 保存草稿 —— 捕获请求 / 响应（AC-1 ~ AC-5 / AC-15 ~ AC-17 的证据来源）
// ──────────────────────────────────────────────────────────────────────────

export interface DraftCapture {
  request: Request;
  response: Response;
  status: number;
  /** 请求体 JSON（api.md §1.2） */
  payload: any;
  /** 请求体字节数（AC-1 < 100 KB） */
  requestBytes: number;
  /** 响应体原文字节数（AC-15 < 500 KB） */
  responseBytes: number;
  responseBody: any;
  /** 从点击到 PUT /draft 响应到达的毫秒数 */
  putMs: number;
}

const DRAFT_URL_RE = /\/quotations\/[^/]+\/draft(?:\?|$)/;

export async function clickSaveDraft(page: Page) {
  const buttons = page.getByRole('button', { name: /保存草稿$/ });
  await expect(buttons, '编辑页应唯一存在「保存草稿」按钮').toHaveCount(1, { timeout: 30_000 });
  const btn = buttons.first();
  // ⚠️ AC-5 要求「什么都不改直接点保存草稿」时**弹出提示**，即按钮在无改动时也必须可点。
  //    若这里因 disabled 而失败，那是实现把按钮置灰了 —— 属 AC-5 的实现偏差，不是选择器问题。
  await expect(btn, '「保存草稿」按钮应可点击（AC-5 要求无改动时点击也给提示，故不得置灰）')
    .toBeEnabled({ timeout: 30_000 });
  await btn.click();
}

/**
 * 点「保存草稿」并捕获 PUT /draft 的请求与响应。
 * @param expectStatus 期望的 HTTP 状态码（AC-12 场景传 409）
 */
export async function saveDraftCapture(page: Page, expectStatus = 200): Promise<DraftCapture> {
  const waiter = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && DRAFT_URL_RE.test(r.url()),
    { timeout: 180_000 }
  );
  const t0 = Date.now();
  await clickSaveDraft(page);
  const response = await waiter;
  const putMs = Date.now() - t0;

  const request = response.request();
  const raw = request.postData() ?? '';
  const payload = raw ? JSON.parse(raw) : {};
  const bodyText = await response.text();
  let responseBody: any = null;
  try { responseBody = JSON.parse(bodyText); } catch { responseBody = bodyText; }

  console.log(
    `[task260901] PUT /draft → ${response.status()}，请求体 ${Buffer.byteLength(raw, 'utf8')} B，` +
    `响应体 ${Buffer.byteLength(bodyText, 'utf8')} B，耗时 ${putMs} ms`
  );

  expect(response.status(), `PUT /draft HTTP 状态码（响应体前 500 字：${bodyText.slice(0, 500)}）`).toBe(expectStatus);

  return {
    request,
    response,
    status: response.status(),
    payload,
    requestBytes: Buffer.byteLength(raw, 'utf8'),
    responseBytes: Buffer.byteLength(bodyText, 'utf8'),
    responseBody,
    putMs,
  };
}

/**
 * 点「保存草稿」但**期望没有 PUT /draft 发出**（AC-5 / AC-24），
 * 并在点击后**立刻**把 toast 文案抓下来。
 *
 * 🚨 2026-09-01 教训：早先的写法是「点 → 等满 10 s 观察窗口 → 再找 toast」。
 *    antd `message` 默认只显示 3 s，等窗口走完再断言，找的是一个早就消失的元素 ——
 *    表现为「页面没提示」，极易误判成 AC-5 未实现。**瞬时反馈必须在触发后立刻抓。**
 *
 * @returns seen: 观察窗口内出现的 PUT /draft；toast: 点击后 6 s 内出现过的提示文案（没有则空串）
 */
export async function clickSaveDraftExpectNoRequest(
  page: Page, windowMs = 8000
): Promise<{ seen: string[]; toast: string }> {
  const seen: string[] = [];
  const onReq = (r: Request) => {
    if (r.method() === 'PUT' && DRAFT_URL_RE.test(r.url())) seen.push(`${r.method()} ${r.url()}`);
  };
  page.on('request', onReq);
  let toast = '';
  try {
    // 先挂 toast 等待，再点击 —— 反过来会漏掉秒现秒消的提示
    const toastLoc = page.locator('.ant-message-notice-content, .ant-notification-notice').first();
    const toastWaiter = toastLoc.waitFor({ state: 'visible', timeout: 6000 })
      .then(() => toastLoc.innerText())
      .catch(() => '');
    await clickSaveDraft(page);
    toast = (await toastWaiter) || '';
    if (toast) console.log(`[task260901] 保存草稿后的提示 = ${JSON.stringify(toast.replace(/\n/g, ' '))}`);
    else console.log('[task260901] 保存草稿后 6 s 内未出现任何 toast');
    await page.waitForTimeout(windowMs);
  } finally {
    page.off('request', onReq);
  }
  return { seen, toast };
}

/** 统计一次操作期间的 ensure-card-values 请求（AC-8 / AC-18）。 */
export function watchEnsureCardValues(page: Page) {
  const rec = { count: 0, totalMs: 0, lastEndAt: 0, urls: [] as string[] };
  page.on('response', (r) => {
    if (r.request().method() === 'POST' && r.url().includes('/ensure-card-values')) {
      rec.count++;
      rec.lastEndAt = Date.now();
      rec.urls.push(r.url());
    }
  });
  return rec;
}

// ──────────────────────────────────────────────────────────────────────────
// 4. API 直调（AC-12 构造「他人修改」；AC-11/AC-14 读版本号）
// ──────────────────────────────────────────────────────────────────────────

/** GET /api/cpq/quotations/{id}，复用浏览器 cookie。 */
export async function apiGetQuotation(page: Page, qid: string): Promise<any> {
  const res = await page.request.get(`/api/cpq/quotations/${qid}`, { timeout: 180_000 });
  expect(res.status(), `GET /quotations/${qid}`).toBe(200);
  const body = await res.json();
  expect(body?.code, 'GET 返回 code').toBe(200);
  return body.data;
}

/**
 * 直接发一次 PUT /draft（api.md §1.2 增量协议），用于 AC-12 模拟「会话 A 保存」。
 * 只改单头字段、三数组全空 —— 与 AC-4 同型，最小影响面。
 */
export async function apiSaveDraftHeaderOnly(
  page: Page, qid: string, baseVersion: number, projectName: string
): Promise<any> {
  const res = await page.request.put(`/api/cpq/quotations/${qid}/draft`, {
    data: { baseVersion, projectName, added: [], modified: [], removed: [] },
    timeout: 180_000,
  });
  const text = await res.text();
  expect(res.status(), `会话 A 的 PUT /draft 应成功（响应：${text.slice(0, 500)}）`).toBe(200);
  return JSON.parse(text).data;
}

// ──────────────────────────────────────────────────────────────────────────
// 5. 证据归档（🚨 test-results/ 每轮开跑会被清空，截图必须落任务目录）
// ──────────────────────────────────────────────────────────────────────────

fs.mkdirSync(EVIDENCE_DIR, { recursive: true });

export async function archiveShot(page: Page, name: string): Promise<string> {
  const file = path.join(EVIDENCE_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: false });
  console.log(`📸 证据归档 → ${file}`);
  return file;
}

export function archiveJson(name: string, data: unknown): string {
  const file = path.join(EVIDENCE_DIR, `${name}.json`);
  fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf-8');
  console.log(`🧾 证据归档 → ${file}`);
  return file;
}

// ──────────────────────────────────────────────────────────────────────────
// 6. 前置校验（🚫 样本漂了就硬失败，不许带着错误分母往下跑）
// ──────────────────────────────────────────────────────────────────────────

export function assertBaselineShape() {
  const lines = queryLineItemCount(BASELINE_QUOTATION_ID);
  const cds = queryComponentDataCount(BASELINE_QUOTATION_ID);
  const header = queryQuotationHeader(BASELINE_QUOTATION_ID);
  console.log(`[task260901] 基准单 ${BASELINE_QUOTATION_NO}: status=${header.status} lines=${lines} componentData=${cds}`);
  expect(header.status, `基准单必须仍是 DRAFT（实为 ${header.status}）`).toBe('DRAFT');
  expect(lines, `基准单必须仍是 ${BASELINE_LINE_COUNT} 行，否则 AC-6/AC-7/AC-18 的判据全部失真`).toBe(BASELINE_LINE_COUNT);
  expect(cds, `基准单 componentData 必须仍是 ${BASELINE_COMPONENT_DATA_COUNT} 条`).toBe(BASELINE_COMPONENT_DATA_COUNT);
}

export function assertSandboxShape() {
  const header = queryQuotationHeader(SANDBOX_QUOTATION_ID);
  const lines = queryLineItemCount(SANDBOX_QUOTATION_ID);
  console.log(`[task260901] 沙箱单 ${SANDBOX_QUOTATION_NO}: status=${header.status} lines=${lines}`);
  expect(header.status, `沙箱单必须是 DRAFT（实为 ${header.status}）`).toBe('DRAFT');
  expect(lines, '沙箱单应有行数据（>0），否则增删行用例的前置为空').toBeGreaterThan(0);
}
