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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                // Use ticker endpoint to get all option data with current pricing
                String url = config.getBinanceApiUrl() + "/eapi/v1/ticker";
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("Failed to get option tickers: " + response.code() + " - " + response.message());
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode jsonArray = objectMapper.readTree(responseBody);
                    
                    if (!jsonArray.isArray()) {
                        throw new RuntimeException("Expected array response from ticker endpoint");
                    }
                    
                    List<OptionContract> contracts = new ArrayList<>();
                    String expiryStr = expiry.format(DateTimeFormatter.ofPattern("yyMMdd"));
                    
                    for (JsonNode tickerNode : jsonArray) {
                        if (!tickerNode.has("symbol")) {
                            continue;
                        }
                        
                        String symbol = tickerNode.get("symbol").asText();
                        
                        // Filter for BTC options with matching expiry (format: BTC-YYMMDD-STRIKE-C/P)
                        if (symbol.startsWith("BTC-") && symbol.contains(expiryStr)) {
                            OptionContract contract = parseOptionContractFromTicker(tickerNode);
                            if (contract != null) {
                                contracts.add(contract);
                            }
                        }
                    }
                    
                    logger.info("Retrieved {} BTC option contracts for expiry {} from ticker endpoint", 
                               contracts.size(), expiry);
                    
                    if (contracts.isEmpty()) {
                        logger.warn("No BTC options found for expiry {}. Available symbols sample:", expiry);
                        // Log first few BTC symbols for debugging
                        int count = 0;
                        for (JsonNode tickerNode : jsonArray) {
                            String symbol = tickerNode.get("symbol").asText();
                            if (symbol.startsWith("BTC-") && count < 5) {
                                logger.warn("  Sample BTC symbol: {}", symbol);
                                count++;
                            }
                        }
                    }
                    
                    return contracts;
                }
            } catch (IOException e) {
                throw new RuntimeException("Error fetching options chain from ticker endpoint", e);
            }
        }, "getOptionsChain");
    }
    

    
    private OptionContract parseOptionContractFromTicker(JsonNode tickerNode) {
        try {
            String symbol = tickerNode.get("symbol").asText();
            
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
                
                // Parse pricing data directly from ticker
                if (tickerNode.has("bidPrice") && !tickerNode.get("bidPrice").asText().equals("0")) {
                    contract.setBidPrice(new BigDecimal(tickerNode.get("bidPrice").asText()));
                }
                if (tickerNode.has("askPrice") && !tickerNode.get("askPrice").asText().equals("0")) {
                    contract.setAskPrice(new BigDecimal(tickerNode.get("askPrice").asText()));
                }
                if (tickerNode.has("bidQty")) {
                    contract.setBidQuantity(new BigDecimal(tickerNode.get("bidQty").asText()));
                }
                if (tickerNode.has("askQty")) {
                    contract.setAskQuantity(new BigDecimal(tickerNode.get("askQty").asText()));
                }
                
                // Use mark price as fallback if bid/ask not available
                if (tickerNode.has("markPrice") && !tickerNode.get("markPrice").asText().equals("0")) {
                    BigDecimal markPrice = new BigDecimal(tickerNode.get("markPrice").asText());
                    if (contract.getBidPrice() == null) {
                        contract.setBidPrice(markPrice);
                    }
                    if (contract.getAskPrice() == null) {
                        contract.setAskPrice(markPrice);
                    }
                }
                
                logger.debug("Parsed option contract from ticker: {} - Strike: {}, Expiry: {}, Type: {}, Bid: {}, Ask: {}", 
                           symbol, strike, expiry, type, contract.getBidPrice(), contract.getAskPrice());
                
                return contract;
            } else {
                logger.warn("Invalid option symbol format: {}", symbol);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse option contract from ticker: {}", tickerNode.get("symbol").asText(), e);
        }
        return null;
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
        // This method is now mostly redundant since we get pricing directly from ticker
        // But keeping it for compatibility and for refreshing individual contract prices
        retryHandler.executeWithRetry(() -> {
            try {
                // Use the ticker endpoint to get current pricing for specific symbol
                String url = config.getBinanceApiUrl() + "/eapi/v1/ticker";
                HttpUrl.Builder urlBuilder = HttpUrl.parse(url).newBuilder()
                        .addQueryParameter("symbol", contract.getSymbol());
                
                Request request = new Request.Builder()
                        .url(urlBuilder.build())
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        logger.warn("Failed to get option pricing for {}: {} - {}", 
                                   contract.getSymbol(), response.code(), response.message());
                        return null;
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode jsonNode = objectMapper.readTree(responseBody);
                    
                    // Update contract with pricing data from ticker
                    updateContractFromTicker(contract, jsonNode);
                    
                    logger.debug("Updated pricing for {}: Bid={}, Ask={}", 
                               contract.getSymbol(), contract.getBidPrice(), contract.getAskPrice());
                }
                return null;
            } catch (IOException e) {
                logger.warn("Error fetching option pricing for {}: {}", contract.getSymbol(), e.getMessage());
                return null;
            }
        }, "updateOptionPricing");
    }
    
    /**
     * Get all option tickers at once for better performance
     */
    public void updateAllOptionPricing(List<OptionContract> contracts) throws Exception {
        retryHandler.executeWithRetry(() -> {
            try {
                // Get all option tickers at once
                String url = config.getBinanceApiUrl() + "/eapi/v1/ticker/24hr";
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        logger.warn("Failed to get all option tickers: {} - {}", response.code(), response.message());
                        return null;
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode jsonArray = objectMapper.readTree(responseBody);
                    
                    if (!jsonArray.isArray()) {
                        logger.warn("Expected array response from ticker endpoint");
                        return null;
                    }
                    
                    // Create a map for quick lookup
                    Map<String, JsonNode> tickerMap = new HashMap<>();
                    for (JsonNode tickerNode : jsonArray) {
                        if (tickerNode.has("symbol")) {
                            tickerMap.put(tickerNode.get("symbol").asText(), tickerNode);
                        }
                    }
                    
                    // Update pricing for all contracts
                    int updatedCount = 0;
                    for (OptionContract contract : contracts) {
                        JsonNode tickerData = tickerMap.get(contract.getSymbol());
                        if (tickerData != null) {
                            updateContractFromTicker(contract, tickerData);
                            updatedCount++;
                        }
                    }
                    
                    logger.info("Updated pricing for {}/{} option contracts", updatedCount, contracts.size());
                }
                return null;
            } catch (IOException e) {
                logger.warn("Error fetching all option tickers: {}", e.getMessage());
                return null;
            }
        }, "updateAllOptionPricing");
    }
    
    private void updateContractFromTicker(OptionContract contract, JsonNode tickerData) {
        try {
            if (tickerData.has("bidPrice") && !tickerData.get("bidPrice").asText().equals("0")) {
                contract.setBidPrice(new BigDecimal(tickerData.get("bidPrice").asText()));
            }
            if (tickerData.has("askPrice") && !tickerData.get("askPrice").asText().equals("0")) {
                contract.setAskPrice(new BigDecimal(tickerData.get("askPrice").asText()));
            }
            if (tickerData.has("bidQty")) {
                contract.setBidQuantity(new BigDecimal(tickerData.get("bidQty").asText()));
            }
            if (tickerData.has("askQty")) {
                contract.setAskQuantity(new BigDecimal(tickerData.get("askQty").asText()));
            }
            
            // Use mark price as fallback
            if (tickerData.has("markPrice") && !tickerData.get("markPrice").asText().equals("0")) {
                BigDecimal markPrice = new BigDecimal(tickerData.get("markPrice").asText());
                if (contract.getBidPrice() == null) {
                    contract.setBidPrice(markPrice);
                }
                if (contract.getAskPrice() == null) {
                    contract.setAskPrice(markPrice);
                }
            }
        } catch (Exception e) {
            logger.warn("Error updating contract {} from ticker data: {}", contract.getSymbol(), e.getMessage());
        }
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
                
                logger.debug("Fetching order book for symbol: {} with depth: {}", symbol, depth);
                
                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body().string();
                    
                    if (!response.isSuccessful()) {
                        logger.error("Failed to get order book for {}: {} - {}", symbol, response.code(), responseBody);
                        throw new RuntimeException("Failed to get order book for " + symbol + ": " + response.code() + " - " + responseBody);
                    }
                    
                    logger.debug("Order book response for {}: {}", symbol, responseBody.substring(0, Math.min(200, responseBody.length())));
                    
                    OrderBook orderBook = objectMapper.readValue(responseBody, OrderBook.class);
                    orderBook.setSymbol(symbol);
                    
                    logger.debug("Parsed order book for {}: {} bids, {} asks, best bid: {}, best ask: {}", 
                               symbol, 
                               orderBook.getBids().size(), 
                               orderBook.getAsks().size(),
                               orderBook.getBestBid(),
                               orderBook.getBestAsk());
                    
                    return orderBook;
                }
            } catch (IOException e) {
                logger.error("IO error fetching order book for {}: {}", symbol, e.getMessage());
                throw new RuntimeException("Error fetching order book for " + symbol, e);
            } catch (Exception e) {
                logger.error("Unexpected error fetching order book for {}: {}", symbol, e.getMessage(), e);
                throw new RuntimeException("Unexpected error fetching order book for " + symbol, e);
            }
        }, "getOrderBook");
    }
    
    public List<LocalDate> getAvailableExpiries() throws Exception {
        return retryHandler.executeWithRetry(() -> {
            try {
                // Use ticker endpoint to get all option symbols
                String url = config.getBinanceApiUrl() + "/eapi/v1/ticker";
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("Failed to get option tickers: " + response.code());
                    }
                    
                    String responseBody = response.body().string();
                    JsonNode jsonArray = objectMapper.readTree(responseBody);
                    
                    if (!jsonArray.isArray()) {
                        throw new RuntimeException("Expected array response from ticker endpoint");
                    }
                    
                    List<LocalDate> expiries = new ArrayList<>();
                    
                    for (JsonNode tickerNode : jsonArray) {
                        if (!tickerNode.has("symbol")) {
                            continue;
                        }
                        
                        String symbol = tickerNode.get("symbol").asText();
                        
                        // Filter for BTC options and extract expiry
                        if (symbol.startsWith("BTC-")) {
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
                    
                    expiries.sort(LocalDate::compareTo);
                    logger.info("Found {} available expiries from ticker endpoint", expiries.size());
                    
                    // Log the expiries for debugging
                    for (LocalDate expiry : expiries) {
                        logger.debug("Available expiry: {}", expiry);
                    }
                    
                    return expiries;
                }
            } catch (IOException e) {
                throw new RuntimeException("Error fetching available expiries from ticker", e);
            }
        }, "getAvailableExpiries");
    }
    
    public LocalDate getCurrentOrNextExpiry() throws Exception {
        LocalDate today = LocalDate.now();
        
        // First try current date
        List<OptionContract> todayOptions = getOptionsChain(today);
        if (!todayOptions.isEmpty()) {
            logger.info("Using current day expiry: {}", today);
            return today;
        }
        
        // Try next few days if today has no options
        for (int i = 1; i <= 7; i++) {
            LocalDate futureDate = today.plusDays(i);
            List<OptionContract> futureOptions = getOptionsChain(futureDate);
            if (!futureOptions.isEmpty()) {
                logger.info("Using next available expiry: {} ({} days from today)", futureDate, i);
                return futureDate;
            }
        }
        
        // If no options found in next 7 days, try weekly expiries (Fridays)
        LocalDate nextFriday = today;
        while (nextFriday.getDayOfWeek().getValue() != 5) { // 5 = Friday
            nextFriday = nextFriday.plusDays(1);
        }
        
        for (int weeks = 0; weeks < 4; weeks++) {
            LocalDate fridayExpiry = nextFriday.plusWeeks(weeks);
            List<OptionContract> fridayOptions = getOptionsChain(fridayExpiry);
            if (!fridayOptions.isEmpty()) {
                logger.info("Using weekly expiry: {} (Friday)", fridayExpiry);
                return fridayExpiry;
            }
        }
        
        throw new RuntimeException("No suitable option expiry found within next 4 weeks");
    }
    
    /**
     * Get today's options chain directly - optimized for current date trading
     */
    public List<OptionContract> getTodaysOptionsChain() throws Exception {
        return getOptionsChain(LocalDate.now());
    }
    
    /**
     * Test method to verify order book functionality with a sample symbol
     */
    public void testOrderBookFunctionality() throws Exception {
        try {
            // First get some option symbols to test with
            List<OptionContract> contracts = getTodaysOptionsChain();
            
            if (contracts.isEmpty()) {
                logger.warn("No option contracts available for testing order book");
                return;
            }
            
            // Test with the first available contract
            OptionContract testContract = contracts.get(0);
            logger.info("Testing order book functionality with symbol: {}", testContract.getSymbol());
            
            OrderBook orderBook = getOrderBook(testContract.getSymbol(), 10);
            
            logger.info("Order book test successful for {}: Best bid: {}, Best ask: {}", 
                       testContract.getSymbol(), orderBook.getBestBid(), orderBook.getBestAsk());
            
        } catch (Exception e) {
            logger.error("Order book test failed: {}", e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Place an option order using Binance Options API
     */
    public OrderResponse placeOrder(OrderRequest orderRequest) throws Exception {
        return retryHandler.executeWithRetry(() -> {
            try {
                String url = config.getBinanceApiUrl() + "/eapi/v1/order";
                
                // Create request body with required parameters
                FormBody.Builder formBuilder = new FormBody.Builder()
                        .add("symbol", orderRequest.getSymbol())
                        .add("side", orderRequest.getSide().toString())
                        .add("type", orderRequest.getType().toString())
                        .add("quantity", orderRequest.getQuantity().toPlainString())
                        .add("timeInForce", "GTC") // Good Till Cancelled
                        .add("timestamp", String.valueOf(System.currentTimeMillis()));
                
                // Add price for limit orders
                if (orderRequest.getType() == OrderType.LIMIT && orderRequest.getPrice() != null) {
                    formBuilder.add("price", orderRequest.getPrice().toPlainString());
                }
                
                FormBody formBody = formBuilder.build();
                
                // Convert FormBody to query string for signing
                String queryString = formBodyToQueryString(formBody);
                
                // Sign the request
                String signature = authService.createSignature(queryString, config.getSecretKey());
                
                Request request = new Request.Builder()
                        .url(url)
                        .post(formBody)
                        .addHeader("X-MBX-APIKEY", config.getApiKey())
                        .addHeader("signature", signature)
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body().string();
                    
                    if (!response.isSuccessful()) {
                        logger.error("Order placement failed: {} - {}", response.code(), responseBody);
                        throw new RuntimeException("Failed to place order: " + response.code() + " - " + responseBody);
                    }
                    
                    JsonNode jsonNode = objectMapper.readTree(responseBody);
                    OrderResponse orderResponse = parseOrderResponse(jsonNode);
                    
                    logger.info("Order placed successfully: {} {} {} @ {} - Order ID: {}", 
                               orderRequest.getSide(), orderRequest.getQuantity(), 
                               orderRequest.getSymbol(), orderRequest.getPrice(), 
                               orderResponse.getOrderId());
                    
                    return orderResponse;
                }
            } catch (IOException e) {
                throw new RuntimeException("Error placing order", e);
            }
        }, "placeOrder");
    }
    
    /**
     * Cancel an option order
     */
    public OrderResponse cancelOrder(String symbol, String orderId) throws Exception {
        return retryHandler.executeWithRetry(() -> {
            try {
                String url = config.getBinanceApiUrl() + "/eapi/v1/order";
                
                FormBody formBody = new FormBody.Builder()
                        .add("symbol", symbol)
                        .add("orderId", orderId)
                        .add("timestamp", String.valueOf(System.currentTimeMillis()))
                        .build();
                
                // Convert FormBody to query string for signing
                String queryString = formBodyToQueryString(formBody);
                
                String signature = authService.createSignature(queryString, config.getSecretKey());
                
                Request request = new Request.Builder()
                        .url(url)
                        .delete(formBody)
                        .addHeader("X-MBX-APIKEY", config.getApiKey())
                        .addHeader("signature", signature)
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body().string();
                    
                    if (!response.isSuccessful()) {
                        logger.error("Order cancellation failed: {} - {}", response.code(), responseBody);
                        throw new RuntimeException("Failed to cancel order: " + response.code() + " - " + responseBody);
                    }
                    
                    JsonNode jsonNode = objectMapper.readTree(responseBody);
                    OrderResponse orderResponse = parseOrderResponse(jsonNode);
                    
                    logger.info("Order cancelled successfully: {} - Order ID: {}", symbol, orderId);
                    return orderResponse;
                }
            } catch (IOException e) {
                throw new RuntimeException("Error cancelling order", e);
            }
        }, "cancelOrder");
    }
    
    private OrderResponse parseOrderResponse(JsonNode jsonNode) {
        try {
            OrderResponse response = new OrderResponse();
            
            if (jsonNode.has("orderId")) {
                response.setOrderId(jsonNode.get("orderId").asText());
            }
            if (jsonNode.has("symbol")) {
                response.setSymbol(jsonNode.get("symbol").asText());
            }
            if (jsonNode.has("status")) {
                response.setStatus(OrderStatus.valueOf(jsonNode.get("status").asText()));
            }
            if (jsonNode.has("executedQty")) {
                response.setFilledQuantity(new BigDecimal(jsonNode.get("executedQty").asText()));
            }
            if (jsonNode.has("origQty")) {
                response.setOriginalQuantity(new BigDecimal(jsonNode.get("origQty").asText()));
            }
            if (jsonNode.has("price")) {
                response.setPrice(new BigDecimal(jsonNode.get("price").asText()));
            }
            if (jsonNode.has("avgPrice") && !jsonNode.get("avgPrice").asText().equals("0")) {
                response.setAvgPrice(new BigDecimal(jsonNode.get("avgPrice").asText()));
            }
            if (jsonNode.has("side")) {
                response.setSide(OrderSide.valueOf(jsonNode.get("side").asText()));
            }
            
            return response;
        } catch (Exception e) {
            logger.error("Error parsing order response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse order response", e);
        }
    }
    
    /**
     * Convert FormBody to query string for API signature
     */
    private String formBodyToQueryString(FormBody formBody) {
        StringBuilder queryString = new StringBuilder();
        
        for (int i = 0; i < formBody.size(); i++) {
            if (queryString.length() > 0) {
                queryString.append("&");
            }
            queryString.append(formBody.encodedName(i))
                      .append("=")
                      .append(formBody.encodedValue(i));
        }
        
        return queryString.toString();
    }
}