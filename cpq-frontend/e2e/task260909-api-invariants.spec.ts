/**
 * task-260909「已有产品抽屉数据源收敛」· 接口层 + SQL 对账用例
 *
 * 覆盖：AC-1 / AC-2 / AC-4(全表对账) / AC-4b / AC-5(字段独立性) / AC-7③ / AC-9 / AC-14 / AC-15 / AC-16
 *
 * 🚫 断言全部从 `需求文档.md §③` AC 原文派生，未读实现代码。
 * 🚫 零硬编码计数：每条计数断言都在断言的同一时刻用 SQL 取不变量（`testing.md §4.5` 全局计数陷阱）。
 * 🚫 全程只读 `ds_quote_customer_part`；唯一写入面是自建的 `T260909-` 报价单，afterAll 回收。
 */

import { test, expect, APIRequestContext } from '@playwright/test';
import {
  BACKEND_URL, MASTER_BACKEND_URL, CUSTOMERS,
  apiContext, fetchExistingProducts, createTestQuotation, cleanupTestQuotations,
  sqlRaw, sqlScalar, distinctMaterialCount,
} from './fixtures/task260909';

let api: APIRequestContext;
/** 每个客户一张自建报价单，全套复用（只读接口，复用不影响隔离）。 */
const q: Record<string, string> = {};

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  api = await apiContext(BACKEND_URL);
  q[CUSTOMERS.CHINT] = (await createTestQuotation(api, CUSTOMERS.CHINT, 'API-CHINT')).id;
  q[CUSTOMERS.ROCKWELL] = (await createTestQuotation(api, CUSTOMERS.ROCKWELL, 'API-RW')).id;
  // 苏州西门子名下实测 0 个报价模板 ⇒ 造不出能进 Step2 的单。
  // 本 spec 全是接口用例，不走 UI，故 needStep2=false（AC-2 只要求接口层不变量断言）。
  q[CUSTOMERS.SIEMENS] = (await createTestQuotation(api, CUSTOMERS.SIEMENS, 'API-SIE', false)).id;
});

test.afterAll(async () => {
  await cleanupTestQuotations(api);
  await api.dispose();
});

/**
 * 取"稳定的"不变量：SQL 前后各取一次，中间夹着接口调用。
 * 两次不等 ⇒ 别的会话正在写这张表，本次对账**结论不可信**，硬失败并说明原因，
 * 🚫 不许悄悄取其中一个继续断言（那会变成一条运气驱动的红/绿）。
 */
async function assertTotalMatchesInvariant(customerNo: string, quotationId: string) {
  const before = distinctMaterialCount(customerNo);
  const pageData = await fetchExistingProducts(api, quotationId, { page: 0, size: 20 });
  const after = distinctMaterialCount(customerNo);
  expect(before,
    `对账期间 ds_quote_customer_part(${customerNo}) 发生了变化（${before} → ${after}）—— ` +
    `共享库被别的会话写了，本次对账结论不可信，请重跑而不是当作缺陷`,
  ).toBe(after);
  expect(pageData.totalElements,
    `客户 ${customerNo}：接口 totalElements 应等于当场实测的 ` +
    `count(DISTINCT material_no)=${before}（AC-1/AC-2 不变量断言）`,
  ).toBe(before);
  return { total: before, pageData };
}

// ──────────────────────────────────────────────────────────────────────────
// T0 · 夹具自检：只读守卫的证伪实验（`testing.md §4.4`）
// ──────────────────────────────────────────────────────────────────────────

test('T0 · 夹具自检 · sqlRaw 只读守卫会硬拒非只读语句', () => {
  // 只读守卫是【白名单】：只有 SELECT / WITH / EXPLAIN 开头的语句放行，其余一律抛错。
  // 🚨 首次 PASS 证明不了守卫接上了，必须故意破坏它保护的条件确认它硬失败。
  //
  // 📌 负向数据为什么不含真实的危险关键词：守卫是**白名单**，它的契约是
  //    「非白名单前缀一律拒」—— 验这个契约用一个明显不属于白名单的假语句，
  //    比用真 DDL 更贴合契约本身，证伪强度不降反升。
  //    🚫 更不要用字符串拼接去规避文本检测：那个手法本身就是绕路的标准形态，
  //    不该在代码库里留下先例（下一个照抄的人未必还有"这些串永不执行"的前提）。
  const mustReject = [
    'DELETE FROM ds_quote_customer_part WHERE 1=1',
    "UPDATE quotation SET name='x'",
    'INSERT INTO t VALUES (1)',
    'NOT_A_READONLY_STATEMENT foo',
    '  \n  MERGE INTO t USING s ON (1=1)',   // 顺带验前导空白被 trim 后仍按首词判定
  ];
  for (const sql of mustReject) {
    expect(() => sqlRaw(sql), `只读守卫应拒绝：${JSON.stringify(sql)}`).toThrow(/只允许只读 SQL/);
  }
  // 阳性对照：合法的只读语句必须真的能跑通并返回值 —— 否则守卫是"全拒"的恒真
  expect(sqlScalar('SELECT 1'), '阳性对照：SELECT 1 应能正常执行并返回 1').toBe(1);
});

// ──────────────────────────────────────────────────────────────────────────
// T1 · AC-1 核心：改动前 vs 改动后 A/B 对照
// ──────────────────────────────────────────────────────────────────────────

test('T1 · AC-1 · 正泰导入产品可见 —— master vs worktree 同库 A/B 对照', async () => {
  // 🚨 证伪前置：两端 URL 必须不同。相同 ⇒ 两个请求打到同一个后端 ⇒ 整条用例是空验证。
  //    `testing.md §4.2`「探活必须验明正身」的同族要求：不验就会把断言打在错误的实例上。
  expect(MASTER_BACKEND_URL,
    `A/B 两侧后端 URL 相同（${MASTER_BACKEND_URL}）—— 这不是"无差异"，是两个请求打到了同一个实例，\n` +
    `整条 A/B 用例将是空验证。请用 PW_MASTER_BACKEND_URL 指向主仓 master 后端（未改动的那个）。`,
  ).not.toBe(BACKEND_URL);

  const masterApi = await apiContext(MASTER_BACKEND_URL);
  try {
    // 改动前一侧：同一个 quotationId、同一个客户、同一个库
    const before = await fetchExistingProducts(masterApi, q[CUSTOMERS.CHINT], { page: 0, size: 20 });
    const after = await fetchExistingProducts(api, q[CUSTOMERS.CHINT], { page: 0, size: 20 });
    const invariant = distinctMaterialCount(CUSTOMERS.CHINT);

    console.log(`[T1] beforeTotal=${before.totalElements}  afterTotal=${after.totalElements}  SQL不变量=${invariant}`);

    // 主断言：改后 == 当场实测不变量
    expect(after.totalElements,
      `改动后 total 应等于 count(DISTINCT material_no)=${invariant}`,
    ).toBe(invariant);

    // 症状断言：改前几乎全不可见（实测为 1）
    expect(before.totalElements,
      `改动前正泰抽屉应几乎看不到产品（≤5）；实际 ${before.totalElements}。` +
      `若这里已经很大，说明 master 侧后端也带了本次改动 —— A/B 失效。`,
    ).toBeLessThanOrEqual(5);

    // 量级断言（🚫 不用绝对下界 2662：那 2662 里 2645 个是别的会话的性能造数，被清一次就假红）
    expect(after.totalElements,
      `改动后应比改动前高两个数量级以上（before=${before.totalElements}, after=${after.totalElements}）`,
    ).toBeGreaterThan(before.totalElements * 100);

    // 首屏结构断言 + 非空保护
    expect(after.content.length, '第一页应有 20 行').toBe(20);
    for (const [i, r] of after.content.entries()) {
      expect(String(r.materialNo ?? '').trim(), `第 ${i + 1} 行「销售料号」不应为空`).not.toBe('');
    }

    console.log(`[T1] 证据：before=${before.totalElements} → after=${after.totalElements}（×${(after.totalElements / Math.max(1, before.totalElements)).toFixed(0)}）`);
  } finally {
    await masterApi.dispose();
  }
});

test('T2 · AC-1 · 正泰 total 不变量（A/B 不可用时的独立覆盖）', async () => {
  const { total, pageData } = await assertTotalMatchesInvariant(CUSTOMERS.CHINT, q[CUSTOMERS.CHINT]);
  expect(total, '正泰应有数据（非空保护：0 会让后续每条断言空跑）').toBeGreaterThan(0);
  expect(pageData.totalPages, 'totalPages 应等于 ceil(total/size)').toBe(Math.ceil(total / 20));
});

// ──────────────────────────────────────────────────────────────────────────
// T3 · AC-2 其余客户不变量
// ──────────────────────────────────────────────────────────────────────────

test('T3 · AC-2 · 罗克韦尔与苏州西门子的 total 不变量', async () => {
  // 罗克韦尔：有数据，非空保护
  const rw = await assertTotalMatchesInvariant(CUSTOMERS.ROCKWELL, q[CUSTOMERS.ROCKWELL]);
  expect(rw.total, '罗克韦尔应有数据').toBeGreaterThan(0);

  // 苏州西门子：dqcp 实测 0 行 —— 这是 AC-2 明写的期望值，也是 AC-12 空态的接口面。
  // ⚠️ 这条"期望为 0"的断言不构成空跑：它断言的正是"0"这个值本身，且上面罗克韦尔一条
  //    已提供阳性对照，证明同一段代码在有数据时会返回非 0（`testing.md §5.5` 阳性对照要求）。
  const sie = await assertTotalMatchesInvariant(CUSTOMERS.SIEMENS, q[CUSTOMERS.SIEMENS]);
  expect(sie.pageData.content.length, '苏州西门子无数据，content 应为空数组').toBe(0);
  console.log(`[T3] 罗克韦尔 total=${rw.total}；苏州西门子 total=${sie.total}`);
});

// ──────────────────────────────────────────────────────────────────────────
// T7 · AC-4 客户图号全表对账
// ──────────────────────────────────────────────────────────────────────────

test('T7 · AC-4 · 客户图号全表对账（料号级口径）', async () => {
  const total = distinctMaterialCount(CUSTOMERS.CHINT);
  expect(total, '正泰应有数据').toBeGreaterThan(0);

  const all = await fetchExistingProducts(api, q[CUSTOMERS.CHINT], { page: 0, size: total });
  // ⚠️ 后端可能对 size 设上限；被截断就退化为逐页累加，并在报告里写明。
  let rows = all.content;
  if (rows.length < total) {
    console.log(`[T7] size=${total} 被截断为 ${rows.length} 行，退化为逐页累加`);
    rows = [];
    const pageSize = 200;
    for (let p = 0; p * pageSize < total; p++) {
      const pg = await fetchExistingProducts(api, q[CUSTOMERS.CHINT], { page: p, size: pageSize });
      rows.push(...pg.content);
    }
  }
  expect(rows.length, `全量拉取应得到 ${total} 行`).toBe(total);

  const withDrawing = rows.filter((r) => String(r.customerDrawingNo ?? '').trim() !== '').length;
  // 🚨 必须用 count(DISTINCT material_no)：接口经 DISTINCT ON 是料号级，
  //    行级 count 会把「一料号挂多行」重复计入（正泰 T260907-M1 就挂 2 行）⇒ 必然差 1 的假红。
  const expected = sqlScalar(
    `SELECT count(DISTINCT material_no) FROM ds_quote_customer_part ` +
    `WHERE customer_no='${CUSTOMERS.CHINT}' AND NULLIF(customer_drawing_no,'') IS NOT NULL`,
  );
  expect(expected, '有图号的料号数应 > 0（非空保护）').toBeGreaterThan(0);
  expect(withDrawing, `接口返回的有图号料号数应等于 SQL 实测 ${expected}`).toBe(expected);
  console.log(`[T7] 全量 ${rows.length} 行，其中有图号 ${withDrawing}，SQL 实测 ${expected}`);
});

// ──────────────────────────────────────────────────────────────────────────
// T9 · AC-5 品名 / 客户物料名 字段独立性（接口层）
// ──────────────────────────────────────────────────────────────────────────

test('T9 · AC-5 · productName 与 customerMaterialName 取两个不同的列', async () => {
  const points = [
    { materialNo: 'S0004', customerMaterialName: '罗克韦尔触桥组件A', productName: '触桥组件A' },
    { materialNo: 'S0001', customerMaterialName: '示例客户料号', productName: '铆钉' },
    { materialNo: 'S0012', customerMaterialName: '正泰端子组件C', productName: '端子组件C' },
  ];
  for (const p of points) {
    const pg = await fetchExistingProducts(api, q[CUSTOMERS.ROCKWELL], { salesPartNo: p.materialNo, size: 20 });
    expect(pg.content.length, `罗克韦尔下应能搜到料号 ${p.materialNo}（非空保护）`).toBeGreaterThan(0);
    const row = pg.content.find((r) => r.materialNo === p.materialNo);
    expect(row, `返回里应含 materialNo=${p.materialNo}，实际 = ${JSON.stringify(pg.content.map((r) => r.materialNo))}`).toBeTruthy();

    expect(row!.customerMaterialName, `${p.materialNo} 的 customerMaterialName 应取 dqcp.customer_part_name`)
      .toBe(p.customerMaterialName);
    expect(row!.productName, `${p.materialNo} 的 productName 应取 v_compat_material_master.material_name`)
      .toBe(p.productName);
    // 本次要修掉的就是"两列必然相同"
    expect(row!.customerMaterialName, `${p.materialNo}：两列不得相同（改动前它们同取 r[2]）`)
      .not.toBe(row!.productName);

    // 与库当场对账，防"值恰好写死成期望值"
    const dbCustName = sqlRaw(
      `SELECT customer_part_name FROM ds_quote_customer_part ` +
      `WHERE customer_no='${CUSTOMERS.ROCKWELL}' AND material_no='${p.materialNo}' ORDER BY created_at LIMIT 1`,
    );
    const dbProdName = sqlRaw(`SELECT material_name FROM v_compat_material_master WHERE material_no='${p.materialNo}'`);
    expect(row!.customerMaterialName, `${p.materialNo} customerMaterialName 应与库一致`).toBe(dbCustName);
    expect(row!.productName, `${p.materialNo} productName 应与库一致`).toBe(dbProdName);
  }
});

test('T9b · AC-5b · 品名为空时回退销售料号，不回退客户物料名', async () => {
  const target = '0028-2609000001';
  // 前提自检：该料号在主数据视图里确实无命中，否则这条用例验的不是兜底路径（`testing.md §5.5` 恒真判据）
  const hit = sqlScalar(`SELECT count(*) FROM v_compat_material_master WHERE material_no='${target}'`);
  expect(hit, `前提失效：${target} 在 v_compat_material_master 里已有记录（${hit} 行），兜底路径不会被触发`).toBe(0);

  const pg = await fetchExistingProducts(api, q[CUSTOMERS.CHINT], { salesPartNo: target, size: 20 });
  expect(pg.content.length, `正泰下应能搜到 ${target}`).toBeGreaterThan(0);
  const row = pg.content.find((r) => r.materialNo === target)!;
  expect(row, `返回里应含 ${target}`).toBeTruthy();
  expect(row.productName, `品名为空时应回退销售料号本身`).toBe(target);
  expect(row.productName, `🚫 不许回退到 customer_part_name（那会让 AC-5 白修）`)
    .not.toBe(row.customerMaterialName);
});

// ──────────────────────────────────────────────────────────────────────────
// T14 · AC-7③ 接口 JSON 不含 3D 字段
// ──────────────────────────────────────────────────────────────────────────

test('T14 · AC-7③ · 响应 JSON 不含 has3d / thumbnailUrl', async () => {
  const pg = await fetchExistingProducts(api, q[CUSTOMERS.CHINT], { page: 0, size: 20 });
  expect(pg.content.length, '需要非空样本才能验字段缺失（0 行会让本条空跑）').toBeGreaterThan(0);
  // 🚨 逐行验，不只验第一行 —— 序列化可能按行裁剪 null 字段
  for (const [i, r] of pg.content.entries()) {
    const keys = Object.keys(r);
    expect(keys, `第 ${i + 1} 行仍含 has3d 字段：${JSON.stringify(keys)}`).not.toContain('has3d');
    expect(keys, `第 ${i + 1} 行仍含 thumbnailUrl 字段：${JSON.stringify(keys)}`).not.toContain('thumbnailUrl');
  }
  // 阳性对照：确认我们确实在检查一个字段齐全的对象，而不是一个空壳
  expect(Object.keys(pg.content[0]), '样本行应含 materialNo —— 否则本条在检查一个空对象')
    .toContain('materialNo');
});

// ──────────────────────────────────────────────────────────────────────────
// T16 · AC-9 老选配消失（正）+ MANUAL 保留（反）
// ──────────────────────────────────────────────────────────────────────────

test('T16 · AC-9 · sel_product_no 独有料号消失（预期）+ dqcp MANUAL 料号保留', async () => {
  // ── 正向：spn 独有（不在 dqcp）的料号，改后应查不到 ──
  for (const [custCode, quotationId] of [
    [CUSTOMERS.ROCKWELL, q[CUSTOMERS.ROCKWELL]],
    [CUSTOMERS.CHINT, q[CUSTOMERS.CHINT]],
  ] as const) {
    const orphans = sqlRaw(
      `SELECT DISTINCT s.quote_part_no FROM sel_product_no s ` +
      `WHERE s.customer_no='${custCode}' AND NOT EXISTS (` +
      `SELECT 1 FROM ds_quote_customer_part d WHERE d.customer_no=s.customer_no AND d.material_no=s.quote_part_no)`,
    ).split('\n').map((x) => x.trim()).filter(Boolean);

    // 非空保护：没有 spn 独有料号就等于没样本，本条会变成恒真的空验证
    expect(orphans.length,
      `客户 ${custCode} 在 sel_product_no 里没有独有料号 —— AC-9 正向断言无样本可验，` +
      `请报主线（数据前提已漂移，不是产品缺陷）`,
    ).toBeGreaterThan(0);
    console.log(`[T16] ${custCode} 应消失的老选配料号 = ${JSON.stringify(orphans)}`);

    for (const mat of orphans) {
      const pg = await fetchExistingProducts(api, quotationId, { salesPartNo: mat, size: 20 });
      const hit = pg.content.filter((r) => r.materialNo === mat);
      expect(hit.length,
        `${custCode} 的老选配料号 ${mat} 仍出现在列表中。\n` +
        `⚠️ 预期是"消失"（用户裁决存量不迁，AC-9）—— 出现才是缺陷，消失不是。`,
      ).toBe(0);
    }
  }

  // ── 反向：dqcp 里 source='MANUAL' 的料号必须仍然出现（防修过头把选配产品也弄丢）──
  const manuals = sqlRaw(
    `SELECT DISTINCT material_no FROM ds_quote_customer_part ` +
    `WHERE customer_no='${CUSTOMERS.ROCKWELL}' AND source='MANUAL' ORDER BY 1`,
  ).split('\n').map((x) => x.trim()).filter(Boolean);
  expect(manuals.length, '罗克韦尔应有 MANUAL 料号作为反向断言样本（非空保护）').toBeGreaterThan(0);
  console.log(`[T16] 必须仍然出现的 MANUAL 料号 = ${JSON.stringify(manuals)}`);

  for (const mat of manuals) {
    const pg = await fetchExistingProducts(api, q[CUSTOMERS.ROCKWELL], { salesPartNo: mat, size: 20 });
    expect(pg.content.some((r) => r.materialNo === mat),
      `选配产品 ${mat}（source=MANUAL）应仍然出现在列表中 —— 缺失说明收敛改过头了`,
    ).toBe(true);
  }
});

// ──────────────────────────────────────────────────────────────────────────
// T21 · AC-14 按非代表编号也能搜到
// ──────────────────────────────────────────────────────────────────────────

test('T21 · AC-14 · 按第二个客户产品编号搜索，命中同一个料号', async () => {
  const secondNo = 'T260907R-SEL-D40B';
  const expectMat = sqlRaw(
    `SELECT material_no FROM ds_quote_customer_part ` +
    `WHERE customer_no='${CUSTOMERS.ROCKWELL}' AND customer_product_no='${secondNo}'`,
  );
  expect(expectMat, `前提失效：库里找不到编号 ${secondNo}，请报主线（数据漂移）`).toBeTruthy();

  const pg = await fetchExistingProducts(api, q[CUSTOMERS.ROCKWELL], { customerProductNo: secondNo, size: 20 });
  expect(pg.totalElements, `按非代表编号 ${secondNo} 搜应命中 1 行（过滤谓词须在去重之前生效）`).toBe(1);
  expect(pg.content[0].materialNo, `命中的料号应为 ${expectMat}`).toBe(expectMat);
});

// ──────────────────────────────────────────────────────────────────────────
// T22 · AC-15 分页性能（预热 3 次 + 5 次取中位数）
// ──────────────────────────────────────────────────────────────────────────

test('T22 · AC-15 · 首屏接口中位耗时 < 500ms', async () => {
  const call = async () => {
    const t0 = Date.now();
    const pg = await fetchExistingProducts(api, q[CUSTOMERS.CHINT], { page: 0, size: 20 });
    const ms = Date.now() - t0;
    // 非空保护：0 行的响应当然快，但那不是本条要证的
    expect(pg.content.length, '计时样本应是有数据的响应').toBeGreaterThan(0);
    return ms;
  };
  // 🚨 预热 3 次 —— worktree 后端是临时新起实例，首个请求含 JIT 与连接池预热，
  //    单次冷测极易 > 500ms，那是假红不是性能缺陷（AC-15 测量协议）。
  for (let i = 0; i < 3; i++) await call();

  const samples: number[] = [];
  for (let i = 0; i < 5; i++) samples.push(await call());
  const median = [...samples].sort((a, b) => a - b)[2];
  console.log(`[T22] 5 次耗时 = ${JSON.stringify(samples)} ms，中位数 = ${median} ms`);
  expect(median, `首屏接口中位耗时应 < 500ms，实测 ${median}ms（样本 ${JSON.stringify(samples)}）`)
    .toBeLessThan(500);
});

// ──────────────────────────────────────────────────────────────────────────
// T23 · AC-16 索引已建（EXPLAIN 那半条已按 D-2 裁决从 AC 删除）
// ──────────────────────────────────────────────────────────────────────────

test('T23 · AC-16 · 索引 idx_ds_quote_customer_part_customer_no 存在且 V436 已成功应用', async () => {
  const idx = sqlRaw(
    `SELECT indexname FROM pg_indexes WHERE tablename='ds_quote_customer_part' ` +
    `AND indexname='idx_ds_quote_customer_part_customer_no'`,
  );
  expect(idx, '未找到索引 idx_ds_quote_customer_part_customer_no').toBe('idx_ds_quote_customer_part_customer_no');

  const ok = sqlRaw(`SELECT success FROM flyway_schema_history WHERE version='436'`);
  expect(ok, `迁移 V436 未在 flyway_schema_history 中成功应用（success=${JSON.stringify(ok)}）`).toBe('t');

  // 🚨 证伪：同一条查法对一个不存在的索引名必须返回空 —— 否则这条判据是恒真的（`testing.md §4.4`）
  const bogus = sqlRaw(
    `SELECT indexname FROM pg_indexes WHERE tablename='ds_quote_customer_part' ` +
    `AND indexname='idx_task260909_definitely_not_exist'`,
  );
  expect(bogus, '证伪失败：查一个不存在的索引名居然有返回，说明这条判据恒真').toBe('');
});
