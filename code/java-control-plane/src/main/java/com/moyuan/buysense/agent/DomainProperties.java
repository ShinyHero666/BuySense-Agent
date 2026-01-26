package com.moyuan.buysense.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("buysense.domain")
public record DomainProperties(List<String> defaultBundle) {
    public DomainProperties {
        defaultBundle = defaultBundle == null || defaultBundle.isEmpty()
                ? List.of("phone", "headphones", "charger")
                : List.copyOf(defaultBundle);
    }
}
