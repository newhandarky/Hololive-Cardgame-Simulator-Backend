package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchLookEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchLookEffectExecutionService service = new MatchLookEffectExecutionService(
        jdbcTemplate,
        new EffectTextParser(objectMapper)
    );

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeLookTopDeckEffectShouldReturnTopDeckSummary() throws Exception {
        Map<String, Object> topCard = new LinkedHashMap<>();
        topCard.put("id", 101L);
        topCard.put("card_id", "hBP99-001");
        when(jdbcTemplate.query(contains("FROM match_cards"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(topCard);

        Map<String, Object> summary = service.executeLookTopDeckEffect(
            1L,
            10L,
            "LOOK_TOP_DECK",
            node("{\"rawText\":\"自分のデッキの上から1枚を見る。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "LOOK_TOP_DECK")
            .containsEntry("applied", true)
            .containsEntry("lookedCardInstanceId", 101L)
            .containsEntry("lookedCardId", "hBP99-001")
            .containsEntry("reordered", false);
    }

    @Test
    void executeLookTopDeckEffectShouldSkipWhenMascotConditionFails() throws Exception {
        when(jdbcTemplate.queryForObject(contains("FROM match_holomem_supports"), eq(Integer.class), eq(1L), eq(10L)))
            .thenReturn(0);

        Map<String, Object> summary = service.executeLookTopDeckEffect(
            1L,
            10L,
            "LOOK_TOP_DECK",
            node("{\"rawText\":\"マスコットが付いている時、自分のデッキの上から1枚を見る。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "LOOK_TOP_DECK")
            .containsEntry("applied", false)
            .containsEntry("reason", "條件不成立：沒有附加中的マスコット")
            .containsEntry("rawText", "マスコットが付いている時、自分のデッキの上から1枚を見る。");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeLookOpponentHandEffectShouldLoadOpponentHandCards() throws Exception {
        when(jdbcTemplate.query(contains("FROM matches"), any(ResultSetExtractor.class), eq(1L)))
            .thenReturn(20L);
        when(
            jdbcTemplate.query(
                contains("FROM match_cards mc"),
                any(RowMapper.class),
                eq(1L),
                eq(20L),
                eq("HAND")
            )
        ).thenReturn(List.of(lookedCard("HAND")));

        Map<String, Object> summary = service.executeLookOpponentHandEffect(
            1L,
            10L,
            "LOOK_OPPONENT_HAND",
            node("{\"rawText\":\"相手の手札を見る。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "LOOK_OPPONENT_HAND")
            .containsEntry("applied", true)
            .containsEntry("lookedUserId", 20L)
            .containsEntry("lookedZone", "HAND")
            .containsEntry("lookedCardCount", 1);
        assertThat(summary.get("lookedCards"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .first()
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("zone", "HAND");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeLookHolopowerEffectShouldUseOpponentWhenTextTargetsOpponent() throws Exception {
        when(jdbcTemplate.query(contains("FROM matches"), any(ResultSetExtractor.class), eq(1L)))
            .thenReturn(20L);
        when(
            jdbcTemplate.query(
                contains("FROM match_cards mc"),
                any(RowMapper.class),
                eq(1L),
                eq(20L),
                eq("HOLOPOWER")
            )
        ).thenReturn(List.of(lookedCard("HOLOPOWER")));

        Map<String, Object> summary = service.executeLookHolopowerEffect(
            1L,
            10L,
            "LOOK_HOLOPOWER",
            node("{\"rawText\":\"相手のホロパワーを見る。\"}")
        );

        assertThat(summary)
            .containsEntry("effectType", "LOOK_HOLOPOWER")
            .containsEntry("applied", true)
            .containsEntry("lookedUserId", 20L)
            .containsEntry("lookedZone", "HOLOPOWER")
            .containsEntry("lookedCardCount", 1);
        verify(jdbcTemplate).query(
            contains("FROM match_cards mc"),
            any(RowMapper.class),
            eq(1L),
            eq(20L),
            eq("HOLOPOWER")
        );
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private Map<String, Object> lookedCard(String zone) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("cardInstanceId", 201L);
        card.put("cardId", "hBP99-002");
        card.put("zone", zone);
        card.put("name", "Look Target");
        card.put("cardType", "MEMBER");
        card.put("imageUrl", "image.png");
        card.put("levelType", "DEBUT");
        return card;
    }
}
