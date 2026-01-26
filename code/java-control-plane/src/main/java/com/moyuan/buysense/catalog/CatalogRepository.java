package com.moyuan.buysense.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.buysense.domain.Product;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.List;

@Repository
public class CatalogRepository {
    private final List<Product> products;

    public CatalogRepository(ObjectMapper objectMapper) throws IOException {
        try (var input = new ClassPathResource("catalog.json").getInputStream()) {
            this.products = List.copyOf(objectMapper.readValue(input, new TypeReference<>() { }));
        }
    }

    public List<Product> findAll() {
        return products;
    }
}
