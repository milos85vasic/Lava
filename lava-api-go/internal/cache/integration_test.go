package cache_test

import (
	"context"
	"os"
	"testing"
	"time"

	pgcache "digital.vasic.cache/pkg/postgres"
	"digital.vasic.lava.apigo/internal/cache"
)

func mustClient(t *testing.T) (*cache.Client, func()) {
	t.Helper()
	url := os.Getenv("POSTGRES_TEST_URL")
	if url == "" {
		t.Skip("POSTGRES_TEST_URL not set; run scripts/run-test-pg.sh")
	}
	inner, err := pgcache.ConnectFromURL(context.Background(), &pgcache.Config{
		URL:        url,
		SchemaName: "lava_api_test",
		TableName:  "response_cache_t",
		GCInterval: 0,
	})
	if err != nil {
		t.Fatalf("ConnectFromURL: %v", err)
	}
	if err := inner.CreateSchema(context.Background()); err != nil {
		_ = inner.Close()
		t.Fatalf("CreateSchema: %v", err)
	}
	return cache.New(inner), func() {
		_, _ = inner.Underlying().Exec(context.Background(),
			`DROP SCHEMA IF EXISTS lava_api_test CASCADE`)
		_ = inner.Close()
	}
}

func TestSetGetReturnsHit(t *testing.T) {
	c, cleanup := mustClient(t)
	defer cleanup()
	ctx := context.Background()
	if err := c.Set(ctx, "k", []byte("v"), time.Minute); err != nil {
		t.Fatalf("Set: %v", err)
	}
	got, outcome, err := c.Get(ctx, "k")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if outcome != cache.OutcomeHit {
		t.Errorf("outcome=%q want hit", outcome)
	}
	if string(got) != "v" {
		t.Errorf("got=%q want v", string(got))
	}
}

func TestGetMissReturnsMiss(t *testing.T) {
	c, cleanup := mustClient(t)
	defer cleanup()
	got, outcome, err := c.Get(context.Background(), "absent")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if outcome != cache.OutcomeMiss {
		t.Errorf("outcome=%q want miss", outcome)
	}
	if got != nil {
		t.Errorf("got=%q want nil", string(got))
	}
}

func TestInvalidate(t *testing.T) {
	c, cleanup := mustClient(t)
	defer cleanup()
	ctx := context.Background()
	_ = c.Set(ctx, "k", []byte("v"), time.Minute)
	if err := c.Invalidate(ctx, "k"); err != nil {
		t.Fatalf("Invalidate: %v", err)
	}
	_, outcome, _ := c.Get(ctx, "k")
	if outcome != cache.OutcomeMiss {
		t.Errorf("outcome after Invalidate=%q want miss", outcome)
	}
}
