/** task-0729 主线亲验：分类持久化后编辑页显示。用完即删。 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

// 新建带分类、无模板（原本必现丢失的场景）
const NEW_ID = 'd80621ca-419f-49c0-a0c7-fefab280e88b';   // QT-20260729-0030
let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

/** 采样分类 Select 的显示值 + Form.Item 的 help 提示。
 *  注意本仓 antd 的选中值节点是 .ant-select-content（不是 .ant-select-selection-item）。 */
async function sampleCategory(page: Page, label: string) {
  const item = page.locator('.ant-form-item', { hasText: '产品分类' }).first();
  const shown = (await item.locator('.ant-select-content, .ant-select-selection-item').first()
    .innerText().catch(() => '')).trim();
  const placeholder = (await item.locator('.ant-select-selection-placeholder').first()
    .innerText().catch(() => '')).trim();
  const help = (await item.locator('.ant-form-item-explain').first().innerText().catch(() => '')).trim();
  console.log(`[T0729][${label}] 显示值="${shown}" 占位="${placeholder}" 提示="${help}"`);
  return { shown, placeholder, help };
}

test('分类持久化：新建带分类且无模板的单，编辑页仍能显示分类', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);
  await page.goto(`/quotations/${NEW_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  const r = await sampleCategory(page, 'QT-20260729-0030(新建/有分类/无模板)');
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-01-new.png'), fullPage: true }).catch(() => {});
  expect(r.shown, '分类应显示出名称而不是空').not.toBe('');
  expect(r.help, '不应再出现红字提示').toBe('');
});
