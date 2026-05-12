# Excel 导入向导 V6 — Staging-Based 三步流程 + 料号版本管理收尾

> 创建日期：2026-05-12
> 状态：设计稿，待用户审阅
> 取代：`2026-04-26-cpq-design-v5.1.md` 中关于 V5 Wizard 六步流程的章节（请勿再参考）

---

## 1. 背景与动机

V5 阶段（commit `06afdc3` 之前）的导入向导存在以下痛点：

1. **6 步流程冗余**：UI2 基础差异 / UI1 客户冲突 / UI3 孤儿行 三个独立 Drawer，用户认知负担重
2. **事务边界过早**：`POST /confirm` 一次性写入 `mat_*` 正式表，导致用户在「创建报价单」中途取消时已污染基础数据
3. **升版决策粒度粗**：当前 Modal 只支持「全部升版 / 全部不升版」二选一，无法分料号决策
4. **NO_BUMP 语义错误**：当前实现中「不升版」=「用新数据覆盖当前版本」，与"版本管理"精神冲突
5. **草稿态版本切换不完整**：`part_version_locked` 改了但 `excel_view_snapshot` 不重算，导致显示与版本号不一致

本 spec 把导入流程改造为 **3 步 staging-based 流程**，并补齐版本切换的快照重算。

---

## 2. 目标用户与场景

**用户**：销售（SALES_REP / SALES_MANAGER）发起报价时上传客户提供的 Excel 基础数据。

**典型场景**：
- 老客户老料号小幅修改 → 升版到新版本 + 留旧版本可回退
- 老客户老料号无实质变更 → 不升版，沿用当前 DB 版本
- 新料号 → 默认创建 v2000
- 上传后觉得不对 → 一键取消，正式表毫发无损

---

## 3. 已对齐的关键决策

| 决策 ID | 问题 | 选择 |
|---|---|---|
| Q1 | 延迟提交事务的实现方式 | **A. 暂存表方案**（新增 `mat_*_staging` + import_session） |
| Q2 | UI1（客户冲突）/ UI3（孤儿行）去向 | **A. 合并进「版本确认」step** |
| Q3 | 升版决策粒度 | **A. 每个料号独立 BUMP/NO_BUMP toggle** |
| Q4 | 差异内容展示详细程度 | **B. Sheet 级别计数 + 可展开 row-level 详情** |
| Q5 | 草稿态切换版本后的数据刷新 | **B. 立即重算 excel_view_snapshot** |

---

## 4. 整体架构

### 4.1 端点契约

```
旧 V5：                                  新 V6：
POST /preview         → 内存解析       POST /upload            → 解析 + 写 staging
POST /confirm         → 写 mat_*       PUT  /sessions/{id}/decisions → 仅更新决策表
(quotation 独立流程)  → POST /quotations  POST /sessions/{id}/commit  → atomic：staging→mat_* + 建报价单
                                       DELETE /sessions/{id}   → 回滚（清 staging）
```

### 4.2 数据流

```
[Step 1 上传]
  POST /upload (multipart: customerId, file)
    → BEGIN
        INSERT import_session (status=PENDING, expires_at=now+24h)
        解析 Excel → INSERT mat_*_staging (import_session_id, ...)
        检测 (cpn,hf) 版本差异 → INSERT import_session_decision (PART_VERSION, ...)
        检测客户料号冲突 → INSERT import_session_decision (CUSTOMER_CONFLICT, ...)
        检测孤儿行 → INSERT import_session_decision (ORPHAN, ...)
      COMMIT
    → 返回 { sessionId, diffPayload }

[Step 2 版本确认]
  用户切换每个料号 BUMP/NO_BUMP / 冲突决策 / 孤儿处理
  每次切换 (debounce 500ms):
    PUT /sessions/{id}/decisions
      → UPDATE import_session_decision SET decision_value = ?

[Step 3 创建报价单]
  用户填名称/分类/模板，点「创建报价单」:
    POST /sessions/{id}/commit (body: name, categoryId, customerTemplateId, costingTemplateId)
      → BEGIN
          SELECT ... FOR UPDATE on import_session
          应用 PART_VERSION 决策:
            BUMP/NEW: staging → mat_* (with new part_version)
                    + mat_part_version_log
                    + UPDATE mat_customer_part_mapping.current_version
                    + UPDATE import_session_decision SET decision_value.appliedVersion = newVersion
            NO_BUMP: 跳过（不写 mat_*）
                    + UPDATE import_session_decision SET decision_value.appliedVersion = currentVersion
          应用 CUSTOMER_CONFLICT / ORPHAN 决策
          INSERT quotation (...)
          INSERT quotation_line_item (..., part_version_locked = appliedVersion)
          生成 excel_view_snapshot (per line_item)
          UPDATE import_session SET status='COMMITTED', committed_at=now()
          DELETE FROM mat_part_staging WHERE import_session_id=? (其它 6 张 staging 同样显式删除)
        COMMIT
      → 返回 { quotationId }
    跳转 /quotations/{id}/edit

  注：commit 后保留 import_session 行（status=COMMITTED）+ import_session_decision 行作审计；
      仅显式删 staging 数据。Cancel 流程才整体 CASCADE 删 session（含 decisions）。

取消任何 step:
  DELETE /sessions/{id}
    → DELETE FROM import_session ... (CASCADE)
```

### 4.3 NO_BUMP 语义（最终明确）

| 决策 | staging 数据动向 | mat_* 写入 | line_item.part_version_locked |
|---|---|---|---|
| BUMP | staging → mat_* | 新版本号 N+1 | N+1 |
| NO_BUMP | 丢弃 staging 数据 | 不动 | DB 当前版本 N |
| NEW | staging → mat_* | v=2000 | 2000 |

「用新数据覆盖当前版本」语义彻底删除，用户无法选择覆盖。

---

## 5. 数据库 Schema（V159 迁移）

### 5.1 Session 主表
```sql
CREATE TABLE import_session (
  id            UUID PRIMARY KEY,
  customer_id   UUID NOT NULL REFERENCES customer(id),
  user_id       UUID,
  status        TEXT NOT NULL DEFAULT 'PENDING',
  source_excel  TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours',
  committed_at  TIMESTAMPTZ
);
CREATE INDEX ix_import_session_status_expires ON import_session(status, expires_at);
```

### 5.2 决策表
```sql
CREATE TABLE import_session_decision (
  import_session_id UUID NOT NULL REFERENCES import_session(id) ON DELETE CASCADE,
  decision_type     TEXT NOT NULL,
  decision_key      TEXT NOT NULL,
  decision_value    JSONB NOT NULL,
  PRIMARY KEY (import_session_id, decision_type, decision_key)
);
```

`decision_type` 取值：`PART_VERSION` / `CUSTOMER_CONFLICT` / `ORPHAN`

`decision_value` JSONB 结构示例：
- PART_VERSION：`{"action":"BUMP","currentVersion":2000,"suggestedVersion":2001,"appliedVersion":null,"sheetDiffs":{"bom":3,"process":1}}`
- CUSTOMER_CONFLICT：`{"action":"USE_EXCEL"}` / `{"action":"USE_DB"}` / `{"action":"SKIP"}`
- ORPHAN：`{"action":"LINK_EXISTING","targetPartId":"..."}` / `{"action":"CREATE_NEW"}` / `{"action":"DISCARD"}`

`appliedVersion` 在 upload 阶段为 `null`，commit 时根据 action 回写（BUMP→suggestedVersion / NO_BUMP→currentVersion / NEW→2000），用于填 `quotation_line_item.part_version_locked`。

**decision_key 格式**：
- PART_VERSION：`"{customerProductNo}|{hfPartNo}"`（如 `"CUST-A001|HF-B100"`）
- CUSTOMER_CONFLICT：`"{conflictType}|{primaryKey}"`（如 `"mapping|CUST-A001|HF-B100"`）
- ORPHAN：`"{sheetCode}|{rowIndex}"`（如 `"bom|42"`）

### 5.4 upload 端点返回结构（diffPayload）
```json
{
  "sessionId": "uuid",
  "diffPayload": {
    "partVersionDecisions": [
      { "key": "CUST-A001|HF-B100", "customerProductNo": "CUST-A001", "hfPartNo": "HF-B100",
        "currentVersion": 2000, "suggestedVersion": 2001,
        "isNew": false, "defaultAction": "BUMP",
        "sheetDiffs": { "bom": 3, "process": 1, "fee": 0 },
        "rowLevelDiff": { "bom": [{"rowKey":"...", "field":"input_qty", "oldValue":"0.8", "newValue":"0.85"}] }
      }
    ],
    "customerConflicts": [...],
    "orphanRows": [...],
    "validation": { "hasErrors": false, "errors": [], "warnings": [] }
  }
}
```

### 5.3 Staging 表
对以下表各建一个 `_staging` 副本（列结构 = 原表 + `import_session_id` + `staging_id`）：
- `mat_part_staging`
- `mat_customer_part_mapping_staging`
- `mat_bom_staging`
- `mat_process_staging`
- `mat_fee_staging`
- `mat_plating_fee_staging`
- `mat_plating_plan_staging`

通用 schema 模板：
```sql
CREATE TABLE mat_bom_staging (
  staging_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  import_session_id UUID NOT NULL REFERENCES import_session(id) ON DELETE CASCADE,
  -- ... 原 mat_bom 全部列
);
CREATE INDEX ix_mat_bom_staging_session ON mat_bom_staging(import_session_id);
```

---

## 6. 事务边界

### 6.1 Upload 事务
单事务：建 session + 解析 Excel + 写 staging + 检测差异 + 写决策。失败回滚整体。

### 6.2 Decisions 事务
单条 UPDATE 一个事务。前端 debounce 500ms 触发，幂等。

### 6.3 Commit 事务（核心）
```
BEGIN
  SELECT FROM import_session WHERE id=? AND status='PENDING' FOR UPDATE
  应用 PART_VERSION 决策（staging → mat_*，回写 appliedVersion）
  应用 CUSTOMER_CONFLICT 决策
  应用 ORPHAN 决策
  INSERT quotation + quotation_line_item (part_version_locked = appliedVersion)
  生成 snapshot per line_item
  UPDATE import_session SET status='COMMITTED', committed_at=now()
  DELETE FROM mat_*_staging WHERE import_session_id=?  -- 7 张 staging 表显式删
  -- import_session 行 + import_session_decision 保留作审计
COMMIT
```

任何一步抛异常 → Postgres 自动回滚 → mat_* 完全没动 → staging 数据保留，前端可重试 commit。

### 6.4 Cancel 事务
```
DELETE FROM import_session WHERE id=?  -- CASCADE 清 staging + decisions
```

### 6.5 Scheduled Cleanup（@Scheduled hourly）
```sql
DELETE FROM import_session WHERE status='PENDING' AND expires_at < now()
```

---

## 7. 前端 3 步向导

### 7.1 顶部 Steps
```
1 上传文件     2 版本确认     3 创建报价单
```
Drawer width 改为 960。

### 7.2 Step 1：上传文件
- 客户下拉 + Dragger
- 下一步 → POST /upload → 拿 sessionId + diffPayload → 进 Step 2

### 7.3 Step 2：版本确认（合并 UI1/UI2/UI3）

3 个可折叠区块：

**区块 A · 料号版本变更**
- 每行：`{cpn} / {hf}  v{current} → v{suggested}  [○升版 ●不升版]`
- 默认勾选 BUMP
- 涉及 sheet：`BOM(3) 工艺(1) 费用(0)` + 「查看详情▼」
- 「查看详情」展开 row-level diff 表（行号 / 字段 / 旧值 / 新值）
- 新料号：禁用 NO_BUMP，提示「将以 v2000 创建」
- 顶部「全部升版 / 全部不升版」批量按钮（不影响新料号）

**区块 B · 客户料号冲突**
- 沿用 `CustomerConflictDrawer` 逻辑，去 Drawer 壳，内嵌为 Section

**区块 C · 孤儿行**
- 沿用 `OrphanRowsDrawer` 逻辑，去 Drawer 壳，内嵌为 Section

**按钮**：`[取消并清理] [上一步] [下一步]`

### 7.4 Step 3：创建报价单
- 合并 `CreateQuotationDrawer` 表单（名称 / 分类 / 报价模板 / 核价模板）
- 「上一步」回 Step 2，保留 sessionId 和决策
- 「创建报价单」→ POST /sessions/{id}/commit → 跳转编辑页

### 7.5 关闭保护
- Mask click / Esc 已禁用（`maskClosable={false}` `keyboard={false}`）
- 显式关闭 → 二次确认 Modal → DELETE session

### 7.6 文件改动清单

| 文件 | 改动 |
|---|---|
| `BasicDataImportV5Wizard.tsx` | 重写 reducer 为 3 步状态机；Steps 改 3 个；UI1/UI2/UI3 合并 |
| `BasicDataImportV5ToQuotation.tsx` | 大幅简化：不再叠加 CreateQuotationDrawer |
| `CreateQuotationDrawer.tsx` | 表单区抽成 `<QuotationCreateForm>` 复用组件 |
| `BasicDataDiffDrawer.tsx` / `CustomerConflictDrawer.tsx` / `OrphanRowsDrawer.tsx` | 改为内嵌 Section 组件 |
| 新建 `PartVersionDecisionList.tsx` | 区块 A 列表 + 详情展开 |
| `basicDataImportV5Service.ts` | 替换为 upload/decisions/commit/cancel 4 方法 |
| `types/import-v5.ts` | 加 sessionId/diffPayload/PartVersionDecision/PartVersionDiffDetail |

---

## 8. 草稿态版本切换 + 快照重算

### 8.1 后端：PUT 端点扩展
```
PUT /api/cpq/quotations/{quotationId}/line-items/{lineItemId}/part-version
Body: { "version": 2003 }

QuotationService.updateLineItemPartVersion:
  1. 校验 quotation.status = 'DRAFT'
  2. 校验 version 存在于 mat_part_version_log 或 == 2000
  3. UPDATE quotation_line_item.part_version_locked = ?
  4. 重新调 SnapshotCollectorService.collect(quotation, lineItem, partVersion=newVersion)
  5. UPDATE quotation_line_item.excel_view_snapshot = newSnapshot
  6. 返回 newSnapshot 给前端
```

### 8.2 前端：切换流程
1. 用户点击产品卡片版本 Tag → `PartVersionDrawer mode='select'`
2. Drawer 内选版本 → PUT 端点
3. 接收新 snapshot → 立即用 React state 更新卡片
4. message.success("已切换至 v{newVersion}，公式已重算")

### 8.3 已提交报价单
- `part_version_locked` 已是「真实使用版本」（commit 时按决策锁死）
- 已提交状态：Tag 只读，依赖 `canSeeVersionTag` 角色控制（已实现）

---

## 9. 兼容性 & 弃用清单

| 旧产物 | 处置 |
|---|---|
| `POST /preview` | @Deprecated，6 个月后删除 |
| `POST /confirm` | @Deprecated，新 commit 端点替代 |
| `importBasicDataV5` 6 参方法 | 保留为 internal commit 逻辑 |
| `/admin/wipe-basic-data` | 改为「清空 staging + 测试 quotation」（dev 期） |
| `partVersionDecisions` Map 入参 | 删除，决策从 session 表读 |
| `ImportRecord` 表 | 保留作为 commit 后审计 |

---

## 10. 未来扩展（不在本期）

1. **断点续传**：sessionId 写 localStorage，浏览器崩溃后用 GET /sessions/{id} 恢复 Step 2/3
2. **多客户合并提交**：session.customer_id 改为多对多
3. **pre-commit 校验端点**：commit 前的分项校验，提前发现问题
4. **历史 session 浏览**：管理员可查看已 COMMITTED 的 session 审计

---

## 11. 风险点 & 缓解

| 风险 | 缓解措施 |
|---|---|
| commit 时 (cpn,hf) 并发升版冲突 | `SELECT ... FOR UPDATE on mat_customer_part_mapping` 串行化 |
| staging 表膨胀（未清理的 PENDING） | scheduled cleanup hourly + 24h TTL |
| Excel 极大文件 OOM | Apache POI streaming 模式 + 入参文件大小限制 |
| VersionedWriter `is_current` 与 part_version 共存歧义 | commit 时按"新版本写入 + 不动 is_current 字段"（is_current 仅 mat_process/fee/plating_fee 使用，与 part_version 正交） |
| 多 tab 同上传 | session 隔离，OK |

---

## 12. 验收标准

1. 上传 Excel 后 mat_* 正式表无变化（仅 staging 有数据）
2. 关闭 Drawer / DELETE session 后 staging 完全清空
3. 24h 未 commit 的 session 被 scheduled job 清理
4. BUMP 决策：mat_* 出现新版本行 + mat_part_version_log 有新条目 + mat_customer_part_mapping.current_version bump
5. NO_BUMP 决策：mat_* 完全无变化，line_item.part_version_locked = 当前 DB 版本
6. NEW 料号：mat_* 出现 v2000 行
7. 草稿态切换版本 → snapshot 立即重算，PDF 导出与版本号一致
8. 已提交报价单：Tag 显示创建时锁定版本，无法点击切换
9. 同一时间多用户上传不相互干扰（staging 按 session_id 隔离）

---

## 13. 实施顺序（待 writing-plans 细化）

粗粒度阶段：

1. **S1 数据库 schema**：V159 迁移（import_session + decision + 7 张 staging 表）
2. **S2 后端 upload 端点**：解析 Excel + 写 staging + 检测差异 + 写决策
3. **S3 后端 decisions 端点**：单 UPDATE
4. **S4 后端 commit 端点**：staging → mat_* + 建报价单 + snapshot
5. **S5 后端 cancel 端点 + scheduled cleanup**
6. **S6 前端 3 步向导重写**
7. **S7 后端 PUT /part-version 加 snapshot 重算**
8. **S8 端到端测试 + 旧端点弃用标记**
