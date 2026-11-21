package com.creditguard;

import com.creditguard.Dtos.InvoiceView;
import com.creditguard.Dtos.RiskResult;
import com.creditguard.Dtos.SettleResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full Telegram Bot integration for Credit-Guard:
 * - Account linking (/link CODE)
 * - Upload credit invoices via photo/PDF (/newbill)
 * - Buyer Confirm / Dispute inline buttons
 * - Buyer Bank Slip upload & AI verification (/pay or inline button)
 * - Buyer credit trust score & AI risk check (/risk, /myscore, /mybills, /vendors)
 * - Real-time push notifications for sellers & buyers
 */
@Service
public class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    @Value("${app.telegram.bot-username:CreditGuardBot}")
    private String botUsername;

    private final VendorRepository vendors;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;
    private final RiskService riskService;
    private final RestClient rest = RestClient.create();

    private long lastUpdateId = 0;

    private enum Step {
        AWAITING_NEWBILL_BUYER_DAYS,
        AWAITING_NEWBILL_FILE,
        AWAITING_SETTLE_SLIP
    }

    private static class BotSession {
        Step step;
        Long targetBuyerId;
        int creditDays = 30;
        Long targetInvoiceId;
    }

    private final Map<Long, BotSession> sessions = new ConcurrentHashMap<>();

    public TelegramBotService(VendorRepository vendors,
                              InvoiceRepository invoices,
                              @Lazy InvoiceService invoiceService,
                              @Lazy RiskService riskService) {
        this.vendors = vendors;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
        this.riskService = riskService;
    }

    public boolean isConfigured() {
        return botToken != null && !botToken.isBlank()
                && !botToken.startsWith("PASTE_")
                && botToken.contains(":");
    }

    public String getBotUsername() {
        return botUsername;
    }

    // --------------------------------------------------------- Polling loop

    @Scheduled(fixedDelay = 2000)
    public void pollUpdates() {
        if (!isConfigured()) return;

        try {
            String url = "https://api.telegram.org/bot" + botToken
                    + "/getUpdates?timeout=1&offset=" + (lastUpdateId + 1);
            JsonNode res = rest.get().uri(url).retrieve().body(JsonNode.class);
            if (res == null || !res.path("ok").asBoolean(false)) return;

            for (JsonNode update : res.path("result")) {
                long updateId = update.path("update_id").asLong();
                if (updateId > lastUpdateId) {
                    lastUpdateId = updateId;
                }
                handleUpdate(update);
            }
        } catch (Exception e) {
            log.debug("Telegram poll error: {}", e.getMessage());
        }
    }

    private void handleUpdate(JsonNode update) {
        try {
            if (update.has("callback_query")) {
                handleCallback(update.path("callback_query"));
            } else if (update.has("message")) {
                handleMessage(update.path("message"));
            }
        } catch (Exception e) {
            log.error("Error handling Telegram update", e);
        }
    }

    // --------------------------------------------------------- Incoming Message

    private void handleMessage(JsonNode msg) {
        long chatId = msg.path("chat").path("id").asLong(0);
        if (chatId == 0) return;

        String text = msg.path("text").asText("").trim();
        Optional<Vendor> linkedOpt = vendors.findByTelegramChatId(chatId);

        // 1. Handle /start and /link even if not linked yet
        if (text.startsWith("/start")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length == 2 && !parts[1].isBlank()) {
                linkAccount(chatId, parts[1].trim());
                return;
            }
            if (linkedOpt.isPresent()) {
                Vendor v = linkedOpt.get();
                sendText(chatId, "✅ *Credit-Guard (ණය-රක්ෂක) Bot*\n\n"
                        + "ආයුබෝවන් *" + v.businessName + "* (" + v.vendorCode + ")!\n"
                        + "ඔබගේ Trust Score: *" + v.trustScore + " / 100*\n\n"
                        + getHelpMenu());
            } else {
                sendText(chatId, "👋 *Credit-Guard (ණය-රක්ෂක) වෙත සාදරයෙන් පිළිගනිමු!*\n\n"
                        + "ඔබගේ ගිණුම සම්බන්ධ කිරීමට Web Portal එකට log වී එහි ඇති Link Code එක පහත පරිදි එවන්න:\n"
                        + "`/link CG-XXXXXX`");
            }
            return;
        }

        if (text.startsWith("/link")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length < 2 || parts[1].isBlank()) {
                sendText(chatId, "⚠️ කරුණාකර ඔබගේ Link Code එක ඇතුළත් කරන්න.\nඋදා: `/link CG-123456`");
                return;
            }
            linkAccount(chatId, parts[1].trim());
            return;
        }

        if (text.equalsIgnoreCase("/help")) {
            sendText(chatId, getHelpMenu());
            return;
        }

        // Require linked & approved vendor for all other actions
        if (linkedOpt.isEmpty()) {
            sendText(chatId, "🔒 ඔබගේ Telegram ගිණුම තවම සම්බන්ධ කර නැත.\n"
                    + "Web Portal එකෙන් ලබාගත් code එක `/link CG-XXXXXX` ලෙස එවන්න.");
            return;
        }

        Vendor me = linkedOpt.get();
        if (me.status != Vendor.Status.APPROVED) {
            sendText(chatId, "⛔ ඔබගේ ගිණුම දැනට ක්‍රියාත්මක නැත (Status: " + me.status + ").");
            return;
        }

        if (text.equalsIgnoreCase("/cancel")) {
            sessions.remove(chatId);
            sendText(chatId, "✅ දැනට කරමින් සිටි ක්‍රියාවලිය අවලංගු කරන ලදී.");
            return;
        }

        // 2. Commands for linked vendors
        if (text.equalsIgnoreCase("/myscore")) {
            List<Invoice> asBuyer = invoices.findByBuyerId(me.id);
            long active = asBuyer.stream().filter(i -> i.status == Invoice.Status.ACTIVE_DEBT).count();
            long overdue = asBuyer.stream().filter(i -> i.status == Invoice.Status.OVERDUE).count();
            long settled = asBuyer.stream().filter(i -> i.status == Invoice.Status.SETTLED_AI_VERIFIED).count();
            sendText(chatId, "📊 *ඔබගේ Credit-Guard තත්ත්වය*\n\n"
                    + "• ව්‍යාපාරය: *" + me.businessName + "* (" + me.vendorCode + ")\n"
                    + "• Trust Score: *" + me.trustScore + " / 100*\n"
                    + "• ගෙවීමට ඇති බිල්පත් (Active): *" + active + "*\n"
                    + "• කල් ඉකුත් වූ බිල්පත් (Overdue): *" + overdue + "*\n"
                    + "• ගෙවා අවසන් කළ බිල්පත් (Settled): *" + settled + "*");
            return;
        }

        if (text.equalsIgnoreCase("/vendors")) {
            List<Vendor> list = vendors.findByStatus(Vendor.Status.APPROVED).stream()
                    .filter(v -> !v.id.equals(me.id)).toList();
            if (list.isEmpty()) {
                sendText(chatId, "වෙනත් අනුමත වෙළඳුන් (Vendors) තවම නැත.");
                return;
            }
            StringBuilder sb = new StringBuilder("🏪 *ලියාපදිංචි වෙළඳුන් (Approved Vendors):*\n\n");
            for (Vendor v : list) {
                sb.append("• `").append(v.vendorCode).append("` — ").append(v.businessName).append("\n");
            }
            sb.append("\n💡 Buyer කෙනෙක්ගේ score බැලීමට: `/risk V0002 50000`");
            sendText(chatId, sb.toString());
            return;
        }

        if (text.startsWith("/risk") || text.startsWith("/score")) {
            handleRiskCommand(chatId, text);
            return;
        }

        if (text.equalsIgnoreCase("/mybills")) {
            handleMyBills(chatId, me);
            return;
        }

        if (text.startsWith("/newbill")) {
            BotSession s = new BotSession();
            s.step = Step.AWAITING_NEWBILL_BUYER_DAYS;
            sessions.put(chatId, s);

            List<Vendor> others = vendors.findByStatus(Vendor.Status.APPROVED).stream()
                    .filter(v -> !v.id.equals(me.id)).toList();
            StringBuilder sb = new StringBuilder("🧾 *අලුත් ණය බිල්පතක් ඇතුළත් කිරීම (New Credit Bill)*\n\n");
            sb.append("කරුණාකර *Buyer ගේ Vendor Code එක* සහ *ණය දින ගණන (Credit Days)* එවන්න.\n");
            sb.append("උදාහරණ: `V0002 30`\n\n");
            if (!others.isEmpty()) {
                sb.append("*දැනට සිටින Buyers:*\n");
                for (Vendor v : others) {
                    sb.append("• `").append(v.vendorCode).append("` - ").append(v.businessName).append("\n");
                }
            }
            sb.append("\n_(අවලංගු කිරීමට /cancel එවන්න)_");
            sendText(chatId, sb.toString());
            return;
        }

        if (text.startsWith("/pay")) {
            String[] parts = text.split("\\s+");
            if (parts.length < 2) {
                sendText(chatId, "💳 ගෙවීමක් කිරීමට Invoice ID එක හෝ අංකය සමඟ `/pay <id>` එවන්න.\n"
                        + "නැතහොත් `/mybills` මගින් ඔබගේ බිල්පත් ලැයිස්තුව බලන්න.");
                return;
            }
            startSlipUploadSession(chatId, me, parts[1].trim());
            return;
        }

        // 3. Handle Multi-Step Session States (text or file uploads)
        BotSession session = sessions.get(chatId);
        if (session != null) {
            handleSessionInput(chatId, me, session, msg, text);
            return;
        }

        sendText(chatId, "❓ විධානය හඳුනාගත නොහැක.\n\n" + getHelpMenu());
    }

    // --------------------------------------------------------- Account Linking

    private void linkAccount(long chatId, String code) {
        Optional<Vendor> match = vendors.findByTelegramLinkCode(code.toUpperCase());
        if (match.isEmpty()) {
            sendText(chatId, "❌ වලංගු නොවන Link Code එකක් (`" + code + "`). කරුණාකර Web Portal එකෙන් නව code එකක් ලබාගන්න.");
            return;
        }
        // Clear this chatId from any previously linked vendor
        vendors.findByTelegramChatId(chatId).ifPresent(old -> {
            if (!old.id.equals(match.get().id)) {
                old.telegramChatId = null;
                vendors.save(old);
            }
        });

        Vendor v = match.get();
        v.telegramChatId = chatId;
        v.telegramLinkCode = null;
        vendors.save(v);

        sendText(chatId, "🎉 *සාර්ථකව සම්බන්ධ කරන ලදී!*\n\n"
                + "• ව්‍යාපාරය: *" + v.businessName + "* (" + v.vendorCode + ")\n"
                + "• Trust Score: *" + v.trustScore + " / 100*\n\n"
                + "දැන් ඔබට බිල්පත් upload කිරීමට, බිල්පත් තහවුරු කිරීමට (Confirm/Dispute), රිසිට්පත් (Bank Slips) upload කිරීමට සහ ගනුදෙනුකරුවන්ගේ Risk Score පරීක්ෂා කිරීමට මෙම Bot භාවිතා කළ හැක.\n\n"
                + getHelpMenu());
    }

    // --------------------------------------------------------- Risk / Score Check

    private void handleRiskCommand(long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length < 2) {
            sendText(chatId, "⚠️ කරුණාකර Buyer ගේ Vendor Code එක ඇතුළත් කරන්න.\n"
                    + "උදාහරණ: `/risk V0002` හෝ `/risk V0002 500000`\n"
                    + "(වෙළඳුන්ගේ කේත බැලීමට `/vendors` භාවිතා කරන්න)");
            return;
        }

        String code = parts[1].trim();
        Optional<Vendor> targetOpt = vendors.findByVendorCodeIgnoreCase(code);
        if (targetOpt.isEmpty()) {
            // Also try numeric ID
            try {
                targetOpt = vendors.findById(Long.parseLong(code.replaceAll("[^0-9]", "")));
            } catch (Exception ignored) {}
        }

        if (targetOpt.isEmpty() || targetOpt.get().status != Vendor.Status.APPROVED) {
            sendText(chatId, "❌ `" + code + "` නමින් අනුමත වෙළඳ ගිණුමක් හමු නොවීය. `/vendors` මගින් ලැයිස්තුව පරීක්ෂා කරන්න.");
            return;
        }

        BigDecimal reqAmount = null;
        if (parts.length >= 3) {
            try {
                reqAmount = new BigDecimal(parts[2].replaceAll("[^0-9.]", ""));
            } catch (Exception ignored) {}
        }

        Vendor target = targetOpt.get();
        sendText(chatId, "🔍 *" + target.businessName + "* (" + target.vendorCode + ") ගේ අවදානම් වාර්තාව (AI Risk Analysis) සකසමින් පවතී...");

        try {
            RiskResult res = riskService.check(target.id, reqAmount);
            String badge = switch (res.riskLevel()) {
                case "HIGH" -> "🔴 HIGH RISK (අවදානම ඉතා වැඩියි)";
                case "MEDIUM" -> "🟡 MEDIUM RISK (මධ්‍යම අවදානම)";
                default -> "🟢 LOW RISK (අවදානම අඩුයි)";
            };

            sendText(chatId, "🛡️ *Credit-Guard Buyer Risk Report*\n\n"
                    + "• Buyer: *" + res.vendorName() + "* (" + target.vendorCode + ")\n"
                    + "• Risk Level: *" + badge + "*\n"
                    + "• Trust Score: *" + res.trustScore() + " / 100*\n"
                    + "• Overdue Debts: *" + res.overdueCount() + "*\n\n"
                    + "🤖 *Gemini AI නිර්දේශය:*\n" + res.recommendation());
        } catch (Exception e) {
            sendText(chatId, "❌ Risk පරීක්ෂාව අසාර්ථක විය: " + e.getMessage());
        }
    }

    // --------------------------------------------------------- My Bills

    private void handleMyBills(long chatId, Vendor me) {
        List<InvoiceView> list = invoiceService.mine(me);
        if (list.isEmpty()) {
            sendText(chatId, "📭 ඔබට අදාළ බිල්පත් කිසිවක් තවම නැත.");
            return;
        }

        StringBuilder sb = new StringBuilder("📋 *ඔබගේ මෑත කාලීන බිල්පත් (My Invoices):*\n\n");
        int count = 0;
        for (InvoiceView i : list) {
            if (count++ >= 10) break;
            boolean iAmBuyer = i.buyerId().equals(me.id);
            sb.append("• *#").append(i.id()).append("* (`").append(i.invoiceNo()).append("`) — ")
              .append(iAmBuyer ? "Buyer (ගැනුම්කරු)" : "Seller (විකුණුම්කරු)").append("\n")
              .append("  ාර්ශවය: ").append(iAmBuyer ? i.sellerName() : i.buyerName()).append("\n")
              .append("  මුදල: *Rs. ").append(i.amount()).append("* | Due: ").append(i.dueDate()).append("\n")
              .append("  තත්ත්වය: `").append(i.status()).append("`\n");
            if (iAmBuyer && (i.status() == Invoice.Status.ACTIVE_DEBT || i.status() == Invoice.Status.OVERDUE)) {
                sb.append("  👉 ගෙවීමේ රිසිට්පත upload කිරීමට: `/pay ").append(i.id()).append("`\n");
            }
            sb.append("\n");
        }
        sendText(chatId, sb.toString());
    }

    // --------------------------------------------------------- Multi-Step Sessions (Upload Bill & Upload Slip)

    private void startSlipUploadSession(long chatId, Vendor buyer, String idOrNo) {
        Optional<Invoice> invOpt = Optional.empty();
        try {
            long id = Long.parseLong(idOrNo.replaceAll("[^0-9]", ""));
            invOpt = invoices.findById(id);
        } catch (Exception ignored) {}

        if (invOpt.isEmpty()) {
            invOpt = invoices.findBySellerIdOrBuyerIdOrderByIdDesc(buyer.id, buyer.id).stream()
                    .filter(i -> i.invoiceNo.equalsIgnoreCase(idOrNo))
                    .findFirst();
        }

        if (invOpt.isEmpty() || !invOpt.get().buyerId.equals(buyer.id)) {
            sendText(chatId, "❌ අදාළ බිල්පත හමු නොවීය.");
            return;
        }

        Invoice inv = invOpt.get();
        if (inv.status != Invoice.Status.ACTIVE_DEBT && inv.status != Invoice.Status.OVERDUE) {
            sendText(chatId, "⚠️ මෙම බිල්පත දැනට ගෙවිය හැකි තත්ත්වයේ නැත (Status: `" + inv.status + "`).");
            return;
        }

        BotSession s = new BotSession();
        s.step = Step.AWAITING_SETTLE_SLIP;
        s.targetInvoiceId = inv.id;
        sessions.put(chatId, s);

        sendText(chatId, "💳 *බිල්පත #" + inv.id + " (" + inv.invoiceNo + ") - Rs. " + inv.amount + " ගෙවීම*\n\n"
                + "කරුණාකර ඔබගේ *Bank Transfer Slip / Receipt එකේ Photo එකක් හෝ PDF එකක්* දැන් මෙතැනට එවන්න.\n"
                + "_(අවලංගු කිරීමට /cancel එවන්න)_");
    }

    private void handleSessionInput(long chatId, Vendor me, BotSession session, JsonNode msg, String text) {
        if (session.step == Step.AWAITING_NEWBILL_BUYER_DAYS) {
            String[] parts = text.split("\\s+");
            if (parts.length < 1 || parts[0].isBlank()) {
                sendText(chatId, "⚠️ කරුණාකර Buyer ගේ Vendor Code එක සහ දින ගණන එවන්න (උදා: `V0002 30`).");
                return;
            }
            String code = parts[0].trim();
            Optional<Vendor> buyerOpt = vendors.findByVendorCodeIgnoreCase(code);
            if (buyerOpt.isEmpty() || buyerOpt.get().status != Vendor.Status.APPROVED) {
                sendText(chatId, "❌ `" + code + "` නමින් අනුමත Buyer කෙනෙක් හමු නොවීය. නැවත උත්සාහ කරන්න.");
                return;
            }
            if (buyerOpt.get().id.equals(me.id)) {
                sendText(chatId, "❌ ඔබටම බිල්පතක් නිකුත් කළ නොහැක.");
                return;
            }
            int days = 30;
            if (parts.length >= 2) {
                try {
                    days = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    sendText(chatId, "⚠️ දින ගණන1-365 අතර ඉලක්කමක් විය යුතුය.");
                    return;
                }
            }
            session.targetBuyerId = buyerOpt.get().id;
            session.creditDays = days;
            session.step = Step.AWAITING_NEWBILL_FILE;
            sendText(chatId, "📸 Buyer: *" + buyerOpt.get().businessName + "* (" + buyerOpt.get().vendorCode + ") | Credit Days: *" + days + "*\n\n"
                    + "දැන් කරුණාකර *ණය බිල්පතේ (Credit Invoice) Photo එකක් හෝ PDF එකක්* මෙතැනට upload කරන්න.");
            return;
        }

        if (session.step == Step.AWAITING_NEWBILL_FILE) {
            MultipartFile file = extractFileFromMessage(msg);
            if (file == null) {
                sendText(chatId, "⚠️ කරුණාකර බිල්පතේ *Photo එකක්* හෝ *PDF file එකක්* එවන්න (හෝ `/cancel` මගින් ඉවත් වන්න).");
                return;
            }
            sessions.remove(chatId);
            sendText(chatId, "⏳ *Gemini AI මගින් ඔබගේ බිල්පත පරීක්ෂා කරමින් පවතී...* කරුණාකර මදක් රැඳී සිටින්න.");
            try {
                InvoiceView created = invoiceService.create(me, session.targetBuyerId, session.creditDays, file);
                sendText(chatId, "✅ *බිල්පත සාර්ථකව ඇතුළත් කරන ලදී!*\n\n"
                        + "• Invoice No: `" + created.invoiceNo() + "` (#" + created.id() + ")\n"
                        + "• Buyer: *" + created.buyerName() + "*\n"
                        + "• Amount: *Rs. " + created.amount() + "*\n"
                        + "• Due Date: *" + created.dueDate() + "*\n"
                        + "• Status: `" + created.status() + "`\n\n"
                        + "🤖 *AI Summary:*\n" + created.aiSummary() + "\n\n"
                        + "📨 Buyer වෙත තහවුරු කිරීමේ පණිවිඩය (Confirm/Dispute) යවන ලදී.");
            } catch (ResponseStatusException e) {
                sendText(chatId, "❌ බිල්පත ඇතුළත් කිරීම අසාර්ථකයි: " + e.getReason());
            } catch (Exception e) {
                sendText(chatId, "❌ දෝෂයක් ඇතිවිය: " + e.getMessage());
            }
            return;
        }

        if (session.step == Step.AWAITING_SETTLE_SLIP) {
            MultipartFile slip = extractFileFromMessage(msg);
            if (slip == null) {
                sendText(chatId, "⚠️ කරුණාකර Bank Slip එකේ *Photo එකක්* හෝ *PDF file එකක්* එවන්න (හෝ `/cancel` එවන්න).");
                return;
            }
            Long invoiceId = session.targetInvoiceId;
            sessions.remove(chatId);
            sendText(chatId, "⏳ *Gemini AI මගින් ඔබගේ Bank Slip එක පරීක්ෂා කරමින් පවතී (4-Layer Forensic Check)...*");
            try {
                SettleResult res = invoiceService.settle(me, invoiceId, slip);
                StringBuilder sb = new StringBuilder();
                if (res.passed()) {
                    sb.append("🎉 *ගෙවීම AI මගින් තහවුරු කරන ලදී! (SETTLED_AI_VERIFIED)*\n\n");
                } else {
                    sb.append("❌ *Bank Slip එක ප්‍රතික්ෂේප විය!*\n\n");
                }
                sb.append("*පරීක්ෂණ ප්‍රතිඵල (Forensic Checks):*\n");
                for (String c : res.checks()) {
                    sb.append(c.startsWith("PASS") ? "✅ " : "❌ ").append(c).append("\n");
                }
                sendText(chatId, sb.toString());
            } catch (ResponseStatusException e) {
                sendText(chatId, "❌ ගෙවීම තහවුරු කිරීම අසාර්ථකයි: " + e.getReason());
            } catch (Exception e) {
                sendText(chatId, "❌ දෝෂයක් ඇතිවිය: " + e.getMessage());
            }
        }
    }

    // --------------------------------------------------------- Inline Button Callbacks

    private void handleCallback(JsonNode cb) {
        String cbId = cb.path("id").asText("");
        String data = cb.path("data").asText("");
        long chatId = cb.path("message").path("chat").path("id").asLong(0);

        Optional<Vendor> vendorOpt = vendors.findByTelegramChatId(chatId);
        if (vendorOpt.isEmpty()) {
            answerCallback(cbId, "කරුණාකර පළමුව ඔබගේ ගිණුම /link කරන්න.");
            return;
        }
        Vendor me = vendorOpt.get();

        try {
            if (data.startsWith("CONFIRM:")) {
                long invId = Long.parseLong(data.substring("CONFIRM:".length()));
                InvoiceView view = invoiceService.confirm(me, invId);
                answerCallback(cbId, "✅ බිල්පත තහවුරු කරන ලදී!");
                sendPaidButtonMessage(chatId, view);
            } else if (data.startsWith("DISPUTE:")) {
                long invId = Long.parseLong(data.substring("DISPUTE:".length()));
                InvoiceView view = invoiceService.dispute(me, invId);
                answerCallback(cbId, "⚠️ බිල්පත Dispute කරන ලදී.");
                sendText(chatId, "⚠️ *බිල්පත #" + view.id() + " (" + view.invoiceNo() + ") ඔබ විසින් ප්‍රතික්ෂේප (Dispute) කරන ලදී.*\n"
                        + "මෙය සංගමයේ Admin Dispute Desk වෙත යොමු කර ඇත.");
            } else if (data.startsWith("PAY:")) {
                String invIdStr = data.substring("PAY:".length());
                answerCallback(cbId, "Bank Slip එක upload කරන්න");
                startSlipUploadSession(chatId, me, invIdStr);
            }
        } catch (ResponseStatusException e) {
            answerCallback(cbId, "Error: " + e.getReason());
            sendText(chatId, "⚠️ " + e.getReason());
        } catch (Exception e) {
            answerCallback(cbId, "දෝෂයක් ඇතිවිය");
        }
    }

    private void sendPaidButtonMessage(long buyerChatId, InvoiceView view) {
        String text = "✅ *ඔබ බිල්පත #" + view.id() + " (" + view.invoiceNo() + ") පිළිගන්නා ලදී (ACTIVE_DEBT).*\n\n"
                + "• Seller: *" + view.sellerName() + "*\n"
                + "• Amount: *Rs. " + view.amount() + "*\n"
                + "• Due Date: *" + view.dueDate() + "*\n\n"
                + "ඔබ මුදල් ගෙවූ පසු පහත බොත්තම ඔබා Bank Slip එක upload කරන්න:";

        List<List<Map<String, String>>> keyboard = List.of(
                List.of(Map.of("text", "💳 Upload Bank Slip (ගෙවීම් රිසිට්පත එවන්න)", "callback_data", "PAY:" + view.id()))
        );
        sendWithInlineKeyboard(buyerChatId, text, keyboard);
    }

    // --------------------------------------------------------- Outgoing Notifications (Called by Services)

    public void notifyVendorApproved(Vendor v) {
        if (!isConfigured() || v.telegramChatId == null) return;
        sendText(v.telegramChatId, "🎉 *ඔබගේ Credit-Guard ගිණුම Admin විසින් අනුමත කරන ලදී!*\n"
                + "ඔබගේ Vendor Code එක: `" + v.vendorCode + "`");
    }

    public void notifyNewInvoiceToBuyer(InvoiceView view) {
        if (!isConfigured()) return;
        vendors.findById(view.buyerId()).ifPresent(buyer -> {
            if (buyer.telegramChatId == null) return;

            String text = "🧾 *අලුත් ණය බිල්පතක් ලැබී ඇත (New Credit Invoice)*\n\n"
                    + "• Invoice: *" + view.invoiceNo() + "* (#" + view.id() + ")\n"
                    + "• Seller: *" + view.sellerName() + "*\n"
                    + "• Amount: *Rs. " + view.amount() + "*\n"
                    + "• Due Date: *" + view.dueDate() + "*\n\n"
                    + "🤖 *AI පරීක්ෂාව:*\n" + view.aiSummary() + "\n\n"
                    + "කරුණාකර මෙම බිල්පත නිවැරදි නම් පිළිගන්න (Confirm) හෝ ගැටලුවක් ඇත්නම් ප්‍රතික්ෂේප කරන්න (Dispute):";

            List<List<Map<String, String>>> keyboard = List.of(
                    List.of(
                            Map.of("text", "✅ Confirm (පිළිගන්න)", "callback_data", "CONFIRM:" + view.id()),
                            Map.of("text", "❌ Dispute (ප්‍රතික්ෂේප)", "callback_data", "DISPUTE:" + view.id())
                    )
            );
            sendWithInlineKeyboard(buyer.telegramChatId, text, keyboard);
        });
    }

    public void notifyInvoiceConfirmedToSeller(InvoiceView view) {
        if (!isConfigured()) return;
        vendors.findById(view.sellerId()).ifPresent(seller -> {
            if (seller.telegramChatId == null) return;
            sendText(seller.telegramChatId, "✅ *බිල්පත තහවුරු කරන ලදී (ACTIVE_DEBT)!*\n\n"
                    + "• Invoice: `" + view.invoiceNo() + "` (#" + view.id() + ")\n"
                    + "• Buyer: *" + view.buyerName() + "*\n"
                    + "• Amount: *Rs. " + view.amount() + "*\n"
                    + "• Due Date: *" + view.dueDate() + "*");
        });
    }

    public void notifyInvoiceDisputedToSeller(InvoiceView view) {
        if (!isConfigured()) return;
        vendors.findById(view.sellerId()).ifPresent(seller -> {
            if (seller.telegramChatId == null) return;
            sendText(seller.telegramChatId, "⚠️ *බිල්පත ප්‍රතික්ෂේප කරන ලදී (DISPUTED)!*\n\n"
                    + "• Invoice: `" + view.invoiceNo() + "` (#" + view.id() + ")\n"
                    + "• Buyer: *" + view.buyerName() + "*\n"
                    + "• Amount: *Rs. " + view.amount() + "*\n"
                    + "මෙය Admin Dispute Desk වෙත යොමු කර ඇත.");
        });
    }

    public void notifyInvoiceSettled(InvoiceView view, Vendor buyer) {
        if (!isConfigured()) return;
        vendors.findById(view.sellerId()).ifPresent(seller -> {
            if (seller.telegramChatId != null) {
                sendText(seller.telegramChatId, "💰 *ගෙවීම ලැබී AI මගින් තහවුරු කරන ලදී (SETTLED_AI_VERIFIED)!*\n\n"
                        + "• Invoice: `" + view.invoiceNo() + "` (#" + view.id() + ")\n"
                        + "• Buyer: *" + view.buyerName() + "*\n"
                        + "• Amount: *Rs. " + view.amount() + "*");
            }
        });
        if (buyer.telegramChatId != null) {
            sendText(buyer.telegramChatId, "✅ *ඔබගේ #" + view.id() + " (" + view.invoiceNo() + ") බිල්පතේ ගෙවීම සම්පූර්ණයි!*\n"
                    + "ඔබගේ නව Trust Score එක: *" + buyer.trustScore + " / 100*");
        }
    }

    public void notifyOverdue(Invoice inv, Vendor buyer, Vendor seller) {
        if (!isConfigured()) return;
        if (buyer != null && buyer.telegramChatId != null) {
            String text = "🚨 *ණය බිල්පත කල් ඉකුත් වී ඇත (OVERDUE ALERT)!*\n\n"
                    + "• Invoice: `" + inv.invoiceNo + "` (#" + inv.id + ")\n"
                    + "• Seller: *" + (seller != null ? seller.businessName : "?") + "*\n"
                    + "• Amount: *Rs. " + inv.amount + "*\n"
                    + "• Due Date: *" + inv.dueDate + "*\n\n"
                    + "⚠️ ඔබගේ Trust Score එක *" + buyer.trustScore + " / 100* දක්වා පහත වැටී ඇත.\n"
                    + "වහාම ගෙවා Bank Slip එක upload කරන්න:";
            List<List<Map<String, String>>> keyboard = List.of(
                    List.of(Map.of("text", "💳 Upload Bank Slip Now", "callback_data", "PAY:" + inv.id))
            );
            sendWithInlineKeyboard(buyer.telegramChatId, text, keyboard);
        }
        if (seller != null && seller.telegramChatId != null) {
            sendText(seller.telegramChatId, "⏰ *කල් ඉකුත් වූ බිල්පතක් (OVERDUE):*\n"
                    + "• Invoice: `" + inv.invoiceNo + "` (#" + inv.id + ")\n"
                    + "• Buyer: *" + (buyer != null ? buyer.businessName : "?") + "*\n"
                    + "• Amount: *Rs. " + inv.amount + "*");
        }
    }

    // --------------------------------------------------------- Telegram REST Helpers

    private MultipartFile extractFileFromMessage(JsonNode msg) {
        String fileId = null;
        String fileName = "upload.jpg";
        String mimeType = "image/jpeg";

        if (msg.has("photo") && msg.path("photo").isArray() && !msg.path("photo").isEmpty()) {
            JsonNode photos = msg.path("photo");
            JsonNode largest = photos.get(photos.size() - 1);
            fileId = largest.path("file_id").asText(null);
            fileName = "telegram_photo.jpg";
            mimeType = "image/jpeg";
        } else if (msg.has("document")) {
            JsonNode doc = msg.path("document");
            fileId = doc.path("file_id").asText(null);
            fileName = doc.path("file_name").asText("document.pdf");
            mimeType = doc.path("mime_type").asText("application/pdf");
        }

        if (fileId == null) return null;

        try {
            String getFileUrl = "https://api.telegram.org/bot" + botToken + "/getFile?file_id=" + fileId;
            JsonNode fileRes = rest.get().uri(getFileUrl).retrieve().body(JsonNode.class);
            String filePath = fileRes == null ? "" : fileRes.path("result").path("file_path").asText("");
            if (filePath.isBlank()) return null;

            String downloadUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;
            byte[] bytes = rest.get().uri(downloadUrl).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) return null;

            return new ByteArrayMultipartFile(bytes, fileName, mimeType);
        } catch (Exception e) {
            log.error("Failed to download file from Telegram", e);
            return null;
        }
    }

    public void sendText(long chatId, String text) {
        if (!isConfigured()) return;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");
            rest.post()
                    .uri("https://api.telegram.org/bot" + botToken + "/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // Fallback without Markdown if special characters broke parsing
            try {
                Map<String, Object> body = Map.of("chat_id", chatId, "text", text);
                rest.post()
                        .uri("https://api.telegram.org/bot" + botToken + "/sendMessage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception ex) {
                log.warn("Could not send Telegram message to {}: {}", chatId, ex.getMessage());
            }
        }
    }

    public void sendWithInlineKeyboard(long chatId, String text, List<List<Map<String, String>>> inlineKeyboard) {
        if (!isConfigured()) return;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("reply_markup", Map.of("inline_keyboard", inlineKeyboard));
            rest.post()
                    .uri("https://api.telegram.org/bot" + botToken + "/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Could not send Telegram inline keyboard message to {}: {}", chatId, e.getMessage());
        }
    }

    private void answerCallback(String callbackQueryId, String text) {
        if (!isConfigured() || callbackQueryId.isBlank()) return;
        try {
            rest.post()
                    .uri("https://api.telegram.org/bot" + botToken + "/answerCallbackQuery")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("callback_query_id", callbackQueryId, "text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ignored) {}
    }

    private String getHelpMenu() {
        return "*ලබාගත හැකි Telegram විධාන (Commands):*\n"
                + "• `/newbill` — අලුත් ණය බිල්පතක් (Photo/PDF) upload කිරීමට\n"
                + "• `/mybills` — ඔබගේ බිල්පත් බැලීමට සහ ගෙවීම් කිරීමට\n"
                + "• `/pay <id>` — බිල්පතකට අදාළ Bank Slip එක upload කිරීමට\n"
                + "• `/risk <VendorCode> [Amount]` — Buyer කෙනෙක්ගේ Trust Score සහ AI උපදෙස් බැලීමට (උදා: `/risk V0002 50000`)\n"
                + "• `/vendors` — ලියාපදිංචි වෙළඳුන්ගේ කේත (Vendor Codes) බැලීමට\n"
                + "• `/myscore` — ඔබගේ Trust Score සහ සාරාංශය බැලීමට\n"
                + "• `/cancel` — දැනට කරමින් සිටින ක්‍රියාවලිය නැවැත්වීමට";
    }
}
