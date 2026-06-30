package org.program.pair.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.ActivityFormat;
import org.program.pair.domain.activity.ActivityLevel;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.search.EmbeddingService;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Random;

@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final UserActivityRepository userActivityRepository;
    private final ProgramRepository programRepository;
    private final ScheduleRepository scheduleRepository;
    private final ActivityRepository activityRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmbeddingService embeddingService;

    @Value("${seed.center-lat:48.8566}")
    private double centerLat;

    @Value("${seed.center-lng:2.3522}")
    private double centerLng;

    private static final GeometryFactory GEOMETRY_FACTORY =
        new GeometryFactory(new PrecisionModel(), 4326);

    private static final Random RANDOM = new Random(42); // Seed fixe pour reproductibilité

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail("demo1@pair.app")) {
            log.info("Demo users already exist, skipping demo data seeding");
            return;
        }

        log.info("Starting demo data seeding...");
        createDemoUsers();
        log.info("Demo data seeding completed");
    }

    private void createDemoUsers() {
        List<DemoProfile> profiles = buildDemoProfiles();

        for (DemoProfile profile : profiles) {
            try {
                log.info("Creating demo user: {}", profile.email());

                // Create user with random location near center
                Point userLocation = randomPointNear(centerLat, centerLng, 5000);

                User user = User.builder()
                    .email(profile.email())
                    .passwordHash(passwordEncoder.encode("Demo1234!"))
                    .displayName(profile.displayName())
                    .bio(profile.bio())
                    .location(userLocation)
                    .blurRadiusM(500)
                    .locationPublic(true)
                    .onlineStatusVisible(true)
                    .receiveMessages(true)
                    .verificationStatus(VerificationStatus.EMAIL_VERIFIED)
                    .verifiedAt(Instant.now())
                    .isActive(true)
                    .lastActiveAt(Instant.now())
                    .build();

                user = userRepository.save(user);
                log.info("Created user: {} ({})", user.getDisplayName(), user.getEmail());

                // Attach activities and programs
                attachActivitiesAndPrograms(user, profile.activities());

            } catch (Exception e) {
                log.error("Error creating demo user {}: {}", profile.email(), e.getMessage(), e);
            }
        }
    }

    private void attachActivitiesAndPrograms(User user, List<DemoActivityProfile> activityProfiles) {
        for (DemoActivityProfile activityProfile : activityProfiles) {
            try {
                // Find the activity by slug
                Activity activity = activityRepository.findBySlug(activityProfile.activitySlug())
                    .orElseThrow(() -> new IllegalArgumentException(
                        "Activity not found: " + activityProfile.activitySlug()));

                // Create UserActivity
                UserActivity userActivity = UserActivity.builder()
                    .user(user)
                    .activity(activity)
                    .visibleOnMap(true)
                    .customDescription(activityProfile.customDescription())
                    .level(activityProfile.level())
                    .format(activityProfile.format())
                    .build();

                userActivity = userActivityRepository.save(userActivity);
                log.debug("Created user activity: {} for {}", activity.getName(), user.getDisplayName());

                // Create Program
                Program program = Program.builder()
                    .userActivity(userActivity)
                    .title(activityProfile.programTitle())
                    .description(activityProfile.programDescription())
                    .status(ProgramStatus.ACTIVE)
                    .isPublic(true)
                    .build();

                program = programRepository.save(program);
                log.debug("Created program: {}", program.getTitle());

                // Generate embedding synchronously for the program
                if (embeddingService.isConfigured()) {
                    try {
                        String text = buildProgramText(program);
                        float[] embedding = embeddingService.generateEmbedding(text);
                        if (embedding != null) {
                            String vectorString = embeddingService.toVectorString(embedding);
                            programRepository.updateEmbedding(program.getId(), vectorString);
                            log.debug("Generated embedding for program: {}", program.getTitle());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to generate embedding for program {}: {}",
                            program.getTitle(), e.getMessage());
                    }
                }

                // Create Schedule
                LocalDate nextDate = nextWeekday(activityProfile.dayOfWeek());
                Instant startsAt = nextDate.atTime(activityProfile.hour(), 0)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
                Instant endsAt = startsAt.plusSeconds(2 * 3600); // 2 hours duration

                Point scheduleLocation = randomPointNear(
                    user.getLocation().getY(),
                    user.getLocation().getX(),
                    2000
                );

                Schedule schedule = Schedule.builder()
                    .program(program)
                    .placeName(activityProfile.placeName())
                    .placeType(PlaceType.PUBLIC)
                    .location(scheduleLocation)
                    .showExactAddress(false)
                    .startsAt(startsAt)
                    .endsAt(endsAt)
                    .maxParticipants(activityProfile.maxParticipants())
                    .build();

                scheduleRepository.save(schedule);
                log.debug("Created schedule for program: {} at {}",
                    program.getTitle(), activityProfile.placeName());

            } catch (Exception e) {
                log.error("Error attaching activity {} to user {}: {}",
                    activityProfile.activitySlug(), user.getEmail(), e.getMessage(), e);
            }
        }
    }

    private String buildProgramText(Program p) {
        StringBuilder sb = new StringBuilder(p.getTitle()).append(". ");
        if (p.getDescription() != null) {
            sb.append(p.getDescription()).append(". ");
        }
        if (p.getUserActivity() != null) {
            sb.append(p.getUserActivity().getActivity().getName()).append(". ");
            if (p.getUserActivity().getCustomDescription() != null) {
                sb.append(p.getUserActivity().getCustomDescription());
            }
        }
        return sb.toString();
    }

    /**
     * Generate a random point within radiusMeters of the given center point
     */
    private Point randomPointNear(double centerLat, double centerLng, int radiusMeters) {
        // Convert radius to degrees (approximate)
        double radiusInDegrees = radiusMeters / 111000.0;

        // Random angle and distance
        double angle = RANDOM.nextDouble() * 2 * Math.PI;
        double distance = RANDOM.nextDouble() * radiusInDegrees;

        // Calculate offset
        double deltaLat = distance * Math.cos(angle);
        double deltaLng = distance * Math.sin(angle) / Math.cos(Math.toRadians(centerLat));

        double newLat = centerLat + deltaLat;
        double newLng = centerLng + deltaLng;

        return GEOMETRY_FACTORY.createPoint(new Coordinate(newLng, newLat));
    }

    /**
     * Calculate the next occurrence of the given weekday
     */
    private LocalDate nextWeekday(String dayOfWeekCode) {
        DayOfWeek dayOfWeek = switch (dayOfWeekCode.toUpperCase()) {
            case "MO" -> DayOfWeek.MONDAY;
            case "TU" -> DayOfWeek.TUESDAY;
            case "WE" -> DayOfWeek.WEDNESDAY;
            case "TH" -> DayOfWeek.THURSDAY;
            case "FR" -> DayOfWeek.FRIDAY;
            case "SA" -> DayOfWeek.SATURDAY;
            case "SU" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("Invalid day code: " + dayOfWeekCode);
        };

        LocalDate today = LocalDate.now();
        LocalDate nextOccurrence = today.with(TemporalAdjusters.nextOrSame(dayOfWeek));

        // If it's today but past the hour, go to next week
        if (nextOccurrence.equals(today)) {
            nextOccurrence = nextOccurrence.plusWeeks(1);
        }

        return nextOccurrence;
    }

    /**
     * Build demo profiles with varied activities
     */
    private List<DemoProfile> buildDemoProfiles() {
        return List.of(
            new DemoProfile("demo1@pair.app", "Camille Bertrand",
                "Passionnée de yoga et de céramique, toujours partante pour découvrir.",
                List.of(
                    new DemoActivityProfile("yoga", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "Yoga vinyasa, ambiance détendue", "Séance yoga matinale",
                        "Yoga doux pour bien commencer la journée, tous niveaux bienvenus.",
                        "Studio Lumière", "SA", 9, 8),
                    new DemoActivityProfile("ceramique", ActivityLevel.BEGINNER, ActivityFormat.DUO,
                        "J'apprends encore, ouverte aux conseils", "Atelier céramique du dimanche",
                        "Tournage et modelage, ambiance conviviale et sans pression.",
                        "Atelier Terre & Feu", "SU", 14, 4)
                )),
            new DemoProfile("demo2@pair.app", "Karim Haddad",
                "Coureur régulier, je cherche des partenaires pour les sorties longues.",
                List.of(
                    new DemoActivityProfile("course-a-pied", ActivityLevel.ADVANCED, ActivityFormat.GROUP,
                        "10-15km, rythme soutenu", "Sortie longue du dimanche",
                        "Sortie longue en groupe, prépa semi-marathon.",
                        "Parc des Buttes-Chaumont", "SU", 8, 6),
                    new DemoActivityProfile("trail", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "Découverte du trail", "Initiation trail",
                        "Sortie trail découverte sur sentiers, dénivelé modéré.",
                        "Forêt de Meudon", "SA", 8, 10)
                )),
            new DemoProfile("demo3@pair.app", "Léa Moreau",
                "Échecs et jeux de société, je cherche du monde pour jouer régulièrement.",
                List.of(
                    new DemoActivityProfile("echecs", ActivityLevel.INTERMEDIATE, ActivityFormat.DUO,
                        "Niveau club, parties rapides ou longues", "Parties d'échecs du jeudi",
                        "Parties amicales, tous niveaux, ambiance détendue.",
                        "Café Le Roi", "TH", 19, 2),
                    new DemoActivityProfile("jeux-de-societe", ActivityLevel.ANY, ActivityFormat.GROUP,
                        "Jeux modernes, stratégie et coopératif", "Soirée jeux de société",
                        "Découverte de nouveaux jeux chaque semaine.",
                        "Ludothèque du Marais", "WE", 19, 8)
                )),
            new DemoProfile("demo4@pair.app", "Thomas Girard",
                "Musicien amateur, guitare et un peu de chant. Toujours partant pour une jam.",
                List.of(
                    new DemoActivityProfile("guitare", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "Folk et blues principalement", "Jam session guitare",
                        "Session libre, apportez votre instrument.",
                        "Local associatif Bastille", "FR", 20, 12),
                    new DemoActivityProfile("jam-session", ActivityLevel.ANY, ActivityFormat.GROUP,
                        "Tous instruments bienvenus", "Bœuf musical mensuel",
                        "Improvisation collective, tous styles et niveaux.",
                        "Studio Rive Gauche", "SA", 18, 15)
                )),
            new DemoProfile("demo5@pair.app", "Sophie Lefebvre",
                "Escalade et randonnée, j'aime être dehors le plus possible.",
                List.of(
                    new DemoActivityProfile("escalade", ActivityLevel.ADVANCED, ActivityFormat.DUO,
                        "Bloc principalement, niveau 6a+", "Session bloc en salle",
                        "Recherche partenaire régulier niveau confirmé.",
                        "Arkose Poissonnière", "TU", 18, 2),
                    new DemoActivityProfile("randonnee", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "Journée complète, 15-20km", "Rando du week-end",
                        "Randonnée à la journée, niveau intermédiaire.",
                        "Forêt de Fontainebleau", "SU", 8, 8)
                )),
            new DemoProfile("demo6@pair.app", "Antoine Petit",
                "Photographe argentique, toujours en quête de nouveaux spots.",
                List.of(
                    new DemoActivityProfile("photographie", ActivityLevel.INTERMEDIATE, ActivityFormat.DUO,
                        "Argentique, noir et blanc principalement", "Balade photo urbaine",
                        "Exploration photo de quartiers méconnus.",
                        "Parvis Notre-Dame", "SA", 10, 3)
                )),
            new DemoProfile("demo7@pair.app", "Marine Dubois",
                "Cuisine du monde, je teste une nouvelle recette par semaine.",
                List.of(
                    new DemoActivityProfile("cuisine-du-monde", ActivityLevel.BEGINNER, ActivityFormat.GROUP,
                        "Spécialités asiatiques et moyen-orientales", "Atelier cuisine partagée",
                        "On cuisine ensemble puis on déguste, convivial.",
                        "Cuisine collective Belleville", "WE", 19, 6)
                )),
            new DemoProfile("demo8@pair.app", "Hugo Rousseau",
                "Développeur, je code des side-projects et j'aime en discuter.",
                List.of(
                    new DemoActivityProfile("programmation", ActivityLevel.ADVANCED, ActivityFormat.GROUP,
                        "Web et IA principalement", "Coworking dev du mardi",
                        "Session de code en groupe, projets personnels ou open source.",
                        "Mutinerie Coworking", "TU", 18, 10)
                )),
            new DemoProfile("demo9@pair.app", "Inès Benali",
                "Bénévole engagée, actions environnementales le week-end.",
                List.of(
                    new DemoActivityProfile("benevolat-environnement", ActivityLevel.ANY, ActivityFormat.GROUP,
                        "Nettoyage et sensibilisation", "Nettoyage des berges",
                        "Action de nettoyage collectif, matériel fourni.",
                        "Berges de Seine", "SU", 10, 20)
                )),
            new DemoProfile("demo10@pair.app", "Nicolas Faure",
                "Danse swing depuis 2 ans, je cherche des partenaires d'entraînement.",
                List.of(
                    new DemoActivityProfile("danse", ActivityLevel.INTERMEDIATE, ActivityFormat.DUO,
                        "Lindy hop principalement", "Pratique swing du mercredi",
                        "Entraînement libre, musique swing, tous niveaux acceptés.",
                        "Salle Edison", "WE", 19, 4)
                )),
            new DemoProfile("demo11@pair.app", "Emma Laurent",
                "Passionnée de natation et de sports aquatiques.",
                List.of(
                    new DemoActivityProfile("natation", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "Crawl et dos crawlé", "Entraînement natation",
                        "Séances d'entraînement technique, rythme modéré.",
                        "Piscine Georges Vallerey", "MO", 19, 8)
                )),
            new DemoProfile("demo12@pair.app", "Lucas Martin",
                "Footballeur amateur, je cherche des équipes pour jouer le soir.",
                List.of(
                    new DemoActivityProfile("football", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "Football à 7, tous postes", "Match amical du jeudi",
                        "Match amical, ambiance fair-play, niveau intermédiaire.",
                        "Terrain Léo Lagrange", "TH", 20, 14)
                )),
            new DemoProfile("demo13@pair.app", "Sarah Cohen",
                "Méditation et pleine conscience pour gérer le stress.",
                List.of(
                    new DemoActivityProfile("meditation", ActivityLevel.BEGINNER, ActivityFormat.GROUP,
                        "Méditation guidée, débutants bienvenus", "Séance méditation du matin",
                        "Méditation guidée pour commencer la journée en douceur.",
                        "Centre Zen Paris", "SA", 8, 12)
                )),
            new DemoProfile("demo14@pair.app", "Alexandre Dubois",
                "Adepte de VTT et de sorties en nature le week-end.",
                List.of(
                    new DemoActivityProfile("vtt", ActivityLevel.ADVANCED, ActivityFormat.GROUP,
                        "All-mountain, niveau confirmé", "Sortie VTT du dimanche",
                        "Parcours technique en forêt, dénivelé important.",
                        "Parking Forêt de Rambouillet", "SU", 9, 6)
                )),
            new DemoProfile("demo15@pair.app", "Chloé Bernard",
                "Tennis en double, je cherche des partenaires réguliers.",
                List.of(
                    new DemoActivityProfile("tennis", ActivityLevel.INTERMEDIATE, ActivityFormat.DUO,
                        "Double mixte de préférence", "Session tennis double",
                        "Parties de double, niveau intermédiaire, ambiance amicale.",
                        "Tennis Club de Paris", "WE", 10, 4)
                )),
            new DemoProfile("demo16@pair.app", "Maxime Roux",
                "Passionné de jeux vidéo, surtout les jeux coopératifs.",
                List.of(
                    new DemoActivityProfile("jeux-video", ActivityLevel.ANY, ActivityFormat.GROUP,
                        "Jeux coop et compétitifs", "Soirée gaming",
                        "Soirée jeux vidéo en local ou en ligne, tous niveaux.",
                        "Gaming Bar Level Up", "FR", 20, 8)
                )),
            new DemoProfile("demo17@pair.app", "Julie Petit",
                "Peinture acrylique et aquarelle, je cherche à progresser.",
                List.of(
                    new DemoActivityProfile("peinture", ActivityLevel.BEGINNER, ActivityFormat.GROUP,
                        "Acrylique et aquarelle", "Atelier peinture libre",
                        "Atelier de peinture sans contrainte, tous styles acceptés.",
                        "Atelier d'Art Montmartre", "SA", 14, 6)
                )),
            new DemoProfile("demo18@pair.app", "Pierre Leroy",
                "Amateur de jeux de rôle, maître de jeu occasionnel.",
                List.of(
                    new DemoActivityProfile("jeux-de-role", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "D&D 5e et Pathfinder", "Campagne JDR hebdomadaire",
                        "Campagne longue, ambiance immersive, joueurs motivés.",
                        "Local JDR Bastille", "TH", 19, 5)
                )),
            new DemoProfile("demo19@pair.app", "Anaïs Moreau",
                "Œnologie débutante, j'adore découvrir de nouveaux vins.",
                List.of(
                    new DemoActivityProfile("oenologie", ActivityLevel.BEGINNER, ActivityFormat.GROUP,
                        "Découverte des régions viticoles", "Dégustation vin du vendredi",
                        "Dégustation commentée, découverte des terroirs français.",
                        "Cave Le Sommelier", "FR", 19, 10)
                )),
            new DemoProfile("demo20@pair.app", "Benjamin Girard",
                "Écriture créative et poésie, j'aime partager mes textes.",
                List.of(
                    new DemoActivityProfile("ecriture", ActivityLevel.ANY, ActivityFormat.GROUP,
                        "Fiction et poésie", "Atelier d'écriture créative",
                        "Atelier d'écriture libre, partage de textes, feedback bienveillant.",
                        "Bibliothèque Marguerite Duras", "WE", 18, 12)
                ))
        );
    }

    record DemoProfile(String email, String displayName, String bio,
                       List<DemoActivityProfile> activities) {}

    record DemoActivityProfile(
        String activitySlug, ActivityLevel level, ActivityFormat format,
        String customDescription, String programTitle, String programDescription,
        String placeName, String dayOfWeek, int hour, int maxParticipants) {}
}
