# repair-0727 · 后端任务拆分（B1~B6）

> 主文档：`需求说明.md`；接口契约：`api.md`；测试：`test.md`
> 分支：`feat/repair-0727-backfill-patch`（基于 master bf3822a3）
> 顺序建议：B1 → B2 → B3 →（B4 可与 B3 并行）→ B5 → B6

---

## B1 —— 锚点支持顶层集合运算（修 D3）

**文件**：`cpq-backend/src/main/java/com/cpq/datasource/sqlview/QuotePendingRewriter.java`

**现状**：`rewrite()` 检测到 `hasTopLevelSetOp(masked)` 为真时整体 `anchorInjected=false`，`$bom_view`（边行 `UNION ALL` 根行）因此拿不到 `__v6_id`。

**改法**：
1. 顶层 set-op 时，按 depth==0 的 `UNION|UNION ALL|INTERSECT|EXCEPT` 关键字把 SQL 切成 N 个分支（注意：切分位置基于 `SqlTextMask.mask` 后的坐标，替换作用于原文，沿用本类既有「masked 定位 + 原文替换」范式）。
2. 逐分支处理：
   - 分支内 depth 相对顶层为 0 的 `FROM/JOIN <白名单表>` 命中 → 在该分支自己的 `SELECT`（跳过 `DISTINCT`）后注入 `<alias>.id AS __v6_id, `
   - 未命中白名单表，或该分支同深度含 `GROUP BY` → 注入 `NULL::uuid AS __v6_id, `
3. `Result.primaryTable/primaryAlias` = 第一个命中白名单表的分支的主位表/别名。
4. 全部分支都未命中白名单表 → 保持 `anchorInjected=false`（现状不变）。
5. 表替换（pending 可见性改写）逻辑**不动**——它本来就逐 token 作用，与分支无关。

**不变式**：
- 非 set-op 视图的改写结果必须与改动前**逐字节一致**（回归护栏，用现有 `QuotePendingRewriterTest` 断言）。
- `rewrite(sql, conn, injectAnchor=false)` 重载行为不变（`BomTreeRenderService:384` 的 spine 递归 SQL 依赖它）。

**自检**：`QuotePendingRewriterTest` 新增 set-op 用例（双分支/三分支、分支带别名/裸表名、第二分支非白名单、分支含 GROUP BY）。

---

## B2 —— 列映射支持集合运算视图

**文件**：`quotation/service/backfill/QuoteBackfillColumnMapper.java`

**改法**：`resolveUncached` 中若模板顶层含 set-op，则改用「第一个含白名单表的分支」单独做 `LIMIT 0` 元数据探测（`SELECT * FROM (<branch>) _outer LIMIT 0`），得到 `colToBase`；`primaryTable` 取该分支主位表。

**理由**：pgjdbc 对整体 set-op 结果的列不返回 `getBaseTableName`（输出列不属于任何物理表），直接探测必然全空。SQL 语义保证各分支列位置对齐，故分支映射对整视图成立。

**降级**：分支提取失败 / 探测异常 → `NOT_BACKFILLABLE`（不猜测、不错写，沿用既有约定）。

**自检**：对真实 `bom_view` 模板断言 `backfillable=true`、`primaryTable=material_bom_item`、`colToBase` 含 `_组成数量→composition_qty` 等。

---

## B3 —— 回填改 patch 语义（修 D1 + D2）★核心

**文件**：`quotation/service/backfill/QuoteBackfillCollector.java`（主）、`QuoteBackfillPlan.java`（结构）

### B3.1 新增「基底行集」装载

在现有 Phase B（按 `__v6_id` 批量回查）之后、定路径之前插入：

```
对每张表，收集本次触达的全部组轴 axisSet（来自 CHANGE/DELETE 的 DB 行轴 + ADD 的 axisHint + Phase C 的 pending 扫描轴）
一次 SQL 取本单 pending 基底：
  SELECT * FROM <t> WHERE pending_quotation_id = :qid AND (axis 元组 IN :axisSet)
对 pending 为空的轴，再一次 SQL 取正式基底：
  SELECT * FROM <t> WHERE is_current = true AND pending_quotation_id IS NULL AND (axis 元组 IN :axisSet)
```
- **禁 N+1**：每表最多 2 条 SQL。轴元组匹配用 `(col1, col2, …) IN ((?,?,…), …)`；轴列可能含 NULL（如 `supplier_no`），元组 IN 对 NULL 不成立 → **必须改用 `OR (col IS NOT DISTINCT FROM ? AND …)` 展开**或先按非空轴收窄再内存过滤。二选一，代码注释写明选择理由。
- 基底行按 `id` 建索引，供 B3.2 patch 命中。

### B3.2 有效行集 = 基底 ⊕ patch ⊖ 墓碑 ⊕ 新增

替换 `GroupChange.effectiveNewRows` 的现有构造（当前是「页签行 = 全部」）：

```
for 基底行 r:
    if r.id ∈ 墓碑命中集 → 跳过（记 RowChange DELETE，oldValues = r）
    else:
        newRow = r 的 contentColumns 值副本
        if r.id ∈ 页签 patch 映射:
            对 patch 的每个 (物理列, 非空值) → newRow[列] = 值
            有差异 → 记 RowChange CHANGE（oldValues=r, newValues=newRow 的差异列）
        effectiveNewRows.add(newRow)
追加 ADD 行（手工新增，合成逻辑不变）
```

- **patch 只覆盖非空值**（沿用现有 `isNull → continue`，保持「清空不传导」既有限制，写进 javadoc）。
- **轴列与版本列不参与 patch**（页签映射里若出现轴列，忽略并 WARN）。
- **多页签冲突**：同一 `(rowId, 列)` 被两个组件映射且值不同 → 先到先得（按组件 `sortOrder`，无则按 componentId 稳定序），记 WARN，并在 `RowChange` 上打 `conflict=true` 供预览标注。

### B3.3 路径判定改写

```
effectiveNewRows.isEmpty()                              → OFFLINE
基底来自 pending && 无 CHANGE/ADD/DELETE                 → FLIP
其余                                                    → REBUILD
```
- Phase C（纯 pending 无页签表征）仍产出 FLIP，逻辑不变。
- OFFLINE 判据由「页签行集为空」变为「基底行被墓碑删空」——**注意 Q5 语义**（平铺页签删到 0 行=整组下线）仍成立，因为墓碑齐全时基底会被删空。

### B3.4 结构调整

`QuoteBackfillPlan.GroupChange` 新增：
- `List<Map<String,Object>> baseRows`（基底行，预览/断言用）
- `String baseSource`（`PENDING` / `CURRENT` / `NONE`）
`RowChange` 新增 `boolean conflict`。

**不动** `QuoteBackfillService.executeRebuild/executeFlip/executeOffline`（喂进去的行集变正确即可）。

---

## B4 —— 预览如实 + 语义化 DTO（修 D4 + D5）

**文件**：`quotation/service/backfill/QuoteBackfillPreviewService.java`、`quotation/dto/backfill/*.java`、新增 `BackfillLabelResolver`

1. **如实性**：B3 之后「零差异组」是真结论，`:80`/`:93` 的过滤保留即可；但**必须新增断言点**——若某组 `route=REBUILD` 且 `rowChanges` 为空，记 ERROR 日志（patch 语义下该状态不应出现），便于回归发现。
2. **产品归属**：`BackfillGroupDTO` 新增 `productNo` / `productName`：
   - `material_bom_item` / `element_bom_item` / `capacity` → 轴 `material_no`
   - `unit_price` → 轴 `finished_material_no`，为空取 `code`
   - `plating_scheme` → null（归「全局共享」分区）
3. **业务类别**：`categoryLabel` 静态字典：`material_bom_item`=BOM 组成、`element_bom_item`=材质元素构成、`unit_price`=单价、`capacity`=工时产能、`plating_scheme`=电镀方案。
4. **中文列名**：新增 `BackfillLabelResolver`
   - 一级：`colToBase` 反查（页签列别名，去前导 `_`）→ 该组命中的组件视图别名
   - 二级：静态物理列中文字典（至少覆盖 5 张表的常用列：component_no/characteristic/composition_qty/base_qty/issue_unit/scrap_rate/seq_no/net_weight/rough_weight/pricing_price/currency/unit/…）
   - 三级：物理列名原样（兜底，绝不空）
5. **名称批量解析**：料号→品名一次 `SELECT material_no, material_name FROM material_master WHERE material_no IN (…)`；客户号→客户名一次查 `customer`。**禁 N+1**。
6. **行标签**：`BackfillRowDTO.rowLabel` = 该行业务身份短语（如「组成件 W-1001（外购件）」「元素 Cu」），由表 + 关键列拼装。
7. `changes` 结构由 `Map<String, Object[]>` 升级为 `List<{column,label,oldValue,newValue}>`（见 api.md），保证前端拿得到中文名。

**previewToken 算法不动**（口径随 plan 内容自然变化，属预期）。

---

## B5 —— 既有测试按新语义重写

**文件**：`src/test/java/com/cpq/quotation/service/backfill/*`

- `QuoteBackfillFlatAcceptanceTest` / 树相关验收测试：凡断言「页签没出现的行被删除」的用例，按 patch 语义改为「保留」，并补对照用例（有墓碑才删）。
- `QuoteBackfillPreviewTokenTest`：确认幂等仍成立（AC-R7）。
- `PlatingSchemeGlobalVersioningAcceptanceTest`：全局升版语义不变，复跑。
- 新增用例见 `test.md`（AC-R1~R8）。

**纪律**：不得为了让旧测试变绿而弱化断言；语义变了的用例要**重写并在注释里写明 repair-0727 语义变更**。

---

## B6 —— 文档与登记

- `dev-docs/task-0721-报价升版逻辑/需求说明.md` §4.3 规则四加注：**丙「全量对齐」已于 repair-0727 修订为 patch 语义**，指向本目录。
- `docs/反模式.md` 新增条目：**「页签投影当整组权威 → 回填反向抹数据」**（含 D1/D2/D3/D4 四联根因 + 判据：任何「从渲染层反写基础数据」的路径，必须先确认渲染层是全集还是投影）。
- `docs/RECORD.md` 追加本次修复记录。
- `BACKLOG.md` 新增两条：①已损坏存量数据处置（P1）②「清空不传导」限制（P2）。

---

## 强制自检（合并前必须逐条打勾）

- [ ] `cd cpq-backend && ./mvnw -q test -Dtest='Quote*Backfill*,QuotePendingRewriter*'` 全绿
- [ ] 后端全量 `./mvnw test` 无新增失败（与 master 基线对照，允许既有失败但须列出）
- [ ] 8081 dev server 重启后 `QuoteViewValidationService` 启动校验无 FAIL（含 `bom_view` 这类 set-op 视图）
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → 401
- [ ] 真库端到端：新建报价单→导入→核价通过→**逐字段比对 pending 基底 vs 通过后 current**（AC-R1/R2/R4 的硬证据，附 SQL 输出）
