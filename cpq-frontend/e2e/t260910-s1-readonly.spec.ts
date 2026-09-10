/**
 * task-260910 · **S-只读片**（零写库）
 * 认领 AC-1 / AC-2 / AC-3 / AC-4 / AC-6 / AC-8 / AC-9 / AC-10 / AC-12(只读子序列) / AC-14 第2条
 * 🚫 本片不执行任何 INSERT/UPDATE/DELETE。
 */
import { test, expect } from '@playwright/test';
import * as H from './t260910.helpers';

const BASE = 'QT-20260909-0799';   // CUST-0004 正泰 / S0001 / 完整态
const MIX  = 'QT-20260909-0794';   // CUST-0004 / 5 行：完整 + 3 个核价无明细 + 1 个未绑生产料号
const EMPTY = 'QT-20260907-0592'; // CUST-0001 / 1 行：customer_part_no NULL 且客户料号表无匹配（0652 因缺 customer_template_id，Step1「下一步」禁用，属既有夹具问题，换样本）
const DISAMB = 'QT-20260908-0613'; // CUST-0004 / 同一料号 T260907-M1 对两个客户产品编号

test('T0 · 环境正身：被测后端在 worktree 内且连 cpq_db_0724', async () => {
  await H.assertWorktreeAndDb();
});

test('T1.1/T2.1/T4.1/T4.2/T14.1 · 基准单头部左右两侧 + 边框 + 无料号信息 + 无模板徽标', async ({ page }) => {
  // 🔑 前置事实当场复查，🚫 不吃 2 小时前的采样（共享库有并发写入）
  const f = H.sqlRows(`SELECT p.customer_part_name, p.customer_product_no, m.production_no, li.customer_part_no, li.product_name_snapshot
    FROM quotation_line_item li
    JOIN quotation q ON q.id=li.quotation_id JOIN customer c ON c.id=q.customer_id
    LEFT JOIN ds_quote_customer_part p ON p.customer_no=c.code AND p.customer_product_no=li.customer_part_no
    LEFT JOIN ds_quote_material m ON m.customer_no=c.code AND m.material_no=li.product_part_no_snapshot
    WHERE q.quotation_number='${BASE}'`)[0];
  expect(f, `前置未满足：${BASE} 查不到行 ⇒ 夹具缺失，判【未验证】`).toBeTruthy();
  console.log('[前置实查] ' + JSON.stringify(f));

  await H.uiLogin(page);
  await H.openStep2(page, BASE);
  const cards = await H.readCards(page);
  expect(cards.length, `${BASE} 应至少 1 张产品卡片 ⇒ 0 张说明没渲染，断言会空跑`).toBeGreaterThan(0);
  const c = cards[0];
  console.log('[卡片0] ' + JSON.stringify(c));

  // AC-1：左侧 = [客户料号名称, 客户产品编号: xxx]，且在右块之前
  expect(c.leftTexts, 'AC-1 头部左侧文案序列').toEqual([f.customer_part_name, `客户产品编号: ${f.customer_product_no}`]);
  expect(c.domOrderOk, 'AC-1 左块必须在 DOM 中先于右块出现').toBe(true);
  // AC-2：右侧 = [产品名, 销售料号: xxx, 删除]，且无旧的「料号: xxx」徽标
  expect(c.rightTexts, 'AC-2 头部右侧文案序列').toEqual([f.product_name_snapshot, '销售料号: S0001', '删除']);
  const oldBadge = await page.locator('.qt-product-card').first()
    .locator('text=/(^|[^售])料号:\\s*S0001/').count();
  expect(oldBadge, 'AC-2 🚫 不得再出现旧的「料号: S0001」徽标').toBe(0);
  // AC-4-1：无内联 border + 计算色 = #e0e0e0
  expect(c.inlineStyle ?? '', 'AC-4 卡片不得有内联 border 样式').not.toContain('border');
  expect(c.borderTopColor, 'AC-4 边框应为 CSS 默认 #e0e0e0').toBe('rgb(224, 224, 224)');
  // AC-4-2：无「料号信息」按钮
  expect(c.hasPartInfoBtn, 'AC-4 头部不得存在「料号信息」按钮').toBe(false);
  // AC-14-1：无「模板:」徽标
  const moban = await page.locator('.qt-product-card').first().locator('text=/模板\\s*[:：]/').count();
  expect(moban, 'AC-14 卡片头部不得出现「模板: xxx」徽标').toBe(0);

  await H.shot(page, 'ac1-ac2-头部左右两侧');
  H.writeEvidence('ac1-ac2-ac4-头部与边框.txt',
    `前置实查=${JSON.stringify(f)}\n卡片0=${JSON.stringify(c, null, 1)}\n旧徽标计数=${oldBadge}\n模板徽标计数=${moban}\n`);
});

test('T3.1/T3.2 · 销售料号徽标弹生产料号浮层，五行取自核价侧', async ({ page }) => {
  const exp = H.sqlRows(`SELECT b.production_no, b.material_name, COALESCE(NULLIF(b.specification,''),'—') spec,
      COALESCE(NULLIF(b.dimension,''),'—') dim, COALESCE(NULLIF(b.old_material_no,''),'—') oldno
    FROM ds_quote_material m JOIN ds_cost_basic_material b ON b.production_no=m.production_no
    WHERE m.customer_no='CUST-0004' AND m.material_no='S0001'`)[0];
  expect(exp, '前置未满足：ds_quote_material→ds_cost_basic_material 链路查不到 ⇒ 判【未验证】').toBeTruthy();
  console.log('[核价侧期望] ' + JSON.stringify(exp));
  // 🔑 判别器：销售侧同名列与核价侧**四列全不同**，取错源一定露馅
  const wrong = H.sqlRows(`SELECT material_name, specification, dimension, old_material_no FROM ds_quote_material
     WHERE customer_no='CUST-0004' AND material_no='S0001'`)[0];
  console.log('[取错源会长这样(销售侧)] ' + JSON.stringify(wrong));

  await H.uiLogin(page);
  await H.openStep2(page, BASE);
  const pop = await H.openPopover(page, 0);
  console.log('[浮层原文]\n' + pop.raw);
  H.expectPopoverFive(pop, {
    料号: exp.production_no, 名称: exp.material_name, 规格: exp.spec, 尺寸: exp.dim, 旧料号: exp.oldno,
  }, 'AC-3');
  // AC-3 核心 bug 反向断言：第一行不许是销售料号
  expect(pop.raw.includes('S0001'), 'AC-3 🚫 浮层不得出现销售料号 S0001（那正是本次修的 bug）').toBe(false);
  await H.shot(page, 'ac3-生产料号浮层');
  H.writeEvidence('ac3-浮层五行.txt', `核价侧期望=${JSON.stringify(exp)}\n销售侧(取错源特征)=${JSON.stringify(wrong)}\n浮层原文:\n${pop.raw}\n`);
});

test('T4.3/T4.4 · match 请求零条 + 阳性对照证明观察手段有效', async ({ page }) => {
  const hits: string[] = [];
  page.on('request', (r) => { if (r.url().includes('/material-mappings/match')) hits.push(r.url()); });
  await H.uiLogin(page);
  await H.openStep2(page, BASE);
  await page.waitForTimeout(3000);
  const observed = hits.length;
  expect(observed, `AC-4 打开到渲染完成 /material-mappings/match 请求应为 0，实际 ${observed} 条：${hits.join(' , ')}`).toBe(0);

  // 🚨 阳性对照（testing.md §4.4）：断言「某事没发生」必须证明观察手段抓得到该事件
  const cid = H.sqlScalar(`SELECT id::text FROM customer WHERE code='CUST-0004'`);
  await page.evaluate((c) => fetch(`/api/cpq/customers/${c}/material-mappings/match?partNo=A002`).catch(() => {}), cid);
  await page.waitForTimeout(2500);
  expect(hits.length, '阳性对照失败：故意发一条 match 请求后监听器仍为 0 ⇒ **观察手段本身失效**，T4.3 的 0 是零证据').toBe(1);
  H.writeEvidence('ac4-match请求计数.txt',
    `渲染期间 match 请求数=${observed}（期望 0）\n阳性对照后=${hits.length}（期望 1，证明监听器抓得到）\n命中URL=${hits.join('\n')}\n`);
});

test('T6.1 · 一料号对多客户产品编号：按 line_item.customer_part_no 精确消歧', async ({ page }) => {
  const rows = H.sqlRows(`SELECT li.sort_order, li.customer_part_no, p.customer_part_name
     FROM quotation_line_item li
     JOIN quotation q ON q.id=li.quotation_id
     JOIN ds_quote_customer_part p ON p.customer_no='CUST-0004' AND p.customer_product_no=li.customer_part_no
     WHERE q.quotation_number='${DISAMB}' ORDER BY li.sort_order`);
  expect(rows.length, `前置未满足：${DISAMB} 没取到行 ⇒ 判【未验证】`).toBeGreaterThan(1);
  const names = new Set(rows.map((r) => r.customer_part_name));
  expect(names.size, `前置未满足：${DISAMB} 的行都指向同一个客户料号名 ⇒ 这条判据落在恒为 1 的维度上（testing.md §5.5），验不出消歧`)
    .toBeGreaterThan(1);
  console.log('[AC-6 期望] ' + JSON.stringify(rows));

  await H.uiLogin(page);
  await H.openStep2(page, DISAMB);
  const cards = await H.readCards(page);
  expect(cards.length, `${DISAMB} 卡片数应 > 0`).toBeGreaterThan(0);
  const got = cards.map((c) => c.leftTexts.join(' | '));
  console.log('[AC-6 实际] ' + JSON.stringify(got, null, 1));
  for (const r of rows) {
    const want = `客户产品编号: ${r.customer_part_no}`;
    const hit = cards.find((c) => c.leftTexts.includes(want) && c.leftTexts.includes(r.customer_part_name));
    expect(hit, `AC-6 找不到同时显示「${r.customer_part_name}」与「${want}」的卡片 ⇒ 未按 customer_part_no 精确消歧。实际=${JSON.stringify(got)}`).toBeTruthy();
  }
  await H.shot(page, 'ac6-一料号多编号消歧');
  H.writeEvidence('ac6-消歧.txt', `期望=${JSON.stringify(rows, null, 1)}\n实际左侧=${JSON.stringify(got, null, 1)}\n`);
});

test('T8.1/T8.2 · customer_part_no 为空且客户料号表无匹配 → 左块整块不渲染', async ({ page }) => {
  const rows = H.sqlRows(`SELECT li.product_part_no_snapshot mat, li.product_name_snapshot pname
     FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id JOIN customer c ON c.id=q.customer_id
     WHERE q.quotation_number='${EMPTY}' AND li.customer_part_no IS NULL
       AND NOT EXISTS (SELECT 1 FROM ds_quote_customer_part p WHERE p.customer_no=c.code AND p.material_no=li.product_part_no_snapshot)
     ORDER BY li.sort_order`);
  expect(rows.length, `前置未满足：${EMPTY} 没有「customer_part_no 空且客户料号表无匹配」的行 ⇒ 样本为空，断言会空跑（testing.md §5.5 ③）`)
    .toBeGreaterThan(0);
  console.log('[AC-8 样本] ' + JSON.stringify(rows));

  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(e.message));
  await H.uiLogin(page);
  await H.openStep2(page, EMPTY);
  const cards = await H.readCards(page);
  expect(cards.length, `AC-8 ${EMPTY} 卡片数应 > 0（0 张则断言空跑）`).toBeGreaterThan(0);
  console.log('[AC-8 实际] ' + JSON.stringify(cards, null, 1));
  for (const c of cards) {
    expect(c.leftChildCount, `AC-8 左块应整块不渲染（childElementCount=0），实际 ${c.leftChildCount}：${JSON.stringify(c.leftTexts)}`).toBe(0);
    expect(c.leftTexts.join(''), 'AC-8 左块不得渲染成「—」或空徽标').toBe('');
    // T8.2 反向：右块仍在 ⇒ 证明只塌了左边，不是整卡没渲染
    expect(c.rightTexts.some((t) => t.startsWith('销售料号:')), `AC-8 右侧仍应有销售料号徽标，实际=${JSON.stringify(c.rightTexts)}`).toBe(true);
    expect(c.rightTexts, 'AC-8 右侧仍应有删除按钮').toContain('删除');
  }
  const loading = await page.locator('text=加载中').count();
  expect(loading, 'AC-8 页面不得出现「加载中…」占位').toBe(0);
  expect(errs, `AC-8 页面出现未捕获错误：${errs.join(' | ')}`).toEqual([]);
  await H.shot(page, 'ac8-空客户料号整块不渲染');
  H.writeEvidence('ac8-空客户料号.txt', `样本=${JSON.stringify(rows)}\n实际卡片=${JSON.stringify(cards, null, 1)}\n加载中计数=${loading}\npageerror=${JSON.stringify(errs)}\n`);
});

test('T9.1/T9.2/T6.2 · 两种降级 + customer_part_no 空但表有匹配时的回退', async ({ page }) => {
  const rows = H.sqlRows(`SELECT li.sort_order, li.product_part_no_snapshot mat, COALESCE(li.customer_part_no,'') cpn,
      COALESCE(m.production_no,'') prod,
      (SELECT count(*) FROM ds_cost_basic_material b WHERE b.production_no=m.production_no) in_basic,
      (SELECT count(*) FROM ds_cost_detail_material d WHERE d.production_no=m.production_no) in_detail,
      (SELECT p.customer_part_name FROM ds_quote_customer_part p WHERE p.customer_no='CUST-0004' AND p.material_no=li.product_part_no_snapshot ORDER BY p.id LIMIT 1) fb_name,
      (SELECT p.customer_product_no FROM ds_quote_customer_part p WHERE p.customer_no='CUST-0004' AND p.material_no=li.product_part_no_snapshot ORDER BY p.id LIMIT 1) fb_no
     FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id
     LEFT JOIN ds_quote_material m ON m.customer_no='CUST-0004' AND m.material_no=li.product_part_no_snapshot
     WHERE q.quotation_number='${MIX}' ORDER BY li.sort_order`);
  expect(rows.length, `前置未满足：${MIX} 取不到行`).toBeGreaterThan(0);
  console.log('[AC-9 样本]\n' + JSON.stringify(rows, null, 1));

  const noCost = rows.filter((r) => r.prod !== '' && Number(r.in_basic) === 0 && Number(r.in_detail) === 0);
  const noProd = rows.filter((r) => r.prod === '');
  expect(noCost.length, 'AC-9 场景2 样本为空 ⇒ 断言会空跑').toBeGreaterThan(0);
  expect(noProd.length, 'AC-9 场景1 样本为空 ⇒ 断言会空跑').toBeGreaterThan(0);

  await H.uiLogin(page);
  await H.openStep2(page, MIX);
  const cards = await H.readCards(page);
  expect(cards.length, `${MIX} 卡片数应等于行数 ${rows.length}`).toBe(rows.length);

  const log: string[] = [];
  // AC-9 场景2：第一行有生产料号，其余四行「—」
  for (const r of noCost) {
    const idx = Number(r.sort_order);
    const pop = await H.openPopover(page, idx);
    log.push(`[场景2 idx=${idx} mat=${r.mat} prod=${r.prod}]\n${pop.raw}\n`);
    H.expectPopoverFive(pop, { 料号: r.prod, 名称: '—', 规格: '—', 尺寸: '—', 旧料号: '—' },
      `AC-9 场景2（${r.mat}，核价两表均无明细，现网 2688/2696 属正确行为）`);
  }
  // AC-9 场景1：未绑生产料号 → 单行文案，且不摆空五行骨架
  for (const r of noProd) {
    const idx = Number(r.sort_order);
    const badge = page.locator('.qt-product-card').nth(idx).locator('.qt-part-badge').first();
    expect(await badge.isEnabled(), 'AC-9 场景1 销售料号徽标仍应可点').toBe(true);
    const pop = await H.openPopover(page, idx);
    log.push(`[场景1 idx=${idx} mat=${r.mat}]\n${pop.raw}\n`);
    expect(pop.raw, `AC-9 场景1 浮层应显示「未绑定生产料号」，实际:\n${pop.raw}`).toContain('未绑定生产料号');
    const rowsParsed = H.parsePopoverRows(pop.lines);
    expect(Object.keys(rowsParsed).length, `AC-9 场景1 🚫 不得摆出空的五行骨架，实际解析到=${JSON.stringify(rowsParsed)}`).toBe(0);
    // T6.2（§0.1 新增）：customer_part_no 为 NULL 但按 material_no 有匹配 ⇒ 左块按回退渲染
    if (r.cpn === '' && r.fb_name) {
      expect(cards[idx].leftTexts, `AC-6 回退分支：customer_part_no 为空应回退按 material_no 取一条`)
        .toEqual([r.fb_name, `客户产品编号: ${r.fb_no}`]);
      log.push(`[AC-6 回退 idx=${idx}] 左侧=${JSON.stringify(cards[idx].leftTexts)} 期望=[${r.fb_name}, 客户产品编号: ${r.fb_no}]\n`);
    }
  }
  await H.shot(page, 'ac9-两种降级');
  H.writeEvidence('ac9-降级与回退.txt', `样本=\n${JSON.stringify(rows, null, 1)}\n\n${log.join('\n')}`);
});

/* 🔁 原 T10.1/T10.2/T14.2 用 `QT-20260909-0799` 验详情页产品分类徽标 —— **已迁到
 *    `t260910-s1b-detail.spec.ts` 并换样本**：实查 0799 的 `product_category_id IS NULL`，
 *    该单本来就不该有产品分类徽标 ⇒ 原判据是**恒为空的样本**（testing.md §5.5 ③），
 *    「0 个徽标」既证明不了删过头、也证明不了没删过头。改用 `QT-20260909-0794`（分类=默认分类）。 */

test('T12.2 · 序列（只读子序列）：切走切回 + F5 后头部与浮层逐字不变', async ({ page }) => {
  await H.uiLogin(page);
  await H.openStep2(page, BASE);
  const snap: any[] = [];
  const capture = async (tag: string) => {
    const cards = await H.readCards(page);
    expect(cards.length, `${tag}：卡片数应 > 0`).toBeGreaterThan(0);
    const pop = await H.openPopover(page, 0);
    await page.keyboard.press('Escape').catch(() => {});
    await page.waitForTimeout(500);
    const s = { tag, left: cards[0].leftTexts, right: cards[0].rightTexts, inline: cards[0].inlineStyle,
      border: cards[0].borderTopColor, partInfo: cards[0].hasPartInfoBtn, pop: H.parsePopoverRows(pop.lines) };
    snap.push(s); console.log(`[${tag}] ` + JSON.stringify(s));
    return s;
  };
  await capture('初始态');
  // 切回 Step1 再切回 Step2
  await page.locator('button').filter({ hasText: /^\s*上\s*一\s*步\s*$/ }).first().click();
  await page.waitForTimeout(6000);
  await page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first().click();
  await page.waitForTimeout(14000);
  await capture('切走再切回');
  // F5 整页刷新
  await page.reload();
  await page.waitForTimeout(9000);
  await page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first().click();
  await page.waitForTimeout(15000);
  await capture('F5刷新后');

  const base = snap[0];
  for (const s of snap.slice(1)) {
    expect(s.left, `AC-12「${s.tag}」左侧应与初始态逐字相同`).toEqual(base.left);
    expect(s.right, `AC-12「${s.tag}」右侧应与初始态逐字相同`).toEqual(base.right);
    expect(s.pop, `AC-12「${s.tag}」浮层五行应与初始态逐字相同`).toEqual(base.pop);
    expect(s.inline ?? '', `AC-12「${s.tag}」仍不得有内联 border`).not.toContain('border');
    expect(s.border, `AC-12「${s.tag}」边框仍应是默认灰`).toBe('rgb(224, 224, 224)');
    expect(s.partInfo, `AC-12「${s.tag}」仍不得有「料号信息」按钮`).toBe(false);
  }
  await H.shot(page, 'ac12-序列-F5后');
  H.writeEvidence('ac12-序列三态.txt', JSON.stringify(snap, null, 1));
});
