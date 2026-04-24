package com.hololive.cardgame.config;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "card-admin")
public class CardAdminProperties {

    private Set<Long> allowedUserIds = new LinkedHashSet<>();

    public Set<Long> getAllowedUserIds() {
        return allowedUserIds;
    }

    public void setAllowedUserIds(Set<Long> allowedUserIds) {
        LinkedHashSet<Long> normalizedUserIds = new LinkedHashSet<>();
        if (allowedUserIds != null) {
            for (Long allowedUserId : allowedUserIds) {
                if (allowedUserId != null && allowedUserId > 0) {
                    normalizedUserIds.add(allowedUserId);
                }
            }
        }
        this.allowedUserIds = normalizedUserIds;
    }
}
