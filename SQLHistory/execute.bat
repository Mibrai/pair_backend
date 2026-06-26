@echo off
echo === Executing SQLHistory scripts ===
echo.

set PGPASSWORD=Pair2026!
set PSQL="C:\Program Files\PostgreSQL\18\bin\psql.exe"
set DB_USER=pair_user
set DB_NAME=pair_db
set DB_HOST=localhost

echo 1. Creating tables...
%PSQL% -h %DB_HOST% -U %DB_USER% -d %DB_NAME% -f create-missing-tables.sql
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to create tables
    pause
    exit /b 1
)

echo.
echo 2. Seeding data...
%PSQL% -h %DB_HOST% -U %DB_USER% -d %DB_NAME% -f seed-activities.sql
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to seed data
    pause
    exit /b 1
)

echo.
echo === SUCCESS: All scripts executed ===
echo.
pause
