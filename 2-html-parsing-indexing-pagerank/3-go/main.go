package main

import (
	"context"
	"log"
	"math"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/starci-academy/indexer/internal/indexer"
)

func main() {
	ctx := context.Background()
	dsn := os.Getenv("POSTGRES_URL")
	if dsn == "" {
		dsn = "postgres://indexer:indexer@localhost:5432/indexer"
	}

	var pool *pgxpool.Pool
	var err error
	// Retry: Postgres may still be starting when the service boots.
	for i := 0; i < 30; i++ {
		pool, err = pgxpool.New(ctx, dsn)
		if err == nil {
			if err = pool.Ping(ctx); err == nil {
				break
			}
		}
		time.Sleep(time.Second)
	}
	if err != nil {
		log.Fatalf("cannot connect to postgres: %v", err)
	}
	defer pool.Close()

	store := indexer.NewStore(pool)
	if err := store.Migrate(ctx); err != nil {
		log.Fatalf("migrate failed: %v", err)
	}

	r := gin.Default()

	r.POST("/api/indexer/parse", func(c *gin.Context) {
		var dto struct {
			URL  string `json:"url"`
			HTML string `json:"html"`
		}
		if err := c.ShouldBindJSON(&dto); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"message": err.Error()})
			return
		}
		title, outlinks := indexer.ExtractOutlinks(dto.URL, dto.HTML)
		stored, err := store.ReplaceOutlinks(c, dto.URL, outlinks)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"message": err.Error()})
			return
		}
		if outlinks == nil {
			outlinks = []string{}
		}
		c.JSON(http.StatusOK, gin.H{
			"url": dto.URL, "outlinks": outlinks, "storedLinks": stored, "title": title,
		})
	})

	r.POST("/api/indexer/compute-pagerank", func(c *gin.Context) {
		edges, err := store.AllEdges(c)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"message": err.Error()})
			return
		}
		out := indexer.ComputePageRank(edges)
		if err := store.SaveRanks(c, out.Ranks, out.Iterations); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"message": err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{
			"iterations": out.Iterations, "damping": indexer.Damping,
			"nodes": out.Nodes, "edges": out.Edges, "converged": out.Converged,
		})
	})

	r.GET("/api/indexer/top", func(c *gin.Context) {
		rows, err := store.Top(c, 10)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"message": err.Error()})
			return
		}
		out := make([]gin.H, 0, len(rows))
		for _, row := range rows {
			out = append(out, gin.H{"url": row.URL, "rank": round3(row.Rank)})
		}
		c.JSON(http.StatusOK, out)
	})

	// Use wildcard *url so Gin captures URL-encoded paths containing decoded slashes.
	// c.Param("url") on a wildcard includes the leading slash; strings.TrimPrefix removes it.
	r.GET("/api/indexer/rank/*url", func(c *gin.Context) {
		url := strings.TrimPrefix(c.Param("url"), "/")
		row, err := store.RankOf(c, url)
		if err != nil {
			c.JSON(http.StatusNotFound, gin.H{"statusCode": 404, "message": "URL " + url + " not found"})
			return
		}
		c.JSON(http.StatusOK, gin.H{
			"url": row.URL, "rank": row.Rank, "iterations": row.Iterations,
			"updatedAt": row.UpdatedAt.UTC().Format(time.RFC3339Nano),
		})
	})

	port := os.Getenv("PORT")
	if port == "" {
		port = "3000"
	}
	log.Printf("indexer-service listening on %s", port)
	if err := r.Run(":" + port); err != nil {
		log.Fatal(err)
	}
}

func round3(v float64) float64 {
	return math.Round(v*1000) / 1000
}
