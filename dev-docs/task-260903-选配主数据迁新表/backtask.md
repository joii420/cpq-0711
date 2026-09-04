# task-260903 · 后端任务拆解

> 全部改动在后端与数据库。前端零改动（见 `fronttask.md`）。

---

## 阶段 B · 渲染读取侧（先行）

| 编号 | AC | 内容 |
|---|---|---|
| **B-1** | B-AC-1/4/5/6 | **建兼容视图**（迁移脚本）。三张：<br>`v_compat_material_master` / `v_compat_material_bom_item` / `v_compat_element_bom_item`<br>结构 = `V6 存量` `UNION ALL` `新表投影`。列映射见 `api.md §2`。<br>🚨 **列名、列序、类型必须与 V6 表逐字一致** —— 组件 SQL 只做表名替换，任何列差异都会让 135 段 SQL 里的某一段静默失败 |
| **B-2** | B-AC-5 | 兼容视图里 `component_usage_type` 由 `LEFT JOIN material_recipe ON code = input_material_no` 取 `symbol`。<br>⚠️ **必须 LEFT JOIN**：外购件的 `input_material_no` 不在 `material_recipe` 里，INNER 会把外购件行整行吞掉 |
| **B-3** | B-AC-2 | **135 个组件 SQL 表名替换**。`component_sql_view.sql_template` 里的表名替换为兼容视图名。<br>🚨 **必须是精确的标识符边界替换**，🚫 不许裸 `replace()` —— `material_bom` 是 `material_bom_item` 的前缀，先替换短的会把长的改坏。建议按 `\bmaterial_bom_item\b` → `\bmaterial_bom\b` 的**长优先**顺序，且替换后逐条回读校验 |
| **B-4** | B-AC-3 | **3 个 PG 视图改造**：`v_composite_child_materials` / `v_composite_child_processes` / `v_composite_child_elements` |
| **B-5** | B-AC-2/3 | **改造前后快照比对工具**：改造前把每个组件视图的渲染结果落一份基线，改造后逐单元格 diff。<br>📌 这是 B 阶段唯一可信的验收手段 —— 135 段 SQL 靠人眼看不出来 |
| **B-6** | B-AC-7/8 | **性能基线 + 复测**：改造前后各测一次 ≥100 行报价单的渲染耗时与 SQL 条数 |

---

## 阶段 A · 选配写入侧（B 落地后）

| 编号 | AC | 内容 |
|---|---|---|
| **A-1** | A-AC-1 | `ConfigureProductService` 的料号主档写入：`insertMaterialMasterV6` → 改写 `ds_quote_material`（免版本表，直接 upsert，唯一键 `material_no`） |
| **A-2** | A-AC-1/6 | BOM 写入改走 `VersionedGroupWriter.writeGroup`：<br>`@Inject VersionedGroupWriter`，`sheet` 取报价 Registry 的 `MATERIAL_BOM`，`axisValue` = 销售料号，`source` = `SOURCE_MANUAL`。<br>🚨 **`rows` 必须是该料号的整组全量行**，传部分 = 其余被当成删除 |
| **A-3** | A-AC-1 | 元素含量同上，走 `ELEMENT_BOM` sheet |
| **A-4** | A-AC-6 | **投影维度**：写 BOM 行时 `output_material_type` **必须显式填** `ASSEMBLY` / `RECIPE`（对齐 V6 `characteristic`）。<br>🚨 它是**指纹比对项**，留空会让用户下次导入误判「内容变了」而整组升版 |
| **A-5** | A-AC-7 | 外购件身份写 `ds_quote_material.material_type='外购件'`、零件写 `'零件'`。<br>🚩 **依赖 V409 落地**，未落地前本项阻塞 |
| **A-6** | A-AC-3 | 客户产品编号：`sel_product_no` → `ds_quote_customer_part`。**`sel_product_no` 表退役**（保留表与数据，仅停写，对齐选配模板下线的做法） |
| **A-7** | A-AC-2 | **停写 V6 五表**：删除 `material_master` / `material_bom` / `material_bom_item` / `element_bom` / `element_bom_item` 的写入调用 |
| **A-8** | A-AC-4/9 | 指纹命中路径**保持不变**（`:401 return hit` / `:1943 跳过父级落库`）——现有实现已符合「命中即引用」，🚫 不要改它 |
| **A-9** | A-AC-5 | 选配阶段不升版 —— **无需额外代码**：新料号走 `writeGroup` 的 CREATED 分支必然 `version_no=1`（`VersionedGroupWriter:215` 硬编码）。本项只需**测试守卫**，防后人误加升版逻辑 |
| **A-10** | A-AC-10 | 并发同编号 → 409。`uq_ds_quote_customer_part (customer_no, customer_product_no)` 已保证，捕获 23505 映射成 409（复用 `task-260902` B-23 的 `isUniqueViolation`） |
| **A-11** | R-1 | 改造 `task-260902` 的 33 条测试断言：从查 V6 表改为查 `ds_quote_*` |

---

## 保留不动（写明理由，防后人误删）

| 表 | 为什么留在 V6 |
|---|---|
| `sel_part_signature` | 选配专有的去重逻辑，不属于料号基础资料，新体系无对应表 |
| `quote_material_no_seq` / `quote_customer_code` / `material_customer_map` | 发号器。新体系**没有任何发号机制**，料号全部来自 Excel |
| `quotation_line_item` / `quotation_line_process` / `quotation_line_composite_process` | 单据层，新体系无对应表 |
| `unit_price` / `capacity` | `ds_quote_self_process_fee` 是「按料号导入的基础费率」，与「报价单行的工序单价」语义不同 |

---

## 双向覆盖

**正向 —— 每条 AC 有人认领**：
B-AC-1→B-1 · B-AC-2→B-3,B-5 · B-AC-3→B-4 · B-AC-4→B-1 · B-AC-5→B-2 · B-AC-6→B-1 · B-AC-7→B-6 · B-AC-8→B-6
A-AC-1→A-1,A-2,A-3 · A-AC-2→A-7 · A-AC-3→A-6 · A-AC-4→A-8 · A-AC-5→A-9 · A-AC-6→A-4 · A-AC-7→A-5 · A-AC-8→A-1~A-7 合力 · A-AC-9→A-8 · A-AC-10→A-10 · R-1→A-11

**反向 —— 每个任务项指回 AC**：
B-1→B-AC-1/4/6 · B-2→B-AC-5 · B-3→B-AC-2 · B-4→B-AC-3 · B-5→B-AC-2/3 · B-6→B-AC-7/8
A-1→A-AC-1 · A-2→A-AC-1/6 · A-3→A-AC-1 · A-4→A-AC-6 · A-5→A-AC-7 · A-6→A-AC-3 · A-7→A-AC-2 · A-8→A-AC-4/9 · A-9→A-AC-5 · A-10→A-AC-10 · A-11→R-1

**无指不回 AC 的条目。**

---

## 🚨 合并前必做（漏了就是生产事故）

### ① 还原两个迁移的 `.hold` 后缀

2026-09-03 为防止「未批准就自动应用到共享库」，把两个迁移改名加了 `.hold`（提交 `9ea3c141`）：

```
V410__task260903_compat_views.sql.hold
V411__task260903_rewrite_component_sql.sql.hold
```

🚫 **带着 `.hold` 合进 master = 灾难**：Flyway 不识别该后缀 ⇒ 迁移永远不跑 ⇒
**兼容视图不存在，而 135 段 SQL 已改名指向它** ⇒ 报价单渲染全线 500。

**合并前的动作**：
```bash
cd cpq-backend/src/main/resources/db/migration
mv V410__task260903_compat_views.sql.hold          V410__task260903_compat_views.sql
mv V411__task260903_rewrite_component_sql.sql.hold V411__task260903_rewrite_component_sql.sql
# 然后确认目录里没有任何 .hold
ls | grep '\.hold$' && echo '❌ 还有残留' || echo '✅ 已还原'
```

⚠️ **迁移号在合并那一刻要重新确认** —— 共享库是移动靶，V410/V411 可能已被别的任务线占用。
查**共享库** `flyway_schema_history` 的 `max(version)`，🚫 不要只 `ls` 目录
（目录里看不到别人已应用未合并的号）。

### ② 合并顺序：B 必须先于 A 上线

A 先于 B 上线 = 选配产品在报价单里渲染为空。两者若同批合并，确认 B 的迁移号小于 A。


---

## 🚨 迁移与共享库的流程闸（今天栽了三次，必须成为默认动作）

**同一形状的事故在 2026-09-03~04 发生三次：**

| # | 谁的迁移 | 卡住了谁 |
|---|---|---|
| ① | 我的 `V401~V404`（task-260902） | 「新料号数据规则」会话不敢合并 |
| ② | 我的 `V410/V411` | 「产品管理优化」会话冷启动连续 4 次失败；8081 存活 20 小时纯属侥幸 |
| ③ | 后端代理的 `V414` | 从 master 冷启动 `not resolved locally: 414` |

**根因不是「谁忘了合并」，是流程缺一道闸：**

```
worktree 里任何一次 mvnw test（@QuarkusTest 会启动实例）
  → migrate-at-start 自动把迁移写进【共享库】
  → 无提示、无确认、事后无检查
  → 而文件还在未合并分支上
  ⇒ 所有从 master 或其它分支冷启动的后端全部起不来
```

⚠️ 而 **Flyway 只在启动时校验** —— 已经跑着的进程不会立刻死，所以**故障是延迟引爆的**：
下一个重启它的人（可能是几小时后的另一条线）才踩到，且现场已经和肇事动作脱钩。

### 三条默认动作

1. **跑测试前**：`git merge master` —— 分支落后于共享库是**常态**，不是异常
2. **跑测试后**：查一次 `flyway_schema_history` 的 `max(version)`，与本分支迁移文件比对；
   有新号 = 你刚刚往共享库写了东西，**立刻把 `.sql` 单独合进 master**（不必合整条分支）
3. **合并前**：用这条扫全工程（`src` 与 `target/classes` 都要扫，`target` 里有编译期复制的副本）
   ```bash
   find /home/joii/project/cpq -path "*/db/migration/V4*.sql" | awk -F'/V' '{n=$NF+0; if(n>=409) print}'
   ```

### 处方（三次都一样）

```bash
git checkout master
git checkout <分支> -- cpq-backend/src/main/resources/db/migration/V4xx__*.sql
git commit -m "fix: 补 V4xx 迁移文件进 master（已应用共享库，缺文件致冷启动 validate 失败）" -- <那个路径>
# 然后必须真的冷启动一次验证，不能只看文件在不在
```

🚫 **另外三条路都别走**：删 `flyway_schema_history` 行（§3.2 红线，且 DDL 效果已在库里，删了会重跑撞「对象已存在」）；
`validate-on-migrate=false`；`baselineVersion` —— 后两条都是改共享配置来掩盖问题。
