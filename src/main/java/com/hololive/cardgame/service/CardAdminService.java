package com.hololive.cardgame.service;

import com.hololive.cardgame.dto.AdminCreateCardRequest;
import com.hololive.cardgame.entity.Card;
import com.hololive.cardgame.repository.CardRepository;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CardAdminService {

    private final JdbcTemplate jdbcTemplate;
    private final CardRepository cardRepository;

    public CardAdminService(JdbcTemplate jdbcTemplate, CardRepository cardRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.cardRepository = cardRepository;
    }

    @Transactional
    public Card createCard(AdminCreateCardRequest request) {
        String cardId = normalize(request.getCardId());
        String cardType = normalize(request.getCardType());
        String name = trimOrNull(request.getName());

        if (!StringUtils.hasText(cardId) || !StringUtils.hasText(name) || !StringUtils.hasText(cardType)) {
            throw new IllegalArgumentException("cardId、name、cardType 為必填");
        }
        if (cardRepository.existsById(cardId)) {
            throw new IllegalStateException("card_id 已存在：" + cardId);
        }

        if (!isAllowedCardType(cardType)) {
            throw new IllegalArgumentException("cardType 僅支援 OSHI/MEMBER/SUPPORT/CHEER");
        }

        LocalDateTime now = LocalDateTime.now();
        Timestamp ts = Timestamp.valueOf(now);

        jdbcTemplate.update(
            """
            INSERT INTO cards (card_id, name, rarity, image_url, card_type, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            cardId,
            name,
            trimOrNull(request.getRarity()),
            trimOrNull(request.getImageUrl()),
            cardType,
            ts,
            ts
        );

        try {
            switch (cardType) {
                case "OSHI" -> createOshi(cardId, request, ts);
                case "MEMBER" -> createMember(cardId, request, ts);
                case "SUPPORT" -> createSupport(cardId, request, ts);
                case "CHEER" -> createCheer(cardId, request, ts);
                default -> throw new IllegalArgumentException("不支援的 cardType：" + cardType);
            }
        } catch (DataAccessException ex) {
            throw new IllegalArgumentException("新增卡片失敗，請確認欄位與 FK（顏色/類型）是否正確", ex);
        }

        return cardRepository.findById(cardId)
            .orElseThrow(() -> new IllegalStateException("卡片建立後讀取失敗"));
    }

    private void createOshi(String cardId, AdminCreateCardRequest request, Timestamp ts) {
        if (request.getLife() == null || !StringUtils.hasText(request.getMainColor())) {
            throw new IllegalArgumentException("OSHI 需提供 life、mainColor");
        }
        jdbcTemplate.update(
            """
            INSERT INTO oshi_cards (card_id, life, main_color, sub_color, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            cardId,
            request.getLife(),
            normalize(request.getMainColor()),
            normalizeNullable(request.getSubColor()),
            ts,
            ts
        );
    }

    private void createMember(String cardId, AdminCreateCardRequest request, Timestamp ts) {
        if (request.getHp() == null || !StringUtils.hasText(request.getLevelType())
            || !StringUtils.hasText(request.getMainColor())) {
            throw new IllegalArgumentException("MEMBER 需提供 hp、levelType、mainColor");
        }
        jdbcTemplate.update(
            """
            INSERT INTO member_cards (
                card_id, hp, level_type, main_color, sub_color, bloom_level,
                passive_effect_json, trigger_condition, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            """,
            cardId,
            request.getHp(),
            normalize(request.getLevelType()),
            normalize(request.getMainColor()),
            normalizeNullable(request.getSubColor()),
            request.getBloomLevel(),
            defaultJson(request.getPassiveEffectJson()),
            trimOrNull(request.getTriggerCondition()),
            ts,
            ts
        );
    }

    private void createSupport(String cardId, AdminCreateCardRequest request, Timestamp ts) {
        if (!StringUtils.hasText(request.getEffectType())
            || !StringUtils.hasText(request.getEffectJson())
            || !StringUtils.hasText(request.getTargetType())) {
            throw new IllegalArgumentException("SUPPORT 需提供 effectType、effectJson、targetType");
        }
        jdbcTemplate.update(
            """
            INSERT INTO support_cards (
                card_id, is_limited, condition_type, condition_json,
                effect_type, effect_json, target_type, created_at, updated_at
            ) VALUES (?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?, ?)
            """,
            cardId,
            request.getLimited() != null && request.getLimited(),
            trimOrNull(request.getConditionType()),
            nullableJson(request.getConditionJson()),
            normalize(request.getEffectType()),
            request.getEffectJson(),
            normalize(request.getTargetType()),
            ts,
            ts
        );
    }

    private void createCheer(String cardId, AdminCreateCardRequest request, Timestamp ts) {
        if (!StringUtils.hasText(request.getColor())) {
            throw new IllegalArgumentException("CHEER 需提供 color");
        }
        jdbcTemplate.update(
            """
            INSERT INTO cheer_cards (card_id, color, created_at, updated_at)
            VALUES (?, ?, ?, ?)
            """,
            cardId,
            normalize(request.getColor()),
            ts,
            ts
        );
    }

    private boolean isAllowedCardType(String type) {
        return "OSHI".equals(type) || "MEMBER".equals(type) || "SUPPORT".equals(type) || "CHEER".equals(type);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return normalize(value);
    }

    private String trimOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String defaultJson(String value) {
        if (!StringUtils.hasText(value)) {
            return "{}";
        }
        return value;
    }

    private String nullableJson(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value;
    }
}
