# 物料BOM 子表毛重/净重/单位独立落库 + 产出料号类型存汉字 — 设计

> 日期：2026-06-17 ｜ 状态：设计已确认，待实现
> 关联文档：`docs/table/报价系统Excel导入落库方案.md` §3 物料BOM
> 影响代码主体：`MaterialBomMergeHandler.java`、`MaterialBomItem.java`、新 Flyway 迁移

---

## 1. 背景与目标

报价系统「物料BOM」Sheet 导入时，三列「材料毛重 / 材料净重 / 重量单位」当前被**借住**写进了
`material_bom_item` 的通用字段 `composition_qty` / `base_qty` / `issue_unit`（这三个字段语义上是
"组成数量 / 基础数量 / 投入单位"，与组成件BOM 共用）。这导致语义错配，也让物料BOM 与组成件BOM
在同字段上互相纠缠。

本次目标：把毛重/净重/单位改存到 `material_bom_item` 的**专用新列**
`rough_weight` / `net_weight` / `weight_unit`，并顺带规范「产出料号类型」的存储格式
（由"原始带编号文本"统一为"只存汉字标签"）。

**本次只改落库链路**，不改报价/核价显示视图（见 §5 已知副作用）。

---

## 2. 需求决策（与用户逐条确认）

| # | 决策 | 取值 |
|---|------|------|
| 核心 | 毛重/净重/单位落库目标 | 改存专用新列 `rough_weight` / `net_weight` / `weight_unit` |
| Q2 | 旧字段 `composition_qty`/`base_qty`/`issue_unit` 在物料BOM 侧 | **不再写**（组成件BOM 侧照旧写，组成件优先逻辑不变） |
| Q3/Q4 | 「产出料号类型」存储格式 | **只存汉字**（剥离 "N." 前导编号）：`1.银点类→银点类`、`2.非银点类→非银点类`、`组成件→组成件`、`边角料→边角料` |
| Q4 | 上述汉字规则作用范围 | `material_bom_item.component_usage_type` **和** `material_master.material_type` 两处都改 |
| Q5 | 组成件BOM 侧 `material_master.material_type` 硬编码值 | 由 `"3"` 改为 `"组成件"` |
| Q6 | `v12_raw_bom` 显示视图 | **本次不改**（接受报价"来料BOM"Tab 毛重/净重/单位列暂时变空，显示后续单独处理） |
| Q7 | 存量数据 | **不写迁移**，靠重导自愈（文件即权威） |

---

## 3. 改动清单（端到端）

### 3.1 DDL 迁移（新 Flyway `VNN`）

为 `material_bom_item` 增列：

```sql
ALTER TABLE material_bom_item
    ADD COLUMN IF NOT EXISTS rough_weight DECIMAL(18,6),
    ADD COLUMN IF NOT EXISTS net_weight   DECIMAL(18,6),
    ADD COLUMN IF NOT EXISTS weight_unit  VARCHAR(20);
```

- 文件名取当前最大版本号 +1（实现时 `ls db/migration/ | sort` 取最大值后递增）。
- ⚠️ 属 schema DDL 改动：落盘后须 `touch` 一个 java 文件强制 Quarkus dev 重启
  （CLAUDE.md「视图/schema DDL 后必须重启」规约），并验证 `flyway_schema_history` 该版本 `success=t`。

### 3.2 实体字段

`cpq-backend/.../v6/entity/MaterialBomItem.java` 增三字段：

```java
@Column(name = "rough_weight", precision = 18, scale = 6)
public BigDecimal roughWeight;

@Column(name = "net_weight", precision = 18, scale = 6)
public BigDecimal netWeight;

@Column(name = "weight_unit", length = 20)
public String weightUnit;
```

### 3.3 合并器内容列白名单

`MaterialBomMergeHandler.CONTENT_COLS`（现 `"seq_no","component_no","component_usage_type",
"composition_qty",...`）追加三列 `"rough_weight","net_weight","weight_unit"`。

- 这三列是**物料BOM 独有字段**：组成件BOM 循环不写它们，故合并时按现有"取并集保留物料BOM 独有字段"
  的逻辑自然保留，无冲突取值问题。
- 通用写入器 `VersionedV6Writer` 按内容列 map 通用写库，新增列会随白名单一起被写入；实现时确认
  写入器对未知列不会丢弃（按 CONTENT_COLS 驱动）。

### 3.4 导入器映射改动（`MaterialBomMergeHandler` 物料BOM 循环，约第 79~82 行）

```text
旧:
  c.put("component_usage_type", componentUsageType);   // 原始带编号文本
  c.put("composition_qty", row.getDecimal("材料毛重","毛重"));
  c.put("base_qty",        row.getDecimal("材料净重","净重"));
  c.put("issue_unit",      row.getStr("重量单位"));

新:
  c.put("component_usage_type", labelOnly(componentUsageType));   // 只存汉字
  c.put("rough_weight", row.getDecimal("材料毛重","毛重"));
  c.put("net_weight",   row.getDecimal("材料净重","净重"));
  c.put("weight_unit",  row.getStr("重量单位"));
  // 物料BOM 侧不再 put composition_qty / base_qty / issue_unit
```

### 3.5 产出料号类型 → 只存汉字（新 helper `labelOnly`）

新增静态 helper（与现有 `digitsOnly` 并列），剥离前导数字 + 紧随的分隔符，保留其后标签：

```java
/** "1.银点类"→"银点类"；"2.非银点类"→"非银点类"；"组成件"→"组成件"；null→null。 */
private static String labelOnly(String s) {
    if (s == null) return null;
    String t = s.trim();
    // 去掉前导数字
    int i = 0;
    while (i < t.length() && t.charAt(i) >= '0' && t.charAt(i) <= '9') i++;
    // 去掉数字后紧随的一个分隔符（. 、．／/ 空格 等）
    if (i > 0 && i < t.length()) {
        char sep = t.charAt(i);
        if (sep == '.' || sep == '。' || sep == '、' || sep == '．'
                || sep == '/' || sep == '／' || sep == ' ' || sep == '\t') i++;
    }
    String rest = t.substring(i).trim();
    return rest.isEmpty() ? t : rest;   // 无可剥离内容则原样返回
}
```

应用点：
- 物料BOM 循环：`material_master` upsert 的 `material_type` 由 `digitsOnly(componentUsageType)`
  改 `labelOnly(componentUsageType)`（约第 74 行）。
- 物料BOM 循环：`component_usage_type` 用 `labelOnly(componentUsageType)`（约第 79 行，见 §3.4）。
- 组成件BOM 循环：`material_master` upsert 的 `material_type` 由硬编码 `"3"` 改硬编码 `"组成件"`
  （约第 104 行）。

### 3.6 文档回写

`docs/table/报价系统Excel导入落库方案.md` §3：
- 子表映射表「材料毛重/材料净重/重量单位」三行备注更新为对应新字段。
- 「产出料号类型」相关「只写入数字」改为「只写汉字」（子表 §3 + 料号表 `material_type` 两处备注）。

---

## 4. 不变量（不能破坏）

- **组成件BOM 侧不受影响**：仍写 `composition_qty` / `issue_unit` / `seq_no`，
  `v_zcj_view` / `cfg_child_parts` 等组成件视图照常取数。
- **组成件优先合并逻辑不变**：`composition_qty` / `issue_unit` / `seq_no` 冲突仍取组成件值；
  本次新增的 `rough_weight` / `net_weight` / `weight_unit` 只来自物料BOM 侧，不参与冲突。
- **第 4 步 FLIP 反向 characteristic、CFG- 守卫、material_master 副作用**等既有纪律全部保留。

---

## 5. 已知副作用（用户已确认接受）

1. **报价"来料BOM"Tab 毛重/净重/单位列暂时变空**：`v12_raw_bom` 视图仍从旧字段
   `composition_qty`/`base_qty`/`issue_unit` 取数（`input_qty`/`output_qty`/`input_unit`/`output_unit`），
   本次不改视图（Q6-B）。物料BOM 侧不再写旧字段 → 这几列取不到值。显示链路后续单独立项处理。
2. **选配兜底显示变化**：`ConfigureProductService` / `ConfigureSearchResource` 中
   `COALESCE(mr.symbol, mm.material_type)` 在无配方匹配时用 `material_type` 兜底，值会从 `"1"`
   变为 `"银点类"`（显示变化，非报错）。已确认可接受。
3. **版本血缘**：内容列结构与 `component_usage_type` 取值格式变化 → 存量料号首次重导时
   `bom_version+1`，属预期。

---

## 6. 自检计划

- **后端编译/启动**：`touch` java 文件强制 Quarkus 重启 → `curl /q/health` 期望 200；
  `flyway_schema_history` 新版本 `success=t`。
- **落库验证**：导入一份含毛重/净重/单位 + 各类产出料号类型（含「组成件」「边角料」无编号值）的
  物料BOM，`psql` 查 `material_bom_item`：新列 `rough_weight/net_weight/weight_unit` 有值、
  旧列 `composition_qty/base_qty/issue_unit` 为空、`component_usage_type` 为汉字；
  查 `material_master.material_type` 为汉字（物料BOM 投入料号 + 组成件料号「组成件」）。
- **E2E 回归**：因改了 `MaterialBomMergeHandler`（BOM 落库链路），按 `docs/E2E测试方法.md` 跑
  `quotation-flow.spec.ts`，确认无新增"加载中"残留（`'加载中' final count = 0`）。
  说明：来料Tab 毛重/净重/单位变空是预期（§5.1），不计为回归失败。
- **worktree 后端测试纪律**：若跑 Maven 测试，须在本 worktree 的 `cpq-backend/` 内执行
  （见记忆 `cpq-worktree-maven-test-tree`），不要 cd 主仓跑。

---

## 7. 范围之外（明确不做）

- 不改 `v12_raw_bom` 及任何报价/核价显示视图（Q6-B）。
- 不写存量数据迁移脚本（Q7-A）。
- 不动组成件BOM 的写入逻辑、不动 `VersionedV6Writer` groupKey、不动 characteristic 隔离。
