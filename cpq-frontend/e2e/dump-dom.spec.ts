import { test } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

test('dump step1 DOM', async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);

  // 1) 列举所有 placeholder
  const placeholders = await page.locator('[placeholder]').evaluateAll((els) =>
    els.map((e: any) => ({ tag: e.tagName, type: e.type || '', placeholder: e.placeholder }))
  );
  console.log('--- All [placeholder] elements ---');
  placeholders.forEach((p) => console.log('  ', JSON.stringify(p)));

  // 2) 列举所有 ant-select
  const selects = await page.locator('.ant-select').evaluateAll((els) =>
    els.map((e: any) => {
      const ph = e.querySelector('.ant-select-selection-placeholder');
      const item = e.querySelector('.ant-select-selection-item');
      return {
        cls: e.className,
        placeholder: ph?.textContent || '',
        value: item?.textContent || '',
      };
    })
  );
  console.log('--- All .ant-select wrappers ---');
  selects.forEach((s) => console.log('  ', JSON.stringify(s)));

  // 3) 直接看含"客户"的最近 input
  const customerLabel = await page.locator('text=客户').count();
  console.log('客户 text count:', customerLabel);

  // 4) role=combobox
  const comboboxes = await page.getByRole('combobox').count();
  console.log('role=combobox count:', comboboxes);

  // 5) 直接抓 step1 form 区域的 HTML 局部
  const html = await page.locator('text=报价单基本信息').locator('..').locator('..').innerHTML().catch(() => '(not found)');
  console.log('--- HTML around 报价单基本信息 (first 3000 chars) ---');
  console.log(html.slice(0, 3000));
});
