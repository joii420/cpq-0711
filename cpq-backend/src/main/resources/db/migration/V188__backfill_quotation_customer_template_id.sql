-- V184: 回填 quotation.customer_template_id (2026-05-18)
--
-- 背景: 前端 buildDraftPayload 之前漏传 customerTemplateId / costingCardTemplateId,
-- saveDraft 永远不写入 quotation 表 → 历史 DRAFT 报价单 customer_template_id 全 NULL,
-- 即使 lineItem.template_id 已经写好.
--
-- 本次同期修复前端 buildDraftPayload + 后端 SaveDraftRequest 双侧字段, 新建/编辑报价单
-- 后续会正确落库. 但已有 DRAFT 报价单需要一次性回填.
--
-- 回填策略: 对所有 DRAFT 状态且 customer_template_id 为 NULL 的报价单, 从其 line items
-- 中取一个非空 template_id 作为推断值. 同行 line items 通常用同一个模板 (lineItem.template_id
-- 来自前端 li.templateId || customerTemplateId fallback, 选配/批量导入都是同一份).
--
-- 仅处理 DRAFT 状态 — 已 SUBMITTED/APPROVED 报价单含 *_snapshot, 不应再动 header.

UPDATE quotation q
SET customer_template_id = (
        SELECT li.template_id
        FROM quotation_line_item li
        WHERE li.quotation_id = q.id
          AND li.template_id IS NOT NULL
        LIMIT 1
    ),
    updated_at = NOW()
WHERE q.customer_template_id IS NULL
  AND q.status = 'DRAFT'
  AND EXISTS (
      SELECT 1 FROM quotation_line_item li
      WHERE li.quotation_id = q.id
        AND li.template_id IS NOT NULL
  );

-- 自检: 报告回填了多少行
DO $$
DECLARE
    v_remaining INT;
BEGIN
    SELECT COUNT(*) INTO v_remaining
    FROM quotation q
    WHERE q.customer_template_id IS NULL
      AND q.status = 'DRAFT'
      AND EXISTS (
          SELECT 1 FROM quotation_line_item li
          WHERE li.quotation_id = q.id
            AND li.template_id IS NOT NULL
      );
    IF v_remaining > 0 THEN
        RAISE EXCEPTION 'V184 自检失败: 仍有 % 个 DRAFT 报价单未回填 customer_template_id', v_remaining;
    END IF;
    RAISE NOTICE 'V184 回填完成: 所有 DRAFT 报价单的 customer_template_id 已从 lineItem 反推填入';
END $$;
