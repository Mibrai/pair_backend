# Écran verrouillé : la relance de retour affiche encore l'heure limite, et Android n'est pas en visibilité privée

**Date :** 2026-09-15
**Module :** `tracabilite`
**Audit :** P-MS-10 étape 6 (lien P-BL-11)

> **Ce que fait l'app** (P-MS-10 étapes 1 à 5, `1f4dfbe`) : pour les types de veille et de sécurité,
> les bannières qu'elle compose ne portent ni lieu ni heure, les notifications locales Android sont
> en `NotificationVisibility.private`, et l'extension iOS pose un texte de remplacement sur l'écran
> verrouillé.
>
> **Ce qui vous appartient** : le texte des push que vous envoyez, et leur visibilité Android.

---

## 1. Relevé du 15/09/2026 (code serveur, `origin/master`)

- Vos textes de veille sans lieu sont bien livrés (`messages.properties:115-125`, merci).
- **Mais** `push.WATCH_RETURN_REMINDER.body=Confirme ton retour avant {0}, sinon ton contact sera
  prévenu.` (`messages.properties:116`, idem `messages_de.properties:118` et l'anglais) écrit
  **l'heure limite** sur l'écran verrouillé : l'heure à laquelle quelqu'un pense être rentré chez
  lui, lisible par qui tient le téléphone.
- `PushNotificationService.androidConfig` (`:329-349`) ne pose **aucune**
  `AndroidNotification.Visibility.PRIVATE` : sur un écran verrouillé Android, le contenu complet
  s'affiche.

## 2. La demande

1. Utiliser partout la variante sans heure (`push.WATCH_RETURN_REMINDER.bodyWithoutDeadline`
   existe déjà) pour la bannière — l'heure reste dans l'app, derrière le déverrouillage.
2. Poser `Visibility.PRIVATE` sur les types masqués côté app : `WATCH_RETURN_REMINDER`,
   `WATCH_ARRIVAL_PROMPT`, `WATCH_ARRIVAL_CONFIRMED`, `WATCH_GUARDIAN_ALERT`,
   `WATCH_LOST_ORGANIZER`, `GUARDIAN_CONSENT_REQUEST` (liste miroir de
   `NotificationType.masqueLeLieuSurEcranVerrouille`, `lib/models/notification_models.dart`).
3. Un test : aucun corps de ces types ne contient d'heure ni de lieu, en fr/en/de ; la config
   Android de ces types porte `PRIVATE`.

**Décision de l'utilisateur, 15/09/2026, pour information** : l'app **renonce** aux catégories iOS
`WATCH_RETURN` / `WATCH_ARRIVAL` (boutons d'action sur la notification). Vous pouvez continuer de
poser `MEETDO_TEMPLATE` sur toute push visible ; rien n'est à changer de votre côté pour cela.

## 3. Comment nous vérifierons

Relevé des `messages*.properties` et de `androidConfig`, puis une relance de retour réelle sur un
iPhone verrouillé (écriture : armement d'une veille de test, accord de l'utilisateur).
