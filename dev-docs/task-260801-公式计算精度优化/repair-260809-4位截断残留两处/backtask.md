# backtask · repair-0809 精度残留两处

> 输入：同目录 `需求文档.md`（FR-1~FR-3 / AC-1~AC-7 / D-1~D-5 以它为准）。
> 分支：`fix/repair-0809-precision-leftovers`（worktree 内开发）。**改动只有 2 行代码 + 注释 + 测试。**

---

## 1. 改动清单

### T-B1 · `CardSnapshotService.java:4074-4079`（FR-1）

现状：
```java
// BL-0017 哨兵键登记（task-0729 B8.1 补齐）：`${cid|code|tabName}#__amount_total__` = Σ金额列。
double roundedAmountTotal = amountTotal
    .setScale(4, java.math.RoundingMode.HALF_UP).doubleValue();
putAmountTotalSentinel(cid, code, tabName, roundedAmountTotal, componentSubtotals);
```

改为**不做任何规整**：
```java
// BL-0017 哨兵键登记（task-0729 B8.1 补齐）：`${cid|code|tabName}#__amount_total__` = Σ金额列。
// repair-0809：不做任何规整 —— 该键是喂回公式引擎的**中间量**，不是 PrecisionPolicy 定义的
// 四个呈现边界之一（task-0801 §4.3：计算过程中不规整）。另两个登记点
// ConfigureSnapshotService:1477 / ComponentDataEffectiveRows:216 及前端
// tabTotalLines#sumAmountFromByCol 本来就不规整；此处原先的 setScale(4) 是 task-0801
// 清扫后才新增的漏网点，导致同一语义三个实现里唯独它截断（QT-20260807-0146 产品小计尾差 2.5536e-5）。
putAmountTotalSentinel(cid, code, tabName, amountTotal.doubleValue(), componentSubtotals);
```
- ⚠️ 上方 `roundedTotal`（裸键 = Σ所有 is_subtotal 列）**不动** —— 它本来就没规整，别顺手改。
- ⚠️ `buildTabNode:~1806` 排除该哨兵键泄漏进 `subtotalByColumn` 的逻辑**必须保留**（原注释已标 🔒）。

### T-B2 · `MaterialVersionUpgradeService.java:388`（FR-2）

现状：`q.totalAmount = lineSum.setScale(4, java.math.RoundingMode.HALF_UP);`

改为：
```java
// repair-0809：落库边界统一走 PrecisionPolicy.round()（6 位），与同字段的另一个写点
// QuotationService:894 完全一致（task-0801 B5）。原 setScale(4) 是本链路后长出来的漏网点。
q.totalAmount = com.cpq.common.PrecisionPolicy.round(lineSum);
```
- 🔒 **只改最后这一步规整方式**。上方 `lineSum` 的累加口径（跳过 `PART`、只读其它行既有 `lineTotalAmount`、「只对被升版行执行重算」的硬约束）是 `repair-0807` 刚定的语义，**一个字都不要动**。

### T-B3 · 测试（FR-3，见 `test.md`）
在 `cpq-backend/src/test/java/...` 下新增一个测试类，覆盖 AC-3 / AC-4 两条等价性。
**夹具纪律**：🚫 **禁止** `Assumptions.assumeTrue` 兜底夹具缺失（`BL-0157` 教训：护栏的价值全在「夹具没了要吵」）。夹具用**代码构造**，不依赖库里某张具体单据。

---

## 2. 自检项（提交前自己跑，输出贴进交付说明）

- [ ] `grep -n "setScale(4" cpq-backend/src/main/java -r` → 两处目标**不再命中**；把剩余 4 处的命中行**原样贴出**（供 AC-1 逐条说明为何保留）
- [ ] **在 worktree 内的 `cpq-backend/`** 跑 `./mvnw test`（不是仓库根、不是主仓）→ 记录 `Tests run / Failures / Errors / **Skipped**`
- [ ] **同轮**先在 A 侧（`git stash` 你的两处改动或 `git checkout HEAD~ --` 取改前版本）跑一次同样的命令，两组数字并列贴出 —— **不得引用文档里的历史基线数字**
- [ ] `touch` 一个 java 触发 Quarkus 重启 → `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → 期望 `401`
- [ ] N+1 自检：本次无新增循环、无新增查库 → 按格式声明一行
- [ ] `git show --stat` 自查：只含 2 个源文件 + 1 个测试类

## 3. Task 列表

- [ ] T-B1 `CardSnapshotService` 去规整 + 注释
- [ ] T-B2 `MaterialVersionUpgradeService` 改 `PrecisionPolicy.round` + 注释
- [ ] T-B3 等价性测试（AC-3 / AC-4）
- [ ] T-B4 跑齐 §2 自检并贴 A/B 两组原文输出
