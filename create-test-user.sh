#!/bin/bash

echo "Création d'un utilisateur de test..."

curl -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "Test1234!",
    "displayName": "Test User"
  }'

echo -e "\n\n✅ Utilisateur créé! Vous pouvez maintenant vous connecter avec:"
echo "Email: test@example.com"
echo "Password: Test1234!"
