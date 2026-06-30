@echo off
REM Script pour exécuter les tests MapVisibilityIntegrationTest
REM Prérequis: Docker Desktop doit être démarré

echo ==========================================
echo MapVisibilityIntegrationTest - Exécution
echo ==========================================
echo.

REM Vérifier que Docker est en cours d'exécution
echo Vérification de Docker...
docker ps >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ERREUR: Docker n'est pas en cours d'exécution.
    echo.
    echo Veuillez démarrer Docker Desktop et réessayer.
    exit /b 1
)

echo Docker est en cours d'exécution
echo.

REM Exécuter les tests
echo Exécution des tests MapVisibilityIntegrationTest...
echo.

mvn test -Dtest=MapVisibilityIntegrationTest

REM Capturer le résultat
set TEST_RESULT=%ERRORLEVEL%

echo.
echo ==========================================
if %TEST_RESULT% EQU 0 (
    echo TOUS LES TESTS SONT PASSES
    echo ==========================================
    echo.
    echo Le modèle de confiance est validé:
    echo   - Respect de la vie privée (locationPublic)
    echo   - Sécurité des comptes désactivés
    echo   - Filtrage géographique par rayon
    echo   - Floutage des positions (anti-stalking)
    echo.
) else (
    echo DES TESTS ONT ECHOUE
    echo ==========================================
    echo.
    echo BUGS DE SECURITE DETECTES
    echo.
    echo Veuillez consulter les logs ci-dessus pour identifier:
    echo   - Quels tests ont échoué
    echo   - Quelles assertions n'ont pas été respectées
    echo.
    echo Actions recommandées:
    echo   1. Vérifier MapService.getUsersOnMap()
    echo   2. Vérifier les filtres SQL (locationPublic, is_active)
    echo   3. Vérifier le floutage géographique (blurRadiusM)
    echo   4. Vérifier la recherche géographique (ST_DWithin)
    echo.
    echo NE PAS IGNORER CES ECHECS - Sécurité critique!
    echo.
)

exit /b %TEST_RESULT%
