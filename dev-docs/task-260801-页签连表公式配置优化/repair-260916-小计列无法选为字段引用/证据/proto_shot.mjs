import { chromium } from '/home/joii/project/cpq/cpq-frontend/node_modules/@playwright/test/index.mjs';
const d = process.argv[2];
const b = await chromium.launch({ channel: 'chrome' }).catch(() => chromium.launch());
const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
for (const [f, out] of [['公式抽屉.html', '原型截图-公式抽屉.png'], ['index.html', '原型截图-index.png']]) {
  await p.goto('file://' + d + '/原型图/' + f);
  await p.screenshot({ path: d + '/证据/' + out, fullPage: true });
  const overflow = await p.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  console.log(f, 'horizontal overflow =', overflow);
}
await p.setViewportSize({ width: 390, height: 800 });
await p.goto('file://' + d + '/原型图/公式抽屉.html');
console.log('390px overflow =', await p.evaluate(() => document.documentElement.scrollWidth > window.innerWidth));
await b.close();
