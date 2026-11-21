package com.creditguard;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Runs every night at 01:00. Admin can also trigger it from the portal for demos. */
@Component
public class OverdueJob {

    private final InvoiceRepository invoices;
    private final VendorRepository vendors;
    private final TelegramBotService telegram;

    public OverdueJob(InvoiceRepository invoices, VendorRepository vendors, TelegramBotService telegram) {
        this.invoices = invoices;
        this.vendors = vendors;
        this.telegram = telegram;
    }

    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public int run() {
        List<Invoice> late = invoices.findByStatusAndDueDateBefore(Invoice.Status.ACTIVE_DEBT, LocalDate.now());
        for (Invoice i : late) {
            i.status = Invoice.Status.OVERDUE;
            Vendor buyer = vendors.findById(i.buyerId).orElse(null);
            Vendor seller = vendors.findById(i.sellerId).orElse(null);
            if (buyer != null) {
                buyer.trustScore = Math.min(buyer.trustScore, 30);   // e.g. 85 -> 30
                vendors.save(buyer);
            }
            telegram.notifyOverdue(i, buyer, seller);
        }
        return late.size();
    }
}
