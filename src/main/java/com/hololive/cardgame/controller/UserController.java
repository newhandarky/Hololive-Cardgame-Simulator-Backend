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
    public List<User> getAll() {
        return userRepository.findAll();
    }

    // 測試用：建立一筆假資料
    @PostMapping("/test")
    public User createTestUser() {
        User u = new User();
        u.setLineUserId("test_line_" + System.currentTimeMillis());
        u.setDisplayName("測試使用者");
        u.setAvatarUrl("https://example.com/avatar.png");
        return userRepository.save(u);
    }
}

