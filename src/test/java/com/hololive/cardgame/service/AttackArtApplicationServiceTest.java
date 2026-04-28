package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackArtApplicationServiceTest {

    @Test
    void executeShouldResolveAttackStagesInOrder() {
        List<String> order = new ArrayList<>();
        AttackArtApplicationService service = service(order);

        service.execute(context());

        assertThat(order).containsExactly(
            AttackArtApplicationService.STAGE_PRE_DAMAGE_FOLLOWUP,
            AttackArtApplicationService.STAGE_COST,
            AttackArtApplicationService.STAGE_TARGET,
            AttackArtApplicationService.STAGE_DAMAGE,
            AttackArtApplicationService.STAGE_DAMAGE_PREVENTION,
            AttackArtApplicationService.STAGE_DAMAGE_APPLICATION,
            AttackArtApplicationService.STAGE_POST_DAMAGE_FOLLOWUP,
            AttackArtApplicationService.STAGE_DOWN,
            AttackArtApplicationService.STAGE_DEFENDER_GIFT_FOLLOWUP,
            AttackArtApplicationService.STAGE_POST_TRIGGER_PENDING,
            AttackArtApplicationService.STAGE_REST_AND_PAYLOAD,
            AttackArtApplicationService.STAGE_ACTION_LOG,
            AttackArtApplicationService.STAGE_FINISH_CHECK
        );
    }

    @Test
    void executeShouldExposePreviousStageResultsToLaterResolvers() {
        AttackArtApplicationService.AttackStageResolver preDamage = (context, previous) -> "pre";
        AttackArtApplicationService.AttackStageResolver cost = (context, previous) -> previous.get(
            AttackArtApplicationService.STAGE_PRE_DAMAGE_FOLLOWUP
        );
        AttackArtApplicationService service = new AttackArtApplicationService(
            preDamage,
            cost,
            stage("target"),
            stage("damage"),
            stage("damagePrevention"),
            stage("damageApplication"),
            stage("postDamage"),
            stage("down"),
            stage("defenderGift"),
            stage("pending"),
            stage("payload"),
            stage("actionLog"),
            stage("finish")
        );

        AttackArtApplicationResult result = service.execute(context());

        assertThat(result.stageResult(AttackArtApplicationService.STAGE_COST)).isEqualTo("pre");
    }

    @Test
    void executeShouldReturnPayloadActionLogAndFinishResult() {
        AttackArtApplicationService service = new AttackArtApplicationService(
            stage("pre"),
            stage("cost"),
            stage("target"),
            stage("damage"),
            stage("prevention"),
            stage("application"),
            stage("post"),
            stage("down"),
            stage("defenderGift"),
            stage("pending"),
            (context, previous) -> new PayloadResult(Map.of("artTotalDamage", 120)),
            stage("actionLog"),
            stage("finish")
        );

        AttackArtApplicationResult result = service.execute(context());

        assertThat(result.payload()).containsEntry("artTotalDamage", 120);
        assertThat(result.actionLogResult()).isEqualTo("actionLog");
        assertThat(result.finishCheckResult()).isEqualTo("finish");
    }

    @Test
    void executeShouldRejectMissingContext() {
        assertThatThrownBy(() -> service(new ArrayList<>()).execute(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("attack art application");
    }

    private AttackArtApplicationService service(List<String> order) {
        return new AttackArtApplicationService(
            orderedStage(AttackArtApplicationService.STAGE_PRE_DAMAGE_FOLLOWUP, order),
            orderedStage(AttackArtApplicationService.STAGE_COST, order),
            orderedStage(AttackArtApplicationService.STAGE_TARGET, order),
            orderedStage(AttackArtApplicationService.STAGE_DAMAGE, order),
            orderedStage(AttackArtApplicationService.STAGE_DAMAGE_PREVENTION, order),
            orderedStage(AttackArtApplicationService.STAGE_DAMAGE_APPLICATION, order),
            orderedStage(AttackArtApplicationService.STAGE_POST_DAMAGE_FOLLOWUP, order),
            orderedStage(AttackArtApplicationService.STAGE_DOWN, order),
            orderedStage(AttackArtApplicationService.STAGE_DEFENDER_GIFT_FOLLOWUP, order),
            orderedStage(AttackArtApplicationService.STAGE_POST_TRIGGER_PENDING, order),
            orderedStage(AttackArtApplicationService.STAGE_REST_AND_PAYLOAD, order),
            orderedStage(AttackArtApplicationService.STAGE_ACTION_LOG, order),
            orderedStage(AttackArtApplicationService.STAGE_FINISH_CHECK, order)
        );
    }

    private AttackArtApplicationService.AttackStageResolver orderedStage(String name, List<String> order) {
        return (context, previous) -> {
            order.add(name);
            return name;
        };
    }

    private AttackArtApplicationService.AttackStageResolver stage(String result) {
        return (context, previous) -> result;
    }

    private AttackArtApplicationContext context() {
        return AttackArtApplicationContext.attackArt(
            null,
            100L,
            10L,
            20L,
            3,
            3001L,
            4001L,
            501L,
            "CENTER",
            "HBP01-087",
            "SECOND",
            "BLUE",
            "雨のマントラ",
            1,
            "{}",
            "{\"type\":\"DAMAGE\",\"value\":50}",
            null,
            Map.of(),
            List.of()
        );
    }

    private record PayloadResult(Map<String, Object> payload)
        implements AttackArtApplicationService.AttackPayloadCarrier {
    }
}
