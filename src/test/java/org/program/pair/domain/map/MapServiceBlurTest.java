package org.program.pair.domain.map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le floutage de position, vérifié comme une propriété plutôt que sur un tirage.
 *
 * <p>{@code MapVisibilityIntegrationTest.positionAffichee_doitEtreFlouttee_pasExacte}
 * affirmait déjà « la position affichée ne doit JAMAIS être exactement la
 * position réelle », mais en un seul appel : il tombait donc au hasard, environ
 * une fois sur seize, sur les tirages où l'arrondi à la quatrième décimale
 * ramenait sur la position d'origine. Un échec par exécution complète de la
 * suite, sans rapport avec le code en cours de modification — le genre de faux
 * positif qui apprend à ignorer les rouges.
 *
 * <p>Ces tests bouclent sur des dizaines de milliers de tirages : ils ne
 * constatent plus un tirage, ils établissent une propriété. Sans Spring ni base,
 * ils coûtent quelques millisecondes.
 */
class MapServiceBlurTest {

    // Aucune dépendance n'est touchée par applyBlur : le service est construit
    // à vide, comme SemanticSearchServiceTest le fait pour ses helpers purs.
    private final MapService mapService = new MapService(null, null, null, null, null, null, null, null);

    private static final double LAT = 48.8566;
    private static final double LNG = 2.3522;
    private static final int DRAWS = 50_000;

    /**
     * La promesse faite à l'utilisateur qui règle son rayon de flou. Les
     * coordonnées choisies tombent exactement sur la grille des quatre
     * décimales : c'est le seul cas où la collision est possible, donc le seul
     * qui teste quelque chose.
     */
    @Test
    void positionFloutee_neDoitJamaisEgalerLaPositionReelle() {
        for (int i = 0; i < DRAWS; i++) {
            double[] blurred = mapService.applyBlur(LAT, LNG, 500);

            assertThat(blurred[0]).as("latitude, tirage %d", i).isNotEqualTo(LAT);
            assertThat(blurred[1]).as("longitude, tirage %d", i).isNotEqualTo(LNG);
        }
    }

    /** Vrai quel que soit le rayon, y compris le plus petit autorisé (100 m). */
    @Test
    void positionFloutee_neDoitJamaisEgalerLaPositionReelle_quelQueSoitLeRayon() {
        for (int radius : new int[]{100, 250, 500, 1000, 5000}) {
            for (int i = 0; i < 2_000; i++) {
                double[] blurred = mapService.applyBlur(LAT, LNG, radius);

                assertThat(blurred[0]).as("rayon %d m", radius).isNotEqualTo(LAT);
                assertThat(blurred[1]).as("rayon %d m", radius).isNotEqualTo(LNG);
            }
        }
    }

    /**
     * Le flou doit rester borné : une position poussée trop loin trahirait
     * l'utilisateur autrement, en le montrant où il n'est jamais allé. La marge
     * couvre le pas de grille de la poussée anti-collision (~11 m) et l'arrondi.
     */
    @Test
    void positionFloutee_doitResterDansLeRayonDemande() {
        int radiusMeters = 500;
        double toleranceMeters = 25;

        for (int i = 0; i < DRAWS; i++) {
            double[] blurred = mapService.applyBlur(LAT, LNG, radiusMeters);

            double dLat = (blurred[0] - LAT) * 111320.0;
            double dLng = (blurred[1] - LNG) * 111320.0 * Math.cos(Math.toRadians(LAT));
            double distance = Math.hypot(dLat, dLng);

            assertThat(distance).as("tirage %d", i).isLessThanOrEqualTo(radiusMeters + toleranceMeters);
        }
    }

    /**
     * Et il doit rester un flou : si le déplacement disparaissait, tous les
     * tirages tomberaient sur la même valeur. Ce test-là échouerait si quelqu'un
     * « simplifiait » applyBlur en rendant la position telle quelle.
     */
    @Test
    void positionFloutee_doitVarierDunTirageALautre() {
        long distinctLatitudes = java.util.stream.IntStream.range(0, 1_000)
            .mapToDouble(i -> mapService.applyBlur(LAT, LNG, 500)[0])
            .distinct()
            .count();

        assertThat(distinctLatitudes).isGreaterThan(10);
    }
}
