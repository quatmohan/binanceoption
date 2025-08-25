package com.trading.bot.service;

import com.trading.bot.config.TradingConfig;
import com.trading.bot.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class IronButterflyExecutor {
    
    private static final Logger logger = LoggerFactory.getLogger(IronButterflyExecutor.class);
    
    @Autowired
    private TradingConfig config;
    
    @Autowired
    private MarketDataService marketDataService;
    
    @Autowired
    private AggressiveFillStrategy aggressiveFillStrategy;
    
    @Autowired
    private PositionManager positionManager;
    
    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private LoggingService loggingService;
    
    public IronButterflyPosition executeIronButterfly() throws Exception {
        logger.info("Starting iron butterfly execution...");
        
        try {
            // Step 1: Get current market data
            BigDecimal btcPrice = marketDataService.getCurrentBTCPrice();
            List<OptionContract> optionsChain = marketDataService.getCurrentOptionsChain();
            
            if (optionsChain.isEmpty()) {
                throw new RuntimeException("No options contracts available");
            }
            
            // Step 2: Identify ATM options
            OptionContract atmCall = marketDataService.findATMCall(optionsChain, btcPrice);
            OptionContract atmPut = marketDataService.findATMPut(optionsChain, btcPrice);
            
            if (atmCall == null || atmPut == null) {
                throw new RuntimeException("Could not find ATM call or put options");
            }
            
            BigDecimal atmStrike = atmCall.getStrike();
            logger.info("ATM strike identified: {} (BTC price: {})", atmStrike, btcPrice);
            
            // Debug: Analyze all available strikes
            marketDataService.debugStrikeAnalysis(optionsChain, btcPrice);
            
            // Step 3: Find OTM protective options
            OptionContract otmCall = marketDataService.findOTMCall(optionsChain, atmStrike);
            OptionContract otmPut = marketDataService.findOTMPut(optionsChain, atmStrike);
            
            if (otmCall == null || otmPut == null) {
                throw new RuntimeException("Could not find suitable OTM protective options");
            }
            
            logger.info("Iron butterfly legs identified:");
            logger.info("  Sell ATM Call: {} @ {}", atmCall.getSymbol(), atmCall.getStrike());
            logger.info("  Sell ATM Put: {} @ {}", atmPut.getSymbol(), atmPut.getStrike());
            logger.info("  Buy OTM Call: {} @ {}", otmCall.getSymbol(), otmCall.getStrike());
            logger.info("  Buy OTM Put: {} @ {}", otmPut.getSymbol(), otmPut.getStrike());
            
            // Step 4: Execute all four legs simultaneously
            IronButterflyPosition position = executeAllLegs(atmCall, atmPut, otmCall, otmPut, atmStrike);
            
            // Step 5: Register position for monitoring
            positionManager.addPosition(position);
            
            logger.info("Iron butterfly position created successfully: {}", position.getPositionId());
            
            return position;
            
        } catch (Exception e) {
            logger.error("Failed to execute iron butterfly: {}", e.getMessage(), e);
            notificationService.sendAlert("❌ Iron Butterfly Execution Failed: " + e.getMessage());
            throw e;
        }
    }
    
    private IronButterflyPosition executeAllLegs(OptionContract atmCall, OptionContract atmPut, 
                                               OptionContract otmCall, OptionContract otmPut, 
                                               BigDecimal atmStrike) throws Exception {
        
        BigDecimal quantity = config.getPositionQuantity();
        
        // Create order requests for all four legs
        List<OrderRequest> orderRequests = new ArrayList<>();
        
        // Sell ATM Call
        OrderRequest sellCallOrder = new OrderRequest(
                atmCall.getSymbol(), OrderSide.SELL, quantity, 
                atmCall.getBidPrice() != null ? atmCall.getBidPrice() : atmCall.getAskPrice(), 
                OrderType.LIMIT);
        orderRequests.add(sellCallOrder);
        
        // Sell ATM Put
        OrderRequest sellPutOrder = new OrderRequest(
                atmPut.getSymbol(), OrderSide.SELL, quantity, 
                atmPut.getBidPrice() != null ? atmPut.getBidPrice() : atmPut.getAskPrice(), 
                OrderType.LIMIT);
        orderRequests.add(sellPutOrder);
        
        // Buy OTM Call
        OrderRequest buyCallOrder = new OrderRequest(
                otmCall.getSymbol(), OrderSide.BUY, quantity, 
                otmCall.getAskPrice() != null ? otmCall.getAskPrice() : otmCall.getBidPrice(), 
                OrderType.LIMIT);
        orderRequests.add(buyCallOrder);
        
        // Buy OTM Put
        OrderRequest buyPutOrder = new OrderRequest(
                otmPut.getSymbol(), OrderSide.BUY, quantity, 
                otmPut.getAskPrice() != null ? otmPut.getAskPrice() : otmPut.getBidPrice(), 
                OrderType.LIMIT);
        orderRequests.add(buyPutOrder);
        
        logger.info("Executing {} orders simultaneously...", orderRequests.size());
        
        // Execute all orders concurrently
        List<CompletableFuture<OrderResponse>> futures = new ArrayList<>();
        for (OrderRequest orderRequest : orderRequests) {
            futures.add(aggressiveFillStrategy.executeOrderAsync(orderRequest));
        }
        
        // Wait for all orders to complete
        CompletableFuture<Void> allOrders = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        
        try {
            allOrders.get(); // Wait for completion
        } catch (Exception e) {
            logger.error("Some orders failed during iron butterfly execution", e);
            // Continue with partial fills - we'll handle this in position management
        }
        
        // Collect results
        List<OrderResponse> orderResponses = new ArrayList<>();
        for (CompletableFuture<OrderResponse> future : futures) {
            try {
                orderResponses.add(future.get());
            } catch (Exception e) {
                logger.warn("Order execution failed: {}", e.getMessage());
                orderResponses.add(null); // Add null for failed orders
            }
        }
        
        // Create option legs from successful orders
        OptionLeg sellCallLeg = createOptionLeg(atmCall, OrderSide.SELL, quantity, orderResponses.get(0));
        OptionLeg sellPutLeg = createOptionLeg(atmPut, OrderSide.SELL, quantity, orderResponses.get(1));
        OptionLeg buyCallLeg = createOptionLeg(otmCall, OrderSide.BUY, quantity, orderResponses.get(2));
        OptionLeg buyPutLeg = createOptionLeg(otmPut, OrderSide.BUY, quantity, orderResponses.get(3));
        
        // Create iron butterfly position
        IronButterflyPosition position = new IronButterflyPosition(
                atmStrike, sellCallLeg, sellPutLeg, buyCallLeg, buyPutLeg);
        
        // Log execution summary
        logExecutionSummary(position, orderResponses);
        
        return position;
    }
    
    private OptionLeg createOptionLeg(OptionContract contract, OrderSide side, 
                                    BigDecimal quantity, OrderResponse orderResponse) {
        OptionLeg leg = new OptionLeg(contract.getSymbol(), contract.getType(), 
                                     contract.getStrike(), quantity, side);
        
        if (orderResponse != null && orderResponse.isFilled()) {
            leg.setEntryPrice(orderResponse.getAvgPrice());
            leg.setOrderId(orderResponse.getOrderId());
            leg.setCurrentPrice(orderResponse.getAvgPrice()); // Initial current price
        } else {
            logger.warn("Order not filled for leg: {} {}", side, contract.getSymbol());
            // Set entry price to null to indicate unfilled order
            leg.setEntryPrice(null);
        }
        
        return leg;
    }
    
    private void logExecutionSummary(IronButterflyPosition position, List<OrderResponse> orderResponses) {
        logger.info("Iron Butterfly Execution Summary:");
        logger.info("  Position ID: {}", position.getPositionId());
        logger.info("  ATM Strike: {}", position.getAtmStrike());
        
        int filledOrders = 0;
        BigDecimal totalPremiumReceived = BigDecimal.ZERO;
        BigDecimal totalPremiumPaid = BigDecimal.ZERO;
        
        for (OrderResponse response : orderResponses) {
            if (response != null && response.isFilled()) {
                filledOrders++;
                BigDecimal premium = response.getAvgPrice().multiply(response.getFilledQuantity());
                
                if (response.getSide() == OrderSide.SELL) {
                    totalPremiumReceived = totalPremiumReceived.add(premium);
                } else {
                    totalPremiumPaid = totalPremiumPaid.add(premium);
                }
            }
        }
        
        BigDecimal netPremium = totalPremiumReceived.subtract(totalPremiumPaid);
        
        logger.info("  Orders Filled: {}/4", filledOrders);
        logger.info("  Premium Received: {}", totalPremiumReceived);
        logger.info("  Premium Paid: {}", totalPremiumPaid);
        logger.info("  Net Premium: {}", netPremium);
        logger.info("  Max Theoretical Loss: {}", position.getMaxTheoreticalLoss());
        
        if (filledOrders < 4) {
            String alertMessage = String.format(
                "⚠️ INCOMPLETE IRON BUTTERFLY\n" +
                "Position ID: %s\n" +
                "Orders Filled: %d/4\n" +
                "ATM Strike: %s\n" +
                "Net Premium: %s",
                position.getPositionId(),
                filledOrders,
                position.getAtmStrike(),
                netPremium
            );
            notificationService.sendAlert(alertMessage);
        } else {
            String successMessage = String.format(
                "✅ IRON BUTTERFLY EXECUTED\n" +
                "Position ID: %s\n" +
                "ATM Strike: %s\n" +
                "Net Premium: %s\n" +
                "Max Loss: %s",
                position.getPositionId(),
                position.getAtmStrike(),
                netPremium,
                position.getMaxTheoreticalLoss()
            );
            notificationService.sendNotification(successMessage);
        }
    }
}