package com.kayz.asmer.spring.model;

import com.kayz.asmer.annotation.AssembleMany;
import com.kayz.asmer.annotation.AssembleOne;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.util.List;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String status;

    @Transient
    @AssembleOne(keyField = "userId")
    private UserEntity user;

    @Transient
    @AssembleMany(keyField = "id")
    private List<OrderItemEntity> items;

    protected OrderEntity() {}

    public OrderEntity(Long userId, String status) {
        this.userId = userId;
        this.status = status;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UserEntity getUser() { return user; }
    public void setUser(UserEntity user) { this.user = user; }
    public List<OrderItemEntity> getItems() { return items; }
    public void setItems(List<OrderItemEntity> items) { this.items = items; }

    @Override
    public String toString() {
        return "OrderEntity{" +
                "id=" + id +
                ", userId=" + userId +
                ", status='" + status + '\'' +
                ", user=" + (user != null ? user.getName() : "null") +
                ", items=" + (items != null ? items.size() + " items" : "null") +
                '}';
    }

}
