package com.hololive.cardgame.repository;

import com.hololive.cardgame.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByLineUserId(String lineUserId);
}

