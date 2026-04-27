package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameRuleException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BloomApplicationService {

    private final BloomLegacyResolutionBridge bloomLegacyResolutionBridge;
    private final BloomActionValidator bloomActionValidator;
    private final BloomActionResolver bloomActionResolver;
    private final BloomEventFactory bloomEventFactory;
    private final BloomTriggerDispatcher bloomTriggerDispatcher;

    public BloomApplicationService(
        BloomLegacyResolutionBridge bloomLegacyResolutionBridge,
        BloomActionValidator bloomActionValidator,
        BloomActionResolver bloomActionResolver,
        BloomEventFactory bloomEventFactory,
        BloomTriggerDispatcher bloomTriggerDispatcher
    ) {
        this.bloomLegacyResolutionBridge = bloomLegacyResolutionBridge;
        this.bloomActionValidator = bloomActionValidator;
        this.bloomActionResolver = bloomActionResolver;
        this.bloomEventFactory = bloomEventFactory;
        this.bloomTriggerDispatcher = bloomTriggerDispatcher;
    }

    @Transactional
    public BloomValidationContext validate(BloomAction action) {
        BloomValidationContext validationContext = bloomLegacyResolutionBridge.loadValidationContext(action);
        BloomValidationResult validationResult = bloomActionValidator.validate(action, validationContext);
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
    public BloomResolutionResult resolveState(BloomAction action, BloomValidationContext validationContext) {
        return bloomActionResolver.resolve(action, validationContext);
    }

    public BloomTriggerDispatchResult dispatchResolvedEvents(BloomAction action, BloomResolutionResult resolutionResult) {
        List<BloomEvent> events = bloomEventFactory.createEvents(action, resolutionResult);
        return bloomTriggerDispatcher.dispatch(events);
    }
}
