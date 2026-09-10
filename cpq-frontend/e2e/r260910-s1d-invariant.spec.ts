/**
 * repair-260910 · S1 片 · **无副作用（before/after 对照）** —— AC-12 / AC-13 / AC-14
 *
 * 🚨 before 侧全部来自 `证据/e2e-s1/基线-改动前.txt`（由 `e2e/r260910-baseline.sh`
 *    在**主线改骨架配置之前**采集）。基线不存在时本文件硬失败并说明"这不是产品缺陷"。
 *
 * 🚫 本文件全部只读。
 * ⚠️ 必须在 `r260910-s1e-switch.spec.ts`（唯一写入片）**之前**跑 —— 文件名字母序已保证。
 */
import { test, expect } from '@playwright/test';
import {
  baselineSection, sqlRows, saveEvidence,
  COID, TAB_TREE, BASELINE_TREE, BASELINE_ROW_COUNT,
  BASELINE_QUOTE_MD5, readTreeFromDb, EXPECTED_CURRENT_VERSION,
} from './r260910.helpers';

/*
 * ⚠️ 刻意**不用** `mode: 'serial'`：本文件各条是**互相独立的探针/断言**，
 *    serial 的 fail-fast 会在第一条红之后把其余整片标成 skipped ——
 *    2026-09-10 实证：P3 红了一次，P4（配置还原点，与 P3 毫无依赖）就再也没跑，
 *    报告里看起来像"只有一个问题"。写入型的 s1e 才需要 serial。
 */

/** 把基线里 `id|no|status|md5|bomrows` 形状的行解析成 Map。 */
function parsePipeRows(section: string, headerFirstCol: string): string[][] {
  return section.split('\n')
    .map((l) => l.trim())
    .filter((l) => l.includes('|') && !l.startsWith('#') && !l.startsWith('##') && !l.startsWith(headerFirstCol))
    .map((l) => l.split('|'))
    .filter((c) => c.length >= 2 && /^[0-9a-f]{8}-/.test(c[0]));
}

// ─────────────────────── AC-12（无副作用）───────────────────────

test('AC-12 · 已冻结核价单的 costing_render md5 逐字不变（全量对照，不只一张）', async () => {
  const base = baselineSection('B4');
  const beforeRows = parsePipeRows(base, 'id');
  expect(beforeRows.length,
    `基线 [B4] 里一张冻结单都没解析出来 —— 解析口径或基线格式不对，AC-12 会空跑（假绿）`).toBeGreaterThan(0);
  const before = new Map(beforeRows.map((c) => [c[0], { no: c[1], status: c[2], md5: c[3], bomRows: Number(c[4]) }]));

  const after = new Map(sqlRows(`
    SELECT id, costing_order_number, status, md5(COALESCE(costing_render::text,'(null)')),
           (SELECT count(*) FROM jsonb_each(COALESCE(co.costing_render,'{}'::jsonb)) li,
                   jsonb_array_elements((li.value->>'costingCardValues')::jsonb->'tabs') tb,
                   jsonb_array_elements(COALESCE(tb->'baseRows','[]'::jsonb)) rr
             WHERE tb->>'tabName'='${TAB_TREE}')::text
    FROM costing_order co WHERE status IN ('APPROVED','REJECTED','WITHDRAWN') ORDER BY id`)
    .map((c) => [c[0], { no: c[1], status: c[2], md5: c[3], bomRows: Number(c[4]) }] as const));

  // 只比**两侧都在**的单：期间别的会话新审批出来的单不算本次的锅（共享库纪律）
  const common = [...before.keys()].filter((id) => after.has(id));
  const added = [...after.keys()].filter((id) => !before.has(id));
  const gone = [...before.keys()].filter((id) => !after.has(id));
  console.log(`[R260910] AC-12 冻结单：基线 ${before.size} 张 / 现在 ${after.size} 张 / 可比 ${common.length} 张` +
    `（新增 ${added.length}、消失 ${gone.length} —— 属并发，不计入断言）`);
  expect(common.length, 'AC-12 可比集合为空 ⇒ 断言空跑（假绿）').toBeGreaterThan(0);

  const changed = common.filter((id) => before.get(id)!.md5 !== after.get(id)!.md5)
    .map((id) => `${after.get(id)!.no}(${after.get(id)!.status}) ${before.get(id)!.md5} → ${after.get(id)!.md5}`);
  expect(changed,
    `🚨 AC-12 违反：已冻结核价单的 costing_render 被改动波及。\n` +
    `   冻结单读 frozenDto 快照，本次改动**不应触达**它们。\n   变化清单：\n     ${changed.join('\n     ')}`)
    .toEqual([]);

  // ⚠️ 证据强度自述（testing.md：说不清强度的绿等于没验）
  const withTree = common.filter((id) => after.get(id)!.bomRows > 0);
  const note =
    `AC-12 可比冻结单 ${common.length} 张，md5 全部不变。\n` +
    `⚠️ 其中**含 BOM 树行**的只有 ${withTree.length} 张。\n` +
    (withTree.length === 0
      ? `🚨 证据强度告警：现有冻结单里没有一张的 costing_render 含 BOM 树行（实查 2026-09-10：49 张全为 0 行），\n` +
        `   ⇒ 本条只证明了"冻结单没被顺手重算"，**证明不了"冻结单的树版本列不受影响"**。\n` +
        `   要补强需要一张含 300001 树的冻结单 —— 本片无权改存量单状态，已报主线。\n`
      : `⇒ 其中 ${withTree.length} 张含树，本条对"冻结树不受影响"具备正向证据。\n`);
  // ── 补强：载体单若已被冻结，它是全库**唯一**含 300001 树的单，正好补上上面的证据缺口 ──
  //    （2026-09-10 11:12 实证：HJ-20260910-0975 被改成 WITHDRAWN，随即成为唯一"含 7 行树的冻结单"）
  const carrier = sqlRows(
    `SELECT status, md5(COALESCE(costing_render::text,'(null)')) FROM costing_order WHERE id='${COID}'`)[0];
  const b3 = baselineSection('B3');
  const b3Md5 = (b3.match(/\|([0-9a-f]{32})\|/) || [])[1] || '';
  if (carrier && ['APPROVED', 'REJECTED', 'WITHDRAWN'].includes(carrier[0])) {
    expect(b3Md5, '基线 [B3] 里解析不出载体单的 render md5 —— 补强条会空跑').toMatch(/^[0-9a-f]{32}$/);
    expect(carrier[1],
      `🚨 AC-12 补强不达：载体单（现为 ${carrier[0]}，含 ${BASELINE_ROW_COUNT} 行 BOM 树）的 costing_render 变了。\n` +
      `   ${b3Md5} → ${carrier[1]}\n` +
      `   这是本任务唯一一张**含树的冻结单**，它变了 = 冻结树被改动波及。`).toBe(b3Md5);
    console.log(`[R260910] AC-12 补强 ✅ 载体单（${carrier[0]}，含树）render md5 与基线 [B3] 逐字相同：${b3Md5}`);
  } else {
    console.log(`[R260910] AC-12 补强 ⏭ 载体单当前是 ${carrier ? carrier[0] : '(查不到)'}，不属冻结态，跳过补强（已在正文说明证据强度）`);
  }

  console.log(note);
  saveEvidence('30-AC12-冻结单md5对照',
    note + '\n' + common.map((id) => `${after.get(id)!.no}\t${after.get(id)!.status}\t${after.get(id)!.md5}\tbomRows=${after.get(id)!.bomRows}`).join('\n'));
});

// ─────────────────────── AC-13（无副作用）───────────────────────

test('AC-13 · 树结构不变：行数/层级/父子/node_path 逐字相同，只有版本列取值变化', async () => {
  const after = readTreeFromDb();
  expect(after.length, 'AC-13：读到 0 行树 ⇒ 断言空跑（假绿）').toBeGreaterThan(0);

  // ① 与 AC 原文钉死的基线树逐字比（不是与 DB 现状比 —— 那会自证）
  expect(after.length, `AC-13：树行数应为 ${BASELINE_ROW_COUNT}，实得 ${after.length}`).toBe(BASELINE_ROW_COUNT);
  const gotSkeleton = after.map((r) => `${r.nodePath}|lvl=${r.lvl}|parent=${r.parentNo || '(root)'}`);
  const wantSkeleton = BASELINE_TREE.map((r) => `${r.nodePath}|lvl=${r.lvl}|parent=${r.parentNo || '(root)'}`);
  expect([...gotSkeleton].sort(),
    `🚨 AC-13 违反：树的骨架变了（行数/层级/父子/node_path）。\n` +
    `   本次只改「版本」这一列的取值来源，🚫 不得改变主任务交付的树结构。\n` +
    `   实际：\n     ${gotSkeleton.join('\n     ')}\n   期望：\n     ${wantSkeleton.join('\n     ')}`)
    .toEqual([...wantSkeleton].sort());

  // ② 与基线文件 [B2] 的骨架逐字比（第二个独立数据源，防"AC 常量抄错"）
  const b2 = baselineSection('B2');
  const beforeSk = b2.split('\n').map((l) => l.trim())
    .filter((l) => /^300\d+/.test(l))
    .map((l) => { const c = l.split('|'); return `${c[0]}|lvl=${c[1]}|parent=${c[2] || '(root)'}`; });
  expect(beforeSk.length, `基线 [B2] 解析出 0 行 —— 解析口径不对，本条会空跑`).toBe(BASELINE_ROW_COUNT);
  expect([...gotSkeleton].sort(), `AC-13：与基线文件 [B2] 的骨架不一致`).toEqual([...beforeSk].sort());

  // ③ 版本列**应该**变了 —— 这是本次改动的目的。若一模一样，说明改动 1 没生效。
  const beforeVer = Object.fromEntries(b2.split('\n').map((l) => l.trim()).filter((l) => /^300\d+/.test(l))
    .map((l) => { const c = l.split('|'); return [c[0], c[3]]; }));
  const afterVer = Object.fromEntries(after.map((r) => [r.nodePath, r.bomVersion ?? '(null)']));
  console.log(`[R260910] AC-13 版本列 before=${JSON.stringify(beforeVer)}`);
  console.log(`[R260910] AC-13 版本列 after =${JSON.stringify(afterVer)}`);
  expect(JSON.stringify(afterVer),
    `AC-13/AC-1：版本列取值与改动前**完全相同** ⇒ 骨架 SQL 的 bom_version 改动没生效。\n` +
    `   （AC-13 允许变的正是这一列；它不变才是问题）`).not.toBe(JSON.stringify(beforeVer));

  // ④ 版本列的新值必须等于 AC-1 期望（按 partNo 归并）
  const mismatch: string[] = [];
  for (const r of after) {
    const want = EXPECTED_CURRENT_VERSION[r.partNo];
    const got = r.bomVersion;
    const ok = want === null ? (got === null || got === '' || got === '—') : got === want;
    if (!ok) mismatch.push(`${r.nodePath}: 实得 ${got ?? '(null)'} / 期望 ${want ?? '(null=叶子)'}`);
  }
  expect(mismatch, `🚨 AC-1（库侧同源证据）不达：\n     ${mismatch.join('\n     ')}`).toEqual([]);

  saveEvidence('31-AC13-树结构对照',
    `before(骨架)\n  ${beforeSk.join('\n  ')}\nafter(骨架)\n  ${gotSkeleton.join('\n  ')}\n` +
    `版本列 before=${JSON.stringify(beforeVer)}\n版本列 after =${JSON.stringify(afterVer)}`);
});

// ─────────────────────── AC-14（无副作用）───────────────────────

test('AC-14 · 报价侧零影响：QUOTE 骨架配置字节未动 + 报价单卡片值 md5 逐字不变', async () => {
  // ① 配置侧
  const quote = sqlRows(`SELECT id, name, md5(sql_template) FROM costing_bom_tree_config WHERE usage='QUOTE' AND is_active`);
  expect(quote.length, `usage='QUOTE' 生效配置不是恰好 1 条（${quote.length} 条）`).toBe(1);
  expect(quote[0][2],
    `🚨 AC-14 违反：报价侧骨架 sql_template 变了（${quote[0][2]} ≠ 基线 ${BASELINE_QUOTE_MD5}）`).toBe(BASELINE_QUOTE_MD5);

  // ② 渲染侧：同一张报价单所有行项的 quote_card_values md5 与基线逐字比
  const b6 = baselineSection('B6');
  const before = new Map(b6.split('\n').map((l) => l.trim()).filter((l) => /^[0-9a-f]{8}-/.test(l))
    .map((l) => { const c = l.split('|'); return [c[0], { part: c[1], quoteMd5: c[2], costingMd5: c[3] }] as const; }));
  expect(before.size, `基线 [B6] 解析出 0 行 —— 本条会空跑（假绿）`).toBeGreaterThan(0);

  const after = new Map(sqlRows(`
    SELECT id, product_part_no_snapshot,
           md5(COALESCE(quote_card_values::text,'(null)')),
           md5(COALESCE(costing_card_values::text,'(null)'))
    FROM quotation_line_item
    WHERE quotation_id=(SELECT quotation_id FROM costing_order WHERE id='${COID}') ORDER BY sort_order`)
    .map((c) => [c[0], { part: c[1], quoteMd5: c[2], costingMd5: c[3] }] as const));

  const common = [...before.keys()].filter((id) => after.has(id));
  expect(common.length, 'AC-14 可比行项为空 ⇒ 断言空跑').toBeGreaterThan(0);
  const drift = common.filter((id) => before.get(id)!.quoteMd5 !== after.get(id)!.quoteMd5)
    .map((id) => `${after.get(id)!.part}: ${before.get(id)!.quoteMd5} → ${after.get(id)!.quoteMd5}`);
  expect(drift,
    `🚨 AC-14 违反：报价侧 quote_card_values 被改动波及（本次只动核价侧骨架与核价树分支）。\n` +
    `   变化：\n     ${drift.join('\n     ')}`).toEqual([]);

  saveEvidence('32-AC14-报价侧零影响',
    `QUOTE 配置 md5 = ${quote[0][2]}（基线 ${BASELINE_QUOTE_MD5}）\n` +
    common.map((id) => `${after.get(id)!.part}\tquote=${after.get(id)!.quoteMd5}\tcosting=${after.get(id)!.costingMd5}`).join('\n'));
});
