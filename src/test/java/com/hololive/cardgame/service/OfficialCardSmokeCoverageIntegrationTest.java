package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hololive.cardgame.dto.AttackArtActionRequest;
import com.hololive.cardgame.dto.MulliganActionRequest;
import com.hololive.cardgame.dto.PlaySupportActionRequest;
import com.hololive.cardgame.dto.PlayToStageActionRequest;
import com.hololive.cardgame.dto.ResolveDecisionRequest;
import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import com.hololive.cardgame.model.LobbyMatch;
import com.hololive.cardgame.repository.UserRepository;
import com.hololive.cardgame.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class OfficialCardSmokeCoverageIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Pattern ATTACHED_SUPPORT_HP_SMOKE_PATTERN = Pattern.compile(
        "この(?:マスコット|ツール|ファン)が付いているホロメンのHP\\s*([+＋−-]\\s*\\d+)"
    );
    private static final Pattern ATTACHED_SUPPORT_ARTS_SMOKE_PATTERN = Pattern.compile(
        "この(?:マスコット|ツール|ファン)が付いているホロメンのアーツ\\s*([+＋−-]\\s*\\d+)"
    );
    private static final Pattern ATTACHED_SUPPORT_DAMAGE_REDUCTION_SMOKE_PATTERN = Pattern.compile(
        "受けるダメージ\\s*[−-]\\s*(\\d+)"
    );

    @Autowired
    private LobbyMatchService lobbyMatchService;

    @Autowired
    private MatchActionService matchActionService;

    @Autowired
    private MatchEffectService matchEffectService;

    @Autowired
    private DeckService deckService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private DiceService diceService;

    @BeforeEach
    void setupDiceRoll() {
        Mockito.when(diceService.rollD6()).thenReturn(6);
    }

    @Test
    void officialNonAttachableSupportCardsShouldRemainEffectEngineSmokeCovered() {
        List<SupportSmokeCard> supportCards = loadNonAttachableSupportSmokeCards();
        assertThat(supportCards).hasSizeGreaterThan(40);

        StartedMatchContext context = createStartedMatch("support-smoke-host", "support-smoke-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        List<String> unsupportedFailures = new ArrayList<>();
        List<String> parserFailures = new ArrayList<>();
        for (int index = 0; index < supportCards.size(); index++) {
            SupportSmokeCard supportCard = supportCards.get(index);
            prepareSupportSmokeBoard(matchId, hostId, guestId, index);
            Long targetHolomemCardInstanceId = loadFirstStageCardInstanceId(matchId, hostId);

            Map<String, Object> summary;
            try {
                summary = matchEffectService.applySupportEffect(
                    matchId,
                    hostId,
                    supportCard.effectType(),
                    supportCard.effectJsonText(),
                    supportCard.targetType(),
                    List.of(),
                    targetHolomemCardInstanceId,
                    true
                );
            } catch (RuntimeException ex) {
                parserFailures.add(supportCard.cardId() + " " + supportCard.name() + ": " + ex.getMessage());
                continue;
            }

            List<?> unsupportedEffects = summary.get("unsupportedEffects") instanceof List<?> values
                ? values
                : List.of();
            if (!unsupportedEffects.isEmpty()) {
                unsupportedFailures.add(
                    supportCard.cardId() + " " + supportCard.name() + ": " + unsupportedEffects
                );
            }
        }

        assertThat(parserFailures).isEmpty();
        assertThat(unsupportedFailures).isEmpty();
    }

    @Test
    void officialOshiSkillsShouldRemainEffectEngineSmokeCovered() {
        List<OshiSkillSmokeCard> oshiSkills = loadOshiSkillSmokeCards();
        assertThat(oshiSkills).hasSizeGreaterThan(100);

        StartedMatchContext context = createStartedMatch("oshi-smoke-host", "oshi-smoke-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        List<String> unsupportedFailures = new ArrayList<>();
        List<String> parserFailures = new ArrayList<>();
        for (int index = 0; index < oshiSkills.size(); index++) {
            OshiSkillSmokeCard oshiSkill = oshiSkills.get(index);
            prepareSupportSmokeBoard(matchId, hostId, guestId, index);
            Long targetHolomemCardInstanceId = loadFirstStageCardInstanceId(matchId, hostId);

            Map<String, Object> summary;
            try {
                summary = matchEffectService.applySupportEffect(
                    matchId,
                    hostId,
                    oshiSkill.effectType(),
                    oshiSkill.effectJsonText(),
                    oshiSkill.targetType(),
                    List.of(),
                    targetHolomemCardInstanceId,
                    true
                );
            } catch (RuntimeException ex) {
                parserFailures.add(
                    oshiSkill.oshiCardId() + " " + oshiSkill.skillType() + " " + oshiSkill.skillName()
                        + ": " + ex.getMessage()
                );
                continue;
            }

            List<?> unsupportedEffects = summary.get("unsupportedEffects") instanceof List<?> values
                ? values
                : List.of();
            if (!unsupportedEffects.isEmpty()) {
                unsupportedFailures.add(
                    oshiSkill.oshiCardId() + " " + oshiSkill.skillType() + " " + oshiSkill.skillName()
                        + ": " + unsupportedEffects
                );
            }
        }

        assertThat(parserFailures).isEmpty();
        assertThat(unsupportedFailures).isEmpty();
    }

    @Test
    void officialAttachableSupportCardsShouldRemainPlayableSmokeCovered() {
        List<AttachableSupportSmokeCard> supportCards = loadAttachableSupportSmokeCards();
        assertThat(supportCards).hasSizeGreaterThan(40);

        StartedMatchContext context = createStartedMatch("attach-support-smoke-host", "attach-support-smoke-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        List<String> playFailures = new ArrayList<>();
        List<String> attachFailures = new ArrayList<>();
        for (int index = 0; index < supportCards.size(); index++) {
            AttachableSupportSmokeCard supportCard = supportCards.get(index);
            prepareSupportSmokeBoard(matchId, hostId, guestId, index);
            Long targetHolomemCardInstanceId = loadFirstStageCardInstanceId(matchId, hostId);
            Long supportCardInstanceId = insertCardIntoZone(matchId, hostId, supportCard.cardId(), "HAND", false);

            try {
                PlaySupportActionRequest request = new PlaySupportActionRequest();
                request.setCardInstanceId(supportCardInstanceId);
                request.setTargetHolomemCardInstanceId(targetHolomemCardInstanceId);
                matchActionService.playSupport(matchId, hostId, request);
            } catch (RuntimeException ex) {
                playFailures.add(supportCard.cardId() + " " + supportCard.name() + ": " + ex.getMessage());
                cleanupSupportSmokeAttachment(supportCardInstanceId);
                continue;
            }

            Integer attachedRows = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_holomem_supports hs
                JOIN match_holomems h ON h.id = hs.match_holomem_id
                WHERE h.match_id = ?
                  AND h.owner_user_id = ?
                  AND h.match_card_id = ?
                  AND hs.match_card_id = ?
                  AND hs.support_type = ?
                """,
                Integer.class,
                matchId,
                hostId,
                targetHolomemCardInstanceId,
                supportCardInstanceId,
                supportCard.supportType()
            );
            Integer supportOnStage = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_cards
                WHERE id = ?
                  AND match_id = ?
                  AND owner_user_id = ?
                  AND zone = 'STAGE'
                """,
                Integer.class,
                supportCardInstanceId,
                matchId,
                hostId
            );

            if (attachedRows == null || attachedRows != 1 || supportOnStage == null || supportOnStage != 1) {
                attachFailures.add(
                    supportCard.cardId() + " " + supportCard.name()
                        + ": rows=" + attachedRows + ", stage=" + supportOnStage
                );
            }
            cleanupSupportSmokeAttachment(supportCardInstanceId);
        }

        assertThat(playFailures).isEmpty();
        assertThat(attachFailures).isEmpty();
    }

    @Test
    void officialAttachableSupportStaticBonusesShouldRemainOngoingSmokeCovered() {
        List<AttachableSupportStaticBonusSmokeCard> supportCards = loadAttachableSupportStaticBonusSmokeCards();
        assertThat(supportCards).hasSizeGreaterThan(50);

        StartedMatchContext context = createStartedMatch(
            "attach-support-static-smoke-host",
            "attach-support-static-smoke-guest"
        );
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        Long targetCardInstanceId = loadFirstStageCardInstanceId(matchId, hostId);
        Long targetHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, targetCardInstanceId);
        assertThat(targetCardInstanceId).isNotNull();
        assertThat(targetHolomemId).isNotNull();

        List<String> bonusFailures = new ArrayList<>();
        for (int index = 0; index < supportCards.size(); index++) {
            AttachableSupportStaticBonusSmokeCard supportCard = supportCards.get(index);
            prepareSupportSmokeBoard(matchId, hostId, guestId, 60_000 + index);
            cleanupSupportSmokeAttachments(targetHolomemId);

            Long supportCardInstanceId = attachSupportForStaticBonusSmoke(
                matchId,
                hostId,
                targetHolomemId,
                supportCard
            );

            int resolvedHpBonus = matchEffectService.resolveAttachedSupportHpBonus(matchId, targetHolomemId);
            int resolvedArtBonus = matchEffectService.resolveAttachedSupportArtBonus(matchId, targetHolomemId);
            if (resolvedHpBonus != supportCard.expectedHpBonus()
                || resolvedArtBonus != supportCard.expectedArtBonus()) {
                bonusFailures.add(
                    supportCard.cardId() + " " + supportCard.name()
                        + ": hp=" + resolvedHpBonus + "/" + supportCard.expectedHpBonus()
                        + ", art=" + resolvedArtBonus + "/" + supportCard.expectedArtBonus()
                );
            }

            cleanupSupportSmokeAttachment(supportCardInstanceId);
        }

        assertThat(bonusFailures).isEmpty();
    }

    @Test
    void officialAttachableSupportDamageReductionShouldRemainOngoingSmokeCovered() {
        List<AttachableSupportDamageReductionSmokeCard> supportCards =
            loadAttachableSupportDamageReductionSmokeCards();
        assertThat(supportCards).hasSizeGreaterThan(2);

        StartedMatchContext context = createStartedMatch(
            "attach-support-damage-reduction-smoke-host",
            "attach-support-damage-reduction-smoke-guest"
        );
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        List<String> reductionFailures = new ArrayList<>();
        for (int index = 0; index < supportCards.size(); index++) {
            AttachableSupportDamageReductionSmokeCard supportCard = supportCards.get(index);
            prepareSupportSmokeBoard(matchId, hostId, guestId, 70_000 + index);

            Long targetCardInstanceId = loadFirstStageCardInstanceIdInZone(
                matchId,
                hostId,
                supportCard.targetZone()
            );
            Long targetHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, targetCardInstanceId);
            assertThat(targetCardInstanceId).isNotNull();
            assertThat(targetHolomemId).isNotNull();

            cleanupSupportSmokeAttachments(targetHolomemId);
            Long supportCardInstanceId = attachSupportForSmoke(
                matchId,
                hostId,
                targetHolomemId,
                supportCard.cardId(),
                supportCard.supportType()
            );

            int resolvedDamageReduction = matchEffectService.resolveAttachedSupportIncomingDamageReduction(
                matchId,
                targetHolomemId,
                supportCard.targetZone()
            );
            if (resolvedDamageReduction != supportCard.expectedDamageReduction()) {
                reductionFailures.add(
                    supportCard.cardId() + " " + supportCard.name()
                        + ": damageReduction=" + resolvedDamageReduction + "/"
                        + supportCard.expectedDamageReduction()
                        + ", targetZone=" + supportCard.targetZone()
                );
            }

            cleanupSupportSmokeAttachment(supportCardInstanceId);
        }

        assertThat(reductionFailures).isEmpty();
    }

    @Test
    void officialAttachableSupportConditionalTriggersShouldRemainPreviewSmokeCovered() {
        List<AttachableSupportConditionalTriggerSmokeCard> supportCards =
            loadAttachableSupportConditionalTriggerSmokeCards();
        assertThat(supportCards).hasSize(11);

        StartedMatchContext context = createStartedMatch(
            "attach-support-conditional-trigger-smoke-host",
            "attach-support-conditional-trigger-smoke-guest"
        );
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        Long targetCardInstanceId = loadFirstStageCardInstanceId(matchId, hostId);
        Long targetHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, targetCardInstanceId);
        assertThat(targetCardInstanceId).isNotNull();
        assertThat(targetHolomemId).isNotNull();

        List<String> triggerFailures = new ArrayList<>();
        for (int index = 0; index < supportCards.size(); index++) {
            AttachableSupportConditionalTriggerSmokeCard supportCard = supportCards.get(index);
            prepareSupportSmokeBoard(matchId, hostId, guestId, 80_000 + index);
            setCurrentTurnPlayer(matchId, guestId, 80_000 + index);
            cleanupSupportSmokeAttachments(targetHolomemId);

            Long supportCardInstanceId = attachSupportForSmoke(
                matchId,
                hostId,
                targetHolomemId,
                supportCard.cardId(),
                supportCard.supportType()
            );

            List<Map<String, Object>> previews = matchEffectService.previewAttachedSupportConditionalTriggers(
                matchId,
                hostId,
                targetHolomemId,
                supportCard.triggerType(),
                80_000 + index
            );
            if (previews.size() != 1) {
                triggerFailures.add(
                    supportCard.cardId() + " " + supportCard.name()
                        + ": previewCount=" + previews.size()
                );
            } else {
                Map<String, Object> preview = previews.get(0);
                Object requestedEffects = preview.get("requestedEffects");
                Object unsupportedEffects = preview.get("unsupportedEffects");
                if (!supportCard.cardId().equals(preview.get("giftHolderCardId"))
                    || !supportCard.triggerType().equals(preview.get("triggerType"))
                    || !(requestedEffects instanceof List<?> requestedList)
                    || requestedList.isEmpty()
                    || !(unsupportedEffects instanceof List<?> unsupportedList)
                    || !unsupportedList.isEmpty()) {
                    triggerFailures.add(
                        supportCard.cardId() + " " + supportCard.name()
                            + ": preview=" + preview
                    );
                }
            }

            cleanupSupportSmokeAttachment(supportCardInstanceId);
        }

        assertThat(triggerFailures).isEmpty();
    }

    @Test
    void officialMemberPrimaryArtsShouldRemainAttackSmokeCovered() {
        List<MemberArtSmokeCard> memberCards = loadMemberArtSmokeCards();
        assertThat(memberCards).hasSizeGreaterThan(250);

        StartedMatchContext context = createStartedMatch("member-art-smoke-host", "member-art-smoke-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        Long attackerCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        Long targetCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        assertThat(attackerCardInstanceId).isNotNull();
        assertThat(targetCardInstanceId).isNotNull();

        Long attackerHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, attackerCardInstanceId);
        assertThat(attackerHolomemId).isNotNull();
        attachFullColorSmokeCheerSet(matchId, hostId, attackerHolomemId);

        String targetCardId = createMemberCardDefinition(
            "TSMOKE_ART_TARGET_" + System.nanoTime(),
            "Smoke art target",
            "DEBUT",
            10_000,
            "WHITE"
        );

        List<String> attackFailures = new ArrayList<>();
        List<String> actionFailures = new ArrayList<>();
        for (int index = 0; index < memberCards.size(); index++) {
            MemberArtSmokeCard memberCard = memberCards.get(index);
            int turnNumber = 10_000 + index;
            prepareMemberArtSmokeBoard(
                matchId,
                hostId,
                guestId,
                turnNumber,
                attackerCardInstanceId,
                targetCardInstanceId,
                memberCard,
                targetCardId
            );

            try {
                AttackArtActionRequest request = new AttackArtActionRequest();
                request.setAttackerCardInstanceId(attackerCardInstanceId);
                request.setTargetCardInstanceId(targetCardInstanceId);
                matchActionService.attackArt(matchId, hostId, request);
            } catch (RuntimeException ex) {
                attackFailures.add(memberCard.cardId() + " " + memberCard.name() + ": " + ex.getMessage());
                continue;
            }

            Integer attackActionCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM match_actions
                WHERE match_id = ?
                  AND user_id = ?
                  AND turn_number = ?
                  AND action_type = 'ATTACK_ART'
                  AND payload ->> 'attackerCardId' = ?
                """,
                Integer.class,
                matchId,
                hostId,
                turnNumber,
                memberCard.cardId()
            );
            if (attackActionCount == null || attackActionCount != 1) {
                actionFailures.add(memberCard.cardId() + " " + memberCard.name() + ": action=" + attackActionCount);
            }
        }

        assertThat(attackFailures).isEmpty();
        assertThat(actionFailures).isEmpty();
    }

    @Test
    void officialMemberHighRiskArtRowsShouldRemainEffectEngineSmokeCovered() {
        List<MemberArtEffectSmokeRow> artRows = loadMemberHighRiskArtEffectSmokeRows();
        assertThat(artRows).hasSize(61);

        StartedMatchContext context = createStartedMatch("member-art-effect-smoke-host", "member-art-effect-smoke-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        Long attackerCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        Long opponentTargetCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        assertThat(attackerCardInstanceId).isNotNull();
        assertThat(opponentTargetCardInstanceId).isNotNull();

        Long attackerHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, attackerCardInstanceId);
        assertThat(attackerHolomemId).isNotNull();
        attachFullColorSmokeCheerSet(matchId, hostId, attackerHolomemId);

        String targetCardId = createMemberCardDefinition(
            "TSMOKE_ART_EFFECT_TARGET_" + System.nanoTime(),
            "Smoke art effect target",
            "DEBUT",
            10_000,
            "WHITE"
        );

        List<String> parserFailures = new ArrayList<>();
        List<String> unsupportedFailures = new ArrayList<>();
        List<String> emptyRequestFailures = new ArrayList<>();
        for (int index = 0; index < artRows.size(); index++) {
            MemberArtEffectSmokeRow artRow = artRows.get(index);
            int turnNumber = 90_000 + index;
            prepareMemberArtSmokeBoard(
                matchId,
                hostId,
                guestId,
                turnNumber,
                attackerCardInstanceId,
                opponentTargetCardInstanceId,
                new MemberArtSmokeCard(artRow.cardId(), artRow.cardName(), artRow.levelType()),
                targetCardId
            );
            ensureMinimumZoneCards(matchId, hostId, "DECK", 30, turnNumber);
            ensureMinimumZoneCards(matchId, guestId, "DECK", 30, turnNumber);
            ensureMinimumZoneCards(matchId, hostId, "HAND", 6, turnNumber);
            ensureMinimumZoneCards(matchId, guestId, "HAND", 6, turnNumber);
            ensureMinimumCheerCards(matchId, hostId, "CHEER_DECK", 12, turnNumber);
            ensureMinimumCheerCards(matchId, guestId, "CHEER_DECK", 12, turnNumber);
            ensureMinimumCheerCards(matchId, hostId, "ARCHIVE", 12, turnNumber);
            ensureMinimumCheerCards(matchId, guestId, "ARCHIVE", 12, turnNumber);

            Long targetCardInstanceId = selectMemberArtEffectSmokeTarget(
                artRow.targetType(),
                attackerCardInstanceId,
                opponentTargetCardInstanceId
            );
            Map<String, Object> summary;
            try {
                summary = matchEffectService.applySupportEffect(
                    matchId,
                    hostId,
                    artRow.effectType(),
                    artRow.effectJsonText(),
                    artRow.targetType(),
                    List.of(),
                    targetCardInstanceId,
                    true
                );
            } catch (RuntimeException ex) {
                parserFailures.add(
                    artRow.cardId() + " " + artRow.cardName() + " " + artRow.artName()
                        + ": " + ex.getMessage()
                );
                continue;
            }

            List<?> requestedEffects = summary.get("requestedEffects") instanceof List<?> values
                ? values
                : List.of();
            if (requestedEffects.isEmpty()) {
                emptyRequestFailures.add(
                    artRow.cardId() + " " + artRow.cardName() + " " + artRow.artName()
                        + ": " + summary
                );
            }
            List<?> unsupportedEffects = summary.get("unsupportedEffects") instanceof List<?> values
                ? values
                : List.of();
            if (!unsupportedEffects.isEmpty()) {
                unsupportedFailures.add(
                    artRow.cardId() + " " + artRow.cardName() + " " + artRow.artName()
                        + ": " + unsupportedEffects
                );
            }
        }

        assertThat(parserFailures).isEmpty();
        assertThat(emptyRequestFailures).isEmpty();
        assertThat(unsupportedFailures).isEmpty();
    }

    @Test
    void officialMemberBloomAndCollabTriggersShouldRemainEffectEngineSmokeCovered() {
        List<TriggerSmokeCard> bloomCards = loadBloomTriggerSmokeCards();
        List<TriggerSmokeCard> collabCards = loadCollabTriggerSmokeCards();
        assertThat(bloomCards).hasSizeGreaterThan(100);
        assertThat(collabCards).hasSizeGreaterThan(100);

        StartedMatchContext context = createStartedMatch("trigger-smoke-host", "trigger-smoke-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        Long bloomSourceCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        Long collabSourceCardInstanceId = loadFirstCollabCardInstanceId(matchId, hostId);
        Long opponentTargetCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        assertThat(bloomSourceCardInstanceId).isNotNull();
        assertThat(collabSourceCardInstanceId).isNotNull();
        assertThat(opponentTargetCardInstanceId).isNotNull();

        List<String> parserFailures = new ArrayList<>();
        List<String> unsupportedFailures = new ArrayList<>();
        for (int index = 0; index < bloomCards.size(); index++) {
            TriggerSmokeCard bloomCard = bloomCards.get(index);
            prepareTriggeredEffectSmokeBoard(
                matchId,
                hostId,
                guestId,
                20_000 + index,
                bloomSourceCardInstanceId,
                collabSourceCardInstanceId,
                opponentTargetCardInstanceId
            );
            updateStageHolomemCard(
                matchId,
                hostId,
                bloomSourceCardInstanceId,
                bloomCard.cardId(),
                bloomCard.levelType(),
                "CENTER"
            );

            MatchEffectService.TriggeredEffectPreview preview;
            Map<String, Object> summary;
            try {
                preview = matchEffectService.previewBloomTriggeredEffect(
                    matchId,
                    hostId,
                    bloomCard.cardId(),
                    bloomSourceCardInstanceId,
                    sourceLevelBeforeBloom(bloomCard.levelType())
                );
                summary = matchEffectService.applyBloomTriggeredEffects(
                    matchId,
                    hostId,
                    bloomCard.cardId(),
                    bloomSourceCardInstanceId,
                    sourceLevelBeforeBloom(bloomCard.levelType())
                );
            } catch (RuntimeException ex) {
                parserFailures.add(bloomCard.cardId() + " " + bloomCard.name() + " BLOOM: " + ex.getMessage());
                continue;
            }

            List<?> unsupportedEffects = summary.get("unsupportedEffects") instanceof List<?> values
                ? values
                : List.of();
            if (!unsupportedEffects.isEmpty()) {
                unsupportedFailures.add(
                    bloomCard.cardId() + " " + bloomCard.name() + " BLOOM: " + unsupportedEffects
                );
            }
            if (preview.hasEffect()) {
                assertThat(preview.effectTypes())
                    .as(bloomCard.cardId() + " " + bloomCard.name() + " BLOOM preview effect types")
                    .isNotEmpty();
            }
        }

        for (int index = 0; index < collabCards.size(); index++) {
            TriggerSmokeCard collabCard = collabCards.get(index);
            prepareTriggeredEffectSmokeBoard(
                matchId,
                hostId,
                guestId,
                30_000 + index,
                bloomSourceCardInstanceId,
                collabSourceCardInstanceId,
                opponentTargetCardInstanceId
            );
            updateStageHolomemCard(
                matchId,
                hostId,
                collabSourceCardInstanceId,
                collabCard.cardId(),
                collabCard.levelType(),
                "COLLAB"
            );

            MatchEffectService.TriggeredEffectPreview preview;
            Map<String, Object> summary;
            try {
                preview = matchEffectService.previewCollabTriggeredEffect(collabCard.cardId());
                summary = matchEffectService.applyCollabTriggeredEffects(
                    matchId,
                    hostId,
                    collabCard.cardId(),
                    collabSourceCardInstanceId
                );
            } catch (RuntimeException ex) {
                parserFailures.add(collabCard.cardId() + " " + collabCard.name() + " COLLAB: " + ex.getMessage());
                continue;
            }

            List<?> unsupportedEffects = summary.get("unsupportedEffects") instanceof List<?> values
                ? values
                : List.of();
            if (!unsupportedEffects.isEmpty()) {
                unsupportedFailures.add(
                    collabCard.cardId() + " " + collabCard.name() + " COLLAB: " + unsupportedEffects
                );
            }
            if (preview.hasEffect()) {
                assertThat(preview.effectTypes())
                    .as(collabCard.cardId() + " " + collabCard.name() + " COLLAB preview effect types")
                    .isNotEmpty();
            }
        }

        assertThat(parserFailures).isEmpty();
        assertThat(unsupportedFailures).isEmpty();
    }

    @Test
    void officialMemberPassiveGiftsShouldRemainEffectEngineSmokeCovered() {
        List<PassiveGiftSmokeCard> giftCards = loadPassiveGiftSmokeCards();
        assertThat(giftCards).hasSizeGreaterThan(50);

        StartedMatchContext context = createStartedMatch("gift-smoke-host", "gift-smoke-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        Long centerCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        Long collabCardInstanceId = loadFirstCollabCardInstanceId(matchId, hostId);
        Long opponentTargetCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        assertThat(centerCardInstanceId).isNotNull();
        assertThat(collabCardInstanceId).isNotNull();
        assertThat(opponentTargetCardInstanceId).isNotNull();

        List<String> parserFailures = new ArrayList<>();
        List<String> unsupportedFailures = new ArrayList<>();
        for (int index = 0; index < giftCards.size(); index++) {
            PassiveGiftSmokeCard giftCard = giftCards.get(index);
            prepareTriggeredEffectSmokeBoard(
                matchId,
                hostId,
                guestId,
                40_000 + index,
                centerCardInstanceId,
                collabCardInstanceId,
                opponentTargetCardInstanceId
            );

            String holderZone = selectPassiveGiftSmokeHolderZone(giftCard.passiveText());
            Long holderCardInstanceId = "COLLAB".equals(holderZone) ? collabCardInstanceId : centerCardInstanceId;
            updateStageHolomemCard(
                matchId,
                hostId,
                holderCardInstanceId,
                giftCard.cardId(),
                giftCard.levelType(),
                holderZone
            );
            Long holderHolomemId = loadHolomemIdByCardInstanceId(matchId, hostId, holderCardInstanceId);
            assertThat(holderHolomemId).isNotNull();
            attachSmokeCheerIfEmpty(matchId, hostId, holderCardInstanceId);

            Map<String, Object> storedTrigger = buildStoredGiftTriggerSmokePayload(
                matchId,
                hostId,
                holderHolomemId,
                giftCard
            );
            Map<String, Object> summary;
            try {
                summary = matchEffectService.applyStoredGiftTriggeredEffect(
                    matchId,
                    hostId,
                    inferPassiveGiftSmokeTriggerType(giftCard.passiveText()),
                    holderCardInstanceId,
                    opponentTargetCardInstanceId,
                    storedTrigger
                );
            } catch (RuntimeException ex) {
                parserFailures.add(giftCard.cardId() + " " + giftCard.name() + " GIFT: " + ex.getMessage());
                continue;
            }

            if (summary == null || summary.isEmpty()) {
                parserFailures.add(giftCard.cardId() + " " + giftCard.name() + " GIFT: empty summary");
                continue;
            }
            List<?> unsupportedEffects = summary.get("unsupportedEffects") instanceof List<?> values
                ? values
                : List.of();
            if (!unsupportedEffects.isEmpty()) {
                unsupportedFailures.add(
                    giftCard.cardId() + " " + giftCard.name() + " GIFT: " + unsupportedEffects
                );
            }
            assertThat(summary.get("requestedEffects"))
                .as(giftCard.cardId() + " " + giftCard.name() + " GIFT requested effects")
                .isInstanceOf(List.class);
        }

        assertThat(parserFailures).isEmpty();
        assertThat(unsupportedFailures).isEmpty();
    }

    @Test
    void officialMemberDownEventLifeEffectsShouldRemainEffectEngineSmokeCovered() {
        List<DownEventSmokeCard> downEventCards = loadDownEventLifeSmokeCards();
        assertThat(downEventCards).hasSizeGreaterThan(30);

        StartedMatchContext context = createStartedMatch("down-event-smoke-host", "down-event-smoke-guest");
        Long matchId = context.matchId();
        Long hostId = context.hostId();
        Long guestId = context.guestId();
        ensureSmokeStageCoverage(matchId, hostId, guestId);

        Long downedCardInstanceId = loadFirstCenterCardInstanceId(matchId, hostId);
        Long collabCardInstanceId = loadFirstCollabCardInstanceId(matchId, hostId);
        Long opponentTargetCardInstanceId = loadFirstCenterCardInstanceId(matchId, guestId);
        assertThat(downedCardInstanceId).isNotNull();
        assertThat(collabCardInstanceId).isNotNull();
        assertThat(opponentTargetCardInstanceId).isNotNull();

        List<String> previewFailures = new ArrayList<>();
        List<String> applyFailures = new ArrayList<>();
        for (int index = 0; index < downEventCards.size(); index++) {
            DownEventSmokeCard downEventCard = downEventCards.get(index);
            int turnNumber = 50_000 + index;
            prepareTriggeredEffectSmokeBoard(
                matchId,
                hostId,
                guestId,
                turnNumber,
                downedCardInstanceId,
                collabCardInstanceId,
                opponentTargetCardInstanceId
            );
            ensureSmokeLifeCards(matchId, hostId, turnNumber);
            updateStageHolomemCard(
                matchId,
                hostId,
                downedCardInstanceId,
                downEventCard.cardId(),
                downEventCard.levelType(),
                "CENTER"
            );

            Map<String, Object> preview;
            Map<String, Object> applied;
            try {
                preview = matchEffectService.previewDownEventEffect(
                    matchId,
                    guestId,
                    hostId,
                    downEventCard.cardId(),
                    turnNumber
                );
                applied = matchEffectService.applyDownEventEffect(
                    matchId,
                    guestId,
                    hostId,
                    downEventCard.cardId(),
                    turnNumber,
                    "CENTER"
                );
            } catch (RuntimeException ex) {
                applyFailures.add(downEventCard.cardId() + " " + downEventCard.name() + ": " + ex.getMessage());
                continue;
            }

            if (!Boolean.TRUE.equals(preview.get("triggered"))) {
                previewFailures.add(downEventCard.cardId() + " " + downEventCard.name() + ": " + preview);
            }
            if (!Boolean.TRUE.equals(applied.get("triggered"))) {
                applyFailures.add(downEventCard.cardId() + " " + downEventCard.name() + ": " + applied);
                continue;
            }
            assertThat(applied.get("resolvedLifeLoss"))
                .as(downEventCard.cardId() + " " + downEventCard.name() + " resolved life loss")
                .isInstanceOf(Number.class);
        }

        assertThat(previewFailures).isEmpty();
        assertThat(applyFailures).isEmpty();
    }

    private List<SupportSmokeCard> loadNonAttachableSupportSmokeCards() {
        return jdbcTemplate.query(
            """
            SELECT c.card_id,
                   c.name,
                   sc.effect_type,
                   sc.effect_json::text AS effect_json_text,
                   sc.target_type
            FROM cards c
            JOIN support_cards sc ON sc.card_id = c.card_id
            WHERE c.expansion_code LIKE 'H%'
              AND c.card_type = 'SUPPORT'
              AND COALESCE(sc.effect_json::text, '') NOT LIKE '%マスコット%'
              AND COALESCE(sc.effect_json::text, '') NOT LIKE '%ツール%'
              AND COALESCE(sc.effect_json::text, '') NOT LIKE '%ファン%'
            ORDER BY c.card_id
            """,
            (rs, rowNum) -> new SupportSmokeCard(
                rs.getString("card_id"),
                rs.getString("name"),
                rs.getString("effect_type"),
                rs.getString("effect_json_text"),
                rs.getString("target_type")
            )
        );
    }

    private List<OshiSkillSmokeCard> loadOshiSkillSmokeCards() {
        return jdbcTemplate.query(
            """
            SELECT c.card_id AS oshi_card_id,
                   c.name AS oshi_name,
                   os.skill_type,
                   os.skill_name,
                   os.effect_json ->> 'type' AS effect_type,
                   os.effect_json::text AS effect_json_text,
                   os.effect_json ->> 'target' AS target_type
            FROM cards c
            JOIN oshi_skills os ON os.oshi_card_id = c.card_id
            WHERE c.expansion_code LIKE 'H%'
              AND c.card_type = 'OSHI'
            ORDER BY c.card_id,
                     CASE os.skill_type WHEN 'NORMAL' THEN 0 ELSE 1 END,
                     os.id
            """,
            (rs, rowNum) -> new OshiSkillSmokeCard(
                rs.getString("oshi_card_id"),
                rs.getString("oshi_name"),
                rs.getString("skill_type"),
                rs.getString("skill_name"),
                rs.getString("effect_type"),
                rs.getString("effect_json_text"),
                rs.getString("target_type")
            )
        );
    }

    private List<AttachableSupportSmokeCard> loadAttachableSupportSmokeCards() {
        return jdbcTemplate.query(
            """
            SELECT c.card_id,
                   c.name,
                   CASE
                       WHEN sc.effect_json::text LIKE '%サポート・マスコット%' THEN 'MASCOT'
                       WHEN sc.effect_json::text LIKE '%サポート・ツール%' THEN 'TOOL'
                       WHEN sc.effect_json::text LIKE '%サポート・ファン%' THEN 'FAN'
                       ELSE 'OTHER'
                   END AS support_type
            FROM cards c
            JOIN support_cards sc ON sc.card_id = c.card_id
            WHERE c.expansion_code LIKE 'H%'
              AND c.card_type = 'SUPPORT'
              AND (
                  sc.effect_json::text LIKE '%サポート・マスコット%'
                  OR sc.effect_json::text LIKE '%サポート・ツール%'
                  OR sc.effect_json::text LIKE '%サポート・ファン%'
              )
            ORDER BY c.card_id
            """,
            (rs, rowNum) -> new AttachableSupportSmokeCard(
                rs.getString("card_id"),
                rs.getString("name"),
                rs.getString("support_type")
            )
        );
    }

    private List<AttachableSupportStaticBonusSmokeCard> loadAttachableSupportStaticBonusSmokeCards() {
        return jdbcTemplate.query(
                """
                SELECT c.card_id,
                       c.name,
                       sc.effect_json::text AS effect_json_text,
                       CASE
                           WHEN sc.effect_json::text LIKE '%サポート・マスコット%' THEN 'MASCOT'
                           WHEN sc.effect_json::text LIKE '%サポート・ツール%' THEN 'TOOL'
                           WHEN sc.effect_json::text LIKE '%サポート・ファン%' THEN 'FAN'
                           ELSE 'OTHER'
                       END AS support_type
                FROM cards c
                JOIN support_cards sc ON sc.card_id = c.card_id
                WHERE c.expansion_code LIKE 'H%'
                  AND c.card_type = 'SUPPORT'
                  AND (
                      sc.effect_json::text LIKE '%サポート・マスコット%'
                      OR sc.effect_json::text LIKE '%サポート・ツール%'
                      OR sc.effect_json::text LIKE '%サポート・ファン%'
                  )
                ORDER BY c.card_id
                """,
                (rs, rowNum) -> {
                    String effectJsonText = rs.getString("effect_json_text");
                    return new AttachableSupportStaticBonusSmokeCard(
                        rs.getString("card_id"),
                        rs.getString("name"),
                        rs.getString("support_type"),
                        extractExpectedAttachedSupportStatBonus(effectJsonText, ATTACHED_SUPPORT_HP_SMOKE_PATTERN),
                        extractExpectedAttachedSupportStatBonus(effectJsonText, ATTACHED_SUPPORT_ARTS_SMOKE_PATTERN)
                    );
                }
            )
            .stream()
            .filter(card -> card.expectedHpBonus() != 0 || card.expectedArtBonus() != 0)
            .toList();
    }

    private List<AttachableSupportDamageReductionSmokeCard> loadAttachableSupportDamageReductionSmokeCards() {
        return jdbcTemplate.query(
                """
                SELECT c.card_id,
                       c.name,
                       sc.effect_json::text AS effect_json_text,
                       CASE
                           WHEN sc.effect_json::text LIKE '%サポート・マスコット%' THEN 'MASCOT'
                           WHEN sc.effect_json::text LIKE '%サポート・ツール%' THEN 'TOOL'
                           WHEN sc.effect_json::text LIKE '%サポート・ファン%' THEN 'FAN'
                           ELSE 'OTHER'
                       END AS support_type
                FROM cards c
                JOIN support_cards sc ON sc.card_id = c.card_id
                WHERE c.expansion_code LIKE 'H%'
                  AND c.card_type = 'SUPPORT'
                  AND (
                      sc.effect_json::text LIKE '%サポート・マスコット%'
                      OR sc.effect_json::text LIKE '%サポート・ツール%'
                      OR sc.effect_json::text LIKE '%サポート・ファン%'
                  )
                ORDER BY c.card_id
                """,
                (rs, rowNum) -> {
                    String effectJsonText = rs.getString("effect_json_text");
                    return new AttachableSupportDamageReductionSmokeCard(
                        rs.getString("card_id"),
                        rs.getString("name"),
                        rs.getString("support_type"),
                        resolveAttachedSupportDamageReductionSmokeTargetZone(effectJsonText),
                        extractExpectedAttachedSupportDamageReduction(effectJsonText)
                    );
                }
            )
            .stream()
            .filter(card -> card.expectedDamageReduction() > 0)
            .toList();
    }

    private List<AttachableSupportConditionalTriggerSmokeCard> loadAttachableSupportConditionalTriggerSmokeCards() {
        return jdbcTemplate.query(
                """
                SELECT c.card_id,
                       c.name,
                       sc.effect_json::text AS effect_json_text,
                       CASE
                           WHEN sc.effect_json::text LIKE '%サポート・マスコット%' THEN 'MASCOT'
                           WHEN sc.effect_json::text LIKE '%サポート・ツール%' THEN 'TOOL'
                           WHEN sc.effect_json::text LIKE '%サポート・ファン%' THEN 'FAN'
                           ELSE 'OTHER'
                       END AS support_type
                FROM cards c
                JOIN support_cards sc ON sc.card_id = c.card_id
                WHERE c.expansion_code LIKE 'H%'
                  AND c.card_type = 'SUPPORT'
                  AND (
                      sc.effect_json::text LIKE '%サポート・マスコット%'
                      OR sc.effect_json::text LIKE '%サポート・ツール%'
                      OR sc.effect_json::text LIKE '%サポート・ファン%'
                  )
                  AND (
                      sc.effect_json::text LIKE '%このファンが付いているホロメンがダウンした時%'
                      OR sc.effect_json::text LIKE '%このツールが付いているホロメンがダウンした時%'
                      OR sc.effect_json::text LIKE '%このマスコットが付いているホロメンがダウンした時%'
                      OR sc.effect_json::text LIKE '%このファンが付いているホロメンがダメージを受ける時%'
                      OR sc.effect_json::text LIKE '%このツールが付いているホロメンがダメージを受ける時%'
                      OR sc.effect_json::text LIKE '%このマスコットが付いているホロメンがダメージを受ける時%'
                  )
                ORDER BY c.card_id
                """,
                (rs, rowNum) -> new AttachableSupportConditionalTriggerSmokeCard(
                    rs.getString("card_id"),
                    rs.getString("name"),
                    rs.getString("support_type"),
                    resolveAttachedSupportConditionalTriggerSmokeType(rs.getString("effect_json_text"))
                )
            )
            .stream()
            .filter(card -> !"UNKNOWN".equals(card.triggerType()))
            .toList();
    }

    private List<MemberArtSmokeCard> loadMemberArtSmokeCards() {
        return jdbcTemplate.query(
            """
            SELECT c.card_id,
                   c.name,
                   m.level_type
            FROM cards c
            JOIN member_cards m ON m.card_id = c.card_id
            WHERE c.expansion_code LIKE 'H%'
              AND c.card_type = 'MEMBER'
              AND EXISTS (
                  SELECT 1
                  FROM member_arts ma
                  WHERE ma.member_card_id = c.card_id
              )
            ORDER BY c.card_id
            """,
            (rs, rowNum) -> new MemberArtSmokeCard(
                rs.getString("card_id"),
                rs.getString("name"),
                rs.getString("level_type")
            )
        );
    }

    private List<MemberArtEffectSmokeRow> loadMemberHighRiskArtEffectSmokeRows() {
        return jdbcTemplate.query(
            """
            SELECT c.card_id,
                   c.name AS card_name,
                   m.level_type,
                   ma.name AS art_name,
                   ma.order_index,
                   ma.effect_json ->> 'type' AS effect_type,
                   ma.effect_json ->> 'target' AS target_type,
                   ma.effect_json::text AS effect_json_text
            FROM cards c
            JOIN member_cards m ON m.card_id = c.card_id
            JOIN member_arts ma ON ma.member_card_id = c.card_id
            WHERE c.expansion_code LIKE 'H%'
              AND c.card_type = 'MEMBER'
              AND (
                  ma.effect_json::text LIKE '%ブルームエフェクト%'
                  OR ma.effect_json::text LIKE '%Bloom%'
                  OR ma.effect_json::text LIKE '%ブルーム%'
                  OR ma.effect_json::text LIKE '%コラボエフェクト%'
                  OR ma.effect_json::text LIKE '%コラボ%'
                  OR ma.effect_json::text LIKE '%ギフト%'
                  OR ma.effect_json::text LIKE '%このホロメンがダウンした時%'
                  OR ma.effect_json::text LIKE '%自分のホロメンがダウンした時%'
                  OR ma.effect_json::text LIKE '%相手のホロメンをダウンさせた時%'
                  OR ma.effect_json::text LIKE '%相手のホロメンがダウンした時%'
                  OR ma.effect_json::text LIKE '%ダメージを受ける時%'
                  OR ma.effect_json::text LIKE '%ダメージを受けた時%'
                  OR ma.effect_json::text LIKE '%ダメージを与えた時%'
              )
            ORDER BY c.card_id, ma.order_index
            """,
            (rs, rowNum) -> new MemberArtEffectSmokeRow(
                rs.getString("card_id"),
                rs.getString("card_name"),
                rs.getString("level_type"),
                rs.getString("art_name"),
                rs.getInt("order_index"),
                rs.getString("effect_type"),
                rs.getString("target_type"),
                rs.getString("effect_json_text")
            )
        );
    }

    private List<TriggerSmokeCard> loadBloomTriggerSmokeCards() {
        return jdbcTemplate.query(
            """
            SELECT c.card_id,
                   c.name,
                   m.level_type
            FROM cards c
            JOIN member_cards m ON m.card_id = c.card_id
            WHERE c.expansion_code LIKE 'H%'
              AND c.card_type = 'MEMBER'
              AND COALESCE(m.passive_effect_json::text, '') LIKE '%ブルームエフェクト%'
            ORDER BY c.card_id
            """,
            (rs, rowNum) -> new TriggerSmokeCard(
                rs.getString("card_id"),
                rs.getString("name"),
                rs.getString("level_type")
            )
        );
    }

    private List<TriggerSmokeCard> loadCollabTriggerSmokeCards() {
        return jdbcTemplate.query(
            """
            SELECT c.card_id,
                   c.name,
                   m.level_type
            FROM cards c
            JOIN member_cards m ON m.card_id = c.card_id
            WHERE c.expansion_code LIKE 'H%'
              AND c.card_type = 'MEMBER'
              AND COALESCE(m.passive_effect_json::text, '') LIKE '%コラボエフェクト%'
            ORDER BY c.card_id
            """,
            (rs, rowNum) -> new TriggerSmokeCard(
                rs.getString("card_id"),
                rs.getString("name"),
                rs.getString("level_type")
            )
        );
    }

    private List<PassiveGiftSmokeCard> loadPassiveGiftSmokeCards() {
        return jdbcTemplate.query(
            """
            SELECT c.card_id,
                   c.name,
                   m.level_type,
                   m.passive_effect_json::text AS passive_text
            FROM cards c
            JOIN member_cards m ON m.card_id = c.card_id
            WHERE c.expansion_code LIKE 'H%'
              AND c.card_type = 'MEMBER'
              AND COALESCE(m.passive_effect_json::text, '') LIKE '%ギフト%'
            ORDER BY c.card_id
            """,
            (rs, rowNum) -> new PassiveGiftSmokeCard(
                rs.getString("card_id"),
                rs.getString("name"),
                rs.getString("level_type"),
                rs.getString("passive_text")
            )
        );
    }

    private List<DownEventSmokeCard> loadDownEventLifeSmokeCards() {
        return jdbcTemplate.query(
            """
            SELECT c.card_id,
                   c.name,
                   m.level_type
            FROM cards c
            JOIN member_cards m ON m.card_id = c.card_id
            WHERE c.expansion_code LIKE 'H%'
              AND c.card_type = 'MEMBER'
              AND COALESCE(m.passive_effect_json::text, '') LIKE '%エクストラ%'
              AND COALESCE(m.passive_effect_json::text, '') LIKE '%このホロメンがダウンした時%'
              AND COALESCE(m.passive_effect_json::text, '') LIKE '%ライフ%'
            ORDER BY c.card_id
            """,
            (rs, rowNum) -> new DownEventSmokeCard(
                rs.getString("card_id"),
                rs.getString("name"),
                rs.getString("level_type")
            )
        );
    }

    private StartedMatchContext createStartedMatch(String hostPrefix, String guestPrefix) {
        StartedMatchContext context = createReadyMatch(hostPrefix, guestPrefix);
        lobbyMatchService.startMatch(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.hostId());
        ensureOpeningHandContainsDebut(context.matchId(), context.guestId());

        MulliganActionRequest hostMulligan = new MulliganActionRequest();
        hostMulligan.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.hostId(), hostMulligan);

        MulliganActionRequest guestMulligan = new MulliganActionRequest();
        guestMulligan.setUseMulligan(false);
        matchActionService.mulligan(context.matchId(), context.guestId(), guestMulligan);

        playOpeningCenter(context.matchId(), context.hostId());
        matchActionService.advancePhase(context.matchId(), context.hostId());
        playOpeningCenter(context.matchId(), context.guestId());
        matchActionService.advancePhase(context.matchId(), context.guestId());
        resolvePendingInteractionIfExists(context.matchId(), context.hostId(), "LIVE_START");
        executeRequiredTurnActions(
            context.matchId(),
            context.hostId(),
            loadFirstCenterCardInstanceId(context.matchId(), context.hostId())
        );

        return context;
    }

    private StartedMatchContext createReadyMatch(String hostPrefix, String guestPrefix) {
        User host = createUser(hostPrefix);
        User guest = createUser(guestPrefix);
        deckService.setupQuickDeck(host.getId());
        deckService.setupQuickDeck(guest.getId());

        LobbyMatch created = lobbyMatchService.createMatch(host.getId());
        lobbyMatchService.joinMatch(created.getRoomCode(), guest.getId());
        lobbyMatchService.setReady(created.getId(), host.getId(), true);
        lobbyMatchService.setReady(created.getId(), guest.getId(), true);
        return new StartedMatchContext(created.getId(), host.getId(), guest.getId());
    }

    private User createUser(String prefix) {
        User user = new User();
        String unique = prefix + "_" + System.nanoTime();
        user.setLineUserId(unique);
        user.setDisplayName(unique);
        user.setAvatarUrl("https://example.com/" + unique + ".png");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    private Long playOpeningCenter(Long matchId, Long userId) {
        Long memberCardInstanceId = findDebutMemberCardFromHand(matchId, userId);
        assertThat(memberCardInstanceId).isNotNull();

        PlayToStageActionRequest play = new PlayToStageActionRequest();
        play.setCardInstanceId(memberCardInstanceId);
        play.setTargetZone("CENTER");
        matchActionService.playToStage(matchId, userId, play);
        return memberCardInstanceId;
    }

    private Long findDebutMemberCardFromHand(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT mc.id
            FROM match_cards mc
            JOIN member_cards m ON m.card_id = mc.card_id
            WHERE mc.match_id = ?
              AND mc.owner_user_id = ?
              AND mc.zone = 'HAND'
              AND m.level_type = 'DEBUT'
            ORDER BY mc.order_index, mc.id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
    }

    private void ensureOpeningHandContainsDebut(Long matchId, Long userId) {
        if (findDebutMemberCardFromHand(matchId, userId) != null) {
            return;
        }
        String debutCardId = findMemberCardIdByLevel("DEBUT");
        Long targetCardInstanceId = jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            ORDER BY order_index NULLS LAST, id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId
        );
        assertThat(targetCardInstanceId).isNotNull();
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
              AND zone = 'HAND'
            """,
            debutCardId,
            targetCardInstanceId,
            matchId,
            userId
        );
    }

    private void executeRequiredTurnActions(Long matchId, Long userId, Long sendCheerTargetCardInstanceId) {
        resolvePendingInteractionIfExists(matchId, userId, "TURN_START");
        try {
            matchActionService.drawTurn(matchId, userId);
        } catch (IllegalStateException | GameRuleException ex) {
            if (ex instanceof GameRuleException gameRuleException
                && gameRuleException.getCode() == GameErrorCode.TURN_DRAW_ALREADY_USED) {
                return;
            }
            String message = ex.getMessage();
            if (message == null || (!message.contains("phase=END") && !message.contains("已經抽過卡"))) {
                throw ex;
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
        if (sendCheerDecisionId == null || sendCheerTargetCardInstanceId == null) {
            return;
        }
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setDecisionId(sendCheerDecisionId);
        request.setSelectedCardInstanceIds(List.of(sendCheerTargetCardInstanceId));
        matchActionService.resolveDecision(matchId, userId, request);
    }

    private void resolvePendingInteractionIfExists(Long matchId, Long userId, String decisionType) {
        Long decisionId = findPendingDecision(matchId, userId, decisionType);
        if (decisionId == null) {
            return;
        }
        ResolveDecisionRequest request = new ResolveDecisionRequest();
        request.setDecisionId(decisionId);
        matchActionService.resolveDecision(matchId, userId, request);
    }

    private Long findPendingDecision(Long matchId, Long userId, String decisionType) {
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_pending_decisions
            WHERE match_id = ?
              AND user_id = ?
              AND status = 'PENDING'
              AND decision_type = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            userId,
            decisionType
        );
    }

    private void ensureSmokeStageCoverage(Long matchId, Long hostId, Long guestId) {
        ensureStageZone(matchId, hostId, "BACK");
        ensureStageZone(matchId, hostId, "COLLAB");
        ensureStageZone(matchId, guestId, "BACK");
        ensureStageZone(matchId, guestId, "COLLAB");
    }

    private void ensureStageZone(Long matchId, Long userId, String zone) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            zone
        );
        if (count != null && count > 0) {
            return;
        }
        createStageHolomemWithSingleCard(matchId, userId, findMemberCardIdByLevel("DEBUT"), zone);
    }

    private void prepareSupportSmokeBoard(Long matchId, Long hostId, Long guestId, int index) {
        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = ?,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                status = 'active',
                winner_user_id = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            2 + index,
            hostId,
            matchId
        );
        jdbcTemplate.update("DELETE FROM match_pending_decisions WHERE match_id = ?", matchId);
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = 0,
                is_rested = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
            """,
            matchId
        );
        ensureMinimumZoneCards(matchId, hostId, "DECK", 20, index);
        ensureMinimumZoneCards(matchId, guestId, "DECK", 20, index);
        ensureMinimumCheerCards(matchId, hostId, "CHEER_DECK", 8, index);
        ensureMinimumCheerCards(matchId, guestId, "CHEER_DECK", 8, index);
        ensureMinimumCheerCards(matchId, hostId, "ARCHIVE", 8, index);
        ensureMinimumCheerCards(matchId, guestId, "ARCHIVE", 8, index);
    }

    private void prepareMemberArtSmokeBoard(
        Long matchId,
        Long hostId,
        Long guestId,
        int turnNumber,
        Long attackerCardInstanceId,
        Long targetCardInstanceId,
        MemberArtSmokeCard memberCard,
        String targetCardId
    ) {
        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = ?,
                current_turn_player_id = ?,
                current_phase = 'PERFORMANCE',
                status = 'active',
                winner_user_id = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            turnNumber,
            hostId,
            matchId
        );
        jdbcTemplate.update("DELETE FROM match_pending_decisions WHERE match_id = ?", matchId);
        jdbcTemplate.update("DELETE FROM match_turn_effects WHERE match_id = ?", matchId);
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = 0,
                is_rested = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
            """,
            matchId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            memberCard.cardId(),
            attackerCardInstanceId,
            matchId,
            hostId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            memberCard.cardId(),
            memberCard.levelType(),
            matchId,
            hostId,
            attackerCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN (
                SELECT match_card_id
                FROM match_holomems
                WHERE match_id = ?
                  AND owner_user_id = ?
            )
            """,
            targetCardId,
            matchId,
            guestId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = 'DEBUT',
                damage_taken = 0,
                is_rested = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
            """,
            targetCardId,
            matchId,
            guestId
        );
        ensureTurnPrerequisiteAction(matchId, hostId, turnNumber, "DRAW_TURN", 1);
        ensureTurnPrerequisiteAction(matchId, hostId, turnNumber, "TURN_CHEER", 2);
        entityManager.flush();
        entityManager.clear();
    }

    private void prepareTriggeredEffectSmokeBoard(
        Long matchId,
        Long hostId,
        Long guestId,
        int turnNumber,
        Long bloomSourceCardInstanceId,
        Long collabSourceCardInstanceId,
        Long opponentTargetCardInstanceId
    ) {
        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = ?,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                status = 'active',
                winner_user_id = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            turnNumber,
            hostId,
            matchId
        );
        jdbcTemplate.update("DELETE FROM match_pending_decisions WHERE match_id = ?", matchId);
        jdbcTemplate.update("DELETE FROM match_turn_effects WHERE match_id = ?", matchId);
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = 0,
                is_rested = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
            """,
            matchId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'STAGE',
                order_index = NULL,
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN (?, ?, ?)
            """,
            bloomSourceCardInstanceId,
            collabSourceCardInstanceId,
            opponentTargetCardInstanceId
        );
        ensureMinimumZoneCards(matchId, hostId, "DECK", 30, turnNumber);
        ensureMinimumZoneCards(matchId, guestId, "DECK", 30, turnNumber);
        ensureMinimumZoneCards(matchId, hostId, "HAND", 6, turnNumber);
        ensureMinimumZoneCards(matchId, guestId, "HAND", 6, turnNumber);
        ensureMinimumCheerCards(matchId, hostId, "CHEER_DECK", 12, turnNumber);
        ensureMinimumCheerCards(matchId, guestId, "CHEER_DECK", 12, turnNumber);
        ensureMinimumCheerCards(matchId, hostId, "ARCHIVE", 12, turnNumber);
        ensureMinimumCheerCards(matchId, guestId, "ARCHIVE", 12, turnNumber);
        attachSmokeCheerIfEmpty(matchId, hostId, bloomSourceCardInstanceId);
        attachSmokeCheerIfEmpty(matchId, hostId, collabSourceCardInstanceId);
        entityManager.flush();
        entityManager.clear();
    }

    private void updateStageHolomemCard(
        Long matchId,
        Long ownerUserId,
        Long cardInstanceId,
        String cardId,
        String levelType,
        String zone
    ) {
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET card_id = ?,
                zone = 'STAGE',
                is_face_down = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            cardId,
            cardInstanceId,
            matchId,
            ownerUserId
        );
        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET card_id = ?,
                current_level = ?,
                zone = ?,
                damage_taken = 0,
                is_rested = FALSE,
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            """,
            cardId,
            levelType,
            zone,
            matchId,
            ownerUserId,
            cardInstanceId
        );
    }

    private void attachSmokeCheerIfEmpty(Long matchId, Long ownerUserId, Long holomemCardInstanceId) {
        Long holomemId = loadHolomemIdByCardInstanceId(matchId, ownerUserId, holomemCardInstanceId);
        if (holomemId == null) {
            return;
        }
        Integer current = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_holomem_cheers WHERE match_holomem_id = ?",
            Integer.class,
            holomemId
        );
        if (current != null && current > 0) {
            return;
        }
        attachSmokeCheer(matchId, ownerUserId, holomemId, "WHITE", 0);
    }

    private String sourceLevelBeforeBloom(String levelType) {
        return switch (levelType == null ? "" : levelType) {
            case "SECOND" -> "FIRST";
            case "FIRST", "BUZZ" -> "DEBUT";
            default -> "DEBUT";
        };
    }

    private Map<String, Object> buildStoredGiftTriggerSmokePayload(
        Long matchId,
        Long hostId,
        Long holderHolomemId,
        PassiveGiftSmokeCard giftCard
    ) {
        Map<String, Object> snapshot = matchEffectService.loadGiftHolderSnapshot(matchId, hostId, holderHolomemId);
        assertThat(snapshot).isNotNull();

        Map<String, Object> storedTrigger = new LinkedHashMap<>();
        storedTrigger.put("giftHolderHolomemId", snapshot.get("holomem_id"));
        storedTrigger.put("giftHolderCardInstanceId", snapshot.get("match_card_id"));
        storedTrigger.put("giftHolderCardId", snapshot.get("card_id"));
        storedTrigger.put("giftHolderZone", snapshot.get("zone"));
        storedTrigger.put("rawText", giftCard.passiveText());
        storedTrigger.put("giftHolderAttachedCheerCardInstanceIds", snapshot.get("attached_cheer_card_instance_ids"));
        storedTrigger.put("giftHolderAttachedCheerCardIds", snapshot.get("attached_cheer_card_ids"));
        storedTrigger.put("giftHolderStackCardInstanceIds", snapshot.get("stack_card_instance_ids"));
        storedTrigger.put("giftHolderStackCardIds", snapshot.get("stack_card_ids"));
        return storedTrigger;
    }

    private String selectPassiveGiftSmokeHolderZone(String passiveText) {
        String text = passiveText == null ? "" : passiveText;
        if (text.contains("コラボポジション限定")) {
            return "COLLAB";
        }
        if (text.contains("バックポジション限定")) {
            return "BACK";
        }
        return "CENTER";
    }

    private String inferPassiveGiftSmokeTriggerType(String passiveText) {
        String text = passiveText == null ? "" : passiveText;
        if (text.contains("ダメージを受ける時")) {
            return "DAMAGE_RECEIVED";
        }
        if (text.contains("このホロメンがダウンした時")) {
            return "SELF_DOWNED";
        }
        if (text.contains("ダウンした時") || text.contains("ダウンさせた時")) {
            return "ALLY_DOWNED";
        }
        if (text.contains("コラボした時")) {
            return "COLLAB";
        }
        if (text.contains("バトンタッチ")) {
            return "BATON_TOUCH_BACK";
        }
        if (text.contains("ステージに出た時")) {
            return "STAGE_ENTER";
        }
        if (text.contains("パフォーマンスステップが開始する時")) {
            return text.contains("相手の") ? "PERFORMANCE_START_OPPONENT" : "PERFORMANCE_START_SELF";
        }
        if (text.contains("パフォーマンスステップが終了する時")) {
            return text.contains("相手の") ? "PERFORMANCE_END_OPPONENT" : "PERFORMANCE_END_SELF";
        }
        if (text.contains("メインステップ")) {
            return "MAIN_STEP_SELF";
        }
        return "ART_USED";
    }

    private void ensureSmokeLifeCards(Long matchId, Long ownerUserId, int turnNumber) {
        ensureMinimumZoneCards(matchId, ownerUserId, "LIFE", 10, turnNumber);
        jdbcTemplate.update(
            """
            UPDATE match_players
            SET current_life = (
                    SELECT COUNT(*)
                    FROM match_cards
                    WHERE match_id = ?
                      AND owner_user_id = ?
                      AND zone = 'LIFE'
                ),
                updated_at = CURRENT_TIMESTAMP
            WHERE match_id = ?
              AND user_id = ?
            """,
            matchId,
            ownerUserId,
            matchId,
            ownerUserId
        );
    }

    private void ensureTurnPrerequisiteAction(
        Long matchId,
        Long userId,
        int turnNumber,
        String actionType,
        int actionOrder
    ) {
        Integer existing = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM match_actions
            WHERE match_id = ?
              AND user_id = ?
              AND turn_number = ?
              AND action_type = ?
            """,
            Integer.class,
            matchId,
            userId,
            turnNumber,
            actionType
        );
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update(
            """
            INSERT INTO match_actions (match_id, user_id, turn_number, action_order, action_type, payload, executed_at)
            VALUES (?, ?, ?, ?, ?, '{}'::jsonb, CURRENT_TIMESTAMP)
            """,
            matchId,
            userId,
            turnNumber,
            actionOrder,
            actionType
        );
    }

    private void ensureMinimumZoneCards(Long matchId, Long userId, String zone, int minimum, int index) {
        int current = countZone(matchId, userId, zone);
        if (current >= minimum) {
            return;
        }
        String fillerCardId = createMemberCardDefinition(
            "TSMOKE_MEMBER_" + zone + "_" + index + "_" + System.nanoTime(),
            "Smoke filler " + zone,
            "DEBUT",
            90,
            "WHITE"
        );
        for (int i = current; i < minimum; i++) {
            insertCardIntoZone(matchId, userId, fillerCardId, zone, "DECK".equals(zone));
        }
    }

    private void ensureMinimumCheerCards(Long matchId, Long userId, String zone, int minimum, int index) {
        int current = countZone(matchId, userId, zone);
        for (int i = current; i < minimum; i++) {
            insertCheerCardIntoZone(matchId, userId, "WHITE", zone, index, i);
        }
    }

    private Long createStageHolomemWithSingleCard(Long matchId, Long ownerUserId, String cardId, String zone) {
        Long cardInstanceId = insertCardIntoZone(matchId, ownerUserId, cardId, "STAGE", false);
        Long holomemId = jdbcTemplate.query(
            """
            INSERT INTO match_holomems (
                match_id,
                owner_user_id,
                match_card_id,
                card_id,
                zone,
                is_rested,
                is_face_down,
                damage_taken,
                current_level,
                entered_turn_number
            ) VALUES (?, ?, ?, ?, ?, FALSE, FALSE, 0, 'DEBUT', 0)
            RETURNING id
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            cardInstanceId,
            cardId,
            zone
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_stack_cards (match_holomem_id, match_card_id, stack_order)
            VALUES (?, ?, 1)
            """,
            holomemId,
            cardInstanceId
        );
        return cardInstanceId;
    }

    private String createMemberCardDefinition(
        String cardId,
        String displayName,
        String levelType,
        int hp,
        String mainColor
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (card_id) DO NOTHING
            """,
            cardId,
            displayName
        );
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, ?, ?, ?, NULL, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (card_id) DO NOTHING
            """,
            cardId,
            hp,
            levelType,
            mainColor
        );
        return cardId;
    }

    private Long insertCardIntoZone(Long matchId, Long ownerUserId, String cardId, String zone, boolean faceDown) {
        int nextOrder = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            ownerUserId,
            zone
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_cards (match_id, owner_user_id, card_id, zone, order_index, is_face_down, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            matchId,
            ownerUserId,
            cardId,
            zone,
            nextOrder,
            faceDown
        );
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND card_id = ?
              AND zone = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            cardId,
            zone
        );
    }

    private Long insertCheerCardIntoZone(
        Long matchId,
        Long userId,
        String color,
        String zone,
        int index,
        int sequence
    ) {
        String cheerCardId = "TSMOKE_CHEER_" + zone + "_" + index + "_" + sequence + "_" + System.nanoTime();
        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, card_type, created_at, updated_at)
            VALUES (?, ?, 'CHEER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId,
            "Smoke cheer " + zone
        );
        jdbcTemplate.update(
            """
            INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            cheerCardId,
            color
        );
        return insertCardIntoZone(matchId, userId, cheerCardId, zone, "CHEER_DECK".equals(zone));
    }

    private void cleanupSupportSmokeAttachment(Long supportCardInstanceId) {
        jdbcTemplate.update(
            "DELETE FROM match_holomem_supports WHERE match_card_id = ?",
            supportCardInstanceId
        );
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            supportCardInstanceId
        );
    }

    private void cleanupSupportSmokeAttachments(Long targetHolomemId) {
        jdbcTemplate.update(
            """
            UPDATE match_cards
            SET zone = 'ARCHIVE',
                order_index = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN (
                SELECT match_card_id
                FROM match_holomem_supports
                WHERE match_holomem_id = ?
            )
            """,
            targetHolomemId
        );
        jdbcTemplate.update(
            "DELETE FROM match_holomem_supports WHERE match_holomem_id = ?",
            targetHolomemId
        );
    }

    private Long attachSupportForStaticBonusSmoke(
        Long matchId,
        Long ownerUserId,
        Long targetHolomemId,
        AttachableSupportStaticBonusSmokeCard supportCard
    ) {
        return attachSupportForSmoke(
            matchId,
            ownerUserId,
            targetHolomemId,
            supportCard.cardId(),
            supportCard.supportType()
        );
    }

    private Long attachSupportForSmoke(
        Long matchId,
        Long ownerUserId,
        Long targetHolomemId,
        String supportCardId,
        String supportType
    ) {
        Long supportCardInstanceId = insertCardIntoZone(matchId, ownerUserId, supportCardId, "STAGE", false);
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_supports (
                match_holomem_id,
                match_card_id,
                support_card_id,
                support_type
            ) VALUES (?, ?, ?, ?)
            """,
            targetHolomemId,
            supportCardInstanceId,
            supportCardId,
            supportType
        );
        return supportCardInstanceId;
    }

    private int extractExpectedAttachedSupportStatBonus(String effectJsonText, Pattern pattern) {
        if (effectJsonText == null || effectJsonText.isBlank()) {
            return 0;
        }
        int conditionalIndex = effectJsonText.indexOf('◆');
        String baseSegment = conditionalIndex >= 0 ? effectJsonText.substring(0, conditionalIndex) : effectJsonText;
        Matcher matcher = pattern.matcher(baseSegment);
        int total = 0;
        while (matcher.find()) {
            total += parseSmokeSignedNumber(matcher.group(1));
        }
        return total;
    }

    private int extractExpectedAttachedSupportDamageReduction(String effectJsonText) {
        if (effectJsonText == null || effectJsonText.isBlank()) {
            return 0;
        }
        int conditionalIndex = effectJsonText.indexOf('◆');
        String baseSegment = conditionalIndex >= 0 ? effectJsonText.substring(0, conditionalIndex) : effectJsonText;
        int total = 0;
        for (String clause : baseSegment.split("[。\\n]")) {
            if (!isAttachedSupportDamageReductionSmokeClause(clause)) {
                continue;
            }
            Matcher matcher = ATTACHED_SUPPORT_DAMAGE_REDUCTION_SMOKE_PATTERN.matcher(clause);
            while (matcher.find()) {
                total += Integer.parseInt(matcher.group(1));
            }
        }
        return total;
    }

    private String resolveAttachedSupportDamageReductionSmokeTargetZone(String effectJsonText) {
        if (effectJsonText == null || effectJsonText.isBlank()) {
            return "CENTER";
        }
        int conditionalIndex = effectJsonText.indexOf('◆');
        String baseSegment = conditionalIndex >= 0 ? effectJsonText.substring(0, conditionalIndex) : effectJsonText;
        for (String clause : baseSegment.split("[。\\n]")) {
            if (!isAttachedSupportDamageReductionSmokeClause(clause)) {
                continue;
            }
            if (clause.contains("センターポジション")) {
                return "CENTER";
            }
            if (clause.contains("コラボポジション")) {
                return "COLLAB";
            }
            if (clause.contains("バックポジション")) {
                return "BACK";
            }
        }
        return "CENTER";
    }

    private String resolveAttachedSupportConditionalTriggerSmokeType(String effectJsonText) {
        if (effectJsonText == null || effectJsonText.isBlank()) {
            return "UNKNOWN";
        }
        int conditionalIndex = effectJsonText.indexOf('◆');
        String baseSegment = conditionalIndex >= 0 ? effectJsonText.substring(0, conditionalIndex) : effectJsonText;
        if (baseSegment.contains("がダメージを受ける時")) {
            return "DAMAGE_RECEIVED";
        }
        if (baseSegment.contains("がダウンした時")) {
            return "SELF_DOWNED";
        }
        return "UNKNOWN";
    }

    private boolean isAttachedSupportDamageReductionSmokeClause(String clause) {
        return clause != null
            && !clause.isBlank()
            && (
                clause.contains("このマスコットが付いているホロメン")
                    || clause.contains("このツールが付いているホロメン")
                    || clause.contains("このファンが付いているホロメン")
            )
            && clause.contains("受けるダメージ")
            && !clause.contains("できる")
            && !clause.contains("：")
            && ATTACHED_SUPPORT_DAMAGE_REDUCTION_SMOKE_PATTERN.matcher(clause).find();
    }

    private void setCurrentTurnPlayer(Long matchId, Long currentTurnPlayerId, int turnNumber) {
        jdbcTemplate.update(
            """
            UPDATE matches
            SET turn_number = ?,
                current_turn_player_id = ?,
                current_phase = 'MAIN',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            turnNumber,
            currentTurnPlayerId,
            matchId
        );
    }

    private int parseSmokeSignedNumber(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String normalized = value
            .replace("＋", "+")
            .replace("−", "-")
            .replaceAll("\\s+", "");
        return Integer.parseInt(normalized);
    }

    private Long selectMemberArtEffectSmokeTarget(
        String targetType,
        Long attackerCardInstanceId,
        Long opponentTargetCardInstanceId
    ) {
        String normalized = targetType == null ? "" : targetType.trim().toUpperCase();
        if (normalized.startsWith("SELF")) {
            return attackerCardInstanceId;
        }
        return opponentTargetCardInstanceId;
    }

    private Long loadHolomemIdByCardInstanceId(Long matchId, Long ownerUserId, Long cardInstanceId) {
        return jdbcTemplate.query(
            """
            SELECT id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND match_card_id = ?
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("id") : null,
            matchId,
            ownerUserId,
            cardInstanceId
        );
    }

    private void attachFullColorSmokeCheerSet(Long matchId, Long ownerUserId, Long matchHolomemId) {
        List<String> colors = List.of("WHITE", "RED", "BLUE", "GREEN", "PURPLE", "YELLOW");
        for (String color : colors) {
            for (int i = 0; i < 4; i++) {
                attachSmokeCheer(matchId, ownerUserId, matchHolomemId, color, i);
            }
        }
    }

    private void attachSmokeCheer(
        Long matchId,
        Long ownerUserId,
        Long matchHolomemId,
        String color,
        int sequence
    ) {
        Long cheerCardInstanceId = insertCheerCardIntoZone(
            matchId,
            ownerUserId,
            color,
            "STAGE",
            sequence,
            sequence
        );
        String cheerCardId = jdbcTemplate.queryForObject(
            "SELECT card_id FROM match_cards WHERE id = ?",
            String.class,
            cheerCardInstanceId
        );
        jdbcTemplate.update(
            """
            INSERT INTO match_holomem_cheers (match_holomem_id, match_card_id, cheer_card_id, is_face_down)
            VALUES (?, ?, ?, FALSE)
            """,
            matchHolomemId,
            cheerCardInstanceId,
            cheerCardId
        );
    }

    private Long loadFirstCenterCardInstanceId(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'CENTER'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            userId
        );
    }

    private Long loadFirstCollabCardInstanceId(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = 'COLLAB'
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            userId
        );
    }

    private Long loadFirstStageCardInstanceId(Long matchId, Long userId) {
        return jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone IN ('CENTER', 'COLLAB', 'BACK')
            ORDER BY CASE zone
                       WHEN 'CENTER' THEN 0
                       WHEN 'COLLAB' THEN 1
                       ELSE 2
                     END,
                     id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            userId
        );
    }

    private Long loadFirstStageCardInstanceIdInZone(Long matchId, Long userId, String zone) {
        return jdbcTemplate.query(
            """
            SELECT match_card_id
            FROM match_holomems
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            ORDER BY id
            LIMIT 1
            """,
            rs -> rs.next() ? rs.getLong("match_card_id") : null,
            matchId,
            userId,
            zone
        );
    }

    private String findMemberCardIdByLevel(String levelType) {
        return jdbcTemplate.queryForObject(
            """
            SELECT m.card_id
            FROM member_cards m
            WHERE m.level_type = ?
            ORDER BY m.card_id
            LIMIT 1
            """,
            String.class,
            levelType
        );
    }

    private int countZone(Long matchId, Long userId, String zone) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM match_cards WHERE match_id = ? AND owner_user_id = ? AND zone = ?",
            Integer.class,
            matchId,
            userId,
            zone
        );
        return value == null ? 0 : value;
    }

    private record StartedMatchContext(Long matchId, Long hostId, Long guestId) {}

    private record SupportSmokeCard(
        String cardId,
        String name,
        String effectType,
        String effectJsonText,
        String targetType
    ) {}

    private record OshiSkillSmokeCard(
        String oshiCardId,
        String oshiName,
        String skillType,
        String skillName,
        String effectType,
        String effectJsonText,
        String targetType
    ) {}

    private record AttachableSupportSmokeCard(
        String cardId,
        String name,
        String supportType
    ) {}

    private record AttachableSupportStaticBonusSmokeCard(
        String cardId,
        String name,
        String supportType,
        int expectedHpBonus,
        int expectedArtBonus
    ) {}

    private record AttachableSupportDamageReductionSmokeCard(
        String cardId,
        String name,
        String supportType,
        String targetZone,
        int expectedDamageReduction
    ) {}

    private record AttachableSupportConditionalTriggerSmokeCard(
        String cardId,
        String name,
        String supportType,
        String triggerType
    ) {}

    private record MemberArtSmokeCard(
        String cardId,
        String name,
        String levelType
    ) {}

    private record MemberArtEffectSmokeRow(
        String cardId,
        String cardName,
        String levelType,
        String artName,
        int orderIndex,
        String effectType,
        String targetType,
        String effectJsonText
    ) {}

    private record TriggerSmokeCard(
        String cardId,
        String name,
        String levelType
    ) {}

    private record PassiveGiftSmokeCard(
        String cardId,
        String name,
        String levelType,
        String passiveText
    ) {}

    private record DownEventSmokeCard(
        String cardId,
        String name,
        String levelType
    ) {}
}
