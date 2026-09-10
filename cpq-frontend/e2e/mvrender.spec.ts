/**
 * task-260909 · **主线亲验** AC-6 / AC-7（渲染等价 —— 本任务风险最高的一条）
 * 🚫 不复用 test-engineer 的 helpers。走用户视角完整路径：列表页搜索 → 点单号 → 编辑 → Step2 → 核价单。
 */
import { test, expect } from '@playwright/test';
const SHOT='/tmp/claude-1000/-home-joii-project-cpq/dd3dc6c5-18d8-4be3-b01e-677976918873/scratchpad/verify';
test.use({ viewport: { width: 1920, height: 1200 } });

async function login(page:any){
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products)/,{timeout:20000});
}

/** 抓当前可见卡片表格的所有单元格。有 <input> 读 value，否则读文本 —— innerText 读不到 input 的值。 */
async function grabCells(page:any){
  return await page.evaluate(() => {
    const tables = [...document.querySelectorAll('table')].filter((t:any)=>t.offsetParent!==null);
    const out: {rows:number, cells:string[][], inputs:number}[] = [];
    for (const t of tables as any[]) {
      const rows = [...t.querySelectorAll('tbody tr')];
      const cells: string[][] = [];
      let inputs = 0;
      for (const r of rows as any[]) {
        const line: string[] = [];
        for (const td of [...r.querySelectorAll('td')] as any[]) {
          const inp = td.querySelector('input');
          if (inp) { inputs++; line.push(inp.value ?? ''); }
          else line.push((td.textContent||'').trim());
        }
        cells.push(line);
      }
      out.push({ rows: rows.length, cells, inputs });
    }
    return out;
  });
}

test('AC-6 / AC-7 亲验：BASIC vs INPUT 双页签渲染等价', async ({ page }) => {
  test.setTimeout(420000);
  await login(page);
  await page.goto('/quotations');
  await page.waitForLoadState('networkidle');
  // ⚠️ 只 fill 不回车列表不过滤；🚫 也不直接导航 /edit（那样得 0 张卡片）
  const box = page.locator('input[placeholder*="搜索报价单号"]');
  await box.fill('T260909FT-渲染等价对照'); await box.press('Enter');
  await page.waitForTimeout(3000);
  expect(await page.locator('tbody tr').count(), '搜索须命中且真过滤').toBe(1);
  console.log('① 列表已过滤');
  await page.locator('tbody tr').first().locator('a').first().click();
  await page.waitForTimeout(4000);
  console.log('② 已进详情', page.url());
  await page.locator('button').filter({ hasText: /^\s*编\s*辑\s*$/ }).first().click();
  await page.waitForTimeout(5000);
  console.log('③ 已进编辑', page.url());
  await page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first().click();
  await page.waitForTimeout(9000);
  console.log('④ 已进 Step2');
  // 切到核价单 —— 🚫 别用 locator('*')，它会匹到大量不可点的祖先元素
  const costBtn = page.locator('.ant-segmented-item, .ant-radio-button-wrapper, button, [role=tab]')
                      .filter({ hasText: '核价单' }).first();
  await expect(costBtn, '「核价单」切换必须存在').toHaveCount(1);
  await costBtn.click();
  await page.waitForTimeout(9000);
  console.log('⑤ 已切核价单');
  await page.screenshot({ path: SHOT+'/54-核价单.png', fullPage: true });

  // 实测结构：页签 = button.qt-tab-btn（在 .qt-tab-header 内），卡片 = .qt-product-card
  // 🚫 不是 .ant-tabs-tab —— 按 antd 类名找会匹配 0 个且不报错。
  const card = page.locator('.qt-product-card').first();
  await expect(card, '第 1 张产品卡片必须存在').toHaveCount(1);
  const cardTabs = await card.locator('button.qt-tab-btn').allInnerTexts();
  console.log('第 1 张卡片的页签 =', JSON.stringify(cardTabs.map(t=>t.replace(/\s+/g,''))));

  const res: Record<string, any> = {};
  for (const name of ['T260909FT-BASIC','T260909FT-INPUT']) {
    const tab = card.locator('button.qt-tab-btn').filter({ hasText: name });
    await expect(tab, `页签 ${name} 必须存在于第 1 张卡片`).toHaveCount(1);
    await tab.click();
    await page.waitForTimeout(6000);
    await page.screenshot({ path: `${SHOT}/55-${name}.png`, fullPage: true });
    const g = await card.evaluate((root: any) => {
      const tables = [...root.querySelectorAll('table')].filter((t:any)=>t.offsetParent!==null);
      const out: any[] = [];
      for (const t of tables as any[]) {
        const rows = [...t.querySelectorAll('tbody tr')];
        const cells: string[][] = []; let inputs = 0;
        for (const r of rows as any[]) {
          const line: string[] = [];
          for (const td of [...r.querySelectorAll('td')] as any[]) {
            const inp = td.querySelector('input');
            if (inp) { inputs++; line.push(inp.value ?? ''); }
            else line.push((td.textContent||'').trim());
          }
          cells.push(line);
        }
        out.push({ rows: rows.length, cells, inputs });
      }
      return out;
    });
    const main = g.sort((x:any,y:any)=>y.rows-x.rows)[0] || {rows:0,cells:[],inputs:0};
    res[name] = main;
    console.log(`${name}: 行数=${main.rows} 承载值input数=${main.inputs}`);
    console.log(`${name} 单元格 =`, JSON.stringify(main.cells));
  }

  const A = res['T260909FT-BASIC'], B = res['T260909FT-INPUT'];
  // 防空验证：两侧都必须非空
  expect(A.rows, 'AC-6：BASIC 侧行数必须 > 0（否则"逐字相同"恒真）').toBeGreaterThan(0);
  expect(B.rows, 'AC-6：INPUT 侧行数必须 > 0').toBeGreaterThan(0);
  expect(A.rows, 'AC-6：两侧行数相同').toBe(B.rows);

  // AC-6：所有**非空**单元格逐字相同（判据已订正 —— null 格的「—」vs 空框差异不算）
  let same=0, diff:any[]=[], blank=0;
  for (let i=0;i<A.cells.length;i++){
    for (let j=0;j<Math.max(A.cells[i].length,B.cells[i].length);j++){
      const a=(A.cells[i][j]??'').trim(), b=(B.cells[i][j]??'').trim();
      const aEmpty = a==='' || a==='—', bEmpty = b==='' || b==='—';
      if (aEmpty && bEmpty) { blank++; continue; }
      if (a===b) same++; else diff.push({row:i,col:j,BASIC:a,INPUT:b});
    }
  }
  console.log(`AC-6 结果：非空逐字相同 ${same} ｜ 不一致 ${diff.length} ｜ 两侧皆空(占位差异) ${blank}`);
  if (diff.length) console.log('不一致明细 =', JSON.stringify(diff));
  expect(same, 'AC-6：非空单元格数必须 > 0（全空则断言恒真）').toBeGreaterThan(0);
  expect(diff, 'AC-6：非空单元格必须逐字相同').toEqual([]);

  // AC-7：BASIC 侧无承载值的 <input>；INPUT 侧有（阳性对照，排除"整页没渲染"）
  console.log(`AC-7：BASIC input 数 = ${A.inputs} ｜ INPUT input 数 = ${B.inputs}`);
  expect(A.inputs, 'AC-7：BASIC 页签不得有承载值的 <input>').toBe(0);
  expect(B.inputs, 'AC-7：INPUT 页签必须仍是 <input>（阳性对照）').toBeGreaterThan(0);
  console.log('✅ AC-6 / AC-7 亲验通过');
});
