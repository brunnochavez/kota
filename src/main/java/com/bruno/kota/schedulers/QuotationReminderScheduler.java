package com.bruno.kota.schedulers;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bruno.kota.entities.Quotation;
import com.bruno.kota.repositories.QuotationRepository;
import com.bruno.kota.services.QuotationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Manda um lembrete por e-mail pra representantes que ainda não responderam, algumas
// horas antes do prazo de uma cotação Disponível vencer. Sem isso, quem esquece de
// checar o app só descobre que perdeu o prazo depois que já não dá mais tempo de
// responder — e a cotação fecha com menos respostas do que poderia ter tido. Quem já
// respondeu (lance ou "Não Cotar") não recebe nada, só quem realmente ficaria de fora.
@Component
@RequiredArgsConstructor
@Slf4j
public class QuotationReminderScheduler {

    // Janela de aviso: cotações que vencem entre agora e daqui a REMINDER_WINDOW_HOURS
    // horas entram na lista. Fixo por enquanto — é um comportamento do sistema como um
    // todo, não um dado configurável por cotação.
    private static final int REMINDER_WINDOW_HOURS = 5;

    private final QuotationRepository quotationRepository;
    private final QuotationService quotationService;

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void sendDueReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<Quotation> due = quotationRepository.findDueForReminder(now, now.plusHours(REMINDER_WINDOW_HOURS));

        if (due.isEmpty()) {
            return;
        }

        for (Quotation quotation : due) {
            quotationService.sendDeadlineReminder(quotation.getId());
            quotation.setReminderSentAt(now);
        }
        quotationRepository.saveAll(due);

        log.info("Sent deadline reminder for {} quotation(s)", due.size());
    }
}
