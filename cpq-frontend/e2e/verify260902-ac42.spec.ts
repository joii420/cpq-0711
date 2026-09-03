/**
 * task-260902 · AC-42 零回归 A/B 取证（前端工程师自检用，非正式测试用例）
 *
 * 同一后端（8081）、同一库、同一账号，只换前端来源：
 *   PW_BASE_URL=http://localhost:5174 → 主工作区 = **改动前**
 *   PW_BASE_URL=http://localhost:5200 → worktree = **改动后**
 * 两轮各自截图 + 打印可比对的结构化事实（列头 / 行数 / 首行文本 / 抽屉 tab 名）。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin } from './fixtures/auth';

const __filenameLocal = fileURLToPath(import.meta.url);
const SHOT_DIR = path.join(path.dirname(__filenameLocal), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const TAG = process.env.AB_TAG || 'unknown';

async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `ac42-${TAG}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`SHOT ${name} -> ${file}`);
}

test('AC-42 · 料号核价页签结构取证', async ({ page }) => {
  test.setTimeout(180_000); // 5174 是共享 dev server，可能被其它会话占着，给足时间
  await loginAsAdmin(page);
  await page.goto('/master-data-hub');
  await page.waitForSelector('.ant-tabs-nav .ant-tabs-tab', { timeout: 60_000 });
  await page.waitForTimeout(2000);

  // 页签清单（AC-24 也看这一行）
  const tabs = await page.locator('.ant-tabs-nav .ant-tabs-tab').allInnerTexts();
  console.log(`[${TAG}] TABS = ${JSON.stringify(tabs.map((t) => t.trim()))}`);

  // 停在默认页签「料号核价」
  await page.waitForSelector('.ant-table-thead th', { timeout: 15_000 });
  const headers = await page.locator('.ant-table-thead th').allInnerTexts();
  console.log(`[${TAG}] LIST_HEADERS = ${JSON.stringify(headers.map((h) => h.trim()))}`);

  const placeholder = await page.locator('input[placeholder="按料号 / 品名搜索"]').count();
  console.log(`[${TAG}] SEARCH_PLACEHOLDER_MATCH = ${placeholder}`);

  const btns = await page.locator('.ant-tabs-tabpane-active button').allInnerTexts();
  console.log(`[${TAG}] TOOLBAR_BUTTONS = ${JSON.stringify(btns.map((b) => b.replace(/\s+/g, '')).filter(Boolean).slice(0, 6))}`);

  const total = await page.locator('.ant-pagination-total-text').innerText().catch(() => '(none)');
  console.log(`[${TAG}] PAGINATION_TOTAL = ${total}`);

  const rows = page.locator('.ant-table-tbody tr.ant-table-row');
  const rowCount = await rows.count();
  console.log(`[${TAG}] ROW_COUNT = ${rowCount}`);
  if (rowCount > 0) {
    const first = await rows.nth(0).allInnerTexts();
    console.log(`[${TAG}] FIRST_ROW = ${JSON.stringify(first.map((t) => t.replace(/\s+/g, ' ').trim()))}`);
  }
  await shot(page, 'list');

  // 开抽屉（点第一行）
  if (rowCount > 0) {
    // 点第 2 列（料号）单元格，避开第 1 列的 <a>（行点击处理器会跳过 a/button）
    await rows.nth(0).locator('td').nth(1).click();
    await page.waitForSelector('.ant-drawer', { timeout: 15_000 });
    await page.waitForTimeout(2500);
    const drawerTitle = (await page.locator('.ant-drawer-title').innerText().catch(() => '')).replace(/\s+/g, ' ').trim();
    console.log(`[${TAG}] DRAWER_TITLE = ${JSON.stringify(drawerTitle)}`);
    const leftTabs = await page.locator('.ant-drawer .ant-tabs-left .ant-tabs-tab').allInnerTexts();
    console.log(`[${TAG}] DRAWER_TABS(${leftTabs.length}) = ${JSON.stringify(leftTabs.map((t) => t.replace(/\s+/g, ' ').trim()))}`);
    const drawerBtns = await page.locator('.ant-drawer button').allInnerTexts();
    console.log(`[${TAG}] DRAWER_BUTTONS = ${JSON.stringify(drawerBtns.map((b) => b.replace(/\s+/g, '')).filter(Boolean))}`);
    const innerHeaders = await page.locator('.ant-drawer .ant-tabs-tabpane-active .ant-table-thead th').allInnerTexts();
    console.log(`[${TAG}] DRAWER_TABLE_HEADERS = ${JSON.stringify(innerHeaders.map((h) => h.trim()))}`);
    await shot(page, 'drawer');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(800);
  }

  // 排序：点「已配置」列头，确认服务端排序请求参数逐字一致
  const sortReq: string[] = [];
  page.on('request', (r) => {
    const u = r.url();
    if (u.includes('/pricing-basic-data/parts')) sortReq.push(u.split('/api/cpq')[1] ?? u);
  });
  await page.locator('.ant-table-thead th', { hasText: '已配置' }).first().click();
  await page.waitForTimeout(1500);
  console.log(`[${TAG}] SORT_REQUESTS = ${JSON.stringify(sortReq)}`);

  expect(headers.length).toBeGreaterThan(0);
});
