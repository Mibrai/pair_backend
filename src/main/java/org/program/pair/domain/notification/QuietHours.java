package org.program.pair.domain.notification;

import java.time.Instant;
import java.time.ZoneId;

/**
 * La fenêtre pendant laquelle on ne sonne pas.
 *
 * <p>Type valeur, sans dépendance ni état — au même titre que {@code GivenName},
 * {@code Guidelines} ou {@code ReliabilitySignal}. Toute la difficulté du lot
 * tient dans une ligne de comparaison, et elle mérite d'être testable sans
 * conteneur.
 *
 * <p><b>La fenêtre traverse minuit, et c'est le cas normal.</b> « 22 h – 7 h »
 * n'est pas un intervalle croissant : écrit naïvement comme
 * {@code start <= h && h < end}, il ne contient rien du tout, et le réglage le
 * plus courant du produit n'aurait tout simplement aucun effet — sans erreur,
 * sans trace, et invisible à tout test qui n'essaierait qu'une fenêtre diurne.
 */
public final class QuietHours {

    private final Integer start;
    private final Integer end;

    private QuietHours(Integer start, Integer end) {
        this.start = start;
        this.end = end;
    }

    /**
     * Construit la fenêtre, ou l'absence de fenêtre.
     *
     * <p>Une moitié de réglage — un début sans fin — est traitée comme aucun
     * réglage. La base l'interdit déjà ; ici on refuse simplement de deviner ce
     * qu'une donnée incomplète voulait dire, plutôt que de faire taire des
     * notifications sur une intention supposée.
     */
    public static QuietHours of(Integer start, Integer end) {
        if (start == null || end == null || start.equals(end)) {
            return new QuietHours(null, null);
        }
        return new QuietHours(start, end);
    }

    /** Vrai si aucune fenêtre n'est demandée. */
    public boolean disabled() {
        return start == null;
    }

    /**
     * Vrai si cet instant tombe dans le silence, lu dans ce fuseau.
     *
     * <p>La borne de début est incluse et celle de fin exclue : « 22 h – 7 h »
     * fait taire à 22 h 00 et laisse passer à 7 h 00. C'est la lecture que les
     * gens font d'un réglage écrit ainsi.
     */
    public boolean silences(Instant when, ZoneId zone) {
        if (disabled()) {
            return false;
        }
        int hour = when.atZone(zone).getHour();
        // Deux formes, selon que la fenêtre traverse minuit ou non. C'est la
        // seule subtilité de cette classe, et la cause d'un silence qui ne
        // fonctionne jamais si on l'oublie.
        return start < end
            ? hour >= start && hour < end
            : hour >= start || hour < end;
    }
}
