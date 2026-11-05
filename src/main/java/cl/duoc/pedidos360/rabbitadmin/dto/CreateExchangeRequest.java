package cl.duoc.pedidos360.rabbitadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

import static cl.duoc.pedidos360.rabbitadmin.dto.ResourceNames.*;

/**
 * Exchange nuevo.
 *
 * @param type       direct, topic, fanout o headers (sin distinguir mayúsculas)
 * @param durable    sobrevive a un reinicio del broker (por defecto true)
 * @param autoDelete se elimina solo cuando pierde su último binding (por defecto false)
 */
public record CreateExchangeRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = NAME_MAX, message = "admite hasta {max} caracteres")
        @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE)
        String name,
        @NotBlank(message = "es obligatorio: direct, topic, fanout o headers")
        @Pattern(regexp = "(?i)direct|topic|fanout|headers", message = "debe ser direct, topic, fanout o headers")
        String type,
        Boolean durable,
        Boolean autoDelete) {

    public String normalizedType() {
        return type.toLowerCase(Locale.ROOT);
    }

    public boolean isDurable() {
        return durable == null || durable;
    }

    public boolean isAutoDelete() {
        return autoDelete != null && autoDelete;
    }
}
