# 后端任务分解（闸门 A 草案）

> A0/闸门 A 已裁决四项方案；本文件同时作为实现与回归检查清单。当前实现已落地，未覆盖项以 `test-report.md` 为准。

## B-1 数据库迁移与种子（服务 AC-1、AC-2、AC-5、AC-8、AC-9、AC-10）

1. 新增 `tax_category`：`id uuid PK`、`code varchar(64) unique`、`name varchar(128)`、`status`、`is_default boolean`、审计列；建立“最多一个默认分类”唯一部分索引。
2. 新增 `tax_category_vat_version`：`id uuid PK`、`tax_category_id FK`、`vat_rate numeric(7,4)`、`effective_from date`、`effective_to date null`、`status`、审计列；`CHECK vat_rate BETWEEN 0 AND 100`、日期合法约束、分类/区间索引。
3. 用 PostgreSQL exclusion constraint（`daterange(effective_from, coalesce(effective_to,'infinity'::date),'[)') WITH &&`）阻止同一分类区间重叠；若项目迁移策略不允许该扩展，使用同事务锁 + 显式冲突查询并补并发测试。迁移中的约束创建必须用显式存在性检查，不能写 PostgreSQL 不支持的 `ADD CONSTRAINT IF NOT EXISTS`。
4. `ds_quote_material` 增加 nullable `tax_category_id` FK；为该 FK 和版本表 FK 建索引，保留客户维度唯一键 `(customer_no, material_no)`。不向 `ds_quote_customer_part` 增加 VAT 列。
5. `quotation` 增加 `default_vat_rate numeric(7,4)` 和来源字段（允许存量 NULL）；`quotation_line_item` 增加 `tax_category_id`、`tax_category_version_id`、`vat_rate`、`vat_rate_ratio`、`vat_source`、`vat_effective_from`、`vat_resolved_at`。快照列允许存量 NULL，不能回填存量。
6. 初始化插入 `GENERAL_GOODS / 一般货物 / is_default=true` 及 `13.00`、起始日期为迁移执行日的有效版本；使用幂等 upsert，不能覆盖已有用户配置。
7. 迁移只加结构和默认种子，不更新历史产品、报价、模板快照或旧 `customer_tax` 数据。

## B-2 实体、DTO 和分类管理服务（服务 AC-1、AC-8、AC-9、AC-10）

1. 新增 `TaxCategory`、`TaxCategoryVatVersion` Panache entity / repository / DTO；版本新增、停用、默认分类切换均在事务内完成。
2. `TaxCategoryService` 实现编码规范化、版本区间冲突、引用后不可物理删除、默认分类唯一性和生效日期解析。
3. 分类/版本资源按既有菜单权限映射执行；后端注解与服务层均校验，不能仅依靠前端按钮隐藏。
4. 未设置产品分类时解析默认分类；默认分类无有效版本时抛出 `DEFAULT_TAX_CATEGORY_NO_EFFECTIVE_VERSION`，不得返回 0。

## B-3 客户默认 VAT 与产品绑定（服务 AC-1、AC-2、AC-8、AC-9）

1. `Customer`、创建/更新 DTO、列表/详情 DTO 增加 `defaultVatRate`；空值与 0% 区分，校验 `[0,100]`。
2. 客户 Excel 模板与导入预校验增加 `default_vat_rate`；任一行错误整份拒绝，沿用现有导入错误聚合格式。
3. `ds_quote_material` 读写 DTO/Registry/历史记录/序列化链路同步增加 `tax_category_id`；产品导入空分类使用默认分类并在预览返回 `defaultApplied=true`、影响行数。
4. 未知分类编码整批拒绝，错误至少含 sheet、Excel 行号、列名、原值；事务中不得部分落库。
5. 客户产品查询只 JOIN 销售产品绑定，返回未设置状态，不把客户默认值写回产品字段。

## B-4 VAT 快照服务与所有新增行入口（服务 AC-1、AC-2、AC-5、AC-6、AC-8）

1. 新增 `VatSnapshotService.resolveForNewLine`，统一按“销售产品有效分类版本 → 报价头默认 VAT → 默认分类有效版本”解析。
2. 接入 `QuotationService.create` 初始化报价头默认值。
3. 接入 `QuotationLineItemMaterializeService` 的基础数据导入建行路径。
4. 接入 `ConfigureProductService.insertLineItem` 的 SIMPLE、COMPOSITE 父行、PART 子行路径；每个新行单独快照。
5. 复制报价/其它隐藏建行入口必须通过代码搜索和调用图核对，禁止漏掉旁路；原位更换产品不新增，删除+添加自然产生新行快照。
6. 现有重算、价格编辑、保存草稿和刷新卡片值路径禁止调用主数据 VAT 解析；只使用行快照字段。

## B-5 公式 token、三套求值器和无上下文状态（服务 AC-3、AC-4、AC-11、AC-12）

1. 后端标签元数据注册 `vat_rate`，编辑器显示“增值税”；token 求值统一读取报价行 `vat_rate_ratio`。
2. 同步检查并实现 `FormulaCalculator`、`FormulaCalculationService`、其它 `FormulaEngine`/FormulaEvaluateResource 可达路径；不能只改一套。
3. 前端序列化 token 与后端 token 名称固定为 `vat_rate`；13% 必须变为 `0.13`，不能变为 13 或 1.13。
4. 没有报价行快照的上下文返回结构化“待报价税率”状态；不能用系统当前税率或默认分类偷偷补值。核价/预览调用点按该状态渲染，不把状态当金额。
5. 旧 token/普通字段“税率”保持旧解释；不得迁移历史 token。

## B-6 含税元素单价与输出（服务 AC-4、AC-5、AC-7）

1. 本期不在 QUOTE、COST_BASIC、COST_DETAIL 的 BOM 价格策略结果中增加 `taxIncludedUnitPrice` 字段，避免改动现有 BOM 配置契约。
2. 公式求值仍统一使用报价行 VAT 快照；用户如需含税计算，必须在组件/模板公式中显式引用 `[增值税]`，不得由系统自动加税。
3. HTML/PDF/Excel 输出若展示 VAT 字段、税额或含税结果，只读报价行快照；不得在导出时按当前税率重算。

## B-7 权限、审计与并发（服务 AC-8、AC-10、边界 AC）

1. 复用对应菜单现有角色集合；税率相关写接口、客户默认值、产品绑定在后端执行同等菜单权限检查。
2. 分类/版本/客户默认值/产品绑定记录 `updated_by/updated_at`；已被快照引用的版本不可删除。
3. 版本重叠、默认分类切换、同一产品并发绑定需要事务级并发测试。

## B-8 后端完成定义

- migration 可在空库执行且种子幂等；不改存量快照。
- 所有新增行入口均经过同一快照服务；重算/导出无主数据回查覆盖。
- Java 单测覆盖 0%、6%、9%、13%、边界日期、重叠版本、无上下文状态和精度。
- API 契约、实体字段、Registry/历史记录/导入导出同步完成；`git diff --check` 与后端编译通过。

**AC 追溯**：B-1~B-7 合计覆盖 AC-1 至 AC-12；其中 AC-12 由 B-5 的旧 token 兼容和 B-6 的历史输出只读快照共同验证。
