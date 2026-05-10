-- =============================================
-- 清空组件、模板、产品绑定、产品管理相关测试数据
-- 按外键依赖顺序从下往上删除
-- =============================================

-- 1. 先清空报价单相关（依赖产品和模板）
DELETE FROM quotation_line_component_data;
DELETE FROM quotation_line_process;
DELETE FROM quotation_line_item_snapshot;
DELETE FROM quotation_line_item;
DELETE FROM quotation_approval;
DELETE FROM quotation;

-- 2. 清空产品-模板绑定
DELETE FROM product_template_binding;

-- 3. 清空模板组件关联
DELETE FROM template_component;

-- 4. 清空模板
DELETE FROM template;

-- 5. 清空组件
DELETE FROM component;

-- 6. 清空组件目录
DELETE FROM component_directory;

-- 7. 清空产品-工序绑定
DELETE FROM product_process;

-- 8. 清空产品
DELETE FROM product;

-- 重置相关序列
ALTER SEQUENCE component_code_seq RESTART WITH 1;
ALTER SEQUENCE quotation_number_seq RESTART WITH 1;
