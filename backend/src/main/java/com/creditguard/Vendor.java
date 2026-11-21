package com.creditguard;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** A registered wholesaler / retailer (the association's member). */
@Entity
public class Vendor {

    public enum Status { PENDING, APPROVED, REVOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String vendorCode;          // e.g. V0001, assigned when admin approves
    public String businessName;
    public String brNumber;            // business registration no.
    public String nic;
    public String phone;

    @Column(unique = true)
    public String email;

    public String bankAccount;

    @JsonIgnore
    public String passwordHash;

    @Enumerated(EnumType.STRING)
    public Status status = Status.PENDING;

    @JsonIgnore
    public String apiToken;            // issued at login, cleared when admin revokes

    @Column(unique = true)
    public Long telegramChatId;        // linked Telegram chat ID

    public String telegramLinkCode;    // one-time code to link Telegram account

    public int trustScore = 85;
    public LocalDateTime createdAt = LocalDateTime.now();
}
