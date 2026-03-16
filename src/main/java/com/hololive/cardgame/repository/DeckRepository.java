package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.DeckEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DeckRepository extends JpaRepository<DeckEntity, Long> {

    List<DeckEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<DeckEntity> findByIdAndUserId(Long id, Long userId);

    Optional<DeckEntity> findByUserIdAndActiveTrue(Long userId);

    boolean existsByUserIdAndName(Long userId, String name);

    @Modifying
    @Query(
        value = """
            UPDATE decks
            SET is_active = FALSE
            WHERE user_id = :userId
              AND is_active = TRUE
            """,
        nativeQuery = true
    )
    int deactivateAllByUserId(Long userId);
}
