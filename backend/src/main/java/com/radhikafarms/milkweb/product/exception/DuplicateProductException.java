package com.radhikafarms.milkweb.product.exception;

public class DuplicateProductException extends RuntimeException {
    public DuplicateProductException(String name) {
        super("Product name already exists: " + name);
    }
}
