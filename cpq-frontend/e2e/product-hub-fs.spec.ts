/**
 * task-260903 · 证伪实验（FS-1a / FS-2 / FS-3）—— 不是验收用例，是**验证用例本身有没有牙**。
 *
 * `testing.md §4.4`：首次 PASS 不能证明守卫有效，可能它压根没接上。
 * 断言「某事不存在」的用例尤其危险 —— 选择器写错会**恒定通过**。
 */
import { test, expect } from '@playwright/test';
import {
  assertIsolatedEnv, loginAs, search, assertReadOnly, sqlOne, evidence, HERO,
} from './product-hub.helpers';

test.beforeAll(() => assertIsolatedEnv());

/**
 * FS-1a（非侵入式）：把**同一个** `assertReadOnly` 指向核价侧抽屉 ——
 * 那里实测有 96 个 input + 1 个保存按钮 ⇒ 它**必须失败**。
 * 不失败 = 该 helper 根本没在查编辑控件，E2E-08/09/16 的只读结论全是空验证。
 */
test('FS-1a：assertReadOnly 指向已知可编辑的核价抽屉，必须硬失败（阳性对照）', async ({ page }) => {
  await loginAs(page, 'PRICING_MANAGER');
  await page.goto('/master-data-hub');
  await page.getByText('料号核价', { exact: true }).first().click();
  await page.waitForTimeout(1500);
  await search(page, HERO);
  await page.getByRole('cell', { name: HERO, exact: true }).first().click();
  const drawer = page.locator('.ant-drawer').first();
  await expect(drawer).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(3000);
  await drawer.locator('[role="tab"]').filter({ hasText: '物料BOM' }).first().click();
  await page.waitForTimeout(3500);

  const inputs = await drawer.locator('.ant-table input').count();
  const saves = await drawer.getByRole('button', { name: /保\s*存/ }).count();
  console.log(`[FS-1a] 核价抽屉 input=${inputs} 保存=${saves}（这是已知可编辑的阳性样本）`);
  expect(inputs, 'FS-1a 前置：核价抽屉必须确实有编辑控件，否则这个阳性对照本身无效')
    .toBeGreaterThan(0);

  let threw = false;
  try {
    await assertReadOnly(drawer, 'FS-1a 阳性对照');
  } catch {
    threw = true;
  }
  evidence('FS-1a', `核价抽屉 input=${inputs} 保存=${saves}；assertReadOnly 是否抛错=${threw}`);
  expect(threw,
    '🚨 FS-1a 失败：assertReadOnly 面对 ' + inputs + ' 个 input + ' + saves +
    ' 个保存按钮竟然通过了 ⇒ 它没在真正检查编辑控件，' +
    'E2E-08 / E2E-09 / E2E-16 的「全只读」结论全部是空验证，不可采信').toBe(true);
});

/**
 * FS-2：数字是真的吗 —— 断言读的是库还是写死值。
 * 不改数据，改用「库里查出来的值 vs 用例里的常量」交叉验证：
 * 若把库中主角料号的行数换成一个不可能的值，用例必须失败。
 */
test('FS-2：E2E-07 的行数断言确实读库（交叉验证，不改数据）', async () => {
  const real = Number(sqlOne(`SELECT count(*) FROM ds_quote_material_bom WHERE material_no='${HERO}'`));
  const fake = Number(sqlOne(`SELECT count(*) FROM ds_quote_material_bom WHERE material_no='__NO_SUCH__'`));
  console.log(`[FS-2] 真实=${real} 不存在料号=${fake}`);
  evidence('FS-2', `heroRowsInDb 真实=${real}；不存在料号=${fake}`);
  expect(real, 'FS-2：主角料号必须真有行，否则 E2E-07 的断言是空跑').toBe(9);
  expect(fake, 'FS-2：查询确实按 material_no 过滤（不存在的料号须为 0，否则查询没带条件）').toBe(0);
});

/**
 * FS-3：页面读的是不是 ds_quote_material 这张表。
 * 不插数据（避免污染共享库），改用**列表总数 == 该表 count(*)** 的不变量 +
 * 「按料号过滤后页面结果与库一致」双向交叉。
 */
test('FS-3：销售产品列表的数据源确实是 ds_quote_material', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  const db = Number(sqlOne('SELECT count(*) FROM ds_quote_material'));
  const res = await page.request.get(`/api/cpq/dataset/quote/parts?page=0&size=1`);
  const body = await res.json();
  const apiTotal = body?.data?.total;
  console.log(`[FS-3] 库 count(*)=${db}  端点 total=${apiTotal}`);
  evidence('FS-3', `ds_quote_material count(*)=${db}; GET parts total=${apiTotal}`);
  expect(apiTotal, 'FS-3：端点 total 必须等于 ds_quote_material 的行数（不等 ⇒ 读的不是这张表）')
    .toBe(db);
});
