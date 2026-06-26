#!/bin/bash

echo "🔍 Recherche des processus Java..."
echo ""

# Trouver les processus Java
JAVA_PIDS=$(tasklist | grep "java.exe" | awk '{print $2}')

if [ -z "$JAVA_PIDS" ]; then
    echo "✅ Aucun processus Java en cours"
    exit 0
fi

echo "Processus Java trouvés:"
tasklist | grep "java.exe"
echo ""

# Trouver celui qui utilise le port 8090
PORT_PID=$(netstat -ano | findstr ":8090" | awk '{print $5}' | head -1)

if [ ! -z "$PORT_PID" ]; then
    echo "🎯 Application Spring Boot trouvée (PID: $PORT_PID)"
    echo "🛑 Arrêt de l'application..."
    taskkill //F //PID $PORT_PID
    echo "✅ Application arrêtée"
else
    echo "⚠️  Aucune application sur le port 8090"
    echo "Voulez-vous arrêter tous les processus Java? (y/n)"
fi
