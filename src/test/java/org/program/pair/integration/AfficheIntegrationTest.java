package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.affiche.dto.AfficheDto;
import org.program.pair.domain.affiche.dto.AfficheUpdateDto;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le module « affiche », interrogé en HTTP contre une vraie base.
 *
 * <p>Deux propriétés ne se démontrent nulle part ailleurs, et ce sont
 * exactement les deux demandes bloquantes du contrat :
 *
 * <ul>
 *   <li><b>publier sans être l'hôte</b> — la seule route de publication
 *       existante, {@code PATCH /api/slots/{id}/recap/visibility}, rend 403 à
 *       quiconque n'organise pas ; ici c'est un simple participant qui
 *       publie ;</li>
 *   <li><b>l'audience appliquée par le serveur</b> — la requête de lecture
 *       filtre sur un lien (l'abonnement) que le lecteur ne peut pas connaître,
 *       et seule une vraie base dit si elle filtre ce qu'elle prétend filtrer.
 *       Un test à mocks ne prouverait que l'intention.</li>
 * </ul>
 */
class AfficheIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotParticipationRepository participationRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    private static final ParameterizedTypeReference<List<AfficheDto>> AFFICHE_LIST =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<AfficheUpdateDto>> UPDATE_LIST =
        new ParameterizedTypeReference<>() {};

    @Test
    void unSimpleParticipant_publieSonAffiche_laOuLaCarteSouvenirLuiEstRefusee() {
        Fixture f = endedSlot("aff-participant");
        confirmPresence(f.guestToken, f.scheduleId);

        // La publication de la carte-souvenir, elle, reste réservée à l'hôte.
        webTestClient.patch()
            .uri("/api/slots/{id}/recap/visibility", f.scheduleId)
            .headers(h -> h.setBearerAuth(f.guestToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"visibility\":\"PUBLIC\"}")
            .exchange()
            .expectStatus().isForbidden();

        AfficheDto affiche = publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "EVERYONE");

        assertThat(affiche.scheduleId()).isEqualTo(f.scheduleId);
        assertThat(affiche.motif()).isEqualTo("PREMIERE_FOIS");
        assertThat(affiche.audience()).isEqualTo("EVERYONE");
        assertThat(affiche.slotStartedAt()).isEqualTo(f.livedStart);
        assertThat(affiche.featuredUntil())
            .as("sept jours après la FIN de la séance, que le client ne connaissait pas")
            .isEqualTo(f.livedEnd.plus(7, ChronoUnit.DAYS));
    }

    @Test
    void sansPresenceConfirmee_lAfficheEstRefusee() {
        Fixture f = endedSlot("aff-absent");

        webTestClient.put()
            .uri("/api/affiches/{id}", f.scheduleId)
            .headers(h -> h.setBearerAuth(f.guestToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"motif\":\"PREMIERE_FOIS\",\"audience\":\"EVERYONE\"}")
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("AFFICHE_NOT_ATTENDEE");
    }

    /**
     * Le cœur de la demande 2 : l'audience n'est pas un réglage déclaratif. Le
     * même lecteur, avant et après son abonnement, sur la même affiche.
     */
    @Test
    void lAudienceEstAppliqueeParLeServeur_etNonRendueAFiltrer() {
        Fixture f = endedSlot("aff-audience");
        confirmPresence(f.guestToken, f.scheduleId);
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "SUBSCRIBERS");

        UUID auteurId = userRepository.findByEmail("aff-audience-participant@pair.app")
            .orElseThrow().getId();
        String lecteur = registerAndLogin("aff-audience-lecteur@pair.app");

        assertThat(affichesOf(lecteur, auteurId))
            .as("un lecteur non abonné ne reçoit rien — pas même une liste à filtrer")
            .isEmpty();

        subscribeToAuthor(lecteur, auteurId);

        assertThat(affichesOf(lecteur, auteurId))
            .as("le même lecteur, une fois abonné, la voit")
            .extracting(AfficheDto::motif).containsExactly("PREMIERE_FOIS");

        // Resserrée sur personne : même un abonné n'a plus rien.
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "NOBODY");
        assertThat(affichesOf(lecteur, auteurId)).isEmpty();

        assertThat(affichesOf(f.guestToken, auteurId))
            .as("mais chez soi, une affiche muette se voit toujours")
            .hasSize(1);
    }

    @Test
    void lAnneauNeSAllumeQueChezQuiADroitDeVoir() {
        Fixture f = endedSlot("aff-anneau");
        confirmPresence(f.guestToken, f.scheduleId);

        UUID auteurId = userRepository.findByEmail("aff-anneau-participant@pair.app")
            .orElseThrow().getId();
        String abonne = registerAndLogin("aff-anneau-abonne@pair.app");
        String tiers = registerAndLogin("aff-anneau-tiers@pair.app");
        subscribeToAuthor(abonne, auteurId);

        Instant avant = Instant.now().minus(1, ChronoUnit.MINUTES);
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "SUBSCRIBERS");

        assertThat(updatesSince(abonne, avant))
            .extracting(AfficheUpdateDto::userId).contains(auteurId);
        assertThat(updatesSince(tiers, avant))
            .as("le signal lui-même fuiterait ce que l'affiche protège")
            .extracting(AfficheUpdateDto::userId).doesNotContain(auteurId);
        assertThat(updatesSince(f.guestToken, avant))
            .as("et personne n'a besoin d'un anneau sur son propre avatar")
            .extracting(AfficheUpdateDto::userId).doesNotContain(auteurId);

        assertThat(updatesSince(abonne, Instant.now().plus(1, ChronoUnit.MINUTES)))
            .as("since dans le futur : plus rien de neuf")
            .isEmpty();
    }

    @Test
    void publierDeuxFois_neLaisseQuUneAffiche_etDepublierLaRetire() {
        Fixture f = endedSlot("aff-idempotence");
        confirmPresence(f.guestToken, f.scheduleId);

        UUID auteurId = userRepository.findByEmail("aff-idempotence-participant@pair.app")
            .orElseThrow().getId();

        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "EVERYONE");
        publishAffiche(f.guestToken, f.scheduleId, "DIXIEME_SEANCE", "EVERYONE");

        assertThat(affichesOf(f.guestToken, auteurId))
            .extracting(AfficheDto::motif)
            .as("une seule ligne, le dernier motif")
            .containsExactly("DIXIEME_SEANCE");

        unpublish(f.guestToken, f.scheduleId);
        assertThat(affichesOf(f.guestToken, auteurId)).isEmpty();

        // Idempotent : rejouer le geste dans le vide n'est pas une erreur.
        unpublish(f.guestToken, f.scheduleId);
    }

    /**
     * Demande 3 : la carte-souvenir porte désormais la fin de la séance, et non
     * plus seulement son début. Le repli que le client aurait dû écrire —
     * {@code slotStartedAt + 7 j} — se trompait de la durée de la séance.
     */
    @Test
    void laCarteSouvenirPorteLaFinDeLaSeance() {
        Fixture f = endedSlot("aff-fin");
        confirmPresence(f.hostToken, f.scheduleId);
        confirmPresence(f.guestToken, f.scheduleId);

        SlotRecapDto card = webTestClient.patch()
            .uri("/api/slots/{id}/recap/note", f.scheduleId)
            .headers(h -> h.setBearerAuth(f.hostToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"note\":\"Belle séance.\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(SlotRecapDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(card).isNotNull();
        assertThat(card.slotEndedAt()).isEqualTo(f.livedEnd);
        assertThat(card.slotEndedAt()).isAfter(card.slotStartedAt());
        assertThat(card.recapWindowClosesAt())
            .as("la fenêtre de contribution se compte depuis cette fin-là")
            .isEqualTo(card.slotEndedAt().plus(7, ChronoUnit.DAYS));
    }

    /**
     * B7 : l'affiche porte de quoi se composer seule.
     *
     * <p>L'assertion qui compte n'est pas que les deux champs soient renseignés,
     * c'est qu'ils portent <b>exactement</b> ce que la carte-souvenir de la même
     * séance annonce. Les deux routes lisent la même chaîne
     * {@code Schedule → Program → UserActivity → Activity → Category}, et c'est
     * délibéré : deux chemins vers la même valeur divergent le jour où l'un des
     * replis change, et personne ne remarquerait qu'une affiche et une
     * carte-souvenir d'une même séance annoncent deux activités.
     *
     * <p>Le décor le rend d'autant plus parlant que la carte-souvenir est ici
     * lisible — l'hôte vient de l'écrire. En production c'est l'inverse dans le
     * cas majoritaire : elle est refusée en 404 tant que l'hôte ne l'a pas
     * publiée, et c'est précisément pourquoi l'affiche ne peut pas aller y
     * chercher son titre.
     */
    @Test
    void lAfficheAnnonceLaMemeChaineQueLaCarteSouvenirDeLaSeance() {
        Fixture f = endedSlot("aff-activite");
        confirmPresence(f.hostToken, f.scheduleId);
        confirmPresence(f.guestToken, f.scheduleId);

        SlotRecapDto card = webTestClient.patch()
            .uri("/api/slots/{id}/recap/note", f.scheduleId)
            .headers(h -> h.setBearerAuth(f.hostToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"note\":\"Belle séance.\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(SlotRecapDto.class)
            .returnResult()
            .getResponseBody();

        AfficheDto affiche = publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "EVERYONE");

        assertThat(card).isNotNull();
        assertThat(card.activityName()).isNotBlank();
        assertThat(card.categoryName()).isNotBlank();
        assertThat(card.categoryColorRamp()).isNotBlank();
        assertThat(card.cityLabel()).isEqualTo("Lyon");

        assertThat(affiche.activityName())
            .as("même chaîne que la carte-souvenir, volontairement")
            .isEqualTo(card.activityName());
        assertThat(affiche.categoryColorRamp())
            .as("la teinte aussi : deux sources pour une couleur finiraient par diverger")
            .isEqualTo(card.categoryColorRamp());
        assertThat(affiche.categoryName())
            .as("le NOM de la catégorie, que la rampe ne remplace pas")
            .isEqualTo(card.categoryName());
        assertThat(affiche.cityLabel())
            .as("la ville, la même colonne que la carte — jamais le nom du lieu")
            .isEqualTo(card.cityLabel());

        // Et la ligne que l'ajout ne doit pas franchir : l'auteur publie ce que
        // SA pratique a écrit, jamais la fiche de la séance de l'hôte.
        assertThat(affichesOf(f.guestToken, f.guestId))
            .singleElement()
            .satisfies(a -> {
                assertThat(a.activityName()).isEqualTo(card.activityName());
                assertThat(a.categoryName()).isEqualTo(card.categoryName());
                assertThat(a.categoryColorRamp()).isEqualTo(card.categoryColorRamp());
                assertThat(a.cityLabel()).isEqualTo(card.cityLabel());
            });
    }

    /**
     * La ville est entrée, le <b>lieu</b> non — et c'est la distinction qui porte
     * tout le reste.
     *
     * <p>Un nom de salle répété sur une série d'affiches publiques dessine un
     * emploi du temps ; c'est la raison pour laquelle le motif dont le lieu est
     * le sujet est sorti de la sélection du client, et la garde de publication le
     * refuse de toute façon. Le test regarde le <b>corps JSON brut</b> et non le
     * DTO : c'est la seule façon de prouver qu'un champ n'est pas là, et la seule
     * qui tienne si quelqu'un l'ajoute demain sans y penser.
     */
    @Test
    void laVilleEstPubliee_maisJamaisLeNomDuLieu() {
        Fixture f = endedSlot("aff-lieu");
        confirmPresence(f.guestToken, f.scheduleId);
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_VILLE", "EVERYONE");

        String corps = webTestClient.get()
            .uri("/api/users/{id}/affiches", f.guestId)
            .headers(h -> h.setBearerAuth(f.guestToken))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

        assertThat(corps).contains("\"cityLabel\":\"Lyon\"");
        assertThat(corps)
            .as("le nom du lieu n'est pas au contrat, et ne doit pas y entrer par mégarde")
            .doesNotContain("placeName")
            .doesNotContain("Mur des Lilas")
            .doesNotContain("Rue des Lilas");
    }

    /**
     * B8 : la bande d'affiches n'a pas de liste hôte pour apporter les visages.
     *
     * <p>Ce qui n'est pas rendu ici ne peut être résolu que par un
     * {@code GET /users/{id}} par identifiant — la requête par personne que cette
     * route existe pour éviter.
     */
    @Test
    void laBandeRecoitLeNomEtLAvatarDeCeuxQuElleADroitDeMontrer() {
        Fixture f = endedSlot("aff-bande");
        confirmPresence(f.guestToken, f.scheduleId);

        User auteur = userRepository.findById(f.guestId).orElseThrow();
        auteur.setAvatarUrl("https://cdn.pair.app/aff-bande/auteur.jpg");
        userRepository.save(auteur);

        String tiers = registerAndLogin("aff-bande-tiers@pair.app");

        Instant avant = Instant.now().minus(1, ChronoUnit.MINUTES);
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "EVERYONE");

        assertThat(updatesSince(tiers, avant))
            .filteredOn(u -> u.userId().equals(f.guestId))
            .singleElement()
            .satisfies(u -> {
                assertThat(u.displayName())
                    .as("sans le nom, la bande ne peut pas dessiner cette personne")
                    .isEqualTo(auteur.getDisplayName());
                assertThat(u.avatarUrl())
                    .isEqualTo("https://cdn.pair.app/aff-bande/auteur.jpg");
                assertThat(u.latestPublishedAt()).isAfter(avant);
            });
    }

    /**
     * B14 : la bande porte les champs d'affichage de la <b>dernière</b> affiche
     * visible, et de celle-là seule.
     *
     * <p>C'est la propriété que l'agrégation précédente ne pouvait pas tenir.
     * {@code MAX(published_at)} rendait une <i>date</i> ; il aurait fallu inventer
     * un {@code MAX(motif)} pour rendre le reste, qui aurait donné le motif
     * alphabétiquement dernier — celui d'une affiche que la date ne désigne pas.
     * Le visage aurait annoncé le motif d'une publication et la date d'une autre.
     *
     * <p>Le décor pose donc deux affiches de la même personne, sur deux séances
     * différentes et sous deux motifs différents. La bande doit rendre <b>une
     * ligne</b>, celle de la plus récente, entière.
     */
    @Test
    void laBandePorteLaDerniereAfficheEntiere_etUneSeuleLigneParPersonne() {
        Fixture f = endedSlot("aff-derniere");
        confirmPresence(f.guestToken, f.scheduleId);

        Instant seanceAncienne = Instant.now().minus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        UUID ancienCreneau = autreCreneauTermine("aff-derniere-2", f.guestId, seanceAncienne);
        confirmPresence(f.guestToken, ancienCreneau);

        String tiers = registerAndLogin("aff-derniere-tiers@pair.app");
        Instant avant = Instant.now().minus(1, ChronoUnit.MINUTES);

        // L'ancienne séance publiée D'ABORD, la récente ENSUITE : c'est la
        // seconde publication qui doit gagner, et son motif se trie avant celui
        // de la première — un MAX() aurait rendu l'autre.
        publishAffiche(f.guestToken, ancienCreneau, "RETOUR_APRES_PAUSE", "EVERYONE");
        AfficheDto derniere = publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_CATEGORIE", "EVERYONE");

        assertThat(derniere).isNotNull();
        assertThat(updatesSince(tiers, avant))
            .filteredOn(u -> u.userId().equals(f.guestId))
            .as("une personne, une ligne — jamais une ligne par affiche")
            .singleElement()
            .satisfies(u -> {
                assertThat(u.motif())
                    .as("le motif de la dernière publication, pas le dernier motif par ordre")
                    .isEqualTo("PREMIERE_CATEGORIE");
                assertThat(u.slotStartedAt())
                    .as("la séance de CETTE affiche, et non celle de l'autre")
                    .isEqualTo(derniere.slotStartedAt())
                    .isNotEqualTo(seanceAncienne);
                assertThat(u.activityName()).isEqualTo(derniere.activityName());
                assertThat(u.categoryColorRamp()).isEqualTo(derniere.categoryColorRamp());
                assertThat(u.latestPublishedAt()).isAfter(avant);
            });
    }

    /**
     * <b>« Dernière » veut dire « dernière que ce lecteur a le droit de voir »</b>,
     * et c'est la propriété la plus délicate du lot.
     *
     * <p>Le décor est celui qui fuit si l'ordre des opérations est faux : une
     * affiche publique ancienne, puis une affiche <b>réservée aux abonnés</b> plus
     * récente. Un tiers non abonné doit voir la personne — elle a bien publié
     * quelque chose pour lui — mais avec le motif et la séance de l'<i>ancienne</i>.
     *
     * <p>Rendre le motif de la plus récente serait la fuite exacte que ce module
     * passe son temps à éviter : pas l'affiche elle-même, mais ce qu'elle
     * raconte. C'est le {@code WHERE} qui l'empêche, parce qu'il s'applique
     * <b>avant</b> le {@code DISTINCT ON} : l'affiche réservée n'entre jamais dans
     * l'ensemble que celui-ci départage. L'inverse — filtrer après avoir réduit à
     * une ligne par personne — ferait disparaître la personne au lieu de la fuir,
     * ce qui est un autre bug et non une protection.
     */
    @Test
    void laDerniereAfficheDeLaBande_estLaDerniereQuOnADroitDeVoir() {
        Fixture f = endedSlot("aff-derniere-visible");
        confirmPresence(f.guestToken, f.scheduleId);

        Instant seanceAncienne = Instant.now().minus(15, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        UUID creneauPublic = autreCreneauTermine("aff-derniere-visible-2", f.guestId, seanceAncienne);
        confirmPresence(f.guestToken, creneauPublic);

        String tiers = registerAndLogin("aff-derniere-visible-tiers@pair.app");
        Instant avant = Instant.now().minus(1, ChronoUnit.MINUTES);

        // L'ancienne est ouverte à tous ; la récente est réservée aux abonnés,
        // et le tiers n'en est pas un.
        AfficheDto publique = publishAffiche(f.guestToken, creneauPublic, "PREMIERE_FOIS", "EVERYONE");
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_CATEGORIE", "SUBSCRIBERS");

        assertThat(publique).isNotNull();
        assertThat(updatesSince(tiers, avant))
            .filteredOn(u -> u.userId().equals(f.guestId))
            .singleElement()
            .satisfies(u -> {
                assertThat(u.motif())
                    .as("le motif de l'affiche réservée ne doit pas sortir par la bande")
                    .isEqualTo("PREMIERE_FOIS");
                assertThat(u.slotStartedAt())
                    .as("ni la séance dont elle parle")
                    .isEqualTo(publique.slotStartedAt())
                    .isEqualTo(seanceAncienne);
                assertThat(u.latestPublishedAt())
                    .as("la date suit la même ligne : celle de l'affiche visible")
                    .isEqualTo(publique.publishedAt());
            });
    }

    /**
     * Les quatre champs d'affichage restent derrière le filtre d'audience, comme
     * le nom et l'avatar : une affiche qu'on n'a pas le droit d'ouvrir ne doit pas
     * se laisser lire de dos par la bande. C'est la même garde que ci-dessous,
     * appliquée à ce que le lot vient d'ajouter — et c'est là qu'un élargissement
     * de DTO fuit, quand la garde n'a été écrite que pour les champs d'hier.
     */
    @Test
    void lesChampsDAffichage_neSortentPasHorsDeLAudience() {
        Fixture f = endedSlot("aff-affichage-ferme");
        confirmPresence(f.guestToken, f.scheduleId);

        String tiers = registerAndLogin("aff-affichage-ferme-tiers@pair.app");
        Instant avant = Instant.now().minus(1, ChronoUnit.MINUTES);
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_CATEGORIE", "SUBSCRIBERS");

        assertThat(updatesSince(tiers, avant))
            .as("ni le visage, ni ce qu'il aurait annoncé")
            .extracting(AfficheUpdateDto::userId)
            .doesNotContain(f.guestId);
    }

    /**
     * Le nom et l'avatar restent derrière le filtre d'audience : ils ne sont pas
     * rendus « en plus », ils sont rendus <b>avec</b> une affiche qu'on a le droit
     * de voir. Une bande qui nommerait quelqu'un dont elle ne peut pas ouvrir
     * l'affiche promettrait ce qui n'existe pas.
     */
    @Test
    void unTiersSansDroit_neRecoitNiLAnneauNiLeNom() {
        Fixture f = endedSlot("aff-bande-fermee");
        confirmPresence(f.guestToken, f.scheduleId);

        String tiers = registerAndLogin("aff-bande-fermee-tiers@pair.app");

        Instant avant = Instant.now().minus(1, ChronoUnit.MINUTES);
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "SUBSCRIBERS");

        assertThat(updatesSince(tiers, avant))
            .as("le nom fuiterait ce que l'audience protège, comme l'anneau")
            .extracting(AfficheUpdateDto::userId)
            .doesNotContain(f.guestId);
    }

    /**
     * B16 : {@code hasPublishedAffiche} sur le profil privé.
     *
     * <p>Le fil porte une pastille d'amorce sur son propre visage, qui ne doit
     * paraître que tant qu'on n'a rien publié. Pour le savoir, le client lisait
     * {@code GET /users/{id}/affiches} : <b>une liste entière pour répondre par
     * oui ou non</b>, sur l'écran d'entrée du produit.
     *
     * <p>Le décor publie en <b>{@code NOBODY}</b>, et c'est délibéré : c'est le
     * cas qui distingue les deux questions possibles. « Quelqu'un peut-il la
     * voir ? » répondrait non ; « ai-je fait ce geste ? » répond oui, et c'est
     * celle-là qui décide de la pastille. Publier pour soi seul est un geste
     * posé.
     */
    @Test
    void leDrapeauDuProfilPrive_sAllumeAuPremierGeste_memePublieePourSoiSeul() {
        Fixture f = endedSlot("aff-drapeau");
        confirmPresence(f.guestToken, f.scheduleId);

        assertThat(monProfil(f.guestToken))
            .as("rien de publié : la pastille d'amorce a lieu d'être")
            .doesNotContain("\"hasPublishedAffiche\":true")
            .contains("\"hasPublishedAffiche\":false");

        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "NOBODY");

        assertThat(monProfil(f.guestToken))
            .as("une affiche muette reste une affiche publiée")
            .contains("\"hasPublishedAffiche\":true");
    }

    /**
     * Le drapeau vit sur le profil <b>privé</b>, et sur aucun autre.
     *
     * <p>« Cette personne a publié une affiche » est exactement ce que le filtre
     * d'audience protège : sur le profil public, ce serait la fuite du fait
     * qu'une affiche existe, à quelqu'un qui n'a peut-être pas le droit de la
     * voir. C'est le raisonnement de l'anneau, et il vaut ici sans changement —
     * d'autant que le drapeau ignore l'audience, donc une affiche réglée sur
     * {@code NOBODY} l'allume.
     *
     * <p>Le test lit le corps JSON brut, comme celui qui tient le nom du lieu
     * dehors : c'est la seule forme qui prouve qu'un champ <b>n'est pas</b> là, et
     * la seule qui tienne si quelqu'un l'ajoute par symétrie six mois plus tard.
     */
    @Test
    void leDrapeau_neVoyagePasSurLeProfilPublic() {
        Fixture f = endedSlot("aff-drapeau-public");
        confirmPresence(f.guestToken, f.scheduleId);
        publishAffiche(f.guestToken, f.scheduleId, "PREMIERE_FOIS", "NOBODY");

        String tiers = registerAndLogin("aff-drapeau-public-tiers@pair.app");

        String profilPublic = webTestClient.get()
            .uri("/api/users/{id}", f.guestId)
            .headers(h -> h.setBearerAuth(tiers))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

        assertThat(profilPublic)
            .as("le drapeau dirait qu'une affiche existe là où l'audience dit le contraire")
            .doesNotContain("hasPublishedAffiche");
    }

    // ————————————————————————— décor —————————————————————————

    /**
     * {@code guestId} évite aux tests de retrouver l'auteur par son e-mail : la
     * base est partagée entre classes depuis le conteneur unique, et une
     * recherche par adresse codée en dur est exactement la fixture qu'on ne veut
     * plus multiplier.
     */
    private record Fixture(UUID scheduleId, UUID guestId, String hostToken, String guestToken,
                           Instant livedStart, Instant livedEnd) {}

    /** Un créneau terminé il y a deux heures, son hôte, et un participant inscrit. */
    private Fixture endedSlot(String prefix) {
        String hostEmail = prefix + "-hote@pair.app";
        String hostToken = registerAndLogin(hostEmail);
        User host = userRepository.findByEmail(hostEmail).orElseThrow();

        String guestEmail = prefix + "-participant@pair.app";
        String guestToken = registerAndLogin(guestEmail);
        User guest = userRepository.findByEmail(guestEmail).orElseThrow();

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).visibleOnMap(true).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Bloc du mardi " + prefix)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Instant livedStart = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant livedEnd = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Mur des Lilas")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("Rue des Lilas")
            .city("Lyon")
            .location(geometryFactory.createPoint(new Coordinate(4.85, 45.77)))
            .startsAt(livedStart)
            .endsAt(livedEnd)
            .status(SlotStatus.OPEN)
            .isOpenToPartners(true)
            .build());

        participationRepository.save(SlotParticipation.builder()
            .schedule(schedule)
            .user(guest)
            .status(ParticipationStatus.CONFIRMED)
            .build());

        return new Fixture(schedule.getId(), guest.getId(), hostToken, guestToken,
            livedStart, livedEnd);
    }

    /**
     * Un second créneau terminé, chez un autre hôte, où le même participant est
     * inscrit — ce qu'il faut pour qu'une personne ait <b>deux</b> affiches.
     *
     * <p>Même activité du référentiel que {@link #endedSlot} : ce test-ci
     * distingue les deux affiches par leur motif et leur séance, jamais par leur
     * activité, et fabriquer une activité de plus grossirait le référentiel que
     * d'autres classes parcourent avec un {@code LIMIT}.
     */
    private UUID autreCreneauTermine(String prefix, UUID guestId, Instant debut) {
        String hostEmail = prefix + "-hote@pair.app";
        registerAndLogin(hostEmail);
        User host = userRepository.findByEmail(hostEmail).orElseThrow();
        User guest = userRepository.findById(guestId).orElseThrow();

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).visibleOnMap(true).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Bloc ancien " + prefix)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Mur ancien " + prefix)
            .placeType(PlaceType.PUBLIC)
            .city("Lyon")
            .location(geometryFactory.createPoint(new Coordinate(4.85, 45.77)))
            .startsAt(debut)
            .endsAt(debut.plus(1, ChronoUnit.HOURS))
            .status(SlotStatus.PAST)
            .isOpenToPartners(true)
            .build());

        participationRepository.save(SlotParticipation.builder()
            .schedule(schedule)
            .user(guest)
            .status(ParticipationStatus.CONFIRMED)
            .build());

        return schedule.getId();
    }

    /** Le corps brut de {@code GET /api/users/me} — c'est un champ qu'on y cherche. */
    private String monProfil(String token) {
        return webTestClient.get()
            .uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    }

    private void confirmPresence(String token, UUID scheduleId) {
        webTestClient.post()
            .uri("/api/attendances/{scheduleId}/confirm", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"wasPresent\":true}")
            .exchange()
            .expectStatus().isOk();
    }

    private AfficheDto publishAffiche(String token, UUID scheduleId, String motif, String audience) {
        return webTestClient.put()
            .uri("/api/affiches/{id}", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"motif\":\"" + motif + "\",\"audience\":\"" + audience + "\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody(AfficheDto.class)
            .returnResult()
            .getResponseBody();
    }

    private void unpublish(String token, UUID scheduleId) {
        webTestClient.delete()
            .uri("/api/affiches/{id}", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isNoContent();
    }

    private List<AfficheDto> affichesOf(String token, UUID userId) {
        return webTestClient.get()
            .uri("/api/users/{id}/affiches", userId)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AFFICHE_LIST)
            .returnResult()
            .getResponseBody();
    }

    private List<AfficheUpdateDto> updatesSince(String token, Instant since) {
        return webTestClient.get()
            .uri(uriBuilder -> uriBuilder.path("/api/affiches/updates")
                .queryParam("since", since.toString())
                .build())
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UPDATE_LIST)
            .returnResult()
            .getResponseBody();
    }

    private void subscribeToAuthor(String token, UUID authorId) {
        webTestClient.post()
            .uri("/api/users/{id}/subscription", authorId)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().is2xxSuccessful();
    }

    private String registerAndLogin(String email) {
        org.program.pair.domain.auth.dto.RegisterRequest registerReq =
            new org.program.pair.domain.auth.dto.RegisterRequest(email, "Password123!", email.split("@")[0]);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

        org.program.pair.domain.auth.dto.LoginRequest loginReq =
            new org.program.pair.domain.auth.dto.LoginRequest(email, "Password123!");
        org.program.pair.domain.auth.dto.AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(loginReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(org.program.pair.domain.auth.dto.AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }
}
