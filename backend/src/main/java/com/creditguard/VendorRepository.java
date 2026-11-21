package com.creditguard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VendorRepository extends JpaRepository<Vendor, Long> {
    Optional<Vendor> findByEmail(String email);
    Optional<Vendor> findByApiToken(String apiToken);
    Optional<Vendor> findByTelegramChatId(Long telegramChatId);
    Optional<Vendor> findByTelegramLinkCode(String telegramLinkCode);
    Optional<Vendor> findByVendorCodeIgnoreCase(String vendorCode);
    List<Vendor> findByStatus(Vendor.Status status);
}
