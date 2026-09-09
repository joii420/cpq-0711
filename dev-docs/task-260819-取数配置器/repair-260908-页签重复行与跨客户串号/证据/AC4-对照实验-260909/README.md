# AC-4② · 对照实验（2026-09-09）—— 归档基线失效后的替代验证

## 为什么不能直接用 09-08 的归档基线

`Ac4CostDialectArtifactStableTest` 自己写了这条守卫：`SemanticGraphLoader` 读
`semantic_node_column` **无 `ORDER BY`**，编译器按 PG 堆顺序遍历 ⇒ **任何对该表的写入都会重排
字段、改变产物 md5**。所以比对前必须核对语义图指纹，不一致就要归因为并发写入，
🚫 **不得报成「核价回归」**。

2026-09-09 实测指纹（测试类 `graphFingerprint()` 的口径）：

| | 归档基线（09-08 20:33） | 现在（09-09） |
|---|---|---|
| `semantic_node_column` | 360 | **366** |
| `semantic_node` | 55 | **57** |
| `semantic_edge` | 76 | **78** |
| `semantic_tab_view` | `48:18fd1c81…` | `48:18fd1c81…`（未变） |

⇒ 前三张表在两个时点之间被并发会话改过。**归档基线与现产物已不可直接逐字节比。**

> ⚠️ 主线一度用自写的 psql 聚合（`md5(string_agg(t::text,'|' ORDER BY t.ctid))`）算指纹，
> 得出「`semantic_tab_view` 也变了」。那是**用错了尺** —— 它与测试类的
> `graphFingerprint()` 不是同一个函数。上表已按测试类口径更正。

## 替代方案：同图对照（A/B 背靠背）

在一次性 worktree `ac4-ctrl-260909` 里，**同一份当前语义图**上编译两次：

| 腿 | 代码 | 产物 |
|---|---|---|
| **A** | 分支 `feat/repair-260908-tab-dup-rows` HEAD（含 B-1/B-1b/B-1c） | `A-现产物-分支码/` |
| **B** | **只把 `SemanticCompiler.java` 换成 `master` 版**（自证：`CUSTOMER_SCOPE_COLUMN` 出现 **0** 次，`git diff master` 空），其余文件与迁移全同 | `B-对照-master码/` |

🔑 **两腿的 `_manifest.txt` 指纹逐字节一致**：
`366:31163332… | 57:9deebab8… | 78:664cd5e5… | 48:18fd1c81…`
⇒ 图没在两次之间漂移，**差异只可能来自代码**。

## 结果

**32 / 32 份产物，每一份的唯一差异都是同一形状** —— 桥子查询内部追加
` AND <桥别名>.customer_no = :customerCode`：

```
- WHERE dcdm.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm
                               WHERE dqm.material_no = ANY(:total_material_no))
+ WHERE dcdm.production_no IN (SELECT dqm.production_no FROM ds_quote_material dqm
                               WHERE dqm.material_no = ANY(:total_material_no)
                                 AND dqm.customer_no = :customerCode)
```

**外层锚点一个字节都没动**（连同一行尾部的 `AND :versionFilter(...)` 都原样保留）。
SELECT 列 / JOIN / ORDER BY / 别名全部逐字节不变。

**为什么外层没被加**：核价方言的锚点是 `v_ds_cost_*` 系列视图，它们**本身没有
`customer_no` 列**（实测 `v_ds_cost_basic_element_bom_all` → `f`），B-1 的
`cols.contains(CUSTOMER_SCOPE_COLUMN)` 守卫正确跳过 ⇒ 变的全是 B-1b 的桥。

## 四向自证（🚫 判据既不能恒真也不能恒假）

| # | 判据 | 期望 | 实测 |
|---|---|---|---|
| ① | 量具能认出 1 字节差异（差一个空格） | 报不同 | ✅ |
| ② | 抹除桥谓词后 A 与 B 逐字节相同 | 32/32 | **32/32** |
| ③ | **不抹除**时 A 与 B 相同的份数 | **0**（否则 B-1b 没生效） | **0/32** |
| ④ | 抹除桥谓词后仍含 `:customerCode` 的份数 | **0**（外层零新增） | **0/32** |

③ 是关键的**反向腿**：如果它不是 0，说明有产物压根没被改动覆盖到，
而 ② 的「相同」就退化成了「本来就相同」—— 那是零证据不是通过。

## 判定

**AC-4② 通过**，按 `D-8` 改写后的判据「外层锚点逐字节不变；唯一允许差异 = 桥谓词」。
逐份 md5 见 `md5对照表.txt`（A / B / 抹除后A / 抹除后B 四列）。
