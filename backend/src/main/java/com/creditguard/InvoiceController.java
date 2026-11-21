package com.creditguard;

import com.creditguard.Dtos.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api")
public class InvoiceController {

    private final InvoiceService service;
    private final RiskService risk;
    private final VendorRepository vendors;

    public InvoiceController(InvoiceService service, RiskService risk, VendorRepository vendors) {
        this.service = service;
        this.risk = risk;
        this.vendors = vendors;
    }

    /** Dropdown list of other approved members. */
    @GetMapping("/vendors")
    public List<VendorOption> vendorList(@RequestAttribute("vendor") Vendor me) {
        return vendors.findByStatus(Vendor.Status.APPROVED).stream()
                .filter(v -> !v.id.equals(me.id))
                .map(v -> new VendorOption(v.id, v.vendorCode, v.businessName))
                .toList();
    }

    @GetMapping("/invoices")
    public List<InvoiceView> mine(@RequestAttribute("vendor") Vendor me) {
        return service.mine(me);
    }

    @PostMapping("/invoices")
    public InvoiceView create(@RequestAttribute("vendor") Vendor me,
                              @RequestParam Long buyerId,
                              @RequestParam(defaultValue = "30") int creditDays,
                              @RequestParam MultipartFile file) {
        return service.create(me, buyerId, creditDays, file);
    }

    @PostMapping("/invoices/{id}/confirm")
    public InvoiceView confirm(@RequestAttribute("vendor") Vendor me, @PathVariable Long id) {
        return service.confirm(me, id);
    }

    @PostMapping("/invoices/{id}/dispute")
    public InvoiceView dispute(@RequestAttribute("vendor") Vendor me, @PathVariable Long id) {
        return service.dispute(me, id);
    }

    @PostMapping("/invoices/{id}/settle")
    public SettleResult settle(@RequestAttribute("vendor") Vendor me, @PathVariable Long id,
                               @RequestParam MultipartFile slip) {
        return service.settle(me, id, slip);
    }

    /** Risk report for a prospective buyer - no seller names / invoice details are returned. */
    @GetMapping("/risk/{vendorId}")
    public RiskResult risk(@PathVariable Long vendorId,
                           @RequestParam(required = false) BigDecimal amount) {
        return risk.check(vendorId, amount);
    }
}
