package com.creditguard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    boolean existsByFileHash(String fileHash);
    List<Invoice> findBySellerIdOrBuyerIdOrderByIdDesc(Long sellerId, Long buyerId);
    List<Invoice> findByStatus(Invoice.Status status);
    List<Invoice> findByStatusAndDueDateBefore(Invoice.Status status, LocalDate date);
    List<Invoice> findByBuyerId(Long buyerId);
}
