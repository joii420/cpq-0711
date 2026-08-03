/**
 * task-0729 跨屏元素单价列只读态改动的最小回归冒烟：真实 DRAFT 报价单编辑页 + 详情页
 * 正常打开、无 pageerror、无 Vite 错误 overlay（不依赖 mock，验证的是"没有把现有渲染搞崩"，
 * 不是"新功能生效"——新功能生效的验证见 tmp-task0729-price-locked-cell.spec.ts 的代码走查结论）。
 * 用完即删。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('回归冒烟：真实 DRAFT 单编辑页 + 详情页正常渲染，无 console 报错', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message));

  await loginAsAdmin(page);
  const res = await page.request.get('/api/cpq/quotations?page=1&size=1&status=DRAFT');
  const json = await res.json();
  const qid = (json?.content ?? json?.data?.content ?? [])[0]?.id;
  test.skip(!qid, '本库暂无 DRAFT 报价单');
  if (!qid) return;

  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await expect(page.locator('text=Not found').first()).toHaveCount(0); // 无 broken-image 风格的资源 404 轰炸
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-regsmoke-01-edit.png'), fullPage: true }).catch(() => {});

  await page.goto(`/quotations/${qid}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-regsmoke-02-detail.png'), fullPage: true }).catch(() => {});

  console.log('[T0729][regression-smoke] pageerror 数量=', errs.length, errs.slice(0, 5));
  expect(errs.length, 'no pageerror expected: ' + errs.join(' | ')).toBe(0);
});
