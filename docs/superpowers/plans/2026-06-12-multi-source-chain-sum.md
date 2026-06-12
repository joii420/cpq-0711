# 多 source 链式 SUM + KSUM 嵌套预聚合 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在报价单页签连表公式里写 `SUM([元素.重量(g)]/1000*[元素.单价]*(1+[元素.损耗率]/100) + KSUM([外购件.费用])) * [组成用量]` —— 一个 `SUM` 内引用多个呈行键包含链的 source 页签（v1 多 source 链式 SUM），并新增内层 `KSUM/KAVG/KMAX/KMIN/KCOUNT` 降维投影预聚合（v2），绕开"互不包含维度 → 笛卡尔"硬限制。

**Architecture:** 在既有 `cross_tab_ref` token 上**纯新增** `sources`（有序多 source 链）与 `projectToHostKey`（KSUM 按宿主键塌缩标量）两个内嵌字段，不新增 DB 列、不触发 snapshot 迁移。求值器抽出统一 `aggregateRows` 原语（外层 SUM 与内层 KSUM 复用，仅分组键不同）。前后端对称（前端 `formulaEngine.ts` + `formulaSerialize.ts`，后端 `FormulaCalculator.java` + `TokenMappabilityValidator.java`），cross-tab-cases.json 夹具锁一致。存量单 source token 走完全未改动路径（三判定全 false）。

**Tech Stack:** 前端 React + TypeScript + Vitest（`formulaEngine.test.ts` / `formulaSerialize.test.ts`）；后端 Java 17 + Quarkus + JUnit（`FormulaCalculatorCrossTabFixtureTest`）；PostgreSQL 16（JSONB formula_tokens，无 schema 变更）；Playwright E2E（`quotation-flow.spec.ts`）。

> **Spec:** `docs/superpowers/specs/2026-06-12-multi-source-chain-sum-design.md`（v3.1，含 I-1/I-2 精修）。本 plan 在各 Task 内复述关键代码骨架，实施者可乱序读。
>
> **基线纪律（🔒）**：T4/T5/T6 触三大核心基线（求值器 `formulaEngine.ts` / `FormulaCalculator.java`）—— 本 spec 即 architect 评审产物，可施工，但**退化路径（N=1 无嵌套）必须逐字不变**，由 T7 既有夹具回归证明。
>
> **强制 E2E**：触 `formulaEngine.ts` / `FormulaCalculator.java` / `formulaSerialize.ts` / `TemplateService` 协议级清单 → T10 强制 Playwright（依赖 T0b seed）。
>
> **worktree**：基于 master `beb1d00` 起隔离 worktree（CLAUDE.md 强制）；不另起 dev server，复用主工作区 8081/5174。全程 TDD 先红后绿；只 `git add` 本次明确改动文件，禁 `git add -A`。

---

## File Structure（先锁分解边界）

| 文件 | 职责 | Task |
|---|---|---|
| `cpq-frontend/src/pages/component/types.ts` | `FormulaToken` 加 `sources` / `projectToHostKey` 字段 | T1 |
| `cpq-frontend/src/pages/component/formulaSerialize.ts` | lexer K\* func + C3 误拆文案 + 多 source 成链 + KSUM 折叠 + 回显 + 配色 | T2/T3/T4/T8 |
| `cpq-frontend/src/pages/component/formulaSerialize.test.ts` | 序列化/lexer/折叠/回显/校验 单测 | T2/T3/T4/T8 |
| `cpq-frontend/src/utils/formulaEngine.ts` | 求值器：抽 `aggregateRows` + N 路 join + KSUM 塌缩 + 决策 K 空集分流 | T5 |
| `cpq-frontend/src/utils/formulaEngine.test.ts` | 求值器 vitest（对拍夹具） | T5/T7 |
| `cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json` | 前后端对拍夹具（同步后端副本） | T7 |
| `cpq-backend/.../quotation/service/FormulaCalculator.java` | 后端求值器镜像（C1 sub 透传 + KSUM 白名单 + 决策 K 空集 null） | T6 |
| `cpq-backend/.../component/formula/TokenMappabilityValidator.java` | 校验镜像（白名单 / J / I2 / M） | T6 |
| `cpq-backend/src/test/resources/cross-tab-cases.json` | 后端对拍夹具（与前端副本逐字同步） | T7 |
| `cpq-backend/.../FormulaCalculatorCrossTabFixtureTest.java` | 后端夹具测试 | T7 |
| `cpq-backend/.../TokenMappabilityValidator` 测试（新建或扩 `TemplateCrossTabValidateTest.java`） | 后端绕过拒绝单测 | T6 |
| `cpq-frontend/e2e/quotation-flow.spec.ts` + seed SQL | E2E + T0b seed | T0b/T10 |
| `docs/PRD-v3.md` / `docs/配置方法论-合并版.md` / `docs/RECORD.md` / `docs/反模式.md` | 文档同步 | T10 |

---

## Task 依赖序 + 基线/E2E 标注

```
T0a 探查（最前，决定 T0b/T9/T10 真实工作量）
T0b seed（据 T0a 结论；E2E 前置）
T1 token schema/types           （非基线）
T2 lexer K* + C3 + 多 source 成链 （非基线；formulaSerialize）
T3 KSUM 折叠 + 约束校验（白名单/I1/I2/J/M）（非基线；formulaSerialize）
T4 回显 tokensToDrawerExpression （非基线；formulaSerialize）
T5 前端引擎 aggregateRows + N路join + KSUM塌缩 + 决策K  🔒基线 + 强制E2E
T6 后端引擎（3子项①sub透传②白名单③空集null）+ validator  🔒基线 + 强制E2E
T7 对拍夹具 cross-tab-cases.json（15 类）（锁前后端一致）
T8 配色 insideKsum（P2 必做）+ FN块级错误透出（非基线；formulaSerialize）
T9 Excel 模型 B 降级（TabJoinPlanEvaluator 显式拦截）（据 T0a 探查决定改动量）
T10 E2E 双 spec + 三视图验收 + 文档同步  强制E2E（依赖 T0b）
```

- **触 🔒 基线**：T5、T6（求值器）。
- **强制 E2E**：T5、T6、T10（改 `formulaEngine.ts` / `FormulaCalculator.java`）。
- **依赖 T0b seed**：T10（KSUM E2E 断言无 seed 形同虚设）。

---

### Task 0a: 探查现役模板 / 核价装配链路（评审盲区 C + I-3 最高风险）

> **Spec:** §8.5 / §7.2 / §7.4。**本 Task 无代码产出，只产探查结论**，决定 T0b seed 工作量 + T9 Excel 改动量 + T10 核价单验收是否成立。**已由 plan 作者实地核对，结论如下，实施者执行 Step 复核即可。**

**Files:** 无（只读探查 + DB 查询）

- [ ] **Step 1: 复核现役 报价模板0608 是否有"比宿主细 / 行键含宿主键子集"的 KSUM 可聚合页签**

Run:
```bash
export PGPASSWORD=joii5231
psql -h 10.177.152.12 -U postgres -d cpq_db -t -A -F'|' -c "
SELECT name, id, row_key_fields, data_driver_path
FROM component
WHERE id IN ('ad99c10d-b012-4381-8e4d-98c97ded6b5a','1f82da1b-1a3d-419b-9466-713b18a965eb','3cb220be-c6d2-4956-8031-ec7ec798689d');"
```
Expected（plan 作者已核实，2026-06-12）:
```
外购件|1f82da1b-...|["料件"]|$wgj_view
元素|ad99c10d-...|["料件", "元素"]|$ys_view
来料|3cb220be-...|["料件"]|$ll_view
```

**探查结论（已得）**：
- 宿主 **来料** rowKeyFields=`["料件"]`；**元素** rowKeyFields=`["料件","元素"]`（比宿主细，含宿主键子集 → 外层多 source / KSUM 均可作 source ✓）。
- **外购件** rowKeyFields=`["料件"]`（**与宿主同粒度**，非"更细"）；但 `$wgj_view` 每个料件可有多 **要素** 行（见 Step 2）→ 按料件 rowKey 非唯一 → 正是 **KSUM 降维场景**（多要素行塌缩成料件标量），也是裸引用会触 §10-D ">1 命中报错" 的场景。
- **外购件列只有** `[_料件, 要素, 费用, 单位]`（`declared_columns`），**无 `单价`/`数量` 列** → spec 示例公式 `KSUM([外购件.单价]*[外购件.数量])` 在现役数据上不可直接落地；**现役可落地的真实 KSUM 公式 = `KSUM([外购件.费用])`（inner 单列 → 正好覆盖 C2 单列 KSUM 不复用 shortcut 路径）**。

- [ ] **Step 2: 复核 E2E 客户（苏州西门子 code=8000137）外购件实际行多重性 + 料8 是否有外购件数据**

Run:
```bash
export PGPASSWORD=joii5231
psql -h 10.177.152.12 -U postgres -d cpq_db -t -A -F'|' -c "
SELECT mm.material_name 料件, cost_type 要素, pricing_price 费用
FROM unit_price up LEFT JOIN material_master mm ON mm.material_no = code
WHERE system_type='QUOTE' AND price_type='COMPONENT_OTHER' AND is_current=true
AND customer_no='8000137' ORDER BY 料件;"
```
Expected（plan 作者已核实）:
```
料9|加工费|0.050000
料9|单价|0.200000
料9|运费|0.002000
料9|包装费|0.007000
```
**探查结论（已得）**：苏州西门子下 **料9 有 4 行外购件**（多要素 → KSUM 可塌缩 = 0.259）；**料8 外购件 0 行**（→ 料8 KSUM=0 天然成立，但要验 KSUM>0 需 seed 料8 外购件行 或把验收点改到料9）。**T0b seed 落点 = 给料8 补 ≥2 行外购件**，使 spec §0 料8 验收点（KSUM>0）成立。

- [ ] **Step 3: 复核核价单（CostingSummaryService）是否走 cross_tab_ref / crossTabRows 装配**

Run:
```bash
grep -rln "crossTabRows\|FormulaCalculator\|evalCrossTab\|cross_tab" \
  /home/joii/project/cpq/cpq-backend/src/main/java/com/cpq/costingsummary \
  /home/joii/project/cpq/cpq-backend/src/main/java/com/cpq/costing 2>/dev/null
grep -n "简化求值引擎\|metric\|依赖图" \
  /home/joii/project/cpq/cpq-backend/src/main/java/com/cpq/costingsummary/service/CostingSummaryService.java | head
```
Expected: 第 1 条 grep **无命中**（costing 侧不装配 crossTabRows）；第 2 条命中 "8 项指标的简化求值引擎 / 按依赖图算"。

**探查结论（已得，关键应急说明）**：
> **核价单（CostingSummaryService）使用独立的"8 项指标简化求值引擎"（硬编码 metric 依赖图），完全不走 cross_tab_ref token / FormulaCalculator / crossTabRows。** 唯一装配 crossTabRows 的是 `CardSnapshotService`（报价侧）。前端唯二渲染 cross_tab_ref 的视图 = `QuotationStep2`（编辑）+ `ReadonlyProductCard`（报价详情），**两者复用同一 `buildCrossTabRows`**（导出自 `QuotationStep2.tsx:766`）。
>
> **⟹ spec §7.2 Minor ⑤ 的前提"核价单有独立 crossTabRows 装配需补"为伪命题：核价单根本不评估这类 token，无需补装配、无需单独验收 KSUM。** T10 核价单验收降级为"确认核价单视图不受影响（不渲染该公式、不报错）"，而非"核价单 KSUM 命中正确"。**T0a 应急结论：无需新增核价侧 crossTabRows 装配 Task。**

- [ ] **Step 4: 复核 Excel 模型 B（TabJoinPlanEvaluator）当前对 cross_tab_ref token 的处理**

Run:
```bash
find /home/joii/project/cpq/cpq-backend -name "TabJoinPlanEvaluator.java" 2>/dev/null
grep -n "cross_tab\|projectToHostKey\|sources\|targetExpr\|crossTab" \
  $(find /home/joii/project/cpq/cpq-backend -name "TabJoinPlanEvaluator.java" 2>/dev/null) | head
```
Expected: 找到文件路径 + 列出其对 cross_tab_ref token 的现有处理点（判断"已静默跳过"还是"需新增显式拦截"）。**据此结论决定 T9 改动量**：若已有 cross_tab_ref 分支 → T9 在该分支加 `projectToHostKey===true || sources.size>=2 → throw`；若无 → T9 在求值入口加形态判断 + 抛错。

- [ ] **Step 5: 记录探查结论到 RECORD（不提交代码，只写记忆）**

将 Step1-4 结论汇总，待 T10 一并写入 `docs/RECORD.md`（本 Step 仅在本地笔记记录，避免污染 master）。

- [ ] **Step 6: Commit（探查无代码改动，跳过 commit；结论传递给 T0b/T9/T10）**

无文件改动，不 commit。

---

### Task 0b: seed 测试数据（E2E 强制前置）

> **Spec:** §8.5。据 T0a 结论：现役外购件无 `单价`/`数量` 列、料8 外购件 0 行 → seed 给料8 补 ≥2 行外购件（用现役列 `费用`/`要素`），元素页签料8 保持 Ag/Ni 两行。**幂等 SQL**（先 delete 该 seed 行再 insert）。

**Files:**
- Create: `cpq-frontend/e2e/seed/ksum-seed.sql`

- [ ] **Step 1: 写幂等 seed SQL（料8 外购件 ≥2 行 + 确认元素料8 Ag/Ni）**

Create `cpq-frontend/e2e/seed/ksum-seed.sql`:
```sql
-- KSUM E2E seed (幂等): 料8 外购件 ≥2 行 (现役列 cost_type/要素 + pricing_price/费用),
-- 客户=苏州西门子(8000137)。Kp 期望 = Σ费用 = 1.0 + 0.5 = 1.5 (按下方两行)。
-- 用现役 unit_price 表 + COMPONENT_OTHER 价类型, 与 $wgj_view 同源 (where system_type='QUOTE' and price_type='COMPONENT_OTHER' and is_current=true and customer_no=:customerCode)。

-- 0. 料8 对应 material_no (供 unit_price.code 引用)
--    (实施时按真实 material_master 取料8 的 material_no; 占位 'MAT_LIAO8')
\set liao8_no 'MAT_LIAO8'

-- 1. 先删本 seed 行 (幂等)
DELETE FROM unit_price
 WHERE system_type='QUOTE' AND price_type='COMPONENT_OTHER'
   AND customer_no='8000137' AND code=:'liao8_no'
   AND cost_type IN ('KSUM测试件A','KSUM测试件B');

-- 2. 插 2 行料8 外购件 (费用 1.0 / 0.5 → Kp=1.5)
INSERT INTO unit_price (id, code, customer_no, system_type, price_type, cost_type, pricing_price, unit, is_current, created_at, updated_at)
VALUES
 (gen_random_uuid(), :'liao8_no', '8000137', 'QUOTE', 'COMPONENT_OTHER', 'KSUM测试件A', 1.0, '元', true, now(), now()),
 (gen_random_uuid(), :'liao8_no', '8000137', 'QUOTE', 'COMPONENT_OTHER', 'KSUM测试件B', 0.5, '元', true, now(), now());

-- 3. 验证元素页签料8 仍 Ag/Ni 两行 (只读断言, 不改)
--    SELECT _元素 FROM <ys_view 底表> WHERE _料件='料8';  应含 Ag/Ni
```

> **实施提示**：执行前用 `\d unit_price` 核对真实列名（id 是否 gen_random_uuid、cost_type/pricing_price/unit/is_current/customer_no/code/system_type/price_type 名称），`MAT_LIAO8` 换成料8 真实 material_no（`SELECT material_no FROM material_master WHERE material_name='料8'`）。**保持幂等 DELETE+INSERT，不动其他客户/料件行。**

- [ ] **Step 2: 执行 seed + 验证料8 外购件行落地**

Run:
```bash
export PGPASSWORD=joii5231
psql -h 10.177.152.12 -U postgres -d cpq_db -v ON_ERROR_STOP=1 -f cpq-frontend/e2e/seed/ksum-seed.sql
psql -h 10.177.152.12 -U postgres -d cpq_db -t -A -F'|' -c "
SELECT cost_type, pricing_price FROM unit_price
WHERE system_type='QUOTE' AND price_type='COMPONENT_OTHER'
AND customer_no='8000137' AND cost_type LIKE 'KSUM测试件%' ORDER BY cost_type;"
```
Expected:
```
KSUM测试件A|1.0
KSUM测试件B|0.5
```

- [ ] **Step 3: 验证幂等（重跑一次不报错、不重复）**

Run:
```bash
export PGPASSWORD=joii5231
psql -h 10.177.152.12 -U postgres -d cpq_db -v ON_ERROR_STOP=1 -f cpq-frontend/e2e/seed/ksum-seed.sql
psql -h 10.177.152.12 -U postgres -d cpq_db -t -A -c "
SELECT count(*) FROM unit_price WHERE customer_no='8000137' AND cost_type LIKE 'KSUM测试件%';"
```
Expected: `2`（重跑后仍 2 行，未累加 → 幂等 ✅）。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/e2e/seed/ksum-seed.sql
git commit -m "test(ksum): 料8 外购件 ≥2 行 seed (E2E 前置, 幂等)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 1: token schema（`sources` / `projectToHostKey`）

> **Spec:** §2.2 / §2.3 / §2.5。纯加可选内嵌字段，无 DB 列、无 snapshot 迁移、缺省即 false → 存量 token 不受影响。

**Files:**
- Modify: `cpq-frontend/src/pages/component/types.ts`（`FormulaToken` 接口，cross_tab_ref 专用字段区）

- [ ] **Step 1: 写失败测试（类型层断言新字段存在且可选）**

在 `cpq-frontend/src/pages/component/formulaSerialize.test.ts` 顶部新增（与现有 import 同区）:
```typescript
import type { FormulaToken } from './types';

describe('T1 token schema 扩展', () => {
  it('FormulaToken 支持 sources 与 projectToHostKey（可选,缺省兼容）', () => {
    const legacy: FormulaToken = { type: 'cross_tab_ref', source: 'X', agg: 'SUM' };
    expect(legacy.projectToHostKey).toBeUndefined();   // 缺省 = 旧 token

    const ksum: FormulaToken = {
      type: 'cross_tab_ref', projectToHostKey: true, source: 'WGJ', agg: 'SUM',
      match: [{ a: '料件', b: '料件' }], targetExpr: [{ type: 'field', value: '费用', source: 'WGJ' }],
    };
    expect(ksum.projectToHostKey).toBe(true);

    const multi: FormulaToken = {
      type: 'cross_tab_ref', source: 'YS', agg: 'SUM',
      sources: [{ source: 'YS', sourceLabel: '元素', match: [{ a: '料件', b: '料件' }] }],
    };
    expect(multi.sources?.length).toBe(1);
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "T1 token schema"`
Expected: FAIL — `tsc` 报 `projectToHostKey` / `sources` 不在 `FormulaToken` 上（类型错误）。

- [ ] **Step 3: 加字段到 FormulaToken**

在 `cpq-frontend/src/pages/component/types.ts` 的 cross_tab_ref 专用字段区（`agg?` / `targetExpr?` 附近）追加:
```typescript
  /** cross_tab_ref 单 source（最细驱动 componentId, AP-37 稳定 ID） */
  source?: string;
  /** cross_tab_ref 显示名 */
  sourceLabel?: string;
  /** cross_tab_ref 单列名; targetExpr 存在时为 '' */
  target?: string;
  /** cross_tab_ref 行键配对 a=source 字段, b=宿主字段 */
  match?: Array<{ a: string; b: string }>;
  /** cross_tab_ref 聚合 NONE/SUM/AVG/COUNT/MAX/MIN */
  agg?: string;
  /** cross_tab_ref 行级表达式 (递归: 内可含 projectToHostKey 子 token = KSUM) */
  targetExpr?: FormulaToken[];
  /** v1 多 source 有序链 (最细→更粗); source 镜像为最细 sources[0] */
  sources?: Array<{ source: string; sourceLabel?: string; match: Array<{ a: string; b: string }> }>;
  /** v2 KSUM: true = 按宿主结果行键塌缩成宿主粒度标量 (区别外层 join-set 聚合); 缺省 false */
  projectToHostKey?: boolean;
```
> **注意**：若上述部分字段（source/target/match/agg/targetExpr）已存在于 `FormulaToken`，**只新增 `sources` 与 `projectToHostKey` 两行**，不要重复声明（先 grep `sources?:` / `projectToHostKey` 确认未存在）。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "T1 token schema"`
Expected: PASS（1 passed）。
再跑 `npx tsc --noEmit -p tsconfig.json` → 0 错误。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/types.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(token): FormulaToken 加 sources(多source链) + projectToHostKey(KSUM)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: lexer K\* func + C3 `K SUM` 误拆文案 + 多 source 成链

> **Spec:** §3.1（lexer + C3）/ §3.2（多 source 成链）。现状 lexer 在 `formulaSerialize.ts` 约 `:126-135` 贪婪吃 `[A-Za-z]+`，仅识别 `SUM/AVG/MAX/MIN/COUNT`，未命中抛通用"无法识别的标识符"。

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`（`lex` 函数 + 多 source 收集/成链区，约 `:307-316` 单 source 抛错处）
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 写失败测试（lexer K\* 整词 + C3 误拆 + 多 source 成链）**

```typescript
describe('T2 lexer K* + C3 + 多 source', () => {
  it('KSUM 连写 → func token name=KSUM (不被切成 K+SUM)', () => {
    // __lexForTest 需 export; 若不存在则在 formulaSerialize.ts 加 `export function __lexForTest(e:string){return lex(e);}`
    const toks = __lexForTest('KSUM([外购件.费用])');
    expect(toks.find(t => (t as any).kind === 'func')).toMatchObject({ kind: 'func', name: 'KSUM' });
  });
  it('K SUM(...) 拆写 → 专门 C3 文案 (非通用无法识别)', () => {
    expect(() => __lexForTest('K SUM([外购件.费用])'))
      .toThrow(/不能拆写.*请连写/);
  });
  it('多 source 链 SUM (元素细 + 来料粗, 两两可比) → 不抛"只允许同一细页签"', () => {
    // 元素 rowKey=[料件,元素] ⊇ 来料 rowKey=[料件] → 可比成链
    expect(() => expressionToTokens('SUM([元素.单价] + [来料.组成用量])', MULTI_SRC_CTX))
      .not.toThrow();
  });
});
```
> `MULTI_SRC_CTX` = 测试上下文（提供 selfComponentId=来料、元素/来料 的 componentId + rowKeyFields）。按 `formulaSerialize.test.ts` 既有 ctx 构造惯例补；元素 rowKeyFields=`['料件','元素']`、来料=`['料件']`。

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "T2 lexer"`
Expected: FAIL — KSUM 命中通用"无法识别的标识符"；多 source 抛"只允许引用同一个细页签"。

- [ ] **Step 3: 改 lexer（加 K\* func + C3 误拆文案）**

在 `formulaSerialize.ts` `lex` 的字母分支（现 `if (['SUM','AVG','MAX','MIN','COUNT'].includes(upper))`）替换为:
```typescript
    if (/[A-Za-z]/.test(ch)) {
      let word = '';
      while (i < expr.length && /[A-Za-z]/.test(expr[i])) word += expr[i++];
      const upper = word.toUpperCase();
      const OUTER_FNS = ['SUM', 'AVG', 'MAX', 'MIN', 'COUNT'];
      const INNER_FNS = ['KSUM', 'KAVG', 'KMAX', 'KMIN', 'KCOUNT'];
      if ([...OUTER_FNS, ...INNER_FNS].includes(upper)) {
        tokens.push({ kind: 'func', name: upper });
        continue;
      }
      // 【C3】单字母 K + 空白 + 聚合词 → 误拆专门文案
      if (upper === 'K') {
        let j = i; while (j < expr.length && /\s/.test(expr[j])) j++;
        let peek = ''; let p = j; while (p < expr.length && /[A-Za-z]/.test(expr[p])) peek += expr[p++];
        if (OUTER_FNS.includes(peek.toUpperCase())) {
          throw new Error(`KSUM/KAVG/KMAX/KMIN/KCOUNT 不能拆写，请连写（应写成 "K${peek.toUpperCase()}"，不要写成 "K ${peek.toUpperCase()}"）`);
        }
      }
      throw new Error(`表达式中含有无法识别的标识符 '${word}'（位置 ${i - word.length}）`);
    }
```
同时更新 `RawToken` 的 `func` 注释为 `SUM/AVG/MAX/MIN/COUNT/KSUM/KAVG/KMAX/KMIN/KCOUNT`。若 `__lexForTest` 不存在则 export 一个测试入口。

- [ ] **Step 4: 改多 source 收集 + 成链校验（替换单 source 抛错）**

定位 `formulaSerialize.ts` 约 `:307-316` 的单 source 抛错（"只允许引用同一个细页签"）。替换为有序多 source 收集 + 两两可比成链校验（复用已 export 的 `isSubset`/`comparable`）:
```typescript
// 收集 FN body 内所有 [别名.列] 的 source (componentId ≠ selfComponentId)
const srcRefs = collectSourceRefs(body, ctx);   // → [{ componentId, rowKeyFields }]
const distinct = dedupeById(srcRefs);
if (distinct.length >= 2) {
  // 成链校验: rowKeySets = [宿主, s1, s2, ...], 两两可比 (⊆/⊇), 不要求连续(跳层合法,§10-E)
  const sets = [ctx.selfRowKeyFields, ...distinct.map(s => s.rowKeyFields)];
  for (let a = 0; a < sets.length; a++)
    for (let b = a + 1; b < sets.length; b++)
      if (!comparable(sets[a], sets[b]))
        throw new Error(`页签「${distinct[a-1]?.name ?? '宿主'}」与「${distinct[b-1]?.name ?? ''}」行键不可比(互不包含),无法同进一个 SUM;请改用 KSUM 聚合其中更细/不可比的页签`);
  // 最细 = rowKeyFields 集合最大者 = sources[0] = 驱动
  const ordered = orderByFineness(distinct);   // 最细→更粗
  token.source = ordered[0].componentId;
  token.sources = ordered.map(s => ({ source: s.componentId, sourceLabel: s.name, match: buildMatch(s.rowKeyFields, ctx.selfRowKeyFields) }));
}
// N=1: 不写 sources (字节级兼容批4)
```
> `collectSourceRefs` / `dedupeById` / `orderByFineness` / `buildMatch` 为本 Task 新增私有 helper（实现见各自单测最小实现；`buildMatch` = 取被聚合页签 rowKeyFields ∩ 宿主 rowKeyFields，配对成 `{a:列, b:列}`）。

- [ ] **Step 5: 运行确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "T2 lexer"`
Expected: PASS（3 passed）。再 `npx tsc --noEmit -p tsconfig.json` → 0 错误。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(serialize): lexer 识别 K* + C3 误拆文案 + 多 source 成链校验

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: KSUM 折叠 + 约束校验（C2 / 白名单 / I1 / I2 / J / M）

> **Spec:** §3.3（折叠算法 + C2 不复用单列 shortcut + I2 同页签冲突 + M 顶层裸 KSUM）+ §2.4（inner 白名单）。现状 FN body 行级路径约 `:288` 对 `case 'func'` 直接抛"暂不支持嵌套聚合函数"。

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`（`expressionToTokens` FN body 折叠 + 顶层拒绝 + 冲突校验）
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 写失败测试（C2 单列折叠 + 白名单 + I2 + J + M）**

```typescript
describe('T3 KSUM 折叠 + 约束', () => {
  it('C2: 单列 KSUM([外购件.费用]) → projectToHostKey:true + targetExpr (非单列 shortcut)', () => {
    const toks = expressionToTokens('SUM([元素.单价] + KSUM([外购件.费用]))', KSUM_CTX);
    const outer = toks.find(t => t.type === 'cross_tab_ref' && !t.projectToHostKey)!;
    const ksum = outer.targetExpr!.find(t => t.type === 'cross_tab_ref' && t.projectToHostKey)!;
    expect(ksum.projectToHostKey).toBe(true);
    expect(ksum.agg).toBe('SUM');
    expect(ksum.target ?? '').toBe('');                 // 不是单列 shortcut
    expect(ksum.targetExpr).toEqual([{ type: 'field', value: '费用', source: WGJ_ID }]);
  });
  it('K-agg 映射: KAVG→AVG+proj, KCOUNT→COUNT+proj', () => {
    const t1 = pickKsum(expressionToTokens('SUM([元素.x] + KAVG([外购件.费用]))', KSUM_CTX));
    expect(t1).toMatchObject({ agg: 'AVG', projectToHostKey: true });
    const t2 = pickKsum(expressionToTokens('SUM([元素.x] + KCOUNT([外购件.费用]))', KSUM_CTX));
    expect(t2).toMatchObject({ agg: 'COUNT', projectToHostKey: true });
  });
  it('白名单: KSUM 内含宿主列 / 跨页签 / 上一行小计 → 抛错', () => {
    expect(() => expressionToTokens('SUM([元素.x] + KSUM([来料.组成用量]))', KSUM_CTX)).toThrow(/宿主.*列/);
    expect(() => expressionToTokens('SUM([元素.x] + KSUM([外购件.费用] + [元素.单价]))', KSUM_CTX)).toThrow(/跨页签|同一个页签/);
  });
  it('J: K 套 K → 抛错', () => {
    expect(() => expressionToTokens('SUM([元素.x] + KSUM(KSUM([外购件.费用])))', KSUM_CTX)).toThrow(/K 套 K|再嵌套/);
  });
  it('I2: 同页签既 KSUM 又裸引 → 抛错', () => {
    expect(() => expressionToTokens('SUM(KSUM([外购件.费用]) + [外购件.费用])', KSUM_CTX)).toThrow(/已被 KSUM 聚合.*不能.*裸引用|二选一/);
  });
  it('M: 顶层裸 KSUM → 抛错', () => {
    expect(() => expressionToTokens('KSUM([外购件.费用]) * [组成用量]', KSUM_CTX)).toThrow(/只能写在外层 SUM/);
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "T3 KSUM"`
Expected: FAIL — 现状对 inner func 抛"暂不支持嵌套聚合函数"。

- [ ] **Step 3: 实现 KSUM 折叠（C2 强制 targetExpr+projectToHostKey）**

在 `expressionToTokens` FN body 解析里，**先于** `:244` 单列 shortcut 判定捕获 inner K\* func:
```typescript
const INNER_FNS = ['KSUM', 'KAVG', 'KMAX', 'KMIN', 'KCOUNT'];
const INNER_TO_AGG: Record<string,string> = { KSUM:'SUM', KAVG:'AVG', KMAX:'MAX', KMIN:'MIN', KCOUNT:'COUNT' };
// FN body 逐 raw token:
if (raw.kind === 'func' && INNER_FNS.includes(raw.name)) {
  const closeIdx = matchCloseParen(rawTokens, idx);          // 同外层 closeIdx 逻辑,支持嵌套括号
  const innerBody = rawTokens.slice(idx + 1, closeIdx);
  // a. 同一被聚合页签 kSrc
  const kRefs = dedupeById(collectSourceRefs(innerBody, ctx));
  if (kRefs.length !== 1) throw new Error('KSUM() 内只能引用同一个页签的列,跨页签请放到外层');
  const kSrc = kRefs[0];
  if (kSrc.componentId === ctx.selfComponentId) throw new Error('KSUM() 内不能引用宿主自身列,宿主列请放到外层 SUM');
  // c. J: 不得再含 K*
  if (innerBody.some(t => t.kind === 'func' && INNER_FNS.includes(t.name))) throw new Error('KSUM() 内暂不支持再嵌套 K 聚合(K 套 K)');
  // 递归解析 innerBody → innerTargetExpr (行级)
  const innerTargetExpr = parseTargetExpr(innerBody, ctx, kSrc.componentId);
  // d. 白名单逐 token 比对
  const WHITELIST = new Set(['field','operator','number','bracket_open','bracket_close','global_variable']);
  for (const t of innerTargetExpr) {
    if (!WHITELIST.has(t.type))
      throw new Error(`KSUM() 内不支持 ${t.type}(如上一行小计/组件小计/报价字段/宿主列),请放到外层 SUM`);
    if (t.type === 'field' && t.source !== kSrc.componentId)
      throw new Error('KSUM() 内只能引用同一个页签的列,跨页签请放到外层');
  }
  const m = buildMatch(kSrc.rowKeyFields, ctx.selfRowKeyFields);
  if (m.length === 0) throw new Error('KSUM() 引用的页签与宿主无公共行键,无法按宿主键分组');
  outerTargetExpr.push({
    type: 'cross_tab_ref', projectToHostKey: true,
    source: kSrc.componentId, sourceLabel: kSrc.name, target: '',
    agg: INNER_TO_AGG[raw.name], match: m, targetExpr: innerTargetExpr,
  });
  ksumWrappedSources.add(kSrc.componentId);   // 供 I2 冲突校验
  idx = closeIdx + 1;
  continue;
}
```

- [ ] **Step 4: 实现 I2 冲突校验 + M 顶层拒绝**

FN body 解析完后追加 I2 校验:
```typescript
const nakedSources = new Set(outerTargetExpr.filter(t => t.type==='field').map(t => t.source));
for (const k of ksumWrappedSources)
  if (nakedSources.has(k))
    throw new Error(`页签已被 KSUM 聚合,不能在同一 SUM 内再被裸引用(语义二义:塌缩标量 vs join 行集);请二选一`);
```
在 `expressionToTokens` **顶层** raw-token 扫描遇 INNER_FNS 一律拒（M）:
```typescript
if (raw.kind === 'func' && INNER_FNS.includes(raw.name) && !insideFnBody)
  throw new Error('KSUM/KAVG/KMAX/KMIN/KCOUNT 本期只能写在外层 SUM/AVG/MAX/MIN/COUNT 内,不支持顶层直接使用');
```

- [ ] **Step 5: 运行确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "T3 KSUM"`
Expected: PASS（6 passed）。`npx tsc --noEmit` → 0 错误。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(serialize): KSUM 折叠(C2 强制 projectToHostKey)+白名单/I2/J/M 校验

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 回显 `tokensToDrawerExpression`（KSUM 递归回显 + 幂等）

> **Spec:** §3.5。外层 cross_tab_ref → `SUM(<targetExpr 回显>)`；targetExpr 内 `projectToHostKey` 子 token → `K<AGG>(<inner 回显>)`（反查 SUM→KSUM 等）。

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`（`tokensToDrawerExpression` cross_tab_ref 分支，约 `:480`）
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 写失败测试（KSUM 回显 + round-trip 幂等）**

```typescript
describe('T4 回显', () => {
  it('KSUM token → 回显 KSUM(...) 并幂等 round-trip', () => {
    const src = 'SUM([元素.单价] + KSUM([外购件.费用]))';
    const toks1 = expressionToTokens(src, KSUM_CTX);
    const str1 = tokensToDrawerExpression(toks1, KSUM_CTX);
    expect(str1).toContain('KSUM([外购件.费用])');
    const toks2 = expressionToTokens(str1, KSUM_CTX);
    const str2 = tokensToDrawerExpression(toks2, KSUM_CTX);
    expect(str2).toBe(str1);                            // 两次稳定
  });
  it('KAVG 回显反查 AVG+proj → KAVG', () => {
    const toks = expressionToTokens('SUM([元素.x] + KAVG([外购件.费用]))', KSUM_CTX);
    expect(tokensToDrawerExpression(toks, KSUM_CTX)).toContain('KAVG([外购件.费用])');
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "T4 回显"`
Expected: FAIL — 嵌套 projectToHostKey 子 token 未回显成 `KSUM(...)`。

- [ ] **Step 3: 实现 KSUM 递归回显**

在 `tokensToDrawerExpression` 的 cross_tab_ref 分支，回显 targetExpr 时对子 token 判:
```typescript
const AGG_TO_INNER: Record<string,string> = { SUM:'KSUM', AVG:'KAVG', MAX:'KMAX', MIN:'KMIN', COUNT:'KCOUNT' };
function renderTargetExpr(te: FormulaToken[], ctx): string {
  return te.map(t => {
    if (t.type === 'cross_tab_ref' && t.projectToHostKey) {
      const kw = AGG_TO_INNER[(t.agg ?? 'SUM').toUpperCase()] ?? 'KSUM';
      return `${kw}(${renderTargetExpr(t.targetExpr ?? [], ctx)})`;
    }
    if (t.type === 'field') {
      const srcName = lookupSourceName(t.source ?? '', ctx);    // 反查页签名
      return `[${srcName}.${t.value}]`;
    }
    // operator/number/bracket/global_variable 既有回显
    return renderPlainToken(t);
  }).join(' ');
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "T4 回显"`
Expected: PASS（2 passed）。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(serialize): KSUM 递归回显 + round-trip 幂等

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 前端求值引擎（aggregateRows 抽取 + N 路 join + KSUM 塌缩 + 决策 K）🔒基线 强制E2E

> **Spec:** §4.1（统一原语 + Minor ① NONE 多命中 ERR 旁路保留）/ §4.2（KSUM 塌缩时机 + 决策 K + **I-1 空集落点** + **I-2 污染范围**）/ §4.3。现状 `formulaEngine.ts:257-308` cross_tab_ref case，空集吞 0 在 `:287` `else if (hits.length === 0) out = 0;`（**不是** `:294` `/ arr.length`）。
>
> **🔒 基线纪律**：N=1 无嵌套退化路径逐字不变（T7 既有夹具回归证明）。

**Files:**
- Modify: `cpq-frontend/src/utils/formulaEngine.ts`（cross_tab_ref case `:257-308` + evalRow `:272-279`）
- Modify: `cpq-frontend/src/utils/formulaEngine.test.ts`

- [ ] **Step 1: 写失败测试（KSUM 塌缩 + 决策 K 空集分流 + I-2 污染范围）**

```typescript
describe('T5 前端引擎 KSUM', () => {
  // host 来料 1 行(料8); 外购件 2 行(费用 1.0/0.5); 元素 2 行(Ag/Ni)
  it('KSUM 按宿主键塌缩 = Σ费用 = 1.5, 广播进每元素驱动行', () => {
    const out = evaluateExpression(KSUM_TOKENS, /* ...ctx with crossTabRows */ );
    // SUM([元素.单价] + KSUM([外购件.费用])) ; 元素单价 Ag=2,Ni=3 → (2+1.5)+(3+1.5)=8
    expect(out).toBe(8);
  });
  it('决策 K: KSUM 空集 → 0 (静默, 无 error)', () => {
    const diag: any = {};
    const out = evaluateExpression(KSUM_TOKENS_EMPTY_WGJ, /* ... */, diag);
    expect(out).toBe(5);                  // (2+0)+(3+0)=5
    expect(diag.crossTabError).toBeUndefined();
  });
  it('决策 K + I-2: KAVG 空集 → 整外层表达式塌 0 + crossTabError 非空', () => {
    const diag: any = {};
    const out = evaluateExpression(KAVG_TOKENS_EMPTY_WGJ, /* ... */, diag);
    expect(out).toBe(0);                  // 不是 [元素.x] 的和; 整表达式塌 0
    expect(diag.crossTabError).toBeTruthy();
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/utils/formulaEngine.test.ts -t "T5 前端引擎"`
Expected: FAIL — 嵌套 KSUM 未塌缩 / 空集未按 agg 分流。

- [ ] **Step 3: 抽 aggregateRows（纯重构, 保留 NONE 多命中旁路）**

把现 `:259-298` 的 hits 过滤 + evalRow + agg switch 收敛成可复用函数（决策 K 空集分流落在此原语内, **在 `hits.length===0 → 0` 提前返回之前**）:
```typescript
function aggregateRows(
  rows: Record<string, any>[], match: Array<{a:string;b:string}>, hostRow: Record<string, any> | undefined,
  targetExpr: FormulaToken[] | undefined, agg: string, target: string | undefined,
  evalCtx: EvalCtx, projectToHostKey: boolean,
): { value: number | null; multiMatchErr: boolean } {
  const hits = rows.filter(ar => match.every(p => keyEq(ar[p.a], hostRow?.[p.b])));
  const A = agg.toUpperCase();
  const num = (v: any) => { const n = Number(v); return isNaN(n) ? null : n; };
  const hasTE = !!(targetExpr && targetExpr.length > 0);
  const evalOne = (ar: Record<string, any>) => evalRowExpr(ar, targetExpr!, evalCtx);
  if (A === 'COUNT') return { value: hits.length, multiMatchErr: false };
  if (A === 'NONE') {                                  // Minor ① 旁路保留
    if (hits.length === 0) return { value: 0, multiMatchErr: false };
    if (hits.length > 1) return { value: 0, multiMatchErr: true };   // ERR 旁路
    return { value: hasTE ? evalOne(hits[0]) : (num(hits[0][target ?? '']) ?? 0), multiMatchErr: false };
  }
  // 【I-1 决策 K 空集分流】—— 在统一"空集→0"提前返回之前判 KSUM 分流
  if (hits.length === 0) {
    if (projectToHostKey && (A === 'AVG' || A === 'MAX' || A === 'MIN'))
      return { value: null, multiMatchErr: false };    // KAVG/KMAX/KMIN 空集 → null
    return { value: 0, multiMatchErr: false };          // KSUM/外层 SUM/AVG/.. 空集 → 0
  }
  const nums = hasTE ? hits.map(evalOne) : hits.map(h => num(h[target ?? '']));
  if (nums.some(n => n === null)) return { value: null, multiMatchErr: true };
  const arr = nums as number[];
  const v = A === 'SUM' ? arr.reduce((s,x)=>s+x,0)
          : A === 'AVG' ? arr.reduce((s,x)=>s+x,0)/arr.length
          : A === 'MAX' ? Math.max(...arr) : A === 'MIN' ? Math.min(...arr) : 0;
  return { value: v, multiMatchErr: false };
}
```

- [ ] **Step 4: cross_tab_ref case 调 aggregateRows + KSUM 递归 + N 路 join**

cross_tab_ref case 改为:
```typescript
case 'cross_tab_ref': {
  // 【KSUM】projectToHostKey: hostRow = 宿主当前行 currentRow (非外层驱动行)
  if (token.projectToHostKey) {
    const rows = crossTabRows?.[token.source ?? ''] ?? [];
    const r = aggregateRows(rows, token.match ?? [], currentRow, token.targetExpr,
                            token.agg ?? 'SUM', token.target, evalCtx, true);
    if (r.value === null) {                            // I-1/I-2: 空集 null → 整外层塌 0 + ⚠
      if (outDiag) outDiag.crossTabError = `[${token.sourceLabel ?? token.source}] ${token.agg} 命中 0 行,无定义`;
      expr += '(null.x)';                              // 注入非法 → 外层 try/catch → 0
    } else expr += r.value.toString();
    break;
  }
  // 【外层 v1: 单 / 多 source】驱动 = sources[0] ?? source
  const driver = (token.sources && token.sources.length >= 2) ? token.sources[0].source : token.source;
  const rows = crossTabRows?.[driver ?? ''] ?? [];
  const r = aggregateRows(rows, token.match ?? [], currentRow, token.targetExpr,
                          token.agg ?? 'NONE', token.target, evalCtx, false);
  // 多 source 更粗页签按 match 广播 (>1 命中 → ERR), 在 evalRowExpr 内对各 field.source 取对应行
  if (r.multiMatchErr && outDiag) outDiag.crossTabError = `细项引用命中多行,请改用 SUM 等聚合(或引用「(总计)」)`;
  expr += r.multiMatchErr ? '(null.x)' : (r.value ?? 0).toString();
  break;
}
```
> `evalRowExpr` = 把原 `evalRow` `:272-279` 的 aFieldValues 构造 + `evaluateExpression` 递归调用提取的 helper（透传 componentSubtotals/productAttributes/quotationFields/pathCache/basicDataValues/globalVariableDefs/**currentRow**/**crossTabRows** —— 后两者使嵌套 KSUM 递归进同一 case 时上下文齐备，§4.3 关键实现点）。多 source 的"更粗 source 按 field.source 广播取行"在 evalRowExpr 内按 `field.source` 选 `crossTabRows[source]` 命中宿主键的行（>1 命中 → 该 field 取 null → multiMatchErr）。

- [ ] **Step 5: 运行确认通过 + 回归既有 cross_tab 测试**

Run:
```bash
cd cpq-frontend
npx vitest run src/utils/formulaEngine.test.ts -t "T5 前端引擎"
npx vitest run src/utils/formulaEngine.test.ts          # 全量回归: 既有 cross_tab/单 source 用例零变化
npx tsc --noEmit -p tsconfig.json
```
Expected: T5 PASS；全量 PASS（既有用例不回归）；tsc 0 错误。

- [ ] **Step 6: 自检 Vite transform 200**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/utils/formulaEngine.ts`
Expected: `200`

- [ ] **Step 7: Commit**

```bash
git add cpq-frontend/src/utils/formulaEngine.ts cpq-frontend/src/utils/formulaEngine.test.ts
git commit -m "feat(engine): aggregateRows 抽取 + N路join + KSUM塌缩 + 决策K空集分流(I-1落点)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 后端求值引擎（① sub 透传 ② KSUM 白名单 ③ 空集 null）+ validator 🔒基线 强制E2E

> **Spec:** §4.4（C1 sub 透传 + KSUM 求值分支 + **I-1 空集落点** = `evalCrossTab` `:233` `hits.isEmpty()→ZERO` 之前判分流，**不动** `:243` `.average().orElse(0)`）+ §6.2（validator 白名单/J/I2/M）。

**Files:**
- Modify: `cpq-backend/.../quotation/service/FormulaCalculator.java`（`targetRowValue` `:252-266` / `evalCrossTab` `:206-249` / `appendToken` `:159`）
- Modify: `cpq-backend/.../component/formula/TokenMappabilityValidator.java`
- Modify/Create: `cpq-backend/.../template/service/TemplateCrossTabValidateTest.java`（后端绕过拒绝单测）

- [ ] **Step 1: 写失败测试（C1 sub 对称 + 决策 K 空集 null + validator 绕过拒绝）**

在 `FormulaCalculatorCrossTabFixtureTest` 同包补单测（或新建 `FormulaCalculatorKsumTest.java`）:
```java
@Test void ksum_collapses_by_host_key() {
    // host 料8, 外购件 2 行 费用 1.0/0.5, 元素 Ag=2/Ni=3
    // SUM([元素.单价] + KSUM([外购件.费用])) = (2+1.5)+(3+1.5) = 8
    Object r = calc.evaluateExpression(KSUM_TE, ctxWithRows());
    assertEquals(8.0, toD(r), 1e-9);
}
@Test void ksum_empty_returns_zero_silently() {
    Object r = calc.evaluateExpression(KSUM_TE, ctxEmptyWgj());   // 外购件 0 行
    assertEquals(5.0, toD(r), 1e-9);                              // (2+0)+(3+0)
}
@Test void kavg_empty_returns_null_then_collapses_whole_expr_to_zero() {
    RowContext ctx = ctxEmptyWgj();
    Object r = calc.evaluateExpression(KAVG_TE, ctx);
    assertEquals(0.0, toD(r), 1e-9);                              // I-2: 整外层塌 0
    // crossTabError 标记非空 (按 calc 暴露的 diag 通道断言)
}
@Test void c1_sub_passes_quotationFields_to_outer_targetExpr() {
    // SUM([元素.x] + [报价字段Y]); 后端 sub 补齐后能取到 quotationFields[Y]
    Object r = calc.evaluateExpression(TE_WITH_QF, ctxWithQuotationFields());
    assertEquals(EXPECTED_WITH_QF, toD(r), 1e-9);                 // 修复前后端会分叉(取 0)
}
```
validator 绕过测试（`TemplateCrossTabValidateTest`）:
```java
@Test void validator_rejects_ksum_inner_host_field() {
    String tok = "...projectToHostKey:true, targetExpr 含 b_field(宿主列)...";
    assertThrows(ValidationException.class, () -> validator.validate(parse(tok)));
}
@Test void validator_rejects_nested_ksum_J() { /* K 套 K → 拒 */ }
@Test void validator_rejects_top_level_ksum_M() { /* 顶层 projectToHostKey → 拒 */ }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest='FormulaCalculatorKsumTest,TemplateCrossTabValidateTest' -q`
Expected: FAIL — KSUM 未塌缩 / sub 漏透传 quotationFields / validator 未拒非法嵌套。

- [ ] **Step 3: ① targetRowValue sub 全字段透传（C1）**

`FormulaCalculator.targetRowValue` `:255-262` 的 `sub` 补齐（与前端 evalRow 对齐）:
```java
sub.currentRowRaw      = ctx.currentRowRaw;
sub.basicDataValues    = ctx.basicDataValues;
sub.crossTabRows       = ctx.crossTabRows;
sub.componentSubtotals = ctx.componentSubtotals;   // ← 新增 (C1)
sub.quotationFields    = ctx.quotationFields;      // ← 新增 (C1)
sub.productAttributes  = ctx.productAttributes;    // ← 新增 (C1)
sub.previousRowSubtotal= ctx.previousRowSubtotal;  // ← 新增 (契约对齐; inner 白名单已禁该 token)
```

- [ ] **Step 4: ② evalCrossTab KSUM 分支 + ③ 决策 K 空集 null（I-1 落点）**

`evalCrossTab` 开头判 projectToHostKey；**空集分流落在 `:233` `if (hits.isEmpty()) return ZERO;` 之前**:
```java
boolean proj = token.path("projectToHostKey").asBoolean(false);
// ... hits 过滤 (proj 时按 match ⋈ ctx.currentRowRaw 分组, rows = ctx.crossTabRows[source]) ...
if ("COUNT".equals(agg)) return BigDecimal.valueOf(hits.size());   // :227 不动 (KCOUNT 空集=0)
if ("NONE".equals(agg)) {                                          // :228-232 不动 (Minor ① 旁路)
    if (hits.isEmpty()) return BigDecimal.ZERO;
    if (hits.size() > 1) return ERR;
    return targetRowValue(hits.get(0), token, ctx);
}
// 【I-1 决策 K】在统一"空集→ZERO"之前判 KSUM 分流:
if (hits.isEmpty()) {
    if (proj && ("AVG".equals(agg) || "MAX".equals(agg) || "MIN".equals(agg)))
        return null;                                              // KAVG/KMAX/KMIN 空集 → null
    return BigDecimal.ZERO;                                       // (原 :233; KSUM=SUM / 外层 → 0)
}
// ... :234-248 nums 构造 + agg switch 不动 (含 :243 .average().orElse(0) 死分支, 保留) ...
```
`appendToken` 的 cross_tab_ref case（`:159-167`）见 `evalCrossTab` 返 null → 写非法表达式 / 返 ERR（上层 try/catch → 0，对齐前端 `(null.x)`），并标 crossTabError。

- [ ] **Step 5: validator 镜像（白名单 / J / I2 / M）**

`TokenMappabilityValidator` 对每个 `projectToHostKey===true` 子 token: match 非空 + targetExpr 内 field.source 一律 === KSUM.source（跨页签拒）+ 白名单（只 field/operator/number/bracket/global_variable，拒 b_field/component_subtotal/quotation_field/product_attribute/previous_row_subtotal）+ J（targetExpr 内出现 cross_tab_ref → 拒）+ I2（KSUM 包裹页签集 ∩ 外层裸引用页签集 ≠ ∅ → 拒）；顶层 projectToHostKey 裸 token → 拒（M）。

- [ ] **Step 6: 运行确认通过 + 后端自检**

Run:
```bash
cd cpq-backend && ./mvnw test -Dtest='FormulaCalculatorKsumTest,TemplateCrossTabValidateTest,FormulaCalculatorCrossTabTest,FormulaCalculatorCrossTabFixtureTest' -q
```
Expected: 全 PASS（含既有 CrossTab 回归）。
触发 Quarkus 重启自检: `touch cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java` → 等 5-7 秒 → `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health` → 期望 200。

- [ ] **Step 7: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java \
        cpq-backend/src/main/java/com/cpq/component/formula/TokenMappabilityValidator.java \
        cpq-backend/src/test/java/com/cpq/template/service/TemplateCrossTabValidateTest.java
git commit -m "feat(engine): 后端 KSUM 镜像 - C1 sub透传 + 决策K空集null(I-1 :233落点) + validator白名单/J/I2/M

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: 对拍夹具 cross-tab-cases.json（15 类，含盲区 A / I-2 / 双独立 KSUM / NONE 旁路）

> **Spec:** §7.3（15 类用例）。前后端两份副本逐字同步：`cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json` ⟷ `cpq-backend/src/test/resources/cross-tab-cases.json`。

**Files:**
- Modify: `cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json`
- Modify: `cpq-backend/src/test/resources/cross-tab-cases.json`
- Modify: `cpq-frontend/src/utils/formulaEngine.test.ts`（夹具 runner）
- Modify: `cpq-backend/.../quotation/service/FormulaCalculatorCrossTabFixtureTest.java`

- [ ] **Step 1: 写失败测试（夹具新增 15 类用例）**

向两份 json 追加用例（每条含 `name` / `tokens` / `crossTabRows` / `context` / `expected` / 可选 `expectError`）。必含:
1. 2 source 链 SUM（驱动细 + 1 粗广播）
2. 粗 source 0 命中 → 项=0
3. **盲区 A — 外层链式 join × KSUM 同公式组合**：`SUM([元素.x]/.. + [来料.组成用量] + KSUM([外购件.费用]))`（元素细驱动 + 来料粗广播 + 外购件 KSUM 塌缩三者同公式）
4. 3 source 链跳层
5. 料8 终态（KSUM 多行塌缩 Kp + 外层 Σ_元素）
6. KSUM 空集 → 0（25.41，无 error）
7. KSUM/KAVG/KMAX/KMIN/KCOUNT 各 agg 有命中塌缩值
8. **I-2 — KAVG 空集 → 整表达式 0 + error**：断言 `expected.value === 0 && expected.crossTabError != null`（**不是**元素项和）
9. 外层 AVG/MAX 组合 KSUM（先内塌缩后外聚合优先级）
10. **双独立 KSUM 引不同页签**：`SUM([元素.x] + KSUM([外购件.费用]) + KSUM([其他源.费用]))` 各按各 source 塌缩互不串号
11. **C1 对称**：`SUM([元素.x] + [报价字段Y])` 前后端均取 quotationFields[Y]（修复前分叉）
14. 单 source 退化（批4 4 用例 token 无 sources/projectToHostKey → 逐字不变）
15. **Minor ① NONE 多命中 ERR 旁路**：agg=NONE + 命中 >1 → ERR/⚠（重构零变化）

- [ ] **Step 2: 运行确认失败**

Run:
```bash
cd cpq-frontend && npx vitest run src/utils/formulaEngine.test.ts -t "cross-tab-cases"
cd ../cpq-backend && ./mvnw test -Dtest='FormulaCalculatorCrossTabFixtureTest' -q
```
Expected: FAIL（新用例 expected 未满足；若引擎已在 T5/T6 实现则部分 PASS，但新增 KSUM 用例此前夹具无数据 → runner 报缺失/不匹配）。

- [ ] **Step 3: 落夹具数据 + runner 对齐**

写实 15 类用例的 tokens/crossTabRows/expected（两份 json 逐字相同）。runner 若需支持 `expectError` / `crossTabError` 断言字段则补 runner 读取逻辑（前端 vitest + 后端 fixture test 各一处）。

- [ ] **Step 4: 运行确认前后端一致通过**

Run:
```bash
cd cpq-frontend && npx vitest run src/utils/formulaEngine.test.ts -t "cross-tab-cases"
cd ../cpq-backend && ./mvnw test -Dtest='FormulaCalculatorCrossTabFixtureTest' -q
diff <(jq -S . cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json) <(jq -S . cpq-backend/src/test/resources/cross-tab-cases.json)
```
Expected: 两端全 PASS；`diff` 无输出（两份 json 逐字一致）。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json \
        cpq-backend/src/test/resources/cross-tab-cases.json \
        cpq-frontend/src/utils/formulaEngine.test.ts \
        cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculatorCrossTabFixtureTest.java
git commit -m "test(crosstab): 对拍夹具 15 类(料8终态/KSUM各agg/I-2空集/双独立KSUM/C1对称/NONE旁路)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: 配色 insideKsum（P2 必做）+ FN 块级错误透出

> **Spec:** §5.1（insideKsum P2 必做）/ §5.2（FN 块级错误）。`parseFormulaSegments` 增 `insideKsum` 上下文，对 KSUM 区间内宿主紫块 / 第二被聚合页签块标红 `insideKsum-illegal`。

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`（`parseFormulaSegments` / `classifyRefSegment` 约 `:716-777`）
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 写失败测试（KSUM 内宿主紫块标红 + FN 块级 C3 文案透出）**

```typescript
describe('T8 配色 insideKsum', () => {
  it('合法 KSUM 内被聚合页签列 → 蓝', () => {
    const segs = parseFormulaSegments('SUM([元素.单价] + KSUM([外购件.费用]))', KSUM_CTX);
    const wgj = segs.find(s => s.text === '[外购件.费用]')!;
    expect(wgj.className).toMatch(/blue|detail/);
  });
  it('KSUM 内宿主紫块 → insideKsum-illegal 红', () => {
    const segs = parseFormulaSegments('SUM([元素.x] + KSUM([来料.组成用量]))', KSUM_CTX);
    const host = segs.find(s => s.text === '[来料.组成用量]')!;
    expect(host.className).toMatch(/insideKsum-illegal|red/);
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "T8 配色"`
Expected: FAIL — KSUM 内宿主块仍判紫（未 insideKsum 标红）。

- [ ] **Step 3: 实现 insideKsum 上下文标记**

`parseFormulaSegments` 扫到 `K*(` 起、匹配 `)` 止区间内 segment 打 `insideKsum=true`；`classifyRefSegment` 据 `insideKsum` 对宿主紫块（`:748-751` 判紫）/ 第二被聚合页签块返 `insideKsum-illegal` 红，附 hover 文案。

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "T8 配色"`
Expected: PASS（2 passed）。`npx tsc --noEmit` → 0 错误。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(color): insideKsum 上下文 - KSUM 内违规块即时标红(P2)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Excel 模型 B 降级（TabJoinPlanEvaluator 显式拦截，非静默）

> **Spec:** §1 Minor ③。据 T0a Step 4 探查结论决定改动量：遇 `projectToHostKey===true` 或 `sources.size>=2` token → **报错（不静默吞 token 产错值）**。

**Files:**
- Modify: `cpq-backend/.../TabJoinPlanEvaluator.java`（路径由 T0a Step 4 给出）
- Modify/Create: 对应单测

- [ ] **Step 1: 写失败测试（KSUM token 进 Excel 模型 B → 抛结构化错误）**

```java
@Test void tabjoin_rejects_ksum_token_not_silent() {
    JsonNode ksumTok = parse("{type:'cross_tab_ref', projectToHostKey:true, ...}");
    Exception e = assertThrows(RuntimeException.class, () -> evaluator.evaluate(ksumTok, ctx));
    assertTrue(e.getMessage().contains("Excel 列模型暂不支持 KSUM"));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest='*TabJoinPlanEvaluator*' -q`
Expected: FAIL — 当前对 KSUM token 静默处理 / 产错值，未抛错。

- [ ] **Step 3: 加显式拦截分支**

`TabJoinPlanEvaluator` 求值入口判 token 形态:
```java
if (token.path("projectToHostKey").asBoolean(false) || token.path("sources").size() >= 2) {
    throw new IllegalStateException(
        "Excel 列模型暂不支持 KSUM 降维聚合 / 多 source 链式 SUM，请改用页签连表渲染(模型 A)");
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest='*TabJoinPlanEvaluator*' -q`
Expected: PASS。重启自检: `touch` java → `curl :8081/q/health` → 200。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/.../TabJoinPlanEvaluator.java cpq-backend/src/test/java/.../*TabJoinPlanEvaluator*Test.java
git commit -m "feat(excel): TabJoinPlanEvaluator 遇 KSUM/多source 显式报错(非静默少算)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: E2E 双 spec + 三视图验收 + 文档同步 强制E2E（依赖 T0b）

> **Spec:** §7.4 / §8.3 / §8.4 / §9.2。真机验收 = 料8 终态公式能存 + 能算。**核价单验收据 T0a 降级**：确认核价单视图不渲染该公式、不报错（核价单不走 cross_tab_ref，§7.2 Minor ⑤ 伪命题）。

**Files:**
- Modify: `cpq-frontend/e2e/quotation-flow.spec.ts`（加 KSUM 断言）
- Modify: `docs/PRD-v3.md` / `docs/配置方法论-合并版.md` / `docs/RECORD.md` / `docs/反模式.md`

- [ ] **Step 1: 执行 T0b seed（E2E 前置）**

Run:
```bash
export PGPASSWORD=joii5231
psql -h 10.177.152.12 -U postgres -d cpq_db -v ON_ERROR_STOP=1 -f cpq-frontend/e2e/seed/ksum-seed.sql
```
Expected: 成功，料8 外购件 2 行（KSUM测试件A/B）就绪。

- [ ] **Step 2: 真机验收 — 料8 终态公式能存 + 能算**

在报价模板0608 给料8 配（用现役可落地的单列 KSUM 公式，外购件无单价/数量列 → 用费用）:
```
SUM([元素.重量(g)]/1000*[元素.单价]*(1+[元素.损耗率]/100) + KSUM([外购件.费用])) * [组成用量]
```
保存（lexer 识别 KSUM、序列化折叠成 projectToHostKey 嵌套 token、不报错）→ 进报价单 → 料8 列算出值（KSUM=Σ费用=1.5 广播进 Ag/Ni 两驱动行）。

- [ ] **Step 3: 跑 quotation-flow E2E（加载中=0 + KSUM 断言）**

Run:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`；`'加载中' final count = 0`；全 8 Tab `'加载中'=0`；料8 行 KSUM 列渲染 = Σ费用（非 ⚠、非"加载中"）。
> composite-product-flow：据 MEMORY `cpq-e2e-quotation-flow-test-data` 组合/选配数据 2026-06-11 已清，选模板步可能卡（非本改动回归）；如卡则记录"环境数据缺失,非回归"，单 spec 通过即可。

- [ ] **Step 4: 三视图验收（AP-50）**

报价单编辑（QuotationStep2）+ 报价详情（ReadonlyProductCard，同 `buildCrossTabRows`）料8 KSUM 列一致渲染。**核价单（CostingSummaryDetailPage）**：确认其指标列表正常加载、不渲染该 cross_tab 公式、不报错（核价单走独立 8 指标引擎，不评估 KSUM）。

- [ ] **Step 5: 文档同步**

- `docs/PRD-v3.md`：连表公式章节补"多 source 链式 SUM + KSUM 嵌套预聚合" + 第 9 章演进史。
- `docs/配置方法论-合并版.md §2.6 + §11`：KSUM 用法 + 成链约束 + 降维投影 + inner 白名单 + 决策 K 空集分流 + C3 不可拆写 + Excel 模型 B 降级。
- `docs/RECORD.md`：`[2026-06-12] 多 source 链式 SUM + KSUM 嵌套预聚合 | formulaEngine.ts/FormulaCalculator.java/formulaSerialize.ts/types.ts/TokenMappabilityValidator.java/cross-tab-cases.json | C1前后端sub对称 / KSUM inner白名单 / 决策K空集分流(I-1: :233/:287落点,非.average().orElse(0)) / I-2 KAVG空集整表达式塌0+⚠ / J双端拒 / M顶层裸KSUM不支持 / 核价单不走cross_tab(Minor⑤伪命题)`。
- `docs/反模式.md`：建 KSUM 专项 AP（inner 白名单 + 空集分流真实落点 + I-2 污染范围 + 核价单 crossTabRows 不同源 + 单列 KSUM 不复用 shortcut）。

- [ ] **Step 6: Commit + 自检声明**

```bash
git add cpq-frontend/e2e/quotation-flow.spec.ts docs/PRD-v3.md docs/配置方法论-合并版.md docs/RECORD.md docs/反模式.md
git commit -m "docs+e2e(ksum): E2E KSUM 断言 + 三视图验收 + 文档同步(PRD/方法论/RECORD/反模式)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
自检声明（合并前必附）:
> "TS 0 错误 ✅；formulaEngine.ts/formulaSerialize.ts → Vite 200 ✅；后端 FormulaCalculator/validator → /q/health 200 ✅；前后端 cross-tab-cases.json diff 无输出 ✅；quotation-flow E2E 1 passed + '加载中'=0 ✅；料8 KSUM 终态能存能算 ✅"

---

## Self-Review（plan 作者已执行）

1. **Spec 覆盖**：§2(T1) / §3.1(T2) / §3.3+§2.4(T3) / §3.5(T4) / §4(T5/T6, 含 I-1 落点 + I-2 污染) / §5(T8) / §6(T6) / §7.3(T7) / §7.4+§8(T10) / §8.5(T0b) / §1 Minor③(T9) / §7.2(T0a 探查降级核价单验收) — 全覆盖。
2. **占位符扫描**：无 TBD/TODO；每代码 Step 含真实骨架 + 真实命令 + 期望输出。T0a 探查命令/期望均为已实跑结果。
3. **类型一致**：`aggregateRows`(T5)/`projectToHostKey`/`sources`(T1)/`INNER_TO_AGG`/`AGG_TO_INNER`/`evalRowExpr` 在引用前均已定义；前后端 json 路径 diff 校验。
4. **I-1 一致性**：T5/T6/T7/T10 + §4.2/§4.4/§10.1-K 一律指向"空集提前返回点(前端 `:287` / 后端 `:233`)之前分流，不动 `:243` `.average().orElse(0)`"。
5. **T0a 应急结论已显式标注**：核价单不走 cross_tab(Minor⑤伪命题→验收降级)；外购件现役无单价/数量列(改用 `KSUM([外购件.费用])` 单列→覆盖 C2)；料8 外购件 0 行(T0b seed 补)。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-12-multi-source-chain-sum.md`. Two execution options:

1. **Subagent-Driven (recommended)** — 每 Task 派 fresh subagent，Task 间评审，快迭代。**REQUIRED SUB-SKILL:** superpowers:subagent-driven-development。
2. **Inline Execution** — 本会话内批执行 + checkpoint 评审。**REQUIRED SUB-SKILL:** superpowers:executing-plans。

Which approach?
