package com.bruno.kota.services;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.bruno.kota.entities.Bid;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationItem;
import com.bruno.kota.entities.Representative;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// E-mail é sempre "melhor esforço" — nunca deve derrubar a publicação/fechamento da
// cotação por causa de SMTP fora do ar, credencial errada, ou representante sem e-mail
// válido. Por isso todo envio fica dentro de try/catch que só loga, nunca propaga —
// quem chama esse service (QuotationService) não precisa (nem deve) se preocupar com
// falha de e-mail.
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from}")
    private String fromAddress;

    private static final String APP_URL = "https://easykota.com.br";
    private static final String LOGO_URL = APP_URL + "/img/kota-wordmark.png";
    private static final String BRAND_COLOR = "#2f6fed";

    public void notifyQuotationPublished(Quotation quotation, List<Representative> eligibleReps) {
        String subject = "Nova cotação disponível: " + quotation.getName();
        String link = APP_URL + "/representante.html?cotacao=" + quotation.getId();

        for (Representative rep : eligibleReps) {
            StringBuilder content = new StringBuilder();
            content.append("<p style=\"margin:0 0 12px\">Olá, ").append(escapeHtml(firstName(rep.getName()))).append("!</p>");
            content.append("<p style=\"margin:0 0 12px\">Uma nova cotação está disponível pra você enviar seus preços:</p>");
            content.append("<p style=\"margin:0 0 12px; font-size:16px\"><strong>").append(escapeHtml(quotation.getName())).append("</strong></p>");
            content.append("<p style=\"margin:0 0 20px\">Prazo para enviar preços: <strong>").append(fmtDate(quotation.getExpirationDate())).append("</strong></p>");
            content.append(buttonHtml(link, "Acessar cotação"));
            content.append("<p style=\"color:#888; font-size:12px; margin:20px 0 0\">Cotações enviadas depois do prazo não são consideradas.</p>");

            send(rep.getEmail(), subject, wrapInLayout(content.toString()));
        }
    }

    // Manda pra TODO representante elegível, tenha ele vencido algo ou não — quem não
    // venceu nada também precisa saber que fechou, senão fica esperando resposta de uma
    // cotação que já não existe mais pra responder.
    public void notifyQuotationClosed(Quotation quotation, Representative rep, List<QuotationItem> wonItems) {
        String subject = wonItems.isEmpty()
                ? "Resultado da cotação: " + quotation.getName()
                : "Você venceu itens na cotação: " + quotation.getName();

        StringBuilder content = new StringBuilder();
        content.append("<p style=\"margin:0 0 12px\">Olá, ").append(escapeHtml(firstName(rep.getName()))).append("!</p>");
        content.append("<p style=\"margin:0 0 16px\">A cotação <strong>").append(escapeHtml(quotation.getName()))
                .append("</strong> foi fechada.</p>");

        if (wonItems.isEmpty()) {
            content.append("<p style=\"margin:0\">Dessa vez você não venceu nenhum item — mas fica de olho nas próximas cotações disponíveis!</p>");
        } else {
            BigDecimal total = BigDecimal.ZERO;
            content.append("<p style=\"margin:0 0 12px\">Você venceu ").append(wonItems.size())
                    .append(wonItems.size() == 1 ? " item:</p>" : " itens:</p>");
            content.append("<table style=\"border-collapse:collapse; width:100%; font-size:13px\">");
            content.append("<tr style=\"background:#f0f4fb; text-align:left\">")
                    .append("<th style=\"padding:6px 10px; border-bottom:1px solid #ddd\">Produto</th>")
                    .append("<th style=\"padding:6px 10px; border-bottom:1px solid #ddd\">Qtd.</th>")
                    .append("<th style=\"padding:6px 10px; border-bottom:1px solid #ddd\">Preço (R$)</th>")
                    .append("<th style=\"padding:6px 10px; border-bottom:1px solid #ddd\">Subtotal (R$)</th></tr>");
            for (QuotationItem item : wonItems) {
                Bid winningBid = item.getWinningBid();
                BigDecimal subtotal = winningBid.getValue().multiply(item.getQuantity());
                total = total.add(subtotal);
                content.append("<tr>")
                        .append("<td style=\"padding:6px 10px; border-bottom:1px solid #eee\">").append(escapeHtml(item.getProduct().getName())).append("</td>")
                        .append("<td style=\"padding:6px 10px; border-bottom:1px solid #eee\">").append(item.getQuantity()).append("</td>")
                        .append("<td style=\"padding:6px 10px; border-bottom:1px solid #eee\">").append(winningBid.getValue()).append("</td>")
                        .append("<td style=\"padding:6px 10px; border-bottom:1px solid #eee\">").append(subtotal).append("</td>")
                        .append("</tr>");
            }
            content.append("</table>");
            content.append("<p style=\"margin-top:14px; font-size:15px\"><strong>Total: R$ ").append(total).append("</strong></p>");
        }

        send(rep.getEmail(), subject, wrapInLayout(content.toString()));
    }

    // Cartão branco centralizado com logo no topo e rodapé padrão — dá uma cara mais
    // profissional ao e-mail em vez de HTML solto direto no corpo da mensagem.
    private String wrapInLayout(String innerContent) {
        return "<div style=\"background:#f4f6fb; padding:32px 16px; font-family:Arial, Helvetica, sans-serif\">"
                + "<div style=\"max-width:520px; margin:0 auto; background:#fff; border-radius:12px; "
                + "padding:32px; box-shadow:0 1px 4px rgba(0,0,0,0.06)\">"
                + "<div style=\"text-align:center; margin-bottom:24px\">"
                + "<img src=\"" + LOGO_URL + "\" alt=\"easy Kota\" style=\"height:32px\"/>"
                + "</div>"
                + "<div style=\"color:#222; font-size:14px; line-height:1.5\">" + innerContent + "</div>"
                + "<hr style=\"border:none; border-top:1px solid #eee; margin:28px 0 16px\"/>"
                + "<p style=\"color:#aaa; font-size:11px; text-align:center; margin:0\">"
                + "Este é um e-mail automático, não responda.<br/>"
                + "<a href=\"" + APP_URL + "\" style=\"color:#aaa; text-decoration:underline\">easykota.com.br</a>"
                + "</p>"
                + "</div>"
                + "</div>";
    }

    private String buttonHtml(String link, String label) {
        return "<p style=\"margin:0\"><a href=\"" + link + "\" style=\"display:inline-block; background:" + BRAND_COLOR
                + "; color:#fff; padding:10px 20px; border-radius:8px; text-decoration:none; font-weight:bold\">"
                + escapeHtml(label) + "</a></p>";
    }

    private void send(String to, String subject, String htmlBody) {
        if (!mailEnabled) {
            log.info("Envio de e-mail desabilitado (app.mail.enabled=false) — pulando envio pra {}", to);
            return;
        }
        if (to == null || to.isBlank()) {
            log.warn("Representante sem e-mail cadastrado — não foi possível notificar.");
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Falha ao enviar e-mail pra {}: {}", to, e.getMessage());
        }
    }

    private String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "";
        return fullName.trim().split(" ")[0];
    }

    private String fmtDate(java.time.LocalDateTime date) {
        if (date == null) return "—";
        return date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm"));
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}