#!/bin/bash
# Script pour charger les données de test sur Railway
# 
# Usage: ./load_railway_data.sh <DATABASE_URL>
# 
# Le DATABASE_URL doit être au format:
# postgresql://user:password@host:port/database
#
# Pour obtenir l'URL depuis Railway:
# railway variables | grep DATABASE_URL
# ou depuis le dashboard Railway

if [ -z "$1" ]; then
    echo "❌ Erreur: DATABASE_URL manquant"
    echo ""
    echo "Usage: $0 <DATABASE_URL>"
    echo ""
    echo "Exemple:"
    echo "  $0 'postgresql://user:pass@host:5432/db'"
    echo ""
    echo "Pour obtenir l'URL:"
    echo "  1. Allez sur railway.app"
    echo "  2. Sélectionnez votre projet"
    echo "  3. Variables > DATABASE_URL"
    exit 1
fi

DATABASE_URL=$1

echo "🔄 Chargement des données de test sur Railway..."
echo ""

psql "$DATABASE_URL" -f railway_seed_data.sql

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Données chargées avec succès!"
    echo ""
    echo "📊 Résumé des données insérées:"
    echo "  • 10 utilisateurs (users)"
    echo "  • 10 catégories (categories)"
    echo "  • 10 activités (activities)"
    echo "  • 10 activités utilisateur (user_activities)"
    echo "  • 10 programmes (programs)"
    echo "  • 10 schedules avec localisations"
    echo "  • 10 médias (program_media)"
    echo "  • 10 conversations"
    echo "  • 20 membres de conversation"
    echo "  • 10 messages"
    echo ""
    echo "🔐 Credentials de test:"
    echo "  Email: alice@pair.test (ou bob@, claire@, david@, emma@, frank@, grace@, hugo@, isabelle@, julien@)"
    echo "  Password: Test1234!"
else
    echo ""
    echo "❌ Erreur lors du chargement des données"
    exit 1
fi
