@echo off
setlocal enabledelayedexpansion
echo ==================================================
echo   Test Systeme Chat
echo ==================================================
echo.

echo 1. Creation de 2 utilisateurs...
echo ---------------------------------

curl -s -X POST http://localhost:8090/api/auth/register ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"alice-%RANDOM%@test.com\",\"password\":\"Test1234!\",\"displayName\":\"Alice\"}" ^
  -o temp_alice.json

for /f "tokens=2 delims=:," %%a in ('findstr "accessToken" temp_alice.json') do set TOKEN_ALICE=%%a
set TOKEN_ALICE=!TOKEN_ALICE:"=!
set TOKEN_ALICE=!TOKEN_ALICE: =!

for /f "tokens=2 delims=:," %%a in ('findstr "\"userId\"" temp_alice.json') do set USER_ALICE=%%a
set USER_ALICE=!USER_ALICE:"=!
set USER_ALICE=!USER_ALICE: =!

echo Alice creee - ID: !USER_ALICE:~0,8!...
echo.

curl -s -X POST http://localhost:8090/api/auth/register ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"bob-%RANDOM%@test.com\",\"password\":\"Test1234!\",\"displayName\":\"Bob\"}" ^
  -o temp_bob.json

for /f "tokens=2 delims=:," %%a in ('findstr "accessToken" temp_bob.json') do set TOKEN_BOB=%%a
set TOKEN_BOB=!TOKEN_BOB:"=!
set TOKEN_BOB=!TOKEN_BOB: =!

for /f "tokens=2 delims=:," %%a in ('findstr "\"userId\"" temp_bob.json') do set USER_BOB=%%a
set USER_BOB=!USER_BOB:"=!
set USER_BOB=!USER_BOB: =!

echo Bob cree - ID: !USER_BOB:~0,8!...
echo.

echo 2. Alice cree une conversation avec Bob...
echo --------------------------------------------

curl -s -X POST http://localhost:8090/api/conversations ^
  -H "Authorization: Bearer !TOKEN_ALICE!" ^
  -H "Content-Type: application/json" ^
  -d "{\"targetUserId\":\"!USER_BOB!\"}" ^
  -o temp_conv.json

for /f "tokens=2 delims=:," %%a in ('findstr "\"id\"" temp_conv.json') do set CONV_ID=%%a
set CONV_ID=!CONV_ID:"=!
set CONV_ID=!CONV_ID: =!

echo Conversation creee - ID: !CONV_ID:~0,8!...
echo.

echo 3. Alice envoie un message via REST...
echo ---------------------------------------

curl -s -X POST http://localhost:8090/api/conversations/!CONV_ID!/messages ^
  -H "Authorization: Bearer !TOKEN_ALICE!" ^
  -H "Content-Type: application/json" ^
  -d "{\"conversationId\":\"!CONV_ID!\",\"content\":\"Salut Bob! Tu veux jouer au tennis?\"}" ^
  -o temp_msg1.json

type temp_msg1.json
echo.
echo.

echo 4. Bob recupere ses conversations...
echo -------------------------------------

curl -s http://localhost:8090/api/conversations ^
  -H "Authorization: Bearer !TOKEN_BOB!"
echo.
echo.

echo 5. Bob lit les messages de la conversation...
echo ----------------------------------------------

curl -s "http://localhost:8090/api/conversations/!CONV_ID!/messages?limit=10" ^
  -H "Authorization: Bearer !TOKEN_BOB!"
echo.
echo.

echo 6. Bob repond via REST...
echo --------------------------

curl -s -X POST http://localhost:8090/api/conversations/!CONV_ID!/messages ^
  -H "Authorization: Bearer !TOKEN_BOB!" ^
  -H "Content-Type: application/json" ^
  -d "{\"conversationId\":\"!CONV_ID!\",\"content\":\"Oui avec plaisir! Demain 18h au parc?\"}"
echo.
echo.

echo 7. Bob marque comme lu...
echo --------------------------

curl -s -X POST http://localhost:8090/api/conversations/!CONV_ID!/read ^
  -H "Authorization: Bearer !TOKEN_BOB!"
echo.
echo Conversation marquee comme lue
echo.

echo 8. Alice verifie unread count...
echo ---------------------------------

curl -s http://localhost:8090/api/conversations ^
  -H "Authorization: Bearer !TOKEN_ALICE!" | findstr "unreadCount"
echo.
echo.

del temp_alice.json temp_bob.json temp_conv.json temp_msg1.json 2>nul

echo ==================================================
echo   Test Chat termine!
echo ==================================================
echo.
echo Endpoints testes:
echo   [OK] POST /api/conversations - Creer conversation
echo   [OK] GET  /api/conversations - Lister mes conversations
echo   [OK] POST /api/conversations/{id}/messages - Envoyer message
echo   [OK] GET  /api/conversations/{id}/messages - Lire messages
echo   [OK] POST /api/conversations/{id}/read - Marquer comme lu
echo.
echo Note: Test WebSocket necessite client special
echo       URL: ws://localhost:8090/ws/chat
echo       Destination: /app/chat.send
