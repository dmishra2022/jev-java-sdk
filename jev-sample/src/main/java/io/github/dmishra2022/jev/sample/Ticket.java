package io.github.dmishra2022.jev.sample;

/** A support ticket; sent to Jev as structured state (a JSON object). */
public record Ticket(String id, String customer, String plan, String subject, String body) {
}
