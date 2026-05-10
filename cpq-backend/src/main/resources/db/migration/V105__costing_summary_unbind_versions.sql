-- V105: 核价单解绑价格版本号 (P1: 全局变量重构)
--
-- 背景: 核价单原依赖三个 PUBLISHED 版本号 (elementVersionId/materialVersionId/exchangeVersionId)
-- 改为统一走 v_costing_*_price 视图 (即"当前默认 PUBLISHED 版本"). 已发布单的指标值由
-- costing_summary_result 已冻结, 价格变更不再追溯影响.
--
-- 决策依据 (用户已确认):
--   #4 costing_price_version 表保留作审计骨架 — 已发布单 element_version_id 列保留只读
--   #8 老报价单已绑版本号: 列保留只读做审计
--
-- 改动:
--   1. 三个 version_id 列 NOT NULL → NULL (新核价单不再要求选)
--   2. 列保留 + 注释标记 deprecated, 不破坏已发布单的审计快照

ALTER TABLE costing_summary
    ALTER COLUMN element_version_id  DROP NOT NULL,
    ALTER COLUMN material_version_id DROP NOT NULL,
    ALTER COLUMN exchange_version_id DROP NOT NULL;

COMMENT ON COLUMN costing_summary.element_version_id IS
    'V105 起 deprecated. 历史核价单保留以审计当时所用版本; 新核价单为 NULL, 计算走 v_costing_element_price 当前默认 PUBLISHED 版本';
COMMENT ON COLUMN costing_summary.material_version_id IS
    'V105 起 deprecated. 历史核价单保留以审计当时所用版本; 新核价单为 NULL, 计算走 v_costing_material_price 当前默认 PUBLISHED 版本';
COMMENT ON COLUMN costing_summary.exchange_version_id IS
    'V105 起 deprecated. 历史核价单保留以审计当时所用版本; 新核价单为 NULL, 计算走 v_costing_exchange_rate 当前默认 PUBLISHED 版本';
