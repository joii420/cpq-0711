/**
 * repair-260910 · S1 片 · **前置与正身**（不认领 AC，为其余 4 个 spec 提供归因前提）
 *
 * 为什么要单独一个 spec：本片的红有三种成因，症状一模一样但处理动作完全相反 ——
 *   ① 产品缺陷        → 报 bug
 *   ② 打到了改动前的栈 → 修环境（testing.md「假红（环境）」）
 *   ③ 共享库前置数据被别人改了 → 报主线，🚫 不许就地把期望值改成"现在是什么"
 * 这个 spec 的唯一职责就是把 ②③ 先排掉，让后面 4 个 spec 的红能被归到 ①。
 *
 * 🚨 它**不断言**「树候选非空」——那是 AC-3 本身，拿来当前置会让 AC-3 变成自己证明自己。
 */
import { test, expect } from '@playwright/test';
import {
  assertEnvIdentity, assertFixtureIntegrity, activeCostBasicConfig,
  POSTCHANGE_COST_BASIC_MD5, BASELINE_QUOTE_MD5, sqlRows, sqlRead,
  COID, LIID, COSTING_ORDER_NO, saveEvidence, readBaseline, BASELINE_FILE,
} from './r260910.helpers';

/*
 * ⚠️ 刻意**不用** `mode: 'serial'`：本文件各条是**互相独立的探针/断言**，
 *    serial 的 fail-fast 会在第一条红之后把其余整片标成 skipped ——
 *    2026-09-10 实证：P3 红了一次，P4（配置还原点，与 P3 毫无依赖）就再也没跑，
 *    报告里看起来像"只有一个问题"。写入型的 s1e 才需要 serial。
 */

test('P0 · 改动前基线文件存在（AC-12/13/14 的 before 侧取证前提）', async () => {
  const txt = readBaseline();
  expect(txt.length, `基线文件 ${BASELINE_FILE} 是空的`).toBeGreaterThan(500);
  for (const tag of ['B1', 'B2', 'B3', 'B4', 'B5', 'B6', 'B7', 'B8']) {
    expect(txt.includes(`## [${tag}]`), `基线文件缺分节 [${tag}] —— 版本太旧，请在改配置前重采`).toBe(true);
  }
  console.log(`[R260910] 基线文件 OK：${BASELINE_FILE}（${txt.length} 字节）`);
});

test('P1 · 环境正身：前端代理与直连后端是同一个后端（test.md §1）', async () => {
  test.setTimeout(120_000);
  await assertEnvIdentity();
});

test('P2 · 前置数据体检：AC 钉死的期望值与共享库现状一致', async () => {
  assertFixtureIntegrity();
});

test('P3 · 载体单据仍是 PENDING 且树页签在位', async () => {
  const [row] = sqlRows(
    `SELECT costing_order_number, status FROM costing_order WHERE id='${COID}'`);
  expect(row, `库里找不到载体核价单 ${COID}`).toBeTruthy();
  console.log(`[R260910] 载体核价单 ${row[0]} 状态=${row[1]}`);
  expect(row[0], `载体单号变了（期望 ${COSTING_ORDER_NO}）—— 前置漂移，报主线`).toBe(COSTING_ORDER_NO);
  expect(row[1],
    `🚨 载体核价单 ${COSTING_ORDER_NO} 已不是 PENDING（实为 ${row[1]}）。\n` +
    `   ⇒ AC-6/AC-7/AC-8/AC-11 需要一张可切换的 PENDING 单。**这不是产品缺陷**，是有人动了它的状态。\n` +
    `   🚦 本片无权改回来（改存量单状态属禁止动作），停下来报主线。`).toBe('PENDING');

  const bomRows = sqlRead(`
    SELECT count(*)::text FROM costing_order co, jsonb_each(co.costing_render) li,
           jsonb_array_elements((li.value->>'costingCardValues')::jsonb->'tabs') tab,
           jsonb_array_elements(COALESCE(tab->'baseRows','[]'::jsonb)) r
    WHERE co.id='${COID}' AND li.key='${LIID}' AND tab->>'tabName'='BOM'`);
  console.log(`[R260910] 载体行项 ${LIID} 的 BOM 树行数 = ${bomRows}`);
  expect(Number(bomRows),
    'BOM 树 0 行 —— 后面所有"逐行版本"断言都会空跑（假绿）。先确认载体渲染正常').toBeGreaterThan(0);
});

test('P4 · 骨架配置还原点：COST_BASIC 已改 / QUOTE 一字节未动（AC-14 前半 + AC-15 素材）', async () => {
  const cfg = activeCostBasicConfig();
  const quote = sqlRows(`SELECT id, name, md5(sql_template) FROM costing_bom_tree_config WHERE usage='QUOTE' AND is_active`);
  const banner = [
    `生效 COST_BASIC: ${cfg.name}`,
    `  id  = ${cfg.id}`,
    `  md5 = ${cfg.md5}   (改动落地后应为 = ${POSTCHANGE_COST_BASIC_MD5})`,
    `  长度 = ${cfg.len} 字节（改动前 2521）`,
    `  递归体是否已取"自己那张清单"（SQL 侧正则判定） = ${cfg.recursiveUsesOwnList}`,
    `生效 QUOTE 配置: ${quote.map((r) => `${r[1]} md5=${r[2]}`).join(' / ') || '(无)'}   (基线 = ${BASELINE_QUOTE_MD5})`,
  ].join('\n');
  console.log(banner);
  saveEvidence('01-骨架配置还原点', banner);

  // AC-14 前半：报价侧配置**一字节未动**
  expect(quote.length, `usage='QUOTE' 的生效配置不是恰好 1 条（${quote.length} 条）`).toBe(1);
  expect(quote[0][2],
    `🚨 AC-14 违反：报价侧骨架配置 md5 变了（${quote[0][2]} ≠ 基线 ${BASELINE_QUOTE_MD5}）。\n` +
    `   本次改动明确只动 usage='COST_BASIC' 那一条。`).toBe(BASELINE_QUOTE_MD5);

  // 配置侧改动是否落地 —— **独立于任何 AC** 的判据，把"改动没做"与"改动做了但不对"分开。
  // 🚨 判据是**内容**不是 md5：md5 只能证明"变了/没变"，而我的基线晚于配置改动（见 helpers 注释），
  //    拿它当 before 侧会得出"配置没改"的错误结论（2026-09-10 首跑实测踩到，已更正）。
  expect(cfg.recursiveUsesOwnList,
    `🚨 生效 COST_BASIC 骨架的**递归体仍未取"自己那张清单"**\n` +
    `   （模板里找不到 \`WHERE sv.production_no = ch.component_no\` 这段）。\n` +
    `   ⇒ 改动 1 尚未执行或做在了另一条非生效记录上，AC-1 必然红，但那不是产品缺陷。停下来报主线。`)
    .toBe(true);
  expect(cfg.md5,
    `⚠️ 生效 COST_BASIC 的 md5 与"改动落地后"记录值不一致（${cfg.md5} ≠ ${POSTCHANGE_COST_BASIC_MD5}）。\n` +
    `   递归体判据已通过 ⇒ 大概率是主线又改了一版骨架，属**还原点需要更新**，不是产品缺陷。`)
    .toBe(POSTCHANGE_COST_BASIC_MD5);
});
