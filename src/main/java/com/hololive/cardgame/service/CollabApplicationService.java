package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollabApplicationService {

    private final CollabLegacyResolutionBridge collabLegacyResolutionBridge;
    private final CollabActionValidator collabActionValidator;
    private final CollabActionResolver collabActionResolver;

    public CollabApplicationService(
        CollabLegacyResolutionBridge collabLegacyResolutionBridge,
        CollabActionValidator collabActionValidator,
        CollabActionResolver collabActionResolver
    ) {
        this.collabLegacyResolutionBridge = collabLegacyResolutionBridge;
        this.collabActionValidator = collabActionValidator;
        this.collabActionResolver = collabActionResolver;
    }

    @Transactional
    public CollabValidationContext validate(CollabAction action) {
        CollabValidationContext validationContext = collabLegacyResolutionBridge.loadValidationContext(action);
        CollabValidationResult validationResult = collabActionValidator.validate(action, validationContext);
        if (!validationResult.allowed()) {
            throw new GameRuleException(
                validationResult.errorCode(),
                validationResult.message(),
                validationResult.details()
            );
        }
        return validationContext;
    }

    @Transactional
    public CollabResolutionResult resolveState(CollabAction action, CollabValidationContext validationContext) {
        return collabActionResolver.resolve(action, validationContext);
    }
}
