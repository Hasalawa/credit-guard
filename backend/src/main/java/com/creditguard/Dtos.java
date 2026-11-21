package com.creditguard;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record RegisterReq(
            @NotBlank String businessName,
            @NotBlank String brNumber,
            @NotBlank String nic,
            @NotBlank String phone,
            @NotBlank @Email String email,
            @NotBlank String bankAccount,
            @NotBlank @Size(min = 6) String password) {}

    public record LoginReq(@NotBlank String email, @NotBlank String password) {}

    public record LoginRes(String token, Vendor vendor) {}

    public record VendorOption(Long id, String vendorCode, String businessName) {}

    public record InvoiceView(
            Long id, String invoiceNo,
            Long sellerId, String sellerName,
            Long buyerId, String buyerName,
            BigDecimal amount, LocalDate invoiceDate, LocalDate dueDate,
            Invoice.Status status, String aiSummary, LocalDateTime createdAt) {}

    public record SettleResult(boolean passed, List<String> checks, String message, InvoiceView invoice) {}

    public record RiskResult(String vendorName, int trustScore, String riskLevel,
                             int overdueCount, String recommendation) {}
}
