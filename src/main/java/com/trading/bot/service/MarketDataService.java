package com.trading.bot.service;

import com.trading.bot.config.TradingConfig;
import com.trading.bot.model.OptionContract;
import com.trading.bot.model.OptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class MarketDataService {
    
    private static final Logger logger = LoggerFactory.getLogger(MarketDataService.class);
    
    @Autowired
    private BinanceOptionsClient binanceClient;
    
    @Autowired
    private TradingConfig config;
    
    public BigDecimal getCurrentBTCPrice() throws Exception {
        logger.info("Fetching current BTC futures price...");
        BigDecimal price = binanceClient.getBTCFuturesPrice();
        logger.info("Current BTC futures price: {}", price);
        return price;
    }
    
    public List<OptionContract> getCurrentOptionsChain() throws Exception {
        LocalDate expiry = binanceClient.getCurrentOrNextExpiry();
        logger.info("Fetching options chain for expiry: {}", expiry);
        
        List<OptionContract> contracts = binanceClient.getOptionsChain(expiry);
        
        // Update contracts with current market prices
        for (OptionContract contract : contracts) {
            try {
                updateContractPrices(contract);
            } catch (Exception e) {
                logger.warn("Failed to update prices for contract {}: {}", contract.getSymbol(), e.getMessage());
            }
        }
        
        return contracts;
    }
    
    private void updateContractPrices(OptionContract contract) throws Exception {
        try {
            var orderBook = binanceClient.getOrderBook(contract.getSymbol(), 10);
            contract.setBidPrice(orderBook.getBestBid());
            contract.setAskPrice(orderBook.getBestAsk());
            contract.setBidQuantity(orderBook.getBestBidQuantity());
            contract.setAskQuantity(orderBook.getBestAskQuantity());
        } catch (Exception e) {
            logger.debug("Could not fetch order book for {}: {}", contract.getSymbol(), e.getMessage());
            // Continue without prices - they might be available from the options info endpoint
        }
    }
    
    public OptionContract findATMCall(List<OptionContract> contracts, BigDecimal futuresPrice) {
        return binanceClient.findATMOption(contracts, futuresPrice, OptionType.CALL);
    }
    
    public OptionContract findATMPut(List<OptionContract> contracts, BigDecimal futuresPrice) {
        return binanceClient.findATMOption(contracts, futuresPrice, OptionType.PUT);
    }
    
    public OptionContract findOTMCall(List<OptionContract> contracts, BigDecimal atmStrike) {
        List<OptionContract> otmCalls = binanceClient.findStrikeDistanceOptions(
                contracts, atmStrike, config.getStrikeDistance(), OptionType.CALL);
        
        // The method now returns calls sorted by distance from ATM, take the furthest one (last in list)
        if (!otmCalls.isEmpty()) {
            OptionContract selectedCall = otmCalls.get(otmCalls.size() - 1); // Get the furthest OTM call
            logger.info("Found OTM call option: {} at strike {} ({} strikes from ATM {})", 
                       selectedCall.getSymbol(), selectedCall.getStrike(), 
                       otmCalls.size(), atmStrike);
            return selectedCall;
        }
        
        logger.warn("No suitable OTM call found within {} strikes from ATM {}", config.getStrikeDistance(), atmStrike);
        return null;
    }
    
    public OptionContract findOTMPut(List<OptionContract> contracts, BigDecimal atmStrike) {
        List<OptionContract> otmPuts = binanceClient.findStrikeDistanceOptions(
                contracts, atmStrike, config.getStrikeDistance(), OptionType.PUT);
        
        // The method now returns puts sorted by distance from ATM, take the furthest one (last in list)
        if (!otmPuts.isEmpty()) {
            OptionContract selectedPut = otmPuts.get(otmPuts.size() - 1); // Get the furthest OTM put
            logger.info("Found OTM put option: {} at strike {} ({} strikes from ATM {})", 
                       selectedPut.getSymbol(), selectedPut.getStrike(), 
                       otmPuts.size(), atmStrike);
            return selectedPut;
        }
        
        logger.warn("No suitable OTM put found within {} strikes from ATM {}", config.getStrikeDistance(), atmStrike);
        return null;
    }
    
    public void updateOptionPrices(List<OptionContract> contracts) throws Exception {
        logger.debug("Updating prices for {} option contracts", contracts.size());
        
        for (OptionContract contract : contracts) {
            try {
                updateContractPrices(contract);
            } catch (Exception e) {
                logger.warn("Failed to update prices for {}: {}", contract.getSymbol(), e.getMessage());
            }
        }
    }
    
    /**
     * Get all available strikes for calls and puts within the configured distance
     * Useful for debugging and monitoring available options
     */
    public void logAvailableStrikes(List<OptionContract> contracts, BigDecimal atmStrike) {
        List<OptionContract> availableCalls = binanceClient.findStrikeDistanceOptions(
                contracts, atmStrike, config.getStrikeDistance(), OptionType.CALL);
        List<OptionContract> availablePuts = binanceClient.findStrikeDistanceOptions(
                contracts, atmStrike, config.getStrikeDistance(), OptionType.PUT);
        
        logger.info("Available strikes within {} positions from ATM {}:", config.getStrikeDistance(), atmStrike);
        logger.info("  Calls ({}): {}", availableCalls.size(), 
                   availableCalls.stream().map(c -> c.getStrike().toString()).toArray());
        logger.info("  Puts ({}): {}", availablePuts.size(), 
                   availablePuts.stream().map(c -> c.getStrike().toString()).toArray());
    }
    
    /**
     * Debug method to analyze all strikes and categorize them
     */
    public void debugStrikeAnalysis(List<OptionContract> contracts, BigDecimal currentBTCPrice) {
        logger.info("=== STRIKE ANALYSIS DEBUG ===");
        logger.info("Current BTC Price: {}", currentBTCPrice);
        
        // Find actual ATM strike
        OptionContract atmCall = findATMCall(contracts, currentBTCPrice);
        OptionContract atmPut = findATMPut(contracts, currentBTCPrice);
        BigDecimal atmStrike = atmCall != null ? atmCall.getStrike() : 
                              (atmPut != null ? atmPut.getStrike() : currentBTCPrice);
        
        logger.info("ATM Strike: {}", atmStrike);
        
        // Categorize all calls
        logger.info("CALL OPTIONS:");
        contracts.stream()
                .filter(c -> c.getType() == OptionType.CALL)
                .sorted((a, b) -> a.getStrike().compareTo(b.getStrike()))
                .forEach(c -> {
                    String category = c.getStrike().compareTo(currentBTCPrice) < 0 ? "ITM" :
                                    c.getStrike().compareTo(currentBTCPrice) == 0 ? "ATM" : "OTM";
                    logger.info("  {} Call @ {} ({})", category, c.getStrike(), c.getSymbol());
                });
        
        // Categorize all puts
        logger.info("PUT OPTIONS:");
        contracts.stream()
                .filter(c -> c.getType() == OptionType.PUT)
                .sorted((a, b) -> b.getStrike().compareTo(a.getStrike()))
                .forEach(c -> {
                    String category = c.getStrike().compareTo(currentBTCPrice) > 0 ? "ITM" :
                                    c.getStrike().compareTo(currentBTCPrice) == 0 ? "ATM" : "OTM";
                    logger.info("  {} Put @ {} ({})", category, c.getStrike(), c.getSymbol());
                });
        
        // Show what our filtering returns
        List<OptionContract> selectedCalls = binanceClient.findStrikeDistanceOptions(
                contracts, atmStrike, config.getStrikeDistance(), OptionType.CALL);
        List<OptionContract> selectedPuts = binanceClient.findStrikeDistanceOptions(
                contracts, atmStrike, config.getStrikeDistance(), OptionType.PUT);
        
        logger.info("SELECTED FOR IRON BUTTERFLY:");
        logger.info("  Protective Calls: {}", selectedCalls.stream()
                .map(c -> c.getStrike().toString()).toArray());
        logger.info("  Protective Puts: {}", selectedPuts.stream()
                .map(c -> c.getStrike().toString()).toArray());
        
        if (!selectedCalls.isEmpty()) {
            OptionContract selectedCall = selectedCalls.get(selectedCalls.size() - 1);
            logger.info("  Final Call Selection: {} @ {}", selectedCall.getSymbol(), selectedCall.getStrike());
        }
        if (!selectedPuts.isEmpty()) {
            OptionContract selectedPut = selectedPuts.get(selectedPuts.size() - 1);
            logger.info("  Final Put Selection: {} @ {}", selectedPut.getSymbol(), selectedPut.getStrike());
        }
        
        logger.info("=== END STRIKE ANALYSIS ===");
    }
}