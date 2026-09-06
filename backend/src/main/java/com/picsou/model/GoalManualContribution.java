package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "goal_manual_contribution")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GoalManualContribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goal_id", nullable = false)
    private Goal goal;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;
}
