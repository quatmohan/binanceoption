package com.trading.bot.service;

import com.trading.bot.config.TradingConfig;
import com.trading.bot.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class AggressiveFillStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(AggressiveFillStrategy.class);
    
    @Autowired
    private TradingConfig config;
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private BinanceOptionsClient binanceClient;
    
    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private CircuitBreaker circuitBreaker;
    
    @Autowired
    private LoggingService loggingService;
    
    public OrderResponse executeOrderWithAggressiveFill(OrderRequest orderRequest) throws Exception {
        logger.info("Executing aggressive fill for {} {} {} @ {}", 
                   orderRequest.getSide(), 
                   orderRequest.getQuantity(), 
                   orderRequest.getSymbol(), 
                   orderRequest.getPrice());
        
        // Log order execution attempt
        loggingService.logTradingAction("AGGRESSIVE_FILL_START", 
            String.format("Symbol: %s, Side: %s, Qty: %s, Price: %s", 
                orderRequest.getSymbol(), orderRequest.getSide(), 
                orderRequest.getQuantity(), orderRequest.getPrice()));
        
        try {
            // Place initial order with circuit breaker protection
            OrderResponse orderResponse = circuitBreaker.execute(() -> {
                try {
                    return orderService.placeOrder(orderRequest);
                } catch (Exception e) {
                    throw new RuntimeException("Order placement failed", e);
                }
            }, "placeOrder_" + orderRequest.getSymbol());
            
            if (orderResponse.isFilled()) {
                logger.info("Order filled immediately: {}", orderResponse.getOrderId());
                loggingService.logTradingAction("AGGRESSIVE_FILL_IMMEDIATE", 
                    String.format("OrderId: %s, AvgPrice: %s", orderResponse.getOrderId(), orderResponse.getAvgPrice()));
                return orderResponse;
            }
            
            // Start aggressive fill monitoring
            return monitorAndUpdateOrder(orderResponse, orderRequest);
            
        } catch (Exception e) {
            logger.error("Failed to execute aggressive fill for {}: {}", orderRequest.getSymbol(), e.getMessage(), e);
            loggingService.logError("AggressiveFillStrategy", "executeOrderWithAggressiveFill", e.getMessage(), e);
            throw e;
        }
    }
    
    private OrderResponse monitorAndUpdateOrder(OrderResponse initialOrder, OrderRequest originalRequest) throws Exception {
        String orderId = initialOrder.getOrderId();
        String symbol = originalRequest.getSymbol();
        OrderSide side = originalRequest.getSide();
        BigDecimal quantity = originalRequest.getQuantity();
        
        LocalDateTime startTime = LocalDateTime.now();
        LocalDateTime timeoutTime = startTime.plusSeconds(config.getOrderTimeoutSeconds());
        
        logger.info("Starting aggressive fill monitoring for order {} (timeout: {} seconds)", 
                   orderId, config.getOrderTimeoutSeconds());
        
        while (LocalDateTime.now().isBefore(timeoutTime)) {
            try {
                // Wait for update interval
                Thread.sleep(config.getOrderUpdateIntervalSeconds() * 1000L);
                
                // Check current order status with circuit breaker protection
                OrderResponse currentStatus = circuitBreaker.execute(() -> {
                    try {
                        return orderService.getOrderStatus(symbol, orderId);
                    } catch (Exception e) {
                        throw new RuntimeException("Get order status failed", e);
                    }
                }, "getOrderStatus_" + symbol);
                
                if (currentStatus.isFilled()) {
                    logger.info("Order {} filled successfully at average price {}", 
                               orderId, currentStatus.getAvgPrice());
                    loggingService.logTradingAction("AGGRESSIVE_FILL_SUCCESS", 
                        String.format("OrderId: %s, AvgPrice: %s, Duration: %ds", 
                            orderId, currentStatus.getAvgPrice(), 
                            java.time.Duration.between(startTime, LocalDateTime.now()).getSeconds()));
                    return currentStatus;
                }
                
                if (currentStatus.isPartiallyFilled()) {
                    logger.info("Order {} partially filled: {}/{}", 
                               orderId, currentStatus.getFilledQuantity(), currentStatus.getOriginalQuantity());
                    loggingService.logTradingAction("AGGRESSIVE_FILL_PARTIAL", 
                        String.format("OrderId: %s, Filled: %s/%s", 
                            orderId, currentStatus.getFilledQuantity(), currentStatus.getOriginalQuantity()));
                    // Continue monitoring for remaining quantity
                }
                
                // Get current market prices and update order with more aggressive pricing
                OrderBook orderBook = circuitBreaker.execute(() -> {
                    try {
                        return binanceClient.getOrderBook(symbol, 10);
                    } catch (Exception e) {
                        throw new RuntimeException("Get order book failed", e);
                    }
                }, "getOrderBook_" + symbol);
                
                BigDecimal aggressivePrice = calculateAggressivePrice(side, orderBook);
                
                if (aggressivePrice != null && !aggressivePrice.equals(currentStatus.getPrice())) {
                    logger.info("Updating order {} with more aggressive price: {} -> {}", 
                               orderId, currentStatus.getPrice(), aggressivePrice);
                    
                    loggingService.logOrderModification(orderId, symbol, currentStatus.getPrice(), aggressivePrice);
                    
                    OrderModifyRequest modifyRequest = new OrderModifyRequest(
                            orderId, symbol, quantity, aggressivePrice);
                    
                    // Execute order modification with circuit breaker protection
                    OrderResponse modifiedOrder = circuitBreaker.execute(() -> {
                        try {
                            return orderService.modifyOrder(modifyRequest);
                        } catch (Exception e) {
                            throw new RuntimeException("Order modification failed", e);
                        }
                    }, "modifyOrder_" + symbol);
                    
                    if (modifiedOrder.isFilled()) {
                        logger.info("Order {} filled after modification at price {}", 
                                   orderId, modifiedOrder.getAvgPrice());
                        loggingService.logTradingAction("AGGRESSIVE_FILL_MODIFIED_SUCCESS", 
                            String.format("OrderId: %s, AvgPrice: %s", orderId, modifiedOrder.getAvgPrice()));
                        return modifiedOrder;
                    }
                }
                
            } catch (Exception e) {
                // Handle different types of errors appropriately
                handleNetworkError(e, "monitorAndUpdateOrder", symbol);
                
                // Check if this is a circuit breaker exception
                if (e.getMessage() != null && e.getMessage().contains("CIRCUIT_BREAKER_OPEN")) {
                    logger.error("Circuit breaker is open - stopping aggressive fill for order {}", orderId);
                    break; // Exit monitoring loop if circuit breaker is open
                }
                
                // For rate limiting errors, wait longer
                long waitTime = config.getOrderUpdateIntervalSeconds() * 1000L;
                if (e.getMessage() != null && (e.getMessage().contains("rate limit") || e.getMessage().contains("429"))) {
                    waitTime = Math.min(30000, waitTime * 5); // Wait up to 30 seconds for rate limits
                    logger.warn("Rate limit detected, waiting {} ms before retry", waitTime);
                } else {
                    waitTime = Math.min(5000, waitTime * 2); // Exponential backoff for other errors
                }
                
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.info("Aggressive fill monitoring interrupted for order {}", orderId);
                    break;
                }
            }
        }
        
        // Timeout reached - send alert and return current status
        logger.warn("Order {} not filled within {} seconds - sending alert", orderId, config.getOrderTimeoutSeconds());
        loggingService.logOrderTimeout(orderId, symbol, config.getOrderTimeoutSeconds());
        
        try {
            // Get final status with circuit breaker protection
            OrderResponse finalStatus = circuitBreaker.execute(() -> {
                try {
                    return orderService.getOrderStatus(symbol, orderId);
                } catch (Exception e) {
                    throw new RuntimeException("Get final order status failed", e);
                }
            }, "getOrderStatus_final_" + symbol);
            
            String alertMessage = String.format(
                "⚠️ ORDER NOT FILLED WITHIN %d SECONDS\n" +
                "Order ID: %s\n" +
                "Symbol: %s\n" +
                "Side: %s\n" +
                "Quantity: %s\n" +
                "Price: %s\n" +
                "Status: %s\n" +
                "Filled: %s/%s\n" +
                "Circuit Breaker: %s",
                config.getOrderTimeoutSeconds(),
                orderId,
                symbol,
                side,
                quantity,
                finalStatus.getPrice(),
                finalStatus.getStatus(),
                finalStatus.getFilledQuantity() != null ? finalStatus.getFilledQuantity() : "0",
                finalStatus.getOriginalQuantity() != null ? finalStatus.getOriginalQuantity() : quantity,
                circuitBreaker.getStatusInfo()
            );
            
            notificationService.sendAlert(alertMessage);
            loggingService.logTradingAction("AGGRESSIVE_FILL_TIMEOUT", 
                String.format("OrderId: %s, FinalStatus: %s", orderId, finalStatus.getStatus()));
            
            return finalStatus;
            
        } catch (Exception e) {
            logger.error("Failed to get final order status for {}: {}", orderId, e.getMessage(), e);
            loggingService.logError("AggressiveFillStrategy", "getFinalOrderStatus", e.getMessage(), e);
            
            // Create a synthetic response indicating failure
            OrderResponse failureResponse = new OrderResponse();
            failureResponse.setOrderId(orderId);
            failureResponse.setSymbol(symbol);
            failureResponse.setStatus(OrderStatus.REJECTED);
            failureResponse.setSide(side);
            failureResponse.setOriginalQuantity(quantity);
            failureResponse.setFilledQuantity(BigDecimal.ZERO);
            
            throw new RuntimeException("Order timeout and failed to get final status: " + e.getMessage(), e);
        }
    }
    
    private BigDecimal calculateAggressivePrice(OrderSide side, OrderBook orderBook) {
        try {
            if (side == OrderSide.SELL) {
                // For sells, use bid price or lower to ensure quick fill
                BigDecimal bestBid = orderBook.getBestBid();
                if (bestBid != null && bestBid.compareTo(BigDecimal.ZERO) > 0) {
                    // Go slightly below best bid for aggressive fill
                    return bestBid.multiply(new BigDecimal("0.999")).setScale(8, RoundingMode.DOWN);
                }
            } else {
                // For buys, use ask price or higher to ensure quick fill
                BigDecimal bestAsk = orderBook.getBestAsk();
                if (bestAsk != null && bestAsk.compareTo(BigDecimal.ZERO) > 0) {
                    // Go slightly above best ask for aggressive fill
                    return bestAsk.multiply(new BigDecimal("1.001")).setScale(8, RoundingMode.UP);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to calculate aggressive price: {}", e.getMessage());
        }
        
        return null;
    }
    
    public CompletableFuture<OrderResponse> executeOrderAsync(OrderRequest orderRequest) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeOrderWithAggressiveFill(orderRequest);
            } catch (Exception e) {
                logger.error("Async order execution failed for {}: {}", orderRequest.getSymbol(), e.getMessage(), e);
                loggingService.logError("AggressiveFillStrategy", "executeOrderAsync", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Handle partial fills by attempting to fill remaining quantity
     */
    public OrderResponse handlePartialFill(OrderResponse partialOrder, OrderRequest originalRequest) throws Exception {
        if (!partialOrder.isPartiallyFilled()) {
            return partialOrder;
        }
        
        BigDecimal remainingQuantity = originalRequest.getQuantity().subtract(
            partialOrder.getFilledQuantity() != null ? partialOrder.getFilledQuantity() : BigDecimal.ZERO);
        
        if (remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return partialOrder; // Already fully filled
        }
        
        logger.info("Handling partial fill for order {} - Remaining quantity: {}", 
                   partialOrder.getOrderId(), remainingQuantity);
        
        // Cancel the existing partial order
        try {
            circuitBreaker.execute(() -> {
                try {
                    orderService.cancelOrder(originalRequest.getSymbol(), partialOrder.getOrderId());
                    return null;
                } catch (Exception e) {
                    throw new RuntimeException("Cancel partial order failed", e);
                }
            }, "cancelOrder_" + originalRequest.getSymbol());
        } catch (Exception e) {
            logger.warn("Failed to cancel partial order {}: {}", partialOrder.getOrderId(), e.getMessage());
        }
        
        // Create new order for remaining quantity with more aggressive pricing
        OrderBook orderBook = circuitBreaker.execute(() -> {
            try {
                return binanceClient.getOrderBook(originalRequest.getSymbol(), 10);
            } catch (Exception e) {
                throw new RuntimeException("Get order book for partial fill failed", e);
            }
        }, "getOrderBook_partial_" + originalRequest.getSymbol());
        
        BigDecimal aggressivePrice = calculateAggressivePrice(originalRequest.getSide(), orderBook);
        if (aggressivePrice == null) {
            aggressivePrice = originalRequest.getPrice();
        }
        
        OrderRequest remainingOrder = new OrderRequest(
            originalRequest.getSymbol(),
            originalRequest.getSide(),
            remainingQuantity,
            aggressivePrice,
            originalRequest.getType()
        );
        
        loggingService.logTradingAction("PARTIAL_FILL_RETRY", 
            String.format("OriginalOrderId: %s, RemainingQty: %s, NewPrice: %s", 
                partialOrder.getOrderId(), remainingQuantity, aggressivePrice));
        
        // Execute the remaining order
        return executeOrderWithAggressiveFill(remainingOrder);
    }
    
    /**
     * Enhanced error handling for network timeouts and API errors
     */
    private void handleNetworkError(Exception e, String operation, String symbol) {
        logger.error("Network error during {} for {}: {}", operation, symbol, e.getMessage(), e);
        
        // Log specific error types
        if (e.getMessage() != null) {
            if (e.getMessage().contains("timeout") || e.getMessage().contains("SocketTimeoutException")) {
                loggingService.logError("NETWORK_TIMEOUT", 
                    String.format("Operation: %s, Symbol: %s", operation, symbol), e);
            } else if (e.getMessage().contains("ConnectException") || e.getMessage().contains("UnknownHostException")) {
                loggingService.logError("NETWORK_CONNECTION", 
                    String.format("Operation: %s, Symbol: %s", operation, symbol), e);
            } else if (e.getMessage().contains("rate limit") || e.getMessage().contains("429")) {
                loggingService.logError("RATE_LIMIT", 
                    String.format("Operation: %s, Symbol: %s", operation, symbol), e);
            } else {
                loggingService.logError("NETWORK_ERROR", 
                    String.format("Operation: %s, Symbol: %s", operation, symbol), e);
            }
        }
        
        // Send alert for critical network issues
        String alertMessage = String.format(
            "🔴 NETWORK ERROR\n" +
            "Operation: %s\n" +
            "Symbol: %s\n" +
            "Error: %s\n" +
            "Circuit Breaker: %s",
            operation, symbol, e.getMessage(), circuitBreaker.getStatusInfo()
        );
        
        try {
            notificationService.sendAlert(alertMessage);
        } catch (Exception alertException) {
            logger.error("Failed to send network error alert: {}", alertException.getMessage());
        }
    }
}