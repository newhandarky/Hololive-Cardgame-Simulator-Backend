package com.hololive.cardgame.game.action;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class EffectResolverTest {

    private final EffectResolver effectResolver = new EffectResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void firstBatchEffectTypesShouldAllHaveResolverMappings() {
        for (String effectType : effectResolver.firstBatchEffectTypes()) {
            assertThat(effectResolver.hasResolver(effectType))
                .as("effectType should be mapped: %s", effectType)
                .isTrue();
        }
    }

    @Test
    void resolveDrawShouldReturnDrawAction() {
        EffectContext context = new EffectContext(1L, 2L, 3, "SUPPORT", null, null);
        ObjectNode effectJson = objectMapper.createObjectNode();
        effectJson.put("drawCount", 2);

        var actions = effectResolver.resolve(context, "DRAW", effectJson);

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0)).isInstanceOf(DrawAction.class);
        DrawAction drawAction = (DrawAction) actions.get(0);
        assertThat(drawAction.ownerUserId()).isEqualTo(2L);
        assertThat(drawAction.drawCount()).isEqualTo(2);
    }

    @Test
    void resolveSearchShouldReturnUnimplementedAction() {
        EffectContext context = new EffectContext(1L, 2L, 3, "SUPPORT", null, null);
        ObjectNode effectJson = objectMapper.createObjectNode();

        var actions = effectResolver.resolve(context, "SEARCH", effectJson);

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0)).isInstanceOf(UnimplementedAction.class);
        UnimplementedAction action = (UnimplementedAction) actions.get(0);
        assertThat(action.effectType()).isEqualTo("SEARCH");
    }
}
