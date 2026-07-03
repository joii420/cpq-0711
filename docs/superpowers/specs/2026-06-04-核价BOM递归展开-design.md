> ⚠️ **已被取代(SUPERSEDED)**：本设计（旧 spineKeys/逐根闭包/组件级开关机制）已于 2026-07-03 被 `docs/superpowers/specs/2026-07-02-核价单全量递归按料号分组渲染-design.md` 彻底替换，仅作历史追溯，勿再据此实现。

# 核价 BOM 递归展开（料号闭包 + 节点级核价版本）— 设计方案

- 日期：2026-06-04
- 状态：已与用户逐条确认全部口径与分期，待评审 → 写实现计划（plan）
- 适用范围：**仅核价侧**（核价模板 / 核价产品卡片 / 核价 Excel 视图）；报价侧零改动
- 关联文档：
  - `docs/三大核心模块基线.md`（改动落在「报价单渲染」核心基线范围内，需 architect 评估）
  - `docs/统一智能视图路径方案.md`（`:hfPartNos` / RuntimeContext 占位符协议）
  - `docs/反模式.md` AP-22（多行 "X(共N项)"）、AP-37/AP-41（同名视图/报价核价不对齐串号）、AP-51（driver 行数权威）、AP-53（V6 基础资料表使用规则）
  - `docs/superpowers/specs/2026-06-04-material_bom_item-版本化-design.md`（`bom_version` + `is_current` 多版本纪律，本方案闭包必须遵守）

---

## 0. 定位（一句话）

> **核价时，产品卡片/Excel 不再只查"根料号那一层"，而是由 `material_bom_item` 算出根料号的整棵 BOM 子料号闭包，把闭包料号灌进现有的 `:hfPartNos`，所有核价组件视图据此按整棵树取数；BOM 父子结构 + 每节点核价版本（spine）单独产出，供卡片树状渲染、层级显示与"逐料号选核价版本"。默认全部取最新版（`is_current`），用户可对单个料号改选历史版本。**

命名纪律：闭包/骨架相关新工具、方法、参数用中性业务词（`bomClosure` / `spine` / `nodeNo` / `rootNo` / `nodeId`）；触发开关挂核价模板层，但取数引擎以"是否开启 BOM 递归"为唯一判据，不写死 `costing` 业务分支，避免 AP-41。

---

## 1. 需求与已确认口径

用户诉求：核价模板渲染时把 `material_bom_item` 某根料号下**所有子料号递归展开**；产品卡片多行带出全部子料号、Excel 树状展示；每个核价组件**默认带固定列（料号 + 父料号 + 版本下拉）**；用户可**对单个料号选核价版本**重查，默认用最新版。

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 递归放哪层 | **不**每视图自递归；做成核价模板级**料号闭包**全局条件 |
| 2 | 卡片料号 vs BOM 根 | **相等**（卡片 `hf_part_no` = 递归树根） |
| 3 | 父子关系来源 | **唯一来自 `material_bom_item`**（单一权威，所有核价组件共用） |
| 4 | 客户维度 | 永远 `customer_no = '_GLOBAL_'` |
| 5 | `system_type` | 仅 `'PRICING'` |
| 6 | `characteristic` | **不约束，全拉**（同母件多特性子件全展，接受重复） |
| 7 | 版本默认 | 每节点默认 `is_current = true`（最新核价版本） |
| 8 | 环路保护 | PG16 原生 `CYCLE`；成环料号标记回传**提醒用户** |
| 9 | 累乘用量列 | 本期不做（先把数据查出来） |
| 10 | 报价侧 | 完全不改（开关只在核价模板） |
| 11 | Excel 节点多行 | 节点下某组件 N 行 → 展开 N 子行（跨组件并排布局属二期细节） |
| 12 | 核价组件固定列 | **每个核价组件默认带 3 可见列**：料号 `hf_part_no`、父料号 `parent_no`、版本 `bom_version`（下拉）；**其余业务列用户自配** |
| 13 | 节点级核价版本（核心行为，**修正旧 13**） | 每节点有"生效版本"：默认 `is_current`；用户给某料号选了 `bom_version=V` → **该料号本身**改用 V 查（决定其子件清单 + 该料号自身核价业务数据）；**其带出的子件仍默认 `is_current`**（除非各自再被选）。⇒ **业务数据随生效版本变**（推翻"只换结构不换业务数据"的旧表述） |
| 14 | 版本作用域 | 覆盖表 `{料号 → bom_version}`，默认空 = 全 `is_current` |

---

## 2. 架构决策：中央闭包，而非每视图自递归

否决"每视图带料号+子料号列各自递归"：投料/加工/单价等视图本质"按单料号挂属性"，无父子边；父子结构全局唯一（`material_bom_item`），应只算一次。

**采用：中央闭包注入 `:hfPartNos`**（Java 预算闭包一次/卡片，复用现有 `WHERE inner_q.hf_part_no = ANY(:hfPartNos)` 外包机制）。组件业务视图改动：P1 零改动（只需带 `hf_part_no`），P2 起需带 `hf_part_no + bom_version`（见 §6 分期）。

---

## 3. 闭包查询（唯一递归源，一次/卡片）

产出三样：**料号集合**（喂 `:hfPartNos`）、**spine 骨架**（节点身份 + 父 + 层级 + 节点生效版本）、**成环清单**。下为"全程 `is_current`"的默认形态（P1）：

```sql
WITH RECURSIVE bom_tree AS (
    -- 锚点：根节点 = 卡片料号
    SELECT 1 AS lvl,
           CAST(:rootPartNo AS varchar) AS node_no,
           CAST(NULL AS varchar)        AS parent_no,
           ARRAY[]::uuid[]              AS edge_path     -- 根无入边
    UNION ALL
    -- 递归：取"当前节点生效版本"下的子件边（默认 is_current）
    SELECT parent.lvl + 1,
           child.component_no,
           child.material_no,
           parent.edge_path || child.id                 -- 累加本条边 id → 节点唯一身份
    FROM material_bom_item child
    JOIN bom_tree parent ON child.material_no = parent.node_no
    WHERE child.customer_no = '_GLOBAL_'        -- 口径4
      AND child.system_type = 'PRICING'         -- 口径5
      AND child.is_current  = true              -- 口径7 默认最新（节点级版本切换在此处按节点替换，见 §5.3）
      AND child.component_no IS NOT NULL
      -- 口径6：不约束 characteristic
)
CYCLE node_no SET is_cycle USING cyc_path
SELECT t.lvl,
       t.node_no   AS hf_part_no,                        -- 逐行子件料号（可见列）
       t.parent_no,                                       -- 父料号（可见列，显示用）
       t.edge_path AS node_id,                            -- 节点唯一身份（边id路径，元数据）
       t.edge_path[1:array_length(t.edge_path,1)-1] AS parent_id,  -- 父身份（去末段，元数据）
       v.bom_version,                                     -- 节点生效版本（可见下拉列；此处=当前版）
       t.is_cycle, t.cyc_path
FROM bom_tree t
LEFT JOIN LATERAL (
    SELECT bom_version FROM material_bom_item
    WHERE material_no = t.node_no
      AND customer_no = '_GLOBAL_' AND system_type = 'PRICING' AND is_current = true
    LIMIT 1
) v ON true
ORDER BY t.cyc_path;
```

产出拆解：
- **料号集合**：`DISTINCT node_no WHERE NOT is_cycle` → `:hfPartNos`。
- **spine**：每行 = 一个 BOM 节点 occurrence，含 `node_id`/`parent_id`/`lvl`/`hf_part_no`/`parent_no`/`bom_version`/`cyc_path`。
- **成环告警**：`is_cycle = true` 的 `node_no` → 前端提示"料号 X 存在 BOM 环，已截断"。

**`node_id` 说明（建树连边键）**：`material_bom_item` 无"树节点"概念（存的是边）。`node_id` 是闭包递归时**累加边 id（`material_bom_item.id` UUID）**合成的路径，per-occurrence 唯一。因口径 6 不约束 characteristic、同一子件可多父/多次出现（BOM 是 DAG），**前端建树连边必须用 `parent_id → node_id`（不是料号）**，否则连成 DAG 而非树。`hf_part_no`/`parent_no`（料号）只作显示与数据对号。

> ⚠️ `:rootPartNo` 由核价 expand 入口以卡片料号绑定；`material_no` 为 VARCHAR(20)，与文本绑定相容。

---

## 4. 数据取数：闭包注入 `:hfPartNos`

### 4.1 现状取数链路
- 单卡：`ComponentDriverService.expand` → `DataLoader.loadByPath(driverPath, hint, partNo, customerId)`（`ComponentDriverService.java:355`），`partNo` 单值。
- DataLoader 对 `$view` driver 形态：`partNos = List.of(partNo)`（`DataLoader.java:196`）→ `SqlViewExecutor.executeAllRows` 外包 `WHERE inner_q.hf_part_no = ANY(:hfPartNos)`（`SqlViewExecutor.java:268-280`）。
- 批量合桶：`batchExpand` → `loadByPath(..., partNos, ...)`（`:534`）+ 按 `hf_part_no` 拆回各卡（`:549`）。

### 4.2 改动：核价模式 partNos 由 `[根料号]` 换成"闭包料号集合"
新增 `BomClosureService`（进程级缓存）：
```
BomClosureService.compute(rootPartNo, versionOverrides /*默认空*/) → {
    Set<String>  partSet,      // 喂 :hfPartNos（含根+全部子孙，去环）
    List<SpineNode> spine,     // nodeId/parentId/lvl/hfPartNo/parentNo/bomVersion
    List<String> cyclePartNos  // 成环料号（告警）
}
```
注入点（仅"核价 + 该核价模板 `bomRecursiveExpand=on`"时触发）：`expand` 调 `loadByPath` 前用 `partSet` 走多值入口；现有 `:hfPartNos = ANY(...)` 外包原样生效 → 各核价组件视图返回整棵树行。

### 4.3 ⚠️ 必须关掉"跨卡片合桶"
合桶按 `hf_part_no` 拆结果回各卡（`:549`）。闭包模式下两卡可能**共享子料号** → 拆不开。**强制：开启 BOM 递归的卡片不进合桶通道，逐卡独立查**（各自闭包 → 各自结果，全部归本卡）。关联 AP-37（配对禁用 backend `hf_part_no` 作键）。

---

## 5. 成树渲染 + 节点级版本

### 5.1 产品卡片（P1，多行 + 树）
- 组件按闭包展开整棵树 N 行；每行携带 spine 元数据 `node_id`/`parent_id`/`lvl` + 可见列 `hf_part_no`/`parent_no`/`bom_version`。
- 前端用 **`parent_id → node_id`** 建树（口径选 (b) 父引用式）；缩进可辅以 `lvl`。
- 版本列为**下拉**：点击查该料号所有可选版本（见 §5.3）；P1 下拉仅渲染 + 显示当前版本，**切换重查为 P2**。

### 5.2 核价 Excel（P2，树状）
现状每报价行只产 1 行 Excel（`ExcelViewService.java:124-127`）、每组件只取 `rowDataList.get(0)`（`:257`）——**铺不出树，须改**。二期：行主轴=spine，按 `cyc_path` 排序、`lvl` 缩进；节点下组件 N 行 → N 子行（口径 11）；数据复用闭包展开后的卡片有效行（`CardEffectiveRows`/`CardDataProvider`）。

### 5.3 节点级核价版本（口径 13/14）

**目标行为**：打开报价单 → 全 `is_current`（最新）；用户给料号 X 选 `bom_version=V` → X 改用 V 查（X 的子件清单 + X 自身核价业务数据取 V 版）；X 的子件仍默认 `is_current`；递归同理。

**模型**：
- 覆盖表 `versionOverrides: {料号 → bom_version}`，默认空。
- 节点**生效版本** = `versionOverrides[料号]` 有则用，否则 `is_current` 的当前版本。
- 递归在节点 N 用"N 的生效版本"取 N 的子件边（默认 `is_current`）。
- 业务列取数按 **(本行料号, 本行生效版本)**（P2 起）。
- spine 每节点补充：`effectiveVersion`（本次实际版本）、`availableVersions`（可切版本，`SELECT DISTINCT bom_version FROM material_bom WHERE material_no=X AND system_type='PRICING'` 对齐主表，供下拉）。

**实现取舍**：默认（无覆盖）走 §3 纯 CTE；有覆盖时"按节点动态选版本"在单 CTE 内不便表达 → **Java 逐层迭代展开**应用覆盖；两路产出同一 spine。

> 关联 AP-51：切版本后子件数变化，行数以本次闭包为准，禁止与历史 `snapshotRows` 取 `Math.max`。

---

## 6. 业务列引用契约 + 分期

### 6.1 三固定列怎么被用户自配业务列"当条件"
| 固定列 | 用户配置时如何使用 |
|---|---|
| 料号 `hf_part_no` | **自动**：闭包灌 `:hfPartNos`，业务 `$view` 带 `hf_part_no` 列即被外层 `= ANY(:hfPartNos)` 按本行料号对号取数；子查询内引用走驱动行隐式 JOIN。 |
| 父料号 `parent_no` | 行上下文按需引用（隐式 JOIN / 路径占位符）；一般业务列用不到，主要树/核对用。 |
| 版本 `bom_version` | **P1**：业务列按 `is_current` 取最新（≈现状）。**P2**：按本行**生效版本**取数 → 业务 `$view` 必须带 `bom_version` 列，外层升级为 (料号,版本) 配对。 |

### 6.2 分期（用户选 B）
- **P1（先把数据查出来 + 看见树）**：
  - 闭包（全 `is_current`）→ `:hfPartNos` → 整棵树最新核价数据；
  - 核价组件默认 3 固定列（料号/父料号/版本下拉**UI 占位+显示当前版**）+ 隐藏元数据；
  - 前端 (b) 父引用建树（卡片）；
  - 合桶旁路、环告警、spine 回传；
  - 业务视图硬前提：仅需 `hf_part_no`（同现状，零改动）。
- **P2（版本切换真正生效）**：
  - `versionOverrides` 覆盖路径 + `availableVersions` 下拉真正可选；
  - `SqlViewExecutor` 外层过滤由 `hf_part_no = ANY(:hfPartNos)` 升级为 **(hf_part_no, bom_version) 逐行配对**；
  - 业务 `$view` 硬前提升级：必须带 `hf_part_no + bom_version`；
  - Excel 树状多行渲染（§5.2）。
- **后续**：累乘用量列。

### 6.3 报价侧隔离
触发 = 核价渲染 且 核价模板 `bomRecursiveExpand=on`；报价模板无开关 → `:hfPartNos` 仍 `[根料号]`，报价侧一行不改。引擎以"是否开启 BOM 递归"判据，不写死 costing 分支（防 AP-41）。

---

## 7. 落点（文件触点）

| 改动 | 文件 / 位置 | 期 |
|---|---|---|
| `BomClosureService`（闭包 SQL + spine + 环清单 + `versionOverrides` 入参预留，进程级缓存） | 后端新文件 | P1 |
| 核价 expand 注入闭包集合替换单料号 | `ComponentDriverService.java:355` 附近 | P1 |
| 合桶旁路：开启 BOM 递归 task 不进合桶 | `ComponentDriverService.java:496-549` | P1 |
| 核价模板开关 `bomRecursiveExpand` + 守卫 | 模板/快照 schema + 读取处 | P1 |
| 核价组件默认 3 固定列（料号/父料号/版本下拉） | 组件管理默认字段 / 核价模板装配 | P1 |
| spine + 环告警回传（卡片层级/版本显示） | `ExpandDriverResponse`/batch 响应 + 前端 | P1 |
| 前端 (b) 父引用建树（`parent_id→node_id`） | 核价产品卡片渲染 | P1 |
| 外层过滤升级 (料号,版本) 配对 + 业务视图带 `bom_version` | `SqlViewExecutor`（`executeAllRows`/`buildWrappedSql`） | **P2** |
| `versionOverrides` 生效 + `availableVersions` 下拉 + 子树重查 | `BomClosureService` 覆盖路径 + 前端 | **P2** |
| Excel 树状多行渲染（行主轴=spine） | `ExcelViewService.buildRowData`/`exportExcelView` | **P2** |
| 累乘用量列 | 闭包 SQL + 渲染 | 后续 |

> 协议级改动（`ComponentDriverService`/`DataLoader`/`SqlViewExecutor`/`ExcelViewService`），按 CLAUDE.md 必跑 E2E `quotation-flow.spec.ts` + 核价复测；落在「三大核心模块基线·报价单渲染」范围，开工前走 architect 评估。

---

## 8. 风险与自检

风险：
1. **闭包膨胀**：深/宽树 → `:hfPartNos` 大。缓解：`BomClosureService` 进程级缓存（key=`rootPartNo`+overrides 指纹）+ 去重 + 上限告警。
2. **共享子料号重复**：多父下重复（口径 6/11 接受）；建树靠 `node_id` 区分 occurrence。
3. **合桶旁路遗漏 → 串卡**（AP-37/AP-41，只在 E2E 暴露）。必须 E2E 验。
4. **AP-22 重复行**：闭包/业务视图漏 `is_current` 会拉历史版本。已固化口径 7。
5. **P2 (料号,版本) 配对过滤**改动 `SqlViewExecutor` 较深，且要求业务视图带 `bom_version`；P1 不碰，降低单期风险。

自检（完成判据，P1）：
- 后端：`touch` 重启 → `/q/health` 200；闭包对一个已知多层 BOM 料号返回正确 partSet + spine（node_id/parent_id 正确）+ 无误环标记。
- 前端：`tsc --noEmit` 0 错 + 改动 `.tsx` Vite 200。
- **E2E**：核价 `quotation-flow` 复测：①卡片展开行数 = 闭包子料数；②`parent_id→node_id` 建树正确（重复子件不塌成 DAG）；③刷新 3 次行数稳定（AP-51）；④报价侧无变化（隔离）；⑤`'加载中' final = 0`。

---

## 9. Tasks 拆分

### P1（默认最新渲染 + 树 + 固定列）
- **T1** `BomClosureService`：闭包 SQL（§3，node_id=边路径、节点 bom_version）+ DTO（partSet/spine/cycle）+ `compute(rootPartNo, versionOverrides)` 签名（本期 overrides 恒空走 CTE）+ 进程级缓存 + 单测（多层/环/单层/空/重复子件 DAG）。
- **T2** 核价模板开关 `bomRecursiveExpand` + 仅核价守卫。
- **T3** 核价组件默认 3 固定列（料号/父料号/版本下拉，绑 spine 值）+ 隐藏元数据 node_id/parent_id/lvl。
- **T4** expand 注入闭包 partSet（多值 loadByPath）；单测"开关关=旧行为等价"。
- **T5** 合桶旁路：开启 BOM 递归 task 逐卡独立查，防串卡。
- **T6** 响应扩展 spine + cyclePartNos；前端 (b) 父引用建树 + 层级/版本显示 + 环告警。
- **T7** E2E + 自检（§8），报价侧隔离回归。

### P2（版本切换真正生效）— 另起 plan
- `SqlViewExecutor` 外层 (料号,版本) 配对过滤 + 业务视图强制 `bom_version`；`versionOverrides` 覆盖路径 + `availableVersions` 下拉重查；Excel 树状多行渲染。

### 后续
- 累乘用量列。

---

## 10. 待评审确认点

1. 开关 `bomRecursiveExpand` 落**核价模板**层（非组件/Excel 模板）——同意？
2. spine 回传：随 expand 响应内联（倾向）vs 单独端点？
3. **版本作用域 × characteristic 歧义（P2 前必须定，不阻塞 P1）**：覆盖表按"料号 → 版本"键控，但 `bom_version` 作用域是 per-(料号, characteristic)，而口径 6 不约束 characteristic。当同一料号有多 characteristic、各自多版本时，"选某料号的版本"与"该料号所有可选版本"按什么键定位？（part_no 单键会歧义）
4. P1 是否就是 §9 P1 清单（默认最新 + 树 + 固定列含下拉占位），切换重查/Excel 树/累乘均后置？
