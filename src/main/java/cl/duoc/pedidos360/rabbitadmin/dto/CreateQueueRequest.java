package cl.duoc.pedidos360.rabbitadmin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import static cl.duoc.pedidos360.rabbitadmin.dto.ResourceNames.*;

/**
 * Cola nueva. Solo el nombre es obligatorio.
 *
 * @param name            nombre de la cola
 * @param durable         sobrevive a un reinicio del broker (por defecto true)
 * @param autoDelete      se elimina sola cuando se desconecta su último consumidor (por defecto false)
 * @param messageTtlMs    vida máxima de cada mensaje en milisegundos (opcional, hasta 7 días)
 * @param maxLength       cantidad máxima de mensajes en la cola (opcional)
 * @param deadLetterQueue crea además &lt;name&gt;.dlq enlazada a la DLX (por defecto true)
 */
public record CreateQueueRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = NAME_MAX, message = "admite hasta {max} caracteres")
        @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE)
        String name,
        Boolean durable,
        Boolean autoDelete,
        @Positive(message = "debe ser mayor que 0")
        @Max(value = 604_800_000L, message = "no puede superar 7 días (604800000 ms)")
        Long messageTtlMs,
        @Positive(message = "debe ser mayor que 0")
        @Max(value = 1_000_000, message = "no puede superar 1.000.000 mensajes")
        Integer maxLength,
        Boolean deadLetterQueue) {

    public boolean isDurable() {
        return durable == null || durable;
    }

    public boolean isAutoDelete() {
        return autoDelete != null && autoDelete;
    }

    public boolean withDeadLetterQueue() {
        return deadLetterQueue == null || deadLetterQueue;
    }

    /** Una cola con DLQ debe ser durable: si se pierde al reiniciar el broker, el respaldo de errores también. */
    @JsonIgnore
    @AssertTrue(message = "una cola con DLQ debe ser durable y no autoDelete")
    public boolean isDeadLetterConfigurationValid() {
        return !withDeadLetterQueue() || (isDurable() && !isAutoDelete());
    }

    /** Evita crear "x.dlq.dlq": las DLQ se crean junto a su cola principal. */
    @JsonIgnore
    @AssertTrue(message = "el sufijo .dlq está reservado para las DLQ que crea el sistema")
    public boolean isNameNotReservedForDeadLetter() {
        return name == null || !name.endsWith(".dlq");
    }
}
