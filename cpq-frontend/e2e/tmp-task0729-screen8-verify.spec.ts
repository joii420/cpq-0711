/**
 * task-0729 屏 8：组件编辑器元素列绑定三个下拉——真实后端冒烟验证（/components 端点早已存在，
 * 不依赖 price-adjust 后端进度）。只验证 UI 渲染 + 保存交互，不断言后端是否已持久化新字段
 * （B7 组件三项显式绑定后端进度未知，用此测试顺带侦察）。用完即删。
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

test('屏8：元素列/元素单价列/货币列下拉渲染 + 保存交互', async ({ page }) => {
  test.skip(!backendUp, 'backend down');

  const respPromise: { status?: number; body?: any } = {};
  page.on('response', async (resp) => {
    if (resp.request().method() === 'PUT' && /\/api\/cpq\/components\/[^/]+$/.test(new URL(resp.url()).pathname)) {
      respPromise.status = resp.status();
      try { respPromise.body = await resp.json(); } catch { /* ignore */ }
    }
  });

  await loginAsAdmin(page);
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);

  // 左侧树：自定义目录树（非 antd Tree），先展开"页签组件"分组（NORMAL 类型，才会显示三个下拉），
  // 再点第一个组件卡片（.cmm-card，见 ComponentManagement.tsx:704-707）
  await page.locator('text=页签组件').first().click();
  await page.waitForTimeout(500);
  await page.locator('.cmm-card').first().click();
  await page.waitForTimeout(1000);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-s8-01-selected.png'), fullPage: true }).catch(() => {});

  const codeSelect = page.locator('.cmm-acts .ant-select', { hasText: '元素列' });
  const hasElementUI = await codeSelect.count();
  console.log('[T0729][屏8] 元素列下拉出现次数（0 说明选中的不是 NORMAL 组件，或该组件树为空）=', hasElementUI);

  if (hasElementUI > 0) {
    await expect(page.locator('.cmm-acts', { hasText: '元素单价列' })).toHaveCount(1);
    await expect(page.locator('.cmm-acts', { hasText: '货币列' })).toHaveCount(1);
    await expect(page.locator('button', { hasText: '推荐' })).toHaveCount(1);
    await page.screenshot({ path: path.join(SHOT_DIR, 't0729-s8-02-element-fields.png') }).catch(() => {});

    // 点保存，观察后端响应（不改动任何字段值，纯粹验证 payload 携带 elementCodeField 等新键不会导致 500）
    // 用 .cmm-acts 范围限定，避免撞到左侧"保存全部草稿"同名按钮（常因无草稿而 disabled）
    await page.locator('.cmm-acts button', { hasText: '保存' }).first().click();
    await page.waitForTimeout(1500);
    console.log('[T0729][屏8] PUT /components/{id} 响应状态=', respPromise.status, 'body=', JSON.stringify(respPromise.body)?.slice(0, 500));
  }
});
