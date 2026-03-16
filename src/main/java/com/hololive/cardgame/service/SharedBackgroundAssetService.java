package com.hololive.cardgame.service;

import com.hololive.cardgame.dto.SharedBackgroundAssetResponse;
import com.hololive.cardgame.entity.SharedBackgroundAssetEntity;
import com.hololive.cardgame.model.BackgroundAssetCategory;
import com.hololive.cardgame.repository.SharedBackgroundAssetRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SharedBackgroundAssetService {

    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("^https?://.+", Pattern.CASE_INSENSITIVE);

    private final SharedBackgroundAssetRepository sharedBackgroundAssetRepository;

    /**
     * 共用背景素材服務，處理素材查詢與新增去重。
     */
    public SharedBackgroundAssetService(SharedBackgroundAssetRepository sharedBackgroundAssetRepository) {
        this.sharedBackgroundAssetRepository = sharedBackgroundAssetRepository;
    }

    @Transactional(readOnly = true)
    /**
     * 依素材類別查詢背景素材列表（新到舊）。
     */
    public List<SharedBackgroundAssetResponse> listByCategory(BackgroundAssetCategory category) {
        return sharedBackgroundAssetRepository.findByCategoryOrderByIdDesc(category.name())
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    /**
     * 新增背景素材；若同分類同 URL 已存在則直接回傳既有資料。
     */
    public SharedBackgroundAssetResponse create(
        BackgroundAssetCategory category,
        String imageUrl,
        Long createdByUserId
    ) {
        if (createdByUserId == null || createdByUserId <= 0) {
            throw new IllegalArgumentException("缺少有效的登入使用者");
        }
        String normalizedUrl = normalizeImageUrl(imageUrl);
        return sharedBackgroundAssetRepository.findByCategoryAndImageUrl(category.name(), normalizedUrl)
            .map(this::toResponse)
            .orElseGet(() -> {
                SharedBackgroundAssetEntity entity = new SharedBackgroundAssetEntity();
                entity.setCategory(category.name());
                entity.setImageUrl(normalizedUrl);
                entity.setCreatedByUserId(createdByUserId);
                entity.setCreatedAt(LocalDateTime.now());
                entity.setUpdatedAt(LocalDateTime.now());
                return toResponse(sharedBackgroundAssetRepository.save(entity));
            });
    }

    /**
     * 檢查並正規化背景圖片 URL（僅允許 http/https）。
     */
    private String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("imageUrl 不可為空");
        }
        String normalized = imageUrl.trim();
        if (!HTTP_URL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("imageUrl 僅支援 http/https");
        }
        return normalized;
    }

    /**
     * Entity 轉 DTO。
     */
    private SharedBackgroundAssetResponse toResponse(SharedBackgroundAssetEntity entity) {
        SharedBackgroundAssetResponse response = new SharedBackgroundAssetResponse();
        response.setId(entity.getId());
        response.setCategory(entity.getCategory());
        response.setImageUrl(entity.getImageUrl());
        response.setCreatedByUserId(entity.getCreatedByUserId());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }
}
