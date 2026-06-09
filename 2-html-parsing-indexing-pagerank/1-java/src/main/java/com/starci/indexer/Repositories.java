package com.starci.indexer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

interface PageLinkRepository extends JpaRepository<PageLink, Long> {
    @Modifying
    @Transactional
    @Query("DELETE FROM PageLink p WHERE p.fromUrl = :fromUrl")
    void deleteByFromUrl(@Param("fromUrl") String fromUrl);
}

interface PageRankRepository extends JpaRepository<PageRankRow, String> {
    List<PageRankRow> findTop10ByOrderByRankDesc();
}
