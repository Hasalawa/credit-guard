package com.creditguard;

import com.creditguard.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final VendorRepository vendors;
    private final TelegramBotService telegram;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(VendorRepository vendors, TelegramBotService telegram) {
        this.vendors = vendors;
        this.telegram = telegram;
    }

    /** Step 1 of KYB: vendor registers, status = PENDING until admin approves. */
    @PostMapping("/register")
    public Map<String, String> register(@Valid @RequestBody RegisterReq r) {
        if (vendors.findByEmail(r.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        Vendor v = new Vendor();
        v.businessName = r.businessName();
        v.brNumber = r.brNumber();
        v.nic = r.nic();
        v.phone = r.phone();
        v.email = r.email();
        v.bankAccount = r.bankAccount();
        v.passwordHash = encoder.encode(r.password());
        vendors.save(v);
        return Map.of("message", "Registered. Wait for the association admin to approve your account.");
    }

    @PostMapping("/login")
    public LoginRes login(@Valid @RequestBody LoginReq r) {
        Vendor v = vendors.findByEmail(r.email())
                .filter(x -> encoder.matches(r.password(), x.passwordHash))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong email or password"));
        if (v.status == Vendor.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your account is waiting for admin approval");
        }
        if (v.status == Vendor.Status.REVOKED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your access was revoked by the admin");
        }
        v.apiToken = UUID.randomUUID().toString();   // new login = new token (old one stops working)
        vendors.save(v);
        return new LoginRes(v.apiToken, v);
    }

    @GetMapping("/me")
    public Vendor me(@RequestAttribute("vendor") Vendor me) {
        return vendors.findById(me.id).orElse(me);   // fresh trust score
    }

    /** Generates a 6-character code so the logged-in vendor can link their Telegram chat. */
    @PostMapping("/telegram-link-code")
    public Map<String, Object> generateTelegramLinkCode(@RequestAttribute("vendor") Vendor me) {
        Vendor v = vendors.findById(me.id).orElse(me);
        if (v.telegramLinkCode == null || v.telegramLinkCode.isBlank()) {
            v.telegramLinkCode = "CG-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            vendors.save(v);
        }
        return Map.of(
                "code", v.telegramLinkCode,
                "botUsername", telegram.getBotUsername(),
                "botConfigured", telegram.isConfigured(),
                "linked", v.telegramChatId != null
        );
    }

    @PostMapping("/telegram-unlink")
    public Vendor unlinkTelegram(@RequestAttribute("vendor") Vendor me) {
        Vendor v = vendors.findById(me.id).orElse(me);
        v.telegramChatId = null;
        v.telegramLinkCode = null;
        return vendors.save(v);
    }
}
