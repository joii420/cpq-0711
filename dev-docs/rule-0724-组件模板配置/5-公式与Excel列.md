# 5 · 公式与 Excel 列

> **来源**：`配置方法论-合并版.md` §5.1（公式 token 全集）+ §3.4/§3.4.1（cross_tab_ref + 多 source SUM / KSUM）+ §11（AP-44 字段类型联动协议）+ §4（Excel 模板列配置）+ `反模式.md AP-37/AP-44`。
> **料号列绑定值铁律见 `1-总则与工作流.md §1.3`；`$view` 机制见 `3-SQL视图.md`。**

## 5.1 公式引擎

### 存储与引用
- 公式存 `component.formulas` JSONB —— 结构 `{ "<formula_name>": [<token>...] }`（一个组件多条命名公式）。
- 字段 `field_type=FORMULA` + `formula_name` 指向 `formulas` 里的某条 token 数组。
- 引擎：前端 `formulaEngine.ts` + 后端 `FormulaCalculationService` / 卡片引擎 `FormulaCalculator`，前后端语义对等。

### token 类型（组件 formulas 核心集）

| token type | 用途 | 关键键 |
|---|---|---|
| `field` | 引用同行/同页签其他字段 | `value`（字段名） |
| `b_field` | 引用 B 当前行某列 —— **仅用于 `cross_tab_ref.targetExpr` 内部**，非顶层可配 | `value`（列名） |
| `component_subtotal` | 跨组件小计（对方组件需有 `is_subtotal=true` 字段） | `component_code` 或 `tab_name` |
| `cross_tab_ref` | 跨页签 VLOOKUP / SUMIF（见下） | `source` + `match` + (`predicate`) + (`target` 或 `targetExpr`) + `agg` |
| `operator` | `+ - * /` | `value` |
| `number` | 数字常量 | `value` |
| `bracket` | 括号（`bracket_open` / `bracket_close`） | - |
| `__amount_total__` | 金额总额哨兵 —— 引用当前上下文的金额合计 | - |

> 前端 `formulaEngine.ts` 完整 union 达 **14 种**（另含 `path` / `previous_row_subtotal` / `product_attribute` / `quotation_field` / `datasource_field` / `global_variable`），后端实现其中 9 种；上表为组件 `formulas` 配置常用核心集，完整清单与前后端实现分工见 `配置方法论-合并版.md §5.1`。
> **重要**：公式引用 DATA_SOURCE 字段用 `datasource_field`（非 `field`）；`global_variable` 的 `path` 由前端编译，不要手写。

### cross_tab_ref —— 跨页签引用（VLOOKUP / SUMIF）
B 页签 FORMULA 字段按「A.列 = B.列（可多列 AND）」匹配**同卡片（同目录）**内 A 页签已算行，取值（`agg=NONE`）或聚合（`SUM`/`AVG`/`COUNT`/`MAX`/`MIN`）。

```json
{ "type": "cross_tab_ref", "source": "<A组件componentId>", "sourceLabel": "A组件名",
  "target": "目标列名", "match": [{"a": "A列", "b": "B列"}], "agg": "NONE" }
```

- **`match`**：行匹配列对数组 `[{a,b}...]`，多对 AND；`predicate`：可选附加过滤谓词。
- **`targetExpr`（进阶）**：目标列可改用公式，内含 `field`（A 匹配行列）、`b_field`（B 当前行列）、四则、`global_variable`；对每匹配 A 行先算再按 `agg` 聚合（SUMPRODUCT 式）；`targetExpr` 非空时优先于 `target`。
- **语义边界**：0 行匹配→0；`NONE` 多行匹配→整公式报错按 0；聚合非数字→按 0；空/纯空白键不参与匹配；`COUNT` 无需 target。
- **范围**：仅同卡片（同目录）其他组件，禁跨目录；禁循环依赖（模板 publish 时 `TemplateService` 拓扑校验，有环拒绝）；计算顺序按 `CrossTabComponentOrder.topoOrder`（Kahn BFS，A 先于 B）。
- 配置入口：组件管理 → B 组件 → FORMULA 字段 → 公式区「跨页签引用」→ `CrossTabRefDrawer`。

### 多 source 链式 SUM + KSUM 降维预聚合
在 `SUM/AVG/MAX/MIN/COUNT(...)` 的行级表达式里可跨多页签运算：
- **多 source 链式 SUM**：外层 SUM 内引用多个非宿主 source 页签，合法前提是各 source 与宿主 `row_key_fields` **两两可比**（`⊆`/`⊇`）；驱动 = 最细 source，更粗 source 按公共行键广播（0 命中→0，1 命中→取值，>1 命中→报错改用 KSUM）。
- **KSUM/KAVG/KMAX/KMIN/KCOUNT**：内层降维算子，对单个被聚合页签按宿主行键塌缩成标量，绕开「互不包含维度→笛卡尔积」硬限制。
  - 折叠 token 形态：`{ "type":"cross_tab_ref", "projectToHostKey":true, "source":"<被聚合页签id>", "agg":"SUM", "match":[...], "targetExpr":[...] }`。
  - **空集分流**：`KSUM`/`KCOUNT` 命中 0 行 → 0（静默）；`KAVG`/`KMAX`/`KMIN` 命中 0 行 → null → 整外层塌 0 + ⚠ 错误标记。

🚨 **KSUM 硬约束**：
1. **不允许嵌套跨页签引用**：`KSUM` inner 白名单仅 `field`（限被聚合页签自己的列）/ `operator` / `number` / `bracket` / `global_variable`；拒 `b_field`（宿主列）/ 跨页签 `field` / `component_subtotal` / 嵌套 `cross_tab_ref`（前端序列化期 + 后端 `TokenMappabilityValidator` 双端镜像拒绝）。
2. **不允许 KSUM 套 KSUM**（决策 J，双端拒）；顶层裸 KSUM 也报错（决策 M，必须写在外层 SUM/AVG/MAX/MIN/COUNT 内）。
3. 同一页签不能在同一 SUM 内既被 KSUM 聚合又被裸引用（决策 I2，语义二义）；单列 KSUM 强制折叠成 `targetExpr`（`target=''`）；`K SUM(...)` 不可拆写（C3，必须连写）。
4. **Excel 模型 B 降级**：Excel 列模型（`TabJoinPlanEvaluator`）暂不支持 KSUM / 多 source 链式 SUM，遇此类 token 显式抛错（非静默少算），上层降级为该列空值 + warn，改用页签连表渲染（模型 A）。

> token 模型：`FormulaToken` 纯新增 `sources`（多 source 链）+ `projectToHostKey`（KSUM），**无 DB 列、无 snapshot 迁移**；前后端求值由共享夹具 `cross-tab-cases.json` 锁一致。

## 5.2 🚨 字段类型联动协议（AP-44 核心规范）

> **核心教训**：「字段类型」**不是单点属性**，是**跨前后端 + 跨多视图（报价单/核价单/详情页/比对页/批量导入）+ 跨持久化层**的协议变换。当前约 **17 个协议检查点跨约 13 个文件**（随方案演进数量会变；以下枚举为骨架，改动时以 grep 实际命中为准）。**漏一处必有静默失败**（不报编译错也不报运行时错，只是 UI 渲染不对）。

### 何时触发
改 `field_type`（X→Y）/ 引入新枚举 / 给现有类型加 sub-type / 字段 JSON 加新键 / 改 `VALID_FIELD_TYPES` / 模板 schema 数据迁移。

### 检查点清单（按数据流方向）

| 区 | 检查点 | 文件 |
|---|---|---|
| 配置层（前端） | ① 类型枚举 + 选项 + config 接口 | `component/types.ts`（`FieldItem.field_type` + `FIELD_TYPE_OPTIONS`） |
| | ② 字段配置渲染分支 | `component/FieldConfigTable.tsx` |
| | ③ 复杂配置独立 Drawer | `{NewType}ConfigDrawer.tsx`（如 `ListFormulaConfigDrawer`） |
| 持久化（后端） | ④ 白名单 | `ComponentService.VALID_FIELD_TYPES` |
| | ⑤ 按 type 分发校验 | `ComponentService.validateFields` |
| | ⑥ BNF 路径采集 | `ComponentDriverService.parseBasicDataPaths` |
| | ⑦ 全局变量 task 采集（3 路径：default_source A / datasource_binding B / BASIC_DATA+global_variable_code C） | `ComponentDriverService.parseGvarDefaultTasks` |
| | ⑧ 公式 token case | `FormulaCalculationService.buildExpression` |
| | ⑨ snapshot 同步（**同 cid 多 tc 实例必须按 sortOrder 精确匹配**，禁 `firstResult()`） | `TemplateService.refreshSnapshotsByComponent` |
| 渲染层（多视图） | ⑩ enrich mapper + normalizeFieldType（显式 spread 新类型全部 config 字段） | `QuotationWizard.tsx#enrichComponentData` |
| | ⑪ 批量导入 builder（同 ⑩ 同步改） | `BulkImportPartsDrawer.tsx#buildComponentDataFromTemplate` |
| | ⑫ 路径预热（fingerprint + tasks 收集新类型路径） | `usePathFormulaCache.ts` |
| | ⑬ `driverExpansionKey` 含 fieldsHash + `fieldsOverrideHash`（**额外含 BASIC_DATA 字段 `global_variable_code` 维度**） | `useDriverExpansions.ts` |
| | ⑭ 渲染 case + fallback 链（**编辑/详情双态单点复用**；`QuotationStep2` + `ReadonlyProductCard` 调用侧 prop 都透传） | `components/ComponentCell.tsx` |
| | ⑮ 非渲染逻辑（`ComponentField` interface + normalize + `computeAllFormulas` 字段值循环 + effectiveRows 派生 + **所有 `<ProductCard>` callsite prop**） | `QuotationStep2.tsx` |
| 解析层（独立） | ⑯ `$`/`$$` SQL 视图前缀解析（纯解析层扩展，**不影响 ①~⑮** 字段类型/缓存/渲染矩阵） | `com.cpq.datasource.sqlview`（`SqlViewPathRewriter` / `SqlViewExecutor`） |

### 强制自检 SOP（写代码前 / 中 / 后 3 步）
1. **写前**：grep 全工程列清单（`field_type` 命中），少于约 15 处 = 矩阵漏点：
   ```
   grep -rn "FIELD_TYPE\|field_type\|normalizeFieldType" cpq-frontend/src cpq-backend/src
   ```
2. **写中**：每改一个文件对照清单勾掉一格，漏一格大概率失败。
3. **写后**：跑 **E2E 双 spec**（`quotation-flow.spec.ts` SIMPLE + `composite-product-flow.spec.ts` COMPOSITE，须 `1 passed` + `'加载中' final count = 0`）+ **报价单 / 核价单 / 详情页三视图**关键 Tab 截图（修复前 vs 后）+ 跑 `POST /components/{id}/refresh-template-snapshots` 后打印各 Tab snapshot.fields 确认同 cid 不同 Tab 配置独立。

### AP-37 结构性根因补充（同 componentId 多实例）
- **enrich 反查不能塌缩同 cid 多条**：用 `Map<cid, Queue<saved>>` 按 `(cid, tabName)` 精确出队，命中后 splice 剔除；结构性字段（`tabName/componentType/dataDriverPath/componentId`）一律 snapshot 优先、saved 兜底；load 路径不能因 fields 已存在跳过 enrich（snapshot 是唯一权威）。
- **driverExpansion cache key** 必须含 `(partNo, componentId, customerId, dataDriverPath)` 全部；`batchExpand` 结果配对按 task **index 直接对应**，不能用 backend `r.key` 反查。
- **详情页/编辑页 enrich 必须同源**：`ReadonlyProductCard` / `QuotationStep2` / 比对页 / 导出快照走同一份 snapshot-driven 队列匹配 enrich，禁 inline 复刻 saved-driven `find()`（Tab 数不一致 = 协议漂移 = 必有双源）。
- **不要用 componentHasData 隐藏空 Tab**：Tab 头按 snapshot 1:1 渲染（除 SUBTOTAL），空数据 Tab 内部「暂无数据」占位。

> 组合产品（SIMPLE/COMPOSITE）现行主线 = 统一智能视图（`v_composite_child_*` 视图自适应 + `ComponentDriverService` 按 `compositeType` 三分支注入），早期「双轨字段」仅作个别字段覆盖补充。详见 `docs/三大核心模块基线.md §5.1`。

## 5.3 Excel 列配置

`component_type=EXCEL` 组件用 `excel_columns` JSONB 定义列结构；Excel 视图按预定义列**每个 lineItem（产品行）渲染一行**。列级来源词汇与模板层（`costing_template` 列）一致。

### 列字段 + 8 种列来源
列基本字段：`col_key`（公式中 `[X]` 引用名，建议 A/B/C…）、`title`、`source_type`、来源配置（`variable_path`/`formula`/`field_key`/`fixed_value` 等）、`comparison_tag`（比对分组，可空）、`hidden`。

> **权威来源**：后端 `ExcelViewService.buildRowData` 的 `source_type` switch 支持以下 **8 种**：

| source_type | 含义 | 取值来源 |
|---|---|---|
| `VARIABLE` | 取数列（BNF 路径 / `{code}` 简写 / 全局变量 / `$name` 模板 SQL 视图） | 前端 hook 已算好，后端取 `componentRowData[col_key]` |
| `FORMULA` | 模板公式列，`[名称]` 引用，**后端**求值 | `evaluateFormulaColumn` |
| `EXCEL_FORMULA` | `[X]` 列引用公式，**前端**求值 | 后端原样返回公式串 |
| `CARD_FORMULA` | 卡片公式列，跨列依赖批量拓扑求值 | `cardFormulaEvaluator`（异常吞为 null 不 500） |
| `TAB_JOIN_FORMULA` | 页签连表公式 | `TabJoinPlanEvaluator.evaluateColumn` |
| `PRODUCT_ATTRIBUTE` | 产品属性值 | `productAttrs[field_key]` |
| `COMPONENT_FIELD` | 取某组件字段值 | `componentRowData[field_key]` |
| `FIXED_VALUE` | 固定值 | `col.fixed_value` |

### VARIABLE 列三种写法

| 写法 | 示例 | 何时用 |
|---|---|---|
| **`{code}` 简写**（不查 DB） | `{hf_part_no}` `{product_name}` | lineItem 内存已有字段，性能最好 |
| **BNF 物理路径** | `v_costing_summary_full.material_cost` | 查物理视图（隐式按料号过滤） |
| **`$name` 模板 SQL 视图**（推荐） | `$summary_full.material_cost` | 模板私有、发布冻结、dry-run 拒 V44（AP-53） |

### EXCEL_FORMULA `[X]` 前端求值语法
前端 `LinkedExcelView.evaluateFormula`：

| 语法 | 含义 |
|---|---|
| `[X]` | 引用本行 X 列已求值数值 |
| `{code}` | 引用 VARIABLE 列已 resolved 的值 |
| `+ - * / ( )` / `< > <= >= == != && \|\| ? :` | 四则 + 比较 + 逻辑 + 三元 |
| `=` 前缀 | 兼容 Excel 习惯，自动剥离 |

- **不支持**：字符串字面量（`"KG"` → 返 `—`）、Excel 函数（`SUM`/`IF`/`VLOOKUP`）、跨行引用；安全闸只允许 `[\d+\-*/().,\s%<>=!&|?:]`，含其他字符立即返 `—`。要显示固定文本 → 改 VARIABLE 列指向视图硬编码字段（如 `weight_unit` 写死 `'KG'::varchar`）。
- **两遍扫描顺序**：第一遍处理 VARIABLE 列（`{code}` 同步 resolve / BNF path 异步进 pathCache），第二遍处理 FORMULA/EXCEL_FORMULA 列。含义：EXCEL_FORMULA 只能引用 VARIABLE 列，**不能引用其他公式列**（无第三遍），嵌套公式须平铺成单行。

### TAB_JOIN_FORMULA — 页签连表公式
一列的值由单卡片内多个页签内容按行键对齐后算出**一个单值**。令牌三类：明细 `[别名.字段]`；小计列总计 `[别名.列名(总计)]`；页签总计 `[别名(总计)]`。页签按各自 `rowKeyFields` 完全相等分「行键类」，同类页签全外连对齐（只有同行键类明细才能在一个表达式里逐行运算）。函数 `SUM/AVG/MIN/MAX/COUNT`；缺值→0、除数 0 或缺→按 1。求值器 `TabJoinPlanEvaluator`；试算走 `POST /templates/{id}/excel-view-config/dry-run-tab-formula`。

> 核价 Excel 模板的 `:spineKeys` 复合键过滤 / `:versionFilter` 版本宏见 `核价侧.md`；报价侧 `:customerCode` / `:total_material_no` 树契约见 `报价侧.md`。完整 8 source_type 细节与 `$name` 模板 SQL 视图操作见 `配置方法论-合并版.md §4`。
