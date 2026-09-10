/**
 * task-260909 · 主线亲验 AC-12（同组件上切换 field_type → 渲染形态变、值不变）
 * 载体：COMP-2422 的「元素代码」列。🚦 进场/收尾核对指纹，确保逐字还原。
 */
import { test, expect } from '@playwright/test';
const SHOT='/tmp/claude-1000/-home-joii-project-cpq/dd3dc6c5-18d8-4be3-b01e-677976918873/scratchpad/verify';
test.use({ viewport: { width: 1920, height: 1200 } });
const COL = '元素代码', COL_IDX = 4;   // 列序：生产料号/材料名/料号/项次/**元素代码**/组成含量/损耗率/元素单价

async function login(page:any){
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products)/,{timeout:20000});
}

async function setFieldType(page:any, target:string){
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  await page.locator('input[placeholder*="搜索组件"]').fill('COMP-2422');
  await page.waitForTimeout(1800);
  const card = page.locator('.cmm-card').filter({ hasText: 'COMP-2422' });
  const dir = page.locator('.cmm-dir').filter({ has: card });
  if (!(await card.isVisible())) { await dir.click({position:{x:12,y:12}}); await page.waitForTimeout(1500); }
  await card.locator('.cmm-c-name').first().click();
  await page.waitForTimeout(2500);
  await page.locator('.ant-tabs-tab', { hasText: '取数配置' }).first().click();
  await page.waitForTimeout(3500);
  const sel = page.locator(`[data-role="field-type-select"][data-field-name="${COL}"]`);
  await expect(sel, `列「${COL}」的选择器必须存在`).toHaveCount(1);
  await sel.click(); await page.waitForTimeout(800);
  await page.locator('.ant-select-item-option:visible', { hasText: target }).first().click();
  await page.waitForTimeout(600);
  const btn = page.locator('[data-role="builder-actions"] button').filter({ hasText: /保\s*存/ }).first();
  await btn.click(); await page.waitForTimeout(4000);
  const ok = page.locator('.ant-modal button').filter({ hasText: /确\s*定|确\s*认/ }).first();
  if (await ok.count() && await ok.isVisible()) { await ok.click(); await page.waitForTimeout(3000); }
  console.log(`   已把「${COL}」设为「${target}」并保存`);
}

async function renderAndRead(page:any){
  await page.goto('/quotations');
  await page.waitForLoadState('networkidle');
  const box = page.locator('input[placeholder*="搜索报价单号"]');
  await box.fill('T260909FT-渲染等价对照'); await box.press('Enter');
  await page.waitForTimeout(3000);
  await page.locator('tbody tr').first().locator('a').first().click();
  await page.waitForTimeout(4000);
  await page.locator('button').filter({ hasText: /^\s*编\s*辑\s*$/ }).first().click();
  await page.waitForTimeout(5000);
  await page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first().click();
  await page.waitForTimeout(9000);
  await page.locator('.ant-segmented-item, .ant-radio-button-wrapper, button, [role=tab]')
            .filter({ hasText: '核价单' }).first().click();
  await page.waitForTimeout(9000);
  const card = page.locator('.qt-product-card').first();
  await card.locator('button.qt-tab-btn').filter({ hasText: 'T260909FT-BASIC' }).click();
  await page.waitForTimeout(6000);
  return await card.evaluate((root:any, ci:number) => {
    const tables=[...root.querySelectorAll('table')].filter((t:any)=>t.offsetParent!==null);
    const t:any = tables.sort((a:any,b:any)=>b.querySelectorAll('tbody tr').length-a.querySelectorAll('tbody tr').length)[0];
    if(!t) return {vals:[],inputs:[]};
    const vals:string[]=[], inputs:number[]=[];
    for(const r of [...t.querySelectorAll('tbody tr')] as any[]){
      const td=[...r.querySelectorAll('td')][ci] as any;
      if(!td){vals.push('(无该列)');inputs.push(0);continue;}
      const inp=td.querySelector('input');
      vals.push(inp ? (inp.value??'') : (td.textContent||'').trim());
      inputs.push(inp?1:0);
    }
    return {vals,inputs};
  }, COL_IDX);
}

test('AC-12 亲验：同组件上 BASIC_DATA ↔ INPUT_TEXT 往返，值不变形态变', async ({ page }) => {
  test.setTimeout(900000);
  await login(page);

  console.log('① 初始态（BASIC_DATA）渲染');
  const s0 = await renderAndRead(page);
  console.log('   值 =', JSON.stringify(s0.vals), ' input 数 =', JSON.stringify(s0.inputs));
  expect(s0.vals.filter(v=>v!=='').length, 'AC-12：初始值必须非空，否则"值不变"恒真').toBeGreaterThan(0);
  expect(s0.inputs, 'AC-12①：BASIC_DATA 态该列应为纯文本').toEqual(s0.inputs.map(()=>0));

  console.log('② 改为 INPUT_TEXT');
  await setFieldType(page, '文本输入');
  const s1 = await renderAndRead(page);
  console.log('   值 =', JSON.stringify(s1.vals), ' input 数 =', JSON.stringify(s1.inputs));
  expect(s1.vals, 'AC-12②：切换后值必须逐字不变').toEqual(s0.vals);
  expect(s1.inputs, 'AC-12②：应变为 <input>').toEqual(s1.inputs.map(()=>1));

  console.log('③ 改回 BASIC_DATA');
  await setFieldType(page, '基础数据');
  const s2 = await renderAndRead(page);
  console.log('   值 =', JSON.stringify(s2.vals), ' input 数 =', JSON.stringify(s2.inputs));
  expect(s2.vals, 'AC-12③：改回后值仍逐字不变').toEqual(s0.vals);
  expect(s2.inputs, 'AC-12③：应变回纯文本').toEqual(s0.inputs);
  console.log('✅ AC-12 往返闭合：值三态全等 =', JSON.stringify(s0.vals));
});
