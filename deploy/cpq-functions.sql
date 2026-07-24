--
-- CPQ 函数定义（兜底文件）
-- 仅当主脚本 cpq-init-navicat.sql 在末尾的函数区失败时，单独执行本文件。
-- 库内无任何对象依赖这 3 个函数，可在建库完成后任意时刻补跑。
--
SET search_path = public;

CREATE FUNCTION public.current_part_version(p_customer_product_no text, p_hf_part_no text) RETURNS integer
    LANGUAGE plpgsql STABLE
    AS 'DECLARE
    v_ver INT;
BEGIN
    IF p_customer_product_no IS NULL OR p_hf_part_no IS NULL THEN
        RETURN 2000;
    END IF;
    SELECT current_version INTO v_ver
    FROM mat_customer_part_mapping
    WHERE customer_product_no = p_customer_product_no
      AND hf_part_no = p_hf_part_no
    LIMIT 1;
    RETURN COALESCE(v_ver, 2000);
END;';

COMMENT ON FUNCTION public.current_part_version(p_customer_product_no text, p_hf_part_no text) IS '料号版本管理: 给定 (customer_product_no, hf_part_no) 返回当前激活版本号; orphan/未注册返回 2000';

CREATE FUNCTION public.get_bom_components(p_material_no text) RETURNS TABLE(js integer, hf_part_no text, material_no text, component_no text)
    LANGUAGE sql STABLE
    AS 'WITH RECURSIVE bom_tree AS (
        SELECT 1 js, material_no dj, mbi.*
        FROM material_bom_item mbi
        WHERE mbi.material_no = p_material_no
          AND mbi.customer_no = ''_GLOBAL_''
        UNION ALL
        SELECT js+1 js, dj, child.*
        FROM material_bom_item child
        INNER JOIN bom_tree parent
            ON  child.material_no = parent.component_no
           AND child.customer_no = parent.customer_no
    )
    SELECT js, dj hf_part_no, material_no, component_no
    FROM bom_tree;';

CREATE FUNCTION public.get_bom_components(p_material_no text, p_customer_no text) RETURNS TABLE(component_no text)
    LANGUAGE sql STABLE
    AS 'WITH RECURSIVE bom_tree AS (
        SELECT mbi.*
        FROM material_bom_item mbi
        WHERE mbi.material_no = p_material_no
          AND mbi.customer_no = p_customer_no
        UNION ALL
        SELECT child.*
        FROM material_bom_item child
        INNER JOIN bom_tree parent
            ON child.component_no = parent.material_no
           AND child.customer_no = parent.customer_no
    )
    SELECT distinct bom_tree.component_no
    FROM bom_tree;';


