package org.program.pair.domain.support;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.program.pair.domain.user.User;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "search_logs", indexes = {
    @Index(name = "idx_search_log_user", columnList = "user_id"),
    @Index(name = "idx_search_log_created", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "raw_query", nullable = false, length = 500)
    @NotBlank
    private String rawQuery;

    @Column(name = "parsed_intent", columnDefinition = "jsonb")
    private String parsedIntent;

    @Column(name = "query_embedding", columnDefinition = "vector(1536)")
    private float[] queryEmbedding;

    @Column(name = "results_count")
    private Integer resultsCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
