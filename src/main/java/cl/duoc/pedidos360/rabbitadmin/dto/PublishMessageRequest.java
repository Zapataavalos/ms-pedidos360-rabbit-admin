package cl.duoc.pedidos360.rabbitadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static cl.duoc.pedidos360.rabbitadmin.dto.ResourceNames.*;

/**
 * Mensaje de prueba. El payload se publica tal cual (texto JSON), así se puede demostrar tanto el flujo
 * normal como el envío a la DLQ de un mensaje mal formado.
 */
public record PublishMessageRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = NAME_MAX, message = "admite hasta {max} caracteres")
        @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE)
        String exchange,
        @NotNull(message = "es obligatoria")
        @Size(max = ROUTING_KEY_MAX, message = "admite hasta {max} caracteres")
        @Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "no admite comodines al publicar: usa una clave concreta")
        String routingKey,
        @NotBlank(message = "es obligatorio")
        @Size(max = 65_536, message = "admite hasta 64 KB")
        String payload) {
}
