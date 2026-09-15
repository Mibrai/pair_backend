# Réponse du 15/09 — la relance de retour ne porte plus l'heure limite, et Android garde ces push privées

**Date :** 2026-09-15
**Module :** `tracabilite`
**Fait suite à :** `PROMPT_BACKEND_2026-09-15.md`
**Audit :** P-MS-10 étape 6

> **Votre relevé est exact, et les trois points sont livrés.**
>
> - La relance de retour dit « Confirme ton retour, sinon ton contact sera prévenu », **toujours**,
>   sans l'heure limite, en fr/en/de.
> - Les six types de veille et de consentement partent en **`Visibility.PRIVATE`** sur Android.
> - Un test vérifie les deux.

---

## 1. Le texte

`push.WATCH_RETURN_REMINDER.body`, la variante avec `{0}` pour l'heure, est **retirée** des trois
catalogues. Le corps est désormais toujours `bodyWithoutDeadline` :

| Langue | Corps |
|---|---|
| fr | Confirme ton retour, sinon ton contact sera prévenu. |
| en | Confirm your return, or your contact will be alerted. |
| de | Bestätigen Sie Ihre Rückkehr, sonst wird Ihr Kontakt benachrichtigt. |

La charge garde `deadlineAt` dans ses **données** : l'app en a besoin pour l'afficher derrière le
déverrouillage, et rien ne l'écrit dans la bannière.

## 2. La visibilité Android

`PushNotificationService.androidConfig` pose `AndroidNotification.Visibility.PRIVATE` sur les types
de `NotificationType.masqueSurEcranVerrouille()`, liste miroir de la vôtre :

`WATCH_RETURN_REMINDER`, `WATCH_ARRIVAL_PROMPT`, `WATCH_ARRIVAL_CONFIRMED`, `WATCH_GUARDIAN_ALERT`,
`WATCH_LOST_ORGANIZER`, `GUARDIAN_CONSENT_REQUEST`.

Les autres types gardent le réglage du canal, comme avant.

**Un point à connaître** : `WATCH_LOST_ORGANIZER` porte le **prénom** de la personne attendue
(« Camille n'a pas confirmé son arrivée »), parce que l'organisateur doit savoir qui chercher. Ce n'est
ni une heure ni un lieu, donc le texte ne change pas. Avec `PRIVATE`, il ne se lit plus sur un écran
Android verrouillé. Côté iOS, c'est votre texte de remplacement qui s'applique.

## 3. Les catégories iOS

Décision notée : vous renoncez à `WATCH_RETURN` et `WATCH_ARRIVAL`. Rien ne change de notre côté.
`MEETDO_TEMPLATE` reste posé sur les push visibles.

## 4. Vérification

**Test** (`PushNotificationServiceTest.lesTypesMasques_neDoiventPorterNiHeureNiLieu_etRestentPrivesSurAndroid`) :

- pour les six types et les trois langues, titre et corps composés avec une charge qui porte
  `deadlineAt`, `sessionAt`, un nom de lieu et une adresse : **aucune heure** (motif `HH:MM`, `HHhMM`
  ou `HH.MM`) et **aucun lieu** ;
- ces six types rendent `PRIVATE`, tous les autres types rien.

Le test existant de la relance de retour attend désormais la phrase sans heure dans les trois langues.

**Votre protocole du §3** : relevé des catalogues et de `androidConfig`, puis une relance réelle sur un
iPhone verrouillé, avec l'accord de l'utilisateur pour armer une veille de test.
