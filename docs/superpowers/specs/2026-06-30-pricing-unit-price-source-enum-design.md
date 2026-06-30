# 核价系统 unit_price.price_type 细分化 — 设计方案

> 日期：2026-06-30 | 状态：已定稿（决策已确认，待实现） | 范围：核价系统 Excel 导入写入端（`system_type=PRICING`）+ 核价侧 SQL 视图重写
> 先例：报价侧 `docs/superpowers/specs/2026-06-08-quote-price-type-subdivide-design.md`（V297，本方案直接参照其做法）
> 关联：`docs/table/核价系统Excel导入落库方案.md`

## 0. 已确认决策（2026-06-30）

1. **拆分范围**：只拆超载的 `MATERIAL` 桶；`ELEMENT`（sheet 1）/ `CONSUMABLE`（sheet 13）已与 Sheet 1:1，**保留不动**（最小化视图改写）。
2. **电镀粒度**：一行拆的 2 条记录都写 `PLATING`，靠 `cost_type`（电镀加工费/电镀材料费）区分（照搬报价侧 V297）。
3. **比例/固定不进 price_type**：来料/成品的"比例 + 固定"各合并为单一值（`INCOMING_OTHER` / `FINISHED_OTHER`），靠 `cost_ratio` vs `pricing_price` 哪列有值区分。
4. **存量数据不处理**：本方案**不写任何回填/重导/迁移脚本**，存量行保持原 `MATERIAL` 等大类不动（见 §4 运维注意）。

## 1. 背景与目标

核价系统 Excel 导入（`system_type=PRICING`）有 **12 个费用 Sheet 落入同一张 `unit_price` 表**（电镀成本拆 2 条）。当前区分来源靠 `price_type`（大类）+ `cost_type` 双字段，问题：

- `price_type` 的 `MATERIAL` 是**超载桶**：sheet 2/14/15/16/17/18/19/20/22/23 全写 `MATERIAL`，单看无法定位 Sheet。
- 其中 4 个 Sheet 的 `cost_type` 是**动态值**（取自 Excel「要素编号/要素名称」列），无法当固定标识，组装时只能拼一长串脆弱条件。

**做法（按用户裁定，直接参照报价侧 V297）**：把核价导入写入端的 `price_type` **直接改写为每 Sheet 细分值，大类在写入端废弃**；`cost_type` 保持不动。下游核价 SQL 视图由用户全面改写为按新细分值取数。**存量数据由用户处理（本方案不写回填脚本）。**

> 与之前"新增 price_source 列"方案的区别：那版为了不动视图而新增列；本版按用户决定**直接改 price_type + 主动重写视图**，与报价侧统一范式，不再新增列。

## 2. 细分映射（核价侧）

| Sheet # | Excel Sheet | Handler | 旧 price_type | **新 price_type** | cost_type（不变） |
|:-:|------|------|------|------|------|
| 1 | 元素核价价格表 | P01 | `ELEMENT` | `ELEMENT`（**保留**，已 1:1） | `元素核价价格` |
| 2 | 材料核价价格表 | P02 | `MATERIAL` | `MATERIAL_PRICE` | `材料核价价格` |
| 13 | 生产耗材BOM | P13 | `CONSUMABLE` | `CONSUMABLE`（**保留**，已 1:1） | `耗材` |
| 14 | 包装材料BOM | P14 | `MATERIAL` | `PACKAGING` | `包装` |
| 15 | 来料加工费 | P15 | `MATERIAL` | `INCOMING_PROCESS` | `来料加工费` |
| 16 | 来料其他费用（比例） | P16 | `MATERIAL` | `INCOMING_OTHER` | 动态(要素编号) |
| 17 | 来料其他固定费用 | P17 | `MATERIAL` | `INCOMING_OTHER` | 动态(要素名称) |
| 18 | 加工费&组装费 | P18 | `MATERIAL` | `SELF_PROCESS` | `自制加工费` |
| 19 | 成品其他比例费用 | P19 | `MATERIAL` | `FINISHED_OTHER` | 动态(要素名称) |
| 20 | 成品其他固定费用 | P20 | `MATERIAL` | `FINISHED_OTHER` | 动态(要素名称) |
| 22 | 电镀成本（加工费） | P22 | `MATERIAL` | `PLATING` | `电镀加工费` |
| 22 | 电镀成本（材料费） | P22 | `MATERIAL` | `PLATING` | `电镀材料费` |
| 23 | 其他外加工成本 | P23 | `MATERIAL` | `OUTSOURCE_PROCESS` | `其他加工费` |

### 设计要点

1. **只拆超载的 MATERIAL 桶**：`ELEMENT`（sheet 1）/ `CONSUMABLE`（sheet 13）本就与各自 Sheet 1:1，**保留不动**（与报价侧 Q01 保留 ELEMENT 同理）。这样核价视图中既有的 `price_type='ELEMENT'` / `'CONSUMABLE'` 过滤**无需改动**，最小化视图改写量。confusion 全部出在 MATERIAL，拆它即解决。
2. **电镀照搬报价侧**：一行拆的 2 条记录都写 `PLATING`，靠 `cost_type`（电镀加工费/电镀材料费）区分（与报价侧 Q17 同范式）。
3. **大类 MATERIAL 在核价写入端废弃**：改后核价导入不再写 `MATERIAL`；旧值仍保留在 CHECK 白名单（存量行 + 报价侧 + 其它系统仍可能用，删除有风险且无必要）。
4. **`cost_type` 全程不动**：动态 cost_type 的 4 个 Sheet（16/17/19/20）其要素值仍存 cost_type；固定/比例费用仍以 `pricing_price` / `cost_ratio` 是否为空区分（V276 既有约定）。
5. **跨系统不撞车**：核价所有 sql_template 都带 `system_type='PRICING'` 过滤，与报价侧 `QUOTE` 隔离；故 `PLATING` 等值即便报价侧也有，按 `system_type` 天然不混。
6. **比例/固定不进 price_type**：来料其他费用、成品其他费用各有"比例 + 固定"两个 Sheet，但**比例费用值落 `cost_ratio`、固定费用值落 `pricing_price`，本就是两个不同的列**——固定/比例靠"哪列有值"天然区分，无需 price_type 再扛。故两对各合并为单一值 `INCOMING_OTHER` / `FINISHED_OTHER`（与报价侧 `INCOMING_MATERIAL_OTHER` / `FINISHED_MATERIAL_OTHER` 范式一致）。`(price_type, 哪列非空)` 仍 1:1 可还原到原 Sheet，不丢信息。
   - 同理电镀：2 条记录都写 `PLATING`，靠 `cost_type` 区分（要点 2）。两处都是"price_type 表业务类别，细分维度交给已有字段"的同一原则。

### 新增枚举值（7 个，需进 CHECK 白名单）

`MATERIAL_PRICE`、`PACKAGING`、`INCOMING_PROCESS`、`INCOMING_OTHER`、`SELF_PROCESS`、`FINISHED_OTHER`、`OUTSOURCE_PROCESS`
（`PLATING` / `ELEMENT` / `CONSUMABLE` 已在白名单，无需新增。）

- 最长 `INCOMING_PROCESS` = 16 字符。列 `price_type` 已是 `VARCHAR(40)`（V297 扩过），**本次无需再扩列宽**。

## 3. 改动落点

### 3.1 DDL — Flyway `V<next>__pricing_unit_price_type_subdivide.sql`（版本号落库前取当前最大+1）

```sql
-- 重建 CHECK：保留现有 14 个 + 新增 9 个核价细分值
ALTER TABLE unit_price DROP CONSTRAINT IF EXISTS chk_unit_price_type;
ALTER TABLE unit_price ADD CONSTRAINT chk_unit_price_type
    CHECK (price_type IN (
        -- 旧大类（存量 / 报价 zcj_view / 其它仍用，保留）
        'ELEMENT','MATERIAL','COMPONENT','PART','CONSUMABLE',
        -- 报价侧 V297 细分值（保留）
        'INCOMING_MATERIAL_PROCESS','INCOMING_MATERIAL_OTHER',
        'INCOMING_MATERIAL_REDUCTION','INCOMING_MATERIAL_RECYCLE',
        'PROCESS','FINISHED_MATERIAL_OTHER','COMPONENT_OTHER','COMPONENT_REDUCTION','PLATING',
        -- 核价侧本次新增细分值
        'MATERIAL_PRICE','PACKAGING','INCOMING_PROCESS','INCOMING_OTHER',
        'SELF_PROCESS','FINISHED_OTHER','OUTSOURCE_PROCESS'
    ));
```

- 不扩列宽（已 40）；**不动 `uq_unit_price`**（结构不含 price_type 的具体值，细分后唯一性只会更细，不新增冲突）；不 DROP 任何真实视图（CHECK 重建是 binary 兼容操作）。

### 3.2 实体 `UnitPrice.java`

- `priceType` 的 `@Column(length=20)` → `length=40`（与 DB 现状对齐；V297 漏改，本次顺手修正。Panache 不强校验，纯 DDL 元数据，低风险）。

### 3.3 Writer `UnitPriceWriter`

- **无需改签名**：`newRow(systemType, priceType, costType, ...)` 已以 priceType 为参数，Handler 传新值即可。INSERT / ON CONFLICT 不动。

### 3.4 Handler 传值（10 个改，2 个不动）

| 改：传新 price_type | 不动 |
|------|------|
| P02→`MATERIAL_PRICE`、P14→`PACKAGING`、P15→`INCOMING_PROCESS`、P16→`INCOMING_OTHER`、P17→`INCOMING_OTHER`、P18→`SELF_PROCESS`、P19→`FINISHED_OTHER`、P20→`FINISHED_OTHER`、P22（两条 p1/p2 均）→`PLATING`、P23→`OUTSOURCE_PROCESS` | P01（ELEMENT）、P13（CONSUMABLE） |

- 各 Handler 当前是 `UnitPriceWriter.newRow("PRICING","MATERIAL",...)` 的硬编码字符串，逐个替换。
- 建议建 `PricingPriceType` 常量类集中 9+ 个值，Handler 引用常量，避免拼写漂移（参照 AP 防静默失败）。

### 3.5 下游 SQL 视图重写（用户负责，本节给完整范围）

> 核价"视图" = `component_sql_view` / `template_sql_view` 表的 `sql_template` 行（V255/V257 为种子，可能已被管理端/后续迁移改过）。**改写前务必以 live DB 的 sql_template 为准**，迁移文件仅供定位。

**a) 无需改（保留 ELEMENT/CONSUMABLE）**：
- `V255` :322/:360 `price_type='CONSUMABLE'`
- `V257` :45 `'CONSUMABLE'`、:120 `'ELEMENT'`

**b) 与本次拆分无关（COMPONENT 非核价导入产物，按现状）**：
- `V255` :397/:517/:663/:701 `price_type='COMPONENT'`
- `V257` :63/:137/:154/:276 `price_type='COMPONENT'`

**c) 重点核查项**：迁移种子里**没有任何** `price_type='MATERIAL'` 过滤 → 说明 MATERIAL 桶数据当前靠 `cost_type` / `code` join 取。拆分后这些取数若仍按 cost_type 则**不受影响**；用户若要改用新细分 price_type 取数（更清晰），需在对应 sql_template 把 `cost_type='电镀材料费'` 之类条件替换为 `price_type='PLATING' AND cost_type='电镀材料费'` 或纯 `price_type='...'`。**务必 grep live sql_template 的 `MATERIAL` / 各 cost_type 中文值，确认无遗漏。**

**d) DDL 纪律**：改 sql_template 后按 CLAUDE.md「视图 DROP CASCADE/重建后必须重启 Quarkus」执行，清进程级缓存（`ImplicitJoinRewriter` / `CachedSqlCompiler`）。

## 4. 存量数据（不处理）

- **本方案不写任何回填 / 重导 / 迁移脚本**（决策 4）。存量 `unit_price` 行保持原值（`MATERIAL` 等大类）不变。
- ⚠️ **运维注意（非本方案代码范围，但必须知会）**：改后核价导入写新细分值；**未重导的存量行仍是旧 `MATERIAL`**，与新值并存。若 §3.5 把 sql_template 改成只认新细分值取数，则存量旧行会取空 → 由运维方决定是否重导/清旧数据，并自行编排与视图切换的上线次序。本方案交付物不包含此动作。
- 开发/测试环境如需对齐，可手动 `DELETE FROM unit_price WHERE system_type='PRICING'` 后重导（与报价侧 V297 决策 6 同款，仅限非生产）。

## 5. 影响面与风险

| 项 | 评估 |
|---|---|
| `price_type` 列宽 / `uq_unit_price` | 不动（已 40；唯一键不含具体值） |
| `ELEMENT`/`CONSUMABLE` 视图过滤 | 不动（保留这两个值） |
| `MATERIAL` 桶视图过滤 | 种子中无 `='MATERIAL'`，低风险；以 live sql_template 为准核查（§3.5c） |
| 报价侧（QUOTE） | 零影响（只改核价 Handler；CHECK 仅增白名单） |
| `cost_type` / 固定vs比例区分 | 不动 |
| 存量行 | 与新值并存；视图切换需配合重导（§4） |
| 主要风险 | ① 漏改某 sql_template 的 cost_type/MATERIAL 取数 → 静默取空；② 拼写漂移（用 §3.4 常量类消除）|

## 6. 验证清单

- Flyway 新迁移 `success=t`；CHECK 含 9 新值；非法值被拒（23514）；最长 20 字符值写入成功。
- 跑一次核价导入：逐 Sheet 行 `price_type` = §2 预期值；电镀一行→2 条 `PLATING`（cost_type 异）；P01/P13 仍 ELEMENT/CONSUMABLE。
- `SELECT price_type, count(*) FROM unit_price WHERE system_type='PRICING' GROUP BY 1` 分布符合预期。
- 用户重写 sql_template 后：核价单/核价模板渲染各 Tab 取数与改前一致（抽关键料号比对核价结果数值），无"—（共N项）"/空值。
- 后端编译 + Quarkus 重启 OK；`/q/health` 200。

## 7. 决策记录（全部已确认，见 §0）

| # | 议题 | 结论 |
|:-:|------|------|
| 1 | 拆分范围 | 仅拆 `MATERIAL`，保留 `ELEMENT`/`CONSUMABLE` |
| 2 | 电镀粒度 | 单 `PLATING` + cost_type 区分 2 条 |
| 3 | 比例/固定 | 不进 price_type，合并为 `INCOMING_OTHER`/`FINISHED_OTHER`，靠 `cost_ratio` vs `pricing_price` 区分 |
| 4 | 存量数据 | 不处理（不写任何脚本，§4） |

> 唯一键说明：合并后 sheet 16/17（同属 `INCOMING_OTHER`）的行 `cost_type`（要素编号 vs 要素名称）+ `seq_no` 不同，`uq_unit_price` 仍可区分，无新增冲突；sheet 19/20（`FINISHED_OTHER`）同理。

## 8. 实现任务清单（落地用）

- [ ] **T1 DDL**：新建 Flyway `V<next>__pricing_unit_price_type_subdivide.sql`（§3.1），重建 `chk_unit_price_type` 加 7 个新值；不扩列宽、不动 uq、不 DROP 视图。
- [ ] **T2 常量**：新建 `PricingPriceType` 常量类，集中 7 个新值 + 复用 ELEMENT/CONSUMABLE/PLATING。
- [ ] **T3 实体**：`UnitPrice.priceType` `@Column(length=20)` → `length=40`。
- [ ] **T4 Handler 改值**：P02/P14/P15/P16/P17/P18/P19/P20/P22(×2)/P23 改传新值（§3.4），引用 T2 常量；P01/P13 不动。
- [ ] **T5 视图 sql_template 重写**（用户主导，§3.5）：以 live DB 的 `component_sql_view` / `template_sql_view` 为准，把按 `MATERIAL`/cost_type 取数处改为新细分值；改完按 CLAUDE.md 重启 Quarkus 清缓存。
- [ ] **T6 验证**：§6 全部跑通；后端编译 + `/q/health` 200。
- [ ] **T7 收尾**：RECORD.md 追加；按开发流程合并并清理 worktree。
