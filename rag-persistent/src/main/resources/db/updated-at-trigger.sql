CREATE OR REPLACE FUNCTION public.kb_chunk_set_updated_at()
RETURNS trigger LANGUAGE plpgsql AS '
BEGIN
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END;
';

DROP TRIGGER IF EXISTS trg_kb_chunk_updated_at ON public.kb_chunk;
CREATE TRIGGER trg_kb_chunk_updated_at
    BEFORE UPDATE ON public.kb_chunk
    FOR EACH ROW EXECUTE FUNCTION public.kb_chunk_set_updated_at();
