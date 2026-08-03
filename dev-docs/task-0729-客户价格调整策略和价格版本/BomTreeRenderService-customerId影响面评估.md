# `BomTreeRenderService` customerId 影响面评估

> 2026-08-03。coordinator 要求：动 `BomTreeRenderService`（共享渲染基础设施）之前先出影响面评估，
> 同 S6 对拍清单 / 精度影响面清单同一个做法。结论见文末「四、最小改动方案」。

## ✅ 2026-08-03 最终结论：真根因已用 DEBUG 日志锁死，修复已落地并全量验证（本节最新，覆盖下方两轮被推翻的假设）

**本调查先后出现三版根因假设，前两版都被下一轮实测推翻，如实记录不美化：**

1. **第一版（本文档最初版本）**："`BomTreeRenderService` 设计上就把 customerId 传 null，让 `$view`
   靠 `:total_material_no` 收窄" —— coordinator 指出这只是"地基"层面的观察，未定位到底谁在读这个
   null。
2. **第二版**（"补充"章节，见下方保留段落）："核价侧 4 条路径遗漏了 `QuotationIdContext.set()`"
   —— coordinator 亲自用"已设置 `QuotationIdContext`"的路径复测，**依然拿到 null，此版被证伪**。
3. **第三版（最终，已用临时 DEBUG 日志在真实调用现场锁死，非推断）**：`:customerCode` 在
   component `$view` driver-path 渲染链路里，走的是 `DataLoader.loadByPath`（约196~230行）里
   `ctx.quotation = new RuntimeContext.QuotationContext(quotIdSv, customerId)` 这一行——
   `quotIdSv` 确实来自 `QuotationIdContext`（这也是第二版"看起来有道理"的原因：quotationId/
   priceBaseDate 确实靠它解析），但**同一行里的 `customerId` 是另一个完全独立的方法参数，从
   `expand()`/`expandUncached()` 一路透传下来，没有任何 ThreadLocal 兜底**。`BomTreeRenderService`
   §④ 调 `componentDriverService.expandUncached(compId, null)` 时这个 customerId 实参就是字面
   意义上的 null，且全程没有第二条补全路径。

   DEBUG 日志实测证据（`SqlViewExecutor.executeAllRows`，渲染 `wl_ys_bom_view` 那一刻打印）：
   ```
   ctx.quotation.id=5ae37319-...（真实 quotationId，正确）
   ctx.quotation.customerId=null          ← 这里
   namedParams.priceBaseDate=2026-07-27   （正确，靠 quotationId 反查得到）
   namedParams.customerId=null / namedParams.customerCode=null
   ```
   quotationId 那条线全程正确，customerId 那条线全程是 null——两条线在同一个 `QuotationContext`
   构造函数里被同时赋值，但只有一个字段真的有值来源。

**修复**：`BomTreeRenderService.renderInternal()` 用 `lineItems.get(0).quotationId` 查一次
`Quotation.customerId`，把 §④ `expandUncached(compId, null)` 的 null 改传这个真实值。
`partNo`/`lineItemId`/`partVersion` 继续传 null（那部分的既有设计不受影响，且 `expandUncached`
本就完全绕开 `expandCache`，改 customerId 不产生 AP-37 型缓存串号风险）。

coordinator 已先行确认 `QuotationIdContext` 语义（`clear()` 无条件 `CURRENT.remove()`，不支持
重入）并做了「保存-恢复」重入修复（提取 `renderInternal` + `prev/restore`），该修复保留并一并
提交——它本身修的是一个真实重入隐患，不是本次 null 的根因，但与新修法不冲突。

**全量验证结果**（真实数据，`QT-20260726-0018`/`S-3120014539`，探针清理完毕）：

| 路径 | 结果 |
|---|---|
| `refreshCostingCardValuesForLine`（task-0729 B0 S5） | ✅ Cu 元素单价 null→3650，与报价侧同元素 3650 逐位相同（验收 #64 核心断言满足）|
| `snapshotLineValuesWithUnion`→`snapshotCostingSideOnly`（加产品单行） | ✅ 同上，Cu=3650 |
| `refreshCostingCardValues`（整单批量刷新） | ✅ 同上，Cu=3650；该单仅1个line item，无collateral影响 |
| `CostingVersionService.switchVersion`（task-0713核价版本切换） | ⚠️ **未做完整 live-fire 验证**——真实 PENDING 核价单当前有并发 Playwright 测试在跑不便 mutate；隔离环境需先 materialize 该行才能拿到 version-options 候选，构造成本超出本轮预算。已代码走读确认它调用与 `refreshCostingCardValuesForLine` 完全相同的 3 参 `render()` 重载，结构性必然受益，但这是 code-level 确认非 live-fire 实测，验证强度弱于其余 3 条 |
| `ConfigureSnapshotService.snapshotLines`（报价侧，"有保护"路径回归确认） | ✅ 隔离测试单外购件成本/加工费仍各1行，与修复前逐位一致——证明 render() 内新增的嵌套 QuotationIdContext 保存-恢复对已自行 set 过上下文的调用方无副作用 |

commit `c602ff86`。

---

## 历史记录（以下两节是被推翻的第二版排查过程，保留存档不删，标题已加删除线说明）

coordinator 要求先解开 §3.3 那处"理论上应 0 行、实测普遍 1 行"的矛盾再批改法。已按要求走真实验证
（隔离测试数据 + 真实 REST 端点触发 `ConfigureSnapshotService.snapshotQuotation` 这条 saveDraft
生产路径），**验证步骤 1（新建路径下几行）：`外购件成本`/`加工费` 均为 1 行，不是 0 行**——
但真正有价值的不是"1 行"这个数字本身，是顺着这个结果往回查代码，**找到了比"customerId 全程传 null"
更精确的根因**：

**`:customerCode` 在树渲染场景下真正的解析来源不是 `expandUncached(compId, customerId)` 那个显式
参数，而是 `QuotationIdContext`（ThreadLocal）→ `DataLoader` → `RuntimeContext.toNamedParams()`
这条独立管线（`SqlViewExecutor.enrichPriceBaseDate` 方法的 javadoc 已经点破这条管线，`enrichCustomerCode`
同款读法，本次评估第一版没有追到这一层）。`BomTreeRenderService.render()` 内部确实把
`customerId` 参数硬传 null，但只要**调用方在调 `render()` 之前调用过 `QuotationIdContext.set(quotationId)`**，
`:customerCode` 照样能正确解析——`render()` 那个 null 参数根本不是唯一入口。

逐个查了全部 6 个直接触发 `render()` 的方法，是否在调用前设置了 `QuotationIdContext`：

| 方法 | 是否设置 `QuotationIdContext` | 侧别 | 归属场景 |
|---|---|---|---|
| `ConfigureSnapshotService.snapshotLines`（saveDraft/选配/V6导入，行274） | ✅ 是 | 报价侧 | 与实测"外购件成本/加工费=1行"完全吻合 |
| `CardSnapshotService.snapshotNewLinesCardValues`（ensureCardValues新行，行508） | ✅ 是 | 核价+报价 | 唯一"受保护"的核价侧路径 |
| `CardSnapshotService.snapshotCostingSideOnly`（加产品单行，行611-652） | ❌ 否 | 核价侧 | **受影响** |
| `CardSnapshotService.refreshCostingCardValues`（整单批量/刷新基础数据/复制报价单，行856-883） | ❌ 否 | 核价侧 | **受影响** |
| `CardSnapshotService.refreshCostingCardValuesForLine`（**task-0729 B0 S5**，行900-916） | ❌ 否 | 核价侧 | **受影响——本次 wl_ys_bom_view 元素单价 null 就是从这条路径实测出来的** |
| `CostingVersionService.switchVersion`（task-0713核价版本切换） | ❌ 否 | 核价侧 | **受影响** |

**对四个问题的最终结论**：
1. **新建路径下几行**：报价侧（唯一受保护的一侧）1 行，不是 0 行——假设不成立，`wg_view`/`jg_view`
   本身没有被打中，因为它们只经由"有保护"的 `snapshotLines` 到达。
2. **哪些视图会被同样打中**：不是"40个组件里逐个看"，而是**精确锁定为 4 个具体方法**（上表打勾的
   4 行）——凡是经这 4 个方法触发 `render()` 的场景，其模板下**所有**引用 `:customerCode` 的核价侧
   视图都会受影响；本任务实测 core 侧只有 `wl_ys_bom_view`（COMP-0049）一个视图用到
   `:customerCode`（见原 §3.1），故实际受损视图 = 这一个，但受损**场景**是 4 个（加产品/整单刷新/
   B0升版/核价版本切换），比原评估"只有COMP-0049"的结论更完整——原评估只验证了其中一个场景
   （B0升版），现在确认另外3个场景同样会命中。
3. **回头找 customerCode 实际是从哪来的**：`QuotationIdContext`（ThreadLocal，由调用方在渲染入口
   `set()`）→ `DataLoader.loadByPath` → `RuntimeContext.quotation.id` → `ctx.toNamedParams()`
   自动暴露 `customerId`/`customerCode`/`quotationId` 三个键，与 `enrichPriceBaseDate`
   同款注入管线（该方法 javadoc 早就写明这条管线，本次评估第一版读代码时漏看）。
4. **根因重新定性**：不是"`BomTreeRenderService` 设计上就该传 null"，而是**核价侧 4 个方法
   遗漏了报价侧 `snapshotLines`/`snapshotNewLinesCardValues` 都有的 `QuotationIdContext.set()`
   调用**——是遗漏，不是设计。

**推荐方案随之修正**（原"扩展 `BomTreeVarsContext` 携带 customerId"的方案地基不对，
`BomTreeVarsContext` 从来不参与 `:customerCode` 解析，改它不会有任何效果）：

**新推荐方案：在 `BomTreeRenderService.render()` 方法体最前面统一补一次
`QuotationIdContext.set(quotationId)`（用 `lineItems.get(0).quotationId`，配类的"单模板假设"取首个
即可）+ `finally` 块 `clear()`。** 这样无论调用方有没有记得设置，`render()` 自己兜底保证正确，一次
改动覆盖全部 6 个调用方（含未来新增的调用方），比"扩展 BomTreeVarsContext"更小、更精确、风险更低
——只加两行代码（set + clear），不改任何 SQL 视图、不改 `partNo`/`lineItemId` 继续传 null 的既有
设计（那部分结论不变）。唯一要注意：与调用方已有的 `QuotationIdContext.set/clear`（如
`snapshotLines`）会发生"内层 set 覆盖外层同值/内层 clear 提前清空外层"的嵌套问题——`QuotationIdContext`
需要确认其 `set/clear` 语义是否支持重入（本次未读该类源码，是实施前必须先看的点，若不支持重入，
改成"仅当当前值为空时才 set/clear"的保护写法）。

**验证方法**：隔离测试客户 `TEST-0729-TREEVERIFY` + 独立测试物料 `TEST-TREE-001`（真实
`material_bom_item`(QUOTE/OUTSOURCED) + `unit_price`(QUOTE/PROCESS) 各一行，挂罗克韦尔模板3
真实模板）+ 全新报价单/行项（`quote_card_values` 插入时为 NULL，确认是"从未 render 过"的首次创建态）
→ 调用真实 `ConfigureSnapshotService.snapshotQuotation`（saveDraft 同款生产方法）→
`外购件成本`/`加工费` 均正确出 1 行（`row_data` 长度=1，非 0）。测试用 6 张表的探针数据已在
验证完成后全部 DELETE 清理，`CUST-0001` 的 50 张真实报价单逐个计数核对未受任何影响。

---

## 一、谁在复用它

`BomTreeRenderService.render`（三个重载）有 **7 个直接调用方**（`codegraph_callers` 结果），
逐个追到各自的真实入口后，汇总为 **7 条独立业务场景**：

| # | 直接调用方 | 更上层真实入口（HTTP/触发点） | 业务场景 |
|---|---|---|---|
| 1 | `ConfigureSnapshotService.snapshotLines`（3参重载） ← `snapshotQuotation` | `QuotationResource`（**saveDraft**，`snapshotQuotation(id, true)`）| **报价单保存草稿**——全系统调用频率最高的路径之一 |
| | | `ConfigureProductResource`（选配加产品配置确认） | 选配产品配置流程 |
| | | `CreateQuotationMaterializer`（V6 批量导入建单，① 展开 driver → UPSERT） | Excel 批量导入 |
| 2 | `CardSnapshotService.snapshotNewLinesCardValues` ← `ensureCardValues` | `QuotationResource`（`/ensure-card-values`，详情页打开时懒计算） | **报价单/核价单详情页打开** |
| | | `ComparisonViewService`（比对视图 extractSide） | task-0717 对拍视图 + **本任务(task-0729) B4 预算对拍** |
| | | `CreateQuotationMaterializer`（V6 导入） | 同上 |
| | | `CostingFreezeService`（核价冻结提交审批） | 核价提交流程 |
| 3 | `CardSnapshotService.snapshotCostingSideOnly` ← `snapshotLineValuesWithUnion` | `CardSnapshotService` 内部整单快照编排（saveDraft/详情页共用底层） | 核价侧独立整单快照 |
| 4 | `CardSnapshotService.refreshCostingCardValues`（整单批量） | `ConfigureProductResource`（"刷新基础数据"按钮） | 选配/普通产品手动刷新 |
| | | `QuotationService.copy`（复制报价单） | 报价单复制 |
| 5 | `CardSnapshotService.refreshCostingCardValuesForLine`（单行） | **`MaterialVersionUpgradeService.upgrade`**（task-0729 B0 · S5 核价侧重算） | 🔴 **本任务自己的价格调整升版流程** |
| 6 | `CardSnapshotService.buildCostingCardValues`（多个重载委派） | 是 2/3/4/5 的公共下游实现，不单独构成新入口 | — |
| 7 | `CostingVersionService.switchVersion` | `CostingOrderResource`（`POST /costing-orders/{coid}/version-switch`，task-0713） | **核价单手动切换 BOM/元素版本** |

**结论**：`BomTreeRenderService` 不是冷门工具类，是**报价单/核价单渲染主链路的公共基础设施**，
覆盖：建单（选配/导入）、保存草稿、详情页打开、核价冻结提交、核价版本切换、报价单复制，
**以及 task-0729 自己的价格调整升版流程本身**（第5条）。改它必须当"共享基础设施"级别对待，
不能按"改一个孤立小方法"的风险预算来动。

---

## 二、传 null 是否有其他依赖方

`BomTreeRenderService.java:210` 附近的设计原话（源码注释）：

> 见类注释「跑组件 $view 的入口」说明：customerId/partNo/partVersion/lineItemId 全传 null，
> 让 SqlViewExecutor 从 `BomTreeVarsContext` 拿 `:total_material_no` 收窄，不再靠
> partNo/lineItemId 维度过滤（这条 $view 对整单只跑一次）。用 `expandUncached`（Task 3.1 事项A）
> 而非 `expand`：9-arg expand 的 expandCache key 不含 `:total_material_no` 维度（customerId/
> partNo/partVersion 全传 null → key 恒定），30s TTL 内会与其他报价单/料号集合的同组件调用
> 串号（AP-37 型缺维度缓存 bug）。

**这段注释解释的是"为什么 partNo/lineItemId 传 null"（性能：整单只跑一次，不逐行/逐料号重复
调用），不是"为什么 customerId 也必须传 null"**——两者被放在同一个函数签名里一起传了 null，
但驱动"必须传 null"这个设计决策的真实理由只覆盖 partNo/lineItemId 维度（避免逐行重复查询 +
避免 `expandUncached` 缓存串号）。**没有找到任何证据显示存在"某个 $view 依赖 customerCode
必须为 null 才能正确工作"的场景**——检查了 11 个含树页签模板下挂载的全部 40 个组件的
`sql_template`（见 §三），没有一个视图写了类似 `WHERE customer_no = :customerCode OR
:customerCode IS NULL` 这种"null 时退化为全量/全客户"的兼容写法；反而全部 40 个视图里，
用到 `:customerCode` 的 15 个视图，**customerCode 都被当作"应该有值"的正常过滤/JOIN 键**，
null 只是被动接受（LEFT JOIN 兜底为空值 / WHERE 过滤器致零行），没有任何一处主动利用"null"
这个特殊值本身。

**结论**：没有发现依赖"customerCode 必须为 null"的下游。但 partNo/lineItemId 传 null 背后
的"整单只跑一次，避免逐行重复查询 + 避免 expandCache 串号"这个设计目的**必须原样保留**——
改动方案不能退回到"每行都查一次 customerCode"式的粗暴做法，否则会重新引入这段注释警告的
AP-37 型缓存串号 bug 和性能倒退。

---

## 三、改成传真实 customerId 后，哪些视图的行数/值会变

对 11 个含 `bom_recursive_expand=true`（或 `tab_type='BOM'`）树页签组件的模板，逐一列出其下
挂载的**全部**组件 `$view` 是否引用 `:customerCode`，及其引用方式（直接查了 40 个组件的
`sql_template` 原文，非猜测）：

### 3.1 核价侧（核价模板1，唯一一个含树页签的核价模板）

| 组件 | $view | 用 `:customerCode`？ | 用法 | 改动后影响 |
|---|---|---|---|---|
| COMP-0048 物料BOM（树页签本身） | wl_bom_view | 否 | — | 无 |
| **COMP-0049 物料与元素BOM** | **wl_ys_bom_view** | **是** | `LEFT JOIN f_material_element_price(:customerCode,...)`（函数参数，非 WHERE 过滤） | **本次 V373/V374 已修复目标**——行数不变（LEFT JOIN），元素单价从 null 变为正确值 |
| COMP-0050~0064（产能/设备折旧/能耗/模具/生产耗材/包材/来料/加工费/电镀等 15 个组件） | cn_view/zj_view/nh_view/aux_view/tool_view/xh_view/bz_view/lljg_view/llbl_view/llgd_view/jgf_view/cpbl_view/cpgd_view/dj_view/wjg_view | **否，全部不用** | — | **零影响**——核价侧改动的唯一波及面就是 COMP-0049 一个组件，已被本次改动覆盖并验证 |

**核价侧结论：改动面极窄，就是 COMP-0049 一个视图，本次已修复并验证（Cu 元素 3650.0，14行进14行出无膨胀）。这条已经不需要再评估了。**

### 3.2 报价侧（3 个"罗克韦尔模板X"实例 + 1 个"施耐德BUG2"实例，各自若干模板版本）

| 组件族 | $view | 用 `:customerCode`？ | 用法 | 潜在改动后影响 |
|---|---|---|---|---|
| 产品 | cp_view | 是 | `LEFT JOIN material_customer_map mcm ON ... AND mcm.customer_no=:customerCode` | 行数不变（driven by material_master 主表），`_客户料号名称/_客户产品编号/汇率` 三列从 null 变有值 |
| BOM（树页签本身，部分实例） | bom_view | 部分是（COMP-0089/0157 是，COMP-0020/0026 否） | 视实例而定 | 需逐实例复核，未展开（树页签本身的行来自递归 SQL，不经过本次讨论的 `$view` 循环） |
| 材料成本 | mc_view | 是 | `LEFT JOIN f_material_element_price/f_customer_element_price(:customerCode,...)`（函数参数） | 行数不变，元素单价理论上会变——**但见下方 §3.3 的实证矛盾** |
| **外购件成本** | **wg_view** | **是** | 🔴 `WHERE mbi.customer_no = :customerCode`（**硬 WHERE 过滤，不是 LEFT JOIN**） | **行数会变**：customerCode=null 时 `= NULL` 永不匹配 → 理论上 0 行；改真实值后应变为真实行数 |
| **加工费** | **jg_view** | **是** | 🔴 `WHERE up.customer_no = :customerCode`（**同样硬 WHERE 过滤**） | **行数会变**，同上 |
| 施耐德BUG2 专属（COMP-0155/0156/0158/0159） | lqt_view/ll_view/qt_view/zz_view | 是 | 未逐个查具体用法（时间预算内未展开），但组件命名（来料其他费用/来料固定加工费/其他费用/组装加工费）与 wg_view/jg_view 语义高度相似，**大概率也是硬 WHERE 过滤同款风险** | 需要在实施前逐个确认 |

### 3.3 ⚠️ 未能 100% 闭环的经验性矛盾（如实标注，非隐瞒）

按 §3.2 的代码走读，`wg_view`/`jg_view`（外购件成本/加工费）用 `customer_no = :customerCode`
做硬 WHERE 过滤，理论上只要经过 `BomTreeRenderService.render` 这条 customerId=null 的路径，
**应该结构性返回 0 行**。

但实测抽查了 5 张真实使用"含树页签模板"的报价单（含本次任务反复用作测试样本的
QT-20260726-0018/S-3120014539），其 `quote_card_values` 里"外购件成本"/"加工费" 两个 tab
**普遍是 1 行**（5 张单里 4 张两者都是1行，1 张外购件成本是 0 行——但 0 行本身也可能是真实业务
数据没有外购件，不能反推是 bug）：

```
QT-20260726-0016: 外购件成本=1 加工费=1
QT-20260728-0022: 外购件成本=1 加工费=1
QT-20260726-0002: 外购件成本=1 加工费=1
QT-20260728-0024: 外购件成本=0 加工费=1
QT-20260726-0018: 外购件成本=1 加工费=1
```

**这与"customerCode=null → 硬 WHERE 过滤 → 结构性 0 行"的代码走读结论不完全吻合**（如果真的
结构性 0 行，5 张单的"外购件成本"应该全部是 0，不会是 4/5 都有 1 行）。

**我目前最支持的解释**（有代码依据，但未做真实写操作实证，故只能算"较强假设"非"结论"）：
`snapshotLines` 方法头部有这段注释——

> 注:evictAll 改为「懒触发」——仅当确有行需要 expand 时才清缓存 + 合桶预取(见下方
> anyNeedsExpand 闸门),否则增量 draft(全行已有快照→全跳过)会白白 evictAll + 报价合桶
> expand(纯浪费)。

这说明**常规 saveDraft 对"已有完整 snapshot_rows 的存量行"会整体跳过 expand/render**，
不会重新调用 `BomTreeRenderService.render`。也就是说：这 5 张单看到的"外购件成本=1行"，
很可能是这些行**当初第一次创建时**（选配确认/V6导入/新建产品那一刻）留下的快照，此后
历次 saveDraft 都因为"已有快照"被跳过，从未重新走过这条 customerId=null 的路径——
**不代表这条路径本身没问题，只代表这几张存量单当初创建时恰好没暴露这个问题**
（可能是因为当初的调用链在到达 `BomTreeRenderService` 之前，就已经拿到了真实
customerId——具体是哪一环，本次时间预算内未能追到底，留待下面的验证项）。

**这个矛盾我没有强行给出一个自己不确信的解释就结案**，而是把它标记为**实施前必须做的
验证项**（见下方「最小改动方案」的第 0 步）：新建一张挂树页签模板的报价单（走"加产品"
或 V6 导入这条**首次创建**路径），直接观察它的"外购件成本"/"加工费" tab 是否为空——
这个测试不需要动 `BomTreeRenderService` 一行代码，纯读当前行为，能在动代码之前把
"customerId=null 到底有没有在报价侧结构性炸零行"这件事坐实或证伪。

---

## 四、最小改动方案（⚠️ 本节是第一版评估的原始推荐，已被文首「2026-08-03 补充」取代，仅保留存档）

> **最终推荐见文首「2026-08-03 补充」章节**：在 `BomTreeRenderService.render()` 内部统一补
> `QuotationIdContext.set/clear`，不是下面这个"扩展 BomTreeVarsContext"方案——后者的地基不对
> （`BomTreeVarsContext` 从来不参与 `:customerCode` 解析，改它没有效果）。以下内容保留作为
> 排查过程存档，不再是推荐方案。

### （已废弃）原推荐方案：扩展 `BomTreeVarsContext` 携带 customerId（+ priceBaseDate）

**做法**：`BomTreeVarsContext.Vars` 目前构造为 `Vars(seed, totalMaterialNo, overrides)`
（`render()` 方法体里两次 `set()` 调用，第一次 `Vars(seed, null, overrides)` 用于递归建树，
第二次 `Vars(null, g.totalMaterialNo, overrides)` 用于 §④ 的 `$view` 循环）——第一个字段目前
传的是 `seed`/`null`，从两次调用的实参位置看更像是"这次调用关心的东西"而非固定语义槽位，
需要先确认其真实字段名（本次评估未展开读 `BomTreeVarsContext` 类定义，实施阶段第一步应该
读清楚这个类，不要在不清楚字段语义的情况下改）。核心思路：

1. `render()` 的 4 参重载增加从 `lineItems`（已经拿到手的 `List<QuotationLineItem>`）反查
   `Quotation.customerId` → 查 `customer.code` 得到真实 `customerCode`，以及
   `Quotation.createdAt`（或既有 `priceBaseDate` 口径，需与 task-0729 §11.4.1 的基准日
   规则对齐，不要另起一套）。
2. 把这两个值放进 `BomTreeVarsContext.Vars`（新增字段，不删旧字段），让
   `SqlViewExecutor#injectCostingTreeVars`（现有已存在的注入点，本次未读其源码，实施阶段
   需要读）在绑定 `:customerCode`/`:priceBaseDate` 时优先从 `BomTreeVarsContext` 取，
   取不到再退回现有的 `namedParams` 兜底（保证非树上下文的调用零回归）。
3. **`partNo`/`lineItemId` 继续保持传 null 不变**——那段设计是为了性能（整单只跑一次）和
   避免 `expandUncached` 缓存串号，与 customerId 无关，不能一起改掉。

**为什么推荐这个而不是"改用 `expand` 换 `expandUncached`"或"每行单独 expand"**：
- 后两者都会重新引入注释里明确警告过的 AP-37 型缓存串号 bug（`expandCache` key 不含
  `:total_material_no` 维度）和逐行重复查询的性能倒退，属于"修一个 bug 引入另一个已知 bug"，
  不可取。
- 扩展 `BomTreeVarsContext` 是在**已有的、专门为"树渲染场景下沉全局上下文"设计的机制**上
  加一个维度，符合这个类本来的设计意图（它已经承担 `total_material_no`/`overrides` 两个
  全局维度的下沉，加 customerId/priceBaseDate 是同类扩展，不是引入新范式）。

**风险点**：
1. **§三的经验性矛盾未闭环**——如果最终验证结果是"customerId=null 在报价侧其实从未在
   已上线场景里真正生效过（比如某个更上游的调用点已经用别的方式规避了）"，那么"扩展
   BomTreeVarsContext"这个方案的报价侧收益可能被高估，需要先做完 0 号验证项再定改动范围
   （只改核价侧，还是报价侧一起改）。
2. **`BomTreeVarsContext` 目前的字段语义/生命周期**（是否线程安全、`set/clear` 的配对是否
   在所有异常路径都能保证）本次评估未读源码，实施前必须先读一遍，不能凭这份评估的推测直接改。
3. **`CostingVersionService.switchVersion`**（表一第7条，task-0713 核价版本切换）如果也走
   这条路径，需要单独验证它切版本后 `:customerCode` 的来源是否与整单一致（它操作的是单个
   `lineItemId`+`componentId`，不是整单 render，需确认它调用 `render()` 时传入的
   `lineItems` 参数能正确反查到 customerId）。
4. **改动后 15 个报价侧受影响视图（wg_view/jg_view/lqt_view/ll_view/qt_view/zz_view 等）
   需要逐个用真实数据跑一遍 before/after 对拍**（同 S6 对拍清单的方法论），尤其
   wg_view/jg_view 这两个"硬 WHERE 过滤"的，如果验证坐实了它们确实结构性 0 行，那么
   修复后会让存量的、"看起来外购件成本/加工费是 0 行"的历史单在下次强制刷新
   （`refreshCostingCardValues`）时突然冒出行来——这对财务/销售是"数据变多了"的可见变化，
   需要产品侧确认这是否要单独通知或做迁移。

---

## 附：本评估的方法论边界

- 全程未修改 `BomTreeRenderService.java`（或任何其他文件），只读代码 + 只读 SQL 查询，
  符合"评估先行、代码后动"的约束。
- §一用 `codegraph_callers`/`codegraph_impact`，对 codegraph 未能追到的 3 个方法
  （`switchVersion`/`snapshotQuotation`/`ensureCardValues`，均因框架注入模式导致静态调用图
  漏边）补了针对性 grep 定位其 REST 入口，未做大范围盲目 grep。
- §三的 40 个组件 `sql_template` 是直接查库拿到的原文，不是转述/猜测。
- §3.3 的矛盾没有强行解释掉，如实标注为"较强假设，非结论"，并给出了一个不需要动
  `BomTreeRenderService` 就能验证的具体测试步骤。
