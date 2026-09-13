# test-report-SB · repair-260911 分片 S-B（精度回归）

- **分片范围**：AC-R5（精度契约回归）全条 + AC-R3 的阳性对照单测。**纯单元测试**：未起后端 / 未起前端 / 未占端口 / 未连数据库 / 未造任何数据。
- **工作区**：`/home/joii/project/cpq/.claude/worktrees/repair-260911-card-render`，分支 `fix/repair-260911-card-render`
- **被测模块**：`cpq-frontend/src/pages/quotation/inputDefaults.ts` → `resolveInputDefaultSourceOnly`
- **我新增的用例文件**：`cpq-frontend/src/pages/quotation/inputDefaults.repair260911-SB.test.ts`
- **执行时间**：2026-09-11 20:00 前后（多次运行，结果一致）

> ⚠️ **派工书路径与实际不符**：派工书写的是 `cpq-frontend/src/utils/inputDefaults.ts`，该路径**不存在**；实际文件在
> `cpq-frontend/src/pages/quotation/inputDefaults.ts`（属于派工书列为「禁止读」的 `src/pages/quotation/` 目录）。
> 处理方式：**只取导出签名行**（`/usr/bin/grep -n "resolveInputDefaultSourceOnly"`）用于 import；断言全部来自
> `问题说明.md §⑦` AC 原文，未读实现体。FT-2 要求的守卫定位属于派工书 ④ 明确授权的例外，且发生在用例写完并跑绿之后，
> **未据此修改任何断言**。

> ⚠️ **同源风险已规避**：实现提交 `a0b0e09d`（F-1）**自带**一组同口径用例
> （`inputDefaults.test.ts:92-135`，「裸 JS number 源值」describe 块）。那是实现者自测，与实现同源，
> **不作为本片验收证据**。我另写了独立文件，字段形状与 `basicDataValues` 键（`'{$b._项次}'`）
> 均逐字抄自 AC 原文，**不经 `bnfDriverLookupKey` 推导**。

---

## 1. AC 追溯矩阵

| AC | 用例名 | 输入 | 期望 | **实际返回值** | 结论 |
|---|---|---|---|---|---|
| **AC-R3**（阳性对照） | `AC-R3：裸 number 1 → "1"` | `basicDataValues={'{$b._项次}': 1}`（裸 number） | `'1'` | **`"1"`** | 🟢 绿 |
| **AC-R3**（变量隔离对照组） | `AC-R3 对照组：同一路径喂字符串 "1"` | 同上但值为 `'1'` | `'1'` | **`"1"`** | 🟢 绿（改动前后都绿，用于隔离「number vs string」单变量） |
| **AC-R5 ①** | `AC-R5①：1 → "1"` | `1` | `'1'` | **`"1"`** | 🟢 绿 |
| **AC-R5 ②** | `AC-R5②：1.5 → undefined` | `1.5` | `undefined` | **`undefined`** | 🟢 绿 |
| **AC-R5 ③** | `AC-R5③：9007199254740993 → undefined` | `9007199254740993` | `undefined` | **`undefined`** | 🟢 绿 |
| AC-R5 量具自检 | `字面量 9007199254740993 本身已非安全整数` | — | `Number.isSafeInteger(...)===false` | `false` | 🟢 绿（证明这条用例不是在测一个已被 double 改写成安全整数的字面量） |
| AC-R5 边界补充 | `NaN / Infinity / -Infinity → undefined` | 三值 | `undefined` | 均 `undefined` | 🟢 绿 |
| AC-R5 边界补充 | `0 / -3 → 放行` | `0` / `-3` | `'0'` / `'-3'` | `"0"` / `"-3"` | 🟢 绿 |
| AC-R5 边界补充 | `-1.5 与 1e21 → undefined` | `-1.5` / `1e21` | `undefined` | 均 `undefined` | 🟢 绿 |

**本片认领的 AC 无缺行。** AC-R1 / R2 / R4 / R6 不属于本片（需真机，归其他分片）。

---

## 2. 证据 · 测试运行器原始输出

### 2.1 S-B 用例（还原后的最终一次）

```
 ✓ ... > AC-R3：裸 number 1 → "1"（改动前为 undefined，改完必须为 "1"） 1ms
 ✓ ... > AC-R3 对照组：同一路径喂字符串 "1" → "1"（改动前就该绿；它红说明是键格式/路径问题而非 number 问题） 0ms
 ✓ ... > 量具自检：字面量 9007199254740993 本身已非安全整数 0ms
 ✓ ... > AC-R5①：1 → "1" 0ms
 ✓ ... > AC-R5②：1.5 → undefined（小数仍被丢弃） 0ms
 ✓ ... > AC-R5③：9007199254740993（超安全整数）→ undefined 0ms
 ✓ ... > AC-R5 边界补充：NaN / Infinity / -Infinity 均非安全整数 → undefined 0ms
 ✓ ... > AC-R5 边界补充：0 / -3 是安全整数 → 放行 0ms
 ✓ ... > AC-R5 边界补充：-1.5 与 1e21（非安全整数）→ undefined 0ms

 Test Files  1 passed (1)
      Tests  9 passed (9)
```

用例内 `console.log` 打印的**实际返回值**（防「断言空跑」）：

```
[S-B][AC-R3] resolve(number 1) = "1"
[S-B][AC-R3-ctrl] resolve(string "1") = "1"
[S-B][AC-R5-1] resolve(1) = "1"
[S-B][AC-R5-2] resolve(1.5) = undefined
[S-B][AC-R5-3] resolve(9007199254740993) = undefined
```

### 2.2 精度契约相关既有套件（AC-R5「不被打破」的横向回归）

```
$ npx vitest run src/utils/precision.test.ts src/utils/losslessJson.test.ts \
    src/services/quotationService.precision.test.ts \
    src/services/v6MasterDataService.precision.test.ts \
    src/pages/quotation/inputDefaults.test.ts

 Test Files  5 passed (5)
      Tests  57 passed (57)
```

### 2.3 前端全量单测（顺带跑，判有无外溢）

```
$ npx vitest run src

 Test Files  100 passed (100)
      Tests  1206 passed (1206)
   Duration  9.88s
```

---

## 3. FT-2 证伪实验（本片最锋利的一条）

**干预**：把 `inputDefaults.ts:72` 的守卫临时改回「numbers 一律丢弃」：

```diff
     if (typeof resolved === 'number') {
-      if (!Number.isSafeInteger(resolved)) return undefined;
+      return undefined; // FT-2 临时证伪实验（必须还原）
       resolved = String(resolved);
     }
```

**① 干预是否确认生效 —— 两重独立确证**

- **磁盘态**：`sed -n '70,75p'` 读回实验态源码，确认磁盘上就是 `return undefined;`；`git diff --stat` 显示 `1 file changed, 1 insertion(+), 1 deletion(-)`。
- **运行时态**：`vitest run` 每次是全新 node 进程、从磁盘重新 transform；**输出的实际值随之改变**
  （`[S-B][AC-R3] resolve(number 1) = undefined`，干预前是 `"1"`）。值变了 = 运行时确实加载了实验态模块，
  排除「改了没重编译 / 命中缓存」。

**② 是否如期变红 —— 且变量被精确隔离**

```
 × AC-R3：裸 number 1 → "1"                     AssertionError: expected undefined to be '1'
 ✓ AC-R3 对照组：同一路径喂字符串 "1" → "1"        （仍绿）
 ✓ 量具自检：9007199254740993 非安全整数
 × AC-R5①：1 → "1"                              AssertionError: expected undefined to be '1'
 ✓ AC-R5②：1.5 → undefined
 ✓ AC-R5③：9007199254740993 → undefined
 ✓ AC-R5 边界补充：NaN / Infinity / -Infinity → undefined
 × AC-R5 边界补充：0 / -3 是安全整数 → 放行        AssertionError: expected undefined to be '0'
 ✓ AC-R5 边界补充：-1.5 与 1e21 → undefined

 Test Files  1 failed (1)
      Tests  3 failed | 6 passed (9)
```

**硬失败，且失败面精确**：红的**只有「安全整数应被放行」这一族**（3 条）；
「字符串 `'1'` 对照组」全程绿 ⇒ 排除键格式/路径写错；
AC-R5②③ 期望 `undefined` 故仍绿 ⇒ 说明这两条**不能单独证明守卫存在**，必须靠 ① 那一族撑着 —— 这正是配对设计的目的。

**③ 已还原 —— `git diff` 证明**

```
$ git diff -- cpq-frontend/src/pages/quotation/inputDefaults.ts
（无任何输出，exit 0）

$ git status --porcelain
 M cpq-frontend/src/pages/quotation/QuotationWizard.tsx      ← 不是我改的，见 §5
?? cpq-frontend/src/pages/quotation/inputDefaults.repair260911-SB.test.ts   ← 我新增的用例
```

还原后重跑：**9 passed (9)**，实际值回到 `resolve(number 1) = "1"`。

---

## 4. 哪些没做到 / 未验证

| 项 | 状态 | 说明 |
|---|---|---|
| AC-R3 的**真机部分**（刷新后 BOM 页签「项次」列非空行数 = 材质数 = 2） | **未验证** | 本片是纯单测，不起服务、不连库。单测只证明**函数层**放行了裸整数，**不能**证明页面上项次列真的显示出来 —— 中间还隔着 batch-expand 通道、渲染层。归其他分片/主线亲验。 |
| AC-R1 / AC-R2 / AC-R4 / AC-R6 | **未覆盖（不属本片）** | 均需真机 + 数据库。 |
| `npx tsc -b` 类型检查 | **未跑** | 按角色纪律，编译自检是开发的自检项，不是我的验收项。 |
| 「实时通道到底给不给裸 number」这一前提 | **未独立验证** | AC 原文与 F-1 注释都称「axios 默认 `JSON.parse` ⇒ 裸 number」。我**照 AC 口径构造入参**，没有抓包证明线上真是裸 number。若该前提不成立，本片全绿也不代表 AC-R3 真机会绿 —— 由主线亲验兜底。 |

---

## 5. 过程中规避掉的坑

1. **派工书给的文件路径不存在**（`src/utils/` vs 实际 `src/pages/quotation/`）。没有凭包名猜路径，用 `find` + `/usr/bin/grep -a` 实查定位（`grep` 在本环境是 `ugrep -I`，中文多的源文件会被静默判为二进制返空）。
2. **实现者自带的同源用例**：`a0b0e09d` 里已有一组几乎同口径的用例。若直接拿它当证据，就是拿实现者的理解验实现者的实现。另写独立文件规避。
3. **键格式来源**：`basicDataValues` 的键没有去 `useDriverExpansions.ts` 抄 `bnfDriverLookupKey`（那是禁读目录，且会把测试与实现耦合），直接用 AC 原文字面量 `'{$b._项次}'`；靠「字符串 `'1'` 对照组」在键写错时一起变红来自我诊断。
4. **`9007199254740993` 字面量陷阱**：这个字面量本身会被 IEEE-754 改写成 `9007199254740992`（安全整数）。加了一条量具自检 `expect(Number.isSafeInteger(9007199254740993)).toBe(false)`，确认它在 JS 里仍判为非安全整数，否则 AC-R5③ 会变成一条测错东西的假绿。
5. **断言空跑**：每条主用例都先把实际返回值 `console.log` 出来（`--disable-console-intercept` 才看得到），报告里贴出原始值，不只写「绿」。
6. **`node_modules` 是软链**（→ 主仓 `cpq-frontend/node_modules`），未执行 `npm install`，未新增依赖。⚠️ 副作用告知：vitest 的 transform 缓存写在 `node_modules/.vite` 下，即写进了**主仓的 node_modules**（非源码、非 git 跟踪，无污染风险，但主线知悉为好）。
7. **共享 dev server（8081 / 5174）全程没碰**，也没探活 —— 那服务的是主工作区代码，与本片无关。
8. **工作区里有别人的在途改动** `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`（不是我改的）。提交时**只走路径限定** `git commit -- <我的两个文件>`，🚫 未 `git add -A`，未碰该文件。

---

## 6. 待回收清单

**零新增。** 本片未造任何业务数据、未建库、未建表、未占端口、未改任何全局状态（用户启停用 / 角色权限 / 模板发布态 / 系统开关 / 公共基础数据一概未动）。
FT-2 的临时源码改动已 `git diff` 证明还原。

---

## 7. 自检声明

`npx vitest run src/pages/quotation/inputDefaults.repair260911-SB.test.ts` → **9 passed (9)** ✅；
精度契约相关 5 个套件 → **57 passed (57)** ✅；
前端全量 `npx vitest run src` → **100 files / 1206 passed** ✅；
FT-2 干预态 → **3 failed | 6 passed**（如期硬失败）✅；
还原后 `git diff -- inputDefaults.ts` → **空** ✅。
