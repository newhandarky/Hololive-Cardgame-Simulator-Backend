package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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

class MatchGiftEffectServiceHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void executeAddCheerEffectShouldDelegateToAddCheerExecutionService() {
        MatchEffectService effectService = mock(MatchEffectService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        GameActionExecutor gameActionExecutor = mock(GameActionExecutor.class);
        MatchAddCheerTargetResolverService addCheerTargetResolverService = mock(MatchAddCheerTargetResolverService.class);
        MatchAddCheerSourceResolverService addCheerSourceResolverService = mock(MatchAddCheerSourceResolverService.class);
        EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
        MatchGiftAddCheerEffectExecutionService addCheerEffectExecutionService = new MatchGiftAddCheerEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            gameActionExecutor,
            matchId -> 1,
            (effectNode, defaultValue) -> effectTextParser.extractInt(effectNode, defaultValue, "value", "cards", "amount"),
            addCheerTargetResolverService,
            addCheerSourceResolverService,
            matchHolomemId -> 300L
        );
        MatchGiftEffectServiceHandlers handlers = new MatchGiftEffectServiceHandlers(
            effectService,
            null,
            null,
            addCheerEffectExecutionService
        );
        ObjectNode effectNode = objectMapper.createObjectNode();
        effectNode.put("rawText", "自分のエールデッキの上から1枚を、このホロメンに付ける。");
        effectNode.put("value", 1);
        when(addCheerTargetResolverService.resolvePreferredAddCheerTargetHolomemId(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(Boolean.class),
            any()
        )).thenReturn(30L);
        when(addCheerSourceResolverService.resolvePreferredAddCheerSource(any(), any(), any()))
            .thenReturn(Map.of("id", 501L, "card_id", "CHEER-A", "zone", "CHEER_DECK"));
        when(gameActionExecutor.execute(any(EffectContext.class), any(List.class)))
            .thenReturn(List.of(ActionResult.success("SEND_CHEER", Map.of())));

        Map<String, Object> summary = handlers.executeAddCheerEffect(1L, 2L, "ADD_CHEER", effectNode, "SELF", 30L);

        assertThat(summary)
            .containsEntry("effectType", "ADD_CHEER")
            .containsEntry("attachApplied", 1)
            .containsEntry("targetHolomemCardInstanceId", 300L);
        verifyNoInteractions(effectService);
    }
}
