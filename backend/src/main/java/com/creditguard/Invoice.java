package com.creditguard;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
public class Invoice {

    public enum Status {
        PENDING_CONFIRMATION, ACTIVE_DEBT, DISPUTED, OVERDUE, SETTLED_AI_VERIFIED, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String invoiceNo;
    public Long sellerId;
    public Long buyerId;

    @Column(precision = 15, scale = 2)
    public BigDecimal amount;

    public LocalDate invoiceDate;
    public LocalDate dueDate;

    /** SHA-256 of the uploaded file - stops the same bill being entered twice. */
    @Column(unique = true, length = 64)
    public String fileHash;

    @Enumerated(EnumType.STRING)
    public Status status = Status.PENDING_CONFIRMATION;

    @Column(length = 2000)
    public String aiSummary;

    public LocalDateTime createdAt = LocalDateTime.now();
}
