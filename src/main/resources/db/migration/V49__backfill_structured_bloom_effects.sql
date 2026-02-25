-- Backfill structured bloomEffect definitions for currently supported BLOOM patterns.

-- Generic: return one archive holomem to hand.
UPDATE member_cards
SET passive_effect_json = jsonb_set(
        COALESCE(passive_effect_json, '{}'::jsonb),
        '{bloomEffect}',
        '{
          "effects": ["RETURN_TO_HAND"],
          "value": 1,
          "searchCriteria": {
            "cardType": "MEMBER"
          }
        }'::jsonb,
        TRUE
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE passive_effect_json::text LIKE '%ブルームエフェクト%'
  AND passive_effect_json::text LIKE '%自分のアーカイブのホロメン1枚を手札に戻せる%'
  AND NOT (COALESCE(passive_effect_json, '{}'::jsonb) ? 'bloomEffect');

-- Tagged return-to-hand: #秘密結社holoX from archive, up to two.
UPDATE member_cards
SET passive_effect_json = jsonb_set(
        COALESCE(passive_effect_json, '{}'::jsonb),
        '{bloomEffect}',
        '{
          "effects": ["RETURN_TO_HAND"],
          "value": 2,
          "searchCriteria": {
            "cardType": "MEMBER",
            "tag": "#秘密結社holoX"
          }
        }'::jsonb,
        TRUE
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE passive_effect_json::text LIKE '%ブルームエフェクト%'
  AND passive_effect_json::text LIKE '%自分のアーカイブの#秘密結社holoXを持つホロメン１～２枚を手札に戻せる%'
  AND NOT (COALESCE(passive_effect_json, '{}'::jsonb) ? 'bloomEffect');

-- Dice branch: odd -> return to hand, even -> return to deck top for #秘密結社holoX.
UPDATE member_cards
SET passive_effect_json = jsonb_set(
        COALESCE(passive_effect_json, '{}'::jsonb),
        '{bloomEffect}',
        '{
          "effects": ["RETURN_TO_HAND", "RETURN_TO_DECK_TOP"],
          "value": 1,
          "searchCriteria": {
            "cardType": "MEMBER",
            "tag": "#秘密結社holoX"
          },
          "effectDiceConditions": {
            "RETURN_TO_HAND": "ODD",
            "RETURN_TO_DECK_TOP": "EVEN"
          }
        }'::jsonb,
        TRUE
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE passive_effect_json::text LIKE '%ブルームエフェクト%'
  AND passive_effect_json::text LIKE '%サイコロを1回振れる：奇数の時、自分のアーカイブの#秘密結社holoXを持つホロメン1枚を手札に戻す。偶数の時、自分のアーカイブの#秘密結社holoXを持つホロメン1枚をデッキの上に戻す。%'
  AND NOT (COALESCE(passive_effect_json, '{}'::jsonb) ? 'bloomEffect');

-- Archive Bloom: use archive holomem to bloom Debut with #ID2期生.
UPDATE member_cards
SET passive_effect_json = jsonb_set(
        COALESCE(passive_effect_json, '{}'::jsonb),
        '{bloomEffect}',
        '{
          "effects": ["BLOOM_FROM_ARCHIVE"],
          "searchCriteria": {
            "cardType": "MEMBER",
            "level": "DEBUT",
            "tag": "#ID2期生"
          }
        }'::jsonb,
        TRUE
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE passive_effect_json::text LIKE '%ブルームエフェクト%'
  AND passive_effect_json::text LIKE '%自分の#ID2期生を持つDebutホロメン1人を、自分のアーカイブのホロメンを使ってBloomできる%'
  AND NOT (COALESCE(passive_effect_json, '{}'::jsonb) ? 'bloomEffect');
