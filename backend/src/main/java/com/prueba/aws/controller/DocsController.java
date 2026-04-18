package com.prueba.aws.controller;

import com.prueba.aws.service.GitHubService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

/**
 * Proxy autenticado para servir markdown del repo GitHub privado.
 * Reusa GITHUB_TOKEN del backend, así el frontend NO necesita acceso al repo.
 */
@RestController
@RequestMapping("/api/docs")
public class DocsController {

    private static final Map<String, String> WHITELIST = Map.ofEntries(
            Map.entry("pipeline", "docs/pipeline.md"),
            Map.entry("permissions", "docs/permissions.md"),
            Map.entry("data-model", "docs/data-model.md"),
            Map.entry("data-quality", "docs/data-quality.md"),
            Map.entry("data-generation", "docs/data-generation.md"),
            Map.entry("data-decision", "docs/data-decision.md"),
            Map.entry("deployment", "docs/deployment.md"),
            Map.entry("cost", "docs/cost.md"),
            Map.entry("ej2", "docs/exercises/ej2-architecture.md"),
            Map.entry("ej3", "docs/exercises/ej3-answers.md"),
            Map.entry("readme", "README.md")
    );

    private final GitHubProxy proxy;

    public DocsController(GitHubProxy proxy) { this.proxy = proxy; }

    @GetMapping(value = "/{name}", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> get(@PathVariable String name) {
        String path = WHITELIST.get(name);
        if (path == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Doc no permitido: " + name + " · whitelist: " + WHITELIST.keySet());
        }
        String content = proxy.fetch(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(content);
    }

    @GetMapping("/list")
    public Set<String> list() { return WHITELIST.keySet(); }

    @Component
    static class GitHubProxy {
        private final GitHubService gh;
        public GitHubProxy(GitHubService gh) { this.gh = gh; }

        @Cacheable(cacheNames = "docs", key = "#path")
        public String fetch(String path) {
            String content = gh.getRawContent(path);
            return content != null ? content : "_(documento no disponible)_";
        }
    }
}
