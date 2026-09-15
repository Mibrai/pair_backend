# Une description de programme effacée s'enregistre `""`, et la page publique affiche un paragraphe vide

**Date :** 2026-09-15
**Module :** `programmes`
**Audit :** signalement de la vérification d'audit mobile du 14/09/2026 (jamais écrit jusqu'ici)

> **Ce que fait l'app** : pour effacer une description, elle envoie `description: ""` — seul moyen,
> puisque la mise à jour ignore `null`.
>
> **Ce qui vous appartient** : ce qu'une valeur vide devient.

---

## 1. Relevé du 15/09/2026 (code serveur, production `0c3fb50`)

- `ProgramService.updateProgram` appelle `sanitizer.sanitize("")` et **stocke une chaîne vide**.
- `public-program.html:77` teste `th:if="${program.description}"`, vrai pour une chaîne vide : la page
  publique affiche un paragraphe `.bienvenue` vide.

## 2. La demande

1. Au nettoyage, **ramener une description vide ou blanche à `null`**, à la création comme au `PUT`,
   en gardant la règle « clé absente ou `null` = inchangé » (même convention que `welcomeNote` et
   `city` depuis le 14/09).
2. Une migration pour l'existant : `UPDATE programs SET description = NULL WHERE btrim(description) = ''`.
3. À défaut, tester `!#strings.isEmpty(program.description)` dans le gabarit.

## 3. Comment nous vérifierons

Sur un programme de test (écriture soumise à l'accord de l'utilisateur) : description effacée depuis
l'app → `GET /programs/{id}` rend `description: null`, et la page `/p/<jeton>` n'a plus de paragraphe vide.
