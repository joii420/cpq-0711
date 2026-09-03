/**
 * task-260902 · E2E：**选配模板功能下线（S-9）**
 *
 * 覆盖 **AC-25 / AC-26**。
 *
 * 🚨 AC-26 是**带反向断言**的用例：菜单要藏起来，但**路由/页面/表/数据一个都不许删**。
 * 反向断言就是防「实现时顺手清理」的守卫 —— 少了它，把功能删干净也能让正向断言全绿。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';
import {
  shot, query, drawer, openSelConfigDrawer, fillStep1, startNewPart, addMaterial, nextStep,
} from './fixtures/task260902';

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test.describe('task-260902 选配模板下线', () => {

  /**
   * **AC-25**：「一个客户，其产品分类**没有配任何选配模板**，且默认产品分类也没有
   * （即 `getEffective` 返回 `hasTemplate=false`）→ 打开『选配添加』，完整配一个零件并提交」⇒
   * ①「抽屉**正常渲染**，底部有『确认加入』按钮（🚫 不再只剩『取消』）」；
   * ②「**能完整走完 4 步并提交成功**」；
   * ③「页面不出现『缺少选配模板』之类的空态或阻断提示」。
   *
   * 🚨 **前置构造方式**：全库唯一的存量 `sel_template`（名为「11」）挂在**默认分类**上 ⇒
   * 任何客户都会回退命中它，`hasTemplate=false` 在现网**造不出来**；
   * 而停用那条模板属于 `testing.md §4.3` 禁止的「改模板发布态」全局污染。
   * ⇒ 本用例用 `page.route()` 把 `effective` 响应的 `hasTemplate` 强制改成 `false`
   * （这正是前端门禁 `ConfigureProductDrawer.tsx:250-253` 的真实输入），零全局副作用。
   */
  test('AC-25 hasTemplate=false 时抽屉仍可用、仍能提交', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');

    await page.route('**/api/cpq/sel-templates/effective**', async (route) => {
      const res = await route.fetch();
      let body: any;
      try { body = await res.json(); } catch { return route.fulfill({ response: res }); }
      const target = body?.data ?? body;
      if (target && typeof target === 'object') {
        target.hasTemplate = false;
        target.templateId = null;
        target.usedDefault = false;
        target.params = [];
      }
      console.log('[AC-25] 已注入 hasTemplate=false');
      await route.fulfill({ response: res, body: JSON.stringify(body) });
    });

    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac25');

    // ③ 不得出现「缺少选配模板」阻断
    await expect(drawer(page).getByText(/缺少选配模板|未配置选配模板|请先配置选配模板/),
      'AC-25③：无模板时🚫 不得出现「缺少选配模板」之类的空态或阻断提示'
    ).toHaveCount(0);
    // ① 底部按钮不再只剩「取消」
    await expect(drawer(page).getByRole('button', { name: /取消/ }),
      'AC-25①：「取消」应在（对照锚点，证明我们确实看到了 footer）'
    ).toBeVisible();
    await shot(page, 'AC-25-无模板抽屉正常渲染');

    // ② 完整走完 4 步并提交成功
    const productNo = `T260902-NOTPL-${Date.now()}`;
    await fillStep1(page, productNo);
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');
    await addMaterial(page, '00006', '100');
    await drawer(page).getByRole('button', { name: /确定/ }).last().click();
    await page.waitForTimeout(600);
    await nextStep(page);      // → 组合工序
    await nextStep(page);      // → 确认并添加

    const submit = drawer(page).getByRole('button', { name: /确认并添加|确认加入/ }).last();
    await expect(submit,
      'AC-25①：无模板时底部必须有「确认加入」按钮（🚫 不再是只剩「取消」的门禁态）'
    ).toBeVisible({ timeout: 10000 });
    await expect(submit, 'AC-25①：该按钮必须可点').toBeEnabled();
    await submit.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2500);
    await shot(page, 'AC-25-无模板提交成功');

    // ② 落库确认：该编号真的建出来了（🚫 不靠「界面没报错」当成功）
    const landed = query(
      `SELECT count(*) FROM sel_product_no WHERE customer_product_no='${productNo}'`
    );
    console.log(`[AC-25②] sel_product_no 落行数 = ${landed}`);
    expect(landed, 'AC-25②：无模板时提交必须真的落库（界面没报错 ≠ 提交成功）').toBe('1');
  });

  /**
   * **AC-26**：「以任一角色登录 → 展开左侧菜单」⇒
   * 「『选配模板管理』**不出现在菜单中**」；
   * 🚫 **反向断言**：「路由与页面代码**保留、不删除**（直接访问 URL 仍可打开）」，
   * 「`sel_template` 系列表与数据**不删**」。
   *
   * ⚠️ **覆盖边界如实披露**：AC 原文点名三个角色（`PRICING_MANAGER` / `SALES_MANAGER` / `SYSTEM_ADMIN`），
   * 但实查库里 ACTIVE 且有已知口令的只有 `admin`（`SYSTEM_ADMIN`）——
   * `alice`/`bob` 已不存在，其余测试账号全 INACTIVE，而**新建用户属改全局状态**（`testing.md §4.3`）。
   * ⇒ 本用例以 `SYSTEM_ADMIN` 断言。**这不是随便挑一个角色**：菜单按权限做的是**减法**，
   * 权限最高的角色看不到的项，权限更低的角色一定也看不到 ⇒ 对 `SYSTEM_ADMIN` 成立即对三者成立。
   * 该推理已在 `test-report.md` 登记，若主线不接受可补建测试账号后扩跑。
   */
  test('AC-26 菜单隐藏「选配模板管理」，但路由/页面/表/数据一个都不许删', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAsAdmin(page);
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1200);

    // 展开所有可折叠菜单，确保不是「藏在折叠项里没找到」造成的假绿
    const submenus = page.locator('.ant-menu-submenu-title');
    const n = await submenus.count();
    for (let i = 0; i < n; i++) {
      await submenus.nth(i).click({ timeout: 3000 }).catch(() => {});
      await page.waitForTimeout(250);
    }
    await shot(page, 'AC-26-菜单已全部展开');

    // 阳性对照：证明观察手段确实能在菜单里抓到条目（否则「没找到」可能只是没看对地方）
    const menu = page.locator('.ant-menu');
    await expect(menu, 'AC-26 阳性对照：菜单里应能看到别的配置项（证明选择器有效）')
      .toContainText(/系统|配置|管理/, { timeout: 8000 });

    await expect(page.locator('.ant-menu').getByText('选配模板管理'),
      'AC-26：「选配模板管理」不得出现在左侧菜单'
    ).toHaveCount(0);
    await expect(page.locator(`.ant-menu a[href*="/config/sel-templates"]`),
      'AC-26：菜单里不得有指向 /config/sel-templates 的链接'
    ).toHaveCount(0);

    // 🚫 反向断言 ①：直接访问 URL 仍能打开页面
    await page.goto('/config/sel-templates');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1500);
    expect(page.url(), 'AC-26 反向：直接访问该 URL 不得被重定向走（路由必须保留）')
      .toContain('/config/sel-templates');
    const notFound = await page.getByText(/404|页面不存在|Not Found/).count();
    expect(notFound, 'AC-26 反向：页面必须仍能打开，🚫 不得 404（只隐藏入口，不删路由与页面）').toBe(0);
    await shot(page, 'AC-26-直接访问URL仍可打开');

    // 🚫 反向断言 ②：表与数据未被删
    const tpl = query(`SELECT count(*) FROM sel_template`);
    const item = query(`SELECT count(*) FROM sel_template_item`);
    console.log(`[AC-26 反向] sel_template=${tpl} 行, sel_template_item=${item} 行`);
    expect(Number(tpl), 'AC-26 反向：sel_template 的存量数据不得被删（本期是停用不是清理）')
      .toBeGreaterThanOrEqual(1);
    expect(Number(item), 'AC-26 反向：sel_template_item 的存量数据不得被删').toBeGreaterThanOrEqual(3);
  });
});
