-- Migrate legacy VERIFIED value to EMAIL_VERIFIED to match current VerificationStatus enum
UPDATE users
SET verification_status = 'EMAIL_VERIFIED'
WHERE verification_status = 'VERIFIED';
