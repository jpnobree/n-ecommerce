package com.atelier.notification;

import com.atelier.shared.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Outbox: o e-mail é gravado na mesma transação do fato (pedido pago) e enviado depois por um job.
 * Se o processo cair entre um e outro, o envio acontece na próxima rodada. Pode sair duplicado em caso raro
 * (enviou e caiu antes de marcar), nunca perdido.
 */
@Component
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);
    private static final int MAX_ATTEMPTS = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final JavaMailSender mail;
    private final AppProperties app;

    Outbox(NamedParameterJdbcTemplate jdbc, JsonMapper json, JavaMailSender mail, AppProperties app) {
        this.jdbc = jdbc;
        this.json = json;
        this.mail = mail;
        this.app = app;
    }

    /** Só dentro da transação que registra o fato. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void email(String aggregateType, long aggregateId, String to, String subject, String body) {
        jdbc.update("""
                INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
                VALUES (:type, :id, 'EMAIL', CAST(:payload AS jsonb))
                """, new MapSqlParameterSource("type", aggregateType).addValue("id", aggregateId)
                .addValue("payload", json.writeValueAsString(Map.of("to", to, "subject", subject, "body", body))));
    }

    /** SKIP LOCKED: várias instâncias dividem a fila sem enviar o mesmo e-mail duas vezes. */
    @Scheduled(fixedDelayString = "${app.outbox.interval:10s}")
    @Transactional
    public void publish() {
        jdbc.query("""
                SELECT id, payload::text FROM outbox_event WHERE status = 'PENDING' ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource(), rs -> {
            long id = rs.getLong(1);
            JsonNode p = json.readTree(rs.getString(2));
            try {
                var message = new SimpleMailMessage();
                message.setFrom(app.mailFrom());
                message.setTo(p.path("to").asString());
                message.setSubject(p.path("subject").asString());
                message.setText(p.path("body").asString());
                mail.send(message);
                jdbc.update("UPDATE outbox_event SET status = 'PUBLISHED', published_at = now(), attempts = attempts + 1 WHERE id = :id",
                        new MapSqlParameterSource("id", id));
            } catch (RuntimeException e) {
                log.error("Falha ao publicar outbox {}", id, e);
                jdbc.update("""
                        UPDATE outbox_event SET attempts = attempts + 1,
                               status = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE 'PENDING' END
                         WHERE id = :id
                        """, new MapSqlParameterSource("id", id).addValue("max", MAX_ATTEMPTS));
            }
        });
    }
}
