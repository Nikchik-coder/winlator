-- Create the games table
CREATE TABLE games (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title TEXT NOT NULL,
    description TEXT,
    thumbnail_url TEXT,
    download_url TEXT,
    config_preset JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL
);

-- Enable Row Level Security
ALTER TABLE games ENABLE ROW LEVEL SECURITY;

-- Create a policy that allows anyone to read games
CREATE POLICY "Allow public read access to games" ON games
    FOR SELECT
    USING (true);


-- ==========================================
-- ADDITIONS FOR PAYMENT & ACCESS CODES
-- ==========================================

-- Create access_codes table
CREATE TABLE access_codes (
    code TEXT PRIMARY KEY, -- The code the user enters in the app
    device_id TEXT,        -- Will be null initially, bound upon first app use
    telegram_user_id BIGINT, -- Tracks which TG user bought it
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE
);

-- Enable RLS for access codes
ALTER TABLE access_codes ENABLE ROW LEVEL SECURITY;

-- Allow the app to check if a code exists (Read access)
CREATE POLICY "App can read access codes" ON access_codes
    FOR SELECT USING (true);

-- SECURE ACTIVATION FUNCTION
-- We use a database function (RPC) instead of raw UPDATE policies to securely 
-- handle the device binding logic without exposing the table to malicious edits.
CREATE OR REPLACE FUNCTION activate_code(p_code TEXT, p_device_id TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER -- Runs with elevated privileges
AS $$
DECLARE
    v_existing_device TEXT;
BEGIN
    -- Get current device_id for the provided code
    SELECT device_id INTO v_existing_device FROM access_codes WHERE code = p_code;
    
    -- If code doesn't exist in the database, reject
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;
    
    -- If device_id is NULL, the code is fresh. Bind it to this device and accept.
    IF v_existing_device IS NULL THEN
        UPDATE access_codes 
        SET device_id = p_device_id, activated_at = NOW() 
        WHERE code = p_code;
        RETURN TRUE;
    END IF;
    
    -- If it's already bound to this exact device, accept (allows reinstall/clearing data on same phone)
    IF v_existing_device = p_device_id THEN
        RETURN TRUE;
    END IF;
    
    -- If bound to a DIFFERENT device, reject (prevents sharing)
    RETURN FALSE;
END;
$$;
