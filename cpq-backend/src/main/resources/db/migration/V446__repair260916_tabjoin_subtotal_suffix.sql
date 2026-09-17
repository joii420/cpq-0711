-- repair-260916 B-3 (AC-15): rewrite stored Excel TAB_JOIN_FORMULA text to the "(小计)" notation.
--
-- New text contract (问题说明 5.1): [tab.col] always means the row value; the column subtotal is
-- written [tab.col(小计)]. Legacy text used a bare [tab.col] for a subtotal-flagged column, so every
-- stored bare reference to a subtotal-flagged column is rewritten here (rules: 问题说明 5.4, identical
-- to com.cpq.component.service.TabJoinSubtotalSuffixRewriter used by the bundle importer):
--   * only columns with source_type = 'TAB_JOIN_FORMULA';
--   * per tabs[] entry: component id = tabKey before the first ':'; tabKey 'idx:...' is skipped;
--   * subtotal columns = component.fields[] names with is_subtotal = true;
--   * literal replace() of '[alias.col]' -> '[alias.col(小计)]' (no regex); '[alias.col(总计)]' untouched;
--   * a referenced component that does not exist -> the column is left unchanged + RAISE NOTICE;
--   * only the "expression" value changes; other keys and the column order are preserved.
-- Idempotent: after one pass the old literal no longer occurs, so a second pass rewrites 0 rows
-- (targets are only updated when the value actually differs).
-- Storage covered: component.excel_columns, template.excel_view_config (legacy array form and the
-- object form's column_overrides), template_component_snapshot.excel_columns,
-- customer_excel_template.excel_columns.
-- Rollback by hand: strip the "(小计)" suffix from the rewritten references (values listed in
-- 问题说明 5.4 for the dev database).
-- The work table is a session temp table dropped automatically at commit (no explicit DROP).

CREATE TEMP TABLE rp0916_work (
    tbl       text  NOT NULL,   -- target storage
    row_id    uuid  NOT NULL,
    arr       jsonb NOT NULL,   -- column array to rewrite
    base_cols jsonb,            -- template object form: base component columns (tabs / source_type fallback by col_key)
    new_arr   jsonb
) ON COMMIT DROP;

INSERT INTO rp0916_work (tbl, row_id, arr)
SELECT 'component', c.id, c.excel_columns
  FROM component c
 WHERE jsonb_typeof(c.excel_columns) = 'array'
   AND c.excel_columns @> '[{"source_type":"TAB_JOIN_FORMULA"}]';

INSERT INTO rp0916_work (tbl, row_id, arr)
SELECT 'template_component_snapshot', s.id, s.excel_columns
  FROM template_component_snapshot s
 WHERE jsonb_typeof(s.excel_columns) = 'array'
   AND s.excel_columns @> '[{"source_type":"TAB_JOIN_FORMULA"}]';

INSERT INTO rp0916_work (tbl, row_id, arr)
SELECT 'customer_excel_template', x.id, x.excel_columns
  FROM customer_excel_template x
 WHERE jsonb_typeof(x.excel_columns) = 'array'
   AND x.excel_columns @> '[{"source_type":"TAB_JOIN_FORMULA"}]';

-- template, legacy bare-array form
INSERT INTO rp0916_work (tbl, row_id, arr)
SELECT 'template_array', t.id, t.excel_view_config
  FROM template t
 WHERE jsonb_typeof(t.excel_view_config) = 'array'
   AND t.excel_view_config @> '[{"source_type":"TAB_JOIN_FORMULA"}]';

-- template, object form: only overrides that carry an expression can hold formula text
INSERT INTO rp0916_work (tbl, row_id, arr, base_cols)
SELECT 'template_overrides', t.id, t.excel_view_config -> 'column_overrides',
       COALESCE((SELECT ec.excel_columns FROM component ec
                  WHERE ec.id::text = t.excel_view_config ->> 'excel_component_id'
                    AND jsonb_typeof(ec.excel_columns) = 'array'), '[]'::jsonb)
  FROM template t
 WHERE jsonb_typeof(t.excel_view_config) = 'object'
   AND jsonb_typeof(t.excel_view_config -> 'column_overrides') = 'array'
   AND EXISTS (SELECT 1 FROM jsonb_array_elements(t.excel_view_config -> 'column_overrides') o
                WHERE o ? 'expression');

DO $$
DECLARE
    wr         record;
    col        jsonb;
    base_col   jsonb;
    tab        jsonb;
    sub_name   text;
    idx        int;
    v_arr      jsonb;
    expr       text;
    new_expr   text;
    src_type   text;
    tabs       jsonb;
    alias      text;
    tab_key    text;
    comp_id    text;
    comp_found boolean;
    unknown    boolean;
    changed    int := 0;
BEGIN
    FOR wr IN SELECT * FROM rp0916_work LOOP
        v_arr := wr.arr;
        FOR idx IN 0 .. jsonb_array_length(wr.arr) - 1 LOOP
            col := wr.arr -> idx;
            IF jsonb_typeof(col) <> 'object' OR jsonb_typeof(col -> 'expression') IS DISTINCT FROM 'string' THEN
                CONTINUE;
            END IF;
            base_col := NULL;
            IF wr.base_cols IS NOT NULL THEN
                SELECT b INTO base_col
                  FROM jsonb_array_elements(wr.base_cols) b
                 WHERE b ->> 'col_key' = col ->> 'col_key'
                 LIMIT 1;
            END IF;
            src_type := COALESCE(col ->> 'source_type', base_col ->> 'source_type');
            IF src_type IS DISTINCT FROM 'TAB_JOIN_FORMULA' THEN
                CONTINUE;
            END IF;
            tabs := COALESCE(col -> 'tabs', base_col -> 'tabs', '[]'::jsonb);
            IF jsonb_typeof(tabs) <> 'array' THEN
                CONTINUE;
            END IF;
            expr := col ->> 'expression';
            new_expr := expr;
            unknown := false;
            FOR tab IN SELECT * FROM jsonb_array_elements(tabs) LOOP
                alias   := tab ->> 'alias';
                tab_key := tab ->> 'tabKey';
                IF alias IS NULL OR tab_key IS NULL OR tab_key LIKE 'idx:%' THEN
                    CONTINUE;
                END IF;
                comp_id := split_part(tab_key, ':', 1);
                -- CASE guarantees the ::uuid cast only runs on a uuid-shaped id (AND does not short-circuit reliably)
                comp_found := CASE
                    WHEN comp_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                    THEN EXISTS (SELECT 1 FROM component c WHERE c.id = comp_id::uuid)
                    ELSE false
                END;
                IF NOT comp_found THEN
                    unknown := true;
                    RAISE NOTICE 'repair-260916: % row % col_key % references unknown component % (alias %); column left unchanged',
                        wr.tbl, wr.row_id, col ->> 'col_key', comp_id, alias;
                    EXIT;
                END IF;
                FOR sub_name IN
                    SELECT f ->> 'name'
                      FROM component c, jsonb_array_elements(c.fields) f
                     WHERE c.id = comp_id::uuid
                       AND jsonb_typeof(c.fields) = 'array'
                       AND (f ->> 'is_subtotal') = 'true'
                       AND COALESCE(f ->> 'name', '') <> ''
                LOOP
                    new_expr := replace(new_expr,
                                        '[' || alias || '.' || sub_name || ']',
                                        '[' || alias || '.' || sub_name || '(小计)]');
                END LOOP;
            END LOOP;
            IF NOT unknown AND new_expr IS DISTINCT FROM expr THEN
                v_arr := jsonb_set(v_arr, ARRAY[idx::text, 'expression'], to_jsonb(new_expr), false);
                RAISE NOTICE 'repair-260916: % row % col_key %: % -> %',
                    wr.tbl, wr.row_id, col ->> 'col_key', expr, new_expr;
            END IF;
        END LOOP;
        UPDATE rp0916_work SET new_arr = v_arr WHERE tbl = wr.tbl AND row_id = wr.row_id;
    END LOOP;

    UPDATE component c SET excel_columns = w.new_arr
      FROM rp0916_work w
     WHERE w.tbl = 'component' AND w.row_id = c.id AND w.new_arr IS DISTINCT FROM c.excel_columns;
    GET DIAGNOSTICS changed = ROW_COUNT;
    RAISE NOTICE 'repair-260916: component.excel_columns rows updated = %', changed;

    UPDATE template_component_snapshot s SET excel_columns = w.new_arr
      FROM rp0916_work w
     WHERE w.tbl = 'template_component_snapshot' AND w.row_id = s.id AND w.new_arr IS DISTINCT FROM s.excel_columns;
    GET DIAGNOSTICS changed = ROW_COUNT;
    RAISE NOTICE 'repair-260916: template_component_snapshot.excel_columns rows updated = %', changed;

    UPDATE customer_excel_template x SET excel_columns = w.new_arr
      FROM rp0916_work w
     WHERE w.tbl = 'customer_excel_template' AND w.row_id = x.id AND w.new_arr IS DISTINCT FROM x.excel_columns;
    GET DIAGNOSTICS changed = ROW_COUNT;
    RAISE NOTICE 'repair-260916: customer_excel_template.excel_columns rows updated = %', changed;

    UPDATE template t SET excel_view_config = w.new_arr
      FROM rp0916_work w
     WHERE w.tbl = 'template_array' AND w.row_id = t.id AND w.new_arr IS DISTINCT FROM t.excel_view_config;
    GET DIAGNOSTICS changed = ROW_COUNT;
    RAISE NOTICE 'repair-260916: template.excel_view_config (array form) rows updated = %', changed;

    UPDATE template t SET excel_view_config = jsonb_set(t.excel_view_config, '{column_overrides}', w.new_arr, false)
      FROM rp0916_work w
     WHERE w.tbl = 'template_overrides' AND w.row_id = t.id
       AND w.new_arr IS DISTINCT FROM (t.excel_view_config -> 'column_overrides');
    GET DIAGNOSTICS changed = ROW_COUNT;
    RAISE NOTICE 'repair-260916: template.excel_view_config.column_overrides rows updated = %', changed;
END $$;
