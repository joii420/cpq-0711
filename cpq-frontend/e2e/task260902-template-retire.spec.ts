/**
 * task-260902 · E2E：**选配模板功能下线（S-9）**
 *
 * 覆盖 **AC-25 / AC-26**。
 *
 * 🚨 AC-26 是**带反向断言**的用例：菜单要藏起来，但**路由/页面/表/数据一个都不许删**。
 * 反向断言就是防「实现时顺手清理」的守卫 —— 少了它，把功能删干净也能让正向断言全绿。
 */
import { test, expect, Page } from '@playwright/test';
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
    // 🚨 这条要走完「建单 → 4 步向导 → 提交 → 落库核对」，实测建单+零件表单就近 30s，
    //    默认 30s 上限会在中途把用例掐断，失败点落在**当时正好在做的那一步**，
    //    看起来像那一步的选择器坏了（本轮已被这个假象骗过一次）。
    test.setTimeout(180_000);

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

    // 🚨 阳性对照：记录提交请求本身。
    //    没有它，「sel_product_no 落 0 行」有两种完全不同的解释 ——
    //    ① 提交真的失败了（产品缺陷）；② 我压根没点到提交（夹具缺陷）。
    //    看库是分不出来的，必须看请求有没有发出去、回了什么。
    const submits: string[] = [];
    page.on('response', async (r) => {
      if (r.url().includes('/configure-product/quotations/') && r.request().method() === 'POST') {
        let body = '';
        try { body = (await r.text()).slice(0, 400); } catch { body = '(读不到 body)'; }
        submits.push(`${r.status()} ${body}`);
      }
    });

    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac25');

    // ③ 不得出现「缺少选配模板」阻断
    await expect(drawer(page).getByText(/缺少选配模板|未配置选配模板|请先配置选配模板/),
      'AC-25③：无模板时🚫 不得出现「缺少选配模板」之类的空态或阻断提示'
    ).toHaveCount(0);
    // ① 对照锚点：先把抽屉里**实际有哪些按钮**打出来再断言。
    //    🚨 锚点不要挑某个具体文案（上一版挑了「取消」，它压根不存在 ⇒ 用例死在锚点上，
    //    而真正要验的「确认加入」一次都没验到）。锚点只需证明「我确实看到了抽屉的按钮区」。
    const drawerButtons = await drawer(page).getByRole('button').allInnerTexts();
    console.log('[AC-25] 抽屉内按钮 =', JSON.stringify(drawerButtons.map(t => t.replace(/\s+/g, ''))));
    expect(drawerButtons.length,
      'AC-25 对照锚点：抽屉里应能看到按钮；一个都看不到 ⇒ 没抓到抽屉，本条结论无效'
    ).toBeGreaterThan(0);
    await shot(page, 'AC-25-无模板抽屉正常渲染');

    // ② 完整走完 4 步并提交成功
    const productNo = `T260902-NOTPL-${Date.now()}`;
    await fillStep1(page, productNo);
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');
    await addMaterial(page, '00006', '100');
    await drawer(page).getByRole('button', { name: /确\s*定/ }).last().click();
    await page.waitForTimeout(600);
    await nextStep(page);      // → 组合工序
    await nextStep(page);      // → 确认并添加

    // 提交前先把现场打出来：按钮清单 + 向导当前停在哪一步
    const btnTexts = (await drawer(page).getByRole('button').allInnerTexts())
      .map((t) => t.replace(/\s+/g, ''));
    console.log('[AC-25①] 第 4 步的按钮 =', JSON.stringify(btnTexts));
    console.log('[AC-25①] 抽屉文本尾部 =',
      (await drawer(page).innerText().catch(() => '')).replace(/\n+/g, ' | ').slice(-400));

    const submit = drawer(page).getByRole('button', { name: /添加到报价单|确认并添加|确认加入/ }).last();
    await expect(submit,
      'AC-25①：无模板时底部必须有「确认加入」按钮（🚫 不再是只剩「取消」的门禁态）'
    ).toBeVisible({ timeout: 10000 });
    await expect(submit, 'AC-25①：该按钮必须可点').toBeEnabled();

    // 🚨 点击与「请求真的发出」绑在一起等：只 click 不等响应，
    //    「点了但没发请求」会一路滑到最后才以「落库 0 行」的面目出现，根因被推远。
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/configure-product/quotations/') && r.request().method() === 'POST',
        { timeout: 25000 },
      ).catch(() => null),
      submit.click(),
    ]);
    if (!resp) {
      const after = (await drawer(page).innerText().catch(() => '(抽屉已关闭)'))
        .replace(/\n+/g, ' | ').slice(0, 600);
      throw new Error(
        '[AC-25 诊断] 点了提交按钮后 25s 内没有观测到 POST /configure-product/quotations/。\n'
        + `点击的按钮文案清单=${JSON.stringify(btnTexts)}\n点击后抽屉现场=${after}`);
    }
    console.log(`[AC-25①] 提交响应 ${resp.status()}`);
    await page.waitForTimeout(2000);
    await shot(page, 'AC-25-无模板提交成功');

    // ② 先看提交请求（阳性对照），再看落库
    console.log(`[AC-25②] 观测到的提交请求 = ${JSON.stringify(submits)}`);
    expect(submits.length,
      'AC-25 阳性对照：整条用例没有观测到任何 POST /configure-product/quotations/ 请求 ⇒ '
      + '说明**提交按钮根本没点到**（夹具问题），此时「落库 0 行」不能解释成产品缺陷'
    ).toBeGreaterThan(0);
    const lastStatus = submits[submits.length - 1].slice(0, 3);
    expect(lastStatus,
      `AC-25②：提交请求应返回 200，实际=${submits[submits.length - 1]}`
    ).toBe('200');

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

    // 🚨 必须先把「配置中心」子菜单真的展开 ——「选配模板管理」本来就挂在它下面，
    //    一级菜单里当然没有。不展开就断言「没找到」= 假绿（主线实测踩过这个坑）。
    const expanded = await expandConfigCenter(page);
    await shot(page, 'AC-26-配置中心子菜单已展开');

    // 🚨 硬阳性对照：看得见两个兄弟项，才证明「观察手段能抓到该子菜单里的条目」。
    //    抓不到就直接 fail 并说明结论无效，🚫 绝不让它静默通过。
    expect(expanded,
      'AC-26 阳性对照失败：「配置中心」子菜单没有展开成功（看不到「组件管理」/「3D 模型配置」）⇒ '
      + '本条关于「选配模板管理不在菜单里」的结论**无效**，不是产品通过。'
      + '请检查 antd 菜单是 inline 还是 popup 模式后重跑'
    ).toBeTruthy();
    const sibling = page.locator('.ant-menu-sub, .ant-layout-sider').filter({ hasText: '组件管理' }).first();
    await expect(sibling, 'AC-26 阳性对照：配置中心子菜单里应能看到「组件管理」').toBeVisible();
    await expect(sibling, 'AC-26 阳性对照：配置中心子菜单里应能看到「3D 模型配置」')
      .toContainText('3D');

    // 正式断言：整页范围内都不许出现该菜单项（子菜单是挂到 body 的浮层，不在 sider 里，
    // 🚫 因此不能只在 .ant-layout-sider 内找；也不能用裸 .ant-menu —— 它会匹配到 6 个而 strict 违规）
    await expect(page.getByText('选配模板管理', { exact: true }),
      'AC-26：「选配模板管理」不得出现在菜单中'
    ).toHaveCount(0);
    await expect(page.locator('a[href*="/config/sel-templates"]'),
      'AC-26：页面上不得有指向 /config/sel-templates 的菜单链接'
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

/**
 * 展开左侧「配置中心」子菜单，并返回**是否真的展开了**。
 *
 * 🚨 为什么要返回布尔而不是直接断言：调用方要把它做成<b>阳性对照</b> ——
 * 展不开时必须让用例以「结论无效」的名义失败，而不是让「没找到选配模板管理」静默通过。
 *
 * ⚠️ antd 菜单两种形态都要覆盖（`cpq-playwright-selector-pitfalls`：这类坑全表现为 timeout）：
 *   · inline 模式：子项渲染在 `.ant-layout-sider` 内，点击标题展开（有动画 + DOM 重排，需重试）
 *   · popup 模式：子项渲染成挂在 body 上的 `.ant-menu-sub` 浮层（id 形如 `rc-menu-uuid-/config-popup`），
 *     要 hover 才出来 —— 主线实测抓到的正是这一种
 */
async function expandConfigCenter(page: Page): Promise<boolean> {
  const visible = async () =>
    (await page.getByText('组件管理', { exact: true }).first().isVisible().catch(() => false));

  const title = page.locator('.ant-menu-submenu-title').filter({ hasText: '配置中心' }).first();
  if (await title.count() === 0) {
    console.log('[AC-26] 找不到「配置中心」子菜单标题');
    return false;
  }

  for (let i = 1; i <= 4; i++) {
    if (await visible()) { console.log(`[AC-26] 子菜单已展开（第 ${i} 轮前）`); return true; }
    // 先试 hover（popup 模式），再试 click（inline 模式）
    await title.hover({ force: true }).catch(() => {});
    await page.waitForTimeout(600);
    if (await visible()) { console.log(`[AC-26] hover 展开成功（第 ${i} 轮）`); return true; }
    await title.click({ force: true, timeout: 3000 }).catch(() => {});
    await page.waitForTimeout(700);
  }
  const ok = await visible();
  console.log(`[AC-26] 展开结果=${ok}`);
  return ok;
}
