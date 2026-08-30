# api.md —— 接口契约

## 结论：本次无契约变更

**零新增端点、零删除端点、零签名变更。** 方法 / 路径 / 入参 / 响应体 / 错误码全未变动。

## 涉及但未变更的端点（供回归定位）

| 端点 | Resource | 本次关系 |
|---|---|---|
| `POST /api/cpq/basic-data/v6/import`（导入） | `BasicDataImportV6Resource` | 写入行为改变（首次落地写正式行），但**契约不变**：请求体、`SheetResultDTO` 字段与语义均不变 |
| `POST /api/cpq/basic-data/v6/create-quotation` | `BasicDataImportV6Resource` | 不变。首次落地的组不产生 pending 行，`repointPendingOwnership` 对其自然为 no-op（UPDATE 影响 0 行），无需改动 |
| 核价通过（`costingApprove`） | `QuotationResource` | **契约不变，行为有变**：响应体字段与错误码全不变；但 FLIP 路径内新增「正式货架已有同版本号同内容的行 → 删除本单 pending 行而非转正」的去重分支（B-14）。首次落地的组仍走 NOOP，不计入 `versionedGroups`。<br>⚠️ 本行**修正了立项初期「核价回填零改动」的判断** —— 该判断在 2026-08-30 扩范围（S-4~S-6）后不再成立 |
| 回填影响预览 | `QuotationResource:594` | 不变。NOOP 组本就不进预览 |

## 行为差异说明（契约未变，但调用方需知晓）

| 场景 | 改动前 | 改动后 |
|---|---|---|
| 全新组首次导入 | 落 pending 影子行（`is_current=false`） | **落正式行**（`is_current=true`、`pending_quotation_id=NULL`） |
| 内容相同的重复导入 | 每次新版本号 + 整份堆行 | **零写入、版本号不变** |
| 内容有差异的导入 | pending 影子行 + 升版 | **不变** |
| 核价通过 | 该组走 FLIP（转正） | 未改值走 **NOOP**（跳过）；改了值走 REBUILD；**共用版本号的第二张单走 FLIP 去重分支（删本单 pending 行）** |
| 连续导入内容相同的改过文件 | 每次 `MAX+1`（2001 / 2002 / 2003…） | **复用同一版本号**（全为 2001），行仍按单各存一份 |

## 回写总账

按 `task-docs.md §2.5`：**本次无契约变更，无需回写 `main-api.md`**。该结论须在 `test-report.md` 中复述确认。
