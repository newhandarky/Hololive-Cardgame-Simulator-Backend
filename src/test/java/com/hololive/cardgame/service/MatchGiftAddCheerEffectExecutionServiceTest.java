package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hololive.cardgame.game.action.ActionResult;
import com.hololive.cardgame.game.action.EffectContext;
import com.hololive.cardgame.game.action.GameActionExecutor;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MatchGiftAddCheerEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final GameActionExecutor gameActionExecutor = mock(GameActionExecutor.class);
    private final MatchAddCheerTargetResolverService addCheerTargetResolverService =
        mock(MatchAddCheerTargetResolverService.class);
    private final MatchAddCheerSourceResolverService addCheerSourceResolverService =
        mock(MatchAddCheerSourceResolverService.class);

    @Test
    @SuppressWarnings("unchecked")
    void executeShouldAttachCheerThroughGameActionExecutor() {
        MatchGiftAddCheerEffectExecutionService service = newService(22L, Map.of(
            "id", 501L,
            "card_id", "CHEER-A",
            "zone", "CHEER_DECK"
        ));
        ObjectNode effectNode = addCheerEffectNode("自分のエールデッキの上から1枚を、このホロメンに付ける。", 1);
        when(gameActionExecutor.execute(any(EffectContext.class), any(List.class)))
            .thenReturn(List.of(ActionResult.success("SEND_CHEER", Map.of())));

        Map<String, Object> summary = service.executeAddCheerEffect(
            1L,
            10L,
            "ADD_CHEER",
            effectNode,
            "SELF",
            100L
        );

        assertThat(summary)
            .containsEntry("effectType", "ADD_CHEER")
            .containsEntry("attachRequested", 1)
            .containsEntry("attachApplied", 1)
            .containsEntry("targetHolomemCardInstanceId", 2200L);
        assertThat(summary.get("attachedCheerCardInstanceIds")).isEqualTo(List.of(501L));
        assertThat(summary.get("sourceZones")).isEqualTo(List.of("CHEER_DECK"));
        verify(jdbcTemplate, never()).update(contains("UPDATE match_cards"), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeShouldFallbackToDirectSqlWhenGameActionExecutorFails() {
        MatchGiftAddCheerEffectExecutionService service = newService(22L, Map.of(
            "id", 501L,
            "card_id", "CHEER-A",
            "zone", "ARCHIVE"
        ));
        ObjectNode effectNode = addCheerEffectNode("自分のアーカイブのエール1枚を、このホロメンに付ける。", 1);
        when(gameActionExecutor.execute(any(EffectContext.class), any(List.class)))
            .thenReturn(List.of(ActionResult.failure("SEND_CHEER", "failed")));
        when(jdbcTemplate.update(contains("UPDATE match_cards"), eq(501L), eq(1L), eq(10L))).thenReturn(1);

        Map<String, Object> summary = service.executeAddCheerEffect(
            1L,
            10L,
            "ADD_CHEER",
            effectNode,
            "SELF",
            100L
        );

        assertThat(summary)
            .containsEntry("attachRequested", 1)
            .containsEntry("attachApplied", 1)
            .containsEntry("targetHolomemCardInstanceId", 2200L);
        assertThat(summary.get("attachedCheerCardInstanceIds")).isEqualTo(List.of(501L));
        assertThat(summary.get("sourceZones")).isEqualTo(List.of("ARCHIVE"));
        verify(jdbcTemplate).update(
            contains("INSERT INTO match_holomem_cheers"),
            eq(22L),
            eq(501L),
            eq("CHEER-A")
        );
    }

    @Test
    void executeShouldThrowWhenTargetCannotBeResolved() {
        MatchGiftAddCheerEffectExecutionService service = newService(null, Map.of(
            "id", 501L,
            "card_id", "CHEER-A",
            "zone", "CHEER_DECK"
        ));
        ObjectNode effectNode = addCheerEffectNode("自分のエールデッキの上から1枚を、このホロメンに付ける。", 1);

        assertThatThrownBy(() -> service.executeAddCheerEffect(
            1L,
            10L,
            "ADD_CHEER",
            effectNode,
            "SELF",
            100L
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("ADD_CHEER 需要指定可用的我方 Holomen");
        verify(gameActionExecutor, never()).execute(any(EffectContext.class), any(List.class));
    }

    private MatchGiftAddCheerEffectExecutionService newService(Long targetHolomemId, Map<String, Object> source) {
        when(addCheerTargetResolverService.resolvePreferredAddCheerTargetHolomemId(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(Boolean.class),
            any()
        )).thenReturn(targetHolomemId);
        when(addCheerSourceResolverService.resolvePreferredAddCheerSource(any(), any(), any()))
            .thenReturn(source);
        return new MatchGiftAddCheerEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            gameActionExecutor,
            matchId -> 3,
            (effectNode, defaultValue) -> effectTextParser.extractInt(effectNode, defaultValue, "value", "cards", "amount"),
            addCheerTargetResolverService,
            addCheerSourceResolverService,
            matchHolomemId -> matchHolomemId == null ? null : matchHolomemId * 100L
        );
    }

    private ObjectNode addCheerEffectNode(String rawText, int value) {
        ObjectNode effectNode = objectMapper.createObjectNode();
        effectNode.put("rawText", rawText);
        effectNode.put("value", value);
        return effectNode;
    }
}
