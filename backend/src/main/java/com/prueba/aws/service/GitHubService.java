package com.prueba.aws.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prueba.aws.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Lee y escribe contenido de archivos en el repo GitHub usando la Contents API.
 * Usado por el editor de Ej.2 / Ej.3 para persistir markdown que dispara
 * el redeploy automático en Vercel.
 */
@Service
public class GitHubService {
    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);
    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AppProperties props;

    public GitHubService(AppProperties props) {
        this.props = props;
    }

    public record FileContent(String content, String sha, String htmlUrl) {}

    public FileContent getFile(String path) {
        try {
            String url = "https://api.github.com/repos/" + props.getGithub().getRepo()
                    + "/contents/" + path + "?ref=" + props.getGithub().getBranch();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Authorization", "Bearer " + props.getGithub().getToken())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                log.info("Archivo no existe en GitHub: {}", path);
                return new FileContent("", null, null);
            }
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("GitHub GET " + path + " → " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode node = M.readTree(resp.body());
            String b64 = node.get("content").asText().replaceAll("\\s+", "");
            String content = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            return new FileContent(content, node.get("sha").asText(), node.path("html_url").asText(null));
        } catch (Exception e) {
            log.warn("Failed to fetch {} from GitHub: {}", path, e.getMessage());
            return new FileContent("", null, null);
        }
    }

    public String getRawContent(String path) {
        try {
            String url = "https://raw.githubusercontent.com/" + props.getGithub().getRepo()
                    + "/" + props.getGithub().getBranch() + "/" + path;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + props.getGithub().getToken())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : "";
        } catch (Exception e) {
            log.warn("raw GET {} failed: {}", path, e.getMessage());
            return "";
        }
    }

    /** PUT contenido al repo → genera commit en main → Vercel detecta y redespliega. */
    public String putFile(String path, String newContent, String commitMessage) throws Exception {
        FileContent existing = getFile(path);
        String url = "https://api.github.com/repos/" + props.getGithub().getRepo() + "/contents/" + path;

        var body = M.createObjectNode();
        body.put("message", commitMessage == null ? "chore: actualizar " + path : commitMessage);
        body.put("branch", props.getGithub().getBranch());
        body.put("content", Base64.getEncoder().encodeToString(newContent.getBytes(StandardCharsets.UTF_8)));
        body.put("committer.name", "Datalake Editor");
        body.put("committer.email", "noreply@datalake-energia.local");
        if (existing.sha() != null) body.put("sha", existing.sha());

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + props.getGithub().getToken())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body))).build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("GitHub PUT " + path + " → " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode r = M.readTree(resp.body());
        return r.path("commit").path("sha").asText();
    }

}
