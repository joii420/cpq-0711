-- task-0723 阶段 5：视图改名 + 全局变量停用 + 配置 INACTIVE
--
-- ⚠️ 施工前必读（见 dev-docs/task-0723-废弃业务与表清洗/backtask.md B7.1 + B0 依赖顺序）：
--   1. 版本号 V360 是本次编写时的占位（基于 SELECT MAX(version::int) FROM flyway_schema_history = 359 +1）。
--      技术总监应用前必须重新核对 flyway_schema_history 的当前 MAX 版本号，与并发 worktree
--      （尤其 task-0722 update-0724）不冲突，必要时改名改号（未应用文件改名不受"已应用迁移禁改名"约束）。
--   2. 本文件只是"编写"，本 wave 不 touch java、不触发 Quarkus dev 重启、不应用到共享库 cpq_db。
--   3. 应用前必须先确认阶段 2~4（B1~B6）的代码退役已合并到将要跑迁移的目标分支/主仓，
--      否则视图/变量/配置改动会导致仍在读它们的死代码抛错（那些死代码这一 wave 已经确认清除，
--      但技术总监合并前请再跑一次 grep 复核，见交付说明）。
--   4. 应用后必须 touch 一个 java 文件强制 Quarkus dev 重启，清 ImplicitJoinRewriter.tableColumnsCache
--      等进程级缓存（视图改名不破坏视图本身的可用性，但会让"改名验证法"失效——见 backtask B0 表格第 8 条）。

-- ── 第 1 层视图改名（4 张，✅自验 0 运行时引用，pg_depend 验证仅这 4 张依赖 mat_*）──────────────
ALTER VIEW v_costing_summary_full RENAME TO v_costing_summary_full_drop;
ALTER VIEW v_c_summary_agg        RENAME TO v_c_summary_agg_drop;
ALTER VIEW v_q_part_info_merged   RENAME TO v_q_part_info_merged_drop;
ALTER VIEW v_part_material_recipe RENAME TO v_part_material_recipe_drop;

-- ── 第 2 层视图改名（3 张价格视图，仅被 254 张历史核价单 frozen_dto 引用元数据，非真查询）──────
ALTER VIEW v_costing_element_price  RENAME TO v_costing_element_price_drop;
ALTER VIEW v_costing_material_price RENAME TO v_costing_material_price_drop;
ALTER VIEW v_costing_exchange_rate  RENAME TO v_costing_exchange_rate_drop;

-- ── 死全局变量停用（不删行，历史 frozen_dto.gvDefs 引用的是定义元数据快照，不受影响）───────────
UPDATE global_variable_definition SET is_active = false
WHERE code IN ('ELEM_PRICE', 'MAT_PRICE', 'EXCHANGE_RATE');

-- ── 废弃配置置 INACTIVE（不删行，保留追溯；实测 58 条 ACTIVE 指向 mat_* / costing_*）─────────────
UPDATE basic_data_config SET status = 'INACTIVE'
WHERE target_table LIKE 'mat\_%' OR target_table LIKE 'costing\_%';
