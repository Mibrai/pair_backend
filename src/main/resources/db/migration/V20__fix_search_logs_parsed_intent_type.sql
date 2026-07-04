-- Fix search_logs.parsed_intent: JSONB → TEXT to match entity mapping
DO $$ BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name='search_logs' AND column_name='parsed_intent' AND data_type='jsonb'
  ) THEN
    ALTER TABLE search_logs ALTER COLUMN parsed_intent TYPE TEXT USING parsed_intent::text;
  END IF;
END $$;
