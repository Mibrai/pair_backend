#!/usr/bin/env python3
"""
Script pour charger les données de test sur Railway PostgreSQL
Usage: python load_railway_data.py <DATABASE_URL>

Le DATABASE_URL doit être au format:
postgresql://user:password@host:port/database
"""

import sys
import psycopg2
from urllib.parse import urlparse

def load_data(database_url):
    """Load test data from SQL file into Railway database"""

    print("🔄 Chargement des données de test sur Railway...")
    print()

    try:
        # Parse DATABASE_URL
        result = urlparse(database_url)
        username = result.username
        password = result.password
        database = result.path[1:]  # Remove leading slash
        hostname = result.hostname
        port = result.port or 5432

        # Connect to database
        print(f"📡 Connexion à {hostname}:{port}/{database}...")
        conn = psycopg2.connect(
            database=database,
            user=username,
            password=password,
            host=hostname,
            port=port
        )
        conn.autocommit = True
        cursor = conn.cursor()

        # Read SQL file
        print("📄 Lecture du fichier SQL...")
        with open('railway_seed_data.sql', 'r', encoding='utf-8') as f:
            sql_content = f.read()

        # Execute SQL
        print("⚙️  Exécution du script SQL...")
        cursor.execute(sql_content)

        # Summary
        print()
        print("✅ Données chargées avec succès!")
        print()
        print("📊 Résumé des données insérées:")
        print("  • 10 utilisateurs (users)")
        print("  • 10 catégories (categories)")
        print("  • 10 activités (activities)")
        print("  • 10 activités utilisateur (user_activities)")
        print("  • 10 programmes (programs)")
        print("  • 10 schedules avec localisations")
        print("  • 10 médias (program_media)")
        print("  • 10 conversations")
        print("  • 20 membres de conversation")
        print("  • 10 messages")
        print()
        print("🔐 Credentials de test:")
        print("  Email: alice@pair.test")
        print("         bob@pair.test")
        print("         claire@pair.test")
        print("         david@pair.test")
        print("         emma@pair.test")
        print("         frank@pair.test")
        print("         grace@pair.test")
        print("         hugo@pair.test")
        print("         isabelle@pair.test")
        print("         julien@pair.test")
        print("  Password: Test1234!")
        print()

        # Verify data
        cursor.execute("SELECT COUNT(*) FROM users")
        user_count = cursor.fetchone()[0]
        cursor.execute("SELECT COUNT(*) FROM schedules WHERE location IS NOT NULL")
        schedule_count = cursor.fetchone()[0]

        print(f"✓ {user_count} utilisateurs dans la base")
        print(f"✓ {schedule_count} schedules avec localisation")

        cursor.close()
        conn.close()

        return 0

    except FileNotFoundError:
        print("❌ Erreur: Fichier railway_seed_data.sql introuvable")
        print("   Assurez-vous d'exécuter le script depuis le répertoire pair_backend")
        return 1

    except psycopg2.Error as e:
        print(f"❌ Erreur PostgreSQL: {e}")
        return 1

    except Exception as e:
        print(f"❌ Erreur: {e}")
        return 1

def main():
    if len(sys.argv) != 2:
        print("❌ Erreur: DATABASE_URL manquant")
        print()
        print(f"Usage: {sys.argv[0]} <DATABASE_URL>")
        print()
        print("Exemple:")
        print(f"  {sys.argv[0]} 'postgresql://user:pass@host:5432/db'")
        print()
        print("Pour obtenir l'URL:")
        print("  1. Allez sur railway.app")
        print("  2. Sélectionnez votre projet")
        print("  3. Variables > DATABASE_URL")
        return 1

    database_url = sys.argv[1]
    return load_data(database_url)

if __name__ == '__main__':
    sys.exit(main())
