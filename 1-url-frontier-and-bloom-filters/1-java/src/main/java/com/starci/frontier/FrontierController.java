package com.starci.frontier;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/frontier")
public class FrontierController {

    private final FrontierService service;

    public FrontierController(FrontierService service) {
        this.service = service;
    }

    public record EnqueueDto(String url, int priority) {}

    @PostMapping("/enqueue")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> enqueue(@RequestBody EnqueueDto dto) {
        return service.enqueue(dto.url(), dto.priority());
    }

    @PostMapping("/dequeue")
    public ResponseEntity<Map<String, Object>> dequeue() {
        return ResponseEntity.ok(service.dequeue());
    }

    @GetMapping("/seen/{url}")
    public Map<String, Object> seen(@PathVariable String url) {
        return service.seen(URLDecoder.decode(url, StandardCharsets.UTF_8));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }
}
