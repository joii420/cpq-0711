# E2 · row_data 的 jsonb 规范化（根因二的直接证据）

## 类型映射

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "row_data", columnDefinition = "jsonb")
public String rowData = "[]";
```

DB 侧确认：`row_data` / `snapshot_rows` / `deleted_row_keys` 三列均为 **jsonb**。

## 库中实际存储形态（取自基准单）

```json
[{"row_index": 0, "材料成本": "0"},
 {"单位": "g", "料件": "AgNi11#-Ⅰ", "row_index": 1, "损耗率": "0",
  "材料净重": "3.3", "材料成本": "0", "材料毛重": "1", "组成数量": "1"}]
```

键序为 PG jsonb 的规范化顺序（先按 key 的**字节长度**，再按字节序）：

| key | UTF-8 字节数 |
|---|---|
| 单位 / 料件 | 6 |
| row_index / 损耗率 | 9 |
| 材料净重 / 材料成本 / 材料毛重 / 组成数量 | 12 |

**不是前端的插入顺序。**

## 推论（高置信，非直接实测）

Hibernate 的 dirty check 比较的是 Java `String`：
- 前端来的串 = `JSON.stringify` 的插入序
- 库里读出的串 = PG 规范化序

⇒ 二者必然不等 ⇒ **每行每次都判脏** ⇒ 9225 条 componentData 全量 UPDATE。

⚠️ **未能直接测得 UPDATE 条数**：该库未安装 `pg_stat_statements`（`SELECT extname FROM pg_extension` 无该行）。要坐实需临时开 `log_statement` 或安装扩展 —— 已列为本任务的前置调研项（B-0）。

## 同源问题：cd.subtotal 缺 compareTo 保护

| 位置 | 写法 | 是否防 scale 误判 |
|---|---|---|
| `QuotationService:477` / `:2532`（`li.subtotal`） | `li.subtotal.compareTo(liDraft.subtotal) != 0` | ✅ repair-260829 B-9 已修 |
| `QuotationService:2645` / `:2655`（`cd.subtotal`） | `if (cdDraft.subtotal != null) reused.subtotal = cdDraft.subtotal;` | ❌ **无条件赋值，B-9 漏修** |

库中基准单 `subtotal` 全部 scale=12（1845/1845），前端若发 6 位则 `equals()` 恒判不等。
