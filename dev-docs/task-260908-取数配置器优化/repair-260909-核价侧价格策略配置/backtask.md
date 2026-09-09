# 后端任务分解 · repair-260909

> 本次**全部改动在后端**：1 处 Java + 1 个 Flyway 迁移（含 2 个视图重建 + 语义图数据）。
> AC 原文在 `问题说明.md ⑥`，**任务分解是分解结果，AC 原文才是验收标准**，有出入以 AC 原文为准并报主线。

---

## B-1 · 价格边第 2..N 键改用 `leftColumn`（服务 AC-P2 / AC-R1）

**文件**：`cpq-backend/src/main/java/com/cpq/builder/compiler/SemanticCompiler.java:1429-1437`

**现状**：
```java
if (keys.size() > 1) {
    String hfExprForJoin = requalifyAnchorExpr(c);
    for (int i = 1; i < keys.size(); i++) {
        on.add(PRICE_FUNC_ALIAS + "." + keys.get(i).rightColumn + " = " + hfExprForJoin);
    }
}
```
第 2..N 键的**左侧写死成锚点表达式，`leftColumn` 被完全忽略**。

**改成**：`c.anchorAlias + "." + keys.get(i).leftColumn` —— 与同方法 key[0] 的 `codeExpr` 一致，
也与普通边 `ensureLeftJoin`（`:502`）的 `alias + "." + k.rightColumn + " = " + c.anchorAlias + "." + k.leftColumn` 一致。

### 🔑 这是抹平不一致，不是加机制

`:502` 早就在用 `leftColumn`。**只有 PRICE 分支例外**。
⚠️ 那段的现场注释「task-260907 B-8 后 QUOTE 侧只剩 element_code 一个键，走不到这里」**已经过期** ——
实测报价侧 `semantic_edge_key` 就是 2 行（`element_code` / `material_no`），**天天走这条分支**。
🚫 不要相信那句注释而跳过报价侧回归。

### 🚫 三条不许动

1. **不许改 key[0] 的处理**（`codeKey` / `elementCodeSourceColumn`）—— 形态 B（`AC-23`，元素键改绑手填字段）依赖它。
2. **不许删或弱化 `COMPILE_PRICE_EDGE_NOT_FOUND`**（`:1387`）—— 它防的是「选了 FUNCTION 节点的列但锚点没 PRICE 边」被静默当成普通列，报出不相干的错。`AC-N2` 钉这条。
3. **不许删 `COMPILE_PRICE_MULTI_FUNC`**（`:1398`）—— 别名 `cep` 只有一个，两个价格函数合成一张视图无业务含义。

### ⚠️ 等价性必须实测，不许推理宣布

报价侧 `key[1].leftColumn = 'material_no'`、`anchor_expr = 'dqeb.material_no'`、`anchorAlias = 'dqeb'`
⇒ 两种写法**应当**生成同一串 SQL。**这是待验命题不是前提**（`AC-R1`）。
🚨 采基准 → 改 → 重采 → `diff`；**且先故意写错一个列名证明 `diff` 会动**，否则空对空。

---

## B-2 · 两张核价元素BOM视图补 `sales_material_no`（服务 AC-P2 / AC-B1 / AC-B2 / AC-R3）

**对象**：`v_ds_cost_basic_element_bom_all` / `v_ds_cost_detail_element_bom_all`
两者都是 `ds_cost_*_element_bom` **UNION ALL** `ds_cost_*_element_bom_history` 的形态，**两支都要加**。

**新列**：`sales_material_no`，来源 `material_master.material_no`，按 `production_no` 桥接。

### 🚨 必须用 LATERAL + LIMIT 1，不许裸 LEFT JOIN

```sql
LEFT JOIN LATERAL (
    SELECT m.material_no FROM material_master m
     WHERE m.production_no = e.production_no
     ORDER BY m.material_no LIMIT 1
) mm ON TRUE
```
**理由是实测的**：`material_master` 存在 1 生产料号 → 2 销售料号（`TEST0813-P01-PROD`，22 个里 1 个）。
裸 `LEFT JOIN` 会让**元素BOM行本身翻倍** —— 那比价格取错严重得多（污染的是 BOM 结构）。
`AC-B2` 用这条现成样本钉它。

### 🚫 两条不许

1. **不许给 `ds_cost_*_element_bom` 底表加物理列** —— 加了就归锚点，且底表由导入器写，多一列多一处写入点。桥接是派生，属视图的活。
2. **新列必须追加在末尾**，不许插在既有列中间（`AC-R3` 断言既有列名字与顺序不变）。

---

## B-3 · 语义图迁移（服务 AC-P1 / AC-P2）

| 对象 | 内容 | 每方言 |
|---|---|---|
| `semantic_node` | `FUNC_ELEMENT_PRICE`，`node_kind='FUNCTION'`，`func_signature` **与报价侧逐字相同**：`f_material_element_price(:customerCode, :priceBaseDate)` | ×1 |
| `semantic_edge` | `ELEMENT_BOM --PRICE--> FUNC_ELEMENT_PRICE`，`MANY_TO_ONE` | ×1 |
| `semantic_edge_key` | `seq=0` `element_code` → `element_code`；`seq=1` `sales_material_no` → `material_no` | ×2 |
| `semantic_node_column` | 登记 `sales_material_no`。🚫 **`roles` 必须留空** —— 给它 `PART_NO` 会把它卷进材料名查名边的锚点判定（`FieldTreeBuilder:387` 按 `PART_NO` 找插入位），静默改掉材料名的位置 | ×1 |

方言 = `COST_BASIC` + `COST_DETAIL`，合计 2 节点 / 2 边 / 4 边键 / 2 列。

**顺带更正 QUOTE 侧节点的 `note`**：现文「只挂 QUOTE 方言：函数按销售料号取价，核价侧锚点是生产料号，
语义对不上，不硬接」在本次之后会**主动误导下一个人**。改成记录真实卡点与本次处置。

### 🚨 迁移号：四方核对，且落库后当场报主线

写文件前**重新四方核对**：共享库 `flyway_schema_history` × master 的 `db/migration` ×
当前分支的 `db/migration` × `target/classes/db/migration`（第四方**双向**看）。
🚨 **迁移一落共享库，立刻在回报的第一行单独说一句「已落共享库 V4xx」** ——
这条规则最近连续落空四次，最近一次（`V433`）漏的是主线自己。

---

## B-4 · 协议级自检（`change-protocol.md`）

本次动了 **PRICE 边的解析语义** + **两张视图的列集** ⇒ 协议级，必须逐点勾：

1. `Sec31CompileCorrectnessTest` 墓碑哨兵 —— `V430` 加节点时打红过它两条断言。本次加 FUNCTION 节点与 PRICE 边，须复核 `lookupOrAuxEdgeIds` 白名单口径。⚠️ **若确需放宽白名单，停下报主线**（该常量 Javadoc 明写放宽 = 削弱哨兵，需用户裁决）。
2. 全工程 grep `keys.size() > 1` / `requalifyAnchorExpr` 的其他消费点，确认没有第二处依赖「PRICE 键=anchor_expr」这个旧语义。
3. `component_sql_view.sql_template` 存量 md5 前后不变（`AC-R4`）。

## B-5 · 后端自检（交付前必跑，输出贴进回报）

1. 迁移 `select version, success from flyway_schema_history where version='4xx'` → `success = t`
2. 两张视图 `\d+ v_ds_cost_basic_element_bom_all` 含新列且在末尾；`count(*)` 与改前相同
3. `AC-R1` 报价侧编译产物 `diff` 为空 —— **先做灵敏度实验**（故意写错列名 → 确认 diff 有输出 → 改回）
4. `AC-P2` 核价侧 compile 产物含 `cep.material_no = <锚点>.sales_material_no`，🚫 不含 `= <锚点>.production_no`
5. `AC-P3` 两侧同一 (客户,料号,元素) 取价逐位相同 —— **先证明两侧都非空**再比
6. `mvnw test` 全绿（含 `Sec31CompileCorrectnessTest`）
7. N+1：本次不新增查询路径，不适用

## 🚫 越界纪律

- 只做 B-1 ~ B-3。🚫 不许改前端任何文件（`fronttask.md` 已判定前端零任务）
- 🚫 不许改渲染链路（`SqlViewExecutor` / `ComponentDriverService` / 核价渲染）—— `enrichCustomerCode` 与方言无关，实测能绑；若实测发现绑不上，**停下报主线**，不要自行扩范围
- 🚫 不许动 `component.row_key_fields`、不许动报价侧任何语义图行
- 🚨 遇 `CLAUDE.md §3.2` 红线（`DROP`/`TRUNCATE`/无 `WHERE` 的 `DELETE`/`UPDATE`）→ 停下报主线，你没有批准权

---

## 📌 主线已代跑的预实验（2026-09-09，事务内执行后 `ROLLBACK`）

**别再重跑一遍去确认这三条，直接用**：

| 问题 | 实测结论 |
|---|---|
| `CREATE OR REPLACE VIEW` 加列要不要先 `DROP`？ | **不要**。新列追加在末尾时 PG 直接替换成功 ⇒ **B-2 不触碰 §3.2 红线** |
| LATERAL 桥接取到的值对不对？ | 对。`3120014539 → S-3120014539` —— 正是那个有冻结 `Ag` 价（43358.75）的销售料号 |
| 会不会行翻倍？ | **不会**。`v_ds_cost_detail_element_bom_all` 改前 7 行、改后 7 行 |

⚠️ 这是**预实验不是交付**：正式迁移仍须自己跑一遍并贴 `AC-R3`/`AC-B2` 的前后对照。
`v_ds_cost_basic_element_bom_all` 改前 **15 行**（本次未预跑，你来对照）。
