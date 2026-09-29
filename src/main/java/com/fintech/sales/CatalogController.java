package com.fintech.sales;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {

    public record ActiveRequest(boolean active) {}

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/v1/catalog")
    @PreAuthorize("hasAuthority('catalog.view')")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(catalog.list(false));
    }

    @GetMapping("/api/v1/admin/catalog")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ApiResponse<List<Map<String, Object>>> adminList() {
        return ApiResponse.ok(catalog.list(true));
    }

    @PatchMapping("/api/v1/admin/catalog/items/{id}")
    @PreAuthorize("hasAuthority('catalog.manage')")
    public ApiResponse<Map<String, Object>> active(@PathVariable UUID id, @RequestBody ActiveRequest req) {
        return ApiResponse.ok(catalog.setActive(id, req.active()));
    }
}
