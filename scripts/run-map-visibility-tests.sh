#!/bin/bash

# Script pour exécuter les tests MapVisibilityIntegrationTest
# Prérequis: Docker Desktop doit être démarré

set -e

echo "=========================================="
echo "MapVisibilityIntegrationTest - Exécution"
echo "=========================================="
echo ""

# Vérifier que Docker est en cours d'exécution
echo "⏳ Vérification de Docker..."
if ! docker ps > /dev/null 2>&1; then
    echo "❌ ERREUR: Docker n'est pas en cours d'exécution."
    echo ""
    echo "Veuillez démarrer Docker Desktop et réessayer."
    exit 1
fi

echo "✅ Docker est en cours d'exécution"
echo ""

# Exécuter les tests
echo "⏳ Exécution des tests MapVisibilityIntegrationTest..."
echo ""

mvn test -Dtest=MapVisibilityIntegrationTest

# Capturer le résultat
TEST_RESULT=$?

echo ""
echo "=========================================="
if [ $TEST_RESULT -eq 0 ]; then
    echo "✅ TOUS LES TESTS SONT PASSÉS"
    echo "=========================================="
    echo ""
    echo "Le modèle de confiance est validé:"
    echo "  ✅ Respect de la vie privée (locationPublic)"
    echo "  ✅ Sécurité des comptes désactivés"
    echo "  ✅ Filtrage géographique par rayon"
    echo "  ✅ Floutage des positions (anti-stalking)"
    echo ""
else
    echo "❌ DES TESTS ONT ÉCHOUÉ"
    echo "=========================================="
    echo ""
    echo "⚠️  BUGS DE SÉCURITÉ DÉTECTÉS ⚠️"
    echo ""
    echo "Veuillez consulter les logs ci-dessus pour identifier:"
    echo "  - Quels tests ont échoué"
    echo "  - Quelles assertions n'ont pas été respectées"
    echo ""
    echo "Actions recommandées:"
    echo "  1. Vérifier MapService.getUsersOnMap()"
    echo "  2. Vérifier les filtres SQL (locationPublic, is_active)"
    echo "  3. Vérifier le floutage géographique (blurRadiusM)"
    echo "  4. Vérifier la recherche géographique (ST_DWithin)"
    echo ""
    echo "NE PAS IGNORER CES ÉCHECS - Sécurité critique!"
    echo ""
fi

exit $TEST_RESULT
