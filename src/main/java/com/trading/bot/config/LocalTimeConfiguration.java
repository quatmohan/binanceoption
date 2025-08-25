package com.trading.bot.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Configuration
public class LocalTimeConfiguration {

    @Bean
    @ConfigurationPropertiesBinding
    public Converter<String, LocalTime> localTimeConverter() {
        return new Converter<String, LocalTime>() {
            @Override
            public LocalTime convert(String source) {
                if (source == null || source.trim().isEmpty()) {
                    return null;
                }
                
                // Remove any potential invisible characters and trim
                source = source.trim().replaceAll("[^\\d:]", "");
                
                try {
                    // Try parsing with seconds first (HH:mm:ss)
                    if (source.matches("\\d{2}:\\d{2}:\\d{2}")) {
                        return LocalTime.parse(source, DateTimeFormatter.ofPattern("HH:mm:ss"));
                    }
                    // Try parsing without seconds (HH:mm)
                    else if (source.matches("\\d{2}:\\d{2}")) {
                        return LocalTime.parse(source, DateTimeFormatter.ofPattern("HH:mm"));
                    }
                    // Try parsing with single digit hours (H:mm or H:mm:ss)
                    else if (source.matches("\\d{1}:\\d{2}(:\\d{2})?")) {
                        if (source.contains(":") && source.split(":").length == 3) {
                            return LocalTime.parse(source, DateTimeFormatter.ofPattern("H:mm:ss"));
                        } else {
                            return LocalTime.parse(source, DateTimeFormatter.ofPattern("H:mm"));
                        }
                    }
                    else {
                        throw new IllegalArgumentException("Invalid time format: " + source + ". Expected formats: HH:mm, HH:mm:ss, H:mm, or H:mm:ss");
                    }
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Failed to parse time: " + source + ". Expected formats: HH:mm, HH:mm:ss, H:mm, or H:mm:ss", e);
                }
            }
        };
    }
}