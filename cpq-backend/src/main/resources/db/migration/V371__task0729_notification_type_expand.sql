-- =============================================================================
-- task-0729 · B11：批量调价通知 —— 扩展 notification.type 白名单
-- =============================================================================
-- 背景：notification 表建表时的 chk_notification_type 白名单只覆盖审批流
-- （APPROVAL_*）+ 账号相关（PASSWORD_RESET/ROLE_CHANGED）+ SYSTEM，不含本任务
-- 新增的两类通知类型。真实调用暴露：INSERT 直接被 CHECK 约束拒绝（未提前建迁移
-- 就先写代码，属流程失误，此处补正）。
--
-- 新增两个类型：
--   PRICE_ADJUST_JOB_SUMMARY    —— 批量调价 job 执行完成后，通知触发批次的财务人员
--                                   （success/failed/conflict/stale 计数汇总）。
--   PRICE_ADJUST_QUOTATION_REVIEW —— 通知受影响报价单的销售负责人（价格可能已被
--                                   新版本覆盖，需复核；若该单同批次有失败/冲突项一并说明）。
-- 详见 PriceAdjustNotificationService。
--
-- 只改约束，不改列/不改既有数据。
-- =============================================================================

ALTER TABLE notification DROP CONSTRAINT chk_notification_type;

ALTER TABLE notification ADD CONSTRAINT chk_notification_type
    CHECK (type IN (
        'APPROVAL_SUBMITTED', 'APPROVAL_APPROVED', 'APPROVAL_REJECTED', 'APPROVAL_REMINDER',
        'PASSWORD_RESET', 'ROLE_CHANGED', 'SYSTEM',
        'PRICE_ADJUST_JOB_SUMMARY', 'PRICE_ADJUST_QUOTATION_REVIEW'
    ));
