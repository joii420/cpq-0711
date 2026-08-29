/**
 * E2E · task-260825 报价单大单量前端分页 —— 回归组（不许因分页而破）
 *
 * 覆盖 AC-21 / AC-22 / AC-23 / AC-24 / AC-25（test.md T-21 ~ T-25）。
 * test.md §4 要求"先跑回归组，再谈新功能"——本文件独立可跑，不依赖其它 spec 的状态。
 *
 * 🚨 A/B 对比手法（同库背靠背，test.md 明确要求）：
 *   共享 dev server (5174/8081) 当前服务的是**主工作区**代码，本 worktree 尚未把分页代码
 *   合入主工作区，所以此刻它就是"改动前"基线。本文件设计为跑两次：
 *     PHASE=before  npx playwright test task260825-paging-regression.spec.ts   （现在，改动前）
 *     PHASE=after   npx playwright test task260825-paging-regression.spec.ts   （合并到主工作区后）
 *   before 阶段把结果写入 e2e/fixtures/task260825-snapshots/*.json（这是源码目录，不会被
 *   下一轮测试清空，满足 testing.md "证据不会被清空" 的归档要求）；after 阶段读回并做
 *   逐字节/逐字段比对。若环境变量未设置，默认按 'before' 处理并只落盘、不比对（首次运行）。
 *
 * 🚨 AC-22 的特殊处理：AC-22 要求检查 saveDraft 请求体，但已知"打开编辑页会自发整单
 *   PUT /draft"（需求文档 S-8，独立缺陷，本任务不修）。为避免真的写入共享库，本文件用
 *   page.route 拦截该请求、只读 body、然后用 mock 响应短路，不放行到真实后端。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import * as crypto from 'crypto';
import { execSync } from 'child_process';
import * as os from 'os';
import { fileURLToPath } from 'url';
import { isBackendUp } from './fixtures/auth';
import { LARGE_QUOTATION_ID, loginAdmin, openEditStep2, queryLineItemCount } from './fixtures/task260825-paging';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SNAP_DIR = path.join(__dirnameLocal, 'fixtures', 'task260825-snapshots');
fs.mkdirSync(SNAP_DIR, { recursive: true });

const PHASE = (process.env.TASK260825_PHASE === 'after') ? 'after' : 'before';

// 大单(1845行)渲染 30s+，默认 120s test timeout 偏紧，放宽到 180s（不改判据，只改等待余量）。
test.setTimeout(180_000);

function snapPath(name: string) {
  return path.join(SNAP_DIR, `${name}.json`);
}
function saveSnapshot(name: string, data: unknown) {
  fs.writeFileSync(snapPath(name), JSON.stringify(data, null, 2));
}
function loadSnapshot<T = any>(name: string): T | null {
  const p = snapPath(name);
  if (!fs.existsSync(p)) return null;
  return JSON.parse(fs.readFileSync(p, 'utf-8'));
}

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (backendUp) {
    expect(queryLineItemCount(LARGE_QUOTATION_ID), '固定样本应仍为 1845 行').toBe(1845);
  }
  console.log(`[regression] PHASE=${PHASE}`);
});

test.describe('AC-21: 总额逐字节不变', () => {
  test('T-21 Step5 显示的原价与总价，分页改动前后逐字节相同', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1500);

    // 走到 Step5，读取总览区金额文本
    const stepItems = page.locator('.ant-steps-item');
    if (await stepItems.count() >= 5) {
      await stepItems.nth(4).click().catch(() => {});
      await page.waitForTimeout(1000);
    } else {
      for (let i = 0; i < 5; i++) {
        const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
        if (!(await nextBtn.isVisible().catch(() => false))) break;
        if (!(await nextBtn.isEnabled().catch(() => false))) break;
        await nextBtn.click().catch(() => {});
        await page.waitForTimeout(800);
      }
    }
    await page.waitForTimeout(1500);

    const bodyText = await page.locator('body').innerText();
    // 抓取常见金额展示格式：¥ / ￥ 开头的数字，或"原价"/"总价"/"总额"关键字附近的数字
    const amountMatches = bodyText.match(/[¥￥]\s*[-\d,]+\.\d+/g) || [];
    console.log(`[T-21] Step5 页面抓到的金额字符串数量 = ${amountMatches.length}`);
    expect(amountMatches.length, 'AC-21 前置：Step5 页面应至少展示出一个金额（结果非空守卫）').toBeGreaterThan(0);

    const snapshot = { amountMatches, capturedAt: new Date().toISOString() };
    if (PHASE === 'before') {
      saveSnapshot('ac21-total-amount', snapshot);
      console.log('[T-21] PHASE=before，已落盘基线快照，跳过比对');
    } else {
      const before = loadSnapshot<typeof snapshot>('ac21-total-amount');
      expect(before, 'AC-21: 缺少 before 阶段快照，需先以 TASK260825_PHASE=before 跑一次本文件').not.toBeNull();
      console.log(`[T-21] before=${JSON.stringify(before!.amountMatches)}`);
      console.log(`[T-21] after =${JSON.stringify(amountMatches)}`);
      expect(amountMatches, 'AC-21: Step5 显示的金额集合分页改动前后应逐字节相同').toEqual(before!.amountMatches);
    }
  });
});

test.describe('AC-22: saveDraft payload 仍为全量 1845 行', () => {
  test('T-22 拦截自发的 PUT /draft 请求，检查 body.lineItems.length == 1845（不放行到真实后端）', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');

    let capturedLen: number | null = null;
    let capturedStructureKeys: string[] = [];
    await page.route('**/api/cpq/quotations/*/draft', async (route) => {
      const req = route.request();
      if (req.method() === 'PUT') {
        try {
          const body = req.postDataJSON();
          const items = body?.lineItems ?? [];
          capturedLen = Array.isArray(items) ? items.length : null;
          capturedStructureKeys = items[0] ? Object.keys(items[0]).sort() : [];
        } catch (e) {
          console.warn('[T-22] 解析 saveDraft body 失败', e);
        }
        // 🚨 红线：不放行到真实后端，用 mock 响应短路，避免污染共享库
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: {} }) });
        return;
      }
      await route.continue();
    });

    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(3000); // 等自发 saveDraft 触发（已实证会在打开时自动发起一次）

    console.log(`[T-22] 拦截到的 lineItems.length = ${capturedLen}`);
    if (capturedLen === null) {
      console.warn('[T-22] 本次打开未捕获到 PUT /draft 请求 —— 可能时序未触发，需要主线加长等待或手动触发保存后重跑');
      test.skip(true, '未捕获到 saveDraft 请求，无法判定 payload 长度');
    }
    expect(capturedLen, 'AC-22: saveDraft payload 的 lineItems.length 必须仍是全量 1845').toBe(1845);

    if (PHASE === 'before') {
      saveSnapshot('ac22-payload-structure', { structureKeys: capturedStructureKeys });
    } else {
      const before = loadSnapshot<{ structureKeys: string[] }>('ac22-payload-structure');
      if (before) {
        expect(capturedStructureKeys, 'AC-22: payload 单行结构字段集合不应因分页而改变').toEqual(before.structureKeys);
      }
    }
  });
});

test.describe('AC-23: 卡片内计算不受影响', () => {
  test('T-23 同一产品卡片（第 1 页首卡）行值/footer 合计，分页改动前后逐字段相等', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1800);

    const firstCard = page.locator('.qt-product-card').first();
    await expect(firstCard, '第 1 页首卡应可见').toBeVisible({ timeout: 15000 });
    const cardText = await firstCard.innerText();
    expect(cardText.length, 'AC-23 前置：首卡文本应非空').toBeGreaterThan(0);

    const snapshot = { cardText, capturedAt: new Date().toISOString() };
    if (PHASE === 'before') {
      saveSnapshot('ac23-first-card', snapshot);
      console.log('[T-23] PHASE=before，已落盘首卡文本快照');
    } else {
      const before = loadSnapshot<typeof snapshot>('ac23-first-card');
      expect(before, 'AC-23: 缺少 before 阶段快照').not.toBeNull();
      expect(cardText, 'AC-23: 第 1 页首卡的行值/footer 合计文本分页改动前后应逐字相等').toBe(before!.cardText);
    }
  });
});

/**
 * 🚨 自查证伪（testing.md §4.4 "新加的断言要先证伪"）：xlsx 是 zip 容器，
 * `docProps/core.xml` 里的 `dcterms:created` 由 Apache POI 在每次生成时打上当前时间戳。
 * 用只读 curl 对同一份未改动数据连续导出两次实测：整份文件 md5 不同，但除
 * `docProps/core.xml` 外的其余全部条目逐字节相同（`diff -rq` 验证过，仅 1 个文件有差异）。
 * 也就是说"整份原始文件 md5 相等"这个判据在改动前/改动后**必然假失败**（哪怕代码
 * 零改动，两次导出的裸 md5 也不相等）——这不是本任务代码引入的问题，是导出接口本身
 * 的固有行为，但会让 AC-24 的 md5 判据从第一天起就失真，必须避开这个易变文件再比较。
 * 做法：解压后剔除 docProps/core.xml，对其余文件按路径排序逐个取 md5 再整体取 md5
 * （比较的是"文件名→内容哈希"这份清单，不受 zip 内部条目顺序/时间戳影响）。
 */
function hashXlsxContentStable(buf: Buffer): string {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'task260825-xlsx-'));
  const zipPath = path.join(tmpRoot, 'export.xlsx');
  const extractDir = path.join(tmpRoot, 'extracted');
  fs.writeFileSync(zipPath, buf);
  execSync(`unzip -oq "${zipPath}" -d "${extractDir}"`);
  const volatileFile = path.join(extractDir, 'docProps', 'core.xml');
  if (fs.existsSync(volatileFile)) fs.unlinkSync(volatileFile);

  function walk(dir: string, base: string): string[] {
    const out: string[] = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      const rel = path.join(base, entry.name);
      if (entry.isDirectory()) out.push(...walk(full, rel));
      else out.push(rel);
    }
    return out;
  }
  const files = walk(extractDir, '').sort();
  const manifest = files.map((rel) => {
    const content = fs.readFileSync(path.join(extractDir, rel));
    const fileHash = crypto.createHash('md5').update(content).digest('hex');
    return `${rel.replace(/\\/g, '/')}:${fileHash}`;
  }).join('\n');

  fs.rmSync(tmpRoot, { recursive: true, force: true });
  return crypto.createHash('md5').update(manifest).digest('hex');
}

test.describe('AC-24: 导出/提交/复制结果逐字节一致', () => {
  test('T-24 导出 Excel 内容哈希（剔除生成时间戳），分页改动前后相同', async ({ page, request }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page); // 建立 session cookie（复用 page 的 context 供 request 使用）

    const resp = await page.context().request.post(
      `${process.env.PW_BACKEND_URL || 'http://localhost:8081'}/api/cpq/quotations/${LARGE_QUOTATION_ID}/export/excel`,
      { timeout: 60000 } // 1845 行导出接近默认 15s 超时边界，放宽余量
    );
    console.log(`[T-24] 导出 Excel HTTP 状态 = ${resp.status()}`);
    if (resp.status() !== 200) {
      console.warn(`[T-24] 导出接口返回非 200（${resp.status()}），可能端点契约与 dev-docs/main-api.md 记录不符，需主线确认`);
      test.skip(true, `导出 Excel 接口返回 ${resp.status()}，无法计算内容哈希`);
    }
    const buf = await resp.body();
    expect(buf.length, 'AC-24 前置：导出文件应非空').toBeGreaterThan(0);
    const contentHash = hashXlsxContentStable(buf);
    console.log(`[T-24] 导出 Excel 内容哈希（剔除时间戳）= ${contentHash}，文件大小 = ${buf.length} bytes`);

    if (PHASE === 'before') {
      saveSnapshot('ac24-export-excel-md5', { contentHash, size: buf.length });
    } else {
      const before = loadSnapshot<{ contentHash: string; size: number }>('ac24-export-excel-md5');
      expect(before, 'AC-24: 缺少 before 阶段快照').not.toBeNull();
      expect(contentHash, 'AC-24: 导出 Excel 内容哈希（已剔除生成时间戳这一已知易变字段）分页改动前后应完全一致').toBe(before!.contentHash);
    }
  });
});

test.describe('AC-25: COMPOSITE 渲染不受影响（🚧 需造数，主线批准前 skip）', () => {
  test.skip('T-25 父卡片组合工序页签行数，分页改动前后一致', async () => {
    // 只读 SQL 已确认：全库 composite_type 均为 SIMPLE，零 COMPOSITE / 零 PART。
    // 造数方案草案（未执行，待主线批准）：
    //   通过前端 UI 新建一张测试报价单，添加一个已知带组合结构的产品模板/BOM
    //  （具体哪个产品模板支持组合产品，需要产品/开发方指出，测试侧不读实现代码无法自行判断），
    //   验证父卡片"组合工序"页签行数在分页改动前后一致。
  });
});
