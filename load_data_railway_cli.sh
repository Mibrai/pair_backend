#!/bin/bash
# Script simplifié utilisant Railway CLI
# Prérequis: railway CLI installé et authentifié

echo "🚀 Chargement des données via Railway CLI..."
echo ""

# Se connecter au projet
railway link

# Exécuter le script SQL
railway run psql -f railway_seed_data.sql

echo ""
echo "✅ Terminé!"
