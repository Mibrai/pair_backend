package org.program.pair.domain.chat;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.chat.dto.*;
import org.program.pair.domain.notification.UnreadChangedEvent;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.repository.*;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final MessageEditHistoryRepository messageEditHistoryRepository;
    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final ProgramRepository programRepository;
    private final UserProgramRepository userProgramRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final HtmlSanitizer sanitizer;
    private final BlockFilterService blockFilterService;
    private final ApplicationEventPublisher eventPublisher;
    private final org.program.pair.domain.media.MediaFileService mediaFileService;

    /** Longueur de l'aperçu de message porté par la push. */
    private static final int PREVIEW_MAX_LENGTH = 120;

    public ConversationSummaryDto createConversation(UUID initiatorId,
                                                      CreateConversationRequest request) {
        return createConversation(initiatorId, request, null, null);
    }

    /**
     * Ouvre — ou retrouve — la conversation directe entre deux personnes, en y
     * inscrivant le contexte qui les lie.
     *
     * <p>{@code derivedProgramId} et {@code derivedScheduleId} ne viennent pas du
     * client : ils sont dérivés du créneau par l'appelant qui le connaît
     * ({@code SlotService} au moment de rejoindre) et l'emportent sur le
     * {@code programId} du corps, plus précis qu'un programme nommé de loin.
     */
    public ConversationSummaryDto createConversation(UUID initiatorId,
                                                      CreateConversationRequest request,
                                                      UUID derivedProgramId,
                                                      UUID derivedScheduleId) {
        // 1. Check if target accepts messages
        User target = userRepository.findById(request.targetUserId())
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_UTILISATEUR_INTROUVABLE", "Utilisateur introuvable."));

        // Avant tout le reste : les refus qui suivent sont bavards, et l'un
        // d'eux appris par une personne bloquée lui dirait que le compte visé
        // existe et va bien.
        if (blockFilterService.blockedBy(initiatorId, request.targetUserId())) {
            throw new ForbiddenException(ErrorCode.USER_BLOCKED,
                "Vous avez bloqué cette personne.");
        }
        if (blockFilterService.blocked(initiatorId, request.targetUserId())) {
            // L'autre sens : rien ne doit distinguer ce refus de celui d'un
            // compte qui n'existe pas.
            throw new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_UTILISATEUR_INTROUVABLE", "Utilisateur introuvable.");
        }

        if (!Boolean.TRUE.equals(target.getReceiveMessages())) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_MESSAGES_NON_ACCEPTES", "Cet utilisateur n'accepte pas les messages.");
        }

        UUID programId = derivedProgramId != null ? derivedProgramId : request.programId();

        // 2. L'auteur du programme peut refuser les messages de ses participants.
        //    Vérifié avant toute écriture : un refus ne doit pas laisser derrière
        //    lui une conversation vide.
        if (programId != null) {
            messagingPolicyOf(programId).ifPresent(policy -> {
                if (policy.refuses(initiatorId, request.targetUserId())) {
                    throw new ForbiddenException(ErrorCode.PROGRAM_MESSAGES_DISABLED,
                        "L'auteur de ce programme n'accepte pas les messages de ses participants.");
                }
            });
        }

        // 3. Check if DIRECT conversation already exists
        final UUID effectiveProgramId = programId;
        return conversationRepository
            .findDirectBetween(initiatorId, request.targetUserId())
            .map(conv -> {
                // Le contexte d'une conversation qui existe déjà est rafraîchi,
                // pas conservé : c'est la séance qu'on vient de rejoindre
                // ensemble qui lie les deux personnes maintenant, et c'est sa
                // date que le client compare pour griser le fil. Garder la
                // première fixerait l'en-tête sur un créneau passé alors qu'un
                // autre est à venir.
                applyContext(conv, request.activityContextId(), effectiveProgramId, derivedScheduleId);
                return toSummaryDto(conversationRepository.save(conv), initiatorId);
            })
            .orElseGet(() -> {
                Conversation conv = new Conversation();
                conv.setType(ConversationType.DIRECT);
                applyContext(conv, request.activityContextId(), effectiveProgramId, derivedScheduleId);
                Conversation saved = conversationRepository.save(conv);

                // Add both members
                addMember(saved.getId(), initiatorId);
                addMember(saved.getId(), request.targetUserId());

                return toSummaryDto(saved, initiatorId);
            });
    }

    /**
     * Réglage d'autorisation du programme, s'il existe encore.
     *
     * <p>Un programme introuvable ne refuse rien : il est traité comme une
     * absence de contexte, pas comme un refus. Un programme supprimé entre-temps
     * ne doit pas rendre une conversation impossible à ouvrir.
     */
    private Conversation loadConversation(UUID conversationId) {
        return conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CONVERSATION_INTROUVABLE", "Conversation introuvable."));
    }

    private Optional<ProgramMessagingPolicy> messagingPolicyOf(UUID programId) {
        return programRepository.findMessagingPolicy(programId);
    }

    /**
     * Droit de <b>lire</b> une conversation.
     *
     * <p>Deux règles, selon la nature du fil. Une conversation directe s'ouvre à
     * ses membres inscrits. Un fil de diffusion s'ouvre à l'auteur du programme
     * et à ses participants actifs — dérivé à chaque accès, jamais lu dans
     * {@code conversation_members} : c'est ce qui fait qu'un participant parti
     * perd le fil <b>et son historique</b> à l'instant où il part, sans qu'aucun
     * traitement n'ait eu à passer derrière lui.
     */
    private void assertMayRead(Conversation conv, UUID userId) {
        if (conv.getType() == ConversationType.PROGRAM_BROADCAST) {
            if (conv.getProgramId() == null
                    || !broadcastMemberIds(conv.getProgramId()).contains(userId)) {
                throw new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_ACCES_CONVERSATION", "Accès conversation refusé.");
            }
            return;
        }
        if (!conversationMemberRepository.existsByConversationIdAndUserId(conv.getId(), userId)) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_ACCES_CONVERSATION", "Accès conversation refusé.");
        }
    }

    /**
     * Le blocage vaut aussi dans un fil <b>déjà ouvert</b>.
     *
     * <p><b>Le défaut fermé ici.</b> {@code blockFilterService} n'était consulté
     * qu'à la création d'une conversation. Or le fil à deux naît tout seul en
     * rejoignant un créneau ({@code SlotService.joinSlot}) : au moment du
     * blocage, il existe déjà dans la quasi-totalité des cas, et rien n'empêchait
     * plus d'y écrire. Bloquer quelqu'un et le voir continuer d'écrire est ce qui
     * fait qu'on n'utilise plus l'application.
     *
     * <p><b>Deux refus, deux formes, et c'est toute la règle.</b> Celle qui a
     * bloqué reçoit un code nommé — elle sait pourquoi, c'est sa décision. Celle
     * qui est bloquée reçoit le refus d'accès générique, mot pour mot celui qu'un
     * non-membre reçoit : un code nommé lui apprendrait le blocage.
     *
     * <p><b>La lecture n'est jamais touchée.</b> L'historique reste lisible des
     * deux côtés (D6) : c'est une preuve pour un signalement, et l'effacer d'un
     * côté priverait la personne visée de ce qu'elle a besoin de montrer.
     *
     * <p>Les fils de groupe et de diffusion ne passent pas par ici : on n'y refuse
     * rien, on y retire des destinataires (voir {@link #persistAndDeliver}). Un
     * fil à trente personnes ne se ferme pas parce que deux de ses membres se sont
     * bloqués.
     */
    private void assertNotBlockedIn(Conversation conv, UUID senderId) {
        if (conv.getType() != ConversationType.DIRECT) {
            return;
        }
        UUID other = otherMemberOf(conv, senderId);
        if (other == null) {
            return;
        }
        if (blockFilterService.blockedBy(senderId, other)) {
            throw new ForbiddenException(ErrorCode.USER_BLOCKED,
                "Vous avez bloqué cette personne.");
        }
        if (blockFilterService.blocked(senderId, other)) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_ACCES_CONVERSATION", "Accès conversation refusé.");
        }
    }

    /** L'autre membre d'un fil à deux, ou {@code null} s'il n'y en a pas. */
    private UUID otherMemberOf(Conversation conv, UUID userId) {
        return conversationMemberRepository.findUserIdsByConversationId(conv.getId()).stream()
            .filter(id -> !id.equals(userId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Un fil de diffusion n'a qu'une plume.
     *
     * <p>Les participants y sont en lecture seule — le composeur disparaît chez
     * eux, mais c'est ici que la règle tient : un client modifié ne doit pas
     * pouvoir écrire dans un fil qui n'est pas le sien.
     */
    private void assertMayWriteInBroadcast(Conversation conv, UUID senderId) {
        if (conv.getType() != ConversationType.PROGRAM_BROADCAST) {
            return;
        }
        boolean isAuthor = conv.getProgramId() != null
            && messagingPolicyOf(conv.getProgramId())
                .map(policy -> senderId.equals(policy.authorId()))
                .orElse(false);
        if (!isAuthor) {
            throw new ForbiddenException(ErrorCode.PROGRAM_BROADCAST_READ_ONLY,
                "Seul l'auteur du programme peut écrire dans ce fil de diffusion.");
        }
    }

    /**
     * Diffuse un message à tous les participants d'un programme.
     *
     * <p>Le fil naît ici, à la première diffusion, plutôt qu'à la création du
     * programme : inutile de peupler la base de fils vides que personne n'ouvrira.
     * Les suivantes réutilisent le même — un seul fil par programme, garanti par
     * un index unique partiel (V53) autant que par cette lecture.
     */
    public MessageDto broadcastToProgram(UUID authorId, UUID programId, String content) {
        ProgramMessagingPolicy policy = messagingPolicyOf(programId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_PROGRAMME_INTROUVABLE", "Programme introuvable."));

        if (!authorId.equals(policy.authorId())) {
            throw new ForbiddenException(ErrorCode.PROGRAM_BROADCAST_READ_ONLY,
                "Seul l'auteur du programme peut diffuser un message.");
        }

        Conversation conv = conversationRepository.findBroadcastByProgramId(programId)
            .orElseGet(() -> {
                Conversation created = new Conversation();
                created.setType(ConversationType.PROGRAM_BROADCAST);
                created.setProgramId(programId);
                return conversationRepository.save(created);
            });

        // Ligne de membre de l'auteur : elle ne lui donne aucun droit — il les
        // tient du programme — mais lui ouvre un lastReadAt, sans quoi ses propres
        // diffusions lui reviendraient comme non lues.
        ensureMemberRow(conv.getId(), authorId);

        return sendMessage(authorId, new SendMessageRequest(conv.getId(), content));
    }

    /**
     * Garantit qu'une ligne de membre existe, pour porter {@code lastReadAt}.
     *
     * <p>Sur un fil de diffusion, cette ligne n'est <b>pas</b> un droit d'accès :
     * elle est créée quand quelqu'un lit, et sa présence après un départ ne rouvre
     * rien — {@link #assertMayRead} ne la consulte pas, et le compte de non-lus
     * l'écarte à son tour.
     */
    private void ensureMemberRow(UUID conversationId, UUID userId) {
        if (!conversationMemberRepository.existsByConversationIdAndUserId(conversationId, userId)) {
            addMember(conversationId, userId);
        }
    }

    /**
     * Refuse l'écriture d'un participant dans un fil rattaché à un programme dont
     * l'auteur n'accepte pas les messages.
     *
     * <p>Le refus exige que l'auteur soit <b>membre du fil</b> : deux participants
     * qui discutent entre eux au sujet d'un programme ne sont pas concernés par un
     * réglage qui porte sur ce que l'auteur reçoit. L'auteur, lui, garde le droit
     * d'écrire en toutes circonstances.
     */
    private void assertMayWriteInProgramThread(Conversation conv, UUID senderId) {
        if (conv.getProgramId() == null) {
            return;
        }
        messagingPolicyOf(conv.getProgramId()).ifPresent(policy -> {
            if (senderId.equals(policy.authorId())
                    || Boolean.TRUE.equals(policy.allowParticipantMessages())) {
                return;
            }
            if (conversationMemberRepository
                    .existsByConversationIdAndUserId(conv.getId(), policy.authorId())) {
                throw new ForbiddenException(ErrorCode.PROGRAM_MESSAGES_DISABLED,
                    "L'auteur de ce programme n'accepte pas les messages de ses participants.");
            }
        });
    }

    /**
     * Écrit le contexte sur la conversation, sans l'effacer quand rien n'est
     * fourni : une conversation rouverte depuis un profil ne doit pas perdre le
     * programme et la séance qu'un passage par un créneau lui avait donnés.
     */
    private void applyContext(Conversation conv, UUID activityContextId,
                              UUID programId, UUID scheduleId) {
        if (activityContextId != null) {
            conv.setActivityContext(activityRepository.findById(activityContextId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_ACTIVITE_INTROUVABLE", "Activité introuvable.")));
        }
        if (programId != null) {
            conv.setProgramId(programId);
        }
        if (scheduleId != null) {
            conv.setScheduleId(scheduleId);
        }
    }

    public MessageDto sendMessage(UUID senderId, SendMessageRequest request) {
        // 1. Verify sender is member of conversation
        Conversation conv = conversationRepository.findById(request.conversationId())
            .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_ACCES_CONVERSATION", "Accès conversation refusé."));
        assertMayRead(conv, senderId);

        // 1 bis. Le blocage, avant tous les autres refus d'écriture : ceux qui
        // suivent nomment le programme et son réglage, et l'un d'eux rendu à une
        // personne bloquée lui apprendrait que le fil vit toujours.
        assertNotBlockedIn(conv, senderId);

        // 1 ter. Un fil de diffusion n'a qu'une plume : celle de l'auteur.
        assertMayWriteInBroadcast(conv, senderId);

        // 1 quater. Le refus de l'auteur vaut aussi sur un fil déjà ouvert.
        //
        // Ne le vérifier qu'à la création laisserait passer tout participant
        // ayant déjà écrit une fois — et la conversation ouverte
        // automatiquement en rejoignant un créneau fait que c'est le cas de
        // presque tous. Le réglage ne serait alors qu'un drapeau d'affichage,
        // exactement ce que la demande écarte.
        //
        // La lecture n'est jamais touchée : lecture seule veut dire lecture.
        assertMayWriteInProgramThread(conv, senderId);

        // 2. Sanitize content (anti-XSS required)
        String cleanContent = sanitizer.sanitize(request.content());
        if (!StringUtils.hasText(cleanContent)) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR, "REFUS_MESSAGE_VIDE", "Message vide après sanitisation.");
        }

        // 3 à 5 : écriture, diffusion, push.
        return persistAndDeliver(senderId, conv, cleanContent, null, null, null);
    }

    /**
     * Écrit le message, le diffuse et déclenche les pushes.
     *
     * <p>Extrait de {@code sendMessage} pour que le partage de position emprunte
     * exactement le même chemin. Un partage de position <b>est</b> un message :
     * il apparaît dans le fil, il compte comme non lu, il notifie. Lui écrire un
     * chemin parallèle aurait fait diverger les deux le jour où l'un des deux
     * change — et la liste des destinataires d'un fil de diffusion est la
     * dernière chose qu'on veut voir calculée à deux endroits.
     *
     * <p>Les contrôles d'accès restent chez l'appelant : ce sont eux qui
     * distinguent les deux gestes, l'un pouvant être refusé là où l'autre passe.
     */
    private MessageDto persistAndDeliver(UUID senderId, Conversation conv, String content,
                                         Double lat, Double lng, Instant locationExpiresAt) {
        User sender = userRepository.findById(senderId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_UTILISATEUR_INTROUVABLE", "Utilisateur introuvable."));

        Message message = new Message();
        message.setConversation(conv);
        message.setSender(sender);
        message.setContent(content);
        message.setStatus(MessageStatus.SENT);
        message.setLocationLat(lat);
        message.setLocationLng(lng);
        message.setLocationExpiresAt(locationExpiresAt);
        message = messageRepository.save(message);

        MessageDto dto = toMessageDto(message);

        // Destinataires. Pour un fil de diffusion, ils sont dérivés des
        // inscriptions actives au moment de l'envoi — pas d'une liste de membres
        // recopiée, qui aurait divergé dès la première inscription.
        //
        // Puis retrait de ceux pour qui l'expéditeur est invisible. C'est la
        // moitié « groupe » de la règle de blocage : dans un fil à deux l'envoi
        // est déjà refusé (assertNotBlockedIn), mais un fil de groupe ou de
        // diffusion ne se ferme pas — il cesse simplement de porter jusqu'à ceux
        // qui ont bloqué l'auteur, WebSocket comme push.
        //
        // Un seul appel à invisibleTo par envoi, jamais un par message : sur un
        // fil de diffusion à trente personnes, la différence est celle entre une
        // requête et trente (P-BA-15).
        Set<UUID> invisibleToSender = blockFilterService.invisibleTo(senderId);
        List<UUID> memberIds = recipientsOf(conv).stream()
            .filter(memberId -> !invisibleToSender.contains(memberId))
            .toList();

        // La sourdine ne retire personne d'ici : une application ouverte sur le
        // fil doit voir le message arriver. Elle ne coupe que la push, plus bas.
        for (UUID memberId : memberIds) {
            messagingTemplate.convertAndSendToUser(
                memberId.toString(),
                "/queue/messages",
                dto
            );
        }

        // Push aux destinataires — le WebSocket ci-dessus ne porte que jusqu'à
        // une app ouverte, or le badge sert précisément quand elle est fermée.
        // L'expéditeur est exclu : il vient d'écrire, il n'a rien à lire.
        String programTitle = conv.getType() == ConversationType.PROGRAM_BROADCAST
            ? contextOf(conv.getId()).programTitle()
            : null;

        Set<UUID> muted = Set.copyOf(
            conversationMemberRepository.findMutedUserIdsByConversationId(conv.getId()));

        for (UUID memberId : memberIds) {
            if (memberId.equals(senderId) || muted.contains(memberId)) {
                continue;
            }
            eventPublisher.publishEvent(new MessageSentEvent(
                memberId,
                senderId,
                conv.getId(),
                message.getId(),
                sender.getDisplayName(),
                preview(content),
                conv.getType() == ConversationType.PROGRAM_BROADCAST ? conv.getProgramId() : null,
                programTitle));
        }

        return dto;
    }

    /**
     * Durée maximale d'un partage de position, en minutes.
     *
     * <p>Garde-fou n°4. La borne n'est pas un réglage de confort : c'est elle qui
     * fait la différence entre « je te dis où je suis » et un suivi. Une demande
     * qui la dépasse est refusée plutôt que rabotée — raboter en silence
     * laisserait l'appelant croire qu'il a obtenu ce qu'il demandait.
     */
    public static final int MAX_LOCATION_SHARE_MINUTES = 30;

    /** Ce qu'affiche le fil quand rien n'est joint au partage. */
    private static final String DEFAULT_LOCATION_NOTE = "Position partagée.";

    /**
     * Partage ponctuel de position dans une conversation.
     *
     * <p><b>Ponctuel veut dire un point, pas un flux.</b> La position est celle
     * que l'appelant transmet au moment de l'envoi ; elle ne se met jamais à
     * jour, et rien ne permet d'en demander une plus récente. Renouveler suppose
     * un nouveau message, donc une nouvelle bulle dans le fil : suivre quelqu'un
     * resterait visible de celui qu'on suit, ce qui est toute la protection.
     *
     * <p>Le message emprunte le chemin d'un message ordinaire, contrôles
     * d'accès compris — un fil de diffusion ne se partage pas plus une position
     * qu'il ne se répond, et une lecture seule reste une lecture seule.
     */
    public MessageDto shareLocation(UUID senderId, UUID conversationId, ShareLocationRequest request) {
        Conversation conv = loadConversation(conversationId);
        assertMayRead(conv, senderId);
        // Dans le même ordre que sendMessage, et c'est le contrôle qui compte le
        // plus ici : une position partagée dit où l'on est, et la partager à
        // quelqu'un qu'on a bloqué — ou qui nous a bloqué — est exactement ce que
        // le blocage existe pour empêcher.
        assertNotBlockedIn(conv, senderId);
        assertMayWriteInBroadcast(conv, senderId);
        assertMayWriteInProgramThread(conv, senderId);

        int minutes = request.expiresInMinutes() == null
            ? MAX_LOCATION_SHARE_MINUTES
            : request.expiresInMinutes();
        if (minutes > MAX_LOCATION_SHARE_MINUTES) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR, "REFUS_PARTAGE_POSITION_TROP_LONG",
                "Un partage de position ne peut pas dépasser "
                    + MAX_LOCATION_SHARE_MINUTES + " minutes.", MAX_LOCATION_SHARE_MINUTES);
        }

        // Le mot joint passe par le même assainissement que n'importe quel
        // contenu : il finit dans une bulle de conversation, au même titre.
        String note = request.note() == null ? null : sanitizer.sanitize(request.note());
        String content = StringUtils.hasText(note) ? note : DEFAULT_LOCATION_NOTE;

        return persistAndDeliver(senderId, conv, content,
            request.lat(), request.lng(),
            Instant.now().plus(minutes, ChronoUnit.MINUTES));
    }

    /**
     * Sourdine et archivage, pour l'appelant seul.
     *
     * <p>Les deux réglages sont indépendants et ne se déduisent pas l'un de
     * l'autre : on peut archiver un fil qu'on veut continuer d'entendre, et
     * mettre en sourdine un fil qu'on garde sous les yeux. Un champ absent reste
     * inchangé, de sorte que régler l'un ne remette pas l'autre à sa valeur par
     * défaut — c'est ce qui distingue un PATCH d'un PUT, et ici cela compte : les
     * deux commandes vivent sur deux écrans différents.
     *
     * <p>La ligne d'appartenance est créée si elle manque, comme à la première
     * lecture : sur un fil de diffusion, l'accès vient du programme et non d'elle.
     */
    public ConversationSummaryDto updateSettings(UUID userId, UUID conversationId,
                                                 Boolean muted, Boolean archived) {
        Conversation conv = loadConversation(conversationId);
        assertMayRead(conv, userId);
        ensureMemberRow(conversationId, userId);

        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_MEMBRE_INTROUVABLE", "Membre introuvable."));

        // La date n'est réécrite que sur un vrai changement d'état : réappliquer
        // « en sourdine » à un fil déjà en sourdine ne doit pas faire croire que
        // le geste vient d'être refait. Le réseau mobile double les requêtes.
        if (muted != null) {
            if (muted && member.getMutedAt() == null) {
                member.setMutedAt(Instant.now());
            } else if (!muted) {
                member.setMutedAt(null);
            }
        }
        if (archived != null) {
            if (archived && member.getArchivedAt() == null) {
                member.setArchivedAt(Instant.now());
            } else if (!archived) {
                member.setArchivedAt(null);
            }
        }
        conversationMemberRepository.save(member);

        // Le badge bouge : mettre en sourdine ou archiver retire des messages non
        // lus du total, sans qu'aucun ait été lu.
        eventPublisher.publishEvent(new UnreadChangedEvent(userId));

        return toSummaryDto(conv, userId, contextOf(conversationId));
    }

    /**
     * Indicateur de saisie.
     *
     * <p><b>Rien n'est écrit nulle part.</b> Un « untel écrit… » n'a de valeur
     * que dans la seconde où il est émis ; le persister reviendrait à conserver
     * une trace de qui a commencé à écrire puis renoncé, ce que personne n'a
     * demandé. Il ne touche donc ni le fil, ni la date de lecture, ni le badge,
     * et ne déclenche aucune push : il n'existe que pour une application ouverte.
     *
     * <p>L'appartenance est vérifiée malgré tout. Sans ce contrôle, n'importe
     * quel compte connecté pourrait faire apparaître son nom dans le fil de
     * n'importe qui, ce qui suffirait à découvrir l'existence d'une conversation.
     *
     * <p>Le serveur ne pose aucune échéance et n'émet aucun rappel : c'est au
     * client d'effacer l'indicateur au bout de quelques secondes sans nouvelle.
     * Un émetteur qui perd sa connexion juste après avoir annoncé qu'il écrivait
     * ne pourra jamais annoncer le contraire, et l'indicateur resterait sinon
     * allumé pour toujours.
     */
    @Transactional(readOnly = true)
    public void typing(UUID userId, UUID conversationId, boolean typing) {
        Conversation conv = loadConversation(conversationId);
        assertMayRead(conv, userId);

        TypingEventDto event = new TypingEventDto(conversationId, userId, typing);

        // Même retrait qu'à l'envoi, et pour la même raison : « untel écrit… »
        // est un signe de présence, et le faire apparaître chez quelqu'un qui a
        // bloqué son auteur rendrait le blocage inutile sur le seul écran où il
        // compte le plus.
        Set<UUID> invisibleToSender = blockFilterService.invisibleTo(userId);

        for (UUID memberId : recipientsOf(conv)) {
            if (!memberId.equals(userId) && !invisibleToSender.contains(memberId)) {
                messagingTemplate.convertAndSendToUser(
                    memberId.toString(), "/queue/typing", event);
            }
        }
    }

    /**
     * À qui ce message doit parvenir.
     *
     * <p>Un fil de diffusion sert ses participants <b>actifs du moment</b> et son
     * auteur ; une conversation directe, ses membres. La liste des membres n'est
     * jamais l'autorité pour un fil de diffusion : elle ne porte que la lecture.
     */
    private List<UUID> recipientsOf(Conversation conv) {
        if (conv.getType() != ConversationType.PROGRAM_BROADCAST || conv.getProgramId() == null) {
            return conversationMemberRepository.findUserIdsByConversationId(conv.getId());
        }
        return broadcastMemberIds(conv.getProgramId());
    }

    /** Auteur du programme et participants actifs, sans doublon, l'auteur d'abord. */
    private List<UUID> broadcastMemberIds(UUID programId) {
        List<UUID> members = new ArrayList<>();
        messagingPolicyOf(programId).map(ProgramMessagingPolicy::authorId).ifPresent(members::add);
        for (UUID participantId : userProgramRepository.findActiveParticipantIdsByProgramId(programId)) {
            if (!members.contains(participantId)) {
                members.add(participantId);
            }
        }
        return members;
    }

    /**
     * Aperçu affiché sur l'écran verrouillé. Tronqué : {@code content} monte à
     * 4000 caractères, une notification n'en montre qu'une poignée, et la charge
     * push est plafonnée à 4 Ko par APNs.
     *
     * <p>La coupe tombe sur une <b>frontière de mot</b> quand il y en a une dans
     * la fenêtre : couper au caractère près donne « … devant le cou… », qu'un
     * lecteur pressé lit comme un mot entier. Un texte de plus de 120 caractères
     * sans le moindre espace — une URL, un collage — n'en a pas : il est alors
     * coupé net, la seule règle qui tienne étant de ne pas dépasser.
     */
    private static String preview(String content) {
        if (content.length() <= PREVIEW_MAX_LENGTH) {
            return content;
        }
        String window = content.substring(0, PREVIEW_MAX_LENGTH);
        int lastSpace = window.lastIndexOf(' ');
        String cut = lastSpace > 0 ? window.substring(0, lastSpace) : window;
        // stripTrailing : la ponctuation reste, mais « bonjour , » ne doit pas
        // devenir « bonjour  … ».
        return cut.stripTrailing() + "…";
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getMyConversations(UUID userId) {
        return getMyConversations(userId, false);
    }

    /**
     * Les conversations de quelqu'un, archivées ou non.
     *
     * <p>Les deux listes sont disjointes et jamais mélangées : {@code archived}
     * choisit laquelle on veut. Rendre les archivées au milieu des autres, même
     * marquées, aurait fait de l'archivage un simple drapeau d'affichage — or
     * ranger un fil, c'est demander qu'il quitte l'écran.
     */
    public List<ConversationSummaryDto> getMyConversations(UUID userId, boolean archived) {
        // Deux sources, et c'est voulu. Les lignes de membre donnent les
        // conversations directes ; les fils de diffusion, eux, se dérivent des
        // inscriptions actives — un nouvel inscrit voit le fil sans qu'aucune
        // ligne ait eu à être écrite pour lui, et un partant cesse de le voir
        // même si la sienne subsiste.
        List<Conversation> conversations = new ArrayList<>();
        for (Conversation conv : conversationRepository.findByMemberId(userId)) {
            if (conv.getType() != ConversationType.PROGRAM_BROADCAST) {
                conversations.add(conv);
            }
        }
        conversations.addAll(conversationRepository.findBroadcastsForMember(userId));

        // Contextes chargés en une fois pour toute la liste, plutôt qu'un aller
        // par fil : l'écran de messagerie les demande tous, à chaque ouverture.
        Map<UUID, ConversationContextDto> contexts = contextsOf(
            conversations.stream().map(Conversation::getId).toList());

        // Un seul calcul des masquages pour toute la liste, et non un par fil :
        // c'est la règle que BlockFilterService pose pour les surfaces qui n'ont
        // pas d'autre choix que de filtrer en mémoire.
        Set<UUID> invisible = blockFilterService.invisibleTo(userId);

        return conversations.stream()
            .map(conv -> toSummaryDto(conv, userId,
                contexts.getOrDefault(conv.getId(), ConversationContextDto.empty(conv.getId())),
                // Pas de relecture du réglage d'autorisation par ligne : voir
                // readOnlyReasonFor.
                invisible, false))
            .filter(summary -> summary.archived() == archived)
            .collect(Collectors.toList());
    }

    private Map<UUID, ConversationContextDto> contextsOf(List<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        return conversationRepository.findContextsByIds(conversationIds).stream()
            .collect(Collectors.toMap(ConversationContextDto::conversationId, ctx -> ctx));
    }

    private ConversationContextDto contextOf(UUID conversationId) {
        return conversationRepository.findContextsByIds(List.of(conversationId)).stream()
            .findFirst()
            .orElseGet(() -> ConversationContextDto.empty(conversationId));
    }

    /**
     * L'historique d'un fil.
     *
     * <p><b>Un fil à deux garde tout, y compris après un blocage</b>, et c'est la
     * décision D6 : ce qui a été écrit avant est une preuve pour un signalement, et
     * l'effacer de la vue de la personne visée la priverait de ce qu'elle a besoin
     * de montrer. Le fil ne s'écrit plus (voir {@link #assertNotBlockedIn}), mais
     * lecture seule veut dire lecture.
     *
     * <p><b>Un fil de groupe ou de diffusion, lui, écarte les messages de qui est
     * invisible pour l'appelant.</b> La différence tient à ce qu'un fil à deux se
     * quitte — il disparaît de la liste des deux côtés — là où un fil de groupe
     * continue de servir vingt-neuf autres personnes.
     *
     * <p>Ce filtre passe <b>après</b> le {@code LIMIT}, ce que
     * {@code BlockFilterService} déconseille en général : une page peut donc
     * rendre moins d'éléments que demandé. C'est acceptable ici et nulle part
     * ailleurs — cette lecture n'est pas paginée par curseur, elle rend « les N
     * derniers », et un N un peu plus petit ne fait perdre aucun message au
     * client. Filtrer en base demanderait de porter la liste des personnes
     * masquées dans la requête, ce que le prédicat de {@code BlockSql} ne sait
     * pas faire sur l'auteur d'un message.
     */
    @Transactional(readOnly = true)
    public List<MessageDto> getMessages(UUID userId, UUID conversationId, int limit) {
        return getMessages(userId, conversationId, limit, null, null);
    }

    /** Plafond d'une page d'historique (règle commune de P-BA-14). */
    public static final int HISTORIQUE_MAX = org.program.pair.shared.web.Pages.TAILLE_MAX;

    /**
     * L'historique d'un fil, avec curseur (demande mobile du 13/09, P-BA-14).
     *
     * <ul>
     *   <li><b>sans curseur</b> : les {@code limit} derniers messages, <b>du plus
     *       récent au plus ancien</b> — l'ordre que l'app publiée lit déjà ;</li>
     *   <li><b>{@code after}</b> : les messages postérieurs à ce message, <b>du plus
     *       ancien au plus récent</b> — la relecture d'un fil ouvert, qui rend
     *       {@code []} quand rien n'est arrivé ;</li>
     *   <li><b>{@code before}</b> : la page précédente, <b>du plus ancien au plus
     *       récent</b> — « charger plus ».</li>
     * </ul>
     *
     * <p>Un curseur inconnu, ou d'un autre fil, rend {@code 400} : une liste vide
     * silencieuse ferait croire au client qu'il est à jour. Les deux curseurs à la
     * fois aussi.
     */
    @Transactional(readOnly = true)
    public List<MessageDto> getMessages(UUID userId, UUID conversationId, int limit, UUID after, UUID before) {
        if (limit < 1) {
            throw new ValidationException(ErrorCode.INVALID_PARAMETER, "La limite doit valoir au moins 1.");
        }
        if (after != null && before != null) {
            throw new ValidationException(ErrorCode.INVALID_PARAMETER,
                "after et before ne se combinent pas : un seul curseur à la fois.");
        }
        Conversation conv = loadConversation(conversationId);
        assertMayRead(conv, userId);

        org.springframework.data.domain.Pageable page =
            org.springframework.data.domain.PageRequest.of(0, Math.min(limit, HISTORIQUE_MAX));
        List<Message> messages;
        if (after != null) {
            Message curseur = curseur(conversationId, after);
            messages = messageRepository.findAfter(conversationId, curseur.getSentAt(), curseur.getId(), page);
        } else if (before != null) {
            Message curseur = curseur(conversationId, before);
            messages = new java.util.ArrayList<>(
                messageRepository.findBefore(conversationId, curseur.getSentAt(), curseur.getId(), page));
            java.util.Collections.reverse(messages);
        } else {
            messages = messageRepository.findLatest(conversationId, page);
        }

        if (conv.getType() != ConversationType.DIRECT) {
            Set<UUID> invisible = blockFilterService.invisibleTo(userId);
            if (!invisible.isEmpty()) {
                messages = messages.stream()
                    .filter(msg -> !invisible.contains(msg.getSender().getId()))
                    .toList();
            }
        }

        return messages.stream()
            .map(this::toMessageDto)
            .collect(Collectors.toList());
    }

    /** Le message qui sert de curseur : il doit exister, et dans ce fil. */
    private Message curseur(UUID conversationId, UUID messageId) {
        return messageRepository.findById(messageId)
            .filter(m -> m.getConversation().getId().equals(conversationId))
            .orElseThrow(() -> new ValidationException(ErrorCode.INVALID_PARAMETER,
                "Curseur inconnu : ce message n'appartient pas à ce fil."));
    }

    /**
     * Nombre de messages non lus, tous fils confondus.
     *
     * <p>Sert {@code GET /api/conversations/unread-count}, et c'est la moitié
     * « messagerie » du badge d'icône : la même requête alimente les deux, de
     * sorte que la somme du client et le nombre porté par la push ne peuvent pas
     * diverger.
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return messageRepository.countUnreadByUserId(userId);
    }

    public void markAsRead(UUID userId, UUID conversationId) {
        // Sur un fil de diffusion, la ligne de membre peut ne pas exister encore :
        // l'accès vient du programme, pas d'elle. On la crée à la première
        // lecture — c'est elle qui portera lastReadAt.
        assertMayRead(loadConversation(conversationId), userId);
        ensureMemberRow(conversationId, userId);

        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_MEMBRE_INTROUVABLE", "Membre introuvable."));

        member.setLastReadAt(Instant.now());
        conversationMemberRepository.save(member);

        // Lire ici fait baisser le badge des autres appareils du compte, qui
        // eux ne reçoivent aucune push sur une lecture.
        eventPublisher.publishEvent(new UnreadChangedEvent(userId));
    }

    private void addMember(UUID conversationId, UUID userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CONVERSATION_INTROUVABLE", "Conversation introuvable."));
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_UTILISATEUR_INTROUVABLE", "Utilisateur introuvable."));

        ConversationMember.ConversationMemberId id = new ConversationMember.ConversationMemberId();
        id.setConversationId(conversationId);
        id.setUserId(userId);

        ConversationMember member = new ConversationMember();
        member.setId(id);
        member.setConversation(conversation);
        member.setUser(user);
        member.setJoinedAt(Instant.now());
        conversationMemberRepository.save(member);
    }

    private ConversationSummaryDto toSummaryDto(Conversation conv, UUID currentUserId) {
        return toSummaryDto(conv, currentUserId, contextOf(conv.getId()));
    }

    /**
     * Le résumé d'un fil rendu seul — création, réglages.
     *
     * <p>Un fil seul paie ce que la liste ne peut pas payer : le calcul des
     * masquages et, s'il le faut, le réglage d'autorisation de son programme.
     * Voir {@link #readOnlyReasonFor}.
     */
    private ConversationSummaryDto toSummaryDto(Conversation conv, UUID currentUserId,
                                                ConversationContextDto context) {
        return toSummaryDto(conv, currentUserId, context,
            blockFilterService.invisibleTo(currentUserId), true);
    }

    private ConversationSummaryDto toSummaryDto(Conversation conv, UUID currentUserId,
                                                ConversationContextDto context,
                                                Set<UUID> invisible, boolean readProgramPolicy) {
        // Get other user for DIRECT conversation
        UserPublicDto otherUser = null;
        if (conv.getType() == ConversationType.DIRECT) {
            List<UUID> memberIds = conversationMemberRepository
                .findUserIdsByConversationId(conv.getId());
            UUID otherUserId = memberIds.stream()
                .filter(id -> !id.equals(currentUserId))
                .findFirst()
                .orElse(null);

            if (otherUserId != null) {
                User other = userRepository.findById(otherUserId).orElse(null);
                if (other != null) {
                    otherUser = UserPublicDto.identity(
                        other.getId(),
                        other.getDisplayName(),
                        other.getBio(),
                        other.getAvatarUrl(),
                        other.getVerificationStatus().name()
                    );
                }
            }
        }

        // Get last message — celui que l'appelant peut voir, pas celui qui existe.
        Message lastMsg = lastVisibleMessage(conv, invisible);

        // Non lus du fil : les messages des autres, arrivés depuis la dernière
        // lecture. Ses propres messages et ceux qui ont été supprimés n'en sont
        // pas — c'est le décompte qu'un client somme pour obtenir son badge.
        int unreadCount = messageRepository
            .countUnreadByUserIdAndConversationId(currentUserId, conv.getId());

        boolean broadcast = conv.getType() == ConversationType.PROGRAM_BROADCAST;

        // Ligne absente vaut « ni en sourdine ni archivé » : sur un fil de
        // diffusion, elle n'est écrite qu'à la première lecture ou au premier
        // réglage, et son absence ne dit rien d'autre que « jamais touché ».
        ConversationMember own = conversationMemberRepository
            .findByConversationIdAndUserId(conv.getId(), currentUserId)
            .orElse(null);

        return new ConversationSummaryDto(
            conv.getId(),
            conv.getType().name(),
            otherUser,
            context.activityName(),
            context.programId(),
            context.programTitle(),
            context.activityName(),
            context.scheduleId(),
            context.scheduleStartsAt(),
            context.scheduleEndsAt(),
            broadcast ? context.programTitle() : null,
            broadcast && conv.getProgramId() != null
                ? broadcastMemberIds(conv.getProgramId()).size() : null,
            lastMsg != null ? lastMsg.getContent() : null,
            lastMsg != null ? lastMsg.getSentAt() : conv.getCreatedAt(),
            unreadCount,
            own != null && own.getMutedAt() != null,
            own != null && own.getArchivedAt() != null,
            readOnlyReasonFor(conv, currentUserId, invisible, readProgramPolicy)
        );
    }

    /**
     * Combien de messages on remonte pour trouver un aperçu visible.
     *
     * <p>Une borne, et non « tous » : l'aperçu d'un fil de diffusion dont les
     * vingt derniers messages viennent de quelqu'un de masqué se rend vide, ce qui
     * est juste — il n'y a rien à montrer de récent — et ne coûte pas la lecture
     * d'un fil entier.
     */
    private static final int PREVIEW_LOOKBACK = 20;

    /**
     * Le dernier message que cette personne peut voir dans ce fil.
     *
     * <p><b>Le défaut fermé ici.</b> L'aperçu de la liste des fils était lu sans
     * aucun filtre : sur un fil de diffusion, le texte du message de quelqu'un de
     * bloqué s'affichait dans la liste — le masquage tenait dans le fil et tombait
     * sur l'écran qui y mène.
     *
     * <p>La seconde lecture n'a lieu que si le dernier message est masqué, donc
     * jamais dans le cas ordinaire. Un fil à deux n'est pas concerné : son
     * historique reste entier (D6), et il quitte de toute façon la liste dès le
     * blocage.
     */
    private Message lastVisibleMessage(Conversation conv, Set<UUID> invisible) {
        Message last = messageRepository
            .findFirstByConversationIdOrderBySentAtDesc(conv.getId())
            .orElse(null);

        if (last == null
                || conv.getType() == ConversationType.DIRECT
                || invisible.isEmpty()
                || !invisible.contains(last.getSender().getId())) {
            return last;
        }

        return messageRepository
            .findLatest(conv.getId(), org.springframework.data.domain.PageRequest.of(0, PREVIEW_LOOKBACK))
            .stream()
            .filter(msg -> !invisible.contains(msg.getSender().getId()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Pourquoi ce fil ne s'écrit pas pour cette personne, ou {@code null} s'il
     * s'écrit.
     *
     * <p>Le pendant, côté lecture, des refus que lèveraient
     * {@link #assertNotBlockedIn}, {@link #assertMayWriteInBroadcast} et
     * {@link #assertMayWriteInProgramThread} : les quatre causes de lecture seule
     * du produit, nommées une fois, pour que l'application retire son composeur
     * avant la frappe au lieu de perdre un message dans un 403.
     *
     * <p><b>Le côté décide de la valeur, pour le blocage seulement.</b> Celle qui
     * a bloqué lit {@code BLOCKED} ; celle qui est bloquée lit la forme neutre
     * {@code PARTICIPANT_UNAVAILABLE}, qu'un compte désactivé rendrait aussi. Voir
     * {@link ConversationReadOnlyReason}.
     *
     * @param invisible          qui est masqué pour l'appelant, calculé une fois
     *                           par requête
     * @param readProgramPolicy  vrai si l'on accepte de relire le réglage
     *                           d'autorisation du programme — une requête par fil,
     *                           que la liste des fils ne paie pas. Conséquence
     *                           assumée : un fil dont l'auteur refuse les messages
     *                           de ses participants est annoncé en lecture seule
     *                           par {@code GET /api/conversations/{id}}, qui porte
     *                           le composeur, et pas par la liste, qui n'en a pas.
     */
    private ConversationReadOnlyReason readOnlyReasonFor(Conversation conv, UUID userId,
                                                         Set<UUID> invisible,
                                                         boolean readProgramPolicy) {
        // La condition sur l'ensemble d'abord : sans personne de masquée — le cas
        // de presque tous les appels — il n'y a pas de membre à relire.
        if (conv.getType() == ConversationType.DIRECT && !invisible.isEmpty()) {
            UUID other = otherMemberOf(conv, userId);
            if (other != null && invisible.contains(other)) {
                return blockFilterService.blockedBy(userId, other)
                    ? ConversationReadOnlyReason.BLOCKED
                    : ConversationReadOnlyReason.PARTICIPANT_UNAVAILABLE;
            }
        }

        if (conv.getType() == ConversationType.PROGRAM_BROADCAST) {
            boolean isAuthor = conv.getProgramId() != null
                && messagingPolicyOf(conv.getProgramId())
                    .map(policy -> userId.equals(policy.authorId()))
                    .orElse(false);
            return isAuthor ? null : ConversationReadOnlyReason.PROGRAM_BROADCAST_READ_ONLY;
        }

        if (readProgramPolicy && conv.getProgramId() != null) {
            return messagingPolicyOf(conv.getProgramId())
                .filter(policy -> !userId.equals(policy.authorId()))
                .filter(policy -> !Boolean.TRUE.equals(policy.allowParticipantMessages()))
                // La même condition que le refus : deux participants qui parlent
                // d'un programme entre eux ne sont pas concernés par un réglage
                // qui porte sur ce que son auteur reçoit.
                .filter(policy -> conversationMemberRepository
                    .existsByConversationIdAndUserId(conv.getId(), policy.authorId()))
                .map(policy -> ConversationReadOnlyReason.PROGRAM_MESSAGES_DISABLED)
                .orElse(null);
        }

        return null;
    }

    @Transactional(readOnly = true)
    public ConversationDetailDto getConversationDetail(UUID userId, UUID conversationId) {
        Conversation conv = loadConversation(conversationId);
        assertMayRead(conv, userId);

        // Membres : dérivés pour un fil de diffusion — les lignes de
        // conversation_members n'y sont qu'un support de lecture et diraient
        // « trois personnes » sur un programme qui en compte trente dont deux
        // seulement l'ont ouvert.
        List<UUID> memberIds = conv.getType() == ConversationType.PROGRAM_BROADCAST
                && conv.getProgramId() != null
            ? broadcastMemberIds(conv.getProgramId())
            : conversationMemberRepository.findUserIdsByConversationId(conversationId);

        // Une requête pour tous les membres, et non une par membre (P-BA-15) : un
        // fil de diffusion en compte autant que le programme. L'ordre de
        // memberIds est gardé.
        Map<UUID, User> parId = userRepository.findAllById(memberIds).stream()
            .collect(Collectors.toMap(User::getId, java.util.function.Function.identity()));
        List<UserPublicDto> members = memberIds.stream()
            .map(parId::get)
            .filter(java.util.Objects::nonNull)
            .map(user -> UserPublicDto.identity(
                user.getId(),
                user.getDisplayName(),
                user.getBio(),
                user.getAvatarUrl(),
                user.getVerificationStatus().name()
            ))
            .collect(Collectors.toList());

        ConversationContextDto context = contextOf(conversationId);

        return new ConversationDetailDto(
            conv.getId(),
            conv.getType().name(),
            members,
            context.activityName(),
            context.programId(),
            context.programTitle(),
            context.activityName(),
            context.scheduleId(),
            context.scheduleStartsAt(),
            context.scheduleEndsAt(),
            conv.getType() == ConversationType.PROGRAM_BROADCAST ? context.programTitle() : null,
            conv.getType() == ConversationType.PROGRAM_BROADCAST ? members.size() : null,
            conv.getCreatedAt(),
            // C'est cet écran qui porte le composeur : il paie donc la relecture
            // du réglage du programme, que la liste des fils ne paie pas.
            readOnlyReasonFor(conv, userId, blockFilterService.invisibleTo(userId), true)
        );
    }

    public void deleteConversation(UUID userId, UUID conversationId) {
        // Un fil de diffusion ne se masque pas : l'appartenance en est dérivée du
        // programme, donc il reparaîtrait à la première lecture. On quitte le
        // programme, pas le fil — le dire franchement vaut mieux qu'un masquage
        // qui ne tient pas.
        if (loadConversation(conversationId).getType() == ConversationType.PROGRAM_BROADCAST) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR, "REFUS_QUITTER_FIL_DIFFUSION",
                "Un fil de diffusion se quitte en quittant le programme.");
        }

        // Verify user is member
        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_ACCES_CONVERSATION", "Accès conversation refusé."));

        // Soft delete: just remove the member
        conversationMemberRepository.delete(member);

        // Note: Actual conversation and messages remain in DB for other participants
        // This is a "hide" rather than true delete
    }

    public MessageDto editMessage(UUID userId, UUID messageId, EditMessageRequest request) {
        // 1. Find message and verify sender
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_MESSAGE_INTROUVABLE", "Message introuvable."));

        if (!message.getSender().getId().equals(userId)) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_MODIFIER_MESSAGE_AUTRUI", "Vous ne pouvez modifier que vos propres messages.");
        }

        if (message.getDeletedAt() != null) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR, "REFUS_MODIFIER_MESSAGE_SUPPRIME", "Impossible de modifier un message supprimé.");
        }

        // 1 bis. Modifier est écrire.
        //
        // Ce chemin n'est pas dans la fiche, et il referme pourtant le même défaut
        // qu'elle : un message déjà envoyé se réécrit entièrement, la nouvelle
        // version part en WebSocket sur /queue/messages.edited, et une application
        // ouverte l'affiche. Sans ce contrôle, une personne bloquée n'avait qu'à
        // reprendre son dernier message pour continuer d'écrire à quelqu'un qui
        // l'a bloquée — refuser l'envoi et laisser la modification ouverte aurait
        // fermé la porte et laissé la fenêtre.
        assertNotBlockedIn(message.getConversation(), userId);

        // 2. Sanitize new content
        String cleanContent = sanitizer.sanitize(request.content());
        if (!StringUtils.hasText(cleanContent)) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR, "REFUS_MESSAGE_VIDE", "Message vide après sanitisation.");
        }

        // 3. Save edit history
        MessageEditHistory history = MessageEditHistory.builder()
            .message(message)
            .previousContent(message.getContent())
            .editedAt(Instant.now())
            .build();
        messageEditHistoryRepository.save(history);

        // 4. Update message
        message.setContent(cleanContent);
        message.setEditedAt(Instant.now());
        message = messageRepository.save(message);

        MessageDto dto = toMessageDto(message);

        // 5. Broadcast update via WebSocket — sans ceux pour qui l'auteur est
        // invisible, comme à l'envoi. Un fil à deux n'est plus concerné (la
        // modification y est refusée plus haut) ; un fil de groupe l'est.
        Set<UUID> invisibleToSender = blockFilterService.invisibleTo(userId);
        List<UUID> memberIds = conversationMemberRepository
            .findUserIdsByConversationId(message.getConversation().getId());

        for (UUID memberId : memberIds) {
            if (invisibleToSender.contains(memberId)) {
                continue;
            }
            messagingTemplate.convertAndSendToUser(
                memberId.toString(),
                "/queue/messages.edited",
                dto
            );
        }

        return dto;
    }

    public void deleteMessage(UUID userId, UUID messageId) {
        // 1. Find message and verify sender
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_MESSAGE_INTROUVABLE", "Message introuvable."));

        if (!message.getSender().getId().equals(userId)) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_SUPPRIMER_MESSAGE_AUTRUI", "Vous ne pouvez supprimer que vos propres messages.");
        }

        if (message.getDeletedAt() != null) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR, "REFUS_MESSAGE_DEJA_SUPPRIME", "Message déjà supprimé.");
        }

        // 2. Soft delete. Le point d'un partage de position part avec le
        // message : sans cela, un partage « supprimé » restait servi par
        // toMessageDto jusqu'à son échéance (demande mobile du 14/09/2026,
        // tracabilite §2.6 a). Le texte, lien de carte compris, est remplacé.
        message.setDeletedAt(Instant.now());
        message.setContent(CONTENU_SUPPRIME);
        effacerPoint(message);
        messageRepository.save(message);

        // 3. Broadcast deletion via WebSocket
        List<UUID> memberIds = conversationMemberRepository
            .findUserIdsByConversationId(message.getConversation().getId());

        for (UUID memberId : memberIds) {
            messagingTemplate.convertAndSendToUser(
                memberId.toString(),
                "/queue/messages.deleted",
                messageId
            );
        }
    }

    /**
     * Le marqueur que l'app écrit dans un message porteur de position
     * ({@code LivePositionShare.encode} : un lien de carte, puis
     * {@code [meetdo:pos v1 lat=… lng=… exp=…]}). L'app n'appelle pas la route
     * {@code /location} : c'est par ce marqueur, et par lui seul, que le serveur
     * reconnaît une position envoyée en texte.
     */
    static final String MARQUEUR_POSITION = "[meetdo:pos v1 ";

    /** La durée maximale d'un partage, côté app comme côté route : trente minutes. */
    private static final java.time.Duration DUREE_PARTAGE_MAX = java.time.Duration.ofMinutes(MAX_LOCATION_SHARE_MINUTES);

    private static final String CONTENU_SUPPRIME = "[Message supprimé]";

    /**
     * Met fin, maintenant, à tous les partages de position encore ouverts de
     * {@code senderId} — dans un seul fil si {@code conversationId} est donné.
     *
     * <p>Deux formes de partage, deux gestes :
     * <ul>
     *   <li>un point posé par {@code POST /conversations/{id}/location} et non
     *       échu : ses coordonnées et son échéance sont effacées, le message
     *       reste ;</li>
     *   <li>un message texte porteur du {@link #MARQUEUR_POSITION}, envoyé depuis
     *       moins de trente minutes : il est traité comme une suppression — le
     *       contenu, lien de carte compris, est remplacé.</li>
     * </ul>
     * Chaque message touché est rediffusé sur {@code /queue/messages.edited} à
     * tous les membres du fil, blocage compris : c'est un retrait, il ne dit rien
     * de neuf à personne.
     *
     * <p>Sert à « tout couper » ({@code VisibilityService}) et au blocage
     * ({@code ChatBlockEffects}). Une push déjà remise ne se rappelle pas.
     */
    public void echoirPartagesDePosition(UUID senderId, UUID conversationId) {
        Instant now = Instant.now();
        List<Message> ouverts = conversationId == null
            ? messageRepository.findPartagesOuverts(senderId, now, now.minus(DUREE_PARTAGE_MAX), MARQUEUR_POSITION)
            : messageRepository.findPartagesOuvertsDansLeFil(senderId, conversationId, now,
                now.minus(DUREE_PARTAGE_MAX), MARQUEUR_POSITION);

        for (Message message : ouverts) {
            if (message.getContent() != null && message.getContent().contains(MARQUEUR_POSITION)) {
                message.setDeletedAt(now);
                message.setContent(CONTENU_SUPPRIME);
            }
            effacerPoint(message);
            Message enregistre = messageRepository.save(message);

            MessageDto dto = toMessageDto(enregistre);
            for (UUID memberId : conversationMemberRepository
                    .findUserIdsByConversationId(enregistre.getConversation().getId())) {
                messagingTemplate.convertAndSendToUser(memberId.toString(), "/queue/messages.edited", dto);
            }
        }
    }

    /** Vrai s'il reste à {@code senderId} un partage que {@link #echoirPartagesDePosition} couperait. */
    @Transactional(readOnly = true)
    public boolean aDesPartagesDePositionOuverts(UUID senderId) {
        Instant now = Instant.now();
        return !messageRepository.findPartagesOuverts(senderId, now, now.minus(DUREE_PARTAGE_MAX),
            MARQUEUR_POSITION).isEmpty();
    }

    private static void effacerPoint(Message message) {
        message.setLocationLat(null);
        message.setLocationLng(null);
        message.setLocationExpiresAt(null);
    }

    public void markAllAsRead(UUID userId, UUID conversationId) {
        assertMayRead(loadConversation(conversationId), userId);
        ensureMemberRow(conversationId, userId);

        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "REFUS_MEMBRE_INTROUVABLE", "Membre introuvable."));

        member.setLastReadAt(Instant.now());
        conversationMemberRepository.save(member);

        eventPublisher.publishEvent(new UnreadChangedEvent(userId));
    }

    /**
     * Rattache une image déjà téléversée à une conversation, et rend son URL.
     *
     * <p><b>L'URL passe par {@code MediaFileService.attacher}</b> (P-MS-01,
     * étape 4). Cette route renvoyait telle quelle la chaîne reçue : une URL
     * étrangère — un pixel de pistage, ou un chemin forgé pour que l'app y
     * envoie son jeton — ressortait avec la caution du serveur. Elle doit
     * désormais désigner un fichier du service de fichiers, déposé par
     * l'appelant ; sinon {@code 400 MEDIA_URL_INVALID}, comme pour une pièce
     * jointe d'incident ou une photo de souvenir. L'app publiée n'appelle pas
     * cette route.
     */
    public String uploadImage(UUID userId, UUID conversationId, String imageUrl) {
        assertMayRead(loadConversation(conversationId), userId);
        return mediaFileService.attacher(imageUrl, userId, null);
    }

    private MessageDto toMessageDto(Message msg) {
        // Un point échu n'est pas servi, même si les colonnes le portent encore.
        // C'est ici que se joue l'expiration, pas dans le balayage : celui-ci
        // passe périodiquement et laisse donc une fenêtre pendant laquelle la
        // base garde un point qu'il ne faut plus rendre. Le balayage nettoie, la
        // lecture décide.
        boolean locationLive = msg.getLocationExpiresAt() != null
            && msg.getLocationExpiresAt().isAfter(Instant.now());

        return new MessageDto(
            msg.getId(),
            msg.getConversation().getId(),
            msg.getSender().getId(),
            msg.getSender().getDisplayName(),
            msg.getSender().getAvatarUrl(),
            msg.getContent(),
            msg.getStatus().name(),
            msg.getSentAt(),
            locationLive ? msg.getLocationLat() : null,
            locationLive ? msg.getLocationLng() : null,
            locationLive ? msg.getLocationExpiresAt() : null
        );
    }
}
