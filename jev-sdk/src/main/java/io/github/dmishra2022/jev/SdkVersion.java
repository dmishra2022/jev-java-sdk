package io.github.dmishra2022.jev;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Reads the SDK version stamped into {@code jev-sdk.properties} at build time. */
final class SdkVersion {

    static final String UNKNOWN = "unknown";

    private SdkVersion() {
    }

    static String load() {
        return load(SdkVersion.class.getResourceAsStream("jev-sdk.properties"));
    }

    static String load(InputStream in) {
        if (in == null) {
            return UNKNOWN;
        }
        try (in) {
            var properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", UNKNOWN);
        } catch (IOException e) {
            return UNKNOWN;
        }
    }
}
