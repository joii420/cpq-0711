/**
 * task-260909 · S1 只读片 · **B 组：核价树渲染 + 轴口径统一**
 *
 * 覆盖 AC：**AC-6 / AC-7 / AC-8 / AC-9 / AC-10 / AC-11 / AC-12**
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 🚨 **本组是全任务防假绿的重灾区**（`test.md §4`）：
 *   AC-10 / AC-11 / AC-12 在**修复前实测是 0 行**。所以本组
 *   🚫 **一律不写 `≥N` 型断言**，全部写**具体行数 + 具体值集合**；
 *   每处断言前先断言「表头非空 + 行数 > 0」，否则「循环 0 次」会让断言压根不执行、
 *   测试照样报绿（`testing.md §3`）。
 *
 * ⚠️ **先跑 A 组**：A 组（报价侧）是定位器阳性对照。A 组不绿时本组的红**不可归因**。
 *
 * 🚫 写入面：无。只 `goto` + 读 DOM。不点「刷新基础数据」（AC-20 属 S-全局 片）。
 */
import { test, expect, Locator } from '@playwright/test';
import { loginAs } from './fixtures/auth';
import {
  ADMIN, QUOTATION_NO, gotoStep2, switchToCosting, cardOf, switchTabInCard,
  readTable, col, colIdx, loadingCountIn, shot, shotCard,
  writeEvidence, appendEvidence, TableSnapshot,
} from './fixtures/task260909tree';

test.describe.configure({ mode: 'serial' });

/** 「核价渲染失败」红框：AC-6 的可观测断言就是它一个都不出现。 */
const FAIL_BOX = '核价渲染失败';

/** 把一张表快照转成可贴进报告的原文。 */
function dump(tag: string, t: TableSnapshot): string {
  const lines = [`${tag}  headers=${JSON.stringify(t.headers)}  treeCol=#${t.treeCol}`];
  t.rows.forEach((r, i) => lines.push(`  row[${i}] indent=${t.indentPx[i]}px ${JSON.stringify(r)}`));
  return lines.join('\n');
}

/** 断言「表真的渲染了」——所有行数断言的前置，专防空跑假绿。 */
function assertRendered(t: TableSnapshot, tag: string) {
  expect(t.headers.length, `${tag}：表头为空 ⇒ 表没渲染出来，判【未验证】而不是 0 行`).toBeGreaterThan(0);
  expect(t.rows.length, `${tag}：0 行 ⇒ 后面的「每行都满足 X」会空跑通过，必须先在这里硬失败`).toBeGreaterThan(0);
}

async function enterCosting(page: any) {
  await loginAs(page, ADMIN.username, ADMIN.password);
  await gotoStep2(page);
  await switchToCosting(page);
}

// ══════════════════════════════════════════════════════════════════════════
// AC-6：4 张卡片均无红色「核价渲染失败」框
// ══════════════════════════════════════════════════════════════════════════
test('T-B1 / AC-6：核价单 4 张产品卡片均无「核价渲染失败」红框', async ({ page }) => {
  await enterCosting(page);
  await shot(page, 'AC-06-costing-cards');

  const cards = page.locator('.qt-product-card');
  const n = await cards.count();

  // 🚨 阳性前提：先证明 4 张卡片确实渲染了。
  //    只断言「红框数 = 0」是典型空验证 —— 页面整个没加载出来时它也成立。
  expect(n, `AC-6 前提：核价单应有 4 张产品卡片（S0001/S0004/S0008/S0012），实得 ${n}`).toBe(4);

  const detail: string[] = [];
  for (const part of ['S0001', 'S0004', 'S0008', 'S0012']) {
    const card = await cardOf(page, part);
    const boxes = await card.locator(`text=${FAIL_BOX}`).count();
    const tabs = await card.locator('button.qt-tab-btn').count();
    console.log(`[AC-6] ${part}: 红框=${boxes} 页签数=${tabs}`);
    detail.push(`${part}: 「${FAIL_BOX}」出现 ${boxes} 次, 页签数=${tabs}`);
    // 第二重阳性前提：卡片里得有页签，否则「没有红框」只是因为什么都没渲染
    expect(tabs, `AC-6 前提：${part} 卡片内应渲染出页签按钮，实得 ${tabs}`).toBeGreaterThan(0);
    expect(boxes, `AC-6：${part} 卡片出现了「${FAIL_BOX}」红框`).toBe(0);
    await shotCard(card, `AC-06-${part}`);
  }
  writeEvidence('AC-06-no-error-box.txt', detail.join('\n') + '\n');
});

// ══════════════════════════════════════════════════════════════════════════
// AC-7 + AC-9：S0001 BOM 页签 7 行 / 树列顺序 / 4 层缩进 / 两行具体业务值
// ══════════════════════════════════════════════════════════════════════════
test('T-B2 / AC-7 + AC-9：S0001 BOM 页签 7 行、树列顺序固定、最大 4 层、行值正确', async ({ page }) => {
  await enterCosting(page);
  const card = await cardOf(page, 'S0001');
  await switchTabInCard(card, 'BOM');
  const t = await readTable(card);
  console.log(dump('[AC-7] S0001/BOM', t));
  writeEvidence('AC-07-S0001-BOM.txt', dump('S0001 / BOM 页签（AC-7 / AC-9）', t) + '\n');
  await shotCard(card, 'AC-07-S0001-BOM');

  assertRendered(t, 'AC-7 S0001/BOM');
  expect(await loadingCountIn(card), 'AC-7：BOM 页签不应出现「加载中…」').toBe(0);

  // ① 行数 = 7（AC-7 原文）
  expect(t.rows.length, `AC-7：S0001 BOM 页签应 7 行，实得 ${t.rows.length}`).toBe(7);

  // ② 树列自上而下的**顺序**（AC-7 原文逐字）
  const tree = col(t, t.treeCol).map((s) => s.replace(/[▼▶\s]/g, ''));
  console.log('[AC-7] 树列 =', JSON.stringify(tree));
  expect(
    tree,
    'AC-7：BOM 树列自上而下应为 300001/300012/300015/992/300013/991/300014',
  ).toEqual(['300001', '300012', '300015', '992', '300013', '991', '300014']);

  // ③ 最大缩进 4 层（AC-7 原文：300001 → 300012 → 300015 → 992）
  //    量法与实现无关：量首列内容元素相对 td 的左偏移，取不同层级数。
  const px = t.indentPx;
  expect(px.every((p) => p >= 0), 'AC-7：缩进量取不到（-1）⇒ 量具问题，判【未验证】').toBe(true);
  const levels = Array.from(new Set(px)).sort((a, b) => a - b);
  console.log('[AC-7] 缩进档位(px) =', JSON.stringify(levels), ' 逐行 =', JSON.stringify(px));
  appendEvidence('AC-07-S0001-BOM.txt',
    `\n缩进档位(px)=${JSON.stringify(levels)}  逐行=${JSON.stringify(px)}\n`);
  expect(levels.length, `AC-7：最大缩进层级应为 4 层，实测 ${levels.length} 档 ${JSON.stringify(levels)}`).toBe(4);

  // ④ 该 4 层必须正好落在 300001 → 300012 → 300015 → 992 这条链上（严格递增）
  const idxOf = (p: string) => tree.indexOf(p);
  const chain = ['300001', '300012', '300015', '992'].map((p) => px[idxOf(p)]);
  console.log('[AC-7] 链 300001→300012→300015→992 的缩进 =', JSON.stringify(chain));
  for (let i = 1; i < chain.length; i++) {
    expect(
      chain[i],
      `AC-7：${['300001', '300012', '300015', '992'][i]} 的缩进应比上一层更深，实得 ${JSON.stringify(chain)}`,
    ).toBeGreaterThan(chain[i - 1]);
  }
  // 992 必须落在最深一档 —— 否则「4 档」可能来自别的分支
  expect(chain[3], 'AC-7：992 应处在最深一层（第 4 层）').toBe(levels[levels.length - 1]);

  // ⑤ AC-9：300012 行 / 991 行的具体业务值
  //    ⚠️ 列名用多候选：AC 写「组成数量 / 单位」，COMP-2299 实配字段名是
  //       「组成用量 / 组成用量单位」。命中 0 个或 ≥2 个都会硬失败并打印实有表头。
  const cQty = colIdx(t, ['组成数量', '组成用量'], 'AC-9 组成数量列');
  const cUnit = colIdx(t, ['单位', '组成用量单位'], 'AC-9 单位列');
  const cSeq = colIdx(t, ['项次'], 'AC-9 项次列');

  const row12 = t.rows[idxOf('300012')];
  const row991 = t.rows[idxOf('991')];
  expect(row12, 'AC-9：找不到 300012 行').toBeTruthy();
  expect(row991, 'AC-9：找不到 991 行').toBeTruthy();
  console.log('[AC-9] 300012 行 =', JSON.stringify(row12));
  console.log('[AC-9] 991   行 =', JSON.stringify(row991));
  appendEvidence('AC-07-S0001-BOM.txt',
    `\n[AC-9] 300012 => 组成数量="${row12[cQty]}" 单位="${row12[cUnit]}" 项次="${row12[cSeq]}"\n` +
    `[AC-9] 991    => 组成数量="${row991[cQty]}" 单位="${row991[cUnit]}"\n`);

  // 数值比较用数值口径（显示可能是 1 / 1.00 / 1.000000，都算 1）
  expect(Number(row12[cQty]), 'AC-9：300012 行「组成数量」应为 1').toBe(1);
  expect(row12[cUnit], 'AC-9：300012 行单位应为 PCS').toBe('PCS');
  expect(Number(row12[cSeq]), 'AC-9：300012 行项次应为 10').toBe(10);
  expect(Number(row991[cQty]), 'AC-9：991 行「组成数量」应为 3.5').toBe(3.5);
  expect(row991[cUnit], 'AC-9：991 行单位应为 KG').toBe('KG');
});

// ══════════════════════════════════════════════════════════════════════════
// AC-8：三张无 BOM 的卡片各 1 行、业务列「—」、不报错、无「加载中…」
// ══════════════════════════════════════════════════════════════════════════
test('T-B3 / AC-8：S0004/S0008/S0012 的 BOM 页签各 1 行且业务列为「—」', async ({ page }) => {
  await enterCosting(page);
  const expected: Record<string, string> = { S0004: '300021', S0008: '300031', S0012: '300041' };
  const report: string[] = [];

  for (const part of Object.keys(expected)) {
    const card = await cardOf(page, part);
    await switchTabInCard(card, 'BOM');
    const t = await readTable(card);
    console.log(dump(`[AC-8] ${part}/BOM`, t));
    report.push(dump(`${part} / BOM`, t));

    assertRendered(t, `AC-8 ${part}/BOM`);
    expect(t.rows.length, `AC-8：${part} BOM 页签应恰好 1 行，实得 ${t.rows.length}`).toBe(1);

    const tree = col(t, t.treeCol).map((s) => s.replace(/[▼▶\s]/g, ''));
    expect(tree[0], `AC-8：${part} 的唯一一行树列应为 ${expected[part]}`).toBe(expected[part]);

    // 不报错 + 不出现「加载中…」（AP-38：0 行 driver 的鬼魂行永久「加载中」是历史坑）
    expect(await card.locator(`text=${FAIL_BOX}`).count(), `AC-8：${part} 不应出现红框`).toBe(0);
    expect(await loadingCountIn(card), `AC-8：${part} BOM 页签不应出现「加载中…」`).toBe(0);

    // 业务列显示「—」：树列/系统固定列之外的单元格
    const biz = t.rows[0].filter((_, i) => i !== t.treeCol);
    const dash = biz.filter((c) => c === '—');
    console.log(`[AC-8] ${part} 业务列 = ${JSON.stringify(biz)}  其中「—」${dash.length}/${biz.length}`);
    expect(biz.length, `AC-8：${part} 除树列外应还有业务列，实得 0 ⇒ 判【未验证】`).toBeGreaterThan(0);
    expect(
      dash.length,
      `AC-8：${part} 的业务列应全部显示「—」，实得 ${JSON.stringify(biz)}`,
    ).toBe(biz.length);

    await shotCard(card, `AC-08-${part}-BOM`);
  }
  writeEvidence('AC-08-empty-bom.txt', report.join('\n\n') + '\n');
});

// ══════════════════════════════════════════════════════════════════════════
// AC-10：材质元素页签 4 行（修复前 0 行）
// ══════════════════════════════════════════════════════════════════════════
test('T-B4 / AC-10：S0001 材质元素页签 4 行，元素码 {Cu,301,Ag,Cu}，2 行归 300013 / 2 行归 300015',
  async ({ page }) => {
    await enterCosting(page);
    const card = await cardOf(page, 'S0001');
    await switchTabInCard(card, '材质元素');
    const t = await readTable(card);
    console.log(dump('[AC-10] S0001/材质元素', t));
    writeEvidence('AC-10-S0001-material-element.txt', dump('S0001 / 材质元素（AC-10）', t) + '\n');
    await shotCard(card, 'AC-10-S0001-材质元素');

    // 🚨 修复前实测 0 行 ⇒ 这里的「非空 + 精确 4」是本条 AC 的全部价值所在。
    //    写成 `≥0` 会永远绿，那正是 `test.md §4` 点名的空验证。
    assertRendered(t, 'AC-10 S0001/材质元素');
    expect(await loadingCountIn(card), 'AC-10：不应出现「加载中…」').toBe(0);
    expect(t.rows.length, `AC-10：S0001 材质元素页签应 4 行（修复前为 0 行），实得 ${t.rows.length}`).toBe(4);

    // 元素编码集合 = {Cu, 301, Ag, Cu}（多重集：Cu 出现两次）
    const cCode = colIdx(t, ['元素编码', '元素代码'], 'AC-10 元素编码列');
    const codes = col(t, cCode).slice().sort();
    console.log('[AC-10] 元素编码 =', JSON.stringify(col(t, cCode)));
    expect(codes, 'AC-10：元素编码集合应为 {Cu, 301, Ag, Cu}').toEqual(['301', 'Ag', 'Cu', 'Cu'].sort());

    // 归属料号：2 行 300013、2 行 300015
    const cPart = colIdx(t, ['生产料号'], 'AC-10 归属料号列');
    const parts = col(t, cPart);
    console.log('[AC-10] 归属料号 =', JSON.stringify(parts));
    expect(parts.filter((p) => p === '300013').length, 'AC-10：应有 2 行归属料号 300013').toBe(2);
    expect(parts.filter((p) => p === '300015').length, 'AC-10：应有 2 行归属料号 300015').toBe(2);
  });

// ══════════════════════════════════════════════════════════════════════════
// AC-11：产品页签 5 行
// ══════════════════════════════════════════════════════════════════════════
test('T-B5 / AC-11：S0001 产品页签 5 行，生产料号集合 {300001,300012,300013,300014,300015}',
  async ({ page }) => {
    await enterCosting(page);
    const card = await cardOf(page, 'S0001');
    await switchTabInCard(card, '产品');
    const t = await readTable(card);
    console.log(dump('[AC-11] S0001/产品', t));
    writeEvidence('AC-11-S0001-product.txt', dump('S0001 / 产品（AC-11）', t) + '\n');
    await shotCard(card, 'AC-11-S0001-产品');

    assertRendered(t, 'AC-11 S0001/产品');
    expect(await loadingCountIn(card), 'AC-11：不应出现「加载中…」').toBe(0);
    expect(t.rows.length, `AC-11：S0001 产品页签应 5 行（修复前为 0 行），实得 ${t.rows.length}`).toBe(5);

    const cPart = colIdx(t, ['生产料号'], 'AC-11 生产料号列');
    const set = Array.from(new Set(col(t, cPart))).sort();
    console.log('[AC-11] 生产料号 =', JSON.stringify(col(t, cPart)));
    expect(set, 'AC-11：生产料号集合应为 {300001,300012,300013,300014,300015}')
      .toEqual(['300001', '300012', '300013', '300014', '300015']);
  });

// ══════════════════════════════════════════════════════════════════════════
// AC-12：加工费页签 4 行（依赖配置动作：COMP-2319 方言改回 COST_BASIC 并重编译）
// ══════════════════════════════════════════════════════════════════════════
test('T-B6 / AC-12：S0001 加工费页签 4 行，工序号 {Z002,Z008,Z053,Z490}，全部归属 300012',
  async ({ page }) => {
    await enterCosting(page);
    const card = await cardOf(page, 'S0001');
    await switchTabInCard(card, '加工费');
    const t = await readTable(card);
    console.log(dump('[AC-12] S0001/加工费', t));
    writeEvidence('AC-12-S0001-process-fee.txt', dump('S0001 / 加工费（AC-12）', t) + '\n');
    await shotCard(card, 'AC-12-S0001-加工费');

    assertRendered(t, 'AC-12 S0001/加工费');
    expect(await loadingCountIn(card), 'AC-12：不应出现「加载中…」').toBe(0);
    expect(t.rows.length, `AC-12：S0001 加工费页签应 4 行，实得 ${t.rows.length}`).toBe(4);

    // ⚠️ COMP-2319 实配字段名是「销售料号 / 要素名称 / 材料名 / 比例（%）/ 计价单位」，
    //    并没有叫「工序号」的列。AC-12 用的是业务说法 ⇒ 多候选匹配，
    //    命中 0 或 ≥2 都硬失败并打印实有表头，交主线判「AC 需澄清」还是「产品缺陷」。
    const cOp = colIdx(t, ['工序号', '工序编号', '要素名称'], 'AC-12 工序号列');
    const ops = Array.from(new Set(col(t, cOp))).sort();
    console.log('[AC-12] 工序号 =', JSON.stringify(col(t, cOp)));
    expect(ops, 'AC-12：工序号集合应为 {Z002, Z008, Z053, Z490}')
      .toEqual(['Z002', 'Z008', 'Z053', 'Z490']);

    const cPart = colIdx(t, ['归属料号', '生产料号', '销售料号'], 'AC-12 归属料号列');
    const parts = col(t, cPart);
    console.log('[AC-12] 归属料号 =', JSON.stringify(parts));
    expect(parts, 'AC-12：4 行应全部归属料号 300012').toEqual(['300012', '300012', '300012', '300012']);
  });
