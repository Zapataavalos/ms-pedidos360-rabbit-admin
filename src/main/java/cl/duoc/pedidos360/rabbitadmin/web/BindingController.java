package cl.duoc.pedidos360.rabbitadmin.web;

import cl.duoc.pedidos360.rabbitadmin.dto.CreateBindingRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.Views.BindingView;
import cl.duoc.pedidos360.rabbitadmin.service.RabbitAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cl.duoc.pedidos360.rabbitadmin.dto.ResourceNames.*;

/**
 * Bindings (exchange → cola). Un binding se identifica por exchange, cola y clave, así que para eliminarlo
 * se envían los tres como parámetros (los caracteres # y * se codifican en la URL: %23 y %2A).
 * <pre>
 * GET    /api/rabbitmq/bindings?exchange=&amp;queue=                   lista (filtros opcionales)
 * POST   /api/rabbitmq/bindings                                     crea          → 201
 * DELETE /api/rabbitmq/bindings?exchange=..&amp;queue=..&amp;routingKey=..  elimina       → 204
 * </pre>
 */
@RestController
@RequestMapping("/api/rabbitmq/bindings")
public class BindingController {

    private final RabbitAdminService service;

    public BindingController(RabbitAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<BindingView> list(@RequestParam(required = false) String exchange,
                                  @RequestParam(required = false) String queue) {
        return service.listBindings(exchange, queue);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BindingView create(@Valid @RequestBody CreateBindingRequest request) {
        return service.createBinding(request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam @NotBlank @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE) String exchange,
                       @RequestParam @NotBlank @Pattern(regexp = NAME_REGEX, message = NAME_MESSAGE) String queue,
                       @RequestParam(defaultValue = "")
                       @Pattern(regexp = ROUTING_KEY_REGEX, message = ROUTING_KEY_MESSAGE) String routingKey) {
        service.deleteBinding(exchange, queue, routingKey);
    }
}
