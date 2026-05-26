package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MatchAttachedSupportIncomingDamageReductionResolverServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EffectTextParser effectTextParser = new EffectTextParser(new ObjectMapper());
    private final MatchAttachedSupportIncomingDamageReductionResolverService service =
        new MatchAttachedSupportIncomingDamageReductionResolverService(jdbcTemplate, effectTextParser);

    @Test
    @SuppressWarnings("unchecked")
    void resolveShouldSumAttachedSupportIncomingDamageReductionEffects() {
        when(
            jdbcTemplate.query(
                contains("FROM match_holomem_supports hs"),
                any(RowMapper.class),
                eq(901L),
                eq(100L)
            )
        ).thenReturn(List.of(
            "{\"rawText\":\"このマスコットが付いているホロメンが受けるダメージ-10。\"}",
            "{\"rawText\":\"このツールが付いているホロメンがセンターポジションで受けるダメージ-20。\"}",
            "{\"rawText\":\"このファンが付いているホロメンがコラボポジションで受けるダメージ-30。\"}"
        ));

        int reduction = service.resolveAttachedSupportIncomingDamageReduction(100L, 901L, "CENTER");

        assertThat(reduction).isEqualTo(30);
    }

    @Test
    void extractShouldSkipConditionalOptionalCostAndNonHolderClauses() {
        String effectJsonText = """
            {
              "rawText": "このマスコットが付いているホロメンが受けるダメージ-10。自分のホロメンが受けるダメージ-20。受けるダメージ-30できる。このツールをアーカイブ：受けるダメージ-40。◆このマスコットが付いているホロメンが受けるダメージ-50。"
            }
            """;

        int reduction = service.extractAttachedSupportIncomingDamageReduction(effectJsonText, "CENTER");

        assertThat(reduction).isEqualTo(10);
    }

    @Test
    void extractShouldRespectTargetStageZoneRestrictions() {
        String effectJsonText = """
            {
              "rawText": "このツールが付いているホロメンがセンターポジションで受けるダメージ-20。このツールが付いているホロメンがコラボポジションで受けるダメージ-30。このツールが付いているホロメンがバックポジションで受けるダメージ-40。"
            }
            """;

        assertThat(service.extractAttachedSupportIncomingDamageReduction(effectJsonText, "CENTER")).isEqualTo(20);
        assertThat(service.extractAttachedSupportIncomingDamageReduction(effectJsonText, "COLLAB")).isEqualTo(30);
        assertThat(service.extractAttachedSupportIncomingDamageReduction(effectJsonText, "BACK")).isEqualTo(40);
    }

    @Test
    void resolveShouldSkipDbForInvalidInput() {
        assertThat(service.resolveAttachedSupportIncomingDamageReduction(null, 901L, "CENTER")).isZero();
        assertThat(service.resolveAttachedSupportIncomingDamageReduction(100L, null, "CENTER")).isZero();
        verifyNoInteractions(jdbcTemplate);
    }
}
