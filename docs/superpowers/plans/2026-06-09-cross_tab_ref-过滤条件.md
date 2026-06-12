# cross_tab_ref 过滤条件（filter / SUMIF）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 cross_tab_ref token 加可选 `filter`，在 match（等值关联）之后、聚合之前过滤源组件 A 行，实现组件字段侧的 SUMIF/COUNTIF。

**Architecture:** filter 是 token 上的可选数组，缺省 = 逐字节同现状（零迁移、零 snapshot 重建）。前端 `formulaEngine.ts` 与后端 `FormulaCalculator.java` 逐字镜像同一谓词折叠逻辑，**由共享夹具 `cross-tab-cases.json`（两份同步）锁双引擎对等**——这是头号风险点，所以夹具先行（RED）、两引擎分别转 GREEN。后端不引入 JEXL，内联轻量谓词镜像前端。P1 范围：RHS 只做字面量；支持 AND/OR 多条件（左到右等优先级折叠，无括号分组）。

**Tech Stack:** TypeScript + Vite + Vitest（前端）；Java 17 + Quarkus + JUnit5 DynamicTest（后端）；Ant Design Drawer（UI）；Playwright（E2E）。

**关联设计:** `docs/superpowers/specs/2026-06-09-cross_tab_ref-过滤条件-design.md`
**协议:** `docs/反模式.md` AP-55（cross_tab_ref token 字段扩展，约 8 处传播点，非 AP-44 全 17 点）

**用户已拍板的两个 scope 决策（2026-06-09）:**
1. RHS **只做字面量**（不含 b_col，A 字段对 B 行字段比较推后 P2）
2. **P1 就做 AND/OR 多条件**（左到右等优先级折叠）

---

## 设计契约（所有 Task 共用，先读再动手）

### filter 数据结构
```jsonc
"filter": [
  { "field": "类型", "op": "ne", "value": "银点", "logic": "and" }
]
```
- `field`：**A 组件字段名**（中文，直接 `arow[field]` 取值，不涉及 SQL → 无 AP-53 别名问题）。
- `op`：复用 `CondOp` 枚举 `eq | ne | gt | gte | lt | lte | in`。
- `value`：**字面量字符串**（P1 only literal RHS）。
- `logic`：`'and' | 'or'`，本行与**下一行**的连接符（末行忽略）。
- `filter` 缺省 / 空数组 → 不过滤，行为与现状一致。

### 求值语义（前后端**必须逐字镜像**，夹具锁定）
`passCrossTabFilter(arow, filter)`：
1. filter 空 → `true`。
2. 否则**左到右折叠**（等优先级，无 AND-over-OR 优先级）：
   ```
   result = test(arow, filter[0])
   for i in 1..n-1:
     result = (filter[i-1].logic === 'and') ? (result && test(arow, filter[i]))
                                             : (result || test(arow, filter[i]))
   ```
3. `test(arow, cond)`：取 `av = arow[cond.field]`：
   - **`in`**：`av` 空 → false；否则 `cond.value` 按逗号拆成列表（trim、去空），`String(av).trim()` ∈ 列表 → true。
   - **`av` 为空（null/''/纯空白）**：除 `ne` → true（空 ≠ 非空字面量）外，一律 false。
   - **数值比较** iff `av` 非空且能 parse 成数字 **且** `value.trim()` 非空且能 parse 成数字：按 `eq/ne/gt/gte/lt/lte` 数值运算。
   - 否则**字符串比较**：`String(av).trim()` vs `String(value).trim()`，`eq/ne` 用 equals，`gt/gte/lt/lte` 用 UTF-16 code-unit 字典序（JS `<`/`>` 与 Java `String.compareTo` 在 BMP 字符上一致，中文均 BMP）。

> **插入点（已核实）:**
> - 前端 `formulaEngine.ts:253` `hits` 计算后、`:263` 聚合前。
> - 后端 `FormulaCalculator.java:221` `hits` 填充后、`:223` 聚合前。

### 原始文本符号映射（crossTabText 序列化用）
| op | 符号 | logic | 符号 |
|---|---|---|---|
| eq | `=` | and | `且` |
| ne | `≠` | or | `或` |
| gt | `>` | | |
| gte | `≥` | | |
| lt | `<` | | |
| lte | `≤` | | |
| in | `∈` | | |

文本段形如：`… | 目标:A.x | 筛选:类型≠银点 且 单价>0`

---

## File Structure

| 文件 | 责任 | Task |
|---|---|---|
| `cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json` | 共享夹具（前端权威副本）+ filter 用例 | 1 |
| `cpq-backend/src/test/resources/cross-tab-cases.json` | 共享夹具（后端同步副本，必须逐字相同） | 1 |
| `cpq-frontend/src/pages/component/types.ts` | `FormulaToken.filter?` + `CrossTabFilterCond` 类型 | 2 |
| `cpq-frontend/src/utils/formulaEngine.ts` | `passCrossTabFilter` 纯函数 + hits 后插入 | 2 |
| `cpq-frontend/src/utils/formulaEngine.test.ts` | `passCrossTabFilter` 单测（fold/compare/blank） | 2 |
| `cpq-backend/.../quotation/service/FormulaCalculator.java` | `evalCrossTab` hits 后内联 filter + `passCrossTabFilter`/`testCond` 私有方法 | 3 |
| `cpq-frontend/src/pages/component/crossTabText.ts` | serialize/parse `筛选:` 段 + `summarizeFilter`/`OP_TO_SYM` 导出 | 4 |
| `cpq-frontend/src/pages/component/crossTabText.test.ts` | `筛选:` round-trip 单测 | 4 |
| `cpq-frontend/src/pages/component/CrossTabRefDrawer.tsx` | 过滤条件 UI 区块 + state + buildTokenLike + 预览 + 应用文本 + reset | 5 |
| `cpq-frontend/src/components/formula/FormulaZone.tsx` | `getTokenLabel` 追加 filter 摘要 | 6 |
| `cpq-backend/.../component/service/ComponentService.java` | `validateFormulas` filter 结构校验 | 7 |
| `cpq-backend/.../ComponentServiceCrossTabValidateTest.java` | filter 校验单测 | 7 |
| `cpq-frontend/e2e/cross-tab-builder.spec.ts` | filter 配置路径 E2E | 8 |
| `docs/反模式.md` / `docs/RECORD.md` | AP-55 追加 filter 小节 + 开发记录 | 9 |

---

## Task 1: 共享夹具加 filter 用例（双引擎 RED 锚点）

先把 filter 语义用例写进共享夹具。此刻两引擎都没实现 filter（直接忽略它），所以这些新用例的 `expected` 与实际不符 → 前端 vitest 夹具 loop 和后端 DynamicTest 都 FAIL。这是 TDD 的 RED，锁住"两引擎必须对等"的契约。

**Files:**
- Modify: `cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json`（在数组末尾 `]` 前追加）
- Modify: `cpq-backend/src/test/resources/cross-tab-cases.json`（逐字相同）

- [ ] **Step 1: 在前端夹具 JSON 数组末尾追加 filter 用例**

打开 `cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json`，把最后一个用例（`"targetExpr 优先于 target"`，结尾 `}`）后面、数组闭合 `]` 之前，插入逗号并粘贴以下 10 个用例：

```jsonc
  {
    "name": "filter ne 单条件 SUM (非银点)",
    "_doc": "三行匹配 P1，filter 类型≠银点 排除银点行 → 0.8+0.5=1.3",
    "aRows": [
      {"子件": "P1", "类型": "银点", "单重": 9.9},
      {"子件": "P1", "类型": "普通", "单重": 0.8},
      {"子件": "P1", "类型": "普通", "单重": 0.5}
    ],
    "currentRow": {"子件": "P1"},
    "token": {
      "type": "cross_tab_ref", "source": "A", "target": "单重", "agg": "SUM",
      "match": [{"a": "子件", "b": "子件"}],
      "filter": [{"field": "类型", "op": "ne", "value": "银点", "logic": "and"}]
    },
    "expected": 1.3
  },
  {
    "name": "filter eq 单条件 SUM",
    "_doc": "filter 类型=银点 只留银点行 → 9.9",
    "aRows": [
      {"子件": "P1", "类型": "银点", "单重": 9.9},
      {"子件": "P1", "类型": "普通", "单重": 0.8}
    ],
    "currentRow": {"子件": "P1"},
    "token": {
      "type": "cross_tab_ref", "source": "A", "target": "单重", "agg": "SUM",
      "match": [{"a": "子件", "b": "子件"}],
      "filter": [{"field": "类型", "op": "eq", "value": "银点", "logic": "and"}]
    },
    "expected": 9.9
  },
  {
    "name": "filter gt 数值 SUM",
    "_doc": "filter 价格>5 数值比较，只留 8、6 → 14",
    "aRows": [
      {"子件": "P1", "价格": 3, "单重": 1},
      {"子件": "P1", "价格": 8, "单重": 1},
      {"子件": "P1", "价格": 6, "单重": 1}
    ],
    "currentRow": {"子件": "P1"},
    "token": {
      "type": "cross_tab_ref", "source": "A", "target": "价格", "agg": "SUM",
      "match": [{"a": "子件", "b": "子件"}],
      "filter": [{"field": "价格", "op": "gt", "value": "5", "logic": "and"}]
    },
    "expected": 14
  },
  {
    "name": "filter in 列表 SUM",
    "_doc": "filter 类型∈A,C 留 A、C 行 → 1+3=4",
    "aRows": [
      {"子件": "P1", "类型": "A", "单重": 1},
      {"子件": "P1", "类型": "B", "单重": 2},
      {"子件": "P1", "类型": "C", "单重": 3}
    ],
    "currentRow": {"子件": "P1"},
    "token": {
      "type": "cross_tab_ref", "source": "A", "target": "单重", "agg": "SUM",
      "match": [{"a": "子件", "b": "子件"}],
      "filter": [{"field": "类型", "op": "in", "value": "A,C", "logic": "and"}]
    },
    "expected": 4
  },
  {
    "name": "filter 多条件 AND SUM",
    "_doc": "类型≠银点 AND 价格>5：只第 3 行(普通,8)满足 → 3",
    "aRows": [
      {"子件": "P1", "类型": "银点", "价格": 8, "单重": 9},
      {"子件": "P1", "类型": "普通", "价格": 3, "单重": 2},
      {"子件": "P1", "类型": "普通", "价格": 8, "单重": 3}
    ],
    "currentRow": {"子件": "P1"},
    "token": {
      "type": "cross_tab_ref", "source": "A", "target": "单重", "agg": "SUM",
      "match": [{"a": "子件", "b": "子件"}],
      "filter": [
        {"field": "类型", "op": "ne", "value": "银点", "logic": "and"},
        {"field": "价格", "op": "gt", "value": "5", "logic": "and"}
      ]
    },
    "expected": 3
  },
  {
    "name": "filter 多条件 OR SUM",
    "_doc": "类型=银点 OR 价格>7：第1行(银点,9)+第3行(普通,8)满足 → 9+3=12",
    "aRows": [
      {"子件": "P1", "类型": "银点", "价格": 1, "单重": 9},
      {"子件": "P1", "类型": "普通", "价格": 3, "单重": 2},
      {"子件": "P1", "类型": "普通", "价格": 8, "单重": 3}
    ],
    "currentRow": {"子件": "P1"},
    "token": {
      "type": "cross_tab_ref", "source": "A", "target": "单重", "agg": "SUM",
      "match": [{"a": "子件", "b": "子件"}],
      "filter": [
        {"field": "类型", "op": "eq", "value": "银点", "logic": "or"},
        {"field": "价格", "op": "gt", "value": "7", "logic": "and"}
      ]
    },
    "expected": 12
  },
  {
    "name": "filter 空数组 = 与无 filter 同结果 (向后兼容锁)",
    "_doc": "filter:[] 等价于不过滤 → 两行 P1 单重 0.8+0.5=1.3",
    "aRows": [
      {"子件": "P1", "单重": 0.8},
      {"子件": "P1", "单重": 0.5},
      {"子件": "P2", "单重": 0.3}
    ],
    "currentRow": {"子件": "P1"},
    "token": {
      "type": "cross_tab_ref", "source": "A", "target": "单重", "agg": "SUM",
      "match": [{"a": "子件", "b": "子件"}],
      "filter": []
    },
    "expected": 1.3
  },
  {
    "name": "filter + targetExpr 组合 SUM",
    "_doc": "先过滤(类型≠废)再逐行算 用量×单价 求和：留行1(2×3=6)+行3(1×4=4)=10，行2(废)排除",
    "aRows": [
      {"子件": "P1", "类型": "正", "用量": 2, "单价": 3},
      {"子件": "P1", "类型": "废", "用量": 9, "单价": 9},
      {"子件": "P1", "类型": "正", "用量": 1, "单价": 4}
    ],
    "currentRow": {"子件": "P1"},
    "token": {
      "type": "cross_tab_ref", "source": "A", "agg": "SUM",
      "match": [{"a": "子件", "b": "子件"}],
      "filter": [{"field": "类型", "op": "ne", "value": "废", "logic": "and"}],
      "targetExpr": [
        {"type": "field", "value": "用量"},
        {"type": "operator", "value": "*"},
        {"type": "field", "value": "单价"}
      ]
    },
    "expected": 10
  },
  {
    "name": "COUNT + filter = COUNTIF",
    "_doc": "类型≠银点 命中 2 行 → COUNT=2",
    "aRows": [
      {"子件": "P1", "类型": "银点"},
      {"子件": "P1", "类型": "普通"},
      {"子件": "P1", "类型": "普通"}
    ],
    "currentRow": {"子件": "P1"},
    "token": {
      "type": "cross_tab_ref", "source": "A", "target": "", "agg": "COUNT",
      "match": [{"a": "子件", "b": "子件"}],
      "filter": [{"field": "类型", "op": "ne", "value": "银点", "logic": "and"}]
    },
    "expected": 2
  },
  {
    "name": "filter ne 空值行保留 (空≠非空字面量=true)",
    "_doc": "类型≠银点：类型为空的行 → ne 对空值返 true → 保留；银点行排除。留 行1(空,5)+行3(普通,3)=8",
    "aRows": [
      {"子件": "P1", "类型": null, "单重": 5},
      {"子件": "P1", "类型": "银点", "单重": 9},
      {"子件": "P1", "类型": "普通", "单重": 3}
    ],
    "currentRow": {"子件": "P1"},
    "token": {
      "type": "cross_tab_ref", "source": "A", "target": "单重", "agg": "SUM",
      "match": [{"a": "子件", "b": "子件"}],
      "filter": [{"field": "类型", "op": "ne", "value": "银点", "logic": "and"}]
    },
    "expected": 8
  }
```

- [ ] **Step 2: 把同样的内容同步进后端夹具**

后端副本必须逐字相同。用文件拷贝保证一致（不要手抄）：

Run:
```bash
cp cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json cpq-backend/src/test/resources/cross-tab-cases.json
```

- [ ] **Step 3: 跑前端夹具，确认新用例 FAIL（RED）**

Run:
```bash
cd cpq-frontend && npx vitest run src/utils/formulaEngine.test.ts -t "cross-tab fixture"
```
Expected: FAIL — 新增 10 个 filter 用例报错（如 `filter ne 单条件 SUM (非银点)` expected 1.3 实得 11.2，因为 filter 当前被忽略，银点行也求和了）。旧 16 例仍 PASS。

- [ ] **Step 4: 跑后端夹具，确认新用例 FAIL（RED）**

Run:
```bash
cd cpq-backend && ./mvnw test -Dtest=FormulaCalculatorCrossTabFixtureTest -q
```
Expected: FAIL — 对应 filter 用例 AssertionError（filter 被后端忽略）。

- [ ] **Step 5: Commit（RED 锚点）**

```bash
git add cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json cpq-backend/src/test/resources/cross-tab-cases.json
git commit -m "test(cross-tab-filter): shared fixtures for SUMIF/COUNTIF filter (RED)"
```

---

## Task 2: 前端引擎实现 filter（夹具转 GREEN）

**Files:**
- Modify: `cpq-frontend/src/pages/component/types.ts:124-170`（FormulaToken += filter；新增 CrossTabFilterCond）
- Modify: `cpq-frontend/src/utils/formulaEngine.ts:251-296`（cross_tab_ref case）
- Test: `cpq-frontend/src/utils/formulaEngine.test.ts`

- [ ] **Step 1: types.ts 加 CrossTabFilterCond 类型 + FormulaToken.filter?**

在 `types.ts` 的 `FormulaToken` 接口定义之前（第 124 行 `export interface FormulaToken {` 上方）插入：

```ts
/** cross_tab_ref filter（SUMIF）单条件。P1：RHS 仅字面量。 */
export interface CrossTabFilterCond {
  /** A 组件字段名（中文，做 map key 直取 arow[field]） */
  field: string;
  /** 复用 CondOp 枚举 */
  op: 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in';
  /** 字面量 RHS（in 时逗号分隔多值） */
  value: string;
  /** 与下一条件的连接符（末条忽略） */
  logic: 'and' | 'or';
}
```

然后在 `FormulaToken` 接口里、`targetExpr?: FormulaToken[];`（第 169 行）下方、闭合 `}` 之前插入：

```ts
  /**
   * cross_tab_ref filter（SUMIF/COUNTIF）：match 后聚合前过滤 A 行。
   * 缺省/空数组 = 不过滤（向后兼容）。AP-55。
   */
  filter?: CrossTabFilterCond[];
```

- [ ] **Step 2: formulaEngine.ts 加 passCrossTabFilter 纯函数**

在 `formulaEngine.ts` 文件顶部的 import 之后、`evaluateExpression` 函数定义之前，插入纯函数（文件内部即可，无需 export，但加 export 便于单测）：

```ts
import type { CrossTabFilterCond } from '../pages/component/types';

/** cross_tab_ref filter（SUMIF）：单条件求值。空值规则与 match 同精神。 */
function testCrossTabCond(arow: Record<string, any>, cond: CrossTabFilterCond): boolean {
  const av = arow[cond.field];
  const blank = av == null || String(av).trim() === '';
  if (cond.op === 'in') {
    if (blank) return false;
    const list = String(cond.value).split(',').map((s) => s.trim()).filter((s) => s !== '');
    return list.includes(String(av).trim());
  }
  if (blank) return cond.op === 'ne'; // 空 ≠ 非空字面量 → true；其余 false
  const na = Number(av);
  const valTrim = String(cond.value).trim();
  const nb = Number(valTrim);
  const numeric = valTrim !== '' && !isNaN(na) && !isNaN(nb);
  if (numeric) {
    switch (cond.op) {
      case 'eq': return na === nb;
      case 'ne': return na !== nb;
      case 'gt': return na > nb;
      case 'gte': return na >= nb;
      case 'lt': return na < nb;
      case 'lte': return na <= nb;
    }
  }
  const sa = String(av).trim();
  const sv = valTrim;
  switch (cond.op) {
    case 'eq': return sa === sv;
    case 'ne': return sa !== sv;
    case 'gt': return sa > sv;
    case 'gte': return sa >= sv;
    case 'lt': return sa < sv;
    case 'lte': return sa <= sv;
  }
  return false;
}

/** 左到右等优先级折叠（无 AND-over-OR 优先级）。后端 FormulaCalculator.passCrossTabFilter 逐字镜像。 */
export function passCrossTabFilter(
  arow: Record<string, any>,
  filter: CrossTabFilterCond[] | undefined,
): boolean {
  if (!filter || filter.length === 0) return true;
  let result = testCrossTabCond(arow, filter[0]);
  for (let i = 1; i < filter.length; i++) {
    const t = testCrossTabCond(arow, filter[i]);
    result = filter[i - 1].logic === 'and' ? (result && t) : (result || t);
  }
  return result;
}
```

- [ ] **Step 3: formulaEngine.ts 把 filter 接进 cross_tab_ref case**

把第 253 行的 `const hits = rows.filter(...)` 改为 `let hits`，并在 `hits` 计算完（第 262 行 `}));` 之后、第 263 行 `const agg = ...` 之前插入 filter。

将 `formulaEngine.ts:253` 的：
```ts
        const hits = rows.filter((ar) =>
```
改为：
```ts
        let hits = rows.filter((ar) =>
```

并在 `formulaEngine.ts:262`（`}));` 行）之后插入：
```ts
        // AP-55: filter (SUMIF/COUNTIF) — match 后聚合前过滤 A 行
        if (token.filter && token.filter.length > 0) {
          hits = hits.filter((ar) => passCrossTabFilter(ar, token.filter));
        }
```

- [ ] **Step 4: 跑前端夹具，确认全部 GREEN**

Run:
```bash
cd cpq-frontend && npx vitest run src/utils/formulaEngine.test.ts -t "cross-tab fixture"
```
Expected: PASS — 全部 26 例（16 旧 + 10 新 filter）通过。

- [ ] **Step 5: 加 passCrossTabFilter 专项单测**

在 `formulaEngine.test.ts` 顶部 import 行加上 `passCrossTabFilter`：
```ts
import { evaluateExpression, isWithinTolerance, passCrossTabFilter } from './formulaEngine';
```

在文件末尾 `describe('isWithinTolerance', ...)` 之前插入：
```ts
describe('passCrossTabFilter', () => {
  it('空/undefined filter → 恒 true', () => {
    expect(passCrossTabFilter({ 类型: '银点' }, undefined)).toBe(true);
    expect(passCrossTabFilter({ 类型: '银点' }, [])).toBe(true);
  });
  it('eq / ne 字符串', () => {
    expect(passCrossTabFilter({ 类型: '银点' }, [{ field: '类型', op: 'eq', value: '银点', logic: 'and' }])).toBe(true);
    expect(passCrossTabFilter({ 类型: '银点' }, [{ field: '类型', op: 'ne', value: '银点', logic: 'and' }])).toBe(false);
  });
  it('数值 gt/gte/lt/lte', () => {
    expect(passCrossTabFilter({ 价: 8 }, [{ field: '价', op: 'gt', value: '5', logic: 'and' }])).toBe(true);
    expect(passCrossTabFilter({ 价: 5 }, [{ field: '价', op: 'gte', value: '5', logic: 'and' }])).toBe(true);
    expect(passCrossTabFilter({ 价: 5 }, [{ field: '价', op: 'lt', value: '5', logic: 'and' }])).toBe(false);
  });
  it('in 列表', () => {
    expect(passCrossTabFilter({ 类型: 'C' }, [{ field: '类型', op: 'in', value: 'A,C', logic: 'and' }])).toBe(true);
    expect(passCrossTabFilter({ 类型: 'B' }, [{ field: '类型', op: 'in', value: 'A,C', logic: 'and' }])).toBe(false);
    expect(passCrossTabFilter({ 类型: null }, [{ field: '类型', op: 'in', value: 'A,C', logic: 'and' }])).toBe(false);
  });
  it('空值：ne→true 其余→false', () => {
    expect(passCrossTabFilter({ 类型: null }, [{ field: '类型', op: 'ne', value: '银点', logic: 'and' }])).toBe(true);
    expect(passCrossTabFilter({ 类型: '' }, [{ field: '类型', op: 'eq', value: '银点', logic: 'and' }])).toBe(false);
    expect(passCrossTabFilter({ 类型: '   ' }, [{ field: '类型', op: 'gt', value: '0', logic: 'and' }])).toBe(false);
  });
  it('AND 折叠', () => {
    const f = [
      { field: '类型', op: 'ne' as const, value: '银点', logic: 'and' as const },
      { field: '价', op: 'gt' as const, value: '5', logic: 'and' as const },
    ];
    expect(passCrossTabFilter({ 类型: '普通', 价: 8 }, f)).toBe(true);
    expect(passCrossTabFilter({ 类型: '普通', 价: 3 }, f)).toBe(false);
    expect(passCrossTabFilter({ 类型: '银点', 价: 8 }, f)).toBe(false);
  });
  it('OR 折叠', () => {
    const f = [
      { field: '类型', op: 'eq' as const, value: '银点', logic: 'or' as const },
      { field: '价', op: 'gt' as const, value: '7', logic: 'and' as const },
    ];
    expect(passCrossTabFilter({ 类型: '银点', 价: 1 }, f)).toBe(true);
    expect(passCrossTabFilter({ 类型: '普通', 价: 8 }, f)).toBe(true);
    expect(passCrossTabFilter({ 类型: '普通', 价: 3 }, f)).toBe(false);
  });
});
```

- [ ] **Step 6: 跑全套前端单测 + tsc**

Run:
```bash
cd cpq-frontend && npx vitest run src/utils/formulaEngine.test.ts && npx tsc --noEmit -p tsconfig.json
```
Expected: 全部 PASS，tsc 0 错误。

- [ ] **Step 7: Commit**

```bash
git add cpq-frontend/src/pages/component/types.ts cpq-frontend/src/utils/formulaEngine.ts cpq-frontend/src/utils/formulaEngine.test.ts
git commit -m "feat(cross-tab-filter): frontend passCrossTabFilter + wire into formulaEngine (fixtures GREEN)"
```

---

## Task 3: 后端引擎镜像 filter（夹具转 GREEN）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java:206-245`（evalCrossTab + 新增 2 个私有方法）

- [ ] **Step 1: evalCrossTab 在 hits 填充后插入 filter**

在 `FormulaCalculator.java:221`（`if (ok) hits.add(arow);` 所在 for 循环结束的 `}` 之后）、第 223 行 `if ("COUNT".equals(agg))` 之前插入：

```java
        // AP-55: filter (SUMIF/COUNTIF) — match 后聚合前过滤 A 行；逐字镜像前端 formulaEngine.passCrossTabFilter
        JsonNode filter = token.path("filter");
        if (filter.isArray() && filter.size() > 0) {
            hits.removeIf(arow -> !passCrossTabFilter(arow, filter));
        }
```

- [ ] **Step 2: 新增 passCrossTabFilter / testCrossTabCond 私有方法**

在 `evalCrossTab` 方法的闭合 `}`（第 245 行）之后、`targetRowValue` 方法之前插入。`toNumber(Object)` 已存在于本类（第 904 行），直接复用：

```java
    /** cross_tab_ref filter 折叠：左到右等优先级。逐字镜像前端 passCrossTabFilter。 */
    private boolean passCrossTabFilter(Map<String, Object> arow, JsonNode filter) {
        boolean result = testCrossTabCond(arow, filter.get(0));
        for (int i = 1; i < filter.size(); i++) {
            boolean t = testCrossTabCond(arow, filter.get(i));
            String logic = filter.get(i - 1).path("logic").asText("and");
            result = "and".equals(logic) ? (result && t) : (result || t);
        }
        return result;
    }

    /** cross_tab_ref filter 单条件求值。空值规则镜像前端 testCrossTabCond。 */
    private boolean testCrossTabCond(Map<String, Object> arow, JsonNode cond) {
        String field = cond.path("field").asText("");
        String op = cond.path("op").asText("eq");
        String value = cond.path("value").asText("");
        Object av = arow.get(field);
        boolean blank = av == null || av.toString().trim().isEmpty();
        if ("in".equals(op)) {
            if (blank) return false;
            String a = av.toString().trim();
            for (String s : value.split(",")) {
                String item = s.trim();
                if (!item.isEmpty() && a.equals(item)) return true;
            }
            return false;
        }
        if (blank) return "ne".equals(op); // 空 ≠ 非空字面量 → true；其余 false
        Double na = toNumber(av);
        String valTrim = value.trim();
        Double nb = valTrim.isEmpty() ? null : toNumber(valTrim);
        if (na != null && nb != null) {
            double a = na, b = nb;
            switch (op) {
                case "eq": return a == b;
                case "ne": return a != b;
                case "gt": return a > b;
                case "gte": return a >= b;
                case "lt": return a < b;
                case "lte": return a <= b;
                default: return false;
            }
        }
        String sa = av.toString().trim();
        int c = sa.compareTo(valTrim);
        switch (op) {
            case "eq": return sa.equals(valTrim);
            case "ne": return !sa.equals(valTrim);
            case "gt": return c > 0;
            case "gte": return c >= 0;
            case "lt": return c < 0;
            case "lte": return c <= 0;
            default: return false;
        }
    }
```

> **注意**：`hits` 在 `evalCrossTab` 中是 `List<Map<String,Object>> hits = new ArrayList<>()`（第 212 行），`removeIf` 可用。`toNumber` 接受 `Object`，传 `String valTrim` 合法。

- [ ] **Step 3: 跑后端夹具，确认全部 GREEN**

Run:
```bash
cd cpq-backend && ./mvnw test -Dtest=FormulaCalculatorCrossTabFixtureTest -q
```
Expected: PASS — 26 个 DynamicTest 全绿（双引擎对等达成）。

- [ ] **Step 4: 后端编译自检（dev 重启）**

Run:
```bash
cd cpq-backend && ./mvnw -q compile
```
Expected: BUILD SUCCESS。若 Quarkus dev 在跑，`touch` 一个 java 文件触发重启后 `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health` 期望 200。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java
git commit -m "feat(cross-tab-filter): backend evalCrossTab inline filter mirror (fixtures GREEN, parity locked)"
```

---

## Task 4: crossTabText 序列化/解析 `筛选:` 段（round-trip）

放宽 `parseCrossTab` 的 4 段硬校验为 4 或 5 段；filter 在 COUNT 早返回**之前**写入 base（保证 COUNTIF 文本也能 round-trip）。

**Files:**
- Modify: `cpq-frontend/src/pages/component/crossTabText.ts`
- Test: `cpq-frontend/src/pages/component/crossTabText.test.ts`

- [ ] **Step 1: crossTabText.ts 顶部加 filter 类型 import + 符号表 + 序列化助手**

在 `crossTabText.ts:1` 的 import 改为：
```ts
import type { FormulaToken, CrossTabFilterCond } from './types';
```

在 `CrossTabTokenLike` 接口（第 28-36 行）的 `agg: string;` 后、闭合 `}` 前加：
```ts
  filter?: CrossTabFilterCond[];
```

在 `exprTokenToCanonical` 函数之前插入符号表与 filter 序列化/摘要助手：
```ts
/** filter op → 文本符号（serialize / chip 摘要共用）。 */
export const OP_TO_SYM: Record<CrossTabFilterCond['op'], string> = {
  eq: '=', ne: '≠', gt: '>', gte: '≥', lt: '<', lte: '≤', in: '∈',
};
const SYM_TO_OP: Array<[string, CrossTabFilterCond['op']]> = [
  ['≠', 'ne'], ['≥', 'gte'], ['≤', 'lte'], ['∈', 'in'], ['=', 'eq'], ['>', 'gt'], ['<', 'lt'],
];

/** filter → 「类型≠银点 且 单价>0」中文摘要（FormulaZone chip / 预览共用）。 */
export function summarizeFilter(filter: CrossTabFilterCond[] | undefined): string {
  if (!filter || filter.length === 0) return '';
  return filter
    .map((c, i) => {
      const seg = `${c.field}${OP_TO_SYM[c.op]}${c.value}`;
      const conn = i < filter.length - 1 ? (c.logic === 'or' ? ' 或 ' : ' 且 ') : '';
      return seg + conn;
    })
    .join('');
}

/** 「筛选:」段文本 → filter 数组；失败抛字符串。 */
function parseFilterSeg(text: string): CrossTabFilterCond[] {
  const parts = text.split(/(\s+且\s+|\s+或\s+)/).filter((s) => s.trim() !== '');
  const conds: CrossTabFilterCond[] = [];
  for (let i = 0; i < parts.length; i += 2) {
    const condStr = parts[i].trim();
    const connector = parts[i + 1]; // ' 且 ' / ' 或 ' / undefined
    let parsed: { field: string; op: CrossTabFilterCond['op']; value: string } | null = null;
    for (const [sym, op] of SYM_TO_OP) {
      const idx = condStr.indexOf(sym);
      if (idx > 0) {
        parsed = { field: condStr.slice(0, idx).trim(), op, value: condStr.slice(idx + sym.length).trim() };
        break;
      }
    }
    if (!parsed || !parsed.field || parsed.value === '') {
      throw `筛选条件「${condStr}」格式应为 字段<操作符>值（操作符: = ≠ > ≥ < ≤ ∈）`;
    }
    const logic: 'and' | 'or' = connector && connector.includes('或') ? 'or' : 'and';
    conds.push({ ...parsed, logic });
  }
  return conds;
}
```

- [ ] **Step 2: serializeCrossTab 末尾追加 `筛选:` 段**

把 `serializeCrossTab` 的 return（第 73 行）：
```ts
  return `${opLabel} | 源:${srcCode} | 关联:${pairs} | 目标:${targetText}`;
```
改为：
```ts
  const base = `${opLabel} | 源:${srcCode} | 关联:${pairs} | 目标:${targetText}`;
  const filterText = summarizeFilter(token.filter);
  return filterText ? `${base} | 筛选:${filterText}` : base;
```

- [ ] **Step 3: parseCrossTab 放宽段数 + filter 先于 COUNT 写入 base**

把 `parseCrossTab` 第 95-96 行：
```ts
  const segs = text.split('|').map((s) => s.trim());
  if (segs.length !== 4) return { error: '格式应为：操作 | 源:CODE | 关联:a=b[,c=d] | 目标:…' };
  const [opSeg, srcSeg, matchSeg, targetSeg] = segs;
```
改为：
```ts
  const segs = text.split('|').map((s) => s.trim());
  if (segs.length < 4 || segs.length > 5)
    return { error: '格式应为：操作 | 源:CODE | 关联:a=b[,c=d] | 目标:…[ | 筛选:字段≠值[ 且/或 …]]' };
  const [opSeg, srcSeg, matchSeg, targetSeg, filterSeg] = segs;
```

在解析 filter 段：把 base 构造（第 116-119 行）后面、`if (op.agg === 'COUNT')`（第 120 行）**之前**插入 filter 解析并挂到 base：
```ts
  if (filterSeg !== undefined) {
    if (!filterSeg.startsWith('筛选:')) return { error: '第 5 段应以「筛选:」开头' };
    try {
      const conds = parseFilterSeg(filterSeg.slice(3).trim());
      if (conds.length > 0) base.filter = conds;
    } catch (e) {
      return { error: typeof e === 'string' ? e : '筛选条件解析失败' };
    }
  }
```

> base 已声明为 `const base: CrossTabTokenLike`（第 116 行）。给它的可选 `filter` 属性赋值合法。后续 COUNT 早返回 `return { token: base }` 与 target/targetExpr 分支 `{ ...base, ... }` 均自动带上 filter（展开保留 filter）。

- [ ] **Step 4: 写 round-trip 单测**

在 `crossTabText.test.ts` 末尾追加：
```ts
describe('筛选 filter round-trip', () => {
  it('serialize 单条件 ne', () => {
    const token: any = {
      type: 'cross_tab_ref', source: 'id-ll', sourceLabel: '来料', target: '组成用量',
      match: [{ a: '子料号', b: '料件' }], agg: 'SUM',
      filter: [{ field: '类型', op: 'ne', value: '银点', logic: 'and' }],
    };
    expect(serializeCrossTab(token, ll)).toBe('求和 | 源:COMP-0028 | 关联:子料号=料件 | 目标:A.组成用量 | 筛选:类型≠银点');
  });
  it('serialize 多条件 AND/OR', () => {
    const token: any = {
      type: 'cross_tab_ref', source: 'id-ll', sourceLabel: '来料', target: '组成用量',
      match: [{ a: '子料号', b: '料件' }], agg: 'SUM',
      filter: [
        { field: '类型', op: 'ne', value: '银点', logic: 'or' },
        { field: '价', op: 'gt', value: '5', logic: 'and' },
      ],
    };
    expect(serializeCrossTab(token, ll)).toBe('求和 | 源:COMP-0028 | 关联:子料号=料件 | 目标:A.组成用量 | 筛选:类型≠银点 或 价>5');
  });
  it('parse round-trip 单条件', () => {
    const text = '求和 | 源:COMP-0028 | 关联:子料号=料件 | 目标:A.组成用量 | 筛选:类型≠银点';
    const r = parseCrossTab(text, siblings);
    expect('token' in r).toBe(true);
    if ('token' in r) {
      expect(r.token.filter).toEqual([{ field: '类型', op: 'ne', value: '银点', logic: 'and' }]);
    }
  });
  it('parse round-trip 多条件 OR', () => {
    const text = '求和 | 源:COMP-0028 | 关联:子料号=料件 | 目标:A.组成用量 | 筛选:类型≠银点 或 价>5';
    const r = parseCrossTab(text, siblings);
    if ('token' in r) {
      expect(r.token.filter).toEqual([
        { field: '类型', op: 'ne', value: '银点', logic: 'or' },
        { field: '价', op: 'gt', value: '5', logic: 'and' },
      ]);
    }
  });
  it('COUNT + filter round-trip（filter 先于 COUNT 写入）', () => {
    const text = '计数 | 源:COMP-0028 | 关联:子料号=料件 | 目标:(计数) | 筛选:类型≠银点';
    const r = parseCrossTab(text, siblings);
    if ('token' in r) {
      expect(r.token.agg).toBe('COUNT');
      expect(r.token.filter).toEqual([{ field: '类型', op: 'ne', value: '银点', logic: 'and' }]);
    }
  });
  it('无 筛选 段 → filter undefined（向后兼容）', () => {
    const text = '求和 | 源:COMP-0028 | 关联:子料号=料件 | 目标:A.组成用量';
    const r = parseCrossTab(text, siblings);
    if ('token' in r) expect(r.token.filter).toBeUndefined();
  });
  it('筛选 段格式错 → error', () => {
    const text = '求和 | 源:COMP-0028 | 关联:子料号=料件 | 目标:A.组成用量 | 筛选:类型银点';
    const r = parseCrossTab(text, siblings);
    expect('error' in r).toBe(true);
  });
});
```

- [ ] **Step 5: 跑测试 + tsc**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/component/crossTabText.test.ts && npx tsc --noEmit -p tsconfig.json
```
Expected: 全 PASS，tsc 0 错误。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/component/crossTabText.ts cpq-frontend/src/pages/component/crossTabText.test.ts
git commit -m "feat(cross-tab-filter): serialize/parse 筛选 segment round-trip + summarizeFilter"
```

---

## Task 5: CrossTabRefDrawer 过滤条件 UI

在「匹配列对」与「要算什么」之间插入「过滤条件（可选）」区块；状态 `filterRows` 写入 token；预览/序列化/应用文本/reset 全链路带上 filter。

**Files:**
- Modify: `cpq-frontend/src/pages/component/CrossTabRefDrawer.tsx`

- [ ] **Step 1: import + CrossTabToken 接口加 filter + filterRows state**

`CrossTabRefDrawer.tsx:5` import 行改为：
```ts
import { OPERATIONS, operationToAgg, serializeCrossTab, parseCrossTab, aggToOperation, OP_TO_SYM } from './crossTabText';
import type { CrossTabFilterCond } from './types';
```

`CrossTabToken` 接口（第 22-30 行）的 `agg: string;` 后加：
```ts
  filter?: CrossTabFilterCond[];
```

在 state 区（第 108 行 `const [rawError, setRawError] = useState<string>('');` 之后）加：
```ts
  const [filterRows, setFilterRows] = useState<CrossTabFilterCond[]>([]);
```

- [ ] **Step 2: reset 时清 filterRows**

在 `useEffect`（第 113-130 行）的 `setRawError('');` 之后加：
```ts
      setFilterRows([]);
```

- [ ] **Step 3: buildTokenLike 写入非空 filter**

把 `buildTokenLike`（第 135-143 行）的 return 对象，在 `agg,` 后加：
```ts
    filter: filterRows.filter((r) => r.field && (r.op === 'in' ? r.value.trim() !== '' : r.value !== '')).length > 0
      ? filterRows.filter((r) => r.field && (r.op === 'in' ? r.value.trim() !== '' : r.value !== ''))
      : undefined,
```

- [ ] **Step 4: serialize effect 依赖加 filterRows**

把第 152 行的依赖数组：
```ts
  }, [mode, sourceId, sourceComp, matchPairs, useFormula, agg, target, targetExpr]);
```
改为：
```ts
  }, [mode, sourceId, sourceComp, matchPairs, useFormula, agg, target, targetExpr, filterRows]);
```

- [ ] **Step 5: filter 行操作 helpers**

在 `handleRemovePair` / `handlePairChange` 附近（第 174 行后）加：
```ts
  const FILTER_OPS: Array<{ value: CrossTabFilterCond['op']; label: string; simple: boolean }> = [
    { value: 'eq', label: '等于', simple: true },
    { value: 'ne', label: '不等于', simple: true },
    { value: 'in', label: '包含于', simple: true },
    { value: 'gt', label: '大于', simple: false },
    { value: 'gte', label: '大于等于', simple: false },
    { value: 'lt', label: '小于', simple: false },
    { value: 'lte', label: '小于等于', simple: false },
  ];
  const addFilterRow = () =>
    setFilterRows((prev) => [...prev, { field: '', op: 'eq', value: '', logic: 'and' }]);
  const removeFilterRow = (idx: number) =>
    setFilterRows((prev) => prev.filter((_, i) => i !== idx));
  const changeFilterRow = (idx: number, patch: Partial<CrossTabFilterCond>) =>
    setFilterRows((prev) => prev.map((r, i) => (i === idx ? { ...r, ...patch } : r)));
```

- [ ] **Step 6: 应用文本回填 filterRows**

在「应用文本」按钮 onClick（第 621-639 行）里、`manualEditRef.current = false;` 之前加：
```ts
                setFilterRows(r.token.filter ?? []);
```

- [ ] **Step 7: 插入「过滤条件（可选）」UI 区块**

在「匹配列对」`</div>`（第 355 行，匹配列对区块闭合）与「要算什么」区块（第 357 行注释）之间插入：
```tsx
        {/* 过滤条件（可选）— SUMIF/COUNTIF */}
        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13 }}>
            过滤条件（可选）
            <span style={{ fontWeight: 400, color: '#8c8c8c', marginLeft: 8, fontSize: 12 }}>
              只对满足条件的 A 行参与计算（如 类型 不等于 银点）
            </span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {filterRows.map((row, index) => (
              <div key={index} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {index > 0 && (
                  <Select
                    size="small"
                    style={{ width: 64 }}
                    value={filterRows[index - 1].logic}
                    onChange={(v) => changeFilterRow(index - 1, { logic: v as 'and' | 'or' })}
                    options={[{ label: '且', value: 'and' }, { label: '或', value: 'or' }]}
                  />
                )}
                <Select
                  style={{ flex: 1 }}
                  placeholder="A.字段"
                  value={row.field || undefined}
                  onChange={(v) => changeFilterRow(index, { field: v })}
                  options={sourceFields.map((f) => ({ label: fieldText(f), value: f.name }))}
                  disabled={!sourceId}
                  showSearch
                />
                <Select
                  style={{ width: 110 }}
                  value={row.op}
                  onChange={(v) => changeFilterRow(index, { op: v as CrossTabFilterCond['op'] })}
                  options={FILTER_OPS.filter((o) => mode === 'advanced' || o.simple).map((o) => ({
                    label: o.label, value: o.value,
                  }))}
                />
                <Input
                  style={{ flex: 1 }}
                  placeholder={row.op === 'in' ? '逗号分隔多值' : '值'}
                  value={row.value}
                  onChange={(e) => changeFilterRow(index, { value: e.target.value })}
                />
                <Button
                  type="text"
                  danger
                  size="small"
                  icon={<DeleteOutlined />}
                  onClick={() => removeFilterRow(index)}
                />
              </div>
            ))}
            <Button
              size="small"
              icon={<PlusOutlined />}
              onClick={addFilterRow}
              style={{ alignSelf: 'flex-start' }}
              disabled={!sourceId}
            >
              添加条件
            </Button>
          </div>
        </div>
```

- [ ] **Step 8: 预览区追加 filter 摘要**

把预览区（第 586-596 行）的 `]` 闭合前、`.join(' 且 ')}` 之后、`]` 之前，插入 filter 摘要。具体把：
```tsx
              {' '}当[
              {matchPairs
                .filter((p) => p.a && p.b)
                .map((p) => `${p.a}=本.${p.b}`)
                .join(' 且 ')}
              ]
```
改为：
```tsx
              {' '}当[
              {matchPairs
                .filter((p) => p.a && p.b)
                .map((p) => `${p.a}=本.${p.b}`)
                .join(' 且 ')}
              ]
              {filterRows.filter((r) => r.field && r.value).length > 0 && (
                <>
                  {' '}且仅当[
                  {filterRows
                    .filter((r) => r.field && r.value)
                    .map((r, i, arr) => `${r.field}${OP_TO_SYM[r.op]}${r.value}${i < arr.length - 1 ? (r.logic === 'or' ? ' 或 ' : ' 且 ') : ''}`)
                    .join('')}
                  ]
                </>
              )}
```

- [ ] **Step 9: 前端自检（tsc + Vite 200）**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "CrossTabRefDrawer=%{http_code}\n" http://localhost:5174/src/pages/component/CrossTabRefDrawer.tsx
```
Expected: tsc 0 错误；CrossTabRefDrawer=200。

- [ ] **Step 10: Commit**

```bash
git add cpq-frontend/src/pages/component/CrossTabRefDrawer.tsx
git commit -m "feat(cross-tab-filter): 过滤条件 UI block in CrossTabRefDrawer (state + preview + apply-text + reset)"
```

---

## Task 6: FormulaZone chip 回显 filter 摘要

**Files:**
- Modify: `cpq-frontend/src/components/formula/FormulaZone.tsx:106-117`（getTokenLabel cross_tab_ref 分支）

- [ ] **Step 1: import summarizeFilter**

在 `FormulaZone.tsx` 顶部 import 区加（路径相对 `components/formula/` → `pages/component/`）：
```ts
import { summarizeFilter } from '../../pages/component/crossTabText';
```

- [ ] **Step 2: getTokenLabel cross_tab_ref 分支追加 filter 摘要**

把第 116 行：
```ts
    return `跨页签[${token.sourceLabel ?? token.source}].${aggLabel}${tgt} 当[${cond}]`;
```
改为：
```ts
    const filterStr = summarizeFilter(token.filter);
    const filterPart = filterStr ? ` 且仅当[${filterStr}]` : '';
    return `跨页签[${token.sourceLabel ?? token.source}].${aggLabel}${tgt} 当[${cond}]${filterPart}`;
```

- [ ] **Step 3: 前端自检**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "FormulaZone=%{http_code}\n" http://localhost:5174/src/components/formula/FormulaZone.tsx
```
Expected: tsc 0 错误；FormulaZone=200。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/components/formula/FormulaZone.tsx
git commit -m "feat(cross-tab-filter): FormulaZone chip shows filter summary"
```

---

## Task 7: 后端 validateFormulas filter 结构校验

P1 在组件级 `validateFormulas` 做**结构校验**（field 非空、op 在枚举、value 非空）。**field 存在性不在此校验**——该方法不持有 source 组件字段（与现状 source/match 字段也不深校验一致；存在性留模板级/运行期容错）。

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java:498-502`
- Test: `cpq-backend/src/test/java/com/cpq/component/service/ComponentServiceCrossTabValidateTest.java`

- [ ] **Step 1: validateFormulas 加 filter 校验**

在 `ComponentService.java` 第 501 行（`throw new BusinessException(400, "跨页签引用缺少目标列或目标公式");` 那条 if 之后）、第 502 行 `}`（for-operand 循环闭合）之前插入：
```java
                Object filterObj = token.get("filter");
                if (filterObj instanceof List<?> fl) {
                    Set<String> okFilterOps = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in");
                    for (Object fo : fl) {
                        if (!(fo instanceof Map)) throw new BusinessException(400, "跨页签筛选条件格式非法");
                        Map<?, ?> fc = (Map<?, ?>) fo;
                        Object fld = fc.get("field");
                        if (fld == null || fld.toString().isBlank())
                            throw new BusinessException(400, "跨页签筛选条件缺少字段(field)");
                        Object fop = fc.get("op");
                        if (fop == null || !okFilterOps.contains(fop.toString()))
                            throw new BusinessException(400, "跨页签筛选条件操作符非法: " + fop);
                        Object fval = fc.get("value");
                        if (fval == null || fval.toString().isBlank())
                            throw new BusinessException(400, "跨页签筛选条件缺少值(value)");
                    }
                }
```

> `Set` 已在文件中使用（第 492 行 `Set.of(...)`），无需新 import。

- [ ] **Step 2: 加 filter 校验单测**

在 `ComponentServiceCrossTabValidateTest.java` 末尾 `}` 之前插入：
```java
    // ------------------------------------------------------------------
    // T12: 合法 filter（field+op+value 齐备）→ 通过
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T12: 合法 filter → 通过不抛")
    void valid_filter_passes() {
        Map<String, Object> token = validToken();
        token.put("filter", List.of(Map.of("field", "类型", "op", "ne", "value", "银点", "logic", "and")));
        assertDoesNotThrow(() ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
    }

    @Test
    @DisplayName("T13: filter 缺 field → 抛含'field'")
    void filter_missing_field_throws() {
        Map<String, Object> token = validToken();
        Map<String, Object> cond = new HashMap<>();
        cond.put("op", "ne");
        cond.put("value", "银点");
        token.put("filter", List.of(cond));
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("field"), "实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("T14: filter op 非法 → 抛含'操作符'")
    void filter_illegal_op_throws() {
        Map<String, Object> token = validToken();
        token.put("filter", List.of(Map.of("field", "类型", "op", "like", "value", "x", "logic", "and")));
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("操作符"), "实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("T15: filter value 空白 → 抛含'值'")
    void filter_blank_value_throws() {
        Map<String, Object> token = validToken();
        token.put("filter", List.of(Map.of("field", "类型", "op", "ne", "value", "  ", "logic", "and")));
        BusinessException ex = assertThrows(BusinessException.class, () ->
                svc.validateFormulas(emptyFields(), formulasWith(token)));
        assertTrue(ex.getMessage().contains("值"), "实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("T16: filter 缺省 / 空列表 → 通过（向后兼容）")
    void filter_absent_or_empty_passes() {
        Map<String, Object> noFilter = validToken();
        assertDoesNotThrow(() -> svc.validateFormulas(emptyFields(), formulasWith(noFilter)));
        Map<String, Object> emptyFilter = validToken();
        emptyFilter.put("filter", new ArrayList<>());
        assertDoesNotThrow(() -> svc.validateFormulas(emptyFields(), formulasWith(emptyFilter)));
    }
```

- [ ] **Step 3: 跑后端校验单测**

Run:
```bash
cd cpq-backend && ./mvnw test -Dtest=ComponentServiceCrossTabValidateTest -q
```
Expected: PASS — T1~T16 全绿。

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java cpq-backend/src/test/java/com/cpq/component/service/ComponentServiceCrossTabValidateTest.java
git commit -m "feat(cross-tab-filter): backend validateFormulas filter structural validation + tests"
```

---

## Task 8: E2E filter 配置路径 + 回归

**Files:**
- Modify: `cpq-frontend/e2e/cross-tab-builder.spec.ts`（加 filter 配置路径，只读验证 UI 与序列化）

- [ ] **Step 1: 看现有 spec 结构，照样式加一条 filter 用例**

先读现有文件了解选择器约定：
```bash
sed -n '1,80p' cpq-frontend/e2e/cross-tab-builder.spec.ts
```

- [ ] **Step 2: 加 filter 配置 E2E 用例**

在 `cross-tab-builder.spec.ts` 末尾、最后一个 `test(...)` 之后、闭合前加（按文件实际打开抽屉的 helper/选择器调整；以下为骨架，需对齐文件既有 `getByText('过滤条件（可选）')` 等中文选择器与打开抽屉步骤）：
```ts
test('过滤条件区块可配置并写入预览', async ({ page }) => {
  // —— 复用本文件已有的"打开 CrossTabRefDrawer + 选源 + 选匹配列"前置步骤 ——
  // （把前面 test 里打开抽屉、选 source、填 match 的步骤抽出或复制到此处）

  // 过滤条件区块存在
  await expect(page.getByText('过滤条件（可选）')).toBeVisible();

  // 添加一条 filter：A.类型 ≠ 银点
  await page.getByRole('button', { name: '添加条件' }).click();
  // 选 A.字段（下拉项文案取决于字段 label/name，按你的种子数据替换"类型"）
  // 选操作符"不等于"
  await page.getByText('不等于').click();
  // 填值
  await page.getByPlaceholder('值').fill('银点');

  // 预览出现"且仅当[...≠银点]"
  await expect(page.getByText(/且仅当\[.*≠银点/)).toBeVisible();
});
```

> 若 `cross-tab-builder.spec.ts` 是数据库种子驱动且没有简单"添加条件"按钮可达路径，则改为最小验证："打开抽屉 → 过滤条件区块标题可见 → 添加条件按钮存在"，避免脆弱断言。E2E 这里只证 UI 配置路径活着，求值正确性已由夹具双跑锁定。

- [ ] **Step 3: 跑该 spec**

Run:
```bash
cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/cross-tab-builder.spec.ts --reporter=list
```
Expected: 该 spec 全 `passed`。

- [ ] **Step 4: 跑报价单回归 E2E（加载中=0）**

Run:
```bash
cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`，`'加载中' final count = 0`，8 Tab `'加载中'=0`（证明 filter 改动未破坏现有渲染链路）。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/e2e/cross-tab-builder.spec.ts
git commit -m "test(cross-tab-filter): E2E filter config path + quotation-flow regression"
```

---

## Task 9: 文档回写（AP-55）+ RECORD + 最终自检

**Files:**
- Modify: `docs/反模式.md`（AP-55 追加 filter 小节）
- Modify: `docs/RECORD.md`（开发记录）

- [ ] **Step 1: AP-55 追加 filter 传播点小节**

在 `docs/反模式.md` 的 AP-55 段落里追加：
```markdown
**[2026-06-09] filter (SUMIF/COUNTIF) 扩展** — cross_tab_ref token 加可选 `filter:[{field,op,value,logic}]`，
match 后聚合前过滤 A 行。8 处传播点：
1. `types.ts` FormulaToken += filter? + CrossTabFilterCond
2. `formulaEngine.ts` passCrossTabFilter + hits 后插入
3. `FormulaCalculator.java` evalCrossTab hits 后 + passCrossTabFilter/testCrossTabCond（**逐字镜像前端，不走 JEXL**）
4. `CrossTabRefDrawer.tsx` 过滤条件 UI + filterRows + buildTokenLike
5. `crossTabText.ts` serialize/parse 「筛选:」段 + summarizeFilter/OP_TO_SYM
6. `FormulaZone.tsx` getTokenLabel filter 摘要
7. `ComponentService.validateFormulas` filter 结构校验（field 存在性不校验）
8. `cross-tab-cases.json`（两份）+ E2E
**头号风险=双引擎对等**：折叠规则（左到右等优先级）+ 比较规则（数值/字符串/空值/in）必须前后端逐字一致，
唯一锁=共享夹具 26 例双跑。改任一侧必须同步两份夹具并 `FormulaCalculatorCrossTabFixtureTest` + vitest 双跑。
P1 范围：RHS 仅字面量（b_col 推后）；多条件无括号分组。
```

- [ ] **Step 2: RECORD.md 追加开发记录**

在 `docs/RECORD.md` 末尾追加：
```markdown
[2026-06-09] cross_tab_ref filter (SUMIF/COUNTIF) - token 加 filter 字段，match 后聚合前过滤 A 行 | types.ts / formulaEngine.ts / FormulaCalculator.java / CrossTabRefDrawer.tsx / crossTabText.ts / FormulaZone.tsx / ComponentService.java / cross-tab-cases.json(×2) | 双引擎逐字镜像，共享夹具 26 例双跑锁对等；后端不走 JEXL；filter 缺省=零迁移向后兼容；AP-55
```

- [ ] **Step 3: 全量自检**

Run（前端）:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json && npx vitest run src/utils/formulaEngine.test.ts src/pages/component/crossTabText.test.ts
```
Expected: tsc 0 错误；前端单测全 PASS。

Run（后端）:
```bash
cd cpq-backend && ./mvnw test -Dtest=FormulaCalculatorCrossTabFixtureTest,ComponentServiceCrossTabValidateTest -q
```
Expected: 后端两测全 PASS（夹具 26 例对等 + 校验 16 例）。

- [ ] **Step 4: Commit**

```bash
git add docs/反模式.md docs/RECORD.md
git commit -m "docs(cross-tab-filter): AP-55 filter propagation points + RECORD"
```

---

## Self-Review（计划自检，已执行）

**1. Spec 覆盖（design §1-§9）:**
- §3.1 token filter 字段 → Task 2 Step 1 ✅
- §3.2 求值语义（折叠+比较+空值+in） → Task 2 Step 2（前端）+ Task 3 Step 2（后端镜像）+ 夹具 Task 1 ✅
- §3.3/§3.4 前后端实现 → Task 2/3 ✅
- §4 校验 → Task 7 ✅
- §5 UI（区块+state+预览+原始文本双向+FormulaZone chip） → Task 5 + Task 4（serialize/parse）+ Task 6 ✅
- §6 范围裁剪（b_col/全局变量 RHS/括号推后） → 已按用户拍板写入约定，不实现 ✅
- §7 测试（夹具≥5例双跑 + passCrossTabFilter单测 + round-trip + E2E + 回归） → Task 1(10例)/2/4/8 ✅
- §8 向后兼容（filter 空=同结果） → 夹具"filter 空数组"用例 + Task 7 T16 ✅
- §9 AP-55 8 处传播点 → 全部有 Task，文档 Task 9 ✅

**2. Placeholder 扫描:** Task 8 E2E 用例标注了"按文件既有选择器对齐"，给了骨架 + 降级方案（非空泛 TODO），因 E2E 选择器依赖实际种子数据与文件现状，执行时需读文件对齐——这是合理的现场适配，非占位。其余步骤均含完整可粘贴代码。

**3. 类型一致性:** `CrossTabFilterCond{field,op,value,logic}` 在 types.ts 定义，formulaEngine.ts/crossTabText.ts/CrossTabRefDrawer.tsx 全 import 同一类型；`passCrossTabFilter`/`testCrossTabCond` 前后端同名；`OP_TO_SYM`/`summarizeFilter` 从 crossTabText 单点导出，CrossTabRefDrawer 与 FormulaZone 复用（DRY）。后端 `passCrossTabFilter`/`testCrossTabCond` 私有方法名与前端一致便于对照。✅

**关键风险提示（执行者必读）:** Task 2/3 的双引擎逻辑**必须逐字对齐**——任何一侧改了比较/折叠规则，Task 1 的共享夹具会立刻在另一侧暴露失败。**永远不要通过改 expected 值来"修复"夹具**，那是在掩盖引擎分歧（见后端夹具测试类注释）。
