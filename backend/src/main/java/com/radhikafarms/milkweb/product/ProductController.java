package com.radhikafarms.milkweb.product;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/api/owner/products")
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody OwnerCreateProductRequest request
    ) {
        ProductResponse response = toResponse(productService.createProduct(request));
        URI location = URI.create("/api/owner/products/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/api/owner/products")
    public List<ProductResponse> listProducts() {
        return productService.listProducts().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/api/owner/products/{productId}")
    public ProductResponse getProduct(@PathVariable UUID productId) {
        return toResponse(productService.getProductById(productId));
    }

    @PutMapping("/api/owner/products/{productId}")
    public ProductResponse updateProduct(
            @PathVariable UUID productId,
            @Valid @RequestBody OwnerUpdateProductRequest request
    ) {
        return toResponse(productService.updateProduct(productId, request));
    }

    private ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getUnit(),
                product.getPrice(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
