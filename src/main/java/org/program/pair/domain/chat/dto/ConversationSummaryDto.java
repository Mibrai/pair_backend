package org.program.pair.domain.chat.dto;

import org.program.pair.domain.user.dto.UserPublicDto;
import java.time.Instant;
import java.util.UUID;

public record ConversationSummaryDto(
    UUID id,
    String type,
    UserPublicDto otherUser,
    // Contexte : le programme, l'activité et la séance qui lient les membres.
    // Tous nullables — une conversation peut naître hors de tout programme.
    // activityName double activityContextName : même valeur, nom attendu par le
    // client aux côtés de programTitle.
    String activityContextName,
    UUID programId,
    String programTitle,
    String activityName,
    UUID scheduleId,
    Instant scheduleStartsAt,
    Instant scheduleEndsAt,
    // Fils de groupe : otherUser n'a aucun sens à trente personnes, et le
    // remplir avec l'auteur ferait afficher « conversation avec X » pour un fil
    // qui en compte trente. Il est nul, et title/memberCount prennent le relais.
    // Nuls à leur tour pour une conversation à deux, qui s'annonce par otherUser.
    String title,
    Integer memberCount,
    String lastMessageContent,
    Instant lastMessageAt,
    int unreadCount,

    // Sourdine et archivage, propres à l'appelant : deux personnes d'un même fil
    // ne le classent pas pareil.
    //
    // Ils ne servent pas qu'à l'affichage. Le total de
    // GET /api/conversations/unread-count écarte les fils en sourdine et les
    // fils archivés — un badge d'icône qui pointerait vers un fil qu'on a
    // délibérément rangé ou fait taire mentirait sur ce qui attend. Ces deux
    // drapeaux sont donc ce qui permet au client de retrouver ce total en
    // sommant : la somme des unreadCount des fils ni en sourdine ni archivés est
    // égale au total renvoyé par le serveur. Sans eux, les deux calculs
    // divergeraient sans que rien ne dise pourquoi.
    boolean muted,
    boolean archived
) {}
