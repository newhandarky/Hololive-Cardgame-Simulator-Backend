package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.MatchDeckSnapshotEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchDeckSnapshotRepository extends JpaRepository<MatchDeckSnapshotEntity, Long> {

    void deleteByMatchId(Long matchId);

    Optional<MatchDeckSnapshotEntity> findByMatchIdAndUserId(Long matchId, Long userId);
}
