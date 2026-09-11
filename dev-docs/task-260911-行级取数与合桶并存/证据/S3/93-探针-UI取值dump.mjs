import { chromium } from 'playwright';
const QID = process.argv[2], TAG = process.argv[3], OUT = process.argv[4];
const b = await chromium.launch({ headless: true, executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'] });
const ctx = await b.newContext({ viewport: { width: 1700, height: 1200 } });
const p = await ctx.newPage();
await p.goto('http://localhost:5174/login', { waitUntil: 'domcontentloaded' });
await p.waitForTimeout(1500);
await p.fill('input[placeholder*="用户"], input#username, input[name="username"]', 'admin');
await p.fill('input[type="password"]', 'Admin@2026');
await p.click('button[type="submit"], button:has-text("登 录"), button:has-text("登录")');
await p.waitForTimeout(3000);
await p.goto(`http://localhost:5174/quotations/${QID}/edit`, { waitUntil: 'domcontentloaded' });
await p.waitForTimeout(7000);
await p.getByRole('button', { name: /下\s*一\s*步/ }).click();
await p.waitForTimeout(10000);
await p.screenshot({ path: `${OUT}/${TAG}-step2-全页.png`, fullPage: true });
const cards = await p.locator('table').all();
console.log('table count =', cards.length);
const dump = await p.evaluate(() => {
  const out = [];
  document.querySelectorAll('table').forEach((t, ti) => {
    const heads = [...t.querySelectorAll('thead th')].map(e => e.innerText.trim());
    const rows = [...t.querySelectorAll('tbody tr')].map(tr =>
      [...tr.querySelectorAll('td')].map(td => {
        const inp = td.querySelector('input,textarea');
        return inp ? ('[input]' + (inp.value ?? '')) : td.innerText.trim();
      })
    );
    out.push({ ti, heads, rows });
  });
  return out;
});
console.log(JSON.stringify(dump, null, 1).slice(0, 6000));
await b.close();
