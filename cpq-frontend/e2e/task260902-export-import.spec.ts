/**
 * task-260902 · E2E（仅两条，AC-21 / AC-22）。
 *
 *   T-21  → AC-21  用户导入 → 取回显初始密码 → 登出 → 用新账号登录 → 强制改密 → 回到用户列表看状态
 *   T-22  → AC-22  材质页签筛选态在「切走再切回页签」后不错位，且导出参数始终跟着筛选走
 *   T-23b → AC-23  三页禁用态 tooltip 文案（2026-09-02 裁决：AntD Tooltip 走 Portal，
 *                  组件层 SSR 看不到 ⇒ 文案这半句落 E2E，🚫 不改实现去迁就测试）
 *   T-27  → AC-27  连点两次导出：loading 态 + **不发第二个请求** + 两次内容一致 + 期间不阻塞
 *                  （2026-09-02 裁决：本项目无 jsdom/RTL，组件层点不了 ⇒ 落 E2E）
 *
 * 🚫 本文件**不往既有 spec 里加用例**（quotation-flow.spec.ts 等）。
 * ⚠️ 已知：干净 master 上 `quotation-flow.spec.ts` 有 3 条失败（夹具漂移），**与本任务无关**，不要误归因。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 🚨 全局状态登记（testing.md §4.3）
 * ─────────────────────────────────────────────────────────────────────────────
 * 本套用例**会写共享库**：
 *   · 新建 3 个用户 `t260902a/b/c`（AC-21 必须真建，否则登不了）
 *   · 其中 `t260902a` 会被改密（改的是本套自建账号，不碰任何真实账号）
 * ⇒ `test.afterAll` 里按 `username LIKE 't260902%'` 精确删除，并做**残留自检**（残留即硬失败）。
 * 🚫 不清库、不 TRUNCATE、不改 admin 的状态/密码/角色。
 *   （历史教训：E2E 反复跑把 admin 置成 INACTIVE，之后所有用例连同真人操作一起坏，
 *     而症状看起来**非常像业务回归**。本文件一个字都不动 admin 的行。）
 *
 * 运行：
 *   npx playwright test e2e/task260902-export-import.spec.ts --reporter=list
 *   （默认打 5174/8081；要避开主线亲验环境时用 PW_BASE_URL / PW_BACKEND_URL 指到临时端口）
 */
import { test, expect, Page, Download } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import * as XLSX from 'xlsx';

const __f = fileURLToPath(import.meta.url);

/** 证据归档目录 —— 🚨 不写 test-results/（下一轮开跑会被清空 ⇒ 留在那里等于没有证据，testing.md §2）。 */
const EVIDENCE_DIR = path.resolve(
  path.dirname(__f),
  '../../dev-docs/task-260902-主数据与用户导入导出/证据'
);
/** 下载文件的落地目录（也归档，导出内容本身就是 AC-22 的证据）。 */
const DOWNLOAD_DIR = path.join(EVIDENCE_DIR, 'downloads');

const ADMIN = 'admin';
const ADMIN_PWD = 'Admin@2026';
const PREFIX = 't260902';
const NEW_USER = 't260902a';
const NEW_PWD = 'T260902@New1';
const XLSX_MIME_E2E =
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

const DB_NAME = process.env.PW_DB || 'cpq_db_0724';
const DB_HOST = process.env.PW_DB_HOST || '10.177.152.12';
const DB_USER = process.env.PW_DB_USER || 'postgres';
const DB_PASS = process.env.PW_DB_PASS || 'joii5231';

// ─────────────────────────────────────────────
// 只读 / 收敛写 SQL
// ─────────────────────────────────────────────
function sql(q: string): string[] {
  const out = execFileSync('psql', ['-h', DB_HOST, '-U', DB_USER, '-d', DB_NAME, '-tAF', '\t', '-c', q],
    { env: { ...process.env, PGPASSWORD: DB_PASS }, encoding: 'utf-8' });
  return out.split('\n').map((s) => s.trim()).filter(Boolean);
}
function sqlOne(q: string): string | null {
  const r = sql(q);
  return r.length ? r[0] : null;
}

/**
 * 基准查询②：材质导出（**不筛选**）应含的数据行数（🚫 不写死 621，共享库在漂移）。
 * ⚠️ 2026-09-02 用户裁决：**材质不限状态**（与页面列表口径一致），配置只取 ACTIVE。
 */
function baseRecipeElementRows(): number {
  return Number(sqlOne(`
    SELECT count(*) FROM material_recipe_element e
      JOIN material_recipe_config c ON c.id = e.config_id AND c.status='ACTIVE'
      JOIN material_recipe r ON r.id = c.recipe_id`));
}

/** 还原：只删本套自建用户（前缀限定），先摘引用行再删主行，否则 FK 违例 → 下一轮恒红。 */
function restoreGlobalState() {
  sql(`
    DELETE FROM operation_log WHERE operator_id IN (
      SELECT id FROM "user" WHERE username LIKE '${PREFIX}%');
    DELETE FROM notification WHERE recipient_id IN (
      SELECT id FROM "user" WHERE username LIKE '${PREFIX}%');
    DELETE FROM "user" WHERE username LIKE '${PREFIX}%';
  `);
}
function assertNoResidue() {
  const n = sqlOne(`SELECT count(*) FROM "user" WHERE username LIKE '${PREFIX}%'`);
  expect(n, `还原自检：仍有 ${n} 个 ${PREFIX}% 测试用户残留`).toBe('0');
}

// ─────────────────────────────────────────────
// 证据
// ─────────────────────────────────────────────
let shotIdx = 0;
async function shot(page: Page, name: string) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `E2E-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: false });
  console.log(`📸 证据 → ${file}`);
  return file;
}
function evidence(name: string, content: string) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `E2E-${name}.txt`);
  fs.writeFileSync(file, content, 'utf-8');
  console.log(`🧾 证据 → ${file}`);
  return file;
}

// ─────────────────────────────────────────────
// 登录 / 登出
// ─────────────────────────────────────────────
/**
 * 走 UI 登录。选择器取自 `e2e/global-setup.ts` 的既有约定。
 * ⚠️ 登录限流 30 次/分/IP —— 打满后表现为「登录失败」，**看起来像鉴权坏了**，
 *    实际是测试基础设施问题（task-260901 2026-09-02 实跑踩到）。这里给出明确区分。
 */
async function uiLogin(page: Page, username: string, password: string) {
  // 🚨 结构性修复：先**等登录框渲染出来**再 fill，并整体重试一次。
  //    症状：`locator.fill: Timeout` —— vite dev server 在连续整页 goto 下偶发变慢，
  //    输入框还没挂载 fill 就打上去了。这**不是产品缺陷**：仓库自带的 `global-setup.ts`
  //    在同一次运行里对 alice/bob 撞的是**一模一样**的报错（2026-09-03 实测），
  //    与本任务代码无关。不修的话它会伪装成「登录页坏了」。
  for (let attempt = 1; attempt <= 2; attempt++) {
    await page.goto('/login', { waitUntil: 'domcontentloaded' });
    const userBox = page.locator('input[placeholder="用户名或邮箱"]');
    try {
      await expect(userBox).toBeVisible({ timeout: 20_000 });
      break;
    } catch (e) {
      if (attempt === 2) throw new Error(
        `[task260902] 登录页两次都没渲染出输入框（URL=${page.url()}）—— ` +
        '测试基础设施问题（dev server 慢），不是产品缺陷');
      console.warn('[uiLogin] 登录页未就绪，重试一次');
    }
  }
  await page.locator('input[placeholder="用户名或邮箱"]').fill(username);
  await page.locator('input[placeholder="密码"]').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|master-data|change-password)/, {
    timeout: 20_000,
  }).catch(async () => {
    const body = await page.locator('body').innerText().catch(() => '');
    throw new Error(
      `[task260902] ${username} 登录后没跳转，当前 URL=${page.url()}\n` +
      `页面文案片段：${body.slice(0, 300)}\n` +
      '若含「过于频繁」= 登录限流（测试基础设施问题，不是产品缺陷）；否则才是真失败。'
    );
  });
}

async function uiLogout(page: Page) {
  // 退出入口在不同布局下位置不同 —— 直接清会话 cookie 再回 /login，等价且稳定。
  await page.context().clearCookies();
  await page.goto('/login');
  await expect(page.locator('input[placeholder="用户名或邮箱"]'),
    '登出后应回到登录页').toBeVisible({ timeout: 15_000 });
}

// ─────────────────────────────────────────────
// 通用定位
// ─────────────────────────────────────────────
/**
 * 🚨 AntD 会给**恰好两个中文字**的按钮插空格（「刷新」→「刷 新」）。
 * 用精确文本找 2 字按钮会恒定 not found，而 not found 长得像「按钮没做」。
 * ⇒ 一律按「去掉空白后相等」匹配。
 */
function btn(page: Page, text: string) {
  return page.locator('button').filter({
    has: page.locator(`xpath=.//*[normalize-space(translate(text(), " ", ""))="${text}"]`),
  }).or(page.getByRole('button', { name: new RegExp('^\\s*' + text.split('').join('\\s*') + '\\s*$') }))
    .first();
}

/** 按邻近文本找下拉（🚫 不按 nth 下标 —— 顺序一变就静默选错，症状是「候选为空」）。 */
async function selectNear(page: Page, labelText: string) {
  const sels = page.locator('.ant-select');
  const n = await sels.count();
  expect(n, `页面上应有下拉框（0 个 = 定位错了）`).toBeGreaterThan(0);
  const seen: string[] = [];
  for (let i = 0; i < n; i++) {
    const ctx: string = await sels.nth(i).evaluate((el: Element) => {
      let node: Element | null = el;
      for (let d = 0; d < 5 && node; d++) {
        node = node.parentElement;
        const t = (node?.textContent || '').trim();
        if (t.length > 1) return t;
      }
      return '';
    });
    seen.push(ctx.slice(0, 30));
    if (ctx.includes(labelText)) return sels.nth(i);
  }
  throw new Error(`未找到邻近文本含「${labelText}」的下拉（共 ${n} 个）：${JSON.stringify(seen)}`);
}

async function pickOption(page: Page, select: ReturnType<Page['locator']>, optionText: string) {
  await select.click();
  const opt = page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: optionText }).first();
  await expect(opt, `下拉里应有选项「${optionText}」`).toBeVisible({ timeout: 10_000 });
  await opt.click();
}

/**
 * 进「主数据维护 → <tab>」，带一次重试。
 * 🚨 与 uiLogin 同源的结构性修复：Playwright 每个用例都是**全新 browser context**，
 *    要把整站模块重新从 vite dev server 拉一遍；重路由（master-data-hub 挂了十几个页签组件）
 *    偶发慢到分钟级，表现为「页签点不到」——那是**取证环境慢**，不是「页签没渲染」的产品缺陷。
 *    实测同一次运行里服务器本身健康（curl 2ms、load 1.0）、vite 无报错。
 */
async function gotoMasterDataTab(page: Page, tabName: string) {
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      await page.goto('/master-data-hub', { waitUntil: 'domcontentloaded' });
      const tab = page.getByRole('tab', { name: tabName });
      await expect(tab).toBeVisible({ timeout: 45_000 });
      await tab.click();
      await page.waitForSelector('.ant-table', { timeout: 45_000 });
      return;
    } catch (e) {
      if (attempt === 2) throw new Error(
        `[task260902] 两次都进不了「主数据维护 → ${tabName}」（URL=${page.url()}）—— ` +
        '测试环境慢，不是产品缺陷；服务器健康度请看 vite/后端日志');
      console.warn(`[gotoMasterDataTab] 「${tabName}」首次未就绪，重试一次`);
    }
  }
}

/** 进「系统管理 → 用户管理」，带一次重试（与 gotoMasterDataTab 同源的结构性修复）。 */
async function gotoUsersPage(page: Page) {
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      await page.goto('/system/users', { waitUntil: 'domcontentloaded' });
      await expect(page.locator('input[placeholder*="搜索"]').first()).toBeVisible({ timeout: 45_000 });
      return;
    } catch (e) {
      if (attempt === 2) throw new Error(
        `[task260902] 两次都进不了用户管理页（URL=${page.url()}）—— 测试环境慢，不是产品缺陷`);
      console.warn('[gotoUsersPage] 首次未就绪，重试一次');
    }
  }
}

/**
 * 清空一个 AntD Select 的选择。
 * 🚨 **不能用 pickOption(…, '全部')** —— 实测「状态」下拉的选项只有 `["启用","停用"]` 两项，
 *    界面上那个「状态：全部」是**前缀 + 占位符**，不是一个可选项。
 *    按「全部」找选项会恒定 not found，而 not found 长得像「清空筛选做不了」的产品缺陷
 *    （2026-09-02 实跑踩到）。清空要点 antd 的 clear（×）图标。
 */
async function clearSelect(page: Page, select: ReturnType<Page['locator']>, tag: string) {
  await select.hover();                       // clear 图标 hover 才出现
  const clear = select.locator('.ant-select-clear');
  // ⚠️ 未选中任何值时 antd 不渲染 clear 图标 —— 此时「清空」本就是 no-op。
  //    直接硬等 toBeAttached 会在**筛选已被重置**的情况下超时，
  //    把 AC-22 的真失败（筛选被重置）盖成一句「清空按钮不存在」，读报告的人会追错方向。
  if (await clear.count() === 0) {
    console.log(`[${tag}] 该下拉当前没有选中值，无需清空（no-op）`);
    return;
  }
  await clear.click({ force: true });
  await page.waitForTimeout(800);
}

/** 空态判据：0 行 + 出现空态占位。 */
async function expectEmptyState(page: Page, tag: string) {
  // 🚨 三页的空态 DOM **不一致**：材质/用户是 antd 的 `.ant-empty`，
  //    工序页是自定义文案（`.ant-table-placeholder` 里写「未找到匹配"…"的工序数据」）。
  //    只找 `.ant-empty` 会在工序页恒定 not found —— 那是选择器错，不是产品缺陷（2026-09-02 实测）。
  await expect(page.locator('.ant-table-row'), `${tag}：搜不存在的关键词后应 0 行`)
    .toHaveCount(0, { timeout: 20_000 });
  await expect(page.locator('.ant-empty, .ant-table-placeholder').first(),
    `${tag}：应出现空态占位`).toBeVisible({ timeout: 20_000 });
}

/** 同 downloadExport，但额外返回表头行（按列名取值用，避免把列序写死）。 */
async function downloadExportWithHeader(
  page: Page, buttonText: string, tag: string,
): Promise<[string, string[][], string[]]> {
  fs.mkdirSync(DOWNLOAD_DIR, { recursive: true });
  const [dl] = await Promise.all([
    page.waitForEvent('download', { timeout: 60_000 }),
    btn(page, buttonText).click(),
  ]);
  const file = path.join(DOWNLOAD_DIR, `${tag}-${(dl as Download).suggestedFilename()}`);
  await dl.saveAs(file);
  const wb = XLSX.read(fs.readFileSync(file));
  const grid = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]],
    { header: 1, defval: '', raw: true }) as unknown[][];
  const rows = grid.map((r) => r.map((c) => (c === null || c === undefined ? '' : String(c))));
  const header = rows[0] ?? [];
  const data = rows.slice(1).filter((r) => r.some((c) => c.trim() !== ''));
  console.log(`⬇️ ${tag}: ${file}  表头=${JSON.stringify(header)}  数据行=${data.length}`);
  return [file, data, header];
}

/** 点导出按钮并把下载文件落到证据目录，返回 [文件路径, 数据行数]。 */
async function downloadExport(page: Page, buttonText: string, tag: string): Promise<[string, string[][]]> {
  fs.mkdirSync(DOWNLOAD_DIR, { recursive: true });
  const [dl] = await Promise.all([
    page.waitForEvent('download', { timeout: 60_000 }),
    btn(page, buttonText).click(),
  ]);
  const file = path.join(DOWNLOAD_DIR, `${tag}-${(dl as Download).suggestedFilename()}`);
  await dl.saveAs(file);
  const wb = XLSX.read(fs.readFileSync(file));
  const sheet = wb.Sheets[wb.SheetNames[0]];
  const grid = XLSX.utils.sheet_to_json(sheet, { header: 1, defval: '', raw: true }) as unknown[][];
  const rows = grid.map((r) => r.map((c) => (c === null || c === undefined ? '' : String(c))));
  const data = rows.slice(1).filter((r) => r.some((c) => c.trim() !== ''));
  console.log(`⬇️ ${tag}: ${file}  表头=${JSON.stringify(rows[0])}  数据行=${data.length}`);
  return [file, data];
}

// ═══════════════════════════════════════════════════════════════════
// ⚠️ 刻意**不用** `mode:'serial'`：本文件四条用例彼此无状态依赖（T-21 自建自清，其余只读）。
//    serial 下第一条红了会把后面三条**跳过**，报告里显示成「did not run」——
//    那看起来像「没覆盖」，实际是被前一条连坐，会误导读报告的人（2026-09-02 实跑踩到）。

test.beforeAll(() => {
  restoreGlobalState();
  // 🚨 清完立刻自检：脏库必须以「残留」的名义硬失败，不许伪装成业务缺陷。
  assertNoResidue();
});

test.afterAll(() => {
  try {
    restoreGlobalState();
  } finally {
    assertNoResidue();
  }
});

// ═══════════════ T-21 → AC-21：导入 → 登录 → 强制改密 闭环 ═══════════════

test('T-21 / AC-21：导入用户 → 取回显初始密码 → 登出 → 用该密码登录 → 被强制跳转到修改密码页 → 改密后用户列表显示「启用」', async ({ page }) => {
  await uiLogin(page, ADMIN, ADMIN_PWD);
  await gotoUsersPage(page);
  await expect(btn(page, '导入用户'), 'AC-21 前置：用户管理页应有「导入用户」按钮').toBeVisible({ timeout: 20_000 });

  // ── 构造 3 行导入文件（邮箱按用户名派生，避开 user.email 的 UNIQUE 约束）──
  const header = ['用户名', '姓名', '邮箱', '角色', '区域', '部门'];
  const body = [
    ['t260902a', '张明', 't260902a@t260902.invalid', '销售代表', '', ''],
    ['t260902b', '李思', 't260902b@t260902.invalid', '销售经理', '', ''],
    ['t260902c', '王赫', 't260902c@t260902.invalid', '财务', '', ''],
  ];
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([header, ...body]), '用户');
  fs.mkdirSync(DOWNLOAD_DIR, { recursive: true });
  const uploadFile = path.join(DOWNLOAD_DIR, 't260902-用户导入.xlsx');
  fs.writeFileSync(uploadFile, XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' }));

  const before = Number(sqlOne('SELECT count(*) FROM "user"'));

  // ── 打开抽屉 → 选文件 → 开始导入（同时抓接口响应，密码从响应取、也断言 UI 上确实回显了）──
  await btn(page, '导入用户').click();
  const fileInput = page.locator('input[type="file"]').last();
  await expect(fileInput, 'AC-14/AC-21：导入抽屉里应有上传控件').toBeAttached({ timeout: 15_000 });
  await fileInput.setInputFiles(uploadFile);
  await shot(page, 'AC21-01-抽屉已选文件');

  const [resp] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/cpq/users/import') && r.request().method() === 'POST',
      { timeout: 60_000 }),
    btn(page, '开始导入').click(),
  ]);
  expect(resp.status(), 'AC-21：导入接口应 200').toBe(200);
  const payload = await resp.json();
  const report = payload?.data ?? payload;
  console.log('[AC-21] 导入报告 =', JSON.stringify(report));
  evidence('AC21-导入报告', JSON.stringify(report, null, 2));

  expect(report.createdCount, 'AC-15/AC-21：应新增 3 条').toBe(3);
  const after = Number(sqlOne('SELECT count(*) FROM "user"'));
  expect(after - before, 'AC-15：导入前后 基准查询④ 的差值应为 3').toBe(3);

  const created: Array<{ username: string; initialPassword: string }> = report.created ?? [];
  expect(created.length, 'AC-15：created 明细应有 3 条').toBe(3);
  const mine = created.find((c) => c.username === NEW_USER);
  expect(mine, `AC-21：created 明细里应有 ${NEW_USER}，实际 = ${JSON.stringify(created)}`).toBeTruthy();
  const initialPwd = mine!.initialPassword;
  expect(initialPwd, 'AC-15/AC-21：初始密码不能为空').toBeTruthy();
  const distinct = new Set(created.map((c) => c.initialPassword));
  expect(distinct.size, 'AC-15：3 个初始密码必须互不相同').toBe(3);

  // ── 结果页必须把密码**回显在界面上**（AC-15 原文：逐人回显；不是只在响应里有）──
  await shot(page, 'AC21-02-导入结果页');
  const drawerText = await page.locator('body').innerText();
  expect(drawerText.includes(initialPwd),
    `AC-15/AC-21：🚨 结果页必须逐人回显初始密码，页面上找不到 ${NEW_USER} 的密码。` +
    '（密码只显示这一次，界面不显示 = 这个功能等于没做）'
  ).toBe(true);
  expect(drawerText.includes(NEW_USER), 'AC-15：结果页应列出用户名').toBe(true);

  // ── 登出 → 用新账号 + 初始密码登录 ──
  await uiLogout(page);
  await page.locator('input[placeholder="用户名或邮箱"]').fill(NEW_USER);
  await page.locator('input[placeholder="密码"]').fill(initialPwd);
  await page.locator('button[type="submit"]').click();

  await page.waitForURL(/\/change-password/, { timeout: 20_000 }).catch(() => {});
  console.log('[AC-21] 登录后 URL =', page.url());
  await shot(page, 'AC21-03-首登强制改密页');
  expect(page.url(),
    `AC-21：🚨 新用户首登必须被强制跳转到修改密码页（is_first_login=true），实际 URL=${page.url()}`
  ).toMatch(/\/change-password/);

  // ── 改密 ──
  const pwdInputs = page.locator('input[type="password"]');
  // 🚨 必须先等渲染完再数：改密页是路由跳转后异步挂载的，落地 URL 的瞬间 DOM 里可能一个框都没有。
  //    直接 count() 会拿到 0 —— 那是**竞态**，不是「改密页没有输入框」的产品缺陷
  //    （2026-09-03 实跑：同一用例两次跑分别得到 3 和 0）。
  await expect(pwdInputs.first(), 'AC-21：改密页应渲染出密码输入框')
    .toBeVisible({ timeout: 20_000 });
  const cnt = await pwdInputs.count();
  console.log('[AC-21] 改密页密码输入框个数 =', cnt);
  expect(cnt, 'AC-21：改密页应有密码输入框（0 个 = 定位错了，不是产品缺陷）').toBeGreaterThan(0);
  if (cnt >= 3) {
    await pwdInputs.nth(0).fill(initialPwd);
    await pwdInputs.nth(1).fill(NEW_PWD);
    await pwdInputs.nth(2).fill(NEW_PWD);
  } else {
    for (let i = 0; i < cnt; i++) await pwdInputs.nth(i).fill(NEW_PWD);
  }
  await page.locator('button[type="submit"]').first().click()
    .catch(async () => { await btn(page, '确定').click(); });
  await page.waitForURL((u) => !/\/change-password/.test(u.toString()), { timeout: 20_000 })
    .catch(() => {});
  console.log('[AC-21] 改密后 URL =', page.url());
  await shot(page, 'AC21-04-改密后进入系统');
  expect(page.url(), 'AC-21：改密后应离开修改密码页、进入系统').not.toMatch(/\/change-password/);
  expect(page.url(), 'AC-21：改密后不该被踢回登录页').not.toMatch(/\/login/);
  expect(sqlOne(`SELECT is_first_login::text FROM "user" WHERE username='${NEW_USER}'`),
    'AC-21：改密后 is_first_login 应转 false（否则每次登录都会被拦）').toBe('false');

  // ── 换回管理员，用户列表能看到该用户且状态为「启用」──
  await uiLogout(page);
  await uiLogin(page, ADMIN, ADMIN_PWD);
  await gotoUsersPage(page);
  const search = page.locator('input[placeholder*="搜索"]').first();
  await search.fill(NEW_USER);
  await page.keyboard.press('Enter');
  const row = page.locator('.ant-table-row').filter({ hasText: NEW_USER }).first();
  await expect(row, `AC-21：用户列表应能搜到 ${NEW_USER}`).toBeVisible({ timeout: 20_000 });
  const rowText = (await row.innerText()).replace(/\s+/g, '');
  console.log('[AC-21] 用户行 =', rowText);
  await shot(page, 'AC21-05-用户列表状态启用');
  expect(rowText.includes('启用'),
    `AC-21：${NEW_USER} 的状态应显示「启用」，实际行文本 = ${rowText}`).toBe(true);
});

// ═══════════════ T-22 → AC-22：筛选态与导出参数始终同源 ═══════════════
//
// 🚨 **2026-09-03 修订**：本条原文曾要求「切到工序页签再切回 → 筛选保持为停用」。
//    那是 AC 的**错误假设**，已由主线亲验推翻并删除：
//    壳页 `MasterDataHubPage.tsx:28` 打了 `destroyInactiveTabPane`（task-0728 既有架构），
//    切走页签即销毁组件、切回重新挂载 ⇒ 材质页三个本地筛选 state 必然归零。
//    主线实测：切回后**筛选框显示「状态：全部」、列表 263 条、导出 630 行 —— 三者完全自洽**，
//    是「所见即所得」，不是缺陷。要求筛选保持 = 要求改壳页架构，超范围且与导出无关。
//    ⇒ 🚫 本用例**不再断言任何跨页签行为**。改验真正的不变量：**列表显示什么，就导出什么**。

/** 库里的材质**条数**（= 页面列表「共 N 条」的口径）。 */
function dbMaterialCount(): number {
  return Number(sqlOne('SELECT count(*) FROM material_recipe'));
}
/**
 * 库里的**不同材质名**个数（= 导出文件「材质」列去重后的口径）。
 * 🚨 二者**不相等是正常的**：导出的「材质」列写的是材质名（`symbol`），而库里存在**同名材质**
 *    —— 2026-09-03 实测 `AgCu`(AgCu85/AgCu90) 与 `AgNi`(AgNi90/AgNi95) 各 2 条，
 *    名字相同、编号不同、都 ACTIVE ⇒ 263 条材质只对应 **261** 个不同材质名。
 *    所以「导出的不同材质数 == 列表条数」**是个错误的不变量**（我最初就写错了这条，实测 261≠263）。
 */
function dbDistinctMaterialNames(): number {
  return Number(sqlOne('SELECT count(DISTINCT symbol) FROM material_recipe'));
}

/** 读分页控件的「共 N 条」。 */
async function listTotal(page: Page, tag: string): Promise<number> {
  const el = page.locator('text=/共\\s*\\d+\\s*条/').first();
  await expect(el, `${tag}：应能读到分页「共 N 条」`).toBeVisible({ timeout: 20_000 });
  const m = (await el.innerText()).match(/共\s*(\d+)\s*条/);
  expect(m, `${tag}：分页文案解析失败`).not.toBeNull();
  return Number(m![1]);
}

/** 导出文件里的不同材质名集合。 */
function materialSet(rows: string[][], header: string[]): Set<string> {
  const i = header.indexOf('材质');
  expect(i, `导出文件应有「材质」列，实际表头 = ${JSON.stringify(header)}`).toBeGreaterThanOrEqual(0);
  return new Set(rows.map((r) => (r[i] ?? '').trim()).filter(Boolean));
}

test('T-22 / AC-22：筛选态与导出参数始终同源 —— 列表显示什么就导出什么', async ({ page }) => {
  await uiLogin(page, ADMIN, ADMIN_PWD);
  await gotoMasterDataTab(page, '材质');
  await page.waitForSelector('.ant-table-row', { timeout: 30_000 });

  // 🚨 两个筛选下拉**必须在还没选任何值时**一次性抓好并全程复用。
  //    原因：选中后该下拉的邻近文本会从「类型：全部」变成选中值（如「标准锁定」），
  //    再用 selectNear('类型') 就找不到了 —— 报错长得像「筛选框没了」的产品缺陷，
  //    实际是定位方式错（2026-09-03 实跑踩到）。
  const typeSel = await selectNear(page, '类型');
  const statusSel = await selectNear(page, '状态');

  // ── ① 状态=停用 ⇒ 导出行数 == 该筛选下页面列表显示的条数（N1）──
  await pickOption(page, statusSel, '停用');
  await page.waitForTimeout(1000);
  await expect(btn(page, '导出材质库'),
    'AC-1/AC-22：筛出非 0 条时「导出材质库」必须可点击（恒定禁用是缺陷）').toBeEnabled();

  const total1 = await listTotal(page, 'AC-22 停用');
  const [f1, rows1, head1] = await downloadExportWithHeader(page, '导出材质库', 'AC22-停用');
  const n1 = rows1.length;
  const set1 = materialSet(rows1, head1);
  console.log(`[AC-22] 停用：列表共 ${total1} 条 ｜ 导出 ${n1} 行 ｜ 不同材质 ${set1.size} 个 = ${[...set1]}`);
  expect(n1, 'AC-22 前置：筛「停用」导出 0 行 —— 后续断言会空跑（假绿）').toBeGreaterThan(0);

  // AC 字面：导出行数 == 列表条数。
  // ⚠️ 判据说明：列表一行 = 一个**材质**，导出一行 = 一个**元素行**，二者相等的前提是
  //    「筛出的每个材质恰好一组配置、每组恰好一个元素」。当前停用材质只有 SnO2-del（1 元素）故成立。
  //    若将来该筛选下出现多元素材质，这条会**合理地**不再相等 —— 那不是回归，
  //    真正durable的判据是下面那条「不同材质数 == 列表条数」。两条都留，失败时看这段注释。
  expect(n1, `AC-22：筛「停用」时导出行数(${n1}) 应 == 页面列表条数(${total1})。` +
    '若不等，先看是不是筛出的材质里有「一个材质多个元素行」——那属数据变化，不是功能回归').toBe(total1);
  // ⚠️ 这条成立的前提是「该筛选结果内没有同名材质」。库里确有同名材质（AgCu/AgNi 各 2 条），
  //    若将来筛选结果命中它们，这里会**合理地**不等 —— 那不是回归，看下面这句提示即可。
  expect(set1.size,
    `AC-22：筛「停用」时导出的不同材质数(${set1.size}) 应 == 页面列表条数(${total1})。` +
    '若不等，先查该筛选结果里是不是有**同名材质**（导出按名字去重会塌成一个），那属数据状态不是回归')
    .toBe(total1);

  // 列表里显示的材质名，必须与导出集合一致（此时只有 1 页，可逐条比对）
  const listText1 = (await page.locator('.ant-table-row').allInnerTexts()).join(' | ');
  for (const name of set1) {
    expect(listText1.includes(name),
      `AC-22：导出的材质「${name}」应出现在页面列表里，实际列表 = ${listText1}`).toBe(true);
  }
  await shot(page, 'AC22-01-状态停用');

  // ── ② 叠加「类型」筛选 ⇒ 导出的材质集合 == 页面此刻显示的集合 ──
  await pickOption(page, typeSel, '标准锁定');
  await page.waitForTimeout(1000);
  const total2 = await listTotal(page, 'AC-22 停用+标准锁定');
  const [, rows2, head2] = await downloadExportWithHeader(page, '导出材质库', 'AC22-停用+标准锁定');
  const set2 = materialSet(rows2, head2);
  console.log(`[AC-22] 停用+标准锁定：列表共 ${total2} 条 ｜ 导出 ${rows2.length} 行 ｜ 材质 = ${[...set2]}`);
  expect(total2, 'AC-22 前置：叠加两个筛选后列表 0 条 —— 集合相等断言会退化成空验证（假绿）')
    .toBeGreaterThan(0);
  expect(set2.size, `AC-22：叠加两个筛选后，导出的不同材质数(${set2.size}) 必须 == 列表条数(${total2})`)
    .toBe(total2);
  const listText2 = (await page.locator('.ant-table-row').allInnerTexts()).join(' | ');
  for (const name of set2) {
    expect(listText2.includes(name),
      `AC-22：叠加筛选后导出的材质「${name}」应出现在页面列表里，实际列表 = ${listText2}`).toBe(true);
  }
  // 叠加筛选必须是收敛（子集）
  expect([...set2].every((x) => set1.has(x)),
    `AC-22：叠加「类型」后集合应是原集合的子集，实际 ${[...set2]} ⊄ ${[...set1]}`).toBe(true);
  await shot(page, 'AC22-02-停用叠加标准锁定');

  // ── ③ 清空全部筛选 ⇒ 导出行数 == 基准查询② 且明显大于 N1 ──
  await clearSelect(page, typeSel, 'AC-22 清空类型筛选');
  await clearSelect(page, statusSel, 'AC-22 清空状态筛选');
  await page.waitForTimeout(1000);

  const totalAll = await listTotal(page, 'AC-22 全量');
  const baseBefore = baseRecipeElementRows();
  const [, rowsAll, headAll] = await downloadExportWithHeader(page, '导出材质库', 'AC22-全量');
  const baseAfter = baseRecipeElementRows();
  const setAll = materialSet(rowsAll, headAll);
  console.log(`[AC-22] 全量：列表共 ${totalAll} 条 ｜ 导出 ${rowsAll.length} 行 ｜ ` +
    `不同材质 ${setAll.size} 个 ｜ 基准查询② = ${baseBefore}~${baseAfter}`);
  evidence('AC22-同源对照', JSON.stringify({
    '停用': { '列表条数': total1, '导出行数': n1, '材质': [...set1] },
    '停用+标准锁定': { '列表条数': total2, '导出行数': rows2.length, '材质': [...set2] },
    '全量': { '列表条数': totalAll, '导出行数': rowsAll.length, '不同材质数': setAll.size,
              '基准查询2': `${baseBefore}~${baseAfter}` },
  }, null, 2));

  // ⚠️ 共享库有并发写入：基准与导出取的是相邻两刻，落在两次基线之间即视为一致。
  //    🚫 这不是放宽断言，是排除「并发漂移」这一个已知干扰项（不排除会随机红）。
  expect(rowsAll.length,
    `AC-22：清空筛选后导出行数应 == 基准查询②（${baseBefore}~${baseAfter}），实际 ${rowsAll.length}`)
    .toBeGreaterThanOrEqual(Math.min(baseBefore, baseAfter));
  expect(rowsAll.length).toBeLessThanOrEqual(Math.max(baseBefore, baseAfter));
  expect(rowsAll.length,
    `AC-22：全量行数(${rowsAll.length}) 必须明显大于 N1(${n1}) —— 相等说明筛选参数根本没生效`)
    .toBeGreaterThan(n1);
  // 「同源」的核心不变量（全量）：两侧各自与库口径对齐 —— 🚫 不能直接把两侧数字划等号。
  const dbCount = dbMaterialCount();
  const dbNames = dbDistinctMaterialNames();
  console.log(`[AC-22] 库口径：材质条数=${dbCount} ｜ 不同材质名=${dbNames}`);
  expect(totalAll, `AC-22：页面列表「共 N 条」(${totalAll}) 应 == 库里材质条数(${dbCount})`)
    .toBe(dbCount);
  expect(setAll.size,
    `AC-22：导出的不同材质名数(${setAll.size}) 应 == 库里不同材质名数(${dbNames})。\n` +
    `⚠️ 它与列表条数(${totalAll}) **本就不相等**：导出的「材质」列写名字，而库里有同名材质` +
    `（实测 AgCu/AgNi 各 2 条），两条记录导出成同一个名字 ⇒ 差值 = ${dbCount - dbNames}。` +
    '把这两个数字划等号是错误的不变量。').toBe(dbNames);
  // 关键否定断言（与 AC-8 同源）：不等于当前页 20 条
  expect(rowsAll.length, 'AC-8/AC-22：全量导出行数恰为 20 —— 疑似只导了当前页').not.toBe(20);
  await shot(page, 'AC22-03-清空筛选后全量');
});

// ═══════════════ T-23b → AC-23：禁用态 tooltip 文案（三页逐字相同）═══════════════

/**
 * 🚨 读**禁用**元素的 tooltip 有三个坑，全部踩过，缺一条就会把测试问题误判成产品缺陷：
 *
 *  1. `locator.hover({force:true})` 在禁用元素上**取不到** tooltip ⇒ 必须用 `page.mouse.move`
 *     （task-260901 实测：前端一度据此准备把共享组件报成缺陷，换 mouse.move 后三处全正常）。
 *  2. **AntD v6 的 tooltip 文本在 `.ant-tooltip-container`；`.ant-tooltip-inner` 在 v6 里不存在**
 *     ⇒ 取错 class 恒 not found，会被误报成「tooltip 没做」。
 *  3. **antd 关闭 tooltip 只隐藏不移除**，而三页文案又完全相同
 *     ⇒ 上一页的陈旧 tooltip 与本页的真读数**肉眼无法区分**。
 *     对策：只取 `:visible` 的那一个，且**每页 hover 前先断言可见 tooltip 数为 0**（阳性对照）——
 *     没有这个对照组，「读到了文案」可能只是读到了上一页的残留。
 */
async function tooltipOfDisabled(page: Page, locator: ReturnType<Page['locator']>, tag: string): Promise<string> {
  const visibleTips = page.locator('.ant-tooltip-container:visible, .ant-tooltip:visible');

  // 阳性对照：hover 前必须一个可见 tooltip 都没有
  await page.mouse.move(5, 5);
  await expect(visibleTips, `${tag}：hover 前应无可见 tooltip（有 = 读到的是上一页残留）`)
    .toHaveCount(0, { timeout: 8_000 });
  console.log(`[${tag}] 对照组 hover前 可见tooltip数 = 0 ✅`);

  // 🚨 hover 重试一次：页面刚渲染完时 mouse.move 偶发打空（元素位置还在抖），
  //    表现为「tooltip 不出现」——看起来像 tooltip 没做，实际是取证时机问题。
  for (let attempt = 1; attempt <= 2; attempt++) {
    const box = await locator.boundingBox();
    expect(box, `${tag}：tooltip 目标须可见并有布局盒（拿不到 = 定位错了，不是产品缺陷）`).not.toBeNull();
    await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
    await page.waitForTimeout(700);
    if (await visibleTips.count() > 0) break;
    if (attempt === 2) break;
    console.warn(`[${tag}] 首次 hover 没出 tooltip，移开后重试一次`);
    await page.mouse.move(5, 5);
    await page.waitForTimeout(500);
  }

  await expect(visibleTips.first(), `${tag}：hover 后应出现 tooltip`).toBeVisible({ timeout: 8_000 });
  const text = (await visibleTips.first().innerText()).trim();
  console.log(`[${tag}] tooltip 原文 = 「${text}」`);

  // 移开并等它消失，避免污染下一页（antd 只隐藏不移除）
  await page.mouse.move(5, 5);
  await expect(visibleTips, `${tag}：移开后 tooltip 应隐藏`).toHaveCount(0, { timeout: 8_000 });
  return text;
}

test('T-23b / AC-23：材质/工序/用户三页在结果 0 条时，禁用的「导出」按钮 tooltip 文案逐字为「当前筛选结果为 0 条，无可导出数据」', async ({ page }) => {
  const EXPECTED = '当前筛选结果为 0 条，无可导出数据';
  await uiLogin(page, ADMIN, ADMIN_PWD);

  const pages: Array<{ name: string; goto: () => Promise<void>; exportBtn: string; importBtn: string }> = [
    {
      name: '材质',
      goto: async () => { await gotoMasterDataTab(page, '材质'); },
      exportBtn: '导出材质库', importBtn: '导入材质库',
    },
    {
      name: '工序',
      goto: async () => { await gotoMasterDataTab(page, '工序'); },
      exportBtn: '导出工序', importBtn: '导入工序',
    },
    {
      name: '用户',
      goto: async () => { await gotoUsersPage(page); },
      exportBtn: '导出用户', importBtn: '导入用户',
    },
  ];

  const texts: Record<string, string> = {};
  for (const p of pages) {
    await p.goto();
    const search = page.locator('input[placeholder*="搜索"]').first();
    await expect(search, `AC-23：${p.name}页应有搜索框`).toBeVisible({ timeout: 20_000 });
    // 🚨 先等首屏数据真的落地再搜 —— 否则会踩这个竞态：搜索词填进去时首次列表请求还在飞，
    //    它的响应随后回来把**已过滤的结果覆盖成全量**，于是「搜不存在的词却还有 16 行」，
    //    看起来像「搜索功能坏了」，实际是取证时机问题（2026-09-03 实跑实测）。
    await expect(page.locator('.ant-table-row').first(),
      `AC-23：${p.name}页首屏应先加载出数据（0 行则无法证明搜索真的生效）`)
      .toBeVisible({ timeout: 30_000 });
    // 填入 → 回车；若 5s 后仍未收敛，再补一次（防抖/竞态兜底）
    for (let attempt = 1; attempt <= 2; attempt++) {
      await search.fill('zzz不存在zzz');
      await page.keyboard.press('Enter');
      try {
        await expect(page.locator('.ant-table-row')).toHaveCount(0, { timeout: 8_000 });
        break;
      } catch {
        if (attempt === 2) break;
        console.warn(`[AC-23·${p.name}] 搜索未收敛，重试一次`);
      }
    }
    // 等空态真的出现 —— 🚨 不等就 hover，可能在「还有旧数据」的瞬间读到「按钮可点」
    await expectEmptyState(page, `AC-23·${p.name}页`);

    const exportBtn = btn(page, p.exportBtn);
    await expect(exportBtn, `AC-23：${p.name}页「${p.exportBtn}」应处于禁用态`).toBeDisabled();
    await expect(btn(page, p.importBtn),
      `AC-23：🚨 ${p.name}页「${p.importBtn}」在空态下**不**该被禁用 —— 一张空表恰恰最需要导入`)
      .toBeEnabled();

    texts[p.name] = await tooltipOfDisabled(page, exportBtn, `AC-23·${p.name}`);
    await shot(page, `AC23b-${p.name}页空态tooltip`);
  }

  evidence('AC23b-三页tooltip原文', JSON.stringify(texts, null, 2));
  for (const [name, t] of Object.entries(texts)) {
    expect(t, `AC-23：${name}页的 tooltip 文案必须逐字为「${EXPECTED}」，实际「${t}」`).toBe(EXPECTED);
  }
  expect(new Set(Object.values(texts)).size,
    `AC-23：三页共用同一句文案，实际读到 ${JSON.stringify(texts)}（各写各的是缺陷）`).toBe(1);
});

// ═══════════════ T-27 → AC-27：连点两次不发第二个请求 + 内容一致 + 不阻塞 ═══════════════

test('T-27 / AC-27：导出进行中按钮显示 loading、期间再次点击不发第二个请求、页面不被阻塞；两次导出内容一致', async ({ page }) => {
  await uiLogin(page, ADMIN, ADMIN_PWD);
  await gotoMasterDataTab(page, '材质');
  await page.waitForSelector('.ant-table-row', { timeout: 30_000 });
  await expect(btn(page, '导出材质库'), 'AC-27 前置：有数据时导出按钮应可点').toBeEnabled();

  // ── 请求计数器（AC-27 的核心可观测量）──
  let exportReqs = 0;
  const countReq = (req: { url: () => string }) => {
    if (/\/api\/cpq\/material-recipes\/export/.test(req.url())) exportReqs++;
  };
  page.on('request', countReq);

  // ── 阶段一：把导出响应人为拖慢 1.5s，才有稳定的 loading 观察窗 ──
  //    🚨 不拖慢的话导出常在 100ms 内结束，「没观察到 loading」与「实现没做 loading」
  //       在日志里长得一模一样 —— 那是最典型的假绿/假红同源。
  await page.route('**/api/cpq/material-recipes/export**', async (route) => {
    await new Promise((r) => setTimeout(r, 1500));
    await route.continue();
  });

  const before = exportReqs;
  const dlPromise = page.waitForEvent('download', { timeout: 60_000 });
  await btn(page, '导出材质库').click();

  // loading 态（antd Button 加 .ant-btn-loading）
  const loadingBtn = page.locator('button.ant-btn-loading').filter({ hasText: /导\s*出\s*材\s*质\s*库/ });
  await expect(loadingBtn,
    'AC-27：点击后「导出材质库」应进入 loading 态（原型图 1 状态 A 的说明）')
    .toBeVisible({ timeout: 10_000 });
  await shot(page, 'AC27-01-导出loading态');

  // 🚨 飞行中连点 3 次 —— 必须一个新请求都不发
  //    ⚠️ 判据只能是「请求数」，🚫 **不能用 `isDisabled()`**：antd v6 的 loading 按钮
  //       DOM 上**不带 `disabled`**，但会吞掉点击 —— 按 disabled 判会假红（前端 2026-09-02 实测）。
  for (let i = 0; i < 3; i++) {
    await btn(page, '导出材质库').click({ force: true, timeout: 3_000 }).catch(() => { /* 吞掉即达标 */ });
    await page.waitForTimeout(120);
  }
  await page.waitForTimeout(300);

  // 期间页面其他操作不被阻塞（AC-27 后半句）
  const search = page.locator('input[placeholder*="搜索"]').first();
  await search.fill('t260902不阻塞探针');
  expect(await search.inputValue(),
    'AC-27：导出进行中页面不该被阻塞（搜索框应仍能输入）').toBe('t260902不阻塞探针');
  await search.fill('');

  const dl = await dlPromise;
  fs.mkdirSync(DOWNLOAD_DIR, { recursive: true });
  const f1 = path.join(DOWNLOAD_DIR, `AC27-第1次-${dl.suggestedFilename()}`);
  await dl.saveAs(f1);

  const fired = exportReqs - before;
  console.log('[AC-27] 首次点击 + 飞行中连点 3 次 ⇒ 实际发出的导出请求数 =', fired);
  expect(fired,
    `AC-27：🚨 loading 期间重复点击**不得**发出第二个请求，实际发出 ${fired} 个。` +
    '2 = 按钮没在请求进行中锁住，用户连点会打出多份导出'
  ).toBe(1);

  await expect(loadingBtn, 'AC-27：导出完成后 loading 态应解除').toHaveCount(0, { timeout: 15_000 });
  await expect(btn(page, '导出材质库'), 'AC-27：完成后按钮应恢复可点').toBeEnabled({ timeout: 15_000 });
  await shot(page, 'AC27-02-loading已解除');

  // ── 阶段二：解除拖慢，正常再点一次，两次内容必须一致 ──
  await page.unroute('**/api/cpq/material-recipes/export**');
  const [, grid2] = await downloadExport(page, '导出材质库', 'AC27-第2次');
  const wb1 = XLSX.read(fs.readFileSync(f1));
  const g1raw = XLSX.utils.sheet_to_json(wb1.Sheets[wb1.SheetNames[0]],
    { header: 1, defval: '', raw: true }) as unknown[][];
  const g1 = g1raw.map((r) => r.map((c) => (c === null || c === undefined ? '' : String(c))))
    .slice(1).filter((r) => r.some((c) => c.trim() !== ''));

  // ⚠️ 「内容一致」= **数据一致，不是字节一致**：POI 会把生成时间戳写进 docProps/core.xml，
  //    两次导出的 sha256 必然不同。🚫 不要断言哈希/字节相等 —— 那条断言在 POI 下不可能绿。
  console.log(`[AC-27] 第1次行数=${g1.length} 第2次行数=${grid2.length}`);
  expect(g1.length, 'AC-27 前置：导出 0 行 —— 「内容一致」会退化成空验证（假绿）').toBeGreaterThan(0);
  expect(grid2, 'AC-27：连续两次导出的内容必须逐单元格一致').toEqual(g1);

  page.off('request', countReq);
});

// ═══════ T-01/02/09-UI → AC-1 / AC-2 / AC-9：工具栏按钮集合与顺序（按角色）═══════
//
// 🚨 这条本来在组件层（vitest）。2026-09-03 实证它在那一层**验不了**：
//    `renderToStaticMarkup` 走 SSR 路径，zustand 取不到 `setState` 写入的角色，
//    页面里 `useAuthStore(s => s.user?.role === 'SYSTEM_ADMIN')` 恒 false ⇒
//    「管理员应看到」恒红，而「非管理员不该看到」**恒绿且是空验证**（任何角色都不渲染）。
//    后者比前者危险得多 —— 所以整条移到真实浏览器里验。

/** 页面上所有可见按钮的文字（去空白，AntD 会给两字按钮插空格：「刷新」→「刷 新」）。 */
async function visibleButtonTexts(page: Page): Promise<string[]> {
  return (await page.locator('button:visible').evaluateAll((els) =>
    els.map((e) => (e.textContent || '').replace(/\s+/g, '')).filter(Boolean)));
}

/** 断言这些文字都出现，且**相对顺序**与给定顺序一致。 */
function assertOrder(texts: string[], expected: readonly string[], tag: string) {
  const idx = expected.map((t) => texts.indexOf(t));
  expect(idx.filter((i) => i < 0).length,
    `${tag}：以下按钮没出现 = ${expected.filter((_, k) => idx[k] < 0).join('/')}；实际按钮 = ${JSON.stringify(texts)}`
  ).toBe(0);
  expect(idx, `${tag}：右组顺序应为 ${expected.join(' → ')}，实际按钮序列 = ${JSON.stringify(texts)}`)
    .toEqual([...idx].sort((a, b) => a - b));
}

test('T-01/02/09-UI / AC-1 · AC-2 · AC-9：管理员看到导出按钮且顺序正确；非管理员整个按钮不渲染', async ({ page }) => {
  const MATERIAL_TOOLBAR = ['刷新', '导出材质库', '导入材质库', '下载导入模板', '新建材质'] as const;
  const PROCESS_TOOLBAR = ['刷新', '导出工序', '导入工序', '新增工序'] as const;

  // ── ① 管理员：两页的按钮集合与顺序（AC-1 / AC-9 正向）──
  await uiLogin(page, ADMIN, ADMIN_PWD);
  await gotoMasterDataTab(page, '材质');
  const matAdmin = await visibleButtonTexts(page);
  console.log('[AC-1] 管理员·材质页按钮 =', JSON.stringify(matAdmin));
  assertOrder(matAdmin, MATERIAL_TOOLBAR, 'AC-1');
  await shot(page, 'AC01-管理员材质工具栏');

  await gotoMasterDataTab(page, '工序');
  const procAdmin = await visibleButtonTexts(page);
  console.log('[AC-9] 管理员·工序页按钮 =', JSON.stringify(procAdmin));
  assertOrder(procAdmin, PROCESS_TOOLBAR, 'AC-9');
  await shot(page, 'AC09-管理员工序工具栏');

  // ── ② 用导入端点造一个 SALES_MANAGER（前缀化，afterAll 精确删）──
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([
    ['用户名', '姓名', '邮箱', '角色', '区域', '部门'],
    ['t260902mgr', '权限UI销售经理', 't260902mgr@t260902.invalid', '销售经理', '', ''],
  ]), '用户');
  fs.mkdirSync(DOWNLOAD_DIR, { recursive: true });
  const f = path.join(DOWNLOAD_DIR, 't260902-非管理员.xlsx');
  fs.writeFileSync(f, XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' }));

  const resp = await page.request.post('/api/cpq/users/import', {
    multipart: { file: { name: '非管理员.xlsx', mimeType: XLSX_MIME_E2E, buffer: fs.readFileSync(f) } },
  });
  expect(resp.status(), 'AC-2 前置：造非管理员账号的导入请求应 200').toBe(200);
  const rep = (await resp.json())?.data ?? (await resp.json());
  const mgrPwd: string = rep.created?.[0]?.initialPassword;
  expect(mgrPwd, `AC-2 前置：应拿到 t260902mgr 的初始密码，实际报告 = ${JSON.stringify(rep)}`).toBeTruthy();

  // ── ③ 非管理员登录（首登强制改密）→ 两页都不该出现导出按钮（AC-2 / AC-9 反向）──
  await uiLogout(page);
  await page.locator('input[placeholder="用户名或邮箱"]').fill('t260902mgr');
  await page.locator('input[placeholder="密码"]').fill(mgrPwd);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/change-password/, { timeout: 30_000 }).catch(() => {});
  const pw = page.locator('input[type="password"]');
  await expect(pw.first(), 'AC-2 前置：非管理员首登应到改密页').toBeVisible({ timeout: 20_000 });
  const n = await pw.count();
  if (n >= 3) { await pw.nth(0).fill(mgrPwd); await pw.nth(1).fill(NEW_PWD); await pw.nth(2).fill(NEW_PWD); }
  else { for (let i = 0; i < n; i++) await pw.nth(i).fill(NEW_PWD); }
  await page.locator('button[type="submit"]').first().click();
  await page.waitForURL((u) => !/\/change-password/.test(u.toString()), { timeout: 30_000 }).catch(() => {});
  console.log('[AC-2] 非管理员改密后 URL =', page.url());

  await gotoMasterDataTab(page, '材质');
  const matMgr = await visibleButtonTexts(page);
  console.log('[AC-2] 非管理员·材质页按钮 =', JSON.stringify(matMgr));
  await shot(page, 'AC02-非管理员材质工具栏');
  expect(matMgr.includes('导出材质库'),
    `AC-2：🚨 SALES_MANAGER 不该看到「导出材质库」（原型图 1 状态 B 要求**整个按钮不渲染**，` +
    `不是禁用态）。实际按钮 = ${JSON.stringify(matMgr)}`).toBe(false);
  for (const t of ['导入材质库', '下载导入模板']) {
    expect(matMgr.includes(t),
      `AC-2：非管理员仍应看到「${t}」（本次不收紧导入/模板权限，那是既有行为）。` +
      `实际按钮 = ${JSON.stringify(matMgr)}`).toBe(true);
  }

  await gotoMasterDataTab(page, '工序');
  const procMgr = await visibleButtonTexts(page);
  console.log('[AC-9] 非管理员·工序页按钮 =', JSON.stringify(procMgr));
  await shot(page, 'AC09-非管理员工序工具栏');
  expect(procMgr.includes('导出工序'),
    `AC-9：🚨 SALES_MANAGER 不该看到「导出工序」。实际按钮 = ${JSON.stringify(procMgr)}`).toBe(false);
  expect(procMgr.includes('导入工序'),
    `AC-9：非管理员仍应看到「导入工序」。实际按钮 = ${JSON.stringify(procMgr)}`).toBe(true);

  evidence('AC01-02-09-工具栏按钮对照', JSON.stringify({
    管理员_材质: matAdmin, 管理员_工序: procAdmin,
    非管理员_材质: matMgr, 非管理员_工序: procMgr,
  }, null, 2));
});
