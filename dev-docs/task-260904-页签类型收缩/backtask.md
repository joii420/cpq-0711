# 后端任务分解 · task-260904-页签类型收缩

> 只按本文件做。AC 原文在 `需求文档.md §③`，此处只标编号不复制原文。接口契约以 `api.md` 为准。
> 🚨 遇 `CLAUDE.md` §3.2 不可逆红线（DROP / DELETE / TRUNCATE / 清库）**停下报主线**，不得自行执行。

## 阶段划分

| 阶段 | 内容 | 前置 |
|---|---|---|
| **第一阶段**（本期主体） | B-1 ~ B-13。`component.tab_type` 列**保留**，但后端不再消费它做任何判定 | 闸门 A 通过 |
| **第二阶段** | B-14 删列 | 🚦 第一阶段验证通过 **+ 用户按 §3.2 单独批准** |

---

## 第一阶段

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-2, AC-3, AC-20 | `FieldTreeBuilder.FieldTreeResponse` 新增 `availableSources`（结构见 `api.md §1.2`），移除 `availableTabTypes`。数据来自 `semantic_tab_view` 中 `status='ACTIVE'` 且方言匹配的行 + 其锚点节点的 `node_key`/`display_name`。`semantic` 三态由 `tab_type` 映射：`BOM 树→TREE`、`材质元素→MATERIAL_ELEMENT`、其余→`null` |
| **B-2** | AC-1, AC-3, AC-20 | 迁移：`semantic_tab_view` 中 3 方言 × {`零件`, `外购件`} 共 **6 行** 置 `status='INACTIVE'`。<br>🚫 **用 UPDATE 不用 DELETE**（A0-2）。<br>🚨 **自检不许只查表** —— 查 `WHERE status='ACTIVE'` 只证明 UPDATE 执行了，**不证明任何东西被停用**（B-16 之前 `status` 全工程无人读）。自检必须是**发请求看行为**：对 `tabType='零件'` 调一次 `field-tree`，期望 **404**。<br>✅ **零字段损失已实证**：`BOM 树`/`零件`/`外购件` 三者的 `semantic_tab_view_node` 逐字相同（都只挂 `MATERIAL_BOM(MAIN)`）、`semantic_tab_view_column` 均 0 行 ⇒ 停用后改选「物料BOM」字段清单不变（AC-20②） |
| **B-3** | AC-1 | `SemanticCompiler.resolveDiscriminator()` 移除 `"外购件".equals(tabType) → characteristic='OUTSOURCED'` 分支。<br>📌 该列在 `ds_quote_material_bom` 中已不存在，移除的是死代码 |
| **B-4** | AC-1, AC-14 | 树页签判据改造：`BomTreeRenderService.isQuoteTreeTabType()` 现为 `"BOM".equals(tabType)`。改为按组件绑定数据源的 `semantic=='TREE'` 判定。<br>🚨 **这是单一路由收口点**，被 `ConfigureSnapshotService` 分流 6 处、`CardSnapshotService` 多处消费 —— **只改这一个方法的实现，不要在调用点各写一份判断** |
| **B-5** | AC-4, AC-5, AC-11, AC-12 | `BomNodeTypeResolver` 判定来源改读主数据：`material_recipe.code` 命中→`材质`；`ds_quote_material` 命中→按 `material_type` 判 `零件`/`外购件`；**`material_type` 为空 → 判 `零件`**（A0-1）。<br>⚠️ 该类现在**不查库、纯逻辑、便于单测**（见其 javadoc）。改造后必须保持"判定逻辑可单测"——把查库放在调用方或注入的 Port 里，不要把 EntityManager 塞进判定体 |
| **B-6** | AC-6 | 加叶子存在性校验：料号不在 `ds_quote_material` 也不在 `material_recipe` → 400 `LEAF_PART_NOT_IN_MASTER`。文案须点名料号 |
| **B-7** | AC-7 | 加叶子环检测：挂上后会形成环（含自环）→ 400 `LEAF_CYCLE_DETECTED`，**文案须给出环路径**。<br>📌 判据 = 待挂料号是否为宿主节点的祖先（含宿主自身）。树上下文已由 `buildHitContext` 载入，**不要为此新增一次全树查询** |
| **B-8** | AC-8 | 既有护栏保持：宿主是材质/外购件时仍 400「不可再添加下级」。宿主类型判定同样改读主数据 |
| **B-9** | AC-17 | `QuotationTreeService.assertCanAddToRestrictedTab` —— 🚨 **判据是树结构（该料号是否已有下级），不是料号类型**。本次**只改"哪些页签触发该校验"的判定方式**（由 `tabType∈{材质元素,外购件}` 改为按数据源 `semantic`），**判定体 `assertNoChildrenInRestrictedTab` 一行不动** |
| **B-10** | AC-18 | 公式 token 闸门（`ComponentService` 内）：`tree_ref`/`tree_attr` 仅树页签可用、树页签禁「上一行」类 token、被未冻结核价模板引用的组件不可改为树页签 —— 三条判据全部由 `tab_type='BOM'` 改为按数据源 `semantic='TREE'`，**语义不变** |
| **B-11** | AC-15 | `ComponentService.TAB_TYPES_REQUIRE_PART_NO_FIELD`（5 类要求料号列或名称列至少一个）随收缩调整。<br>🚫 **`tabType == null` 直接 return 的放行分支必须保留** —— 114 个存量未配组件靠它 |
| **B-12** | AC-9 | `QuoteBackfillCollector` 手工叶子回填：**不再写 `content.characteristic`**。<br>🚫 `output_material_type` 一列不动、一值不改（用户业务字段） |
| **B-13** | AC-11, AC-13, AC-14 | 回归取证：① 5 张在途单的 `snapshot_rows` / `quote_card_values` 改动前后 md5 逐字对比；② 27 个含树模板各取一张单验证渲染路由与行数不变。<br>⚠️ **必须在改动前先取一次基线 md5**，改完再取 —— 改完才想起取基线就没有对照了 |

| **B-16** | AC-1, AC-3 | 🚨 **`semantic_tab_view.status` 加过滤 —— 没有这一项，B-2 是纯 no-op**。实测 `SemanticGraphLoader:61` 是 `SemanticTabView.listAll()` **无过滤**，`semanticgraph` + `builder` 两个包内**零处** `ACTIVE` 过滤，该字段全工程只写不读。三处要改：`SemanticGraphLoader.loadSnapshot()` · `FieldTreeBuilder.build()` 的 `findFirst()` · `SemanticCompiler.resolveTabView()`。<br>⚠️ 顺带确认其余 6 张 `semantic_*` 表的 `status` 是否同样只写不读（若是，本次只修 `tab_view` 一张，其余记 BACKLOG） |
| **B-17** | AC-21 | 🚨 **给 `component.bom_recursive_expand` 找新的写入源**。现唯一写点 `ComponentService.applyTabType():118/136` 以 `requestedTabType != null` 为入口 ⇒ 下拉去掉后该列恒 `false`。改为按「绑定数据源的 `semantic=='TREE'`」在保存事务里推导写入。<br>下游三处消费：`ComponentDriverService.eligibleForBomUnion():972` · `CostingBomTreeConfigService:162-173` · `CardSnapshotService.templateHasTreeTab` |
| **B-18** | AC-22 | 🚨 **收编 6 处硬编码 `"BOM".equals`**：`ComponentService:115`（`wasBom`）· `ComponentService:248`（`isBom`）· `ComponentImportService:361` · `PublishedTemplateReader:145` · `CardSnapshotService:3881` · `QuoteBackfillCollector:176`。全部改走同一判据函数。<br>📌 `BomTreeRenderService:92` 的 javadoc 本来就写着「不要在别处散落」—— 这次是**把既有违规一并收编**，不是新加约束 |
| **B-19** | AC-23 | 模板冻结链路：`TemplateService` **6 处原生 SQL 字符串**（1117/1128/1146/1348/1359/1377）含 `c.tab_type` / `tcs.tab_type`；`TemplateFreezeDriftService:191` 的 `tab_type` 漂移比对。<br>⚠️ **第一阶段就要处理漂移检测**：UI 不再写 `tab_type` 后 `comp.tabType` 变 null 而 `frozen.tabType` 是历史值 ⇒ 114 行冻结快照全部报漂移 |
| **B-20** | AC-26 | `BomNodeTypeResolver` **规则三（结构推导）与规则五（成品拦截）**改读主数据后的新判据必须写明，🚫 不许静默删。规则五尤其关键：成品也是物料，改后会被判成「零件」而放行挂为他人叶子 |
| **B-21** | AC-25 | 30 个存量零件/外购件组件（DB 实测 15+15）的打开路径：自动映射到「物料BOM」数据源（首选）或给可读提示，🚫 不许未处理 404 白屏 |

## 第二阶段（需用户单独批准后才能做）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-14** | AC-16, AC-23 | 迁移 `ALTER TABLE component DROP COLUMN tab_type`，同步移除 `Component` 实体、`ComponentDTO`、`CreateComponentRequest`、`ComponentExportBundle` 的该字段；组件导入遇旧包中的 `tabType` **静默忽略不报错**。<br>🚨 **仅此一张表** —— `template_component_snapshot.tab_type` 与 `semantic_tab_view.tab_type` 都不动（A0-3） |

---

## 强制自检（`backend.md` + `CLAUDE.md` §6.1）

- [ ] **N+1 硬指标**：B-5/B-6/B-7 的主数据查询与环检测，SQL 条数必须与树节点数、料号数**无关**。整单一次预取，🚫 循环体内出现查询即违规
- [ ] 迁移文件号取号前先查共享库 `flyway_schema_history` 最大值（并发会话在抢号）
- [ ] DDL 后重启后端；🚫 不手工 `psql -f`，走 `migrate-at-start`
- [ ] 后端存活自检：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → 期望 **401**
- [ ] 迁移 `success=t`；启动期视图校验全通过
- [ ] 「完成」宣告必须带 §6.1 的「已自检」声明行
