package cl.duoc.pedidos360.rabbitadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static cl.duoc.pedidos360.rabbitadmin.dto.ResourceNames.*;

/**
 * Binding nuevo: enlaza una cola a un exchange con una clave de enrutamiento.
 *
 * @param routingKey clave exacta (direct), patrón con * y # (topic) o vacía (fanout)
 */
public record CreateBindingRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = NAME_MAX, message = "admite hasta {max} caracteres")
        @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE)
        String exchange,
        @NotBlank(message = "es obligatoria")
        @Size(max = NAME_MAX, message = "admite hasta {max} caracteres")
        @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE)
        String queue,
        @NotNull(message = "es obligatoria (puede ser \"\" para un exchange fanout)")
        @Size(max = ROUTING_KEY_MAX, message = "admite hasta {max} caracteres")
        @Pattern(regexp = ROUTING_KEY_REGEX, message = ROUTING_KEY_MESSAGE)
        String routingKey) {
}
