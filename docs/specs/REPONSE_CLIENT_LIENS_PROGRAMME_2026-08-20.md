# Réponse au point ⛔ 5 — le jeton, et ce qui manque pour l'ouvrir

> Réponse à `VERIFICATIONS_CLIENT_MOBILE_2026-08-20.md` §5, mesurée sur un
> appareil et sur vos routes le 20 août 2026 au soir.

---

## Votre question, et sa réponse courte

> « Accepter un jeton sur cet hôte, ou nous dire quelle forme d'adresse vous
> attendez ? »

**Nous acceptons le jeton, c'est livré.** `meetdo://programs/{jeton}` et
`https://lien.meetdo.fun/p/{jeton}` entrent tous deux par le même chemin. Un
segment en **UUID** reste l'identifiant interne et se route sans aller-retour ;
tout autre segment est traité comme un jeton public et passe par
`GET /public/programs/{jeton}`. La distinction est structurelle — vos
identifiants sont `format: uuid`, vos jetons font 22 caractères base62 sans
tiret — donc elle ne repose pas sur une longueur qui pourrait changer.

**Mais accepter le jeton ne suffit pas, et c'est l'objet de ce document.**

## Ce qui manque : la réponse publique n'expose aucun identifiant

Mesuré sur un vrai jeton, celui d'un programme de test :

```
GET https://lien.meetdo.fun/public/programs/AFaApCuEOVrCS6En00OoKI
200 application/json

title, description, activityName, categoryName, categoryColorRamp,
locationType, city, placeName, nextSessionAt, sessionCount, enrolledCount,
maxParticipants, organizerGivenName, organizerVerified, hasImage
```

Quinze champs, **aucun identifiant** — conforme à `PublicProgramView` tel que
`/v3/api-docs` le documente.

Or la fiche interne de l'app vit sur `/programs/{id}`. Sans identifiant dans la
réponse, le jeton se résout en une description qu'on ne peut afficher nulle
part : il n'y a pas d'adresse où aller. Le bouton « Ouvrir dans meetDo »
rouvre donc l'application — sans erreur, depuis nos corrections d'aujourd'hui —
et **la laisse où elle était**.

Nous préférons ce silence à l'ouverture d'une fiche au hasard, mais il n'est pas
satisfaisant : quelqu'un a touché un lien vers un programme précis.

### Le même trou vaut pour les créneaux

`PublicSlotView` ne porte pas davantage d'identifiant. Notre résolution de
créneau cherche `scheduleId`, `slotId`, `id`, `scheduleUuid` et rend `null`
quand elle ne trouve rien — ce qui est le cas aujourd'hui. Les liens de créneau,
livrés hier, souffrent donc du même défaut, **silencieusement** : ils ouvrent
l'app et n'arrivent nulle part. Nous ne l'avions pas vu parce que rien ne se
plaint.

## Ce que nous demandons

Un identifiant dans les deux réponses publiques :

| Route | Champ demandé |
|---|---|
| `GET /public/programs/{token}` | `programId` |
| `GET /public/slots/{token}` | `scheduleId` |

Notre lecture est déjà large — `programId`, `id`, `programUuid`, `program_id`
côté programme ; `scheduleId`, `slotId`, `id`, `scheduleUuid` côté créneau —
donc n'importe lequel de ces noms fonctionnera **sans que nous redéployions**.

### Pourquoi cela ne contredit pas votre garde-fou

Votre règle interdit l'UUID **dans l'URL**, et elle a raison : une adresse bâtie
sur la clé primaire s'énumère, et l'on remonte la base en incrémentant. Nous ne
composons d'ailleurs jamais d'URL publique à partir d'un identifiant.

Un identifiant dans le **corps** d'une réponse qu'on n'obtient qu'en présentant
un jeton valide ne se prête pas à cela : il n'est lisible que par qui détient
déjà le lien, c'est-à-dire par qui a déjà accès à tout ce que la page montre.

## Deux défauts trouvés chez nous en cherchant le vôtre

Nous les signalons parce que votre document présentait le point 5 comme une
question de routage : il l'était, mais chez nous.

1. **Le moteur Flutter doublait notre routeur.** Laissé à son défaut, il remet
   l'URI brute à `go_router`, qui cherche une route nommée
   `meetdo://programs/{jeton}` et affiche « Page not Found for location
   meetdo://… ». C'est ce que voyait l'utilisateur, et cela n'avait rien à voir
   avec votre livraison. Réglé par `FlutterDeepLinkingEnabled` à `false`.
2. **L'hôte `programs` concaténait son segment** dans `/programs/{x}` sans
   distinguer un identifiant d'un jeton. Réglé par le test d'UUID décrit plus
   haut.

## Un défaut mineur, mais qui nous a fait trébucher

`GET /api/programs/{id}/share-link` rend une `pageUrl` qui vaut
`…/public/programs/{jeton}` — la route **JSON**, pas la page. Collée telle
quelle dans un message, elle ouvre un navigateur sur du texte brut ; c'est ce
que nous avons livré pendant une heure avant de nous en apercevoir.

Nous composons désormais `/p/{jeton}` nous-mêmes et n'acceptons une URL du
serveur que si elle ne désigne pas une route de données. Mais un champ nommé
« URL de page » qui rend une route de données fera trébucher le prochain
client : soit `pageUrl` pointe sur `/p/{jeton}`, soit elle mériterait un autre
nom.

## Ce qui est livré de notre côté

- Un programme se partage en `https://lien.meetdo.fun/p/{jeton}`, cliquable
  dans toutes les messageries — c'était le défaut d'origine, un `meetdo://`
  n'étant linkifié par aucune d'elles.
- Le repli reste silencieux : pas organisateur (`404`), partage éteint (`403`)
  ou réseau coupé renvoient au `meetdo://` sans déranger personne.
- Les liens entrants, jeton comme UUID, sont routés — et attendent votre
  identifiant pour aboutir.
