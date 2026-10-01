package io.github.dmishra2022.jev.supplychain.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An unstructured message from a supplier about an open purchase order: an e-mail, a portal update or a
 * transcribed call.
 */
public record SupplierNotice(
        @NotBlank @Size(max = 64) String noticeId,
        @NotBlank @Size(max = 32) String purchaseOrder,
        @Size(max = 32) String channel,
        @NotBlank @Size(max = 20_000) String message) {
}
