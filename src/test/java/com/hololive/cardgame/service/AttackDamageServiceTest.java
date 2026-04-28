package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AttackDamageServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchEffectCombatModifierService matchEffectCombatModifierService =
        mock(MatchEffectCombatModifierService.class);
    private final AttackDamageService service =
        new AttackDamageService(jdbcTemplate, new ObjectMapper(), matchEffectCombatModifierService);

    @Test
    void resolveDamageShouldBuildPayloadFieldsAndApplyCriticalWhenTargetColorMatches() {
        AttackTargetHolomem target = new AttackTargetHolomem(901L, 701L, "TARGET", "CENTER", "RED");
        when(matchEffectCombatModifierService.resolveAttachedSupportArtBonus(100L, 501L)).thenReturn(10);
        when(matchEffectCombatModifierService.resolveArtTextDamageBonus(
            100L,
            10L,
            0,
            501L,
            "{\"damage\":30,\"rawText\":\"赤+50\"}"
        )).thenReturn(20);
        when(matchEffectCombatModifierService.resolvePassiveGiftArtBonus(100L, 10L, 501L, "CENTER")).thenReturn(15);
        when(matchEffectCombatModifierService.resolvePassiveGiftIncomingDamageReduction(100L, 20L, 901L, "DEBUT"))
            .thenReturn(4);
        when(matchEffectCombatModifierService.resolveAttachedSupportIncomingDamageReduction(100L, 901L, "CENTER"))
            .thenReturn(6);

        AttackDamageResult result = service.resolveDamage(AttackDamageContext.resolve(
            100L,
            10L,
            20L,
            0,
            501L,
            "1st",
            target,
            true,
            "{\"damage\":30,\"rawText\":\"赤+50\"}",
            5
        ));

        assertThat(result.baseDamage()).isEqualTo(30);
        assertThat(result.criticalColor()).isEqualTo("RED");
        assertThat(result.criticalBonus()).isEqualTo(50);
        assertThat(result.criticalApplied()).isTrue();
        assertThat(result.incomingDamageReduction()).isEqualTo(10);
        assertThat(result.totalDamage()).isEqualTo(120);
        assertThat(result.toPayloadFields())
            .containsEntry("artBaseDamage", 30)
            .containsEntry("attachedSupportArtBonus", 10)
            .containsEntry("artTextDamageBonus", 20)
            .containsEntry("holoxRevealArtBonus", 5)
            .containsEntry("passiveGiftArtBonus", 15)
            .containsEntry("turnArtDamageModifier", 0)
            .containsEntry("criticalColor", "RED")
            .containsEntry("criticalBonus", 50)
            .containsEntry("criticalApplied", true)
            .containsEntry("turnIncomingDamageReduction", 0)
            .containsEntry("passiveGiftIncomingDamageReduction", 4)
            .containsEntry("attachedSupportIncomingDamageReduction", 6)
            .containsEntry("incomingDamageReduction", 10)
            .containsEntry("artTotalDamage", 120);
    }

    @Test
    void resolveDamageShouldNotApplyCriticalWhenColorDiffers() {
        AttackTargetHolomem target = new AttackTargetHolomem(901L, 701L, "TARGET", "CENTER", "BLUE");

        AttackDamageResult result = service.resolveDamage(AttackDamageContext.resolve(
            100L,
            10L,
            20L,
            0,
            501L,
            "DEBUT",
            target,
            true,
            "{\"damage\":30,\"rawText\":\"赤+50\"}",
            0
        ));

        assertThat(result.criticalColor()).isEqualTo("RED");
        assertThat(result.criticalBonus()).isZero();
        assertThat(result.criticalApplied()).isFalse();
        assertThat(result.totalDamage()).isEqualTo(30);
    }

    @Test
    void resolveDamageShouldNotQueryTargetReductionsWhenNoOpponentHolomem() {
        when(matchEffectCombatModifierService.resolveAttachedSupportArtBonus(100L, 501L)).thenReturn(3);

        AttackDamageResult result = service.resolveDamage(AttackDamageContext.resolve(
            100L,
            10L,
            20L,
            0,
            501L,
            "DEBUT",
            null,
            false,
            "80 damage text",
            0
        ));

        assertThat(result.baseDamage()).isEqualTo(80);
        assertThat(result.totalDamage()).isEqualTo(83);
        verify(matchEffectCombatModifierService).resolveAttachedSupportArtBonus(100L, 501L);
        verify(matchEffectCombatModifierService).resolveArtTextDamageBonus(100L, 10L, 0, 501L, "80 damage text");
    }

    @Test
    void resolveDamageShouldClampTotalAtZero() {
        AttackTargetHolomem target = new AttackTargetHolomem(901L, 701L, "TARGET", "CENTER", "RED");
        when(matchEffectCombatModifierService.resolvePassiveGiftIncomingDamageReduction(100L, 20L, 901L, "DEBUT"))
            .thenReturn(50);
        when(matchEffectCombatModifierService.resolveAttachedSupportIncomingDamageReduction(100L, 901L, "CENTER"))
            .thenReturn(50);

        AttackDamageResult result = service.resolveDamage(AttackDamageContext.resolve(
            100L,
            10L,
            20L,
            0,
            501L,
            "DEBUT",
            target,
            true,
            "{\"damage\":10}",
            0
        ));

        assertThat(result.incomingDamageReduction()).isEqualTo(100);
        assertThat(result.totalDamage()).isZero();
    }

    @Test
    void resolveArtDamageShouldUseJsonFieldsBeforeFallbackText() throws Exception {
        assertThat(service.resolveArtDamageFromEffectJson(new ObjectMapper().readTree("{\"baseDamage\":70}")))
            .isEqualTo(70);
        assertThat(service.resolveArtDamage("{not-json 40")).isEqualTo(40);
    }

    @Test
    void resolveDamageShouldNotUseCombatModifiersWhenContextHasNoIds() {
        AttackDamageResult result = service.resolveDamage(AttackDamageContext.resolve(
            null,
            null,
            null,
            0,
            null,
            "",
            null,
            false,
            "{\"damage\":20}",
            0
        ));

        assertThat(result.totalDamage()).isEqualTo(20);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void payloadFieldsShouldExposeLegacyKeys() {
        AttackDamageResult result = new AttackDamageResult(
            1,
            2,
            3,
            4,
            5,
            6,
            "RED",
            7,
            true,
            8,
            9,
            10,
            27,
            1
        );

        assertThat(result.toPayloadFields().keySet()).containsExactlyElementsOf(List.of(
            "artBaseDamage",
            "attachedSupportArtBonus",
            "artTextDamageBonus",
            "holoxRevealArtBonus",
            "passiveGiftArtBonus",
            "turnArtDamageModifier",
            "criticalColor",
            "criticalBonus",
            "criticalApplied",
            "turnIncomingDamageReduction",
            "passiveGiftIncomingDamageReduction",
            "attachedSupportIncomingDamageReduction",
            "incomingDamageReduction",
            "artTotalDamage"
        ));
    }
}
