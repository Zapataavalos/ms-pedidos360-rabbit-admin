package cl.duoc.pedidos360.rabbitadmin.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Configuración del administrador (prefijo {@code pedidos360.rabbit-admin} en application.yml).
 *
 * @param management       API HTTP de administración de RabbitMQ (para listar colas, exchanges y bindings)
 * @param deadLetter       DLX y sufijo usados al crear colas con DLQ
 * @param protectedNames   exchanges y colas del sistema que la API no permite eliminar ni vaciar
 * @param confirmTimeout   espera máxima por la confirmación del broker al publicar un mensaje de prueba
 */
@Validated
@ConfigurationProperties(prefix = "pedidos360.rabbit-admin")
public record RabbitAdminProperties(
        @Valid @NotNull Management management,
        @Valid @NotNull DeadLetter deadLetter,
        Set<String> protectedNames,
        @NotNull Duration confirmTimeout) {

    public RabbitAdminProperties {
        protectedNames = protectedNames == null ? Set.of() : Set.copyOf(protectedNames);
    }

    /**
     * @param urls        URL de la API de cada nodo del clúster (si uno no responde, se prueba el siguiente)
     * @param virtualHost virtual host administrado
     */
    public record Management(@NotEmpty List<@NotBlank String> urls, @NotBlank String username,
                             @NotBlank String password, @NotBlank String virtualHost) {
    }

    /**
     * @param exchange DLX (direct) a la que se enlazan las DLQ que crea la API
     * @param suffix   sufijo del nombre de la DLQ (cola "x" → DLQ "x.dlq")
     */
    public record DeadLetter(@NotBlank String exchange, @NotBlank String suffix) {
    }

    public boolean isProtected(String name) {
        return protectedNames.contains(name);
    }
}
