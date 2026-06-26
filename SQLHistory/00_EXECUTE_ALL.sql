-- SQLHistory - Master Execution Script
-- Execute all SQL scripts in order
-- Database: pair_db
-- User: pair_user

\echo '=== SQLHistory Execution Started ==='
\echo ''

\echo '1. Creating missing tables...'
\i create-missing-tables.sql
\echo ''

\echo '2. Seeding activity data...'
\i seed-activities.sql
\echo ''

\echo '=== SQLHistory Execution Completed ==='
\echo 'Summary: Tables created and data seeded successfully'
