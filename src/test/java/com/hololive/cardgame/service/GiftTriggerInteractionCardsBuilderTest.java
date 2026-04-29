package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class GiftTriggerInteractionCardsBuilderTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final GiftTriggerInteractionCardsBuilder builder = new GiftTriggerInteractionCardsBuilder(jdbcTemplate);

    @Test
    void buildGiftTriggerInteractionCardsShouldIncludeSourceAndGiftHoldersWithFallbackZones() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(), any(), any())).thenReturn(null);

        Map<String, Object> backGiftTrigger = Map.of(
            "giftHolderCardInstanceId",
            801L,
            "giftHolderCardId",
            "hBP06-014",
            "giftHolderZone",
            "BACK"
        );
        Map<String, Object> defaultZoneGiftTrigger = Map.of(
            "giftHolderCardInstanceId",
            802L,
            "giftHolderCardId",
            "hBP06-015",
            "giftHolderZone",
            ""
        );

        List<Map<String, Object>> cards = builder.buildGiftTriggerInteractionCards(
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(backGiftTrigger, defaultZoneGiftTrigger)
        );

        assertThat(cards).hasSize(3);
        assertThat(cards.get(0))
            .containsEntry("cardInstanceId", 701L)
            .containsEntry("cardId", "hBP01-001")
            .containsEntry("zone", "STAGE");
        assertThat(cards.get(1))
            .containsEntry("cardInstanceId", 801L)
            .containsEntry("cardId", "hBP06-014")
            .containsEntry("zone", "BACK");
        assertThat(cards.get(2))
            .containsEntry("cardInstanceId", 802L)
            .containsEntry("cardId", "hBP06-015")
            .containsEntry("zone", "STAGE");
    }

    @Test
    void buildGiftTriggerInteractionCardsShouldDedupeAndSkipInvalidGiftHolders() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(), any(), any())).thenReturn(null);

        Map<String, Object> sameAsSourceTrigger = Map.of(
            "giftHolderCardInstanceId",
            701L,
            "giftHolderCardId",
            "hBP01-001",
            "giftHolderZone",
            "CENTER"
        );
        Map<String, Object> holderTrigger = Map.of(
            "giftHolderCardInstanceId",
            801L,
            "giftHolderCardId",
            "hBP06-014",
            "giftHolderZone",
            "BACK"
        );
        Map<String, Object> duplicateHolderTrigger = Map.of(
            "giftHolderCardInstanceId",
            801L,
            "giftHolderCardId",
            "hBP06-014",
            "giftHolderZone",
            "BACK"
        );
        Map<String, Object> invalidHolderTrigger = Map.of(
            "giftHolderCardInstanceId",
            0L,
            "giftHolderCardId",
            "hBP06-999",
            "giftHolderZone",
            "BACK"
        );

        List<Map<String, Object>> cards = builder.buildGiftTriggerInteractionCards(
            100L,
            10L,
            701L,
            "hBP01-001",
            List.of(sameAsSourceTrigger, holderTrigger, duplicateHolderTrigger, invalidHolderTrigger)
        );

        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(card -> card.get("cardInstanceId")).containsExactly(701L, 801L);
        assertThat(cards.get(0)).containsEntry("zone", "STAGE");
        assertThat(cards.get(1)).containsEntry("zone", "BACK");
    }
}
