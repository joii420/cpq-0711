/**
 * task-260907 第二段 · E2E —— 核价通过确认抽屉
 *
 * 服务的 AC（原文在 `dev-docs/task-260907-报价导入建单切ds新表/task-260907-record层与核价回填/需求文档.md`）：
 *   - AC-5  ① 抽屉形态（placement=right / width=1200，🚫 不是 Modal）+ ③ 汇总条五项 + ④「本次不动」必须渲染
 *   - AC-14 ③ 判定为 UNCHANGED 的组仍出现在表格里（🚫 不许过滤）+ 顶部提示条文案
 *   - AC-20 ③ 「对不上的行」明细区必须直出（🚫 不许折叠进「更多」）+ 主按钮变红、文案「仍然确认并核价通过」
 *
 * ─────────────────────────────────────────────────────────────────
 * 🚫 全程只读：本 spec **绝不点「确认并核价通过」**。
 *    共享 dev 库上点一次确认 = 真的把基础资料升版了，那是不可逆的（CLAUDE.md §3.2 环境销毁）。
 *    ⇒ 预览响应一律用 page.route 拦截构造，只验**渲染**，不验后端行为（后端行为归 Java 契约测试）。
 *
 * 🚫 不改变共享库全局状态：不改用户启停用 / 角色 / 模板发布态。
 *    （testing.md §4.3 实证：E2E 反复跑把 admin 置成 INACTIVE，之后所有用例连同真人操作一起坏，
 *      而症状看起来非常像业务回归。）
 *
 * ⚠️ workers=1 是契约不是性能参数（playwright.config.ts 注释）—— 🚫 不要调大。
 * ⚠️ channel:'chrome' 必须走系统 Chrome：Ubuntu 26.04 装不上自带 chromium，
 *    缺它时每个用例都倒在 "Executable doesn't exist"，那种失败**长得和业务回归一模一样**
 *    但一个断言都没执行 —— 是最隐蔽的一类假绿。
 *
 * ⛔ 执行前置：前端 F-x 落地 + 一张 SUBMITTED 的新链路报价单可进入核价通过入口。
 */
import { test, expect, Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

/** 🚨 本任务统一夹具前缀（test.md §二）。收尾核对按它查；查出 0 条 = 查错了前缀，不是「很干净」。 */
const PREFIX = 'T260907R-';

/** AC-5③ 汇总条五项，与 `原型图/01-核价通过确认抽屉-默认态.html` 逐字一致（实测提取）。 */
const SUMMARY_LABELS = ['涉及表', '涉及料号组', '将升版', '无变更', '无法对齐的行'];

/** AC-5② 四条列级不变量涉及的列名（原型实测原文）。 */
const COL_UNTOUCHED = '本次不动';
const COL_PATCHED_COLS = '本次覆盖的列';
const COL_PRESERVED_COLS = '原样保留的列';
const COL_UNANCHORED = '对不上';
/** 「展示料号组的表格」的判别列 —— 每一屏的组表都有它，明细表/字段表都没有。 */
const COL_RESULT = '判定';

/**
 * AC-5② 的判据（2026-09-07 重写）。
 *
 * 🕰️ **本条曾自相矛盾，由本轮测试查出后已修**：原文写「**九列**，与 `原型图/01` 的表头逐字一致」，
 * 而实测 `01` 只有 8 列且缺「对不上」，四份原型没有一份等于那 9 列 ⇒ 照字面写必然假红，
 * 而且红的是 AC 不是实现。顺带暴露的设计漏洞：「原样保留的列」（AP-60 **列维度**守卫）
 * 恰好在 `02`/`04` 两屏缺失 —— 那正是出现「对不上的行」、财务最需要看清「哪些列不动」的两屏。
 * 两份原型已补上该列（`02` 8→9 列、`04` 9→10 列）。
 *
 * ⇒ 新判据**不写死列数**，按 AC-5② 的四条不变量断言：
 *   a. 「本次不动」在**每一张展示料号组的表格**里都必须有（AP-60 行维度守卫）
 *   b. 🚨「本次覆盖的列」与「原样保留的列」**必须成对出现** —— 有前者缺后者即不合格（AP-60 列维度守卫）
 *   c. `unanchoredRows > 0` 的屏必须有「对不上」列
 *   d. 判定恒 `UNCHANGED` 的屏可省 b 的两列（此时无意义）—— 这解释了 `03` 的 7 列不是漏画
 *
 * @param screen         该屏对应的原型编号（'01' | '02' | '03'），用于「逐字一致」比对
 * @param expectUnanchored 本屏的 fixture 是否含 unanchoredRows > 0（不变量 c 的前件）
 * @param allUnchanged   本屏的组是否全部判定为 UNCHANGED（不变量 d 的前件）
 */
async function assertAc5ColumnInvariants(
  page: Page,
  drawer: ReturnType<Page['locator']>,
  screen: '01' | '02' | '03',
  expectUnanchored: boolean,
  allUnchanged: boolean,
): Promise<void> {
  // 找出抽屉里所有「展示料号组的表格」= thead 含「判定」列的表
  const headers: string[][] = await drawer.evaluate((root, resultCol) => {
    const out: string[][] = [];
    root.querySelectorAll('table').forEach((t) => {
      const ths = Array.from(t.querySelectorAll('thead th')).map((th) =>
        (th.textContent || '').replace(/\s+/g, ''),
      );
      if (ths.includes(resultCol)) out.push(ths);
    });
    return out;
  }, COL_RESULT);

  // 🚨 空验证守卫：一张组表都没找到时，下面四条不变量会 0 次循环恒真恒通过。
  expect(
    headers.length,
    `🚨 空验证守卫：抽屉里找不到任何「展示料号组的表格」（thead 含「${COL_RESULT}」列的表）⇒ ` +
      'AC-5② 的四条列级不变量会 0 次循环恒真。此刻的绿不构成任何证据。',
  ).toBeGreaterThan(0);

  headers.forEach((cols, i) => {
    const where = `第 ${i + 1} 张组表（表头=${cols.join(' / ')}）`;

    // ── a. 「本次不动」必须有（AP-60 行维度守卫）
    expect(
      cols,
      `AC-5②a / AP-60：${where} 缺「${COL_UNTOUCHED}」列。` +
        '财务要能看见「这一组有 N 行本次不动」，否则「不写 = 删除」这类后果永远不在她的视野里 ——' +
        'repair-0727 的真实事故正是预览显示「0 变更」而执行删了 3 行。',
    ).toContain(COL_UNTOUCHED);

    const hasPatchedCols = cols.includes(COL_PATCHED_COLS);
    const hasPreservedCols = cols.includes(COL_PRESERVED_COLS);

    // ── b. 🚨 两列必须成对（AP-60 列维度守卫）
    if (hasPatchedCols) {
      expect(
        hasPreservedCols,
        `AC-5②b / AP-60 列维度：${where} 有「${COL_PATCHED_COLS}」却缺「${COL_PRESERVED_COLS}」——` +
          '出现前者而缺后者即不合格。缺了财务就看不见「哪些列原样保留」，' +
          'AP-60 实证：element_bom_item.base_qty 由 0.624610 变 NULL 而预览显示 0 变更。',
      ).toBe(true);
    }

    // ── d. 判定恒 UNCHANGED 的屏可省 b 的两列；反之两列都必须在
    if (!allUnchanged) {
      expect(
        hasPatchedCols && hasPreservedCols,
        `AC-5②b+d：${where} 存在非 UNCHANGED 的组，因此「${COL_PATCHED_COLS}」与「${COL_PRESERVED_COLS}」` +
          `两列都必须渲染（只有「判定恒 UNCHANGED 的屏」才可省，那时它们无意义）。` +
          `实际 有覆盖列=${hasPatchedCols} 有保留列=${hasPreservedCols}`,
      ).toBe(true);
    }

    // ── c. unanchoredRows > 0 的屏必须有「对不上」列
    if (expectUnanchored) {
      expect(
        cols,
        `AC-5②c：本屏 unanchoredRows > 0，${where} 必须有「${COL_UNANCHORED}」列，` +
          '否则财务不知道有几行没对上。',
      ).toContain(COL_UNANCHORED);
    }
  });

  // ── AC-5② 前半：「列名与顺序与**该屏对应的**原型表头逐字一致」
  //    🔑 原型表头在测试时**从原型 HTML 现读**，🚫 不在本文件里抄一份 ——
  //       抄一份就等于把判据从「与原型一致」偷换成「与我抄的那份一致」，原型改了也不会红。
  const expected = readPrototypeGroupTableHeader(screen);
  expect(
    expected.length,
    `🚨 阳性对照失败：从原型 ${screen} 里没解析出组表表头 ⇒ 下面的「逐字一致」比对会空跑。` +
      '要么原型路径错了，要么解析写坏了。',
  ).toBeGreaterThan(0);
  expect(
    headers[0],
    `AC-5②：抽屉组表的列名与顺序应与原型 ${screen} 的表头逐字一致。\n` +
      `  原型 ${screen}：${expected.join(' / ')}\n` +
      `  实际渲染　：${headers[0].join(' / ')}`,
  ).toEqual(expected);
}

/**
 * 从原型 HTML 里读「展示料号组的表格」的表头（含「判定」列的那个 thead）。
 *
 * ⚠️ 原型是任务文档的一部分（`原型图/`），读它是允许的；读的是**需求侧素材**，不是实现代码。
 */
function readPrototypeGroupTableHeader(screen: '01' | '02' | '03'): string[] {
  const fs = require('fs') as typeof import('fs');
  const path = require('path') as typeof import('path');
  const dir = path.resolve(
    __dirname,
    '../../dev-docs/task-260907-报价导入建单切ds新表/task-260907-record层与核价回填/原型图',
  );
  const file = fs.readdirSync(dir).find((f) => f.startsWith(`${screen}-`) && f.endsWith('.html'));
  if (!file) throw new Error(`原型 ${screen} 找不到，目录=${dir}`);
  const html = fs.readFileSync(path.join(dir, file), 'utf-8');
  for (const m of html.matchAll(/<thead>([\s\S]*?)<\/thead>/g)) {
    const cols = Array.from(m[1].matchAll(/<th[^>]*>([\s\S]*?)<\/th>/g)).map((x) =>
      x[1].replace(/<[^>]+>/g, '').replace(/\s+/g, ''),
    );
    if (cols.includes(COL_RESULT)) return cols;
  }
  return [];
}

test.describe('task-260907R · 核价通过确认抽屉', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('T-05 · AC-5 抽屉形态 + 汇总条五项 +「本次不动」必须渲染', async ({ page }) => {
    await stubPreview(page, previewFixture({ withUnanchored: false, unchangedOnly: false }));
    await openCostingApproveDrawer(page);

    const drawer = page.locator('.ant-drawer').filter({ hasText: '核价通过' });
    await expect(drawer, 'AC-5①：应弹出抽屉（🚫 不是 Modal）').toBeVisible();

    // AC-5①：placement=right / width=1200
    await expect(
      page.locator('.ant-drawer-right'),
      'AC-5①：抽屉 placement 应为 right（原型 01 的形态）',
    ).toBeVisible();
    // ⚠️ 2026-09-07 修：原来用 `.ant-drawer-content` —— 项目是 antd ^6.3.5，**该 class 已不存在**，
    //    boundingBox() 返 null ⇒ 这条断言必红，而且**红的是选择器不是实现**。
    //    那正是最坏的一类假红：读报告的人会去查产品代码，查不出问题。
    const box = await drawer.locator('.ant-drawer-content-wrapper').boundingBox();
    expect(box, 'AC-5①：拿不到抽屉尺寸 ⇒ 断言会空跑').not.toBeNull();
    expect(
      Math.round(box!.width),
      `AC-5①：抽屉 width 应为 1200，实际 ${box!.width}`,
    ).toBe(1200);
    // 🚫 反向：不许是 Modal
    await expect(
      page.locator('.ant-modal-wrap:visible'),
      'AC-5①：🚫 不许用 Modal 承载确认界面（AC 原文明写）',
    ).toHaveCount(0);

    // AC-5③：汇总条五项
    for (const label of SUMMARY_LABELS) {
      await expect(
        drawer.getByText(label, { exact: false }),
        `AC-5③：汇总条缺「${label}」（与原型 01 逐字一致）`,
      ).toBeVisible();
    }

    // 🚨 AC-5④：「本次不动」必须渲染 —— AP-60 判据四的直接产物。
    //    预览只描述「哪些值变了」会漏掉「不写 = 删除」这一整类后果；
    //    repair-0727 的真实事故正是预览显示「0 变更」而执行删了 3 行。
    await expect(
      drawer.getByText('本次不动', { exact: false }),
      'AC-5④ / AP-60：「本次不动」列必须渲染，财务要能看见「这一组有 N 行本次不动」',
    ).toBeVisible();
    // 阳性对照：不仅表头在，值也要出得来（表头在而恒空同样等于没渲染）
    const untouchedCells = drawer.locator('[data-testid="ds-backfill-untouched-rows"]');
    await expect(
      untouchedCells.first(),
      'AC-5④：「本次不动」应有实际取值，只有表头没有值等于没渲染',
    ).toBeVisible();
    await expect(untouchedCells.first()).not.toHaveText('');

    // AC-5②：四条列级不变量 + 与原型 01 逐字一致
    //   本屏 = 默认态：无对不上的行；含 UPGRADED 组 ⇒ 不变量 b 的两列都必须在
    await assertAc5ColumnInvariants(page, drawer, '01', /*expectUnanchored*/ false, /*allUnchanged*/ false);
  });

  test('T-14 · AC-14 零变更：UNCHANGED 组仍列出，且提示条文案与原型 03 一致', async ({ page }) => {
    await stubPreview(page, previewFixture({ withUnanchored: false, unchangedOnly: true }));
    await openCostingApproveDrawer(page);

    const drawer = page.locator('.ant-drawer').filter({ hasText: '核价通过' });
    await expect(drawer).toBeVisible();

    // AC-14③：顶部提示条文案与 `原型图/03-核价通过确认抽屉-零变更与空态.html` 上半屏一致
    await expect(
      drawer.getByText('本单不会改动任何基础数据。', { exact: false }),
      'AC-14③：零变更时顶部提示条文案应为「本单不会改动任何基础数据。」（原型 03 实测原文）',
    ).toBeVisible();

    // 🔑 AC-14③：UNCHANGED 的组**仍出现在表格里**，🚫 不许过滤掉
    //    理由：过滤掉之后，财务分不清「这张表没变」与「这张表根本没被算进去」。
    const rows = drawer.locator('[data-testid="ds-backfill-group-row"]');
    const rowCount = await rows.count();
    expect(
      rowCount,
      '🚨 空验证守卫：抽屉里一个料号组都没渲染 ⇒ 下面「UNCHANGED 组仍在」的断言会空跑',
    ).toBeGreaterThan(0);
    // ⚠️ 2026-09-07 修：原来用 `hasText: 'UNCHANGED'` —— **界面根本不渲染这个词**。
    //    独立核实（查的是原型图这份设计契约，不是实现代码）：四份原型里
    //    「无变更」出现 9 次 / 「将升版」6 次，而 `UNCHANGED` / `UPGRADED` **出现 0 次**。
    //    ⇒ 那个匹配永远命中不了，用例会以「组没渲染」的面目失败，而实际是我匹配错了。
    //    改用 data-result 属性选择器：它是语义钩子，不随文案与语言变化。
    const unchangedRow = rows.filter({ has: page.locator('[data-result="UNCHANGED"]') }).first();
    await expect(
      unchangedRow,
      'AC-14③：判定为 UNCHANGED 的组必须仍出现在表格里（带「本次覆盖 = 0」），🚫 不许过滤掉',
    ).toBeVisible();

    // 🚨 阳性对照：属性钩子在 ≠ 用户看得见。属性若还在、中文却没渲染出来，
    //    上面那条照样绿，而财务在界面上什么也看不到。两侧都断言才算验到。
    await expect(
      unchangedRow,
      'AC-14③：判定列应向用户渲染中文「无变更」（原型实测原文），而不只是挂着 data-result 属性',
    ).toContainText('无变更');

    await expect(
      unchangedRow.locator('[data-testid="ds-backfill-patched-rows"]'),
      'AC-14③：UNCHANGED 的组应显示「本次覆盖 = 0」',
    ).toHaveText('0');

    // AC-5②d：本屏判定恒 UNCHANGED ⇒ 允许省「本次覆盖的列 / 原样保留的列」两列
    //   （这解释了原型 03 的 7 列不是漏画）。但不变量 a 的「本次不动」仍必须在。
    await assertAc5ColumnInvariants(page, drawer, '03', /*expectUnanchored*/ false, /*allUnchanged*/ true);
  });

  test('T-20c③ · AC-20 锚不上：明细区直出不折叠，主按钮变红且文案为「仍然确认并核价通过」', async ({ page }) => {
    await stubPreview(page, previewFixture({ withUnanchored: true, unchangedOnly: false }));
    await openCostingApproveDrawer(page);

    const drawer = page.locator('.ant-drawer').filter({ hasText: '核价通过' });
    await expect(drawer).toBeVisible();

    // AC-20③①：「对不上的行」明细区出现并列出该行
    const panel = drawer.locator('[data-testid="ds-backfill-unanchored-panel"]');
    await expect(
      panel,
      'AC-20③①：unanchoredRows 非空时必须出现「对不上的行」明细区',
    ).toBeVisible();
    // 🚫 不许折叠进「更多」—— 判据：明细区在**不做任何点击**的情况下就可见，
    //    且它不在一个收起态的折叠面板里。
    await expect(
      panel.locator('.ant-collapse-item:not(.ant-collapse-item-active)'),
      'AC-20③①：🚫「对不上的行」不许折叠进「更多」里，必须直出',
    ).toHaveCount(0);
    // 明细区必须真的列出了那一行（原型 02 的 displayValues：项次 / 投入料号）
    await expect(
      panel.getByText('S-1630010773', { exact: false }),
      'AC-20③①：明细区应列出对不上的那一行的可辨识信息（displayValues），否则财务看不出是哪一行',
    ).toBeVisible();

    // AC-20③①：主按钮变红 + 文案（原型 02 实测原文 `btn btn-danger` / 「仍然确认并核价通过」）
    const primary = drawer.getByRole('button', { name: '仍然确认并核价通过' });
    await expect(
      primary,
      'AC-20③①：有对不上的行时主按钮文案应为「仍然确认并核价通过」（原型 02 实测原文）',
    ).toBeVisible();
    await expect(
      primary,
      'AC-20③①：主按钮应为危险态（红）—— antd 对应 .ant-btn-dangerous',
    ).toHaveClass(/ant-btn-dangerous/);

    // AC-5②c：本屏 unanchoredRows > 0 ⇒ 组表必须有「对不上」列；
    //   且本屏含 UPGRADED 组 ⇒ 不变量 b 的两列都必须在。
    //   🕰️ 「原样保留的列」正是 2026-09-07 在原型 02 上补回来的那一列 —— 本条守着它别再掉。
    await assertAc5ColumnInvariants(page, drawer, '02', /*expectUnanchored*/ true, /*allUnchanged*/ false);

    // 🚫 到此为止：**绝不点这个按钮**。点一次就在共享 dev 库上真的升版了（§3.2 不可逆）。
  });
});

// ═══════════════════════ 夹具 ═══════════════════════

/**
 * 拦截预览端点，返回构造好的 `dsBackfill`（形状照 `api.md §1`）。
 *
 * 🚫 只读：拦截让本 spec 完全不依赖库里有没有合适的数据，也保证一个字节都不写。
 * ⚠️ 代价：它验的是**渲染**，不是后端算得对不对。后端侧归 Java 契约测试
 *    （`BackfillPreviewContractTest`），两边不可互相替代 —— 报告里必须分开写。
 */
async function stubPreview(page: Page, body: unknown) {
  await page.route('**/api/cpq/quotations/*/costing-approve/preview', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', data: body }),
    });
  });
  // 🚨 阳性对照：确认拦截真的挂上了。拦截没生效时页面会打真实后端，
  //    渲染出来的东西看着也「正常」，于是断言全绿却验错了对象。
  let intercepted = false;
  page.on('response', (r) => {
    if (r.url().includes('/costing-approve/preview')) intercepted = true;
  });
  (page as any).__previewIntercepted = () => intercepted;
}

function previewFixture(opts: { withUnanchored: boolean; unchangedOnly: boolean }) {
  const group = (axis: string, result: string, patched: number, untouched: number, unanchored: unknown[]) => ({
    axisValue: axis,
    customerNo: 'CUST-0001',
    baseVersionNo: 1,
    currentVersionNo: opts.withUnanchored ? 2 : 1,
    targetVersionNo: opts.withUnanchored ? 3 : 2,
    crossVersion: opts.withUnanchored,
    result,
    baseRowCount: patched + untouched,
    resultRowCount: patched + untouched + unanchored.length,
    patchedRows: patched,
    untouchedRows: untouched,
    unanchoredRows: unanchored,
    columnScope: {
      patched: patched > 0 ? ['component_qty', 'unit_weight'] : [],
      preserved: ['item_seq', 'input_material_no', 'output_material_type'],
    },
  });

  const unanchoredRow = {
    recordId: 8812,
    originId: 161,
    baseRowFingerprint: '9f2c00000000000000000000000000000000000000000000000000000000abcd',
    displayValues: { 项次: '90', 投入料号: 'S-1630010773' },
    reason: 'CROSS_VERSION_FINGERPRINT_MISS',
  };

  const groups = opts.unchangedOnly
    ? [group(`${PREFIX}M1`, 'UNCHANGED', 0, 9, [])]
    : [
        group(`${PREFIX}M1`, 'UPGRADED', 2, 7, opts.withUnanchored ? [unanchoredRow] : []),
        group(`${PREFIX}M2`, 'UNCHANGED', 0, 4, []),
      ];

  return {
    quotationId: '00000000-0000-0000-0000-0000000000a1',
    previewToken: 'T260907R-token',
    summary: { versionedGroups: 0, addedRows: 0, deletedRows: 0, changedRows: 0 },
    products: [],
    globalShared: {},
    groups: [],
    dsBackfill: {
      applicable: true,
      confirmRequired: true,
      summary: {
        tables: 1,
        axes: groups.length,
        upgradedGroups: groups.filter((g) => g.result === 'UPGRADED').length,
        unchangedGroups: groups.filter((g) => g.result === 'UNCHANGED').length,
        unanchoredRows: opts.withUnanchored ? 1 : 0,
      },
      tables: [
        {
          sheetKey: 'MATERIAL_BOM',
          sheetName: '物料BOM',
          tableName: 'ds_quote_material_bom',
          groups,
        },
      ],
      extendColumnOnly: [{ sheetKey: 'MATERIAL_BOM', fields: ['自定义列A', '毛利率(公式)'] }],
    },
  };
}

/**
 * ⛔ 待接前端：从核价评审入口打开「核价通过确认抽屉」。
 *
 * 🚫 刻意不返回空实现 —— 空实现会让上面的断言在「抽屉压根没打开」的情况下跑完，
 *    而 `expect(...).toBeVisible()` 会以 timeout 的面目失败，读报告的人会去查业务代码。
 */
async function openCostingApproveDrawer(_page: Page): Promise<void> {
  throw new Error(
    '⛔ 待接前端（**不是被测功能的结论**）：需要「从核价评审页进入某张 SUBMITTED 的新链路报价单 → ' +
      '点核价通过 → 抽屉打开」的稳定路径与选择器。' +
      '缺的信息（入口页面路由、按钮可访问名、抽屉与各单元格的 data-testid）已列在测试回报的「缺什么」清单里。',
  );
}
