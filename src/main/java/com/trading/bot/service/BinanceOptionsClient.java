package com.trading.bot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.bot.config.TradingConfig;
import com.trading.bot.model.*;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class BinanceOptionsClient {
    
    private static final Logger logger = LoggerFactory.getLogger(BinanceOptionsClient.class);
    
    @Autowired
    private TradingConfig config;
    
    @Autowired
    private AuthenticationService authService;
    
    @Autowired
    private RetryHandler retryHandler;
    
    private OkHttpClient httpClient;
    private ObjectMapper objectMapper;
    
    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
        
        logger.info("Binance Options Client initialized");
    }
    
    public BigDecimal getBTCFuturesPrice() throws Exception {
        return retryHandler.executeWithRetry(() -> {
            try {
                // Use Binance Futures API for BTC price
                String url = config.getBinanceFuturesApiUrl() + "/fapi/v1/ticker/price?symbol=BTCUSDT";
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("Failed to get BTC futures price: " + response.code());
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode jsonNode = objectMapper.readTree(responseBody);
                    
                    // Futures API returns: {"symbol":"BTCUSDT","price":"43250.50"}
                    BigDecimal price = new BigDecimal(jsonNode.get("price").asText());
                    logger.info("Retrieved BTC futures price: {} from {}", price, url);
                    
                    return price;
                }
            } catch (IOException e) {
                throw new RuntimeException("Error fetching BTC futures price", e);
            }
        }, "getBTCFuturesPrice");
    }
    
    public List<OptionContract> getOptionsChain(LocalDate expiry) throws Exception {
        return retryHandler.executeWithRetry(() -> {
            try {
                String expiryStr = expiry.format(DateTimeFormatter.ofPattern("yyMMdd"));
                String url = config.getBinanceApiUrl() + "/eapi/v1/exchangeInfo";
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("Failed to get options chain: " + response.code());
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode jsonNode = objectMapper.readTree(responseBody);
                    
                    List<OptionContract> contracts = new ArrayList<>();
                    
                    // exchangeInfo returns: {"symbols": [...], "timezone": "UTC", ...}
                    JsonNode symbolsNode = jsonNode.get("symbols");
                    if (symbolsNode != null && symbolsNode.isArray()) {
                        for (JsonNode contractNode : symbolsNode) {
                            String symbol = contractNode.get("symbol").asText();
                            
                            // Filter for BTC options with matching expiry
                            if (symbol.startsWith("BTC") && symbol.contains(expiryStr)) {
                                OptionContract contract = parseOptionContract(contractNode);
                                if (contract != null) {
                                    contracts.add(contract);
                                }
                            }
                        }
                    } else {
                        logger.warn("No 'symbols' array found in exchangeInfo response");
                    }
                    
                    logger.info("Retrieved {} option contracts for expiry {}", contracts.size(), expiry);
                    return contracts;
                }
            } catch (IOException e) {
                throw new RuntimeException("Error fetching options chain", e);
            }
        }, "getOptionsChain");
    }
    
    private OptionContract parseOptionContract(JsonNode contractNode) {
        try {
            String symbol = contractNode.get("symbol").asText();
            
            // Parse symbol format: BTC-YYMMDD-STRIKE-C/P
            String[] parts = symbol.split("-");
            if (parts.length >= 4) {
                String expiryStr = parts[1];
                String strikeStr = parts[2];
                String typeStr = parts[3];
                
                // Parse expiry date
                LocalDate expiry = LocalDate.parse("20" + expiryStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                
                // Parse strike price from symbol
                BigDecimal strike = new BigDecimal(strikeStr);
                
                // Parse option type
                OptionType type = typeStr.equals("C") ? OptionType.CALL : OptionType.PUT;
                
                OptionContract contract = new OptionContract(symbol, strike, expiry, type);
                
                // Note: exchangeInfo doesn't include pricing data (bid/ask prices)
                // Pricing data needs to be fetched separately using /eapi/v1/ticker or /eapi/v1/depth
                logger.debug("Parsed option contract: {} - Strike: {}, Expiry: {}, Type: {}", 
                           symbol, strike, expiry, type);
                
                return contract;
            } else {
                logger.warn("Invalid option symbol format: {}", symbol);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse option contract: {}", contractNode, e);
        }
        return null;
    }
    
    public OptionContract findATMOption(List<OptionContract> contracts, BigDecimal futuresPrice, OptionType type) {
        OptionContract closestContract = null;
        BigDecimal smallestDifference = null;
        
        for (OptionContract contract : contracts) {
            if (contract.getType() == type) {
                BigDecimal difference = contract.getStrike().subtract(futuresPrice).abs();
                
                if (smallestDifference == null || difference.compareTo(smallestDifference) < 0) {
                    smallestDifference = difference;
                    closestContract = contract;
                }
            }
        }
        
        if (closestContract != null) {
            logger.info("Found ATM {} option: {} at strike {} (futures price: {})", 
                       type, closestContract.getSymbol(), closestContract.getStrike(), futuresPrice);
        }
        
        return closestContract;
    }
    
    public List<OptionContract> findStrikeDistanceOptions(List<OptionContract> contracts, 
                                                         BigDecimal atmStrike, 
                                                         int strikeDistance, 
                                                         OptionType type) {
        List<OptionContract> result = new ArrayList<>();
        
        for (OptionContract contract : contracts) {
            if (contract.getType() == type) {
                BigDecimal strikeDiff = contract.getStrike().subtract(atmStrike).abs();
                
                // Find options that are approximately strikeDistance away
                if (strikeDiff.compareTo(BigDecimal.valueOf(strikeDistance * 1000)) >= 0) {
                    result.add(contract);
                }
            }
        }
        
        // Sort by strike distance and return closest matches
        result.sort((a, b) -> {
            BigDecimal diffA = a.getStrike().subtract(atmStrike).abs();
            BigDecimal diffB = b.getStrike().subtract(atmStrike).abs();
            return diffA.compareTo(diffB);
        });
        
        return result;
    }
    
    public void updateOptionPricing(OptionContract contract) throws Exception {
        retryHandler.executeWithRetry(() -> {
            try {
                String url = config.getBinanceApiUrl() + "/eapi/v1/ticker";
                Request request = new Request.Builder()
                        .url(url + "?symbol=" + contract.getSymbol())
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("Failed to get option pricing: " + response.code());
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode jsonNode = objectMapper.readTree(responseBody);
                    
                    // Update contract with pricing data
                    if (jsonNode.has("bidPrice") && !jsonNode.get("bidPrice").asText().equals("0")) {
                        contract.setBidPrice(new BigDecimal(jsonNode.get("bidPrice").asText()));
                    }
                    if (jsonNode.has("askPrice") && !jsonNode.get("askPrice").asText().equals("0")) {
                        contract.setAskPrice(new BigDecimal(jsonNode.get("askPrice").asText()));
                    }
                    if (jsonNode.has("bidQty")) {
                        contract.setBidQuantity(new BigDecimal(jsonNode.get("bidQty").asText()));
                    }
                    if (jsonNode.has("askQty")) {
                        contract.setAskQuantity(new BigDecimal(jsonNode.get("askQty").asText()));
                    }
                    
                    logger.debug("Updated pricing for {}: Bid={}, Ask={}", 
                               contract.getSymbol(), contract.getBidPrice(), contract.getAskPrice());
                }
                return null;
            } catch (IOException e) {
                throw new RuntimeException("Error fetching option pricing for " + contract.getSymbol(), e);
            }
        }, "updateOptionPricing");
    }

    public OrderBook getOrderBook(String symbol, int depth) throws Exception {
        return retryHandler.executeWithRetry(() -> {
            try {
                String url = config.getBinanceApiUrl() + "/eapi/v1/depth";
                HttpUrl.Builder urlBuilder = HttpUrl.parse(url).newBuilder()
                        .addQueryParameter("symbol", symbol)
                        .addQueryParameter("limit", String.valueOf(depth));
                
                Request request = new Request.Builder()
                        .url(urlBuilder.build())
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("Failed to get order book: " + response.code());
                    }
                    
                    String responseBody = response.body().string();
                    OrderBook orderBook = objectMapper.readValue(responseBody, OrderBook.class);
                    orderBook.setSymbol(symbol);
                    
                    return orderBook;
                }
            } catch (IOException e) {
                throw new RuntimeException("Error fetching order book for " + symbol, e);
            }
        }, "getOrderBook");
    }
    
    public List<LocalDate> getAvailableExpiries() throws Exception {
        return retryHandler.executeWithRetry(() -> {
            try {
                String url = config.getBinanceApiUrl() + "/eapi/v1/exchangeInfo";
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("Failed to get available expiries: " + response.code());
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode jsonNode = objectMapper.readTree(responseBody);
                    
                    List<LocalDate> expiries = new ArrayList<>();
                    
                    // exchangeInfo returns: {"symbols": [...], "timezone": "UTC", ...}
                    JsonNode symbolsNode = jsonNode.get("symbols");
                    if (symbolsNode != null && symbolsNode.isArray()) {
                        for (JsonNode contractNode : symbolsNode) {
                            String symbol = contractNode.get("symbol").asText();
                            
                            if (symbol.startsWith("BTC")) {
                                String[] parts = symbol.split("-");
                                if (parts.length >= 2) {
                                    try {
                                        String expiryStr = parts[1];
                                        LocalDate expiry = LocalDate.parse("20" + expiryStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                                        
                                        if (!expiries.contains(expiry)) {
                                            expiries.add(expiry);
                                        }
                                    } catch (Exception e) {
                                        // Skip invalid expiry formats
                                    }
                                }
                            }
                        }
                    } else {
                        logger.warn("No 'symbols' array found in exchangeInfo response");
                    }
                    
                    expiries.sort(LocalDate::compareTo);
                    logger.info("Found {} available expiries", expiries.size());
                    
                    return expiries;
                }
            } catch (IOException e) {
                throw new RuntimeException("Error fetching available expiries", e);
            }
        }, "getAvailableExpiries");
    }
    
    public LocalDate getCurrentOrNextExpiry() throws Exception {
        List<LocalDate> expiries = getAvailableExpiries();
        LocalDate today = LocalDate.now();
        
        // First try to find today's expiry
        for (LocalDate expiry : expiries) {
            if (expiry.equals(today)) {
                logger.info("Using current day expiry: {}", expiry);
                return expiry;
            }
        }
        
        // If no current day expiry, find next available
        for (LocalDate expiry : expiries) {
            if (expiry.isAfter(today)) {
                logger.info("Using next available expiry: {}", expiry);
                return expiry;
            }
        }
        
        throw new RuntimeException("No suitable expiry found");
    }
}