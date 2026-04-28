package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

public class AttackDefenderGiftFollowupService {

    private static final String HBP01124_RAW_TEXT =
        "相手のターンで、このファンが付いているホロメンがダウンした時、このファンが付いているホロメンのエール1枚を、自分の他のホロメンに付け替える。";

    private final MatchGiftTriggerService matchGiftTriggerService;
    private final OfficialOshiSelfDownedEffectResolver officialOshiSelfDownedEffectResolver;

    public AttackDefenderGiftFollowupService(
        MatchGiftTriggerService matchGiftTriggerService,
        OfficialOshiSelfDownedEffectResolver officialOshiSelfDownedEffectResolver
    ) {
        this.matchGiftTriggerService = matchGiftTriggerService;
        this.officialOshiSelfDownedEffectResolver = officialOshiSelfDownedEffectResolver;
    }

    public AttackDefenderGiftFollowupResult resolveFollowup(AttackDefenderGiftFollowupContext context) {
        if (context == null) {
            throw new IllegalArgumentException("attack defender gift followup 缺少必要上下文");
        }
        if (!context.hasDownedHolomem()) {
            return new AttackDefenderGiftFollowupResult(
                Map.of(),
                List.of(),
                context.downedTargetCardId(),
                context.downedTargetZone()
            );
        }

        Map<String, Object> officialOshiSelfDownedSummary =
            officialOshiSelfDownedEffectResolver.resolveOfficialOshiSelfDownedEffects(context);
        List<Map<String, Object>> defenderGiftTriggeredEffects = new ArrayList<>();
        defenderGiftTriggeredEffects.addAll(
            matchGiftTriggerService.previewGiftTriggeredEffectsOnSelfDowned(
                context.matchId(),
                context.defenderUserId(),
                context.downedTargetCardInstanceId(),
                context.downedTargetZone(),
                context.turnNumber(),
                context.holderSnapshot()
            )
        );
        defenderGiftTriggeredEffects.addAll(
            matchGiftTriggerService.previewGiftTriggeredEffectsOnAllyDowned(
                context.matchId(),
                context.defenderUserId(),
                context.downedTargetCardInstanceId(),
                context.downedTargetZone(),
                context.turnNumber()
            )
        );
        defenderGiftTriggeredEffects.addAll(previewHbp01124FanTriggeredEffectsOnSelfDowned(context));

        return new AttackDefenderGiftFollowupResult(
            officialOshiSelfDownedSummary,
            defenderGiftTriggeredEffects,
            context.downedTargetCardId(),
            context.downedTargetZone()
        );
    }

    private List<Map<String, Object>> previewHbp01124FanTriggeredEffectsOnSelfDowned(
        AttackDefenderGiftFollowupContext context
    ) {
        Map<String, Object> holderSnapshot = context.holderSnapshot();
        List<Map<String, Object>> fanSupportSnapshots = context.fanSupportSnapshots();
        if (holderSnapshot == null || holderSnapshot.isEmpty() || fanSupportSnapshots == null || fanSupportSnapshots.isEmpty()) {
            return List.of();
        }
        Long holderHolomemId = asLong(holderSnapshot.get("holomem_id"));
        List<Long> attachedCheerCardInstanceIds = toLongList(holderSnapshot.get("attached_cheer_card_instance_ids"));
        List<String> attachedCheerCardIds = toStringList(holderSnapshot.get("attached_cheer_card_ids"));
        List<Long> stackCardInstanceIds = toLongList(holderSnapshot.get("stack_card_instance_ids"));
        List<String> stackCardIds = toStringList(holderSnapshot.get("stack_card_ids"));
        List<Map<String, Object>> previews = new ArrayList<>();
        for (Map<String, Object> supportSnapshot : fanSupportSnapshots) {
            Long supportCardInstanceId = asLong(supportSnapshot.get("supportCardInstanceId"));
            String supportCardId = asString(supportSnapshot.get("supportCardId"));
            String rawText = asString(supportSnapshot.get("rawText"));
            if ("HBP01-124".equals(supportCardId)) {
                rawText = HBP01124_RAW_TEXT;
            }
            if (supportCardInstanceId == null || supportCardInstanceId <= 0 || !StringUtils.hasText(rawText)) {
                continue;
            }
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("triggerType", "SELF_DOWNED");
            preview.put("giftHolderHolomemId", holderHolomemId);
            preview.put("giftHolderCardInstanceId", supportCardInstanceId);
            preview.put("giftHolderCardId", supportCardId);
            preview.put("giftHolderZone", normalizeZone(context.downedTargetZone()));
            preview.put("sourceCardInstanceId", context.downedTargetCardInstanceId());
            preview.put("triggerTargetCardInstanceId", context.downedTargetCardInstanceId());
            preview.put("rawText", rawText);
            preview.put("requestedEffects", List.of("REATTACH"));
            preview.put("executedEffects", List.of());
            preview.put("unsupportedEffects", List.of());
            preview.put("skippedEffects", List.of());
            preview.put("giftHolderAttachedCheerCardInstanceIds", attachedCheerCardInstanceIds);
            preview.put("giftHolderAttachedCheerCardIds", attachedCheerCardIds);
            preview.put("giftHolderStackCardInstanceIds", stackCardInstanceIds);
            preview.put("giftHolderStackCardIds", stackCardIds);
            previews.add(preview);
        }
        return previews;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeZone(Object value) {
        String text = asString(value).trim();
        return text.isEmpty() ? "" : text.toUpperCase();
    }

    private List<Long> toLongList(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : values) {
            Long parsed = asLong(item);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = asString(item);
            if (StringUtils.hasText(text)) {
                result.add(text);
            }
        }
        return result;
    }

    @FunctionalInterface
    public interface OfficialOshiSelfDownedEffectResolver {
        Map<String, Object> resolveOfficialOshiSelfDownedEffects(AttackDefenderGiftFollowupContext context);
    }
}
