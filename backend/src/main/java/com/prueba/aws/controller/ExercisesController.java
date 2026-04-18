package com.prueba.aws.controller;

import com.prueba.aws.dto.Dtos.ExerciseDoc;
import com.prueba.aws.service.GitHubService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

/** Read-only — los ejercicios se editan via Git directamente, no desde el front. */
@RestController
@RequestMapping("/api/exercises")
public class ExercisesController {

    private static final Map<String, String> PATHS = Map.of(
            "ej2", "docs/exercises/ej2-architecture.md",
            "ej3", "docs/exercises/ej3-answers.md"
    );
    private static final Map<String, String> TITLES = Map.of(
            "ej2", "Ejercicio 2 · Plataforma de notificaciones de divisas",
            "ej3", "Ejercicio 3 · Preguntas de experiencia AWS"
    );

    private final GitHubService gh;

    public ExercisesController(GitHubService gh) { this.gh = gh; }

    @GetMapping("/{id}")
    public ExerciseDoc get(@PathVariable String id) {
        String path = PATHS.get(id);
        if (path == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercise no encontrado: " + id);
        GitHubService.FileContent f = gh.getFile(path);
        return new ExerciseDoc(id, TITLES.get(id), f.content(), f.sha(),
                f.htmlUrl(), OffsetDateTime.now());
    }
}
