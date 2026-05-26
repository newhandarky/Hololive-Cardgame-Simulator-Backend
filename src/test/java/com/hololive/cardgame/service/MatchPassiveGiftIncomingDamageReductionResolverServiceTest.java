package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import com.hololive.cardgame.service.effect.SearchCriteriaParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class MatchPassiveGiftIncomingDamageReductionResolverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);
    private final DiceService diceService = mock(DiceService.class);
    private final GiftTurnUsageReader giftTurnUsageReader = mock(GiftTurnUsageReader.class);
    private final PassiveGiftTriggerActionWriter passiveGiftTriggerActionWriter =
        mock(PassiveGiftTriggerActionWriter.class);
    private final MatchPassiveGiftIncomingDamageReductionResolverService service =
        new MatchPassiveGiftIncomingDamageReductionResolverService(
            jdbcTemplate,
            objectMapper,
            effectTextParser,
            new GiftTriggerMatcher(),
            new SearchCriteriaParser(jdbcTemplate, effectTextParser),
            diceService,
            giftTurnUsageReader,
            passiveGiftTriggerActionWriter
        );

    @Test
    void resolveFromHolderShouldApplySelfDamageReduction() {
        int reduction = service.resolvePassiveGiftIncomingDamageReductionFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", "{\"キーワード\":\"このホロメンが受けるダメージ-10\"}"),
            target(901L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生"), "OSHI", "FIRST")
        );

        assertThat(reduction).isEqualTo(10);
    }

    @Test
    void resolveFromHolderShouldApplyOwnCollabDamageReduction() {
        int reduction = service.resolvePassiveGiftIncomingDamageReductionFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", "{\"キーワード\":\"自分のコラボホロメンが受けるダメージ-10\"}"),
            target(902L, "COLLAB", "FIRST", "Roboco", Set.of("#0期生"), "OSHI", "SECOND")
        );

        assertThat(reduction).isEqualTo(10);
    }

    @Test
    void resolveFromHolderShouldSkipWhenIncomingSourceLevelDoesNotMatch() {
        int reduction = service.resolvePassiveGiftIncomingDamageReductionFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", "{\"キーワード\":\"このホロメンが相手の1stホロメンから受けるダメージ-20\"}"),
            target(901L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生"), "OSHI", "SECOND")
        );

        assertThat(reduction).isZero();
    }

    @Test
    void resolveFromHolderShouldRollDiceAndRecordOncePerTurnReduction() {
        String rawText = """
            【ギフト】ターンに1回、このホロメン以外の自分のホロメンが相手からダメージを受ける時、
            サイコロを1回振れる。奇数なら、受けるダメージ-40。偶数なら、受けるダメージ-20。
            """;
        whenCurrentTurn(7);
        when(giftTurnUsageReader.isGiftAlreadyUsedThisTurn(100L, 20L, 7, 901L)).thenReturn(false);
        when(diceService.rollD6()).thenReturn(3);

        int reduction = service.resolvePassiveGiftIncomingDamageReductionFromHolder(
            100L,
            20L,
            holder(901L, "CENTER", passiveJson(rawText)),
            target(902L, "COLLAB", "FIRST", "Roboco", Set.of("#0期生"), "OSHI", "DEBUT")
        );

        assertThat(reduction).isEqualTo(40);
        verify(passiveGiftTriggerActionWriter)
            .appendIncomingDamageReductionTrigger(100L, 20L, 7, 901L, effectTextParser.normalizeDigits(rawText), 3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveShouldLoadTargetAndHoldersThenSumReductions() {
        when(
            jdbcTemplate.query(
                contains("oc.name AS oshi_card_name"),
                any(ResultSetExtractor.class),
                eq(100L),
                eq(20L),
                eq(901L)
            )
        ).thenReturn(target(901L, "CENTER", "DEBUT", "Tokino Sora", Set.of("#0期生"), "OSHI", "FIRST"));
        when(
            jdbcTemplate.query(
                contains("h.zone IN ('CENTER', 'COLLAB')"),
                any(RowMapper.class),
                eq(100L),
                eq(20L)
            )
        ).thenReturn(List.of(
            holder(901L, "CENTER", "{\"キーワード\":\"このホロメンが受けるダメージ-10\"}"),
            holder(902L, "COLLAB", "{\"キーワード\":\"自分のDebutホロメンが受けるダメージ-20\"}")
        ));

        int reduction = service.resolvePassiveGiftIncomingDamageReduction(100L, 20L, 901L, "FIRST");

        assertThat(reduction).isEqualTo(30);
    }

    @SuppressWarnings("unchecked")
    private void whenCurrentTurn(int turnNumber) {
        when(jdbcTemplate.query(contains("SELECT turn_number FROM matches"), any(ResultSetExtractor.class), eq(100L)))
            .thenReturn(turnNumber);
    }

    private MatchPassiveGiftIncomingDamageReductionResolverService.PassiveGiftHolderContext holder(
        Long holomemId,
        String stageZone,
        String passiveEffectJsonText
    ) {
        return new MatchPassiveGiftIncomingDamageReductionResolverService.PassiveGiftHolderContext(
            holomemId,
            stageZone,
            passiveEffectJsonText
        );
    }

    private String passiveJson(String rawText) {
        return "{\"キーワード\":" + objectMapper.valueToTree(rawText).toString() + "}";
    }

    private MatchPassiveGiftIncomingDamageReductionResolverService.PassiveGiftIncomingDamageReductionTargetContext target(
        Long holomemId,
        String stageZone,
        String levelType,
        String cardName,
        Set<String> tags,
        String oshiCardName,
        String incomingSourceLevelType
    ) {
        return new MatchPassiveGiftIncomingDamageReductionResolverService.PassiveGiftIncomingDamageReductionTargetContext(
            holomemId,
            stageZone,
            levelType,
            cardName,
            tags,
            oshiCardName,
            incomingSourceLevelType
        );
    }
}
