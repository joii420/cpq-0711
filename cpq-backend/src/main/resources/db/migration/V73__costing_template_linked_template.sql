-- V73: costing_template 加列 linked_template_id —— 关联到「模板配置」(template 表) 中的某个具体模板
-- （报价模板 templateKind='QUOTATION' 或 核价模板 templateKind='COSTING'）
--
-- 业务语义：
--   当用户在 V5 导入流程"创建报价单"抽屉里选了报价模板 X / 核价模板 Y 时，
--   报价单视图的 Excel 视图渲染 → 用 linked_template_id = X.id 的 costing_template
--   核价单视图的 Excel 视图渲染 → 用 linked_template_id = Y.id 的 costing_template
--
-- FK 设 ON DELETE SET NULL —— 模板被删（极少数场景）时 Excel 模板不连带删除，仅解除关联。
-- 不加 UNIQUE 约束：一个 template 理论上可以被多个 Excel 模板关联（不同版本/默认/A-B 测试）；
--                  渲染时按 (linked_template_id, status='PUBLISHED', is_default=true) 取唯一一份。

ALTER TABLE costing_template
    ADD COLUMN IF NOT EXISTS linked_template_id UUID;

ALTER TABLE costing_template
    DROP CONSTRAINT IF EXISTS costing_template_linked_template_fk;

ALTER TABLE costing_template
    ADD CONSTRAINT costing_template_linked_template_fk
    FOREIGN KEY (linked_template_id) REFERENCES template(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_costing_template_linked_template
    ON costing_template(linked_template_id);
