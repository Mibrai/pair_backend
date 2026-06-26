@echo off
echo ================================================
echo  Test Profil Utilisateur
echo ================================================
echo.

echo 1. Inscription utilisateur...
curl -X POST http://localhost:8090/api/auth/register ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"profile.test@example.com\",\"password\":\"Test1234!\",\"displayName\":\"Profile Test\"}" ^
  -o temp_register.json
echo.

echo 2. Extraction du token...
for /f "tokens=2 delims=:," %%a in ('findstr "accessToken" temp_register.json') do set TOKEN=%%a
set TOKEN=%TOKEN:"=%
set TOKEN=%TOKEN: =%
echo Token: %TOKEN:~0,20%...
echo.

echo 3. Recuperer mon profil...
curl http://localhost:8090/api/users/me ^
  -H "Authorization: Bearer %TOKEN%"
echo.
echo.

echo 4. Mettre a jour bio...
curl -X PUT http://localhost:8090/api/users/me ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"bio\":\"Passionne de sport et de rencontres!\"}"
echo.
echo.

echo 5. Mettre a jour localisation Paris...
curl -X PUT http://localhost:8090/api/users/me/location ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"lat\":48.8566,\"lng\":2.3522}"
echo.
echo.

echo 6. Verifier profil avec localisation...
curl http://localhost:8090/api/users/me ^
  -H "Authorization: Bearer %TOKEN%"
echo.
echo.

del temp_register.json

echo ================================================
echo  Test termine!
echo ================================================
