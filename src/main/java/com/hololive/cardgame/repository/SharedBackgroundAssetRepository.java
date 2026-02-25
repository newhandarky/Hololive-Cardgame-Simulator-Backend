package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.SharedBackgroundAssetEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SharedBackgroundAssetRepository extends JpaRepository<SharedBackgroundAssetEntity, Long> {

    List<SharedBackgroundAssetEntity> findByCategoryOrderByIdDesc(String category);

    Optional<SharedBackgroundAssetEntity> findByCategoryAndImageUrl(String category, String imageUrl);
}
