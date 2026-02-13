package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.MatchActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchActionRepository extends JpaRepository<MatchActionEntity, Long> {

    @Query("""
        SELECT COALESCE(MAX(a.actionOrder), 0)
        FROM MatchActionEntity a
        WHERE a.matchId = :matchId
          AND a.turnNumber = :turnNumber
        """)
    int findMaxActionOrderByTurn(@Param("matchId") Long matchId, @Param("turnNumber") Integer turnNumber);
}
