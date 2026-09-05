/**
 * `assertSameDatabase()` 的**证伪实验**（`testing.md §4.4`：新加的守卫必须故意破坏一次，确认硬失败）。
 *
 * 🚨 **这不是验收用例。** 它验的是「那条守卫有没有牙」。
 *    守卫本身防的是一个**只会假绿的洞**（跨库比较），而只会假绿的洞最怕的就是
 *    「加了一条同样只会假绿的守卫」—— 那比没有更糟，因为人会以为已经有防护了。
 *
 * ── 三个变体，由 `PW_FS_DB_EXPECT` 切换（每个变体要用不同的 `PW_DB` 单独跑一轮）──
 *
 *   PW_FS_DB_EXPECT=same   PW_DB=<后端实际连的库>      → 守卫必须**通过**（正对照）
 *   PW_FS_DB_EXPECT=diff   PW_DB=<后端的一个克隆库>    → 守卫必须**硬失败**（负对照，关键）
 *   PW_FS_DB_EXPECT=nowrite（同 same 的库）            → 不写哨兵直接比对，必须**硬失败**
 *
 * 🚩 **负对照为什么必须用「克隆库」而不是随便一个别的库**：
 *    随便一个库会在步骤③（行数不等）就被拦下，那证明的是③有牙，不是④有牙。
 *    只有**同行数、逐行一致的克隆库**能让③通过、把判定压力全部交给④ ——
 *    而这恰恰是 2026-09-04 那次真实事故的形态。
 *
 * ⚠️ 本文件会写库：只写 `S-1630010773` 的 `production_no`（本套已授权的那一行），
 *    由 `assertSameDatabase` 内部 `finally` 自复位，条件恒为该行主键。
 */
import { test, expect } from '@playwright/test';
import {
  assertSameDatabase, readProductionNo, evidence, sqlOne,
  BACKEND_URL, HERO_EDIT,
} from './product-hub-edit.helpers';

const EXPECT = (process.env.PW_FS_DB_EXPECT || '') as 'same' | 'diff' | 'nowrite' | '';

test('FS-DB / 守卫证伪：assertSameDatabase 在跨库时必须硬失败、同库时必须通过', async () => {
  const dbName = process.env.PW_DB || 'cpq_db_0724';
  expect(EXPECT, '本实验必须显式给 PW_FS_DB_EXPECT=same|diff|nowrite —— ' +
    '不给就没有预期，跑出什么都能算「通过」，那正是本实验要防的东西')
    .toMatch(/^(same|diff|nowrite)$/);

  const before = readProductionNo().raw;
  const dbTotal = Number(sqlOne('SELECT count(*) FROM ds_quote_material'));
  console.log(`[FS-DB] 变体=${EXPECT}  PW_DB=${dbName}(${dbTotal} 行)  BACKEND=${BACKEND_URL}`);

  let threw = false;
  let msg = '';
  try {
    // 🚩 三个变体跑的都是**同一条守卫**，只有 `nowrite` 变体多传一个开关跳过哨兵写入。
    //    🚫 刻意不写平行实现 —— 平行实现只能证明"我另写的那份有牙"，证明不了正品有牙。
    await assertSameDatabase(EXPECT === 'nowrite' ? { __fsSkipSentinelWrite: true } : {});
  } catch (e) {
    threw = true;
    msg = (e as Error).message;
  }

  const after = readProductionNo().raw;
  console.log(`[FS-DB] 抛错=${threw}\n${msg.slice(0, 900)}`);
  evidence(`FS-DB-${EXPECT}`,
    `变体=${EXPECT}\nPW_DB=${dbName}（${dbTotal} 行）\nBACKEND=${BACKEND_URL}\n` +
    `守卫是否抛错=${threw}\n错误原文=\n${msg}\n\n` +
    `哨兵前 ${HERO_EDIT}.production_no=${JSON.stringify(before)}\n` +
    `跑完后 ${HERO_EDIT}.production_no=${JSON.stringify(after)}\n` +
    `残留检查=${before === after ? '✅ 已复位，无残留' : '🚨 有残留，须人工处理'}`);

  // 🚨 无论哪个变体，哨兵都必须自复位 —— 守卫抛错的路径上尤其要验，那正是最容易漏还原的路径
  expect(after, `FS-DB[${EXPECT}]：哨兵未复位，${dbName} 上留下了残留 ` +
    `（前=${JSON.stringify(before)} 后=${JSON.stringify(after)}）`).toBe(before);

  if (EXPECT === 'same') {
    expect(threw, `FS-DB[same]：后端与 PW_DB 本就是同一个库，守卫**不该**抛错。\n` +
      `抛了说明守卫过严（假红），会把好环境也拦住。错误原文：\n${msg}`).toBe(false);
  } else {
    expect(threw, `🚨 FS-DB[${EXPECT}]：守卫**必须**硬失败，但它通过了 ⇒ **这条守卫是摆设**。\n` +
      (EXPECT === 'diff'
        ? `  PW_DB=${dbName} 是后端所连库的克隆（行数相同、逐行一致），\n` +
          `  ①②③ 会通过（${dbTotal} == ${dbTotal}），全部判定压力在步骤④。\n` +
          `  ④ 没抓到 ⇒ 哨兵没真正写进去、或后端读的是缓存、或比对逻辑是空的。`
        : `  这个变体压根没写哨兵，后端不可能读到它。守卫却说「同库」⇒ 比对逻辑恒真。`))
      .toBe(true);
    if (EXPECT === 'diff') {
      expect(msg, 'FS-DB[diff]：必须是**步骤④**拦下的（若是步骤③拦的，说明这个负对照没选对库，' +
        '证明的是③有牙而不是④有牙）').toContain('步骤④');
    }
  }
});
