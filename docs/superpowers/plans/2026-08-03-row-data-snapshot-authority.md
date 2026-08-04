# 行数据即快照（键存在即权威）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「用户清空一个输入格」成为可持久化的状态——默认值只在该格从未定值时烘一次，之后任何路径都不得再自动填充。

**Architecture:** 全链路统一到一条不变式——**`row_data[row][fieldName]` 键存在 = 已定值，禁止写入；键不存在 = 从未定值，仅此时允许烘一次默认值**。清空写空串 `""`，不是删键。该口径在本仓已有两处正确实现（后端 `FormulaCalculator.fillInputDefaultSourceByFieldName:1866`、前端 `currentRowForEval` 增量补值），本计划把其余 6 处对齐到它，**不发明新规则**。

**Tech Stack:** Java 17 / Quarkus 3.34 / JUnit 5；React 18 / TypeScript / Vitest。

**Spec:** `docs/superpowers/specs/2026-08-03-row-data-snapshot-authority-design.md`

---

## 执行前须知（每个 Task 都适用）

**工作目录**：`/home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot`（隔离 worktree，分支 `fix/repair-0803-snapshot-authority`）

**环境约束（踩过的坑，务必遵守）**：
- 前端 `node_modules` 是**软链**到主仓的，**不要** `npm install`。
- **不要**在本 worktree 里另起 dev server（8081 / 5174 是全会话共享的，跑在主仓）。
- `cpq-backend/src/main/resources/db/migration/V368~V371` 是**从主仓借来的未提交文件**（并发会话 task-0729 的），仅为让 Flyway validate 通过。**绝不允许 `git add` 它们**，收尾前删除。
- 提交一律 **先 `git add <显式路径>` 再 `git commit -q -m "..." -- <同样的显式路径>`**，**严禁 `git add -A`**（同分支并发会交错，历史上夹带过他人改动）。
  > ⚠️ 新文件**必须先 `git add`**：只写 `git commit -- <新文件路径>` 会报 `pathspec did not match any file(s) known to git`。两条都写就都能覆盖。
- 每次提交后跑一次 `git show --stat --oneline HEAD`，**亲眼确认只列出本次该改的文件**（历史上夹带过并发会话的改动）。
- 提交信息末尾加：
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01QVCtXU6tJEg7ThuzZHJPvX
  ```

**已知的基线状态（不是你引入的，别去修）**：
- `GoldenCardValuesEquivTest` 2 个用例恒 SKIPPED（夹具单不在测试库，BL-0021/BL-0078 同族）。
- `npx vitest run` 有 63 个 `e2e/*.spec.ts` 文件加载失败（Playwright spec 被 vitest 误收）。**基线 886 tests passed**，只看这个数。

**常用命令**：
```bash
# 后端单测（必须在 worktree 的 cpq-backend 里跑，不是主仓）
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot/cpq-backend && ./mvnw -q test -Dtest='XxxTest'

# 前端
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot/cpq-frontend
npx vitest run src/pages/quotation/xxx.test.ts
npx tsc --noEmit -p tsconfig.json
```

---

## 文件结构

| 文件 | 职责 | 本次动作 |
|---|---|---|
| `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java` | 逐行解析 + 公式求值。`resolveRowByFieldName` 是 row_data 落库形态的唯一产出点 | 修改 INPUT 分支（Task 1） |
| `cpq-backend/src/test/java/com/cpq/quotation/service/InputKeyPresenceAuthorityTest.java` | 「键存在即权威」的矩阵 golden，纯 JUnit 不起 Quarkus | 新建（Task 1） |
| `cpq-frontend/src/pages/quotation/keyPresenceAuthority.ts` | **不变式的唯一前端实现**：`isKeyUnset` / `rowsHaveUserData` | 新建（Task 2） |
| `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | 报价/核价卡片渲染 + 默认值烘焙 effect | 改 bake 判据为调用 `isKeyUnset`（Task 2） |
| `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` | 草稿 payload 组装 + enrich 合并 | §1.5/§1.6 调 `isKeyUnset`、`hasUserInput` 调 `rowsHaveUserData`（Task 3、4） |
| `cpq-frontend/src/pages/quotation/components/ComponentCell.tsx` | 单元格渲染（编辑态 + 只读态共用） | 修改只读分支（Task 5） |
| `cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts` | 前端判据的回归 | 新建（Task 2/3/4 共用） |

> **为什么单开一个模块**：本次改动的主旨就是「消灭多份判空口径」。若在 `QuotationStep2` 和 `QuotationWizard` 各写一份语义相同的判据，等于在修复过程中复制出新的漂移源。三处调用点共用同一个函数，才是这条不变式该有的形状。
| `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java` | 快照物化 + row_data 落库 | 新增 overlay + 接线（Task 6） |
| `cpq-backend/src/test/java/com/cpq/configure/service/OverlayExistingInputKeysTest.java` | overlay 纯函数单测 | 新建（Task 6） |

---

## Task 1：后端 `resolveRowByFieldName` —— 显式清空必须落键

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java:1251-1283`
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/InputKeyPresenceAuthorityTest.java`（新建）

- [ ] **Step 1: 写失败测试**

新建 `cpq-backend/src/test/java/com/cpq/quotation/service/InputKeyPresenceAuthorityTest.java`：

```java
package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * spec 2026-08-03「键存在即权威」矩阵 golden。
 *
 * <p>刻意<b>不</b>加 {@code @QuarkusTest}：FormulaCalculator 是无可变状态的纯计算 bean
 * （类注释明确「同时支持 new 直接单测」），不起 Quarkus 就不依赖数据库/Flyway，
 * 在共享测试库被并发会话改动时仍然可跑。
 */
class InputKeyPresenceAuthorityTest {

    private final FormulaCalculator calc = new FormulaCalculator();
    private static final ObjectMapper M = new ObjectMapper();

    /** 有源 INPUT / 无源但有 content 的 INPUT / 无源无 content 的 INPUT / BASIC_DATA / FORMULA */
    private static final String FIELDS = """
      [ {"name":"损耗率","field_type":"INPUT_NUMBER",
         "default_source":{"type":"BASIC_DATA","path":"$mc_view._损耗率"}},
        {"name":"税率","field_type":"INPUT_NUMBER","content":"13"},
        {"name":"备注","field_type":"INPUT_TEXT"},
        {"name":"元素","field_type":"BASIC_DATA","basic_data_path":"$mc_view._元素"},
        {"name":"材料成本","field_type":"FORMULA"} ]
      """;

    private static final String DRIVER_ROW = "{\\"元素\\":\\"Ag\\"}";
    private static final String BDV =
        "{\\"{$mc_view._损耗率}\\":1.05,\\"{$mc_view._元素}\\":\\"Ag\\"}";
    private static final String FORMULA_VALUES = "{\\"材料成本\\":641.925}";

    private Map<String, Object> resolve(String editValuesJson) throws Exception {
        JsonNode fields = M.readTree(FIELDS);
        JsonNode editValues = editValuesJson == null ? null : M.readTree(editValuesJson);
        return calc.resolveRowByFieldName(
            fields, M.readTree(DRIVER_ROW), M.readTree(BDV), editValues, M.readTree(FORMULA_VALUES));
    }

    // ── 本次唯一意图变更的一格 ────────────────────────────────────────────────
    @Test
    void 显式清空的INPUT必须落键且值为空串() throws Exception {
        Map<String, Object> row = resolve("{\\"损耗率\\":\\"\\"}");
        assertTrue(row.containsKey("损耗率"),
            "显式清空必须落键——否则库里「用户清空」与「从未填过」同形，重开会被默认值回填");
        assertEquals("", row.get("损耗率"), "空值的物理表示统一为空串，不能是 null");
    }

    @Test
    void 显式清空不得回落default_source() throws Exception {
        Map<String, Object> row = resolve("{\\"损耗率\\":\\"\\"}");
        assertNotEquals(1.05, row.get("损耗率"), "清空后不得再取 $mc_view._损耗率 的 1.05");
    }

    @Test
    void 显式清空不得回落静态content() throws Exception {
        Map<String, Object> row = resolve("{\\"税率\\":\\"\\"}");
        assertTrue(row.containsKey("税率"));
        assertEquals("", row.get("税率"), "清空后不得回落 content=13");
    }

    // ── 以下全部是「必须逐位不变」的既有行为（回归护栏）────────────────────────
    @Test
    void 键缺失时仍按default_source烘值() throws Exception {
        Map<String, Object> row = resolve("{}");
        assertEquals(1.05, ((Number) row.get("损耗率")).doubleValue(), 1e-9);
    }

    @Test
    void 键缺失且无源时仍回落静态content() throws Exception {
        Map<String, Object> row = resolve("{}");
        assertEquals("13", row.get("税率"));
    }

    @Test
    void 键缺失且无源无content时不落键() throws Exception {
        Map<String, Object> row = resolve("{}");
        assertFalse(row.containsKey("备注"), "从未定值且无任何默认值 → 不落键（保持原行为）");
    }

    @Test
    void editValues为null时等价于键全缺失() throws Exception {
        Map<String, Object> row = resolve(null);
        assertEquals(1.05, ((Number) row.get("损耗率")).doubleValue(), 1e-9);
        assertEquals("13", row.get("税率"));
        assertFalse(row.containsKey("备注"));
    }

    @Test
    void 有值的INPUT原样保留() throws Exception {
        Map<String, Object> row = resolve("{\\"损耗率\\":2.5,\\"备注\\":\\"手填\\"}");
        assertEquals(2.5, ((Number) row.get("损耗率")).doubleValue(), 1e-9);
        assertEquals("手填", row.get("备注"));
    }

    @Test
    void 非INPUT字段一律不受本次改动影响() throws Exception {
        Map<String, Object> empty = resolve("{\\"损耗率\\":\\"\\"}");
        Map<String, Object> filled = resolve("{\\"损耗率\\":2.5}");
        // BASIC_DATA / FORMULA 在两种情况下逐位相同
        assertEquals("Ag", empty.get("元素"));
        assertEquals("Ag", filled.get("元素"));
        assertEquals(641.925, ((Number) empty.get("材料成本")).doubleValue(), 1e-9);
        assertEquals(641.925, ((Number) filled.get("材料成本")).doubleValue(), 1e-9);
    }

    @Test
    void editValues里的null按键缺失处理不落键() throws Exception {
        // 空值的物理表示只认空串；null 走「键缺失」链路（spec §4 注：不引入第三种空语义）
        Map<String, Object> row = resolve("{\\"损耗率\\":null}");
        assertEquals(1.05, ((Number) row.get("损耗率")).doubleValue(), 1e-9);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot/cpq-backend
./mvnw -q test -Dtest='InputKeyPresenceAuthorityTest'
```

预期：`显式清空的INPUT必须落键且值为空串` / `显式清空不得回落静态content` **FAIL**（`containsKey` 返回 false，因为 `nonEmpty("")` 为假、键被丢弃）；其余 7 个 PASS。

- [ ] **Step 3: 改实现**

`FormulaCalculator.java` 把 1251-1283 的 INPUT 分支整体替换为：

```java
            // ── INPUT_NUMBER / INPUT_TEXT / INPUT ─────────────────────────────
            // spec 2026-08-03「键存在即权威」：editValues 明确含该字段（present 且非 null）
            // = 用户已定值 → 原样落键，**含显式清空 ""**。
            // 改动前这里是 `if (nonEmpty(v)) out.put(...)`，空值被挡掉不落键，导致
            // 「用户清空」与「从未填过」在 row_data 里物理同形 → 前端 bake 判成空格子
            // 重新烘默认值 → 用户删掉的数字重开又回来。
            // 本口径与本类 fillInputDefaultSourceByFieldName(:1866「仅键缺失才补」) 一致。
            if ("INPUT_NUMBER".equals(type) || "INPUT_TEXT".equals(type) || "INPUT".equals(type)) {
                JsonNode editNode = (editValues != null) ? editValues.path(name) : null;
                boolean editHas = editNode != null && !editNode.isMissingNode() && !editNode.isNull();
                if (editHas) {
                    Object uv = unwrapNode(nodeToObject(editNode));
                    // 空值的物理表示统一为 ""：若落成 null，mergeRowDataInputsIntoEdits 的
                    // `!v.isNull()` 会跳过该键 → 下一轮又退化成「键缺失」被回填（spec §4 注）。
                    out.put(name, uv != null ? uv : "");
                    continue;
                }
                Object v = null;
                if (driverRow != null) v = nodeToObject(driverRow.path(name));
                if (!nonEmpty(v)) {
                    JsonNode ds = defaultSource(f);
                    if (ds != null && basicDataValues != null) {
                        String dsType = ds.path("type").asText("");
                        if ("GLOBAL_VARIABLE".equals(dsType)) {
                            Object g = lookupBdv(basicDataValues, "@gvar:" + ds.path("code").asText(""));
                            if (nonEmpty(g)) v = g;
                        } else if ("BNF_PATH".equals(dsType) || "BASIC_DATA".equals(dsType)) {
                            String p = ds.path("path").asText("");
                            if (!p.isEmpty()) {
                                Object g = lookupBdv(basicDataValues, bnfDriverLookupKey(p));
                                if (nonEmpty(g)) v = g;
                            }
                        }
                    }
                }
                if (!nonEmpty(v)) {
                    String c = content(f);
                    if (c != null && !c.isEmpty()) v = c;
                }
                if (nonEmpty(v)) out.put(name, unwrapNode(v));
                continue;
            }
```

- [ ] **Step 4: 跑测试确认全过**

```bash
./mvnw -q test -Dtest='InputKeyPresenceAuthorityTest'
```
预期：`Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑既有回归**

```bash
./mvnw -q test -Dtest='ResolveRowByFieldNameTest,RowDataMaterializerTest,LineRowDataMaterializeCrossTabTest,EffKeyNodeIdAlignmentTest,CardSnapshotResolvedRowsTest,CardSnapshotCrossTabTest'
```
预期：全部 PASS，0 failures。**若有失败，停下来报告**——说明改动波及了未预期的路径。

- [ ] **Step 6: 提交**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot
git commit -q -m "fix(repair-0803-snapshot): 后端显式清空的 INPUT 值必须落键

resolveRowByFieldName 原先 nonEmpty(v) 假就不写键，使「用户清空」与「从未填过」
在 row_data 里物理同形，前端 bake 据此重新烘默认值。改为 editValues 含该键即原样
落键（含 \"\"），口径对齐同类 fillInputDefaultSourceByFieldName。

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QVCtXU6tJEg7ThuzZHJPvX" \
  -- cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java \
     cpq-backend/src/test/java/com/cpq/quotation/service/InputKeyPresenceAuthorityTest.java
git show --stat --oneline HEAD
```
`git show --stat` 必须只列出这 2 个文件。

---

## Task 2：抽出唯一判据模块 + bake effect 改用它

**Files:**
- Create: `cpq-frontend/src/pages/quotation/keyPresenceAuthority.ts`
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx:1869-1870`
- Test: `cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts`（新建）

- [ ] **Step 1: 写失败测试**

新建 `cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts`：

```ts
import { describe, it, expect } from 'vitest';
import { isKeyUnset } from './keyPresenceAuthority';

describe('isKeyUnset —— 键存在即权威（spec 2026-08-03）', () => {
  it('键不存在 → 未定值，允许烘一次', () => {
    expect(isKeyUnset({}, '损耗率')).toBe(true);
    expect(isKeyUnset({ 其他列: 1 }, '损耗率')).toBe(true);
  });

  it('键存在且值为空串（用户清空）→ 已定值，禁止烘', () => {
    expect(isKeyUnset({ 损耗率: '' }, '损耗率')).toBe(false);
  });

  it('键存在且有值 → 已定值，禁止烘', () => {
    expect(isKeyUnset({ 损耗率: 1.05 }, '损耗率')).toBe(false);
    expect(isKeyUnset({ 损耗率: 0 }, '损耗率')).toBe(false);
  });

  it('键存在但值为 null → 按键缺失处理', () => {
    // 空值物理表示只认空串；null 不引入第三种语义（spec §4 注）
    expect(isKeyUnset({ 损耗率: null }, '损耗率')).toBe(true);
  });

  it('undefined 行对象 → 未定值（新行）', () => {
    expect(isKeyUnset(undefined, '损耗率')).toBe(true);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot/cpq-frontend
npx vitest run src/pages/quotation/keyPresenceAuthority.test.ts
```
预期：FAIL —— 找不到模块 `./keyPresenceAuthority`。

- [ ] **Step 3: 新建判据模块**

新建 `cpq-frontend/src/pages/quotation/keyPresenceAuthority.ts`：

```ts
/**
 * spec 2026-08-03「行数据即快照 / 键存在即权威」的**唯一前端实现**。
 *
 * 不变式：`row_data[row][fieldName]` 键存在 = 已定值，任何路径不得写入；
 * 键不存在 = 从未定值，仅此时允许烘一次默认值。清空写空串 `''`，不是删键。
 *
 * 本模块存在的意义就是「只有一份判据」——bake effect、保存回填、enrich 合并三处
 * 必须调这里，禁止各自内联判空。修复前正是因为三处各写各的（都把 `''` 和
 * 「键不存在」折叠成同一个「空」），用户清空的数字每次重开都被默认值填回。
 *
 * 后端同口径见 `FormulaCalculator.resolveRowByFieldName` INPUT 分支与
 * `fillInputDefaultSourceByFieldName`（:1866「仅键缺失才补」）。
 */

/**
 * 该格是否「从未定值」（= 允许烘一次默认值）。
 *
 * `null` 按键缺失处理：空值的物理表示只认空串，不引入第三种空语义
 * （后端 `mergeRowDataInputsIntoEdits` 会跳过 null，落成 null 等于键丢了）。
 */
export function isKeyUnset(row: Record<string, any> | undefined | null, key: string): boolean {
  if (!row) return true;
  if (!Object.prototype.hasOwnProperty.call(row, key)) return true;
  return row[key] === null;
}

/**
 * 这批 rows 里是否含用户数据。
 *
 * 判据是「有没有非 row_index 的业务键」，**不看值是否为空**——用户把一行清空
 * 也是用户数据，不能因此把整行退回 enriched 默认行。
 */
export function rowsHaveUserData(rows: Array<Record<string, any>> | null | undefined): boolean {
  if (!Array.isArray(rows) || rows.length === 0) return false;
  return rows.some((r) => r && Object.keys(r).some((k) => k !== 'row_index'));
}
```

- [ ] **Step 4: 改 bake effect 用它**

`QuotationStep2.tsx` 顶部 import 区加：

```ts
import { isKeyUnset } from './keyPresenceAuthority';
```

`QuotationStep2.tsx:1869-1870`，把：

```ts
          const cur = curRow[key];
          if (!(cur === undefined || cur === null || cur === '')) { bakedRef.current.add(guard); continue; }
```

替换为：

```ts
          // spec 2026-08-03：判据由「值为空」收紧为「键不存在」，用户清空('')不再被回填。
          // bakedRef 只活在当前挂载实例、刷新即清零，故判据必须自己就是持久的。
          if (!isKeyUnset(curRow, key)) { bakedRef.current.add(guard); continue; }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
npx vitest run src/pages/quotation/keyPresenceAuthority.test.ts
```
预期：`5 passed`

- [ ] **Step 6: 跑前端全量 + 类型检查**

```bash
npx vitest run --reporter=dot 2>&1 | tail -5
npx tsc --noEmit -p tsconfig.json
```
预期：vitest **≥ 886 passed**（基线 886 + 本次新增 5 = 891），tsc **0 错误**。63 个 e2e 文件加载失败是基线噪声，忽略。

- [ ] **Step 7: Vite transform 自检（CLAUDE.md 强制）**

```bash
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' \
  http://localhost:5174/src/pages/quotation/QuotationStep2.tsx
```
预期 `200`。注意 5174 跑的是**主仓**代码，这一步只验证语法能被 Vite 解析——真正的行为验证在 Task 7。

- [ ] **Step 8: 提交**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot
git add cpq-frontend/src/pages/quotation/keyPresenceAuthority.ts \
        cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts \
        cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -q -m "fix(repair-0803-snapshot): 抽出唯一判据模块，bake 收紧为「键不存在」才烘

bakedRef 只活在当前挂载实例、刷新即清零；配合「值为空即烘」的判据，用户清空的
格子每次重开都被 default_source/content 填回。判据下沉到 keyPresenceAuthority.ts
单一实现，供 bake / 保存回填 / enrich 合并三处共用，杜绝再次各写各的。

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QVCtXU6tJEg7ThuzZHJPvX" \
  -- cpq-frontend/src/pages/quotation/keyPresenceAuthority.ts \
     cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts \
     cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git show --stat --oneline HEAD
```

---

## Task 3：前端保存回填 §1.5 / §1.6 —— 同判据

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx:1061-1088`
- Test: `cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts`（追加）

- [ ] **Step 1: 追加回归测试（锁住"保存那一刻也不许回填"这条语义）**

在 `keyPresenceAuthority.test.ts` 末尾追加：

```ts
describe('保存回填复用同一判据（§1.5 / §1.6）', () => {
  it('用户清空的格子在保存时不得被静态 content 填回', () => {
    // snapshotRows §1.5/§1.6 的守卫条件必须等价于「键未定值」
    const enriched: Record<string, any> = { 税率: '' };
    expect(isKeyUnset(enriched, '税率')).toBe(false);
  });

  it('从未定值的格子在保存时仍应填入静态 content', () => {
    const enriched: Record<string, any> = {};
    expect(isKeyUnset(enriched, '税率')).toBe(true);
  });
});
```

- [ ] **Step 2: 跑测试确认通过（本步是护栏，非红灯）**

```bash
npx vitest run src/pages/quotation/keyPresenceAuthority.test.ts
```
预期：`7 passed`。判据函数 Task 2 已建，这两条锁的是「§1.5/§1.6 必须复用它」这个约定；真正的红→绿由 Step 5 的全量回归体现。

- [ ] **Step 3: 在 `QuotationWizard.tsx` 引入判据**

`QuotationWizard.tsx` 顶部 import 区加：

```ts
import { isKeyUnset, rowsHaveUserData } from './keyPresenceAuthority';
```

（`rowsHaveUserData` 供 Task 4 用，一次 import 到位。）

- [ ] **Step 4: 改 §1.5（FIXED_VALUE）**

`QuotationWizard.tsx:1064-1072`，把：

```ts
        if (enriched[fieldKey] === undefined || enriched[fieldKey] === null || enriched[fieldKey] === '') {
          enriched[fieldKey] = f.content;
        }
```

替换为：

```ts
        // spec 2026-08-03：仅「键不存在」才补默认值；用户清空('')必须原样保存。
        if (isKeyUnset(enriched, fieldKey)) {
          enriched[fieldKey] = f.content;
        }
```

- [ ] **Step 5: 改 §1.6（INPUT 静态默认值）**

`QuotationWizard.tsx:1083-1087`，把：

```ts
        if (enriched[fieldKey] === undefined || enriched[fieldKey] === null || enriched[fieldKey] === '') {
          enriched[fieldKey] = f.field_type === 'INPUT_NUMBER'
            ? (coerceInputNumber(f.content) ?? f.content)  // 数值列归一，非法保留原值
            : f.content;
        }
```

替换为：

```ts
        // spec 2026-08-03：同 §1.5，仅「键不存在」才补。
        if (isKeyUnset(enriched, fieldKey)) {
          enriched[fieldKey] = f.field_type === 'INPUT_NUMBER'
            ? (coerceInputNumber(f.content) ?? f.content)  // 数值列归一，非法保留原值
            : f.content;
        }
```

- [ ] **Step 6: 跑测试**

```bash
npx vitest run src/pages/quotation/keyPresenceAuthority.test.ts
npx vitest run --reporter=dot 2>&1 | tail -5
npx tsc --noEmit -p tsconfig.json
```
预期：新测试 `7 passed`（Task2 的 5 + Task3 的 2）；全量 **≥ 893 passed**；tsc 0 错误。

- [ ] **Step 7: 提交**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx \
        cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts
git commit -q -m "fix(repair-0803-snapshot): 保存回填 §1.5/§1.6 收紧为「键不存在」才补默认值

原判据「值为空就填回 content」使用户清空在点保存那一刻即被回填，比重开回填更早发作。

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QVCtXU6tJEg7ThuzZHJPvX" \
  -- cpq-frontend/src/pages/quotation/QuotationWizard.tsx \
     cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts
git show --stat --oneline HEAD
```

---

## Task 4：前端 `hasUserInput` —— 空值也算用户数据

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx:519-521`
- Test: `cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts`（追加）

**背景**：enrich 完成后合并 `componentData` 时，若某行「只剩空值」会被判成「用户没动过」→ 整行退回 enriched 默认行，用户的清空被整行抹掉。

- [ ] **Step 1: 追加失败测试**

在 `keyPresenceAuthority.test.ts` 末尾追加（`rowsHaveUserData` 已由 Task 2 的模块导出，把它加进文件顶部那行 import：`import { isKeyUnset, rowsHaveUserData } from './keyPresenceAuthority';`）：

```ts
describe('rowsHaveUserData —— 清空也是用户数据', () => {
  it('有非空值 → true', () => {
    expect(rowsHaveUserData([{ 损耗率: 1.05 }])).toBe(true);
  });

  it('只有空串（用户清空过）→ true，不得退回默认行', () => {
    expect(rowsHaveUserData([{ 损耗率: '' }])).toBe(true);
  });

  it('只有 row_index → false', () => {
    expect(rowsHaveUserData([{ row_index: 0 }])).toBe(false);
  });

  it('全空对象 → false', () => {
    expect(rowsHaveUserData([{}, {}])).toBe(false);
  });

  it('null / 空数组 → false', () => {
    expect(rowsHaveUserData(null)).toBe(false);
    expect(rowsHaveUserData([])).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认通过（护栏，非红灯）**

```bash
npx vitest run src/pages/quotation/keyPresenceAuthority.test.ts
```
预期：`12 passed`（5 + 2 + 5）。函数本体 Task 2 已建，这 5 条锁的是「清空的行不算空行」这条语义；真正的红→绿由 Step 4 的调用点替换体现。

- [ ] **Step 3: 确认 import 已就位**

Task 3 Step 3 已在 `QuotationWizard.tsx` 顶部加过 `import { isKeyUnset, rowsHaveUserData } from './keyPresenceAuthority';`。若因故未加，现在补上。

- [ ] **Step 4: 改调用点**

`QuotationWizard.tsx:519-521`，把：

```ts
              const hasUserInput = rowsCur && rowsCur.some((r: Record<string, any>) =>
                r && Object.keys(r).some(k => k !== 'row_index' && r[k] != null && r[k] !== '')
              );
```

替换为：

```ts
              // spec 2026-08-03：清空也是用户数据 —— 只看键，不看值。
              const hasUserInput = rowsHaveUserData(rowsCur);
```

- [ ] **Step 5: 跑测试**

```bash
npx vitest run src/pages/quotation/keyPresenceAuthority.test.ts
npx vitest run --reporter=dot 2>&1 | tail -5
npx tsc --noEmit -p tsconfig.json
```
预期：新测试 `12 passed`（5+2+5）；全量 **≥ 898 passed**（基线 886 + 12）；tsc 0 错误。

- [ ] **Step 6: 提交**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx \
        cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts
git commit -q -m "fix(repair-0803-snapshot): hasUserInput 只看键不看值，清空的行不再退回默认行

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QVCtXU6tJEg7ThuzZHJPvX" \
  -- cpq-frontend/src/pages/quotation/QuotationWizard.tsx \
     cpq-frontend/src/pages/quotation/keyPresenceAuthority.test.ts
git show --stat --oneline HEAD
```

---

## Task 5：只读渲染分支 —— 空就显示「—」（AP-50 两端一致）

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/components/ComponentCell.tsx:589-610`

**背景**：只读态（详情页 / `ReadonlyProductCard`）在值为空时会回退显示 `resolveInputDefault` 解析出的默认值。不改的话，用户清空后编辑页是空的、详情页却仍显示数字——正是 AP-50 说的双端不一致。

- [ ] **Step 1: 改实现**

`ComponentCell.tsx:589-610`，把：

```tsx
  // readonly=true: 只读文本渲染
  if (readonly) {
    if (!isEmpty) {
      const formatted = formatPathValue(rawCell);
      return <span>{formatted ?? String(rawCell)}</span>;
    }

    // default_source 解析（统一解析器；含 BASIC_DATA + content 兜底）
    if (isNumber || field.field_type === 'INPUT_TEXT' || field.field_type === 'INPUT') {
      const def = resolveInputDefault(field, {
        basicDataValues,
        partNo,
        pathCache: pathCacheState as Record<string, any>,
      });
      if (def !== undefined) {
        const formatted = formatPathValue(def) ?? String(def);
        return <span title="默认值">{formatted}</span>;
      }
    }

    return <span className="qt-ds-placeholder">—</span>;
  }
```

替换为：

```tsx
  // readonly=true: 只读文本渲染
  //
  // spec 2026-08-03「快照即权威」：只读态**不再**回退解析 default_source。
  // 行数据是什么就显示什么，空就是「—」。改动前这里会在值为空时显示默认值，
  // 于是用户清空后编辑页空白、详情页仍有数字 —— AP-50 双端不一致的典型形状。
  // 默认值的唯一写入时机是「键不存在时烘一次」（QuotationStep2 bake effect），
  // 烘完即落进行数据，只读态自然就能读到，无需在渲染层再补一次。
  if (readonly) {
    if (!isEmpty) {
      const formatted = formatPathValue(rawCell);
      return <span>{formatted ?? String(rawCell)}</span>;
    }
    return <span className="qt-ds-placeholder">—</span>;
  }
```

- [ ] **Step 2: 清理不再使用的 import（若已无引用）**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot/cpq-frontend
grep -n "resolveInputDefault" src/pages/quotation/components/ComponentCell.tsx
```
若只剩第 32 行的 import 而无任何调用，删除该 import；若仍有其它调用点，保留。

- [ ] **Step 3: 类型检查 + 全量测试**

```bash
npx tsc --noEmit -p tsconfig.json
npx vitest run --reporter=dot 2>&1 | tail -5
```
预期：tsc **0 错误**（若报 `resolveInputDefault` 未使用，回到 Step 2 删 import）；vitest **≥ 898 passed**，且**不得有新增失败**。若某个既有测试断言「只读态显示默认值」，说明它编码的正是被本次推翻的旧行为——**停下来报告**，不要擅自改测试。

- [ ] **Step 4: Vite transform 自检**

```bash
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' \
  http://localhost:5174/src/pages/quotation/components/ComponentCell.tsx
```
预期 `200`。

- [ ] **Step 5: 提交**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot
git add cpq-frontend/src/pages/quotation/components/ComponentCell.tsx
git commit -q -m "fix(repair-0803-snapshot): 只读态不再回退 default_source，空即显示「—」

不改则用户清空后编辑页空白、详情页仍显示数字，正是 AP-50 双端不一致。

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QVCtXU6tJEg7ThuzZHJPvX" \
  -- cpq-frontend/src/pages/quotation/components/ComponentCell.tsx
git show --stat --oneline HEAD
```

---

## Task 6：重展开路径的 patch 保留（本次风险最高，单独做单独验）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java:986-995`
- Test: `cpq-backend/src/test/java/com/cpq/configure/service/OverlayExistingInputKeysTest.java`（新建）

**背景**：`snapshotLines` 里凡 `lineNeedsExpand` 为真的行（某 driver 组件 snapshot_rows 缺失/为空），会走 `computeRowDataFromSnap` **用空 editRows 重物化整行所有组件的 row_data**，把刚保存的手工输入连同「键存在」这个事实一起冲掉 → 不变式在这条路径上被破坏。

**修法**：物化后、落库前，把库内既有 row_data 中**已存在的 INPUT_\* 键**原样盖回；BASIC_DATA / DATA_SOURCE / FORMULA 一律用新算值（这正是「刷新基础数据」该刷的部分，spec D2）。

**行对齐用 `row_index`**（物化输出必带该键），不用数组下标——行增删时下标会错位，`row_index` 匹配不上就自然退化为「用新值」，安全。

- [ ] **Step 1: 写失败测试**

新建 `cpq-backend/src/test/java/com/cpq/configure/service/OverlayExistingInputKeysTest.java`：

```java
package com.cpq.configure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** spec 2026-08-03 Task 6：重物化后把既有 row_data 的 INPUT 键盖回（纯函数，不起 Quarkus）。 */
class OverlayExistingInputKeysTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final UUID CID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** components_snapshot：一个组件，含 INPUT_NUMBER「损耗率」/ BASIC_DATA「元素」/ FORMULA「材料成本」 */
    private static final String SNAPSHOT = """
      [ {"componentId":"11111111-1111-1111-1111-111111111111","componentCode":"C1","tabName":"材料成本",
         "fields":[ {"name":"损耗率","field_type":"INPUT_NUMBER"},
                    {"name":"元素","field_type":"BASIC_DATA"},
                    {"name":"材料成本","field_type":"FORMULA"} ]} ]
      """;

    private Map<UUID, ArrayNode> fresh(String json) throws Exception {
        Map<UUID, ArrayNode> m = new LinkedHashMap<>();
        m.put(CID, (ArrayNode) M.readTree(json));
        return m;
    }

    private Map<UUID, JsonNode> existing(String json) throws Exception {
        Map<UUID, JsonNode> m = new LinkedHashMap<>();
        m.put(CID, M.readTree(json));
        return m;
    }

    @Test
    void 既有的用户清空必须盖回重物化结果() throws Exception {
        Map<UUID, ArrayNode> f = fresh(
            "[{\\"row_index\\":0,\\"损耗率\\":1.05,\\"元素\\":\\"Ag\\",\\"材料成本\\":641.9}]");
        Map<UUID, JsonNode> e = existing(
            "[{\\"row_index\\":0,\\"损耗率\\":\\"\\",\\"元素\\":\\"旧\\",\\"材料成本\\":1}]");

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, e);

        JsonNode row = f.get(CID).get(0);
        assertEquals("", row.path("损耗率").asText(), "用户清空必须保留，不能被重物化的 1.05 覆盖");
        assertEquals("Ag", row.path("元素").asText(), "BASIC_DATA 必须用新值（刷新的意义所在）");
        assertEquals(641.9, row.path("材料成本").asDouble(), 1e-9, "FORMULA 必须用重算值");
    }

    @Test
    void 既有的用户手填值必须盖回() throws Exception {
        Map<UUID, ArrayNode> f = fresh("[{\\"row_index\\":0,\\"损耗率\\":1.05}]");
        Map<UUID, JsonNode> e = existing("[{\\"row_index\\":0,\\"损耗率\\":9.99}]");

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, e);

        assertEquals(9.99, f.get(CID).get(0).path("损耗率").asDouble(), 1e-9);
    }

    @Test
    void 既有行没有该键时用新烘的值() throws Exception {
        Map<UUID, ArrayNode> f = fresh("[{\\"row_index\\":0,\\"损耗率\\":1.05}]");
        Map<UUID, JsonNode> e = existing("[{\\"row_index\\":0}]");

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, e);

        assertEquals(1.05, f.get(CID).get(0).path("损耗率").asDouble(), 1e-9);
    }

    @Test
    void 按rowIndex对齐而非数组下标() throws Exception {
        // 新结果 2 行(row_index 0,1)；既有只有 row_index=1 那行有用户值
        Map<UUID, ArrayNode> f = fresh(
            "[{\\"row_index\\":0,\\"损耗率\\":1.05},{\\"row_index\\":1,\\"损耗率\\":2.10}]");
        Map<UUID, JsonNode> e = existing("[{\\"row_index\\":1,\\"损耗率\\":\\"\\"}]");

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, e);

        assertEquals(1.05, f.get(CID).get(0).path("损耗率").asDouble(), 1e-9, "row_index=0 不受影响");
        assertEquals("", f.get(CID).get(1).path("损耗率").asText(), "row_index=1 盖回用户清空");
    }

    @Test
    void 既有为空或null时整体不变() throws Exception {
        Map<UUID, ArrayNode> f = fresh("[{\\"row_index\\":0,\\"损耗率\\":1.05}]");
        String before = f.get(CID).toString();

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, Map.of());
        assertEquals(before, f.get(CID).toString());

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, null);
        assertEquals(before, f.get(CID).toString());
    }

    @Test
    void 快照里没有的组件不动() throws Exception {
        UUID other = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Map<UUID, ArrayNode> f = new LinkedHashMap<>();
        f.put(other, (ArrayNode) M.readTree("[{\\"row_index\\":0,\\"损耗率\\":1.05}]"));
        Map<UUID, JsonNode> e = new LinkedHashMap<>();
        e.put(other, M.readTree("[{\\"row_index\\":0,\\"损耗率\\":\\"\\"}]"));

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, e);

        assertEquals(1.05, f.get(other).get(0).path("损耗率").asDouble(), 1e-9,
            "组件不在 components_snapshot 里 → 取不到 INPUT 字段清单 → 不动");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot/cpq-backend
./mvnw -q test -Dtest='OverlayExistingInputKeysTest'
```
预期：编译失败 —— `overlayExistingInputKeys` 方法不存在。

- [ ] **Step 3: 加静态纯函数**

在 `ConfigureSnapshotService.java` 的 `computeRowDataFromSnap`（:986）**之前**插入：

```java
    /**
     * spec 2026-08-03「键存在即权威」：把库内既有 {@code row_data} 里<b>已存在的 INPUT_* 键</b>
     * 原样盖回重物化结果（含显式清空 {@code ""}）。
     *
     * <p><b>为什么需要</b>：{@link #computeRowDataFromSnap} 是「纯按 snapshot 重物化」（editRows 恒空），
     * 会把用户手填/清空的 INPUT 值一并冲掉。只盖 INPUT_* 列，BASIC_DATA / DATA_SOURCE / FORMULA
     * 一律保留新算值 —— 那正是「刷新基础数据」该刷的部分（spec 决策 D2）。
     *
     * <p><b>行对齐用 {@code row_index}</b>（物化输出必带），不用数组下标：行增删时下标会错位，
     * {@code row_index} 匹配不上就自然退化为「用新值」，安全（AP-54 同族纪律：过滤后子集的下标
     * 绝不当原集合下标使）。
     *
     * <p>静态纯函数，无 IO、无状态，便于单测（{@code OverlayExistingInputKeysTest}）。
     *
     * @param componentsSnapshot 模板 components_snapshot（提供各组件的 fields → INPUT 字段清单）
     * @param fresh              重物化结果，<b>原地修改</b>
     * @param existingByComp     库内既有 row_data（componentId → 数组），可为 null/空 → 整体不动
     */
    static void overlayExistingInputKeys(JsonNode componentsSnapshot,
                                         Map<UUID, ArrayNode> fresh,
                                         Map<UUID, JsonNode> existingByComp) {
        if (componentsSnapshot == null || !componentsSnapshot.isArray()
                || fresh == null || fresh.isEmpty()
                || existingByComp == null || existingByComp.isEmpty()) {
            return;
        }
        for (JsonNode tab : componentsSnapshot) {
            UUID cid;
            try {
                cid = UUID.fromString(tab.path("componentId").asText(""));
            } catch (Exception ignore) {
                continue;
            }
            ArrayNode freshRows = fresh.get(cid);
            JsonNode existingRows = existingByComp.get(cid);
            if (freshRows == null || existingRows == null || !existingRows.isArray()) continue;

            // 本组件的 INPUT_* 字段名集合
            List<String> inputFields = new ArrayList<>();
            for (JsonNode f : tab.path("fields")) {
                String ft = f.path("field_type").asText("");
                if ("INPUT_NUMBER".equals(ft) || "INPUT_TEXT".equals(ft) || "INPUT".equals(ft)) {
                    String n = f.path("name").asText("");
                    if (!n.isEmpty()) inputFields.add(n);
                }
            }
            if (inputFields.isEmpty()) continue;

            // 既有行按 row_index 建索引
            Map<Integer, JsonNode> oldByRowIndex = new HashMap<>();
            for (JsonNode oldRow : existingRows) {
                if (!oldRow.isObject()) continue;
                JsonNode ri = oldRow.get("row_index");
                if (ri != null && ri.isInt()) oldByRowIndex.put(ri.asInt(), oldRow);
            }
            if (oldByRowIndex.isEmpty()) continue;

            for (JsonNode freshRow : freshRows) {
                if (!(freshRow instanceof ObjectNode)) continue;
                JsonNode ri = freshRow.get("row_index");
                if (ri == null || !ri.isInt()) continue;
                JsonNode oldRow = oldByRowIndex.get(ri.asInt());
                if (oldRow == null) continue;
                for (String fld : inputFields) {
                    // 「键存在即权威」：既有行有这个键就盖回（含 ""）；没有就用新烘的值。
                    if (oldRow.has(fld)) ((ObjectNode) freshRow).set(fld, oldRow.get(fld));
                }
            }
        }
    }
```

确认 `ConfigureSnapshotService.java` 顶部已 import：`com.fasterxml.jackson.databind.node.ObjectNode`、`java.util.ArrayList`、`java.util.HashMap`、`java.util.List`。缺哪个补哪个。

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q test -Dtest='OverlayExistingInputKeysTest'
```
预期：`Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 接线到 `computeRowDataFromSnap`**

把 `ConfigureSnapshotService.java:986-995` 整个方法替换为：

```java
    private Map<UUID, ArrayNode> computeRowDataFromSnap(UUID lineItemId, JsonNode componentsSnapshot,
                                                        Map<UUID, String> snapByComp) {
        if (componentsSnapshot == null || snapByComp == null || snapByComp.isEmpty()) return Map.of();
        Map<UUID, JsonNode> baseRowsByComp = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> e : snapByComp.entrySet()) {
            baseRowsByComp.put(e.getKey(), parseRows(e.getValue()));
        }
        LinkedHashMap<UUID, ArrayNode> freshRowData = computeLineRowData(lineItemId, componentsSnapshot,
                baseRowsByComp, Map.of(), Map.of(), Map.of());
        // spec 2026-08-03：上面是「纯按 snapshot 重物化」(editRows 恒空)，会冲掉用户手填/清空的
        // INPUT 值。落库前把库内既有 row_data 的 INPUT 键盖回（含显式清空 ""），
        // BASIC_DATA/DATA_SOURCE/FORMULA 仍用新算值。降级：查库失败只记 warn，不中止整份快照。
        try {
            overlayExistingInputKeys(componentsSnapshot, freshRowData, loadRowDataByComp(lineItemId));
        } catch (Exception e) {
            LOG.warnf("[materialize-line] line=%s 既有 INPUT 键盖回失败(已降级，本次按重物化值落库): %s",
                    lineItemId, e.getMessage());
        }
        return freshRowData;
    }

    /** 读本行各组件既有 row_data（componentId → 数组），供 {@link #overlayExistingInputKeys} 用。 */
    private Map<UUID, JsonNode> loadRowDataByComp(UUID lineItemId) {
        Map<UUID, JsonNode> out = new LinkedHashMap<>();
        if (lineItemId == null) return out;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT component_id, row_data FROM quotation_line_component_data " +
                "WHERE line_item_id = :lid AND row_data IS NOT NULL")
            .setParameter("lid", lineItemId)
            .getResultList();
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            try {
                JsonNode arr = MAPPER.readTree(r[1].toString());
                if (arr.isArray()) out.put((UUID) r[0], arr);
            } catch (Exception ignore) { /* 单组件解析失败不影响其它组件 */ }
        }
        return out;
    }
```

若 `MAPPER` / `LOG` 在本类的常量名不同，按本类实际命名调整（先 `grep -n "ObjectMapper MAPPER\|Logger LOG" ConfigureSnapshotService.java` 确认）。

- [ ] **Step 6: 跑相关回归**

```bash
./mvnw -q test -Dtest='OverlayExistingInputKeysTest,RowDataWholeBatchEquivTest,LineRowDataMaterializeCrossTabTest,FirstSaveBatchWriteEquivTest,PersistWholeBatchEquivTest'
```
预期：全部 PASS。`RowDataWholeBatchEquivTest` / `PersistWholeBatchEquivTest` 是「新旧路径逐位等价」类测试，**它们过 = 本次没改动落库内容的其它维度**。任一失败**停下来报告**。

- [ ] **Step 7: 提交**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot
git add cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java \
        cpq-backend/src/test/java/com/cpq/configure/service/OverlayExistingInputKeysTest.java
git commit -q -m "fix(repair-0803-snapshot): 重展开路径落库前盖回既有 INPUT 键，不再冲掉用户输入

lineNeedsExpand 为真的行会用空 editRows 重物化整行 row_data，把刚保存的手工输入
连同「键存在」这一事实一起冲掉。新增静态纯函数 overlayExistingInputKeys 按 row_index
对齐盖回 INPUT_* 键（含显式清空），BASIC_DATA/DATA_SOURCE/FORMULA 仍用新算值。

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QVCtXU6tJEg7ThuzZHJPvX" \
  -- cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java \
     cpq-backend/src/test/java/com/cpq/configure/service/OverlayExistingInputKeysTest.java
git show --stat --oneline HEAD
```

---

## Task 7：全链路验收 + 文档回写

**Files:**
- Modify: `docs/RECORD.md`
- Modify: `BACKLOG.md`

- [ ] **Step 1: 后端全量测试**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot/cpq-backend
./mvnw -q test 2>&1 | tail -30
```
把结果**如实**记下来。已知基线失败项（**不是本次引入，不要去修**）：
- `GoldenCardValuesEquivTest` 2 个 SKIPPED（夹具单不在测试库）
- `PricingVersioningImportE2ETest` 3 个失败（Excel 夹具本身不是有效 zip，RECORD 已记载）

出现**其它**失败 → 停下来报告，先做 A/B 归因（`git stash` 本次改动后在同环境重跑对照）。

- [ ] **Step 2: 前端全量 + 类型检查**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot/cpq-frontend
npx vitest run --reporter=dot 2>&1 | tail -5
npx tsc --noEmit -p tsconfig.json
```
预期：**≥ 898 passed**（基线 886 + 新增 12），tsc **0 错误**。

- [ ] **Step 3: 真实单据闭环复测（人工，必须做）**

用主仓 dev server（5174 / 8081）**跑主仓代码**是验不了本分支的。按历史做法起临时实例：

```bash
# 后端临时实例（端口 8099，连开发库 cpq_db_0724，不影响 8081）
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot/cpq-backend
./mvnw quarkus:dev -Dquarkus.http.port=8099

# 前端临时实例（另开终端，端口 5199，proxy 指向 8099）
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot/cpq-frontend
npx vite --port 5199 --strictPort
```

复测脚本（用 spec §1.2 里那张真实单）：
1. 打开 `QT-20260731-0037` → 「材料成本」页签
2. 找到第 0 行「元素单价」（当前值 216770，其源 `{$mc_view.元素单价}` 有值 —— 这是改动前**必被回填**的那一格）
3. 清空 → 保存草稿 → **重新打开报价单**
4. ✅ 期望：该格仍为空
5. 再保存一次 → 重开 → ✅ 期望：仍为空
6. ✅ 期望：同页签**未动过**的其它格子默认值照常显示（如「项次」）
7. 切到该单**详情页** → ✅ 期望：该格显示「—」，与编辑页一致
8. 点「刷新基础数据」→ ✅ 期望：该格仍为空，而 BASIC_DATA 列（元素/材质）按最新基础数据刷新、FORMULA 列重算

**每一步都要截图**，作为交付证据。任一步不符 → 停下来报告，不要自行调整期望。

- [ ] **Step 4: 删除借来的迁移文件**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot
rm -f cpq-backend/src/main/resources/db/migration/V368__task0729_price_adjust_schema.sql \
      cpq-backend/src/main/resources/db/migration/V369__task0729_material_element_price_and_views.sql \
      cpq-backend/src/main/resources/db/migration/V370__task0729_component_element_role_fields_backfill.sql \
      cpq-backend/src/main/resources/db/migration/V371__task0729_notification_type_expand.sql
git status --short
```
预期：`git status` 里**不含任何 V36x/V37x 文件**，也不含其它未跟踪的意外文件。

- [ ] **Step 5: 回写 `docs/RECORD.md`**

在文件末尾追加一行（格式对齐既有条目）：

```
[2026-08-03] 报价渲染/物化 - 行数据即快照：默认值只在「键不存在」时烘一次，用户清空可持久化 | FormulaCalculator.resolveRowByFieldName / keyPresenceAuthority.ts(isKeyUnset+rowsHaveUserData，前端唯一判据) / QuotationStep2 bake effect / QuotationWizard(§1.5/§1.6/hasUserInput) / ComponentCell 只读分支 / ConfigureSnapshotService.overlayExistingInputKeys | **根因**：`''`(用户清空) 与「键不存在」(从未填过) 在 6 处判空口径里被折叠成同一个「空」，而 bake 守卫 bakedRef 只活在当前挂载的卡片实例里、刷新即清零 → 重开必把用户删掉的数字按 default_source/content 烘回来。实测 91 个 INPUT_NUMBER 里 81 个配了 default_source，几乎全部数字列受影响。**不变式**：row_data 键存在=已定值禁止写入，键不存在=仅此时允许烘一次；清空写空串不删键。**先例**：该口径在 FormulaCalculator.fillInputDefaultSourceByFieldName:1866 与前端 currentRowForEval 已正确实现，本次是把其余 6 处对齐，非新规则。**值中性**：不改任何求值口径（""与键缺失在列小计/公式引擎/cross_tab 里行为相同）。**踩坑**：GoldenCardValuesEquivTest 夹具单已不在测试库恒 SKIPPED（BL-0021/BL-0078 同族），不能当值中性绿灯用，改以自包含矩阵单测 InputKeyPresenceAuthorityTest 替代；worktree 里 Flyway 会因「库有 V368~V371、本地无」validate 失败，需临时借入并发会话的未提交迁移，收尾前删除。
```

- [ ] **Step 6: `BACKLOG.md` 登记 PriceReconciler 语义冲突**

在 `## P1` 段末尾追加：

```markdown
### [BL-0101] 价格策略接管的 `元素单价` 列仍可编辑 —— 改了保存后被静默改回
- **优先级**：P1（用户可见的「改了不生效」，与 2026-08-03「行数据即快照」不变式语义冲突）
- **来源**：2026-08-03 排查「保存后重开数字复原」时顺带查证（spec `2026-08-03-row-data-snapshot-authority-design.md` §7）
- **状态**：TODO（未排期）
- **登记日期**：2026-08-03
- **背景**：`PriceReconciler.reconcileQuotation` 在每次 `saveDraft` 后对「元素 ∈ 调价清单 ∧ 料号 ∈ 范围 ∧ 策略启用」的行**无条件覆盖** `row_data` 的价格列（`PriceReconciler.java:204-233`），解不出价时直接 `remove` 该键。实测 `cpq_db_0724`：`CUST-0001 罗克韦尔` 有 1 条 enabled 策略（2026-08-03 07:23 建）、`material_scope_mode=ALL`、元素清单 `Ag`/`Cu`；8 个组件配了 `element_price_field=元素单价`。
- **问题**：价格列被系统接管后，UI 上**仍然是可编辑的输入框**。用户改了或清空后保存，会被策略价悄悄改回 —— 与「行数据即快照、用户定值不可被系统改写」的不变式直接冲突。
- **范围**：产品裁定后二选一 ——（a）价格列在被策略接管时置灰 + 悬浮提示「由价格调整策略管控（版本 X）」，`__priceLocked` 标记已在传输链路里；（b）允许手工覆盖并让归位跳过被手工改过的格子。**不要**保持现状。
- **依赖**：task-0729 合并落地后再动。**预估规模**：S
- **验收要点**：①被策略接管的价格格子行为对用户可预期（要么改不了，要么改了算数）；②不再出现「改了保存后被静默改回」。
```

- [ ] **Step 7: 提交文档**

```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0803-snapshot
git add docs/RECORD.md BACKLOG.md
git commit -q -m "docs(repair-0803-snapshot): RECORD 登记 + BL-0101 价格列语义冲突

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QVCtXU6tJEg7ThuzZHJPvX" \
  -- docs/RECORD.md BACKLOG.md
git show --stat --oneline HEAD
```

- [ ] **Step 8: 交付自检声明**

按 CLAUDE.md 要求输出一行「已自检」，**如实**包含：后端全量测试结果（含已知基线失败项）、前端 vitest 数、tsc 结果、真实单据闭环复测结论、以及**没有 E2E 绿灯**这一事实（BL-0078 夹具失效）。

---

## 计划自检

**Spec 覆盖**：spec §4 的 7 个改动点 → Task 1（#4）/ Task 2（#1）/ Task 3（#2#3）/ Task 4（#5）/ Task 5（#6）/ Task 6（#7 加码）；§5 验证策略 → Task 7；§7 BACKLOG 登记 → Task 7 Step 6。**无遗漏**。

**占位符扫描**：无 TBD / TODO / 「类似 Task N」；每个改代码的步骤都给了完整代码块。

**类型一致性**：前端判据收敛为 `keyPresenceAuthority.ts` 一个模块的两个导出 —— `isKeyUnset(row, key): boolean`（bake effect + §1.5 + §1.6 三处共用）与 `rowsHaveUserData(rows): boolean`（`hasUserInput` 用）。已核实 `QuotationWizard.tsx:15` 本就 import 自 `QuotationStep2`，新模块被两者共同依赖不引入循环。后端 `overlayExistingInputKeys(componentsSnapshot, fresh, existingByComp)` 三参 static，测试与实现签名逐字一致；已核实 `ConfigureSnapshotService` 的 `LOG` / `MAPPER` / `em` 命名与 `ObjectNode`/`ArrayList`/`HashMap`/`List` 四个 import **均已存在**，无需补 import。

**与 spec 的一处偏离（已在 Task 前言说明）**：spec §5.1 指定 `GoldenCardValuesEquivTest` 作头号值中性证据，实测其 2 个用例恒 SKIPPED（夹具单不在测试库）。改由 `InputKeyPresenceAuthorityTest` 的矩阵断言承担该职责，并保留 `RowDataWholeBatchEquivTest` / `PersistWholeBatchEquivTest` 作落库等价护栏。
