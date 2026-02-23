package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.DeckEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeckRepository extends JpaRepository<DeckEntity, Long> {

    List<DeckEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<DeckEntity> findByIdAndUserId(Long id, Long userId);

    Optional<DeckEntity> findByUserIdAndActiveTrue(Long userId);

    boolean existsByUserIdAndName(Long userId, String name);
}
