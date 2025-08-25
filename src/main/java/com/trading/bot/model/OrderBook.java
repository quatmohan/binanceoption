package com.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.trading.bot.model.deserializer.OrderBookDeserializer;
import java.math.BigDecimal;
import java.util.List;

@JsonDeserialize(using = OrderBookDeserializer.class)
public class OrderBook {
    private List<PriceLevel> bids;
    private List<PriceLevel> asks;
    private String symbol;

    public OrderBook() {}

    public OrderBook(List<PriceLevel> bids, List<PriceLevel> asks) {
        this.bids = bids;
        this.asks = asks;
    }

    // Getters and Setters
    public List<PriceLevel> getBids() { return bids; }
    public void setBids(List<PriceLevel> bids) { this.bids = bids; }

    public List<PriceLevel> getAsks() { return asks; }
    public void setAsks(List<PriceLevel> asks) { this.asks = asks; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public BigDecimal getBestBid() {
        return bids != null && !bids.isEmpty() ? bids.get(0).getPrice() : BigDecimal.ZERO;
    }

    public BigDecimal getBestAsk() {
        return asks != null && !asks.isEmpty() ? asks.get(0).getPrice() : BigDecimal.ZERO;
    }

    public BigDecimal getBestBidQuantity() {
        return bids != null && !bids.isEmpty() ? bids.get(0).getQuantity() : BigDecimal.ZERO;
    }

    public BigDecimal getBestAskQuantity() {
        return asks != null && !asks.isEmpty() ? asks.get(0).getQuantity() : BigDecimal.ZERO;
    }

    public static class PriceLevel {
        private BigDecimal price;
        private BigDecimal quantity;

        public PriceLevel() {}

        public PriceLevel(BigDecimal price, BigDecimal quantity) {
            this.price = price;
            this.quantity = quantity;
        }

        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }

        public BigDecimal getQuantity() { return quantity; }
        public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

        @Override
        public String toString() {
            return String.format("PriceLevel{price=%s, quantity=%s}", price, quantity);
        }
    }
}