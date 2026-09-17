# test-report · S-全局（repair-260916）

> 片：**S-全局**（串行殿后）· 库：`cpq_db_260916`（验收库）· 实例：5196 → 8196
> 认领 AC：**AC-2 / AC-3 / AC-4 / AC-6 / AC-7 / AC-10**
> 用例：`cpq-frontend/e2e/repair260916-global-1-readonly.spec.ts`（T0、T3.1~T3.3）· `repair260916-global-2-write.spec.ts`（T3.4~T3.6）· `repair260916-global.helpers.ts` · `repair260916-global.config.ts`（无 globalSetup）
> 执行者：test-engineer（S-全局）· 未读实现代码 · 未 commit · 未启停 8196/5196 · 无 SQL 写入（psql 在 helper 里硬限只允许 SELECT）

## 0. 结论

| AC | 结论 | 证据来源 |
|---|---|---|
| AC-2 | ✅ 通过（核价两问按主线裁决 ①：在 COMP-0004 配置器里切数据集，不新建组件） | run 6 实跑 |
| AC-3 | ✅ 通过（同上） | run 6 实跑 |
| AC-4 | ✅ 通过 | run 6 实跑 |
| AC-6 | ✅ 通过 | run 6 实跑记录；run 10 续跑时按证据文件重评估 |
| AC-7 ①②③ | ✅ 通过 | run 6 实跑记录 + run 10 续跑时只读现查 |
| AC-7 ④ | ✅ 通过 | run 10 实跑 |
| E-2 | ✅ **观察手段有效**：同一套 md5 与计数判据在 AC-7 执行后看到了变化 | run 6 快照 |
| AC-10（8 步） | ✅ 通过（第 1、2 步与 T3.5 的重叠已按主线裁决 ② 处理） | run 10 实跑；第 1 步执行前截图来自 run 6 |

最终一轮 run 10（续跑模式，只跑写段）：`4 passed (2.1m)`，`exit=0`。原始输出：`证据/e2e/run10-续跑-控制台输出.log`。

## 1. 环境正身

- 8196：run 1~6 时 pid=1065514；WSL 重启后 pid=14381（主线重启）。进程环境变量 `DB_NAME=cpq_db_260916`。验收库 flyway `444 success=true`。`/quotations` 的 `totalElements` 与库内计数一致（run 1 时为 196=196）。完整记录见 `证据/e2e/00-环境正身.txt`，共 66 行，每轮追加。
- 开跑前采样 `pgrep -f "node.*[p]laywright test"`，每轮结果均为无进程。采样时刻：02:48:02Z、02:49:35Z、02:51:00Z、02:53:37Z、02:55:41Z、02:58:52Z、03:29:23Z、03:34:30Z、03:35:57Z、03:40:55Z、03:45:08Z。

## 2. 执行经过（全部轮次）

| 轮 | 范围 | 结果 | 原因与处置 |
|---|---|---|---|
| 1 | 全量 | T3.1 挂，写段被锁 | 量具问题：取数树里搜到的第一个文字节点不可见，而且 config 里的 `devices['Desktop Chrome']` 视口把尺寸覆盖成了 1280。修选择器与视口 |
| 2 | 全量 | T3.1② 挂 | 量具问题：切数据集弹窗的按钮文案是「确认切换」。补进确认匹配 |
| 3/4 | 全量 | T3.1② 挂 | 量具问题：下拉定位到了隐藏的下拉实例；另有第二个弹窗，按钮是「继续切换」。修正 |
| 5 | 全量 | T3.2（明细核价）挂 | 环境问题：5196 登录页白屏（HTTP 200，React 未挂载，没有 pageerror）。uiLogin 加 30s 等待并重载一次 |
| 6 | 全量 | 只读段 13/13 通过 → T3.4 通过 → **T3.5 已执行 AC-7** → 随后 API 断线（ECONNRESET），WSL 重启 | 这一轮的控制台日志随 /tmp 一起丢失；证据文件都已落盘 |
| 7 | 写段·续跑 | T3.5 挂 | 量具问题：`GET /builder` 返回体没有 `{data}` 外层，改为兼容读取 |
| 8 | 写段·续跑 | T3.6 第 4 步挂（此前已建出模板 v1.7） | 量具问题：选定客户后，名称框的 placeholder 变成「请填写报价单名称」，改为按字段标签定位 |
| 9 | 写段·续跑 | T3.5b 挂 | 环境问题：`/components` 白屏。新增 `gotoReady`：等待 45s，白屏则重载一次，并记录 pageerror 与失败请求 |
| 10 | 写段·续跑 | **4/4 通过** | `gotoReady` 触发重载 5 次（/components 两次，失败请求是 `auth/me net::ERR_ABORTED`；/quotations/new、详情页、旧单详情页各一次，没有记录到错误） |

判定：挂掉的轮次全部是量具或环境问题，没有一次是断言本身失败。所有修复都只改定位与等待方式，没有改任何断言或期望值。

## 3. 用例实际值（逐字，取自证据文件）

### T0 量具自检（run 6）
- T0.2 E-1：基线文件 `E-1-COMP-0004-预览-8081.json` 中 00144 → `[null]`。判据对改动前基线判「未修」（false），对修好的副本判「已修」（true）。✅
- T0.3：改动前 COMP-0004 的 SQL 里找不到 COALESCE，`material_recipe` 的 JOIN 数为 0，`ds_quote_material` 的 JOIN 数为 1；`ds_quote_material` 不会误匹配到 `ds_quote_material_bom`。✅
- T0-one-shot（02:59:06Z，执行前）：3 个视图的 md5 = 基线 `ba3c87bc…/b56b315f…/d92b26ab…`，`recompileAudit=0`。

### T3.1 · AC-2（run 6）
- COMP-0004：分组 `来料固定加工费`，字段为 `销售料号,项次,投入料号,材料名,基准值,比例（%）,货币,计价单位,是否随材料价格波动,材料结算涨幅比例（%）,材料固定的涨幅值,涨幅货币,涨幅单位`。「材料名」×1，没有「材质」分组。✅
- 基础核价 → 来料加工费：分组 `来料加工费`，字段为 `生产料号,项次,来料料号,材料名,加工费,币种,计量单位,损耗（%）`。「材料名」×1。✅
- 明细核价 → 来料其他固定费用：分组 `来料其他固定费用`，字段为 `生产料号,项次,来料料号,材料名,要素项次,要素名称,费用,币种,计价单位`。「材料名」×1。✅

### T3.2 · AC-3（run 6，界面「生成的 SQL」原文；已核对与 compile 响应一致）
- COMP-0004：`COALESCE(dqm.material_name, mr.symbol) AS "_物料_材料名"` ①；`LEFT JOIN material_recipe mr ON mr.code = dqiff.input_material_no` ②（单条件，不含 customer_no）；`LEFT JOIN ds_quote_material dqm ON dqm.material_no = dqiff.input_material_no AND dqm.customer_no = dqiff.customer_no` ③。✅
- 基础核价：`COALESCE(dcbm.material_name, mr.symbol) AS "material_name"`；`LEFT JOIN ds_cost_basic_material dcbm ON dcbm.production_no = vdcbipfa.incoming_material_no`；`LEFT JOIN material_recipe mr ON mr.code = vdcbipfa.incoming_material_no`。✅
- 明细核价：`COALESCE(dcdm.material_name, mr.symbol) AS "material_name"`；`LEFT JOIN ds_cost_detail_material dcdm ON dcdm.production_no = vdcdioffa.incoming_material_no`；`LEFT JOIN material_recipe mr ON mr.code = vdcdioffa.incoming_material_no`。✅
- 全文：`证据/e2e/AC-3-*.txt`。

### T3.3 · AC-4（run 6，预览接口原始响应；请求体确认带了所填料号）
| 组件 / 料号 | rowCount | (投入料号, 材料名) | diagnostics |
|---|---|---|---|
| COMP-0004 / S3120011203 | 2 | (00144, H85), (S3110520422, 料号2) | [] |
| COMP-0005 / S3110520422 | 3 | (00256, TU2丝), (00256, TU2丝), (00257, 羰基镍粉) | [] |
| COMP-0005 / S3120011203 | 3 | (00144, H85), (S3110520422, 料号2) ×2 | WARN COLUMN_ALL_NULL `_来料其他费用_值`（不涉及材料名列） |
| COMP-0008 / S3120011203 | 1 | (00144, H85) | WARN COLUMN_ALL_NULL `_来料回收折扣_回收折扣（%）`（不涉及材料名列） |

没有材料名为空的行，材料名列没有 ERROR。屏幕上的预览表同样显示这些名称。✅

### T3.4 · AC-6（来源：run 6 实跑记录；run 10 重评估）
- ①：`HTTP 200`，`preview=true`，`componentCount=3`，`views=3`，`changed=3`，`changes=["builder_c35c2bd590fe","builder_4602c64a0c38","builder_46f244df7ede"]`，`unchanged=[]`，`skipped=[]`。每项 `newSqlTemplate` 含 `material_recipe`，`oldSqlTemplate` 不含。✅
  - 附带检查：`md5(oldSqlTemplate)` 与调用前落库的 md5 逐一相等（ba3c87bc… / b56b315f… / d92b26ab…）。
- ②：调用前 03:02:37.591Z → 调用后 03:02:38.620Z，逐项 diff 为 `[]`。`component_sql_view` 全表 md5 前后均为 `24c66e77e1b1b8a48240a910dee02aa4`；`operation_log` 前后均为 170 行。✅
- md5 清单：`证据/AC-6-7-md5-前后.md` 的「AC-6 调用前」「AC-6 调用后」两节。

### T3.5 · AC-7 ①②③（run 6 实跑记录 + run 10 续跑时只读现查）
- 执行前全量预览（run 6）：9 个视图名，包含 3 个目标视图。✅
- ①（run 6）：`HTTP 200`，`preview=false`，`changed=3`，`changedViewNames=["builder_4602c64a0c38","builder_46f244df7ede","builder_c35c2bd590fe"]`，`operationLogIds=["f8923343-…","c8696ffd-…","ee56d1be-…"]`。
  - 续跑现查的审计行：三行都是 `COMPONENT_VIEW_RECOMPILE / COMPONENT`，`target_id` 依次为 57554055…（COMP-0005）、39fa3d9d…（COMP-0008）、4db28822…（COMP-0004），与 `changedViewNames` 同序；`created_at` 为 03:02:44.938~.940Z。✅
- ②
  - 续跑现查：落库 md5 与 AC-6 响应的 newSqlTemplate md5 相同（c35c=`9a4a3949…`，4602=`0aee4c4e…`，46f2=`829a17e8…`），逐字全文比较相等。✅
  - 执行后全量预览（run 6）：6 个视图名，不含 3 个目标视图。✅
  - `declared_columns` md5 执行前后相同（73c06f18… / 0ffe0014… / 16a5d44f…），字节相同即列名集合相同。✅
  - 续跑现查：3 个组件的 `builder_version`=1，等于 `currentCompilerVersion`=1。✅
- ③（run 6：AC-6 调用后快照 → AC-7 执行后快照）：3 个组件的六个字段、施耐德5.4 全部 7 个版本（两份快照、`updated_at`、各自快照行 md5）、其余全部视图（含 COMP-2413/2296/2414/2424）、全部模板，diff 均为 `[]`。✅
  - 续跑现查（03:34:56Z）与执行后快照比，3 个视图的 sql_md5 和 updated_at 无变化，即执行后的全量预览没有写入，此后也没人改过这 3 个视图。✅

### E-2 · 零写入判据的阳性对照（来源：run 6 快照）
```
before 03:02:38.620Z  viewsTableMd5=24c66e77e1b1b8a48240a910dee02aa4  opLogCount=170
after  03:02:45.660Z  viewsTableMd5=31fa1ad3abaf6f7e0dece4b182590daa  opLogCount=173
viewsTableMd5Changed=true  threeSqlChanged=true  opLogDelta=3  recompileAuditDelta=3
```
**结论：有效。** AC-6 与 S-1 用来判「零写入」的这套手段（视图全表 md5、单视图 md5、`operation_log` 行数），在真实写入发生时能看到变化。文件：`证据/e2e/E-2-观察手段阳性对照.json`，md5 清单末尾也有同名一节。

### T3.5b · AC-7 ④（run 10）
`GET /builder` 返回 `isStale=false`、`builderVersion=1`、`currentCompilerVersion=1`；页签文字里没有「过期」；拦截器没有拦到任何写请求。截图：`AC-7-4-COMP-0004-取数配置-执行后.png`。✅

### T3.6 · AC-10（run 10）
1. COMP-0004 预览（CUST-0004/S3120011203）返回 rowCount=2，00144 → `H85`。截图：执行前为 `AC-10-step1-…-执行前.png`（run 6），执行后为 `…-执行后.png`（run 10）。✅
2. AC-7 执行证据为 200、changed=3。此刻再预览：`HTTP 200`，`views=3`，`changed=0`，`unchangedViewNames=[3 个]`；落库文本等于 AC-6 的新文本。✅
3. 从 v1.6（9516751e）创建新草稿并发布，得到 **v1.7（fbb0545d-73f3-497e-8649-af968f86c42b）**，状态 PUBLISHED。
   - 附带核对：v1.7 快照里 3 个视图的 SQL 都含 `material_recipe`，且等于当前落库文本；v1.6 快照里不含。
   - 截图：step3a / step3b / step3c。✅
4. 新建报价单 **QT-20260916-0880**（b276f66e-…），`project_name=R260916-G-AC10`，模板为 v1.7，含产品 S3120011203，保存草稿请求返回 2xx。来料固定加工费页签：00144 → `["H85"]`，S3110520422 → `["料号2"]`。✅
5. 来料其他费用：00144 → `["H85"]`；来料回收：00144 → `["H85"]`；切回来料固定加工费：00144 → `["H85"]`。✅
6. F5 后回到 `/quotations/b276f66e-…/edit`，00144 → `["H85"]`。✅
7. 详情页 00144 → `["H85"]`。✅
8. 旧单 **QT-20260916-0877**（v1.4）详情页：00144 → `["—"]`，S3110520422 → `["料号2"]`（阳性对照）。✅
   - 打开前后时间戳没有变化（`updated_at` 为 01:06:16.881588Z），打开详情页没有触发补算写入。
- 第 4~8 步每一步都检查了：`加载中=0`、`error=0`、`nullCells=0`。✅

## 4. 本片实际写入（验收库 `cpq_db_260916`，截至 03:48Z 现查）

| 对象 | 内容 |
|---|---|
| component_sql_view | 3 行（builder_c35c2bd590fe / builder_4602c64a0c38 / builder_46f244df7ede），updated_at 为本地时间 20:02:44（即 03:02:44Z） |
| operation_log | 3 行 COMPONENT_VIEW_RECOMPILE。02:00Z 之后没有其他新增行 |
| template | 1 行：施耐德5.4模板 **v1.7**（fbb0545d-…，PUBLISHED，创建于 03:37:06Z）。v1.0~v1.6 的 updated_at 未变 |
| quotation | 1 行：**QT-20260916-0880**（b276f66e-…，DRAFT，`R260916-G-AC10`）及其行项。报价单总数 196 → 197 |
| component | 0 行变更 |

没有 DROP 或 DELETE。本片也没有新建数据库。验收库的回收由主线随闸门 B 呈报。

## 5. 续跑模式改了哪些代码（只改流程，不改判据）

- `repair260916-global-2-write.spec.ts`
  - 新增开关 `R260916_RESUME_FROM_EVIDENCE=1`。打开后，T3.4/T3.5 的响应与快照 s0/s1/s3 从 run 6 的证据文件读取，能现查的部分做只读现查；两种模式共用同一组 `expect`。
  - 续跑模式不覆盖任何 run 6 证据，另写 `AC-7-snapshot-续跑现查.json` 与 `AC-7-operation_log-续跑现查.json`，并在 md5 清单追加「续跑现查」一节。
  - 有两处等价替换：
    - 执行前那份快照 s2 当时只在内存里，改用 s1。run 6 在执行前硬断言过「s1 与 s2 一致」，而执行确实发生了，说明这条断言当时通过了。
    - 列名集合的比较改为比较 `declared_columns` 的 md5，字节相同比列名集合相同更严格。
  - T3.6 发布步骤改为可续跑：若状态文件里的模板仍是 DRAFT，就发布这张草稿，不再新建。
  - 快照核对改为在 SQL 里比较，原写法 `sqlScalar` 只取第一行，比多行 SQL 会出错。
- 量具修复：`GET /builder` 返回体没有外层；名称输入框改按字段标签定位；下拉只取可见选项（`visible=true`）；两个确认弹窗按钮「确认切换」「继续切换」；`gotoReady`（白屏时重载一次）与 `uiLogin` 的重载；组件树只点可见节点；config 里 projects 显式设定视口。
- 为什么续跑时没有重跑只读段：重跑会用执行后的状态覆盖 AC-2/3/4 和「AC-10-step1-执行前」这些执行前证据，所以只跑了写段。read-only 标记文件来自 run 6，当时只读段全绿，文件生成于 03:02:35Z。

## 6. 未验证项

1. 拦截写请求的守卫和视图指纹守卫本身，没有做「故意破坏后确认它会硬失败」的实验。
2. run 6 的控制台日志因 WSL 重启、/tmp 被清而丢失。只读段每条用例的通过结果，是本会话从实时监控里看到的，另有证据文件与 marker 可对照。
3. AC-10 的「全程不出现加载中、报错、null 字面量」只在第 4~8 步检查了，第 1~3 步（配置器页、模板页）没有检查。
4. AC-2/3 核价部分没有按「新建组件」的原路径验证（主线裁决 ① 已接受替代做法）。

## 7. 观察（不影响本片结论，报主线）

- **5196 dev server 间歇性白屏**：HTTP 200，React 未挂载，没有 pageerror，偶尔伴随 `auth/me net::ERR_ABORTED`。run 10 里触发重载 5 次。属于环境问题；主线亲验时如遇白屏，重载即可。
- COMP-0005 / COMP-0008 的预览里有 `COLUMN_ALL_NULL` 类 WARN，出现在费用、回收折扣这两列，与本任务的材料名列无关。
- 核价两种数据集下，材料名列的视图列名是 `"material_name"`。AC-3 对核价侧没有要求列名，只作记录。
- 发布 v1.7 没有产生 operation_log 行，也没有改动 v1.0~v1.6 的 `updated_at`。
