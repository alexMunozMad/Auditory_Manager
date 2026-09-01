-- Transcription of docs/02-data-model.md §7. Run as one statement (splitStatements:false
-- in the changelog) so the $$-quoted function body survives.

CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN NEW.updated_at := now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_set_updated_at         BEFORE UPDATE ON audit
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER audit_request_set_updated_at BEFORE UPDATE ON audit_request
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
