package com.minsu.welocation.spot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "spot_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spot_id")
    private Spot spot;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(nullable = false)
    private int sortOrder;

    SpotImage(Spot spot, String url, int sortOrder) {
        this.spot = spot;
        this.url = url;
        this.sortOrder = sortOrder;
    }

    void moveTo(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
