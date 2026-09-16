package com.inditex.similarproducts.web;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String productId) {
        super("Product " + productId + " was not found");
    }
}
