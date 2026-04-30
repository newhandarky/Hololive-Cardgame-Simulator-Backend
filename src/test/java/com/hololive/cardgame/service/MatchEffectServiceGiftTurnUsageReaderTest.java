package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.game.action.EffectResolver;
import com.hololive.cardgame.game.action.GameActionExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchEffectServiceGiftTurnUsageReaderTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchEffectService service = new MatchEffectService(
        jdbcTemplate,
        new ObjectMapper(),
        mock(DiceService.class),
        mock(EffectResolver.class),
        mock(GameActionExecutor.class)
    );

    @Test
    void isGiftAlreadyUsedThisTurnShouldReturnFalseAndSkipDbForInvalidInput() {
        assertThat(service.isGiftAlreadyUsedThisTurn(null, 201L, 3, 301L)).isFalse();
        assertThat(service.isGiftAlreadyUsedThisTurn(100L, null, 3, 301L)).isFalse();
        assertThat(service.isGiftAlreadyUsedThisTurn(100L, 201L, 0, 301L)).isFalse();
        assertThat(service.isGiftAlreadyUsedThisTurn(100L, 201L, 3, null)).isFalse();
        assertThat(service.isGiftAlreadyUsedThisTurn(100L, 201L, 3, 0L)).isFalse();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void isGiftAlreadyUsedThisTurnShouldReturnFalseWhenCountIsZero() {
        when(jdbcTemplate.query(
            anyString(),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(201L),
            eq(3),
            eq("301")
        )).thenReturn(0);

        boolean used = service.isGiftAlreadyUsedThisTurn(100L, 201L, 3, 301L);

        assertThat(used).isFalse();
    }

    @Test
    void isGiftAlreadyUsedThisTurnShouldQueryGiftTriggerByHolderMarker() {
        when(jdbcTemplate.query(
            anyString(),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(201L),
            eq(3),
            eq("301")
        )).thenReturn(2);

        boolean used = service.isGiftAlreadyUsedThisTurn(100L, 201L, 3, 301L);

        assertThat(used).isTrue();
        verify(jdbcTemplate).query(
            contains("action_type = 'GIFT_TRIGGER'"),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(201L),
            eq(3),
            eq("301")
        );
        verify(jdbcTemplate).query(
            contains("payload ->> 'giftHolderHolomemId' = ?"),
            any(ResultSetExtractor.class),
            eq(100L),
            eq(201L),
            eq(3),
            eq("301")
        );
    }
}
