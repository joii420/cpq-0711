# 改动前基线（主线采集，2026-09-16）

采集时刻（UTC）：2026-09-17T01:56:31Z；验收库克隆开始（UTC）：2026-09-17T01:58:08Z

## 验收库 `cpq_db_260916`

- 建法：`CREATE DATABASE cpq_db_260916` → `pg_dump -Fc --no-owner --no-acl cpq_db_0724`（7.9 MB）→ `pg_restore -j 4 --no-owner --no-acl`
- 恢复告警：仅 `SET transaction_timeout = 0` 不被 PG16 识别（pg_dump 18.6 客户端输出，无害），其余 0 错误
- 与源库比对（克隆后立即）：表 261 / 视图 32 / 函数 6 / 序列 106 / flyway 最大 443 / quotation 196 / builder 视图 110 / semantic_edge 78 / ds_quote_incoming_other_fee 6 / user 28 / `component_sql_view` 全表 md5 `4155158c6fd218c0de993953759c6af9` —— **两库逐项相同**
- 🚦 一次性库：闸门 B 时进「待回收清单」，回收需用户批准（`DROP DATABASE cpq_db_260916;`）

## 共享 8081（master 代码 + `cpq_db_0724`）上采集的文件

| 文件 | 用途 | 要点 |
|---|---|---|
| `refresh-all-snapshots-预览-8081.json` | AC-9 键集合 · AC-11 集合 B | `recompileViews=110`、`recompileChanged=0`、`recompileChangedViewNames=[]` ⇒ **B = 空集**。调用前后 `component_sql_view` / `template` 的 md5 不变（零写入已核） |
| `compile-COMP-0002-8081.json` / `compile-COMP-0003-8081.json` | AC-11 第二段（改动前编译 SQL） | 两者均含 `material_recipe`（物料BOM / 材质元素本来就连材质表） |
| `compile-COMP-0004-8081.json` | 参考 | 改动前编译 SQL |
| `E-1-COMP-0004-预览-8081.json` | 证伪实验 E-1 阳性对照 | `rowCount=2`：`00144` → `_物料_材料名 = null`；`S3110520422` → `料号2` |
| `查名连线-cpq_db_0724.csv` | AC-1 改动前连线与连接键 | 46 行 |
| `施耐德5.4模板-cpq_db_0724.csv` | AC-13 参考 | ⚠️ 采集时已有 **v1.0~v1.6** 七个版本（v1.5/v1.6 为用户立项期间新发布；v1.6 的 `sql_views_snapshot` 与前 6 版仅 COMP-0007 视图不同）；全部版本 `updated_at` 于 01:42:54 被同时刷新（用户在配置器保存组件所致） |
| `三组件-cpq_db_0724.csv` | AC-13 参考 | COMP-0004/0005/0008 的字段表、公式、视图 md5 |

8081 验明正身：`GET /api/cpq/quotations` 的 `totalElements=196` = `cpq_db_0724.quotation` 行数 196。
E-1 的界面截图未采集（接口原始响应已足以作阳性对照）。
