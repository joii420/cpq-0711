BEGIN;
DO $$ BEGIN
 IF current_database() IS NULL OR current_setting('server_version_num')::int < 160000 THEN RAISE EXCEPTION 'PostgreSQL 16+ required'; END IF;
 IF to_regclass('public.template') IS NULL OR to_regclass('public.customer') IS NULL THEN RAISE EXCEPTION 'template/customer tables required'; END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='template' AND column_name='customer_id') THEN RAISE EXCEPTION 'template.customer_id required'; END IF;
END $$;
CREATE TABLE IF NOT EXISTS template_customer (template_id UUID NOT NULL REFERENCES template(id), customer_id UUID NOT NULL REFERENCES customer(id), PRIMARY KEY(template_id, customer_id));
CREATE INDEX IF NOT EXISTS idx_template_customer_customer ON template_customer(customer_id, template_id);
CREATE INDEX IF NOT EXISTS idx_template_customer_template ON template_customer(template_id, customer_id);
DO $$ DECLARE pk_count integer; fk_count integer; BEGIN
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='template_customer' AND column_name='template_id' AND data_type='uuid' AND is_nullable='NO') THEN RAISE EXCEPTION 'template_customer.template_id UUID NOT NULL required'; END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='template_customer' AND column_name='customer_id' AND data_type='uuid' AND is_nullable='NO') THEN RAISE EXCEPTION 'template_customer.customer_id UUID NOT NULL required'; END IF;
 SELECT count(*) INTO pk_count FROM pg_constraint WHERE conrelid='public.template_customer'::regclass AND contype='p' AND pg_get_constraintdef(oid) ILIKE '%template_id%customer_id%';
 IF pk_count <> 1 THEN RAISE EXCEPTION 'template_customer composite primary key (template_id, customer_id) required'; END IF;
 SELECT count(*) INTO fk_count FROM pg_constraint WHERE conrelid='public.template_customer'::regclass AND contype='f';
 IF fk_count < 2 THEN RAISE EXCEPTION 'template_customer foreign keys to template/customer required'; END IF;
 IF to_regclass('public.idx_template_customer_customer') IS NULL OR to_regclass('public.idx_template_customer_template') IS NULL THEN RAISE EXCEPTION 'template_customer indexes required'; END IF;
END $$;
INSERT INTO template_customer(template_id, customer_id) SELECT id, customer_id FROM template WHERE customer_id IS NOT NULL ON CONFLICT DO NOTHING;
DO $$ DECLARE old_count bigint; new_count bigint; BEGIN SELECT count(*) INTO old_count FROM template WHERE customer_id IS NOT NULL; SELECT count(*) INTO new_count FROM template_customer; IF new_count < old_count THEN RAISE EXCEPTION 'backfill count mismatch'; END IF; IF EXISTS (SELECT 1 FROM template t WHERE t.customer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM template_customer tc WHERE tc.template_id=t.id AND tc.customer_id=t.customer_id)) THEN RAISE EXCEPTION 'legacy template customer rows missing'; END IF; IF EXISTS (SELECT 1 FROM template_customer tc LEFT JOIN template t ON t.id=tc.template_id LEFT JOIN customer c ON c.id=tc.customer_id WHERE t.id IS NULL OR c.id IS NULL) THEN RAISE EXCEPTION 'orphan rows'; END IF; RAISE NOTICE 'template_customer postcheck old_nonnull=%, associations=%',old_count,new_count; END $$;
COMMIT;
