package cl.duoc.pedidos360.rabbitadmin.web;

import cl.duoc.pedidos360.rabbitadmin.exception.BrokerUnavailableException;
import cl.duoc.pedidos360.rabbitadmin.exception.InvalidRequestException;
import cl.duoc.pedidos360.rabbitadmin.exception.ResourceConflictException;
import cl.duoc.pedidos360.rabbitadmin.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Errores en JSON: 400 (validación), 404 (no existe), 409 (conflicto con el estado del broker),
 * 503 (RabbitMQ no disponible). Los errores de validación incluyen el detalle por campo en "errors".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), null, req);
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ResourceConflictException ex, HttpServletRequest req) {
        return error(HttpStatus.CONFLICT, ex.getMessage(), null, req);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> invalid(InvalidRequestException ex, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), null, req);
    }

    @ExceptionHandler(BrokerUnavailableException.class)
    public ResponseEntity<Map<String, Object>> unavailable(BrokerUnavailableException ex, HttpServletRequest req) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), null, req);
    }

    /** Cuerpo con campos inválidos (@Valid en un DTO). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError e : ex.getBindingResult().getFieldErrors()) {
            errors.merge(e.getField(), e.getDefaultMessage(), (a, b) -> a + "; " + b);
        }
        ex.getBindingResult().getGlobalErrors().forEach(e -> errors.put(e.getObjectName(), e.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, summary(errors), errors, req);
    }

    /** Parámetros de ruta o de consulta inválidos (@Pattern, @NotBlank en el método del controlador). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> parameters(HandlerMethodValidationException ex, HttpServletRequest req) {
        Map<String, String> errors = new TreeMap<>();
        ex.getParameterValidationResults().forEach(result -> errors.put(
                result.getMethodParameter().getParameterName(),
                result.getResolvableErrors().stream().map(MessageSourceResolvable::getDefaultMessage)
                        .collect(Collectors.joining("; "))));
        return error(HttpStatus.BAD_REQUEST, summary(errors), errors, req);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missing(MissingServletRequestParameterException ex, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "Falta el parámetro obligatorio '" + ex.getParameterName() + "'",
                Map.of(ex.getParameterName(), "es obligatorio"), req);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> unreadable(Exception ex, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "Solicitud inválida: el cuerpo debe ser JSON válido con los tipos esperados",
                null, req);
    }

    private static String summary(Map<String, String> errors) {
        return "Solicitud inválida: " + errors.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining("; "));
    }

    static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, Map<String, String> errors,
                                                     HttpServletRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (errors != null && !errors.isEmpty()) {
            body.put("errors", errors);
        }
        body.put("path", req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
