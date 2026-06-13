package kinozal

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestParseTopicPage(t *testing.T) {
	html := loadTestData("topic/topic.html")
	result, err := ParseTopicPage(html)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if result.Title != "Test Movie 2024" {
		t.Errorf("expected title 'Test Movie 2024', got %s", result.Title)
	}
	if result.Description != "Description of the movie goes here." {
		t.Errorf("expected description, got %s", result.Description)
	}
	if result.MagnetLink != "magnet:?xt=urn:btih:ABC123DEF4567890ABC123DEF4567890ABC1234" {
		t.Errorf("unexpected magnet: %s", result.MagnetLink)
	}
}

func TestClientGetTopic(t *testing.T) {
	html := loadTestData("topic/topic.html")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/details.php" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if r.URL.Query().Get("id") != "12345" {
			t.Errorf("unexpected id: %s", r.URL.Query().Get("id"))
		}
		w.WriteHeader(http.StatusOK)
		w.Write(html)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.GetTopic(context.Background(), "12345", "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.ID != "12345" {
		t.Errorf("expected id 12345, got %s", result.ID)
	}
}

// TestParseTopicPage_PrefersH1OverTitleTag closes a discrimination gap surfaced
// by the 2026-06-13 §6.N go-parsers bluff hunt. ParseTopicPage reads the topic
// title from <h1> first and only falls back to the <title> tag when <h1> is
// empty (topic.go lines 22-25). The existing topic.html fixture carries the
// SAME text in both <title> and <h1>, so a mutation that drops the h1-first
// priority (reading the title straight from <title>) left every kinozal test
// green — a §6.J bluff: the user would be shown the site-suffixed page title
// instead of the clean topic name, and no test would notice.
//
// This test gives <title> a site-suffixed value ("...:: Kinozal.TV") distinct
// from the clean <h1> ("Inception 2010 BDRip"), then asserts the user-visible
// Title equals the <h1> text. It also keeps the magnet + description assertions
// so the whole user-visible TopicResult is pinned.
//
// FALSIFIABILITY (rehearsed 2026-06-13): replacing the h1-first block with a
// straight `doc.Find("title")` read makes Title = the site-suffixed string →
// this test FAILS with
//
//	"Title = \"Inception 2010 BDRip :: Kinozal.TV\", want clean <h1> text".
//
// Reverting restores green.
func TestParseTopicPage_PrefersH1OverTitleTag(t *testing.T) {
	const html = `<html>
<head><title>Inception 2010 BDRip :: Kinozal.TV</title></head>
<body>
  <h1>Inception 2010 BDRip</h1>
  <a href="magnet:?xt=urn:btih:FACEFEED0001" class="magnet">Magnet</a>
  <div class="content">A thief who steals corporate secrets.</div>
  <a href="details.php?id=98765">self</a>
</body>
</html>`
	result, err := ParseTopicPage([]byte(html))
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if result.Title != "Inception 2010 BDRip" {
		t.Errorf("Title = %q, want clean <h1> text \"Inception 2010 BDRip\" (not the <title> site-suffixed string)", result.Title)
	}
	if result.MagnetLink != "magnet:?xt=urn:btih:FACEFEED0001" {
		t.Errorf("MagnetLink = %q, want the magnet href", result.MagnetLink)
	}
	if result.Description != "A thief who steals corporate secrets." {
		t.Errorf("Description = %q, want the div.content text", result.Description)
	}
	if result.ID != "98765" {
		t.Errorf("ID = %q, want 98765 (parsed from details.php?id=)", result.ID)
	}
}
