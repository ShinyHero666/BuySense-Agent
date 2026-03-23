package com.moyuan.buysense.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.platform.DomainPackRegistry;
import com.moyuan.buysense.platform.ExtensionRegistry;
import com.moyuan.buysense.retail.RetailDataGateway;
import com.moyuan.buysense.retail.RetailProviderProperties;

import java.time.Duration;

final class AgentTestFixture {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ExtensionRegistry extensions = new ExtensionRegistry();
    final DomainPackRegistry domains = new DomainPackRegistry(mapper, extensions);
    final RetailDataGateway retail = new RetailDataGateway(
            mapper,
            domains,
            new RetailProviderProperties(
                    false,
                    "",
                    "",
                    true,
                    false,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2),
                    1_048_576));
    final IntentParser parser = new IntentParser(domains);
    final DecisionEngine engine = new DecisionEngine(retail, domains);
}
