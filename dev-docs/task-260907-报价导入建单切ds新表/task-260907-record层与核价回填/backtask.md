# task-260907 · 第二段 —— 后端任务分解

> **后端只按本文做。** AC 原文在 `需求文档.md`，接口契约在 `api.md`，🚫 本文只标 AC 编号不复制原文。
> **每项都能指回至少一条 AC**（反向覆盖），**每条 AC 都有人认领**（正向覆盖）—— 见文末覆盖矩阵。

---

## 🚨 开工前必须先读（不读就动手 = 违规）

| 文档 | 为什么 |
|---|---|
| `docs/rules/backend.md` | **N+1 硬指标**：单个业务操作的 SQL 条数必须是常数，与轴值数无关；**循环体里出现查询 = 违规** |
| `docs/反模式.md` **AP-60** | 本段的核心形状约束。真实事故：核价通过把一组 4 行「对齐」成 1 行、多列置 NULL，而预览显示 0 变更 |
| `docs/反模式.md` **AP-54** | 「过滤后下标当原数组下标」—— A0-1 否决「按行序对位」的直接依据 |
| `VersionedGroupWriter` 类注释 | 🚫 严禁自己算指纹 / 自己归档 / 自己定版本号。「两套实现必然漂移」 |

## 🚦 三条硬时序（不可跳）

1. ~~**B-1 / B-3 的 `CREATE TABLE` / `ALTER TABLE` 必须排在 `task-260907-报价侧加客户维度` 的「DDL + 轴模型同批合并」之后。**~~<br>✅ **2026-09-07 17:54 该时序已满足并解除**：上游 `V425~V429` 已应用（逐条 `success=t`，实测 42 张 `ds_quote_%` 带 `customer_no`）；`customer_no` 列定义已裁定为 **`varchar(20) NOT NULL` 无默认、取值 = `customer.code`**，本段迁移已按此填实；**B-1 的 13 张 `_record` 已由 `V420` 建成**（`success=t`）。<br>⛔ **仍未落库的只剩 B-3（`source_quotation_id`，实测 0 张）与 D-35（`ds_quote_record_stale`）** ——这两条是**本段自己的迁移**，压着的原因是**落库窗口由主线统一开**（步骤见 `scripts/RUNBOOK-B3-D35-落库.md`），**不是「等上游」**。🚫 **两个洞不要混成一个。**
2. **挂 B-11（新回填）之前，主线必须先通知第一段会话。** 其 `AC-16` 断言「核价通过时 `QuoteBackfillService` 摘要恒 `0/0/0/0`」，挂上之后即不成立，需其抢先验掉。
3. **迁移号取 `max(目录, 共享库) + 1`，且在落库那一刻实取。** 🚫 不许预取、🚫 不许只看目录、🚫 不许只看共享库 —— 两个都可能偏小（文件先落库后进 master ⇒ 目录小；迁移写完没跑 ⇒ 共享库小）。
   - 📌 2026-09-07 03:30 实测：共享库 = **V419**，master 目录 = **V419**，齐平无孤儿 ⇒ 下一个是 V420。
   - 🕰️ 同日 02:40 我采到的是「齐平在 V417」，**50 分钟后就过期**。⇒ **本文档里这个数字只是样本，不是依据。**
   - 🚨 **落库那一刻就把迁移文件推 master**（`RECORD.md` 记 `V416`/`V417` 同型复发两次：迁移先落共享库、文件后进 master，Flyway 只在启动时校验 ⇒ 肇事者看不见，下一个重启的人才踩到）。

---

## 一、Schema 与 Registry

### B-1 · 13 张 `_record` 表建表迁移
**服务的 AC**：AC-1
- 对**每张带版本**的 `ds_quote_*` 主表建一张 `<主表>_record`。判据是「有 `version_no` 列」，🚫 不许写死表名清单（表会增）。
- 列 = 主表全部业务列（`persistedColumns()`）+ 下列附加列：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial PK | 本表自增 |
| `origin_id` | bigint **可空** | 拍快照时主表那一行的 `id`。**可空** —— 报价单里手工新增的行在主表没有对应行 |
| `base_row_fingerprint` | char(64) **可空** | 拍快照时主表那一行的整行指纹（A0-1 兜底锚）。手工新增行为 NULL |
| `quotation_id` | uuid **非空** | 来源报价单 |
| `base_version_no` | integer **非空** | 拍快照时主表该组的 `version_no`；主表当时无该组则为 `0` |
| `extend_column` | jsonb | 主表对不齐的字段（自定义列 / 公式列）。`D-5`：不回填、不参与升版与比对 |
| `customer_no` | `varchar(20)` **NOT NULL** 无默认 | ✅ 上游 `A0-1` 已裁决（取值 = `customer.code`）；已随 `V420` 建成。~~待上游裁决~~ |
| 系统列 | | 与主表一致：`source` / `created_at` / `created_by` / `updated_at` / `updated_by` |

- **`element_price numeric(26,12)` 只加在两张**：`ds_quote_material_bom_record` / `ds_quote_element_bom_record`（`S-3`；用户原话「物料BOM元素 表」）。
- 索引：`(quotation_id)` + `(轴列, quotation_id)`。
- 🚫 **免版本三表不建 `_record`**（`ds_quote_material` / `ds_quote_customer_part` / `ds_quote_plating_scheme`）。

### B-2 · `SheetDef` 增加「系统列」声明与 `expectedRecordColumns()`
**服务的 AC**：AC-1②⑤, AC-8
- 🚨 **来源报价单 id 与 `_record` 的附加列一律不得声明为 `ColumnDef`。** 实查依据（`需求文档.md` §4.2）：
  - `DatasetSchemaSelfCheck` 要求列集**完全相等**，多一列即**启动失败**；
  - `DatasetSheetParser:75` 要求**每个 `persistedColumns()` 的 `label` 都出现在 Excel 表头**，缺一个整张 sheet 拒收 ⇒ 声明成 `ColumnDef` 会让**所有存量报价 Excel 导不进去**。
- ⇒ 照 `SYSTEM_COLUMNS` / `VERSION_COLUMNS` / `ARCHIVE_COLUMNS` 的形状，新增 `RECORD_COLUMNS` 常量与 `expectedRecordColumns()`，并把来源报价单 id 加进带版本表的期望列。
- ⚠️ `SheetDef` 是**三套数据集共用**的 —— 新增的期望列必须**只对报价侧生效**，🚫 不得让核价两套的自检跟着要求这些列（会让核价侧启动失败）。

### B-3 · 主表 + `_history` 加来源报价单 id 列
**服务的 AC**：AC-8
- 13 张带版本主表 + 13 张 `_history` 各加一列（列名与 B-2 的声明逐字一致）。
- 进 `_history` 的理由见 `需求文档.md` §⑥「无岔路」：`VersionedGroupWriter.archive()` 是逐列 `INSERT…SELECT`，不进反而要写特例。
- ⚠️ 该列**不参与 `row_fingerprint`** —— 否则「同样的数据换一张单回填」会被判成 UPGRADED，产生虚假升版。

### B-4 · `DatasetSchemaSelfCheck` 纳入 `_record`
**服务的 AC**：AC-1⑤
- 按 B-2 的 `expectedRecordColumns()` 比对 `_record` 表，与 `_history` 同等对待（列名集合完全相等 + 类型一致）。
- 不一致 → 启动失败，异常里列全部差异（沿用该类既有行为，不是遇到第一条就停）。

---

## 二、`_record` 写入（保存链路）

### B-5 · `saveDraft` 挂 `_record` 增量写入
**服务的 AC**：AC-2
- 挂载点：`QuotationService.saveDraft`（`:388` 一带的增量三数组协议之后）。
- **复用既有三数组协议**（`added` / `modified` / `removed` + `baseVersion` 乐观锁 + 409 `StaleVersionException`，`task-260901` 交付）。🚫 **不要整单重写** —— autoSave 频率下会写放大（`task-260901` 已为 `row_data` 那条路付过 43 秒保存的账）。
- 只写**本次变更的产品**对应的轴值组；未变更产品的 `_record` 行**一个字节不动**（AC-2②断言 `updated_at` 逐字未变）。
- 写入时同时落 `origin_id` / `base_row_fingerprint` / `base_version_no` —— 三者都取自**拍快照那一刻主表该组的真实状态**。
- 🚫 **N+1 红线**：按 sheet 批量写，SQL 条数与产品数 / 轴值数无关。

### B-6 · `extend_column` 归集
**服务的 AC**：AC-3
- 页签字段中**主表没有对应列**的（自定义列、公式列、常量列）归入 `extend_column` jsonb。
- 判据沿用既有映射：`QuoteBackfillColumnMapper:105-111` 对计算列 / 表达式 / 常量返回空 `baseTableName` 即跳过 —— 现网 1257 个字段里 **396 个无 `default_source` + 64 个 `FORMULA`** 走这条，属常态不是异常。
- 🚫 `extend_column` **不进 `row_fingerprint`**（AC-3③：只改自定义列再通过，该组必须判 `UNCHANGED`）。

### B-7 · `element_price` 建单时算一次
**服务的 AC**：AC-4
- 建单（物化）时把当时的元素实时价写进 `ds_quote_element_bom_record.element_price` / `ds_quote_material_bom_record.element_price`。
- 🚫 **不进主表**（`D-6`）。

### B-8 · 价格调整时同步 `_record.element_price`
**服务的 AC**：AC-12
- 挂载点：`MaterialVersionUpgradeService` 的 **S3a/S3b 字段级写回**处（`:601` 一带写 `snapshot_rows` / `row_data` 的同一位置）。
- 🔑 **同写点同事务** —— 这是唯一能防两者分叉的口径（`D-29`）。`snapshot_rows` 写了 `_record` 就必须写；被 S0 L3 守卫拦下、或状态不在 `ACTIVE_STATUSES`（实测 `{DRAFT, SUBMITTED, APPROVED, REJECTED, COSTING_REJECTED}`，`:124`）而 `SKIPPED` 的单，**两者同时不写**。
- ⚠️ 🚫 不许新起一个「扫全表同步」的定时任务 —— 那会制造第二套口径，必然漂移。

---

## 三、回填（核价通过链路）

### B-9 · 回填 collector：按「表 × 轴值」组装 patch
**服务的 AC**：AC-6, AC-10, AC-13, AC-20
🔴 **本段风险最高的一项。** 形状严格照 `AP-60` 的「正确形状」，逐条对照：

1. **基底 = 主表该组的真实整组行**（`D-9`），🚫 **不是 `_record` 的行集**。
   > `_record` 是**投影**：一个页签往往只表征组内**部分行、部分列**。把投影当全集正是 `AP-60` 的事故形状。
2. **遍历主轴 = 基底行**，🚫 严禁「遍历 `_record` 生成行」的写法。
3. **双锚对位**（A0-1 裁决）：
   - `_record.base_version_no == 主表当前 version_no` → 按 `origin_id` 精确对位；
   - 不等 → 按 `base_row_fingerprint` 在当前组内重锚；
   - 都锚不上 → 进 `unanchoredRows`，**显式上报，🚫 不许静默丢弃、也不许当新增行悄悄插进去**。
4. **列级 patch**：只覆盖该页签表征的列；页签没暴露的列**原样保留**，🚫 不许写 NULL（`AP-60` 列维度共因）。
5. **删除必须有显式墓碑** —— 「没出现」不等于「用户删了它」，可能只是这个视图本来就不查它。
6. 🚫 **N+1 红线**：整单一次算完，SQL 条数与轴值数 / 表数无关。

### B-10 · 预览扩展 `dsBackfill`
**服务的 AC**：AC-5, AC-14, AC-20
- 按 `api.md` §1 的形状扩 `BackfillPreviewDTO`，既有字段**一律保持原样**（老回填仍在用，AC-15）。
- 🚨 **描述「将写入什么」而不是「哪些值变了」**（`AP-60` 判据四）。必须输出 `untouchedRows` 与 `columnScope.preserved`。
- `result == "UNCHANGED"` 的组**仍要出现在列表里**，🚫 不许过滤 —— 否则财务分不清「这张表没变」与「这张表没被算进去」。
- 只读、无副作用、幂等（与既有 preview 同语义）。

### B-11 · `doCostingApprove` 挂新回填
**服务的 AC**：AC-6, AC-9, AC-18
- 挂载点：`QuotationService.doCostingApprove`（`:1607`），在既有 `quoteBackfillService.execute(id, currentUserId)` **之后**追加。
- 🚫 **必须调 `VersionedGroupWriter.writeGroups`（批量入口），不许在 for 循环里逐轴值调 `writeGroup`** —— 那正是本项目反复踩过的 N+1 形态（该类注释明写）。
- 新增两个常量，⚠️ **受列长约束**（实测 `source varchar(16)` / `archive_reason varchar(32)`）：写入来源 ≤16 字符、归档原因 ≤32 字符。
- 与状态机翻转**同一事务**：失败整体回滚，报价单保持 `SUBMITTED`（沿用既有语义）。
- AC-18 并发：沿用既有的状态闸（`!"SUBMITTED".equals(q.status)` → 400）+ `DatasetGroupLock` 表级 advisory lock（`VersionedGroupWriter` 内部已取，与维护端逐字同一把锁）。

### B-12 · 取消路径零副作用
**服务的 AC**：AC-7
- 预览是 `GET`、无副作用（既有语义）；财务不点确认即**没有任何写动作**。
- 断言口径：主表 / `_history` / `_record` 三者 md5 逐字未变，且**未写 `quotation_approval`**。

### B-13 · 免版本三表排除
**服务的 AC**：AC-1③, AC-5
- 回填与预览均**跳过**三张免版本表。判据用 `SheetDef.versioned == false`，🚫 不许写死表名。
- 依据用户原话：「这三张无版本的表定义的就是不需要回填的，是基础资料，不是料件组成版本资料」。

---

## 四、反向守卫

### B-14 · 老回填链路零改动
**服务的 AC**：AC-15
- `QuoteBackfillService` / `QuoteBackfillCollector` / `QuoteBackfillColumnMapper` 三个文件 **`git diff` 必须为空**。
- 🚫 顺手重构、顺手改注释都算违规 —— 这三个文件被第一段的 `AC-16` 当基线用。

### B-15 · 核价两套 + 选配不被波及
**服务的 AC**：AC-16, AC-17
- `VersionedGroupWriter` **只调用、不修改**。若发现必须改它，**停下报主线**（会波及核价两套 39 张带版本表）。
- 回归证据：`COST_BASIC` / `COST_DETAIL` 的导入与维护端保存，同一份夹具改动前后逐行 md5 相同。

---

## 五、⛔ 红线项（未获批准前不得执行）

### B-16 · S-7 全库清空
**服务的 AC**：AC-19
🚨 **`CLAUDE.md` §3.2 不可逆红线。子代理没有批准权。**
- 本项**只允许产出脚本与影响面报告**（逐表 `SELECT count(*)`、外键引用方清单、可恢复性说明），🚫 **不得执行任何 `DELETE` / `TRUNCATE`**。
- 执行由主线在拿到用户**当次明确批准**后进行。批准不跨操作、不跨会话。
- 遇到任何红线动作 → **停下报主线**，不要自行判断。

---

## 六、AC 覆盖矩阵（双向）

| AC | 认领方 | | AC | 认领方 |
|---|---|---|---|---|
| AC-1 | B-1, B-2, B-4, B-13 | | AC-11 | B-5, B-9 |
| AC-2 | B-5 | | AC-12 | B-8 |
| AC-3 | B-6 | | AC-13 | B-9 |
| AC-4 | B-7 | | AC-14 | B-10 |
| AC-5 | B-10, B-13 | | AC-15 | B-14 |
| AC-6 | B-9, B-11 | | AC-16 | B-15 |
| AC-7 | B-12 | | AC-17 | B-15 |
| AC-8 | B-2, B-3 | | AC-18 | B-11 |
| AC-9 | B-11 | | AC-19 | B-16（⛔ 仅出脚本） |
| AC-10 | B-9 | | AC-20 | B-9 |

**反向**：B-1~B-16 每项均已在上表指回至少一条 AC，无超范围项。

---

## 七、后端强制自检（「完成」宣告必须带这一行）

- `./mvnw -q compile` 0 错误
- 后端起得来：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → **401**（起不来说明 `DatasetSchemaSelfCheck` 没过）
- 迁移 `success = t`（查 `flyway_schema_history`）
- 🚫 **N+1 自检**：B-5 / B-9 / B-11 三处的 SQL 条数与轴值数无关，用日志或 `hibernate.show-sql` 计数自证
