package org.program.pair.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class HomeController {

    @GetMapping("/")
    @ResponseBody
    public String home() {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Pair API</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px; }
                    h1 { color: #007bff; }
                    .endpoint { background: #f8f9fa; padding: 10px; margin: 10px 0; border-radius: 4px; }
                    code { background: #e9ecef; padding: 2px 6px; border-radius: 3px; }
                </style>
            </head>
            <body>
                <h1>🔐 Pair API - Réseau Social d'Activités</h1>
                <p><strong>Statut:</strong> ✅ L'application fonctionne correctement</p>

                <h2>Endpoints d'authentification disponibles :</h2>

                <div class="endpoint">
                    <strong>POST /api/auth/register</strong><br>
                    Inscription d'un nouvel utilisateur<br>
                    <code>{"email":"user@example.com","password":"Test1234!","displayName":"John Doe"}</code>
                </div>

                <div class="endpoint">
                    <strong>POST /api/auth/login</strong><br>
                    Connexion<br>
                    <code>{"email":"user@example.com","password":"Test1234!"}</code>
                </div>

                <div class="endpoint">
                    <strong>POST /api/auth/refresh</strong><br>
                    Rafraîchir le token<br>
                    <code>{"refreshToken":"..."}</code>
                </div>

                <div class="endpoint">
                    <strong>GET /api/auth/verify-email?token=xxx</strong><br>
                    Vérifier l'email
                </div>

                <h2>Test rapide :</h2>
                <p>Utilisez curl ou Postman pour tester les endpoints ci-dessus.</p>

                <h3>Exemple avec curl :</h3>
                <pre style="background: #282c34; color: #abb2bf; padding: 15px; border-radius: 4px; overflow-x: auto;">
curl -X POST http://localhost:8090/api/auth/register \\
  -H "Content-Type: application/json" \\
  -d '{"email":"test@example.com","password":"Test1234!","displayName":"Test User"}'
                </pre>

                <p><em>Phase 1 implémentée : Authentification JWT ✅</em></p>
            </body>
            </html>
            """;
    }
}
