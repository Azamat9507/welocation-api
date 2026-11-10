package com.minsu.welocation.menu;

import com.minsu.welocation.cafe.Cafe;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cafe_id")
    private Cafe cafe;

    @Column(nullable = false, length = 100)
    private String name;

    // won, no decimals
    @Column(nullable = false)
    private int price;

    @Column(nullable = false)
    private boolean available = true;

    public Menu(Cafe cafe, String name, int price) {
        this.cafe = cafe;
        this.name = name;
        this.price = price;
    }

    public void changeAvailability(boolean available) {
        this.available = available;
    }
}
