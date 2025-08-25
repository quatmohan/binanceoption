package com.trading.bot.config;

import com.trading.bot.exception.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import java.util.Set;

@Component
public class ConfigLoader {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    
    @Autowired
    private TradingConfig tradingConfig;
    
    @Autowired
    private Validator validator;
    
    @PostConstruct
    public void validateConfiguration() throws ConfigurationException {
        logger.info("Validating trading configuration...");
        
        try {
            Set<ConstraintViolation<TradingConfig>> violations = validator.validate(tradingConfig);
            
            if (!violations.isEmpty()) {
                StringBuilder errorMessage = new StringBuilder("Configuration validation failed:\n");
                for (ConstraintViolation<TradingConfig> violation : violations) {
                    errorMessage.append("- ").append(violation.getMessage()).append("\n");
                }
                logger.error(errorMessage.toString());
                throw new ConfigurationException(errorMessage.toString());
            }
            
            // Additional business logic validation
            validateBusinessRules();
            
            logger.info("Configuration validation successful");
            logConfiguration();
            
        } catch (ConfigurationException e) {
            throw e; // Re-throw configuration exceptions
        } catch (Exception e) {
            logger.error("Unexpected error during configuration validation: {}", e.getMessage(), e);
            throw new ConfigurationException("Unexpected configuration validation error", e);
        }
    }
    
    private void validateBusinessRules() throws ConfigurationException {
        if (tradingConfig.getSessionStartTime().isAfter(tradingConfig.getSessionEndTime())) {
            throw new ConfigurationException("Session start time must be before session end time", "session-timing");
        }
        
        if (tradingConfig.getStopLossPercentage() >= 100) {
            throw new ConfigurationException("Stop loss percentage must be less than 100%", "stop-loss-percentage");
        }
        
        if (tradingConfig.getProfitTargetPercentage() <= 0) {
            throw new ConfigurationException("Profit target percentage must be greater than 0%", "profit-target-percentage");
        }
        
        if (tradingConfig.getPortfolioRiskPercentage() >= 100) {
            throw new ConfigurationException("Portfolio risk percentage must be less than 100%", "portfolio-risk-percentage");
        }
        
        // Validate API credentials format (basic validation)
        if (tradingConfig.getApiKey() != null && tradingConfig.getApiKey().length() < 10) {
            throw new ConfigurationException("API key appears to be invalid (too short)", "api-key");
        }
        
        if (tradingConfig.getSecretKey() != null && tradingConfig.getSecretKey().length() < 10) {
            throw new ConfigurationException("Secret key appears to be invalid (too short)", "secret-key");
        }
        
        // Validate Telegram configuration if provided
        if ((tradingConfig.getTelegramBotToken() != null && !tradingConfig.getTelegramBotToken().isEmpty()) ||
            (tradingConfig.getTelegramChatId() != null && !tradingConfig.getTelegramChatId().isEmpty())) {
            
            if (tradingConfig.getTelegramBotToken() == null || tradingConfig.getTelegramBotToken().isEmpty()) {
                throw new ConfigurationException("Telegram chat ID provided but bot token is missing", "telegram-bot-token");
            }
            
            if (tradingConfig.getTelegramChatId() == null || tradingConfig.getTelegramChatId().isEmpty()) {
                throw new ConfigurationException("Telegram bot token provided but chat ID is missing", "telegram-chat-id");
            }
        }
    }
    
    private void logConfiguration() {
        logger.info("Trading Configuration:");
        logger.info("  Session Time: {} - {}", 
                   tradingConfig.getSessionStartTime(), 
                   tradingConfig.getSessionEndTime());
        logger.info("  Cycle Interval: {} minutes", tradingConfig.getCycleIntervalMinutes());
        logger.info("  Number of Cycles: {}", tradingConfig.getNumberOfCycles());
        logger.info("  Position Quantity: {}", tradingConfig.getPositionQuantity());
        logger.info("  Strike Distance: {}", tradingConfig.getStrikeDistance());
        logger.info("  Stop Loss: {}%", tradingConfig.getStopLossPercentage());
        logger.info("  Profit Target: {}%", tradingConfig.getProfitTargetPercentage());
        logger.info("  Portfolio Risk: {}%", tradingConfig.getPortfolioRiskPercentage());
        logger.info("  Binance Options API URL: {}", tradingConfig.getBinanceApiUrl());
        logger.info("  Binance Futures API URL: {}", tradingConfig.getBinanceFuturesApiUrl());
        logger.info("  Order Timeout: {} seconds", tradingConfig.getOrderTimeoutSeconds());
        
        // Don't log sensitive information
        logger.info("  API Key: {}***", 
                   tradingConfig.getApiKey() != null ? tradingConfig.getApiKey().substring(0, 8) : "null");
        logger.info("  Telegram Bot Configured: {}", 
                   tradingConfig.getTelegramBotToken() != null && !tradingConfig.getTelegramBotToken().isEmpty());
    }
}