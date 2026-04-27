package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hololive.cardgame.entity.MatchEntity;
import com.hololive.cardgame.model.MatchPhase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class AttachCheerActionResolverTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AttachCheerActionResolver resolver = new AttachCheerActionResolver(jdbcTemplate);

    @Test
    void resolveShouldMoveCheerCardToStageAndInsertAttachment() {
        AttachCheerAction action = action();
        AttachCheerValidationContext context = validationContext();
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(701L), eq(100L), eq(10L)))
            .thenReturn(1);
        when(jdbcTemplate.query(contains("INSERT INTO match_holomem_cheers"), any(ResultSetExtractor.class), eq(901L), eq(701L), eq("hY01-001")))
            .thenReturn(501L);

        AttachCheerResolutionResult result = resolver.resolve(action, context);

        assertThat(result.cheerCardInstanceId()).isEqualTo(701L);
        assertThat(result.cheerCardId()).isEqualTo("hY01-001");
        assertThat(result.sourceZone()).isEqualTo("CHEER_DECK");
        assertThat(result.targetHolomemId()).isEqualTo(901L);
        assertThat(result.targetHolomemCardInstanceId()).isEqualTo(801L);
        assertThat(result.attachmentId()).isEqualTo(501L);

        verify(jdbcTemplate).update(contains("UPDATE match_cards"), eq(701L), eq(100L), eq(10L));
        verify(jdbcTemplate).query(
            contains("INSERT INTO match_holomem_cheers"),
            any(ResultSetExtractor.class),
            eq(901L),
            eq(701L),
            eq("hY01-001")
        );
    }

    private AttachCheerAction action() {
        return new AttachCheerAction(
            "ATTACH_CHEER",
            100L,
            10L,
            701L,
            801L,
            4,
            AttachCheerAction.ActionSource.TEST,
            "trace-attach-cheer",
            "idem-attach-cheer",
            LocalDateTime.now()
        );
    }

    private AttachCheerValidationContext validationContext() {
        MatchEntity match = new MatchEntity();
        match.setId(100L);
        match.setStatus("active");
        match.setLobbyStatus("STARTED");
        match.setCurrentTurnPlayerId(10L);
        match.setCurrentPhase(MatchPhase.MAIN.name());
        match.setTurnNumber(4);
        return new AttachCheerValidationContext(
            match,
            10L,
            4,
            10L,
            MatchPhase.MAIN,
            "active",
            "STARTED",
            false,
            false,
            false,
            new AttachCheerSourceCardSnapshot(701L, "hY01-001", "CHEER_DECK", true),
            new AttachCheerTargetHolomemSnapshot(901L, 801L, "hBP01-001", "CENTER")
        );
    }
}
