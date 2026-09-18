/** S-C 探测 2（只读）：导入弹层关闭后再次进入组件页，目录列表为何找不到前缀目录 */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
const OUT = '/home/joii/project/cpq/.claude/worktrees/repair-260916-subtotal-suffix/dev-docs/task-260801-页签连表公式配置优化/repair-260916-小计列无法选为字段引用/证据/测试/S-C/探测';
const log = (n: string, o: unknown) => { fs.appendFileSync(path.join(OUT, '探测记录.txt'), `[${new Date().toISOString()}] ${n}: ${typeof o === 'string' ? o : JSON.stringify(o)}\n`); console.log(n, o); };
test.skip(process.env.PW_BASE_URL !== 'http://localhost:5293', 'only temp stack');
test('probe-import-twice', async ({ page }) => {
  await page.request.post('/api/cpq/auth/login', { data: { username: 'admin', password: 'Admin@2026' } });
  for (let i = 0; i < 2; i++) {
    await page.goto('/components');
    await page.waitForLoadState('networkidle'); await page.waitForTimeout(3000);
    log(`p2.round${i}.url`, page.url());
    log(`p2.round${i}.dirNames`, await page.locator('.cmm-dir .cmm-dir-name').allInnerTexts());
    log(`p2.round${i}.inputs`, await page.locator('input').evaluateAll((els) => els.map((e) => [(e as HTMLInputElement).placeholder, (e as HTMLInputElement).value])));
    await page.screenshot({ path: path.join(OUT, `p2-round${i}.png`) });
    if (i === 0) {
      const dir = page.locator('.cmm-dir').filter({ hasText: 'RP0916C-导入v10' }).first();
      await dir.locator('.cmm-dir-acts button').filter({ has: page.locator('.anticon-import') }).first().click();
      const panel = page.locator('.ant-modal, .ant-drawer').filter({ hasText: /导入组件到目录/ }).last();
      await expect(panel).toBeVisible();
      await panel.locator('input[type=file]').first().setInputFiles('/home/joii/project/cpq/.claude/worktrees/repair-260916-subtotal-suffix/dev-docs/task-260801-页签连表公式配置优化/repair-260916-小计列无法选为字段引用/证据/夹具/施耐德成环检测-导出包-v1.0.json');
      await panel.getByRole('button', { name: /预\s*览/ }).first().click();
      await page.waitForTimeout(3000);
      log('p2.panelButtons', await panel.locator('button').allInnerTexts());
      const closeBtn = panel.getByRole('button', { name: /关\s*闭/ });
      log('p2.closeCount', await closeBtn.count());
      await closeBtn.first().click({ timeout: 5000 }).catch((e) => log('p2.closeErr', String(e).slice(0, 200)));
      await page.waitForTimeout(800);
      log('p2.afterClose.url', page.url());
      log('p2.afterClose.modalsVisible', await page.locator('.ant-modal:visible').count());
    }
  }
});
