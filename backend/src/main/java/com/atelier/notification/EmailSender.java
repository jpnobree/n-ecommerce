package com.atelier.notification;

import com.atelier.shared.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envia após o commit, fora da thread da requisição.
 * ponytail: se o processo cair entre o commit e o envio, o e-mail se perde. Aceitável para verificação e
 * redefinição (o usuário pede de novo); e-mails de pedido (Fase 8) passam a usar outbox.
 */
@Component
class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JavaMailSender mail;
    private final AppProperties app;

    EmailSender(JavaMailSender mail, AppProperties app) {
        this.mail = mail;
        this.app = app;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(EmailRequested email) {
        var message = new SimpleMailMessage();
        message.setFrom(app.mailFrom());
        message.setTo(email.to());
        message.setSubject(email.subject());
        message.setText(email.body());
        try {
            mail.send(message);
        } catch (MailException e) {
            log.error("Falha ao enviar e-mail '{}'", email.subject(), e);
        }
    }
}
