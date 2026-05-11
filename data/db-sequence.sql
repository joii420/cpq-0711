-- =================================================================
-- PostgreSQL sequences export from cpq_db (public schema)
-- Generated at: 2026-05-11T06:13:43.617Z
-- Total: 3 sequences
-- Use: psql -h <host> -U postgres -d cpq_db -f db-sequence.sql
-- =================================================================

-- Sequence: component_code_seq
CREATE SEQUENCE IF NOT EXISTS public.component_code_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    CACHE 1;
SELECT pg_catalog.setval('public.component_code_seq', 18, true);

-- Sequence: customer_code_seq
CREATE SEQUENCE IF NOT EXISTS public.customer_code_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    CACHE 1;
SELECT pg_catalog.setval('public.customer_code_seq', 1268, true);

-- Sequence: quotation_number_seq
CREATE SEQUENCE IF NOT EXISTS public.quotation_number_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 9223372036854775807
    CACHE 1;
SELECT pg_catalog.setval('public.quotation_number_seq', 1394, true);

