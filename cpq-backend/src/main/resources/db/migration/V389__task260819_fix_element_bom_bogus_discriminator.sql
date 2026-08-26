-- V389__task260819_fix_element_bom_bogus_discriminator.sql
-- 修正 V388 种子数据的判别式声明错误（本轮 B-5 编译器实测发现，backtask.md 回报）。
--
-- 根因：V388 给 ELEMENT_BOM_ITEM / ELEMENT_RECOVERY 两个节点声明了
-- discriminator = "characteristic = 'RECIPE'"，但 element_bom_item.characteristic 实际存的是
-- 一列与 material_bom_item.characteristic（真正的 RECIPE/ASSEMBLY/OUTSOURCED 三态枚举，
-- task-0720 落地）完全无关的数字编码（实测取值如 2001/2049/2050…，从未出现字符串 'RECIPE'）。
-- 现网手写基准视图 mc_view（COMP-0027 使用）对 element_bom_item 也从未加过 characteristic 过滤，
-- 只有 system_type/is_current/customer_no 三件套——与本次实测结论一致。
--
-- 症状：任何"材质元素"页签配置一旦真正预览/执行，WHERE ebi.characteristic = 'RECIPE' 恒为假，
-- 返回 0 行（AC-26 预期 2 行/闭包后 4 行，实测在去掉这条判别式后精确复现）。
-- 影响面：仅 semantic_node 表 2 行（ELEMENT_BOM_ITEM / ELEMENT_RECOVERY），discriminator 列。

UPDATE semantic_node
   SET discriminator = NULL, updated_at = now()
 WHERE node_key IN ('ELEMENT_BOM_ITEM', 'ELEMENT_RECOVERY')
   AND dialect = 'QUOTE';
