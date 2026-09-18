/**
 * repair-260916 · 勾了「小计」的列在公式编辑器里选不出「字段列」引用 —— 测试分片 S-B（私有写片）
 *
 * 认领：AC-1 ~ AC-10、AC-18（T-B1 ~ T-B11）。断言全部派生自 问题说明.md ⑥ 原文与 原型图/公式抽屉.html；
 * 🚫 编写时未读 cpq-frontend/src/**、cpq-backend/src/main/**。
 *
 * 隔离：开发库（经共享后端 8081）自建目录 RP0916B-E2E + 6.1 表的 5 个组件；只断言这些对象（按 id）；
 *       afterAll 按 id 调 DELETE 回收。不改任何全局状态（test.md §6）。
 *
 * 运行（主线解锁后）：
 *   cd cpq-frontend && PW_BASE_URL=http://localhost:5292 \
 *     npx playwright test -c e2e/playwright.config.ts repair260916-subtotal-suffix.spec.ts
 *
 * 📌 主线审核 260917：文字逐字比较一律以组件管理公式列表「表达式」列为准；公式框只断言块数与颜色。
 *    worker 重建：造数前按上一轮 fixture-ids.json 的 id 清理残留（未用 serial 模式）。
 * ⚠️ 已知坑：两字按钮带空格（用正则/primary 类）；antd v6 无 .ant-drawer-content / .ant-tooltip-inner；
 *    公式表与字段表同在 DOM（用「含『配置』按钮的行」定位公式行）。
 */
import { test, expect, type Page, type Locator } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
import * as H from './repair260916-subtotal-suffix.helpers';
import { fx, N } from './repair260916-subtotal-suffix.helpers';

test.use({ viewport: { width: 1920, height: 1080 } });

const MATCH = [{ a: '销售料号', b: '销售料号' }, { a: '料号', b: '料号' }];

// 5.1 文案（逐字，来自 问题说明.md ⑥ AC-5/AC-6/AC-8）
const MSG = {
  a5: '页签「RP0916加工费」的列「备注数」没有勾选小计，不能写成「(小计)」',
  b5: '不能引用本页签自身的小计：[RP0916宿主.数量(小计)]',
  c5: '「(小计)」要写在列名后面，如 [页签.列(小计)]；整页签合计请写 [页签(总计)]',
  a6: 'SUM() 里不能再对小计求和：[RP0916加工费.加工费(小计)] 已是整列小计',
  c6: 'KSUM() 内不支持 (小计) 小计引用 [RP0916加工费.加工费(小计)]，请引用明细字段或把小计放到外层',
  d6: 'SUMIF() 里不支持「(小计)」引用 [RP0916加工费.加工费(小计)]',
  b8: '存在与宿主无公共行键的跨页签引用（match 为空），不可对齐。请改引可比页签或用其整页签小计 [页签(总计)]。，请改用 Excel 组件',
  // AC-6b 原写法：文案与 master 相同；主线给出的 master 行为前缀（完整文字以页面实际为准，落证据）
  b6oldPrefix: 'SUM() 行级聚合必须引用至少一个细页签明细列 [页签别名.字段]',
  e18: 'Excel 列暂不支持 SUMIF 类函数（SUMIF / COUNTIF / AVGIF / MINIF / MAXIF）',
  tip8: '行键 [工序] 与宿主 [销售料号+料号] 不可比；可改用「RP0916工序(总计)」',
};

test.beforeAll(async () => {
  H.writeEvidence('run-meta.json', { startedAt: new Date().toISOString(), baseURL: process.env.PW_BASE_URL, backend: H.BACKEND_URL });
  await H.setupFixtures();
});
test.afterAll(async () => { await H.cleanupFixtures(); });

let pageErrors: string[] = [];
test.beforeEach(async ({ page }) => {
  test.setTimeout(240_000);
  pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e)));
  await loginAsAdmin(page);
});
test.afterEach(async () => {
  expect(pageErrors, '不应有前端运行时错误').toEqual([]);
});

/** 在宿主上新增公式 → 打开抽屉；返回 { drawer, editor, baseCount }。 */
async function newHostFormula(page: Page, host = fx.host!) {
  const baseCount = ((await H.getComponent(host.id)).data.formulas ?? []).length;
  await H.openComponent(page, host);
  const drawer = await H.addFormulaAndOpen(page);
  const editor = H.editorOf(drawer);
  await H.clearEditor(page, editor);
  return { drawer, editor, baseCount };
}

/** 新落库的那条公式（按 id 与基线比对）。 */
function newest(data: any, baseIds: string[]) {
  const f = (data.formulas ?? []).filter((x: any) => !baseIds.includes(x.id));
  expect(f.length, '应恰好新增 1 条公式').toBe(1);
  return f[0];
}

/** 反向用例共用：保存被拒 → 提示逐字 → 抽屉仍开 → 刷新后公式未新增。 */
async function expectRejected(page: Page, drawer: Locator, host: H.Fx, baseIds: string[], msg: string, tag: string, rejectedExpr: string) {
  await H.drawerSave(page, drawer);
  await H.expectMessage(page, msg, tag);
  await H.shot(page, `${tag}-拒绝提示`);
  await expect(drawer, `${tag}：保存被拒时抽屉应保持打开`).toBeVisible();
  // 不点组件页保存；刷新后读库 + 读列表
  await page.reload();
  const data = (await H.getComponent(host.id)).data;
  const ids = (data.formulas ?? []).map((f: any) => f.id);
  console.log(`[S-B] ${tag}：刷新后库内公式 id=${JSON.stringify(ids)}（基线 ${JSON.stringify(baseIds)}）`);
  expect(ids.sort(), `${tag}：公式不应新增`).toEqual([...baseIds].sort());
  await H.openComponent(page, host);
  await H.gotoFormulaTab(page);
  // 探测 260917：未保存的公式行会作为本地草稿在刷新后保留（「保存全部草稿 (N)」），
  // 故列表判据为「没有任何一行的表达式是被拒的那段文字」，行数只打印不断言。
  const n = await H.formulaRows(page).count();
  const texts: (string | null)[] = [];
  for (let i = 0; i < n; i++) texts.push(await H.formulaListExpr(page, i));
  console.log(`[S-B] ${tag}：刷新后列表 ${n} 行（库内 ${ids.length} 条）表达式=${JSON.stringify(texts)}`);
  expect(texts.some((t) => t !== null && H.noWs(t).includes(H.noWs(rejectedExpr))), `${tag}：刷新后列表不应出现被拒公式`).toBe(false);
  await H.shot(page, `${tag}-刷新后公式列表`);
}

// ════════════════════════════════════════════════════════════════════ T-B1 · AC-1
test('T-B1 AC-1 点「明细·加工费」得到本行取值（蓝块 + cross_tab_ref NONE）', async ({ page }) => {
  const host = fx.host!;
  const baseIds = ((await H.getComponent(host.id)).data.formulas ?? []).map((f: any) => f.id);
  const { drawer, editor, baseCount } = await newHostFormula(page);
  const r = await H.insertedByChip(page, editor, H.chip(H.cardOf(drawer, N.fee), '加工费'));
  console.log(`[T-B1] 插入 blocks=${JSON.stringify(r.blocks)}`);
  expect(r.blocks.length, '① 应插入 1 个块').toBe(1);
  H.expectBlock(r.blocks[0], 'blue', 'AC-1①');
  await H.shot(drawer, 'T-B1-AC1-蓝块');

  await H.drawerSave(page, drawer);
  const { raw, data } = await H.persistFormulas(page, host, drawer, baseCount + 1);
  H.writeEvidence('T-B1-AC1-component.json', raw);
  const f = newest(data, baseIds);
  const lt = await H.listTextOf(page, host, f.id);
  expect(lt.text, '① 公式文字（列表「表达式」列）').toBe('[RP0916加工费.加工费]');
  const refs = H.refTokens(f.expression);
  console.log(`[T-B1] 存储 expression=${JSON.stringify(f.expression)}`);
  expect(refs.length, '② 应恰有 1 个引用 token').toBe(1);
  const t = refs[0];
  expect(t.type).toBe('cross_tab_ref');
  expect(t.agg).toBe('NONE');
  expect(t.target).toBe('加工费');
  expect(t.source).toBe(fx.fee!.id);
  expect(t.match).toEqual(MATCH);
});

// ════════════════════════════════════════════════════════════════════ T-B2 · AC-2
test('T-B2 AC-2 点「小计列·加工费(小计)」得到整列小计（黄块 + component_subtotal）', async ({ page }) => {
  const host = fx.host!;
  const baseIds = ((await H.getComponent(host.id)).data.formulas ?? []).map((f: any) => f.id);
  const { drawer, editor, baseCount } = await newHostFormula(page);
  const r = await H.insertedByChip(page, editor, H.chip(H.cardOf(drawer, N.fee), '加工费(小计)'));
  expect(r.blocks.length, '① 应插入 1 个块').toBe(1);
  H.expectBlock(r.blocks[0], 'yellow', 'AC-2①');
  await H.shot(drawer, 'T-B2-AC2-黄块');

  await H.drawerSave(page, drawer);
  const { raw, data } = await H.persistFormulas(page, host, drawer, baseCount + 1);
  H.writeEvidence('T-B2-AC2-component.json', raw);
  const f = newest(data, baseIds);
  const lt = await H.listTextOf(page, host, f.id);
  expect(lt.text, '① 公式文字（列表「表达式」列）').toBe('[RP0916加工费.加工费(小计)]');
  const refs = H.refTokens(f.expression);
  console.log(`[T-B2] 存储 expression=${JSON.stringify(f.expression)}`);
  expect(refs.length).toBe(1);
  const t = refs[0];
  expect(t.type).toBe('component_subtotal');
  expect(t.value).toBe('加工费');
  expect(t.component_code).toBe(fx.fee!.code);
  expect(t.tab_name).toBe(fx.fee!.code);
  expect('is_tab_total' in t, '② 不应带 is_tab_total').toBe(false);
});

// ════════════════════════════════════════════════════════════════════ T-B3 + T-B4 · AC-3 / AC-4（序列）
const MIX = '[RP0916加工费.加工费] + [RP0916加工费.加工费(小计)] + [RP0916加工费(总计)] + SUM([RP0916加工费.加工费])';

async function assertMix(r: Awaited<ReturnType<typeof H.readEditor>>, tag: string) {
  console.log(`[${tag}] display=「${r.display}」 blocks=${JSON.stringify(r.blocks.map((b) => [b.display, b.color]))}`);
  expect(r.blocks.length, `${tag}：应为 4 个块`).toBe(4);
  expect(r.blocks.map((b) => b.color), `${tag}：四块颜色依次 蓝/黄/绿/蓝`).toEqual(['blue', 'yellow', 'green', 'blue']);
  r.blocks.forEach((b, i) => H.expectBlock(b, (['blue', 'yellow', 'green', 'blue'] as const)[i], `${tag} 块${i + 1}`));
}

test('T-B3/T-B4 AC-3 四种写法混用：保存→关→开→原样再保存→刷新→开；AC-4 列表表达式一致', async ({ page }) => {
  const host = fx.host!;
  const baseIds = ((await H.getComponent(host.id)).data.formulas ?? []).map((f: any) => f.id);
  const { drawer, editor, baseCount } = await newHostFormula(page);
  const fee = H.cardOf(drawer, N.fee);

  // 依次点选/输入
  await H.chip(fee, '加工费').click();
  await H.caretEnd(page, editor); await page.keyboard.insertText(' + ');
  await H.chip(fee, '加工费(小计)').click();
  await H.caretEnd(page, editor); await page.keyboard.insertText(' + ');
  await H.chip(fee, 'RP0916加工费(总计)').click();
  await H.caretEnd(page, editor); await page.keyboard.insertText(' + SUM(');
  await H.chip(fee, '加工费').click();
  await H.caretEnd(page, editor); await page.keyboard.insertText(')');
  await page.waitForTimeout(400);
  await assertMix(await H.readEditor(editor), 'AC-3 保存前');
  await H.shot(drawer, 'T-B3-1-保存前');

  // ① 首次保存
  await H.drawerSave(page, drawer);
  const save1 = await H.persistFormulas(page, host, drawer, baseCount + 1);
  H.writeEvidence('T-B3-2-首次保存-component.json', save1.raw);
  const f1 = newest(save1.data, baseIds);
  const refs = H.refTokens(f1.expression);
  console.log(`[T-B3] 首存 refs=${JSON.stringify(refs)}`);
  expect(refs.length, '① 应为 4 个引用 token').toBe(4);
  expect([refs[0].type, refs[0].agg]).toEqual(['cross_tab_ref', 'NONE']);
  expect(refs[1].type).toBe('component_subtotal');
  expect('is_tab_total' in refs[1], '① 第 2 项不带 is_tab_total').toBe(false);
  expect([refs[2].type, refs[2].is_tab_total]).toEqual(['component_subtotal', true]);
  expect([refs[3].type, refs[3].agg]).toEqual(['cross_tab_ref', 'SUM']);

  // 首存后列表文字（逐字口径）
  const L1 = await H.listTextOf(page, host, f1.id);
  expect(H.noWs(L1.text), 'AC-3① 首存后公式文字（去空白比较，插入是否带空格未知）').toBe(H.noWs(MIX));
  await H.shot(page, 'T-B3-2b-首存后公式列表');

  // ② 关闭后重开（抽屉已关）：颜色与块数
  let d2 = await H.openFormulaAt(page, L1.idx);
  await assertMix(await H.readEditor(H.editorOf(d2)), 'AC-3② 重开');
  await H.shot(d2, 'T-B3-3-重开');

  // ③ 原样再保存
  await H.drawerSave(page, d2);
  const save2 = await H.persistFormulas(page, host, d2, baseCount + 1);
  H.writeEvidence('T-B3-4-原样再保存-component.json', save2.raw);
  const f2 = save2.data.formulas.find((x: any) => x.id === f1.id);
  const diff = diffDeep(f1, f2);
  H.writeEvidence('T-B3-5-两次保存逐字段diff.json', { formulaId: f1.id, differences: diff, first: f1, second: f2 });
  expect(diff, 'AC-3③ 原样再保存后存储应逐字段相同').toEqual([]);
  const L2 = await H.listTextOf(page, host, f1.id);
  expect(L2.text, 'AC-3②③ 再保存后公式文字与首存逐字相同').toBe(L1.text);

  // ④ 刷新浏览器后再打开
  await page.reload();
  await H.openComponent(page, host);
  d2 = await H.openFormulaAt(page, L1.idx);
  await assertMix(await H.readEditor(H.editorOf(d2)), 'AC-3④ 刷新后');
  await H.shot(d2, 'T-B3-6-刷新后重开');
  await d2.locator('button').filter({ hasText: /取\s*消/ }).first().click();
  await expect(d2).toBeHidden();

  // AC-4：刷新后公式列表「表达式」列
  const L4 = await H.listTextOf(page, host, f1.id);
  expect(L4.text, 'AC-4 刷新后列表表达式与 AC-3 文字逐字相同').toBe(L1.text);
  expect(L4.text, 'AC-4 应含 (小计)').toContain('[RP0916加工费.加工费(小计)]');
  H.writeEvidence('T-B4-AC4-列表表达式.txt', `首存: ${L1.text}\n再保存: ${L2.text}\n刷新后: ${L4.text}\n期望(去空白): ${MIX}\n`);
  await H.shot(page, 'T-B4-AC4-公式列表');
});

function diffDeep(a: any, b: any, p = ''): string[] {
  if (JSON.stringify(a) === JSON.stringify(b)) return [];
  if (typeof a !== 'object' || typeof b !== 'object' || !a || !b) return [`${p}: ${JSON.stringify(a)} ≠ ${JSON.stringify(b)}`];
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  return [...keys].flatMap((k) => diffDeep(a[k], b[k], `${p}/${k}`));
}

// ════════════════════════════════════════════════════════════════════ T-B5 · AC-5
for (const c of [
  { k: 'a', expr: '[RP0916加工费.备注数(小计)]', msg: MSG.a5 },
  { k: 'b', expr: '[RP0916宿主.数量(小计)]', msg: MSG.b5 },
  { k: 'c', expr: '[RP0916加工费(小计)]', msg: MSG.c5 },
]) {
  test(`T-B5${c.k} AC-5${c.k} 非法 (小计)：${c.expr} → 红块 + 拒绝 + 文案逐字`, async ({ page }) => {
    const host = fx.host!;
    const baseIds = ((await H.getComponent(host.id)).data.formulas ?? []).map((f: any) => f.id);
    const { drawer, editor } = await newHostFormula(page);
    const r = await H.typeExpr(page, editor, c.expr);
    expect(r.blocks.length, '该式应渲染为 1 个块').toBe(1);
    H.expectBlock(r.blocks[0], 'red', `AC-5${c.k}`);
    await H.shot(drawer, `T-B5${c.k}-红块`);
    await expectRejected(page, drawer, host, baseIds, c.msg, `T-B5${c.k}`, c.expr);
  });
}

// ════════════════════════════════════════════════════════════════════ T-B6 · AC-6
for (const c of [
  { k: 'a', expr: 'SUM([RP0916加工费.加工费(小计)])', msg: MSG.a6 },
  { k: 'c', expr: 'SUM(KSUM([RP0916加工费.加工费(小计)]))', msg: MSG.c6 },
  { k: 'd', expr: 'SUMIF([RP0916加工费.备注数] > 0, [RP0916加工费.加工费(小计)])', msg: MSG.d6 },
]) {
  test(`T-B6${c.k} AC-6${c.k} 函数里的 (小计) 被拒：${c.expr}`, async ({ page }) => {
    const host = fx.host!;
    const baseIds = ((await H.getComponent(host.id)).data.formulas ?? []).map((f: any) => f.id);
    const { drawer, editor } = await newHostFormula(page);
    await H.typeExpr(page, editor, c.expr);
    await H.shot(drawer, `T-B6${c.k}-输入`);
    await expectRejected(page, drawer, host, baseIds, c.msg, `T-B6${c.k}`, c.expr);
  });
}

test('T-B6b AC-6b（D-8）行级表达式 SUM([加工费.备注数] * [加工费.加工费(小计)]) 可保存且重开不变', async ({ page }) => {
  const host = fx.host!;
  const expr = 'SUM([RP0916加工费.备注数] * [RP0916加工费.加工费(小计)])';
  const baseIds = ((await H.getComponent(host.id)).data.formulas ?? []).map((f: any) => f.id);
  const { drawer, editor, baseCount } = await newHostFormula(page);
  await H.typeExpr(page, editor, expr);
  await H.shot(drawer, 'T-B6b-输入');
  await H.drawerSave(page, drawer);
  const { raw, data } = await H.persistFormulas(page, host, drawer, baseCount + 1);
  H.writeEvidence('T-B6b-AC6b-component.json', raw);
  const f = newest(data, baseIds);
  const subs = H.allTokens(f.expression).filter((t) => t.type === 'component_subtotal');
  console.log(`[T-B6b] 存储=${JSON.stringify(f.expression)}`);
  expect(subs.length, 'b 行级表达式内应有 1 个 component_subtotal').toBe(1);
  expect(subs[0].value).toBe('加工费');
  const bz = H.allTokens(f.expression).filter((t) => t.type === 'field' && t.value === '备注数');
  expect(bz.length, 'b 行级表达式内「备注数」应存为 1 个字段 token').toBe(1);
  expect(bz[0].source, 'b「备注数」应为来源页签（RP0916加工费）的列').toBe(fx.fee!.id);

  const L1 = await H.listTextOf(page, host, f.id);
  expect(H.noWs(L1.text), 'b 保存后公式文字与手输一致（去空白）').toBe(H.noWs(expr));
  // 重开 → 不改 → 原样保存 → 列表文字逐字不变
  const d2 = await H.openFormulaAt(page, L1.idx);
  await H.shot(d2, 'T-B6b-重开');
  await H.drawerSave(page, d2);
  await H.persistFormulas(page, host, d2, baseCount + 1);
  const L2 = await H.listTextOf(page, host, f.id);
  H.writeEvidence('T-B6b-列表表达式.txt', `手输: ${expr}\n保存后: ${L1.text}\n重开再保存后: ${L2.text}\n`);
  expect(L2.text, 'b 重开（原样保存）后文字逐字不变').toBe(L1.text);
});

test('T-B6b′ AC-6b 原写法 SUM([宿主.数量] * [加工费.加工费(小计)]) 仍被拒（文案与 master 相同）', async ({ page }) => {
  const host = fx.host!;
  const expr = 'SUM([RP0916宿主.数量] * [RP0916加工费.加工费(小计)])';
  const baseIds = ((await H.getComponent(host.id)).data.formulas ?? []).map((f: any) => f.id);
  const { drawer, editor } = await newHostFormula(page);
  await H.typeExpr(page, editor, expr);
  await H.drawerSave(page, drawer);
  await expect.poll(async () => (await H.messageTexts(page)).some((m) => m.startsWith(MSG.b6oldPrefix)),
    { timeout: 8_000, message: `应弹出以「${MSG.b6oldPrefix}」开头的提示` }).toBe(true);
  const msgs = await H.messageTexts(page);
  H.writeEvidence('T-B6b′-原写法拒绝提示.txt', msgs.join('\n') + '\n');
  await H.shot(page, 'T-B6b′-原写法拒绝提示');
  await expect(drawer).toBeVisible();
  await page.reload();
  const ids = ((await H.getComponent(host.id)).data.formulas ?? []).map((f: any) => f.id);
  expect(ids.sort(), '原写法：公式不应新增').toEqual([...baseIds].sort());
});

// ════════════════════════════════════════════════════════════════════ T-B7 · AC-7
test('T-B7 AC-7 本页签卡片无「小计列」组（宿主确有小计列）；点「数量」插入 [数量] 紫色', async ({ page }) => {
  const host = fx.host!;
  // 判别前提：宿主确有勾小计的列（读回核对）
  const back = (await H.getComponent(host.id)).data;
  const subs = (back.fields ?? []).filter((f: any) => f.is_subtotal).map((f: any) => f.name);
  console.log(`[T-B7] 宿主小计列=${JSON.stringify(subs)}`);
  expect(subs, '前提：宿主应确有小计列「成本」').toEqual(['成本']);
  const baseIds = (back.formulas ?? []).map((f: any) => f.id);

  const { drawer, editor, baseCount } = await newHostFormula(page);
  const self = H.cardOf(drawer, N.host);
  await expect(self, '左栏应有本页签卡片').toBeVisible();
  const selfText = await self.innerText();
  console.log(`[T-B7] 本页签卡片文字=${JSON.stringify(selfText)}`);
  expect(selfText, '本页签卡片应有明细组').toContain('明细');
  expect(selfText.includes('小计列'), '① 本页签卡片不应有「小计列」组').toBe(false);
  expect(selfText.includes('成本(小计)'), '① 本页签卡片不应出现「成本(小计)」芯片').toBe(false);
  // 阳性对照：同一抽屉的 RP0916加工费 卡片确有「小计列」组（证明判据能抓到该组）
  expect(await H.cardOf(drawer, N.fee).innerText(), '对照：加工费卡片应有「小计列」组').toContain('小计列');
  await H.shot(drawer, 'T-B7-本页签卡片');

  const r = await H.insertedByChip(page, editor, H.chip(self, '数量'));
  expect(r.blocks.length).toBe(1);
  H.expectBlock(r.blocks[0], 'purple', 'AC-7②');
  await H.drawerSave(page, drawer);
  const { data } = await H.persistFormulas(page, host, drawer, baseCount + 1);
  const f = newest(data, baseIds);
  const lt = await H.listTextOf(page, host, f.id);
  expect(lt.text, '② 插入文字（列表「表达式」列）').toBe('[数量]');
});

// ════════════════════════════════════════════════════════════════════ T-B8 · AC-8
test('T-B8a AC-8a 行键不可比页签：明细灰且不可点，悬停提示逐字', async ({ page }) => {
  const { drawer, editor } = await newHostFormula(page);
  const proc = H.cardOf(drawer, N.proc);
  const c = H.chip(proc, '工时');
  await expect(c, '工序卡应有「工时」芯片').toBeVisible();
  const st = await c.evaluate((el) => {
    const cs = getComputedStyle(el);
    return { cursor: cs.cursor, color: cs.color };
  });
  console.log(`[T-B8a] 工时芯片样式=${JSON.stringify(st)}`);
  expect(st.cursor, '明细芯片应不可点（not-allowed）').toBe('not-allowed');
  const [r, g, b] = (st.color.match(/\d+/g) ?? []).map(Number);
  expect(r === g && g === b, `明细芯片应为灰色，实际 ${st.color}`).toBe(true);

  await c.click({ force: true });
  await page.waitForTimeout(300);
  expect((await H.readEditor(editor)).raw.trim(), '点灰色芯片不应插入任何文字').toBe('');

  await c.hover();
  const tip = page.locator('[role="tooltip"], .ant-tooltip').filter({ visible: true });
  await expect.poll(async () => (await tip.allInnerTexts()).map((s) => s.trim()), { timeout: 5000, message: '悬停提示' })
    .toContain(MSG.tip8);
  await H.shot(drawer, 'T-B8a-悬停提示');
});

test('T-B8b AC-8b 小计组件：明细→红+拒绝；小计列→黄+成功存 component_subtotal', async ({ page }) => {
  const sub = fx.sub!;
  const baseIds = ((await H.getComponent(sub.id)).data.formulas ?? []).map((f: any) => f.id);
  const { drawer, editor, baseCount } = await newHostFormula(page, sub);
  const fee = H.cardOf(drawer, N.fee);

  const r1 = await H.insertedByChip(page, editor, H.chip(fee, '加工费'));
  expect(r1.blocks.length).toBe(1);
  H.expectBlock(r1.blocks[0], 'red', 'AC-8b 明细');
  await H.shot(drawer, 'T-B8b-1-明细红块');
  await H.drawerSave(page, drawer);
  await H.expectMessage(page, MSG.b8, 'AC-8b 明细拒绝');
  await expect(drawer, 'AC-8b 保存被拒时抽屉仍打开').toBeVisible();
  await H.shot(page, 'T-B8b-2-拒绝提示');

  const r2 = await H.insertedByChip(page, editor, H.chip(fee, '加工费(小计)'));
  expect(r2.blocks.length).toBe(1);
  H.expectBlock(r2.blocks[0], 'yellow', 'AC-8b 小计列');
  await H.shot(drawer, 'T-B8b-3-小计列黄块');
  await H.drawerSave(page, drawer);
  const { raw, data } = await H.persistFormulas(page, sub, drawer, baseCount + 1);
  H.writeEvidence('T-B8b-AC8b-component.json', raw);
  const f = newest(data, baseIds);
  const refs = H.refTokens(f.expression);
  console.log(`[T-B8b] 存储=${JSON.stringify(f.expression)}`);
  expect(refs.length).toBe(1);
  expect(refs[0].type).toBe('component_subtotal');
  expect(refs[0].value).toBe('加工费');
});

// ════════════════════════════════════════════════════════════════════ T-B9 · AC-9
/**
 * 在一个新公式里：点「明细·加工费」→ 输入「 + 」→ 点「小计列·加工费(小计)」→ 保存；返回列表文字。
 * search 非空时，两次点击都在搜索态下完成。
 */
async function insertPairAndSave(page: Page, search: string, tag: string) {
  const host = fx.host!;
  const baseIds = ((await H.getComponent(host.id)).data.formulas ?? []).map((f: any) => f.id);
  const { drawer, editor, baseCount } = await newHostFormula(page);
  const box = drawer.getByPlaceholder('搜索页签或字段名');
  await box.fill(search);
  await page.waitForTimeout(400);
  await H.chip(H.cardOf(drawer, N.fee), '加工费').click();
  await H.caretEnd(page, editor); await page.keyboard.insertText(' + ');
  if (search) await expect(box, '点完芯片后仍应处于搜索态').toHaveValue(search);
  await H.chip(H.cardOf(drawer, N.fee), '加工费(小计)').click();
  await page.waitForTimeout(300);
  const r = await H.readEditor(editor);
  expect(r.blocks.map((b) => b.color), `${tag} 块颜色`).toEqual(['blue', 'yellow']);
  await H.shot(drawer, `T-B9-${tag}`);
  await H.drawerSave(page, drawer);
  const { data } = await H.persistFormulas(page, host, drawer, baseCount + 1);
  return (await H.listTextOf(page, host, newest(data, baseIds).id)).text;
}

test('T-B9 AC-9 搜索态与非搜索态插入文字两两逐字相同', async ({ page }) => {
  const inSearch = await insertPairAndSave(page, '加工费', '搜索态');
  const plain = await insertPairAndSave(page, '', '非搜索态');
  H.writeEvidence('T-B9-AC9-插入文字.json', { inSearch, plain });
  const want = '[RP0916加工费.加工费] + [RP0916加工费.加工费(小计)]';
  expect(H.noWs(inSearch), '搜索态：明细/小计列插入文字').toBe(H.noWs(want));
  expect(plain, '非搜索态与搜索态逐字相同').toBe(inSearch);
});

// ════════════════════════════════════════════════════════════════════ T-B10 · AC-10
test('T-B10 AC-10 原型对齐：分组顺序/芯片文字/图例/占位 + 整屏截图（状态 A / F）', async ({ page }) => {
  const { drawer, editor } = await newHostFormula(page);

  // 状态 F 对应：公式框为空 → 占位文字
  const ph = await editor.evaluate((el) => {
    const attr = ['placeholder', 'data-placeholder', 'aria-placeholder']
      .map((a) => el.getAttribute(a)).find((v) => v);
    if (attr) return attr;
    const before = getComputedStyle(el, '::before').content;
    return before && before !== 'none' ? before.replace(/^"|"$/g, '') : '';
  });
  console.log(`[T-B10] 占位文字=「${ph}」`);
  expect(ph, '占位文字应能读到').toBeTruthy();
  expect(ph).toBe('例:[投料.金额] * [加工.工时] + [回料.金额(小计)] + [回料(总计)]');
  await H.shot(page, 'T-B10-状态F-空公式框-整屏');

  // 左栏
  const all = await drawer.innerText();
  for (const s of ['页签组件与可选字段']) expect(all, `左栏标题「${s}」`).toContain(s);
  await expect(drawer.getByPlaceholder('搜索页签或字段名')).toBeVisible();
  const feeText = (await H.cardOf(drawer, N.fee).innerText()).replace(/\s+/g, ' ');
  console.log(`[T-B10] 加工费卡片=「${feeText}」`);
  const iD = feeText.indexOf('明细'), iS = feeText.indexOf('小计列'), iT = feeText.indexOf('页签总计');
  expect(iD >= 0 && iS > iD && iT > iS, '分组顺序应为 明细 / 小计列 / 页签总计').toBe(true);
  const chips = async (from: number, to: number) =>
    feeText.slice(from, to).replace(/^(明细|小计列|页签总计)/, '').trim().split(' ').filter(Boolean);
  expect(await chips(iD, iS), '明细组芯片').toEqual(['加工费', '备注数']);
  expect(await chips(iS, iT), '小计列组芯片').toEqual(['加工费(小计)']);
  expect(await chips(iT, feeText.length), '页签总计组芯片').toEqual(['RP0916加工费(总计)']);

  // 图例（原型状态 A 逐字）
  for (const s of ['块底色 = 引用语义', '普通引用 · [页签.列]', '小计 · [页签.列(小计)]', '总计', '本页签', '非法',
    '括号字色 = 配对深度']) {
    expect(all, `图例文字「${s}」`).toContain(s);
  }

  // 状态 A：四种写法 + 本页签
  await editor.click();
  await page.keyboard.insertText(`${MIX} + [数量]`);
  await page.waitForTimeout(400);
  const r = await H.readEditor(editor);
  console.log(`[T-B10] 状态A blocks=${JSON.stringify(r.blocks.map((b) => [b.display, b.color]))}`);
  expect(r.blocks.map((b) => b.color), '状态 A 块颜色').toEqual(['blue', 'yellow', 'green', 'blue', 'purple']);
  await H.shot(page, 'T-B10-状态A-整屏');
  await H.shot(drawer, 'T-B10-状态A-抽屉');
});

// ════════════════════════════════════════════════════════════════════ T-B11 · AC-18
/**
 * Excel 组件「新增一列页签连表公式」的入口：现有 spec 无先例，选择器按文案猜测；
 * 走不通时落 DOM 文字与截图到证据目录并判【未验证·入口】，不伪装成产品缺陷。
 */
async function addExcelTabJoinColumn(page: Page, tag: string) {
  // 探测 260917：「添加列」按钮 → 行内来源下拉「固定值 / 页签连表公式」→ 行内「配置公式」。
  // 表格行本身带 role=button（可拖拽排序），占位行不是 .ant-table-row。
  const rows = page.locator('.ant-table-tbody tr.ant-table-row').filter({ visible: true });
  try {
    const before = await rows.count();
    await page.locator('button.ant-btn').filter({ hasText: /添加列/ }).first().click();
    await expect.poll(() => rows.count(), { message: '点「添加列」后应多一行' }).toBe(before + 1);
    const row = rows.last();
    await row.locator('.ant-select').first().click();
    // 已关闭的下拉仍留在 DOM（run1 实测会先匹配到上一列的隐藏选项）⇒ 只取可见项
    await page.locator('.ant-select-item-option').filter({ hasText: /^页签连表公式$/ }).filter({ visible: true }).first().click();
    await page.waitForTimeout(400);
    await row.locator('button.ant-btn').filter({ hasText: /配\s*置\s*公\s*式/ }).first().click();
    const drawer = H.drawerLoc(page);
    await expect(drawer).toBeVisible({ timeout: 10_000 });
    await expect.poll(() => H.leftCards(drawer).count(), { timeout: 15_000 }).toBeGreaterThan(0);
    return drawer;
  } catch (e) {
    H.writeEvidence(`${tag}-入口失败-DOM.txt`, await page.locator('body').innerText());
    await H.shot(page, `${tag}-入口失败`);
    throw new Error(`[未验证·入口] Excel 组件新增页签连表公式列的入口没走通：${e}`);
  }
}

test('T-B11 AC-18 Excel 组件抽屉：小计列 / 明细 两列保存 + 重开', async ({ page }) => {
  const ex = fx.excel!;
  await H.openComponent(page, ex);
  await H.shot(page, 'T-B11-0-Excel组件页');

  const cases = [
    { chipText: '加工费(小计)', color: 'yellow' as const, display: 'RP0916加工费·加工费(小计)', expr: '[RP0916加工费.加工费(小计)]' },
    { chipText: '备注数', color: 'blue' as const, display: 'RP0916加工费·备注数', expr: '[RP0916加工费.备注数]' },
  ];
  const seen: Array<{ display: string; color: string }> = [];
  for (const [i, c] of cases.entries()) {
    const drawer = await addExcelTabJoinColumn(page, `T-B11-${i + 1}`);
    const editor = H.editorOf(drawer);
    const r = await H.insertedByChip(page, editor, H.chip(H.cardOf(drawer, N.fee), c.chipText));
    expect(r.blocks.length).toBe(1);
    expect(r.blocks[0].display, `① 第 ${i + 1} 列块文字`).toBe(c.display);
    H.expectBlock(r.blocks[0], c.color, `AC-18① 第 ${i + 1} 列`);
    seen.push({ display: r.blocks[0].display, color: r.blocks[0].color });
    await H.shot(drawer, `T-B11-${i + 1}-块`);
    await H.drawerSave(page, drawer);
    await expect(drawer).toBeHidden({ timeout: 8_000 });
    await H.pageSave(page);
  }

  let cols: any[] = [];
  let raw = '';
  await expect.poll(async () => {
    const g = await H.getComponent(ex.id);
    raw = g.raw;
    cols = H.parseExcelColumns(g.data.excelColumns).filter((x: any) => x.source_type === 'TAB_JOIN_FORMULA');
    return cols.length;
  }, { timeout: 10_000, message: 'Excel 组件应落库 2 列页签连表公式' }).toBe(2);
  H.writeEvidence('T-B11-AC18-component.json', raw);
  console.log(`[T-B11] excelColumns=${JSON.stringify(cols)}`);
  expect(cols.map((x) => x.expression), '② 两列 expression 逐字').toEqual(cases.map((c) => c.expr));
  for (const col of cols) {
    expect(col.tabs?.length, `② ${col.col_key} tabs 恰好一项`).toBe(1);
    expect(col.tabs[0].alias).toBe(N.fee);
    expect(String(col.tabs[0].tabKey).startsWith(fx.fee!.id), `② ${col.col_key} tabKey 以加工费组件 id 开头`).toBe(true);
  }

  // ③ 重开两列
  const rows = page.locator('.ant-table-tbody tr.ant-table-row').filter({ visible: true });
  expect(await rows.count(), '③ 页面应有 2 行可配置列').toBeGreaterThanOrEqual(2);
  for (const [i, c] of cases.entries()) {
    const colIdx = cols.findIndex((x) => x.expression === c.expr);
    // run2 实测：保存后行内按钮文字变为「公式：[原文]」（不再是「配置公式」）
    const btn = rows.nth(colIdx).locator('button.ant-btn').filter({ hasText: /公\s*式/ }).first();
    const btnText = (await btn.innerText()).trim();
    console.log(`[T-B11] 第 ${i + 1} 列行内按钮文字=「${btnText}」`);
    await btn.click();
    const d = H.drawerLoc(page);
    await expect(d).toBeVisible();
    await page.waitForTimeout(500);
    const r = await H.readEditor(H.editorOf(d));
    console.log(`[T-B11] 重开第 ${i + 1} 列 blocks=${JSON.stringify(r.blocks)}`);
    expect(r.blocks.map((b) => ({ display: b.display, color: b.color })),
      `③ 重开第 ${i + 1} 列块文字/颜色与保存前相同`).toEqual([seen[i]]);
    const again = H.parseExcelColumns((await H.getComponent(ex.id)).data.excelColumns)
      .filter((x: any) => x.source_type === 'TAB_JOIN_FORMULA')[colIdx];
    expect(again.expression, `③ 重开后第 ${i + 1} 列存储文字不变`).toBe(c.expr);
    await H.shot(d, `T-B11-3-重开第${i + 1}列`);
    await d.locator('button').filter({ hasText: /取\s*消/ }).first().click();
    await expect(d).toBeHidden();
  }
});

// ════════════════════════════════════════════════════════════════════ T-B11 ④⑤ · AC-18（D-11 / D-9）
async function excelColsCount() {
  return H.parseExcelColumns((await H.getComponent(fx.excel!.id)).data.excelColumns)
    .filter((x: any) => x.source_type === 'TAB_JOIN_FORMULA').length;
}

test('T-B11④ AC-18④ Excel 列手写 [RP0916加工费.备注数(小计)] → 红块 + 拒绝 + excelColumns 不新增', async ({ page }) => {
  const before = await excelColsCount();
  await H.openComponent(page, fx.excel!);
  const drawer = await addExcelTabJoinColumn(page, 'T-B11④');
  const r = await H.typeExpr(page, H.editorOf(drawer), '[RP0916加工费.备注数(小计)]');
  expect(r.blocks.length).toBe(1);
  H.expectBlock(r.blocks[0], 'red', 'AC-18④');
  await H.drawerSave(page, drawer);
  await H.expectMessage(page, MSG.a5, 'AC-18④');
  await H.shot(page, 'T-B11④-拒绝提示');
  await expect(drawer).toBeVisible();
  await page.reload();
  const after = await excelColsCount();
  console.log(`[T-B11④] 页签连表公式列数 before=${before} after=${after}`);
  expect(after, '④ excelColumns 不新增').toBe(before);
});

const IF_FNS = ['SUMIF', 'COUNTIF', 'AVGIF', 'MINIF', 'MAXIF'];
async function ifButtons(drawer: import('@playwright/test').Locator) {
  const texts = (await drawer.locator('button').allInnerTexts()).map((t) => t.replace(/\s+/g, ''));
  return IF_FNS.filter((f) => texts.includes(f));
}

test('T-B11⑤ AC-18⑤ Excel 抽屉无 SUMIF 类按钮（页签组件对照仍有）；手写 SUMIF 被拒', async ({ page }) => {
  // 对照：页签组件抽屉仍有这组按钮
  const { drawer: hd } = await newHostFormula(page);
  const hostIf = await ifButtons(hd);
  console.log(`[T-B11⑤] 宿主抽屉 IF 按钮=${JSON.stringify(hostIf)}`);
  expect(hostIf, '⑤ 对照：页签组件抽屉仍有 SUMIF 类按钮').toEqual(IF_FNS);
  await H.shot(hd, 'T-B11⑤-1-宿主抽屉工具条');
  await page.reload();

  const before = await excelColsCount();
  await H.openComponent(page, fx.excel!);
  const drawer = await addExcelTabJoinColumn(page, 'T-B11⑤');
  expect(await drawer.locator('button').count(), 'Excel 抽屉应能读到按钮（非空前提）').toBeGreaterThan(0);
  const exIf = await ifButtons(drawer);
  console.log(`[T-B11⑤] Excel 抽屉 IF 按钮=${JSON.stringify(exIf)}`);
  expect(exIf, '⑤ Excel 抽屉不应有 SUMIF 类按钮').toEqual([]);
  await H.shot(drawer, 'T-B11⑤-2-Excel抽屉工具条');

  await H.typeExpr(page, H.editorOf(drawer), 'SUMIF([RP0916加工费.备注数] > 0, [RP0916加工费.加工费])');
  await H.drawerSave(page, drawer);
  await H.expectMessage(page, MSG.e18, 'AC-18⑤');
  await H.shot(page, 'T-B11⑤-3-拒绝提示');
  await expect(drawer).toBeVisible();
  await page.reload();
  expect(await excelColsCount(), '⑤ excelColumns 不新增').toBe(before);
});
