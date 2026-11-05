package cl.duoc.pedidos360.rabbitadmin.web;

import cl.duoc.pedidos360.rabbitadmin.dto.CreateQueueRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.Views.PurgeResult;
import cl.duoc.pedidos360.rabbitadmin.dto.Views.QueueView;
import cl.duoc.pedidos360.rabbitadmin.service.RabbitAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

import static cl.duoc.pedidos360.rabbitadmin.dto.ResourceNames.NAME_MESSAGE;
import static cl.duoc.pedidos360.rabbitadmin.dto.ResourceNames.NAME_REGEX;

/**
 * Colas.
 * <pre>
 * GET    /api/rabbitmq/queues                    lista con mensajes, consumidores y nodo
 * GET    /api/rabbitmq/queues/{name}             detalle
 * POST   /api/rabbitmq/queues                    crea (por defecto con su DLQ)          → 201 + Location
 * DELETE /api/rabbitmq/queues/{name}             elimina (?ifUnused=true&amp;ifEmpty=true)   → 204
 * DELETE /api/rabbitmq/queues/{name}/messages    vacía la cola                          → 200
 * </pre>
 */
@RestController
@RequestMapping("/api/rabbitmq/queues")
public class QueueController {

    private final RabbitAdminService service;

    public QueueController(RabbitAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<QueueView> list() {
        return service.listQueues();
    }

    @GetMapping("/{name}")
    public QueueView get(@PathVariable @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE) String name) {
        return service.getQueue(name);
    }

    @PostMapping
    public ResponseEntity<QueueView> create(@Valid @RequestBody CreateQueueRequest request) {
        QueueView created = service.createQueue(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{name}")
                .buildAndExpand(created.name()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE) String name,
                       @RequestParam(defaultValue = "false") boolean ifUnused,
                       @RequestParam(defaultValue = "false") boolean ifEmpty) {
        service.deleteQueue(name, ifUnused, ifEmpty);
    }

    @DeleteMapping("/{name}/messages")
    public PurgeResult purge(@PathVariable @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE) String name) {
        return service.purgeQueue(name);
    }
}
