package com.trading.bot.service;

import com.trading.bot.config.TradingConfig;
import com.trading.bot.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class PositionManager {
    
    private static final Logger logger = LoggerFactory.getLogger(PositionManager.class);
    
    @Autowired
    private TradingConfig config;
    
    @Autowired
    private BinanceOptionsClient binanceClient;
    
    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private AggressiveFillStrategy aggressiveFillStrategy;
    
    @Autowired
    private LoggingService loggingService;
    
    private RiskManager riskManager; // Lazy injection to avoid circular dependency
    
    private final List<IronButterflyPosition> positions = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, BigDecimal> currentPrices = new ConcurrentHashMap<>();
    private ScheduledExecutorService monitoringExecutor;
    
    @PostConstruct
    public void init() {
        this.monitoringExecutor = Executors.newScheduledThreadPool(2);
        startPositionMonitoring();
        logger.info("Position Manager initialized");
    }
    
    public void setRiskManager(RiskManager riskManager) {
        this.riskManager = riskManager;
    }
    
    @PreDestroy
    public void cleanup() {
        stopPositionMonitoring();
    }
    
    public void addPosition(IronButterflyPosition position) {
        positions.add(position);
        logger.info("Added position {} to monitoring (Total positions: {})", 
                   position.getPositionId(), positions.size());
    }
    
    public void removePosition(IronButterflyPosition position) {
        positions.remove(position);
        logger.info("Removed position {} from monitoring (Total positions: {})", 
                   position.getPositionId(), positions.size());
    }
    
    public List<IronButterflyPosition> getAllPositions() {
        return new CopyOnWriteArrayList<>(positions);
    }
    
    public List<IronButterflyPosition> getOpenPositions() {
        return positions.stream()
                .filter(IronButterflyPosition::isOpen)
                .collect(CopyOnWriteArrayList::new, CopyOnWriteArrayList::add, CopyOnWriteArrayList::addAll);
    }
    
    public int getOpenPositionCount() {
        return (int) positions.stream().filter(IronButterflyPosition::isOpen).count();
    }
    
    public void startPositionMonitoring() {
        logger.info("Starting position monitoring (1-second interval)");
        
        monitoringExecutor.scheduleAtFixedRate(this::monitorPositions, 0, 1, TimeUnit.SECONDS);
    }
    
    public void stopPositionMonitoring() {
        logger.info("Stopping position monitoring");
        
        if (monitoringExecutor != null && !monitoringExecutor.isShutdown()) {
            monitoringExecutor.shutdown();
            try {
                if (!monitoringExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    monitoringExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                monitoringExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void monitorPositions() {
        List<IronButterflyPosition> openPositions = getOpenPositions();
        
        if (openPositions.isEmpty()) {
            return;
        }
        
        try {
            // Update current prices for all option symbols
            updateCurrentPrices(openPositions);
            
            // Check each position for P&L and risk management
            for (IronButterflyPosition position : openPositions) {
                updatePositionPrices(position);
                checkPositionRisk(position);
            }
            
        } catch (Exception e) {
            logger.warn("Error during position monitoring: {}", e.getMessage());
        }
    }
    
    private void updateCurrentPrices(List<IronButterflyPosition> positions) {
        for (IronButterflyPosition position : positions) {
            updateLegPrice(position.getSellCall());
            updateLegPrice(position.getSellPut());
            updateLegPrice(position.getBuyCall());
            updateLegPrice(position.getBuyPut());
        }
    }
    
    private void updateLegPrice(OptionLeg leg) {
        if (leg == null || leg.getSymbol() == null) {
            return;
        }
        
        try {
            OrderBook orderBook = binanceClient.getOrderBook(leg.getSymbol(), 1);
            
            BigDecimal currentPrice;
            if (leg.getSide() == OrderSide.SELL) {
                // For sold options, use bid price (price we can buy back at)
                currentPrice = orderBook.getBestBid();
            } else {
                // For bought options, use ask price (price we can sell at)
                currentPrice = orderBook.getBestAsk();
            }
            
            if (currentPrice != null && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                leg.setCurrentPrice(currentPrice);
                currentPrices.put(leg.getSymbol(), currentPrice);
            }
            
        } catch (Exception e) {
            logger.debug("Failed to update price for {}: {}", leg.getSymbol(), e.getMessage());
        }
    }
    
    private void updatePositionPrices(IronButterflyPosition position) {
        // Update current prices for all legs
        if (position.getSellCall() != null) {
            BigDecimal price = currentPrices.get(position.getSellCall().getSymbol());
            if (price != null) {
                position.getSellCall().setCurrentPrice(price);
            }
        }
        
        if (position.getSellPut() != null) {
            BigDecimal price = currentPrices.get(position.getSellPut().getSymbol());
            if (price != null) {
                position.getSellPut().setCurrentPrice(price);
            }
        }
        
        if (position.getBuyCall() != null) {
            BigDecimal price = currentPrices.get(position.getBuyCall().getSymbol());
            if (price != null) {
                position.getBuyCall().setCurrentPrice(price);
            }
        }
        
        if (position.getBuyPut() != null) {
            BigDecimal price = currentPrices.get(position.getBuyPut().getSymbol());
            if (price != null) {
                position.getBuyPut().setCurrentPrice(price);
            }
        }
    }
    
    private void checkPositionRisk(IronButterflyPosition position) {
        // Delegate individual position risk checking to RiskManager
        if (riskManager != null) {
            riskManager.checkIndividualPositionRisk(position);
        }
    }
    
    private BigDecimal calculateNetPremium(IronButterflyPosition position) {
        BigDecimal premiumReceived = BigDecimal.ZERO;
        BigDecimal premiumPaid = BigDecimal.ZERO;
        
        if (position.getSellCall() != null && position.getSellCall().getEntryPrice() != null) {
            premiumReceived = premiumReceived.add(
                    position.getSellCall().getEntryPrice().multiply(position.getSellCall().getQuantity()));
        }
        
        if (position.getSellPut() != null && position.getSellPut().getEntryPrice() != null) {
            premiumReceived = premiumReceived.add(
                    position.getSellPut().getEntryPrice().multiply(position.getSellPut().getQuantity()));
        }
        
        if (position.getBuyCall() != null && position.getBuyCall().getEntryPrice() != null) {
            premiumPaid = premiumPaid.add(
                    position.getBuyCall().getEntryPrice().multiply(position.getBuyCall().getQuantity()));
        }
        
        if (position.getBuyPut() != null && position.getBuyPut().getEntryPrice() != null) {
            premiumPaid = premiumPaid.add(
                    position.getBuyPut().getEntryPrice().multiply(position.getBuyPut().getQuantity()));
        }
        
        return premiumReceived.subtract(premiumPaid);
    }
    
    public void closePosition(IronButterflyPosition position, PositionStatus status, String reason) {
        logger.info("Closing position {} - Reason: {}", position.getPositionId(), reason);
        
        try {
            // Close all legs of the iron butterfly
            closeOptionLeg(position.getSellCall());
            closeOptionLeg(position.getSellPut());
            closeOptionLeg(position.getBuyCall());
            closeOptionLeg(position.getBuyPut());
            
            // Update position status
            position.setStatus(status);
            
            // Calculate final P&L
            BigDecimal finalPnL = position.calculateCurrentPnL();
            
            logger.info("Position {} closed successfully - Final P&L: {}", 
                       position.getPositionId(), finalPnL);
            
            // Send notification
            String message = String.format(
                "🔒 POSITION CLOSED\n" +
                "Position ID: %s\n" +
                "Reason: %s\n" +
                "Final P&L: %s\n" +
                "Status: %s",
                position.getPositionId(),
                reason,
                finalPnL,
                status
            );
            notificationService.sendNotification(message);
            
        } catch (Exception e) {
            logger.error("Failed to close position {}: {}", position.getPositionId(), e.getMessage());
            notificationService.sendAlert("❌ Failed to close position " + position.getPositionId() + ": " + e.getMessage());
        }
    }
    
    private void closeOptionLeg(OptionLeg leg) {
        if (leg == null || leg.getEntryPrice() == null) {
            logger.debug("Skipping unfilled leg: {}", leg != null ? leg.getSymbol() : "null");
            return; // Skip unfilled legs
        }
        
        try {
            // Create opposite order to close the leg
            OrderSide closingSide = leg.getSide() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
            
            // Get current market price for aggressive closing
            OrderBook orderBook = binanceClient.getOrderBook(leg.getSymbol(), 10);
            BigDecimal closingPrice;
            
            if (closingSide == OrderSide.SELL) {
                // Selling - use bid price for quick fill
                closingPrice = orderBook.getBestBid();
                if (closingPrice == null || closingPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    closingPrice = leg.getCurrentPrice() != null ? leg.getCurrentPrice() : leg.getEntryPrice();
                }
            } else {
                // Buying - use ask price for quick fill
                closingPrice = orderBook.getBestAsk();
                if (closingPrice == null || closingPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    closingPrice = leg.getCurrentPrice() != null ? leg.getCurrentPrice() : leg.getEntryPrice();
                }
            }
            
            OrderRequest closeOrder = new OrderRequest(
                    leg.getSymbol(), closingSide, leg.getQuantity(), closingPrice, OrderType.LIMIT);
            
            logger.info("Closing leg: {} {} {} @ {}", 
                       closingSide, leg.getQuantity(), leg.getSymbol(), closingPrice);
            
            // Log position closure attempt
            loggingService.logPositionClosure(leg.getSymbol(), closingSide, leg.getQuantity(), closingPrice);
            
            // Execute order with aggressive fill strategy for reliable closure
            OrderResponse closeResponse = aggressiveFillStrategy.executeOrderWithAggressiveFill(closeOrder);
            
            if (closeResponse.isFilled()) {
                logger.info("Successfully closed leg {} - Filled at average price: {}", 
                           leg.getSymbol(), closeResponse.getAvgPrice());
                loggingService.logPositionClosureSuccess(leg.getSymbol(), closeResponse.getAvgPrice(), closeResponse.getFilledQuantity());
            } else if (closeResponse.isPartiallyFilled()) {
                logger.warn("Partially filled leg closure for {}: {}/{}", 
                           leg.getSymbol(), closeResponse.getFilledQuantity(), closeResponse.getOriginalQuantity());
                loggingService.logPositionClosurePartial(leg.getSymbol(), closeResponse.getFilledQuantity(), closeResponse.getOriginalQuantity());
            } else {
                logger.error("Failed to close leg {} - Order status: {}", leg.getSymbol(), closeResponse.getStatus());
                loggingService.logPositionClosureFailure(leg.getSymbol(), closeResponse.getStatus().toString());
            }
            
        } catch (Exception e) {
            logger.error("Failed to close leg {}: {}", leg.getSymbol(), e.getMessage(), e);
            loggingService.logPositionClosureError(leg.getSymbol(), e.getMessage());
            
            // Send alert for failed position closure
            String alertMessage = String.format(
                "❌ FAILED TO CLOSE POSITION LEG\n" +
                "Symbol: %s\n" +
                "Side: %s\n" +
                "Quantity: %s\n" +
                "Error: %s",
                leg.getSymbol(),
                leg.getSide() == OrderSide.BUY ? "SELL" : "BUY",
                leg.getQuantity(),
                e.getMessage()
            );
            notificationService.sendAlert(alertMessage);
        }
    }
    
    public void closeAllPositions(String reason) {
        List<IronButterflyPosition> openPositions = getOpenPositions();
        
        logger.warn("Closing all {} open positions - Reason: {}", openPositions.size(), reason);
        loggingService.logTradingAction("CLOSE_ALL_POSITIONS", 
            String.format("Count: %d, Reason: %s", openPositions.size(), reason));
        
        int successCount = 0;
        int failureCount = 0;
        
        for (IronButterflyPosition position : openPositions) {
            try {
                closePosition(position, PositionStatus.CLOSED_RISK, reason);
                successCount++;
            } catch (Exception e) {
                failureCount++;
                logger.error("Failed to close position {} during bulk closure: {}", 
                           position.getPositionId(), e.getMessage(), e);
                loggingService.logError("PositionManager", "closeAllPositions", 
                    String.format("PositionId: %s, Error: %s", position.getPositionId(), e.getMessage()), e);
            }
        }
        
        logger.info("Bulk position closure completed - Success: {}, Failures: {}", successCount, failureCount);
        loggingService.logTradingAction("CLOSE_ALL_POSITIONS_COMPLETE", 
            String.format("Success: %d, Failures: %d", successCount, failureCount));
        
        if (failureCount > 0) {
            String alertMessage = String.format(
                "⚠️ BULK POSITION CLOSURE ISSUES\n" +
                "Reason: %s\n" +
                "Total Positions: %d\n" +
                "Successfully Closed: %d\n" +
                "Failed to Close: %d\n" +
                "Manual intervention may be required!",
                reason, openPositions.size(), successCount, failureCount
            );
            notificationService.sendAlert(alertMessage);
        }
    }
    
    /**
     * Enhanced position closure with retry logic and better error handling
     */
    public void closePositionWithRetry(IronButterflyPosition position, PositionStatus status, String reason, int maxRetries) {
        int attempts = 0;
        Exception lastException = null;
        
        while (attempts < maxRetries) {
            try {
                closePosition(position, status, reason);
                logger.info("Successfully closed position {} on attempt {}", position.getPositionId(), attempts + 1);
                return; // Success
            } catch (Exception e) {
                attempts++;
                lastException = e;
                logger.warn("Failed to close position {} on attempt {}/{}: {}", 
                           position.getPositionId(), attempts, maxRetries, e.getMessage());
                
                if (attempts < maxRetries) {
                    try {
                        // Exponential backoff between retries
                        long waitTime = (long) Math.pow(2, attempts) * 1000; // 2s, 4s, 8s, etc.
                        Thread.sleep(Math.min(waitTime, 30000)); // Max 30 seconds
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        // All retries failed
        logger.error("Failed to close position {} after {} attempts", position.getPositionId(), maxRetries);
        loggingService.logError("PositionManager", "closePositionWithRetry", 
            String.format("PositionId: %s, Attempts: %d", position.getPositionId(), maxRetries), lastException);
        
        String alertMessage = String.format(
            "❌ CRITICAL: FAILED TO CLOSE POSITION\n" +
            "Position ID: %s\n" +
            "Reason: %s\n" +
            "Attempts: %d\n" +
            "Last Error: %s\n" +
            "MANUAL INTERVENTION REQUIRED!",
            position.getPositionId(), reason, maxRetries, 
            lastException != null ? lastException.getMessage() : "Unknown error"
        );
        notificationService.sendAlert(alertMessage);
    }
}