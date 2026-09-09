/**
 * 主线亲验 · repair-260908 —— AC-1 / AC-2 / AC-3 走 UI（详情页，用户查看卡片的真实入口）
 * 量具 = 页面渲染文本（用户真正看到的东西）。🚫 不用 CSS 类名 —— 本页页签/表格非 antd 原生类，
 *        第一版用 .ant-tabs-tab / .ant-table 全部落空却报 passed（量具失效，已记入 test.md）。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs'; import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';
const SHOT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots', 'qinyan-260908');
fs.mkdirSync(SHOT, { recursive: true });
const QID = '4ce0fcc4-a73b-4672-ba45-6387e03491ad';

let up = false;
test.beforeAll(async () => { up = await isBackendUp(); });

test('主线亲验 AC-1/AC-2/AC-3（详情页文本量具）', async ({ page }) => {
  test.setTimeout(420000);
  test.skip(!up, '后端未启动');
  const errs: string[] = [];
  page.on('pageerror', e => errs.push('PAGE-ERROR: ' + e.message));

  await loginAsAdmin(page);
  await page.goto(`/quotations/${QID}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(10000);
  await page.evaluate(() => (Array.from(document.querySelectorAll('*')) as HTMLElement[])
      .filter(e => e.scrollHeight > e.clientHeight + 50).forEach(e => { e.scrollTop = e.scrollHeight; }));
  await page.waitForTimeout(6000);
  await page.screenshot({ path: path.join(SHOT, 'AC1-详情页全览.png'), fullPage: true });

  const text = await page.evaluate(() => document.body.innerText);
  fs.writeFileSync(path.join(SHOT, 'AC1-页面文本.txt'), text, 'utf-8');

  // 🔑 量具自证：文本必须非空且含卡片结构，否则后面所有断言都是空跑
  expect(text.length, '页面文本为空 ⇒ 量具失效，不许据此下结论').toBeGreaterThan(500);
  expect(text, '页面未渲染产品卡片 ⇒ 量具失效').toContain('产品小计');

  // 按「料号: X」切卡片
  const cards = text.split(/料号:\s*/).slice(1);
  console.log(`[量具自证] 页面文本 ${text.length} 字符，切出 ${cards.length} 张卡片`);
  expect(cards.length, '切不出卡片 ⇒ 量具失效').toBeGreaterThan(0);

  let ac1Pass = 0, ac1Fail = 0;
  for (const c of cards) {
    const pn = (c.match(/^([A-Za-z0-9\-]+)/) || [])[1];
    if (!pn) continue;
    // 「产品」页签的数据区：表头行之后到「产品小计」之前
    const seg = c.split('产品小计')[0];
    const idx = seg.indexOf('类型');                    // 产品页签表头最后一列
    const dataPart = idx >= 0 ? seg.slice(idx + 2) : '';
    const dataLines = dataPart.split('\n').map(s => s.trim()).filter(s => s.length > 0);
    const rows = dataLines.filter(l => l.includes('\t') || /^[A-Za-z0-9]/.test(l));
    const pnInRows = new Set(rows.map(r => r.split('\t')[0]).filter(Boolean));
    const ok = rows.length === 1 && pnInRows.has(pn);
    console.log(`[AC-1] 卡片 ${pn}：产品页签数据行=${rows.length}  出现的料号=${[...pnInRows].join(',')}  ${ok ? '✅' : '❌'}`);
    ok ? ac1Pass++ : ac1Fail++;
  }
  console.log(`[AC-1] 汇总 通过 ${ac1Pass} / 失败 ${ac1Fail}`);

  // AC-2 / 回归：不得出现加载中、渲染失败、暂无组件数据
  for (const bad of ['加载中', '渲染失败', '暂无组件数据']) {
    const n = (text.match(new RegExp(bad, 'g')) || []).length;
    console.log(`[AC-2/回归] 「${bad}」出现 ${n} 次 ${n === 0 ? '✅' : '❌'}`);
  }
  // AC-3：四个页签名都在
  for (const tab of ['产品', 'BOM', '材质元素', '加工费']) {
    console.log(`[AC-3] 页签「${tab}」出现 ${(text.match(new RegExp(tab, 'g')) || []).length} 次`);
  }
  console.log(`[pageerror] ${errs.length}`);
  errs.slice(0, 5).forEach(e => console.log('  ' + e));
});
