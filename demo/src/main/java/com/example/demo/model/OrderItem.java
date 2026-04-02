package com.example.demo.model;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    private String productName;
    private Integer quantity;
    private BigDecimal price;

    protected OrderItem() {}

    public OrderItem(Long orderId, String productName, Integer quantity, BigDecimal price) {
        this.orderId = orderId;
        this.productName = productName;
        this.quantity = quantity;
        this.price = price;
    }

    public Long getId()                      { return id; }
    public void setId(Long id)               { this.id = id; }
    public Long getOrderId()                 { return orderId; }
    public void setOrderId(Long orderId)     { this.orderId = orderId; }
    public String getProductName()           { return productName; }
    public void setProductName(String n)     { this.productName = n; }
    public Integer getQuantity()             { return quantity; }
    public void setQuantity(Integer q)       { this.quantity = q; }
    public BigDecimal getPrice()             { return price; }
    public void setPrice(BigDecimal p)       { this.price = p; }

    @Override
    public String toString() {
        return "OrderItem{id=" + id + ", product='" + productName + "', qty=" + quantity + ", price=" + price + "}";
    }
}
