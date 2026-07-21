# 报价单 BOM 树状渲染 — 设计

> 状态：待评审｜日期：2026-07-21｜前置：核价侧 BOM 树引擎（2026-07-03 全量递归重构，master 092f48a）

---

## 1. 背景与目标

报价单的组成件 BOM 中，最终销售料号可能呈**树状结构**。当前报价卡片按平铺渲染，树结构不可见。

核价侧已有成熟的 spine 树渲染引擎（递归 SQL → 分组建树 → 按 spine 节点为行主轴展开）。本设计复用该引擎，让报价卡片同样按 BOM 树渲染，**并支持树上编辑**（手填 / 加叶子 / 删行 / 剪枝）——这是与核价侧（只读）的本质差异。

---

## 2. 范围

### 2.1 本期做

- 报价卡片视图按 BOM spine 树渲染：Step2 编辑页 + 详情页只读卡片（AP-50 要求两侧必须同步）
- 树上编辑：单元格手填、节点内加叶子、行级删除、节点级剪枝
- 报价侧独立的递归 SQL 配置（与核价侧口径解耦）
- `:total_material_no` 供非树页签引用（与核价侧同机制）

### 2.2 本期不做

- **报价 Excel 视图 / 导出的树状渲染**——Excel 侧只展示主料号数据，维持平铺（已确认）
- 手工新增行回写 `material_bom_item`——新增行只进报价单快照
- 在树上新增**不存在于任何页签**的料号——按 §5 判定规则，这是错误输入

---

## 3. 架构与数据流

**核心原则：树在物化时产生并冻结，渲染层零改动。**

### 3.1 建单 / 物化阶段（`ConfigureSnapshotService` 写 `snapshot_rows`）

```
① 整单收集产品料号种子（li.productPartNoSnapshot）
② 跑报价侧 active 递归 SQL（usage=QUOTE）→ 全单 BOM 闭包
     输出 root_no / material_no / bom_version / parent_no / node_path
③ 按 node_path 物化路径分组建树 → 每个 line item 一棵 spine
④ totalMaterialNo = 全单料号并集 → set 进 CostingTreeVarsContext（改名后见 §3.5）
⑤ 对模板每个 driver 组件跑一次其 $view（受 :total_material_no 收窄）
⑥ 分流装配 baseRows：
     树页签（bom_recursive_expand=true）
       → 以 spine 节点为行主轴，业务行按 (parent_no, material_no) 边匹配左关联
       → 节点无业务数据补空行，仅带系统列
       → 每行携带 __nodeId/__parentId/__lvl/__hfPartNo/__parentNo/__bomVersion
     非树页签 → 平铺（行为不变），其 $view 可引用 :total_material_no
⑦ 全部写进 snapshot_rows（系统列随 JSON 透传，纯加法）
```

### 3.2 渲染阶段（几乎零改动）

`buildCardValues` 继续纯读 `snapshot_rows`，系统列自动带出。前端 `buildSnapshotExpansions`（`QuotationStep2.tsx:1508`）的 `__sys` 提取本就无侧别判断，自动生效。

前端仅需拆两处闸门：

- `QuotationStep2.tsx:2110` —— `activeComponentBomTree = cardSide === 'COSTING' && ...`
- `ReadonlyProductCard.tsx:664` —— 同款条件

### 3.3 为什么不在读取侧渲染

已评估并否决两个替代方案：

| 方案 | 否决理由 |
|---|---|
| `buildCardValues` 改为自渲染（对齐核价侧） | 推翻报价侧「纯读快照」架构基线；树结构随基础数据 BOM 实时漂移，已提交的报价单下次打开形状会变，与冻结语义冲突 |
| 骨架实时 render + 值读快照 | 两个数据源需对齐；BOM 一变老快照的值挂不到新节点 → 单元格静默变空。AP-31/AP-38「加载中…」族的典型成因 |

### 3.4 复用清单

**零改动直接用**：`CostingTreeRenderService` 递归+分组建树、`CostingTreeGrouping`、`CostingTreeSqlValidator`、`SqlViewExecutor.injectCostingTreeVars`、前端 `treeTable.ts`、`useTreeCollapse`

**关键复用发现**：`treeTable.ts:25` 规则「同 id 多行 → 第一条声明者胜」使得**同一节点渲染多行**天然工作——后续行的 parent 仍指向父节点，成为同 depth 兄弟行；折叠箭头与子树只挂第一行。`layoutTreeRows` 一行不用改，核价侧共用亦不受影响。

### 3.5 命名

`CostingTreeRenderService` / `CostingTreeVarsContext` 等 Java 类名改为中性命名（如 `BomTreeRenderService`），因其完成后同时服务两侧。

**SQL 参数名 `:production_part_nos` / `:total_material_no` 保持不变**——改名需同步修改所有现役视图的 SQL 文本，风险高收益低。

**配置表名 `costing_bom_tree_config` 保持不变**——共享开发库有并发会话，`ALTER TABLE ... RENAME` 会波及他人（见 `cpq-shared-flyway-history-churn`）。

---

## 4. 数据模型

### 4.1 递归 SQL 配置加 `usage` 维度

```sql
ALTER TABLE costing_bom_tree_config
  ADD COLUMN usage VARCHAR(16) NOT NULL DEFAULT 'COSTING';   -- 存量全部落 COSTING

CREATE UNIQUE INDEX uq_bom_tree_config_active_per_usage
  ON costing_bom_tree_config(usage) WHERE is_active;
```

`findActive()` → `findActive(usage)`，调用方按侧传 `QUOTE` / `COSTING`。报价侧需要独立的管理入口与 active 配置。

### 4.2 页签类型属性（新增）

组件（页签）增加**类型属性**，声明该页签的料号列 / 料号名称列所属的业务语义。

- 值域（业务定义）：`BOM` / `材质元素` / `零件` / `组成件` / `外购件` / `主件`
- 用途：§5 类型判定规则的依据；本期渲染不依赖其到 `characteristic` 三态的映射

> **待确认 A**：6 类值域与 `material_bom_item.characteristic` 三态（`RECIPE`/`ASSEMBLY`/`OUTSOURCED`）的映射关系；以及「零件」与「组成件」的语义区别。本期不回写 `material_bom_item`，故不阻塞实现。

### 4.3 节点级墓碑

```sql
ALTER TABLE quotation_line_item
  ADD COLUMN deleted_tree_nodes jsonb;   -- ["<nodeId>", ...]
```

放 line item 级以满足「跨所有树页签联动」；与现有 `deleted_row_keys`（component 级）同风格，不引入新表与 FK。

**整枝移除不需要递归**：`__nodeId` 即 `node_path` 物化路径（per-occurrence 唯一），子孙路径必然以父路径为前缀，故只存被删节点一个 id，渲染时按**前缀匹配**隐藏整枝。BOM 结构变化时该判断自愈——新长出的子孙自动落在前缀下。

### 4.4 行键

树上行键**必须含 `__nodeId`**，否则同一料号出现在不同节点（DAG 重复子件）时必然撞键。

```
行键 = __nodeId ⊕ 现有 rowKeyFields 计算值 → 再走现有 uniquifyRowKeys（撞键加 #序号）
```

同节点内多行靠 `rowKeyFields` 区分，跨节点靠 `nodeId` 区分。行级墓碑继续用 `deleted_row_keys`，仅键中多一个节点维度。

### 4.5 手工叶子的存储

手工新增叶子写进 `snapshot_rows`，系统列如下生成：

```
__nodeId   = <宿主节点 nodeId> + '/' + __manual_<uuid>
__parentId = <宿主节点 nodeId>
__lvl      = 宿主 __lvl + 1
__manual   = true
__sourceComponentId = <料号来源页签的 componentId>     // §5 类型判定的权威来源
```

沿用物化路径语义，使所有基于路径前缀的逻辑**自动适用、一行不改**：

- 剪枝按前缀匹配 → 删宿主节点时人工叶子跟随整枝移除
- 折叠按 parentIndex 上溯 → 人工叶子跟随宿主折叠
- 刷新按 `__nodeId` 挂回 → 宿主还在则挂回，宿主消失则按 §7.2 处理

`rowCount` 依然以 `snapshot_rows` 为准，`CardSnapshotService.java:1043`「AP-51: rowCount 不做 Math.max，以 snapshot_rows 行数为准」保持成立——手工行不是额外行，它就是快照的一部分。

---

## 5. 类型判定规则（核心）

树上节点的类型**不由用户声明，而由「料号出现在哪个类型的页签」推导**。这消除了用户选错类型、以及类型与数据不一致的可能。

### 5.1 判定链

| 序 | 条件 | 判定 |
|---|---|---|
| 1 | 料号出现在「材质元素」类型页签 | 材质 |
| 2 | 料号出现在「零件」类型页签 | 零件 |
| 3 | 料号未出现在「零件」页签，但其**直接子节点**挂有材质 | 零件（结构推导） |
| 4 | 料号出现在「外购件」类型页签 | 外购件 |
| 5 | 料号出现在「主件」类型页签 | **错误**——成品是树根，不能作为他人叶子挂入 |
| 6 | 以上皆不满足（零命中） | **错误**——该料号不是有效的报价产品，拒绝新增 |

> **2026-07-21 修订**：本表原为 5 条，漏了规则 5「命中主件页签」（需求说明 §4.3 一直是 6 条）。
> 规则 5 与规则 6 是两条独立分支、两种不同错误文案。规则 3 只看**直接子节点**。

规则 3 是**通用类型判定规则**，适用于树上所有节点（含 SQL 递归产生的中间层节点），不限于新增时刻。

### 5.2 匹配范围

匹配对象是**当前报价单该页签已渲染的行**，不是基础数据主表全量。

依据：「这个产品的报价数据就在这些页签里」。候选集因此完全本地化——前端已持有各页签 `componentData`，无需任何后端查询、远程搜索或分页。

### 5.3 加叶子的交互推论

既然料号必须已在某页签存在，交互应为**从当前报价单各页签已有料号中选择**，而非自由输入：

- 自由输入的两种结局都无价值：输错只能报错；输对等于手工重复了系统已有的信息
- 选中瞬间类型即确定（来自哪个页签）
- 候选集规模为单个产品的物料数量（数十量级），一次性本地加载

**因此本期不需要**：三态下拉、新 `field_type`、新 config 键（`options` / `master_source`）、`lookup` 端点扩展、`material_recipe` 接入 MASTER map。AP-44 的 17 点协议风险随之消失——本期不碰任何 `field_type` 配置。

### 5.4 命中 ≥2 个页签

基础数据本不应如此（一个料号只应属于一种类型）。发生时提示冲突并由用户裁决，不静默取第一个。

---

## 6. 编辑协议

### 6.1 行可编辑性与加子约束

> ⚠️ **2026-07-21 修订**：本节原有的「非叶子行整行只读」规则**已推翻**。详见
> `dev-docs/task-0721-报价侧树状结构与页签类型属性/需求说明.md` §4.3 规则三。

**行可编辑性**：树上**所有行**的业务列凡可填即可编辑，不因该节点是否拥有子节点而变化。加子**不影响**父节点自身数据，其原本可填的单元格加完子之后照样可改。仅**系统列**（料号 / 父料号 / 版本）恒只读。

**加子约束（按节点类型，双向）**：

| 节点类型 | 能否加子 | 原因 |
|---|---|---|
| 零件 | ✅ 可以 | 零件可继续往下拆 |
| 材质 | ❌ 禁止 | 终端物料 |
| 外购件 | ❌ 禁止 | 终端物料 |

- **正向**：材质 / 外购件节点的 `+` 置灰（不隐藏，hover 给出原因，遵循列表操作规范）
- **反向**：已拥有子节点的料号，禁止被添加到「材质元素」/「外购件」类型页签（跨页签一致性校验）

### 6.2 加叶子

```
点击宿主行的 +
  → 弹出候选料号选择（来源：当前报价单各页签已渲染行的料号）
  → 选中 → 类型按 §5 自动判定，无需用户选择
  → 生成系统列（§4.5），业务列留空待填
  → 插入 snapshot_rows 中【宿主节点行组的最后一行之后】
  → autoSave 落库
```

**插入位置是纪律**：`buildTreeRows` 按原始下标顺序聚子（`treeTable.ts:71-76`）。新行若 append 到数组末尾，depth 算对了但会排到该父节点所有子树之后，树视觉上会散开。必须紧邻插入。

> **待确认 B**：手工叶子的**业务列数据来源**。树页签与料号来源页签是不同组件、字段定义不同，无法直接复制。当前设计为「业务列留空、用户手填」。若业务期望自动带出来源页签的数据，需要额外的跨组件字段映射设计，不在本期。

### 6.3 行级删除（`×`）

沿用现有 `deleted_row_keys` 墓碑，行键按 §4.4 含 `__nodeId`。

**必须守的不变量**（AP-54）：effKey 必须在**完整行集**上计算，过滤后的子集绝不重算。现有 `buildSnapshotExpansions` 的 `uniqFull`（`QuotationStep2.tsx:1474`）即为此设计，树上照搬；`side === 'QUOTE'` 条件须保留（核价侧仍不过滤）。

删到该节点只剩一行时退化为空行，保留骨架，节点不消失。

### 6.4 节点级删除（剪枝）+ 跨页签级联

> ⚠️ **2026-07-21 扩展**：剪枝从「树页签联动」升级为**跨全部页签的级联删除**。

```
点击节点主行的剪枝入口（区别于行删除的 ×，仅在节点首行显示）
  → 计算影响面：该节点及全部子孙料号
  → 对每个受影响料号，重算其在树上是否还有剩余 occurrence
  → 弹窗展示完整删减明细（树节点 + 各页签将被删除的行），等待确认
  → 确认后：
       nodeId 写入 quotation_line_item.deleted_tree_nodes
       无剩余 occurrence 的料号 → 其在其余所有页签的行写入各组件 deleted_row_keys
  → 所有树页签按 node_path 前缀匹配隐藏整枝
```

**级联判定（关键正确性规则）**：仅当某料号在树上**已无任何剩余 occurrence** 时，才删除其在其余页签的数据。

DAG 重复子件是核心场景：同一料号挂在多个父件下时，剪掉其中一支后另一支仍在使用该料号的页签数据，此时**不得**删除。现网已有实例（`3110520789` 同挂两个父件），必须覆盖测试。

**行级删除同样适用**：`×` 删除的若是该料号在树上的最后一行，触发同样的级联；否则只删当前行。

**一律走墓碑机制**，不做物理删除，保留回退余地并符合快照语义。

**前端隐藏与后端小计必须同源**：小计计算须同样跳过被剪枝节点，否则页面显示金额与落库金额不一致（AP-22 族：渲染层与计算层各读各的）。

### 6.5 `:total_material_no` 供非树页签引用

非树页签的 `$view` 直接写 `... = ANY(:total_material_no)` 即可取得整棵树的料号并集，`SqlViewExecutor.injectCostingTreeVars` 自动注入，**无需新代码**。

### 6.6 必须堵的缓存陷阱

`ComponentDriverService.java:293` 明示 expand 缓存 key 为 `componentId:customerId:_:_`，**不含 `total_material_no` 维度**；`CostingTreeRenderService.java:177` 另注明「key 恒定」。

后果：两个产品 BOM 料号集合不同，同一组件的 expand 结果在 30s TTL 内互相串号。该隐患核价侧现已存在，报价侧接入会放大暴露面。

```java
// 修法：cacheKey 增加 totalMaterialNo 内容 hash 维度
cacheKey(componentId, customerId, partNo, partVersion, totalMaterialNoHash)
```

与 `cpq-sqlview-cache-key-needs-component-dim` 同类修法（缓存 key 补齐真实影响维度）。

### 6.7 小计口径

spine 全节点展开后，父节点通常为空行（该组件对父节点无业务数据），故 `Σ 所有行` **不会**父子重复累加——这是结构性安全，非巧合。

父子聚合（累乘用量、子树汇总）属**业务公式层**，不在渲染层实现。后台只提供原语（`__lvl` / `__parentId` / 边用量），累加算法由公式表达（见 `cpq-computed-values-formula-driven-not-hardcoded`）。

---

## 7. 错误处理

### 7.1 递归渲染失败

复用核价侧 BL-0030 的失败哨兵机制：`render` 失败**不上抛**（否则整单快照 500 + 全 NULL → 前端无限「加载中…」），逐 line item 落带原文的错误哨兵，前端显式展示错误原文。

报价侧接入后同样继承该机制，不新造。

### 7.2 刷新后宿主节点消失

`refreshCardSnapshot` 重新物化时，手工叶子按 `__nodeId` 挂回宿主节点。宿主节点已不存在（BOM 变更）时：

**丢弃 + 明确提示**（"N 行因所属节点已不存在被移除"）。静默保留游离行会在卡片中留下无归属数据，后续无人能解释其来源。

### 7.3 类型判定零命中

按 §5.1 规则 5，拒绝新增并提示该料号不在任何页签中。这是输入错误，不是系统故障，提示文案应指向业务含义而非技术错误。

### 7.4 继承自核价侧引擎的已知缺陷

复用引擎同时继承以下 BACKLOG 条目，报价侧会同样表现：

- `BL-0027`：树导入后首屏走实时兜底，需手动刷新才出树
- `BL-0030`：render 失败被静默吞成空卡片（§7.1 的机制已缓解）
- `BL-0005`：版本感知 BOM 闭包未生效

---

## 8. 测试策略

### 8.1 强制 E2E（协议级改动）

按 `CLAUDE.md` 修改后强制自检 §5，本设计触碰 `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `CardSnapshotService` / 模板 snapshot 结构，属协议级改动：

```
quotation-flow.spec.ts        # SIMPLE 路径
composite-product-flow.spec.ts # COMPOSITE 路径
```

必须看到全部 test `passed`、`'加载中' final count = 0`、全 Tab `'加载中'=0`。

> 已知环境缺口：干净 master 上 `quotation-flow` 恒有 3 失败（夹具单缺产品分类致 Step1 下一步禁用）。判断回归须做 A/B 同型对比，勿误归因（见 `task0712-update071501-category-axis`）。

### 8.2 后端单测

| 测试点 | 断言 |
|---|---|
| 报价侧 spine 展开 | 树页签行数 = spine occurrence 数；连跑两次行数稳定（AP-51） |
| 类型判定链 | §5.1 五条规则各一例，含规则 3 结构推导与规则 5 零命中拒绝 |
| 剪枝前缀匹配 | 删中间节点后子孙全部隐藏；删叶子不影响兄弟 |
| 手工叶子挂回 | 宿主存在→挂回；宿主消失→丢弃且计数正确（§7.2） |
| 缓存 key 维度 | 不同 totalMaterialNo 下同组件 expand 结果不串号（§6.6） |
| 核价侧零回归 | 核价树渲染逐位不变（共用引擎改动不得影响核价侧） |

### 8.3 三视图一致性

按 AP-50，同一数据在 **Step2 编辑页 / 详情页只读卡片** 两处渲染必须一致。任一侧缺分支会导致僵尸数据被掩盖。

---

## 9. 影响面与自检清单

| 反模式 | 本设计的应对 |
|---|---|
| AP-31 / AP-38「加载中…」族 | 不引入双数据源（§3.3 否决方案 C）；BASIC_DATA 缺值直接落「—」不降级 globalPathCache |
| AP-44 字段类型联动 | **本期不触发**——不新增 `field_type`、不新增 config 键（§5.3） |
| AP-50 详情页 single-source | 两处渲染分支同步改（§3.2 两处闸门 + §8.3） |
| AP-51 行数纪律 | rowCount 恒以 snapshot_rows 为准，禁 `Math.max`（§4.5） |
| AP-54 过滤下标错配 | effKey 在完整行集上算，过滤子集不重算（§6.3） |
| AP-53 V6 表使用规则 | 递归 SQL 与页签 $view 均须 FROM V6 表，禁直接引用 V44 废弃表 |

---

## 10. 本期不做（转 BACKLOG）

| 条目 | 说明 | 规模 |
|---|---|---|
| 报价 Excel 视图 / 导出树状渲染 | 本期 Excel 只展示主料号（已确认） | M |
| `master_source` 动态候选下拉 | 通用能力；与 `BL-0046`（物料BOM 组成件按 calc_type 动态下拉）合流 | S |
| 手工叶子自动带出来源页签数据 | 待确认 B；需跨组件字段映射设计 | M |
| 在节点下新增**非叶子**节点 | 本期仅支持加叶子；树骨架的人工扩展仅限叶子层 | M |

---

## 11. 待确认项

**全部待确认项已于 2026-07-21 澄清完毕**，结论见
`dev-docs/task-0721-报价侧树状结构与页签类型属性/需求说明.md` §11 与 §12 澄清记录。

| 原编号 | 结论 |
|---|---|
| A | 「组成件」=「零件」合并；「主件」= 成品 = 树根，命中判错；值域共 5 类 |
| B | 手工叶子业务列**全部留空手填**；自动带出转 BACKLOG |
| C | 问题不成立 —— 「非叶子只读」规则已推翻，改为按节点类型约束加子 |
| 权限 | **不做权限控制**，沿用现有端点类级鉴权，不新增鉴权代码 |
| 新增 | 剪枝 / 删行须**跨页签级联删除**（无剩余 occurrence 才删，走墓碑，须弹窗确认） |
