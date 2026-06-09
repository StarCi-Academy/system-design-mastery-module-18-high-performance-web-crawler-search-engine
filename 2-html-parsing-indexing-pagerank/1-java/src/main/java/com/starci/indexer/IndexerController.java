package com.starci.indexer;

import jakarta.annotation.PostConstruct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/indexer")
class IndexerController {

    private static final String BASE = "https://search.starci.test/";
    private static final String[][] SEED = {
            {"a", "b"}, {"a", "c"}, {"b", "c"}, {"c", "a"},
            {"d", "c"}, {"d", "b"}, {"e", "d"}
    };

    private final PageLinkRepository linkRepo;
    private final PageRankRepository rankRepo;

    IndexerController(PageLinkRepository linkRepo, PageRankRepository rankRepo) {
        this.linkRepo = linkRepo;
        this.rankRepo = rankRepo;
    }

    @PostConstruct
    void seed() {
        if (linkRepo.count() > 0) {
            return;
        }
        for (String[] e : SEED) {
            linkRepo.save(new PageLink(BASE + e[0], BASE + e[1]));
        }
    }

    @PostMapping("/parse")
    ResponseEntity<Map<String, Object>> parse(@RequestBody Map<String, String> dto) {
        String url = dto.get("url");
        String html = dto.getOrDefault("html", "");
        Document doc = Jsoup.parse(html, url);
        String title = doc.title().isBlank() ? url : doc.title();
        Set<String> outlinks = new LinkedHashSet<>();
        doc.select("a[href]").forEach(e -> {
            String abs = e.attr("abs:href");
            if (!abs.isBlank() && !abs.equals(url)) {
                outlinks.add(abs);
            }
        });
        linkRepo.deleteByFromUrl(url);
        for (String to : outlinks) {
            linkRepo.save(new PageLink(url, to));
        }
        return ResponseEntity.ok(Map.of(
                "url", url, "outlinks", new ArrayList<>(outlinks),
                "storedLinks", outlinks.size(), "title", title));
    }

    @PostMapping("/compute-pagerank")
    ResponseEntity<Map<String, Object>> compute() {
        List<PageRankEngine.Edge> edges = new ArrayList<>();
        for (PageLink l : linkRepo.findAll()) {
            edges.add(new PageRankEngine.Edge(l.fromUrl, l.toUrl));
        }
        PageRankEngine.Output out = PageRankEngine.compute(edges);
        rankRepo.deleteAll();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        out.ranks().forEach((url, rank) ->
                rankRepo.save(new PageRankRow(url, rank, out.iterations(), now)));
        return ResponseEntity.ok(Map.of(
                "iterations", out.iterations(), "damping", PageRankEngine.DAMPING,
                "nodes", out.nodes(), "edges", out.edges(), "converged", out.converged()));
    }

    @GetMapping("/top")
    List<Map<String, Object>> top() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PageRankRow r : rankRepo.findTop10ByOrderByRankDesc()) {
            double rounded = BigDecimal.valueOf(r.rank).setScale(3, RoundingMode.HALF_UP).doubleValue();
            result.add(Map.of("url", r.url, "rank", rounded));
        }
        return result;
    }

    @GetMapping("/rank/{url}")
    ResponseEntity<Map<String, Object>> rankOf(@PathVariable String url) {
        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        Optional<PageRankRow> row = rankRepo.findById(decoded);
        if (row.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "statusCode", 404, "message", "URL " + decoded + " not found"));
        }
        PageRankRow r = row.get();
        return ResponseEntity.ok(Map.of(
                "url", r.url, "rank", r.rank, "iterations", r.iterations,
                "updatedAt", r.updatedAt.toString()));
    }
}
