package com.trading.bot.model.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.trading.bot.model.OrderBook;
import com.trading.bot.model.OrderBook.PriceLevel;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class OrderBookDeserializer extends JsonDeserializer<OrderBook> {

    @Override
    public OrderBook deserialize(JsonParser p, DeserializationContext ctxt) 
            throws IOException, JsonProcessingException {
        
        JsonNode node = p.getCodec().readTree(p);
        
        List<PriceLevel> bids = parsePriceLevels(node.get("bids"));
        List<PriceLevel> asks = parsePriceLevels(node.get("asks"));
        
        return new OrderBook(bids, asks);
    }
    
    private List<PriceLevel> parsePriceLevels(JsonNode arrayNode) {
        List<PriceLevel> priceLevels = new ArrayList<>();
        
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode levelNode : arrayNode) {
                if (levelNode.isArray() && levelNode.size() >= 2) {
                    // Binance returns [price, quantity] arrays
                    BigDecimal price = new BigDecimal(levelNode.get(0).asText());
                    BigDecimal quantity = new BigDecimal(levelNode.get(1).asText());
                    priceLevels.add(new PriceLevel(price, quantity));
                }
            }
        }
        
        return priceLevels;
    }
}