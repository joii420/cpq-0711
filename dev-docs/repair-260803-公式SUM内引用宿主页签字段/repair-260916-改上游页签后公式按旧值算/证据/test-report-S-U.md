# test-report · S-U 片（单元测试）· repair-260916

> 认领 AC：**AC-11、AC-13**。本片不连库、不起服务。写用例时未读任何实现代码（只读了任务文档、`证据/`、既有 `*.test.ts`、`losslessJson.ts` 导出签名）。
> 阶段：**第一轮 —— 修复前对照已跑完；修复分支上的执行尚未进行（等主线通知）。**

---

## 1. 做了什么（用例 → AC → 断言值）

测试文件：`cpq-frontend/src/pages/quotation/bfieldStaleRowData.repair260916.test.ts`（共 18 条）
夹具：`cpq-frontend/src/pages/quotation/__fixtures__/qt20260916-0879/wuliao.json`，由 `证据/离线判决/fixture_0879_wuliao.json` 原样复制，sha256 两者相同 `e84c63eb1d15ee100a75cf5011d20485bcdc980073246adfd79e6c01d3a24d09`。读取用 `tryParseSnapshotJsonLossless`。

| 用例 | AC | 断言（9 位比较） |
|---|---|---|
| 夹具形状 | U-0 的前置条件 | 6 行、8 个公式列、6 份后端结果；00257 行 `row.来料加工费 = 170.404`；上游两个键（`57554055-…`、`COMP-0005`）下 00257 费用 `170.404`、00256 比例 `5` |
| U-0 对照 | 夹具可信 | 夹具原样喂 `computeTabFormulasTree` ⇒ 6 行 × 8 列 = `backendFormulaResults`，逐格比对，并断言共比对 48 格 |
| U-1 | AC-13（AC-1 的离线等价） | 树入口，`rows[5].row.来料加工费='150.8'`（旧值），上游仍 `170.404` ⇒ 00257 来料加工费 `170.404`、材料成本 `0.002418226`；S3110520422 `0.059189199`；00255 `1.437983994`、00144 `0.463735546`、00256 `0.015491845` |
| U-2 | AC-13 | 非树入口 `computeAllFormulas` 算 00257（同 U-1 输入）⇒ 来料加工费 `170.404`、材料成本 `0.002418226`、`errors = {}` |
| U-3 E0 | AC-13 | 行数据保持原样、上游 170.404/5 ⇒ 00257 `0.002418226`、00256 `0.015491845`、父行 `0.059189199`、列合计 `1.978818810` |
| U-3 E1 | AC-13 | 上游费用 200/比例 5 ⇒ 00257 来料加工费 `200`、材料成本 `0.002678099`；00256 `0.015491845`；父行 `0.059194397`；列合计 `1.979083881` |
| U-3 E2 | AC-13 | 200/10 ⇒ 00256 来料损耗率 `10`、材料成本 `0.016191348`；00257 `0.002678099`；父行 `0.059208387`；列合计 `1.979797374` |
| U-3 E3 | AC-13 | 0/10 ⇒ 00257 来料加工费 `0`、材料成本 `0.000921968`；00256 `0.016191348`；父行 `0.059173264`；列合计 `1.978006120` |
| （U-3 各组共有） | | 00255 `1.437983994`、00144 `0.463735546`；列合计 = 6 格先舍 9 位（ROUND_HALF_UP）再相加；改上游时断言两个键下各恰好命中 1 行 |
| U-4 ×2 入口 | AC-11a | 构造组件（test.md §2.3 原样），行 `{A:'8', X:'1', N:'3'}` ⇒ `X=16`、`Y=1` |
| U-5 ×4 | AC-11b | `N='3'` ⇒ `Z=15`；`N=''` ⇒ `Z=0`（两个入口各跑一遍） |
| U-6 ×4 | AC-11c | 构造组件两个入口：调用前深拷贝 `{A,X,Y,Z,N}`，调用后键集合与值完全相同；另用 0879 夹具（00257 存旧值）分别对树入口 6 行、非树入口 00257 行做同样检查，并断言公式列键 `来料加工费='150.8'`、`材料成本` 仍在 |

---

## 2. 证据

### 2.1 修复前对照（副本 `/home/joii/project/cpq/.claude/worktrees/repair-260916-bfield-baseline`，HEAD `0ee0e57c`）

副本 `git status --short`（复制后）：只有
```
?? cpq-frontend/src/pages/quotation/__fixtures__/qt20260916-0879/
?? cpq-frontend/src/pages/quotation/bfieldStaleRowData.repair260916.test.ts
```

命令：`cd /home/joii/project/cpq/.claude/worktrees/repair-260916-bfield-baseline/cpq-frontend && npx vitest run src/pages/quotation/bfieldStaleRowData.repair260916.test.ts --reporter=verbose`
输出头：`RUN  v4.1.4 /home/joii/project/cpq/.claude/worktrees/repair-260916-bfield-baseline/cpq-frontend`（确认跑的是对照副本）

```
 ✓ 夹具形状 > 6 行、8 个公式列、6 份后端结果、00257 行存着公式列旧键
 ✓ U-0 对照 > 6 行 × 8 公式列
 × U-1 > 00257 材料成本 0.002418226、S3110520422 0.059189199
 × U-2 > 00257 材料成本 0.002418226，且无计算错误
 ✓ U-3 > 'E0 现状 170.404/5'
 × U-3 > 'E1 费用 200/比例 5'
 × U-3 > 'E2 费用 200/比例 10'
 × U-3 > 'E3 费用 0/比例 10'
 × U-4 > computeAllFormulas：A=8、X 存旧值 1 ⇒ X=16、Y=1
 × U-4 > computeTabFormulasTree：A=8、X 存旧值 1 ⇒ X=16、Y=1
 ✓ U-5 > computeAllFormulas：N=3 ⇒ Z=15
 ✓ U-5 > computeTabFormulasTree：N=3 ⇒ Z=15
 ✓ U-5 > computeAllFormulas：N='' ⇒ Z=0
 ✓ U-5 > computeTabFormulasTree：N='' ⇒ Z=0
 ✓ U-6 > computeAllFormulas：构造组件
 ✓ U-6 > computeTabFormulasTree：构造组件
 ✓ U-6 > computeTabFormulasTree：0879 真实夹具 6 行
 ✓ U-6 > computeAllFormulas：0879 真实夹具 00257 行
 Test Files  1 failed (1)
      Tests  7 failed | 11 passed (18)
```

失败时的实际值（原文）：
```
U-1  AssertionError: 00257 材料成本：实际 0.002246090543（9 位 0.002246091），期望 0.002418226
U-2  AssertionError: 00257 材料成本：实际 0.002246090543（9 位 0.002246091），期望 0.002418226
U-3 E1 AssertionError: 00257 材料成本：实际 0.002418226427（9 位 0.002418226），期望 0.002678099
U-3 E2 AssertionError: 00257 材料成本：实际 0.002418226427（9 位 0.002418226），期望 0.002678099
U-3 E3 AssertionError: 00257 材料成本：实际 0.002418226427（9 位 0.002418226），期望 0.000921968
U-4 computeAllFormulas     AssertionError: computeAllFormulas Y：实际 2（9 位 2.000000000），期望 1.000000000
U-4 computeTabFormulasTree AssertionError: computeTabFormulasTree Y：实际 2（9 位 2.000000000），期望 1.000000000
```

测试打印的实际值（节选）：
```
== U-0 对照
  [1] S3110520422  材料成本=0.059189199486
  [4] 00256  来料加工费=5.8  来料损耗率=5  材料成本=0.015491844826
  [5] 00257  来料加工费=170.404  来料损耗率=0  材料成本=0.002418226427
== U-1
  [1] S3110520422  材料成本=0.059185756768      ← 与用户截图 1 的 0.059185757 相同
  [5] 00257  来料加工费=170.404  材料成本=0.002246090543   ← 与截图 1 的 0.002246091 相同
== U-2 00257 来料加工费=170.404 材料成本=0.002246090543 errors={}
== U-3 E2 费用 200/比例 10
  [4] 00256  来料加工费=5.8  来料损耗率=10  材料成本=0.015491844826   ← 列显示 10，材料成本仍按 5 算
  [5] 00257  来料加工费=200  来料损耗率=0  材料成本=0.002418226427
== U-4 computeAllFormulas out={"X":"16","Z":"15","Y":"2"}
== U-4 computeTabFormulasTree out={"X":"16","Z":"15","Y":"2"}
== U-5 computeAllFormulas N='' out={"X":"16","Z":"0","Y":"2"}
```

结论：与 test.md §2.1 的期望完全一致 —— **U-1、U-2、U-3（E1/E2/E3）、U-4 在修复前失败；U-0、U-3 E0、U-5、U-6 通过**。修复前的失败值与立项实测证据 §3.1、§3.2、§8.2 完全一致。

### 2.2 修复前全量失败集合（AC-13 第 2 项的基线）

命令：`cd /home/joii/project/cpq/.claude/worktrees/repair-260916-bfield-baseline/cpq-frontend && npx vitest run src`
```
 RUN  v4.1.4 /home/joii/project/cpq/.claude/worktrees/repair-260916-bfield-baseline/cpq-frontend
 Test Files  1 failed | 100 passed (101)
      Tests  7 failed | 1234 passed (1241)
```
失败清单（全部是本片新增的用例，既有测试失败为 **0 条**）：
```
bfieldStaleRowData.repair260916.test.ts > U-1 … > 00257 材料成本 0.002418226、S3110520422 0.059189199
bfieldStaleRowData.repair260916.test.ts > U-2 … > 00257 材料成本 0.002418226，且无计算错误
bfieldStaleRowData.repair260916.test.ts > U-3 … > 'E1 费用 200/比例 5'
bfieldStaleRowData.repair260916.test.ts > U-3 … > 'E2 费用 200/比例 10'
bfieldStaleRowData.repair260916.test.ts > U-3 … > 'E3 费用 0/比例 10'
bfieldStaleRowData.repair260916.test.ts > U-4 … > computeAllFormulas：A=8、X 存旧值 1 ⇒ X=16、Y=1
bfieldStaleRowData.repair260916.test.ts > U-4 … > computeTabFormulasTree：A=8、X 存旧值 1 ⇒ X=16、Y=1
```
⇒ **修复前基线中既有测试的失败集合 = 空集**；修复分支的判据为「既有测试失败 0 条，本片 18 条全部通过」。

### 2.3 修复分支上的执行

**未执行**（等主线通知前端完成）。待跑：本文件、`npx vitest run src`、`npx tsc -b`。

---

## 3. 未验证 / 没做到的项

- 修复分支上：U-0~U-6 全部通过、`npx vitest run src` 无新增失败、`npx tsc -b` 0 错误 —— **未验证**（尚未解锁执行）。
- 本文件本身的类型检查（`tsc -b`，tsconfig.test.json 会覆盖它）—— **未验证**，将在修复分支执行时一并跑。
- AC-11 **只有单元测试覆盖**（全库无样本，问题说明 AC-11 已声明），不算已验收，闸门 B 需显式列出。
- U-4/U-5 调用构造组件时 `allComponentSubtotals` 传 `{}`、`partNo` 不传；test.md 没规定这两个参数，修复前输出与证据 §8.2 一致，说明调用方式可用。

## 4. 规避掉的坑

- 夹具用 `tryParseSnapshotJsonLossless` 读，没有用 `JSON.parse`（否则材料成本整列为 0）。
- 改上游值时，`57554055-…` 与 `COMP-0005` 两个键都改，并断言每个键下各恰好命中 1 行，防止改了个空。
- 复制上游行、复制本行数据时都是先拷贝再改，不动夹具对象本身；U-6 检查入参没有被改写。
- 列合计按「每格先舍 9 位再相加」算（E3 期望 `1.978006120`，不是 12 位累加得到的 `1.978006121`）。
- 数值断言前先断言值非空、是数字（`—` 或空值不会被当成 0 放过）；U-0 断言比对了 48 格，防止比对循环一次没跑也报绿。
- 第一次运行的精简输出里没看到 U-0 的打印，没有当成它跑过，用 `--reporter=verbose` 复跑，确认 U-0 执行并通过，且打印了实际值。
- 核对了 vitest 输出头里的路径，确认跑的是对照副本；复制前确认对照副本里没有同名文件，复制后 `git status` 只多出这两项。
- 没有使用 `git stash`，没有改动对照副本里的其他文件。

## 5. 复制进对照副本的文件（由主线清理）

- `/home/joii/project/cpq/.claude/worktrees/repair-260916-bfield-baseline/cpq-frontend/src/pages/quotation/bfieldStaleRowData.repair260916.test.ts`
- `/home/joii/project/cpq/.claude/worktrees/repair-260916-bfield-baseline/cpq-frontend/src/pages/quotation/__fixtures__/qt20260916-0879/wuliao.json`（连同新建的目录 `__fixtures__/qt20260916-0879/`）
