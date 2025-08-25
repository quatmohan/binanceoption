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
        
        // Find the call option that is above ATM strike by the configured distance
        for (OptionContract contract : otmCalls) {
            if (contract.getStrike().compareTo(atmStrike) > 0) {
                logger.info("Found OTM call option: {} at strike {}", contract.getSymbol(), contract.getStrike());
                return contract;
            }
        }
        
        logger.warn("No suitable OTM call found at {} strikes from ATM {}", config.getStrikeDistance(), atmStrike);
        return null;
    }
    
    public OptionContract findOTMPut(List<OptionContract> contracts, BigDecimal atmStrike) {
        List<OptionContract> otmPuts = binanceClient.findStrikeDistanceOptions(
                contracts, atmStrike, config.getStrikeDistance(), OptionType.PUT);
        
        // Find the put option that is below ATM strike by the configured distance
        for (OptionContract contract : otmPuts) {
            if (contract.getStrike().compareTo(atmStrike) < 0) {
                logger.info("Found OTM put option: {} at strike {}", contract.getSymbol(), contract.getStrike());
                return contract;
            }
        }
        
        logger.warn("No suitable OTM put found at {} strikes from ATM {}", config.getStrikeDistance(), atmStrike);
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
}