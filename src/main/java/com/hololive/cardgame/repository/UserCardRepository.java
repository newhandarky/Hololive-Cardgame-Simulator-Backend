package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.UserCard;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCardRepository extends JpaRepository<UserCard, Long> {

    List<UserCard> findByUserIdOrderByCardIdAsc(Long userId);

    Optional<UserCard> findByUserIdAndCardId(Long userId, String cardId);
}
