package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AttackTargetServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AttackTargetService service = new AttackTargetService(jdbcTemplate, new ObjectMapper());

    @Test
    void resolveTargetShouldReturnNoOpponentWhenOpponentHasNoHolomem() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(100L), eq(20L))).thenReturn(0);
        AttackTargetContext context = AttackTargetContext.resolve(100L, 10L, 20L, 3, 701L);

        AttackTargetResult result = service.resolveTarget(context);

        assertThat(result.hasOpponentHolomem()).isFalse();
        assertThat(result.target()).isNull();
        assertThat(result.effectiveTargetCardInstanceId()).isEqualTo(701L);
        assertThat(result.passiveGiftTargetRestrictionToCollab()).isFalse();
        assertThat(result.damageRedirectApplied()).isFalse();
    }

    @Test
    void extractRequiredCenterTagShouldReturnTagWhenPassiveTextHasRequirement() {
        String passiveText = "条件:#Promiseを持つセンターホロメンがいる間、相手のホロメンのアーツは自分のコラボホロメンしか対象にできない。";

        String result = service.extractRequiredCenterTagForPassiveTargetRestriction(passiveText);

        assertThat(result).isEqualTo("#Promise");
    }

    @Test
    void hasPassiveGiftTargetRestrictionShouldRequireCenterTagWhenTextSpecifiesTag() {
        when(jdbcTemplate.query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
            eq(100L),
            eq(20L)
        )).thenReturn(List.of(
            "#Promiseを持つセンターホロメンがいる間、相手のホロメンのアーツは自分のコラボホロメンしか対象にできない。"
        ));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(100L), eq(20L), eq("#Promise")))
            .thenReturn(1);

        boolean result = service.hasPassiveGiftTargetRestrictionToCollab(100L, 20L);

        assertThat(result).isTrue();
    }

    @Test
    void resolveDamageRedirectTargetShouldConsumeMatchedRedirectEffect() {
        TestAttackTargetService testService = new TestAttackTargetService(jdbcTemplate);
        when(jdbcTemplate.queryForList(anyString(), eq(100L), eq(20L), eq(3))).thenReturn(List.of(Map.of(
            "id",
            501L,
            "payload_text",
            "{\"actions\":[\"DAMAGE_REDIRECT\"],\"targetHolomemId\":901}"
        )));
        when(jdbcTemplate.update(anyString(), eq(501L), eq(100L))).thenReturn(1);

        AttackTargetService.DamageRedirectTarget result = testService.resolveDamageRedirectTarget(100L, 20L, 3);

        assertThat(result).isNotNull();
        assertThat(result.effectId()).isEqualTo(501L);
        assertThat(result.target().holomemId()).isEqualTo(901L);
        verify(jdbcTemplate).update(anyString(), eq(501L), eq(100L));
    }

    private static class TestAttackTargetService extends AttackTargetService {

        TestAttackTargetService(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate, new ObjectMapper());
        }

        @Override
        AttackTargetHolomem loadTargetHolomemById(Long matchId, Long ownerUserId, Long holomemId) {
            return new AttackTargetHolomem(holomemId, 701L, "TEST_TARGET", "CENTER", "RED");
        }
    }
}
