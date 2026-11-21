package com.creditguard;

import com.creditguard.Dtos.RiskResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

/**
 * Gives a buyer's community-wide risk summary WITHOUT revealing which seller
 * or which invoices are behind it (business privacy).
 */
@Service
public class RiskService {

    private final VendorRepository vendors;
    private final InvoiceRepository invoices;
    private final GeminiService gemini;

    public RiskService(VendorRepository vendors, InvoiceRepository invoices, GeminiService gemini) {
        this.vendors = vendors;
        this.invoices = invoices;
        this.gemini = gemini;
    }

    public RiskResult check(Long vendorId, BigDecimal requestedAmount) {
        Vendor v = vendors.findById(vendorId)
                .filter(x -> x.status == Vendor.Status.APPROVED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vendor not found"));

        List<Invoice> all = invoices.findByBuyerId(vendorId);
        int overdue = (int) all.stream().filter(i -> i.status == Invoice.Status.OVERDUE).count();
        int active = (int) all.stream().filter(i -> i.status == Invoice.Status.ACTIVE_DEBT).count();
        int settled = (int) all.stream().filter(i -> i.status == Invoice.Status.SETTLED_AI_VERIFIED).count();
        String level = v.trustScore >= 70 ? "LOW" : v.trustScore >= 40 ? "MEDIUM" : "HIGH";

        String prompt = """
                You are a credit risk assistant for a wholesale traders' association.
                Facts about a buyer (only these facts; never invent sellers or invoice details):
                - Credit Trust Score: %d / 100 (risk level %s)
                - Overdue debts: %d, active debts: %d, debts settled on record: %d
                - Credit requested now: %s
                Write a recommendation of at most 2 short sentences in English, then the same in Sinhala.
                """.formatted(v.trustScore, level, overdue, active, settled,
                requestedAmount == null ? "not specified" : "Rs. " + requestedAmount);

        String text;
        try {
            text = gemini.askText(prompt);
        } catch (ResponseStatusException e) {
            // keep working even if Gemini is down / key missing
            text = switch (level) {
                case "HIGH" -> "High risk: do not give credit. Cash only.";
                case "MEDIUM" -> "Medium risk: give only a small credit limit.";
                default -> "Low risk: normal credit terms are fine.";
            };
        }
        return new RiskResult(v.businessName, v.trustScore, level, overdue, text);
    }
}
