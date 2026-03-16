package com.hololive.cardgame.controller;

import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.repository.UserRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    /**
     * 取得所有使用者資料（目前主要用於開發期檢查）。
     */
    public List<User> getAll() {
        return userRepository.findAll();
    }

    @PostMapping("/test")
    /**
     * 建立測試用使用者資料。
     */
    public User createTestUser() {
        User u = new User();
        u.setLineUserId("test_line_" + System.currentTimeMillis());
        u.setDisplayName("測試使用者");
        u.setAvatarUrl("https://example.com/avatar.png");
        return userRepository.save(u);
    }
}
