package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.MatchPlayerEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchPlayerRepository extends JpaRepository<MatchPlayerEntity, Long> {

    List<MatchPlayerEntity> findByMatchIdOrderByIdAsc(Long matchId);

    Optional<MatchPlayerEntity> findByMatchIdAndUserId(Long matchId, Long userId);

    boolean existsByMatchIdAndUserId(Long matchId, Long userId);
}
