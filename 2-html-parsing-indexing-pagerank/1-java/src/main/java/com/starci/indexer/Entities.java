package com.starci.indexer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;

/** page_link stores one directed edge (fromUrl -> toUrl) of the web graph. */
@Entity
@Table(name = "page_link",
        uniqueConstraints = @UniqueConstraint(columnNames = {"from_url", "to_url"}))
class PageLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "from_url", nullable = false, columnDefinition = "text")
    String fromUrl;

    @Column(name = "to_url", nullable = false, columnDefinition = "text")
    String toUrl;

    PageLink() {
    }

    PageLink(String fromUrl, String toUrl) {
        this.fromUrl = fromUrl;
        this.toUrl = toUrl;
    }
}

/** page_rank stores the computed authority score per URL after a batch run. */
@Entity
@Table(name = "page_rank")
class PageRankRow {
    @Id
    @Column(columnDefinition = "text")
    String url;

    @Column(nullable = false)
    double rank;

    @Column(nullable = false)
    int iterations;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;

    PageRankRow() {
    }

    PageRankRow(String url, double rank, int iterations, OffsetDateTime updatedAt) {
        this.url = url;
        this.rank = rank;
        this.iterations = iterations;
        this.updatedAt = updatedAt;
    }
}
