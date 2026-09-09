# backtask.md · 后端任务分解

> 每项标「服务的 AC」。⚖️ **任务分解是分解结果，`需求文档.md` 的 AC 原文才是验收标准**，有出入以 AC 原文为准并报主线。
> 🚨 **批次顺序不可乱**：0 → 1 → 2 → 3。批次 1 内部 `B-2/B-3 → B-4 → B-5` 也不可乱（`AC-12` 专钉这条）。

---

## 批次 0 · 关掉测试直写共享 dev 库

### B-1 · `application-test.properties` 指向独立测试库
**服务的 AC：`AC-1`**

- 现状：`application-test.properties:24` 的默认值是 `cpq_db_0724` —— **就是 dev 库本身**
- 改法：指向独立库（库名与建库方式由实现者定，须在回报里写明）
- 🚫 **不许靠「让测试跑不起来」来通过** —— `AC-1` 明确要求 `Q13ComponentOtherFeeHandlerTest` **仍全绿**
- ⚠️ 这会影响**所有人**的测试环境，改完必须在回报里写清「其他会话需要做什么」

---

## 批次 1 · 退役 3 个 `v_compat_*`

### B-2 · 改写 3 个 `v_composite_child_*` 视图直读 `ds_quote_*`
**服务的 AC：`AC-3`、`AC-12`**

| 视图 | 现依赖 | 改后 |
|---|---|---|
| `v_composite_child_materials` | `v_compat_material_bom_item` + `v_compat_material_master` | `ds_quote_material_bom` + `ds_quote_material` |
| `v_composite_child_elements` | `v_compat_element_bom_item` + `v_compat_material_master` | `ds_quote_element_bom` + `ds_quote_material` |
| `v_composite_child_processes` | **3 条路径**：一阶 ← `unit_price`；二阶 ← 两个 compat | 二阶换 `ds_*`；**一阶 `unit_price` 本期保留**（它不在退役对象里） |

🚨 **必须保持输出列集与语义不变** —— 前端读的是渲染结果，`ComponentDriverService` 多处按路径名分支（`:531/:568/:851/:948/:1028`）。
🚫 **不许顺手"优化"视图的 `DISTINCT ON` / `COALESCE` 兜底逻辑** —— 那会引入本任务范围外的数据变化。

### B-3 · 修 `costing_bom_tree_config` 2 行配置（`A0-5`）
**服务的 AC：`AC-4`**

- 行 `d6defaa0-…`：`FROM v_compat_material_bom_item` → `ds_quote_material_bom`
- 行 `82612f2b-…`：`FROM material_bom_item` **直连老表** → `ds_quote_material_bom`
  📌 这一行是 **V411 表名替换时漏改的**，前置审计发现，属基线之外的新增修正

### B-4 · 产出批次 1 数据变化对照清单（`A0-4` 前置，**必须先于 B-5**）
**服务的 AC：`AC-5`**

产出一份「改动前 vs 改动后」逐料号对照，至少含：
- 42 个重叠料号的品名/类型取值（标出预期变化的 3 个单元格）
- 6 个将消失料号的当前引用面（`3110520422` `mbi_ref=4`/`mb_ref=1`；`3120011203` `mbi_ref=15`/`mb_ref=5`/`mcm_ref=1`）
- 🚦 **清单交主线 → 主线呈报用户 → 用户确认后才允许执行 B-5**

### B-5 · `DROP VIEW v_compat_*` ×3
**服务的 AC：`AC-2`、`AC-15`**

🚨 **`CLAUDE.md §3.2` 不可逆红线【数据销毁】**。执行前**三步缺一不可**：
1. 量化影响面（每个视图的依赖对象数、行数）
2. 说清可恢复路径（`V410`/`V415`/`V428`/`V429` 的 DDL 可原样重建；视图无数据）
3. 🚦 **用户明确批准本次** —— **批准不跨对象**，批了删 A 不等于批了删 B

🚫 **子代理没有批准权**：遇到这一步**停下报主线**。

### B-6 · 回滚演练
**服务的 AC：`AC-13`**

用 `V410`/`V415`/`V428`/`V429` 原样重建 3 个视图，验 `v_compat_material_master` 行数回到 **2715**、`AC-3` 仍通过。
🚫 **不许只在文档里写「可回滚」** —— 必须真跑一次，附命令原始输出。

---

## 批次 2 · `v_ds_cost_*_all` 换桥

### B-7 · 桥从 `material_master` 换成 `ds_quote_material` + 守卫同批
**服务的 AC：`AC-6`、`AC-7`**

两个视图各有**两段** `LEFT JOIN LATERAL`（当前版 + `_history` 版），共 4 处：

```sql
LEFT JOIN LATERAL (SELECT m.material_no FROM material_master m
                   WHERE m.production_no = e.production_no
                   ORDER BY m.material_no LIMIT 1) mm ON true
```

改为 `FROM ds_quote_material m`。✅ 实测逐值等价（唯一能桥的 `3120014539` 两侧都得 `S-3120014539`）。

🚨 **必须与 `CostAllVersionViewSelfCheck` 的守卫改动同一批提交**：该守卫在 `@Observes StartupEvent` 里双向比对视图与主表列集，**多一列即 `IllegalStateException` 应用起不来**，且开关全工程无覆盖。
📌 这是 `repair-260909` 的原样教训：**量具所在的层，和失败发生的层，不是同一层** —— 事务内 `CREATE OR REPLACE` + `ROLLBACK` 的预实验**永远碰不到启动期失败面**。

---

## 批次 3 · 摘除 pending 机制

### B-8 · pending 写点改 no-op（`A0-1` 乙）
**服务的 AC：`AC-8`**

对象（以前置审计的 41 个写点清单为准，以下为**已点名的下界**）：

| 写点 | 动作 |
|---|---|
| `V6QuotationCommitService:139` `repointPendingOwnership` | 10 张表的 `UPDATE … SET pending_quotation_id` |
| `QuoteBackfillService:77` `flipPending` | `UPDATE material_master SET pending_quotation_id = NULL` |
| `QuotationService:2332` · `PendingHygieneService:123` · `QuoteImportService:285` | `deletePendingWithGuard` |
| `VersionedV6Writer:325-327` | pending 分支（`if (pendingQuotationId != null)`） |

**改法（`A0-1` 乙）**：保留方法签名 + 内部改空实现 + 标 `@Deprecated`，Javadoc 写明「已随 task-260909 摘除，物理删除留到 `DROP TABLE` 任务」。

🚨 **三份 pending 清单故意不等长（9 / 8 / 10）**，源码注释逐条说明了原因（删除须走引用守卫 ⇒ `material_master` 不入删除清单）。
🚫 **摘除时「顺手对齐成一样长」会引入真 bug** —— 原文写着「两清单不该"顺手对齐成一样长"」。

🚫 **不许动 `pending_quotation_id` 列本身**（`A0-2` 甲），**不许删 `MaterialMasterCrudService` 的读过滤**（`A0-3` 乙）。

### B-9 · 3 处用户可见文案改指向 `ds_`（`D-4`）
**服务的 AC：`AC-9`**

| 位置 | 类型 |
|---|---|
| `SqlViewValidator.java:106` | 祈使型（错误码 `SQL_VIEW_DEPRECATED_TABLE`，**错误码不改**） |
| `BnfPathLinter.java:107` | 祈使型（`suggestion`；`PUBLISHED`=ERROR / `DRAFT`=WARN） |
| `Q05ElementRecoveryHandler.java:90` | 诊断型（`recordError` → 到用户手里） |

🔑 **为什么这三处非改不可**：它们的**存在目的**就是把用户从 V44 `mat_*` 引导到 V6 表 ——
本批退役后，**这两条建议本身就变成新的 AP-53**，系统会拿着过期推荐清单把用户导向下一批不存在的表。

🚫 **5 处日志型文案本期不改**（`QuotationService:2335` · `PendingHygieneService:129` · `QuoteImportService:288` · `V6QuotationCommitService:92` · `ConfigureProductService:1081`）——
优先级低两级（只是日志失真，不误导用户），混进来会让 review 抓不住重点。

### B-10 · 全程行数不变核验
**服务的 AC：`AC-10`**

每个批次前后各采一次 10 张表的行数 + 指纹。**本任务不删任何数据行。**

---

## ⚠️ 全局纪律

- 🚨 **`§3.2` 红线只有 `B-5` 一处**（`DROP VIEW` ×3）。其余交付项在数据层**零不可逆操作** —— 这是 `A0-1/2/3` 三条裁决合起来的有意设计
- ⚠️ **共享库上的基线是瞬时量**：每个批次执行前**重采**，不要用文档里写死的数字（那是 2026-09-09 的快照）
- ⚠️ **N+1 硬指标**：本任务不新增循环体查询；`B-8` 改 no-op 时注意别把批量 SQL 拆成逐行
- 🚫 **不许跑 `mvnw test` 打共享库** —— 除非 `B-1` 已完成并验证
