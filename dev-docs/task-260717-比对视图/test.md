# 报价单比对视图 · 测试用例文档（test.md）

> 版本：v1（2026-07-18，cpq-tester 编写）
> 状态：**仅设计，未执行**（开发尚未完成，本文档用于开发完成后按图验收）
> 依据：`需求说明.md §11`（业务唯一标准）、`api.md`（接口契约）、`backtask.md`（AC-1~AC-6）、`fronttask.md`（AC-F1~AC-F9）、`prototype-比对视图.html`（UI 1:1 基准）
> 关联历史：`docs/RECORD.md` 2026-06-29 两条「报价单比对/详情只读视图」「核价单表状态机」记录（`comparisonModel.ts`/`ComparisonView.tsx`/`ReadonlyComparison.tsx` 旧实现将被本次改造替换，见 fronttask.md §6）

---

## 0. 测试环境与数据准备（Fixtures，各用例按需引用）

| Fixture | 说明 | 用途 |
|---|---|---|
| **F1** | 一张 **编辑态（DRAFT）** 报价单，报价侧/核价侧共享同一套模板，模板含 ≥3 个页签、每页签 ≥1 个 `is_subtotal` 字段；line item ≥10 条，覆盖 `BOTH`/`QUOTE_ONLY`/`COSTING_ONLY` 三种 presence（可参照原型演示数据结构自建，或复用已导入测试数据如"苏州西门子"报价单 + 销售料号 `3120018220` 系列，需执行时核对该单报价侧/核价侧模板是否已配置 `is_subtotal` 字段） | A/B 模块编辑态用例主力夹具 |
| **F2** | 一张 **已提交 / 已审批（SUBMITTED 或 APPROVED）** 核价单，四份快照（`quoteCardValues`/`quoteExcelValues`/`costingCardValues`/`costingExcelValues`）齐备非 NULL（参照 RECORD 2026-06-29 提示：早期 SUBMITTED 单可能快照缺失，需选快照特性上线后创建的单，如记录中提到的 `89da551c`） | `frozen=true` 口径用例 |
| **F3** | 两个测试账号：`SALES_REP`/`SALES_MANAGER`（销售侧）与 `PRICING_MANAGER`/`SYSTEM_ADMIN`（财务侧） | 桶隔离 + 鉴权用例 |
| **F4** | 一个页签**无** `is_subtotal` 字段的组件（若现网无此类组件需在组件管理临时建一个仅含普通字段的页签用于回归） | 边界用例 C-02 |
| **F5** | 模板漂移场景：先正常连线保存一个用户列，随后修改/删除该列引用的 `componentId`（如把该页签的 `is_subtotal` 字段改为普通字段，或删除该页签）| 边界用例 C-03 |
| **F6** | 极限数量：单张单 ≥50 个销售料号（用于分页/性能观察，非严格性能压测） | 数据状态覆盖-极限 |

**通用前置**（除非用例单独说明，均默认已满足）：前后端 dev server 已启动（8081/5174 存活）；F1/F2 数据已导入；测试账号已建好角色权限。

---

## A. 后端接口测试（对 `api.md` / `backtask.md` AC-1~AC-6）

### A.1 Config：`GET/PUT /{id}/comparison-view/config?bucket=`

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| A-CFG-01 | api.md §3 语义（AC-2 前置态） | F1，该 (quotationId, bucket=SALES) 从未 PUT 过 | `GET /{id}/comparison-view/config?bucket=SALES` | `data.columns = null`；`data.bucket='SALES'`；`data.quotationId=id` | 响应 200 + `columns` 字段值为 JSON `null`（非 `[]`）|
| A-CFG-02 | AC-2 | 承 A-CFG-01 | `PUT` body：`{columns:[{id:'p1',kind:'PRODUCT_TOTAL',sortOrder:0,threshold:0},{id:'p2',kind:'TAB_PAIR',sortOrder:1,threshold:500,quoteComponentId:'<uuid>',quoteMetric:'material_subtotal',quoteLabel:'投料·材料小计',costingComponentId:'<uuid>',costingMetric:'__TAB_TOTAL__',costingLabel:'投料成本·页签合计'}]}`，再 `GET` 同 bucket | GET 返回的 `columns` 与 PUT 提交的**逐字段**一致（id/kind/sortOrder/threshold/quoteComponentId/quoteMetric/quoteLabel/costingComponentId/costingMetric/costingLabel 全部原样回显，无字段丢失/类型漂移） | JSON deep-equal（顺序也需一致，按 sortOrder）|
| A-CFG-03 | AC-1（桶隔离，关键） | 同一 quotationId | 先 `PUT bucket=SALES` 保存 columns=[A]，再 `PUT bucket=FINANCE` 保存 columns=[B]（B≠A），随后分别 `GET bucket=SALES` 与 `GET bucket=FINANCE` | SALES 返回仍是 [A]（未被 FINANCE 的 PUT 覆盖），FINANCE 返回 [B] | 两次 GET 结果与各自保存内容一致，互不污染；SQL 核验 `SELECT bucket,jsonb_array_length(columns) FROM quotation_comparison_config WHERE quotation_id=<id>` 应有 2 行（SALES 一行 + FINANCE 一行） |
| A-CFG-04 | AC-2（upsert 语义） | 承 A-CFG-02，该 bucket 已有记录 | 再次 `PUT bucket=SALES` 提交新的 columns=[C]（不含之前的 p1/p2），随后 `GET` | GET 返回 [C]（**全量覆盖**，p1/p2 不再出现，不是合并保留） | 旧列彻底消失，`updated_at` 晚于 `created_at` 且刷新 |
| A-CFG-05 | 边界（T2 校验） | — | `PUT` 时 `bucket` 传非法值（如 `bucket=OTHER` 或缺省不传） | HTTP 400 | 状态码=400，非 500/200 |
| A-CFG-06 | 边界（T2 校验） | — | `PUT` body 结构非法：`columns` 传字符串/对象而非数组，或数组元素缺 `id`/`kind`/`threshold` 必填键 | HTTP 400 | 状态码=400 |
| A-CFG-07 | 鉴权（api.md §0.3） | 不带鉴权 token/带无权限角色 | `GET`/`PUT` config 端点 | 未鉴权 → 401；角色不在 `{SALES_REP,SALES_MANAGER,PRICING_MANAGER,SYSTEM_ADMIN}` → 403 | 状态码符合预期，不泄漏数据 |
| A-CFG-08 | AC-1 隔离维度扩展 | 两张不同报价单 quotationId1/quotationId2，各自 PUT `bucket=SALES` | 分别 GET | 两单数据互不影响（配置以 `(quotationId,bucket)` 联合唯一键隔离，非仅按 bucket） | SQL `SELECT quotation_id,bucket FROM quotation_comparison_config` 每行 `(quotation_id,bucket)` 唯一 |
| A-CFG-09 | api.md §5.1 | F1 | PUT 的 columns 中包含 `kind='PRODUCT_TOTAL'` 且自定义 `threshold=200` | GET 回显该 threshold=200（默认列阈值持久化生效，非恒为 0） | 回显值=200 |

### A.2 Meta：`GET /{id}/comparison-view/meta`

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| A-META-01 | AC-5 | F1（模板含 N 个报价侧页签 + M 个核价侧页签） | `GET /{id}/comparison-view/meta` | `quoteTabs.length === N`，`costingTabs.length === M`，与模板实际页签数一致 | 数量相等 |
| A-META-02 | AC-5 | F1 | 逐个页签核对 `metrics` | 每页签 `metrics` = 该页签所有 `is_subtotal=true` 字段（`key`=字段 name，`label`=字段 label，`type='SUBTOTAL_FIELD'`）+ **末尾恰好一条** `{key:'__TAB_TOTAL__',type:'TAB_TOTAL'}` | 逐页签 metrics 数组内容比对：`is_subtotal` 字段无遗漏无多余 + `__TAB_TOTAL__` 存在且唯一、位于末尾 |
| A-META-03 | AC-5（componentId 一致性） | 承 A-META-01 | 取 meta 返回的某 `componentId`，用它构造一条 config TAB_PAIR 列并 PUT，再 GET config | 回显的 `quoteComponentId`/`costingComponentId` 与 meta 提供的 componentId 完全一致（同一 UUID，可作为稳定引用键） | 字符串相等 |
| A-META-04 | api.md §1 语义（边界） | 构造一张只有报价侧模板、核价侧未绑模板的报价单（或用核价单尚未生成的场景） | `GET meta` | 核价侧 `costingTabs = []`（空数组，非 null，非报错） | 200 + 空数组 |
| A-META-05 | api.md §1（"与 bucket 无关"） | F1 | 不传 bucket 参数请求 meta（本端点无 bucket query） | 正常返回（meta 端点本身无 bucket 参数，两侧目录对所有桶一致），SALES 与 FINANCE 入口调用应得到相同结果 | 响应结构一致，无 bucket 相关差异 |

### A.3 Data：`GET /{id}/comparison-view/data[?frozen=]`（核心）

**AC-3「单源一致」核对方法**（同一 line item 的取值必须在两处逐值相等）：
- **方法 M1（UI 交叉核对）**：打开报价单编辑页 → 产品卡片视图，找到目标销售料号对应卡片 → 切到某页签，读该页签底部"页签合计"及各字段列小计；核价单页面同理读核价侧对应页签合计/小计；再读该产品卡片的"产品总计"（SUBTOTAL 组件独立公式值）。与 `comparison-view/data` 同一 partNo 的 `quote.tabs[componentId].tabTotal` / `.subtotals[metric]` / `quote.productTotal` 逐值比对（核价侧同理比对 `costing.*`）。
- **方法 M2（服务端二次调用交叉核对，推荐用于自动化）**：同一 partNo，分别调用（a）`comparison-view/data`，（b）现有报价单/核价单详情接口（`GET /quotations/{id}`，取返回体中该 line item 的 `quoteCardValues`/`costingCardValues` 快照 JSON，或编辑态下产品卡片渲染所用的 `ComponentDataEffectiveRows`/`CardSnapshotService` 同源计算结果），对同一 `componentId`+字段名，程序化 diff 两边数值（允许的浮点误差为 0，因为 backtask.md 要求"复用同一服务"，理论上应是同一个 `BigDecimal` 值）。
- **方法 M3（DB 层探测，仅用于人工抽查）**：`SELECT quote_card_values, costing_card_values FROM quotation_line_item WHERE id = '<lineItemId>'`，用 `jsonb` 路径提取对应页签/字段的值（具体 JSON 结构以 `CardSnapshotService` 实际落盘结构为准，执行时先 `SELECT quote_card_values::text` 看一次真实结构再定位路径），与 data 端点返回值比对。

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| A-DATA-01 | AC-4 | F1 中已知一条 `presence` 应为 `BOTH` 的料号 | `GET data`，定位该 partNo 行 | `presence='BOTH'`，`quote` 与 `costing` 均非 null | 字段值符合 |
| A-DATA-02 | AC-4 | F1 中一条仅报价侧有（无核价侧核价单/line item）的料号 | 同上 | `presence='QUOTE_ONLY'`，`quote` 非 null，`costing===null` | 字段值符合 |
| A-DATA-03 | AC-4 | F1 中一条仅核价侧有的料号 | 同上 | `presence='COSTING_ONLY'`，`costing` 非 null，`quote===null` | 字段值符合 |
| A-DATA-04 | AC-3（关键） | F1，选 3 个 partNo（含 A-DATA-01/02/03 各一个单边样本） | 用方法 M1 或 M2 逐值核对 `productTotal` | `data.rows[].quote.productTotal` 与报价单该产品卡片"产品总计"逐值相等；`costing.productTotal` 同理，且**不等于**该产品卡片各页签合计的简单加总（验证"是 SUBTOTAL 组件独立公式值，不是页签合计加总"这条特别语义，需构造存在差异的样例） | 数值相等（productTotal ≠ Σtabtotal 时更能证明取值路径正确）|
| A-DATA-05 | AC-3（关键） | 同上 3 个 partNo | 逐页签核对 `tabs[componentId].subtotals[field]` 与 `tabTotal` | 与报价单/核价单 Tab 对应页签展示值逐值相等；`tabTotal` = 该页签所有 `is_subtotal` 列之和（可用 M2/M3 验证求和口径） | 数值相等 |
| A-DATA-06 | api.md §2 语义 | F1（编辑态） | `GET data`（不带 frozen，即默认 false） | 取值 = 当前有效卡片值，与报价单/核价单**编辑态** Tab 展示一致（此时若手动改一个字段值并触发 autosave，重新 GET data 应能取到最新值） | 编辑后重新拉取，diff 前后应变化，证明未走陈旧缓存 |
| A-DATA-07 | api.md §2 语义 | F2（已提交核价单） | `GET data?frozen=true` | 取值 = 冻结快照，与该单**详情页 / 已提交核价单**只读展示一致；即使之后基础数据变了也不应变化（可选：修改底层物料价格后重新 GET，确认 frozen 结果不变） | 数值与只读视图一致且对底层数据变化免疫 |
| A-DATA-08 | api.md §2 语义（边界） | F1（DRAFT，未提交/未冻结） | `GET data?frozen=true` | 应有明确、非 500 的行为（返回空快照 / 回退当前值 / 明确错误码，三选一，具体口径需求文档未显式约束，**测试时如遇 500 直接判 Bug，如遇非预期静默数据丢失需向 PM/架构澄清预期口径**） | 非 500；若行为与前端调用假设（详情页/已提交单才传 frozen=true）不符需登记为待澄清项，非阻断项 |
| A-DATA-09 | 精度口径（api.md §0.3） | F1 | 核对返回的原始数值精度 | `productTotal` 返回时其精度应支持 2 位小数展示口径（后端给原始值，不强制截断，但数值应在 2 位精度范围内无异常多位浮点噪声，如 `15500.001234` 这种应为 `BigDecimal` 而非 `double` 误差）；`tabTotal`/`subtotals` 同理支持 4 位口径 | 数值类型/精度符合 `docs/小数显示口径` |
| A-DATA-10 | api.md §2 语义（边界） | F4（某页签无 `is_subtotal` 字段） | `GET data`，核对该页签在 `subtotals` 中的表现 | 该页签的 `subtotals` 为空对象 `{}`（无字段可小计），`tabTotal` 应为 0 或按"无小计列求和=0"处理（需与 A-META-02 的"该页签 metrics 里没有 SUBTOTAL_FIELD、只有 __TAB_TOTAL__"配套验证，不应报错/返回 null 崩溃前端） | 无 500，`subtotals={}`，`tabTotal` 有明确数值（非 undefined） |
| A-DATA-11 | api.md §2 语义（料号并集） | F1 | 核对 `rows` 集合 | `rows` = 报价侧 line item partNo 并集 核价侧 line item partNo，无遗漏（报价侧独有的、核价侧独有的都要出现）、无重复（同 partNo 不应出现两行） | partNo 去重后数量 = rows.length；并集覆盖完整 |
| A-DATA-12 | 性能/健壮性（backtask T3 备注） | F6（≥50 料号 × 多页签） | `GET data`，观察响应时间与是否有 N+1 迹象（可用日志/APM 观察 SQL 调用次数） | 响应在可接受时间内返回（无需硬性 SLA，但不应出现随料号数线性放大到秒级以上的明显 N+1），不 500/超时 | 响应成功且耗时无异常量级增长（可选记录基线用于后续回归对比）|

### A.4 旧端点无回归（AC-6）

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| A-REG-01 | AC-6 | F1 | `GET /{id}/comparison`（旧 tag-based 端点） | 返回结构/字段与改造前一致（`buildComparison` 逻辑未被触碰） | 响应结构不变；`CostingComparisonResourceTest` 全绿 |
| A-REG-02 | AC-6 | F1 | `POST /{id}/comparison/export` | 行为不变（仍可导出，即使新比对视图前端已移除导出按钮，后端端点本身应保持可用） | 响应不变，无 404/500 |
| A-REG-03 | AC-6 | — | 跑后端回归测试套件 | `./mvnw test -Dtest=CostingComparisonResourceTest`（worktree 内 `cpq-backend/` 目录执行）全绿 | BUILD SUCCESS |

---

## B. 前端交互测试（对 `fronttask.md` AC-F1~AC-F9 + 原型 1:1）

### B.1 主表结构（AC-F1/AC-F2）

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| B-STRUCT-01 | AC-F1 | F1，登录 SALES_REP，进入报价单编辑页 → 比对视图 Tab | 观察任一销售料号对应的表格区域 | 该料号占 **3 行**（报价数据行/核价数据行/差异行），"销售料号"列用 `rowspan=3` 合并成一格 | DOM 中该料号单元格 `rowspan="3"`，其余两行无该列单元格 |
| B-STRUCT-02 | AC-F2 | 同上 | 观察第 1 数据列 | 列头显示"🔒 产品卡片总计"+ 灰色"默认"徽标，**恒为第一列**，**无 ✕ 删除图标** | 视觉/DOM 均不含 close icon |
| B-STRUCT-03 | AC-F1/AC-F3 | 已存在 ≥1 个用户列（TAB_PAIR） | 观察用户列列头 | 分两行显示"报价：`<页签>`·`<小计/合计>` / 核价：`<页签>`·`<小计/合计>`"，右侧含 ⚙（阈值）+ ✕（删除）图标，下方显示"阈值 N" | 文案格式与原型 `renderColHeadInner` 逐字符一致（含间隔点格式） |
| B-STRUCT-04 | AC-F2 | 编辑态（非 readonly） | 点击默认列 ⚙ 图标 | 弹出气泡输入框，预填当前阈值，输入新值点"确定" | 默认列阈值更新，列头"阈值 N"文案同步刷新，toast "阈值已更新" | 
| B-STRUCT-05 | AC-F4 | 用户列 | 点击 ⚙ 修改用户列阈值为 800 | 该列所有差异格按新阈值 800 重新判色（原橙色若 diff≥800 变无色，反之亦然） | 着色随阈值改变**实时**变化，无需刷新页面 |
| B-STRUCT-06 | AC-F1（比对值格式） | 任意含"XX小计"/"XX合计"字样的列头 | 观察格式化 | 末尾"小计"→"·小计"、"合计"→"·合计"（如"材料小计"显示"材料·小计"，"页签合计"显示"页签·合计"），该格式在列头、已配对清单、连线节点标签**处处统一** | 三处文案格式一致 |
| B-STRUCT-07 | AC-F1（数值展示） | 任意数据行 | 观察数值单元格 | 千分位分隔（如 `15,500`），空值显示"—"（浅灰色） | 视觉/文本内容符合 |

### B.2 着色规则（AC-F5）

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| B-COLOR-01 | AC-F5 | 某列某料号 `diff = quote - costing < 0`（如报价 14500、核价 15000，diff=-500） | 观察该差异格 | 红底白字（`#ff4d4f`/`#fff`），**优先于**阈值判定（即使 diff 绝对值 < 阈值也判红不判橙）| 视觉红底 + 数值带负号 `-500` |
| B-COLOR-02 | AC-F5 | 某列阈值=500，diff=300（0 ≤ diff < 阈值）| 观察 | 橙底白字（`#fa8c16`/`#fff`），数值带正号 `+300` | 视觉橙底 |
| B-COLOR-03 | AC-F5 | 某列阈值=500，diff=600（diff ≥ 阈值）| 观察 | 无背景色，普通文字，数值 `+600` | 无着色 |
| B-COLOR-04 | AC-F5（边界值） | 某列阈值=0（默认），diff=0 | 观察 | `diff<0` 不成立、`diff<threshold(0)` 不成立 → 无色（"默认阈值 0 时两档重叠→只显红"的临界点，diff 恰好=0 时应为无色而非橙）| 无着色，非红非橙 |
| B-COLOR-05 | AC-F5（边界值） | 某列阈值=500，diff 恰好=500 | 观察 | `diff<threshold` 为 `500<500`=false → 无色（非橙，验证 `<` 而非 `<=`） | 无着色 |
| B-COLOR-06 | AC-F5 | 任意红/橙差异格 | 观察是否"整格填色" | 仅差异格背景色改变，**同料号的报价/核价数据行不受影响**（不整行染色） | 数据行背景保持默认（`cq-row-diff td` 浅灰背景 `#fbfbfb` 除外，这是差异行整行的基础底色非红橙判色） |
| B-COLOR-07 | AC-F5 | 差异值为正/负/零 | 观察数值前缀符号 | 正数带 `+`，负数自带 `-`（无需额外加号），零无符号 | 文本前缀符合 |

### B.3 单边料号变灰（AC-F5）

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| B-MUTE-01 | AC-F5 | F1 中 `presence=QUOTE_ONLY` 的料号 | 观察该料号 3 行 | 核价数据行整行变灰（`#fafafa` 背景 + `#bbb` 文字），差异行差异格显示"—"（非 0，非空白）| 视觉+文本符合 |
| B-MUTE-02 | AC-F5 | `presence=COSTING_ONLY` 的料号 | 观察 | 报价数据行整行变灰，差异"—" | 视觉+文本符合，与 B-MUTE-01 对称 |
| B-MUTE-03 | AC-F5/AC-F6 | 同上两类单边料号 | 观察料号列 | 追加橙色 Tag："仅报价"（QUOTE_ONLY）或"仅核价"（COSTING_ONLY），样式 `#fff7e6`底/`#d46b08`字 | Tag 文案与显隐条件正确 |
| B-MUTE-04 | AC-F5 | `presence=BOTH` 的正常料号 | 观察 | 两侧数据行均正常展示（非灰），料号列无橙色 Tag | 无变灰、无 Tag |

### B.4 差异料号排序前置（AC-F6）

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| B-SORT-01 | AC-F6 | F1，已知哪些料号会被判为"差异料号"（任一列标红/标橙，或单边） | 关闭状态下记录当前顺序，点击"差异料号"开关 | 差异料号（含单边，最高优先级）排到列表前面，其余料号保持**原有相对顺序**跟在后面 | 排序前后对比：差异料号全部前置，非差异料号间相对顺序不变 |
| B-SORT-02 | AC-F6（稳定排序） | 存在多个差异料号 | 观察多个差异料号之间的相对顺序 | 保持它们在原数组中的相对顺序（不因排序算法不稳定而打乱），单边料号在"有色差异"料号之前（最高优先级） | 顺序符合"稳定排序"定义 |
| B-SORT-03 | AC-F6（非隐藏） | 开关勾选后 | 核对总行数/总料号数 | 与关闭前**总数相同**，只是顺序变化，非差异料号仍可见（可能翻页可见），未被过滤掉 | 分页 meta "共 N 个料号" 数值前后一致 |
| B-SORT-04 | AC-F6 | 已勾选状态 | 再次点击开关取消勾选 | 恢复到未勾选前的原始顺序（不残留排序状态） | 顺序还原 |

### B.5 过滤（AC-F7）

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| B-FILTER-01 | AC-F7 | F1 | 过滤框输入某销售料号的**子串**（如输入 `018220` 而非完整料号） | 只显示 partNo 包含该子串的料号行 | 显示结果集全部满足子串匹配，不匹配项消失 |
| B-FILTER-02 | AC-F7 | 已翻到第 2 页 | 输入过滤字符 | 自动重置回第 1 页 | 当前页码=1 |
| B-FILTER-03 | AC-F7（边界） | — | 输入一个不存在的料号子串 | 主表显示空态"暂无匹配的销售料号"（跨全部列合并单元格） | 空态文案与样式符合原型 `.empty-hint` |
| B-FILTER-04 | AC-F7 | 已输入过滤字符 | 清空过滤框 | 恢复展示全部料号 | 行数恢复 |
| B-FILTER-05 | AC-F7（边界，仅匹配销售料号本身） | — | 在过滤框输入产品名称关键字（非料号） | 不应匹配到任何行（过滤仅针对销售料号字段，非产品名称）| 结果为空，验证过滤字段范围 |

### B.6 分页（AC-F7）

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| B-PAGE-01 | AC-F7 | F1（料号数 >10） | 打开比对视图，不做任何操作 | 默认每页显示 10 个料号（每料号 3 行，共 30 行 + 表头） | 页面渲染行数=页内料号数×3 |
| B-PAGE-02 | AC-F7 | 同上 | 切换页大小选择器为 20 / 50 | 每页显示对应数量料号（若总数不足则显示全部），页大小切换后重置到第 1 页 | 行数与页码符合 |
| B-PAGE-03 | AC-F7 | 任意页 | 观察任一料号的 3 行块 | 不会出现"料号块被从中间切断、部分行在上一页部分在下一页"的情况 | 分页边界严格按料号对齐 |
| B-PAGE-04 | AC-F7 | 非首页 | 点击"‹"上一页 / "›"下一页 | 页码正确前进/后退，首页时"‹"禁用，末页时"›"禁用 | 按钮 disabled 状态正确 |
| B-PAGE-05 | AC-F7 | F6（≥50 料号，pageSize=10） | 计算总页数 | 总页数 = ceil(总料号数/pageSize)，页码按钮数量与之一致 | 页码按钮数=总页数 |

### B.7 连线配置抽屉（AC-F1/AC-F3，交互核心）

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| B-DRAWER-01 | AC-F3 | 编辑态（readonly=false） | 点击"+ 新增比对" | 从右侧滑出 Ant `Drawer`，宽 960px（`max-width:96vw`），标题"新增比对列 · 连线配置" | Drawer 打开，宽度/标题符合 |
| B-DRAWER-02 | AC-F1 | 抽屉已打开 | 观察左右两列 | 左列"报价单页签"标题 + 按 `meta.quoteTabs` 渲染分组（蓝带+左强调条 `#1677ff`）；右列"核价单页签"同理渲染 `meta.costingTabs` | 分组数=对应侧页签数，标题文案=tabName |
| B-DRAWER-03 | AC-F1 | 展开任一分组 | 观察节点 | 字段小计（`SUBTOTAL_FIELD`）节点标签前有绿点（`#52c41a`）；末尾"页签合计"（`TAB_TOTAL`）节点为橙点橙字（`#fa8c16`/`#d46b08`），且加粗 | 节点样式/顺序（小计在前、合计在最后）符合 |
| B-DRAWER-04 | AC-F1 | 观察右列（核价单页签） | 对比左列节点布局 | 核价侧节点 port 圆点在**内侧（左缘）**、标签**右对齐**；报价侧节点 port 在右缘、标签左对齐（连线只走中间空隙，不穿过文字标签） | DOM 结构/CSS class 与原型 `.cq-link-col-costing .cq-link-node` 一致 |
| B-DRAWER-05 | AC-F3 | 抽屉打开，无 pending 状态 | 点击左侧任一节点（如"投料·材料小计"）| 该节点 port 放大变实心蓝（pending 高亮态） | port 视觉变化为 pending 样式 |
| B-DRAWER-06 | AC-F3 | 承上，已有 pending | 再点击左侧**另一个**节点（同侧） | pending 端切换到新点击的节点（允许改主意），原节点恢复未选中态 | pending 唯一且指向最新点击节点 |
| B-DRAWER-07 | AC-F3 | 承 B-DRAWER-05（左侧 pending 已选） | 点击右侧任一核价节点 | 生成一条正式连线（SVG 贝塞尔曲线连接两 port），"已配对清单"追加一行，pending 态清空，两端 port 变为 `connected`（实心蓝） | 清单行数 +1，连线可见，两端 port 样式变化 |
| B-DRAWER-08 | AC-F3（一对多） | 已有一条连线 A↔B | 再选中节点 A（已连接）作为新连线起点，连到另一核价节点 C | 允许生成第二条连线 A↔C（同一节点可连多条，不报错/不覆盖旧连线） | 清单中同时存在 A↔B 与 A↔C 两条 |
| B-DRAWER-09 | AC-F3 | 已配对清单非空 | 观察清单每行 | 显示"报价：`<页签>`·`<比对值>` ↔ 核价：`<页签>`·`<比对值>`" + 阈值数字输入框（默认 0）+ 删除 ✕ | 文案格式含间隔点，阈值默认值=0 |
| B-DRAWER-10 | AC-F3 | 承上 | 修改某行阈值输入框为 300 | 该配对对象的 threshold 更新为 300（内存态，确定后随该列一起持久化）| 输入框失焦/输入后值保持 300 |
| B-DRAWER-11 | AC-F3 | 已配对清单 ≥1 行 | 鼠标悬停某清单行 | 对应连线高亮（变橙 `#fa8c16`、加粗），移开鼠标恢复 | 双向联动：行→线 |
| B-DRAWER-12 | AC-F3 | 同上 | 鼠标点击画布中某条连线 | 对应清单行滚动定位并短暂高亮（`flashPairRow`）| 连线→行联动生效 |
| B-DRAWER-13 | AC-F3 | 已配对清单 ≥2 行 | 点击某行删除 ✕ | 该配对从清单移除，对应连线同步消失，其余连线不受影响 | 清单行数-1，画布连线数同步-1 |
| B-DRAWER-14 | AC-F1（页签折叠，本期新增范围） | 抽屉打开，默认全展开 | 点击某分组标题 | 箭头从 ▾ 变为 ▸，该分组下节点隐藏（`display:none`）| 折叠态视觉/DOM 符合 |
| B-DRAWER-15 | AC-F1 | 折叠某分组前，该分组内某节点**已有连线** | 折叠该分组 | 该连线不消失，改为**锚定到该分组标题内侧边缘**，线型变为**虚线**（`stroke-dasharray`），仍能在清单中看到该配对（未被删除）| 连线仍可见（虚线态），清单行不变 |
| B-DRAWER-16 | AC-F1 | 承上（已折叠+虚线锚定） | 再次点击该分组标题展开 | 连线恢复实线、恢复连到具体节点 port（非标题边缘）| 视觉恢复正常连线 |
| B-DRAWER-17 | AC-F3（重绘时机） | 已有 ≥1 条连线 | 分别触发：滚动左/右列、缩放浏览器窗口（resize）、抽屉刚滑入完成 | 每次触发后连线路径重新计算并正确贴合两端 port 位置（不出现连线漂移/断裂/位置错位）| 视觉连线始终准确贴合两端 |
| B-DRAWER-18 | AC-F3（空清单校验） | 已配对清单为空 | 点击"确定" | 弹出 toast"请先连线配置至少一对"，抽屉**不关闭** | toast 出现，抽屉仍展示 |
| B-DRAWER-19 | AC-F3 | 已配对清单非空（如 2 条） | 点击"确定" | 每条配对按 `sortOrder` 递增转为 `ColumnDef(kind:'TAB_PAIR')` **追加到 columns 末尾**（不影响已有列顺序）→ 调 `PUT config` → 抽屉关闭 → 主表重渲染新增的列 → toast "已添加 2 个比对列" | 主表新增 2 列且位于末尾，toast 文案含正确数量 |
| B-DRAWER-20 | AC-F3（持久化） | 承 B-DRAWER-19 | 刷新页面（F5）| 新增的比对列仍然存在（已通过 PUT 落库，非仅内存态）| 刷新后列不丢失 |
| B-DRAWER-21 | AC-F3 | 抽屉已打开且有若干未确认的连线操作 | 点击右上角 ✕ 关闭按钮 / 点击"取消" / 点击遮罩区域 | 当前配对全部丢弃（不落库），抽屉关闭，主表列不变化 | 主表列数与打开前一致 |
| B-DRAWER-22 | AC-F3 | 上次关闭时曾有残留 pending/配对 | 再次点击"新增比对"打开抽屉 | 清单/pending 态被**重置**为空，节点树按**最新** meta 重新构建（而非沿用上次残留状态）| 抽屉内无残留连线/pending |
| B-DRAWER-23 | AC-F1（比对值格式） | 抽屉内任意节点/已配对清单 | 观察文案 | "XX小计"显示"XX·小计"、"页签合计"显示"页签·合计"，与主表列头格式统一 | 三处（节点/清单/主表列头）文案格式一致 |

### B.8 桶隔离（AC-F8）

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| B-BUCKET-01 | AC-F8 | F1，SALES_REP 登录 | 报价单编辑页 → 比对视图，新增 1 个比对列并保存 | 该列仅出现在**该单**报价单编辑页的比对视图，不出现在核价单页面的比对视图 | 两处列表互不影响 |
| B-BUCKET-02 | AC-F8 | F3 财务账号登录，同一 quotationId | 核价单页面 → 比对视图，独立新增另一个比对列并保存 | 该列仅出现在核价单页面，不影响报价单编辑页 SALES 桶配置（即 B-BUCKET-01 的列仍完整保留） | SALES 与 FINANCE 两侧列各自独立、互不覆盖（对应 A-CFG-03 后端验证的前端表现）|
| B-BUCKET-03 | AC-F8 | 报价单**详情页**（只读） | 打开该单详情页比对视图 | **无**"新增比对"按钮；列头**无** ✕/⚙ 图标；展示的是 SALES 桶已保存的配置（与编辑页当前配置一致，除非编辑页有未保存改动） | 控件均隐藏，配置来源=SALES 桶 |
| B-BUCKET-04 | AC-F8（不调 PUT，关键） | 详情页比对视图打开中 | 打开浏览器 F12 Network 面板，观察全过程 | 页面加载期间只有 `GET meta`/`GET data?frozen=true`/`GET config?bucket=SALES` 三个请求，**没有任何 `PUT comparison-view/config` 请求** | Network 面板无 PUT 请求记录 |
| B-BUCKET-05 | AC-F8 | 核价单页面，PENDING 状态 + 财务/管理员登录（`editable=true`） | 打开比对视图 | 可配置（`readonly=false`，同 B-BUCKET-02 场景）| "新增比对"按钮可见 |
| B-BUCKET-06 | AC-F8 | 核价单非 PENDING（如已核价/已驳回）或非财务角色登录 | 打开比对视图 | `readonly=true`（按该页现有 `editable` 逻辑收紧），无配置控件 | 按钮/图标隐藏，与编辑权限一致 |
| B-BUCKET-07 | AC-F8 | fronttask.md §1 挂载点表 | 核价单页面（CostingReviewPage）| 检查是否存在"比对视图"入口 | 该页新增比对视图 Tab/分区可见（当前无此入口，为本次新增功能）| 入口存在且可点击进入 |
| B-BUCKET-08 | AC-F8 | 刷新验证 | SALES 与 FINANCE 各自保存配置后，分别刷新两处页面 | 各自读到的仍是各自桶的配置（不串桶）| 刷新前后一致，无串桶 |

### B.9 无回归（AC-F9）

| 编号 | 所属AC | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| B-REG-01 | AC-F9 | F1 | 打开报价单编辑页"报价单"Tab、"核价单"Tab（既有产品卡片渲染） | 渲染正常，无"加载中"卡死、无数据错位（本次改动不应影响这两个既有 Tab）| 两 Tab 渲染正常，无回归 |
| B-REG-02 | AC-F9 | 干净环境 + 改动后环境各跑一次 | `npx playwright test e2e/quotation-flow.spec.ts --reporter=list`（A/B 同型对比：改动前 checkout 到改动前的 commit 跑一次，改动后再跑一次，比较失败集合是否新增） | 改动后失败用例集合 ⊆ 改动前失败用例集合（不新增失败）；**注意**已知涟漪：干净 master 上因共享 DB 夹具缺产品分类可能存在 3 个既存失败（`task0712-update071501-category-axis`），需按此基线判断，不可误将既存失败算作本次回归 | A/B 对比无新增失败项 |
| B-REG-03 | AC-F9 | — | `npx tsc --noEmit -p tsconfig.json` | 0 错误 | 编译通过 |
| B-REG-04 | AC-F9 | 改动涉及 `comparisonModel.ts`/`comparisonTable.tsx` 等旧文件处置（fronttask.md §6）| 全工程 grep 确认 `comparisonModel.ts`/`comparisonModel.test.ts` 是否还有其它消费方 | 若无其它引用，旧文件应被删除或改造，不应"新旧两套比对逻辑并存"（双源风险，AP-50 类）| 无孤儿旧实现残留在 import 图中 |

---

## C. 边界 / 异常场景

| 编号 | 覆盖点 | 前置条件 | 操作步骤 | 预期结果 | 通过判定 |
|---|---|---|---|---|---|
| C-01 | 空配置（数据状态-空） | 全新报价单，从未保存过比对配置 | 打开比对视图 | 前端自动种入唯一默认列"产品卡片总计"（`{id:'__product_total__',kind:'PRODUCT_TOTAL',threshold:0,sortOrder:0}`），**不立即调用 PUT 落库**（config 表仍无记录，直到用户首次保存）| 主表仅 1 列；DB 中该 (quotationId,bucket) 无记录（`SELECT` 为空）|
| C-02 | 某页签无 `is_subtotal` 字段 | F4 | 打开连线抽屉，展开该页签分组 | 该分组下**只有**"页签合计"一个节点（无字段小计/绿点节点），主表若已配置该页签的 metric 为具体字段则不可能存在（因为不存在该 metric）| 节点数=1，仅 `__TAB_TOTAL__` |
| C-03 | 模板漂移（列引用失效） | F5（先保存一个引用某 componentId+metric 的用户列，随后修改/删除该字段的 `is_subtotal` 属性或删除该页签）| 重新打开比对视图 | 该列仍显示在列头（用保存时冗余的 `quoteLabel`/`costingLabel` 兜底渲染文案），但取值格显示"—"（因为该 metric 在最新 `data`/`meta` 中已找不到对应字段），**不应报错/白屏** | 页面不崩溃，该列全部数据格="—"，列头仍可读（用兜底 label）|
| C-04 | 两侧都无该料号（理论边界） | — | 检查 `data.rows` 生成逻辑是否可能产生 `presence` 既非 BOTH/QUOTE_ONLY/COSTING_ONLY 的脏行 | 不应存在——`rows` 严格是"报价侧∪核价侧料号"的并集，两侧都没有的料号根本不会入选，此边界应为"不可达代码路径"验证（即没有第 4 种 presence 值）| `presence` 枚举值只有 3 种，无脏数据 |
| C-05 | 报价侧和核价侧均为空（数据状态-空，边界） | 新建报价单，尚无任何 line item | 打开比对视图 | `data.rows=[]`，主表展示空态（"暂无匹配的销售料号"或等价空态），不报错 | 无 500，空态展示正常 |
| C-06 | 阈值负数（待澄清） | — | 阈值输入框/PUT body 中传 `threshold=-100` | 需求文档未显式禁止负数阈值；测试时观察实际行为（若允许，则验证 `diff < -100` 才判橙，比红线更严格，逻辑上可行但业务含义存疑）| **标记为待与 PM 澄清项**：是否应校验阈值 ≥ 0？当前按"未禁止即允许"验证系统不崩溃，但业务合理性需确认 |
| C-07 | 阈值极限值 | — | 阈值输入 `0`、极大值（如 `999999999`）、小数（如 `0.01`，若字段允许小数）| 系统正常处理不报错，着色判定按数值大小比较正常工作 | 无异常，判色逻辑对极值仍正确 |
| C-08 | 销售料号含特殊字符/超长（等价类-非法边界） | 构造一个含特殊字符（如 `%`、`&`、中文引号）或超长（>50 字符）的销售料号数据 | 过滤框输入该料号片段 | 过滤功能正常工作（无 XSS 渲染问题、无因特殊字符导致的 SQL/正则报错），超长料号在表格中正常显示（可换行或省略号，不撑破布局）| 无渲染异常、无控制台报错 |
| C-09 | 网络请求失败（错误处理） | 模拟 meta/data/config 任一请求失败（如断网、后端临时 503）| 打开比对视图 | 前端应有明确的失败态提示（loading 失败提示/错误 message），不应无限"加载中"或白屏（参照 `docs/反模式.md` AP-31 "加载中永久占位族"的教训，本次虽非 driver expand 链路但同类心智模型适用）| 有明确错误反馈，非永久 loading |
| C-10 | 并发保存冲突（配置全量覆盖语义） | 两个浏览器 tab 同时打开同一 (quotationId, bucket)，各自新增不同的比对列 | tab A 先保存，tab B 后保存 | 由于 PUT 是**全量覆盖**（非增量合并），tab B 的保存会**覆盖** tab A 的改动（tab A 新增的列丢失）。这是当前架构设计的**已知行为**（api.md §4 明确"后端不做增量语义"），非 bug，但建议记录为**已知限制**供 PM 评估是否需要前端提示"配置已被他人更新" | 验证行为符合设计文档描述（覆盖发生），非崩溃；作为已知限制记录，不计入 Bug |
| C-11 | 极限数量-连线抽屉性能 | 模板含较多页签（如 ≥10 个）及较多字段小计（每页签 ≥5 个）| 打开连线抽屉，滚动左右列，尝试连线 | 节点树渲染流畅、滚动不卡顿、连线重绘及时（无需硬性 FPS 指标，人工观感为准）| 无明显卡顿/连线错位 |
| C-12 | 极限数量-主表 | F6（≥50 料号）| 打开比对视图，翻页到最后一页 | 页面依然可用，无渲染崩溃/内存暴涨迹象 | 正常翻页可用 |
| C-13 | 单条数据（数据状态-单条）| 报价单仅 1 个销售料号（presence=BOTH） | 打开比对视图 | 分页显示"共 1 个料号"，页码仅 1 页，"‹""›"均禁用 | 分页组件对单条数据的边界表现正确 |

---

## D. 用例统计

| 模块 | 子分类 | 用例数 |
|---|---|---|
| A. 后端接口 | Config (A-CFG) | 9 |
| | Meta (A-META) | 5 |
| | Data (A-DATA) | 12 |
| | 旧端点回归 (A-REG) | 3 |
| **A 小计** | | **29** |
| B. 前端交互 | 主表结构 (B-STRUCT) | 7 |
| | 着色规则 (B-COLOR) | 7 |
| | 单边变灰 (B-MUTE) | 4 |
| | 差异料号前置 (B-SORT) | 4 |
| | 过滤 (B-FILTER) | 5 |
| | 分页 (B-PAGE) | 5 |
| | 连线抽屉 (B-DRAWER) | 23 |
| | 桶隔离 (B-BUCKET) | 8 |
| | 无回归 (B-REG) | 4 |
| **B 小计** | | **67** |
| C. 边界/异常 | | **13** |
| **总计** | | **109** |

---

## E. 已知覆盖缺口 / 待确认项（如实标注，非隐瞒）

1. **AC-3 单源一致的自动化程度有限**：方法 M1（UI 逐值核对）与 M3（DB 探测）依赖人工操作，未写成自动化脚本；建议开发方在 `ComparisonViewResourceTest`（backtask.md T5）中直接调用同一 `CardSnapshotService`/`ComponentDataEffectiveRows` 方法做程序化断言，本测试文档的 A-DATA-04/05 更多是**验收时的人工复核步骤**，不能替代后端单测里的严格断言。
2. **C-06 阈值负数、C-10 并发覆盖**：需求文档（`需求说明.md`/`api.md`/`backtask.md`/`fronttask.md`）均未显式定义预期行为，本文档按"未禁止即验证不崩溃"的保守策略设计用例，**建议开发前或验收前与 PM 过一遍这两点**，避免验收时口径不一致。
3. **A-DATA-08（DRAFT 单请求 frozen=true）**：api.md 未明确规定编辑态报价单请求冻结口径时的返回值，测试仅能验证"不 500"，具体应返回空/回退/报错三选一需要架构或 PM 明确后才能收紧为强断言。
4. **性能类用例（A-DATA-12/C-11/C-12）为观感级别，非量化 SLA**：需求文档未给出具体性能指标（如"N 个料号响应应 <Xs"），本文档暂以定性描述（"无明显卡顿""无 N+1 迹象"）覆盖，若后续有明确性能预算应补充量化断言。
5. **详情页三处挂载点中"核价单详情"入口**（fronttask.md §1 表格备注"若有独立核价详情入口则同挂只读"）**在当前代码库中是否存在独立路由待开发阶段确认**；若不存在，B-BUCKET 中涉及"核价单详情"的部分用例需相应调整或删除，本文档暂按 fronttask.md 假设该入口存在来设计。
6. **E2E 自动化脚本本身未编写**：本文档 B-REG-02 引用了 `quotation-flow.spec.ts`（既有脚本），但 fronttask.md §8 建议的"比对视图专项 E2E"新脚本尚未编写，属于开发任务的一部分（fronttask.md §8 第 5 条），本测试文档已将其交互点拆入 B.1~B.8 的人工用例，待开发方补充专项 spec 后应回填自动化用例编号映射。
7. **本文档尚未执行**：功能未完成开发，以上所有用例的"实际结果"列留空，待开发完成后按 CLAUDE.md「三轮自检循环」逐条执行、记录通过/失败，Bug 按标准格式登记。

---

*（本文档为用例设计阶段产出，执行记录/Bug 报告将在开发完成后另行补充或以本文件"实际结果"列回填）*
