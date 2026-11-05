package cl.duoc.pedidos360.rabbitadmin.web;

import cl.duoc.pedidos360.rabbitadmin.dto.CreateExchangeRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.Views.ExchangeView;
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
 * Exchanges.
 * <pre>
 * GET    /api/rabbitmq/exchanges           lista (marca los del sistema: default y amq.*)
 * POST   /api/rabbitmq/exchanges           crea direct, topic, fanout o headers   → 201 + Location
 * DELETE /api/rabbitmq/exchanges/{name}    elimina                                → 204
 * </pre>
 */
@RestController
@RequestMapping("/api/rabbitmq/exchanges")
public class ExchangeController {

    private final RabbitAdminService service;

    public ExchangeController(RabbitAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<ExchangeView> list() {
        return service.listExchanges();
    }

    @PostMapping
    public ResponseEntity<ExchangeView> create(@Valid @RequestBody CreateExchangeRequest request) {
        ExchangeView created = service.createExchange(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{name}")
                .buildAndExpand(created.name()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE) String name) {
        service.deleteExchange(name);
    }
}
