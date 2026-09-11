# 子代理回报 · 测试片 S-D（AC-24 树页签重影修复）

> 落盘时间：2026-09-10 18:0x（本机时区）
> **认领**：AC-24（单点）　**造数前缀**：`T260910D-`
> **被测代码**：worktree `feat/task-260910-sel-ds-tables` 的**在途实现**（`CostingTreeGrouping.java` 的 B-23 改动，未提交）
> **断言来源**：`需求文档.md` AC-24 原文 + `诊断-卡片内snapshot_rows重影.md §5` + `test.md` S-D 行。
> 🚫 未读 `BomTreeRenderService.java` / `ConfigureSnapshotService.java`；`CostingTreeGrouping.java` **只在用例与断言写完之后**、为执行 AC 强制要求的**阳性对照**才读其 diff 并在隔离副本上做干预（详见 §3），🚫 未据此修改任何断言。

## 结论

**AC-24 三条断言 A1 / A2 / A3 全部达成（实测值，非「✅ 通过」）**，阳性对照与阴性对照均按 AC 预期成立。
另加一条 AC 未要求的**过度折叠反向守卫**（合法多 occurrence 未被折叠），也成立。

| # | 断言 | 期望 | **实际** |
|---|---|---|---|
| A1 | 本卡片 `BOM` 页签 `jsonb_array_length(snapshot_rows)` | 3 | **3** |
| A2 | **本卡片内**逐字节相同的元素分组数 | 0 | **0** |
| A3 | 边行数 == 该边在 `ds_quote_material_bom` 的真实行数；且 `A1-1 == A3` | 2 / 2 | **db=2 · 卡片边行=2 · A1-1=2**，且两行内容**不同地保留** |
| 🧪 阳性 | 去重关掉后 A1 | **5** | **5**（A2=2 组、边行=4） |
| 🧪 阴性 | `S0004` 单边场景行数 | 6 且逐位不变 | **6 行，`md5(snapshot_rows)=1ded1158852588e9ed41434b03f5ae80` 三次渲染逐位相同** |

⚠️ **文档内部矛盾一处（需主线修文档，不影响结论）**：`需求文档.md` AC-24 的 🧪 阳性对照那一行仍写「A1 必须**变成 4**」，与同一条 AC 上方「🔴 A1 口径更正 ⇒ 修复前 5」自相矛盾。**实测是 5**，即更正后的表是对的、阳性对照那一行是漏改的旧数字。

---

## 1. 环境与连库（实确认，不凭配置文件）

- 隔离方式：**源码整份 rsync 到 scratchpad 副本**后在副本里跑 `quarkus:dev`，🚫 未在 worktree 里跑过任何 maven ⇒ 与并行两三路**零共享 `target/`**。
  - 副本：`/tmp/claude-1000/-home-joii-project-cpq/54527263-0bc3-4b06-b80f-f4e6682000f1/scratchpad/sd/iso/cpq-backend`
  - 启动：`./mvnw -o quarkus:dev -Dquarkus.http.port=8146 -Dquarkus.http.host=0.0.0.0`（日志 `…/scratchpad/sd/dev.log`）
  - 📌 `-Dmaven.build.dir=` 这条路**在本项目行不通**：`cpq-backend/pom.xml` 没有 `<directory>` 覆盖，Maven 超级 POM 把 `${project.basedir}/target` 写死 ⇒ 该参数不生效。用副本法。
- **撞到 target 假故障：没有**（`Tests run: 0` / `bad class file` / `cannot find symbol` / `NoClassDefFoundError` 一个都没出现）。
- 端口：**8146**（🚫 未占 8081 / 5174）。收尾只 `kill` 了副本自己的 3 个 PID（按 `iso/cpq-backend` 特征精确匹配），🚫 未用任何宽匹配 `pkill`。收尾实测：`8146 → 000`（已停）、**主线 `8081 → 401`（仍活）**。
- **连库实确认**：`GET /api/cpq/quotations?page=1&size=1` → `"totalElements":156`；`select count(*) from quotation` → `cpq_db_0724 = 156` / `cpq_db_test = 134` ⇒ **被测后端连的是 `cpq_db_0724`**（默认 profile，用户真实开发库）。
  - 选它的理由（实查得出，非偏好）：`cpq_db_test` **没有**本次要用的模板 `45cc0267…（正泰测试模板1）`（`tpl45cc=0`），树页签渲染起不来。两库基线差异复核：`component_sql_view` 99 vs 42、`costing_bom_tree_config` 3 vs 2（`QUOTE` 口径两库都在）。
- 迁移：worktree 最高 `V439`，`cpq_db_0724` 已应用最高也是 `V439` ⇒ 起临时后端**没有新迁移落到共享库**。
- 🚫 全程无 DROP / TRUNCATE / 无 WHERE 的 DELETE / 清库；未动任何全局状态（用户启停用、模板发布态、共享模板/组件/视图**一个字节未改**）。

## 2. 造的数据 + 触发条件成立性自证

**触发条件（唯一）**：同一 `(customer_no, 父件料号, 子件料号)` 在 `ds_quote_material_bom` ≥2 行。

自造料号（🚫 未在任何现网已有料号上加重复边）：

| 料号 | 角色 |
|---|---|
| `T260910D-P-01` | 成品 P（重影场景根） |
| `T260910D-C-01` | 子件 C |
| `T260910D-P2-01` / `T260910D-M2-01` | 过度折叠反向守卫用（§4） |

边行（`customer_no='CUST-0004'`，`source='MANUAL'`）：

```sql
SELECT customer_no, material_no, input_material_no, count(*) edge_rows,
       array_agg(item_seq ORDER BY item_seq) seqs
FROM ds_quote_material_bom WHERE material_no LIKE 'T260910D-%' GROUP BY 1,2,3;
```
```
 customer_no |  material_no  | input_material_no | edge_rows | seqs
-------------+---------------+-------------------+-----------+-------
 CUST-0004   | T260910D-P-01 | T260910D-C-01     |         2 | {1,2}   ← 触发条件成立
 CUST-0004   | T260910D-P    | T260910D-C        |         2 | {1,2}   ← ⚠️ 不是我造的，见 §6
```
两行刻意在**被 `$view` 投影的业务列**上取不同值（`item_seq 1/2`、`组成数量 1/2`、`净重 11/22`、`损耗率 1/2`），
这样 A2 才有判别力 —— 若两行投影后逐列相同（`BL-0256` 那 3 组脏边就是这样），A2 修复后仍会 >0，**那是正确行为**。

报价单（自建，🚫 未碰 `e9ac790d` 与 `QT-20260910-0813~0816`）：

| 单号 | quotation_id | line_item_id | 产品 | 用途 |
|---|---|---|---|---|
| `QT-T260910D-SHADOW` | `11110000-…-260910d00001` | `22220000-…-260910d00001` | `T260910D-P-01` | A1/A2/A3 + 阳性对照 |
| `QT-T260910D-NEG` | `…d00002` | `…d00002` | `S0004` | 阴性对照 |
| `QT-T260910D-MULTIOCC` | `…d00003` | `…d00003` | `T260910D-P2-01` | 过度折叠反向守卫 |

单头/明细行由**只读克隆**同客户同模板的既有单（源 `f385d2bb…` / 行 `947aa6c2…`）得到，模板 = `45cc0267…`，
`BOM` 页签组件 = `7f9a5bbf-264f-4b07-8dff-3118a9428a48`（`COMP-2345`，`bom_recursive_expand=t` ⇒ **树页签**，正是 AC-24 的范围）。

渲染入口：`POST /api/cpq/configure-product/quotations/{id}/refresh-snapshot` → **200**，卡片落 `quotation_line_component_data`（`snapshot_at` 有值）。
> 📌 这是**新渲染**的卡片，不是存量 —— 所以不受 `BL-0260`「存量 32 张不自愈」的干扰。

## 3. A1 / A2 / A3 实测（SQL + 返回值）

完整输出见 `证据/S-D-AC24-1-修复后.txt`（阳性 `-2-`、还原后 `-3-`、多 occurrence `-4-`、干预日志 `…干预生效证据-devlog.txt`、整段 `snapshot_rows` 原文 `…shadow-card-snapshot_rows.json`）。

**A1**
```sql
SELECT jsonb_array_length(cd.snapshot_rows) FROM quotation_line_component_data cd
WHERE cd.line_item_id='22220000-0000-4d00-a000-260910d00001'
  AND cd.component_id='7f9a5bbf-264f-4b07-8dff-3118a9428a48';
```
→ `card_id=451b52c0-75b1-41f4-81ed-6b6fecce4352 | tab_name=BOM | a1_rows = 3`（= 树根 1 + 边行 2）✅

**A2（🚫 只在本卡片上扫，未做全库扫）**
```sql
WITH e AS (SELECT r.elem FROM quotation_line_component_data cd
           CROSS JOIN LATERAL jsonb_array_elements(cd.snapshot_rows) r(elem)
           WHERE cd.line_item_id='22220000-…-d00001' AND cd.component_id='7f9a5bbf-…')
SELECT count(*) FROM (SELECT elem, count(*) c FROM e GROUP BY 1 HAVING count(*)>1) t;
```
→ `a2_dup_groups = 0` ✅

**A3（计数层）**
```
 db_edge_rows | card_edge_rows | a1_minus_root
            2 |              2 |             2
```
（`card_edge_rows` = `snapshot_rows` 里 `driverRow.parent_no IS NOT NULL` 的行数）✅

**A3（内容层 —— 两行必须「不同地保留」，不是两行一模一样）**
```
   parent_no   |  material_no  | item_seq | qty | net_weight | loss_rate
---------------+---------------+----------+-----+------------+-----------
               | T260910D-P-01 |          |     |            |            ← 树根行
 T260910D-P-01 | T260910D-C-01 | 1        | 1   | 11         | 1
 T260910D-P-01 | T260910D-C-01 | 2        | 2   | 22         | 2
```
两条边行的 `item_seq` / 组成数量 / 净重 / 损耗率**逐列各不相同且与库里两行一一对应** ⇒ 既没重影，也**没被折叠成 1 行**。✅

## 4. 两个对照实验（都做了）

### 🧪 阳性对照 —— 把去重关掉，A1 必须变 5
- 干预：在**隔离副本**里把 `CostingTreeGrouping` 的 `if (seenNodes.add(nodeKey(r))) {` 改成 `if (true) { // T260910D 阳性对照…`（等价于回到未去重的旧行为）。
- **干预生效证据**（🚫 不靠「我改了所以它生效了」）：`dev.log` 第 325 行
  `Restarting quarkus due to changes in CostingTreeGrouping$Result.class, CostingTreeGrouping.class.` + `Live reload total time: 6.960s`。
- 重跑同一条 `refresh-snapshot` → **A1 = 5**，A2 = **2 组**，卡片边行 = **4**（= m×n = 2×2），内容层出现
  `seq 1/qty 1` ×2 与 `seq 2/qty 2` ×2 的两两全等行 ⇒ **用例确实踩在重影场景上，这次读数有效**。
- **还原**：把 pristine 副本拷回，`md5sum` 与 worktree 原文**逐字节一致**（`2a9525b24cc692f5d041ee03e0893dd1`），
  `diff` 对比 worktree 原文**无差异**；再次 `Restarting quarkus …CostingTreeGrouping.class`（dev.log:598）后重跑
  → **A1=3 / A2=0 / A3=2·2·2**（`证据/S-D-AC24-3-还原后复验.txt`）。
  📌 **worktree 里的实现文件全程未被我改过**（干预只发生在 `/tmp` 副本）。

### 🧪 阴性对照 —— `S0004` 单边场景逐位不变
`QT-T260910D-NEG` 的 `S0004` 卡片 `BOM` 页签：**去重开 = 6 行 / 去重关 = 6 行 / 还原后 = 6 行**，
且三次 `md5(snapshot_rows::text)` 全部 = `1ded1158852588e9ed41434b03f5ae80` ⇒ **逐位不变**，单边场景零误伤。
（md5 在三次独立渲染间稳定，说明该指纹本身有判别力 —— 内容真变了它就会变。）

### ➕ 额外：过度折叠的反向守卫（AC 未要求，我加的）
造 `P2 → M2`、`P2 → C`、`M2 → C` 三条**各 1 行**的边 ⇒ 同一子件 `C` 合法地出现在两个不同父件下（node_path 不同）。
修复后渲染 **4 行**，两个 `C` occurrence **都在**且各带自己的业务值：
```
 parent=∅        child=T260910D-P2-01  node_id=T260910D-P2-01
 parent=P2-01    child=C-01   seq=2 qty=3  node_id=…P2-01/…C-01
 parent=P2-01    child=M2-01  seq=1 qty=1  node_id=…P2-01/…M2-01
 parent=M2-01    child=C-01   seq=1 qty=4  node_id=…P2-01/…M2-01/…C-01
```
⇒ 修法甲的五元组去重**没有**把「同料号多 occurrence」误折叠。

## 5. 自检口径

- 🚫 未把「全量 `mvnw test` 全绿」当门槛（`mat_*` 表本库从未创建）；本片**根本没跑全量测试**，只跑了针对 AC-24 的端到端读数。
- `BL-0261` 两个恒红存量测试（`BomTreeRenderServiceTreeParamMaskingTest` / `QuoteBomTreeEndToEndTest`）本片未触及，也未据其归因。
- 本片断言**全部按自造对象收窄**（本 line_item / 本料号 / 本边），🚫 无任何全局计数断言。

## 6. 待回收清单（🚫 我没有自己删，请主线裁决后执行）

📌 **保留而非自删的两个理由**：① 派工明确要求「待回收清单交主线，不要自己删」；② 保留现场可让主线亲验时直接打开 `QT-T260910D-SHADOW` 复核。

**A. 本片自造（`T260910D-*-01` 命名空间）**

| 对象 | 数量 | 定位 |
|---|---|---|
| `quotation` | 3 | `quotation_number IN ('QT-T260910D-SHADOW','QT-T260910D-NEG','QT-T260910D-MULTIOCC')` |
| `quotation_line_item` | 3 | `id::text LIKE '22220000-0000-4d00-a000-260910d%'` |
| `quotation_line_component_data` | 15 | 随上面 3 个 line_item（FK ON DELETE CASCADE） |
| `ds_quote_material` | 4 | `material_no IN ('T260910D-P-01','T260910D-C-01','T260910D-P2-01','T260910D-M2-01')` |
| `ds_quote_material_bom` | 5 | `material_no IN ('T260910D-P-01','T260910D-P2-01','T260910D-M2-01')` |
| `quotation_view_structure` | 0 | 实查 0 行 |

回收命令（**命中面已量化 = 上表数字，请主线批准后执行**；每条都带 WHERE，无 DROP/TRUNCATE）：
```sql
DELETE FROM quotation WHERE quotation_number IN
  ('QT-T260910D-SHADOW','QT-T260910D-NEG','QT-T260910D-MULTIOCC');   -- 3 行，级联带走 line_item / component_data
DELETE FROM ds_quote_material_bom WHERE customer_no='CUST-0004'
  AND material_no IN ('T260910D-P-01','T260910D-P2-01','T260910D-M2-01');  -- 5 行
DELETE FROM ds_quote_material WHERE customer_no='CUST-0004'
  AND material_no IN ('T260910D-P-01','T260910D-C-01','T260910D-P2-01','T260910D-M2-01');  -- 4 行
```

**B. 不是我造的、但占着同一前缀（请主线归属给 B-23 后再定去留）**
`ds_quote_material` 的 `T260910D-P` / `T260910D-C`（`material_name` 写着「B23重影验证-成品/子件」，`created_at 2026-09-11 00:33:23`）
+ `ds_quote_material_bom` 对应 **2 行**边（id 16609/16610）。🚫 我**没有**读写它们，也没有删。

**C. 临时物**：`/tmp/…/scratchpad/sd/`（源码副本 + 日志 + SQL 脚本），随会话清理；后端进程已停（`8146 → 000`）。
**D. 一次性库**：**无**（本片没建任何库）。

## 7. 「AC 在当前实现下过不了」的情况

**无。** 三条断言 + 两个对照 + 一条额外守卫全部按 AC 预期成立，🚫 未放松任何断言。

唯一需要主线处理的是 §结论里那条**文档内部矛盾**（AC-24 的阳性对照行残留旧数字「4」，应为「5」），它是**文档缺陷**，不是实现缺陷。
