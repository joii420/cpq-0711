# formula-golden — 双端公式一致性黄金用例集（task-0729 B9 L1）

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
- 前端：待协调（本次由后端工程师建骨架，前端后续接入 `formulaEngine.test.ts`）。
