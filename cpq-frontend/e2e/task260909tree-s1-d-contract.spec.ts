/**
 * task-260909 · S1 只读片 · **D 组：契约与权限（SQL + 只读端点）**
 *
 * 覆盖 AC：**AC-13**（重编译后 sql_template 不再含 ds_quote_material、轴谓词形如
 *          `<轴列> = ANY(:total_material_no)`）、**AC-14**（客户隔离不丢）、
 *          **AC-18**（5 个端点 × 2 账号 的 403/200）
 *
 * 🚫 写入面：无。
 *    - AC-18 只**探测**状态码；对 `SALES_MANAGER` 期望 403 ⇒ 不会产生任何写入。
 *    - 对 `admin`：**只调 `GET`**。🚫 本片不发 POST/PUT/PATCH/DELETE ——
 *      那些会真的建/改/删生效配置，属 **S-全局** 片的写入面（AC-3/4/5/19）。
 *      ⇒ AC-18 中「admin 调同一端点 → 200」按 AC 原文只点名了 GET，本片只验 GET；
 *        4 个写端点仅验 `SALES_MANAGER` 侧的 403（见下方用例注释）。
 */
import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';
import {
  ADMIN, BACKEND_URL, CUSTOMER_CODE, COMPONENTS, QUOTATION_ID,
  salesManagerCred, loginApi, sqlRows,
  writeEvidence, appendEvidence, recordBackendIdentity,
} from './fixtures/task260909tree';

test.describe.configure({ mode: 'serial' });

const CFG_PATH = '/api/cpq/costing-bom-tree-config';

/**
 * 调后端并只取状态码。
 * 🚨 鉴权是 **Cookie 会话**（`CPQ_SESSION`），不是 Bearer token（2026-09-09 实证）。
 * `redirect: 'manual'` 防止 302 被自动跟随后把 403 洗成 200。
 */
async function call(method: string, path: string, cookie: string): Promise<number> {
  const res = await fetch(`${BACKEND_URL}${path}`, {
    method,
    redirect: 'manual',
    headers: {
      Cookie: cookie,
      ...(method === 'GET' ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
  return res.status;
}

// ══════════════════════════════════════════════════════════════════════════
// AC-13：轴口径统一后的 sql_template 契约
// ══════════════════════════════════════════════════════════════════════════
test('T-D1 / AC-13：COMP-2298/2299/2300 的 sql_template 不再含 ds_quote_material，轴谓词为 = ANY(:total_material_no)',
  async () => {
    recordBackendIdentity();

    const codes = [COMPONENTS.产品, COMPONENTS.BOM, COMPONENTS.材质元素];
    const rows = sqlRows(
      `SELECT c.code, csv.sql_view_name, csv.status,
              (csv.builder_config::jsonb)->>'dialect' AS dialect,
              length(csv.sql_template) AS len,
              (csv.sql_template ILIKE '%ds_quote_material%') AS has_dqm
         FROM component_sql_view csv JOIN component c ON c.id = csv.component_id
        WHERE c.code IN ('${codes.join("','")}') AND csv.status='ACTIVE'
        ORDER BY c.code`,
    );

    // 防空验证：三条都得在，否则「没有一条含 ds_quote_material」是因为压根没查到行
    console.log('[AC-13] rows =', JSON.stringify(rows, null, 2));
    expect(rows.length, `AC-13：应查到 3 个 ACTIVE 的 COST_BASIC 组件视图，实得 ${rows.length}`).toBe(3);

    const evidence: string[] = [];
    for (const r of rows) {
      expect(r.dialect, `AC-13：${r.code} 的方言应为 COST_BASIC`).toBe('COST_BASIC');
      expect(
        r.has_dqm,
        `AC-13：${r.code} 的 sql_template 仍含 ds_quote_material 子查询（改动前实测=t，改动后应为 f）`,
      ).toBe('f');

      // 轴谓词形如 `<轴列> = ANY(:total_material_no)`
      // ⚠️ 用 `-t -A` 单列口径取**全文**（含换行）：`sqlRows` 按 `|` 切列，
      //    多行 SQL 会被截断，那会造成「找不到轴谓词」的**假阴性**。
      const full = componentSqlTemplate(r.code);
      const m = full.match(/([A-Za-z_][\w.]*)\s*=\s*ANY\s*\(\s*:total_material_no\s*\)/);
      console.log(`[AC-13] ${r.code} 轴谓词命中 = ${m ? m[0] : '（未命中）'}`);
      expect(
        m,
        `AC-13：${r.code} 的轴谓词应形如 \`<轴列> = ANY(:total_material_no)\`，未在 sql_template 中找到。` +
          `\n实际模板前 400 字：\n${full.slice(0, 400)}`,
      ).not.toBeNull();

      evidence.push(
        `${r.code}  view=${r.sql_view_name}  dialect=${r.dialect}  len=${r.len}\n` +
        `  含 ds_quote_material = ${r.has_dqm}（期望 f）\n` +
        `  轴谓词 = ${m ? m[0] : '（未命中）'}\n` +
        `  ── sql_template 全文 ──\n${full}\n`,
      );
    }
    writeEvidence('AC-13-sql-template.txt', evidence.join('\n' + '─'.repeat(70) + '\n'));
  });

/** 取某组件 ACTIVE 视图的 sql_template 全文（含换行）。🚫 只读。 */
function componentSqlTemplate(code: string): string {
  const sql =
    `SELECT csv.sql_template FROM component_sql_view csv JOIN component c ON c.id=csv.component_id ` +
    `WHERE c.code='${code}' AND csv.status='ACTIVE'`;
  const out = execSync(
    `PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db_0724 -X -A -t -c ${JSON.stringify(sql)}`,
    { shell: '/bin/bash', encoding: 'utf-8' },
  );
  return String(out).trim();
}

// ══════════════════════════════════════════════════════════════════════════
// AC-14：客户隔离不丢
// ══════════════════════════════════════════════════════════════════════════
test('T-D2 / AC-14a：COST_BASIC 生效骨架 SQL 的种子子查询含 dqm.customer_no = :customerCode', async () => {
  const rows = sqlRows(
    "SELECT id, name, usage, is_active, " +
    "  (sql_template ILIKE '%:customerCode%') AS has_cust_param, " +
    "  (sql_template ~* 'customer_no\\s*=\\s*:customerCode') AS has_seed_pred " +
    "  FROM costing_bom_tree_config WHERE usage='COST_BASIC' AND is_active",
  );
  console.log('[AC-14a] rows =', JSON.stringify(rows, null, 2));

  // 防空验证：查不到生效的 COST_BASIC 配置 ⇒ 配置动作（交付项 10）没做，
  // 这是**前置未完成**，不是断言通过
  expect(rows.length,
    'AC-14a：应有恰好 1 条 usage=COST_BASIC 的生效配置（交付项 10 的配置动作）').toBe(1);
  expect(rows[0].has_cust_param, 'AC-14a：骨架 SQL 应引用 :customerCode').toBe('t');
  expect(rows[0].has_seed_pred,
    'AC-14a：骨架 SQL 的种子子查询应含 `customer_no = :customerCode`').toBe('t');

  const full = (() => {
    return String(execSync(
      `PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db_0724 -X -A -t ` +
      `-c "SELECT sql_template FROM costing_bom_tree_config WHERE usage='COST_BASIC' AND is_active"`,
      { shell: '/bin/bash', encoding: 'utf-8' },
    )).trim();
  })();
  writeEvidence('AC-14-skeleton-sql.txt',
    `usage=COST_BASIC 生效配置：${JSON.stringify(rows[0])}\n\n── sql_template 全文 ──\n${full}\n`);
});

/**
 * AC-14b：「实际绑定值为 CUST-0004」+「后端日志不出现 `解析不到本单客户` 告警」。
 *
 * 🚨 **本机 8081 是 `mvnw quarkus:dev` 起在别人终端里的，没有日志文件**
 *    （2026-09-09 实测：`cpq-backend/*.log` 不存在）⇒ 日志断言**无处可读**。
 *    ⇒ 需要主线用 `PW_BACKEND_LOG=<日志文件路径>` 提供后端 stdout 落盘位置
 *      （例如重启时 `./mvnw quarkus:dev 2>&1 | tee /tmp/cpq-backend.log`）。
 *
 * 🚫 拿不到日志时**硬失败**并写明「测试环境缺陷 ⇒ 判【未验证】」，
 *    🚫 不许降级成「没看到告警就算通过」—— 那是零证据（`testing.md §5.5`）：
 *    观察手段本身没接上时，"没观察到" 和 "没发生" 长得一模一样。
 */
test('T-D3 / AC-14b：渲染本单时后端日志不出现「解析不到本单客户」告警，且绑定值为 CUST-0004', async () => {
  const logPath = process.env.PW_BACKEND_LOG || '';
  if (!logPath) {
    throw new Error(
      '🚨 停下报告：AC-14b 需要读后端日志，但本机 8081 的 dev server 没有日志文件。\n' +
        '  · 请主线以 `./mvnw quarkus:dev 2>&1 | tee /tmp/cpq-backend.log` 方式重启后端，\n' +
        '    并用 `PW_BACKEND_LOG=/tmp/cpq-backend.log` 跑本条。\n' +
        '  ⚠️ 这是**测试环境缺陷**，本条判【未验证】。\n' +
        '  🚫 不得降级成「grep 不到告警就算通过」——观察手段没接上时，\n' +
        '     「没观察到」与「没发生」完全无法区分（testing.md §5.5 零证据）。',
    );
  }

  const mark = `[AC-14] probe ${new Date().toISOString()}`;
  const sizeBefore = Number(execSync(`stat -c %s ${JSON.stringify(logPath)} || echo 0`,
    { shell: '/bin/bash', encoding: 'utf-8' }).trim());

  // 触发一次核价渲染（只读端点）
  const cookie = await loginApi(ADMIN.username, ADMIN.password);
  const res = await fetch(`${BACKEND_URL}/api/cpq/quotations/${QUOTATION_ID}`, {
    headers: { Cookie: cookie },
  });
  expect(res.status, 'AC-14b：取报价单详情应 200').toBe(200);

  await new Promise((r) => setTimeout(r, 3000));
  const tail = String(execSync(
    `tail -c +${sizeBefore + 1} ${JSON.stringify(logPath)}`,
    { shell: '/bin/bash', encoding: 'utf-8' },
  ));

  // 🚨 阳性对照：本段日志必须**非空**，否则「没有告警」只是因为一个字都没抓到
  expect(tail.trim().length,
    'AC-14b：本次渲染期间日志增量为空 ⇒ 观察手段没接上（可能不是同一个后端实例），判【未验证】')
    .toBeGreaterThan(0);

  const warn = tail.split('\n').filter((l) => /解析不到本单客户/.test(l));
  console.log(`[AC-14b] 日志增量 ${tail.length} 字节，命中告警 ${warn.length} 条`);
  writeEvidence('AC-14-backend-log.txt', `${mark}\n增量字节=${tail.length}\n告警行=${warn.length}\n${warn.join('\n')}\n`);
  expect(warn.length, `AC-14b：不应出现「[bom-tree render] … 解析不到本单客户」告警：\n${warn.join('\n')}`).toBe(0);

  // 绑定值为 CUST-0004：日志里若打印了 customerCode 就直接核对
  const bound = tail.split('\n').filter((l) => /customerCode/i.test(l));
  if (bound.length) {
    console.log('[AC-14b] 含 customerCode 的日志行：\n' + bound.join('\n'));
    appendEvidence('AC-14-backend-log.txt', `\n含 customerCode 的行：\n${bound.join('\n')}\n`);
    expect(bound.join('\n'), `AC-14b：绑定的客户编码应为 ${CUSTOMER_CODE}`).toContain(CUSTOMER_CODE);
  } else {
    // 🚫 不静默放过：写清「这半条没验到」，交主线在亲验时补
    appendEvidence('AC-14-backend-log.txt',
      '\n⚠️ 日志未打印 customerCode ⇒ 「绑定值 = CUST-0004」这半条本轮【未验证】，需主线亲验补充。\n');
    throw new Error(
      'AC-14b 后半条【未验证】：日志里没有任何含 customerCode 的行，无法证明绑定值是 CUST-0004。\n' +
        '🚫 不得当作通过。请主线在亲验时用 SQL 日志/断点确认，或让后端把绑定值打进日志。',
    );
  }
});

// ══════════════════════════════════════════════════════════════════════════
// AC-18：权限收紧
// ══════════════════════════════════════════════════════════════════════════
test('T-D4 / AC-18a：admin 调 GET /costing-bom-tree-config → 200', async () => {
  const cookie = await loginApi(ADMIN.username, ADMIN.password);
  const code = await call('GET', CFG_PATH, cookie);
  console.log(`[AC-18a] admin GET ${CFG_PATH} → ${code}`);
  appendEvidence('AC-18-http-codes.txt', `admin  GET    ${CFG_PATH}  → ${code}\n`);
  expect(code, 'AC-18：admin(SYSTEM_ADMIN) 调 GET 应 200').toBe(200);
});

test('T-D5 / AC-18b：SALES_MANAGER 调 GET + 4 个写端点一律 403', async () => {
  // 🚨 缺口令硬失败（测试环境缺陷 ⇒ 判【未验证】），🚫 不 skip
  const sm = salesManagerCred();
  const cookie = await loginApi(sm.username, sm.password);

  // ⚠️ 写端点用**不存在的 uuid**，即使权限意外放行也不会改到任何真实配置
  //    （本片写入面必须为 0）。403 应在业务逻辑之前发生，与 id 是否存在无关。
  const NOPE = '00000000-0000-0000-0000-000000000000';
  const probes: [string, string][] = [
    ['GET', CFG_PATH],
    ['POST', CFG_PATH],
    ['PUT', `${CFG_PATH}/${NOPE}`],
    ['POST', `${CFG_PATH}/${NOPE}/activate`],
    ['DELETE', `${CFG_PATH}/${NOPE}`],
  ];

  const lines: string[] = [];
  const codes: number[] = [];
  for (const [m, p] of probes) {
    const c = await call(m, p, cookie);
    console.log(`[AC-18b] ${sm.username} ${m} ${p} → ${c}`);
    lines.push(`${sm.username}  ${m.padEnd(6)} ${p}  → ${c}`);
    codes.push(c);
  }
  appendEvidence('AC-18-http-codes.txt', lines.join('\n') + '\n');

  // 逐条断言（不写「都不是 200」这种弱断言 —— 404/500 也不是 200，但那不叫权限生效）
  probes.forEach(([m, p], i) => {
    expect(codes[i], `AC-18：${sm.username}(SALES_MANAGER) ${m} ${p} 应 403，实得 ${codes[i]}`).toBe(403);
  });
});
