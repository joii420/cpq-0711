/**
 * task-260902「选配流程重构」E2E 公共夹具。
 *
 * 🚨 证据归档纪律（`testing.md §2`）：
 * 截图**不写 `e2e/screenshots/` 也不写 `test-results/`** —— 那两个目录下一轮开跑就被清空，
 * 「声明了截图证据、却留在会被清空的目录」等于没有证据。
 * 本文件统一把截图写进 **任务目录** `dev-docs/task-260902-选配流程重构/证据/e2e/`，
 * 随任务一起提交。
 *
 * 🚫 全局状态纪律（`testing.md §4.3`）：本套 E2E **不写共享库**。
 * 需要「0 条外购件」「0 组配置材质」「hasTemplate=false」这类现网构造不出的前置时，
 * 一律用 `page.route()` 拦截**该前端页面自己的请求**来注入，零全局副作用。
 * 这些前置的**后端半句**由 `cpq-backend/src/test/java/com/cpq/task260902/` 的接口层用例覆盖，
 * 不存在「只 mock 不验真」的缺口。
 */
import { Page, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

/** 任务目录下的证据归档位（下一轮跑测试不会清空它）。 */
export const EVIDENCE_DIR = path.join(
  __dirname, '..', '..', '..',
  'dev-docs', 'task-260902-选配流程重构', '证据', 'e2e'
);
fs.mkdirSync(EVIDENCE_DIR, { recursive: true });

let shotIdx = 0;
/** 截图 → 归档到任务目录。文件名带序号与用例名，便于在 test-report.md 里逐条引用。 */
export async function shot(page: Page, name: string): Promise<string> {
  const file = path.join(EVIDENCE_DIR, `${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  console.log(`📸 ${name} → ${file}`);
  return file;
}

/** 只读查库（取 fixture 用）。🚫 本套 E2E 不用它写库。 */
export function query(sql: string): string {
  // 🚨 必须先把 SQL 压成一行：JSON.stringify 会把真实换行转义成字面量 \n，
  //    psql 通过 -tAc 收到的就是两个字符 `\` + `n`，直接语法错（2026-09-03 实测踩到）。
  const oneLine = sql.replace(/\s+/g, ' ').trim();
  return execSync(
    `PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db_0724 -tAc ${JSON.stringify(oneLine)}`,
    { encoding: 'utf-8', shell: '/bin/bash' }
  ).trim();
}

/** 抽屉根节点。 */
export const drawer = (page: Page) => page.locator('.ant-drawer').last();

/**
 * 新建一张报价单并走到第 2 步，打开「选配添加」抽屉。
 * 沿用 `tc0712-selconfig-composite-smoke.spec.ts` 已验证的下拉选择时序
 * （分类/模板落定后再填名称，避开 QuotationCreateForm 的 stale closure 回填竞态）。
 */
export async function openSelConfigDrawer(page: Page, label: string, customer?: QualifiedCustomer) {
  const cust = customer || pickQualifiedCustomer();
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');

  await selectByLabel(page, '客户', cust.name);
  await page.waitForTimeout(1500);

  // 🚨 产品分类**不要选**：task-0712 update-071501 换轴到「客户产品分类」后，
  //    它随客户自动带出且渲染为 `ant-select-disabled`（2026-09-03 实测），
  //    点它不会出下拉 ⇒ 旧写法在这里必然 timeout，且长得像产品缺陷。
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(400);

  // 模板：有这个字段就随便挑一个**可用的**候选，🚫 不写死模板名。
  //    实测「报价模板0608」只存在于某些产品分类下 —— 换个客户就一个候选都搜不到，
  //    写死模板名和写死客户 code 是同一个病：数据一漂移就把环境问题伪装成产品问题。
  for (const tpl of ['报价模板', '核价模板']) {
    await selectAnyIfPresent(page, tpl);
  }

  // 🚨 名称必须在体检**之前**填：它本身就是必填项，没填时「下一步」本来就该禁用。
  //    上一版把体检放在填名称之前，于是把「还没填完」误判成「分类解析不出来」——
  //    自己造了一个假故障，还差点当成产品缺陷报上去。
  await page.locator('input[placeholder*="报价单名称"]').first().fill(`E2E-260902-${label}-${Date.now()}`);

  await assertStep1Passable(page);
  const next = page.getByRole('button', { name: /下一步/ }).first();
  await next.click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);

  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(400);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(800);
  await expect(drawer(page), '「选配添加」抽屉应打开').toBeVisible({ timeout: 10000 });
}

export async function selectByLabel(page: Page, label: string, search: string, optionText?: string) {
  // 🚨 先关掉上一个下拉：antd 选完之后浮层**仍挂在 DOM 里**，
  //    全局查 `.ant-select-item-option` 会命中上一个下拉的残留选项。
  //    实测症状：选「产品分类」时拿到的候选是 ["苏州西门子 (8000137)"]（客户下拉的），
  //    然后以 timeout 结束 —— 长得像产品缺陷，其实是选择器串台。
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(250);

  const item = page.locator('.ant-form-item')
    .filter({ has: page.locator('label', { hasText: label }) }).first();
  await item.locator('.ant-select').first().click();
  await page.waitForTimeout(300);
  if (search) {
    await page.keyboard.type(search, { delay: 60 });
    await page.waitForTimeout(900);
  }
  // 🚨 找不到选项时 **不要让它超时**：超时长得和产品缺陷一模一样
  //    （`cpq-playwright-selector-pitfalls`：四个选择器坑全表现为 timeout，最易误判）。
  //    这里把它转成一条带「当前实际候选」的诊断，读报告的人一眼能分清是夹具还是产品。
  const want = optionText || search;
  // 只在**当前可见**的那个下拉浮层里找选项（.last() = 最近打开的那个）
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last();
  const option = dropdown.locator('.ant-select-item-option').filter({ hasText: want }).first();
  try {
    await option.waitFor({ state: 'visible', timeout: 8000 });
  } catch {
    const candidates = await dropdown.locator('.ant-select-item-option').allInnerTexts().catch(() => []);
    throw new Error(
      `[夹具失败·非产品缺陷] 「${label}」下拉里找不到选项「${want}」。`
      + `当前实际候选（${candidates.length} 个）=${JSON.stringify(candidates)}。`
      + `⇒ 这是 E2E 夹具/选择器问题，不是被测功能的结论。`);
  }
  await option.click();
  await page.waitForTimeout(400);
}

/**
 * 🚨 AP-31 族守卫：断言某个区域里**不出现「加载中」**。
 * 空态必须是空态，不是永久 loading 占位。
 */
export async function expectNoLoadingPlaceholder(page: Page, scope = '.ant-drawer') {
  const loading = page.locator(scope).getByText(/加载中/);
  await expect(loading, '🚫 空态区域出现了「加载中…」永久占位（AP-31 族）').toHaveCount(0);
}

/**
 * 读禁用按钮的 tooltip 文案（§1.2：禁用必须可见并说明原因）。
 *
 * <p>🚨 <b>antd 的禁用按钮本身不触发鼠标事件</b>，tooltip 挂在外层包裹元素上 ⇒
 * 直接 hover 按钮读到的是空字符串。空字符串会让「产品没写原因」和「我没读到」
 * 长得一模一样 —— 又一个观测手段失灵伪装成产品缺陷的形态。
 * ⇒ 三路依次尝试：外层包裹 → 按钮本身 → 鼠标坐标移动；仍为空才认为真的没有 tooltip。
 */
export async function tooltipOf(page: Page, button: ReturnType<Page['locator']>): Promise<string> {
  // 🚨 与 hover 同一类坑的第二个入口：`innerText()` 对**不存在的元素**也会等，
  //    不传 timeout 时它继承整条用例的超时（180s）⇒ 没有 tooltip 的场景直接把用例卡满。
  //    ⇒ 先 count() 判存在，再带 timeout 读。
  const read = async () => {
    const tip = page.locator('.ant-tooltip-inner').last();
    if (await tip.count() === 0) return '';
    return (await tip.innerText({ timeout: 2000 }).catch(() => '')).trim();
  };

  // 🚨 每个 hover 都必须带 timeout：不传时它继承**整条用例的超时**（本项目设成了 180s），
  //    一次 hover 不成功就把用例卡满 3 分钟，最后以「Test timeout」收场 ——
  //    失败点落在毫不相干的地方，根因完全不可见（AC-2/AC-14 就这么各挂了 3 分钟）。
  for (const target of [button.locator('xpath=..'), button]) {
    await target.hover({ force: true, timeout: 3000 }).catch(() => {});
    await page.waitForTimeout(700);
    const t = await read();
    if (t) return t;
  }
  const box = await button.boundingBox().catch(() => null);
  if (box) {
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.waitForTimeout(800);
  }
  return await read();
}

// ─────────────────────────── 步骤操作（按原型的可见文案定位）───────────────────────────

export async function fillStep1(page: any, productNo: string) {
  const input = drawer(page).getByPlaceholder(/客户产品编号|请输入.*编号/).first();
  await input.fill(productNo);
  await page.waitForTimeout(1200);
  await nextStep(page);
}

export async function nextStep(page: any) {
  const next = drawer(page).getByRole('button', { name: /下一步/ }).first();
  await expect(next, '「下一步」应可点（不可点说明上一步校验没过）').toBeEnabled({ timeout: 10000 });
  await next.click();
  await page.waitForTimeout(900);
}

/** 「+ 添加配件」→ 零件 → 新建零件 → 填品名/规格/尺寸/总重（原型 2 + 原型 3）。 */
export async function startNewPart(page: any, name: string, spec: string, dim: string, weight: string) {
  await openNewPartForm(page);
  await fillInDrawer(page, '品名', name);
  await fillInDrawer(page, '规格', spec);
  await fillInDrawer(page, '尺寸', dim);
  await fillInDrawer(page, '总重', weight);
  await page.waitForTimeout(300);
}

/**
 * 「+ 添加材质」→ 搜索 → 选择 → 填占比。
 * ⚠️ 250+ 条材质走虚拟滚动，**必须先输入过滤再点选**（靠滚动找会随机挂，对齐 AC-18 的踩坑记录）。
 */
/**
 * 加一个材质并填占比。**每一步都自验**，失败时打印现场 —— 🚫 不允许「点了就当成了」。
 *
 * ⚠️ 250+ 条材质走虚拟滚动，必须先输入过滤再点选（AC-18 的踩坑记录）。
 */
export async function addMaterial(page: Page, recipeCode: string, ratio: string) {
  const dump = async (why: string) => {
    const txt = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '(取不到)'))
      .replace(/\n+/g, ' | ').slice(0, 800);
    throw new Error(`[夹具失败·选择器，非产品缺陷] ${why}\n当前抽屉可见文本：${txt}`);
  };

  // ① 打开材质选择器（空态/非空态两个入口都试，并验证真的开了）
  const pickerOpen = async () =>
    (await page.locator('.ant-drawer, .ant-modal').last()
      .getByPlaceholder(/搜索|材质编号|材质名/).count()) > 0;
  for (const name of [/\+\s*添加第一个材质/, /\+\s*添加材质/, /添加材质/]) {
    if (await pickerOpen()) break;
    const btn = drawer(page).getByRole('button', { name }).first();
    if (await btn.count() === 0) continue;
    await btn.click({ force: true }).catch(() => {});
    await page.waitForTimeout(900);
  }
  if (!await pickerOpen()) await dump('点了「+ 添加材质」后，材质选择器（带搜索框）没有出现。');

  // ② 过滤
  const panel = page.locator('.ant-drawer, .ant-modal').last();
  await panel.getByPlaceholder(/搜索|材质编号|材质名/).first().fill(recipeCode);
  await page.waitForTimeout(1200);

  // ③ 选中该行：优先点行内「选择」按钮，没有就点行本身
  const row = panel.locator('tr, li, .picker-row, [class*="row"]')
    .filter({ hasText: recipeCode }).first();
  if (await row.count() === 0) {
    const seen = (await panel.innerText({ timeout: 3000 }).catch(() => '')).replace(/\n+/g, ' | ').slice(0, 600);
    throw new Error(`[夹具失败·选择器，非产品缺陷] 材质选择器里搜「${recipeCode}」没有命中任何行。\n选择器现场：${seen}`);
  }
  const pickBtn = row.getByRole('button', { name: /选\s*择|添加|确\s*定/ }).first();
  if (await pickBtn.count() > 0) await pickBtn.click({ force: true }).catch(() => {});
  else await row.click({ force: true }).catch(() => {});
  await page.waitForTimeout(1000);

  // ④ 填占比：材质加进列表后，找到含该材质码的那一行的最后一个 input
  const ratioRow = drawer(page).locator('tr, [class*="row"]').filter({ hasText: recipeCode }).first();
  const ratioInput = ratioRow.locator('input').last();
  try {
    await ratioInput.waitFor({ state: 'visible', timeout: 6000 });
  } catch {
    await dump(`材质 ${recipeCode} 选完之后，材质列表里没有出现它的占比输入框（可能没选中成功）。`);
  }
  await ratioInput.fill(ratio);
  await ratioInput.blur().catch(() => {});
  await page.waitForTimeout(600);
  console.log(`[夹具] 已加材质 ${recipeCode} 占比 ${ratio}`);
}


/** 打开材质选择器（不选，只打开）—— AC-17 / AC-18 / AC-18b 用。 */
export async function openMaterialPicker(page: Page) {
  // 🚨 空态入口叫「+ 添加第一个材质」，非空态才是「+ 添加材质」——两个都要试，
  //    并且**验证选择器真的开了**（出现搜索框），🚫 不许「点了就当开了」。
  const opened = async () =>
    (await drawer(page).getByPlaceholder(/搜索|材质编号|材质名/).count()) > 0;
  for (const name of [/\+\s*添加第一个材质/, /\+\s*添加材质/, /添加材质/]) {
    if (await opened()) return;
    const btn = drawer(page).getByRole('button', { name }).first();
    if (await btn.count() === 0) continue;
    await btn.click({ force: true }).catch(() => {});
    await page.waitForTimeout(900);
  }
  if (await opened()) return;
  const txt = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '(取不到)')).replace(/\n+/g, ' | ').slice(0, 800);
  throw new Error(
    '[夹具失败·选择器，非产品缺陷] 点了「+ 添加材质 / + 添加第一个材质」后，材质选择器没有出现。\n'
    + `当前抽屉可见文本：${txt}`);
}

/** 材质选择器里输入关键词并等过滤结果稳定，返回可见行数。 */
export async function searchInPicker(page: any, keyword: string): Promise<number> {
  const picker = page.locator('.ant-drawer, .ant-modal').last();
  const kw = picker.getByPlaceholder(/搜索|材质编号|材质名|工序/).first();
  await kw.fill(keyword);
  await page.waitForTimeout(1200);
  return picker.locator('tbody tr, li.picker-row, .picker-row').count();
}

/**
 * 「+ 添加工序」→ 搜索 → 选择，按给定顺序依次加入**有序列表**（允许重复）。
 * ⚠️ 顺序即工艺顺序：它影响落库 `unit_price.seq_no` 与显示，**不影响料号复用判定**（A0 裁决）。
 */
export async function addProcesses(page: Page, processNos: string[]) {
  for (const no of processNos) {
    // 打开工序选择器（空态入口是「+ 添加第一个工序」），并验证真的开了
    const opened = async () =>
      (await drawer(page).getByPlaceholder(/搜索|工序编号|工序名/).count()) > 0;
    for (const name of [/\+\s*添加第一个工序/, /\+\s*添加工序/, /添加工序/]) {
      if (await opened()) break;
      const btn = drawer(page).getByRole('button', { name }).first();
      if (await btn.count() === 0) continue;
      await btn.click({ force: true }).catch(() => {});
      await page.waitForTimeout(900);
    }
    if (!await opened()) {
      const txt = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '')).replace(/\n+/g, ' | ').slice(0, 700);
      throw new Error(`[夹具失败·选择器，非产品缺陷] 点了「+ 添加工序」后工序选择器没出现。\n抽屉现场：${txt}`);
    }

    await drawer(page).getByPlaceholder(/搜索|工序编号|工序名/).first().fill(no);
    await page.waitForTimeout(1100);

    const row = drawer(page).locator('tr, li, .picker-row, [class*="row"]')
      .filter({ hasText: no }).first();
    if (await row.count() === 0) {
      const txt = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '')).replace(/\n+/g, ' | ').slice(0, 700);
      throw new Error(`[夹具失败·选择器，非产品缺陷] 工序选择器里搜「${no}」没命中任何行。\n抽屉现场：${txt}`);
    }
    const pick = row.getByRole('button', { name: /选\s*择|添\s*加/ }).first();
    if (await pick.count() > 0) await pick.click({ force: true, timeout: 5000 }).catch(() => {});
    else await row.click({ force: true, timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(800);
    console.log(`[夹具] 已加工序 ${no}`);
  }
}


/** 表单里有这个字段就选，没有就跳过（并打印，便于事后判断夹具走了哪条路）。 */
export async function selectIfPresent(page: Page, label: string, search: string) {
  const n = await page.locator('.ant-form-item')
    .filter({ has: page.locator('label', { hasText: label }) }).count();
  if (n === 0) { console.log(`[夹具] 表单里没有「${label}」字段，跳过`); return false; }
  const sel = page.locator('.ant-form-item')
    .filter({ has: page.locator('label', { hasText: label }) }).first().locator('.ant-select').first();
  const cls = (await sel.getAttribute('class')) || '';
  if (cls.includes('ant-select-disabled')) {
    console.log(`[夹具] 「${label}」是禁用态（随客户自动带出），跳过`);
    return false;
  }
  await selectByLabel(page, label, search);
  return true;
}

/**
 * 断言 Step1 **真的能过** —— 判据是「下一步」变可点，而不是某个控件长什么样。
 *
 * <p>🚨 <b>为什么判据是按钮而不是分类控件</b>（归因经两次更正，写全免得后人只学到一半）：
 * <ol>
 *   <li>第一版在**填名称之前**就读分类 ⇒ 把「必填项还没填完」判成「分类坏了」（自造假故障）；</li>
 *   <li>第二版怀疑是异步竞态。<b>但主线实机复核推翻了它</b>：罗克韦尔的分类<b>一直正常显示</b>
 *       「默认分类」，按钮禁用的真实原因写在 `title="请先填写产品分类和报价模板"` 里 ——
 *       是<b>报价模板没选</b>。之所以三次探测都读不到分类值，是<b>用 antd 内部类名做选择器失灵</b>
 *       （还有一次把输出截断了，而目标在更后面）。</li>
 * </ol>
 * ⇒ 结论：<b>根因是「读中间态的手段不可靠」，不只是时机</b>。
 * 🚫 <b>所以不要以为加个 `waitForTimeout` 就够</b> —— 真正管用的是<b>不去读中间态</b>：
 * 判据直接取「下一步能不能点」这个最终事实，读不到中间态也就不会被它骗。
 *
 * <p>只有在**等不到**的时候才去读控件做归因，并把三类可能一次列全，
 * 让读报告的人不用再猜（🚫 失败信息里不写「可能有问题」这种话）。
 */
export async function assertStep1Passable(page: Page) {
  const next = page.getByRole('button', { name: /下一步/ }).first();
  try {
    await expect(next).toBeEnabled({ timeout: 15000 });
    return;
  } catch {
    // 等不到 → 现场取证
    const required = await page.locator('.ant-form-item-required').allInnerTexts().catch(() => []);
    const errors = await page.locator('.ant-form-item-has-error').allInnerTexts().catch(() => []);
    const catItem = page.locator('.ant-form-item')
      .filter({ has: page.locator('label', { hasText: '产品分类' }) }).first();
    const catValue = (await catItem.locator('.ant-select-selection-item').first()
      .innerText({ timeout: 3000 }).catch(() => '')).trim();
    const catDisabled = ((await catItem.locator('.ant-select').first()
      .getAttribute('class').catch(() => '')) || '').includes('ant-select-disabled');
    throw new Error(
      '[夹具失败·非产品缺陷] Step1 的「下一步」15s 内始终不可点，本用例进不到被测流程。\n'
      + `现场：必填项=${JSON.stringify(required)}；报错项=${JSON.stringify(errors)}；`
      + `产品分类 值="${catValue}" 禁用=${catDisabled}\n`
      + '三类可能，按现场取证对号入座：\n'
      + ' ① 产品分类为空且禁用 ⇒ 该客户的 product_category_id 指向已不存在的分类（悬空）。'
      + " 核对：SELECT c.code, pc.id IS NOT NULL AS 分类存在 FROM customer c"
      + " LEFT JOIN product_category pc ON pc.id=c.product_category_id WHERE c.code='<本次客户>';\n"
      + ' ② 有别的必填项没填 ⇒ 夹具漏了字段，补上即可；\n'
      + ' ③ 报错项非空 ⇒ 看报错文案，那是产品的校验结论，此时才可能是真缺陷。');
  }
}


export type QualifiedCustomer = { code: string; name: string };

/**
 * 挑一个**合格的**夹具客户，🚫 不写死 code。
 *
 * <p>合格判据（主线 2026-09-03 裁定）：
 * <ol>
 *   <li>{@code status='ACTIVE'}</li>
 *   <li>产品分类<b>有效</b> —— 即 {@code product_category_id} 能 JOIN 到一条 ACTIVE 的分类。
 *       ⚠️ 悬空引用（指向已不存在的分类）会让「产品分类」这个**必填项**永远为空且控件禁用 ⇒
 *       「下一步」永远不可点 ⇒ 建不出报价单。实测全库有 1 个这样的客户（苏州西门子），
 *       它恰恰是历史上的 E2E 夹具客户。</li>
 *   <li><b>有历史报价单</b> —— 这是「建单流程真的走得通」的实证，比「分类存在」更硬：
 *       分类字段可能还有别的坑，而历史单是结果证据。</li>
 *   <li>可选：{@code needsTakenProductNo} —— 需要一个**已被占用**的客户产品编号时才加这一条
 *       （AC-2 验「编号已存在则挡住」必须有真实的占用行）。</li>
 * </ol>
 *
 * <p>🚨 <b>为什么不写死 code</b>：写死的夹具会在共享库数据漂移时，把「环境坏了」伪装成
 * 「产品坏了」——本任务就真的踩到了（旧夹具客户的分类被删，表现为「下一步点不动」）。
 * <p>📌 罗克韦尔（项目标杆客户、报价模板 V2 的模型客户）只作**排序优先**，不作硬依赖：
 * 它若哪天不合格，查询会自动退到下一个合格客户，而不是整套 E2E 崩掉。
 */
export function pickQualifiedCustomer(opts: { needsTakenProductNo?: boolean } = {}): QualifiedCustomer {
  const extra = opts.needsTakenProductNo
    ? `AND EXISTS (SELECT 1 FROM material_customer_map m
                   WHERE m.customer_no = c.code AND m.system_type='QUOTE'
                     AND m.customer_product_no IS NOT NULL)`
    : '';
  const sql = `
    SELECT c.code || '|' || c.name
    FROM customer c
    JOIN product_category pc ON pc.id = c.product_category_id
    WHERE c.status='ACTIVE' AND pc.status='ACTIVE'
      AND EXISTS (SELECT 1 FROM quotation q WHERE q.customer_id = c.id)
      ${extra}
    ORDER BY (c.name LIKE '%罗克韦尔%') DESC,
             (SELECT count(*) FROM quotation q WHERE q.customer_id = c.id) DESC
    LIMIT 1`;
  const row = query(sql);
  if (!row) {
    throw new Error(
      '[夹具失败·环境不满足，非产品缺陷] 找不到合格的夹具客户。'
      + `合格判据 = ACTIVE + 产品分类有效 + 有历史报价单${opts.needsTakenProductNo ? ' + 有已占用的客户产品编号' : ''}。\n`
      + '核对 SQL：' + sql.replace(/\s+/g, ' ').trim() + '\n'
      + '⇒ 🚫 不要退回写死某个客户 —— 那正是本次踩坑的成因（数据漂移会把环境问题伪装成产品问题）。');
  }
  const [code, name] = row.split('|');
  console.log(`[夹具] 选用客户 ${code} / ${name}${opts.needsTakenProductNo ? '（含已占用编号）' : ''}`);
  return { code, name };
}

/**
 * 表单里有这个下拉、且可用，就挑**第一个候选**选上；没有 / 禁用 / 无候选则跳过并打印。
 *
 * <p>🚫 <b>不写死候选名</b>：模板候选是按产品分类过滤的，换个客户就可能一个都搜不到。
 * 夹具只需要「随便一个合法值」，写死具体名字只会让它随数据漂移而碎。
 */
export async function selectAnyIfPresent(page: Page, label: string): Promise<boolean> {
  const item = page.locator('.ant-form-item')
    .filter({ has: page.locator('label', { hasText: label }) }).first();
  if (await item.count() === 0) { console.log(`[夹具] 表单里没有「${label}」，跳过`); return false; }
  const sel = item.locator('.ant-select').first();
  if (((await sel.getAttribute('class')) || '').includes('ant-select-disabled')) {
    console.log(`[夹具] 「${label}」禁用态，跳过`); return false;
  }
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(250);
  await sel.click();
  await page.waitForTimeout(900);
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last();
  const options = dropdown.locator('.ant-select-item-option');
  const n = await options.count().catch(() => 0);
  if (n === 0) {
    console.log(`[夹具] 「${label}」无可选候选（该产品分类下没有配模板），跳过`);
    await page.keyboard.press('Escape').catch(() => {});
    return false;
  }
  const picked = (await options.first().innerText({ timeout: 3000 }).catch(() => '')).replace(/\n/g, ' ');
  await options.first().click();
  await page.waitForTimeout(500);
  console.log(`[夹具] 「${label}」选了第 1 个候选（共 ${n} 个）："${picked}"`);
  return true;
}

/**
 * 在抽屉里点一个文案元素；找不到时**打印抽屉当前可见文本**再判死。
 *
 * 🚨 目的同 `selectByLabel`：让选择器失效以「找不到 X，现场是 Y」的面目出现，
 * 而不是一个 30s timeout —— 后者和产品缺陷长得一模一样（`cpq-playwright-selector-pitfalls`）。
 */
export async function clickInDrawer(page: Page, what: string, matcher: RegExp | string) {
  const el = drawer(page).getByText(matcher).first();
  try {
    await el.waitFor({ state: 'visible', timeout: 6000 });
  } catch {
    const txt = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '(取不到抽屉文本)'))
      .replace(/\n+/g, ' | ').slice(0, 900);
    throw new Error(
      `[夹具失败·选择器，非产品缺陷] 抽屉里找不到「${what}」。\n当前抽屉可见文本：${txt}`);
  }
  await el.click();
}

/**
 * 打开「配件类型」选择（零件 / 外购件）。
 *
 * <p>🚨 两个入口都要试：空态是「+ 添加第一个配件」，非空态是「+ 添加配件」，
 * 且实测点了其中一个之后抽屉可能**没有任何反应**（DOM 顺序上的 .first() 未必是可见的那个）。
 * ⇒ 点完必须**验证状态真的变了**（出现「零件」选项），没变就换另一个入口再点，
 * 仍不变才判死并打印现场 —— 🚫 不许「点了就当开了」然后让后续断言去背锅。
 */
export async function openPartTypePicker(page: Page) {
  // 🚨 就绪判据用**弹层标题**，不用「零件」二字：加完第一个配件后，配件卡片摘要里
  //    就带着「零件」（如「触点 零件新建 · 总重 10 g」）⇒ 第二次调用会误判为「已经开了」，
  //    于是根本没点开选择器，失败点落到后面找不到「外购件」（AC-11 就这么挂的）。
  const appeared = async () =>
    await drawer(page).getByText(/第\s*1\s*步：选择类型/).first().isVisible().catch(() => false);

  // 🚨 必须靠**前导「+」**把动作按钮和**步骤导航**区分开：
  //    向导顶部的步骤条「客户产品编号 / 添加配件 / 组合工序 / 确认并添加」**本身就是 button**，
  //    `/添加配件/` 会先命中导航项 ⇒ 点了只是切步骤，等于没点，
  //    然后失败点落到「类型选择没出现」（AC-11 就这么挂的）。
  for (const name of [/\+\s*添加第一个配件/, /\+\s*添加配件/, /\+\s*添加第一个配件/]) {
    if (await appeared()) return;
    const btn = drawer(page).getByRole('button', { name }).first();
    if (await btn.count() === 0) continue;
    await btn.click({ force: true, timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(900);
  }
  if (await appeared()) return;
  const txt = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '(取不到)')).replace(/\n+/g, ' | ').slice(0, 900);
  throw new Error(
    '[夹具失败·选择器，非产品缺陷] 点了「+ 添加配件 / + 添加第一个配件」之后，'
    + '配件类型选择（零件 / 外购件）始终没出现。\n当前抽屉可见文本：' + txt);
}

/**
 * 点「下一步」推进子流程，直到目标出现。
 *
 * <p>🚨 每推进一步都**验证目标是否出现**，而不是「点 N 次就当到了」。
 * 实测「添加配件」是多步子流程（选类型 → 下一步 → 选来源 → 下一步 → 零件表单），
 * 步数会随实现调整；写死点几次会在流程一变就碎，且碎得像产品缺陷。
 */
export async function advanceUntilVisible(page: Page, target: RegExp, what: string, maxSteps = 3) {
  const seen = async () => await drawer(page).getByText(target).first().isVisible().catch(() => false);
  for (let i = 0; i < maxSteps; i++) {
    if (await seen()) return;
    const next = drawer(page).getByRole('button', { name: /下一步/ }).last();
    if (await next.count() === 0) break;
    await next.click({ force: true }).catch(() => {});
    await page.waitForTimeout(900);
  }
  if (await seen()) return;
  const txt = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '(取不到)')).replace(/\n+/g, ' | ').slice(0, 900);
  throw new Error(
    `[夹具失败·选择器，非产品缺陷] 连点「下一步」${maxSteps} 次后仍未出现「${what}」。\n当前抽屉可见文本：${txt}`);
}

/**
 * 按「表单项标签」在抽屉里填值。
 *
 * <p>🚨 不用 `getByLabel`：antd 的 label 未必通过 `for` / `aria-label` 与 input 关联，
 * 匹配不上时报的是 `locator.fill` 超时 —— 又一个「长得像产品缺陷」的夹具故障。
 * ⇒ 改为「先按 label 文案锁定 .ant-form-item，再取其中的 input」，找不到就打印现场。
 */
export async function fillInDrawer(page: Page, label: string, value: string) {
  // 定位分两路，按可靠性排序：
  //  ① 标准 antd 表单项（有 .ant-form-item + label）
  //  ② 🚨 本抽屉的零件表单**不用 .ant-form-item**，标签是纯文本（形如 `*品名`）
  //     ⇒ 退化为「找到标签元素，取它之后的第一个 input」（文档顺序），
  //     这对「标签在上、输入框在下」的布局是稳的，且不依赖 class 名。
  const byFormItem = drawer(page).locator('.ant-form-item')
    .filter({ has: page.locator('label', { hasText: label }) }).first();
  let input = byFormItem.locator('input, textarea').first();
  if (await byFormItem.count() === 0) {
    const labelEl = drawer(page).getByText(new RegExp(`^\\*?\\s*${label}\\s*$`)).first();
    if (await labelEl.count() > 0) {
      input = labelEl.locator('xpath=following::input[1]');
    }
  }
  try {
    await input.waitFor({ state: 'visible', timeout: 6000 });
  } catch {
    const drawerCount = await page.locator('.ant-drawer').count().catch(() => -1);
    const openDrawers = await page.locator('.ant-drawer-open').count().catch(() => -1);
    const labels = await drawer(page).locator('.ant-form-item label').allInnerTexts().catch(() => []);
    const phsInDrawer = await drawer(page).locator('input').evaluateAll(
      (els) => els.map((e) => (e as HTMLInputElement).placeholder || '(无)')).catch(() => []);
    const phsPage = await page.locator('input:visible').evaluateAll(
      (els) => els.map((e) => (e as HTMLInputElement).placeholder || '(无)')).catch(() => []);
    const txt = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '(取不到)'))
      .replace(/\n+/g, ' | ').slice(0, 700);
    throw new Error(
      `[夹具失败·选择器，非产品缺陷] 抽屉里找不到「${label}」对应的输入框。\n`
      + `.ant-drawer 数量=${drawerCount}（open=${openDrawers}）\n`
      + `抽屉内表单标签=${JSON.stringify(labels)}\n`
      + `抽屉内 input placeholder=${JSON.stringify(phsInDrawer)}\n`
      + `整页可见 input placeholder=${JSON.stringify(phsPage)}\n`
      + `抽屉可见文本：${txt}`);
  }
  await input.fill(value);
  await page.waitForTimeout(200);
}

/**
 * 点「下一步」推进，直到 `ready()` 成立。
 *
 * <p>🚨 判据用**结构性事实**（出现了可输入字段 / 出现了某个控件），不要用可能出现在
 * 说明文案里的关键词 —— 后者会在上一步就误判为已到位，然后停止推进，
 * 表现为「后面的操作全找不到元素」，而根因在两步之前。
 */
export async function advanceUntil(
  page: Page, ready: () => Promise<boolean>, what: string, maxSteps = 3,
) {
  for (let i = 0; i < maxSteps; i++) {
    if (await ready()) return;
    const next = drawer(page).getByRole('button', { name: /下一步/ }).last();
    if (await next.count() === 0) break;
    await next.click({ force: true }).catch(() => {});
    await page.waitForTimeout(1000);
  }
  if (await ready()) return;
  const txt = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '(取不到)')).replace(/\n+/g, ' | ').slice(0, 900);
  throw new Error(
    `[夹具失败·选择器，非产品缺陷] 连点「下一步」${maxSteps} 次后仍未到达「${what}」。\n当前抽屉可见文本：${txt}`);
}

/**
 * 点「添加到报价单」提交，并**等到提交请求真的发出**。返回该响应。
 *
 * <p>🚨 <b>凡是要断言落库结果的用例，都必须走这个函数</b>（主线纪律）：
 * 看库分不出「提交失败」和「没点到提交」—— 两者都表现为「0 行」。
 * 本轮 AC-25 就靠这条阳性对照拦下了一次险些误报的 P0：当时
 * 「观测到的提交请求 = []」，真因是提交按钮叫「添加到报价单」而不是「确认并添加」。
 */
export async function submitToQuotation(page: Page, tag: string) {
  const btnTexts = (await drawer(page).getByRole('button').allInnerTexts())
    .map((t) => t.replace(/\s+/g, ''));
  console.log(`[${tag}] 提交前按钮 =`, JSON.stringify(btnTexts));

  const submit = drawer(page)
    .getByRole('button', { name: /添加到报价单|确认并添加|确认加入/ }).last();
  await expect(submit, `${tag}：底部应有「添加到报价单」按钮`).toBeVisible({ timeout: 10000 });
  await expect(submit, `${tag}：提交按钮应可点`).toBeEnabled();

  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes('/configure-product/quotations/') && r.request().method() === 'POST',
      { timeout: 25000 },
    ).catch(() => null),
    submit.click(),
  ]);
  if (!resp) {
    const after = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '(抽屉已关闭)'))
      .replace(/\n+/g, ' | ').slice(0, 600);
    throw new Error(
      `[${tag} 阳性对照失败] 点了提交按钮后 25s 内没有观测到 `
      + 'POST /configure-product/quotations/ ⇒ **按钮没点到**（夹具问题），'
      + '此时任何「落库 0 行」都不能解释成产品缺陷。\n'
      + `按钮清单=${JSON.stringify(btnTexts)}\n点击后现场=${after}`);
  }
  const body = await resp.text().catch(() => '');
  console.log(`[${tag}] 提交响应 ${resp.status()} ${body.slice(0, 300)}`);

  // 🚨 等抽屉真的关掉再把控制权交回调用方：抽屉/遮罩还在时，页面上的按钮
  //    （如「添加产品」）会一直处于 not stable，表现为 locator.click 一路等到用例超时 ——
  //    失败点落在那个按钮上，而根因是抽屉没关（AC-19④/AC-12 就这么各挂了 4 分钟）。
  await page
    .waitForFunction(() => document.querySelectorAll('.ant-drawer-open').length === 0, undefined,
      { timeout: 15000 })
    .catch(() => console.log(`[${tag}] ⚠️ 15s 内抽屉未关闭，后续操作可能被遮罩挡住`));
  await page.waitForTimeout(1500);
  return resp;
}

/**
 * 加一个外购件配件：打开类型选择 → 选「外购件」→ 下一步 → 选料号 → 确定。
 *
 * <p>🚨 与零件同构：「添加配件」是**多步子流程**，选完类型必须再点「下一步」。
 * 每一步都验证状态真的变了，失败时打印抽屉现场（🚫 不许「点了就当成了」）。
 */
export async function addOutsourcedPart(page: Page, partNo: string) {
  await openOutsourcedList(page);

  const kw = drawer(page).getByPlaceholder(/搜索|料号|品名/).first();
  if (await kw.count() > 0) { await kw.fill(partNo); await page.waitForTimeout(1000); }

  const row = drawer(page).locator('tr, li, .picker-row, [class*="row"]')
    .filter({ hasText: partNo }).first();
  if (await row.count() === 0) {
    const txt = (await drawer(page).innerText({ timeout: 3000 }).catch(() => '')).replace(/\n+/g, ' | ').slice(0, 700);
    throw new Error(`[夹具失败·选择器，非产品缺陷] 外购件列表里找不到 ${partNo}。\n抽屉现场：${txt}`);
  }
  const pick = row.getByRole('button', { name: /选\s*择|添加|确\s*定/ }).first();
  if (await pick.count() > 0) await pick.click({ force: true }).catch(() => {});
  else await row.click({ force: true }).catch(() => {});
  await page.waitForTimeout(800);

  const ok = drawer(page).getByRole('button', { name: /确\s*定/ }).last();
  if (await ok.count() > 0) { await ok.click({ force: true }).catch(() => {}); await page.waitForTimeout(800); }
  console.log(`[夹具] 已加外购件 ${partNo}`);
}

/**
 * 打开「外购件」候选列表（只打开、不选料号）—— AC-5 / AC-16 用。
 *
 * 🚨 「添加配件」是多步子流程：选类型 → **下一步** → 才到列表。
 * 到达判据用结构性事实（出现搜索框或列表行），🚫 不用「外购件」三个字 ——
 * 类型卡片的说明文案里就有它，会在上一步误判为已到位。
 */
export async function openOutsourcedList(page: Page) {
  await openPartTypePicker(page);
  await clickInDrawer(page, '外购件', /^外购件$/);
  await page.waitForTimeout(400);
  await advanceUntil(page, async () =>
    (await drawer(page).getByPlaceholder(/搜索|料号|品名/).count()) > 0
    || (await drawer(page).getByText(/料号库里还没有外购件|共\s*\d+\s*条/).count()) > 0,
    '外购件候选列表（或其空态）');
}

/** 打开「新建零件」表单（只打开、不填）—— AC-23 用；startNewPart 也走它。 */
export async function openNewPartForm(page: Page) {
  await openPartTypePicker(page);
  await clickInDrawer(page, '零件', /^零件$/);
  await page.waitForTimeout(400);
  await advanceUntilVisible(page, /新建零件/, '零件来源选择（新建/已有）');
  await clickInDrawer(page, '新建零件', /新建零件/);
  await page.waitForTimeout(400);
  await advanceUntil(page, async () => (await drawer(page).locator('input:visible').count()) > 0,
    '零件表单（可输入的字段）');
}
