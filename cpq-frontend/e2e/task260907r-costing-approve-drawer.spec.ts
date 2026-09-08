/**
 * task-260907 第二段 · E2E —— 核价通过确认抽屉
 *
 * 服务的 AC（原文在 `dev-docs/task-260907-报价导入建单切ds新表/task-260907-record层与核价回填/需求文档.md`）：
 *   - AC-5  ① 抽屉形态（placement=right / width=1200，🚫 不是 Modal）+ ③ 汇总条五项 + ④「本次不动」必须渲染
 *   - AC-14 ③ 判定为 UNCHANGED 的组仍出现在表格里（🚫 不许过滤）+ 顶部提示条文案
 *   - AC-20 ③ 「对不上的行」明细区必须直出（🚫 不许折叠进「更多」）+ 主按钮变红、文案「仍然确认并核价通过」
 *   - AC-21 🆕 `result === 'BLOCKED'`（本次跳过回填、一个字节不写，但**不阻断确认**）
 *   - 🆕 `noRecordSnapshot` —— 整单没有比对快照（导入建单绕开了写快照的路径）⇒ 确认后一个字节不写。
 *           本期只让它**可见**，不补写入能力（用户 2026-09-07 裁决）。
 *   - D-35  🆕 `recordStale.stale === true` 时必须显著提示「此刻预览的内容可能不是报价单的最新数据」
 *           （api.md §1 硬约束 4：🚫 不许折叠、🚫 不许静默、🚫 不许把 detail 异常原文给财务看）
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
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin } from './fixtures/auth';

/**
 * 🚨 AP-43 原样复发（2026-09-07 修）：本文件原来在 readPrototypeGroupTableHeader() 里用
 * `require('fs')` + `__dirname`，而本项目 e2e 跑在 **ESM** 下（package.json type=module；
 * 同目录 `fixtures/auth.ts` 自己就用 fileURLToPath(import.meta.url)）⇒ 两个都不存在。
 * **四条用例全部经过那个函数** ⇒ 一旦入口接通，会同时倒在一个与业务无关的 ReferenceError 上，
 * 而那种失败长得和「实现坏了」一模一样。⇒ 改为顶层 import + import.meta.url。
 */
const HERE = path.dirname(fileURLToPath(import.meta.url));

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
 * @param screen         该屏对应的原型编号（'01' | '02' | '03' | '05'），用于「逐字一致」比对
 * @param expectUnanchored 本屏的 fixture 是否含 unanchoredRows > 0（不变量 c 的前件）
 * @param allUnchanged   本屏的组是否全部判定为 UNCHANGED（不变量 d 的前件）
 */
async function assertAc5ColumnInvariants(
  page: Page,
  drawer: ReturnType<Page['locator']>,
  screen: '01' | '02' | '03' | '05',
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
function readPrototypeGroupTableHeader(screen: '01' | '02' | '03' | '05'): string[] {
  const dir = path.resolve(
    HERE,
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

    /**
     * AC-5③：汇总条五项。
     * ⚠️ 2026-09-07 修（接通入口后第一次真跑就撞到）：原来是
     * `drawer.getByText(label, { exact: false })` —— **范围没收窄**，而抽屉里别处也有同样的字：
     *   「将升版」→ 页脚「确认后不可撤销：1 个料号组将升版，旧版本进入历史可查。」
     *   「无变更」→ 判定列的 <Tag>无变更</Tag>
     * ⇒ 命中多个元素，以 **strict mode violation** 的面目失败，而那长得像「汇总条没渲染」。
     * ⇒ 先按 data-testid 收窄到汇总条，再 exact 匹配标签。
     */
    const summaryBar = drawer.locator('[data-testid="ds-backfill-summary"]');
    await expect(summaryBar, 'AC-5③：汇总条整块必须渲染').toBeVisible();
    for (const label of SUMMARY_LABELS) {
      await expect(
        summaryBar.getByText(label, { exact: true }),
        `AC-5③：汇总条缺「${label}」（与原型 01 逐字一致）`,
      ).toBeVisible();
    }

    // 🚨 AC-5④：「本次不动」必须渲染 —— AP-60 判据四的直接产物。
    //    预览只描述「哪些值变了」会漏掉「不写 = 删除」这一整类后果；
    //    repair-0727 的真实事故正是预览显示「0 变更」而执行删了 3 行。
    // ⚠️ 2026-09-07 修（同一族的第三次）：`getByText('本次不动')` 在抽屉里命中 **3 个**元素 ——
    //    ① 顶部说明条里的 <b>本次不动</b>、② 组表列头 <th>、③ 一个同文本的 <div>
    //    ⇒ strict mode violation，失败长得像「这一列没渲染」。
    //    本条断言要验的是**这一列在不在**，所以判据就该是「列头」，而不是「页面上有没有这四个字」。
    await expect(
      drawer.getByRole('columnheader', { name: '本次不动' }).first(),
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
    /**
     * ⚠️ 2026-09-07 修（与 T-21 同款，实测撞到）：`data-result` 挂在 `<tr>` **自己**身上，
     * 而 `filter({ has })` 匹配的是**后代** ⇒ 恒 0 命中，用例会以「UNCHANGED 组没渲染」的
     * 面目失败 —— 那是选择器错，不是实现错，但两者长得一模一样。
     * ⇒ 属性直接拼在同一个选择器上。
     */
    const unchangedRow = drawer
      .locator('[data-testid="ds-backfill-group-row"][data-result="UNCHANGED"]')
      .first();
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

  /**
   * 🆕 AC-21（2026-09-07 契约扩到四值，后端在写）：`result === 'BLOCKED'`。
   *
   * 本用例的判据分成**两半**，缺一半这条就是假绿：
   *   A. 正向 —— BLOCKED 必须**可见且可读**：橙档判定标签「本次跳过」、汇总条「本次跳过的组」、
   *      warning 告警条、独立的「本次跳过的组」明细表（粒度键取值 + 两侧行数）。
   *   B. 🚨 反向 —— BLOCKED **不许**表现成错误：
   *      ① 主按钮仍是「确认并核价通过」且**没有** `ant-btn-dangerous`；
   *      ② 主按钮**不禁用**（`BLOCKED` 不阻断确认）；
   *      ③ **不许**误报「该组行数会减少 —— 按设计不应发生」——
   *         夹具刻意让 BLOCKED 组的 `resultRowCount` 缺失（后端过渡期完全可能不给），
   *         此时 `num()` 得 0、`0 < baseRowCount` 恒真，**旧判据会点亮顶部红条**。
   *         这一条守的就是那个假警报：它长得和真缺陷一模一样，读报告的人会去查后端。
   */
  test('T-21 · AC-21 BLOCKED：橙档可见 + 明细表直出，且🚫 不阻断确认、🚫 不误报「行数会减少」', async ({ page }) => {
    await stubPreview(page, blockedPreviewFixture());
    await openCostingApproveDrawer(page);

    const drawer = page.locator('.ant-drawer').filter({ hasText: '核价通过' });
    await expect(drawer).toBeVisible();

    // ── A①：汇总条出现「本次跳过的组」，且值 = 2（不是表头在而值恒空）
    const blockedSummary = drawer.locator('[data-testid="ds-backfill-blocked-summary"]');
    await expect(
      blockedSummary,
      'AC-21：汇总条必须出现「本次跳过的组」一项（口径同「不参与的组件」，>0 才出现）',
    ).toBeVisible();
    await expect(blockedSummary).toContainText('本次跳过的组');
    await expect(
      blockedSummary,
      'AC-21：汇总条的 BLOCKED 组数应为 2（夹具两组），只有标题没有数字等于没渲染',
    ).toContainText('2');

    // ── A②：warning 告警条（🚫 不是 error）
    const blockedAlert = drawer.locator('[data-testid="ds-backfill-blocked-alert"]');
    await expect(blockedAlert, 'AC-21：BLOCKED 必须有顶部告警条，🚫 不许静默').toBeVisible();
    await expect(
      blockedAlert,
      'AC-21：BLOCKED 不是错误态 —— 告警条应为 warning（antd .ant-alert-warning），🚫 不许用 error',
    ).toHaveClass(/ant-alert-warning/);
    await expect(
      blockedAlert,
      'AC-21：告警条必须说清「本次一个字节都不写」这个结论',
    ).toContainText('一个字节都不写');
    await expect(
      blockedAlert,
      'AC-21：告警条必须说清「核价通过照常进行」—— 否则财务会以为自己被卡住了',
    ).toContainText('核价通过照常进行');

    // ── A③：组表里的 BLOCKED 行 —— 属性钩子 + 用户可见中文，两侧都要断言
    const rows = drawer.locator('[data-testid="ds-backfill-group-row"]');
    expect(
      await rows.count(),
      '🚨 空验证守卫：一个料号组都没渲染 ⇒ 下面所有断言会空跑',
    ).toBeGreaterThan(0);
    /**
     * ⚠️ antd 选择器坑（本轮实测撞到）：`data-result` 挂在 `<tr>` **自己**身上，
     * 而 `filter({ has })` 匹配的是**后代** ⇒ 恒 0 命中，症状是「一个 BLOCKED 组都没渲染」。
     * 那是**选择器错**不是产品错，但两者长得一模一样。⇒ 属性直接拼在同一个选择器上。
     * 📌 同一写法在本文件 T-14（UNCHANGED）里也在用，见回报「发现但没动的问题」。
     */
    const blockedRow = drawer
      .locator('[data-testid="ds-backfill-group-row"][data-result="BLOCKED"]')
      .first();
    await expect(
      blockedRow,
      'AC-21：判定为 BLOCKED 的组必须出现在表格里（理由同 UNCHANGED 不许过滤）',
    ).toBeVisible();
    await expect(
      blockedRow,
      'AC-21：判定列应向财务渲染中文「本次跳过」，而不只是挂着 data-result 属性',
    ).toContainText('本次跳过');
    await expect(
      blockedRow,
      'AC-21：版本迁移列的终点必须是「不升版」—— 🚫 不许显示一个本次不会发生的目标版本',
    ).toContainText('不升版');

    // ── A④：独立明细表直出（🚫 不折叠），且带得出粒度键取值与两侧行数
    const panel = drawer.locator('[data-testid="ds-backfill-blocked-panel"]');
    await expect(panel, 'AC-21：必须有独立的「本次跳过的组」明细区').toBeVisible();
    await expect(
      panel.locator('.ant-collapse-item:not(.ant-collapse-item-active)'),
      'AC-21：🚫 明细区不许折叠进「更多」，必须直出',
    ).toHaveCount(0);
    await expect(
      panel.getByText('00005', { exact: false }),
      'AC-21：明细区必须列出粒度键取值，否则财务不知道是哪一条撞了',
    ).toBeVisible();
    // 明细行顺序 = tables[] × groups[] × collidingRows[] 的展开顺序：
    //   第 0 行 = MATERIAL_BOM/M2（基底 2 / 报价单 1），第 1 行 = ELEMENT_BOM/E1（基底 4 / 报价单 1）
    await expect(
      panel.locator('[data-testid="ds-backfill-colliding-base"]').nth(0),
      'AC-21：明细区必须给出「基础数据里的行数」，只有表头没有值等于没渲染',
    ).toHaveText('2');
    await expect(
      panel.locator('[data-testid="ds-backfill-colliding-base"]').nth(1),
      'AC-21：第二条冲突的基底行数应为 4',
    ).toHaveText('4');
    await expect(
      panel.locator('[data-testid="ds-backfill-colliding-record"]').nth(1),
      'AC-21：明细区必须给出「报价单里的行数」——冲突可能出在任一侧，两个数缺一不可判',
    ).toHaveText('1');

    // 🚫 反向：与「对不上的行」是两套，🚫 不许被并进同一张表
    await expect(
      drawer.locator('[data-testid="ds-backfill-unanchored-panel"]'),
      'AC-21：本屏 unanchoredRows=0 ⇒ 🚫 不许出现「对不上的行」明细区（两者语义与后果相反，不许混用同一区）',
    ).toHaveCount(0);

    // ── B①②：主按钮不变红、不禁用 —— BLOCKED 不阻断确认
    const primary = drawer.getByRole('button', { name: '确认并核价通过' });
    await expect(
      primary,
      'AC-21：BLOCKED 不阻断确认 ⇒ 主按钮文案应仍是「确认并核价通过」，🚫 不许变成「仍然确认并核价通过」',
    ).toBeVisible();
    await expect(
      primary,
      'AC-21：BLOCKED 不是错误态 ⇒ 主按钮 🚫 不许带 .ant-btn-dangerous（那是「有行对不上」才用的）',
    ).not.toHaveClass(/ant-btn-dangerous/);
    await expect(
      primary,
      'AC-21：🚫 BLOCKED 不许禁用确认按钮 —— 核价通过本身照常进行',
    ).toBeEnabled();

    // ── B③：🚨 不许误报「组会变小」
    await expect(
      drawer.locator('[data-testid="ds-backfill-shrink-alert"]'),
      'AC-21：BLOCKED 组的 resultRowCount 缺失时 🚫 不许触发「行数会减少 —— 按设计不应发生」的红条。' +
        '那是假警报，且长得和真缺陷一模一样。',
    ).toHaveCount(0);
    await expect(
      drawer.getByText('该组行数会减少', { exact: false }),
      'AC-21：BLOCKED 行内也 🚫 不许出现「该组行数会减少」的小注',
    ).toHaveCount(0);

    // ── AC-5② 列级不变量：本屏无对不上、含 UPGRADED 组 ⇒ 九列，与原型 05 逐字一致
    await assertAc5ColumnInvariants(page, drawer, '05', /*expectUnanchored*/ false, /*allUnchanged*/ false);

    // 🚫 到此为止：**绝不点这个按钮**（同上，共享库不可逆）。
  });

  /**
   * 🆕 D-35（api.md §1 硬约束 4）：`_record` 快照写失败过 ⇒ **这份预览本身可能不可信**。
   *
   * 防的是什么（需求文档 D-35 后果链）：保存成功 → 快照没更新 → 预览读到过期 `_record`
   * → 财务照着确认 → **按错的数据回填主表**。⇒ 与 AP-60 判据四同族：预览在撒谎，且撒得很有说服力。
   *
   * 判据四条：① 红档（error，比 BLOCKED 的 warning 更重）；② **排在面板最顶端**（它限定下面所有结论的
   * 可信度，排在汇总条之后就退化成「众多提示之一」）；③ 🚫 不许折叠；④ 🚫 不许把 `detail` 的异常原文
   * 渲染给财务（api.md 明写）。
   */
  test('T-35 · D-35 recordStale：红档置顶不折叠，且🚫 不许把异常原文给财务看', async ({ page }) => {
    await stubPreview(page, recordStalePreviewFixture({ viaSummaryOnly: false }));
    await openCostingApproveDrawer(page);

    const drawer = page.locator('.ant-drawer').filter({ hasText: '核价通过' });
    await expect(drawer).toBeVisible();

    const alert = drawer.locator('[data-testid="ds-backfill-record-stale-alert"]');
    // ① 存在且可见（🚫 不折叠 —— 判据：不做任何点击就可见，且不在收起态折叠面板里）
    await expect(
      alert,
      'D-35：recordStale.stale=true 时必须有显著提示，🚫 不许静默',
    ).toBeVisible();
    await expect(
      alert.locator('.ant-collapse-item:not(.ant-collapse-item-active)'),
      'D-35：🚫 不许折叠',
    ).toHaveCount(0);

    // ① 红档（比 BLOCKED 的 warning 更重）
    await expect(
      alert,
      'D-35：它说的是「我给你看的东西可能是错的」⇒ 必须是 error 档（antd .ant-alert-error），' +
        '🚫 不许降到 warning —— 那是 BLOCKED 那一档',
    ).toHaveClass(/ant-alert-error/);

    // api.md 原文那句必须出现
    await expect(
      alert,
      'D-35：文案须含 api.md 原文「此刻预览的内容可能不是报价单的最新数据」',
    ).toContainText('此刻预览的内容可能不是报价单的最新数据');
    // reason 必须被映射成中文，🚫 不许把常量码直接摆给财务
    await expect(
      alert,
      'D-35：reason 必须映射成财务读得懂的中文',
    ).toContainText('快照没有写成功');
    await expect(
      alert,
      'D-35：🚫 不许把 reason 常量码原样渲染（未知码才带原码兜底，已知码必须译成中文）',
    ).not.toContainText('WRITE_FAILED');

    // 🚨 ④ 反向：detail 是异常原文，api.md 明写**不得**渲染给财务
    await expect(
      drawer.getByText('IllegalStateException', { exact: false }),
      'D-35：🚫 `detail` 是排障用的异常原文，api.md 明写不得直接当用户文案渲染',
    ).toHaveCount(0);
    await expect(
      drawer.getByText('NullPointerException', { exact: false }),
      'D-35：🚫 同上（换一个异常名做交叉核对，防止只躲过某一个字符串）',
    ).toHaveCount(0);

    // ② 位置：必须排在汇总条**之前**（DOM 序）
    const beforeSummary = await drawer.evaluate((root) => {
      const a = root.querySelector('[data-testid="ds-backfill-record-stale-alert"]');
      // ⚠️ 2026-09-07 修：原来按「含『涉及料号组』字样的第一个 div」找汇总条 ——
      //    那会先命中一个**祖先容器**（它也包含告警条），compareDocumentPosition 返回的是
      //    CONTAINS 而不是 FOLLOWING ⇒ 判据恒 false，失败长得像「实现把顺序放错了」。
      //    ⇒ 改用汇总条自己的 data-testid，两者必为兄弟关系。
      const sum = root.querySelector('[data-testid="ds-backfill-summary"]');
      if (!a || !sum) return null;
      // Node.DOCUMENT_POSITION_FOLLOWING = 4 ⇒ sum 在 a 之后
      return (a.compareDocumentPosition(sum) & 4) !== 0;
    });
    expect(
      beforeSummary,
      'D-35：🚨 定位失败（拿不到告警条或汇总条）⇒ 下面这条位置断言会空跑',
    ).not.toBeNull();
    expect(
      beforeSummary,
      'D-35②：告警条必须排在汇总条之前 —— 它限定的是下面所有结论的可信度，' +
        '排到中间就退化成「众多提示之一」。',
    ).toBe(true);

    // 页脚必须同步提示（🚫 不许只在正文说一句就算了）
    await expect(
      drawer.locator('.ant-drawer-footer'),
      'D-35：页脚必须同步给出「先不要确认」的指令',
    ).toContainText('预览数据可能已过期');

    /**
     * 🆕 2026-09-07 主线裁决：recordStale 与 unanchoredRows **同档** —— 主按钮变红 + 文案「仍然确认并核价通过」。
     * 由来：顶部红条写「请先不要确认」而主按钮是蓝色的「确认并核价通过」⇒ **同一屏给了两个相反的信号**，
     * 财务照哪个做都不算错。⇒ 三条断言分别钉死「变红 / 换文案 / 但不禁用」，
     * 少任何一条，下次有人把它改回蓝色都不会有东西变红。
     */
    const primary = drawer.getByRole('button', { name: '仍然确认并核价通过' });
    await expect(
      primary,
      'D-35：recordStale 时主按钮文案必须是「仍然确认并核价通过」——「仍然」承担的是「你已被警告，这是你的决定」',
    ).toBeVisible();
    await expect(
      primary,
      'D-35：主按钮必须是危险态（antd .ant-btn-dangerous）—— 🚫 不许顶部红条说「请先不要确认」而按钮还是蓝色',
    ).toHaveClass(/ant-btn-dangerous/);
    // 🚫 反向：变红 ≠ 阻断
    await expect(
      primary,
      'D-35：🚫 变红但**不禁用** —— 核价通过改的是报价单状态（业务流程），' +
        '不该被数据层的可信度问题卡死（同 BLOCKED「跳过但不阻断」的理由）',
    ).toBeEnabled();
    await expect(
      drawer.getByRole('button', { name: '确认并核价通过', exact: true }),
      'D-35：🚫 不许再出现普通蓝色态的「确认并核价通过」（两个信号并存正是本次要消灭的东西）',
    ).toHaveCount(0);
  });

  /**
   * 🆕 D-35 的**第二个出口**：`summary.recordStale`（api.md :51「便于前端直接做红条」）。
   * 本用例刻意**只**给 summary 的布尔位、顶层 `recordStale` 整个不发 ——
   * 前端若只信顶层对象，告警就整块消失，而**漏报的代价是财务照着过期数据确认回填**。
   * ⇒ 判据必须是「两个出口取并」，这条用例守的就是这个并。
   */
  test('T-35b · D-35 只给 summary.recordStale 布尔位时，告警仍必须出现', async ({ page }) => {
    await stubPreview(page, recordStalePreviewFixture({ viaSummaryOnly: true }));
    await openCostingApproveDrawer(page);

    const drawer = page.locator('.ant-drawer').filter({ hasText: '核价通过' });
    await expect(
      drawer.locator('[data-testid="ds-backfill-record-stale-alert"]'),
      'D-35：顶层 recordStale 缺失、只有 summary.recordStale=true 时，告警**仍必须出现**' +
        '（两个出口取并，🚫 不许只信其中一个）',
    ).toBeVisible();
    await expect(
      drawer.locator('[data-testid="ds-backfill-record-stale-alert"]'),
      'D-35：reason 缺失时走「未说明原因」兜底，🚫 不许渲染空白',
    ).toContainText('未说明原因');

    // 危险态由 flags.recordStale 驱动 ⇒ **两个出口都得点亮它**，🚫 不许只在顶层对象那条路上变红
    const primary = drawer.getByRole('button', { name: '仍然确认并核价通过' });
    await expect(
      primary,
      'D-35：只给 summary.recordStale 布尔位时，主按钮**同样**必须变红改文案（危险态与告警条同源）',
    ).toBeVisible();
    await expect(primary).toHaveClass(/ant-btn-dangerous/);
    await expect(primary, 'D-35：🚫 仍然不许禁用').toBeEnabled();
  });

  /**
   * 🆕 T-39 `noRecordSnapshot`：**整单没有比对快照** ⇒ 确认后一个字节都不会写。
   *
   * 防的是什么：写快照只挂在报价页面的保存路径上，而**导入建单绕开了它** ⇒
   * 导入出来的单只要没人手工保存过就一行快照都没有。现在的表现是**静默 no-op**——
   * 核价通过照常返 200、主表一个字节不写，**界面上与「本来就没什么要回填」长得一模一样**。
   * ⇒ 本用例守的就是「这个 no-op 必须说出来」。
   *
   * 🚨 三条容易写成假绿的地方，逐条钉死：
   *   ① 本条**必然伴随 `tables=[]`** ⇒ 断言必须在「一张表都没有」的夹具上成立
   *      （把它挂在 hasTables 上会让告警恰好在唯一会出现的场景里消失 —— 本任务踩过两次）；
   *   ② 与 `recordStale` **必须能被财务区分开**：两条都说「让销售保存一次」，
   *      ⇒ 断言首句差异，而不是只断言「有个红条」；
   *   ③ 🚫 不许出现内部术语。
   */
  test('T-39 · noRecordSnapshot：整单没有快照必须显式报出，且与 recordStale 可区分', async ({ page }) => {
    await stubPreview(page, noRecordSnapshotFixture({ participatingComponents: 3 }));
    await openCostingApproveDrawer(page);

    const drawer = page.locator('.ant-drawer').filter({ hasText: '核价通过' });
    await expect(drawer).toBeVisible();

    const alert = drawer.locator('[data-testid="ds-backfill-no-record-snapshot-alert"]');
    await expect(
      alert,
      'noRecordSnapshot：整单零写入必须显式报出，🚫 不许静默 —— 静默正是本缺口现在的表现',
    ).toBeVisible();

    // ① tables=[] 仍然要渲染（本条唯一会出现的场景就是没有表）
    await expect(
      drawer.locator('[data-testid="ds-backfill-group-row"]'),
      '前置校验：本夹具 tables=[] ⇒ 一个料号组都不该渲染。若这里 >0，说明夹具没造对，' +
        '下面「tables 为空时告警仍在」的判据就没验到。',
    ).toHaveCount(0);

    // ② 结论 + 成因 + 处置，三件事都要说到
    await expect(
      alert,
      'noRecordSnapshot：必须说清后果 —— 确认后不会写入任何基础数据',
    ).toContainText('不会写入任何基础数据');
    await expect(
      alert,
      'noRecordSnapshot：必须说清常见成因（导入建出来的单，从未在报价页面保存过）',
    ).toContainText('没有在报价页面保存过');
    await expect(
      alert,
      'noRecordSnapshot：必须给出处置动作 —— 让销售打开这张报价单保存一次',
    ).toContainText('保存一次');
    await expect(
      alert,
      'noRecordSnapshot：participatingComponents > 0 时应说明「有 N 个页签本该写回」，' +
        '否则财务不知道漏掉的是多大一块',
    ).toContainText('3');

    // 🚫 ③ 不许出现内部术语
    for (const term of ['_record', 'syncRecords', 'saveDraft']) {
      await expect(
        drawer.getByText(term, { exact: false }),
        `noRecordSnapshot：🚫 不许把内部术语「${term}」摆给财务看`,
      ).toHaveCount(0);
    }

    /**
     * 🚨 反向：🚫 不许同屏出现「本单没有需要写回基础数据的页签」。
     * 那是 tables=[] 时的既有空态文案，而它与本告警**直接矛盾** ——
     * 告警说「有 3 个页签本该写回、现在一个都写不了」，那句说「没有需要写回的页签」，
     * 财务只会记住后者（它在下面、更像结论）。
     */
    await expect(
      drawer.getByText('本单没有需要写回基础数据的页签', { exact: false }),
      'noRecordSnapshot：🚫 空态文案与本告警矛盾，本条为真时不许渲染',
    ).toHaveCount(0);

    // ② 与 recordStale 可区分：本屏 recordStale 未触发 ⇒ 那条红条不该出现
    await expect(
      drawer.locator('[data-testid="ds-backfill-record-stale-alert"]'),
      'noRecordSnapshot：🚫 不许顺带点亮 recordStale —— 两者是两件事（从来没写过 vs 写过但失败）',
    ).toHaveCount(0);

    // 主按钮：本轮方案 = 不变红、不禁用，但把后果写进文案（主线待裁）
    const primary = drawer.getByRole('button', { name: '确认核价通过（不写入基础数据）' });
    await expect(
      primary,
      'noRecordSnapshot：按钮文案必须写明后果 —— 真正的风险是「点了会以为回填生效了」',
    ).toBeVisible();
    await expect(
      primary,
      'noRecordSnapshot：🚫 不变红 —— 确认的后果是零写入零伤害，涂红是假警报，会稀释真警报',
    ).not.toHaveClass(/ant-btn-dangerous/);
    await expect(primary, 'noRecordSnapshot：🚫 不禁用 —— 核价通过改的是报价单状态（业务流程）').toBeEnabled();

    await expect(
      drawer.locator('.ant-drawer-footer'),
      'noRecordSnapshot：页脚必须同步说明',
    ).toContainText('没有比对快照');
  });

  /**
   * 🆕 T-39b **阴性对照**：`participatingComponents === 0` ⇒ 🚫 不许报。
   *
   * 那是「本单本来就没有要写回基础数据的组件」，属**正常态**，不是本缺口。
   * 报了 = 把一句吓人的话说给一张完全正常的单。
   * 🔑 没有这条，T-39 无法排除「我只是无条件渲染了一个红条」。
   */
  test('T-39b · 阴性对照：participatingComponents=0 时🚫 不许报', async ({ page }) => {
    await stubPreview(page, noRecordSnapshotFixture({ participatingComponents: 0 }));
    await openCostingApproveDrawer(page);

    const drawer = page.locator('.ant-drawer').filter({ hasText: '核价通过' });
    await expect(drawer).toBeVisible();
    await expect(
      drawer.locator('[data-testid="ds-backfill-no-record-snapshot-alert"]'),
      '阴性对照：participatingComponents=0 是「本来就没什么要回填」的正常态，🚫 不许报',
    ).toHaveCount(0);
    await expect(
      drawer.getByText('没有可供比对的数据快照', { exact: false }),
      '阴性对照：🚫 整条文案都不许出现（testid 没了但文字还在，等于没修）',
    ).toHaveCount(0);
    // 🚫 也不许把按钮文案改成「不写入基础数据」那一版
    await expect(
      drawer.getByRole('button', { name: '确认核价通过（不写入基础数据）' }),
      '阴性对照：🚫 按钮文案不许被本条影响',
    ).toHaveCount(0);
  });

  /**
   * 🆕 T-39c **取并**：只给 `summary.noRecordSnapshot` 布尔位、顶层对象整个不发时，告警仍必须出现。
   * 与 D-35 的 T-35b 同型 —— 上一轮已证明「只信一个出口」会让告警在另一形态下整块消失。
   * ⚠️ 本形态下拿不到 participatingComponents ⇒ **字段缺失 🚫 不许当 0 处理**
   *    （当 0 就会走进阴性对照分支，告警在这条路径上静默消失）。
   */
  test('T-39c · 只给 summary.noRecordSnapshot 布尔位时，告警仍必须出现', async ({ page }) => {
    await stubPreview(page, noRecordSnapshotFixture({ summaryOnly: true }));
    await openCostingApproveDrawer(page);

    const drawer = page.locator('.ant-drawer').filter({ hasText: '核价通过' });
    await expect(
      drawer.locator('[data-testid="ds-backfill-no-record-snapshot-alert"]'),
      '取并：顶层对象缺失、只有 summary 布尔位时，告警**仍必须出现**（🚫 字段缺失不许当 0）',
    ).toBeVisible();
    await expect(
      drawer.locator('[data-testid="ds-backfill-no-record-snapshot-alert"]'),
      '取并：reason 缺失时走「未说明原因」兜底，🚫 不许渲染空白',
    ).toContainText('未说明原因');
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
  // 🚨 阳性对照：**在 route handler 里**记命中次数。
  //    ⚠️ 2026-09-07 修：原来监听 page.on('response') 判「有没有这个 URL 的响应」——
  //    那在拦截**没生效**、请求打到真实后端时同样为真 ⇒ 该对照证明不了任何事。
  //    命中计数只有拦截真的执行了才会 +1，这才是能证伪的写法。
  (page as any).__previewStubHits = 0;
  await page.route('**/api/cpq/quotations/*/costing-approve/preview', async (route) => {
    (page as any).__previewStubHits += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', data: body }),
    });
  });
}

/** 本 spec 自造的核价单 id —— 只存在于拦截层，🚫 库里没有、也绝不会写进库。 */
const STUB_COID = '00000000-0000-0000-0000-0000000000c1';
const STUB_QUOTATION_ID = '00000000-0000-0000-0000-0000000000a1';

/**
 * 拦截核价单详情端点，造出一张 `status='PENDING'` 的核价单 —— 这是「核价通过」按钮出现的前提
 * （`CostingReviewPage` 的 `canReview` = 角色 ∈ {PRICING_MANAGER, SYSTEM_ADMIN} && status === 'PENDING'）。
 *
 * 🚫 为什么不用库里的真单：① 本 spec 全程只读，不依赖库里此刻有没有合适的数据；
 *    ② 共享库正在等一次全库清空，任何「找一张真单」的路径都会在清空后失效。
 * ⚠️ `frozenDto` 给最小可解析对象即可：`buildFrozenView` 解不出 lineItems 时页面走降级提示，
 *    而本 spec 只需要页面渲染出操作栏与「核价通过」按钮。
 */
async function stubCostingOrder(page: Page) {
  await page.route(`**/api/cpq/costing-orders/${STUB_COID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'ok',
        data: {
          costingOrderId: STUB_COID,
          quotationId: STUB_QUOTATION_ID,
          costingOrderNumber: `CO-${PREFIX}0001`,
          status: 'PENDING',
          createdAt: '2026-09-07T10:00:00Z',
          frozenDto: JSON.stringify({ quotationNumber: 'QT-20260907-0431', lineItems: [] }),
        },
      }),
    });
  });
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
 * 🆕 AC-21 夹具：一张含 **BLOCKED 组**的预览（形状照 `api.md §1` 的四值 `result` 扩展）。
 *
 * 三个刻意的设计：
 *   1. **混编**（1 个 UPGRADED + 2 个 BLOCKED）——「只有 BLOCKED」的屏验不出「BLOCKED 不该影响
 *      正常组的渲染」，也验不出九列列集；
 *   2. **BLOCKED 组不给 `resultRowCount`** —— 后端过渡期完全可能不给，而那正是「组会变小」假警报的触发条件；
 *   3. `unanchoredRows` 恒空 —— BLOCKED 与「对不上的行」是两套东西，同屏出现会让反向断言失去意义。
 */
function blockedPreviewFixture() {
  const upgraded = {
    axisValue: `${PREFIX}M1`,
    customerNo: 'CUST-0001',
    baseVersionNo: 2,
    currentVersionNo: 2,
    targetVersionNo: 3,
    crossVersion: false,
    result: 'UPGRADED',
    baseRowCount: 9,
    resultRowCount: 9,
    patchedRows: 2,
    untouchedRows: 7,
    unanchoredRows: [],
    columnScope: {
      patched: ['component_qty', 'unit_weight'],
      preserved: ['item_seq', 'input_material_no', 'output_material_type'],
    },
  };

  /** ⚠️ 刻意不带 resultRowCount：守 T-21 的 B③（假警报）。 */
  const blockedSameVersion = {
    axisValue: `${PREFIX}M2`,
    customerNo: 'CUST-0001',
    baseVersionNo: 1,
    currentVersionNo: 1,
    targetVersionNo: 1,
    crossVersion: false,
    result: 'BLOCKED',
    blockedReason: 'GRAIN_KEY_COLLISION',
    baseRowCount: 4,
    patchedRows: 0,
    untouchedRows: 4,
    unanchoredRows: [],
    collidingRows: [
      { grainKey: { input_material_no: 'S-3110520790' }, baseRowCount: 2, recordRowCount: 1 },
    ],
    columnScope: { patched: [], preserved: ['item_seq', 'input_material_no'] },
  };

  /** 跨版 + BLOCKED：版本迁移列必须仍以「不升版」收尾。 */
  const blockedCrossVersion = {
    axisValue: `${PREFIX}E1`,
    customerNo: 'CUST-0001',
    baseVersionNo: 1,
    currentVersionNo: 2,
    targetVersionNo: 3,
    crossVersion: true,
    result: 'BLOCKED',
    blockedReason: 'GRAIN_KEY_COLLISION',
    baseRowCount: 6,
    patchedRows: 0,
    untouchedRows: 6,
    unanchoredRows: [],
    collidingRows: [
      { grainKey: { material_part_no: '00005', element_code: 'C' }, baseRowCount: 4, recordRowCount: 1 },
    ],
    columnScope: { patched: [], preserved: ['material_part_no', 'element_code'] },
  };

  return {
    quotationId: '00000000-0000-0000-0000-0000000000a1',
    previewToken: 'T260907R-token-blocked',
    summary: { versionedGroups: 0, addedRows: 0, deletedRows: 0, changedRows: 0 },
    products: [],
    globalShared: {},
    groups: [],
    dsBackfill: {
      applicable: true,
      confirmRequired: true,
      summary: {
        tables: 2,
        axes: 3,
        upgradedGroups: 1,
        unchangedGroups: 0,
        blockedGroups: 2,
        unanchoredRows: 0,
      },
      tables: [
        {
          sheetKey: 'MATERIAL_BOM',
          sheetName: '物料BOM',
          tableName: 'ds_quote_material_bom',
          groups: [upgraded, blockedSameVersion],
        },
        {
          sheetKey: 'ELEMENT_BOM',
          sheetName: '物料与元素BOM',
          tableName: 'ds_quote_element_bom',
          groups: [blockedCrossVersion],
        },
      ],
    },
  };
}

/**
 * 🆕 D-35 夹具。两种投递形态各一：
 *   `viaSummaryOnly=false` → 顶层 `recordStale` 对象（api.md :88 的主形态，含 detail 异常原文）
 *   `viaSummaryOnly=true`  → **只**给 `summary.recordStale` 布尔位（api.md :51 的便捷位）
 * ⚠️ `detail` 刻意塞真实异常原文，好让 T-35 的「🚫 不许渲染给财务」那条**有东西可证伪**——
 *    detail 若是空串，那条断言恒真，等于没验。
 */
function recordStalePreviewFixture(opts: { viaSummaryOnly: boolean }) {
  const base = previewFixture({ withUnanchored: false, unchangedOnly: false }) as any;
  base.dsBackfill.summary.recordStale = true;
  if (!opts.viaSummaryOnly) {
    base.dsBackfill.recordStale = {
      stale: true,
      reason: 'WRITE_FAILED',
      detail: 'IllegalStateException: ds_quote_material_bom_record write failed at DsRecordProjector.project',
      detectedAt: '2026-09-07T06:17:29Z',
    };
  } else {
    // 顶层对象整个不发 —— 守「两个出口取并」
    delete base.dsBackfill.recordStale;
  }
  return base;
}

/**
 * 🆕 T-39 夹具：**整单没有比对快照**。
 *
 * 🔑 `tables: []` 是本情形的**必然形态**（一行快照都没有 ⇒ 一个组都算不出来）——
 *    🚫 不许为了「让屏幕上有点东西」硬造几张表，那会把最该守的判据（tables 为空时告警仍在）验没。
 * ⚠️ `applicable` 仍是 `true`：主线已裁 api.md 的定义为「三者全空才 false」。
 *
 * @param opts.participatingComponents 显式计数；`0` 即阴性对照（本来就没什么要回填）
 * @param opts.summaryOnly            只发 summary 布尔位、顶层对象整个不发（守取并）
 */
function noRecordSnapshotFixture(opts: { participatingComponents?: number; summaryOnly?: boolean }) {
  const ds: any = {
    applicable: true,
    confirmRequired: true,
    summary: {
      tables: 0,
      axes: 0,
      upgradedGroups: 0,
      unchangedGroups: 0,
      unanchoredRows: 0,
      noRecordSnapshot: true,
    },
    tables: [],
  };
  if (!opts.summaryOnly) {
    ds.noRecordSnapshot = {
      reason: 'NEVER_WRITTEN',
      participatingComponents: opts.participatingComponents ?? 3,
      recordRows: 0,
    };
    // 阴性对照：契约「恒发」下，真的没有此情形时布尔位也该是 false。
    // 夹具照这个口径造，否则验的就不是产品而是我自己造的矛盾输入。
    if (opts.participatingComponents === 0) ds.summary.noRecordSnapshot = false;
  }
  return {
    quotationId: STUB_QUOTATION_ID,
    previewToken: `${PREFIX}token-nosnapshot`,
    summary: { versionedGroups: 0, addedRows: 0, deletedRows: 0, changedRows: 0, affectedProducts: 0 },
    products: [],
    globalShared: { groupIndexes: [] },
    groups: [],
    dsBackfill: ds,
  };
}

/**
 * 从核价评审页打开「核价通过确认抽屉」——**走用户视角的完整路径**：
 * 进核价评审页 → 点「核价通过」→ 抽屉打开。
 *
 * 🕰️ 2026-09-07 接通（此前是「⛔ 待接前端」的抛错桩，整个 spec 跑不起来）。
 * 两个端点都被拦截，因此：🚫 不写库、🚫 不依赖库里有没有合适的数据、🚫 不受即将到来的全库清空影响。
 *
 * ⚠️ 顺序要紧：`stubCostingOrder` 必须在 `page.goto` **之前**注册，否则详情请求会漏出去打真实后端，
 *    页面拿到 404/401 就渲染「核价单不存在」，而下面所有断言会以 timeout 的面目失败。
 */
async function openCostingApproveDrawer(page: Page): Promise<void> {
  await stubCostingOrder(page);
  await page.goto(`/costing-orders/${STUB_COID}/review`);

  const approveBtn = page.getByRole('button', { name: '核价通过' });
  await expect(
    approveBtn,
    '⛔ 前置未满足：核价评审页没渲染出「核价通过」按钮 ⇒ 抽屉根本打不开，下面的断言全部会以 timeout 的面目失败。' +
      '先查：① 详情拦截是否生效（status 必须是 PENDING）；② 登录角色是否 PRICING_MANAGER / SYSTEM_ADMIN。',
  ).toBeVisible();
  await approveBtn.click();

  // 🚨 阳性对照：预览拦截**真的执行过**。没执行 = 页面打了真实后端，
  //    此时渲染出来的东西看着也「正常」，断言会全绿却验错了对象（最隐蔽的一类假绿）。
  await expect
    .poll(() => (page as any).__previewStubHits, {
      message:
        '🚨 阳性对照失败：预览端点的拦截一次都没命中 ⇒ 本次渲染的不是夹具数据，' +
        '后面所有断言即使全绿也不构成证据。',
      timeout: 15_000,
    })
    .toBeGreaterThan(0);
}
