package cl.duoc.pedidos360.rabbitadmin.web;

import cl.duoc.pedidos360.rabbitadmin.dto.PublishMessageRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.Views.PublishResult;
import cl.duoc.pedidos360.rabbitadmin.service.RabbitAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Mensajes de prueba: permiten demostrar el enrutamiento, los reintentos y la DLQ sin pasar por el frontend.
 * <pre>
 * POST /api/rabbitmq/messages   publica en un exchange con una clave   → 202 (informa si llegó a alguna cola)
 * </pre>
 */
@RestController
@RequestMapping("/api/rabbitmq/messages")
public class MessageController {

    private final RabbitAdminService service;

    public MessageController(RabbitAdminService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PublishResult publish(@Valid @RequestBody PublishMessageRequest request) {
        return service.publish(request);
    }
}
