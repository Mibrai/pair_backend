# Dernier mot — le retour de remise e-mail est en place

**Date :** 2026-09-01
**Fait suite à :** `REPONSE_BACKEND_2026-09-01-BIS.md`, §4.4 et §6.

> La brique que nous vous devions encore est livrée et déployée : `alertDelivery`
> ne dit plus seulement « envoyé », il dit désormais **« arrivé »** ou
> **« rebondi »**. Avec un seul canal, c'était le filet qui manquait.
>
> **Rien à changer côté app.** Même champ, mêmes règles de lecture tolérante que
> vous aviez déjà posées. Deux valeurs de plus, c'est tout.

---

## Ce qui a changé

`GET /api/watches/{id}` porte toujours `alertDelivery`, mais il peut maintenant
prendre deux valeurs de plus, parce que nous recevons désormais les accusés de
remise de Resend :

| Valeur | Sens |
|---|---|
| `NONE` | aucune alerte n'est partie |
| `PENDING` | déposée, pas encore remise au fournisseur |
| `SENT` | acceptée par le fournisseur — mais on ne sait pas encore si elle est arrivée |
| **`DELIVERED`** | **arrivée** dans la boîte du contact |
| **`BOUNCED`** | **a rebondi**, ou marquée indésirable — le proche n'a jamais reçu |
| `FAILED` | l'envoi lui-même a échoué |

`BOUNCED` prime sur tout : c'est le seul état qui vous dise, noir sur blanc, que
le message n'est pas arrivé. C'est exactement le point de défaillance unique que
vous aviez identifié — une adresse en faute, et personne prévenu, sans que rien
ne le signale. Il se signale maintenant.

## Ce que vous n'avez pas à faire

- **Aucun changement d'API.** Le champ est le même ; il gagne deux valeurs. Votre
  lecture tolérante — une valeur inconnue ne casse rien — les absorbe déjà.
- **Aucun webhook à appeler de votre côté.** Tout se passe entre Resend et nous ;
  vous ne lisez que le résultat sur la veille.

## Une réserve honnête, à connaître

`DELIVERED` et `BOUNCED` n'apparaissent que si l'envoi d'e-mail réel est actif de
notre côté et le webhook déclaré chez le fournisseur. Tant que ce n'est pas le
cas, vous verrez `SENT` comme avant — jamais une fausse promesse de remise. Vous
pouvez donc traiter `DELIVERED`/`BOUNCED` dès aujourd'hui : ils n'arriveront que
quand ils seront vrais.

---

Cela referme le dernier point ouvert de votre retour du 01/09. Le module de veille
est complet côté serveur ; le seul chantier restant, le vrai canal SMS, attend
votre décision, et vous nous avez dit de ne pas le porter. Nous en restons là,
sauf de vos nouvelles.
