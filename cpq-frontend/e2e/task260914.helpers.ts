/**
 * task-260914 · S-UI 分片辅助函数（报价单列表：料号搜索 + 扩列）
 *
 * 🚦 纪律（写在代码里，防止后来者破坏分片隔离）：
 *  - 本分片是 **纯只读片**：只做打开列表 / 输入搜索 / 切筛选 / 翻页 / 切页签。
 *    🚫 不建单、不保存、不提交、不删除、不改任何数据；🚫 不碰 S-API 片的数据。
 *  - 所有「共 N 条」的期望值一律用基准 SQL **现场重算**（同分钟采样），
 *    🚫 不许硬编码立项日采样值（cpq_db_0724 是共享库，随时有别的会话建单）。
 *  - 断言前先断言「非空」：数据为空时循环 0 次 = 断言压根没跑 = 假绿。
 */
import { execSync } from 'child_process';
import * as fs from 'fs';
import { Page, Locator, expect } from '@playwright/test';

export const DB_HOST = process.env.PW_DB_HOST || '10.177.152.12';
export const DB_NAME = process.env.PW_DB_NAME || 'cpq_db_0724';
export const DB_USER = process.env.PW_DB_USER || 'postgres';
export const DB_PASSWORD = process.env.PW_DB_PASSWORD || 'joii5231';

/** 全角破折号 U+2014 —— AC-9 / AC-10 要求的空值占位符 */
export const EM_DASH = '—';

/** 列表接口 URL 判据（只认列表查询，不认 /quotations/{id}） */
export const LIST_API = /\/api\/cpq\/quotations(\?|$)/;

// ──────────────────────────────── SQL（只读） ────────────────────────────────

export function psql(sql: string): string {
  const cmd = `PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -t -A -F'|' -c "${sql.replace(/"/g, '\\"')}"`;
  return execSync(cmd, { encoding: 'utf-8', shell: '/bin/bash' }).trim();
}

/** 只读标量查询。🚨 只允许 SELECT —— 任何写语句直接抛错（§3.2 红线的代码级护栏）。 */
export function scalar(sql: string): number {
  guardReadOnly(sql);
  const out = psql(sql);
  const n = Number(out.split('\n')[0]);
  if (!Number.isFinite(n)) throw new Error(`基准 SQL 未返回数字: ${out} <<< ${sql}`);
  return n;
}

export function rows(sql: string): string[][] {
  guardReadOnly(sql);
  const out = psql(sql);
  if (!out) return [];
  return out.split('\n').map((l) => l.split('|'));
}

function guardReadOnly(sql: string) {
  if (!/^\s*select/i.test(sql.trim())) {
    throw new Error(`S-UI 是只读分片，只允许 SELECT：${sql}`);
  }
  if (/\b(insert|update|delete|drop|truncate|alter|create)\b/i.test(sql)) {
    throw new Error(`S-UI 是只读分片，检测到写语句关键字：${sql}`);
  }
}

/** 料号模糊命中的单据数（任务.md §7 基准 SQL Q1）。kw 传小写。 */
export function sqlPartNoCount(kw: string, extraAnd = ''): number {
  const k = kw.toLowerCase().replace(/'/g, "''");
  return scalar(
    `SELECT count(*) FROM quotation q WHERE 1=1 ${extraAnd} AND EXISTS (` +
      `SELECT 1 FROM quotation_line_item li WHERE li.quotation_id=q.id ` +
      `AND (LOWER(li.product_part_no_snapshot) LIKE '%${k}%' OR LOWER(li.customer_part_no) LIKE '%${k}%'))`
  );
}

export function sqlTotalQuotations(): number {
  return scalar('SELECT count(*) FROM quotation');
}

export function sqlUncategorizedCount(): number {
  return scalar('SELECT count(*) FROM quotation WHERE product_category_id IS NULL');
}

export function sqlActiveCategoryNames(): string[] {
  return rows("SELECT name FROM product_category WHERE status='ACTIVE' ORDER BY name").map((r) => r[0]);
}

export function sqlCategoryIdByName(name: string): string {
  const r = rows(`SELECT id FROM product_category WHERE name='${name.replace(/'/g, "''")}' LIMIT 1`);
  if (!r.length) throw new Error(`分类不存在: ${name}`);
  return r[0][0];
}

export function sqlPublishedQuotationTemplateNames(): string[] {
  return rows("SELECT name FROM template WHERE template_kind='QUOTATION' AND status='PUBLISHED' ORDER BY name").map(
    (r) => r[0]
  );
}

/**
 * 基准 SQL **Q4'**（C-3 裁决后的口径）：PUBLISHED 报价模板**按模板系列聚合**，每系列一条。
 * 🚫 不再是「21 个模板版本」而是「13 个系列」—— 同名多版本叠加 D-8（不带版本号）会产生
 * 完全无法区分的重复条目，这正是 C-3 要消除的东西。
 * 返回每个系列：seriesId / 该系列下的不同名称个数 / 名称样本（多名称时用 ' | ' 连接）。
 */
export function sqlQuotationTemplateSeries(): { seriesId: string; nameCount: number; names: string[] }[] {
  return rows(
    "SELECT template_series_id, count(DISTINCT name), string_agg(DISTINCT name, '|') " +
      "FROM template WHERE template_kind='QUOTATION' AND status='PUBLISHED' GROUP BY template_series_id"
  ).map((r) => ({ seriesId: r[0], nameCount: Number(r[1]), names: (r[2] ?? '').split('|').filter(Boolean) }));
}

/** IANA 时区名白名单校验（时区串来自浏览器，直接拼进 SQL 前必须过一遍）。 */
export function assertSafeTimeZone(tz: string): string {
  if (!/^(UTC|[A-Za-z]+\/[A-Za-z0-9_+\-]+(\/[A-Za-z0-9_+\-]+)?)$/.test(tz)) {
    throw new Error(`拿到的浏览器时区串不合法，拒绝拼进 SQL：${JSON.stringify(tz)}`);
  }
  return tz;
}

// ──────────────────────────── 并发 playwright 采样 ────────────────────────────

/** 取某 pid 的祖先链（含自身）。 */
function ancestors(pid: number): number[] {
  const chain: number[] = [];
  let cur = pid;
  for (let i = 0; i < 40 && cur > 1; i++) {
    chain.push(cur);
    try {
      const stat = fs.readFileSync(`/proc/${cur}/stat`, 'utf-8');
      const ppid = Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[1]);
      if (!Number.isFinite(ppid) || ppid <= 0) break;
      cur = ppid;
    } catch {
      break;
    }
  }
  return chain;
}

/**
 * 🚦 采样是否有 **别的** playwright 在跑。
 * 理由：`playwright.config.ts` 的 `workers:1` 只管单进程内，跨进程零互斥；
 * 而 `e2e/global-setup.ts` 每次跑任何 spec 都会写 cpq_db_0724 共享库的 `user` 表。
 * ⚠️ 我们自己就是一个 playwright 进程，必须把自己的进程树排除掉，否则永远误报。
 */
export function otherPlaywrightPids(): number[] {
  let out = '';
  try {
    out = execSync('pgrep -f "node.*[p]laywright test" || true', { encoding: 'utf-8', shell: '/bin/bash' }).trim();
  } catch {
    return [];
  }
  if (!out) return [];
  const mine = new Set(ancestors(process.pid));
  const others: number[] = [];
  for (const line of out.split('\n')) {
    const pid = Number(line.trim());
    if (!Number.isFinite(pid) || pid <= 0) continue;
    const chain = ancestors(pid);
    if (chain.some((p) => mine.has(p))) continue; // 同一进程树 = 我们自己
    others.push(pid);
  }
  return others;
}

// ──────────────────────────────── 页面操作 ────────────────────────────────

/** 等待一次列表查询往返（比 waitForTimeout 可靠）。 */
export async function withListRequest<T>(page: Page, action: () => Promise<T>): Promise<T> {
  const waiter = page
    .waitForResponse((r) => LIST_API.test(r.url()) && r.request().method() === 'GET', { timeout: 20_000 })
    .catch(() => null);
  const res = await action();
  await waiter;
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(300);
  return res;
}

export async function openQuotationList(page: Page) {
  await withListRequest(page, async () => {
    await page.goto('/quotations');
  });
  await page.waitForSelector('.ant-table-thead th', { timeout: 60_000 });
  await page.waitForLoadState('networkidle').catch(() => {});
}

const norm = (s: string) => s.replace(/\s+/g, '').trim();

/** 表头文案（原样，含空表头）。 */
export async function headerTextsRaw(page: Page): Promise<string[]> {
  const ths = await page.locator('.ant-table-thead th').allInnerTexts();
  return ths.map(norm);
}

/** 非空表头文案 —— AC-8 的列序断言对象。 */
export async function headerTexts(page: Page): Promise<string[]> {
  return (await headerTextsRaw(page)).filter((t) => t.length > 0);
}

/** 某列在 th 里的下标（含勾选列占位），用于按列名取单元格。 */
export async function columnIndex(page: Page, header: string): Promise<number> {
  const raw = await headerTextsRaw(page);
  const idx = raw.findIndex((t) => t === norm(header));
  expect(idx, `表头里应能找到「${header}」列，实际表头 = ${JSON.stringify(raw)}`).toBeGreaterThanOrEqual(0);
  return idx;
}

export function bodyRows(page: Page): Locator {
  return page.locator('.ant-table-tbody tr.ant-table-row');
}

/** 取某列所有单元格文本。⚠️ 调用方必须先断言行数 > 0，否则空数组会让断言空跑（假绿）。 */
export async function columnCells(page: Page, header: string): Promise<string[]> {
  const idx = await columnIndex(page, header);
  const n = await bodyRows(page).count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) {
    out.push(norm(await bodyRows(page).nth(i).locator('td').nth(idx).innerText()));
  }
  return out;
}

/** 分页「共 N 条」。找不到直接抛错，🚫 不返回 0（返回 0 会伪装成合法值）。 */
export async function totalCount(page: Page): Promise<number> {
  const texts: string[] = [];
  const pag = page.locator('.ant-pagination').first();
  if (await pag.count()) texts.push(await pag.innerText());
  texts.push(await page.locator('.ant-card-body').first().innerText());
  for (const t of texts) {
    const m = t.replace(/\s+/g, '').match(/共([\d,]+)条/);
    if (m) return Number(m[1].replace(/,/g, ''));
  }
  throw new Error(`页面上找不到「共 N 条」文案。分页区文本 = ${JSON.stringify(texts[0] ?? '')}`);
}

export async function activePageNo(page: Page): Promise<number> {
  const act = page.locator('.ant-pagination-item-active').first();
  if (!(await act.count())) return 1; // 单页时 antd 可能不渲染页码项
  return Number(norm(await act.innerText())) || 1;
}

// ──────────────────────────────── 控件定位 ────────────────────────────────

/** 现有关键字搜索框（报价单号/名称/客户）。 */
export function keywordInput(page: Page): Locator {
  return page.locator('input[placeholder*="搜索报价单号"]').first();
}

/** 🆕 AC-1 料号搜索框。placeholder 取自 AC-1 原文。 */
export function partNoInput(page: Page): Locator {
  return page.locator('input[placeholder*="按料号搜索"]').first();
}

/** 输入料号并回车触发查询。 */
export async function setPartNo(page: Page, value: string) {
  const input = partNoInput(page);
  await expect(input, 'AC-1: 料号搜索框应可见').toBeVisible({ timeout: 15_000 });
  await withListRequest(page, async () => {
    await input.fill(value);
    await input.press('Enter');
  });
}

export async function clearPartNo(page: Page) {
  await withListRequest(page, async () => {
    await partNoInput(page).fill('');
    await partNoInput(page).press('Enter');
  });
}

/**
 * 工具栏上的筛选 Select 在 `.ant-select` 全集里的下标。
 * ⚠️ 必须排除分页的 sizeChanger（它也是 .ant-select），否则 nth 会错位。
 * 返回值按「页面里第几个 .ant-select」计，选中后 placeholder 消失也仍然有效。
 */
export async function resolveFilterSelectIndexes(page: Page): Promise<{ category: number; template: number; all: string[] }> {
  return page.evaluate(() => {
    const all = Array.from(document.querySelectorAll('.ant-select'));
    // 🚨 本项目 antd 的类名与通用 v5 文档不同（2026-09-14 实测 DOM 取证）：
    //    占位符 = `.ant-select-placeholder`（不是 .ant-select-selection-placeholder）
    //    已选值 = `.ant-select-content`（不是 .ant-select-selection-item）
    //    两套都留着，换版本时不至于静默失灵。
    const label = (el: Element) => {
      const pick = (sel: string) => el.querySelector(sel)?.textContent || '';
      return [
        pick('.ant-select-placeholder'),
        pick('.ant-select-selection-placeholder'),
        pick('.ant-select-content'),
        pick('.ant-select-selection-item'),
        el.getAttribute('title') || '',
      ]
        .join('|')
        .replace(/\s+/g, '');
    };
    const usable = all.map((el, i) => ({ i, inPagination: !!el.closest('.ant-pagination'), text: label(el) }));
    const find = (kw: string) => usable.find((u) => !u.inPagination && u.text.includes(kw))?.i ?? -1;
    return {
      category: find('产品分类'),
      template: find('报价模板'),
      all: usable.map((u) => `${u.i}${u.inPagination ? '(pagination)' : ''}:${u.text}`),
    };
  });
}

export function selectAt(page: Page, index: number): Locator {
  return page.locator('.ant-select').nth(index);
}

function openDropdown(page: Page): Locator {
  return page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last();
}

/**
 * 展开下拉并把**全部**选项文案按渲染顺序收集出来。
 * ⚠️ antd Select 是虚拟滚动的：一次只渲染可视的几条，直接 count() 会少数 —— 必须滚动收集。
 */
export async function collectSelectOptions(page: Page, selectIndex: number): Promise<string[]> {
  await selectAt(page, selectIndex).click();
  const dd = openDropdown(page);
  await expect(dd, '下拉面板应展开').toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(300);

  const seen: string[] = [];
  const push = async () => {
    const texts = await dd.locator('.ant-select-item-option').allInnerTexts();
    for (const t of texts) {
      const v = norm(t);
      if (v && !seen.includes(v)) seen.push(v);
    }
  };
  await push();
  // 🚨 antd Select 是虚拟滚动：**固定滚动次数会收不全**（主线实测第一次只收到 10/13 项，误判成失败）。
  //    改为收敛式 —— 连续 3 轮既无新增项、滚动位置也不再变化，才认为收完。
  const holder = dd.locator('.rc-virtual-list-holder').first();
  if (await holder.count()) {
    let stable = 0;
    let lastPos = -1;
    for (let i = 0; i < 80 && stable < 3; i++) {
      const before = seen.length;
      const pos = await holder.evaluate((el) => {
        el.scrollTop = el.scrollTop + Math.max(40, el.clientHeight * 0.6);
        return el.scrollTop;
      });
      await page.waitForTimeout(120);
      await push();
      stable = seen.length === before && pos === lastPos ? stable + 1 : 0;
      lastPos = pos;
    }
  }
  return seen;
}

export async function closeDropdown(page: Page) {
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);
}

/** 在已展开的下拉里滚动找到并点击某个选项（虚拟滚动安全）。 */
export async function pickOption(page: Page, selectIndex: number, optionText: string) {
  await selectAt(page, selectIndex).click();
  const dd = openDropdown(page);
  await expect(dd, '下拉面板应展开').toBeVisible({ timeout: 10_000 });
  const target = dd.locator('.ant-select-item-option').filter({ hasText: optionText }).first();

  const holder = dd.locator('.rc-virtual-list-holder').first();
  for (let i = 0; i < 40; i++) {
    if (await target.count()) break;
    if (!(await holder.count())) break;
    const moved = await holder.evaluate((el) => {
      const before = el.scrollTop;
      el.scrollTop = el.scrollTop + Math.max(40, el.clientHeight * 0.8);
      return el.scrollTop !== before;
    });
    await page.waitForTimeout(150);
    if (!moved) break;
  }
  await expect(target, `下拉里应能找到选项「${optionText}」`).toHaveCount(1, { timeout: 5_000 });
  await withListRequest(page, async () => {
    await target.click();
  });
}

export async function clearSelect(page: Page, selectIndex: number) {
  const sel = selectAt(page, selectIndex);
  await sel.hover();
  // 类名两套都试（见 resolveFilterSelectIndexes 的注释）
  const clear = sel.locator('.ant-select-clear, [class*="select-clear"], [class*="clear-icon"]').first();
  await expect(clear, 'allowClear 的清除按钮应出现').toBeVisible({ timeout: 5_000 });
  await withListRequest(page, async () => {
    await clear.click();
  });
}

/**
 * 切状态页签。
 * ⚠️ 列表页页签的实现形态未知（antd Tabs / Radio.Group / Segmented 都可能），
 * 这里按候选依次尝试；全不命中时报出页面上找到了什么，🚫 不静默跳过。
 */
export async function switchStatusTab(page: Page, label: string) {
  const candidates = [
    `.ant-tabs-tab:has-text("${label}")`,
    `.ant-radio-button-wrapper:has-text("${label}")`,
    `.ant-segmented-item:has-text("${label}")`,
    `.ant-card-body button:has-text("${label}")`,
  ];
  for (const sel of candidates) {
    const loc = page.locator(sel).first();
    if (await loc.count()) {
      await withListRequest(page, async () => {
        await loc.click();
      });
      return sel;
    }
  }
  const dump = await page.locator('.ant-card-body').first().innerText();
  throw new Error(`找不到状态页签「${label}」。页面文本前 400 字 = ${dump.slice(0, 400)}`);
}

/** 翻页到第 n 页（虚拟省略号安全：找不到页码项就点「下一页」推进）。 */
export async function gotoPage(page: Page, n: number) {
  for (let guard = 0; guard < 30; guard++) {
    const cur = await activePageNo(page);
    if (cur === n) return;
    const direct = page.locator(`.ant-pagination-item[title="${n}"]`).first();
    if (await direct.count()) {
      await withListRequest(page, async () => {
        await direct.click();
      });
      continue;
    }
    const next = page.locator('.ant-pagination-next').first();
    const disabled = (await next.getAttribute('class'))?.includes('disabled');
    expect(disabled, `翻不到第 ${n} 页：当前第 ${cur} 页且「下一页」已禁用`).toBeFalsy();
    await withListRequest(page, async () => {
      await next.click();
    });
  }
  throw new Error(`翻页到第 ${n} 页超过 30 次仍未到达`);
}

/** 当前页的报价单号集合（用于证明翻页真的换了数据）。 */
export async function quotationNumbersOnPage(page: Page): Promise<string[]> {
  return columnCells(page, '报价单号');
}

/**
 * 按钮区快照（AC-20 比对用）。
 * 🚦 **C-8 裁决后三个操作按钮在「卡片标题栏」`.ant-card-head`，不再在 `.ant-card-body` 工具栏里。**
 * 两处都扫，并标出每个按钮落在哪个区（`zone`），这样「位置变了」会被看见而不是被静默漏掉。
 */
export async function toolbarButtonSnapshot(page: Page) {
  return page.evaluate(() => {
    const card = document.querySelector('.ant-card');
    if (!card) return [];
    const btns = Array.from(card.querySelectorAll('button')).filter(
      (b) => !b.closest('.ant-pagination') && !b.closest('.ant-table')
    );
    return btns.map((b, i) => {
      const r = b.getBoundingClientRect();
      return {
        order: i,
        zone: b.closest('.ant-card-head') ? 'card-head' : b.closest('.ant-card-body') ? 'card-body' : 'other',
        text: (b.textContent || '').replace(/\s+/g, ''),
        disabled: b.disabled || b.getAttribute('disabled') !== null || b.className.includes('ant-btn-disabled'),
        ariaDisabled: b.getAttribute('aria-disabled'),
        title: b.getAttribute('title') || (b.parentElement?.getAttribute('title') ?? ''),
        x: Math.round(r.x),
        y: Math.round(r.y),
        w: Math.round(r.width),
      };
    });
  });
}

/** 卡片标题（「报价单管理」）的位置 —— 判「按钮是否与标题同一行、在其右侧」。 */
export async function cardTitleBox(page: Page) {
  return page.evaluate(() => {
    const t = document.querySelector('.ant-card-head-title') || document.querySelector('.ant-card-head');
    if (!t) return null;
    const r = t.getBoundingClientRect();
    return { text: (t.textContent || '').replace(/\s+/g, ''), x: Math.round(r.x), y: Math.round(r.y), h: Math.round(r.height) };
  });
}
