/**
 * E2E: 报价单 Excel 视图 buildEvalKey 4 段协议修复验证
 *
 * 背景:
 *   V249/V250 引入 template_sql_view 后,后端 batch-evaluate 的 r.key 升级为 4 段
 *   (expr:cust:partNo:templateId),但前端 buildEvalKey 仍是 3 段。
 *   导致 LinkedExcelView 拿 3 段 reqKey 反查 4 段 itemByKey 永远 miss,
 *   pathCache 全部进 null 分支 → V111 noCostingData=true → 整行 13 列全 `—`。
 *
 *   修复点:
 *   - formulaService.ts:buildEvalKey 加 templateId 第 4 参数(向后兼容,默认 "_")
 *   - LinkedExcelView.tsx:247 调用时透传 templateId
 *
 * 验收目标:
 *   1) 打开使用模板 v1.30 (id=27fab96b...) 的报价单 (QT-20260527-1649)
 *   2) 切到「报价单 → Excel 视图」
 *   3) 报价单 lineItems 含 partNo 3120012574 和 3120012575
 *   4) 表格行不应全部是 `—`
 *      - [A]料号 (path=$part_basic.hf_part_no) 应显示 partNo 字符串本身
 *      - [D]材质 (path=$part_basic.recipe_name) 应显示具体名称(非 `—`)
 *      - [F]单重(g) (path=$part_basic.unit_weight) 应显示数字
 *      - [I]材料成本 + [J]加工费 + [K]总成本 应显示具体数字
 *   5) 全表 `—` 计数 << 列数 × 行数(允许部分字段确实 NULL,但绝不是全部)
 *   6) 控制台无 "buildEvalKey" 相关的 ERROR
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `qek-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

const TARGET_QUOTATION_ID = 'ae33ae97-01a7-4aee-ae85-0ccd77f10a04'; // QT-20260527-1649
const EXPECTED_PART_NOS = ['3120012574', '3120012575'];

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('报价单 Excel 视图: v1.30 模板 + partNo 3120012574/5 应正确渲染,非全 `—`', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  // 控制台错误监控
  const consoleErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') {
      consoleErrors.push(m.text());
    }
  });

  // 监听 batch-evaluate 请求/响应以便诊断
  let batchEvalRespCount = 0;
  let batchEvalLastBody: any = null;
  page.on('response', async (resp) => {
    if (resp.url().includes('/formulas/batch-evaluate')) {
      batchEvalRespCount++;
      try {
        batchEvalLastBody = await resp.json();
      } catch { /* ignore */ }
    }
  });

  // ── 1. 登录 ────────────────────────────────────────────────────
  await loginAsAdmin(page);
  console.log('✅ 登录成功');

  // ── 2. 直接打开报价单编辑页 ─────────────────────────────────────
  await page.goto(`/quotations/${TARGET_QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500); // wait for autoload
  await shot(page, 'open-quotation');

  // ── 3. 进入 Step 2(产品配置) ────────────────────────────────────
  // 报价单 wizard 默认在 Step 1, 找"下一步" 按钮 / 直接跳 step
  // 也可能已经在 Step 2 看 lineItems。
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForTimeout(1500);
  }
  // 等待 Step 2 的标志元素出现(mainTab Segmented)
  const segMain = page.locator('.ant-segmented').first();
  await expect(segMain).toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(800);
  await shot(page, 'step2-loaded');

  // ── 4. 确保 mainTab=报价单(默认) + 切到 Excel 视图 ─────────────
  // 找到 "📑 Excel 视图" segment
  const excelTab = page.locator('.ant-segmented-item', { hasText: 'Excel 视图' }).first();
  await expect(excelTab).toBeVisible({ timeout: 10_000 });
  await excelTab.click();
  console.log('✅ 已切到 Excel 视图');
  await page.waitForTimeout(2500); // 等 batch-evaluate 完成
  await shot(page, 'excel-view-rendered');

  // ── 5. 收集表格单元值 ─────────────────────────────────────────
  // 表格通常是 antd Table; 我们直接读 .ant-table-tbody tr td 文本
  const tableRows = page.locator('.ant-table-tbody tr.ant-table-row');
  const rowCount = await tableRows.count();
  console.log(`📋 表格行数 = ${rowCount}`);
  expect(rowCount).toBeGreaterThanOrEqual(2); // 至少 2 个 partNo

  // 收集所有单元文本
  type Cell = { row: number; col: number; text: string };
  const cells: Cell[] = [];
  for (let r = 0; r < rowCount; r++) {
    const tds = tableRows.nth(r).locator('td');
    const colCount = await tds.count();
    for (let c = 0; c < colCount; c++) {
      const text = (await tds.nth(c).innerText().catch(() => '')).trim();
      cells.push({ row: r, col: c, text });
    }
  }
  const dashCells = cells.filter(c => c.text === '—' || c.text === '-' || c.text === '');
  const dataCells = cells.filter(c => c.text !== '—' && c.text !== '-' && c.text !== '' && c.text !== '加载中');
  console.log(`📊 总单元 = ${cells.length}, '—' 单元 = ${dashCells.length}, 有数据单元 = ${dataCells.length}`);

  // ── 6. 关键断言: '加载中' 计数应为 0 ──────────────────────────
  const loadingCount = await page.locator('text=加载中').count();
  console.log(`⏳ '加载中' 计数 = ${loadingCount}`);
  expect(loadingCount).toBe(0);

  // ── 7. 关键断言: '—' 占比应 << 100% (修复前是 100%) ─────────
  // 单行 13 列 + 客户料号 = 14 列; 2 行 = 28 个; 修复前 28 个全 `—`(除"客户料号"列)
  // 修复后,预期至少 50% 单元有数据(allow 一些字段 NULL 比如 product_type / specification / config_fingerprint)
  const totalDataCols = cells.length;
  const minDataExpected = Math.floor(totalDataCols * 0.4); // 至少 40% 有数据
  expect(dataCells.length).toBeGreaterThanOrEqual(minDataExpected);

  // ── 8. 验证特定 partNo 显示在表格中 ────────────────────────
  for (const partNo of EXPECTED_PART_NOS) {
    const partNoVisible = cells.some(c => c.text.includes(partNo));
    expect(partNoVisible, `partNo ${partNo} 应该出现在表格某个单元中`).toBe(true);
  }
  console.log(`✅ partNos ${EXPECTED_PART_NOS.join(', ')} 均出现在表格中`);

  // ── 9. 验证至少一行的 [D]材质 / [F]单重 / [I]材料成本 列有数据 ─
  // 表头通常是: 客户料号(0) / [A]料号(1) / [B]品名(2) / [C]产品类型(3) / [D]材质(4) /
  //          [E]材质符号(5) / [F]单重(g)(6) / [G]规格(7) / [H]工序数(8) /
  //          [I]材料成本(9) / [J]加工费(10) / [K]总成本(11) / [L]配置指纹(12) / [M]创建时间(13)
  // 但实际表头索引依 antd Table 组件渲染,我们用列名查
  const findColIdxByHeader = async (headerText: string): Promise<number> => {
    const ths = page.locator('.ant-table-thead th');
    const n = await ths.count();
    for (let i = 0; i < n; i++) {
      const t = (await ths.nth(i).innerText().catch(() => '')).trim();
      if (t.includes(headerText)) return i;
    }
    return -1;
  };
  const idxMaterial = await findColIdxByHeader('材质');
  const idxWeight = await findColIdxByHeader('单重');
  const idxProcessCount = await findColIdxByHeader('工序数');
  const idxMatCost = await findColIdxByHeader('材料成本');
  const idxProcCost = await findColIdxByHeader('加工费');
  const idxTotalCost = await findColIdxByHeader('总成本');

  console.log(`列索引: 材质=${idxMaterial}, 单重=${idxWeight}, 工序数=${idxProcessCount}, 材料成本=${idxMatCost}, 加工费=${idxProcCost}, 总成本=${idxTotalCost}`);

  // 对每个关键列断言至少一行有数据
  const assertColHasData = (colIdx: number, colName: string) => {
    if (colIdx < 0) {
      console.log(`⚠️  列 ${colName} 未找到,跳过`);
      return;
    }
    const colCells = cells.filter(c => c.col === colIdx);
    const hasData = colCells.some(c => c.text !== '—' && c.text !== '-' && c.text !== '' && c.text !== '加载中');
    expect(hasData, `列 ${colName}(idx=${colIdx}) 至少一行应有数据,但全是 \`—\``).toBe(true);
    console.log(`✅ ${colName} 至少一行有数据: ${colCells.map(c => c.text).join(' | ')}`);
  };

  assertColHasData(idxMaterial, '[D]材质');
  assertColHasData(idxWeight, '[F]单重(g)');
  assertColHasData(idxProcessCount, '[H]工序数');
  assertColHasData(idxMatCost, '[I]材料成本');
  assertColHasData(idxProcCost, '[J]加工费');
  assertColHasData(idxTotalCost, '[K]总成本');

  // ── 9.1 关键断言: [H]工序数列必须是 int 标量(V260+V261 修复),不是 "(共N项)" 数组形态 ─
  // 修复前: process_info 视图 UNION ALL FROM mat_process 无谓词,返 372 行,前端 formatPathValue
  //         截首 + "(共N项)" → [H] 显示 "1(共372项)"
  // 修复后: COUNT(DISTINCT seq_no) GROUP BY hf_part_no → 每料号单值整数
  if (idxProcessCount >= 0) {
    const procCells = cells.filter(c => c.col === idxProcessCount);
    for (const cell of procCells) {
      // 工序数列文本应该是纯数字(可空),不应含 "共" 或 "项"
      expect(cell.text, `[H]工序数 row=${cell.row} 不应是数组形态 (实际: "${cell.text}")`)
        .not.toMatch(/共.*项/);
      // 至少一行应是纯整数
    }
    const hasIntValue = procCells.some(c => /^\d+$/.test(c.text));
    expect(hasIntValue, `[H]工序数 至少一行应是纯整数 (实际值: ${procCells.map(c => c.text).join(' | ')})`)
      .toBe(true);
    console.log(`✅ [H]工序数 单值聚合正常: ${procCells.map(c => c.text).join(' | ')}`);
  }

  // ── 10. 验证 batch-evaluate 请求被调用且返回成功 ────────────
  console.log(`🌐 batch-evaluate 响应次数 = ${batchEvalRespCount}`);
  expect(batchEvalRespCount).toBeGreaterThan(0);
  if (batchEvalLastBody) {
    const results = batchEvalLastBody?.data?.results || [];
    const okResults = results.filter((r: any) => r.status === 'OK' && r.data?.success);
    const nonNullResults = okResults.filter((r: any) => r.data?.result != null);
    console.log(`🌐 batch results: total=${results.length}, OK=${okResults.length}, non-null=${nonNullResults.length}`);
    expect(nonNullResults.length).toBeGreaterThan(0);

    // ── 11. 关键断言: 后端 r.key 是 4 段格式 ────────────────
    if (results.length > 0) {
      const sampleKey = results[0].key as string;
      const segCount = sampleKey.split(':').length;
      console.log(`🔑 后端 r.key 段数 = ${segCount} (sample: ${sampleKey.substring(0, 100)})`);
      expect(segCount, '后端 r.key 应是 4 段 expr:cust:partNo:templateId').toBe(4);
    }
  }

  // ── 12. 控制台错误 ────────────────────────────────────────
  const evalErrors = consoleErrors.filter(e =>
    e.includes('buildEvalKey') ||
    e.includes('batchEvaluate') ||
    e.includes('formulaService'));
  console.log(`❌ 控制台 evalErrors 计数 = ${evalErrors.length}`);
  expect(evalErrors.length).toBe(0);

  console.log('\n✅ 全部断言通过');
});
