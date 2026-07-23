-- task-0722 元素单价维护与价格策略 · B1
-- 策略表 + 变更历史表 + element_daily_price.fetch_status 扩 IMPORT 枚举 + 查询索引
--
-- 【版本号变更说明】本迁移原为 V351，因共享开发库上另一并发会话（不同分支/worktree）也创建了
-- 版本号 V351 的迁移文件并对共享 flyway_schema_history 执行了 repair，导致该版本号的历史记录被
-- 覆盖（不是本迁移的 DDL 被回滚——实测 element_price_strategy 表已存在且结构正确，repair 只改写
-- 记账行，不触碰实际 schema 对象）。按 cpq-shared-flyway-history-churn 教训"已应用的迁移禁止改名
-- 改号"，此文件不去抢占/篡改已被占用的 V351，改为在新查到的空闲版本号 V356 下重新登记；全文改写为
-- 幂等（IF NOT EXISTS / DROP+ADD CONSTRAINT）以兼容"对象已存在、只需补登记"的场景。

-- ============ 1. 策略表 ============
-- 客户维度用 customer_no VARCHAR，禁止 customer_id UUID 外键（需求 §11.11.4）
--    原因：核价侧走 '_GLOBAL_'，它不是一条真实 customer 记录，UUID 外键无处安放。
CREATE TABLE IF NOT EXISTS element_price_strategy (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_no   VARCHAR(64)   NOT NULL,      -- 真实客户编码(CUST-1269) 或 '_GLOBAL_'
    element_code  VARCHAR(64),                 -- NULL = 客户级默认行；非空 = 元素级例外
    source_id     UUID          NOT NULL REFERENCES element_price_source(id),
    method        VARCHAR(16)   NOT NULL,
    window_num    INT,
    window_unit   VARCHAR(8),
    factor        NUMERIC(10,4) NOT NULL DEFAULT 1,
    premium       NUMERIC(18,4) NOT NULL DEFAULT 0,
    status        VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by    UUID,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_by    UUID,
    CONSTRAINT chk_eps2_method CHECK (method IN ('LATEST','AVG','MAX','MIN')),
    CONSTRAINT chk_eps2_unit   CHECK (window_unit IS NULL OR window_unit IN ('DAY','WEEK','MONTH','YEAR')),
    CONSTRAINT chk_eps2_status CHECK (status IN ('ACTIVE','DISABLED')),
    -- LATEST 不带窗口；其余三种窗口必填且 > 0（需求 §11.7）
    CONSTRAINT chk_eps2_window CHECK (
        (method =  'LATEST' AND window_num IS NULL AND window_unit IS NULL) OR
        (method <> 'LATEST' AND window_num > 0     AND window_unit IS NOT NULL)
    ),
    CONSTRAINT chk_eps2_factor CHECK (factor > 0)
);

-- 同一客户下：默认行至多 1 条、每个元素例外至多 1 条
CREATE UNIQUE INDEX IF NOT EXISTS uq_eps2_cust_elem
    ON element_price_strategy (customer_no, COALESCE(element_code, ''));
CREATE INDEX IF NOT EXISTS idx_eps2_customer ON element_price_strategy (customer_no);

COMMENT ON TABLE  element_price_strategy IS 'task-0722 客户元素价格策略；element_code IS NULL 为客户级默认行';
COMMENT ON COLUMN element_price_strategy.customer_no IS '真实客户编码或 _GLOBAL_（核价成本口径）';

-- ============ 2. 变更历史表 ============
-- 不加 FK 到 strategy：策略删除后历史必须留存（需求 §11.14F.2）
CREATE TABLE IF NOT EXISTS element_price_strategy_log (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id     UUID,
    customer_no     VARCHAR(64)  NOT NULL,
    element_code    VARCHAR(64),                -- NULL = 客户级默认策略
    action          VARCHAR(16)  NOT NULL,
    snapshot        JSONB        NOT NULL,      -- 变更后完整快照；DELETE 存删除前快照
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    changed_by      UUID,
    changed_by_name VARCHAR(100),               -- 冗余姓名，历史查询免 join user
    CONSTRAINT chk_epsl_action CHECK (action IN ('CREATE','UPDATE','DELETE'))
);
CREATE INDEX IF NOT EXISTS idx_epsl_cust_time ON element_price_strategy_log (customer_no, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_epsl_target    ON element_price_strategy_log (customer_no, COALESCE(element_code,''), changed_at DESC);

COMMENT ON TABLE element_price_strategy_log IS 'task-0722 价格策略变更历史（存快照、展示差异；只读、不做回滚）';

-- ============ 3. element_daily_price 扩 fetch_status 枚举 ============
-- 既有 CHECK 只允许 SUCCESS/FAILED/MANUAL，本期批量导入写 IMPORT
ALTER TABLE element_daily_price DROP CONSTRAINT IF EXISTS chk_edp_fetch_status;
ALTER TABLE element_daily_price ADD  CONSTRAINT chk_edp_fetch_status
    CHECK (fetch_status IN ('SUCCESS','FAILED','MANUAL','IMPORT'));

-- 明细/矩阵查询按 (source_id, price_date) 过滤，补索引
CREATE INDEX IF NOT EXISTS idx_edp_source_date ON element_daily_price (source_id, price_date DESC);
CREATE INDEX IF NOT EXISTS idx_edp_elem_date   ON element_daily_price (element_name, price_date DESC);
