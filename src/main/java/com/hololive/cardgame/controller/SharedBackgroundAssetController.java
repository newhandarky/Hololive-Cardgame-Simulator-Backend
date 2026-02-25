package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.CreateSharedBackgroundAssetRequest;
import com.hololive.cardgame.dto.SharedBackgroundAssetResponse;
import com.hololive.cardgame.model.BackgroundAssetCategory;
import com.hololive.cardgame.service.AuthUserResolver;
import com.hololive.cardgame.service.SharedBackgroundAssetService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/background-assets")
public class SharedBackgroundAssetController {

    private final SharedBackgroundAssetService sharedBackgroundAssetService;
    private final AuthUserResolver authUserResolver;

    public SharedBackgroundAssetController(
        SharedBackgroundAssetService sharedBackgroundAssetService,
        AuthUserResolver authUserResolver
    ) {
        this.sharedBackgroundAssetService = sharedBackgroundAssetService;
        this.authUserResolver = authUserResolver;
    }

    @GetMapping
    public List<SharedBackgroundAssetResponse> listByCategory(@RequestParam String category) {
        try {
            return sharedBackgroundAssetService.listByCategory(BackgroundAssetCategory.parse(category));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SharedBackgroundAssetResponse create(@Valid @RequestBody CreateSharedBackgroundAssetRequest request) {
        try {
            return sharedBackgroundAssetService.create(
                BackgroundAssetCategory.parse(request.getCategory()),
                request.getImageUrl(),
                authUserResolver.currentUserId()
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
}
