package com.example.demo.model;

import com.kayz.asmer.annotation.AssembleMany;
import com.kayz.asmer.annotation.AssembleOne;
import jakarta.persistence.*;

import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private String status;

    /**
     * Many-to-one: many orders → one user.
     * keyField="userId" — use this.getUserId() as the batch key to fetch users.
     */
    @Transient
    @AssembleOne(keyField = "userId")
    private User user;

    /**
     * One-to-many: one order → many items.
     * keyField="id" — use this.getId() as the batch key; children grouped by orderId.
     */
    @Transient
    @AssembleMany(keyField = "id")
    private List<OrderItem> items;

    protected Order() {}

    public Order(Long userId, String status) {
        this.userId = userId;
        this.status = status;
    }

    public Long getId()                     { return id; }
    public void setId(Long id)              { this.id = id; }
    public Long getUserId()                 { return userId; }
    public void setUserId(Long userId)      { this.userId = userId; }
    public String getStatus()               { return status; }
    public void setStatus(String status)    { this.status = status; }
    public User getUser()                   { return user; }
    public void setUser(User user)          { this.user = user; }
    public List<OrderItem> getItems()       { return items; }
    public void setItems(List<OrderItem> i) { this.items = i; }

    @Override
    public String toString() {
        return "Order{id=" + id + ", status='" + status + "'"
                + ", user=" + (user != null ? user.getName() : null)
                + ", items=" + (items != null ? items.size() : 0) + "}";
    }
}
