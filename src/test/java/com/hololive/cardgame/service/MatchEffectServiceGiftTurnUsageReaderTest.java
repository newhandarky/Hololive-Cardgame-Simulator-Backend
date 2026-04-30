package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    void isGiftAlreadyUsedThisTurnShouldDelegateToGiftTurnUsageReader() {
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
    }
}
