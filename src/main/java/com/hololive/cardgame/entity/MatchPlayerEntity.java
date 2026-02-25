package com.hololive.cardgame.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

@Entity
@Table(name = "match_players")
@Data
public class MatchPlayerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "oshi_card_id")
    private String oshiCardId;

    @Column(name = "current_life")
    private Integer currentLife;

    @Column(name = "ready", nullable = false)
    private boolean ready;

    @Column(name = "sp_skill_used", nullable = false)
    private boolean spSkillUsed;

    @Column(name = "skill_used_this_turn", nullable = false)
    private boolean skillUsedThisTurn;

    @Column(name = "mulligan_used", nullable = false)
    private boolean mulliganUsed;

    @Column(name = "mulligan_done", nullable = false)
    private boolean mulliganDone;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
