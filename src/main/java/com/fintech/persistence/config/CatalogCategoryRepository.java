package com.fintech.persistence.config;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogCategoryRepository extends JpaRepository<CatalogCategoryEntity, String> {}
