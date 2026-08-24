package com.bruno.kota.services;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// E-mail é sempre "melhor esforço" — nunca deve derrubar a publicação/fechamento da
// cotação por causa de SMTP fora do ar, credencial errada, ou representante sem e-mail
// válido. Por isso todo envio fica dentro de try/catch que só loga, nunca propaga —
// quem chama esse service (QuotationService) não precisa (nem deve) se preocupar com
// falha de e-mail.
//
// @Async nos 3 métodos de notificação: sem isso, publicar/fechar uma cotação ficava
// esperando o Brevo responder — um e-mail por vez, um representante de cada vez —
// antes da requisição HTTP devolver qualquer coisa pro navegador. @Async devolve a
// resposta pro admin na hora, e os e-mails saem numa thread separada, em segundo plano.
//
// IMPORTANTE: por causa disso, os métodos abaixo recebem RepContact/WonItemLine (records
// simples, só texto/número) em vez das entidades JPA (Quotation/Representative/
// QuotationItem) direto. Um método @Async roda numa thread própria, SEM a sessão do
// Hibernate da requisição original — se essa thread tentasse ler um campo "preguiçoso"
// (lazy) de uma entidade (tipo representative.getName() vindo de uma relação lazy, ou
// item.getProduct()), estouraria LazyInitializationException, e o e-mail simplesmente
// não sairia (silenciosamente, já que send() só loga erro). QuotationService já extrai
// esses valores simples ANTES de chamar esse service, ainda dentro da transação original
// — é lá, não aqui, que os dados "de verdade" saem do banco.
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    public record RepContact(String name, String email) {}

    public record WonItemLine(String productName, BigDecimal quantity, BigDecimal unitPrice) {}

    private final JavaMailSender mailSender;
    private final CompanySettingsService companySettingsService;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from}")
    private String fromAddress;

    private static final String APP_URL = "https://easykota.com.br";
    private static final String LOGO_URL = APP_URL + "/img/kota-wordmark.png";
    private static final String BRAND_COLOR = "#2f6fed";

    @Async
    public void notifyQuotationPublished(Long quotationId, String quotationName, LocalDateTime expirationDate, List<RepContact> eligibleReps) {
        String subject = "Nova cotação disponível: " + quotationName;
        String link = APP_URL + "/representante.html?cotacao=" + quotationId;

        for (RepContact rep : eligibleReps) {
            StringBuilder content = new StringBuilder();
            content.append("<p style=\"margin:0 0 12px\">Olá, ").append(escapeHtml(firstName(rep.name()))).append("!</p>");
            content.append("<p style=\"margin:0 0 12px\">Uma nova cotação está disponível pra você enviar seus preços:</p>");
            content.append("<p style=\"margin:0 0 12px; font-size:16px\"><strong>").append(escapeHtml(quotationName)).append("</strong></p>");
            content.append("<p style=\"margin:0 0 20px\">Prazo para enviar preços: <strong>").append(fmtDate(expirationDate)).append("</strong></p>");
            content.append(buttonHtml(link, "Acessar cotação"));
            content.append("<p style=\"color:#888; font-size:12px; margin:20px 0 0\">Cotações enviadas depois do prazo não são consideradas.</p>");

            send(rep.email(), subject, wrapInLayout(content.toString()));
        }
    }

    // Só vai pra quem ainda NÃO respondeu (nem lance, nem "Não Cotar") — quem já
    // respondeu não precisa de lembrete nenhum, já fez a parte dele. Disparado uma vez
    // só por cotação (QuotationReminderScheduler marca reminderSentAt depois de chamar
    // isso), então não vira spam mesmo que o job rode toda hora.
    @Async
    public void notifyDeadlineApproaching(Long quotationId, String quotationName, LocalDateTime expirationDate, List<RepContact> pendingReps) {
        String subject = "Prazo terminando: " + quotationName;
        String link = APP_URL + "/representante.html?cotacao=" + quotationId;

        for (RepContact rep : pendingReps) {
            StringBuilder content = new StringBuilder();
            content.append("<p style=\"margin:0 0 12px\">Olá, ").append(escapeHtml(firstName(rep.name()))).append("!</p>");
            content.append("<p style=\"margin:0 0 12px\">O prazo pra enviar seus preços na cotação abaixo está terminando:</p>");
            content.append("<p style=\"margin:0 0 12px; font-size:16px\"><strong>").append(escapeHtml(quotationName)).append("</strong></p>");
            content.append("<p style=\"margin:0 0 20px\">Prazo final: <strong>").append(fmtDate(expirationDate)).append("</strong></p>");
            content.append(buttonHtml(link, "Enviar meus preços"));
            content.append("<p style=\"color:#888; font-size:12px; margin:20px 0 0\">Se você não tem o que ofertar dessa vez, toque em \"Não Cotar\" na cotação — assim quem está comprando sabe que você viu.</p>");

            send(rep.email(), subject, wrapInLayout(content.toString()));
        }
    }

    // Disparado quando o admin prorroga o prazo de uma cotação já Disponível — vai pra
    // TODO representante elegível (não só quem ainda não respondeu, diferente do lembrete
    // de prazo terminando): quem já enviou preço também pode querer revisar/ajustar
    // agora que tem mais tempo.
    @Async
    public void notifyDeadlineExtended(Long quotationId, String quotationName, LocalDateTime newExpirationDate, List<RepContact> eligibleReps) {
        String subject = "Prazo prorrogado: " + quotationName;
        String link = APP_URL + "/representante.html?cotacao=" + quotationId;

        for (RepContact rep : eligibleReps) {
            StringBuilder content = new StringBuilder();
            content.append("<p style=\"margin:0 0 12px\">Olá, ").append(escapeHtml(firstName(rep.name()))).append("!</p>");
            content.append("<p style=\"margin:0 0 12px\">O prazo da cotação abaixo foi prorrogado:</p>");
            content.append("<p style=\"margin:0 0 12px; font-size:16px\"><strong>").append(escapeHtml(quotationName)).append("</strong></p>");
            content.append("<p style=\"margin:0 0 20px\">Novo prazo: <strong>").append(fmtDate(newExpirationDate)).append("</strong></p>");
            content.append(buttonHtml(link, "Acessar cotação"));

            send(rep.email(), subject, wrapInLayout(content.toString()));
        }
    }

    // Manda pra TODO representante elegível, tenha ele vencido algo ou não — quem não
    // venceu nada também precisa saber que fechou, senão fica esperando resposta de uma
    // cotação que já não existe mais pra responder. NÃO detalha o que foi ganho (nem
    // tabela, nem valores) — só avisa que fechou e manda pro login; o representante vê
    // os itens/valores de verdade dentro do sistema, não no e-mail. Quem não venceu nada
    // recebe um texto diferente (agradecendo a participação, sem soar como "você
    // perdeu") em vez do texto genérico de "acesse pra conferir".
    @Async
    public void notifyQuotationClosed(String quotationName, RepContact rep, List<WonItemLine> wonItems) {
        boolean won = !wonItems.isEmpty();
        String subject = won
                ? "Você venceu itens na cotação: " + quotationName
                : "Resultado da cotação: " + quotationName;
        String link = APP_URL + "/login.html";

        StringBuilder content = new StringBuilder();
        content.append("<p style=\"margin:0 0 12px\">Olá, ").append(escapeHtml(firstName(rep.name()))).append("!</p>");
        if (won) {
            content.append("<p style=\"margin:0 0 20px\">A cotação <strong>").append(escapeHtml(quotationName))
                    .append("</strong> foi fechada — parabéns, você ganhou! Acesse o sistema pra conferir o resultado "
                            + "e seu desempenho em \"Meu Desempenho\".</p>");
        } else {
            content.append("<p style=\"margin:0 0 20px\">A cotação <strong>").append(escapeHtml(quotationName))
                    .append("</strong> foi fechada. Obrigado por participar — fique de olho nas próximas cotações.</p>");
        }
        content.append(buttonHtml(link, "Acessar o sistema"));

        send(rep.email(), subject, wrapInLayout(content.toString()));
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
        // Uma consulta só, reaproveitada tanto pro nome de exibição do remetente quanto
        // pro prefixo do assunto — evita bater no banco duas vezes por e-mail enviado.
        String companyName = companySettingsService.getOrCreate().getName();
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            if (companyName != null && !companyName.isBlank()) {
                // setFrom(endereço, nome) — sem o nome, o cliente de e-mail (Gmail, Outlook
                // etc.) não tem o que mostrar na lista de conversas além da parte antes do
                // @ do endereço ("no-reply"), já que o cabeçalho From não carregava nome
                // nenhum associado ao endereço.
                helper.setFrom(fromAddress, companyName);
            } else {
                helper.setFrom(fromAddress);
            }
            helper.setTo(to);
            helper.setSubject(prefixWithCompanyName(companyName, subject));
            helper.setText(htmlBody, true);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Falha ao enviar e-mail pra {}: {}", to, e.getMessage());
        }
    }

    // Assunto sempre começa com o nome da empresa contratante (CompanySettings — a
    // mesma fonte usada no PDF e na mensagem de WhatsApp), nunca hardcoded aqui: se o
    // nome mudar em "Dados da Empresa", os próximos e-mails já saem com o nome novo,
    // sem precisar editar código. Recebe o nome já buscado por send() — não busca de
    // novo aqui, pra não duplicar a consulta.
    private String prefixWithCompanyName(String companyName, String subject) {
        if (companyName == null || companyName.isBlank()) {
            return subject;
        }
        return companyName + " - " + subject;
    }

    private String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "";
        return fullName.trim().split(" ")[0];
    }

    private String fmtDate(LocalDateTime date) {
        if (date == null) return "—";
        return date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm"));
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}