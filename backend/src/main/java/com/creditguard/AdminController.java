package com.creditguard;

import com.creditguard.Dtos.InvoiceView;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/** Association admin portal API. Protected by the X-Admin-Key header (see WebConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final VendorRepository vendors;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;
    private final OverdueJob overdueJob;
    private final TelegramBotService telegram;

    public AdminController(VendorRepository vendors, InvoiceRepository invoices,
                           InvoiceService invoiceService, OverdueJob overdueJob,
                           TelegramBotService telegram) {
        this.vendors = vendors;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
        this.overdueJob = overdueJob;
        this.telegram = telegram;
    }

    @GetMapping("/vendors")
    public List<Vendor> allVendors() {
        return vendors.findAll();
    }

    @PostMapping("/vendors/{id}/approve")
    public Vendor approve(@PathVariable Long id) {
        Vendor v = vendor(id);
        v.status = Vendor.Status.APPROVED;
        if (v.vendorCode == null) v.vendorCode = String.format("V%04d", v.id);
        Vendor saved = vendors.save(v);
        telegram.notifyVendorApproved(saved);
        return saved;
    }

    /** One click revoke: status = REVOKED and the token stops working immediately. */
    @PostMapping("/vendors/{id}/revoke")
    public Vendor revoke(@PathVariable Long id) {
        Vendor v = vendor(id);
        v.status = Vendor.Status.REVOKED;
        v.apiToken = null;
        return vendors.save(v);
    }

    @GetMapping("/disputes")
    public List<InvoiceView> disputes() {
        return invoices.findByStatus(Invoice.Status.DISPUTED).stream().map(invoiceService::toView).toList();
    }

    /** decision = UPHOLD (debt is real -> ACTIVE_DEBT) or CANCEL (bill is fake -> CANCELLED). */
    @PostMapping("/invoices/{id}/resolve")
    @Transactional
    public InvoiceView resolve(@PathVariable Long id, @RequestParam String decision) {
        Invoice i = invoices.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (i.status != Invoice.Status.DISPUTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice is not disputed");
        }
        switch (decision.toUpperCase()) {
            case "UPHOLD" -> i.status = Invoice.Status.ACTIVE_DEBT;
            case "CANCEL" -> i.status = Invoice.Status.CANCELLED;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be UPHOLD or CANCEL");
        }
        return invoiceService.toView(i);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        List<Invoice> all = invoices.findAll();
        BigDecimal active = sum(all, Invoice.Status.ACTIVE_DEBT);
        BigDecimal overdue = sum(all, Invoice.Status.OVERDUE);
        BigDecimal settled = sum(all, Invoice.Status.SETTLED_AI_VERIFIED);
        BigDecimal open = active.add(overdue);
        double badDebtPct = open.signum() == 0 ? 0
                : overdue.multiply(BigDecimal.valueOf(100)).divide(open, 1, java.math.RoundingMode.HALF_UP).doubleValue();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vendorsApproved", vendors.findByStatus(Vendor.Status.APPROVED).size());
        m.put("vendorsPending", vendors.findByStatus(Vendor.Status.PENDING).size());
        m.put("totalCreditInCirculation", open);
        m.put("activeDebt", active);
        m.put("overdueDebt", overdue);
        m.put("settledTotal", settled);
        m.put("badDebtPercent", badDebtPct);
        m.put("disputes", all.stream().filter(i -> i.status == Invoice.Status.DISPUTED).count());
        return m;
    }

    /** For demos: mark past-due debts OVERDUE right now instead of waiting for 01:00. */
    @PostMapping("/run-overdue-check")
    public Map<String, Object> runOverdue() {
        return Map.of("markedOverdue", overdueJob.run());
    }

    private Vendor vendor(Long id) {
        return vendors.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vendor not found"));
    }

    private static BigDecimal sum(List<Invoice> all, Invoice.Status s) {
        return all.stream().filter(i -> i.status == s).map(i -> i.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
