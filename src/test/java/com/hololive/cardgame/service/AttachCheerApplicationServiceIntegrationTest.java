package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AttachCheerApplicationServiceIntegrationTest extends MatchIntegrationTestSupport {

    @Autowired
    private AttachCheerApplicationService attachCheerApplicationService;

    @Test
    void resolveStateShouldMoveCheerCardToStageAndInsertAttachment() {
        StartedMatchContext started = createStartedMatch("attach-cheer-app-host", "attach-cheer-app-guest");
        Long matchId = started.matchId();
        Long hostId = started.hostId();
        String targetCardId = createGeneratedMemberCardDefinition(
            "TATTACH_CHEER_TARGET",
            "Bridge Attach Cheer Member",
            "DEBUT",
            60,
            "WHITE"
        );
        Long targetCardInstanceId = createStageHolomemWithSingleCard(
            matchId,
            hostId,
            targetCardId,
            "CENTER",
            "DEBUT",
            0
        );
        Long cheerCardInstanceId = insertCheerCardIntoZone(matchId, hostId, "WHITE", "CHEER_DECK");
        Integer turnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        AttachCheerAction action = AttachCheerAction.fromApi(
            matchId,
            hostId,
            cheerCardInstanceId,
            targetCardInstanceId,
            turnNumber == null ? 1 : turnNumber,
            "attach-cheer-app-integration"
        );

        AttachCheerValidationContext validationContext = attachCheerApplicationService.validate(action);
        AttachCheerResolutionResult result = attachCheerApplicationService.resolveState(action, validationContext);

        AttachedCheerRow attachedCheer = jdbcTemplate.query(
            """
            SELECT mc.zone, mc.is_face_down, c.match_card_id, c.cheer_card_id
            FROM match_holomem_cheers c
            JOIN match_cards mc ON mc.id = c.match_card_id
            WHERE c.id = ?
            """,
            rs -> rs.next()
                ? new AttachedCheerRow(
                    rs.getString("zone"),
                    rs.getBoolean("is_face_down"),
                    rs.getLong("match_card_id"),
                    rs.getString("cheer_card_id")
                )
                : null,
            result.attachmentId()
        );

        assertThat(result.cheerCardInstanceId()).isEqualTo(cheerCardInstanceId);
        assertThat(result.sourceZone()).isEqualTo("CHEER_DECK");
        assertThat(result.targetHolomemCardInstanceId()).isEqualTo(targetCardInstanceId);
        assertThat(result.attachmentId()).isNotNull();
        assertThat(attachedCheer).isNotNull();
        assertThat(attachedCheer.zone()).isEqualTo("STAGE");
        assertThat(attachedCheer.faceDown()).isFalse();
        assertThat(attachedCheer.matchCardId()).isEqualTo(cheerCardInstanceId);
        assertThat(attachedCheer.cheerCardId()).isEqualTo(result.cheerCardId());
    }

    @Test
    void validateShouldRejectCheerSourceOutsideAllowedZones() {
        StartedMatchContext started = createStartedMatch("attach-cheer-zone-host", "attach-cheer-zone-guest");
        Long matchId = started.matchId();
        Long hostId = started.hostId();
        Long targetCardInstanceId = createAttachCheerTarget(matchId, hostId, "TATTACH_CHEER_ZONE_TARGET");
        Long archivedCheerCardInstanceId = insertCheerCardIntoZone(matchId, hostId, "WHITE", "ARCHIVE");
        AttachCheerAction action = action(matchId, hostId, archivedCheerCardInstanceId, targetCardInstanceId, "zone");

        assertThatThrownBy(() -> attachCheerApplicationService.validate(action))
            .isInstanceOfSatisfying(GameRuleException.class, ex -> {
                assertThat(ex.getCode()).isEqualTo(GameErrorCode.ATTACH_CHEER_INVALID_TARGET);
                assertThat(ex.getMessage()).contains("HAND 或 CHEER_DECK");
            });
    }

    @Test
    void validateShouldRejectNonCheerSourceCard() {
        StartedMatchContext started = createStartedMatch("attach-cheer-non-cheer-host", "attach-cheer-non-cheer-guest");
        Long matchId = started.matchId();
        Long hostId = started.hostId();
        Long targetCardInstanceId = createAttachCheerTarget(matchId, hostId, "TATTACH_CHEER_NON_CHEER_TARGET");
        String memberCardId = createGeneratedMemberCardDefinition(
            "TATTACH_CHEER_NON_CHEER_SOURCE",
            "Attach Cheer Non Cheer Source",
            "DEBUT",
            60,
            "WHITE"
        );
        Long nonCheerCardInstanceId = insertCardIntoZone(matchId, hostId, memberCardId, "HAND", false);
        AttachCheerAction action = action(matchId, hostId, nonCheerCardInstanceId, targetCardInstanceId, "non-cheer");

        assertThatThrownBy(() -> attachCheerApplicationService.validate(action))
            .isInstanceOfSatisfying(GameRuleException.class, ex -> {
                assertThat(ex.getCode()).isEqualTo(GameErrorCode.ATTACH_CHEER_INVALID_TARGET);
                assertThat(ex.getMessage()).contains("不是 Cheer");
            });
    }

    @Test
    void validateShouldRejectMissingTargetHolomem() {
        StartedMatchContext started = createStartedMatch("attach-cheer-missing-target-host", "attach-cheer-missing-target-guest");
        Long matchId = started.matchId();
        Long hostId = started.hostId();
        Long cheerCardInstanceId = insertCheerCardIntoZone(matchId, hostId, "WHITE", "CHEER_DECK");
        AttachCheerAction action = action(matchId, hostId, cheerCardInstanceId, 987654321L, "missing-target");

        assertThatThrownBy(() -> attachCheerApplicationService.validate(action))
            .isInstanceOfSatisfying(GameRuleException.class, ex -> {
                assertThat(ex.getCode()).isEqualTo(GameErrorCode.NOT_FOUND);
                assertThat(ex.getMessage()).contains("Holomem");
            });
    }

    @Override
    protected void executeRequiredTurnActions(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        resolvePendingInteractionIfExists(matchId, userId, "TURN_START");
        try {
            executeDrawTurn(matchId, userId);
        } catch (IllegalStateException | GameRuleException ex) {
            if (!(ex instanceof GameRuleException gameRuleException)
                || gameRuleException.getCode() != GameErrorCode.TURN_DRAW_ALREADY_USED) {
                String message = ex.getMessage();
                if (message == null || (!message.contains("phase=END") && !message.contains("已經抽過卡"))) {
                    throw ex;
                }
            }
        }
        resolvePendingInteractionIfExists(matchId, userId, "DRAW_REVEAL");
        try {
            matchActionService.sendTurnCheer(matchId, userId);
        } catch (IllegalStateException | GameRuleException ex) {
            if (ex instanceof GameRuleException gameRuleException
                && gameRuleException.getCode() == GameErrorCode.TURN_CHEER_ALREADY_USED) {
                return;
            }
            String message = ex.getMessage();
            if (message == null || (!message.contains("目前無法發送吶喊") && !message.contains("已經發送過吶喊"))) {
                throw ex;
            }
            return;
        }
        Long sendCheerDecisionId = findPendingDecision(matchId, userId, "SEND_CHEER");
        if (sendCheerDecisionId == null) {
            return;
        }
        Long effectiveTargetCardInstanceId = sendCheerTargetCardInstanceId == null
            ? loadFirstStageCardInstanceId(matchId, userId)
            : sendCheerTargetCardInstanceId;
        if (effectiveTargetCardInstanceId == null) {
            return;
        }
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setDecisionId(sendCheerDecisionId);
        request.setSelectedCardInstanceIds(List.of(effectiveTargetCardInstanceId));
        matchActionService.resolveDecision(matchId, userId, request);
    }

    private Long createAttachCheerTarget(Long matchId, Long hostId, String prefix) {
        String targetCardId = createGeneratedMemberCardDefinition(
            prefix,
            "Bridge Attach Cheer Member",
            "DEBUT",
            60,
            "WHITE"
        );
        return createStageHolomemWithSingleCard(
            matchId,
            hostId,
            targetCardId,
            "CENTER",
            "DEBUT",
            0
        );
    }

    private AttachCheerAction action(
        Long matchId,
        Long hostId,
        Long cheerCardInstanceId,
        Long targetCardInstanceId,
        String traceSuffix
    ) {
        Integer turnNumber = jdbcTemplate.queryForObject(
            "SELECT turn_number FROM matches WHERE id = ?",
            Integer.class,
            matchId
        );
        return AttachCheerAction.fromApi(
            matchId,
            hostId,
            cheerCardInstanceId,
            targetCardInstanceId,
            turnNumber == null ? 1 : turnNumber,
            "attach-cheer-app-integration-" + traceSuffix
        );
    }

    private record AttachedCheerRow(
        String zone,
        boolean faceDown,
        Long matchCardId,
        String cheerCardId
    ) {
    }
}
