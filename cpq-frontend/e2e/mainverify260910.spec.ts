/**
 * repair-260910 · **主线亲验** AC-3 / AC-4（候选列表的 UI 侧）
 * 🚫 不复用 test-engineer 的 helpers —— 亲验不采信子代理量具，独立走一遍。
 * 复用测试员已起的栈（5178 → 8097），只读、不写库。
 */
import { test, expect } from '@playwright/test';
const SHOT='/tmp/claude-1000/-home-joii-project-cpq/dd3dc6c5-18d8-4be3-b01e-677976918873/scratchpad';
const COID='bea4c465-d15a-411e-991d-43f9e108c087';

test('AC-3/AC-4 亲验：核价工作台版本下拉候选', async ({ page, request }) => {
  test.setTimeout(300000);

  // ① 验明正身：经 5178 代理调接口，必须拿到本分支后端的结果（master 上 300001 恒空）
  const login = await request.post('/api/cpq/auth/login', { data: { username:'admin', password:'Admin@2026' } });
  expect(login.ok(), '登录必须成功').toBeTruthy();
  const li='3c582035-d35a-4f99-9372-cb4b908a29bb', cid='32ab8212-df6c-4844-9721-ac7dc41d6cf2';
  const probe = await request.get(`/api/cpq/costing-orders/${COID}/version-options?lineItemId=${li}&componentId=${cid}&partNo=300001`);
  const pj = await probe.json();
  console.log('【验明正身】300001 →', JSON.stringify(pj?.data ?? pj));
  expect(JSON.stringify(pj?.data?.options ?? []), '必须打到本分支后端（master 上此处恒空）').toContain('3');

  // ② UI 登录
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products)/,{timeout:40000});

  // ③ 进核价工作台
  await page.goto(`/costing-orders/${COID}/review`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(6000);

  // 🚨 关键一步（测试员挖出的坑）：该页默认停在「报价单」分段，
  //    那一侧 BOM 表没有「版本」列且树用销售料号 —— 必须先切到「核价单」
  const seg = page.locator('.ant-segmented-item, .ant-radio-button-wrapper, button, [role=tab]')
                  .filter({ hasText: '核价单' });
  console.log('「核价单」分段命中数 =', await seg.count());
  await seg.first().click();
  await page.waitForTimeout(8000);
  await page.screenshot({ path: SHOT+'/MV-工作台-核价分段.png', fullPage: true });

  const card = page.locator('.qt-product-card').first();
  await card.locator('button.qt-tab-btn').filter({ hasText: 'BOM' }).first().click();
  await page.waitForTimeout(6000);

  // ④ 读表：按【版本列左侧一列】定位料号（表头里「料号」二字属于字段列「生产料号」，不能按文案找）
  const rows = await card.evaluate((root:any) => {
    const tables=[...root.querySelectorAll('table')].filter((t:any)=>t.offsetParent!==null);
    const t:any=tables.sort((a:any,b:any)=>b.querySelectorAll('tbody tr').length-a.querySelectorAll('tbody tr').length)[0];
    if(!t) return {head:[],rows:[]};
    const head=[...t.querySelectorAll('thead th')].map((h:any)=>(h.textContent||'').trim());
    const vi=head.findIndex((h:string)=>h==='版本');
    const rows=[...t.querySelectorAll('tbody tr')].map((r:any)=>{
      const td=[...r.querySelectorAll('td')];
      return { 节点:(td[vi-1]?.textContent||'').trim(),
               版本:(td[vi]?.textContent||'').trim(),
               antd: td[vi]?.querySelectorAll('.ant-select').length ?? 0,
               native: td[vi]?.querySelectorAll('select').length ?? 0 };
    });
    return {head,rows,vi};
  });
  console.log('表头 =', JSON.stringify(rows.head), ' 版本列下标 =', rows.vi);
  console.log('逐行 =', JSON.stringify(rows.rows));
  expect(rows.rows.length, '树必须非空（0 行会让后面断言恒真）').toBe(7);

  // ⑤ AC-3/AC-4：逐个展开非叶子行的下拉，读候选
  const want: Record<string,string[]> = { '300001':['3','2','1'], '300012':['1'], '300013':['1'], '300015':['1'] };
  for (const [partNo, expected] of Object.entries(want)) {
    // ⚠️ 树节点文本带展开箭头（如 ▼300001），不能用 === 精确匹配（主线自己踩的第 4 个量具坑）
    const idx = rows.rows.findIndex((r:any)=>String(r.节点).replace(/[▼▶\s]/g,'')===partNo);
    expect(idx, `行 ${partNo} 必须存在`).toBeGreaterThanOrEqual(0);
    const cell = card.locator('tbody tr').nth(idx).locator('td').nth(rows.vi);
    const sel = cell.locator('.ant-select');
    expect(await sel.count(), `${partNo} 必须有可交互下拉（阳性对照）`).toBeGreaterThan(0);
    await sel.first().click();
    await page.waitForTimeout(1500);
    // antd v6：下拉项只有 .ant-select-item-option
    const opts = (await page.locator('.ant-select-item-option:visible').allInnerTexts()).map(s=>s.trim()).filter(Boolean);
    console.log(`  ${partNo} 候选 = ${JSON.stringify(opts)}  期望 ${JSON.stringify(expected)}`);
    expect(opts.length, `${partNo} 候选必须非空（空数组会让"逐字相同"恒真）`).toBeGreaterThan(0);
    expect(opts, `${partNo} 候选须逐字相同`).toEqual(expected);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(600);
  }
  // 🔑 AC-4 的关键反向断言：300015 挂两个父件，但候选里不得出现版本 2
  console.log('✅ AC-3/AC-4 亲验通过（含 300015 不含版本 2 的反向断言）');
});
