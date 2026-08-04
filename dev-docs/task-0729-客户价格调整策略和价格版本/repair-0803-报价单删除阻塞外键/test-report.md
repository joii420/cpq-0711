# 测试报告：BL-0108 报价单删除阻塞外键修复

- 执行环境：分支 `fix/repair-0803-quotation-delete-fk-block`；库 `cpq_db_0724`（共享
  dev DB，10.177.152.12:5432）；后端 8081（共享 dev server，已重启加载本次改动）
- 执行人：主线（同一会话，无独立测试工程师，快速修复流程）
- 测试数据纪律：全部使用本 session 早前测试（价格调整批量升版验证）留下的、customer_id
  `f6d10ef0-...` 名下的 DRAFT 测试单；`costing_order` 场景额外插入 1 条隔离测试 fixture
  （`TEST-CO-ISOLATED-001`），验证后立即清理；**未触碰任何真实业务数据（CUST-0001 生产
  报价单、`QT-20260726-0006`/`QT-20260727-0019` 等既存 costing_order 阻塞记录原样未动）**

## AC 逐条对照

| AC | 结果 | 证据 |
|----|------|------|
| AC-1 | ✅ PASS | `DELETE /api/cpq/quotations/443e7481-8446-4516-8954-31b644690bb9`（QT-20260802-0048，被 10 个 job 的 item 阻塞，每个 job 均有 27~35 条其它 item）→ `HTTP 200 {"code":200,"message":"success"}`；`SELECT COUNT(*) FROM quotation WHERE id=...` → 0 |
| AC-2 | ✅ PASS | 删除前后对比 `material_price_update_job_item WHERE quotation_id=...` → 0 行（无孤儿）；抽查 3 个所属 job（`92490373-...`/`530ee372-...`/`be20057e-...`）item 数分别从 28/32/34 变为 27/31/33，精确 -1，其它报价单的 item 未受影响 |
| AC-3 | ✅ PASS | 隔离插入测试 job `11111111-1111-1111-1111-111111111111`（仅 1 条 item，指向 QT-20260802-0049）→ `DELETE` 该报价单 → `HTTP 200`；验证：该 job 与其 item 均已从表中消失（`COUNT(*)=0`）；同批次里该报价单同时挂在的另外 10 个多条 item 的 job 全部原样保留，仅各自 -1（如 `be20057e-...` 33→32、`92490373-...` 27→26） |
| AC-4 | ✅ PASS | 插入隔离测试 `costing_order` fixture（`TEST-CO-ISOLATED-001`，PENDING，指向 QT-20260803-0056）→ `DELETE /api/cpq/quotations/8561f0e3-...` → `HTTP 409 {"code":409,"message":"无法删除该报价单：存在关联的核价单（costing_order），请先处理后再删除"}`（不是 500） |
| AC-5 | ✅ PASS | AC-4 失败后复查：`quotation` 表该行仍在（`status=DRAFT`），事务正确回滚；测试 fixture `costing_order` 行随后手工 `DELETE` 清理，quotation 本身未受影响原样保留 |

## 缺陷清单
无新增缺陷。

## 顺带发现（记入需求文档 §8「已知坑位」，非本次修复范围）
`GlobalExceptionMapper.handleHibernateConstraint`（捕获
`org.hibernate.exception.ConstraintViolationException`）在 BL-0108 报告的原始 500 场景下
**实测未生效**——本次绕开该盲区，直接在 `QuotationService.delete()` 内主动
`em.flush()` + try/catch 处理，不依赖该全局 mapper。

## 回归结论
无回归。改动只新增/包裹 `delete()` 方法内部逻辑，未改动其它现有删除步骤的执行顺序与语义；
`./mvnw -o compile` 0 错误（worktree + 主仓）；后端健康检查 401。

## ③ costing_order 排查结果（不修，报给 coordinator）

- **当前受影响范围**：2 张 DRAFT 单被 `costing_order` 阻塞（`QT-20260726-0006`、
  `QT-20260727-0019`），共 7 条 `costing_order` 记录（均为 `WITHDRAWN` 状态——**FK 不看
  costing_order 自身状态，即使已撤回的核价单记录依然会挡住报价单删除**，这是给业务决策
  的一个细节：用户直觉上可能认为"核价单已撤回=可以删了"，但当前数据结构下不成立）
- **走 ② 改造后用户会看到的提示**：`HTTP 409`
  `{"code":409,"message":"无法删除该报价单：存在关联的核价单（costing_order），请先处理后再删除"}`
  ——已实测验证（见 AC-4/AC-5），不是裸 500，但**仍是拒绝删除**（本次未加级联/未加"跳过"
  选项），用户需要先手工处理核价单才能删除报价单
- **业务决策留白**：级联删除核价单 vs 保持拒绝但提供更明确的处理路径（如提示"请先撤回/
  归档核价单"或提供后台清理入口）——按 coordinator 要求不擅自决定
