/**
 * task-260902 · E2E 主流程：**4 步向导 + 客户产品编号前置 + 保存刷新回填**
 *
 * 覆盖 **AC-1 / AC-2 / AC-4 / AC-11 / AC-12 / AC-14** 的 UI 半句。
 * 断言逐条来自 `需求文档.md §③` 的 AC 原文与 `原型图/` 的可见文案，
 * 🚫 不从实现代码派生（本 spec 作者未读 `cpq-frontend/src/pages/quotation/`）。
 *
 * 🚨 证据：每条 AC 至少一张截图，**归档到任务目录**（见 fixtures/task260902.ts 的说明）。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';
import {
  shot, query, drawer, openSelConfigDrawer, tooltipOf, EVIDENCE_DIR,
  fillStep1, nextStep, startNewPart, addMaterial, addProcesses, pickQualifiedCustomer,
  submitToQuotation, addOutsourcedPart,
} from './fixtures/task260902';

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
  console.log(`[task-260902] 证据归档目录 = ${EVIDENCE_DIR}`);
});

const FREE_PRODUCT_NO = () => `T260902-E2E-${Date.now()}`;

test.describe('task-260902 选配主流程', () => {

  /**
   * **AC-1**：「`material_customer_map` 中**不存在**客户产品编号 → 输入该编号」⇒
   * 「可继续下一步；不出现任何阻止提示」。
   * 原型 1 状态 A 的可见文案：`✓ 该编号未被占用，可以继续`。
   */
  test('AC-1 未占用的客户产品编号 → 绿色提示 + 「下一步」可点', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(180_000);   // 建单流程本身约 25s，默认 30s 会把用例从中间掐断
    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac1');

    const input = drawer(page).getByPlaceholder(/客户产品编号|请输入.*编号/).first();
    await input.fill(FREE_PRODUCT_NO());
    await page.waitForTimeout(1200);   // F-1：debounce 400ms 后调 check-product-no

    await expect(drawer(page).getByText('该编号未被占用'),
      'AC-1：未占用时应给出「✓ 该编号未被占用，可以继续」的正向提示（原型 1 状态 A）'
    ).toBeVisible({ timeout: 10000 });
    await expect(drawer(page).getByText('该编号已存在'),
      'AC-1：不得出现任何阻止提示'
    ).toHaveCount(0);
    const next = drawer(page).getByRole('button', { name: /下一步/ }).first();
    await expect(next, 'AC-1：「下一步」应可点').toBeEnabled();
    await shot(page, 'AC-1-编号未占用可继续');
  });

  /**
   * **AC-2**：「编号**已存在**于 `material_customer_map` → 输入该编号」⇒
   * ①「阻止继续（『下一步』禁用）」；②「提示文案含『该编号已存在，请从产品库添加』」；
   * ③「提示里给出**跳转到『从产品库添加』的入口**」。
   * 🚫 §1.2：禁用的按钮**必须可见**并说明原因，不许 `if(...) return null` 藏起来。
   */
  test('AC-2 已占用的编号 → 挡住 + 指路文案 + 跳转入口', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(180_000);   // 建单流程本身约 25s，默认 30s 会把用例从中间掐断

    // 🚨 AC-2 对夹具客户有**额外**要求：必须名下已有一个被占用的客户产品编号，
    //    否则「编号已存在则挡住」这条根本没有可验的输入（会空跑）。
    //    ⇒ 用 needsTakenProductNo 让挑选器把这条也纳入判据，🚫 不写死客户。
    const cust = pickQualifiedCustomer({ needsTakenProductNo: true });
    const taken = query(
      `SELECT m.customer_product_no FROM material_customer_map m
       WHERE m.customer_no='${cust.code}' AND m.system_type='QUOTE'
         AND m.customer_product_no IS NOT NULL LIMIT 1`
    );
    expect(taken, `AC-2 前置：${cust.name} 名下应有一个已占用的客户产品编号（取不到 ⇒ 本用例会空跑）`).not.toBe('');
    console.log(`[AC-2] 客户=${cust.code}/${cust.name} 已占用编号=${taken}`);

    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac2', cust);
    const input = drawer(page).getByPlaceholder(/客户产品编号|请输入.*编号/).first();
    await input.fill(taken);
    await page.waitForTimeout(1200);

    // ② 文案
    await expect(drawer(page).getByText('该编号已存在，请从产品库添加'),
      'AC-2②：提示文案必须含「该编号已存在，请从产品库添加」（原型 1 状态 B 的逐字文案）'
    ).toBeVisible({ timeout: 10000 });
    // ③ 跳转入口
    await expect(drawer(page).getByRole('button', { name: /从产品库添加/ }),
      'AC-2③：提示里必须给出跳转到「从产品库添加」的入口'
    ).toBeVisible();
    // ① 下一步禁用但可见 + tooltip 说明原因
    const next = drawer(page).getByRole('button', { name: /下一步/ }).first();
    await expect(next, 'AC-2①：「下一步」必须可见（🚫 不许直接隐藏）').toBeVisible();
    await expect(next, 'AC-2①：「下一步」必须禁用').toBeDisabled();
    const tip = await tooltipOf(page, next);
    console.log(`[AC-2] 「下一步」tooltip = ${tip}`);
    expect(tip, 'AC-2①：禁用态必须有 tooltip 说明原因（§1.2）').toContain('已存在');
    await shot(page, 'AC-2-编号已占用被挡住');
  });

  /**
   * **AC-4**：「材质占比填 `70` + `20` → 点『下一步』」⇒
   * ①「『下一步』禁用」；②「行级提示写出**实际合计值 `90%`**，不是『合计不正确』这种形容词」。
   * 🚫 本用例**不接受**只有形容词的提示。
   */
  test('AC-4 占比合计 90% → 提示必须写出实际值 90%', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(180_000);   // 建单流程本身约 25s，默认 30s 会把用例从中间掐断
    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac4');
    await fillStep1(page, FREE_PRODUCT_NO());
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');

    await addMaterial(page, '00006', '70');
    await addMaterial(page, '00123', '20');

    const warn = drawer(page).getByText(/90/).first();
    await expect(warn, 'AC-4②：占比提示必须写出实际合计值 90（🚫 不接受「合计不正确」这类形容词）')
      .toBeVisible({ timeout: 8000 });
    const warnText = await warn.innerText();
    console.log(`[AC-4] 提示文案 = ${warnText}`);
    expect(warnText, 'AC-4②：提示应写成「材质占比合计为 90%，需要正好 100%」这类含实际值的句子')
      .toMatch(/90\s*%/);

    const confirm = drawer(page).getByRole('button', { name: /确\s*定|下一步/ }).last();
    await expect(confirm, 'AC-4①：合计 ≠ 100 时不得放行').toBeDisabled();
    await shot(page, 'AC-4-占比合计90被拦');
  });

  /**
   * **AC-14**（边界）：「零件**一个材质都不加**就点下一步」⇒
   * 「阻止，提示『请至少添加一个材质』」。
   */
  test('AC-14 零材质 → 「确定」禁用 + tooltip「请至少添加一个材质」', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(180_000);   // 建单流程本身约 25s，默认 30s 会把用例从中间掐断
    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac14');
    await fillStep1(page, FREE_PRODUCT_NO());
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');

    const confirm = drawer(page).getByRole('button', { name: /确\s*定/ }).last();
    await expect(confirm, 'AC-14：「确定」必须可见（禁用不等于隐藏）').toBeVisible();
    await expect(confirm, 'AC-14：零材质时「确定」必须禁用').toBeDisabled();
    const tip = await tooltipOf(page, confirm);
    console.log(`[AC-14] tooltip = ${tip}`);
    expect(tip, 'AC-14：tooltip 应为「请至少添加一个材质」（原型 3 的逐字文案）').toContain('请至少添加一个材质');
    await shot(page, 'AC-14-零材质被拦');
  });

  /**
   * **AC-11**（序列）：「配置零件 → 加外购件 → 选组合工序 → 添加到报价单 →
   * **保存草稿 → 刷新页面 → 重新打开**」⇒
   * 「零件的多材质与占比、外购件、组合工序**全部回填**，占比显示 `70` 而非 `70.000000000000`」。
   *
   * 🚨 这是本套 E2E 里**唯一的完整序列用例**：只写单点会漏掉「切走再回来」这类真实用法。
   * 🚨 占比显示那半句是 F-12 去尾随零的验收面，🚫 不许用 `Number(s).toString()`（12 位小数会丢尾数）。
   */
  test('AC-11 保存草稿 → 刷新 → 重开：多材质/占比/外购件/工序全部回填', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(240_000);   // 整条向导 + 提交 + 刷新回填，默认 30s 会被中途掐断
    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac11');
    const productNo = FREE_PRODUCT_NO();
    await fillStep1(page, productNo);

    // 配件 1：新建零件（双材质 70/30）
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');
    await addMaterial(page, '00006', '70');
    await addMaterial(page, '00123', '30');
    await drawer(page).getByRole('button', { name: /确\s*定/ }).last().click();
    await page.waitForTimeout(600);
    await shot(page, 'AC-11-配件1已添加');

    // 配件 2：外购件（fixture基线 §3.1：现网唯一一条外购件）
    await addOutsourcedPart(page, 'TEST-Q13-CODE');

    // 提交到报价单
    await nextStep(page);           // → 组合工序
    await nextStep(page);           // → 确认并添加
    await submitToQuotation(page, 'AC-11');
    await shot(page, 'AC-11-已添加到报价单');

    // 保存草稿 → 刷新 → 重开
    await page.getByRole('button', { name: /保\s*存/ }).first().click();
    await page.waitForTimeout(2500);
    const url = page.url();
    await page.reload();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2500);
    console.log(`[AC-11] 刷新后 URL = ${url}`);

    const body = page.locator('body');
    await expect(body, 'AC-11：刷新后材质 AgNi10 应回填').toContainText('AgNi10');
    await expect(body, 'AC-11：刷新后材质 AgZnO12/Cu 应回填').toContainText('AgZnO12/Cu');
    await expect(body, 'AC-11：刷新后外购件应回填').toContainText('TEST-Q13-CODE');
    // 🚨 占比显示去尾随零
    await expect(page.getByText('70.000000000000'),
      'AC-11：占比必须显示为 70，🚫 不得出现 70.000000000000（F-12 去尾随零）'
    ).toHaveCount(0);
    await shot(page, 'AC-11-刷新后回填');
  });

  /**
   * **AC-19④**（序列，前端半句）：工序换序后命中复用时，前端必须**明示提示**：
   * 「该配置已存在，工序顺序沿用已有产品（Z100 → Z101）」。
   *
   * 🚨 **这条提示不是锦上添花，是裁决的代价补偿**：A0 裁定「工序顺序不进指纹、也不改写已有
   * `unit_price.seq_no`」⇒ 用户调了序但不生效，只能靠这句提示解释。没有它，用户会以为自己调的序生效了。
   * ①②③（复用料号 / 签名 1 条 / seq_no 不被改写）由 `FingerprintReuseAcTest#ac19_*` 覆盖。
   */
  test('AC-19④ 工序换序命中复用时，确认页必须明示「顺序沿用已有产品」', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(240_000);   // 整条向导 + 提交 + 刷新回填，默认 30s 会被中途掐断
    await loginAsAdmin(page);

    // 第一次：Z100 → Z101
    await openSelConfigDrawer(page, 'ac19-1');
    const first = FREE_PRODUCT_NO();
    await fillStep1(page, first);
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');
    await addMaterial(page, '00006', '100');
    await addProcesses(page, ['Z100', 'Z101']);
    await drawer(page).getByRole('button', { name: /确\s*定/ }).last().click();
    await page.waitForTimeout(600);
    await nextStep(page);
    await nextStep(page);
    await submitToQuotation(page, 'AC-19④·第一次');
    await shot(page, 'AC-19-第一次提交Z100-Z101');

    // 第二次：完全相同但工序为 Z101 → Z100
    await page.getByRole('button', { name: /添加产品/ }).first().click();
    await page.waitForTimeout(400);
    await page.locator('text=选配添加').first().click();
    await page.waitForTimeout(800);
    await fillStep1(page, FREE_PRODUCT_NO());
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');
    await addMaterial(page, '00006', '100');
    await addProcesses(page, ['Z101', 'Z100']);
    await drawer(page).getByRole('button', { name: /确\s*定/ }).last().click();
    await page.waitForTimeout(600);
    await nextStep(page);
    await nextStep(page);

    const footerText = await drawer(page).innerText();
    console.log(`[AC-19④] 确认页文案 = ${footerText.slice(0, 600)}`);
    await expect(drawer(page).getByText(/已有相同配置|该配置已存在/),
      'AC-19④：命中复用时必须明示「该配置已存在」（原型 6 状态 B）'
    ).toBeVisible({ timeout: 10000 });
    expect(footerText,
      'AC-19④：提示必须说明「工序顺序沿用已有产品」——否则用户会以为自己调的序生效了'
    ).toMatch(/顺序.*沿用|沿用.*顺序/);
    await shot(page, 'AC-19-4-换序命中复用提示');
  });

  /**
   * **AC-12**：「选配生成料号后，进入『从产品库添加』入口」⇒
   * ①「该产品**出现在列表中**，『客户产品编号』列显示 AC-1 输入的编号（不再为空 —— 本次要修掉的历史问题）」。
   * ②③（`sel_product_no` 落行 / mcm 零新增 / source=CONFIGURED）由接口层用例
   * `CustomerProductNoAcTest#ac12_*` 覆盖。
   */
  test('AC-12 选配产品能在「从产品库添加」列表按客户产品编号找回', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.setTimeout(240_000);   // 整条向导 + 提交 + 刷新回填，默认 30s 会被中途掐断
    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac12');
    const productNo = FREE_PRODUCT_NO();
    await fillStep1(page, productNo);
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');
    await addMaterial(page, '00006', '100');
    await drawer(page).getByRole('button', { name: /确\s*定/ }).last().click();
    await page.waitForTimeout(600);
    await nextStep(page);
    await nextStep(page);
    await submitToQuotation(page, 'AC-12');

    // 打开「从产品库添加」
    await page.getByRole('button', { name: /添加产品/ }).first().click();
    await page.waitForTimeout(400);
    await page.locator('text=从产品库添加').first().click();
    await page.waitForTimeout(1500);

    await expect(drawer(page), 'AC-12①：选配生成的产品应能在产品库列表里按客户产品编号找回')
      .toContainText(productNo, { timeout: 10000 });
    await shot(page, 'AC-12-产品库列表能找回选配产品');
  });
});
