package com.minsu.welocation.spot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// A drama or film shot at a spot, with what happens there in it (Korean, from the source data)
@Entity
@Table(name = "spot_scenes", uniqueConstraints = @UniqueConstraint(name = "uk_spot_scene", columnNames = {"spot_id", "work"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotScene {

    static final int MAX_WORK_LENGTH = 100;
    static final int MAX_SCENE_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spot_id")
    private Spot spot;

    @Column(nullable = false, length = MAX_WORK_LENGTH)
    private String work;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SceneKind kind;

    @Column(length = MAX_SCENE_LENGTH)
    private String scene;

    SpotScene(Spot spot, String work, SceneKind kind, String scene) {
        this.spot = spot;
        this.work = work;
        this.kind = kind;
        this.scene = scene;
    }
}
