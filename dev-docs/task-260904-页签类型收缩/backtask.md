# 后端任务分解 · task-260904-页签类型收缩

> 只按本文件做。AC 原文在 `需求文档.md §③`，此处只标编号不复制原文。接口契约以 `api.md` 为准。
> 🚨 遇 `CLAUDE.md` §3.2 不可逆红线（DROP / DELETE / TRUNCATE / 清库）**停下报主线**，不得自行执行。

## 🟢 v3：单阶段，无不可逆操作

用户 2026-09-04 裁决「存量组件暂不考虑，只考虑后面创建的新组件」后：**不删列、不停用任何数据、无迁移下线**。原第二阶段（删列）整体取消，`§3.2` 红线审批不再需要。

🔑 **本期的核心机制是 §1.35 的双判据** —— 新组件按 `semantic`，存量回退 `tab_type`，收敛到**同一个方法**。

---

## 任务项

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-2, AC-3, AC-20 | `FieldTreeBuilder.FieldTreeResponse` 新增 `availableSources`（结构见 `api.md §1.2`），移除 `availableTabTypes`。数据来自 `semantic_tab_view` 中 `status='ACTIVE'` 且方言匹配的行 + 其锚点节点的 `node_key`/`display_name`。`semantic` 三态由 `tab_type` 映射：`BOM 树→TREE`、`材质元素→MATERIAL_ELEMENT`、其余→`null` |
| **B-2** | AC-1, AC-3, AC-20, AC-25 | 数据源下拉不再列出「零件」「外购件」—— 在 **B-1 的 `availableSources` 组装处过滤掉**这两个 `tab_type`。<br>🚫 **v3 改法：`semantic_tab_view` 一行都不动、45 行全部保持 `ACTIVE`**（原 v2 的「置 INACTIVE」已取消：① 该字段全工程只写不读，改了是 no-op；② 真加了过滤又会让存量 30 个零件/外购件组件打开 404，属于弄坏存量）。<br>⚠️ 过滤只作用于**新建时的可选项**，`field-tree` 按 `(tabType, variantKey)` 查询时**仍能查到**这两行 —— 存量组件靠它打开 |
| **B-3** | AC-1 | `SemanticCompiler.resolveDiscriminator()` 移除 `"外购件".equals(tabType) → characteristic='OUTSOURCED'` 分支。<br>📌 该列在 `ds_quote_material_bom` 中已不存在，移除的是死代码 |
| **B-4** | AC-1, AC-14, AC-25, AC-27 | 🔑 **实现 §1.35 的双判据**（本期最核心一项）：升级 `BomTreeRenderService.isQuoteTreeTabType()` 为：<br>① `component_sql_view.builder_version IS NOT NULL` → 从 `builder_config` 取 **`(tabType, variantKey, dialect)` 三段**查 `semantic_tab_view` → 判 `semantic=='TREE'`；<br>② 否则回退 `component.tab_type=='BOM'`。<br>🚨 **三段缺一不可**：唯一约束是 `UNIQUE (tab_type, variant_key, dialect)`，只用前两段反查**实测命中 3 行**（三方言各一），`findFirst()` 会静默取错方言。<br>✅ 判「新 vs 存量」用 `builder_version`（`integer` 普通列）而非 `builder_config`，等价且不必刨 JSONB —— 写入点 `BuilderService.save():792-805` 两列同时写。<br>🚨 **必须只有这一处实现**，9 个消费点全部调用它 —— 现网已散落 6 处硬编码（B-18 负责收编）。<br>⚠️ 分支①优先：同时有 `builder_config` 与 `tab_type='BOM'` 时以 `semantic` 为准（AC-27③） |
| **B-5** | AC-4, AC-5, AC-11, AC-12 | `BomNodeTypeResolver` 判定来源改读主数据：`material_recipe.code` 命中→`材质`；`ds_quote_material` 命中→按 `material_type` 判 `零件`/`外购件`；**`material_type` 为空 → 判 `零件`**（A0-1）。<br>⚠️ 该类现在**不查库、纯逻辑、便于单测**（见其 javadoc）。改造后必须保持"判定逻辑可单测"——把查库放在调用方或注入的 Port 里，不要把 EntityManager 塞进判定体 |
| **B-6** | AC-6 | 加叶子存在性校验：料号不在 `ds_quote_material` 也不在 `material_recipe` → 400 `LEAF_PART_NOT_IN_MASTER`。文案须点名料号 |
| **B-7** | AC-7 | 加叶子环检测：挂上后会形成环（含自环）→ 400 `LEAF_CYCLE_DETECTED`，**文案须给出环路径**。<br>📌 判据 = 待挂料号是否为宿主节点的祖先（含宿主自身）。树上下文已由 `buildHitContext` 载入，**不要为此新增一次全树查询** |
| **B-8** | AC-8 | 既有护栏保持：宿主是材质/外购件时仍 400「不可再添加下级」。宿主类型判定同样改读主数据 |
| **B-9** | AC-17 | `QuotationTreeService.assertCanAddToRestrictedTab` —— 🚨 **判据是树结构（该料号是否已有下级），不是料号类型**。本次**只改"哪些页签触发该校验"的判定方式**（由 `tabType∈{材质元素,外购件}` 改为按数据源 `semantic`），**判定体 `assertNoChildrenInRestrictedTab` 一行不动** |
| **B-10** | AC-18 | 公式 token 闸门（`ComponentService` 内）：`tree_ref`/`tree_attr` 仅树页签可用、树页签禁「上一行」类 token、被未冻结核价模板引用的组件不可改为树页签 —— 三条判据全部由 `tab_type='BOM'` 改为按数据源 `semantic='TREE'`，**语义不变** |
| **B-11** | AC-15 | `ComponentService.TAB_TYPES_REQUIRE_PART_NO_FIELD`（5 类要求料号列或名称列至少一个）随收缩调整。<br>🚫 **`tabType == null` 直接 return 的放行分支必须保留** —— 114 个存量未配组件靠它 |
| **B-12** | AC-9 | `QuoteBackfillCollector` 手工叶子回填：**不再写 `content.characteristic`**。<br>🚫 `output_material_type` 一列不动、一值不改（用户业务字段） |
| **B-13** | AC-11, AC-13, AC-14, AC-25 | 回归取证：① 5 张在途单的 `snapshot_rows` / `quote_card_values` 改动前后 md5 逐字对比；② 27 个含树模板各取一张单验证渲染路由与行数不变。<br>⚠️ **必须在改动前先取一次基线 md5**，改完再取 —— 改完才想起取基线就没有对照了 |

| **B-17** | AC-21 | 🚨 **给 `component.bom_recursive_expand` 找新的写入源**。现唯一写点 `ComponentService.applyTabType():118/136` 以 `requestedTabType != null` 为入口 ⇒ 下拉去掉后该列恒 `false`。改为按「绑定数据源的 `semantic=='TREE'`」在保存事务里推导写入。<br>下游三处消费：`ComponentDriverService.eligibleForBomUnion():972` · `CostingBomTreeConfigService:162-173` · `CardSnapshotService.templateHasTreeTab` |
| **B-18** | AC-22, AC-25 | 🚨 **收编 6 处硬编码 `"BOM".equals`，全部改走 B-4 的双判据方法**：`ComponentService:115`（`wasBom`）· `ComponentService:248`（`isBom`）· `ComponentImportService:361` · `PublishedTemplateReader:145` · `CardSnapshotService:3881` · `QuoteBackfillCollector:176`。全部改走同一判据函数。<br>📌 `BomTreeRenderService:92` 的 javadoc 本来就写着「不要在别处散落」—— 这次是**把既有违规一并收编**，不是新加约束 |
| **B-20** | AC-26 | `BomNodeTypeResolver` **规则三（结构推导）与规则五（成品拦截）**改读主数据后的新判据必须写明，🚫 不许静默删。规则五尤其关键：成品也是物料，改后会被判成「零件」而放行挂为他人叶子 |

> ⛔ **原第二阶段（B-14 删列）已整体取消**（v3）。`component.tab_type` 列永久保留，存量组件继续读它。
> 同时取消的还有：B-16（`status` 过滤 —— 不停用就不需要）· B-19（模板冻结 SQL —— 不删列就不受影响）· B-21（存量打开路径 —— 不停用就不会 404）。编号保留不复用。

## 强制自检（`backend.md` + `CLAUDE.md` §6.1）

- [ ] **N+1 硬指标**：B-5/B-6/B-7 的主数据查询与环检测，SQL 条数必须与树节点数、料号数**无关**。整单一次预取，🚫 循环体内出现查询即违规
- [ ] 迁移文件号取号前先查共享库 `flyway_schema_history` 最大值（并发会话在抢号）
- [ ] 🟢 **本期无 DDL、无迁移** —— 若你发现自己要写迁移文件，说明理解偏了，先回主线确认
- [ ] 后端存活自检：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → 期望 **401**
- [ ] 迁移 `success=t`；启动期视图校验全通过
- [ ] 「完成」宣告必须带 §6.1 的「已自检」声明行
