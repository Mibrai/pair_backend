package org.program.pair.repository;

import org.program.pair.domain.notification.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    @Query("SELECT dt.token FROM DeviceToken dt WHERE dt.user.id = :userId")
    List<String> findTokensByUserId(@Param("userId") UUID userId);

    Optional<DeviceToken> findByToken(String token);

    void deleteByToken(String token);

    List<DeviceToken> findByUserId(UUID userId);

    boolean existsByUserIdAndToken(UUID userId, String token);
}
