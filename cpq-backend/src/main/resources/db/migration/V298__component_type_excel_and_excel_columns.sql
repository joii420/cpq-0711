-- V298: 放开 component_type 支持 EXCEL；新增 excel_columns 承载 EXCEL 组件列定义。
-- 背景: V22 的 chk_component_type 仅允许 NORMAL/SUBTOTAL，会挡 EXCEL 插入。
ALTER TABLE component DROP CONSTRAINT IF EXISTS chk_component_type;
ALTER TABLE component ADD CONSTRAINT chk_component_type
    CHECK (component_type IN ('NORMAL', 'SUBTOTAL', 'EXCEL'));

-- EXCEL 组件列定义（{col_key,title,source_type,hidden,formula,sort} 数组）。
-- 非 EXCEL 组件保持 '[]'。
ALTER TABLE component ADD COLUMN IF NOT EXISTS excel_columns jsonb NOT NULL DEFAULT '[]';
