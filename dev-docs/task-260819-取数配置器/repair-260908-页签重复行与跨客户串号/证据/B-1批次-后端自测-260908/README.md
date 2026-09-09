# 后端第一批（B-1 / B-1b / B-1c / B-2 / B-6 / B-7）自测证据 · 2026-09-08

> 采集环境：**临时 dev server 8098**（本 worktree 代码；8081/5174/8099/8097 全程未碰，用完即停）
> 库：`10.177.152.12:5432/cpq_db_0724`（默认 profile）｜ 全部 psql 操作均为 `SELECT`，**零写入**

| 文件 | 是什么 |
|---|---|
| `28视图-预览前-md5.tsv` / `28视图-预览后-md5.tsv` | B-6 预览**前后**各一份 28 个 `sql_template` 的 md5。`diff` 为空 = AC-14 零写入 |
| `28视图-预览前-全文.txt` | 28 个视图改动前的 `sql_template` 全文（供逐字比对） |
| `28视图-预览后-编译产物全文.txt` | 同 28 个视图**改动后**的编译产物全文（走 `/compile`，只读不落库） |
| `28视图-差异分类表.txt` | 逐视图分类：**28/28 全部「仅客户谓词」，零列序/列内容变化** |
| `compile_all.py` | 产出上表的脚本。两个坑写在注释里：① urllib 必须禁代理否则恒 502；② 剔谓词要**两边都剔** |
| `B-6预览响应.json` | `{recompile:true, confirm:false}` 的完整响应（28/28 + 13 模板 / 76 报价单） |
| `before_quote.sql` / `after_quote.json` | QUOTE「产品」页签 A/B（锚点谓词） |
| `before_cost.sql` / `after_cost.json` | COST_BASIC「产品」页签 A/B（B-1b 桥子查询谓词，外层锚点不变） |
| `before_bom_quote.sql` / `after_bom_quote.sql` | QUOTE「BOM」树页签 A/B（B-1c 根分支 + NOT EXISTS） |
| `ab_before.sql` / `ab_after.sql` | 行数 A/B：同参数下 **8 行 → 4 行** |
| `模板快照-预览后-md5.tsv` | 13 个 PUBLISHED/ARCHIVED 模板快照 md5（预览后） |

🚫 **本批次未执行 `confirm=true`**，未写过 `component_sql_view` / `template` 任何一行。
