package com.bruno.kota;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableAsync liga o suporte a métodos @Async no projeto inteiro (usado hoje só no
// EmailService — ver o motivo lá). Sem essa anotação aqui, @Async nos métodos vira
// decoração sem efeito nenhum: o Spring só cria o proxy assíncrono se esse "interruptor
// geral" estiver ligado.
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class KotaApplication {

    public static void main(String[] args) {

        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
        SpringApplication.run(KotaApplication.class, args);
    }

}