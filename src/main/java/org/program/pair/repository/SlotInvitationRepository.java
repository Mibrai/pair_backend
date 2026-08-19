package org.program.pair.repository;

import org.program.pair.domain.invitation.SlotInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SlotInvitationRepository extends JpaRepository<SlotInvitation, UUID> {

    Optional<SlotInvitation> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);

    @Query("SELECT i FROM SlotInvitation i WHERE i.inviter.id = :inviterId ORDER BY i.createdAt DESC")
    List<SlotInvitation> findByInviterId(@Param("inviterId") UUID inviterId);

    /**
     * Combien d'invitations de cette personne ont abouti sur un créneau.
     *
     * <p>Alimente le badge, et rien d'autre. <b>Aucun endpoint ne l'expose</b> :
     * un compteur d'invitations rendu au client deviendrait un classement de
     * parrains, ce que le garde-fou du produit interdit explicitement.
     */
    @Query("SELECT COUNT(i) FROM SlotInvitation i WHERE i.inviter.id = :inviterId AND i.convertedAt IS NOT NULL")
    long countConvertedByInviterId(@Param("inviterId") UUID inviterId);
}
