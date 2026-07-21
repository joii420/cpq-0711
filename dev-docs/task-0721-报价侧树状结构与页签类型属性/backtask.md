# 后端任务文档 — 报价侧树状结构与页签类型属性

> 需求：`需求说明.md`｜接口：`api.md`｜技术设计：`docs/superpowers/specs/2026-07-21-报价单BOM树状渲染-design.md`
> 日期：2026-07-21

---

## 0. 开工前必读

1. `docs/方案制定前必读.md` —— 尤其「改动 3（driver expansion）」「改动 5（snapshot）」「改动 7（snapshotRows）」三棵决策树
2. `docs/三大核心模块基线.md` —— 本任务触及「报价渲染」核心模块
3. `docs/反模式.md` AP-31 / AP-38 / AP-51 / AP-53 / AP-54
4. `docs/RECORD.md` 中 2026-07-03「核价单渲染全量递归重构」与 2026-07-03「核价树渲染上线调试链」两条

### 架构红线（违反即打回）

| # | 红线 |
|---|---|
| 1 | **`buildCardValues` 保持纯读 `snapshot_rows`**，不得改为自渲染。树在物化阶段产生 |
| 2 | **不新增 `field_type`、不新增字段 config 键**。本任务不触发 AP-44 的 17 点联动协议 |
| 3 | **核价侧渲染逐位不变**。共用引擎的任何改动都必须证明核价侧零回归 |
| 4 | `rowCount` 恒以 `snapshot_rows` 为准，**禁止** `Math.max(expansion.rowCount, baseRows.length)`（AP-51） |
| 5 | 递归 SQL 与页签 `$view` 一律 FROM V6 表，禁止直接引用 V44 废弃表（AP-53） |
| 6 | 不得手工 `psql -f V_xx.sql`，让 Quarkus dev mode 自动跑 Flyway |

---

## B1. 数据模型迁移（Flyway）

**依赖**：无（最先做）

**涉及**：`cpq-backend/src/main/resources/db/migration/V<下一个可用号>__*.sql`

> ⚠️ 迁移版本号是**移动靶** —— 共享开发库有并发会话。开工前先查
> `SELECT max(version) FROM flyway_schema_history;` 取号，且**已应用的迁移禁止改名改号**
> （见记忆 `cpq-shared-flyway-history-churn`）。

```sql
-- ① 递归 SQL 配置增加 usage 维度
ALTER TABLE costing_bom_tree_config
  ADD COLUMN usage VARCHAR(16) NOT NULL DEFAULT 'COSTING';

-- 每个 usage 至多一条生效配置（原为全局唯一 active）
CREATE UNIQUE INDEX uq_bom_tree_config_active_per_usage
  ON costing_bom_tree_config(usage) WHERE is_active;

-- ② 组件增加页签类型属性
ALTER TABLE component ADD COLUMN tab_type VARCHAR(16);

-- ③ 报价行增加节点级墓碑
ALTER TABLE quotation_line_item ADD COLUMN deleted_tree_nodes jsonb;
```

**要点**：

- `DEFAULT 'COSTING'` **不可省略** —— 核价侧现役 active 配置（`BOMV2`）必须原地保住
- 部分唯一索引必须带 `WHERE is_active`，否则会禁止同 usage 下存在多条非生效配置

**验收**：

```sql
SELECT version, success FROM flyway_schema_history WHERE version = '<NN>';   -- success=t
SELECT usage, is_active, name FROM costing_bom_tree_config;                  -- BOMV2 仍为 COSTING/true
```

---

## B2. 树渲染引擎泛化

**依赖**：B1

**涉及**：

- `quotation/service/CostingTreeRenderService.java` → 重命名 `BomTreeRenderService`
- `datasource/sqlview/CostingTreeVarsContext.java` → 重命名 `BomTreeVarsContext`
- `component/entity/CostingBomTreeConfig.java`（`findActive` 签名）
- `component/service/CostingBomTreeConfigService.java`
- `component/resource/CostingBomTreeConfigResource.java`
- 所有调用方：`CardSnapshotService` / `CostingVersionService` / 相关测试

**要点**：

1. **类名改，SQL 参数名不改** —— `:production_part_nos` / `:total_material_no` 保持原样。改参数名要同步改所有现役视图 SQL 文本，风险高收益低
2. **表名 `costing_bom_tree_config` 不改** —— 共享库并发会话，RENAME 会波及他人
3. `findActive()` → `findActive(String usage)`；`render(templateId, lineItems, overrides)` 增加 `usage` 参数
4. 激活配置时只下线**同 usage** 的其他配置

**验收**：

- 核价侧调用全部传 `COSTING`，行为逐位不变
- `PUT /costing-bom-tree-configs/{id}/activate` 激活 QUOTE 配置后，`COSTING` 的 active 未变

---

## B3. 报价侧物化接树 ★核心

**依赖**：B1、B2

**涉及**：`configure/service/ConfigureSnapshotService.java`（写 `snapshot_rows` 的位置）

**要点**：

物化写 `snapshot_rows` 时，按组件分流：

```
组件 bom_recursive_expand = true  →  走 BomTreeRenderService.render(报价模板, lines, usage=QUOTE)
                                      spine 行 + 系统列写入 snapshot_rows
组件 bom_recursive_expand = false →  现状不变（平铺）
```

写入的系统列（**纯加法，不改表结构**）：

```
__nodeId / __parentId / __lvl / __hfPartNo / __parentNo / __bomVersion / __nodeType
```

`__nodeType` 由 B5 的类型判定服务提供，**在物化时一次算好写入**，不要留给前端算。

**必须保证**：

- `buildCardValues` 一行不改，继续纯读 `snapshot_rows`
- 非树页签的 `$view` 能引用 `:total_material_no`（`SqlViewExecutor.injectCostingTreeVars` 已有，确认 QUOTE 路径也 set 了 context）
- 失败不上抛：`render` 异常时落**带原文的失败哨兵**，前端显式展示（复用核价侧 BL-0030 机制），**不得**让整单快照 500 导致前端无限「加载中…」

**验收**：

- 树页签行数 = spine occurrence 数；连跑两次行数稳定（AP-51）
- 节点无业务数据时补空行，骨架完整，无孤儿节点
- 非树页签行为逐位不变

---

## B4. 页签类型属性

**依赖**：B1

**涉及**：`component/entity/Component.java`、`component/service/ComponentService.java`、`component/resource/ComponentResource.java`

**要点**：

1. 实体加 `tabType` 字段，保存接口透传
2. **值域强校验**（5 类）：`BOM` / `材质元素` / `零件` / `外购件` / `主件`，非法值抛 400
3. `tabType` 与既有 `bomRecursiveExpand` 的**一致性校验**：
   - `tabType = BOM` 但 `bomRecursiveExpand = false` → 保存期 warn（不阻断，允许过渡）
   - 二者是独立字段：`tabType` 表达业务语义，`bomRecursiveExpand` 控制渲染行为

**验收**：五个值域全部能存能读；非法值 400；组件详情接口回带 `tabType`

---

## B5. 类型判定服务 ★核心

**依赖**：B4

**涉及**：新建 `quotation/service/BomNodeTypeResolver.java`（建议）

**判定链**（需求说明 §4.3 规则二）：

| 序 | 条件 | 判定 |
|---|---|---|
| 1 | 料号出现在 `tabType=材质元素` 的页签 | 材质 |
| 2 | 料号出现在 `tabType=零件` 的页签 | 零件 |
| 3 | 未出现在零件页签，但其**下级挂有材质** | 零件（结构推导） |
| 4 | 料号出现在 `tabType=外购件` 的页签 | 外购件 |
| 5 | 料号出现在 `tabType=主件` 的页签 | **错误**（成品不可作他人叶子） |
| 6 | 零命中 | **错误**（非有效报价产品） |

**要点**：

- **匹配范围 = 当前报价单该页签已渲染的行**，不是基础数据主表全量
- 规则 3 是**通用规则**，适用于树上所有节点（含 SQL 递归产生的中间节点），不限于新增时刻
- 命中 ≥2 个页签 → 返回冲突信息（409），**不静默取第一个**
- 判定结果在 B3 物化时写入 `__nodeType`

**验收**：六条规则各一个单测；含结构推导与零命中拒绝

---

## B6. 加叶子端点

**依赖**：B3、B5

**涉及**：`quotation/resource/QuotationResource.java`（或新建 tree 子资源）

**契约**：`api.md` §3

**要点**：

1. 校验宿主节点类型：材质 / 外购件 → 400（终端物料不可加子）
2. 判定新料号类型（B5），四种错误分别返回可读文案
3. 生成系统列：

```
__nodeId   = <宿主 nodeId> + '/' + __manual_<uuid>
__parentId = <宿主 nodeId>
__lvl      = 宿主 __lvl + 1
__manual   = true
__sourceComponentId = <料号来源页签 componentId>
__nodeType = <判定结果>
```

4. **插入位置是纪律**：必须插入到 `snapshot_rows` 中**宿主节点行组的最后一行之后**，不可 append 到数组末尾 —— 否则树渲染时新行会排到该父节点所有子树之后（`treeTable.ts:71-76` 按原始下标顺序聚子）
5. 业务列**全部留空**，不从来源页签带数据

**验收**：新叶子挂在正确位置；刷新后仍在正确位置；四种错误码正确

---

## B7. 删除预览 + 执行（级联）★核心难点

**依赖**：B3、B5

**契约**：`api.md` §4、§5

### B7.1 影响面计算

```
输入：mode(PRUNE|ROW) + nodeId + rowKey
① 收集将被移除的树节点：
     PRUNE → 该节点 + 全部子孙（按 node_path 前缀匹配，不需递归）
     ROW   → 该行
② 对每个受影响料号，重算其在树上是否还有【剩余 occurrence】
③ 无剩余 → 加入级联删除清单（其在其余所有页签的行）
   有剩余 → 加入 retainedParts，附剩余数量与理由
```

**⚠️ 这是本任务最容易出错的地方**：DAG 重复子件（同一料号挂多个父件下）时，剪掉一支后另一支仍在用该料号的页签数据，**不得删除**。

现网实例：`3110520789` 同时挂在 `2120011658` / `2120011659` 下，必须覆盖测试。

### B7.2 执行删除

1. 校验 `previewToken`（树在预览后变化 → 409）
2. **重新计算影响面，不信任前端传来的结果**
3. 写墓碑（**一律标记删除，不物理删除**）：
   - 树节点 → `quotation_line_item.deleted_tree_nodes`
   - 级联行 → 各组件 `deleted_row_keys`
4. 重算小计与卡片值，返回整单 `quoteCardValues` 供前端回灌

**要点**：

- 剪枝的整枝隐藏用 `__nodeId` **前缀匹配**（`nodeId` 即 `node_path` 物化路径），不需要递归遍历
- 行级删除的行键必须含 `__nodeId`（B10），否则 DAG 场景必然撞键

**验收**：AC-7 / AC-7b / AC-7c / AC-7d 全过；DAG 场景不误删

---

## B8. 反向校验

**依赖**：B5

**涉及**：`saveDraft` / 组件行编辑的既有校验链路

**规则**：已拥有子节点的料号，禁止被添加到 `tabType=材质元素` 或 `外购件` 的页签。

**验收**：尝试添加时返回 400 且文案可读；不影响其他页签的正常添加

---

## B9. 缓存 key 补维度

**依赖**：无（可独立并行）

**涉及**：`component/service/ComponentDriverService.java:87`（`cacheKey`）、`:293`（注释）

**背景**：现有 cache key 为 `componentId:customerId:partNo:partVersion`，**不含 `total_material_no` 维度**。两个产品 BOM 料号集合不同时，同一组件的 expand 结果在 30s TTL 内互相串号。

> 这是**核价侧现存隐患**，报价侧接入会放大暴露面。同族问题见记忆 `cpq-sqlview-cache-key-needs-component-dim`。

**改法**：

```java
cacheKey(componentId, customerId, partNo, partVersion, totalMaterialNoHash)
```

**验收**：单测证明不同 totalMaterialNo 下同组件 expand 结果不串号

---

## B10. 行键含节点维度

**依赖**：B3

**涉及**：`quotation/service/FormulaCalculator.java#computeRowKey` 及前端对应实现

**规则**：

```
树上行键 = __nodeId ⊕ 现有 rowKeyFields 计算值 → 再走现有 uniquifyRowKeys（撞键加 #序号）
```

**必须守的不变量（AP-54）**：effKey 必须在**完整行集**上计算，过滤后的子集绝不重算。

**验收**：DAG 重复子件场景下同料号不同节点的行键不相同；删除时不删错行

---

## B11. 小计口径

**依赖**：B7

**要点**：

- 被剪枝节点必须**同时**从前端展示与后端小计中排除（两者必须同源，否则页面金额与落库金额不一致 —— AP-22 族）
- 父子聚合（累乘用量、子树汇总）属**业务公式层**，后端只提供原语（`__lvl` / `__parentId` / 边用量），**不在渲染层实现累加算法**

**验收**：剪枝后小计与页面显示一致；父子不重复累加

---

## B12. 存量数据清除

**依赖**：B1

**范围**（需求说明 §6）：

| 对象 | 处理 |
|---|---|
| 报价侧快照数据（`snapshot_rows` / `quote_card_values`） | ✅ 清除 |
| 报价单数据 | ✅ 清除 |
| `costing_bom_tree_config` | ❌ **保留** |
| 核价侧任何数据 / 配置 | ❌ **保留** |

---

## B13. 测试

### 单测

| 测试点 | 断言 |
|---|---|
| spine 展开 | 行数 = spine occurrence 数；连跑两次稳定（AP-51） |
| 类型判定链 | 六条规则各一例，含结构推导与零命中拒绝 |
| 级联删除 | 无剩余 occurrence 才删；**DAG 场景不误删**（`3110520789`） |
| 剪枝前缀匹配 | 删中间节点后子孙全部隐藏；删叶子不影响兄弟 |
| 加叶子插入位置 | 新行位于宿主节点行组之后；刷新后位置不变 |
| 缓存 key | 不同 totalMaterialNo 不串号 |
| **核价侧零回归** | 核价树渲染逐位不变 ★门禁 |

### 强制自检（CLAUDE.md §修改后强制自检）

```bash
# 1. 触发重启
touch cpq-backend/src/main/java/com/cpq/<任一>.java   # 等 5-7 秒

# 2. 端点探活（注意两个坑：必须 --noproxy，且 /q/health 是 404 不是健康探针）
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components   # 期望 401

# 3. Flyway
PGPASSWORD=... psql ... -c "SELECT version, success FROM flyway_schema_history WHERE version='<NN>'"  # success=t
```

**「完成」宣告必须附带一行「已自检」声明**，含上述三项的实际输出。没有这行 = 未完成。

---

## 任务依赖图

```
B1 ──┬── B2 ──┬── B3 ──┬── B6
     │        │        ├── B7 ── B11
     ├── B4 ──┴── B5 ──┤
     │                 └── B8
     └── B12
B9（独立并行）
B10 ← B3
B13 ← 全部
```

**建议顺序**：B1 → B2 → B4 → B5 → B3 → B10 → B6 → B7 → B8 → B11 → B9 → B12 → B13
