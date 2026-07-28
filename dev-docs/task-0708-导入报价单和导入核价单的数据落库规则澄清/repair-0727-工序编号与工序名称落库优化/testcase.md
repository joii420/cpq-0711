# 测试用例 —— 工序编号与工序名称落库优化（repair-0727）

> 上游：`需求文档.md`（第四轮定稿）/ `backtask.md`（后端任务书）/ `test.md`（测试任务书骨架）
> 本文档 = `test.md` 骨架的**可执行落地版**：补全前置数据脚本、基线 SQL、每条用例的精确断言、`test.md` 未覆盖的边界/异常用例。
> **本阶段只设计，不执行**。执行阶段直接按本文档编号跑，产出证据后填入 `test.md` §3.1 要求的测试报告。

---

## 0. 复核事实（写用例前已用 codegraph + Read 核对代码，不是照抄需求文档）

| 事实 | 来源 |
|---|---|
| Q14 `sheetName()="组装加工费"`，列头 `销售料号\|项次\|组装工序\|组装加工费\|货币\|计价单位\|拒收率/不良率（%）`；`materialNo==null \|\| processNo==null` → `recordError("宏丰料号/工序编号","必填项为空")`（既有校验，本次原样保留，只是提前到 Phase 1） | `Q14AssemblyProcessFeeHandler.java:50-57` |
| Q14 groupKey=`(material_no, resource_group_no=QUOTE_ASSEMBLY)`，CONTENT 现含 8 列，`VERSION_TRIGGER=(process_no, seq_no)` | 同上 `:36-41` |
| Q15 `sheetName()="组装加工费年降"`，列头 `销售料号\|项次\|组装工序\|年降顺序\|年降系数（%）\|单次固定年降值\|货币\|计价单位\|降价次数`；groupKey 含 `operation_no`；`code==null` → `recordError` | `Q15AssemblyAnnualDiscountHandler.java:48-62` |
| `SheetRow.getStr(keys...)` 是 **contains 匹配**、按列序取首个命中列；`exact()` 是精确表头匹配 | `SheetRow.java:44-56` |
| `process_master` 表：`process_no varchar(20) NOT NULL UNIQUE`，`process_name varchar(50) NOT NULL`（**无唯一约束**） | `ProcessMaster.java` |
| `ProcessMasterRepository.findFirstByProcessName` 存在但 backtask 明确**禁止 resolver 复用它**（逐行查库） | `ProcessMasterRepository.java:37-39`，`backtask.md` T1 |
| `ProcessMasterImportService` upsert 语义：`ON CONFLICT(process_no) DO UPDATE SET process_name = EXCLUDED.process_name`（**名称直接覆盖，非 COALESCE**）；同批同码首行胜出；`process_no`/`process_name` 任一空则跳过（不阻断） | `ProcessMasterImportService.java:37,175-176` |
| 报价导入入口：`POST /api/cpq/basic-data-import/v6/quote`（multipart：`customerId` UUID + `file`），异步；轮询 `GET /api/cpq/basic-data-import/v6/{recordId}` | `BasicDataImportV6Resource.java:53-92,138-155` |
| 工序主数据导入入口：`POST /api/cpq/v6/process-master/import`（multipart `file`，角色 `SYSTEM_ADMIN`）；单条新增 `POST /api/cpq/v6/process-master`（角色含 `SALES_MANAGER`/`PRICING_MANAGER`/`SYSTEM_ADMIN`） | `ProcessMasterResource.java` |
| 端到端耗时日志：`Log.debugf("[v6import] QUOTE TOTAL elapsed=%.0fms status=%s sheets=%d", ...)`；`com.cpq` 包默认 `DEBUG` 级别（`application.properties:97`），控制台可见，**无 `quarkus.log.file` 配置**（日志只在跑 `quarkus:dev` 的进程标准输出里，需自行捕获） | `QuoteImportService.java:158-159` |
| `capacity` 表 `material_no varchar(20) NOT NULL`，**无 FK 到 material_master**，故 `组装加工费` sheet 可用独立于「物料BOM」sheet 的合成料号造多料号场景，不受跨 sheet 校验牵连 | `V220__create_v6_pricing_resource_tables.sql:81-106` |
| `VersionedV6Writer.writeVersionedGroup`：①触发列+全内容都同 → 复用旧版本号、**不写**；②触发列同、内容不同 → **原地更新，复用旧版本号**；③触发列不同 → 升版 + 老组 flip `is_current=false` | `VersionedV6Writer.java:143-199` |
| 客户表：`customer(id, code, ...)`，导入接口用 `customerId`（UUID）查出 `code` 作为 V6 `customer_no` | `Customer.java:11`，`BasicDataImportV6Resource.java:64-66` |
| 测试数据 `报价系统模板0723.xlsx`：「组装加工费」sheet 仅 2 行数据，销售料号 **`S-3120014539`**（项次1=焊接/0.08，项次2=铆接/12，货币 RMB，计价单位 PCS）；「组装加工费年降」sheet **仅表头，0 行数据**（需自行填充造数据） | 实测 `openpyxl` dump（见 §1.3） |

---

## 1. 前置数据准备

### 1.1 FX-1：工序主数据（永久保留，需求方 N7 决定）

Sheet 名 `工序`，列头 `工序编号 | 工序名称 | 工序类别 | 是否外协 | 标准币种 | 标准单位 | 默认不良率`：

| 工序编号 | 工序名称 | 工序类别 | 是否外协 | 标准币种 | 标准单位 | 默认不良率 |
|---|---|---|---|---|---|---|
| Z100 | 焊接 | 组装 | 否 | CNY | PCS | 0.01 |
| Z101 | 铆接 | 组装 | 否 | CNY | PCS | 0.01 |

经「主数据维护 → 工序 → 导入工序」（`POST /api/cpq/v6/process-master/import`）录入。**验收完成后不删**（供 master 合并后其他会话直接可用）。

验证：
```sql
SELECT process_no, process_name FROM process_master WHERE process_no IN ('Z100','Z101') ORDER BY process_no;
-- 期望：Z100|焊接、Z101|铆接
```

### 1.2 FX-2：临时工序（用后即删，仅供 TC-04/TC-11/TC-12/TC-06 用）

用单条接口 `POST /api/cpq/v6/process-master`（不影响 FX-1 批文件）临时新增，用例结束立即 `DELETE /api/cpq/v6/process-master/{id}` 清理，**不得残留**（会污染后续所有名称匹配用例）：

| 用例 | 临时数据 | 用途 |
|---|---|---|
| TC-04 | `process_no=BEND, process_name=Z100` | 验证「先按编号匹配」不会被「名称恰好等于别的编号字符串」干扰 |
| TC-11 | `process_no=Z205, process_name=焊接` | 同名两条，验证取 `process_no` 升序第一条（`Z100`） |
| TC-12 | `process_no=Z050, process_name=焊接` + `process_no=Z205, process_name=焊接`（与 TC-11 共用 Z205，两条一起挂） | 同名三条，验证仍取升序第一条（`Z050`） |

> 每条临时数据用完立即删除并用 `SELECT process_no FROM process_master WHERE process_name='焊接' ORDER BY process_no;` 复核只剩 `Z100` 一条，再进入下一条用例，**防止用例间相互污染**（这是 test.md 骨架没强调但必须补的纪律——同名匹配类用例互相之间是有状态依赖的）。

### 1.3 FX-3：报价 Excel 变体生成脚本

基线文件：`docs/table/报价测试数据/v2/报价系统模板0723.xlsx`（只读，不直接改）。

已实测该文件两张相关 sheet 现状：
```
组装加工费 dims: A1:G4
('销售料号','项次','组装工序','组装加工费','货币','计价单位','拒收率/不良率（%）')
('S-3120014539', 1, '焊接', 0.08, 'RMB', 'PCS', None)
('S-3120014539', 2, '铆接', 12, 'RMB', 'PCS', None)
(None,None,None,None,None,None,None)

组装加工费年降 dims: A1:I4
('销售料号','项次','组装工序','年降顺序','年降系数（%）','单次固定年降值','货币','计价单位','降价次数')
(全部 None —— 需自行填数据)
```

用以下脚本骨架在执行阶段派生各变体到 scratchpad（每个变体独立文件，禁止相互覆盖，文件名自解释）：

```python
import openpyxl, shutil

SRC = "docs/table/报价测试数据/v2/报价系统模板0723.xlsx"

def clone(dst):
    shutil.copy(SRC, dst)
    return openpyxl.load_workbook(dst)

# ---- V-AC1AC2AC5：原样，不改（AC-1/AC-2/AC-5(失败态用另一变体) 用基线本身）----

# ---- V-TC02：按名称匹配，值含前后空格（trim 验证）----
wb = clone("/tmp/.../v-tc02-trim.xlsx")
ws = wb["组装加工费"]
ws["C2"] = " 焊接 "     # 原 '焊接' → 加全角/半角空格
ws["C3"] = " 铆接　"    # 半角+全角空格混合
wb.save(...)

# ---- V-TC03：按编号匹配 ----
wb = clone("/tmp/.../v-tc03-byno.xlsx")
ws = wb["组装加工费"]
ws["C2"] = "Z100"
ws["C3"] = "Z101"
wb.save(...)

# ---- V-TC04：两段匹配顺序（依赖 FX-2 临时数据 BEND/Z100 已建好）----
wb = clone("/tmp/.../v-tc04-order.xlsx")
ws = wb["组装加工费"]
ws["C2"] = "Z100"        # 应命中 byNo→(Z100,焊接)，不是 byName→(BEND,"Z100")
ws["C3"] = "铆接"
wb.save(...)

# ---- V-TC05：单料号单工序未登记 ----
wb = clone("/tmp/.../v-tc05-unregistered.xlsx")
ws = wb["组装加工费"]
ws["C2"] = "点胶"        # 未登记
wb.save(...)

# ---- V-TC06：单料号 3 道工序，2 道未登记（错误聚合）----
wb = clone("/tmp/.../v-tc06-aggregate.xlsx")
ws = wb["组装加工费"]
ws["C2"] = "焊接"         # 成功
ws["C3"] = "点胶"         # 失败1
ws.append(["S-3120014539", 3, "抛光", 5, "RMB", "PCS", None])  # 失败2，同料号第3行
wb.save(...)

# ---- V-TC07：双料号，一个全对、一个部分错 ----
wb = clone("/tmp/.../v-tc07-two-materials.xlsx")
ws = wb["组装加工费"]
# 保留原 S-3120014539 两行不变（焊接/铆接，全部可解析）
ws.append(["S-TEST-0727-B", 1, "焊接", 0.10, "RMB", "PCS", None])   # 新料号，成功
ws.append(["S-TEST-0727-B", 2, "点胶", 3,   "RMB", "PCS", None])   # 新料号，失败 → 该料号整组失败
wb.save(...)

# ---- V-TC08：组装工序列留空 ----
wb = clone("/tmp/.../v-tc08-blank-process.xlsx")
ws = wb["组装加工费"]
ws["C2"] = None
wb.save(...)

# ---- V-TC09：销售料号列留空 ----
wb = clone("/tmp/.../v-tc09-blank-material.xlsx")
ws = wb["组装加工费"]
ws["A2"] = None
wb.save(...)

# ---- V-TC10：全半角不归一化 ----
wb = clone("/tmp/.../v-tc10-fullwidth.xlsx")
ws = wb["组装加工费"]
ws["C2"] = "Ｚ100"        # 全角 Z + 半角 100，process_master 里没有这个串
wb.save(...)

# ---- V-TC11/TC12：同名多条，Excel 本身不用改，仍填"焊接"（用基线或 V-TC01 即可）----

# ---- V-TC14/15/16/17：组装加工费年降 sheet 现状 0 行，需要补数据 ----
wb = clone("/tmp/.../v-tc14-q15-byname.xlsx")
ws = wb["组装加工费年降"]
ws.append(["S-3120014539", 1, "焊接", 1, 5.0, None, "RMB", "PCS", 1])
wb.save(...)

wb = clone("/tmp/.../v-tc15-q15-blank.xlsx")
ws = wb["组装加工费年降"]
ws.append(["S-3120014539", 1, None, 1, 5.0, None, "RMB", "PCS", 1])   # 组装工序列留空
wb.save(...)

wb = clone("/tmp/.../v-tc16-q15-unregistered.xlsx")
ws = wb["组装加工费年降"]
ws.append(["S-3120014539", 1, "点胶", 1, 5.0, None, "RMB", "PCS", 1])
wb.save(...)

wb = clone("/tmp/.../v-tc17-cross-sheet.xlsx")
ws14 = wb["组装加工费"]; ws14["C2"] = "焊接"          # 沿用基线
ws15 = wb["组装加工费年降"]
ws15.append(["S-3120014539", 1, "焊接", 1, 5.0, None, "RMB", "PCS", 1])  # 同料号+同原始值"焊接"
wb.save(...)
```

> 每个变体文件名即用例编号，产出后放 scratchpad，执行阶段直接引用，**不要手工改动同一个文件复用于多个用例**（会导致证据链混乱、无法回放）。

### 1.4 FX-4：基线快照 SQL（AC-5 用，导入任何变体前必跑一次并留存输出）

```sql
SELECT 'capacity' t, count(*) FROM capacity WHERE resource_group_no='QUOTE_ASSEMBLY'
UNION ALL SELECT 'capacity_all', count(*) FROM capacity
UNION ALL SELECT 'material_bom_item', count(*) FROM material_bom_item
UNION ALL SELECT 'unit_price', count(*) FROM unit_price
UNION ALL SELECT 'unit_price_component_reduction', count(*) FROM unit_price WHERE price_type='COMPONENT_REDUCTION'
UNION ALL SELECT 'material_master', count(*) FROM material_master
UNION ALL SELECT 'element_bom_item', count(*) FROM element_bom_item
UNION ALL SELECT 'import_record', count(*) FROM import_record;
```

### 1.5 FX-5：双 current 起点确认

```sql
SELECT count(*) FROM unit_price WHERE price_type='COMPONENT_REDUCTION';
-- 期望 0（技术总监 2026-07-27 实测值）。非 0 先停下报告技术总监，不要继续。
```

### 1.6 客户 UUID 查询（导入接口要 UUID 不是 code）

```sql
SELECT id, code, name FROM customer WHERE code = 'CUST-1269';
```
记为 `<CUSTOMER_ID>`，后续所有 `POST /api/cpq/basic-data-import/v6/quote` 调用带此值。

### 1.7 FX-6：日志取证专用实例（AC-4 WARN / AC-11 elapsed 走这个，不走共享 8081）

技术总监 2026-07-27 实测：`application.properties` **没有任何 `quarkus.log.*` 配置**，共享 8081 dev server 是 7/12 启动的，stdout 无处可取——AC-4 的 `WARN` 断言、AC-11 的 `elapsed` 采集，在 8081 上**都无法取证**。

**做法**：临时起一个专用端口实例、把日志重定向到文件，用完立即 kill；不占用/不影响共享 8081（并发会话已有 8099 先例，CLAUDE.md "不要在 worktree 另起 dev server" 的本意是别重复起造成浪费/冲突，不是禁止一切取证手段）。

**改动后（feature 分支）实例** —— 在 worktree 里起，端口 `8097`，供 TC-11 / TC-12 / TC-24 / TC-25 用：
```bash
cd /home/joii/project/cpq/.claude/worktrees/repair-0727-process-no/cpq-backend
# 起之前先确认没有编译中的半成品(git status 是否有正在改动但未保存的文件)，避免测到中间态
nohup ./mvnw quarkus:dev -Dquarkus.http.port=8097 -Ddebug=false -Dquarkus.console.enabled=false > /tmp/q8097.log 2>&1 &
# 等 6-7s 启动完成后再发请求
# 用完立即：pkill -f 'quarkus.http.port=8097'
```
本实例上导入接口相应改为 `http://localhost:8097/api/cpq/basic-data-import/v6/quote`，工序主数据接口改为 `http://localhost:8097/api/cpq/v6/process-master/...`，其余步骤（客户 UUID、Excel 变体、断言 SQL）不变——**SQL 断言仍查同一个共享 `cpq_db_0724`**，只有 HTTP 请求换端口。

**改动前（master 基线）实例**（仅 TC-24 对比测需要）—— 在主工作区起，端口 `8098`：
```bash
cd /home/joii/project/cpq/cpq-backend   # 主工作区；确认此刻在 master 分支
git status cpq-backend/                  # 确认 cpq-backend/ 无未提交改动，避免测到脏状态
nohup ./mvnw quarkus:dev -Dquarkus.http.port=8098 -Ddebug=false -Dquarkus.console.enabled=false > /tmp/q8098-before.log 2>&1 &
# 用完立即：pkill -f 'quarkus.http.port=8098'
```

**纪律**：
- 两个专用实例都是**临时**的，测完立即 kill，不留作后台常驻。
- 不要往共享 8081、或任何其他会话正在用的实例发起这些取证请求。
- 8097/8098 与共享 8081 连的是**同一个远程 PostgreSQL**（`cpq_db_0724`），断言 SQL 结果不受起了哪个端口影响；起多实例只是为了能拿到各自独立的 stdout 日志文件。

---

## 2. 用例总览

| 编号 | 名称 | 对应 AC | 优先级 |
|---|---|---|---|
| TC-01 | 正向·按名称精确匹配（黄金路径） | AC-1 | P0 |
| TC-02 | 边界·名称含前后空格 trim | AC-1 / R3 | P1 |
| TC-03 | 正向·按编号精确匹配 | AC-2 | P0 |
| TC-04 | 边界·两段匹配顺序（编号优先于名称） | AC-2 / 规则一#1#2 | P0 |
| TC-05 | 反向·单料号单工序未登记 → 整单失败 | AC-3 | P0 |
| TC-06 | 反向·同料号多工序部分未登记 → 错误按料号聚合 | AC-3 / 规则三 | P0 |
| TC-07 | 反向·双料号一好一坏 → 整单失败且"好"的料号零写入 | AC-3 / 规则三 | P0 |
| TC-08 | 边界·组装工序列留空（Q14，必填） | AC-3 变体 | P1 |
| TC-09 | 边界·销售料号列留空（Q14，必填） | AC-3 变体 | P1 |
| TC-10 | 边界·全半角差异不归一化 | R3 | P1 |
| TC-11 | 同名两条 → 取升序第一条 + 日志 WARN | AC-4 | P0 |
| TC-12 | 同名三条 → 仍取升序第一条 + 日志列全部候选 | AC-4 扩展 | P1 |
| TC-13 | 整单原子性 | AC-5 | P0 |
| TC-14 | Q15 正向·按名称/按编号落真编号 | AC-6 | P0 |
| TC-15 | Q15 边界·组装工序列留空 | AC-6 边界 | P1 |
| TC-16 | Q15 反向·未登记 → 整单失败 | AC-6 | P0 |
| TC-17 | 跨 sheet key 隔离（Q14/Q15 同料号同原始值不串键） | 规则二 T2.1 契约 | P1 |
| TC-18 | Q15 双 current 锁定（连续两次导入） | AC-6b | P0 |
| TC-19 | 渲染·组装页签显示名称 + 自制加工费回归 | AC-7 | P0 |
| TC-20 | 版本行为三态 | AC-8 | P0 |
| TC-21 | 单元测试 + 集成回归套件全绿 | AC-9 | P0 |
| TC-22 | 核价侧 P08 不受污染 | AC-9 | P0 |
| TC-23 | 文档纠正核对 | AC-10 | P2 |
| TC-24 | 端到端耗时增幅 < 5% | AC-11 | P1 |
| TC-25 | 索引只建一次（无逐行查库） | AC-11 | P1 |

---

## 3. 详细用例

### TC-01 · AC-1 · 正向·按名称精确匹配（黄金路径）

- **前置**：FX-1 已完成（`Z100=焊接`、`Z101=铆接`）；FX-4 基线已记录
- **步骤**：
  1. 用基线文件 `报价系统模板0723.xlsx` 原样导入（`POST /quote`，`customerId=<CUSTOMER_ID>`）
  2. 轮询 `GET /{recordId}` 直至 `status != PROCESSING`
- **断言**：
  ```sql
  SELECT process_no, process_name FROM capacity
   WHERE resource_group_no='QUOTE_ASSEMBLY' AND material_no='S-3120014539' AND is_current
   ORDER BY process_no;
  -- 期望：Z100|焊接、Z101|铆接
  ```
  导入报告：`status=SUCCESS`，「组装加工费」sheet `failedRows=0`
- **反例对照**（改动前的现状，A/B 用）：`焊接|(null)`、`铆接|(null)`

### TC-02 · AC-1 边界 · 名称含前后空格 trim（R3）

- **前置**：同 TC-01
- **步骤**：导入 `v-tc02-trim.xlsx`（`组装工序` 列填 `" 焊接 "` / `" 铆接　"`，半角+全角空格混合）
- **断言**：结果与 TC-01 完全一致（`Z100|焊接`、`Z101|铆接`），`status=SUCCESS`
- **意义**：验证「匹配前统一 trim」（backtask T1.5 `strip()`）覆盖全角空格 `　`（Java `String.strip()` 对 Unicode 空白更宽松于 `trim()`，需确认用的是 `strip()` 而非 `trim()`——若实现用了 `trim()` 则全角空格用例会失败，此时判定为**代码未遵守 backtask T1.5**，报缺陷而非当作用例设计错误）

### TC-03 · AC-2 · 正向·按编号精确匹配

- **前置**：同 TC-01
- **步骤**：导入 `v-tc03-byno.xlsx`（`组装工序` 列改填 `Z100`/`Z101`）
- **断言**：与 TC-01 逐字节一致（`Z100|焊接`、`Z101|铆接`）—— **名称取自主数据规范名，不是 Excel 原文**（Excel 本身也没填名称，此断言的关键是"两段匹配第一段直接命中，不会因为找不到 byName 而报错"）

### TC-04 · AC-2 边界 · 两段匹配顺序（编号优先于名称）

- **前置**：FX-2 临时建 `process_no=BEND, process_name=Z100`（**注意**：此时 `process_name` 字段的值就是字符串 `"Z100"`，用于制造"一个字符串既是某条的编号又是另一条的名称"的撞库场景）
- **步骤**：导入 `v-tc04-order.xlsx`（`组装工序` 列填字面量 `Z100`）
- **断言**：
  ```sql
  SELECT process_no, process_name FROM capacity
   WHERE resource_group_no='QUOTE_ASSEMBLY' AND material_no='S-3120014539' AND is_current
   ORDER BY seq_no;
  -- 期望第 1 行 process_no='Z100', process_name='焊接'（命中规则1 byNo）
  -- 而不是 process_no='BEND', process_name='Z100'（规则2 byName 误命中）
  ```
- **清理**：删除临时 `BEND` 记录，`SELECT count(*) FROM process_master WHERE process_no='BEND';` 复核为 0
- **意义**：`test.md`/需求文档均未显式设计这条，但 backtask T1「两段匹配顺序不能颠倒」是核心规则，必须有一条用例专门证伪"顺序反了会怎样"，否则规则一#1#2 只停留在文档层面无回归保护

### TC-05 · AC-3 · 反向·单料号单工序未登记 → 整单失败

- **前置**：FX-4 基线已记录
- **步骤**：导入 `v-tc05-unregistered.xlsx`（`组装工序` 第 1 行改 `点胶`，第 2 行仍 `铆接`）
- **断言**：
  - 导入记录 `status=FAILED`
  - `failedRows > 0`；错误文案**同时**含：料号 `S-3120014539`、工序名 `点胶`、固定文案「请先在 主数据维护 → 工序 中录入或导入」
  - ```sql
    SELECT count(*) FROM capacity WHERE material_no='S-3120014539' AND resource_group_no='QUOTE_ASSEMBLY';
    -- 期望：= FX-4 基线该料号的计数（技术总监 2026-07-27 实测 20，非 0——S-3120014539 不是全新料号，
    -- 它带着改动前的 20 行脏数据历史，版本区间 2000~2009，正是本次要纠偏的对象。20 是"改动前的
    -- 存量脏数据"，不是本次导入写入的；断言的是"导入前后这个数字不变"，不是"这个数字是 0"）
    ```
- **禁止**：不要把这条断成 bug —— 这是设计选择（fail-fast），见 `test.md §0.2`

### TC-06 · AC-3 / 规则三 · 反向·同料号 3 道工序 2 道未登记 → 错误按料号聚合

- **前置**：FX-2 临时建（可选，此例不需要额外工序主数据，只需 2 道工序未登记）
- **步骤**：导入 `v-tc06-aggregate.xlsx`（`S-3120014539` 下 3 行：焊接✅ / 点胶❌ / 抛光❌）
- **断言**：
  - `status=FAILED`
  - **只有 1 条** `recordError` 对应 `S-3120014539`（不是 2 条），文案里**同时列出**`点胶`与`抛光`（顿号或类似分隔）
  - `rowNo` 取该料号第一条失败行的行号（第 2 行，即 `点胶` 所在行）
  - `column="组装工序"`
  - **组级拒绝口径（技术总监 2026-07-27 裁决，backtask.md 已更新，确定断言、不再是"需确认"）**：
    - `errors` 数组长度 = **1**
    - `failedRows` = **3**（该料号 3 行全部计入失败，**含本来能成功的"焊接"那行**）
    - `successRows` = **0**
    - 判定原则：组级拒绝 = 整个料号作废；`totalRows(3) == successRows(0) + failedRows(3)` 恒自洽
- **意义**：这是规则三（N1）唯一的专项验证点，`test.md` 只在骨架里带过一句，必须坐实"聚合"的字面含义（1 条错误消息，不是 1 个料号 1 条但内部仍是 2 条 RowError；且"聚合"不只是消息合并，连计数口径也是整组一起算失败）

### TC-07 · AC-3 / 规则三 · 反向·双料号一好一坏 → 整单失败且"好"的料号零写入

- **前置**：FX-4 基线已记录；技术总监 2026-07-27 实测 `S-3120014539` 当前 **20** 行（带改动前脏数据历史，非 0）、`S-TEST-0727-B` 当前 **0** 行（合成料号，从未导入过）——**执行前用下面的 SQL 重跑一次核实，若与此不符以实测为准**
- **步骤**：导入 `v-tc07-two-materials.xlsx`（`S-3120014539` 焊接+铆接全部可解析；`S-TEST-0727-B` 焊接✅ + 点胶❌）
- **断言（与基线比对，不是绝对值 0）**：
  - `status=FAILED`
  - ```sql
    SELECT material_no, count(*) FROM capacity
     WHERE resource_group_no='QUOTE_ASSEMBLY' AND material_no IN ('S-3120014539','S-TEST-0727-B')
     GROUP BY 1;
    -- 期望：仅 S-3120014539 | 20（与基线完全一致，一行不多一行不少）；
    -- S-TEST-0727-B 不出现在结果里（该合成料号从未成功导入过，不该凭空冒出一行）
    ```
  - 更强的一条断言（不受"到底原本有几行"这种基线漂移影响，直接锁版本号）：
    ```sql
    SELECT max(calc_version) FROM capacity WHERE material_no='S-3120014539' AND resource_group_no='QUOTE_ASSEMBLY';
    -- 期望：导入前后完全相同（失败导入不产生新版本——連 flip 都不该发生，因为 Phase 1 已经零写库拦截）
    ```
- **意义**：这是**整单原子性**在"料号"维度的加强版——证明"部分料号全对"不会导致该料号单独落库。`test.md` AC-5 只验证"其他 sheet 零变动"，没有验证"同 sheet 内好料号也零变动"，这条补齐

### TC-08 · AC-3 变体 · 边界·组装工序列留空（Q14 必填）

- **步骤**：导入 `v-tc08-blank-process.xlsx`（第 1 行 `组装工序` 置空，`销售料号` 保留）
- **断言（确定断言，backtask.md 已裁决，沿用既有文案原样不改写）**：`status=FAILED`；`recordError` 的 `column="宏丰料号/工序编号"`、`msg="必填项为空"`（照抄 `Q14:54-57` 既有必填校验文案，只是执行时机从 Phase2 提前到 Phase1，文案本身不变）
- **区分点**：这条测的是"根本没填"，跟 TC-05"填了但查不到"是两种不同失败原因，文案必须明显可区分——`msg="必填项为空"` vs TC-05 的"工序『点胶』未在工序主数据中登记，请先在 主数据维护 → 工序 中录入或导入"。执行阶段贴两条原文对照，确认没有被误合并成一种文案

### TC-09 · AC-3 变体 · 边界·销售料号列留空（Q14 必填）

- **步骤**：导入 `v-tc09-blank-material.xlsx`（第 1 行 `销售料号` 置空）
- **断言**：`status=FAILED`，同 TC-08 必填校验路径触发（回归保护：确认 Phase1 化没有漏掉这条既有校验）

### TC-10 · R3 边界 · 全角/半角差异不归一化

- **步骤**：导入 `v-tc10-fullwidth.xlsx`（`组装工序` 填 `Ｚ100`，全角 Z）
- **断言**：`status=FAILED`（**不是** SUCCESS）；`process_master` 里没有 `Ｚ100` 这个 key，两段匹配均不命中，走"未登记"分支
- **意义**：验证 R3 "全半角差异不做归一化"这条**设计决策**（不是缺陷）；若代码悄悄做了归一化导致这条意外命中 `Z100`，反而是需要报告的偏离（虽然结果"看起来更智能"，但违反需求文档明确约定，可能引入误匹配风险）

### TC-11 · AC-4 · 同名两条 → 取升序第一条 + 日志 WARN

- **前置**：FX-2 临时建 `Z205=焊接`（`Z100=焊接` 已在 FX-1）；**日志取证走 FX-6 的 8097 专用实例**（共享 8081 无日志可取）
- **步骤**：
  1. 按 FX-6 起 8097 实例，日志重定向到 `/tmp/q8097.log`
  2. 向 `http://localhost:8097/api/cpq/basic-data-import/v6/quote` 导入基线文件（或 `v-tc03-byno.xlsx` 的姊妹版，`组装工序` 填名称 `焊接`）；导入前记录一次 `/tmp/q8097.log` 的行数（`wc -l`），便于之后只看新增的日志段
- **断言**：
  - `status=SUCCESS`（**不是失败** —— 第四轮 N3 已把「拒绝」改为「取第一条」，这是本用例最容易被误判成 bug 的地方）
  - ```sql
    SELECT process_no FROM capacity
     WHERE resource_group_no='QUOTE_ASSEMBLY' AND material_no='S-3120014539' AND is_current AND seq_no=1;
    -- 期望：Z100（不是 Z205）
    ```
  - `grep WARN /tmp/q8097.log`（只看导入后新增的行）出现记录，内容含：被选中编号 `Z100` + 全部候选 `Z100`/`Z205` + 原始值 `焊接`（三要素缺一都算不完整实现）
- **清理**：删除临时 `Z205`，复核 `SELECT process_no FROM process_master WHERE process_name='焊接';` 只剩 `Z100`；8097 实例暂不 kill（TC-12/TC-24/TC-25 接续复用）

### TC-12 · AC-4 扩展 · 同名三条 → 仍取升序第一条 + 日志列全部候选

- **前置**：FX-2 临时建 `Z050=焊接` + `Z205=焊接`（`Z100=焊接` 已在 FX-1，此时"焊接"共 3 条）；复用 TC-11 已起的 8097 实例
- **步骤**：同 TC-11 方式向 `http://localhost:8097/...` 导入，导入前同样先记一次 `/tmp/q8097.log` 行数
- **断言**：
  - `status=SUCCESS`，`process_no=Z050`（三者升序最小，**不是** `Z100`，验证不是"固定取 Z100"这种巧合式实现）
  - `grep WARN /tmp/q8097.log`（只看本次新增行）候选列表含全部 3 个：`Z050`/`Z100`/`Z205`
- **清理**：删除 `Z050`、`Z205`，复核只剩 `Z100`
- **意义**：TC-11 用两条时若实现恰好是"取列表第一个"和"取升序最小"两种错误实现都可能凑巧通过（因为 DB 返回顺序也可能是插入序恰好=升序）；三条且新增的 `Z050` 排在原有 `Z100` **之前**，才能真正区分"升序排序"与"数据库返回顺序/插入顺序"这两种实现差异

### TC-13 · AC-5 · 整单原子性

- **前置**：复用 TC-06（或 TC-05/TC-07 任一失败场景）产生的导入
- **步骤**：在该失败导入**前后**分别跑 FX-4 基线 SQL
- **断言**：两次输出**逐行逐字节相同**（`capacity` / `capacity_all` / `material_bom_item` / `unit_price` / `unit_price_component_reduction` / `material_master` / `element_bom_item` 全部计数不变；`import_record` 计数会 +1 属预期，导入记录本身要落一条 FAILED 记录）
- **证据要求**：贴导入前、导入后两次完整 SQL 输出（不是"一致"两个字）

### TC-14 · AC-6 · Q15 正向·按名称/按编号落真编号

- **前置**：FX-1 已完成
- **步骤**：
  1. 导入 `v-tc14-q15-byname.xlsx`（组装加工费年降 sheet `组装工序` 填 `焊接`）
  2. 断言后清空 `unit_price WHERE price_type='COMPONENT_REDUCTION'`（**仅本用例内部清理，为了让 TC-16/TC-18 有干净起点**——若与执行顺序冲突，需要按用例编号顺序执行且每条明确记录清理动作）
  3. 另跑一次 `组装工序` 填 `Z100`（编号路径），断言与按名称结果一致
- **断言**：
  ```sql
  SELECT code, finished_material_no, operation_no FROM unit_price
   WHERE price_type='COMPONENT_REDUCTION' AND is_current;
  -- 期望 operation_no='Z100'（真编号，不是"焊接"）
  ```

### TC-15 · AC-6 边界 · 组装工序列留空

- **步骤**：导入 `v-tc15-q15-blank.xlsx`（`组装工序` 列留空，其余字段正常）
- **断言**：`status=SUCCESS`（不报错），
  ```sql
  SELECT operation_no FROM unit_price WHERE price_type='COMPONENT_REDUCTION' AND is_current;
  -- 期望：NULL
  ```
- **区分点**：这是 Q15 独有的宽松规则（backtask T2.4："组装工序"列允许为空，跳过解析、不记错），与 Q14（TC-08，同样列留空却报错）形成**故意的不对称**——测试时要明确知道这不是不一致的 bug，是两个 handler 既有语义差异的延续

### TC-16 · AC-6 · Q15 反向·未登记 → 整单失败

- **步骤**：导入 `v-tc16-q15-unregistered.xlsx`（`组装工序` 填 `点胶`）
- **断言**：`status=FAILED`，`unit_price` 该料号无新增行

### TC-17 · 规则二 T2.1 契约 · 跨 sheet key 隔离

- **前置**：FX-1 已完成
- **步骤**：导入 `v-tc17-cross-sheet.xlsx`（「组装加工费」`S-3120014539` 焊接 + 「组装加工费年降」同料号同原始值 `焊接`，二者在同一份工作簿、同一次导入内）
- **断言**：
  ```sql
  SELECT process_no, process_name FROM capacity
   WHERE material_no='S-3120014539' AND resource_group_no='QUOTE_ASSEMBLY' AND is_current AND seq_no=1;
  -- 期望 Z100|焊接

  SELECT operation_no FROM unit_price WHERE price_type='COMPONENT_REDUCTION' AND is_current;
  -- 期望 Z100（不是被 Q14 的结果覆盖成别的值，也不是读到 null/异常）
  ```
- **意义**：backtask T2.1 特别强调 `Outcome.assemblyProcessNo` 的 key **必须**以 sheetName 分区（否则两个 sheet 会用 `(销售料号, 组装工序原始值)` 同一个 key 空间互相覆盖）。这是`test.md` 完全没提到的隐藏耦合点，必须专门验证，否则该 bug 只会在"客户 Excel 里两个 sheet 恰好用了同一个料号+同一个工序名"时才会现形，属于生产环境才会暴露的静默错误

### TC-18 · AC-6b · Q15 双 current 锁定

- **前置**：FX-5 起点确认为 0；用干净的 `v-tc14-q15-byname.xlsx` 变体
- **步骤**：
  1. 导入一次，跑断言 SQL
  2. **不改任何数据**，原样再完整导入一次（同一份文件）
  3. 再跑一次断言 SQL
- **断言**（两次都要跑）：
  ```sql
  SELECT code, finished_material_no, operation_no, count(*)
    FROM unit_price
   WHERE price_type='COMPONENT_REDUCTION' AND is_current
   GROUP BY 1,2,3 HAVING count(*) > 1;
  -- 两次都必须是 0 行
  ```
- **补充验证**（比 test.md 骨架更严格）：第二次导入后，`is_current=false` 的历史行数应为 **1**（第一次导入产生的组被 flip，而不是留下一堆 `is_current=false` 的孤儿组）：
  ```sql
  SELECT is_current, count(*) FROM unit_price WHERE price_type='COMPONENT_REDUCTION' GROUP BY 1;
  -- 期望：is_current=true 1行, is_current=false 1行（= 总共2组，1新1老，不是2个都t或残留3个以上）
  ```

### TC-19 · AC-7 · 渲染·组装页签显示名称 + 自制加工费回归（人工确认）

- **前置**：`zz_view` 已改（T6），已 `touch` java 文件重启 Quarkus
- **步骤**：
  1. 用 TC-01 导入后走「创建报价单」（`POST /quote/create-quotation`），或直接打开当前库里已存在的报价单（`QT-20260726-0001~0008`，需含组装加工费页签——先查哪张单有此页签数据）
  2. 打开报价单编辑页/详情页，切到组装加工费页签
- **断言**：
  - 「工序」列渲染值为 `焊接`（名称），**不是** `Z100`（编号）
  - 自制加工费页签「工序」列渲染正常（沿用 `jg_view`，未被本次改动波及）——找一张含自制加工费数据的单核对
- **证据**：两张截图（组装页签 + 自制加工费页签）
- **注意**（BL-0078）：不要用失效的 Playwright E2E spec 红叉当证据，走真实 UI 或当前库存在的单

### TC-20 · AC-8 · 版本行为三态

拆成**两条互相独立的路径**（不共用料号、不共用断言 SQL——此前版本让路径 A 的断言混用 `S-3120014539` 又在收尾用 `S-TEST-0727-V8` 的 SQL，三处料号对不上会在执行时卡住，此处已拆开纠正）。

#### 路径 A · 升版观察（对应断言①）—— 必须用 `S-3120014539`

- **前置**：`S-3120014539` 是**改动前就存在的脏数据料号**（`resource_group_no='QUOTE_ASSEMBLY'`），技术总监 2026-07-27 实测基线：
  ```
  S-3120014539 / QUOTE_ASSEMBLY：共 20 行，版本区间 2000~2009，仅此 1 个料号
  当前 is_current = 2009（process_no='焊接' seq_no=1 / process_no='铆接' seq_no=2，process_name 全 NULL）
  ```
  **执行前先重跑一次基线 SQL 确认 2009 仍是当前值**（并发会话可能在此期间又导过数据，会漂移）：
  ```sql
  SELECT calc_version, process_no, process_name, is_current, seq_no FROM capacity
   WHERE material_no='S-3120014539' AND resource_group_no='QUOTE_ASSEMBLY'
   ORDER BY calc_version DESC, seq_no;
  ```
  若当前 `is_current` 版本不是 `2009`，以实测值为准，下面断言里的目标版本号改为"实测当前版本 + 1"，并在报告里注明发生了漂移。
- **步骤**：导入 TC-01 的基线文件（`报价系统模板0723.xlsx` 原样，「组装加工费」sheet `组装工序` 列仍是 Excel 原文"焊接"/"铆接"，不做任何修改）
- **断言**：
  ```sql
  SELECT calc_version, process_no, process_name, is_current, seq_no FROM capacity
   WHERE material_no='S-3120014539' AND resource_group_no='QUOTE_ASSEMBLY'
   ORDER BY calc_version DESC, seq_no;
  ```
  期望（基线未漂移时）：新版本 **`2010`** 为 `is_current=true`，`process_no='Z100'`(seq_no=1)/`process_no='Z101'`(seq_no=2)，`process_name='焊接'`/`'铆接'`；旧版本 **`2009`** 转 `is_current=false`。机理：`process_no` 从中文名变为真编号命中 `VERSION_TRIGGER`，触发升版——**这是预期，不是 bug**（需求文档规则五）

#### 路径 B · 稳定性 + 改名不升版（对应断言②③）—— 全程用同一个全新料号，与路径 A 完全隔离

- **前置**：全新料号 `S-TEST-0727-V8`（此前从未导入过，避免和路径 A / 其他用例的版本历史混在一起）；单独构造一份只含该料号 1 道工序的小变体 `v-tc20-v8.xlsx`（`组装工序` 直接填编号 `Z100`，跳过升版观察，直接进入"稳定态"起点）
- **步骤 ②（首次导入 + 再导一次，同一份文件、同一个料号）**：
  1. 导入 `v-tc20-v8.xlsx`，记录 `calc_version`（记为 `V0`；该料号首次导入，`V0` 具体数值以 `VersionedV6Writer.nextVersionOf` 实测为准，不强行断言为固定值）
  2. 原样重导同一份 `v-tc20-v8.xlsx`
- **断言 ②**：
  ```sql
  SELECT calc_version FROM capacity
   WHERE material_no='S-TEST-0727-V8' AND resource_group_no='QUOTE_ASSEMBLY' AND is_current;
  ```
  期望：`calc_version` 仍是 `V0`，**不再增长**（`VersionedV6Writer` 分支①"触发列+内容都同→复用旧版本号、不写"）
- **步骤 ③（仅改主数据名称，同一个料号）**：
  1. 用单条 `PUT /api/cpq/v6/process-master/{id}` 把 `Z100` 的 `process_name` 改成 `焊接工序`（业务键 `process_no` 不可改，名称可改）
  2. 原样重导同一份 `v-tc20-v8.xlsx`
- **断言 ③**：
  ```sql
  SELECT calc_version, process_no, process_name FROM capacity
   WHERE material_no='S-TEST-0727-V8' AND resource_group_no='QUOTE_ASSEMBLY' AND is_current;
  -- 期望 process_name='焊接工序'（原地更新为新名称），calc_version 与断言②的 V0 相同（不变）
  ```
  机理：`process_no`（VERSION_TRIGGER 列）未变，`process_name`（CONTENT 但非 TRIGGER 列）变了 → `VersionedV6Writer` 分支②"触发列同、内容不同→原地更新，复用旧版本号"
- **收尾**：把 `Z100` 名称改回 `焊接`（不要让 FX-1 常驻数据被步骤③污染，此收尾对路径 A/B 及后续所有依赖 `Z100=焊接` 的用例都有影响）

### TC-21 · AC-9 · 单元测试 + 集成回归套件全绿

- **步骤**：
  ```bash
  cd cpq-backend
  ./mvnw test -Dtest='ProcessNoResolverTest,QuoteImportValidatorTest,Q14*Test,Q15*Test'
  ./mvnw test -Dtest='*Quote*,*Capacity*,*Process*'
  ```
- **断言**：两条命令均全绿（`BUILD SUCCESS`，`Tests run: N, Failures: 0, Errors: 0`）
- **证据**：贴完整测试汇总输出（不是"全绿"两个字）

### TC-22 · AC-9 · 核价侧 P08 不受污染（回归）

- **步骤**：跑核价侧回归导入（若有现成核价 Excel 样例可复用；若没有，退化为纯查询回归，即只确认现存数据未被本次改动的代码路径污染）
  ```sql
  SELECT DISTINCT process_no FROM capacity WHERE system_type='PRICING' OR resource_group_no='PRICING_DEFAULT';
  -- 期望：仍是 Z008 / Z053 / Z490 等真编号（改动前后完全一致），未出现任何中文名称
  ```
- **对照**：与改动前该 SQL 的输出逐字比对（本次改动理论上完全不碰 P08，这条应为零差异）

### TC-23 · AC-10 · 文档纠正核对

- **步骤**：走查 `docs/table/报价系统Excel导入落库方案.md` §14 / §15
- **断言清单**：
  - §14 是否已说明「组装工序」列的**两段匹配规则**（先编号后名称）
  - §14 是否已说明反查来源为 `process_master`
  - §14 是否已说明 Phase 1 拦截语义（未登记→整单失败，不是留空/不阻断）
  - §14 是否已说明 `process_name` 新增落库
  - §15 是否同步补了 Q15 的 `operation_no` 解析规则
  - 原「组装工序 → process_no ✅（取工序编号对应值）」这句造成本次 bug 的描述是否已改正（不能只是加一段新内容、留着旧的误导性描述不删）

### TC-24 · AC-11 · 端到端耗时增幅 < 5%

- **前置**：需要"改动前"与"改动后"两个耗时基线，**必须同数据同环境对比**（黄金样例 = 基线 `报价系统模板0723.xlsx` 原样，客户不变）；**日志取证走 FX-6**（共享 8081 无日志可取，8097/8098 两个专用实例分别对应改动后/改动前）
- **步骤**：
  1. 按 FX-6 起 `8098`（主工作区、`master`，不含本次改动），向 `http://localhost:8098/api/cpq/basic-data-import/v6/quote` 导入黄金样例 3 次，`grep '\[v6import\] QUOTE TOTAL' /tmp/q8098-before.log` 取 3 条 `elapsed=...ms`，中位数记为 `T_before`；测完 `pkill -f 'quarkus.http.port=8098'`
  2. `8097`（worktree、本次改动分支）——若 TC-11/TC-12 已按 FX-6 起过且仍在跑则直接复用，否则按 FX-6 新起；向 `http://localhost:8097/api/cpq/basic-data-import/v6/quote` 同样导入 3 次（**注意**：黄金样例本身「组装工序」列还是中文名称"焊接"/"铆接"，第 1 次会触发一次性升版但不影响耗时数量级；建议用第 2、3 次的耗时，避免"首次触发升版分支"引入的额外一次 flip 写扭曲对比），`grep '\[v6import\] QUOTE TOTAL' /tmp/q8097.log` 取 `elapsed=...ms`，中位数记为 `T_after`
- **断言**：`(T_after - T_before) / T_before < 5%`
- **证据**：贴 6 条 `elapsed=` 原始日志行（before 3 条 + after 3 条），并注明各自来自 `/tmp/q8098-before.log` / `/tmp/q8097.log`

### TC-25 · AC-11 · 索引只建一次（无逐行查库）

- **步骤（二选一或都做）**：
  - **静态代码走查**：确认 `QuoteImportValidator.validate()` 内 `processNoResolver.buildIndex()` 只被调用 **1 次**（不是在 `validateAssemblyProcess`/`validateAssemblyAnnualDiscount` 内部各自调一次），且两个 validate 方法共用同一个 `Index` 实例（backtask T2.2 明确要求）
  - **运行时验证**：复用 FX-6 的 8097 实例，`pkill -f 'quarkus.http.port=8097'` 后带 SQL 日志参数重启：
    ```bash
    cd /home/joii/project/cpq/.claude/worktrees/repair-0727-process-no/cpq-backend
    nohup ./mvnw quarkus:dev -Dquarkus.http.port=8097 -Ddebug=false -Dquarkus.console.enabled=false \
      '-Dquarkus.log.category."org.hibernate.SQL".level=DEBUG' > /tmp/q8097-sql.log 2>&1 &
    ```
    （仅命令行系统属性覆盖，不改 `application.properties`，不留改动痕迹）导入一次黄金样例，`grep -ci 'from process_master' /tmp/q8097-sql.log` 统计出现次数，期望**恰好 1 次**，且**不随「组装加工费」sheet 行数增长而增长**（额外造一份 50 行同料号多工序的变体对比，1 次仍然是 1 次）；验证完 `pkill -f 'quarkus.http.port=8097'`（同 8097 用完统一 kill，含 TC-11/TC-12/TC-24 都测完之后）
- **断言**：查询次数 = 1（与行数无关）

---

## 4. 边界与异常用例补充说明（相对 `test.md` 骨架新增的点）

| 补充点 | 对应用例 | 为什么 test.md 骨架没覆盖到 |
|---|---|---|
| 两段匹配"顺序"本身的证伪（不只是分别验证两条路径能走通） | TC-04 | 骨架 AC-1/AC-2 各自独立验证，没有验证"两条路径同时可能命中时谁赢" |
| 同料号错误聚合的**消息条数**断言（1 条 vs N 条） | TC-06 | 骨架只写"按料号聚合"，没给出可复核的断言点（条数） |
| "好料号也零写入"（不只是"其他 sheet 零写入"） | TC-07 | 骨架 AC-5 只覆盖跨 sheet 原子性，没覆盖同 sheet 内跨料号原子性 |
| Q14 必填校验在 Phase1 化后是否还在（列留空 / 料号留空） | TC-08 / TC-09 | 骨架完全未提这两个既有校验的回归风险（backtask §1.2 特别提醒的"兜底计数循环"陷阱正是这类场景最容易漏 total/success 计数） |
| 全半角不归一化的正面验证 | TC-10 | 骨架把 R3 写进风险表，没转成用例 |
| 同名 3 条区分"取列表首个"与"取升序最小"两种实现 | TC-12 | 骨架 AC-4 只用 2 条，无法证伪"巧合通过" |
| Q14/Q15 跨 sheet key 隔离 | TC-17 | 骨架完全没提，backtask T2.1 是本次改动隐藏耦合最深的一点 |
| 双 current 场景下"老组是否被正确 flip"（不只是无重复） | TC-18 补充断言 | 骨架只断言 `count(*) > 1` 为 0，没断言历史行是否被正确清理（1 真 1 假） |
| AC-8"首次升版"断言在测试环境的可观察性问题 | TC-20 | 骨架假设"首次重导"天然可复现，但如果测试环境该料号从未导入过，压根没有"旧版本"可对比，必须显式说明用已有脏数据料号 |
| 性能测量的日志捕获可行性（无 `quarkus.log.file`） | TC-24 | 骨架只写"记录耗时"，没说怎么拿到日志 |

---

## 5. 回归测试清单（本次改动完成后，除 AC 对应用例外还须重跑）

| 场景 | 原因 | 命令/操作 |
|---|---|---|
| 自制加工费（Q10）导入 | `jg_view` 口径是本次对齐的参照物，改 `zz_view` 不该动到它 | 导入含「自制加工费」sheet 的 Excel，核对渲染不变 |
| 组成件其他费用（Q13） | 曾经历过类似"工序列"废弃，确认本次改动没有误伤 | `Q13ComponentOtherFeeHandler` 相关测试 |
| 核价侧 P08 | 见 TC-22 | 同 TC-22 |
| 报价单详情页 / 编辑页 双视图 | AP-50 反模式（渲染层 single-source）历史教训，凡是页签渲染改动都要两个视图都过一遍 | 编辑页 + 只读详情页各查看一次组装页签 |
| 已有报价单重新打开（非本次新导入的单） | 确认 `zz_view` 改动的 `LEFT JOIN process_master` 对 `process_no` 找不到主数据的老脏数据（20 行「焊接」文本）不会导致 SQL 报错或页面崩溃，而是走 `COALESCE` 兜底到 `c.process_no` 原样显示 | 打开一张改动前就存在、`process_no` 仍是中文名称的老报价单组装页签，确认能正常显示（不报错、显示原文本），而不是空白/异常 |
| `QuoteImportValidator` 既有校验（物料BOM/自制加工费等其余 sheet） | 本次改动在 `validate()` 中间插入两个新方法调用，需确认没有改变既有方法的调用顺序/参数导致其他 sheet 校验行为变化 | TC-21 的 `*Quote*` 全量套件已覆盖，重点看有没有新增失败用例 |

---

## 6. 执行前必读（继承 `test.md` §0 + §3，浓缩版，避免执行阶段翻两份文档）

1. **没有"部分成功"**：所有失败用例的断言都是"整单 FAILED + 全库零变动"，不是"坏行跳过"。
2. **未导工序主数据导致整单失败是预期**，不是 bug（TC-05 是唯一的黄金反例，不要重复报告同一现象）。
3. **FX-1 造的 `Z100`/`Z101` 测试数据完成后不删**；FX-2 的临时数据每条用完必须删（否则污染后续同名匹配类用例）。
4. **每次改后端代码必须 `touch` 一个 java 文件等 Quarkus 重启 5-7 秒**再测，历史上有测半成品的假阳性。
5. **A/B 归因**：任何疑似 bug，先在 `master`（不含本次改动）跑同型操作，两边都失败则是 pre-existing，登记 BACKLOG 不算本次回归。
6. **渲染验证不用失效 E2E spec 的红叉当证据**（BL-0078，`cpq_db_0724` 只有 8 张单，硬编码旧 quotationId 的 spec 进不去编辑页）。
7. **无证据的 PASS 视为未测**：SQL 输出、日志原文、截图，缺一不可。

---

## 7. 与 `test.md` 的关系

本文档不替代 `test.md`，是其执行细化版：
- `test.md` §0-§1 的纪律性说明本文档 §6 已浓缩引用，不重复展开。
- `test.md` §2 的 11 条 AC 骨架用例，本文档逐条落地为 TC-01~TC-25（含 test.md 未列出的边界/异常，见 §4 对照表）。
- `test.md` §3 的交付要求（报告格式、禁止事项）在执行阶段仍然适用，不在本文档重写。
