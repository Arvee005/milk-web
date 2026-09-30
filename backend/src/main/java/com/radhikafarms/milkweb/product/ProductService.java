package com.radhikafarms.milkweb.product;

import com.radhikafarms.milkweb.product.exception.DuplicateProductException;
import com.radhikafarms.milkweb.product.exception.ProductNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public Product createProduct(OwnerCreateProductRequest request) {
        if (productRepository.findByName(request.name()).isPresent()) {
            throw new DuplicateProductException(request.name());
        }

        Product product = new Product();
        product.setName(request.name());
        product.setDescription(request.description());
        product.setUnit(request.unit());
        product.setPrice(request.price());
        product.setStatus(ProductStatus.ACTIVE);
        return saveAndTranslateDuplicateName(product, request.name());
    }

    public List<Product> listProducts() {
        return productRepository.findAll();
    }

    public Product getProductById(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    @Transactional
    public Product updateProduct(UUID productId, OwnerUpdateProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        productRepository.findByName(request.name())
                .filter(existing -> !existing.getId().equals(productId))
                .ifPresent(existing -> {
                    throw new DuplicateProductException(request.name());
                });

        product.setName(request.name());
        product.setDescription(request.description());
        product.setUnit(request.unit());
        product.setPrice(request.price());
        product.setStatus(request.status());
        return saveAndTranslateDuplicateName(product, request.name());
    }

    private Product saveAndTranslateDuplicateName(Product product, String name) {
        try {
            return productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException ex) {
            if (isProductNameConstraintViolation(ex)) {
                throw new DuplicateProductException(name);
            }
            throw ex;
        }
    }

    private boolean isProductNameConstraintViolation(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "uk_products_name".equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
