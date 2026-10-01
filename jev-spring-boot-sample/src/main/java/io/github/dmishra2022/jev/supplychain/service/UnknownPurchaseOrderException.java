package io.github.dmishra2022.jev.supplychain.service;

public class UnknownPurchaseOrderException extends RuntimeException {

    public UnknownPurchaseOrderException(String purchaseOrder) {
        super("No open purchase order " + purchaseOrder);
    }
}
