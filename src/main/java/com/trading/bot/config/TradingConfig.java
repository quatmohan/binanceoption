package com.trading.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalTime;

@Component
@ConfigurationProperties(prefix = "trading")
@Validated
public class TradingConfig {
    
    // API Configuration
    @NotBlank(message = "API key is required")
    private String apiKey;
    
    @NotBlank(message = "Secret key is required")
    private String secretKey;
    
    // Session Configuration
    @NotNull(message = "Session start time is required")
    private LocalTime sessionStartTime = LocalTime.of(12, 25);
    
    @NotNull(message = "Session end time is required")
    private LocalTime sessionEndTime = LocalTime.of(13, 25);
    
    // String setters for flexible time parsing
    public void setSessionStartTime(String timeStr) {
        this.sessionStartTime = parseTimeString(timeStr);
    }
    
    public void setSessionEndTime(String timeStr) {
        this.sessionEndTime = parseTimeString(timeStr);
    }
    
    private LocalTime parseTimeString(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return null;
        }
        
        // Clean the string
        timeStr = timeStr.trim().replaceAll("[^\\d:]", "");
        
        try {
            // Try parsing with seconds first (HH:mm:ss)
            if (timeStr.matches("\\d{2}:\\d{2}:\\d{2}")) {
                return LocalTime.parse(timeStr);
            }
            // Try parsing without seconds (HH:mm)
            else if (timeStr.matches("\\d{2}:\\d{2}")) {
                return LocalTime.parse(timeStr + ":00");
            }
            // Try parsing with single digit hours
            else if (timeStr.matches("\\d{1}:\\d{2}(:\\d{2})?")) {
                if (!timeStr.contains(":00")) {
                    timeStr += ":00";
                }
                return LocalTime.parse(timeStr);
            }
            else {
                throw new IllegalArgumentException("Invalid time format: " + timeStr);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse time: " + timeStr + ". Expected formats: HH:mm or HH:mm:ss", e);
        }
    }
    
    // Trading Parameters
    @Positive(message = "Cycle interval must be positive")
    private int cycleIntervalMinutes = 5;
    
    @Positive(message = "Number of cycles must be positive")
    private int numberOfCycles = 10;
    
    @NotNull(message = "Position quantity is required")
    @Positive(message = "Position quantity must be positive")
    private BigDecimal positionQuantity = new BigDecimal("0.01");
    
    @Positive(message = "Strike distance must be positive")
    private int strikeDistance = 10;
    
    // Risk Management
    @PositiveOrZero(message = "Stop loss percentage must be non-negative")
    private double stopLossPercentage = 30.0;
    
    @PositiveOrZero(message = "Profit target percentage must be non-negative")
    private double profitTargetPercentage = 50.0;
    
    @PositiveOrZero(message = "Portfolio risk percentage must be non-negative")
    private double portfolioRiskPercentage = 10.0;
    
    // Notification Configuration
    private String telegramBotToken;
    private String telegramChatId;
    
    // Binance API Configuration
    private String binanceApiUrl = "https://eapi.binance.com";
    
    // Order Management
    private int orderTimeoutSeconds = 60;
    private int orderUpdateIntervalSeconds = 1;

    // Getters and Setters
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public LocalTime getSessionStartTime() { return sessionStartTime; }
    public void setSessionStartTime(LocalTime sessionStartTime) { this.sessionStartTime = sessionStartTime; }

    public LocalTime getSessionEndTime() { return sessionEndTime; }
    public void setSessionEndTime(LocalTime sessionEndTime) { this.sessionEndTime = sessionEndTime; }

    public int getCycleIntervalMinutes() { return cycleIntervalMinutes; }
    public void setCycleIntervalMinutes(int cycleIntervalMinutes) { this.cycleIntervalMinutes = cycleIntervalMinutes; }

    public int getNumberOfCycles() { return numberOfCycles; }
    public void setNumberOfCycles(int numberOfCycles) { this.numberOfCycles = numberOfCycles; }

    public BigDecimal getPositionQuantity() { return positionQuantity; }
    public void setPositionQuantity(BigDecimal positionQuantity) { this.positionQuantity = positionQuantity; }

    public int getStrikeDistance() { return strikeDistance; }
    public void setStrikeDistance(int strikeDistance) { this.strikeDistance = strikeDistance; }

    public double getStopLossPercentage() { return stopLossPercentage; }
    public void setStopLossPercentage(double stopLossPercentage) { this.stopLossPercentage = stopLossPercentage; }

    public double getProfitTargetPercentage() { return profitTargetPercentage; }
    public void setProfitTargetPercentage(double profitTargetPercentage) { this.profitTargetPercentage = profitTargetPercentage; }

    public double getPortfolioRiskPercentage() { return portfolioRiskPercentage; }
    public void setPortfolioRiskPercentage(double portfolioRiskPercentage) { this.portfolioRiskPercentage = portfolioRiskPercentage; }

    public String getTelegramBotToken() { return telegramBotToken; }
    public void setTelegramBotToken(String telegramBotToken) { this.telegramBotToken = telegramBotToken; }

    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }

    public String getBinanceApiUrl() { return binanceApiUrl; }
    public void setBinanceApiUrl(String binanceApiUrl) { this.binanceApiUrl = binanceApiUrl; }

    public int getOrderTimeoutSeconds() { return orderTimeoutSeconds; }
    public void setOrderTimeoutSeconds(int orderTimeoutSeconds) { this.orderTimeoutSeconds = orderTimeoutSeconds; }

    public int getOrderUpdateIntervalSeconds() { return orderUpdateIntervalSeconds; }
    public void setOrderUpdateIntervalSeconds(int orderUpdateIntervalSeconds) { this.orderUpdateIntervalSeconds = orderUpdateIntervalSeconds; }
}