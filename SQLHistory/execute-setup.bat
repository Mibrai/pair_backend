@echo off
echo ========================================
echo   SQLHistory - Complete Database Setup
echo ========================================
echo.

set PGPASSWORD=Pair2026!
set PSQL="C:\Program Files\PostgreSQL\18\bin\psql.exe"
set DB_USER=pair_user
set DB_NAME=pair_db
set DB_HOST=localhost

echo Executing SETUP_COMPLETE.sql...
echo.
%PSQL% -h %DB_HOST% -U %DB_USER% -d %DB_NAME% -f SETUP_COMPLETE.sql

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo   SUCCESS! Database setup complete
    echo ========================================
    echo.
    echo Test with:
    echo   curl http://localhost:8090/api/categories
    echo   curl http://localhost:8090/api/activities
    echo.
) else (
    echo.
    echo ========================================
    echo   ERROR: Setup failed
    echo ========================================
    echo.
)

pause
