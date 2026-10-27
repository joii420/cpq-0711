# 后端任务：物料利润字段

## 任务分解

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| B-1 | AC-1、AC-3、AC-11、AC-12 | 新增加法 Flyway：`ds_quote_material.profit numeric(26,12) NULL`；向报价 `MATERIAL` 语义节点追加 `profit / 利润 / NUMBER / sort_order=9`。迁移内做存在性与唯一性自检，不改历史迁移。 |
| B-2 | AC-1、AC-2、AC-3、AC-4、AC-5 | `QuoteRegistry` 在物料列尾追加利润，声明 DECIMAL、非必填、`partEditable()`、`preserveOnNull()`；沿用通用数字校验和 COALESCE 写入。 |
| B-3 | AC-1 | 修改正式报价 XLSX，在「物料」Sheet 末尾追加黄色选填列「利润」，重跑 `gen_matrix.py` 更新字段矩阵，并验证工作簿其他 Sheet/格式未损坏。 |
| B-4 | AC-6、AC-13 | `DsPartsPage.Item` 增加动态 `profit` 键；`DatasetMaintenanceService.listParts` 按 Registry 判断列存在，在原 page SELECT 中取回并以十进制字符串输出，保持两条 SQL。 |
| B-5 | AC-8、AC-9、AC-10 | 通用 `PUT /dataset/{dataset}/parts/{axisValue}` 通过 Registry 白名单接受 `profit` 键，复用 `DatasetValues.coerce` 数字转换；保留客户维度、多行守卫、审计和 source 不变语义。 |
| B-6 | AC-11、AC-12 | 冷启动加载语义图后验证 field-tree、compile、preview；不修改 `FieldTreeBuilder`/`SemanticCompiler` 的字段特例，不改存量配置。 |
| B-7 | AC-6、AC-13 | 同步 `dev-docs/main-api.md` 与必要代码注释，明确 quote 专有 `profit` 字符串/null 契约和可编辑白名单。 |
| B-8 | AC-14、AC-15、AC-17、AC-19 | 新增加法 Flyway（候选 `V455`）：`quotation_line_item.profit_snapshot numeric(26,12) NULL`，不写回填 SQL；实体与 `QuotationDTO.LineItemDTO.profit` 贯通，读 DTO 输出十进制字符串或 `null`。 |
| B-9 | AC-15、AC-18、AC-19 | 新报价行创建时按 `(customer_no, material_no)` 冻结利润：覆盖 saveDraft 新增行、导入批量物化、从已有产品候选、基础数据候选、选配与绑定已有产品；采用整单批量/现有 JOIN 或 INSERT 内联子查询，禁止 N+1。 |
| B-10 | AC-14、AC-17、AC-18 | 既有行 saveDraft/重算/刷新均不补、不改利润；报价复制逐值继承源行 `profit_snapshot`，源行 `NULL` 继续为 `NULL`。 |
| B-11 | AC-15、AC-18、AC-19 | 候选接口、已有产品接口和选配响应增加精度安全的 `profit` 字符串/null；`CustomerPartCandidateDTO` 现有类级 `NON_NULL` 下须给利润字段显式标 `ALWAYS`，保证空利润返回 `profit:null`；补全 `main-api.md` 契约与后端路径测试。 |
| B-12 | AC-20 | 执行新增迁移冷启动、Schema/Flyway 检查、后端定向与相关回归测试，记录 SQL 条数与历史行零回填证据。 |

## 迁移纪律

- 首批利润字段已经使用 `V454`；2026-10-26 闸门 A 前五方复核后，报价行利润快照迁移当前候选为 `V455`，但这不是最终保留号。
- 创建迁移前重新核对：主仓迁移目录、全部 worktree、开发库 `flyway_schema_history`、测试库 `flyway_schema_history`、`target/classes` 构建输出。
- 迁移只做加法：`ADD COLUMN IF NOT EXISTS` + 幂等语义列插入；不得修改已应用的 `V413` 或清理存量数据。
- 语义列使用稳定 UUID，限定 `node_key='MATERIAL' AND dialect='QUOTE'`，唯一落在当前物理表 `ds_quote_material`。
- 迁移末尾验证：物理列类型/精度/可空性正确，ACTIVE 语义列恰好一条，`db_column=profit` 不与现有列重复。
- 扩围迁移候选 `V455`，开工前重新核对主仓、全部 worktree、开发库、测试库和构建输出；只 `ADD COLUMN IF NOT EXISTS profit_snapshot numeric(26,12)`，严禁 `UPDATE` 存量报价行。

## 导入语义

- Header：字段非必填表示单元格可空，不表示表头可缺；沿用 `DatasetSheetParser` 的完整表头契约。
- 值域：现有 `BigDecimal` 解析接受有符号数字、零、小数和合法科学计数法；不另造正数限制。
- 精度：数据库 `numeric(26,12)` 是最终精度/scale 边界；错误必须转为可读 400，不返回 500。
- 空白：`preserveOnNull` 只影响导入 upsert；页面 PUT 显式 `null` 仍然清空。
- 原子性：利润非法与其他导入校验失败一样，在写入前整份拒绝。

## 查询与 N+1

- 列表继续使用 1 条 count + 1 条 page SELECT；利润追加到 page SELECT 的动态列序列，不做逐行回查。
- 更新继续使用 1 条 UPDATE + 1 条审计回读；字段数量和列表行数不增加 SQL 条数。
- 字段树由启动时已加载的不可变语义图快照遍历，新增字段不触发逐项查库。
- 报价行利润快照解析必须与行数解耦：候选/物化复用 SELECT 列，saveDraft 对本批新增料号一次批量解析，选配 INSERT 用已批量解析结果；不得在行循环内查 `ds_quote_material`。

## 不做

- 不新增利润专用 REST 端点。
- 不增加数据库触发器或派生计算。
- 不更新已有组件/模板快照。
- 不给核价侧物料表加同名列。
- 不给历史报价行回填利润，也不新增“刷新利润”入口。
- 不把利润接入公式、金额、折扣、VAT 或导出服务。
