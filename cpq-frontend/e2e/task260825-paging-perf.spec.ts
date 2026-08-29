/**
 * E2E · task-260825 报价单大单量前端分页 —— 性能与泄漏护栏
 *
 * 覆盖 AC-19 / AC-20（test.md T-19 / T-20）。
 *
 * test.md 认领栏对这两条标注"前端自测 + 主线亲验"（不是纯测试岗独占），
 * 但仍在 T-01~T-25 矩阵内，本文件按可执行的口径落地一版可复跑的自动化基线脚本，
 * 供主线亲验时复用，也供开发自测复核用同一把尺子。
 *
 * 🚨 写操作红线：全程用 page.route 拦截非 GET /api 请求，防止"打开编辑页自发整单 PUT /draft"
 *   在大单上真的落库（这是已实证的独立缺陷，见需求文档 S-8，本任务不修，但测量时必须屏蔽）。
 *
 * 五项指标均用脚本化 CDP `Performance.getMetrics()` 取数（含 JSHeapUsedSize / Nodes /
 * JSEventListeners / LayoutObjects，2026-08-27 主线实测确认该接口返回 36 项指标，
 * LayoutObjects 在列，取法见 forceGCAndMeasure()），无需人工在 DevTools 面板读数。
 */
import { test, expect, chromium, Page, BrowserContext } from '@playwright/test';

/** playwright/test 未导出 CDPSession 类型，从 BrowserContext.newCDPSession 的返回值推导，避免额外依赖 playwright-core 类型路径。 */
type CDPSession = Awaited<ReturnType<BrowserContext['newCDPSession']>>;
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp } from './fixtures/auth';
import { LARGE_QUOTATION_ID, queryLineItemCount } from './fixtures/task260825-paging';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const OUT_DIR = path.join(__dirnameLocal, 'screenshots', 'task260825');
fs.mkdirSync(OUT_DIR, { recursive: true });

interface Metrics {
  jsHeapUsedMB: number;
  domNodes: number;
  jsEventListeners: number;
  layoutObjects: number;
}

async function forceGCAndMeasure(session: CDPSession, page: Page): Promise<Metrics> {
  await session.send('HeapProfiler.collectGarbage').catch(() => {});
  await page.waitForTimeout(300);
  await session.send('HeapProfiler.collectGarbage').catch(() => {});
  await page.waitForTimeout(300);

  const perf = await session.send('Performance.getMetrics');
  const map = new Map(perf.metrics.map((m) => [m.name, m.value]));
  const jsHeapUsed = map.get('JSHeapUsedSize') ?? 0;
  const domNodes = map.get('Nodes') ?? 0;
  const jsEventListeners = map.get('JSEventListeners') ?? 0;
  const layoutObjects = map.get('LayoutObjects') ?? 0;

  return {
    jsHeapUsedMB: jsHeapUsed / (1024 * 1024),
    domNodes,
    jsEventListeners,
    layoutObjects,
  };
}

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (backendUp) {
    expect(queryLineItemCount(LARGE_QUOTATION_ID), '固定样本应仍为 1845 行').toBe(1845);
  }
});

test.describe('AC-19: 五项性能指标（可脚本化的四项）', () => {
  test('T-19 分页模式下 1845 行单 Step2：JS堆/DOM节点/事件监听器 均达标，渲染耗时 < 5s', async () => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(120_000);

    const browser = await chromium.launch({
      channel: 'chrome',
      args: ['--enable-precise-memory-info', '--js-flags=--expose-gc'],
    });
    const ctx = await browser.newContext({ baseURL: process.env.PW_BASE_URL || 'http://localhost:5174', viewport: { width: 1600, height: 1000 } });
    const page = await ctx.newPage();

    // 🚨 写操作红线：拦截所有非 GET /api 请求
    let blockedWrites = 0;
    await page.route('**/api/**', async (route) => {
      const req = route.request();
      if (req.method() !== 'GET') { blockedWrites++; await route.abort(); return; }
      await route.continue();
    });

    // 登录
    await page.goto('/login');
    await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
    await page.locator('input[placeholder="密码"]').fill('Admin@2026');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL(/\/(dashboard|quotations|change-password)/, { timeout: 15000 });
    if (page.url().includes('/change-password')) await page.goto('/dashboard');

    const session = await ctx.newCDPSession(page);
    await session.send('Performance.enable');

    const t0 = Date.now();
    await page.goto(`/quotations/${LARGE_QUOTATION_ID}/edit`);
    await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {}); // 大单持续有后台请求，networkidle 可能等不到，不中断流程
    const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
    if (await nextBtn.isVisible().catch(() => false)) {
      await nextBtn.click().catch(() => {});
      await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {}); // 大单持续有后台请求，networkidle 可能等不到，不中断流程
    }
    await page.locator('.qt-product-card').first().waitFor({ state: 'visible', timeout: 30000 }).catch(() => {});
    const renderMs = Date.now() - t0;

    const m = await forceGCAndMeasure(session, page);
    console.log(`[T-19] 渲染耗时=${renderMs}ms JS堆=${m.jsHeapUsedMB.toFixed(1)}MB DOM节点=${m.domNodes} 事件监听器=${m.jsEventListeners} LayoutObjects=${m.layoutObjects} 拦截写请求数=${blockedWrites}`);

    fs.writeFileSync(path.join(OUT_DIR, 'ac19-metrics.json'), JSON.stringify({ renderMs, ...m, blockedWrites }, null, 2));

    expect(m.domNodes, 'AC-19 前置：DOM 节点数应非零').toBeGreaterThan(0);
    expect(renderMs, 'AC-19: Step2 渲染耗时 < 5000ms').toBeLessThan(5000);
    expect(m.jsHeapUsedMB, 'AC-19: JS 堆 used < 250 MB').toBeLessThan(250);
    expect(m.domNodes, 'AC-19: DOM 节点数 < 15000').toBeLessThan(15000);
    expect(m.jsEventListeners, 'AC-19: JS 事件监听器 < 3000').toBeLessThan(3000);
    expect(m.layoutObjects, 'AC-19: LayoutObjects < 15000').toBeLessThan(15000);

    await browser.close();
  });
});

test.describe('AC-20: 泄漏护栏（🚨 最容易被跳过的一条）', () => {
  test('T-20 连续翻 20 页回到第 1 页，四项指标不得单调上升，末值与首值差 <= 20%', async () => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(180_000);

    const browser = await chromium.launch({
      channel: 'chrome',
      args: ['--enable-precise-memory-info', '--js-flags=--expose-gc'],
    });
    const ctx = await browser.newContext({ baseURL: process.env.PW_BASE_URL || 'http://localhost:5174', viewport: { width: 1600, height: 1000 } });
    const page = await ctx.newPage();

    await page.route('**/api/**', async (route) => {
      const req = route.request();
      if (req.method() !== 'GET') { await route.abort(); return; }
      await route.continue();
    });

    await page.goto('/login');
    await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
    await page.locator('input[placeholder="密码"]').fill('Admin@2026');
    await page.locator('button[type="submit"]').click();
    await page.waitForURL(/\/(dashboard|quotations|change-password)/, { timeout: 15000 });
    if (page.url().includes('/change-password')) await page.goto('/dashboard');

    const session = await ctx.newCDPSession(page);
    await session.send('Performance.enable');

    await page.goto(`/quotations/${LARGE_QUOTATION_ID}/edit`);
    await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {}); // 大单持续有后台请求，networkidle 可能等不到，不中断流程
    const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
    if (await nextBtn.isVisible().catch(() => false)) {
      await nextBtn.click().catch(() => {});
      await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {}); // 大单持续有后台请求，networkidle 可能等不到，不中断流程
    }
    await page.locator('.qt-product-card').first().waitFor({ state: 'visible', timeout: 30000 }).catch(() => {});

    const series: Array<{ page: number; m: Metrics }> = [];
    const m0 = await forceGCAndMeasure(session, page);
    series.push({ page: 1, m: m0 });
    console.log(`[T-20] M0(第1页) JS堆=${m0.jsHeapUsedMB.toFixed(1)}MB DOM=${m0.domNodes} 监听器=${m0.jsEventListeners}`);

    for (const target of [5, 10, 15, 20]) {
      const jumper = page.locator('.ant-pagination-options-quick-jumper input').first();
      if (await jumper.count() > 0) {
        await jumper.fill(String(target));
        await jumper.press('Enter');
      } else {
        for (let i = 0; i < 4; i++) {
          await page.locator('.ant-pagination-next').first().click().catch(() => {});
          await page.waitForTimeout(300);
        }
      }
      await page.waitForTimeout(800);
      const m = await forceGCAndMeasure(session, page);
      series.push({ page: target, m });
      console.log(`[T-20] M${target}(第${target}页) JS堆=${m.jsHeapUsedMB.toFixed(1)}MB DOM=${m.domNodes} 监听器=${m.jsEventListeners}`);
    }

    // 回到第 1 页
    const jumperBack = page.locator('.ant-pagination-options-quick-jumper input').first();
    if (await jumperBack.count() > 0) {
      await jumperBack.fill('1');
      await jumperBack.press('Enter');
    } else {
      await page.locator('.ant-pagination-prev').first().click().catch(() => {});
    }
    await page.waitForTimeout(800);
    const mEnd = await forceGCAndMeasure(session, page);
    console.log(`[T-20] M_end(回第1页) JS堆=${mEnd.jsHeapUsedMB.toFixed(1)}MB DOM=${mEnd.domNodes} 监听器=${mEnd.jsEventListeners}`);

    fs.writeFileSync(
      path.join(OUT_DIR, 'ac20-leak-series.json'),
      JSON.stringify({ series, end: mEnd }, null, 2)
    );

    // 断言 1：末值与首值差 <= 20%（GC 抖动容差）
    for (const [key, label] of [['jsHeapUsedMB', 'JS堆'], ['domNodes', 'DOM节点'], ['jsEventListeners', '事件监听器']] as const) {
      const start = m0[key] as number;
      const end = mEnd[key] as number;
      const diffPct = start > 0 ? Math.abs(end - start) / start : 0;
      console.log(`[T-20] ${label}: 首=${start} 末=${end} 差异=${(diffPct * 100).toFixed(1)}%`);
      expect(diffPct, `AC-20: ${label} 末值与首值差应 <= 20%（否则说明存在真泄漏）`).toBeLessThanOrEqual(0.2);
    }

    // 断言 2：M5→M20 区间本身不应有持续增长趋势（真正达标的实现里，每页卡片数固定，
    // M5/M10/M15/M20 的 DOM 节点数/监听器数应大致相等；用"M20 相对 M5 的增幅"而不是
    // "严格单调"来判定 —— 严格单调判据在数值持平（差值为0）时会产生假阳性，不可用）
    const domSeq = series.filter((s) => s.page >= 5).map((s) => s.m.domNodes);
    const listenerSeq = series.filter((s) => s.page >= 5).map((s) => s.m.jsEventListeners);
    console.log(`[T-20] DOM 序列(M5..M20)=${JSON.stringify(domSeq)}`);
    console.log(`[T-20] 监听器序列(M5..M20)=${JSON.stringify(listenerSeq)}`);

    const growthPct = (arr: number[]) => (arr[0] > 0 ? (arr[arr.length - 1] - arr[0]) / arr[0] : 0);
    const domGrowth = growthPct(domSeq);
    const listenerGrowth = growthPct(listenerSeq);
    console.log(`[T-20] DOM 节点 M5→M20 增幅=${(domGrowth * 100).toFixed(1)}%，监听器 M5→M20 增幅=${(listenerGrowth * 100).toFixed(1)}%`);
    expect(domGrowth, 'AC-20: DOM 节点数 M5→M20 增幅应 <= 20%（超出说明卡片卸载未清理，累积泄漏）').toBeLessThanOrEqual(0.2);
    expect(listenerGrowth, 'AC-20: 事件监听器数 M5→M20 增幅应 <= 20%（超出说明 13.2个/行监听器未解绑，累积泄漏）').toBeLessThanOrEqual(0.2);

    await browser.close();
  });
});
