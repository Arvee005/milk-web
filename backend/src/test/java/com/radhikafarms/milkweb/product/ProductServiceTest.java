package com.radhikafarms.milkweb.product;

import com.radhikafarms.milkweb.product.exception.DuplicateProductException;
import com.radhikafarms.milkweb.product.exception.ProductNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ProductServiceTest {

    @Autowired
    private ProductService productService;

    @Test
    void createsActiveProductByDefault() {
        Product created = productService.createProduct(new OwnerCreateProductRequest(
                "Cow Milk", "Fresh milk", ProductUnit.LITRE, new BigDecimal("60.00")));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("Cow Milk");
        assertThat(created.getDescription()).isEqualTo("Fresh milk");
        assertThat(created.getUnit()).isEqualTo(ProductUnit.LITRE);
        assertThat(created.getPrice()).isEqualByComparingTo("60.00");
        assertThat(created.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateProductName() {
        productService.createProduct(new OwnerCreateProductRequest(
                "Cow Milk", null, ProductUnit.LITRE, new BigDecimal("60.00")));

        assertThatThrownBy(() -> productService.createProduct(new OwnerCreateProductRequest(
                "Cow Milk", null, ProductUnit.LITRE, new BigDecimal("61.00"))))
                .isInstanceOf(DuplicateProductException.class);
    }

    @Test
    void updatesProductFieldsAndStatusWithoutChangingIdentityOrCreationTime() {
        Product product = productService.createProduct(new OwnerCreateProductRequest(
                "Cow Milk", "Fresh milk", ProductUnit.LITRE, new BigDecimal("60.00")));
        UUID originalId = product.getId();
        var originalCreatedAt = product.getCreatedAt();

        Product updated = productService.updateProduct(product.getId(), new OwnerUpdateProductRequest(
                "Farm Cheese", "Cheese", ProductUnit.KILOGRAM,
                new BigDecimal("450.25"), ProductStatus.INACTIVE));

        assertThat(updated.getName()).isEqualTo("Farm Cheese");
        assertThat(updated.getDescription()).isEqualTo("Cheese");
        assertThat(updated.getUnit()).isEqualTo(ProductUnit.KILOGRAM);
        assertThat(updated.getPrice()).isEqualByComparingTo("450.25");
        assertThat(updated.getStatus()).isEqualTo(ProductStatus.INACTIVE);
        assertThat(updated.getId()).isEqualTo(originalId);
        assertThat(updated.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(updated.getUpdatedAt()).isNotNull();
    }

    @Test
    void reportsUnknownProduct() {
        assertThatThrownBy(() -> productService.getProductById(UUID.randomUUID()))
                .isInstanceOf(ProductNotFoundException.class);

        assertThatThrownBy(() -> productService.updateProduct(UUID.randomUUID(), new OwnerUpdateProductRequest(
                "Milk", null, ProductUnit.LITRE, new BigDecimal("1.00"), ProductStatus.ACTIVE)))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void rejectsRenamingProductToAnotherProductsName() {
        Product first = productService.createProduct(new OwnerCreateProductRequest(
                "Cow Milk", null, ProductUnit.LITRE, new BigDecimal("60.00")));
        Product second = productService.createProduct(new OwnerCreateProductRequest(
                "Buffalo Milk", null, ProductUnit.LITRE, new BigDecimal("75.00")));

        assertThatThrownBy(() -> productService.updateProduct(second.getId(), new OwnerUpdateProductRequest(
                first.getName(), null, ProductUnit.LITRE, new BigDecimal("75.00"), ProductStatus.ACTIVE)))
                .isInstanceOf(DuplicateProductException.class);
    }

    @Test
    void allowsProductToKeepItsOwnName() {
        Product product = productService.createProduct(new OwnerCreateProductRequest(
                "Cow Milk", null, ProductUnit.LITRE, new BigDecimal("60.00")));

        Product updated = productService.updateProduct(product.getId(), new OwnerUpdateProductRequest(
                "Cow Milk", "Updated description", ProductUnit.LITRE,
                new BigDecimal("62.00"), ProductStatus.ACTIVE));

        assertThat(updated.getName()).isEqualTo("Cow Milk");
        assertThat(updated.getDescription()).isEqualTo("Updated description");
    }
}
