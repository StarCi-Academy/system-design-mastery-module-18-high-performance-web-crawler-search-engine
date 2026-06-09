package com.starci.indexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Iterative power-method PageRank with explicit dangling-node correction. */
final class PageRankEngine {
    static final double DAMPING = 0.85;
    static final int MAX_ITERATIONS = 10;
    static final double TOLERANCE = 1e-6;

    record Edge(String fromUrl, String toUrl) {
    }

    record Output(Map<String, Double> ranks, int nodes, int edges, int iterations, boolean converged) {
    }

    private PageRankEngine() {
    }

    static Output compute(List<Edge> edges) {
        Set<String> nodes = new HashSet<>();
        for (Edge e : edges) {
            nodes.add(e.fromUrl());
            nodes.add(e.toUrl());
        }
        int n = nodes.size();
        if (n == 0) {
            return new Output(new HashMap<>(), 0, 0, 0, true);
        }

        Map<String, Integer> outDegree = new HashMap<>();
        Map<String, List<String>> inLinks = new HashMap<>();
        for (String u : nodes) {
            outDegree.put(u, 0);
            inLinks.put(u, new ArrayList<>());
        }
        for (Edge e : edges) {
            outDegree.merge(e.fromUrl(), 1, Integer::sum);
            inLinks.get(e.toUrl()).add(e.fromUrl());
        }

        Map<String, Double> rank = new HashMap<>();
        for (String u : nodes) {
            rank.put(u, 1.0 / n);
        }

        boolean converged = false;
        int it = 0;
        for (; it < MAX_ITERATIONS; it++) {
            Map<String, Double> next = new HashMap<>();
            double danglingSum = 0.0;
            for (String u : nodes) {
                if (outDegree.get(u) == 0) {
                    danglingSum += rank.get(u);
                }
            }
            double danglingShare = (DAMPING * danglingSum) / n;
            double teleport = (1.0 - DAMPING) / n;
            double delta = 0.0;
            for (String u : nodes) {
                double sum = 0.0;
                for (String v : inLinks.get(u)) {
                    int dv = outDegree.get(v);
                    if (dv > 0) {
                        sum += rank.get(v) / dv;
                    }
                }
                double newRank = teleport + danglingShare + DAMPING * sum;
                next.put(u, newRank);
                delta += Math.abs(newRank - rank.get(u));
            }
            rank = next;
            if (delta < TOLERANCE) {
                converged = true;
                it++;
                break;
            }
        }

        return new Output(rank, n, edges.size(), it, converged);
    }
}
