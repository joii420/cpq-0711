# formula-golden — 双端公式一致性黄金用例集（task-0729 B9 L1）

> ---
> ## ✅ 已兑现（2026-08-03，随 `merge master` 带入 task-0801/0803）
>
> 下面这段警示曾经预埋、现已兑现，**保留作历史记录，不删**——下次再遇到"两个分支各自独立演进导致
> 口径分歧"的场景，这是一份完整的活教材（从"预判风险→提前写警示→按未经验证的转述改一次→实测证伪
> 撤回→分支真合并后再次实测确认→正式改定"，全过程可追溯）。
>
> **实际兑现情况**：merge master 时前端已把 master 侧 `precision.ts` 的 `ArithDecimalParser`
> 原样复制到隔离脚本**实测**（不是照抄代码猜行为），确认 `evaluateArithmetic()` 与后端新
> `PrecisionPolicy`（`DIVISION_SCALE=12`、出口不再统一舍入）语义一致；`formulaEngine.ts` 的
> `evaluateExpression` 冲突块整体采用 master 侧（详见 `merge-master-前端冲突预案.md` §1）；
> `dec-001`/`dec-002`/`dec-004` 三条 `expected` 已改回 12 位精度系列值
> （`3.333333333333`/`0.333333333333`/`2.00005`），`expectedSource` 均为 `frontend-engine`
> （前端引擎合并后实测值，非猜测）。合并后重跑 `npx vitest run src/utils/formulaGolden.test.ts`
> 确认这三条仍然通过（数值来源从"本分支临时口径"切换为"两分支合一后的正式口径"）。
>
> <details>
> <summary>点击展开：原始警示全文（预埋时的措辞，未做任何删改）</summary>
>
> **`task-0801`（commit `f8278099` "公式计算精度优化"，目前是独立分支，未合并进本分支）一旦合并
> 进来，会把后端 `FormulaCalculator.evaluateExpression` 从"除法/结果统一 `setScale(4, HALF_UP)`"
> 改成"计算精度与呈现精度分离"（除法中间精度 12 位 HALF_UP，不在函数出口舍入，展示精度 6 位另在
> 落库/API/显示/导出边界统一规整）。**
>
> **合并 `task-0801` 的人，必须在同一次改动里做两件事，否则本目录 `10-decimal-precision.json` 的
> `dec-001`/`dec-002`/`dec-004` 三条用例会立刻变红**（这不是 bug，是这套黄金用例机制故意设计成
> "任一端漂移就当场报警"，**不要把变红当成"测试坏了"去改 `expected` 绕过**，而要按下面两步走）：
>
> 1. **同步更新前端 `cpq-frontend/src/utils/formulaEngine.ts` 的精度策略**，让它跟后端新的
>    `PrecisionPolicy`（12 位除法中间精度、只在展示层收敛）保持一致，不要让前端继续在
>    `evaluateExpression` 末尾对所有结果无条件 `.toDecimalPlaces(4)`；
> 2. **把 `dec-001`/`dec-002`/`dec-004` 三条用例的 `expected` 改回 12 位精度系列值**
>    （`dec-001: 3.333333333333`、`dec-002: 0.333333333333`、`dec-004: 2.00005`，历史上这三个
>    值曾经写过又被撤销过，具体数值推导见各条 `notes` 字段）。
>
> **背景（2026-08-02~03 实测教训，别重蹈覆辙）**：这三条用例最早就是按"task-0801 已经把后端改成
> 12 位"这个**未经验证的转述**写的 12 位 `expected`；后来直接在本分支上跑
> `cd cpq-backend && ./mvnw test -Dtest=FormulaGoldenTest` 实测才发现 `task-0801`
> **根本没合并进来**——`FormulaCalculator.java` 当前仍是 4 位，仓库里也没有 `PrecisionPolicy.java`
> 这个类。于是业务方裁定把 `expected` 改回 4 位（本分支当下前后端本来就一致），但这不是"问题解决
> 了"，只是"暂时不适用"——`task-0801` 迟早要合并，到那天这三条用例必须跟着一起改，不能让它们变成
> 一个长期挂红却没人管的"狼来了"信号。**任何人合并/审查 `task-0801` 相关 PR 时，看到这段警示就该
> 立刻联动这两处改动一起提交，不要拆成两次 PR（拆开会有一段时间线上是"后端已 12 位、前端还 4 位、
> 用例还挂着旧 expected"的三方不一致窗口期）。**
>
> </details>
> ---

## 契约（不可违反）

1. **前端 vitest 与后端 JUnit 读同一份文件**——`cpq-frontend/src/pages/quotation/formulaEngine.test.ts`
   与 `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaGoldenTest.java` 都从仓库根
   `formula-golden/*.json` 加载用例，不是各自维护副本。各自维护副本会退化回"两边都绿但从未验证
   同一输入"的现状（本任务立项时实测：前端 `formulaEngine.test.ts`756 行 / 后端
   `FormulaCalculatorTest.java`465 行，零共享用例）。

2. **`expected` 由前端引擎产出，后端必须命中**——前端 `formulaEngine.ts` 是当前真正在给客户报价
   的那套，它是基准，不是后端的镜子。

3. **反向验证是核心断言，不是可选项**：改错后端某处口径（如 `component_subtotal` 的列小计键拼接
   逻辑）→ 对应黄金用例必须变红，证明这套测试有拦截力。参见 B4（`ComparisonColumnEvaluator` AMBER
   比较器）、B6（Jackson `isProductTotal` 序列化 bug）两次实操记录（`docs/RECORD.md` task-0729 相
   关条目）。

## 文件结构

每个 `NN-<category>.json` 文件是一个 JSON 数组，元素 shape：

```jsonc
{
  "id": "arith-001",                  // 全局唯一
  "category": "四则运算",              // 中文类目名，对应 backtask 10 类
  "description": "基础四则+优先级+括号",
  "tokens": [ /* formulaEngine token 数组，与 FormulaCalculator.evaluateExpression 输入同形 */ ],
  "context": {                        // 求值上下文，字段名对齐 FormulaCalculator.RowContext
    "componentSubtotals": {},
    "productAttributes": {},
    "basicDataValues": {},
    "quotationFields": {},
    "crossTabRows": {},
    "currentRowRaw": {},
    "previousRowSubtotal": null
  },
  "expected": "17.0000",              // 4 位小数字符串；null = 待前端引擎产出（見下）
  "expectedSource": "manual-computed" // manual-computed | frontend-engine | pending
                                       // manual-computed：本次由后端工程师手工推导验证（可复核）
                                       // frontend-engine：由前端 formulaEngine.ts 实际跑出（权威）
                                       // pending：占位，尚未有权威来源，两端测试都应 SKIP 不是 FAIL
  "notes": "可选：口径说明/已知边界"
}
```

## 现状（2026-08-03，本次交付边界）

| 类目 | 文件 | expectedSource | 说明 |
|---|---|---|---|
| 四则/优先级/括号 | `01-arithmetic.json` | manual-computed | 纯算术，客观可验证，与引擎实现无关 |
| `component_subtotal` 一阶 | `02-component-subtotal-l1.json` | manual-computed | 直接取列小计键，逻辑简单可手工核验 |
| `component_subtotal` 二阶 | `03-component-subtotal-l2.json` | manual-computed | 两个一阶小计相加，同样可手工核验 |
| `__amount_total__` 页签总计 | `04-amount-total.json` | manual-computed | 特殊列小计键 `<code>#__amount_total__`，取值逻辑与普通列小计一致 |
| `product_attribute` | `05-product-attribute.json` | manual-computed | 直接查表相乘，可手工核验 |
| `cross_tab_ref` | `06-cross-tab-ref.json` | manual-computed | KSUM 单一场景手工推导；**未覆盖 KAVG/KMAX/KMIN 空集特殊分支、多 source 广播——留给前端补充** |
| `global_variable` | `07-global-variable.json` | manual-computed | 直接查 `basicDataValues["@gvar:CODE"]`，可手工核验 |
| 单位换算 | `08-unit-conversion.json` | manual-computed | 按固定换算系数相乘/相除，可手工核验 |
| 空值/NULL/除零 | `09-null-divzero.json` | manual-computed | 按 `FormulaCalculator` 文档化行为（缺值→0、除零→0）验证 |
| 小数精度 | `10-decimal-precision.json` | manual-computed | ⚠️ 见下方"重要发现"——实测已不是"4 位 HALF_UP" |

## ⚠️ 重要发现（2026-08，跑用例时实测暴露，非本任务改动）

编写 `10-decimal-precision.json` 时按 `FormulaCalculator.java` 类注释（第25行："4 位小数
HALF_UP；缺值/解析异常/除零 → 0"）手推 `expected`，跑起来 3/4 用例失败（`dec-001`/`dec-002`/
`dec-004`）。定位后发现：**该类注释已过期**——`task-0801`（并发的另一个任务）已把
`evaluateExpression` 改为"计算精度与呈现精度分离"：内部不再在返回前 `setScale(4, HALF_UP)`，
除法中间精度改用 `PrecisionPolicy.DIVISION_SCALE=12` 位 HALF_UP，其余运算不做任何舍入；最终
对外展示精度（6 位，`PrecisionPolicy.DISPLAY_SCALE`）由调用方在落库/API/显示/导出四个边界
统一规整，不是 `evaluateExpression` 自己的职责（详见 `PrecisionPolicy.java` 类注释）。

**已按实测行为更正 `10-decimal-precision.json` 的 4 条 `expected`**（不是掩盖，是让
`manual-computed` 名副其实——它必须反映后端真实当前行为，不是过期文档）。`FormulaCalculator.java`
第25行类注释本身未同步更新，但那是 `task-0801` 负责维护的并发文件，不在本任务(task-0729)范围内
修正，此处仅记录发现供后续跟进。

**对本任务(B9)目标的影响，需要前端配合确认的点**：如果前端 `formulaEngine.ts` 的除法/精度行为
仍是"算完就近似到 4 位小数"（很可能，因为前端历史文档一直这么写），那么对**除法密集型公式**，
前后端在补 `expectedSource: frontend-engine` 时可能会出现真实的数值不一致（如 `10/3`：后端现在
产出 12 位精度的 `3.333333333333`，前端如果仍按 4 位近似会产出 `3.3333`）——这正是 B9"两端一致性
黄金用例"要捕获的信号，**不代表用例写错**，请前端补值时特别关注这 4 条用例（`dec-001/002/003/004`）
是否与后端当前值一致；若不一致，需要澄清"最终一致性应该在哪一层达成"（是 `evaluateExpression`
内部对齐，还是各自展示层都统一规整到同一 `DISPLAY_SCALE` 后再比较）。

**本次交付的 `manual-computed` 用例是后端工程师按 `FormulaCalculator.java` 现有文档化行为独立推导
的（不是跑后端引擎反推），可作为"后端是否至少自洽"的第一道门槛，但按契约第 2 条，
**权威来源仍是前端引擎**——请前端在补 `expectedSource: frontend-engine` 时，任何与本文件现有值
不一致的地方都要修正 `expected` 并改 `expectedSource`，不一致本身就是一个需要澄清的信号（可能是
后端手工推导有误，也可能是两端算法本就存在偏差，后者更需要被这套用例捕获）。**

## 两端读取方式

- 后端：`FormulaGoldenTest.java`，`@TestFactory` 动态从每个 JSON 文件生成用例，`expectedSource
  in {manual-computed, frontend-engine}` 才断言，`pending` 显式 SKIP（不静默通过、不误报失败）。
- 前端：`cpq-frontend/src/utils/formulaGolden.test.ts`（2026-08-02 交付）。同样动态从每个 JSON
  文件生成 vitest 用例，用 `evaluateExpression`（`formulaEngine.ts`）按 `context` 映射位置参数
  求值，`expectedSource=pending` 用 `it.skip` 跳过。执行：`npx vitest run
  src/utils/formulaGolden.test.ts`（本仓库没有裸 `npm test`，`npx vitest run` 不带路径会把
  `e2e/*.spec.ts` 一起扫进来导致大量 Playwright 误报——必须显式指定路径）。

## 前端验证结果（2026-08-03 终态）—— 31/33 一致，2 条已知理论分歧留红（0 生产影响，不修）

跑通后 26 条与 `manual-computed` 逐位吻合，已把这 26 条的 `expectedSource` 改成
`frontend-engine`（**数值未改，只改来源标记**——确认前端引擎独立算出同一个值）。

**最初 7 条不一致**（上面"两端读取方式"旧版说"不一致就改 expected"是本次交付前的预案；实际执行
时改为**如实报告、留红、交人工裁定**，不单方面改 `expected` 迁就任何一端）。coordinator 呈报业务
方后分三批裁定：

| id | 前端实际 | 根因 | 状态 |
|---|---|---|---|
| `dec-001` | `3.3333` | 前端 `evaluateExpression` 末尾 `.toDecimalPlaces(4)` | ✅ **`expected` 已改回 `3.3333`（见文首 🚨 大段警示）**——本分支后端实测也是 4 位（`task-0801` 未合并），两端当下一致 |
| `dec-002` | `0.3333` | 同上 | ✅ 同上，`expected=0.3333` |
| `dec-004` | `2.0001` | 同上 | ✅ 同上，`expected=2.0001` |
| `amt-002` | `0` | `component_subtotal` token 只有 `tab_name`（无 `component_code`）时，前端取值链缺一段"`${tab_name}#${value}` 二段式列小计键"的查找——只有 `component_code` 变体有这段逻辑。后端 `FormulaCalculator.java:133-135` 注释写"与前端 1:1 对齐"且**已经实现**了 tab_name 版复合键，前端实际没有，注释描述与代码不符 | 🟡 **留红，不修**——已审计生产配置（全库 207 处 `component_subtotal` token 无一使用 tab_name-only 变体，0 生产影响），业务方裁定确认这是当前 0 影响的理论缺口，降为低优先级，`expected`/`expectedSource` 原样不动（仍是 `manual-computed`），详见 `前端精度对齐影响面清单.md` §5 |
| `amt-003` | `88.88` | 同 `amt-002` 根因 | 🟡 同上 |
| `nz-001` | ~~`Infinity`~~ → `0` | 前端末尾 `new Decimal(fn())` 对 `Infinity` 不抛异常（不会被 `catch{return 0}` 兜住），直接把 `Infinity` 传播出去 | ✅ **已修复**（`evaluateExpression` 求值后加 `!Number.isFinite(raw)→0` 判断，与后端 `Double.isInfinite()→0` 对齐） |
| `nz-002` | ~~`NaN`~~ → `0` | 同上，`NaN` 同样不被 `catch` 拦下 | ✅ **已修复** |

**`dec-*` 三条的历史反复**（教训沉淀，别重蹈覆辙）：最早按"`task-0801` 已把后端改成 12 位"这个
**未经验证的转述**写 12 位 `expected`；coordinator 直接在本分支跑
`cd cpq-backend && ./mvnw test -Dtest=FormulaGoldenTest` 实测才发现 `task-0801`（commit
`f8278099`）**根本没合并进本分支**——`FormulaCalculator.java` 当前仍是 4 位 `setScale(4, HALF_UP)`，
仓库里也没有 `PrecisionPolicy.java`。业务方据此裁定：本分支前后端当下本来就一致（都是 4 位），
`expected` 改回 4 位，**33/33 里的这 3 条恢复绿**（`amt-002/003` 是另一个独立、业务方已裁定"不修
不追"的分歧，不计入这次改动范围，故整体是 31/33 而非 33/33——详见本文件末尾 commit 历史，两批裁定
分两次提交）。`task-0801` 真正合并进来时的处理方式见文首 🚨 警示。

**影响评估（仅供参考，非本次改动范围）**：`nz-001/002` 的 `Infinity`/`NaN` 在**渲染层**被
`formatNumber.ts:33 if (!d.isFinite()) return null` 兜底显示成"—"，终端用户看不到字面
"Infinity"文本；但 `evaluateExpression` 返回给调用方的**原始数值**仍是 `Infinity`/`NaN`，若被
其它公式当 `fieldValues`/`componentSubtotals` 继续参与运算（如 `Infinity + x = Infinity` 会
向上游传染，或 `JSON.stringify(Infinity)` 落库时被静默转成 `null`），风险面比渲染层看到的更大，
后端在**求值源头**就归零，防护点更靠前（已修复，见上表）。`amt-002/003` 的复合键缺口生产配置
排查结果见上表 + `前端精度对齐影响面清单.md` §5（全库 0 处使用，已排查完毕，非"待排查"）。
