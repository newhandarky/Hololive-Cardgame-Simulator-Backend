package com.hololive.cardgame.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

@Entity
@Table(name = "cards")
@Data
public class Card {

    @Id
    @Column(name = "card_id")
    private String cardId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "rarity")
    private String rarity;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "card_type", nullable = false)
    private String cardType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
