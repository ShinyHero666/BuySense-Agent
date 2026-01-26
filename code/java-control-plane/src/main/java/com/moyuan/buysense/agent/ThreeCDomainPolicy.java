package com.moyuan.buysense.agent;

import com.moyuan.buysense.domain.Product;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ThreeCDomainPolicy implements CommerceDomainPolicy {
    @Override
    public String domainId() {
        return "three_c_digital";
    }

    @Override
    public boolean compatible(List<Product> products) {
        Set<String> constrainedGroups = products.stream()
                .map(Product::compatibilityGroup)
                .filter(group -> group != null && !group.isBlank() && !group.equals("universal"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return constrainedGroups.size() <= 1;
    }
}
