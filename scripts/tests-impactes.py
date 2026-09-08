#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Quelles classes de test faut-il rejouer pour les changements en cours ?

    python3 scripts/tests-impactes.py                  # liste et justifie
    python3 scripts/tests-impactes.py --run            # liste puis exécute
    python3 scripts/tests-impactes.py --base master    # par rapport à une référence

POURQUOI CE SCRIPT EXISTE

La suite entière coûte près d'une heure, et ce n'est pas le temps des
assertions : mesuré le 07/09, une classe d'intégration portant deux assertions
triviales prend 19,05 s — contexte Spring 8,9 s, démarrage du conteneur
Postgres ~8 s, Flyway 0,7 s, assertions ~0,1 s. Sur les 95 classes qui étendent
AbstractIntegrationTest, l'essentiel de l'heure est du remontage
d'infrastructure. Rejouer 24 classes au lieu de 146 fait tomber la boucle de
travail de 50 minutes à 4.

CE QUE CE SCRIPT NE REMPLACE PAS

Il rend plus rapide le fait de rejouer les tests **auxquels on a pensé**. Il ne
fait rien pour ceux auxquels personne n'a pensé. Le défaut des embeddings
corrigé le 07/09 en est la démonstration : il vivait dans un chemin que les
1175 tests n'exerçaient jamais, le modèle étant désactivé en test. Une
sélection, si fine soit-elle, ne pouvait pas le trouver.

Donc : boucle intérieure, oui. Garde-fou avant un push ou une fusion, non — la
suite entière garde ce rôle. Le script le rappelle à chaque exécution.

COMMENT LA SÉLECTION EST CALCULÉE

1. Les fichiers changés viennent de git (arbre de travail par défaut, ou
   comparaison avec --base).

2. Un fichier hors Java — migration, application*.properties, messages*,
   seed/data/*.json, pom.xml — déclenche la **suite entière**. Une migration
   s'applique dans chacun des 95 conteneurs de test, une propriété change le
   contexte de tous : prétendre cerner un périmètre là-dessus serait faux.

3. Pour les classes principales modifiées, on remonte le graphe **inverse** des
   dépendances : qui les référence, puis qui référence ceux-là, jusqu'au point
   fixe. C'est l'ensemble du code affecté.

4. Une classe de test est retenue si elle nomme une classe affectée — ou, et
   c'est le cas que la version naïve rate, si elle **appelle une route** d'un
   contrôleur affecté. Changer ActivityRepository affecte ActivityController,
   mais un test d'intégration qui fait webTestClient.get("/api/activities") ne
   nomme jamais le contrôleur. Les préfixes de @RequestMapping des contrôleurs
   affectés sont donc cherchés littéralement dans les sources de test.

5. Si la sélection dépasse --seuil (60 % par défaut) du total, le script dit de
   tout lancer : en dessous d'un certain écart, cibler ne fait plus gagner assez
   pour valoir le risque de rater quelque chose.

La détection de référence se fait par nom simple de classe. Le sens de l'erreur
est délibéré : un nom commun fait retenir **trop** de tests, jamais trop peu.

CE QUE ÇA DONNE VRAIMENT SUR CE DÉPÔT — mesuré le 07/09 sur sept commits réels,
à profondeur 1 :

    a1c4c02  vecteurs des seeders          81/148   54 %
    a7bba13  programme terminé             89/147   60 %
    34dba93  recherche sans accents       106/144   73 %
    1b52d98  séance commencée             115/143   80 %
    fe7365f  relances de cycle            migration → suite entière
    ad18424  recherche sans position      migration → suite entière
    cb68b9b  quitter un créneau           migration → suite entière

Trois commits sur sept touchent une migration ou les messages : la suite entière
de toute façon. Les quatre autres retiennent de 54 % à 80 %. **La sélection
statique ne paie pas sur ce dépôt**, et il faut le savoir avant de lui faire
confiance. La raison est structurelle : les lots ajoutent presque toujours une
migration, et une modification de dépôt ou de service est atteinte par la moitié
des tests d'intégration via HTTP. Augmenter la profondeur n'arrange rien — 85 %
à deux sauts, 89 % sans borne.

Le script garde donc un usage étroit mais réel : dire en une seconde **si** le
changement en cours est local. Quand il l'est — un DTO, un utilitaire pur, un
test seul — la sélection tombe à quelques classes et fait gagner l'heure. Quand
il ne l'est pas, il le dit au lieu de le masquer.

Le vrai gain est ailleurs, et il est chiffré plus haut : ce sont les 19 secondes
par classe. Un conteneur Postgres partagé et un cache de contexte Spring qui
fonctionne valent plus que n'importe quelle sélection, sans rien retirer de la
couverture.

AVEC --run

Les classes sont exécutées par lots (--lot, 12 par défaut) plutôt qu'en une
fois. Ce n'est pas cosmétique : sur ce poste, une exécution longue se fait tuer
faute de mémoire — une JVM par classe (reuseForks=false) plus un conteneur
Postgres par classe. Les lots rendent la mémoire entre chaque.
"""

import argparse
import os
import re
import subprocess
import sys
from collections import defaultdict

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_MAIN = "src/main/java"
SRC_TEST = "src/test/java"

# Un changement sur l'un de ces chemins touche tous les contextes de test.
GLOBAUX = (
    "src/main/resources/db/migration/",
    "src/main/resources/application",
    "src/main/resources/messages",
    "src/main/resources/seed/",
    "src/test/resources/",
    "pom.xml",
)

# Classes de test qui n'ont jamais de rapport surefire : base abstraite, et le
# test exclu par défaut via excludedGroups (il télécharge un modèle ONNX).
SANS_RAPPORT = {"AbstractIntegrationTest", "LocalEmbeddingServiceTest"}

IDENTIFIANT = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\b")
ROUTE_CLASSE = re.compile(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]+)"')


def git(*args):
    return subprocess.run(["git", *args], cwd=RACINE, capture_output=True,
                          text=True, check=False).stdout.splitlines()


def fichiers_changes(base):
    """Les chemins modifiés, relatifs à la racine du dépôt."""
    if base:
        chemins = git("diff", "--name-only", f"{base}...HEAD")
        # plus ce qui n'est pas encore commité, sinon on juge un arbre qu'on n'a pas
        chemins += git("diff", "--name-only", "HEAD")
        chemins += [l[3:] for l in git("status", "--porcelain") if l[:2] == "??"]
    else:
        chemins = []
        for ligne in git("status", "--porcelain"):
            chemins.append(ligne[3:].strip().strip('"'))
    return sorted({c for c in chemins if c})


def indexer_sources(sous_dossier):
    """simple_name -> chemin, pour tous les .java d'un arbre."""
    index = {}
    base = os.path.join(RACINE, sous_dossier)
    for dossier, _, fichiers in os.walk(base):
        for f in fichiers:
            if f.endswith(".java"):
                index[f[:-5]] = os.path.join(dossier, f)
    return index


def identifiants(chemin):
    try:
        with open(chemin, encoding="utf-8", errors="ignore") as fh:
            return set(IDENTIFIANT.findall(fh.read()))
    except OSError:
        return set()


def contenu(chemin):
    try:
        with open(chemin, encoding="utf-8", errors="ignore") as fh:
            return fh.read()
    except OSError:
        return ""


def construire_graphe(index_main):
    """dependants[X] = classes principales qui référencent X."""
    noms = set(index_main)
    dependants = defaultdict(set)
    for nom, chemin in index_main.items():
        for autre in identifiants(chemin) & noms:
            if autre != nom:
                dependants[autre].add(nom)
    return dependants


def cloture(depart, dependants, profondeur):
    """Ce qui dépend des classes de départ, jusqu'à `profondeur` sauts.

    La profondeur n'est pas un détail de réglage, c'est LE curseur de risque.
    Sans borne, la clôture d'un monolithe Spring converge vers tout le code :
    mesuré le 07/09, trois classes modifiées en atteignent 258, soit 89 % des
    tests — exact, mais sans intérêt. Borner, c'est accepter sciemment de ne pas
    rejouer les tests situés au-delà.
    """
    vus = set(depart)
    front = set(depart)
    for _ in range(profondeur):
        suivant = set()
        for courant in front:
            suivant |= dependants.get(courant, set()) - vus
        if not suivant:
            break
        vus |= suivant
        front = suivant
    return vus


def routes_des_controleurs(affectees, index_main):
    """Préfixes @RequestMapping des contrôleurs affectés."""
    routes = set()
    for nom in affectees:
        if not nom.endswith("Controller"):
            continue
        for route in ROUTE_CLASSE.findall(contenu(index_main[nom])):
            route = route.strip()
            if len(route) > 3:      # "/" ou "/a" ne discrimine rien
                routes.add(route)
    return routes


def selectionner(changes, index_main, index_test, dependants, profondeur):
    """Rend (classes retenues, motifs, global) — global = tout rejouer."""
    motifs = []

    declencheurs = [c for c in changes if c.startswith(GLOBAUX)]
    if declencheurs:
        motifs.append("changement à portée globale : " + ", ".join(declencheurs[:4])
                      + (" …" if len(declencheurs) > 4 else ""))
        return set(index_test) - SANS_RAPPORT, motifs, True

    mains_changees = {os.path.basename(c)[:-5] for c in changes
                      if c.startswith(SRC_MAIN) and c.endswith(".java")}
    tests_changes = {os.path.basename(c)[:-5] for c in changes
                     if c.startswith(SRC_TEST) and c.endswith(".java")}

    if not mains_changees and not tests_changes:
        return set(), ["aucun fichier Java modifié"], False

    retenues = set(tests_changes)
    if tests_changes:
        motifs.append(f"{len(tests_changes)} classe(s) de test modifiée(s) directement")

    affectees = cloture(mains_changees & set(index_main), dependants, profondeur)
    if affectees:
        indirectes = len(affectees) - len(mains_changees & set(index_main))
        motifs.append(f"{len(mains_changees)} classe(s) principale(s) modifiée(s), "
                      f"{indirectes} autre(s) en dépendent")

    routes = routes_des_controleurs(affectees, index_main)
    if routes:
        motifs.append(f"{len(routes)} route(s) de contrôleur affectée(s) : "
                      + ", ".join(sorted(routes)[:5]))

    par_nom, par_route = 0, 0
    for nom, chemin in index_test.items():
        if nom in SANS_RAPPORT or nom in retenues:
            continue
        if identifiants(chemin) & affectees:
            retenues.add(nom)
            par_nom += 1
        elif routes and any(r in contenu(chemin) for r in routes):
            retenues.add(nom)
            par_route += 1

    if par_nom:
        motifs.append(f"{par_nom} test(s) nomment une classe affectée")
    if par_route:
        motifs.append(f"{par_route} test(s) appellent une route affectée sans nommer "
                      "son contrôleur")

    return retenues, motifs, False


def executer(classes, lot, mvn_offline):
    """Lance Maven par lots, puis agrège les rapports surefire."""
    rapports = os.path.join(RACINE, "target", "surefire-reports")
    subprocess.run(["rm", "-rf", rapports], check=False)

    ordonnees = sorted(classes)
    for debut in range(0, len(ordonnees), lot):
        tranche = ordonnees[debut:debut + lot]
        numero = debut // lot + 1
        total = (len(ordonnees) + lot - 1) // lot
        print(f"\n─── lot {numero}/{total} : {len(tranche)} classes ───", flush=True)
        cmd = ["./mvnw", "-q"] + (["-o"] if mvn_offline else []) + [
            "test", "-Dtest=" + ",".join(tranche), "-DfailIfNoSpecifiedTests=false"]
        code = subprocess.run(cmd, cwd=RACINE).returncode
        if code != 0:
            print(f"lot {numero} en échec (code {code})", file=sys.stderr)

    return agreger(rapports)


def agreger(rapports):
    total = echecs = erreurs = ignores = 0
    rouges, vues = [], set()
    if os.path.isdir(rapports):
        for f in os.listdir(rapports):
            if not f.endswith(".xml"):
                continue
            tete = contenu(os.path.join(rapports, f))[:2000]
            nom = re.search(r'name="([^"]+)"', tete)
            chiffres = re.search(r'tests="(\d+)".*?errors="(\d+)"'
                                 r'.*?skipped="(\d+)".*?failures="(\d+)"', tete)
            if nom and chiffres:
                vues.add(nom.group(1).split(".")[-1])
                total += int(chiffres.group(1))
                erreurs += int(chiffres.group(2))
                ignores += int(chiffres.group(3))
                echecs += int(chiffres.group(4))
                if int(chiffres.group(2)) or int(chiffres.group(4)):
                    rouges.append(nom.group(1).split(".")[-1])
    print(f"\nclasses exécutées {len(vues)} | tests {total} | échecs {echecs} "
          f"| erreurs {erreurs} | ignorés {ignores}")
    if rouges:
        print("classes rouges : " + ", ".join(sorted(rouges)))
    return 1 if (echecs or erreurs) else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--base", help="référence de comparaison (ex. master). "
                                   "Sans elle, l'arbre de travail face à HEAD.")
    ap.add_argument("--run", action="store_true", help="exécuter la sélection")
    ap.add_argument("--lot", type=int, default=12, help="classes par lot (défaut 12)")
    ap.add_argument("--profondeur", type=int, default=1,
                    help="sauts de dépendance remontés (défaut 1 ; au-delà la "
                         "sélection sature, voir l'en-tête ; 0 = illimité)")
    ap.add_argument("--seuil", type=int, default=60,
                    help="pourcentage au-delà duquel tout rejouer (défaut 60)")
    ap.add_argument("--online", action="store_true",
                    help="autoriser Maven à aller sur le réseau (sinon -o)")
    args = ap.parse_args()

    changes = fichiers_changes(args.base)
    if not changes:
        print("Aucun changement détecté.")
        return 0

    index_main = indexer_sources(SRC_MAIN)
    index_test = indexer_sources(SRC_TEST)
    dependants = construire_graphe(index_main)

    prof = args.profondeur if args.profondeur > 0 else 10**6
    retenues, motifs, global_ = selectionner(changes, index_main, index_test, dependants, prof)
    total_tests = len(set(index_test) - SANS_RAPPORT)

    print(f"Fichiers changés : {len(changes)}")
    for m in motifs:
        print(f"  · {m}")

    if not retenues:
        print("\nAucune classe de test impactée.")
        return 0

    part = 100 * len(retenues) // max(total_tests, 1)
    print(f"\nSélection : {len(retenues)} / {total_tests} classes ({part} %)")

    if not global_ and part >= args.seuil:
        print(f"Au-delà du seuil de {args.seuil} % : autant tout rejouer, "
              "la sélection ne fait plus gagner assez.")

    print("\n" + " ".join(sorted(retenues)))
    print("\nRappel : ceci est une boucle de travail, pas un garde-fou. "
          "La suite entière reste ce qui autorise un push.")

    if args.run:
        return executer(retenues, args.lot, not args.online)
    print("\n(--run pour exécuter)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
