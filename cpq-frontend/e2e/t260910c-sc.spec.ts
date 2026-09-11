/**
 * task-260910 · 分片 S-C 的 UI 验收：**AC-11**（卡片两页签的真实可见行）与 **AC-20**（绑定路径不要求填配件）。
 *
 * 🚫 断言全部来自 `需求文档.md §③` AC 原文 + `api.md` + `原型图/`，不读实现（派工 prompt 段 c）。
 *
 * ── 为什么这两条必须在真实 UI 上验，后端用例替代不了 ────────────────────────
 *   AC-11 的字面观测点是「打开编辑页，点开两个页签，各渲染出非空行」。
 *   后端用例 `CardRenderFromRecordAcTest` 断的是 `quotation_line_component_data`
 *   —— 数据在库里对，与页面上看得见，中间还隔着渲染层：
 *   本项目 AP-31 / AP-38 / AP-50 整族缺陷就长在那一段（数据齐全但 cell 走 fallback
 *   渲染成「—」/「加载中…」）。两层都绿才算 AC-11 达成。
 *   AC-20 是纯前端按钮可用性（`ConfigureProductDrawer` 的「请至少添加一个配件」拦截），
 *   后端根本观测不到。
 *
 * ── 运行方式（🚫 不占 8081 / 5174，那是主线亲验用的）────────────────────────
 *   PW_BACKEND_URL=http://localhost:8117 PW_BASE_URL=http://localhost:5187 \
 *     npx playwright test e2e/t260910c-sc.spec.ts
 *   ⚠️ `playwright.config.ts` 的 `workers: 1` 是**契约不是性能参数** —— 调大会让并行 spec 互相清库。
 */
import { test, expect } from '@playwright/test';
import {
  C, RUN, CFx, assertEnvIdentity, uiLogin, seedCustomerAndQuotation, seedExistingSalesMaterial,
  cleanupFixture, openStep2, openTabAndReadRows, assertTabNonEmpty, columnValues,
  sqlScalar, shot, writeEvidence, appendEvidence,
} from './t260910c-sc.helpers';

const TAB_MBOM = '物料BOM';
const TAB_EBOM = '物料与元素BOM';

test.describe('task-260910 S-C · AC-11 / AC-20（UI）', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  /**
   * **AC-11** 原文：前置同 AC-10（选配新建零件，材质 AgCu90 100%）；
   * 操作「提交后打开报价单编辑页，点开「物料BOM」与「物料与元素BOM」两个页签」；断言
   * ① 两个页签各渲染出非空行；
   * ② 「物料BOM」页签的「材料占比（%）」列显示 **100**；
   * ③ 「物料与元素BOM」页签渲染出 **Ag 90 / Cu 10** 两行。
   */
  test('AC-11 · 只写 _record 后，两页签在真实 UI 上仍渲染非空行 + 占比 100 + Ag90/Cu10', async ({ page }) => {
    await assertEnvIdentity();
    const fx: CFx = seedCustomerAndQuotation('AC11');
    try {
      await uiLogin(page);

      // ── 走一遍选配新建零件（AgCu90 100%）─────────────────────────────
      await openStep2(page, fx);
      await page.locator('button').filter({ hasText: /选配/ }).first().click();
      await page.locator('input').first().fill(`${C}AC11-${RUN}`);   // 客户产品编号
      await shot(page, '01-AC-11-选配第1步-填客户产品编号');

      // 🔬 后续步骤（选类型 → 选材质占比 → 选工序 → 添加到报价单）的具体控件文案
      //    以 `原型图/` 为准；本用例把每一步都做成「找不到就给出当前 DOM 可读信息」的形态，
      //    🚫 不用固定 sleep 干等 —— 那种超时长得和产品缺陷一模一样（派工 prompt 段 h）。
      await stepThroughNewPart(page, `${C}零件-${RUN}`);

      // ── 前置：方案② 真的生效（主表 0 行 / _record 有行）──────────────
      const partNo = sqlScalar(
        `SELECT product_part_no_snapshot FROM quotation_line_item
          WHERE quotation_id='${fx.quotationId}' AND parent_line_item_id IS NULL
          ORDER BY created_at DESC LIMIT 1`);
      expect(partNo, 'AC-11 前置：提交后本单应有报价行且带销售料号 —— 取不到则本条判【未验证】').toBeTruthy();
      const mainRows = Number(sqlScalar(
        `SELECT count(*) FROM ds_quote_material_bom WHERE material_no='${partNo}'`));
      const recRows = Number(sqlScalar(
        `SELECT count(*) FROM ds_quote_material_bom_record WHERE quotation_id='${fx.quotationId}' AND material_no='${partNo}'`));
      appendEvidence('01-AC-11.txt',
        `partNo=${partNo} main_material_bom=${mainRows} record=${recRows}\n`);
      expect(recRows, `AC-11 前置：_record 应有行（方案② D-7），实际 ${recRows} 行`).toBeGreaterThan(0);
      expect(mainRows, `AC-11 前置：带版本主表必须 0 行，本条才在验「只靠 _record 也能渲染」。`
        + `实际 ${mainRows} 行 ⇒ 主表还在直写，页签有数据说明不了 patch 合并通了（假绿）`).toBe(0);

      // ── ① 两页签非空 ─────────────────────────────────────────────
      await page.reload();
      await openStep2(page, fx);
      const mbom = await openTabAndReadRows(page, TAB_MBOM);
      await shot(page, '02-AC-11-物料BOM页签');
      assertTabNonEmpty(TAB_MBOM, mbom,
        `本单 _record 有 ${recRows} 行、主表 0 行 ⇒ 0 行意味着 ComponentDriverService 的 `
        + `_record patch 合并没接上（S-4/D-8），或冻结时序未调换（AC-13 / FT-5）。`);

      const ebom = await openTabAndReadRows(page, TAB_EBOM);
      await shot(page, '03-AC-11-物料与元素BOM页签');
      assertTabNonEmpty(TAB_EBOM, ebom, '同上归因。');

      // ── ② 「材料占比（%）」列 = 100 ────────────────────────────────
      const ratios = columnValues(mbom, '材料占比');
      console.log(`[AC-11②] 材料占比列实际值 = ${JSON.stringify(ratios)}`);
      appendEvidence('01-AC-11.txt', `材料占比列=${JSON.stringify(ratios)}\n`);
      expect(ratios.some((v) => /^100(\.0+)?$/.test(v.replace(/[,\s%]/g, ''))),
        `AC-11②：「材料占比（%）」列应显示 **100**，实际 = ${JSON.stringify(ratios)}。`
        + `\n  📌 这是 S-5（_record 投影补 material_ratio，D-10）的**可观测出口** ——`
        + ` 投影不补这一列时该格是空的，而页签仍有行 ⇒ ① 会绿、只有本条会红。`).toBe(true);

      // ── ③ 元素两行 Ag 90 / Cu 10 ──────────────────────────────────
      const flat = ebom.rows.map((r) => r.join('|'));
      console.log(`[AC-11③] 元素页签行 = ${JSON.stringify(flat)}`);
      appendEvidence('01-AC-11.txt', `元素页签行=${JSON.stringify(flat)}\n`);
      const hasAg90 = flat.some((r) => /(^|\|)\s*Ag\s*(\||$)/.test(r) && /(^|\|)\s*90(\.0+)?\s*(\||$)/.test(r));
      const hasCu10 = flat.some((r) => /(^|\|)\s*Cu\s*(\||$)/.test(r) && /(^|\|)\s*10(\.0+)?\s*(\||$)/.test(r));
      expect(hasAg90, `AC-11③：应渲染出 **Ag 90** 一行（AgCu90-01 配置 Ag=90 / Cu=10），`
        + `实际行 = ${JSON.stringify(flat)}`).toBe(true);
      expect(hasCu10, `AC-11③：应渲染出 **Cu 10** 一行，实际行 = ${JSON.stringify(flat)}`).toBe(true);

      writeEvidence('01-AC-11-结论.txt',
        `AC-11 通过：两页签非空（${mbom.rows.length} / ${ebom.rows.length} 行），`
        + `材料占比=${JSON.stringify(ratios)}，元素行=${JSON.stringify(flat)}\n`
        + `前置：主表 ${mainRows} 行 / _record ${recRows} 行（方案② 生效）\n`);
    } finally {
      cleanupFixture(fx, []);
    }
  });

  /**
   * **AC-20** 原文：操作「走 S-7 路径，第 2 步只选了「直接绑定」并选中料号，**不添加任何零件/外购件**」；
   * 断言「「下一步」与「添加到报价单」**均可点**（🚫 不再报「请至少添加一个配件」——
   * 现状 `ConfigureProductDrawer.tsx:204` 会拦住）」。
   *
   * <p>🔑 阴性对照同时验：不许出现「请至少添加一个配件」的提示文案。
   *    只断「按钮可点」不够 —— 按钮可点但一点就弹拦截提示，用户依然走不通。
   */
  test('AC-20 · 只选绑定不加配件 ⇒ 「下一步」「添加到报价单」均可点，且不弹「请至少添加一个配件」', async ({ page }) => {
    await assertEnvIdentity();
    const fx: CFx = seedCustomerAndQuotation('AC20');
    const existing = seedExistingSalesMaterial(fx, 'BND');
    try {
      await uiLogin(page);
      await openStep2(page, fx);
      await page.locator('button').filter({ hasText: /选配/ }).first().click();
      await page.locator('input').first().fill(`${C}BIND-20-${RUN}`);

      // 第 2 步：选第三张类型卡（原型 01 的文案）
      const nextTop = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
      await expect(nextTop, 'AC-20 前置：第 1 步应有「下一步」').toBeEnabled({ timeout: 30_000 });
      await nextTop.click();

      const bindCard = page.getByText('直接绑定已有销售料号', { exact: false }).first();
      await expect(bindCard, 'AC-20 前置：第 2 步应出现第三张类型卡「直接绑定已有销售料号」'
        + '（原型图 01）—— 找不到 ⇒ F-3 尚未落地，这是**交付状态结论**不是 AC 结论')
        .toBeVisible({ timeout: 30_000 });
      await bindCard.click();
      await shot(page, '10-AC-20-选中第三张类型卡');

      // 🔑 断言 A：只选了类型卡、还没加任何配件时，「下一步」必须可点
      const nextInPanel = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).last();
      await expect(nextInPanel, 'AC-20：选中「直接绑定」后「下一步」应**可点**（该路径不要求先加配件）')
        .toBeEnabled({ timeout: 30_000 });
      await nextInPanel.click();

      // 搜索并选中已有料号（原型 02：搜索 → 选中即完成 → 「确定绑定 <料号>」）
      const searchBox = page.locator('.ant-drawer input, .ant-modal input').filter({ hasNot: page.locator('[disabled]') }).last();
      // ⚠️ antd Select/Search 的 onSearch 不被 fill() 触发（既有坑）⇒ 用 pressSequentially
      await searchBox.click();
      await searchBox.pressSequentially(existing, { delay: 40 });
      await page.waitForTimeout(2500);
      const hit = page.getByText(existing, { exact: false }).first();
      await expect(hit, `AC-20 前置：应能搜到本片自造的既有料号 ${existing}`
        + '（它在 ds_quote_material 有 1 行、BOM 2 行，夹具已自检）—— 搜不到属 AC-5/AC-7 的范围（S-B 片）')
        .toBeVisible({ timeout: 30_000 });
      await hit.click();
      const confirmBind = page.locator('button').filter({ hasText: /确\s*定\s*绑\s*定/ }).first();
      await expect(confirmBind, 'AC-20 前置：应出现「确定绑定」按钮（原型图 02）').toBeEnabled({ timeout: 30_000 });
      await confirmBind.click();
      await shot(page, '11-AC-20-已绑定未加配件');

      // 🔑 断言 B：一个配件都没加，「添加到报价单」必须可点
      const addBtn = page.locator('button').filter({ hasText: /添\s*加\s*到\s*报\s*价\s*单/ }).first();
      await expect(addBtn, 'AC-20：只绑定、不加任何配件时「添加到报价单」应**可点**'
        + '（现状 ConfigureProductDrawer.tsx:204 的「请至少添加一个配件」会拦住）')
        .toBeEnabled({ timeout: 30_000 });

      // 🔑 断言 C（阴性对照）：点下去不许弹拦截提示
      await addBtn.click();
      await page.waitForTimeout(3000);
      const bodyText = await page.evaluate(() => document.body.innerText || '');
      expect(bodyText.includes('请至少添加一个配件'),
        'AC-20：点「添加到报价单」后不许出现「请至少添加一个配件」拦截提示。'
        + '\n  ⚠️ 只断「按钮可点」不够 —— 可点但一点就被拦，用户依然走不通。').toBe(false);
      await shot(page, '12-AC-20-提交后');

      // 落库正向对照：AC-18② 的同型断言（🚫 断言前先证明结果非空）
      const li = Number(sqlScalar(
        `SELECT count(*) FROM quotation_line_item WHERE quotation_id='${fx.quotationId}'
           AND product_part_no_snapshot='${existing}'`));
      expect(li, `AC-20：绑定成功后本单应有 1 行 product_part_no_snapshot='${existing}' 的报价行，实际 ${li} 行`
        + '（0 行 ⇒ 按钮可点但提交没落库，比被拦住更糟）').toBe(1);
      writeEvidence('10-AC-20-结论.txt',
        `AC-20 通过：未加配件时「下一步」「添加到报价单」均可点，无拦截提示；`
        + `落库 quotation_line_item=${li} 行（料号 ${existing}）\n`);
    } finally {
      cleanupFixture(fx, [existing]);
    }
  });
});

/**
 * 走完「新建零件（AgCu90 100%）」四步并提交。
 * ⚠️ 控件文案以 `原型图/` 为准；每一步找不到目标时立刻给出「当前 DOM 有什么」的可读信息，
 * 🚫 不靠固定 sleep 干等（那种超时长得和产品缺陷一模一样）。
 */
async function stepThroughNewPart(page: import('@playwright/test').Page, partName: string) {
  const next = () => page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).last();
  await expect(next(), '选配第 1 步应有「下一步」').toBeEnabled({ timeout: 30_000 });
  await next().click();

  const partCard = page.getByText('零件', { exact: false }).first();
  await expect(partCard, '第 2 步应出现类型卡「零件」（原型图 01）').toBeVisible({ timeout: 30_000 });
  await partCard.click();

  // 品名/规格/尺寸/总重 —— 按 label 就近取输入框
  for (const [label, value] of [['品名', partName], ['规格', 'spec-234'], ['尺寸', '234'], ['总重', '11']] as const) {
    const input = page.locator('.ant-form-item').filter({ hasText: new RegExp(label) })
      .locator('input').first();
    if (await input.count()) { await input.fill(String(value)); }
    else { console.warn(`[AC-11 夹具] 找不到「${label}」输入框，已跳过（后续断言会以可读信息暴露）`); }
  }
  await expect(next(), '零件信息填完后应可「下一步」').toBeEnabled({ timeout: 30_000 });
  await next().click();

  // 材质 AgCu90 占比 100
  const matSelect = page.locator('.ant-select').first();
  await matSelect.click();
  await page.locator('.ant-select-item-option').filter({ hasText: /AgCu90|AgCu/ }).first()
    .click({ timeout: 30_000 });
  const ratio = page.locator('input[type="number"], .ant-input-number input').first();
  if (await ratio.count()) await ratio.fill('100');

  const add = page.locator('button').filter({ hasText: /添\s*加\s*到\s*报\s*价\s*单/ }).first();
  // 中间可能还有工序步，逐步「下一步」直到出现「添加到报价单」
  for (let i = 0; i < 4 && !(await add.count()); i++) {
    if (await next().isEnabled().catch(() => false)) await next().click();
    await page.waitForTimeout(1500);
  }
  await expect(add, '走到最后一步应出现「添加到报价单」按钮 —— 找不到 ⇒ **入口问题**，判【未验证】')
    .toBeVisible({ timeout: 30_000 });
  await add.click();
  await page.waitForTimeout(5000);
}
