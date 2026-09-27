/**
 * E2E · task-260825 报价单大单量前端分页 —— 料号模糊查询
 *
 * 覆盖 AC-9 / AC-10 / AC-11 / AC-12 / AC-13（test.md T-09 ~ T-13）。
 *
 * 🔁 task-260923（D-7 / AC-13 无副作用回归）同步改动：
 *   1. 搜索触发方式改为「输入后按回车」（submitSearch）。在 master（停止输入即搜）上按回车不影响结果，
 *      在 task-260923 分支上只有回车才搜 —— 同一份 spec 两边都应能过。
 *      「清空查询」仍是把框清空、**不按回车**（task-260923 D-2：去首尾空格后为空立即恢复全部）。
 *   2. 旧样本单 QT-20260825-0180 已不在开发库，改指向现存 1845 个产品草稿 QT-20260908-0628
 *      （SEARCH_SAMPLE_*，beforeAll 按单号反查 id 并断言一致）；第 1200 位料号等一律只读 SQL 现查。
 *   3. 搜索框定位不再依赖提示文字（旧 placeholder 含「客户产品编号」，新提示为「销售/客户/生产料号，回车搜索」），
 *      改为「分页栏 .qt-pgbar 内的 antd 输入框」。
 *   4. 计数（「共 N 条」/「匹配 N 条 / 共 M 条」）不在 .ant-pagination 内，而在分页栏 .qt-pgbar 上 ——
 *      读计数改为读 .qt-pgbar（原先读 .ant-pagination 时 T-09 的断言恒真、T-13 的断言恒假）。
 *   5. 🚨 本单只读：guardReadOnlyApi 短路一切非 GET /api 请求（打开编辑页会自发 ensure-card-values /
 *      batch-evaluate / PUT draft），afterAll 断言该单写入指纹前后一致。
 *   术语（task-260923 ①）：本文件的 productPartNo 是**销售料号**（旧注释称「生产料号」系口误）。
 *
 * 🚨 test.md 陷阱 #1：T-09 必须用 sortOrder 第 1200 位的料号（深位），
 * 用第 1~100 位测即使实现只在当前页里找也会通过 —— 假绿。
 * 本文件的第 1200 位料号来自只读 SQL 现查，不硬编码猜测值。
 *
 * 🔁 task-260923 开工后变更（用户裁决）：
 *   D-2 ① AC-13 不含「客户产品编号可搜」→ 删除 T-10b（由 S-1 的 AC-4 覆盖）；
 *   D-2 ② T-11 判据改为 pageerror 数 = 0，console.error 仅记录；
 *   D-5 AC-13 改在详情页只读验证，移除无法执行的 Excel 子项。
 */
import { test, expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp } from './fixtures/auth';
import {
  SEARCH_SAMPLE_QUOTATION_ID,
  SEARCH_SAMPLE_QUOTATION_NO,
  SEARCH_SAMPLE_PART_NO_REGEX,
  SEARCH_SAMPLE_PART_NO_SQL_PATTERN,
  querySampleQuotationIdByNo,
  queryPartNoPatternCount,
  queryQuotationWriteFingerprint,
  guardReadOnlyApi,
  loginAdmin,
  countRenderedCards,
  extractVisiblePartNoSet,
  queryOrderedLineItems,
  queryLineItemCount,
  queryQuotationCustomerCode,
} from './fixtures/task260825-paging';

const QID = SEARCH_SAMPLE_QUOTATION_ID;
const TOTAL = 1845;

// 大单打开约 15s（登录 + 下一步 + 首屏渲染），默认 30s 用例超时偏紧；只放宽等待余量，不改判据。
// ⚠️ run-01-A 实证：文件顶层 test.setTimeout() 不生效（仍报 30000ms 超时），改用 describe.configure。
test.describe.configure({ timeout: 360_000 }); // 含详情页预热（≤120s）+ 编辑页 Step1 轮询（≤120s）

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
// 证据目录可由环境变量改写（testing.md §5.7⑤：复跑不得覆盖上一轮证据）
const SHOT_DIR = process.env.T260923_S2_EVIDENCE_DIR || path.join(__dirnameLocal, 'screenshots', 'task260825');
fs.mkdirSync(SHOT_DIR, { recursive: true });
let shotIdx = 0;
async function shot(page: Page, name: string) {
  // 文件名带用例标题：worker 重启会把 shotIdx 清零，只用序号会互相覆盖（run-02-A 实证只剩 1 张）
  const tag = test.info().title.split(' ')[0].replace(/[^\w-]/g, '_');
  const file = path.join(SHOT_DIR, `search-${tag}-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: false }).catch(() => {});
  return file;
}

/** 报价单编辑页料号查询输入框：分页栏（.qt-pgbar）里的 antd 输入框；不依赖提示文字。上下两条分页栏取第一条。 */
function searchInput(page: Page) {
  return page.locator('.qt-pgbar input.ant-input').first();
}

/** 分页栏计数文字（第一条分页栏的全部文字，含「共 N 条」/「匹配 N 条 / 共 M 条」）。 */
async function pgbarText(page: Page): Promise<string> {
  return (await page.locator('.qt-pgbar').first().innerText().catch(() => '')).replace(/\n/g, ' ');
}

/** 输入后按回车（task-260923：回车才触发搜索）。 */
async function submitSearch(page: Page, text: string) {
  const input = searchInput(page);
  await expect(input, '查询输入框应可见').toBeVisible({ timeout: 10000 });
  await input.fill(text);
  await input.press('Enter');
  await page.waitForTimeout(600);
}

/** 登录 + 装只读守卫 + 打开稳定的详情页（AC-13 现行口径）。 */
async function openSampleReadOnly(page: Page) {
  const guard = await guardReadOnlyApi(page);
  await loginAdmin(page);
  await page.goto(`/quotations/${QID}`);
  await Promise.all([
    page.locator('[data-testid="task260825-paging-bar"]').first().waitFor({ state: 'visible', timeout: 120_000 }),
    page.locator('.qt-product-card').first().waitFor({ state: 'visible', timeout: 120_000 }),
  ]);
  await page.waitForTimeout(1200);
  return guard;
}

let backendUp = false;
let expectedOrder: ReturnType<typeof queryOrderedLineItems> = [];
let deepPartNo = '';
let customerCode = '';
let fingerprintBefore = '';

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (backendUp) {
    expect(querySampleQuotationIdByNo(SEARCH_SAMPLE_QUOTATION_NO), `样本单 ${SEARCH_SAMPLE_QUOTATION_NO} 应仍在库且 id 未变`).toBe(QID);
    expect(queryLineItemCount(QID), `样本单应仍为 ${TOTAL} 行`).toBe(TOTAL);
    expect(queryPartNoPatternCount(QID, SEARCH_SAMPLE_PART_NO_SQL_PATTERN), '料号抓取正则应覆盖全部行（否则集合比对会漏抓）').toBe(TOTAL);
    expectedOrder = queryOrderedLineItems(QID);
    expect(expectedOrder.length, '只读 SQL 取数应非空').toBe(TOTAL);
    // 第 1200 位（1-indexed）= 下标 1199
    deepPartNo = expectedOrder[1199].productPartNo;
    customerCode = queryQuotationCustomerCode(QID);
    console.log(`[fixtures] 样本=${SEARCH_SAMPLE_QUOTATION_NO} 第1200位销售料号=${deepPartNo}, 客户=${customerCode}`);
    expect(deepPartNo, '第 1200 位料号不应为空').toBeTruthy();
    fingerprintBefore = queryQuotationWriteFingerprint(QID);
    expect(fingerprintBefore, '写入指纹应可取到').toBeTruthy();
    console.log(`[fixtures] 写入指纹(前)=${fingerprintBefore}`);
  }
});

test.afterAll(async () => {
  if (!backendUp) return;
  const after = queryQuotationWriteFingerprint(QID);
  console.log(`[fixtures] 写入指纹(后)=${after}`);
  expect(after, `只读纪律：${SEARCH_SAMPLE_QUOTATION_NO} 不得被本 spec 改写`).toBe(fingerprintBefore);
});

test.describe('AC-9: 查询命中深位料号（第 1200 位）', () => {
  test('T-09 搜索第 1200 位料号必须命中，命中数==1，且该卡片渲染在第 1 页', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    const guard = await openSampleReadOnly(page);

    await submitSearch(page, deepPartNo);

    const countText = await pgbarText(page);
    console.log(`[T-09] 分页栏文案 = "${countText}"`);
    await shot(page, 'deep-hit');

    const cardCount = await countRenderedCards(page);
    console.log(`[T-09] 命中卡片数 = ${cardCount}`);
    expect(cardCount, 'AC-9: 搜索深位料号必须命中且只命中 1 条').toBe(1);

    const cardText = await page.locator('.qt-product-card').first().innerText();
    expect(cardText, `AC-9: 命中卡片应显示搜索的料号 ${deepPartNo}`).toContain(deepPartNo);

    // 计数显示为命中数而非 1845
    expect(countText, 'AC-9: 计数应反映命中数（匹配 1 条，不是全量 1845 的原样展示）').toContain('匹配 1 条');
    console.log(`[T-09] 只读守卫短路的写请求 = ${JSON.stringify(guard.intercepted)}`);
  });
});

test.describe('AC-10: 匹配字段 productPartNo（销售料号）', () => {
  test('T-10a 用销售料号（productPartNo）搜索命中', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await openSampleReadOnly(page);
    await submitSearch(page, deepPartNo);
    const cardCount = await countRenderedCards(page);
    expect(cardCount, `AC-10: productPartNo=${deepPartNo} 应命中`).toBeGreaterThan(0);
  });

  // T-10b（客户产品编号命中）已删除 —— task-260923 D-2 ①：AC-13 不再含此项，
  // 本样本单 0/1845 行有客户产品编号；客户产品编号可搜由 S-1 的 AC-4 在自造样本单 A 上覆盖。
});

test.describe('AC-11: 查询空态', () => {
  test('T-11 查询命中 0 行：空态文案逐字一致，不报错不白屏，不保留上一页内容', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    // task-260923 D-2 ②：判据 = 页面脚本报错（pageerror）数 = 0；React/antd 开发期 console.error 警告不计（仅记录）
    const pageErrors: string[] = [];
    const consoleErrors: string[] = [];
    page.on('pageerror', (e) => { pageErrors.push(`${e.message}\n${e.stack || ''}`); });
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

    await openSampleReadOnly(page);

    await submitSearch(page, 'XYZ999');
    await shot(page, 'empty-state');

    const cardCount = await countRenderedCards(page);
    console.log(`[T-11] 空态下卡片数 = ${cardCount}`);
    expect(cardCount, 'AC-11: 查询命中 0 时不应保留上一页任何卡片').toBe(0);

    const bodyText = await page.locator('body').innerText();
    expect(bodyText, 'AC-11: 空态标题应逐字一致').toContain('未找到匹配的料号');
    expect(bodyText, 'AC-11: 空态副文案应逐字一致（含具体查询词与总数 1845）').toContain('「XYZ999」在本报价单的 1845 个料号中无匹配。请换一个料号片段，或清空查询查看全部。');
    expect(bodyText, 'AC-11: 应有"清空查询"按钮文案').toContain('清空查询');

    console.log(`[T-11] console.error（仅记录，不计入判据）${consoleErrors.length} 条 = ${JSON.stringify(consoleErrors)}`);
    console.log(`[T-11] pageerror ${pageErrors.length} 条 = ${JSON.stringify(pageErrors)}`);
    expect(pageErrors, 'AC-13（D-2 ②）: 页面脚本报错（pageerror）数应为 0').toEqual([]);
  });
});

test.describe('AC-12: 查询状态下翻页正确（2026-08-28 裁决：默认页大小 100→10）', () => {
  test('T-12 命中数跨页时，第 2 页是命中集合第 11-20 条（按新默认页大小 10 算）', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    // 用真实数据构造一个命中数可控的查询词：找一个使命中数 >10（跨页，按新默认页大小 10）的前缀
    // 在销售料号上按不同前缀长度统计命中分布，选一个命中数在 [11,1844] 区间的前缀
    let candidatePrefix = '';
    let candidateCount = 0;
    for (let len = 10; len <= 12; len++) {
      const counts = new Map<string, number>();
      for (const r of expectedOrder) {
        const p = r.productPartNo.slice(0, len);
        counts.set(p, (counts.get(p) || 0) + 1);
      }
      for (const [p, c] of counts) {
        if (c > 10 && c < 1845) { candidatePrefix = p; candidateCount = c; break; }
      }
      if (candidatePrefix) break;
    }
    test.skip(!candidatePrefix, '未能从现网数据构造出命中数跨页（>10 且 <1845）的查询前缀，需要主线协助确认是否要专门造数');
    console.log(`[T-12] 选用前缀="${candidatePrefix}" 期望命中数=${candidateCount}`);

    await openSampleReadOnly(page);
    await submitSearch(page, candidatePrefix);

    console.log(`[T-12] 分页栏="${await pgbarText(page)}"`);

    const matchedInOrder = expectedOrder.filter((r) => r.productPartNo.startsWith(candidatePrefix)).map((r) => r.productPartNo);
    const expectedPage2 = matchedInOrder.slice(10, 20); // 新默认页大小 10：第 2 页 = 第 11~20 条
    expect(expectedPage2.length, 'T-12 期望集合应为 10 条（防空跑）').toBe(10);

    const jumper = page.locator('.ant-pagination-options-quick-jumper input').first();
    if (await jumper.count() > 0) {
      await jumper.fill('2');
      await jumper.press('Enter');
    } else {
      await page.locator('.ant-pagination-item-2').first().click();
    }
    await page.waitForTimeout(800);
    await shot(page, 'query-page2');

    const setPage2 = await extractVisiblePartNoSet(page, '.qt-products-list, body', SEARCH_SAMPLE_PART_NO_REGEX);
    console.log(`[T-12] 查询态第2页 页面抓到的料号=${JSON.stringify([...setPage2])}`);
    const diff = expectedPage2.filter((x) => !setPage2.has(x));
    console.log(`[T-12] 查询态第2页 与期望差异(应为空)=${JSON.stringify(diff.slice(0, 10))}`);
    expect(diff, 'AC-12: 查询状态下第 2 页应恰好是命中集合第 11-20 条（新默认页大小 10）').toEqual([]);
  });
});

test.describe('AC-13: 详情页大单搜索与清空回到全量分页', () => {
  test('T-13 清空查询后计数回到 1845', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await openSampleReadOnly(page);
    await submitSearch(page, deepPartNo);
    expect(await countRenderedCards(page), '查询后应先命中').toBe(1);

    // 清空：不按回车（task-260923 D-2：框内去首尾空格后为空即恢复全部）
    const input = searchInput(page);
    await input.fill('');
    await page.waitForTimeout(600);
    await shot(page, 'cleared');
    const countText = await pgbarText(page);
    console.log(`[T-13] 清空后分页栏="${countText}"`);
    expect(countText, 'AC-13: 清空查询后计数应回到「共 1845 条」').toContain('共 1845 条');
    const cardCount = await countRenderedCards(page);
    expect(cardCount, 'AC-13: 清空查询后应恢复分页渲染（<=10，新默认页大小）').toBeLessThanOrEqual(10);
    expect(cardCount, 'AC-13: 清空查询后应有卡片渲染').toBeGreaterThan(0);
  });
});

// Excel 视图搜索由 task-260923 AC-9 在配置了 Excel 视图的自造样本单 A 覆盖。
// 现存 1845 行样本 QT-20260908-0628 的模板 excel_view_config 为空，AC-13 不再重复验证不可执行的 Excel 子项。
