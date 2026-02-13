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
@Table(name = "matches")
@Data
public class MatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_code", nullable = false, unique = true)
    private String roomCode;

    @Column(name = "player_a_id", nullable = false)
    private Long playerAId;

    @Column(name = "player_b_id")
    private Long playerBId;

    @Column(name = "status", nullable = false)
    private String status = "active";

    @Column(name = "lobby_status", nullable = false)
    private String lobbyStatus = "WAITING";

    @Column(name = "winner_user_id")
    private Long winnerUserId;

    @Column(name = "current_turn_player_id")
    private Long currentTurnPlayerId;

    @Column(name = "turn_number", nullable = false)
    private Integer turnNumber = 1;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
