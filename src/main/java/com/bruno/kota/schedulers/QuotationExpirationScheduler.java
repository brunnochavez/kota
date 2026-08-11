package com.bruno.kota.schedulers;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.bruno.kota.entities.Quotation;
import com.bruno.kota.entities.QuotationStatus;
import com.bruno.kota.repositories.QuotationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuotationExpirationScheduler {

    private final QuotationRepository quotationRepository;

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void expireOverdueQuotations() {
        List<Quotation> overdue = quotationRepository.findByStatusAndExpirationDateBefore(
                QuotationStatus.AVAILABLE, LocalDateTime.now());

        if (overdue.isEmpty()) {
            return;
        }

        for (Quotation quotation : overdue) {
            quotation.setStatus(QuotationStatus.EXPIRED);
        }
        quotationRepository.saveAll(overdue);

        log.info("Marked {} quotation(s) as EXPIRED", overdue.size());
    }
}