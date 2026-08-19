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
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

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
            throw new ResourceNotFoundException("Utilisateur introuvable.");
        }

        if (!Boolean.TRUE.equals(target.getReceiveMessages())) {
            throw new ForbiddenException("Cet utilisateur n'accepte pas les messages.");
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
            .orElseThrow(() -> new ResourceNotFoundException("Conversation introuvable."));
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
                throw new ForbiddenException("Accès conversation refusé.");
            }
            return;
        }
        if (!conversationMemberRepository.existsByConversationIdAndUserId(conv.getId(), userId)) {
            throw new ForbiddenException("Accès conversation refusé.");
        }
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
            .orElseThrow(() -> new ResourceNotFoundException("Programme introuvable."));

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
                .orElseThrow(() -> new ResourceNotFoundException("Activité introuvable.")));
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
            .orElseThrow(() -> new ForbiddenException("Accès conversation refusé."));
        assertMayRead(conv, senderId);

        // 1 bis. Un fil de diffusion n'a qu'une plume : celle de l'auteur.
        assertMayWriteInBroadcast(conv, senderId);

        // 1 ter. Le refus de l'auteur vaut aussi sur un fil déjà ouvert.
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
            throw new ValidationException("Message vide après sanitisation.");
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
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

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
        List<UUID> memberIds = recipientsOf(conv);

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
        assertMayWriteInBroadcast(conv, senderId);
        assertMayWriteInProgramThread(conv, senderId);

        int minutes = request.expiresInMinutes() == null
            ? MAX_LOCATION_SHARE_MINUTES
            : request.expiresInMinutes();
        if (minutes > MAX_LOCATION_SHARE_MINUTES) {
            throw new ValidationException(
                "Un partage de position ne peut pas dépasser "
                    + MAX_LOCATION_SHARE_MINUTES + " minutes.");
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
            .orElseThrow(() -> new ForbiddenException("Membre introuvable."));

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
        for (UUID memberId : recipientsOf(conv)) {
            if (!memberId.equals(userId)) {
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

        return conversations.stream()
            .map(conv -> toSummaryDto(conv, userId,
                contexts.getOrDefault(conv.getId(), ConversationContextDto.empty(conv.getId()))))
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

    @Transactional(readOnly = true)
    public List<MessageDto> getMessages(UUID userId, UUID conversationId, int limit) {
        assertMayRead(loadConversation(conversationId), userId);

        return messageRepository
            .findByConversationIdOrderBySentAtDesc(conversationId, limit)
            .stream()
            .map(this::toMessageDto)
            .collect(Collectors.toList());
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
            .orElseThrow(() -> new ForbiddenException("Membre introuvable."));

        member.setLastReadAt(Instant.now());
        conversationMemberRepository.save(member);

        // Lire ici fait baisser le badge des autres appareils du compte, qui
        // eux ne reçoivent aucune push sur une lecture.
        eventPublisher.publishEvent(new UnreadChangedEvent(userId));
    }

    private void addMember(UUID conversationId, UUID userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation introuvable."));
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

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

    private ConversationSummaryDto toSummaryDto(Conversation conv, UUID currentUserId,
                                                ConversationContextDto context) {
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

        // Get last message
        Message lastMsg = messageRepository
            .findFirstByConversationIdOrderBySentAtDesc(conv.getId())
            .orElse(null);

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
            own != null && own.getArchivedAt() != null
        );
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

        List<UserPublicDto> members = memberIds.stream()
            .map(id -> userRepository.findById(id).orElse(null))
            .filter(user -> user != null)
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
            conv.getCreatedAt()
        );
    }

    public void deleteConversation(UUID userId, UUID conversationId) {
        // Un fil de diffusion ne se masque pas : l'appartenance en est dérivée du
        // programme, donc il reparaîtrait à la première lecture. On quitte le
        // programme, pas le fil — le dire franchement vaut mieux qu'un masquage
        // qui ne tient pas.
        if (loadConversation(conversationId).getType() == ConversationType.PROGRAM_BROADCAST) {
            throw new ValidationException(
                "Un fil de diffusion se quitte en quittant le programme.");
        }

        // Verify user is member
        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException("Accès conversation refusé."));

        // Soft delete: just remove the member
        conversationMemberRepository.delete(member);

        // Note: Actual conversation and messages remain in DB for other participants
        // This is a "hide" rather than true delete
    }

    public MessageDto editMessage(UUID userId, UUID messageId, EditMessageRequest request) {
        // 1. Find message and verify sender
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message introuvable."));

        if (!message.getSender().getId().equals(userId)) {
            throw new ForbiddenException("Vous ne pouvez modifier que vos propres messages.");
        }

        if (message.getDeletedAt() != null) {
            throw new ValidationException("Impossible de modifier un message supprimé.");
        }

        // 2. Sanitize new content
        String cleanContent = sanitizer.sanitize(request.content());
        if (!StringUtils.hasText(cleanContent)) {
            throw new ValidationException("Message vide après sanitisation.");
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

        // 5. Broadcast update via WebSocket
        List<UUID> memberIds = conversationMemberRepository
            .findUserIdsByConversationId(message.getConversation().getId());

        for (UUID memberId : memberIds) {
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
            .orElseThrow(() -> new ResourceNotFoundException("Message introuvable."));

        if (!message.getSender().getId().equals(userId)) {
            throw new ForbiddenException("Vous ne pouvez supprimer que vos propres messages.");
        }

        if (message.getDeletedAt() != null) {
            throw new ValidationException("Message déjà supprimé.");
        }

        // 2. Soft delete
        message.setDeletedAt(Instant.now());
        message.setContent("[Message supprimé]");
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

    public void markAllAsRead(UUID userId, UUID conversationId) {
        assertMayRead(loadConversation(conversationId), userId);
        ensureMemberRow(conversationId, userId);

        ConversationMember member = conversationMemberRepository
            .findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ForbiddenException("Membre introuvable."));

        member.setLastReadAt(Instant.now());
        conversationMemberRepository.save(member);

        eventPublisher.publishEvent(new UnreadChangedEvent(userId));
    }

    public String uploadImage(UUID userId, UUID conversationId, String imageUrl) {
        assertMayRead(loadConversation(conversationId), userId);

        // This method expects the image to be already uploaded to storage
        // and returns the URL. The actual file upload logic would be in the controller
        // using a file storage service (S3, local, etc.)
        return imageUrl;
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
