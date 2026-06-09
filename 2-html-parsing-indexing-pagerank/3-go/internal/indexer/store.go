package indexer

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Store wraps the Postgres connection pool and the page_link / page_rank tables.
type Store struct {
	pool *pgxpool.Pool
}

func NewStore(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool}
}

// Migrate creates the schema and seeds the demo graph when page_link is empty.
func (s *Store) Migrate(ctx context.Context) error {
	_, err := s.pool.Exec(ctx, `
		CREATE TABLE IF NOT EXISTS page_link (
			id BIGSERIAL PRIMARY KEY,
			from_url TEXT NOT NULL,
			to_url TEXT NOT NULL,
			UNIQUE (from_url, to_url)
		);
		CREATE TABLE IF NOT EXISTS page_rank (
			url TEXT PRIMARY KEY,
			rank DOUBLE PRECISION NOT NULL,
			iterations INT NOT NULL,
			updated_at TIMESTAMPTZ NOT NULL
		);`)
	if err != nil {
		return err
	}
	return s.seed(ctx)
}

var seedEdges = [][2]string{
	{"a", "b"}, {"a", "c"}, {"b", "c"}, {"c", "a"},
	{"d", "c"}, {"d", "b"}, {"e", "d"},
}

const base = "https://search.starci.test/"

func (s *Store) seed(ctx context.Context) error {
	var count int
	if err := s.pool.QueryRow(ctx, "SELECT COUNT(*) FROM page_link").Scan(&count); err != nil {
		return err
	}
	if count > 0 {
		return nil
	}
	for _, e := range seedEdges {
		if _, err := s.pool.Exec(ctx,
			"INSERT INTO page_link (from_url, to_url) VALUES ($1, $2) ON CONFLICT DO NOTHING",
			base+e[0], base+e[1]); err != nil {
			return err
		}
	}
	return nil
}

// ReplaceOutlinks performs DELETE-then-INSERT so a re-parse replaces the set.
func (s *Store) ReplaceOutlinks(ctx context.Context, fromURL string, outlinks []string) (int, error) {
	// DELETE existing edges from this URL BEFORE inserting the new set:
	// both run in ONE transaction so a re-parse REPLACES, never appends.
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, "DELETE FROM page_link WHERE from_url = $1", fromURL); err != nil {
		return 0, err
	}
	stored := 0
	for _, to := range outlinks {
		if _, err := tx.Exec(ctx,
			"INSERT INTO page_link (from_url, to_url) VALUES ($1, $2) ON CONFLICT DO NOTHING",
			fromURL, to); err != nil {
			return 0, err
		}
		stored++
	}
	return stored, tx.Commit(ctx)
}

func (s *Store) AllEdges(ctx context.Context) ([]Edge, error) {
	rows, err := s.pool.Query(ctx, "SELECT from_url, to_url FROM page_link")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var edges []Edge
	for rows.Next() {
		var e Edge
		if err := rows.Scan(&e.FromURL, &e.ToURL); err != nil {
			return nil, err
		}
		edges = append(edges, e)
	}
	return edges, rows.Err()
}

func (s *Store) SaveRanks(ctx context.Context, ranks map[string]float64, iterations int) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, "TRUNCATE TABLE page_rank"); err != nil {
		return err
	}
	now := time.Now().UTC()
	for url, r := range ranks {
		if _, err := tx.Exec(ctx,
			"INSERT INTO page_rank (url, rank, iterations, updated_at) VALUES ($1, $2, $3, $4)",
			url, r, iterations, now); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

type RankRow struct {
	URL        string
	Rank       float64
	Iterations int
	UpdatedAt  time.Time
}

func (s *Store) Top(ctx context.Context, limit int) ([]RankRow, error) {
	rows, err := s.pool.Query(ctx,
		"SELECT url, rank, iterations, updated_at FROM page_rank ORDER BY rank DESC LIMIT $1", limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []RankRow
	for rows.Next() {
		var r RankRow
		if err := rows.Scan(&r.URL, &r.Rank, &r.Iterations, &r.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *Store) RankOf(ctx context.Context, url string) (*RankRow, error) {
	var r RankRow
	err := s.pool.QueryRow(ctx,
		"SELECT url, rank, iterations, updated_at FROM page_rank WHERE url = $1", url).
		Scan(&r.URL, &r.Rank, &r.Iterations, &r.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return &r, nil
}
