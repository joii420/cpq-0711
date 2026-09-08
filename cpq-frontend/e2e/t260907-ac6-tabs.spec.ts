import { test, expect, Page } from '@playwright/test';
import { waitBackendReady } from './t260907-helpers';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

// 🚩 本仓是 Vite ESM 项目，__dirname 不存在（同 AP-43 家族：ESM 下用 CJS 全局会 ReferenceError）。
//    既有 e2e/fixtures/auth.ts 也是这么取的，照抄它。
const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * T4.2 · AC-6 —— ds 原生模板的 13 个页签逐个点开，每个都渲染出非空行。
 *
 * 判据（需求文档 AC-6，D-34 后是 13 不是 14）：
 *   每个页签 tbody 行数 > 0，且 **= 该销售料号在对应 ds_quote_* 表中的行数**
 *   🚫 空列表 / 0 行 / 「—」/「加载中…」一律判失败
 *
 * 🚩 为什么断言写「= 该料号的行数」而不是「> 0」：
 *    夹具的 8 张费用表是「FG01 两行 / FG02 一行 / 整表三行」，三个数互不相等。
 *    页签若漏了 WHERE material_no 谓词，行数会是 3 而不是 2 —— 写「> 0」就收工的话，
 *    这个坑照样绿。
 *
 * 🚨 建单必须显式传 templateId：
 *    GET /templates/auto-defaults 对正泰返回的是「正泰模板1」(LAST_USED)，不是 ds 原生模板
 *    （后者刚建、从没被用过）。走默认值会拿到别的模板的页签 ——
 *    那既不是绿也不是有意义的红，而且症状长得像「模板配错了」。
 *
 * 环境：本 spec 跑在 worktree 的临时栈上（PW_BASE_URL / PW_BACKEND_URL 指向临时端口），
 *      🚫 不占用主工作区的 5174 / 8081。
 */

const BACKEND = process.env.PW_BACKEND_URL || 'http://localhost:8098';
const TEMPLATE_ID = '875a5c9f-3579-4c9d-b600-1791ff25afa7';   // 报价模板 · ds 原生 v1.1
const FIXTURE = 'T260907-主文件-CUST0004.xlsx';
const P = 'T260907T-';
const FG1 = `${P}FG01`;

/** 13 个页签 → 该销售料号在对应表中的期望行数（夹具构造决定，见 gen_fixtures.py）。 */
const EXPECTED: Record<string, number> = {
  '物料': 1,
  '物料与元素BOM': 3,
  '来料固定加工费': 2,
  '来料其他费用': 2,
  '来料回收折扣': 2,
  '自制加工费': 2,
  '成品其他费用': 2,
  '组成件其他费用': 2,
  '组装加工费': 2,
  '组装加工费年降': 2,
  '电镀费用': 2,
  '来料年降': 2,
  '年降系数': 2,
  // 🚩 「物料BOM」不在这张表里 —— 它是**树页签**，判据不同，单独一段（见下）。
};

/**
 * 第 14 个页签「物料BOM」（COMP-2254）是树页签，判据是**多层**而不是行数：
 *   ✅ 父行与子行都要在   🚫 只出根行判失败
 *
 * 夹具里 FG01 的 BOM 是真两层：
 *   FG01 → RM01 / RM02 / SC01（直接子件），且 RM01 → RM03（孙件）
 * ⇒ 只要 RM03 出现，就证明递归展开到了第二层；只有前三个 = 只展开一层。
 *
 * ⚠️ 已知外部风险（`取数配置器补齐` B-7）：树的递归依赖 `costing_bom_tree_config`，
 *    实测那 2 条配置仍读 V6 裸表 `material_bom_item`，ds_ 独有料号可能捞不到子件。
 *    若只出根行，**如实标「外部阻塞」，不判本任务红**。
 */
const TREE_TAB = '物料BOM';
const TREE_CHILD = `${P}RM01`;      // 直接子件
const TREE_GRANDCHILD = `${P}RM03`; // 孙件 —— 它出现才证明递归到了第二层

function fixturePath() {
  let cur = HERE;
  for (let i = 0; i < 6; i++) {
    const p = path.join(cur, 'dev-docs', 'task-260907-报价导入建单切ds新表', '测试数据', FIXTURE);
    if (fs.existsSync(p)) return p;
    cur = path.dirname(cur);
  }
  throw new Error('找不到测试夹具 ' + FIXTURE);
}

test.describe('T4.2 · AC-6 · ds 原生模板 13 页签渲染非空', () => {
  let session = '';
  let quotationId = '';

  test.beforeAll(async ({ playwright }) => {
    const api = await playwright.request.newContext({ baseURL: BACKEND });

    // 🚩 beforeAll 钩子自带 30s 上限，装不下最长 120s 的就绪等待 —— 必须抬高，
    //    否则表现为「beforeAll hook timeout」，长得像夹具准备逻辑写错了。
    test.setTimeout(300_000);
    await waitBackendReady(api);
    const login = await api.post('/api/cpq/auth/login', {
      data: { username: 'admin', password: 'Admin@2026' },
    });
    expect(login.status(), `登录失败：${await login.text()}`).toBe(200);
    const cookie = login.headers()['set-cookie'] || '';
    session = /CPQ_SESSION=([^;]+)/.exec(cookie)?.[1] || '';
    expect(session, '没拿到 CPQ_SESSION').toBeTruthy();

    const cust = await api.get('/api/cpq/customers?page=0&size=500', {
      headers: { Cookie: `CPQ_SESSION=${session}` },
    });
    const custBody = await cust.json();
    // 实测分页响应形状：data = { content, page, size, totalElements, totalPages }
    // 🚩 page 是 0-based：page=1 拿到的是空的第二页（totalElements=48 全在第 0 页）
    const list: any[] = custBody.data?.content ?? [];
    expect(list.length, `客户列表为空（响应形状变了？）：${JSON.stringify(custBody).slice(0, 300)}`)
      .toBeGreaterThan(0);
    const chint = list.find((c: any) => c.code === 'CUST-0004');
    expect(chint, '库里没有 CUST-0004（正泰），夹具锚点漂了').toBeTruthy();

    // ① 导入夹具
    const imp = await api.post('/api/cpq/dataset/quote/quotation-import', {
      headers: { Cookie: `CPQ_SESSION=${session}` },
      multipart: {
        customerId: chint.id,
        file: {
          name: FIXTURE,
          mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          buffer: fs.readFileSync(fixturePath()),
        },
      },
    });
    expect(imp.status(), `导入失败：${await imp.text()}`).toBe(200);
    const recId = (await imp.json()).data.importRecordId;

    // 轮询到终态
    let status = '';
    for (let i = 0; i < 240 && status !== 'SUCCESS' && status !== 'FAILED'; i++) {
      const r = await api.get(`/api/cpq/dataset/quote/quotation-import/${recId}`, {
        headers: { Cookie: `CPQ_SESSION=${session}` },
      });
      status = (await r.json()).data.status;
      if (status === 'PROCESSING') await new Promise((r) => setTimeout(r, 500));
    }
    expect(status, '夹具导入未成功，后面全是空验证').toBe('SUCCESS');

    // ② 建单 —— 🚨 显式传 templateId，不走 auto-defaults
    const create = await api.post('/api/cpq/dataset/quote/create-quotation', {
      headers: { Cookie: `CPQ_SESSION=${session}` },
      data: {
        importRecordId: recId,
        customerId: chint.id,
        name: 'T260907T-AC6-页签渲染',
        customerTemplateId: TEMPLATE_ID,
      },
    });
    expect(create.status(), `建单失败：${await create.text()}`).toBe(200);
    quotationId = (await create.json()).data.quotationId;
    expect(quotationId).toBeTruthy();

    // 物化卡片值（否则页签会停在「加载中…」）
    await api.post(`/api/cpq/quotations/${quotationId}/ensure-card-values`, {
      headers: { Cookie: `CPQ_SESSION=${session}`, 'Content-Type': 'application/json' },
      data: {},
    });

    // 🚩 绑对模板的自检 —— 绑错了后面 13 个页签断言全是无意义的红
    const q = await api.get(`/api/cpq/quotations/${quotationId}`, {
      headers: { Cookie: `CPQ_SESSION=${session}` },
    });
    const qb = await q.json();
    expect(
      qb.data?.customerTemplateId,
      `建出的单没绑 ds 原生模板（拿到 ${qb.data?.customerTemplateId}）——`
        + ' auto-defaults 对正泰返回的是「正泰模板1」，必须显式传 templateId',
    ).toBe(TEMPLATE_ID);

    await api.dispose();
  });

  // 🚩 spec 自己清自己建的单 —— E2E 建的报价单不带 T260907T- 料号前缀，
  //    后端用例的前缀还原清不到它；不清会每跑一次攒一张（实测攒了 3 张）。
  test.afterAll(async ({ playwright }) => {
    if (!quotationId) return;
    const api = await playwright.request.newContext({ baseURL: BACKEND });
    await api.delete(`/api/cpq/quotations/${quotationId}`, {
      headers: { Cookie: `CPQ_SESSION=${session}` },
    }).catch(() => { /* 清理失败单独报告，🚫 不许顶替用例结论 */ });
    await api.dispose();
  });

  test('13 个页签逐个点开，每个 tbody 行数 = 该料号在对应表中的行数', async ({ page }) => {
    test.setTimeout(180_000);

    await page.context().addCookies([{
      name: 'CPQ_SESSION', value: session, domain: 'localhost', path: '/',
    }]);

    await page.goto(`/quotations/${quotationId}/edit`);
    await page.waitForLoadState('networkidle');

    // 🚩 编辑页落在 5 步向导的 Step 1「选择客户」，13 个页签在 Step 2「添加产品」。
    //    直接找页签会得到「13 个都不存在」—— 那是导航没到位，不是渲染缺陷。
    //    ⚠️ 点 .ant-steps-item 无效（步骤条不可点），必须点「下一步」。
    const next = page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first();
    await expect(next, '找不到「下一步」按钮（antd 会给两字按钮插空格，已用正则规避）').toHaveCount(1);
    await next.click();
    await page.waitForTimeout(6000);

    // 🚩 页签不是 .ant-tabs-tab —— 实测是自定义的 button.qt-tab-btn，挂在 div.qt-product-card 内。
    //    用 antd 类名找会得到 0，长得像「页签没渲染」。
    const cards = page.locator('div.qt-product-card');
    const cardCount = await cards.count();
    expect(cardCount, '产品卡片数为 0 —— Step 2 没到位或建单没建出明细行').toBeGreaterThan(0);

    // 3 个明细行里挑 FG01 的那张卡（FG01 有两条客户料号，任取其一，行数相同）
    let card = cards.first();
    for (let i = 0; i < cardCount; i++) {
      const t = await cards.nth(i).innerText().catch(() => '');
      if (t.includes(FG1)) { card = cards.nth(i); break; }
    }
    const cardText = await card.innerText();
    expect(cardText, `没找到 ${FG1} 的产品卡片（共 ${cardCount} 张）`).toContain(FG1);

    const failures: string[] = [];
    const actual: Record<string, number> = {};

    for (const [tab, expected] of Object.entries(EXPECTED)) {
      const btn = card.locator('button.qt-tab-btn', { hasText: tab }).first();
      if (await btn.count() === 0) {
        failures.push(`页签「${tab}」在产品卡片里不存在`);
        continue;
      }
      await btn.click();
      await page.waitForTimeout(900);

      const paneText = await card.innerText().catch(() => '');
      if (paneText.includes('加载中')) {
        failures.push(`页签「${tab}」停在「加载中…」（AC-6 明确判失败）`);
        continue;
      }
      // antd 横向滚动表格第一行是隐藏测量行，必须排除（E2E方法 §4.6.1b）
      const rows = card.locator('table tbody tr')
        .locator('visible=true')
        .filter({ hasNot: page.locator('.ant-table-measure-row') });
      const n = await rows.count();
      actual[tab] = n;

      if (n === 0) {
        failures.push(`页签「${tab}」tbody 0 行（空列表一律判失败）`);
      } else if (n !== expected) {
        failures.push(
          `页签「${tab}」行数 ${n} ≠ 期望 ${expected}`
          + `（期望值 = ${FG1} 在对应 ds_quote_* 表中的行数；`
          + `若得到整表行数 3，多半是 SQL 漏了 WHERE material_no 谓词）`);
      }
      await page.screenshot({ path: `test-results/t260907-ac6-${tab}.png` }).catch(() => {});
    }

    console.log('各页签实测行数:', JSON.stringify(actual, null, 1));
    expect(failures, `AC-6 不通过：\n  - ${failures.join('\n  - ')}`).toEqual([]);
  });

  /**
   * 第 14 个页签「物料BOM」（树）。
   *
   * 🚦 **标 fixme：已知外部阻塞，不是本任务缺陷。** 归因证据（2026-09-07 实测）：
   *   - `ds_quote_material_bom` 里本套夹具的 BOM 行 **6 行俱在**（FG01 三条 + FG02 两条 + RM01 一条）
   *   - 同一份夹具的**其余 13 个页签全部渲染出非空行** ⇒ 数据与建单链路没问题
   *   - 两条 active 的 `costing_bom_tree_config` 的 `sql_template` 都读 **V6 裸表 `material_bom_item`**
   *   - 而 `material_bom_item` 里本套料号 **0 行**；`v_compat_material_bom_item` 里 **6 行（全覆盖）**
   *   ⇒ 树只出根行，是因为递归 SQL 查的表里根本没有 ds_ 独有料号 ——
   *     归 `取数配置器补齐` 的 **B-7**。把那两条递归改读兼容视图即可解（覆盖率实测 6/6）。
   *
   * 🚩 留着这条而不是删掉：B-7 修好后它会自动转绿，是**回归哨兵**。
   */
  test('T4.2b · 树页签「物料BOM」必须渲染多层（父行 + 子行）', async ({ page }) => {
    test.fixme(true,
      '外部阻塞 B-7：costing_bom_tree_config 的递归 SQL 仍读 V6 裸表 material_bom_item，'
      + 'ds_ 独有料号在该表 0 行（兼容视图 v_compat_material_bom_item 则 6/6 全覆盖）⇒ 只出根行。'
      + '非本任务缺陷，待上游改读兼容视图后本条自动转绿。');

    await page.context().addCookies([{
      name: 'CPQ_SESSION', value: session, domain: 'localhost', path: '/',
    }]);
    await page.goto(`/quotations/${quotationId}/edit`);
    await page.waitForLoadState('networkidle');
    const next = page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first();
    await next.click();
    await page.waitForTimeout(6000);

    const cards = page.locator('div.qt-product-card');
    let card = cards.first();
    for (let i = 0; i < await cards.count(); i++) {
      if (((await cards.nth(i).innerText().catch(() => '')) || '').includes(FG1)) { card = cards.nth(i); break; }
    }
    await card.locator('button.qt-tab-btn', { hasText: TREE_TAB }).first().click();
    await page.waitForTimeout(1500);

    const t = (await card.innerText().catch(() => '')) || '';
    expect(t, `树只出根行：缺直接子件 ${TREE_CHILD}`).toContain(TREE_CHILD);
    expect(t, `树只展开一层：缺孙件 ${TREE_GRANDCHILD}`).toContain(TREE_GRANDCHILD);
  });
});