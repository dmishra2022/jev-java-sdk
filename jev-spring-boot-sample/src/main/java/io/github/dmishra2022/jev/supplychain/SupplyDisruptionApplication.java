package io.github.dmishra2022.jev.supplychain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Supply disruption response: reads supplier delay notices with Jev and recommends accept / partial / expedite /
 * spot-buy / escalate, costed against live inventory.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SupplyDisruptionApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupplyDisruptionApplication.class, args);
    }
}
