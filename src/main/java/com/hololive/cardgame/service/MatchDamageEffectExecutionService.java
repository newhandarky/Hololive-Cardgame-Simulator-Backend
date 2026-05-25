package com.hololive.cardgame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hololive.cardgame.service.effect.EffectTextParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

final class MatchDamageEffectExecutionService {

    private static final Pattern SPECIAL_DAMAGE_PATTERN = Pattern.compile("特殊ダメージ\\s*(\\d+)");
    private static final Pattern DAMAGE_PATTERN = Pattern.compile("ダメージ\\s*(\\d+)");

    private final JdbcTemplate jdbcTemplate;
    private final EffectTextParser effectTextParser;
    private final TargetHolomemResolver targetHolomemResolver;
    private final HolomemOwnerResolver holomemOwnerResolver;
    private final CurrentTurnResolver currentTurnResolver;
    private final DamageModifierResolver damageModifierResolver;
    private final HpChangeBlocker hpChangeBlocker;
    private final HolomemZoneResolver holomemZoneResolver;
    private final SpecialDamageImmunityChecker specialDamageImmunityChecker;
    private final SpecialDamageGiftActivator specialDamageGiftActivator;
    private final EffectiveHpResolver effectiveHpResolver;
    private final AttachedCardArchiver cheerCardArchiver;
    private final AttachedCardArchiver supportCardArchiver;
    private final AttachedCardArchiver holomemStackArchiver;
    private final LifeLossSuppressor lifeLossSuppressor;
    private final LifeLossResolver lifeLossResolver;
    private final DownEventExecutor downEventExecutor;
    private final LostLifeCardInstanceExtractor lostLifeCardInstanceExtractor;

    MatchDamageEffectExecutionService(
        JdbcTemplate jdbcTemplate,
        EffectTextParser effectTextParser,
        TargetHolomemResolver targetHolomemResolver,
        HolomemOwnerResolver holomemOwnerResolver,
        CurrentTurnResolver currentTurnResolver,
        DamageModifierResolver damageModifierResolver,
        HpChangeBlocker hpChangeBlocker,
        HolomemZoneResolver holomemZoneResolver,
        SpecialDamageImmunityChecker specialDamageImmunityChecker,
        SpecialDamageGiftActivator specialDamageGiftActivator,
        EffectiveHpResolver effectiveHpResolver,
        AttachedCardArchiver cheerCardArchiver,
        AttachedCardArchiver supportCardArchiver,
        AttachedCardArchiver holomemStackArchiver,
        LifeLossSuppressor lifeLossSuppressor,
        LifeLossResolver lifeLossResolver,
        DownEventExecutor downEventExecutor,
        LostLifeCardInstanceExtractor lostLifeCardInstanceExtractor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.effectTextParser = effectTextParser;
        this.targetHolomemResolver = targetHolomemResolver;
        this.holomemOwnerResolver = holomemOwnerResolver;
        this.currentTurnResolver = currentTurnResolver;
        this.damageModifierResolver = damageModifierResolver;
        this.hpChangeBlocker = hpChangeBlocker;
        this.holomemZoneResolver = holomemZoneResolver;
        this.specialDamageImmunityChecker = specialDamageImmunityChecker;
        this.specialDamageGiftActivator = specialDamageGiftActivator;
        this.effectiveHpResolver = effectiveHpResolver;
        this.cheerCardArchiver = cheerCardArchiver;
        this.supportCardArchiver = supportCardArchiver;
        this.holomemStackArchiver = holomemStackArchiver;
        this.lifeLossSuppressor = lifeLossSuppressor;
        this.lifeLossResolver = lifeLossResolver;
        this.downEventExecutor = downEventExecutor;
        this.lostLifeCardInstanceExtractor = lostLifeCardInstanceExtractor;
    }

    Map<String, Object> executeDamageEffect(
        Long matchId,
        Long userId,
        String effectType,
        JsonNode effectNode,
        String targetType,
        Long targetHolomemCardInstanceId
    ) {
        String rawText = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        Long targetHolomemId = targetHolomemResolver.resolve(
            matchId,
            userId,
            targetType,
            targetHolomemCardInstanceId,
            true
        );
        if (targetHolomemId == null) {
            throw new IllegalStateException("DAMAGE 找不到可攻擊的對手 Holomen");
        }
        Long targetOwnerUserId = holomemOwnerResolver.resolve(matchId, targetHolomemId);
        if (targetOwnerUserId == null) {
            throw new IllegalStateException("DAMAGE 結算失敗：找不到目標擁有者");
        }

        int baseDamage = resolveDamageValue(effectNode);
        if (baseDamage <= 0) {
            return noDamageSummary(effectType, targetHolomemId, 0, 0, "無可用傷害數值");
        }
        int currentTurn = currentTurnResolver.resolve(matchId);
        int damageModifier = damageModifierResolver.resolve(matchId, userId, currentTurn);
        int damage = Math.max(baseDamage + damageModifier, 0);
        boolean specialDamage = StringUtils.hasText(rawText) && rawText.contains("特殊ダメージ");
        if (damage <= 0) {
            return noDamageSummary(effectType, targetHolomemId, baseDamage, damageModifier, "修正後傷害小於等於 0");
        }
        if (hpChangeBlocker.isBlocked(matchId, userId, targetOwnerUserId, targetHolomemId, effectType)) {
            return noDamageSummary(
                effectType,
                targetHolomemId,
                baseDamage,
                damageModifier,
                "目標在相手のメインステップ中不受相手能力的 HP 變動影響"
            );
        }
        String targetCurrentZone = holomemZoneResolver.resolve(matchId, targetHolomemId);
        if (
            specialDamage
                && specialDamageImmunityChecker.isActive(matchId, targetOwnerUserId, currentTurn, targetCurrentZone)
        ) {
            Map<String, Object> summary = noDamageSummary(
                effectType,
                targetHolomemId,
                baseDamage,
                damageModifier,
                "特殊ダメージ無効化効果が有効"
            );
            summary.put("specialDamagePrevented", true);
            return summary;
        }
        Map<String, Object> specialDamageGiftSummary = specialDamage
            ? specialDamageGiftActivator.activate(
                matchId,
                userId,
                targetOwnerUserId,
                targetHolomemId,
                targetCurrentZone,
                currentTurn
            )
            : null;
        if (specialDamageGiftSummary != null && MatchEffectValueHelper.toBoolean(specialDamageGiftSummary.get("preventedDamage"))) {
            Map<String, Object> summary = noDamageSummary(
                effectType,
                targetHolomemId,
                baseDamage,
                damageModifier,
                "HSD13-012 特殊ダメージ無効化"
            );
            summary.put("specialDamagePrevented", true);
            summary.put("specialDamageGift", specialDamageGiftSummary);
            return summary;
        }

        jdbcTemplate.update(
            """
            UPDATE match_holomems
            SET damage_taken = COALESCE(damage_taken, 0) + ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            damage,
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );

        Map<String, Object> holomemState = loadHolomemState(matchId, targetOwnerUserId, targetHolomemId);
        String targetCardId = MatchEffectValueHelper.asText(holomemState.get("card_id"));
        EffectiveHp effectiveHp = effectiveHpResolver.resolve(matchId, targetOwnerUserId, targetHolomemId, targetCardId);
        int damageTaken = MatchEffectValueHelper.asInt(holomemState.get("damage_taken"));

        boolean downed = effectiveHp.totalHp() > 0 && damageTaken >= effectiveHp.totalHp();
        boolean lifeReduced = false;
        List<Long> lostLifeCardInstanceIds = new ArrayList<>();
        Map<String, Object> downEventSummary = null;
        List<Long> archivedCheerCardInstanceIds = new ArrayList<>();
        List<Long> archivedSupportCardInstanceIds = new ArrayList<>();
        List<Long> archivedHolomemCardInstanceIds = new ArrayList<>();
        if (downed) {
            Long targetCardInstanceId = MatchEffectValueHelper.asLong(holomemState.get("match_card_id"));
            String targetZone = MatchEffectValueHelper.normalize(holomemState.get("zone"));
            archivedCheerCardInstanceIds = cheerCardArchiver.archive(matchId, targetHolomemId, targetOwnerUserId);
            archivedSupportCardInstanceIds = supportCardArchiver.archive(matchId, targetHolomemId, targetOwnerUserId);
            archivedHolomemCardInstanceIds = holomemStackArchiver.archive(matchId, targetHolomemId, targetOwnerUserId);

            jdbcTemplate.update(
                "DELETE FROM match_holomems WHERE id = ? AND match_id = ?",
                targetHolomemId,
                matchId
            );
            if (archivedHolomemCardInstanceIds.isEmpty() && targetCardInstanceId != null) {
                int archiveOrder = nextZoneOrder(matchId, targetOwnerUserId, "ARCHIVE");
                jdbcTemplate.update(
                    """
                    UPDATE match_cards
                    SET zone = 'ARCHIVE',
                        order_index = ?,
                        is_face_down = FALSE,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                      AND match_id = ?
                      AND owner_user_id = ?
                    """,
                    archiveOrder,
                    targetCardInstanceId,
                    matchId,
                    targetOwnerUserId
                );
            }

            boolean suppressLifeLoss = lifeLossSuppressor.isSuppressed(effectNode);
            if (!suppressLifeLoss && "CENTER".equals(targetZone)) {
                Long lostLifeCardInstanceId = lifeLossResolver.loseLifeOnce(matchId, targetOwnerUserId);
                lifeReduced = lostLifeCardInstanceId != null;
                if (lostLifeCardInstanceId != null) {
                    lostLifeCardInstanceIds.add(lostLifeCardInstanceId);
                }
            }
            boolean deferDownEvent = effectNode != null && effectNode.path("deferDownEvent").asBoolean(false);
            downEventSummary = downEventExecutor.execute(
                matchId,
                userId,
                targetOwnerUserId,
                targetCardId,
                currentTurn,
                !deferDownEvent,
                targetZone
            );
            if (!deferDownEvent && MatchEffectValueHelper.toBoolean(downEventSummary.get("lifeReduced"))) {
                lifeReduced = true;
                lostLifeCardInstanceIds.addAll(lostLifeCardInstanceExtractor.extract(downEventSummary));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("damageRequested", baseDamage);
        summary.put("damageApplied", damage);
        summary.put("baseDamage", baseDamage);
        summary.put("damageModifierApplied", damageModifier);
        summary.put("targetBaseHp", effectiveHp.baseHp());
        summary.put("targetAttachedSupportHpBonus", effectiveHp.attachedSupportHpBonus());
        summary.put("targetHp", effectiveHp.totalHp());
        summary.put("targetDamageTaken", damageTaken);
        summary.put("downed", downed);
        summary.put("archivedCheerCardInstanceIds", archivedCheerCardInstanceIds);
        summary.put("archivedSupportCardInstanceIds", archivedSupportCardInstanceIds);
        summary.put("archivedHolomemCardInstanceIds", archivedHolomemCardInstanceIds);
        summary.put("lifeReduced", lifeReduced);
        summary.put("lostLifeCardInstanceId", lostLifeCardInstanceIds.isEmpty() ? null : lostLifeCardInstanceIds.get(0));
        summary.put("lostLifeCardInstanceIds", lostLifeCardInstanceIds);
        if (downEventSummary != null) {
            summary.put("downEvent", downEventSummary);
        }
        return summary;
    }

    private Map<String, Object> noDamageSummary(
        String effectType,
        Long targetHolomemId,
        int baseDamage,
        int damageModifier,
        String reason
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("effectType", effectType);
        summary.put("damageRequested", baseDamage);
        summary.put("damageApplied", 0);
        summary.put("baseDamage", baseDamage);
        summary.put("damageModifierApplied", damageModifier);
        summary.put("targetHolomemId", targetHolomemId);
        summary.put("downed", false);
        summary.put("lifeReduced", false);
        summary.put("reason", reason);
        return summary;
    }

    private Map<String, Object> loadHolomemState(Long matchId, Long targetOwnerUserId, Long targetHolomemId) {
        Map<String, Object> holomemState = jdbcTemplate.query(
            """
            SELECT id, match_card_id, card_id, zone, damage_taken
            FROM match_holomems
            WHERE id = ?
              AND match_id = ?
              AND owner_user_id = ?
            """,
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("match_card_id", rs.getLong("match_card_id"));
                row.put("card_id", rs.getString("card_id"));
                row.put("zone", rs.getString("zone"));
                row.put("damage_taken", rs.getInt("damage_taken"));
                return row;
            },
            targetHolomemId,
            matchId,
            targetOwnerUserId
        );
        if (holomemState == null) {
            throw new IllegalStateException("DAMAGE 結算失敗：找不到目標 Holomen");
        }
        return holomemState;
    }

    private int nextZoneOrder(Long matchId, Long userId, String zone) {
        Integer next = jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(order_index), 0) + 1
            FROM match_cards
            WHERE match_id = ?
              AND owner_user_id = ?
              AND zone = ?
            """,
            Integer.class,
            matchId,
            userId,
            zone
        );
        return next == null ? 1 : next;
    }

    private int resolveDamageValue(JsonNode effectNode) {
        int fromFields = effectTextParser.extractInt(effectNode, 0, "value", "amount", "damage");
        if (fromFields > 0) {
            return fromFields;
        }
        String merged = effectTextParser.normalizeDigits(effectTextParser.extractText(effectNode, "rawText", "rawEffect", "rawHeader"));
        int special = effectTextParser.extractByPattern(merged, SPECIAL_DAMAGE_PATTERN);
        if (special > 0) {
            return special;
        }
        int normal = effectTextParser.extractByPattern(merged, DAMAGE_PATTERN);
        if (normal > 0) {
            return normal;
        }
        return 0;
    }

    record EffectiveHp(int baseHp, int attachedSupportHpBonus, int totalHp) {
    }

    @FunctionalInterface
    interface TargetHolomemResolver {
        Long resolve(Long matchId, Long userId, String targetType, Long targetHolomemCardInstanceId, boolean allowFallback);
    }

    @FunctionalInterface
    interface HolomemOwnerResolver {
        Long resolve(Long matchId, Long holomemId);
    }

    @FunctionalInterface
    interface CurrentTurnResolver {
        int resolve(Long matchId);
    }

    @FunctionalInterface
    interface DamageModifierResolver {
        int resolve(Long matchId, Long affectedUserId, int currentTurn);
    }

    @FunctionalInterface
    interface HpChangeBlocker {
        boolean isBlocked(Long matchId, Long sourceUserId, Long targetOwnerUserId, Long targetHolomemId, String effectType);
    }

    @FunctionalInterface
    interface HolomemZoneResolver {
        String resolve(Long matchId, Long holomemId);
    }

    @FunctionalInterface
    interface SpecialDamageImmunityChecker {
        boolean isActive(Long matchId, Long affectedUserId, int currentTurn, String targetZone);
    }

    @FunctionalInterface
    interface SpecialDamageGiftActivator {
        Map<String, Object> activate(
            Long matchId,
            Long sourceUserId,
            Long defendingUserId,
            Long targetHolomemId,
            String targetZone,
            int currentTurn
        );
    }

    @FunctionalInterface
    interface EffectiveHpResolver {
        EffectiveHp resolve(Long matchId, Long ownerUserId, Long holomemId, String cardId);
    }

    @FunctionalInterface
    interface AttachedCardArchiver {
        List<Long> archive(Long matchId, Long matchHolomemId, Long ownerUserId);
    }

    @FunctionalInterface
    interface LifeLossSuppressor {
        boolean isSuppressed(JsonNode effectNode);
    }

    @FunctionalInterface
    interface LifeLossResolver {
        Long loseLifeOnce(Long matchId, Long ownerUserId);
    }

    @FunctionalInterface
    interface DownEventExecutor {
        Map<String, Object> execute(
            Long matchId,
            Long actorUserId,
            Long downedOwnerUserId,
            String downedCardId,
            int currentTurn,
            boolean applyLifeLoss,
            String downedStageZone
        );
    }

    @FunctionalInterface
    interface LostLifeCardInstanceExtractor {
        List<Long> extract(Map<String, Object> effectSummary);
    }
}
