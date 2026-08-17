# merge master · 前端冲突预案

> 产出日期：2026-08-03 · 前端工程师（只读研究，未执行 `git merge`，未改任何代码文件）
> 研究方法：`git diff <merge-base>..HEAD -- <file>` / `git diff <merge-base>..master -- <file>` 逐文件核对改动区域是否重叠
> **+ `git merge-tree --write-tree HEAD master`**（git 2.38+ 的只读虚拟合并，不碰工作区/HEAD/索引，不产生 commit）拿到**权威的真实冲突文件清单**，而不是靠肉眼猜测哪些 hunk 会撞车。
> merge-base：`fe9c1f87ef113e6b176a747c04f7790f47ea0a21`（`git merge-base master HEAD`）
> 验证过 `git merge-tree` 全程未修改工作区/HEAD（命令后立即 `git status --short` / `git rev-parse HEAD` 复核，输出只有并发后端会话的未提交改动，与本命令无关）。

---

## 0. 权威结论先行：13 个双方都改的文件里，只有 2 个真冲突

```
$ git merge-tree --write-tree HEAD master
CONFLICT (content): Merge conflict in cpq-backend/.../CardSnapshotService.java   ← 后端，backend 自己在修
CONFLICT (content): Merge conflict in cpq-frontend/src/pages/quotation/QuotationWizard.tsx   ← 前端①（见 §3）
CONFLICT (content): Merge conflict in cpq-frontend/src/utils/formulaEngine.ts    ← 前端②（见 §1）
CONFLICT (content): Merge conflict in docs/RECORD.md                             ← 记录文件，两边都在末尾追加（见 §5）
```

其余 9 个（含前端 6 个：`ComponentCell.tsx` / `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `enrichComponentData.ts` / `ComponentManagement.tsx` / `component/types.ts`）**全部 `Auto-merging`，零冲突标记**。本文档 §3 逐一核对了这 6 个文件"自动合并成功"背后有没有语义层面的隐患（文本不冲突≠语义安全，这是 git 合并的已知盲区），结论：**都安全**，改动区域在原始文件里彻底不相交，附精确行号证据。

---

## 1. 🔴 `formulaEngine.ts` 合并方案

### 1.1 双方各改了哪里

**我们（`feat/task-0729-price-adjust`）**：唯一改动是 `evaluateExpression` 函数末尾——在 `new Function(...)` 求值出原始结果后、包 `Decimal` 之前，插入一段 `!Number.isFinite(raw)` 判断，非有限数（`Infinity`/`NaN`）归零（task-0729 B9 nz-001/nz-002 修复）。全文件只有这一处改动，12 行插入。

**master（含 task-0801「精度分离」+ task-0803「BOM 树父子取值」两批改动）**：
1. **整体架构级重写**：`evaluateExpression` 不再用 `new Function()` + `Decimal` 包装 + `.toDecimalPlaces(4)`，改为调用新文件 `cpq-frontend/src/utils/precision.ts` 里的 `evaluateArithmetic(expr)`——一个自研的十进制精确递归下降解析器（`ArithDecimalParser`），不再 `eval` 任意 JS（更安全），且**不再在出口统一舍入到 4 位**（除法内部按 `DIVISION_SCALE=12` 位 HALF_UP 截断，其余运算精确，最终精度收敛推迟到"落库/API/显示/导出"四个边界，由调用方处理）。
2. `ExpressionToken` 新增 `tree_ref` / `tree_attr` 两个 token 类型（task-0803 BOM 树父子取值：PGET/C* 族/LVL/IS_LEAF/IS_ROOT），新增 `TreeEvalContext` 接口 + `evalTreeRefToken`/`evalTreeAttrToken` 两个约 130 行的求值函数，`evaluateExpression` 签名末尾新增一个可选参数 `treeCtx?: TreeEvalContext`。
3. `evaluateListFormulaString` 尾部同款重写（也改调 `evaluateArithmetic`）。

### 1.2 有没有重叠——有，且刚好卡在我们改的那一行

**重叠范围极小、极精确**：只有 `evaluateExpression` 函数的**最后 5~18 行**（原来的 `try { new Function… } catch { return 0 }` 整个 try/catch 块）——因为这正是我们插入 `Number.isFinite` 判断的那个块，也正是 master 把它整段替换成 `evaluateArithmetic(expr)` 调用的那个块。**两边改的是同一段原始代码，`git merge-tree` 精确定位到这一处，产出的冲突内容如下**（已用 `git show <virtual-merge-tree>:cpq-frontend/src/utils/formulaEngine.ts` 取出，未落盘到任何真实文件）：

```ts
<<<<<<< HEAD
  try {
    const fn = new Function(`return (${expr})`);
    const raw = fn();
    // task-0729 B9 nz-001/nz-002 修复...
    if (typeof raw !== 'number' || !Number.isFinite(raw)) {
      return 0;
    }
    const result = new Decimal(raw);
    return result.toDecimalPlaces(4).toNumber();
  } catch {
    return 0;
  }
=======
  // task-0801：十进制精确求值，替代 new Function...
  const result = evaluateArithmetic(expr);
  return result === null ? 0 : result.toNumber();
>>>>>>> master
```

除这一处外，`TreeEvalContext`/`evalTreeRefToken`/`evalTreeAttrToken`/新增的 `treeCtx` 参数、`evaluateListFormulaString` 尾部改动**全部是 master 独有的新增代码块，落在文件的完全不同区域**（`hasPathToken` 函数之前、`evaluateExpression` 参数列表末尾、`evaluateListFormulaString` 尾部），跟我们那 12 行插入毫无交集，`git merge-tree` 全部判定 `Auto-merging` 成功（只有上面这一处被标记冲突）。

### 1.3 合并后应该长什么样——**直接采用 master 那一侧，我们的补丁整体作废**

不是"各取一半""手工缝合"，而是**结论性判断：我们的 `Number.isFinite` 判断在合并后是死代码/不需要的代码，因为 master 的新实现已经在更底层、更彻底地解决了同一个问题**。用 master 那份 `precision.ts` 的源码逐字核实（已用 `git show master:cpq-frontend/src/utils/precision.ts` 读取原文，并且**没有停在读代码——把 `ArithDecimalParser` 原样复制到一个隔离的临时脚本里用 Node 实测跑了一遍**，不是"读代码猜行为"）：

```ts
// precision.ts · ArithDecimalParser.term()
if (c === '*') {
  v = v.times(r);
} else {
  // 除以 0 → 0（不抛异常，G-9）；否则按 DIVISION_SCALE 中间精度截断
  v = r.isZero()
    ? new Decimal(0)
    : v.dividedBy(r).toDecimalPlaces(DIVISION_SCALE, Decimal.ROUND_HALF_UP);
}
```

实测结果（隔离脚本，`node verify-precision.mjs`，用的就是 `precision.ts` 里原封不动抄出来的 `ArithDecimalParser`）：

| 表达式 | 结果 | 说明 |
|---|---|---|
| `5/0` | `0` | 除数为 0 时**在除法这一步就短路返回 `Decimal(0)`，根本不会产生 `Infinity`** |
| `0/0` | `0` | 同上——判断的是 `r.isZero()`（除数是否为 0），跟被除数无关，**不会产生 `NaN`** |

且 `evaluateArithmetic` 出口还有第二道防线：`return v.isFinite() ? v : new Decimal(0);`——即使某个未来场景（现在想不出具体是哪个）在解析过程中产出了非有限值，出口也会兜底归零。**我们当初加的 `!Number.isFinite(raw)` 判断，在语义上被这两道防线完全覆盖，不存在"master 漏了、我们的判断还有价值"的缝隙。**

**推荐的合并动作（给实际执行 `git merge` 的人）**：
1. 冲突块整体采用 `master` 一侧（`<<<<<<< HEAD` 到 `=======` 之间的内容全部删除，只留 `=======` 到 `>>>>>>> master` 之间的 3 行）。
2. 顺手删掉那段现在已经不对的注释残留（如果编辑器/合并工具没有自动清理 `<<<<<<<`/`=======`/`>>>>>>>` 标记本身，记得手工清）。
3. **合并后必须重新跑 `formula-golden` 黄金用例**（`npx vitest run src/utils/formulaGolden.test.ts` + 对应后端 `FormulaGoldenTest`）验证 `nz-001`/`nz-002` 仍然是 `0`（应该会继续通过，因为 master 的新实现本就覆盖了这两个场景——上面的实测已经验证过，但"合并后再跑一遍黄金用例"仍然是不能省的动作，防止我这次隔离脚本跟 master 真实代码之间有任何我没注意到的细节差异）。
4. `formulaEngine.ts` 顶部 `import Decimal from 'decimal.js';` 这一行：master 已经删除（改成 `import { evaluateArithmetic } from './precision';`），我们没碰过这一行，不会有冲突，合并结果应该沿用 master 删除后的版本（即最终文件里没有裸的 `Decimal` 导入，`Decimal` 只在 `precision.ts` 内部使用）。

---

## 2. 🔒 黄金用例 `dec-001`/`dec-002`/`dec-004` 的新 `expected`

`formula-golden/README.md` 顶部那条警示写的两件事——①更新前端精度策略、②更新这 3 条 expected——第①件事**在 master 里已经由 task-0801/0803 做完了**（不是我们要做的事，`evaluateArithmetic` 就是新策略本身）；第②件事是我们这次要交的。

**推导依据（不是拍脑袋，是把 `precision.ts` 的 `ArithDecimalParser` 原样搬进隔离脚本跑出来的真实结果，见上一节同一份实测输出）**：

| id | 表达式 | 新 `expected` | 推导依据 |
|---|---|---|---|
| `dec-001` | `10/3` | **`3.333333333333`**（12 个 3） | 除法走 `DIVISION_SCALE=12` 位 `HALF_UP`。`10÷3=3.333333333333333…`（3 循环），截到第 12 位小数是 `3.333333333333`，第 13 位仍是 `3`（<5），`HALF_UP` 不进位。实测输出：`3.333333333333` |
| `dec-002` | `1/3` | **`0.333333333333`**（12 个 3） | 同上算法，`1÷3=0.333333333333333…`，截到 12 位是 `0.333333333333`。实测输出一致 |
| `dec-004` | `2.00005+0` | **`2.00005`**（不舍入，原样保留 5 位小数） | 加法不经过 `term()` 的除法分支，`Decimal.plus()` 是精确十进制运算，且出口不再做任何统一舍入（`evaluateExpression` 现在只调用一次 `evaluateArithmetic` 后直接 `.toNumber()`，没有第二层 `.toDecimalPlaces(4)`）。实测输出：`2.00005` |
| `dec-003` | `0.1+0.2` | **不变，仍是 `0.3000`**（数值上等价于 `0.3`） | 纯加法、十进制精确，不受精度策略变化影响，本条**不需要改**——列在这里只是确认它不受影响，避免有人合并时误以为 4 条都要动 |

**这 3 条新值恰好和 `formula-golden/10-decimal-precision.json` 文件历史上最早的版本（backend 工程师按"假设 task-0801 已合并"写的那版）逐字相同**——当时是"猜对了目标值、猜错了时序"（task-0801 那时还没合并，我们据此把 `expected` 改回了 4 位，见 `docs/RECORD.md` 2026-08-03 条目）。现在时序对齐了，改回 12 位系列值即是把之前的"以讹传讹"教训闭环。

**合并时需要同步做的两件事（不在本文档做，留给合并动作 + 后续验证一起完成）**：
1. `formula-golden/10-decimal-precision.json` 的 `dec-001`/`dec-002`/`dec-004` 三条：`expected` 改回上表新值，`expectedSource` 改回 `frontend-engine`（重新验证过），`description`/`notes` 字段里"本分支前后端当前均为 4 位"那句陈述需要同步改成"已对齐 task-0801/0803 的 12 位精度策略"。
2. `formula-golden/README.md` 顶部那段警示可以整体**划掉/归档**（改成"已完成"状态说明，别删——留作历史记录，未来再有类似"两分支各自演进导致口径分歧"的情况，这段是活教材）。

---

## 3. 其余 7 个前端文件冲突性质速查

| 文件 | 冲突性质 | 风险等级 | 依据 |
|---|---|---|---|
| **`QuotationWizard.tsx`** | ⚠️ **有冲突标记，但是最简单的一种**——"我们新增 vs 他们新增，且刚好挤在同一行"。两边各自在 import 区块**最后一行**（`RowKeyConflictDrawer` 那行）后面加了一条新 import：我们加 `QuotationPriceRevisionsDrawer`（屏 7 价格版本抽屉），master 加 `normalizeNumber, toDecimal, roundToDisplay`（`precision.ts`）+ `formatNumber`。git 因为两边都锚定在同一行之后而报冲突，**语义上零重叠**——我们改的是"新增一个价格版本查看入口按钮+抽屉"（`priceRevisionsOpen` state + 一个按钮 + 一个 `<QuotationPriceRevisionsDrawer>` 渲染，全部在文件末尾附近独立代码块），master 改的是 `normalizeDraftPayloadNumbers` 函数内部换成新精度工具函数。**两处改动的函数/JSX 区域完全不同，逐行核对过（见下方证据）。** | 🟢 低 —— 冲突解法：两条 import 都保留（顺序随意），文件其余部分自动合并，不需要人工比对逻辑 |
| **`formulaEngine.ts`** | 🔴 见 §1 | 🔴 高（唯一真正需要理解语义才能合并的文件），但**结论明确**：整段采用 master |
| **`ComponentCell.tsx`** | ✅ 无冲突标记（`Auto-merging` 成功）。我们新增 3 个独立代码块（`CellContext.priceLocked`/`priceVersionNo` 类型声明 12 行、解构赋值 2 行、"请先填写元素"占位 + 价格锁定徽标渲染两个 if 分支 39 行，全部插在 `isEmpty` 声明之后、`readonly=true` 分支之前）；master 删掉了 `import { resolveInputDefault }` 那一行 + `readonly` 分支内部一段 default_source 兜底解析逻辑（"快照即权威"改造，2026-08-03）。**我们插入点严格在 master 删除区域之前，且我们的两个新分支都是 `return` 语句提前退出、完全不调用 `resolveInputDefault`**——不依赖被删的那段逻辑，零语义耦合。 | 🟢 低 —— 已核对具体行号：我们改动锚定在原始文件 585-605 行区间，master 删除的是 590-605 行区间*内部*的 `if (isNumber \|\| ...) {...}` 子块；两者共享的唯一锚点是 `// readonly=true: 只读文本渲染` 这行注释，我们在它之前插入、master 在它之后插入，git 能正确交织 |
| **`QuotationStep2.tsx`** | ✅ 无冲突标记。我们的改动只有 2 处：`ComponentDataItem` 接口新增 `elementCodeField`/`elementPriceField`/`elementCurrencyField` 三个可选字段（原始文件第 148 行附近）+ `cellCtx` 构造里把 `priceLocked`/`priceVersionNo` 从 `row.__priceLocked` 改成 `driverRow.__priceLocked`（原始文件第 2809-2825 行附近）。master 的改动极其庞大（594 行，主要是 `computeAllFormulas` 内嵌入 BOM 树 PGET/C* 族求值逻辑，集中在原始文件 24-26/60-64/356-459/701-1235 行区间，以及 UI 层 1864-2954 行区间的多处小改动）。**逐一核对：master 在 2809 附近这个区间没有任何 hunk**（master 在这一片最近的两处改动分别落在 2648-2656 和 2932-2954，2809-2825 是两者之间的空档）；`ComponentDataItem` 接口所在的第 148 行附近同样不在 master 任何 hunk 范围内（master 对该接口没有改动，它改的是隔壁的 `ComponentField` 接口，第 60-64 行）。 | 🟢 低——文件很大、双方改动都不小，但**两组改动物理上落在完全不同的函数/区域**，属于"表面看着吓人、实际风险很低"的一类，git 也确认能干净自动合并 |
| **`ReadonlyProductCard.tsx`** | ✅ 无冲突标记。我们改了 3 处（详情页 `driverRow` 透传链路，原始文件 659/726/805 行附近）；master 改了 8 处（`buildFormulaCache`/BOM 树相关，103-163/589-676/889-966 行区间）。逐一核对：我们的 805 行改动夹在 master 的 624-632 与 889 两个 hunk 之间的空档，不重叠。 | 🟢 低 |
| **`enrichComponentData.ts`** | ✅ 无冲突标记。我们改了 2 处（205/312 行附近，把结构快照的 `elementCodeField` 等透传进 `ComponentDataItem`）；master 改了 2 处（138/269 行附近）。四个改动点两两错开，逐一核对无重叠。 | 🟢 低 |
| **`ComponentManagement.tsx`** | ✅ 无冲突标记。我们改动很大（129 行，屏 8 组件元素角色字段绑定 UI）；master 改动极小（1 行，给某个公式编辑抽屉新传一个 `tabType` prop，落在原始文件第 1791 行）。核对我们最后一个 hunk 落在 1762-1783 区间，master 那 1 行改动在其后，不重叠。 | 🟢 低 |
| **`component/types.ts`** | ✅ 无冲突标记。我们改动集中在 `ComponentItem` 接口（新增 `elementCodeField`/`elementPriceField`/`elementCurrencyField`，原始文件第 82 行附近）；master 改动集中在 `FieldItem`/`FormulaItem`/`FormulaToken` 三个**不同接口**（BL-0098 公式稳定 id + task-0803 `tree_ref`/`tree_attr` token 类型，原始文件 100-349 行区间）。**接口都不一样，物理不相交。** | 🟢 低 |

---

## 4. 🔒 `amt-002`/`amt-003` 核查——task-0803 没碰 `component_subtotal` 的键构造逻辑

用 `diff` 直接逐字节比对了 `formulaEngine.ts` 里 `case 'component_subtotal': { ... }` 这个代码块在 **本分支 HEAD** 和 **master** 两边的内容：

```
$ diff <(git show HEAD:...formulaEngine.ts | sed -n '/case .component_subtotal.: {/,/^      }/p') \
       <(git show master:...formulaEngine.ts | sed -n '/case .component_subtotal.: {/,/^      }/p')
（无输出，exit=0，逐字节完全相同）
```

`git diff <merge-base>..master -- formulaEngine.ts` 里唯一碰到 `component_subtotal` 字样的地方，是 `ExpressionToken.type` 联合类型声明那一行加了两个新枚举值（`tree_ref`/`tree_attr`），`component_subtotal` 本身作为已有枚举值原样保留，**没有任何一行改动落在这个 token 的求值逻辑（缺 `tab_name`-only 复合键查找分支的那段代码）里**。

也就是说：**`amt-002`/`amt-003` 暴露的那个理论缺口，合并 master 之后依然原样存在**——不多不少，状态不变。此前的生产配置审计结论（`前端精度对齐影响面清单.md` §5：全库 207 处 `component_subtotal` token，0 处命中 `tab_name`-only 变体）**基于的是共享开发库 `cpq_db_0724` 里当前实际存在的公式配置数据，不是代码**，与 task-0803 合并与否无关；但 task-0803 本身带来了大量 BOM 树相关的新公式 token（`tree_ref`/`tree_attr`），理论上合并后**共享库里的公式配置量会持续增长**，之前的审计结果有效期是"审计当时那一刻"，建议：

- **合并 master 落地后，重新跑一遍 §5 的结构性审计脚本**（对 `component.formulas`/`template.components_snapshot`/`quotation_view_structure.structure` 递归扫描 `component_subtotal` token 的 `(component_code, tab_name)` 组合分布），确认 task-0803 引入的公式里有没有意外新增 `tab_name`-only 用法（预期不会有，因为 task-0803 是 BOM 树父子取值，用的是全新的 `tree_ref`/`tree_attr` token 类型，不太可能触碰旧的 `component_subtotal` 用法习惯，但"预期不会"不等于"已验证"，合并后花几分钟重跑一遍审计脚本成本很低）。
- `amt-002`/`amt-003` 这两条黄金用例本身**不需要因为这次合并做任何改动**——它们验证的是代码路径（前端确实缺这段查找分支），代码路径没变，用例状态不变，继续留红。

---

## 5. 附：`docs/RECORD.md` 冲突（不是代码，顺带记一句）

`git merge-tree` 也报了 `docs/RECORD.md` 冲突——纯粹是"两边都在文件末尾追加新条目"的典型追加型冲突（我们这边最后是 2026-08-03 的 B9 收尾记录，master 那边是 task-0803 一路的开发记录），**没有任何一行是双方都改的同一条历史记录**，合并时把两段内容按时间顺序拼接（或干脆保留两段、顺序不敏感，反正是日志）即可，不需要任何判断，纯体力活。

---

## 6. 给合并执行者的操作清单（提炼版，不含判断过程）

1. `formulaEngine.ts`：冲突块整体取 master 一侧（`evaluateArithmetic` 调用），删除我们的 `Number.isFinite` 补丁。
2. `QuotationWizard.tsx`：两条 import 都保留。
3. 其余 6 个前端文件：`git merge-tree` 已确认能自动合并，正常走 `git merge` 流程即可，不需要额外人工介入。
4. `formula-golden/10-decimal-precision.json`：`dec-001`→`3.333333333333`、`dec-002`→`0.333333333333`、`dec-004`→`2.00005`，三条 `expectedSource`→`frontend-engine`，`dec-003` 不动。
5. `formula-golden/README.md` 顶部警示：合并完成后改状态为"已兑现"，不要删除整段（留作历史记录）。
6. 合并完立即跑：`cd cpq-frontend && npx vitest run src/utils/formulaGolden.test.ts`，期望结果从"31/33（amt-002/003 留红）"变成"仍是留红 2 条，dec-* 三条从绿变绿（只是数值来源不同）"——即整体应该还是 31/33，只是 3 条 `expected` 换了新值后继续通过；如果 dec-* 三条合并后反而变红，说明我这次隔离脚本跟 master 真实代码有出入，需要重新核查。
7. 重跑 §4 建议的 `component_subtotal` 结构审计脚本（`amt-002`/`amt-003` 场景），确认 task-0803 没有意外引入新的 `tab_name`-only 用法。
8. `docs/RECORD.md`：两段按时间顺序拼接。
9. 后端 `CardSnapshotService.java` 等 5 个后端文件的冲突：不在本文档范围，交由后端工程师处理（coordinator 已说明后端正在修复一个阻断级 bug，会一并处理）。
