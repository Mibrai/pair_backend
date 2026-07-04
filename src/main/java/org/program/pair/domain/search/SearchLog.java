package org.program.pair.domain.search;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "search_logs", indexes = {
    @Index(name = "idx_search_logs_user", columnList = "user_id"),
    @Index(name = "idx_search_logs_date", columnList = "searched_at"),
    @Index(name = "idx_search_logs_method", columnList = "search_method")
})
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
    @JoinColumn(name = "user_id", nullable = true)
    private User user;

    @Column(name = "raw_query", nullable = false, columnDefinition = "TEXT")
    private String rawQuery;

    @Column(name = "parsed_intent", columnDefinition = "TEXT")
    private String parsedIntent;

    @Column(name = "search_method", length = 50)
    @Builder.Default
    private String searchMethod = "fulltext";

    @Column(name = "results_count")
    private Integer resultsCount;

    @Column(name = "searched_at", nullable = false)
    @Builder.Default
    private Instant searchedAt = Instant.now();
}
