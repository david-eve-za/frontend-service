package com.glez.frontendservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de log persistido en BD para análisis a posteriori (la vista en
 * vivo usa el búfer en memoria del LogEventBroadcaster; esta tabla es el
 * historial consultable, con retención acotada — el purgador elimina los
 * eventos más antiguos que app.log-persistence.retention-days).
 *
 * <p>El contexto MDC del pipeline se guarda como columnas de primera clase
 * (bookId / traceId / step) para poder analizar por libro sin deserializar
 * nada; el mensaje se trunca a 8.000 caracteres para mantenerlo buscable
 * con LIKE (los CLOB no lo permiten cómodamente en JPQL).</p>
 */
@Entity
@DynamicUpdate
@Table(name = "log_event", indexes = {
        // Purga diaria por antigüedad y rangos de tiempo de búsqueda.
        @Index(name = "idx_log_event_logged_at", columnList = "logged_at"),
        // Análisis por libro ("¿qué pasó con este libro hace 3 días?").
        @Index(name = "idx_log_event_book_logged_at", columnList = "book_id, logged_at"),
        // Análisis por nivel ("dame los ERROR de la semana").
        @Index(name = "idx_log_event_level_logged_at", columnList = "level, logged_at")})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogEvent {

    /** Mensajes más largos se truncan al persistir (columna VARCHAR(8192)). */
    public static final int MAX_MESSAGE_LENGTH = 8000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Momento exacto del evento (cuándo se emitió, no cuándo se persistió). */
    private Instant loggedAt;

    /** Nivel Logback (ERROR/WARN/INFO/DEBUG/TRACE) como texto, para LIKE. */
    private String level;

    /**
     * Longitudes EXPLÍCITAS alineadas con los truncados de capture(): sin
     * @Column(length), hbm2ddl crea todo como VARCHAR(255) y jamás ensancha
     * una columna existente — un mensaje legítimo de 470 caracteres
     * reventaba el INSERT (la BD real tenía 255 pese al javadoc).
     */
    @Column(length = 256)
    private String logger;

    @Column(length = 128)
    private String thread;

    /** Mensaje formateado, truncado a {@code MAX_MESSAGE_LENGTH} caracteres. */
    @Column(length = MAX_MESSAGE_LENGTH)
    private String message;

    /** bookId del MDC del pipeline, cuando el evento pertenece a una corrida. */
    @Column(name = "book_id", length = 64)
    private String bookId;

    /** traceId del MDC (corrida del pipeline o chunk, en traducciones). */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** step del MDC (EXTRACT/SPLIT/TRANSLATE/AUDIO/FINALIZE). */
    @Column(length = 32)
    private String step;
}
