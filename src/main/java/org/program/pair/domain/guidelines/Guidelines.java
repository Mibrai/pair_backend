package org.program.pair.domain.guidelines;

import java.util.Objects;

/**
 * La règle qui décide s'il faut redemander l'acceptation.
 *
 * <p>Une égalité, pas un ordre. « La version acceptée est-elle celle en
 * vigueur ? » est la seule question, et elle évite d'avoir à savoir si 1.10
 * vient après 1.9 — comparaison que deux implémentations écriraient
 * différemment.
 *
 * <p>Vit dans sa propre classe parce que <b>deux endroits</b> la posent : le
 * profil privé, qui la porte au démarrage pour éviter un second appel réseau, et
 * l'endpoint dédié. Écrite deux fois, elle finirait par ne pas répondre pareil.
 */
public final class Guidelines {

    private Guidelines() {}

    /** Vrai s'il faut présenter les règles à cette personne. */
    public static boolean acceptanceRequired(String currentVersion, String acceptedVersion) {
        return !Objects.equals(currentVersion, acceptedVersion);
    }
}
