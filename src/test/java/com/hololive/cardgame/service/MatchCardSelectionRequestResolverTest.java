package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import org.junit.jupiter.api.Test;

class MatchCardSelectionRequestResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MatchCardSelectionRequestResolver resolver = new MatchCardSelectionRequestResolver(
        new EffectTextParser(objectMapper)
    );

    @Test
    void resolveSearchCountShouldPreferFieldsThenTextFallbacks() throws Exception {
        assertThat(resolver.resolveSearchCount(node("{\"cards\":2,\"rawText\":\"1〜3枚手札に加える\"}"))).isEqualTo(2);
        assertThat(resolver.resolveSearchCount(node("{\"rawText\":\"1〜3枚手札に加える\"}"))).isEqualTo(3);
        assertThat(resolver.resolveSearchCount(node("{\"rawText\":\"デッキから2枚手札に加える\"}"))).isEqualTo(2);
        assertThat(resolver.resolveSearchCount(node("{\"rawText\":\"デッキから手札に加える\"}"))).isEqualTo(1);
        assertThat(resolver.resolveSearchCount(node("{\"rawText\":\"条件を満たすカードを見る\"}"))).isZero();
    }

    @Test
    void resolveSearchLookTopCountShouldUseFieldsAndRawText() throws Exception {
        assertThat(resolver.resolveSearchLookTopCount(node("{\"peekCount\":4}"), "デッキの上から3枚を見る"))
            .isEqualTo(4);
        assertThat(resolver.resolveSearchLookTopCount(node("{}"), "デッキの上から3枚を見る")).isEqualTo(3);
        assertThat(resolver.resolveSearchLookTopCount(node("{}"), "デッキを見る")).isZero();
    }

    @Test
    void resolveSearchSourceZoneShouldPreferExplicitZoneThenTextFallbacks() throws Exception {
        assertThat(resolver.resolveSearchSourceZone(node("{\"sourceZone\":\"archive\"}"), "")).isEqualTo("ARCHIVE");
        assertThat(resolver.resolveSearchSourceZone(node("{}"), "ホロパワーを見る。その中から1枚手札に加える"))
            .isEqualTo("HOLOPOWER");
        assertThat(resolver.resolveSearchSourceZone(node("{}"), "アーカイブから1枚手札に加える")).isEqualTo("ARCHIVE");
        assertThat(resolver.resolveSearchSourceZone(node("{}"), "デッキから1枚手札に加える")).isEqualTo("DECK");
    }

    @Test
    void resolveActionCountShouldPreferFieldsThenTextFallbacks() throws Exception {
        assertThat(resolver.resolveActionCount(node("{\"value\":2,\"rawText\":\"1〜3枚手札に戻す\"}"), "手札に戻", 1))
            .isEqualTo(2);
        assertThat(resolver.resolveActionCount(node("{\"rawText\":\"1〜3枚手札に戻す\"}"), "手札に戻", 1)).isEqualTo(3);
        assertThat(resolver.resolveActionCount(node("{\"rawText\":\"2枚手札に戻す\"}"), "手札に戻", 1)).isEqualTo(2);
        assertThat(resolver.resolveActionCount(node("{\"rawText\":\"選んだホロメンを手札に戻す\"}"), "手札に戻", 1))
            .isEqualTo(1);
        assertThat(resolver.resolveActionCount(node("{\"rawText\":\"何もしない\"}"), "手札に戻", 5)).isEqualTo(5);
    }

    @Test
    void resolveReturnToHandSourceZoneShouldDetectGiftHolderStackSnapshotOnlyWhenIdsExist() throws Exception {
        JsonNode stacked = node(
            "{\"rawText\":\"重なっているホロメンを手札に戻す\",\"giftHolderStackCardInstanceIds\":[101,102]}"
        );
        JsonNode missingIds = node("{\"rawText\":\"重なっているホロメンを手札に戻す\"}");

        assertThat(resolver.resolveReturnToHandSourceZone(stacked, stacked.path("rawText").asText()))
            .isEqualTo("GIFT_HOLDER_STACK");
        assertThat(resolver.usesGiftHolderStackSnapshotForReturnToHand(stacked, stacked.path("rawText").asText()))
            .isTrue();
        assertThat(resolver.resolveReturnToHandSourceZone(missingIds, missingIds.path("rawText").asText()))
            .isEqualTo("ARCHIVE");
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
