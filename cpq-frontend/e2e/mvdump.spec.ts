import { test } from '@playwright/test';
const SHOT='/tmp/claude-1000/-home-joii-project-cpq/dd3dc6c5-18d8-4be3-b01e-677976918873/scratchpad/verify';
test('dump 搜索后的树结构', async ({ page }) => {
  test.setTimeout(120000);
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products)/, { timeout: 20000 });
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  await page.locator('input[placeholder*="搜索组件"]').fill('COMP-2299');
  await page.waitForTimeout(2000);
  await page.screenshot({ path: SHOT+'/20-dump.png', fullPage: true });

  const info = await page.evaluate(() => {
    const bom: any = [...document.querySelectorAll('.cmm-c-name')].find((e: any) => (e.textContent||'').trim()==='BOM');
    const chain: any[] = [];
    let cur: any = bom;
    for (let i=0; cur && i<8; i++) {
      const r = cur.getBoundingClientRect();
      chain.push({ lvl:i, tag:cur.tagName, cls:(cur.className?.toString?.()||'').slice(0,90),
                   vis: r.width>0&&r.height>0, h:Math.round(r.height),
                   txt:(cur.textContent||'').trim().slice(0,50) });
      cur = cur.parentElement;
    }
    // 含「核价」二字的所有元素（叶子）
    const hejia: any[] = [];
    document.querySelectorAll('*').forEach((e: any) => {
      if (e.childElementCount===0 && (e.textContent||'').includes('核价')) {
        const r = e.getBoundingClientRect();
        hejia.push({ tag:e.tagName, cls:(e.className?.toString?.()||'').slice(0,60),
                     txt:(e.textContent||'').trim().slice(0,40), vis:r.width>0&&r.height>0,
                     y:Math.round(r.y) });
      }
    });
    return { chain, hejia };
  });
  console.log('BOM 祖先链 =', JSON.stringify(info.chain, null, 1));
  console.log('含「核价」的叶子 =', JSON.stringify(info.hejia, null, 1));
});
