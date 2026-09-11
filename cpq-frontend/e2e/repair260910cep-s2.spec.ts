/**
 * repair-260910 · S-2「渲染与回归」片 · UI 用例
 * 认领：AC-1(UI 半) / AC-3(序列) / AC-7(UI 计数) / AC-11 ④层
 * 验收对象：QT-20260910-0005（B-9 新建验收单，绑核价模板 v1.3）
 * 🚫 不写任何数据；对 QT-20260910-0004 一行都不碰。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';

const QID = 'c8eb386e-c051-4d24-aa72-dd8cb18ce406';
const EV = '../dev-docs/task-260908-取数配置器优化/repair-260910-核价侧无销售料号子件取不到元素单价/证据';
const BAD = ['', '—', '-', '0', '加载中…', '加载中...'];

/** 把 "¥ 9134.85" / "1,234.5" 这类展示文本解析成数值。
 *  ⚠️ 直接 parseFloat("¥ 9134.85") = NaN，而 NaN > 0 恒 false ⇒ 会红成「值不对」，
 *     实际是解析没做（本轮实测踩到）。 */
const num = (t: string) => parseFloat(String(t ?? '').replace(/[^0-9.\-]/g, ''));

type Row = Record<string, string>;

async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products)/, { timeout: 30_000 });
}

/** 进入向导 step2 的核价单视图，并切到指定页签，返回该页签的表格 locator */
async function gotoCostingTab(page: Page, tabName: string) {
  // 每次都显式重进编辑页：reload 后 URL 仍是 /edit 但页面回到 Step1 且需要时间重建，
  // 依赖「URL 里有 /edit 就不重进」会在 reload 后拿到半张页面（实测 T2.3 就是栽在这里）
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(2000);
  // 进 step2：🚫 点步骤条无效（实测），必须点「下一步」
  if (!(await page.locator('body').innerText()).includes('核价单')) {
    const next = page.getByRole('button', { name: /下\s*一\s*步/ }).first();
    await next.waitFor({ timeout: 60_000 });
    await next.click();
    await page.waitForTimeout(5000);
  }
  // 切核价单视图：⚠️ 文案带 emoji 前缀「📊 核价单」，exact:true 必然匹配不上
  const costing = page.getByText('核价单', { exact: false }).first();
  await costing.waitFor({ timeout: 30_000 });
  await costing.click();
  await page.waitForTimeout(3000);
  // 切页签
  const tab = page.getByText(tabName, { exact: true }).first();
  await tab.waitFor({ timeout: 30_000 });
  await tab.click();
  await page.waitForTimeout(2500);
  return page;
}

/** 读「材质元素」表：按表头文案动态求列下标，🚫 不写死 nth() */
async function readTable(page: Page): Promise<{ headers: string[]; rows: Row[]; subtotal: Row | null; totalRow: Row | null }> {
  // 选中含「元素代码」表头的那张表（材质元素页签的表）
  const table = page.locator('table').filter({ hasText: '元素代码' }).first();
  await table.waitFor({ timeout: 30_000 });
  const headers = (await table.locator('thead th').allInnerTexts()).map(s => s.trim());
  // ⚠️ 小计行可能落在 tfoot，只扫 tbody 会读不到（实测 subtotal=null）
  const trs = table.locator('tbody tr, tfoot tr');
  const n = await trs.count();
  const rows: Row[] = [];
  let subtotal: Row | null = null;
  let totalRow: Row | null = null;
  for (let i = 0; i < n; i++) {
    const cells = (await trs.nth(i).locator('td').allInnerTexts()).map(s => s.trim());
    if (!cells.length) continue;
    const o: Row = {};
    headers.forEach((h, j) => { if (h) o[h] = cells[j] ?? ''; });
    // ⚠️ 核价侧表尾同时有「小计」和「合计」两行（实测）。只排除「小计」会把「合计」
    //    当成第 5 条数据行 ⇒ rows.length===4 必红，而红的原因是 harness 不是产品。
    const isSubtotal = cells.some(c => c.includes('小计'));
    const isTotal    = cells.some(c => c.includes('合计'));
    if (isSubtotal) subtotal = o;
    else if (isTotal) totalRow = o;
    else rows.push(o);
  }
  return { headers, rows, subtotal, totalRow };
}

const key = (r: Row) => `${r['生产料号']}#${r['元素代码']}`;
const snap = (rows: Row[]) =>
  rows.map(r => ({ key: key(r), unitPrice: r['元素单价'], matCost: r['材料费用'] }))
      .sort((a, b) => a.key.localeCompare(b.key));

test.describe('repair-260910 S-2 UI', () => {
  test('T2.2+T2.8④ · AC-1/AC-7/AC-11④：四行有值、材料费用>0、小计>0、表头有材料费用列', async ({ page }) => {
    test.setTimeout(240_000);
    await login(page);
    await gotoCostingTab(page, '材质元素');
    const { headers, rows, subtotal, totalRow } = await readTable(page);

    console.log('[T2.2] 表头 =', JSON.stringify(headers));
    console.log('[T2.2] 数据行 =', JSON.stringify(rows, null, 1));
    console.log('[T2.2] 小计行 =', JSON.stringify(subtotal));
    console.log('[T2.2] 合计行 =', JSON.stringify(totalRow));

    // AC-11 ④-1：表头必须先有「材料费用」列（列没了会让下面的 >0 断言红得像值不对）
    expect(headers, 'AC-11④ 表头应含「材料费用」').toContain('材料费用');
    expect(headers, 'AC-1 表头应含「元素单价」').toContain('元素单价');

    // 🔒 非空保护 + AC-7 UI 计数
    expect(rows.length, 'AC-7 数据行应为 4').toBe(4);
    expect(new Set(rows.map(r => r['生产料号'])), 'AC-7 生产料号集合').toEqual(new Set(['300013', '300015']));

    // AC-1 断言 1：元素单价非空且 >0
    for (const r of rows) {
      expect(BAD, `AC-1 ${key(r)} 元素单价不应为空/—/0/加载中`).not.toContain(r['元素单价']);
      expect(num(r['元素单价']), `AC-1 ${key(r)} 元素单价>0`).toBeGreaterThan(0);
    }
    // AC-1 断言 3 + AC-11④-2：材料费用四行 >0
    for (const r of rows) {
      expect(num(r['材料费用']), `AC-1 ${key(r)} 材料费用>0`).toBeGreaterThan(0);
    }
    // AC-1 断言 4 + AC-11④-3：小计 >0
    expect(subtotal, 'AC-1 应有小计行').not.toBeNull();
    expect(num(subtotal!['材料费用']), 'AC-1 小计>0').toBeGreaterThan(0);

    fs.mkdirSync(EV, { recursive: true });
    await page.screenshot({ path: `${EV}/S2-AC1-核价侧材质元素四行有值.png`, fullPage: false });
  });

  test('T2.3 · AC-3 序列：打开→切走BOM→切回→整页刷新，四次读数一致且非空', async ({ page }) => {
    // 实测根因：本机并存 8 个 Quarkus JVM + 多个 Vite（内存 19.7G/24G），
    // Playwright 启动 + Chrome 冷启单独就要 ~130s（00:17:41 起 → chrome 00:19:50）。
    // T2.3 要做 3 次重量级向导页加载，300s 预算会被启动开销挤爆 ⇒ 间歇性超时。
    // 非产品问题，也不能靠"重跑"碰运气：把预算调到覆盖启动开销才是结构性修复。
    test.setTimeout(900_000);
    await login(page);

    await gotoCostingTab(page, '材质元素');
    const s1 = snap((await readTable(page)).rows);
    console.log('[T2.3] R1 =', JSON.stringify(s1));
    expect(s1.length, 'AC-3 R1 非空保护：应 4 行').toBe(4);

    const s2 = snap((await readTable(page)).rows);
    console.log('[T2.3] R2 =', JSON.stringify(s2));

    // 切走到 BOM 再切回
    await page.getByText('BOM', { exact: true }).first().click();
    await page.waitForTimeout(2500);
    await page.getByText('材质元素', { exact: true }).first().click();
    await page.waitForTimeout(2500);
    const s3 = snap((await readTable(page)).rows);
    console.log('[T2.3] R3(切走再切回) =', JSON.stringify(s3));
    await page.screenshot({ path: `${EV}/S2-AC3-切回后.png` });

    // 整页刷新
    await page.reload();
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.waitForTimeout(3000);
    await gotoCostingTab(page, '材质元素');
    const s4 = snap((await readTable(page)).rows);
    console.log('[T2.3] R4(整页刷新) =', JSON.stringify(s4));
    await page.screenshot({ path: `${EV}/S2-AC3-刷新后.png` });

    for (const [name, s] of [['R1', s1], ['R2', s2], ['R3', s3], ['R4', s4]] as const) {
      expect(s.length, `AC-3 ${name} 应 4 行`).toBe(4);
      for (const c of s) {
        expect(BAD, `AC-3 ${name} ${c.key} 元素单价非空`).not.toContain(c.unitPrice);
      }
    }
    expect(s2, 'AC-3 R1==R2').toEqual(s1);
    expect(s3, 'AC-3 R1==R3(切走切回)').toEqual(s1);
    expect(s4, 'AC-3 R1==R4(整页刷新)').toEqual(s1);
  });
});
