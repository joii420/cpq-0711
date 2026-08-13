# dev-docs 索引

> **给 Agent 的用法**：接到 bug / 新需求后，**先扫本文件**定位「这事属于哪个历史任务」，再去读那个目录的主文档。
> 不要全量读 dev-docs（36 目录 / 216 篇 / ~5 MB），也不要凭空重查已有结论。
>
> **三个入口，按你要干什么选**：
> - **刚进场、要先搞清项目现在什么情况** → **§0.0 当前项目态势**（活跃主线 / 未闭合缺陷 / 未合并分支 / BACKLOG 概况）
> - **排查 bug** → **§0 按症状反查**
> - **要改某个热点代码文件** → **§0.5 按代码文件反查**（这个文件历史上被哪些任务动过）
>
> **维护规则见 §9**（4 个强制触发点 + 状态列判据 + 收尾必须输出一行）。
> ⚠️ 状态列与 §0.0 均为**快照（2026-08-06）**，权威口径以 `docs/RECORD.md` + `BACKLOG.md` + `git log` 为准；各节末附刷新命令。
>
> **文档层级**：`docs/` 管业务血肉与反模式（`反模式.md` / `RECORD.md` / `方案制定前必读.md`）；
> `dev-docs/` 管**逐任务的澄清结论、根因取证、决策台账**；`.codegraph/` 管代码骨架。

---

## 0.0 当前项目态势（新 Agent 进场先读这一节）⏱️

> **快照时间：2026-08-09**（`master` = `b3cf4872`）。数据由本节末命令重跑，**过期请先刷新再采信**。

### 活跃主线（近 10 天有提交）
| 主线 | 状态 | 最近动作 |
|---|---|---|
| `task-0806-模板发布全量冻结` | ✅ **已交付合 master `8d04336a`** | 模板 PUBLISHED 后「内容层」仍是活的，且**被改写的是整条版本历史**（零组件跨 series 共享 → 「6 个模板」= 同系列 v1.0~v1.5，版本号形同虚设）。改法：权威源改关系表 `template_component_snapshot`，`publish()` 唯一写入 + `PublishedTemplateReader` 唯一读取 + miss 显式报错。**存量不迁移**，改人工「首次冻结」（三态：未冻结 409 / 正常 / 损坏 500）。⚠️ **AC-18 未执行**（E2E 夹具已不存在，`BL-0158`）。核心教训见 `AP-63`：「写了但不生效」一个任务里重复 6 次 |
| `task-0806-价格调整更新任务性能优化` | ✅ **已结案**（`880709fc` + T6 + `74577376`） | 58s(18项) → 冷 JVM 实测 29.24s。`BL-0140`；**分组键必须含取价基准日**（实测反例：按 job 整批会写坏 5/18 张单）；S0 守卫开关默认关。遗留 [[BL-0144]] T5 转二期 / [[BL-0145]] 价格分叉排查 |
| `task-0729-客户价格调整策略和价格版本` **方向 3** | 🟢 **刚交付**（验收报告 `c2562242`） | 总价单一来源改造；收尾三修 `3a69ca97`；遗留 BL-0130/0131/0132（竞态与并发覆盖） |
| `task-0805-组件导入导出功能升级` | ✅ 已合并 `13b62f42` | 绑定校验前移到导出/预览；BL-0120 → DONE |
| **BL-0127 族**（卡片快照回种用户编辑） | ✅ 已交付 `fe5abcdf` | 行内公式列不随编辑重算；收敛 `row_data→editRows` 为单一实现 |
| `repair-0808-前端页签算序假环致列小计归零` | ✅ **已交付合 master `e9c8d9ed`** | 前端建图停在页签粒度 → 与后端 repair-0803 的列粒度分叉 → 假环 → `catch` 静默退回声明序 → 11 个 FORMULA 列整齐归零。**表象是「行内值对、小计 ¥0」看着像显示 bug，实为整页签算错**（行内读后端快照 vs 小计读前端实时重算 = 双源）。遗留 [[BL-0159]]/[[BL-0160]] |
| **文档/部署基建同步**（2026-08-09） | ✅ 已交付 `b3cf4872` 等 4 笔 | ① `deploy/cpq-init-empty-navicat.sql` 同步至 Flyway **V384** + 新增 `deploy/0809-dbupdate.sql`（V378→V384 内网增量，含 V382 存量清空与 `freeze` 补冻指引）；② `rule-0724` 规则集同步 **8 处规则漂移**（H1 退役 / `DATA_SOURCE` 退役 / `tree_ref` / `formula_id` / 导入导出校验 …）；③ `客户组件模板/报价通用组件` 材料成本切 `f_material_element_price`（实证旧配置在静默取错价） |

### ⚠️ 未闭合 / 需要注意
| 项 | 性质 | 说明 |
|---|---|---|
| `repair-0807-更正任务价格丢失与版本错乱` | ✅ **已交付合 master**（`24b68b20`） | 价格更正任务跑完后，被调价元素的单价是"丢的"、行小计与单据总额偏低，靠"有人打开并保存"才自愈 —— DRAFT 单还有人开，`SUBMITTED` 单没人开就一直挂错金额。另有一类单（缺 `QUOTE_CARD` 冻结结构，现网 33 张活跃 DRAFT）被静默跳过却计入 SUCCESS。存量污染另立 `BL-0148` |
| `repair-0803-公式SUM内引用宿主页签字段` | 🔴 **静默少算，文档标「待确认」** | `SUM()` 内引用本页签 FORMULA 字段恒取 0，不报错只算少。文档点名现网 `COMP-0157「物料」` 正踩着。**未见对应 BACKLOG 条目** |
| `BL-0069` `mat_*` 废弃表断供故障族 | 🔴 **P0 · TODO 未排期** | 5 条实证失效路径，其中「漂移检测假阴性」破坏安全属性（13/13 报价单 `referenced_versions` 全 NULL → 恒 `hasDrift=false`，主动骗用户"数据未变"）。#5 已随 task-0722 闭合，其余未修 |
| `BL-0097` `$view` 报错毒化事务 | 🟡 **部分交付，根因未解**（2026-08-09 已核实，存疑解除） | ✅ 错误可见性已修（`2d12bde2`，前端不再只显示「待重算」）；❌ **事务毒化根因未解且 Savepoint 方案已证否**（同提交含 `SavepointIsolationFeasibilityTest`）。**别据「有修复提交」判本条已完成**，需另找方案（独立事务/预校验/连接隔离） |
| `task-0728-组件SQL视图的用户便捷化配置功能` | ⏸️ **暂停** | 七轮澄清 33 决策，尚有 3 项待你拍板，未拆任务文档 |
| `task-0723-废弃业务与表清洗` P2 | 🟡 分阶段未完 | `mat_*` 整族退役是多周工程，与 BL-0069 同源 |
| `task-0715-UI排版审查` | 📋 报告已出，整改未启动 | 180+ 条 / 11 个系统性主题（36 处 Modal 违规、1272 处硬编码 hex、Dashboard 是调试残留） |

### 未合并分支（5 条，详见 §8）
`feat/task-0712-selection-config`（剩 F6 E2E）· `feat/tesk-0709-pricing-import-versioning`（未进场）· `feat/pricing-sales-part-no`（架构冲突暂搁）· `feat/sel-plan3c-sales-landing`（hold 等 V311）· `feat/quote-material-no`（待确认）

### BACKLOG 概况
**2026-08-09 重数并做了一次归档**（此前有 14 条状态已是「已完成」却一直留在未完成区，使计数虚高、扫读误导，已按 BACKLOG 自身规则统一移入文末「已完成」区）。

- **未完成区 135 条**：`TODO` 101 · `待开发` 13 · `BLOCKED` 2（需人工确认意图，不可盲目开发）· `[-]` 2（已裁定不做，附重启条件）· 其他 3（含 [[BL-0097]] 部分交付）
- **未完成区优先级**：**P0 × 4** · P1 × 39 · P2 × 89
- **已完成区 42 条**
> ⚠️ 计数为**近似值** —— BACKLOG 的状态行有 16 种写法（`TODO` / `待开发` / `[x]` / `✅` / `已修` …），无法精确机器统计。**权威口径以 `BACKLOG.md` 原文为准**。

### 刷新本节
```bash
cd /home/joii/project/cpq
git log master --since="10 days ago" --date=short --pretty='%ad %h %s' | head -40   # 活跃主线
git branch --no-merged master                                                        # 未合并分支
/usr/bin/grep -aE '^\- \*\*状态\*\*' BACKLOG.md | sed -E 's/^- \*\*状态\*\*：\**//; s/[（(].*//' | sort | uniq -c | sort -rn
```

---

## 0. 按症状反查（bug triage 首选入口）

| 症状 | 大概率去这里 |
|---|---|
| 页签**全空** / 「暂无组件数据」 | `task-0725-修复-无法正常显示报价单的数据问题`（3 独立根因：pending 改写失效 / SQL 注释吞参数 / 树配置缺失） |
| 卡片显示**「该料号卡片数据待重算」** | `repair-0803-公式计算BUG修复`（`$view` 报错毒化 PG 事务 → 整卡片算不出） |
| 公式列**全空** / 公式不随编辑重算 | `repair-0803-BL0098-公式绑定改绑ID/` 下两个 repair-0805 子目录 |
| 公式 `SUM()` 里引用本页签 FORMULA 字段**恒取 0** | `repair-0803-公式SUM内引用宿主页签字段` |
| **行内公式值正常、页签小计列却是 `¥ 0`** / 产品小计比后端存的小一大截 | `repair-0803-公式计算BUG修复/repair-0808-前端页签算序假环致列小计归零`（前端页签算序假环 → cross_tab 源页签还没算就取 → 整列归零；`BL-0101`。**行读后端快照、小计读前端实时重算**，所以看着像"只有小计错"） |
| 精度不对 / 卡片小计与列表总计不一致 | `task-0801-公式计算精度优化`（统一 6 位，见记忆 `cpq-decimal-display-policy`） |
| 编辑单元格失焦后 **⚠「前后端算值不一致」**（两个数四舍五入到 9 位其实一模一样） / **提交被 409 `RECONCILE_PENDING` 卡死且怎么改都消解不掉** | `task-0801-公式计算精度优化/repair-0812-对账阈值与结果尺度不对称致误报`（前端 12 位 vs 后端 9 位，容差 `1e-12` 比结构性差距小 500 倍 → 结构性误报；2026-08-11 `fd83cac1` 引入；**临时规避 = 重启后端且不再编辑**） |
| **删除行删错行** / 删不掉 / 删完数据乱 | `task-删除行删错架构重构` → `task-0721-.../repair-0727-报价单报价侧删除行BUG`（4 套行身份） |
| 复制报价单后数据丢失 | `repair-0729-copy报价单数据丢失的问题` |
| 核价通过后基础数据**被抹平** | `repair-0727-回填语义与预览重做`（整组权威 → 改 patch 语义，AP-60） |
| BOM 树子件的材质/外购件**不进对应页签** | `task-0726-报价侧BOM闭包渲染` |
| 导入撞 `uq_material_bom_item` / 版本失步 | `task-0709-导入.../update-0723` + 记忆 `cpq-bom-master-child-version-desync` |
| 料号**跨客户串号** | `task-0708-导入.../repair-2`（RECIPE 模型；`task-0717-报价占号表只存销售料号` 是被推翻的竞争方案） |
| 编辑页产品分类丢失 / 红字「请选择产品分类」 | `task-0729-报价单产品分类持久化` |
| 元素单价取不到 / 价格策略不生效 | `task-0722-元素价格策略` → `task-0729-客户价格调整策略和价格版本` |
| 价格调整**更正任务跑完后**元素单价变空 / 产品小计变小 / 价格与 🔒 版本徽标对不上 / 再刷新一次才对 | `task-0729-.../repair-0807-更正任务价格丢失与版本错乱`（三根因：升版不刷版本标记 · S4 删价格键不回填 · 缺冻结结构被静默跳过。**"再刷新才对"不是缓存**，是 `saveDraft` 触发 `PriceReconciler` 归位治好的） |
| 更新任务报 32/32 全成功，但某些单一个字节没改 | 同上根因 ③ —— 缺 `quotation_view_structure(QUOTE_CARD)` → `SKIPPED` 被映射成 `SUCCESS`；存量盘点见 `BL-0148` |
| 报价单**改一格等半秒以上** / 想优化编辑落库耗时 | `task-0806-报价编辑链路优化与前后端对账`（**D17 + 附录 A.6**：编辑路径没接上仓里现成的批量写 → 8 页签 8 次 `REQUIRES_NEW`；生产态三档实测 775→541→481ms。**先查 `materializeLineRowData` 是几参重载**）|
| **想判断某项优化能省多少** | 同上 A.1 的更正段 —— **直接测「关掉它」的差值，别拿方法内打点占比外推**（356ms vs 实测 294ms 的由来）|
| **改了组件配置，已发布模板 / 在途报价单跟着变**（没发新版也变） | `task-0806-模板发布全量冻结`（`ComponentService.update:733` 自动改写已发布快照；另有 6 个字段从未进快照 + 渲染期 10 处直读活表） |
| 已发布模板的行序 / 行键 / 元素单价列**莫名变了** | 同上 —— `rowKeyFields` / `sortField` / `element*Field` 是「组件级角色字段」，为绕开 AP-44 挂在表列上，顺带绕开了冻结 |
| 价格调整审核通过后「更新任务」很慢（几十秒） | `task-0806-价格调整更新任务性能优化`（热点 = 核价树 `render()` 逐项调用，18 项 job 发 306 条 SQL 而非 17 条；**不是 N+1、也不能上线程池**，两条都已实测否定） |
| **同一批报价单里部分单的核价卡片值"串"成了别单的**（尤其元素单价） | 同上 §4 —— `:priceBaseDate` = 报价单建单日，任何跨报价单共享渲染结果的批处理都必须把它并进分组键，否则组长的基准日会被套给全批 |
| 组件模板怎么配（客户新模板） | `rule-0724-组件模板配置/AGENT-配置入口.md`（唯一权威入口） |

---

## 0.5 按代码文件反查（改动前必查：这个文件历史上被哪些任务动过）

> 用法：**动手改某个热点文件前**，先在这里查它的历史任务清单，去读那几个目录的决策/根因 ——
> 这些文件是全仓协议最密的地方，多数「改 A 坏 B」都源于不知道 A 背后压着哪几条历史契约。
> 数据来源：各任务 md 里对该文件的提及（**改动面的代理指标，非 ground truth**）；已剔除 `spec.ts`/`config.ts`/`types.ts`/`index.tsx` 等泛名。
> 精确影响面请配合 `codegraph_impact` / `codegraph_callers`。
> **计数口径提醒（2026-08-13 重跑，分母 = 41 个顶层任务目录）**：本表统计的是「任务 md 里提到该文件」，
> **包含「声明本任务不触碰该文件」这类反向提及** —— 例如 `task-0812-材质元素改下拉选择` 因在需求文档里声明
> 「不触发 E2E 强制清单」而被计入 `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `useDriverExpansions.ts` /
> `ComponentDriverService.java` 四行，它**实际一行都没改**。所以下表只用来「找该读哪几个历史任务」，不要当改动统计。

| 文件 | 命中任务数 | 历史任务 |
|---|---|---|
| **`QuotationStep2.tsx`** | **28 / 41** 🔥 | 0708导入 · 0712核价展示 · 0712选配 · 0713版本选择 · 0715UI · 0717比对 · 0721树 · 0721升版 · 0722元素价格 · 0723清洗 · 0725空白 · 0728版式 · 0729客户价格 · 0801精度 · 0801连表 · 0803父子公式 · **0806编辑链路对账** · **0806模板冻结** · **0806价格任务性能** · 删除行重构 · repair-0727回填 · repair-0729copy · repair-0803BL0098 · repair-0803SUM · **repair-0803公式BUG/repair-0808算序假环** · rule-0724 |
| `ReadonlyProductCard.tsx` | 19 | 0712核价展示 · 0713版本选择 · 0721树 · 0722元素价格 · 0725空白 · 0728版式 · 0729客户价格 · 0801精度 · 0801连表 · 0803父子公式 · **0806编辑链路对账** · **0806模板冻结** · **0806价格任务性能** · 删除行重构 · repair-0727回填 · repair-0803BL0098 · **repair-0808算序假环** |
| `CardSnapshotService.java` | 15 | 0712核价展示 · 0721树 · 0725空白 · 0728版式 · 0729客户价格 · 0801精度 · 0803父子公式 · **0806编辑链路对账** · **0806模板冻结** · **0806价格任务性能** · 删除行重构 · repair-0729copy · repair-0803BL0098 · repair-0803SUM · repair-0803公式BUG |
| `QuotationWizard.tsx` | 13 | 0712核价展示 · 0712选配 · 0722元素价格 · 0723清洗 · 0725空白 · 0729客户价格 · 0729分类 · 0801精度 · 0801连表 · **0806编辑链路对账** · **0812停用四Sheet** · repair-0729copy · rule-0724 |
| `useDriverExpansions.ts` | 14 | 0713版本选择 · 0722元素价格 · 0725空白 · 0728版式 · 0729客户价格 · 0801连表 · **0806模板冻结** · **0806价格任务性能** · 删除行重构 · repair-0727回填 · repair-0803BL0098 · rule-0724 |
| `FormulaCalculator.java` | 8 | 0721树 · 0729客户价格 · 0801精度 · 0803父子公式 · 0805导入导出 · 删除行重构 · repair-0803BL0098 · repair-0803SUM |
| `QuotationService.java` | 9 | 0708导入 · 0729客户价格 · 0729模板校验 · 0801精度 · 删除行重构 · repair-0729copy · repair-0803BL0098 · **repair-0808算序假环** |
| `ComponentService.java` | 10 | 0721树 · 0723清洗 · 0729客户价格 · 0803父子公式 · **0806编辑链路对账** · **0806模板冻结** · repair-0803BL0098 · repair-0803SUM · repair-0803公式BUG |
| `ComponentCell.tsx` | 7 | 0729客户价格 · 0801精度 · 0803父子公式 · 删除行重构 · repair-0803BL0098 · **repair-0808算序假环** · rule-0724 |
| `formulaEngine.ts` | 7 | 0729客户价格 · 0801精度 · 0803父子公式 · **0806编辑链路对账** · **0806模板冻结** · repair-0803SUM · rule-0724 |
| `SqlViewExecutor.java` | 4 | 0722元素价格 · 0725空白 · 0729客户价格 · repair-0727回填 |
| `PricingBasicDataImportDrawer.tsx` | 5 | 0708落库规则 · 0709版本升级 · 0728取数配置器 · **0812停用四Sheet** · **0812停用汇率表**（后两个是实改：抽屉文案 24→20→19 Sheet） |

⚠️ **`QuotationStep2.tsx` 是全仓最危险的文件**：25 个任务在它身上叠了协议。它同时受 AP-31 / AP-37 / AP-38 / AP-44 / AP-50 / AP-51 / AP-54 约束，且属 `CLAUDE.md`「修改后强制自检」第 5 条的 **E2E 强制清单**。改它之前请连读：`task-0721树` + `task-删除行删错架构重构` + `repair-0727-报价单报价侧删除行BUG`（行身份四套口径）。

**重跑本表的命令**（任务目录增删后刷新）：
```bash
cd dev-docs && for d in */; do d="${d%/}"
  find "$d" -name '*.md' -print0 | xargs -0 /usr/bin/grep -aohE '\b[A-Za-z][A-Za-z0-9_]{3,}\.(java|tsx|ts)\b' 2>/dev/null \
    | sort -u | while read -r f; do echo "$f|$d"; done
done | sort | cut -d'|' -f1 | uniq -c | sort -rn | awk '$1>=4'
```
> ⚠️ 必须用 `/usr/bin/grep -a`：本环境 `grep` 是 ugrep，中文多的大文件会被误判二进制**静默返空**。

---

## 1. 常驻规则文档（不是任务，是规范）

| 路径 | 内容 | 篇数 |
|---|---|---|
| `rule-0724-组件模板配置/` | **组件模板配置唯一权威文档集**。起点 `AGENT-配置入口.md`（Runbook）；深度规则 `1-总则`~`5-公式与Excel列`（含 §5.2 AP-44 字段类型联动协议）+ `报价侧.md` / `核价侧.md` / `附录-速查.md` | 10 |
| `任务平台规则.md` | 任务目录命名 / 文档产出规范 | 1 |
| `main-api.md` | 主 API 契约速查 | 1 |
| `task-demo/` | 需求立项说明**空白模板**，新任务从这里复制 | 1 |

---

## 2. 导入与基础数据落库（V6 表 / 版本升级 / 主数据维护）

| 目录 | 一句话 | 状态 | 主战场文件 |
|---|---|---|---|
| `task-0708-导入报价单和导入核价单的数据落库规则澄清/` | 销售料号成为主料号，17 个 `Q*Handler` 落库规则全面重定义；**本仓最大的返修母体（6 个子目录）** | ✅ 已交付 | `QuoteImportService.java` `QuoteImportValidator.java` `VersionedV6Writer.java` |
| ├ `repair-1/` | `production_no` 补落主表/主档/映射；P06 元素行按 calc_type 跳过登记防污染 | ✅ 合 master `02c97a6` | — |
| ├ `repair-2/` | **材质料号 RECIPE 模型**（不铸号、`component_no` 存原始码）→ 解决跨客户串号 | ✅ 合 master `58f98427` | `MaterialBomMergeHandler.java` |
| ├ `repair-0726-BOM中料件类投入料号没有落库/` | 料件类投入料号漏落库 | ✅ | — |
| ├ `repair-0727-工序编号与工序名称落库优化/` | 工序编号/名称落库口径（46KB 测试用例） | ✅ | — |
| ├ `repair-0802-电镀费用投入料号/` | 电镀费用 sheet 的投入料号处理 | ✅ | — |
| └ `repair-0804-年降三sheet的入库规则/` | 年降三 sheet 合并单表；「技术约束注释可能是业务契约」教训 | ✅ 合 master | `Q15AssemblyAnnualDiscountHandler.java` |
| `task-0709-导入报价数据和导入核价数据的版本升级与版本维护/` | 导入即版本化（甲系统自增 / 每 sheet 独立事务 / 顺序无关）+ 产出《版本升级规则文档》 | ⚠️ 主线分支 `feat/tesk-0709-pricing-import-versioning` **未合**；`update-0723` 已交付 | `VersionedV6Writer.java` `PricingBasicDataImportDrawer.tsx` |
| └ `update-0723/` | 报价导入模板适配：物料 BOM 单表三态 + `PartTypeInferenceService` + 两阶段导入 | ✅ 合 master `216aad3c` | — |
| `task-0708-材质库规范澄清/` | 材质库导入规范化 + 元素主表立项（1000 行 < 3s） | ✅ 合 master `1aa7b24` | `MaterialRecipeManagement.tsx` `MaterialImportDrawer.tsx` |
| `task-0709-元素主表管理/` | 元素主表 CRUD（B 模型：`element_no` 不可改、`element_code` 被引用即锁、只停用不删） | ✅ 合 master `c27f604`（BL-0040） | `ElementEditDrawer.tsx` `MasterDataHubPage.tsx` |
| `task-0712-主数据维护-核价基础数据维护/` | 主数据维护第 5 页签「料号核价」：16 tab 版本组 + 历史版本只读查看 | ✅ 已交付 | `PricingMaintenanceService.java` `V6ProcessCrudTab.tsx` |
| └ `childtask-1/` | 核价维护四码名称补齐（BL-0045，方案 B+(ii)） | ✅ 合 master `efa5224` | — |
| `task-0728-主数据维护版式优化/` | 6 个拼装页签的版式/搜索/分页拉齐统一 | ✅ 已澄清定稿→已交付 | `MasterDataHubPage.tsx` `ConfigTemplateManagement.tsx` |
| `task-0812-材质元素改下拉选择/` | 材质抽屉「元素 code + 元素名」两列合并为单列字典下拉（编号/符号/中文名三段可搜、跨行去重置灰、字典外脏值阻断保存）；后端零改动复用 `GET /elements` | ✅ **合 master `92d09aa4`**（32 用例 + 主线亲验 18 断言全 PASS；遗留 [[BL-0163]]） | `MaterialRecipeEditDrawer.tsx` |
| `task-0812-核价导入停用四个Sheet/` | 核价导入 24 → 20 Sheet（摘 P01/P02/P04/P05 调用点，Handler 代码与落库逻辑原样保留，恢复=加回两个 List）；元素/材料核价价格表、核价版本实测无读者，客户料号对应关系改由报价侧单入口 | ✅ **合 master `9477223b`**（48 用例；AC-1~12 全达成；全量 2473 项 A/B **用例名级** diff：只在 B 侧失败 = 0 条） | `PricingImportService.java` `PricingHandlerCatalog.java` `PricingBasicDataImportDrawer.tsx` |
| `task-0812-核价导入停用汇率管理表/` | ↑ 同型追加：再停用 P03 汇率管理表，20 → **19 Sheet**（父任务为上一行）。`exchange_rate_v6` 实测零消费方（代码/视图/组件字段全 0 命中）。**难点＝该表是父任务回归测试里唯一的正对照，必须换成 P24 单重而非删除**，否则用例退化为"什么都没写也全绿"的假测试 | 🚧 分支 `feat/task-0812-停用汇率管理表` 开发中（前端已提交 `09612950`，后端在途） | `PricingImportService.java` `PricingHandlerCatalog.java` `Task0812DisabledSheetsTest.java` |
| `task-0813-基础资料数值列扩至12位小数/` | 补齐 `task-0810` 漏掉的**另一半**：0810 的 `V385` 只扩了 21 个**计算金额列**，**基础资料侧一列没动**，导致 0810 需求文档 §119「基础取数值不按 9 位显示规则压缩」这条契约**落空**——基础值在落库时就被列 scale 截到 4~6 位。实证：`material_bom_item.net_weight` = `numeric(20,6)`，库内 68 行有值记录**小数位全部恰好 = 6**。范围 **86 列**（重量 3 / 含量·占比 24 / 单价·价格·汇率 31 / 用量·工时 28），目标 `precision = 原 p − 原 s + 12`。**难点＝ scale 常量有四份独立副本**（DB 列 / JPA `@Column` / handler 硬编码 `DecimalScale.at(x,6)` 约 28 处 / `PricingSheetRegistry.scale()` 16 处），漏一处**静默失效**；③④ 分管"导入"与"维护页保存"两条写路径。实施期又扫出 **BOM 四件套 + P12 完全零归一**（比 D-1 更彻底），一并补齐。**⚠️ 两条被实测推翻的判断**：①「视图依赖只有 1 处」是**只扫 dev 库**得出的，测试库还有 7 个 `task-0723` 时代 `_drop` 遗留视图（dev 库 0 个）→ 迁移在测试库直接失败（教训见 `AP-64` 取样代表性）；②「重导虚假升版是精度缺失导致」**归因错了** —— QUOTE 侧走 pending 草稿模式（`is_current=false` 206/208 行）无比对基线，升版是结构性必然、与归一无关；归一的价值由 PRICING 侧 13 位输入连导两次版本恒定独立证实。⚠️ **前端非零改动**：`EditableSheetTable.tsx` 用 `String(value)` 原样渲染定标字符串，改 `normalizeDecimalString`（**纯去尾零不截位**——截 9 位会让用户局部编辑时把 12 位精度改回 9 位） | ✅ **已交付合 master `b64742fc`**（`BL-0164`；AC-1~12 全达成；反射测试 2/2；遗留 [[BL-0165]] 22 个零归一 handler 系统审计） | `V386` `V387` · `PricingSheetRegistry.java` · `MaterialBomMergeHandler.java` · `P06/P07/Q04/P12` · `v6/entity/*.java` · `EditableSheetTable.tsx` |
| `task-0717-报价占号表只存销售料号/` | 🛑 **已作废**——与 repair-2 重叠，改走 repair-2 路径。保留作决策追溯 | ❌ 废弃 | — |

---

## 3. 报价单渲染 / 编辑 / 树（`QuotationStep2.tsx` 热区）

| 目录 | 一句话 | 状态 | 主战场文件 |
|---|---|---|---|
| `task-0806-报价编辑链路优化与前后端对账/` | 把后端从**显示权威降级为校验器**：DRAFT 行内走前端引擎（与列小计同源、零等待），后端异步照算做**对账**，不一致亮标记 + **禁提交**；其上叠异步/懒物化/缓存三级优化。⚠️ **原记「整行物化 356ms(45%)」已被 D17 生产态实测更正为 294ms(38%)** —— 方法内打点占比 ≠ 「关掉它能省多少」 | ⚠️ **阶段⓪①③a 已合并**（`2942a4d8` 白名单收窄 + 分流/对账/提交闸门；`e40ab0fb` ③a 批量写，8 次 REQUIRES_NEW → 1 次）；**阶段③ 范围经实测重裁（D17）**：③b 懒物化**裁定不做**→`BL-0156`；**阶段②④⑤ 未开工**（`BL-0137` P0，D1~D17） | `QuotationStep2.tsx` `ReadonlyProductCard.tsx` `CardSnapshotService.java`（`editCardValue`/`materializeWholeLineRowData`） |
| `task-0806-价格调整更新任务性能优化/` | 价格调整通过后「更新任务」提速：18 项 job 实测 58s（3.22s/项）→ 目标 22~25s。热点 = 核价树 `render()` 43.5% + S0 口径守卫 14.3%。**实测否定两条路**：不是 N+1（一次 render 恰好 17 条 SQL、零重复）、不能上线程池（2026-06-22 同款设计已 revert）。真正浪费 = `refreshCostingCardValuesForLine` 逐项调 `render(List.of(li))` → 18 项发 306 条 SQL 而非 17 条 | ✅ **已交付合 master**（`880709fc` + T6）。主线亲验：75 个 line item 逐字节相同、测试 61/61；实测 18 项 job 冷 JVM 29.24s（**非同 JVM 严格对照，不作确定倍数**）。遗留 [[BL-0144]] T5 转二期 / [[BL-0145]] 价格分叉 | `BomTreeRenderService.java` `CardSnapshotService.java`（`refreshCostingCardValuesForLine`）`PriceAdjustJobExecutionService.java` `MaterialVersionUpgradeService.java` `DriverBatchSafetyAuditor.java` `PriceBaseDateUtil.java` |
| `task-0806-模板发布全量冻结/` | 模板 PUBLISHED 后**内容层仍活穿透**（实际 18 处活表读，非立项时以为的 10 处）→ 关系表 `template_component_snapshot` + `PublishedTemplateReader` 收口 + miss 报错 + 存量不迁移改「首次冻结」 | ✅ 合 master `8d04336a` | `TemplateService.java` `ComponentService.java` `CardSnapshotService.java` `ConfigureSnapshotService.java` `ExcelViewService.java` `BomTreeRenderService.java` `QuotationTreeService.java` `CostingVersionService.java` |
| `task-0721-报价侧树状结构与页签类型属性/` | 报价卡片按 BOM 树渲染（复用核价 spine 引擎）+ 页签类型属性（BOM/材质元素/零件/组成件/外购件/主件） | ✅ 已交付 | `QuotationStep2.tsx` `QuotationTreeService.java` |
| └ `repair-0727-报价单报价侧删除行BUG/` | **一行有 4 套身份**（`__effKey`/fp 墓碑/rowKey/`__nodeId`），删除与校验都没用 `__nodeId` | ✅ 合 master `bf3822a3` | `DeletedRowKeys.java` `BomTreeDeleteConfirmDrawer.tsx` |
| `task-删除行架构重构/` | 架构师设计稿：driver 行两份平行存储 → 内容指纹身份 `uniqFp` 统一（Phase1 止血 / Phase2 根治） | 📐 设计定稿 | `QuotationStep2.tsx` `useCardSnapshots.ts` |
| `task-0721-报价升版逻辑/` | 导入不再全局升版，改为**核价通过时**把报价单当刻有效数据回填 + 升版落地 | ✅ 已交付 | `QuotePendingRewriter.java` |
| `repair-0727-回填语义与预览重做/` | 上条的返修：页签投影当整组权威 → **改 patch 语义**（AP-60）+ 核价通过预览重做 | ✅ 合 master `25687548` | `QuoteBackfillService.java` `CostingApprovePreviewDrawer.tsx` |
| `task-0725-修复-无法正常显示报价单的数据问题/` | **页签全空 P0**：3 根因（`quotationId` 未传播致 pending 改写恒关 / `rewriteNamedParams` 不屏蔽 SQL 注释 / 树配置表空） | ✅ 已交付 | `ComponentDriverService.java` `SqlViewExecutor.java` `BomTreeRenderService.java` |
| `task-0726-报价侧BOM闭包渲染/` | 子件的材质/外购件/零件要进各自平铺页签（配置解已落地，框架解待评估） | ✅ 配置解已验证 | — |
| `repair-0729-copy报价单数据丢失的问题/` | 详情页「复制」→ 新单 BOM 空、刷新后全空 | ✅ 已修复交付 | `QuotationService.java:1391` `CardSnapshotService.java` |
| `task-0712-报价单中的核价单数据展示问题修复/` | 报价单里核价侧数据展示不出来（服务端整单物化） | ✅ 合 master `5f0736e` | `QuotationWizard.tsx` `ReadonlyProductCard.tsx` |
| `task-0729-报价单产品分类持久化/` | 产品分类从来只是前端一次性参数，从没落库 → 编辑页必丢 | ✅ 已交付 | `V364__quotation_product_category_id` `QuotationCreateForm.tsx` |
| `task-0729-模板绑定状态校验/` | 服务层补模板绑定不变量（存在/类型/状态），堵住 DRAFT 模板被绑 + `ON DELETE SET NULL` 静默置空 | ✅ 已交付 | `QuotationService.java:239/272/343/1465` |

---

## 4. 公式引擎

| 目录 | 一句话 | 状态 | 主战场文件 |
|---|---|---|---|
| `task-0803-BOM页签增加父子取值公式/` | BOM 树页签新增 `PGET`（子取父）/ `CSUM/CAVG/CMAX/CMIN/CCOUNT`（父取子）；求值器从「逐行单遍」下沉到**单元格级拓扑排序**；非树页签四道闸拦截 | ✅ 已交付 | `FormulaCalculator.java` `FormulaBuilder.tsx` `TreeRefDrawer.tsx` |
| `task-0801-公式计算精度优化/` | 全链路统一 6 位小数（引擎 + 小计 + 总计 + 列表一致） | ✅ 已交付 | `PrecisionPolicy.java` `NumberFormatUtil.java` |
| ├ `repair-0809-4位截断残留两处/` | 上条清扫的**漏网点**（由 repair-0808 对拍暴露，`BL-0159`）：①`CardSnapshotService:4078` 给 `__amount_total__` 中间键做 `setScale(4)` —— 同语义的另两个登记点（`ConfigureSnapshotService:1477` / `ComponentDataEffectiveRows:216`）和前端都不截断；②`MaterialVersionUpgradeService:388` 写 `q.totalAmount` 用 `setScale(4)`，同字段另一写点 `QuotationService:894` 已是 `PrecisionPolicy.round()`（6 位）。**同一语义后端内部就三分之二实现是对的 → 漏改而非设计** | ⏸️ **被 task-0810 吸收，旧 6 位目标不再单独进场** | `CardSnapshotService.java:4078` `MaterialVersionUpgradeService.java:388` |
| ├ `task-0810-公式计算12位显示9位/` | 精度契约升级：后端 BigDecimal、前端 Decimal.js Decimal，精度链禁止 Double/JS number；公式节点/快照/持久化保留 12 位，最终显示最多 9 位；decimal string 无损传输；扩 21 个计算金额列；吸收 `BL-0159/0160` | ✅ **已交付 master `df592eab`**（2026-08-11；exact-5 5/5；任务关键 37/37；TC-077 环境 BLOCKED） | `PrecisionPolicy.java` `FormulaCalculator.java` `precision.ts` `formulaEngine.ts` |
| ├ `repair-0811-输入值原样与结果精度分层/` | **由子任务 `task-0810` 引入**（2026-08-13 按「两层上限」规则从 `task-0810/` 下平铺至此）。修正 task-0810 动态数值分类：INPUT_TEXT/INPUT_NUMBER 保留当前单元格原值；公式过程 12 位、结果 9 位；产品卡片小计与报价总额拆为独立精度变量，预留系统参数入口；闭合 `BL-0162` | ✅ **已交付，AC-1~AC-12 准入**（2026-08-11；真实 PUT/DB/GET；N=2/2N=4 元数据 SQL 均 1；Playwright 旧客户 fixture 环境阻塞） | `DecimalRequestValidator.java` `PrecisionPolicy.java` `losslessJson.ts` `precision.ts` |
| └ `repair-0812-对账阈值与结果尺度不对称致误报/` | **由子任务 `task-0810` 引入**（目录按「主任务下只挂一层子目录」规则平铺在 `task-0801/` 下，不嵌进 task-0810）。**回归**：前端拿 12 位工作值（`toCalculationString`）去比后端 9 位结果值（`roundFormulaResult`），容差却写死 `1e-12` —— 比两侧结构性差距上界 `5e-10` **小 500 倍** ⇒ 凡第 10~12 位非零的公式列**必然**报「前后端算值不一致」，且**永不消解**（改多少次还是 12 位 vs 9 位）→ 提交闸门 409 卡死，只能重启后端绕过。病根是 **FR-12 规格自相矛盾**（要求按 12 位判定，而后端从不产出 12 位 `formulaResults`）。**数据/显示/导出全对，纯判定层缺陷** | ✅ **已交付合 master**（2026-08-13）。修法＝`valuesReconcile` 比较前两侧按 `formatFormulaResult` 归一到 9 位，容差参数不动；**判定用归一值、展示与上报仍用原始 12 位**（真出分歧时靠第 10~12 位排查）。后端零改动、契约零变更、不改数。**唯一鉴别力证据＝单测级 A/B**（还原修复→TC-01 等 4 条 fail，用的是故障单真实值 `63.211125158028` vs `63.211125158`）。✅ **AC-1/AC-2 已由用户闸门 B 真机验收通过**（主线 harness 无效：未切到物料页签，首次假绿已被 A/B 自查推翻并删除脚本）；AC-5 未验证、**AC-3 阳性能力 BLOCKED**（`BL-0167` 种子缺失）。配套：FR-12 作废改写 + E2E TC-076 重写 + 新增 TC-076b。教训 → `AP-64`/`AP-65` | `QuotationStep2.tsx:2744`（`valuesReconcile`） `formulaEngine.ts:827`（容差） `FormulaCalculator.java:1113,1244` |
| `task-0801-页签连表公式配置优化/` | 连表公式抽屉改版（左右分栏 / 括号配对可视化 / 试算） | ✅ 已交付 | `TabJoinFormulaDrawer.tsx` `TabFieldMatrix.tsx` |
| `repair-0803-公式计算BUG修复/` | 「待重算」根因 = `$view` 报错毒化 PG 事务；顺带挖出 BL-0097/0098/0099 三条独立缺陷 | ✅ 已交付 | `CardSnapshotService.java:2245` |
| └ `repair-0808-前端页签算序假环致列小计归零/` | 上条**未做完的另一半**（`BL-0101`）：后端 repair-0803 已改列粒度建图，**前端仍是页签粒度** → `component_subtotal` 引用零依赖 INPUT 列（产品·税率）也建边 → 与真实的 `产品→物料` cross_tab 边撞成**假环** → `topoOrderComponents` 抛错 → `catch` **静默**退回声明序 → 物料先于其 cross_tab 源页签求值 → 11 个 FORMULA 列整齐归零。表象是「行内值对、小计 ¥0」（行读后端快照 / 小计读前端实时重算，双源） | ✅ **已交付合 master `e9c8d9ed`**（闭合 `BL-0101`；AC-8 E2E 因 `BL-0158` 夹具枯竭**阻塞不可执行**，已改用 `e2e/repair0808-verify.spec.ts` 真机验收 AC-1/AC-2） | `crossTabOrder.ts#buildComponentDeps` `QuotationStep2.tsx:1447-1466`（对拍基准 `CrossTabComponentOrder.java#buildComponentDeps`） |
| `repair-0803-BL0098-公式绑定改绑ID/` | 公式绑定从「按名字/按位置猜」改成**绑不可变 UUID**（5 条决策台账；位置回退代码永久保留给老冻结单） | ✅ 已交付 | `V375__bl0098_formula_stable_id` `FieldConfigTable.tsx` |
| ├ `repair-0805-渲染侧丢公式id致公式列全空/` | 渲染侧丢 formulaId → 公式列全空（含 34KB `backend-rowkey-contract.md`） | ✅ | — |
| └ `repair-0805-行内公式列不随编辑重算/` | 行内公式列编辑后不重算 | ✅ | — |
| `repair-0803-公式SUM内引用宿主页签字段/` | `SUM()` 内引用本页签 **FORMULA** 字段恒取 0（`b_field` 读 `currentRowRaw`，FORMULA 只回填 `fieldValues`）——不报错，只算少 | ⚠️ **待确认/未完** | `FormulaCalculator.java:195/884/1861` `formulaEngine.ts:415` |
| `task-0805-组件导入导出功能升级/` | 绑定完整性校验从「导入提交」前移到**导出 + 导入预览**；旧 bundle 兼容 + 往返一致 | ✅ 合 master `13b62f42` | `ComponentImportDrawer.tsx` `FormulaBindingConsolidateDrawer.tsx` |

---

## 5. 价格策略

| 目录 | 一句话 | 状态 | 主战场文件 |
|---|---|---|---|
| `task-0722-元素价格策略/` | 元素单价维护页 + 客户价格策略（`v_customer_element_price` 视图实时算，不建客户价格表） | ✅ 已交付 | `PriceDetailTab.tsx` `ElementManagement.tsx` |
| └ `update-0724-元素价格手工维护/` | 元素价格手工维护补齐 | ✅ | — |
| `task-0729-客户价格调整策略和价格版本/` | **本仓最大任务（16 篇 / 1 MB）**：客户价格调整策略 + 价格版本；§11.15 技术方案优先级最高（修正了 5 条裁决、证伪 4 条"已实证事实"）；方向 3 = 总价单一来源改造 | ✅ 方向 3 已交付（验收报告 `c2562242`） | `QuotationService.java` `ComponentCell.tsx` `PricingStrategy.tsx` |
| ├ `repair-0803-报价单删除阻塞外键/` | BL-0108 报价单删除被外键阻塞 | ✅ | — |
| └ `repair-0807-更正任务价格丢失与版本错乱/` | **更正任务跑完 = 数据是错的**：三根因 —— ①升版只改价不刷 `__priceVersion`（=BL-0124）②S4 删价格键不回填 → 元素单价空、小计塌，靠"打开保存"自愈 ③缺 `QUOTE_CARD` 冻结结构的单被静默跳过却报 SUCCESS（现网 33 张）。附修审核抽屉「调整后小计」恒 `—`（后端零赋值点） | ✅ **已交付合 master**（merge `24b68b20`） | `MaterialVersionUpgradeService.java` `PriceAdjustReviewService.java` `PriceAdjustJobExecutionService.java` |

---

## 6. 核价 / 比对 / 选配

| 目录 | 一句话 | 状态 | 主战场文件 |
|---|---|---|---|
| `task-0713- 核价管理-核价单的版本选择/` | 核价单切料号版本重渲染（报价侧 frozen 不变 / 核价侧可重算；每 sheet 独立版本 + override 表） | ✅ 合 master `12c6506` | `CostingVersionService.java` `VersionSelectDropdown.tsx` |
| └ `repair-071501/` | 版本切换 2 UI bug（版本列过宽 + 切后整组「—」） | ✅ 合 master `1167aba` | — |
| `task-0717-比对视图/` | 报价侧 ↔ 核价侧同料号逐页签比对：连线配置列 + 3 行块 + 双色阈值 + 按桶（SALES/FINANCE）存 | ✅ 合 master `152931c1`+`fd2bbfc1` | `ComparisonView.tsx` `LinkConfigDrawer.tsx` |
| `task-0712-选配模板和报价单选配功能/` | 选配模板管理 + 报价单「从已有产品添加」/「选配添加」+ 3D 模型带出（含 `prototypes/` 可交互 HTML） | ⚠️ 分支 `feat/task-0712-selection-config` **未合**，剩 F6 E2E | `ConfigureProductDrawer.tsx` `SelTemplateManagement.tsx` |
| └ `update-071501/` | 三模板统一产品分类轴 | ✅ 合 master `b53f348` | — |

---

## 7. 清理 / 工具 / UI

| 目录 | 一句话 | 状态 | 主战场文件 |
|---|---|---|---|
| `task-0723-废弃业务与表清洗/` | V44 `mat_*` 整族退役盘点：**废弃表 + 活功能引用 = bug**（P0 僵尸按钮 / P1 BL-0069 热路径读冻结表 / P2 整族退役） | 🟡 分阶段，P2 未完 | `ImportHistoryList.tsx` `BasicDataImportV5ToQuotation.tsx` |
| `task-0715-UI排版审查/` | 全站 UI 挑刺报告：180+ 条带行号条目 + 11 个系统性主题（36 处 Modal 违规 / 1272 处硬编码 hex / Dashboard 是调试残留） | 📋 报告，整改未完 | `MainLayout.tsx` `Dashboard.tsx` |
| `task-0728-组件SQL视图的用户便捷化配置功能/` | 取数配置器：让实施人员不写裸 SQL 也能配视图（七轮澄清 33 决策） | ⏸️ **暂停**，3 项待拍板 | `SqlViewConfigDrawer.tsx` |

---

## 8. 未合并分支 / 悬挂工作（`git branch --no-merged master`）

| 分支 | 对应任务 | 备注 |
|---|---|---|
| `feat/task-0712-selection-config` | task-0712 选配 | 20 commits，剩 F6 E2E + 合并前置对齐 |
| `feat/tesk-0709-pricing-import-versioning` | task-0709 版本升级主线 | 文档就绪，工程师未进场 |
| `feat/pricing-sales-part-no` | 核价侧销售料号 | 疑与 task-0708 架构冲突，用户暂搁 |
| `feat/sel-plan3c-sales-landing` | 选配 Plan3c | 后端 20 测试全绿，hold 等 V311 落 master |
| `feat/quote-material-no` | — | 待确认 |

> 另有 14 个存量 worktree（`git worktree list`），多数已合并未清理，收尾时按 `superpowers:finishing-a-development-branch` 清。

---

## 9. 维护规则（什么时候必须改本文件）

### 9.1 四个强制触发点

| # | 触发时机 | 必做动作 | 改本文件哪里 |
|---|---|---|---|
| **T1** | **新建任务目录**（含 `repair-*` / `update-*` / `childtask-*` **嵌套子目录**——历史上 18 个子目录正是最容易漏的） | 追加一行；嵌套子目录用 `├`/`└` 挂在父任务行下方 | §1–§7 对应章节 |
| **T2** | **任务状态变化**（见 9.2 判据） | 更新状态列 | §1–§7 状态列 + §0.0 态势 |
| **T3** | **分支合并 / 新建特性分支** | 增删条目 | §8 未合并分支 |
| **T4** | **发现新的「症状 → 任务」映射**（本次 bug 查了半天才定位到某历史任务 = §0 缺一行） | 追加一行症状 | §0 按症状反查 |

### 9.2 状态列判据（不要凭感觉填）

| 标记 | 判据（**必须可验证**） |
|---|---|
| ✅ | **代码已合入 `master`**。填写时附 commit hash 或 merge hash，如 `✅ 合 master bf3822a3`。评审通过但未合 = 不算 ✅ |
| ⚠️ | 有未闭合缺口：分支未合 / 子项未完 / 文档自标「待确认」。**必须一句话写清缺什么** |
| ⏸️ | 暂停，等外部输入（待用户拍板 / 等前置任务） |
| 📐📋 | 只有设计稿或报告，未进入实现 |
| ❌ | 作废。**必须写明被什么取代**（如 `task-0717-报价占号表` → 被 repair-2 取代） |

### 9.3 派生动作（顺手做，否则索引会烂）

- **T1 触发时**：顺手重跑 §0.5 的反查表命令（新任务会引入新的文件↔任务映射）
- **T2 触发时**：同步更新 §0.0「当前项目态势」的活跃主线 / 未闭合表
- **BACKLOG 登记**：新任务同时在 `BACKLOG.md` 登记 `BL-NNNN`（这是 `CLAUDE.md`「Backlog 自动管理规则」的要求，不是本文件的）
- **教训上提**：若沉淀出**跨任务可复用**的教训（不只是本任务的过程记录）→ 写进 `docs/反模式.md` 新增 `AP-NN`，本文件只留指针。**本文件是导航，不是知识库**

### 9.4 收尾必须输出（forcing function）

每次开发任务结束时，必须在回复里输出一行：

> `dev-docs 索引：新增 N 条 / 更新 M 条状态 / 无变化`

没有这一行 = 没检查过索引。规则同步钉在 `CLAUDE.md`「质量保证规范」第 4 条。
