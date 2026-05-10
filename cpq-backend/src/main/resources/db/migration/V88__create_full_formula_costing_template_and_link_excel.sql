-- V88: 创建 COSTING 模板「核价-完整公式版 v1.0」并把 Excel 视图模板关联过来
--
-- 目的: 完成"端到端配置方案"阶段 3-4——把 V83-V87 的 Excel 视图模板和 V87 的基础数据
--       通过这个新核价模板串起来。用户在「创建报价单」抽屉里选这个新核价模板,
--       报价单的核价 tab 自动渲染 16 列(基础成本 + 4 加价 + CNY/USD 总成本)。
--
-- 三步操作:
--   1) INSERT 新模板 template (kind=COSTING, status=DRAFT, customer_id=NULL 通用,
--      category_id=默认分类, components=空让 admin 在 UI 拖拽)
--   2) UPDATE costing_template.linked_template_id 切到新模板
--   3) 新模板暂不发布(DRAFT), 让 admin 在 UI 里:
--        a. 配 9 个组件 (来料BOM/元素BOM/工序成本(合并)/模具工装/耗材包装/
--                       来料加工费/成品加工费/电镀/其他外加工)
--        b. 检查 Excel 视图渲染
--        c. PUBLISH 模板
--
-- 不做组件 SQL 的原因:
--   * 组件 BASIC_DATA 字段需要按"工序号 / 元素代码 / fee_type / cost_type" 这些
--     二维/三维谓词配 BNF 路径; 字段名又依赖 V82 重命名后的现状, SQL 维护风险大
--   * PathPickerDrawer 在 UI 里能列出当前所有 sheet 的真实字段名, admin 拖拽更准
--   * 组件设计还有几个未定决策(单元格命名/preset_rows 用还是不用), UI 边建边调更高效

-- ============================================================
-- 1) 创建新 COSTING 模板「核价-完整公式版 v1.0」
-- ============================================================
DO $$
DECLARE
    v_template_id UUID := gen_random_uuid();
    v_series_id   UUID := gen_random_uuid();
    v_default_cat UUID := 'b9576df8-24bf-42b7-b5a7-58bda3a023d2';
    v_excel_template_id UUID := '0cc0bb1d-840d-4d74-a89c-fc1b39ef4830';
BEGIN
    -- 防重复: 同名模板已存在则跳过 (检查所有版本)
    IF EXISTS (SELECT 1 FROM template WHERE name = '核价-完整公式版' AND template_kind = 'COSTING') THEN
        RAISE NOTICE 'V88: 「核价-完整公式版」模板已存在, 跳过插入';
        -- 仍尝试 link Excel 视图 (取最新版本)
        SELECT id INTO v_template_id FROM template
            WHERE name = '核价-完整公式版' AND template_kind = 'COSTING'
            ORDER BY created_at DESC LIMIT 1;
    ELSE
        INSERT INTO template (
            id, template_series_id, name, version, category, category_id, customer_id,
            description, usage_note, product_attributes, subtotal_formula,
            components_snapshot, status, template_kind,
            created_at, updated_at
        ) VALUES (
            v_template_id, v_series_id, '核价-完整公式版', 'v1.0', NULL, v_default_cat, NULL,
            '基于 data/template/核价系统计算公式和取值（示例）.xlsx 的全套计算公式构建。' ||
            '本模板对接 Excel 视图模板「核价Excel视图模板（完整公式版）」(V83-V87)——' ||
            '报价单选用此核价模板时, 核价 tab 自动渲染 16 列汇总(基础成本 + 4 加价 + CNY/USD 总成本)。' ||
            '本模板组件需 admin 在 UI 拖拽配置 9 个: 来料BOM / 元素BOM / 工序成本(合并 4 类) / ' ||
            '模具工装 / 耗材包装 / 来料加工费 / 成品加工费 / 电镀 / 其他外加工。' ||
            '详见 docs/templates/核价完整版-端到端配置方案.md。',
            '核价时 sales rep 在每个 tab 上录入或编辑该料号的成本数据; ' ||
            '加价比例 / 核价汇率自动从基础数据 BNF 引用, 不需要手动填。',
            '[]'::jsonb,
            '[]'::jsonb,
            NULL,                -- components_snapshot: 发布时由后端冻结组件状态填充
            'DRAFT',
            'COSTING',
            now(), now()
        );
        RAISE NOTICE 'V88: 已创建「核价-完整公式版 v1.0」模板 id=%, series=%', v_template_id, v_series_id;
    END IF;

    -- ============================================================
    -- 2) 把 Excel 视图模板关联到新模板
    -- ============================================================
    UPDATE costing_template
    SET linked_template_id = v_template_id,
        updated_at = now()
    WHERE id = v_excel_template_id;
    RAISE NOTICE 'V88: 已切换 Excel 视图模板的 linked_template_id 至新模板';

    -- 确保新模板的 Excel 视图设为默认 (linked_template_id 内最多一个 default)
    UPDATE costing_template
    SET is_default = true,
        updated_at = now()
    WHERE id = v_excel_template_id
      AND NOT EXISTS (
          SELECT 1 FROM costing_template ct2
          WHERE ct2.linked_template_id = v_template_id
            AND ct2.is_default = true
            AND ct2.id != v_excel_template_id
      );
    RAISE NOTICE 'V88: 新模板的默认 Excel 视图已锁定为「核价Excel视图模板（完整公式版）」';
END $$;
