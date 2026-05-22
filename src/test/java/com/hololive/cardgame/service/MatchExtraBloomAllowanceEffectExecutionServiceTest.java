package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import com.hololive.cardgame.service.effect.GiftTriggerMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class MatchExtraBloomAllowanceEffectExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(objectMapper);

    @Test
    void executeAllowExtraBloomEffectShouldReturnNoOpWhenRawTextIsMissing() throws Exception {
        MatchExtraBloomAllowanceEffectExecutionService service = service(2, null, true);

        Map<String, Object> summary = service.executeAllowExtraBloomEffect(
            1L,
            10L,
            "ALLOW_EXTRA_BLOOM",
            node("{}")
        );

        assertThat(summary)
            .containsEntry("effectType", "ALLOW_EXTRA_BLOOM")
            .containsEntry("applied", false)
            .containsEntry("reason", "沒有可判讀的額外 Bloom 文案");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeAllowExtraBloomEffectShouldReturnNoOpWhenLifeThresholdFails() throws Exception {
        MatchExtraBloomAllowanceEffectExecutionService service = service(2, null, true);
        when(jdbcTemplate.query(contains("SELECT current_life"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(4);

        Map<String, Object> summary = service.executeAllowExtraBloomEffect(
            1L,
            10L,
            "ALLOW_EXTRA_BLOOM",
            node("{\"rawText\":\"自分のライフが3以下なら、このターンにBloomしたセンターホロメンはもう1回Bloomできる。\"}")
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "條件不成立：目前 Life 大於 3");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeAllowExtraBloomEffectShouldReturnNoOpWhenRequiredOshiDoesNotMatch() throws Exception {
        MatchExtraBloomAllowanceEffectExecutionService service = service(2, "輪堂千速", true);
        when(jdbcTemplate.query(contains("SELECT current_life"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(3);
        when(jdbcTemplate.query(contains("mp.oshi_card_id"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn("別の推し");

        Map<String, Object> summary = service.executeAllowExtraBloomEffect(
            1L,
            10L,
            "ALLOW_EXTRA_BLOOM",
            node("{\"rawText\":\"自分の推しホロメンが〈輪堂千速〉で、このホロメンはもう1回Bloomできる。\"}")
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "條件不成立：推しホロメン不符合要求");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeAllowExtraBloomEffectShouldReturnNoOpWhenOpponentFirstConditionFails() throws Exception {
        MatchExtraBloomAllowanceEffectExecutionService service = service(2, "輪堂千速", false);
        when(jdbcTemplate.query(contains("SELECT current_life"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(3);
        when(jdbcTemplate.query(contains("mp.oshi_card_id"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn("輪堂千速");

        Map<String, Object> summary = service.executeAllowExtraBloomEffect(
            1L,
            10L,
            "ALLOW_EXTRA_BLOOM",
            node("{\"rawText\":\"自分の推しホロメンが〈輪堂千速〉で、相手のステージに1stホロメンがいるなら、このホロメンはもう1回Bloomできる。\"}")
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "條件不成立：相手ステージ沒有 1st Holomem");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeAllowExtraBloomEffectShouldInsertAllowanceForPreferredCurrentTurnBloomTarget() throws Exception {
        MatchExtraBloomAllowanceEffectExecutionService service = service(2, "輪堂千速", true);
        when(jdbcTemplate.query(contains("SELECT current_life"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(3);
        when(jdbcTemplate.query(contains("mp.oshi_card_id"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn("輪堂千速");
        when(jdbcTemplate.query(contains("AND h.id = ?"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(100L)))
            .thenReturn(target(100L, 1000L, "HSD10-004", "輪堂千速", "CENTER"));
        when(jdbcTemplate.query(contains("payload ->> 'targetHolomemId'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(2), eq("100")))
            .thenReturn(0);
        when(jdbcTemplate.update(
            contains("ALLOW_EXTRA_BLOOM"),
            eq(1L),
            eq(10L),
            eq(10L),
            eq("BUFF"),
            eq(2),
            contains("\"targetHolomemId\":100")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeAllowExtraBloomEffect(
            1L,
            10L,
            "ALLOW_EXTRA_BLOOM",
            node("{\"rawText\":\"自分の推しホロメンが〈輪堂千速〉で、相手のステージに1stホロメンがいるなら、このホロメンはもう1回Bloomできる。\"}"),
            100L,
            9000L
        );

        assertThat(summary)
            .containsEntry("effectType", "ALLOW_EXTRA_BLOOM")
            .containsEntry("applied", true)
            .containsEntry("targetHolomemId", 100L)
            .containsEntry("targetHolomemCardInstanceId", 1000L)
            .containsEntry("targetCardId", "HSD10-004")
            .containsEntry("targetName", "輪堂千速")
            .containsEntry("targetZone", "CENTER")
            .containsEntry("expiresTurn", 2);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeAllowExtraBloomEffectShouldSelectAllowedCurrentTurnBloomTarget() throws Exception {
        MatchExtraBloomAllowanceEffectExecutionService service = service(3, null, true);
        when(jdbcTemplate.query(contains("SELECT current_life"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(3);
        when(jdbcTemplate.query(contains("AND h.last_bloom_turn = ?"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(3)))
            .thenReturn(target(200L, 2000L, "HBP05-040", "さくらみこ", "CENTER"));
        when(jdbcTemplate.query(contains("payload ->> 'targetHolomemId'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(3), eq("200")))
            .thenReturn(0);
        when(jdbcTemplate.update(
            contains("ALLOW_EXTRA_BLOOM"),
            eq(1L),
            eq(10L),
            eq(10L),
            eq("BUFF"),
            eq(3),
            contains("\"targetCardId\":\"HBP05-040\"")
        )).thenReturn(1);

        Map<String, Object> summary = service.executeAllowExtraBloomEffect(
            1L,
            10L,
            "ALLOW_EXTRA_BLOOM",
            node("{\"rawText\":\"自分のライフが3以下なら、このターンにBloomしたセンターホロメン〈さくらみこ〉はもう1回Bloomできる。\"}")
        );

        assertThat(summary)
            .containsEntry("applied", true)
            .containsEntry("targetHolomemId", 200L)
            .containsEntry("targetName", "さくらみこ")
            .containsEntry("targetZone", "CENTER");
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void executeAllowExtraBloomEffectShouldReturnNoOpWhenDuplicateAllowanceExists() throws Exception {
        MatchExtraBloomAllowanceEffectExecutionService service = service(3, null, true);
        when(jdbcTemplate.query(contains("SELECT current_life"), any(ResultSetExtractor.class), eq(1L), eq(10L)))
            .thenReturn(3);
        when(jdbcTemplate.query(contains("AND h.last_bloom_turn = ?"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(3)))
            .thenReturn(target(200L, 2000L, "HBP05-040", "さくらみこ", "CENTER"));
        when(jdbcTemplate.query(contains("payload ->> 'targetHolomemId'"), any(ResultSetExtractor.class), eq(1L), eq(10L), eq(3), eq("200")))
            .thenReturn(1);

        Map<String, Object> summary = service.executeAllowExtraBloomEffect(
            1L,
            10L,
            "ALLOW_EXTRA_BLOOM",
            node("{\"rawText\":\"自分のライフが3以下なら、このターンにBloomしたセンターホロメン〈さくらみこ〉はもう1回Bloomできる。\"}")
        );

        assertThat(summary)
            .containsEntry("applied", false)
            .containsEntry("reason", "本回合已存在同目標的額外 Bloom 許可");
        verify(jdbcTemplate).query(
            contains("payload ->> 'targetHolomemId'"),
            any(ResultSetExtractor.class),
            eq(1L),
            eq(10L),
            eq(3),
            eq("200")
        );
    }

    private MatchExtraBloomAllowanceEffectExecutionService service(
        int currentTurn,
        String requiredOshiName,
        boolean opponentFirstExists
    ) {
        return new MatchExtraBloomAllowanceEffectExecutionService(
            jdbcTemplate,
            effectTextParser,
            new GiftTriggerMatcher(),
            matchId -> currentTurn,
            rawText -> requiredOshiName,
            (matchId, userId, levelType) -> opponentFirstExists,
            this::containsAnyName
        );
    }

    private boolean containsAnyName(String source, List<String> candidates) {
        if (source == null || candidates == null) {
            return false;
        }
        return candidates.stream().anyMatch(source::contains);
    }

    private Map<String, Object> target(Long holomemId, Long matchCardId, String cardId, String name, String zone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("holomem_id", holomemId);
        row.put("match_card_id", matchCardId);
        row.put("card_id", cardId);
        row.put("name", name);
        row.put("zone", zone);
        return row;
    }

    private JsonNode node(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
