package org.program.pair.domain.attendance;

/**
 * Dire « on peut compter sur cette personne » sans jamais la noter.
 *
 * <p>Cette classe est presque entièrement faite d'interdits, et c'est voulu :
 * elle touche à ce que le produit refuse d'être. Le calcul tient en trois
 * lignes ; le reste est la raison pour laquelle il ne rend qu'un libellé.
 *
 * <p><b>Le serveur rend le libellé, jamais les compteurs.</b> C'était la
 * décision à trancher, et elle se justifie par le résultat plutôt que par le
 * principe : renvoyer « 12 venues sur 15 inscriptions » laisse n'importe quel
 * client afficher 80 %, puis classer les gens par ce nombre, puis en faire un
 * filtre de recherche. Aucune règle côté serveur n'empêche cela une fois le
 * chiffre parti. Le libellé, lui, ne se divise pas.
 *
 * <p><b>Jamais de libellé négatif.</b> L'absence de signal n'est pas un mauvais
 * signal : c'est le cas de toute personne qui vient d'arriver, et lui accrocher
 * une mention défavorable ferait du produit un tribunal. Sous le seuil, ou sous
 * la barre, cette classe rend {@code null} — et l'interface n'affiche rien.
 *
 * <p><b>Un seuil de données, pas un palier de mérite.</b> En dessous de cinq
 * créneaux, la proportion ne veut rien dire : deux venues sur deux ne prouvent
 * rien, et une absence sur deux non plus. Le seuil existe pour éviter de dire
 * quelque chose de faux, pas pour récompenser l'assiduité.
 */
public final class ReliabilitySignal {

    /** En deçà, on ne dit rien : la proportion n'aurait aucune valeur. */
    private static final int MIN_JOINED = 5;

    /**
     * Quatre venues sur cinq. Exprimé en entiers plutôt qu'en pourcentage, pour
     * qu'aucun flottant ne traîne dans un calcul dont on ne veut justement pas
     * qu'il ressemble à une note.
     */
    private static final int NUMERATOR = 4;
    private static final int DENOMINATOR = 5;

    /** Le seul libellé qui existe. Il n'en aura jamais de contraire. */
    private static final String USUALLY_SHOWS_UP = "USUALLY_SHOWS_UP";

    private ReliabilitySignal() {}

    /**
     * Le libellé à afficher, ou {@code null} s'il n'y a rien à dire.
     *
     * @param joinedSlots créneaux passés auxquels la personne s'était inscrite
     * @param attended    ceux où sa présence a été confirmée
     */
    public static String of(Integer joinedSlots, Integer attended) {
        int joined = joinedSlots == null ? 0 : joinedSlots;
        int present = attended == null ? 0 : attended;

        if (joined < MIN_JOINED) {
            return null;
        }
        return present * DENOMINATOR >= joined * NUMERATOR ? USUALLY_SHOWS_UP : null;
    }
}
