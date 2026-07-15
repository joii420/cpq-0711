-- task-0712 缺口1: 工序 id 契约修复(方案 A 的加法式变体, 共享 DB 安全)。
-- 背景: dev-docs/task-0712-选配模板和报价单选配功能/架构评审.md
--       "工序 id 契约修复设计"节(方案 A 全链贯通 process_no)。
--
-- 核心矛盾: 候选端 + sel_template.allowed_value_key 都在 process_master.process_no 域,
-- 但消费端 quotation_line_process.process_id 是 process(V4) 表的 UUID(FK -> process),
-- 两个标识域此前靠前端防御式映射临时缝合。
--
-- ⚠️ 与架构原 V336(expand-contract, 删 process_id 列换 FK)的偏离:
-- 本迁移只加列 + 放开 NOT NULL + 加新 FK, 不删 process_id 列/不删旧 FK。
-- 原因: process_id 列被共享 8081(master)以及其它并发 worktree 会话的实体映射
-- (QuotationLineProcess.processId) 引用, DROP COLUMN 会导致这些进程的 Hibernate
-- 映射失效而崩溃。收缩阶段(删 process_id 列 + 换主 FK 指向 process_master)
-- 留到本特性分支合并 master 时另做一次迁移, 见 docs/RECORD.md 对应条目 TODO。

-- 1. 加列(可空, 与 process_id 并存)
ALTER TABLE quotation_line_process ADD COLUMN process_no varchar(20);

-- 2. backfill: process.id -> process.code(== process_master.process_no, F4 已实证 1:1)
UPDATE quotation_line_process q
SET process_no = p.code
FROM process p
WHERE p.id = q.process_id;

-- 3. 放开 process_id 的 NOT NULL, 允许选配新写路径只填 process_no(process_id 留 NULL)
ALTER TABLE quotation_line_process ALTER COLUMN process_id DROP NOT NULL;

-- 4. process_no 的新 FK 指向工序库权威表 process_master(process_no 上有 uq_process_master_no
--    唯一索引, 可作 FK 目标)。process_no 允许为 NULL, FK 对 NULL 值不校验, 不影响存量含 NULL
--    的行(理论上 backfill 后不应有 NULL, 但 FK 定义本身对 NULL 安全)。
ALTER TABLE quotation_line_process
    ADD CONSTRAINT quotation_line_process_process_no_fkey
    FOREIGN KEY (process_no) REFERENCES process_master (process_no);

-- 保留(不删): quotation_line_process_process_id_fkey (process_id -> process)
-- 保留(不删): process_id 列本身
-- 两者留给收缩阶段迁移一并处理。
