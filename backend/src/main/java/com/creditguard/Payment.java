package com.creditguard;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A bank slip that was verified by AI. UTR is unique so a slip can't be reused. */
@Entity
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long invoiceId;

    @Column(unique = true)
    public String utr;

    @Column(precision = 15, scale = 2)
    public BigDecimal amount;

    public String beneficiaryAccount;

    @Column(length = 2000)
    public String aiAnalysis;

    public LocalDateTime createdAt = LocalDateTime.now();
}
