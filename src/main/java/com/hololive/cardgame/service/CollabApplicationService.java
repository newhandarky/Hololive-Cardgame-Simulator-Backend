package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameRuleException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollabApplicationService {

    private final CollabLegacyResolutionBridge collabLegacyResolutionBridge;
    private final CollabActionValidator collabActionValidator;
    private final CollabActionResolver collabActionResolver;
    private final CollabEventFactory collabEventFactory;
    private final CollabTriggerDispatcher collabTriggerDispatcher;

    public CollabApplicationService(
        CollabLegacyResolutionBridge collabLegacyResolutionBridge,
        CollabActionValidator collabActionValidator,
        CollabActionResolver collabActionResolver,
        CollabEventFactory collabEventFactory,
        CollabTriggerDispatcher collabTriggerDispatcher
    ) {
        this.collabLegacyResolutionBridge = collabLegacyResolutionBridge;
        this.collabActionValidator = collabActionValidator;
        this.collabActionResolver = collabActionResolver;
        this.collabEventFactory = collabEventFactory;
        this.collabTriggerDispatcher = collabTriggerDispatcher;
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

    public CollabTriggerDispatchResult dispatchResolvedEvents(
        CollabAction action,
        CollabResolutionResult resolutionResult,
        CollabEffectResolution effectResolution
    ) {
        List<CollabEvent> events = collabEventFactory.createEvents(action, resolutionResult, effectResolution);
        return collabTriggerDispatcher.dispatch(events);
    }
}
