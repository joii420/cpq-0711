/**
 * task-260909「核价树骨架分档与轴口径统一」· **S-全局片**（串行殿后）
 *
 * 认领 6 条 AC：**AC-3 / AC-4 / AC-19 / AC-5 / AC-16 / AC-20**
 * （声明顺序 = 执行顺序，见下方「为什么是这个顺序」）
 *
 * 🚫 断言全部从 `需求文档.md §③` AC 原文派生；未读任何实现源码。
 * 🚨 本片会写三类全局状态（test.md §3 已登记），还原逻辑集中在 `afterAll`：
 *      ① costing_bom_tree_config 的生效配置    ② template 的发布态（仅副本）
 *      ③ QT-20260909-0661 的卡片值快照
 *
 * ── 为什么是这个顺序（不是 AC 编号序）─────────────────────────────────────
 *   AC-3  先跑：它用**产品端点**成功建了一条配置 ⇒ 成为 AC-4「400」的**阳性对照**
 *              （证明 400 不是 body 形状写错造成的，testing.md §5.5 形态②）
 *   AC-4  紧随：只有阴性断言，必须挨着阳性对照跑
 *   AC-19 再跑：纯 UI，不改渲染结果
 *   AC-5  倒数第二：它是本片唯一会让核价单**渲染失败**的用例，跑完立刻恢复
 *   AC-16 与渲染无关，放这里避免和 AC-5 的错误态交叉
 *   AC-20 **殿后**：它自己的「刷新基础数据」就是快照还原动作（派工书 §e），
 *              必须在配置恢复之后才跑，否则重算出来的是坏快照
 *
 * ── 运行方式 ─────────────────────────────────────────────────────────────
 *   npx playwright test --config=e2e/t260909tree-sg.config.ts --reporter=list
 *   （可用 PW_BASE_URL / PW_BACKEND_URL 指向 worktree 临时实例）
 *
 * ⚠️ 本片**串行殿后**：S1 只读片全绿之前不许开跑（一动生效配置，S1 当场全红）。
 */
import { test, expect, APIRequestContext, Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
import {
  BACKEND_URL, PREFIX, QUOTATION_NO, QUOTATION_ID, TPL_MIXED, TPL_SAME,
  AC7_BOM_ROWS, AC10_ELEMENT_ROWS,
  sqlRead, sqlNum, sqlRowsF, sqlWrite, registerOwnedId,
  apiContext, postConfig, putConfig, activateConfig, deleteConfig, messageOf, safeJson,
  activeIdOf, activeConfigRows, activeCountByUsage, activeCountByUsageRaw, configTotalCount,
  assertShardPreconditions, costingTemplateId,
  evidenceDir, shot, saveEvidence, dumpCandidates,
  gotoTreeConfigTab, selectUsage, readConfigTableRows,
  enterCostingCard, cardOf, readTabRows, renderErrors, clickRefreshBasicData,
} from './t260909tree-sg.helpers';

// 串行由 config 的 workers:1 保证；mode:'default' 让单条失败不阻断后续用例
// （🚫 不用 serial：AC-4 挂了不该把 AC-20 的还原动作一起跳过）
// 超时统一在 t260909tree-sg.config.ts 里设 300s —— test.setTimeout() 不能在模块作用域调。
test.describe.configure({ mode: 'default' });

let api: APIRequestContext;

/** 进场快照 —— `afterAll` 还原的唯一依据（派工书 §e 第 1 条）。 */
const ENTRY = {
  activeByUsage: {} as Record<string, string>,   // usage → 生效配置 id
  costBasicSql: '',                              // 生效 COST_BASIC 配置的 sql_template（造数复用它，保证 validator 必过）
  raw: '',
};

/** 本片自建的对象 —— 全部登记，`afterAll` 逐个回收。 */
const OWNED = {
  configIds: [] as string[],       // costing_bom_tree_config（走产品 DELETE 端点回收）
  templateIds: [] as string[],     // template 副本（归档回收，🚫 不 DELETE）
};

/** 记录本轮观测到的已知缺陷 / 偏差，afterAll 汇总落证据文件。 */
const NOTES: string[] = [];
function note(s: string) { console.log(`[SG][note] ${s}`); NOTES.push(s); }

// ════════════════════════════════════════════════════════════════════════
// 进场：实例正身 + 前置快照
// ════════════════════════════════════════════════════════════════════════
test.beforeAll(async () => {
  api = await apiContext(BACKEND_URL);
  await assertShardPreconditions(api);

  ENTRY.raw = activeCountByUsageRaw();
  for (const r of activeConfigRows()) ENTRY.activeByUsage[r.usage] = r.id;
  ENTRY.costBasicSql = sqlRead(
    `SELECT sql_template FROM costing_bom_tree_config WHERE id='${ENTRY.activeByUsage['COST_BASIC']}'`);

  expect(ENTRY.costBasicSql.length,
    '生效 COST_BASIC 配置的 sql_template 读出来是空的 —— 造数会失去"必过 validator"的保证，请报主线',
  ).toBeGreaterThan(50);

  const banner =
    `进场快照（afterAll 按此还原）\n` +
    `  生效配置：${JSON.stringify(ENTRY.activeByUsage, null, 2)}\n` +
    `  GROUP BY 原始输出：\n${ENTRY.raw}\n` +
    `  COST_BASIC sql_template 长度 = ${ENTRY.costBasicSql.length} 字符\n` +
    `  单据 = ${QUOTATION_NO} (${QUOTATION_ID})`;
  console.log(`[SG] ${banner}`);
  saveEvidence('01-进场快照', banner);
});

// ════════════════════════════════════════════════════════════════════════
// 🔒 退场：还原清单（缺一项即视为本片未完成）—— 派工书 §e
// ════════════════════════════════════════════════════════════════════════
test.afterAll(async () => {
  const log: string[] = ['退场还原清单执行记录'];

  // ── ① 恢复进场时的生效配置（COST_BASIC / QUOTE 各恢复为同一 id 生效）──
  for (const usage of ['COST_BASIC', 'QUOTE']) {
    const want = ENTRY.activeByUsage[usage];
    if (!want) { log.push(`  ① ${usage}: 进场时无生效配置 ⇒ 无需恢复`); continue; }
    const now = activeIdOf(usage);
    if (now === want) { log.push(`  ① ${usage}: 已是进场 id（${want}），无需动作`); continue; }
    const r = await activateConfig(api, want);
    const after = activeIdOf(usage);
    log.push(`  ① ${usage}: ${now} → activate(${want}) [HTTP ${r.status}] ⇒ 现生效 ${after}` +
      (after === want ? ' ✅' : ' ❌ **未恢复**'));
  }

  // ── ② 删除本片新建的 T260909- 前缀配置（走产品 DELETE 端点，不写裸 SQL）──
  for (const id of OWNED.configIds) {
    const stillThere = sqlNum(`SELECT count(*) FROM costing_bom_tree_config WHERE id='${id}'`);
    if (stillThere === 0) { log.push(`  ② config ${id}: 已不存在`); continue; }
    const r = await deleteConfig(api, id);
    const left = sqlNum(`SELECT count(*) FROM costing_bom_tree_config WHERE id='${id}'`);
    log.push(`  ② config ${id}: DELETE [HTTP ${r.status}] ⇒ 残留 ${left} 行` + (left === 0 ? ' ✅' : ' ❌'));
  }
  const strays = sqlRowsF(
    `SELECT id, usage, name, is_active::text FROM costing_bom_tree_config WHERE name LIKE '${PREFIX}%'`);
  log.push(`  ② 前缀残留复查：${strays.length} 条` +
    (strays.length ? '\n' + strays.map((s) => '      ' + s.join(' | ')).join('\n') : ' ✅'));

  // ── ③ 归档本片派生的模板副本（🚫 不 DELETE —— 派工书允许「归档/删除」，取更保守的一侧）──
  const strayTpl = sqlRowsF(
    `SELECT id, name, status, template_kind FROM template WHERE name LIKE '${PREFIX}%'`);
  log.push(`  ③ ${PREFIX} 前缀模板全库复查：${strayTpl.length} 条` +
    (strayTpl.length ? '\n' + strayTpl.map((t) => '      ' + t.join(' | ')).join('\n') : ''));
  const tplIds = OWNED.templateIds.filter((i) => /^[0-9a-f-]{36}$/.test(i));
  if (tplIds.length) {
    const ids = tplIds.map((i) => `'${i}'`).join(',');
    sqlWrite(`UPDATE template SET status='ARCHIVED', updated_at=now() WHERE id IN (${ids})`,
      `归档本片派生的模板副本 ${OWNED.templateIds.length} 份`);
    const after = sqlRowsF(`SELECT id, name, status FROM template WHERE id IN (${ids})`);
    log.push('  ③ 模板副本归档：\n' + after.map((r) => '      ' + r.join(' | ')).join('\n'));
    log.push(`  ③ ⚠️ 归档≠删除：这 ${tplIds.length} 份 ${PREFIX} 副本仍在库里（含其 template_component 行），` +
      `已写进 test-report.md 的「待回收清单」交主线裁决 —— DELETE 属 §3.2 红线，测试无批准权。`);
  } else {
    log.push('  ③ 本片未派生模板副本');
  }

  // ── ④ 快照兜底：AC-20 未跑到 / 跑失败时，也要把 QT-0661 的卡片值重算回来 ──
  const bomOk = NOTES.some((n) => n.startsWith('AC-20 完成'));
  if (!bomOk) {
    log.push('  ④ AC-20 未成功收尾 ⇒ 用接口兜底重算 QT-0661 卡片值快照');
    for (const p of [`/api/cpq/quotations/${QUOTATION_ID}/refresh-card-snapshot`,
                     `/api/cpq/quotations/${QUOTATION_ID}/refresh-snapshot`,
                     `/api/cpq/configure-product/quotations/${QUOTATION_ID}/refresh-snapshot`]) {
      const r = await api.post(p).catch(() => null);
      log.push(`      POST ${p} → ${r ? r.status() : '(异常)'}`);
      if (r && r.status() < 300) break;
    }
  } else {
    log.push('  ④ AC-20 已跑完并自带重算，无需兜底');
  }

  // ── ⑤ 收口断言：还原没做到就必须红 ──
  const finalActive = activeConfigRows();
  log.push(`  ⑤ 退场生效配置：${JSON.stringify(Object.fromEntries(finalActive.map((r) => [r.usage, r.id])))}`);
  log.push(`  ⑤ 进场生效配置：${JSON.stringify(ENTRY.activeByUsage)}`);
  if (NOTES.length) log.push('\n本轮记录的偏差 / 已知缺陷：\n' + NOTES.map((n) => '  · ' + n).join('\n'));

  const text = log.join('\n');
  console.log(`[SG]\n${text}`);
  saveEvidence('99-退场还原清单', text);
  await api.dispose();

  for (const usage of ['COST_BASIC', 'QUOTE']) {
    const want = ENTRY.activeByUsage[usage];
    if (!want) continue;
    expect(activeIdOf(usage),
      `🚨 还原失败：usage=${usage} 的生效配置没有恢复成进场 id ${want}。\n` +
      `   共享库已被本片污染，**下一轮的红都不可信**，请立刻报主线。\n${text}`,
    ).toBe(want);
  }
  expect(strays.length,
    `🚨 还原失败：库里仍残留 ${strays.length} 条 ${PREFIX} 前缀配置。\n${text}`).toBe(0);
});

// ════════════════════════════════════════════════════════════════════════
// AC-3（单点）骨架分档：新增 + 设为生效，不动 QUOTE
// ════════════════════════════════════════════════════════════════════════
test('AC-3 新增 COST_BASIC 配置并设为生效后：COST_BASIC=1 且 QUOTE=1，QUOTE 生效 id 逐字不变', async () => {
  const beforeQuoteId = activeIdOf('QUOTE');
  const beforeRaw = activeCountByUsageRaw();
  expect(beforeQuoteId, '进场时 usage=QUOTE 应有生效配置（AC-22 的零回归基线）').not.toBeNull();

  // ── 新增（sql_template 直接克隆生效配置，保证不是 validator 挡下来的假红）──
  const name = `${PREFIX}基础核价BOM树-v1`;
  const created = await postConfig(api, {
    name, sqlTemplate: ENTRY.costBasicSql, usage: 'COST_BASIC', isActive: false,
  });
  expect(created.status,
    `POST 配置应成功（2xx），实际 ${created.status}\n  请求 usage=COST_BASIC name=${name}\n  响应：${created.text.slice(0, 800)}`,
  ).toBeLessThan(300);

  const newId: string = created.json?.data?.id
    ?? sqlRead(`SELECT id FROM costing_bom_tree_config WHERE name='${name}' ORDER BY created_at DESC LIMIT 1`);
  expect(newId, `拿不到新建配置的 id。响应：${created.text.slice(0, 500)}`).toMatch(/^[0-9a-f-]{36}$/);
  OWNED.configIds.push(newId); registerOwnedId(newId);

  // ── 设为生效 ──
  const act = await activateConfig(api, newId);
  expect(act.status, `activate 应成功（2xx），实际 ${act.status}：${act.text.slice(0, 500)}`).toBeLessThan(300);

  // ── AC-3 的原文断言（一条 SQL）──
  const afterRaw = activeCountByUsageRaw();
  const map = activeCountByUsage();
  const evidence =
    `AC-3 取证\n` +
    `  操作前 SELECT usage,count(*) FROM costing_bom_tree_config WHERE is_active GROUP BY 1：\n${beforeRaw}\n` +
    `  操作后：\n${afterRaw}\n` +
    `  新建并生效的配置 id = ${newId}（name=${name}）\n` +
    `  QUOTE 生效 id：操作前 ${beforeQuoteId} → 操作后 ${activeIdOf('QUOTE')}`;
  saveEvidence('02-AC-3-生效配置分档', evidence);
  console.log(evidence);

  expect(map['COST_BASIC'], `usage=COST_BASIC 的生效记录应恰好 1 条，实际 ${map['COST_BASIC']}\n${afterRaw}`).toBe(1);
  expect(map['QUOTE'], `usage=QUOTE 的生效记录应恰好 1 条，实际 ${map['QUOTE']}\n${afterRaw}`).toBe(1);
  expect(activeIdOf('COST_BASIC'), '生效的应当是刚新建的那条').toBe(newId);
  expect(activeIdOf('QUOTE'),
    `AC-3 要求 usage='QUOTE' 的生效记录 id 与操作前**逐字相同**（零回归门禁，同 AC-22）`,
  ).toBe(beforeQuoteId);

  // ── 本条用例自己的局部还原：把生效权交回进场那条（不等 afterAll，缩短污染窗口）──
  await activateConfig(api, ENTRY.activeByUsage['COST_BASIC']);
  expect(activeIdOf('COST_BASIC')).toBe(ENTRY.activeByUsage['COST_BASIC']);
});

// ════════════════════════════════════════════════════════════════════════
// AC-4（边界）缺输出列的递归 SQL：400 + 不新增记录
// ════════════════════════════════════════════════════════════════════════
test('AC-4 保存缺 node_path 输出列的递归 SQL：400「递归 SQL 缺输出列: node_path」且不新增记录', async () => {
  // 🔑 阴性用例的构造纪律：与 AC-3 里**已被接受**的那段 SQL 只差一件事 —— 外层投影砍掉 node_path。
  //    这样 400 不可能来自"SQL 写错/body 形状错"，只能来自缺列校验本身（testing.md §5.5 阳性对照）。
  const inner = ENTRY.costBasicSql.trim().replace(/;\s*$/, '');
  const badSql = `SELECT root_no, material_no, bom_version, parent_no FROM (\n${inner}\n) AS sg_missing_node_path`;
  const name = `${PREFIX}AC4-缺node_path`;

  const before = configTotalCount();
  const res = await postConfig(api, { name, sqlTemplate: badSql, usage: 'COST_BASIC', isActive: false });
  const after = configTotalCount();

  const evidence =
    `AC-4 取证\n  请求 usage=COST_BASIC name=${name}\n` +
    `  提交的 sqlTemplate（外层投影缺 node_path）：\n${badSql}\n\n` +
    `  HTTP ${res.status}\n  响应体原文：\n${res.text}\n\n` +
    `  costing_bom_tree_config 记录数：操作前 ${before} → 操作后 ${after}`;
  saveEvidence('03-AC-4-缺列校验', evidence);
  console.log(evidence);

  // 万一被接受了：先登记以便回收，再让断言红
  if (res.status < 300) {
    const id = res.json?.data?.id
      ?? sqlRead(`SELECT id FROM costing_bom_tree_config WHERE name='${name}' ORDER BY created_at DESC LIMIT 1`);
    if (/^[0-9a-f-]{36}$/.test(id)) { OWNED.configIds.push(id); registerOwnedId(id); }
  }

  expect(res.status,
    `AC-4 要求缺输出列的递归 SQL 被拒。实际 HTTP ${res.status}\n${res.text.slice(0, 800)}`,
  ).toBeGreaterThanOrEqual(400);

  expect(after,
    `AC-4 要求「记录数与操作前相同（不新增）」，实际 ${before} → ${after}。\n` +
    `  ⚠️ 若差值 ≠ 1，可能是别的会话正在写这张表 —— 那属环境串扰，不是本任务缺陷，请重跑再判。`,
  ).toBe(before);

  if (res.status === 400) {
    expect(messageOf(res.text),
      `AC-4 要求 message 含「递归 SQL 缺输出列: node_path」，实际：${res.text.slice(0, 500)}`,
    ).toContain('递归 SQL 缺输出列: node_path');
  } else {
    // 派工书 §h：BL-0227 已登记「递归 SQL 校验失败返 500 而非 400」，本期不修
    note(`AC-4 🟡 已知缺陷 BL-0227 命中：期望 400，实际 HTTP ${res.status}。` +
      `响应片段=${res.text.slice(0, 200).replace(/\s+/g, ' ')}。按派工书 §h 记录为已知缺陷，不算本任务回归。` +
      `⚠️ 但「不新增记录」与「拒绝保存」两条已独立断言通过。`);
  }
});

// ════════════════════════════════════════════════════════════════════════
// AC-19（序列）新增 → 设为生效 → 切走 → 切回 → 刷新整页
// ════════════════════════════════════════════════════════════════════════
test('AC-19 序列：新增 A → 设为生效 → 切「报价」确认未变 → 切回 → 刷新整页，A 仍生效且恰好 1 条', async ({ page }) => {
  const A_NAME = `${PREFIX}AC19-A`;
  const quoteActiveName = sqlRead(
    `SELECT name FROM costing_bom_tree_config WHERE id='${ENTRY.activeByUsage['QUOTE']}'`);

  await loginAsAdmin(page);
  await gotoTreeConfigTab(page);
  await selectUsage(page, '基础核价');
  await shot(page, 'AC-19-01-基础核价初始');
  const rows0 = await readConfigTableRows(page);

  // ── 新增 A ──
  let aId = '';
  if (process.env.SG_AC19_MODE === 'api') {
    // 备用通道（仅当 UI 表单无法驱动、且主线明确要求继续时才用）：
    note('AC-19 ⚠️ 走了 SG_AC19_MODE=api 备用通道创建配置 A —— 「新增」这一步未经 UI 验证');
    const c = await postConfig(api, { name: A_NAME, sqlTemplate: ENTRY.costBasicSql, usage: 'COST_BASIC', isActive: false });
    expect(c.status, `备用通道建 A 失败：${c.text.slice(0, 400)}`).toBeLessThan(300);
    aId = c.json?.data?.id ?? sqlRead(`SELECT id FROM costing_bom_tree_config WHERE name='${A_NAME}' LIMIT 1`);
    await page.reload(); await page.waitForLoadState('networkidle');
    await gotoTreeConfigTab(page); await selectUsage(page, '基础核价');
  } else {
    await createConfigViaUI(page, A_NAME, ENTRY.costBasicSql);
    aId = sqlRead(`SELECT id FROM costing_bom_tree_config WHERE name='${A_NAME}' ORDER BY created_at DESC LIMIT 1`);
    expect(aId,
      `UI 保存后库里查不到名为 ${A_NAME} 的配置 —— 保存没落库（真缺陷）或名字被改写。\n` +
      `  提示：本片其余用例已证明 POST 端点可用（AC-3 拿到过 2xx），所以这不是接口层的问题。`,
    ).toMatch(/^[0-9a-f-]{36}$/);
  }
  OWNED.configIds.push(aId); registerOwnedId(aId);
  await shot(page, 'AC-19-02-A已新增');

  // ── 设为生效 ──
  await activateRowViaUI(page, A_NAME);
  await page.waitForTimeout(1500);
  await shot(page, 'AC-19-03-A已设为生效');
  expect(activeIdOf('COST_BASIC'),
    `点「设为生效」后，库里 COST_BASIC 的生效配置应是 A(${aId})`).toBe(aId);

  // ── 切到「报价」：其生效配置未变 ──
  await selectUsage(page, '报价');
  await page.waitForTimeout(1200);
  const quoteRows = await readConfigTableRows(page);
  await shot(page, 'AC-19-04-报价页签');
  expect(quoteRows.length, '「报价」列表不应为空（否则下面的断言会空跑）').toBeGreaterThan(0);
  const quoteActiveRows = quoteRows.filter((r) => r.active);
  expect(quoteActiveRows.length,
    `「报价」列表中标「生效中」的应恰好 1 条，实际 ${quoteActiveRows.length}：\n` +
    quoteRows.map((r) => '   ' + r.text).join('\n')).toBe(1);
  expect(quoteActiveRows[0].text,
    `「报价」的生效配置应仍是进场那条「${quoteActiveName}」（AC-19 中段 + AC-22 零回归）`,
  ).toContain(quoteActiveName);
  expect(activeIdOf('QUOTE'), 'QUOTE 生效 id 不应被 COST_BASIC 的操作改动').toBe(ENTRY.activeByUsage['QUOTE']);

  // ── 切回「基础核价」→ 刷新整页 ──
  await selectUsage(page, '基础核价');
  await page.waitForTimeout(1200);
  await page.reload();                       // ← AC-19 的「刷新整页」本体
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await gotoTreeConfigTab(page, false);      // 刷新后页签可能回到默认项，重新点开（不再二次 goto）
  await selectUsage(page, '基础核价');
  await page.waitForTimeout(1500);
  const rowsFinal = await readConfigTableRows(page);
  await shot(page, 'AC-19-05-刷新整页后');

  expect(rowsFinal.length, '刷新后「基础核价」列表不应为空').toBeGreaterThan(0);
  const aRow = rowsFinal.find((r) => r.text.includes(A_NAME));
  expect(aRow,
    `刷新整页后列表里找不到 A（${A_NAME}）。实际各行：\n${rowsFinal.map((r) => '   ' + r.text).join('\n')}`,
  ).toBeTruthy();
  expect(aRow!.active, `AC-19：刷新整页后 A 应仍显示「生效中」。该行文本：${aRow!.text}`).toBe(true);
  const activeCount = rowsFinal.filter((r) => r.active).length;
  expect(activeCount,
    `AC-19：「基础核价」列表中标「生效中」的应恰好 1 条，实际 ${activeCount}：\n` +
    rowsFinal.map((r) => '   ' + r.text).join('\n')).toBe(1);

  saveEvidence('04-AC-19-序列',
    `AC-19 取证\n  初始行：\n${rows0.map((r) => '    ' + r.text).join('\n')}\n` +
    `  A = ${A_NAME} (${aId})\n` +
    `  切「报价」时生效行：${quoteActiveRows[0].text}\n` +
    `  刷新整页后「基础核价」各行：\n${rowsFinal.map((r) => `    active=${r.active} | ${r.text}`).join('\n')}`);

  // ── 局部还原 ──
  await activateConfig(api, ENTRY.activeByUsage['COST_BASIC']);
  expect(activeIdOf('COST_BASIC')).toBe(ENTRY.activeByUsage['COST_BASIC']);
});

/** UI 新增一条配置。表单形态未知 ⇒ 逐种尝试并在失败时 dump。 */
async function createConfigViaUI(page: Page, name: string, sql: string) {
  const addBtn = page.getByRole('button', { name: /新\s*增|新\s*建|添\s*加/ }).first();
  if (!(await addBtn.isVisible().catch(() => false))) {
    const dump = await dumpCandidates(page, '找不到「新增」按钮', ['button']);
    await shot(page, 'ERR-AC19-找不到新增按钮');
    throw new Error(`🚨 「核价树配置 → 基础核价」下找不到「新增」按钮。\n${dump}`);
  }
  await addBtn.click();
  await page.waitForTimeout(1200);

  // antd 6.3.5 起 .ant-drawer-content 类名没了 ⇒ 用 .ant-drawer / .ant-modal
  const panel = page.locator('.ant-modal:visible, .ant-drawer:visible').first();
  const scope = (await panel.count()) > 0 ? panel : page.locator('body');
  const nameInput = scope.locator('input:not([type="checkbox"]):not([type="radio"])').first();
  const sqlArea = scope.locator('textarea').first();
  if (!(await nameInput.isVisible().catch(() => false)) || !(await sqlArea.isVisible().catch(() => false))) {
    const dump = await dumpCandidates(page, '新增表单定位失败',
      ['.ant-modal', '.ant-drawer', 'input', 'textarea', 'button']);
    await shot(page, 'ERR-AC19-新增表单');
    throw new Error(`🚨 新增表单里定位不到「名称输入框」或「SQL 文本域」。\n${dump}`);
  }
  await nameInput.fill(name);
  await sqlArea.fill(sql);
  await shot(page, 'AC-19-02a-新增表单已填');

  // antd 两字按钮渲染成「保 存」⇒ 必须用 /保\s*存/
  const saveBtn = scope.getByRole('button', { name: /保\s*存|确\s*定|提\s*交/ }).last();
  await saveBtn.click();
  await page.waitForTimeout(2500);
}

/** UI 上把某一行「设为生效」。可能是行内按钮，也可能藏在「更多」下拉里。 */
async function activateRowViaUI(page: Page, rowKeyText: string) {
  const row = page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: rowKeyText }).first();
  if (!(await row.isVisible().catch(() => false))) {
    const rows = await readConfigTableRows(page);
    await shot(page, 'ERR-AC19-找不到目标行');
    throw new Error(`🚨 列表里找不到「${rowKeyText}」行。实际各行：\n${rows.map((r) => '   ' + r.text).join('\n')}`);
  }
  const inline = row.getByRole('button', { name: /设为生效|生\s*效|启\s*用/ }).first();
  if (await inline.isVisible().catch(() => false)) {
    await inline.click();
  } else {
    const link = row.locator('a, .ant-btn-link').filter({ hasText: /设为生效|生\s*效|启\s*用/ }).first();
    if (await link.isVisible().catch(() => false)) {
      await link.click();
    } else {
      const more = row.locator('button, a').filter({ hasText: /更多|\.\.\.|···/ }).first();
      if (!(await more.isVisible().catch(() => false))) {
        const acts = await row.locator('button, a').allInnerTexts().catch(() => [] as string[]);
        await shot(page, 'ERR-AC19-找不到设为生效');
        throw new Error(`🚨 目标行里找不到「设为生效」。该行可用动作=${JSON.stringify(acts.map((t) => t.trim()))}`);
      }
      await more.click(); await page.waitForTimeout(600);
      await page.locator('.ant-dropdown-menu-item').filter({ hasText: /设为生效|生\s*效|启\s*用/ }).first().click();
    }
  }
  await page.waitForTimeout(800);
  // 可能有二次确认
  const ok = page.locator('.ant-modal-confirm-btns button, .ant-popconfirm button')
    .filter({ hasText: /确\s*定|确\s*认|是/ }).first();
  if (await ok.isVisible().catch(() => false)) { await ok.click(); await page.waitForTimeout(1200); }
}

// ════════════════════════════════════════════════════════════════════════
// AC-5（边界·阴性）无生效 COST_BASIC 配置时，错误文案带对的数据集名
// ════════════════════════════════════════════════════════════════════════
test('AC-5 无生效 COST_BASIC 配置时错误文案出现 usage=COST_BASIC（而非 COSTING），恢复后错误消失', async ({ page }) => {
  // ── 1. 造一条**与生效配置字节相同**的自有配置并设为生效，再停用它 ──
  //     🔑 只在自己的行上做停用/删除，🚫 不动交付出来的那条（它是别的 AC 的取证对象）
  const name = `${PREFIX}AC5-临时`;
  const c = await postConfig(api, { name, sqlTemplate: ENTRY.costBasicSql, usage: 'COST_BASIC', isActive: false });
  expect(c.status, `建临时配置失败：${c.text.slice(0, 400)}`).toBeLessThan(300);
  const tmpId: string = c.json?.data?.id
    ?? sqlRead(`SELECT id FROM costing_bom_tree_config WHERE name='${name}' ORDER BY created_at DESC LIMIT 1`);
  OWNED.configIds.push(tmpId); registerOwnedId(tmpId);
  await activateConfig(api, tmpId);
  expect(activeIdOf('COST_BASIC'), '临时配置应已生效').toBe(tmpId);

  // ── 2. 停用：先试 PUT isActive=false；不生效则删掉自己这条（走产品 DELETE 端点）──
  const put = await putConfig(api, tmpId,
    { name, sqlTemplate: ENTRY.costBasicSql, usage: 'COST_BASIC', isActive: false });
  let how = `PUT isActive=false [HTTP ${put.status}]`;
  if (activeIdOf('COST_BASIC') !== null) {
    const del = await deleteConfig(api, tmpId);
    how += ` → 未生效，改用 DELETE 自有配置 [HTTP ${del.status}]`;
  }
  expect(activeIdOf('COST_BASIC'),
    `无法把 usage=COST_BASIC 置成"无生效配置"（${how}）。\n` +
    `  ⚠️ api.md §1 只写了 GET/POST/PUT/activate/DELETE，**没有规定"停用"怎么做** —— ` +
    `这是契约缺口，请报主线，不要当产品缺陷。`,
  ).toBeNull();
  console.log(`[SG] AC-5 停用方式：${how}`);

  let didBreakSnapshot = false;
  try {
    // ── 3. 重新渲染核价单 ──
    await loginAsAdmin(page);
    await enterCostingCard(page);
    let errs = await renderErrors(page);
    await shot(page, 'AC-5-01-无生效配置时渲染');

    if (errs.length === 0 && process.env.SG_AC5_ALLOW_REFRESH === '1') {
      note('AC-5 ⚠️ 首次加载未见错误框，按 SG_AC5_ALLOW_REFRESH=1 强制「刷新基础数据」重算（会重写 QT-0661 快照）');
      await clickRefreshBasicData(page);
      didBreakSnapshot = true;
      await enterCostingCard(page);
      errs = await renderErrors(page);
      await shot(page, 'AC-5-01b-强制重算后');
    }

    expect(errs.length,
      `AC-5 期望「无生效 COST_BASIC 骨架配置时核价单渲染报错」，但页面上一个「核价渲染失败」框都没有。\n` +
      `  两种可能，需主线裁定，🚫 不要直接判成产品缺陷：\n` +
      `   (a) 骨架解析走了别的兜底 ⇒ AC-5 的前提不成立；\n` +
      `   (b) 核价卡片这次读的是既有快照而非实时渲染 ⇒ 需要 SG_AC5_ALLOW_REFRESH=1 强制重算再验。`,
    ).toBeGreaterThan(0);

    const joined = errs.join(' | ');
    saveEvidence('05-AC-5-错误文案', `AC-5 取证\n  停用方式：${how}\n  错误文案原文：\n${errs.map((e) => '    ' + e).join('\n')}`);
    expect(joined,
      `AC-5 要求错误文案出现 usage=COST_BASIC。实际文案：\n${joined}`).toContain('usage=COST_BASIC');
    expect(joined,
      `AC-5 要求不再是恒 COSTING 的老文案。实际文案：\n${joined}`).not.toContain('usage=COSTING');
  } finally {
    // ── 4. 恢复生效 + 确认错误消失 ──
    await activateConfig(api, ENTRY.activeByUsage['COST_BASIC']);
    expect(activeIdOf('COST_BASIC')).toBe(ENTRY.activeByUsage['COST_BASIC']);
    if (didBreakSnapshot) {
      await enterCostingCard(page);
      await clickRefreshBasicData(page).catch(() => false);
    }
    await enterCostingCard(page);
    const errs2 = await renderErrors(page);
    await shot(page, 'AC-5-02-恢复生效后');
    expect(errs2.length,
      `AC-5 后半段：恢复生效后错误应消失，实际仍有 ${errs2.length} 个红框：\n${errs2.join('\n')}`).toBe(0);
    const card = await cardOf(page, 'S0001');
    const bom = await readTabRows(card, 'BOM');
    expect(bom.count,
      `恢复生效后 S0001 的 BOM 页签应回到 ${AC7_BOM_ROWS} 行（阳性对照：证明"错误消失"不是因为页面空了）。` +
      `实际 ${bom.count} 行，首列=${JSON.stringify(bom.firstCells)}`).toBe(AC7_BOM_ROWS);
  }
});

// ════════════════════════════════════════════════════════════════════════
// AC-16（单点）发布期告警：只告警不拦
// ════════════════════════════════════════════════════════════════════════
test('AC-16 发布派生副本：混方言副本 200 且 warnings 含 1 条；同方言副本 200 且 warnings 为 []', async () => {
  const srcId = costingTemplateId();

  // ── 解析源模板页签方言（判定基准 = 树页签组件的方言，api.md §3）──
  const tabs = sqlRowsF(
    `SELECT tc.id, tc.sort_order::text, tc.tab_name, c.code, ` +
    `coalesce(csv.builder_config->>'dialect','(无)') ` +
    `FROM template_component tc JOIN component c ON c.id=tc.component_id ` +
    `LEFT JOIN component_sql_view csv ON csv.component_id=c.id AND csv.status='ACTIVE' ` +
    `WHERE tc.template_id='${srcId}' ORDER BY tc.sort_order`);
  console.log(`[SG] 源模板「核价模板1」页签：\n${tabs.map((t) => '   ' + t.join(' | ')).join('\n')}`);
  expect(tabs.length, '源模板页签数为 0，AC-16 无前置').toBeGreaterThan(0);

  const procTab = tabs.find((t) => t[2] === '加工费');
  expect(procTab, `源模板里找不到「加工费」页签（AC-16 的告警文案就以它为例）。实际页签=${JSON.stringify(tabs.map((t) => t[2]))}`).toBeTruthy();
  const procTcId = procTab![0];

  // 混方言副本要一个 **QUOTE 方言**的「加工费」组件
  const quoteProcId = sqlRead(
    `SELECT c.id FROM component c JOIN component_sql_view csv ON csv.component_id=c.id AND csv.status='ACTIVE' ` +
    `WHERE c.name='加工费' AND csv.builder_config->>'dialect'='QUOTE' ORDER BY c.code LIMIT 1`);
  expect(quoteProcId, '库里找不到 QUOTE 方言的「加工费」组件 —— 混方言副本造不出来，请报主线').toMatch(/^[0-9a-f-]{36}$/);

  // 同方言副本要一个 **COST_BASIC 方言**的「加工费」组件（= 交付项 10 把 COMP-2319 改回来）
  const basicProcId = sqlRead(
    `SELECT c.id FROM component c JOIN component_sql_view csv ON csv.component_id=c.id AND csv.status='ACTIVE' ` +
    `WHERE c.name='加工费' AND csv.builder_config->>'dialect'='COST_BASIC' ORDER BY c.code LIMIT 1`);
  expect(basicProcId,
    `库里找不到 COST_BASIC 方言的「加工费」组件。\n` +
    `  ⇒ 交付项 10（COMP-2319 方言由 QUOTE 改回 COST_BASIC 并重编译）尚未执行，\n` +
    `     AC-16 后半段（warnings=[]）没有可验前置。**请报主线补配置**，这不是产品缺陷。`,
  ).toMatch(/^[0-9a-f-]{36}$/);

  // ⚠️ AC 原文写「5 个页签原样，其中加工费组件方言为 QUOTE」——
  //    但交付项 10 已把 COMP-2319 改成 COST_BASIC，"原样"派生就不再混方言了（AC 自相矛盾，已上报主线）。
  //    这里按 AC 的**意图**（一份混方言、一份同方言）显式指定加工费页签绑哪个组件。
  const mixedId = await deriveDraftCopy(srcId, TPL_MIXED, procTcId, quoteProcId);
  const sameId = await deriveDraftCopy(srcId, TPL_SAME, procTcId, basicProcId);

  const pub1 = await publishAndRead(mixedId, TPL_MIXED);
  const pub2 = await publishAndRead(sameId, TPL_SAME);

  saveEvidence('06-AC-16-publish-warnings',
    `AC-16 取证\n源模板页签：\n${tabs.map((t) => '  ' + t.join(' | ')).join('\n')}\n\n` +
    `【混方言副本 ${TPL_MIXED}】id=${mixedId}\n  HTTP ${pub1.status}\n  响应体原文：\n${pub1.text}\n\n` +
    `【同方言副本 ${TPL_SAME}】id=${sameId}\n  HTTP ${pub2.status}\n  响应体原文：\n${pub2.text}\n`);

  // ── 混方言：200 + 恰好 1 条告警，文案逐字 ──
  expect(pub1.status, `AC-16：混方言副本发布应 **200 不拦**，实际 ${pub1.status}\n${pub1.text.slice(0, 800)}`).toBe(200);
  const w1 = pub1.json?.data?.warnings;
  expect(Array.isArray(w1), `AC-16：warnings 应是数组且恒非 null，实际 ${JSON.stringify(w1)}`).toBe(true);
  expect(w1.length, `AC-16：混方言副本应恰好 1 条告警，实际 ${JSON.stringify(w1)}`).toBe(1);
  expect(w1[0], `AC-16 告警文案逐字判据。实际：${JSON.stringify(w1[0])}`)
    .toBe('本模板含 1 个非本数据集页签：加工费(QUOTE)');

  // ── 同方言：200 + [] ──
  expect(pub2.status, `AC-16：同方言副本发布应 200，实际 ${pub2.status}\n${pub2.text.slice(0, 800)}`).toBe(200);
  const w2 = pub2.json?.data?.warnings;
  expect(Array.isArray(w2), `AC-16：warnings 应是数组（**非 null**），实际 ${JSON.stringify(w2)}`).toBe(true);
  expect(w2.length, `AC-16：同方言副本 warnings 应为空数组，实际 ${JSON.stringify(w2)}`).toBe(0);

  // ── 🚫 不动本体：核价模板1 的状态与页签绑定逐字未变 ──
  const srcAfter = sqlRowsF(
    `SELECT status, (SELECT count(*)::text FROM template_component WHERE template_id='${srcId}') FROM template WHERE id='${srcId}'`);
  expect(srcAfter[0][0], '本体「核价模板1」的状态不应被本用例改动').toBe('PUBLISHED');
  expect(srcAfter[0][1], '本体「核价模板1」的页签数不应被本用例改动').toBe(String(tabs.length));
});

/**
 * 由源模板派生一份 **DRAFT 副本**（连同页签绑定），并把「加工费」页签改绑到指定组件。
 *
 * 🚨 为什么用 SQL 而不是接口：`api.md` 只规定了 `publish`，**没有派生/复制端点**
 *    （实测 GET /templates/{id}/copy|duplicate|clone 全 404）。本次 AC 验的是
 *    「publish 的 warnings」，派生只是前置 ⇒ 用 SQL 造前置是可接受的（判据：我的 AC 验的是什么）。
 *    造完立刻用 `GET /templates/{id}` 做**阳性对照**，证明应用确实认得这份副本。
 */
async function deriveDraftCopy(srcId: string, newName: string, procTcId: string, procCompId: string): Promise<string> {
  const newId = sqlWrite(
    `INSERT INTO template (id, template_series_id, name, version, category, description, usage_note, ` +
    `product_attributes, subtotal_formula, components_snapshot, status, created_by, published_at, ` +
    `excel_view_config, customer_id, category_id, template_kind, formulas, is_default, ` +
    `referenced_variables, sql_views_snapshot, template_sql_views_snapshot) ` +
    `SELECT gen_random_uuid(), gen_random_uuid(), '${newName}', version, category, ` +
    `'${newName} —— task-260909 S-全局片 AC-16 派生副本，跑完归档', usage_note, ` +
    `product_attributes, subtotal_formula, NULL, 'DRAFT', created_by, NULL, ` +
    `excel_view_config, customer_id, category_id, template_kind, formulas, false, ` +
    `referenced_variables, NULL, '{}'::jsonb FROM template WHERE id='${srcId}' RETURNING id`,
    `派生 DRAFT 副本 ${newName}`).trim();
  // 🔑 先登记再断言：万一 id 解析不出，也不能让一份已插进库的副本变成没人认领的孤儿行
  if (/^[0-9a-f-]{36}$/.test(newId)) { OWNED.templateIds.push(newId); registerOwnedId(newId); }
  expect(newId,
    `派生副本失败，没拿到 id（${newName}）。原始输出：${JSON.stringify(newId)}\n` +
    `  ⚠️ 若库里已多出一行同名模板，请手工归档 —— 本用例已失去它的 id。`,
  ).toMatch(/^[0-9a-f-]{36}$/);

  sqlWrite(
    `INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, preset_rows, ` +
    `formula_assignments, data_driver_path_override, fields_override) ` +
    `SELECT gen_random_uuid(), '${newId}', ` +
    `CASE WHEN tc.id='${procTcId}' THEN '${procCompId}'::uuid ELSE tc.component_id END, ` +
    `tc.tab_name, tc.sort_order, tc.preset_rows, tc.formula_assignments, tc.data_driver_path_override, ` +
    `tc.fields_override FROM template_component tc WHERE tc.template_id='${srcId}'`,
    `复制 ${newName} 的页签绑定（加工费改绑 ${procCompId}）`);

  // 阳性对照：应用必须认得这份副本（否则"publish 没告警"可能只是因为副本是残的）
  const res = await api.get(`/api/cpq/templates/${newId}`);
  const j = safeJson(await res.text());
  const srcTabs = sqlNum(`SELECT count(*) FROM template_component WHERE template_id='${srcId}'`);
  const cpTabs = sqlNum(`SELECT count(*) FROM template_component WHERE template_id='${newId}'`);
  expect(res.status(), `派生副本 ${newName} 读不回来（HTTP ${res.status()}）`).toBe(200);
  expect(j?.data?.status, `派生副本 ${newName} 应为 DRAFT`).toBe('DRAFT');
  expect(cpTabs, `派生副本 ${newName} 的页签数应与源模板一致`).toBe(srcTabs);
  expect((j?.data?.components ?? []).length,
    `应用读到的副本页签数应与库里一致（=${cpTabs}）—— 不一致说明 SQL 派生没被应用识别，请报主线`).toBe(cpTabs);
  console.log(`[SG] 派生副本 ${newName} = ${newId}（${cpTabs} 页签，DRAFT）`);
  return newId;
}

async function publishAndRead(templateId: string, label: string) {
  const res = await api.post(`/api/cpq/templates/${templateId}/publish`);
  const text = await res.text();
  console.log(`[SG] publish ${label} → HTTP ${res.status()}\n${text.slice(0, 1200)}`);
  return { status: res.status(), text, json: safeJson(text) };
}

// ════════════════════════════════════════════════════════════════════════
// AC-20（序列）BOM 7 → 材质元素 4 → 切回 7 → 刷新基础数据 → 仍 7 / 4
// ════════════════════════════════════════════════════════════════════════
test('AC-20 序列：BOM 7 行 → 材质元素 4 行 → 切回 BOM 仍 7 行 → 刷新基础数据后仍 7 / 4 且无红框', async ({ page }) => {
  // 前置：生效配置必须是进场那条（前面每条用例都做了局部还原，这里再确认一次）
  expect(activeIdOf('COST_BASIC'),
    'AC-20 前置：COST_BASIC 生效配置必须是进场那条，否则重算出来的是别的树').toBe(ENTRY.activeByUsage['COST_BASIC']);

  await loginAsAdmin(page);
  await enterCostingCard(page);
  expect((await renderErrors(page)).length, 'AC-20 起手：不应有「核价渲染失败」红框').toBe(0);

  let card = await cardOf(page, 'S0001');
  const bom1 = await readTabRows(card, 'BOM');
  await shot(page, 'AC-20-01-BOM首次');
  expect(bom1.count,
    `AC-20 第 1 步：S0001 的 BOM 页签应 ${AC7_BOM_ROWS} 行，实际 ${bom1.count}，首列=${JSON.stringify(bom1.firstCells)}`,
  ).toBe(AC7_BOM_ROWS);

  const el1 = await readTabRows(card, '材质元素');
  await shot(page, 'AC-20-02-材质元素');
  expect(el1.count,
    `AC-20 第 2 步：材质元素页签应 ${AC10_ELEMENT_ROWS} 行，实际 ${el1.count}，首列=${JSON.stringify(el1.firstCells)}`,
  ).toBe(AC10_ELEMENT_ROWS);

  const bom2 = await readTabRows(card, 'BOM');
  await shot(page, 'AC-20-03-切回BOM');
  expect(bom2.count,
    `AC-20 第 3 步：切回 BOM 应仍 ${AC7_BOM_ROWS} 行，实际 ${bom2.count}，首列=${JSON.stringify(bom2.firstCells)}`,
  ).toBe(AC7_BOM_ROWS);
  expect(bom2.firstCells, 'AC-20：切走再切回，BOM 的行内容也应一致（不只是行数）').toEqual(bom1.firstCells);

  // ── 刷新基础数据（本片登记的第 ③ 类全局状态写入面）──
  const fired = await clickRefreshBasicData(page);
  expect(fired, '「刷新基础数据」确认后应发出刷新请求（没发出说明确认框/按钮没点到，属选择器问题）').toBe(true);
  await shot(page, 'AC-20-04-刷新完成');

  await enterCostingCard(page);
  expect((await renderErrors(page)).length, 'AC-20 第 4 步：刷新后不应出现红框').toBe(0);
  card = await cardOf(page, 'S0001');
  const bom3 = await readTabRows(card, 'BOM');
  await shot(page, 'AC-20-05-刷新后BOM');
  const el2 = await readTabRows(card, '材质元素');
  await shot(page, 'AC-20-06-刷新后材质元素');

  expect(bom3.count,
    `AC-20 第 4 步：刷新后 BOM 应仍 ${AC7_BOM_ROWS} 行，实际 ${bom3.count}，首列=${JSON.stringify(bom3.firstCells)}`,
  ).toBe(AC7_BOM_ROWS);
  expect(el2.count,
    `AC-20 第 4 步：刷新后材质元素应仍 ${AC10_ELEMENT_ROWS} 行，实际 ${el2.count}，首列=${JSON.stringify(el2.firstCells)}`,
  ).toBe(AC10_ELEMENT_ROWS);

  saveEvidence('07-AC-20-序列',
    `AC-20 取证（单据 ${QUOTATION_NO} / 卡片 S0001）\n` +
    `  ① BOM 首次        ${bom1.count} 行  ${JSON.stringify(bom1.firstCells)}\n` +
    `  ② 材质元素        ${el1.count} 行  ${JSON.stringify(el1.firstCells)}\n` +
    `  ③ 切回 BOM        ${bom2.count} 行  ${JSON.stringify(bom2.firstCells)}\n` +
    `  ④ 刷新后 BOM      ${bom3.count} 行  ${JSON.stringify(bom3.firstCells)}\n` +
    `  ④ 刷新后材质元素  ${el2.count} 行  ${JSON.stringify(el2.firstCells)}\n` +
    `  红框数：全程 0`);

  note('AC-20 完成 —— QT-0661 卡片值快照已由本用例的「刷新基础数据」重算，afterAll 无需兜底');
});
