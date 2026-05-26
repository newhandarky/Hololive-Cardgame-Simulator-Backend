package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchEffectTypeInferenceServiceTest {

    private final MatchEffectTypeInferenceService service =
        new MatchEffectTypeInferenceService(new EffectTextParser(new ObjectMapper()));

    @Test
    void inferEffectTypesShouldPreserveCompositeBloomTextOrderAndReplacementRule() {
        List<String> effectTypes = service.inferEffectTypes(
            "デッキから手札に加える。アーカイブするかわりに手札に加えられる。自分のエールデッキからエール1枚を送る。"
        );

        assertThat(effectTypes).containsExactly(
            "REPLACE_ARCHIVE_WITH_HAND",
            "ADD_CHEER",
            "REMOVE_CHEER",
            "DISCARD_HAND"
        );
    }

    @Test
    void inferEffectTypesShouldReturnUnimplementedWhenTextIsBlankOrUnknown() {
        assertThat(service.inferEffectTypes("")).containsExactly("UNIMPLEMENTED");
        assertThat(service.inferEffectTypes("まだ解析できない効果。")).containsExactly("UNIMPLEMENTED");
    }

    @Test
    void inferTargetTypeShouldClassifyEnemyEffects() {
        assertThat(service.inferTargetType("DAMAGE")).isEqualTo("ENEMY");
        assertThat(service.inferTargetType("REST")).isEqualTo("ENEMY");
        assertThat(service.inferTargetType("DOWN_EXTRA_LIFE")).isEqualTo("ENEMY");
        assertThat(service.inferTargetType("DRAW")).isEqualTo("SELF");
    }
}
