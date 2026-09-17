# backtask · 元素价格支持 9 位小数

> **只按本文件实现后端。** AC 原文在 `需求文档.md §③`，本文件只标编号不抄原文 —— **两者有出入以 AC 原文为准并向主线报告**。
> 契约见 `api.md`。口径见 `需求文档.md §②`「口径」表：**写入 / 取价按 `HALF_UP` 四舍五入到 9 位**。

---

## 任务清单

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1~AC-6, AC-20 | **新迁移**重定义取价函数 `f_customer_element_price(text, date)`：结果列 `unit_price` 由 `ROUND(… , 4)` 改为 `ROUND(… , 9)`。**除这一个数字外函数体一字不改**（签名、返回列、`STABLE`、`LANGUAGE sql` 全保持）。🚫 迁移里**不许**有任何数据回写（存量报价单快照、存量价格版本一律不动，AC-6）。底稿取**库里现行定义**（`pg_get_functiondef('public.f_customer_element_price(text,date)'::regprocedure)`，开发库与测试库 md5 同为 `0b55508cabf384f70437c58c0e8990d3`），不要从 `V357`/`V358` 文件拼。版本号开发时实取（`ls db/migration` 与 `flyway_schema_history` 取最大号 +1，当前最大 `V444`，被占就顺延）；文件名 `V<N>__task260916_element_price_scale9.sql` |
| **B-2** | AC-7~AC-11, AC-19 | `PrecisionPolicy` 新增常量 `ELEMENT_PRICE_SCALE = 9` 与方法 `roundElementPrice(BigDecimal)`（null 安全，`HALF_UP`）。🚫 **不要复用 `DISPLAY_SCALE`**：那是显示边界，将来可能单独调整，不能把存储口径绑在它上面。前端 `precision.ts` 由 `F-0` 加同名同值常量（决策台账：两端必须同值） |
| **B-3** | AC-7, AC-8, AC-18 | **价格导入**（`PriceImportService` / `PriceImportRowWriter`）：① 单价先按 B-2 舍入到 9 位，**再**做「必须大于 0」校验（舍入后为 0 → 该行失败，文案不变 `单价必须大于 0`）② 写库用舍入后的值 ③ 结果行 `price` 返回舍入后的值 ④ 覆盖提示「原值 X → 新值 Y」里的数字改为**最多 9 位、去尾零**（现为 `setScale(4)`） |
| **B-4** | AC-9, AC-10, AC-18 | **手工新建 / 编辑单价**（`PriceMaintenanceService` 的 create / update）：单价先舍入到 9 位再做「大于 0」校验、再写库；变更日志快照里记录舍入后的值 |
| **B-5** | AC-18 | **单价变更历史文本**（`PriceTableService` 里把快照数值转文本的方法，现为 `setScale(4)`）：改为最多 9 位、去尾零。⚠️ 该文本**同时用于判断「单价是否变化」**—— 改完后 `101.13921 → 101.13922` 必须能产生一条单价变更 |
| **B-6** | AC-11, AC-19 | **客户策略保存**（`StrategyService` 的保存默认策略 / 新建例外 / 编辑例外）：`factor`、`premium` 先舍入到 9 位再写库；变更日志快照记录舍入后的值 |
| **B-7** | AC-19 | **策略变更历史文本**（`StrategyService` 里把快照数值转文本的方法，现为 `setScale(2)`）：改为最多 9 位、去尾零（同样用于变化判断） |
| **B-8** | AC-15 | **策略试算**（`StrategyService` 试算）：`finalPrice` 由 `setScale(4)` 改为按 B-2 舍入到 9 位；`rawValue` / `factor` / `premium` 原样返回（前端按最多 9 位显示） |
| **B-9** | AC-20 | 跑本任务相关的既有测试（至少 `elementprice/**`、`priceadjust/**`、`semanticgraph/Sec34PriceStrategyTest`、`task260902/CustDimElementPriceAcTest`、`datasource/sqlview/QuotePendingRewriterTest`），被本次口径**合理影响**而需要改断言的，**逐个列出「原断言 → 新断言 → 为什么是口径变化而不是回归」**；与本次无关的失败做 A/B（`master` vs 本分支）后如实报告，🚫 不许顺手改 |
| **B-11** | AC-18, AC-19, AC-21, AC-22 | 🆕 **（D-12，2026-09-16 开发中纳入）变更历史快照数字按字符串返回**：`GET /api/cpq/element-price/prices/history` 的 `snapshot.price`、`GET /api/cpq/element-price/strategies/history` 的 `snapshot.factor` / `snapshot.premium`，改为**规范十进制字符串**（去尾零、禁科学计数法，与 PRD 精度契约一致）。🚫 **不许经过 double**：快照解析须保留原始数字字面量（如 `USE_BIG_DECIMAL_FOR_FLOATS` 或逐字段取文本），`0.000000002` 必须是 `"0.000000002"` 而不是 `"2.0E-9"`；`changes` 的「A → B」文本也用同一精确值。`windowNum` 等整数结构字段**保持数字不变**。新增 / 修改 / 删除三种记录一致处理；不改库里存量日志。背景：主仓实测「策略变更历史」遇新增 / 删除记录整页崩溃（`value.trim is not a function`），「单价变更历史」新增摘要显示「—」 |
| **B-10** | AC-20 | **内网增量升级脚本** `deploy/db/update-260916-element-price-scale9.sql`，按 `deploy/db/README.md §②③` 写：文件头（源迁移号 · 日期 · 一句话）/ `CREATE OR REPLACE FUNCTION` / **函数体用单引号 `AS '…'`、0 个非 ASCII 字符、无 TAB**（B-1 迁移里若有中文注释，脚本里一律去掉）/ 函数区在文件末尾 / 末尾更新 flyway 基线号 / 自检 SQL。同步更新 README §①「当前节点」。**验证方式**：在 `cpq_db_test` 上 `BEGIN; <脚本>; <自检>; ROLLBACK;` 跑一遍（🚫 不建临时库、不 DROP 任何库），并跑 README §③ 的「引号感知不认美元引用」切分自检 |

---

## 强联动检查点清单（`change-protocol.md §2` 步骤 1，写代码前 / 写完后逐项勾）

| # | 检查点 | 预期 | 勾 |
|---|---|---|---|
| K-1 | 取价函数的直接调用方：`f_material_element_price` 实时分支、`PriceReconciler`（取当期价）、`PriceAdjustVersionGenerationService`（生成版本） | 都只读 `unit_price`，不自行再舍入；生成的新版本冻结价为 9 位 | ☐ |
| K-2 | 库里直接引用该函数的取数视图（`component_sql_view.sql_template`，开发库实查 **3 个**）与经 `f_material_element_price` 间接引用的（**13 个**） | `CREATE OR REPLACE` 同签名，视图无需重编译；取价结果自动变 9 位 | ☐ |
| K-3 | 建单时写本单元素快照（`ds_quote_element_bom_record.element_price`，列 `numeric(26,12)`） | 新建单为 9 位，**不回写存量**（AC-6） | ☐ |
| K-4 | 进程内缓存（取数结果 / 公式求值缓存） | 不改代码；合并后 8081 热重载即清空（主线亲验前确认已重载） | ☐ |
| K-5 | 价格版本「涨跌幅」计算（`divide(…, 6, HALF_UP)`） | 不改；输入单价精度变化是预期 | ☐ |
| K-6 | 全后端残留的「元素价格 / 系数 / 加价」写死位数 | 用 `/usr/bin/grep -a -rn "setScale(\|ROUND(" cpq-backend/src/main` 复扫，除 K-5 外**元素价格相关处 0 残留**，输出贴回报 | ☐ |
| K-8 | 历史快照字段类型变化（number → string）的消费方 | 前端 `PriceHistoryTab.tsx`（`isDecimalString(snap.price)`）与 `StrategyHistoryDrawer.tsx`（`formatDisplayDecimal(snap.factor/premium)`）本就按字符串写；全仓 `/usr/bin/grep -a -rn "prices/history\|strategies/history"` 复扫无其他消费方，输出贴回报 | ☐ |
| K-7 | 精度字段的接口形状 | 请求仍为十进制字符串（`DecimalStringDeserializer`），响应仍为规范十进制字符串（去尾零）；不新增 / 不改字段 | ☐ |

---

## 不在本文件范围（后端视角）

- 🚫 不改库列类型、不改表结构
- 🚫 不回算存量价格版本、不回算存量报价单快照（用户裁决）
- 🚫 不改导出 Excel 的数字格式
- 🚫 **不让价格导入写变更历史**（D-13：既有行为，本次不改）
- ✅ V445 已由主线单独并入 master（`ae6dae0f`，D-10），分支上同内容提交 `4330662e`；🚫 不许再改这个迁移文件（已应用到两个共享库）
- 🚫 不改调价 / 审核里的金额计算与涨跌幅口径

## N+1 自检口径

本任务新增的都是「单个数值舍入」，不应引入任何新查询。回报里写明：`N+1 自检：本次改动 N 处循环，均为纯内存运算，无查库 ✅`（或如实说明）。
