package v1

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// TestTopicPage_Success is the anti-bluff regression for the missing
// GET /v1/{provider}/topic/:id/page route.
//
// User-visible defect (device-proven, 2026-07-02 kinozal keystone): tapping a
// real kinozal search result to open the topic detail failed with
// kotlinx.serialization.MissingFieldException [id, title, author, category,
// torrentData] for TopicPageDto. Root cause: the Android client
// (ApiBackedTrackerClient.getTopicPage) requests `/v1/{provider}/topic/:id/page`
// and decodes the response into the TOLERANT TopicDetailDto (id+title required
// only). The goapi router registered ONLY `/v1/{provider}/topic/:id`, so the
// page request 404'd → the SDK page-source returned null → TopicServiceImpl fell
// back to the LEGACY rutracker-only `/topic2/{id}` whose strict TopicPageDto
// decode threw for any non-rutracker provider.
//
// The chief assertion is on the HTTP response the client consumes: status 200
// (route exists — no 404 fall-through) AND a body carrying the `id` + `title`
// fields the client's TopicDetailDto decode requires.
//
// FALSIFIABILITY REHEARSAL (Bluff-Audit ready):
//
//	Mutation: remove the `group.GET("/topic/:id/page", ...)` registration in
//	  handlers.go (the production route this test covers).
//	Observed-Failure: `topic/:id/page route: expected 200, got 404` — the
//	  recorder returns 404 because gin has no matching route, exactly the
//	  production 404 that triggered the legacy fallback + MissingFieldException.
//	Reverted: yes.
func TestTopicPage_Success(t *testing.T) {
	fp := &fakeProvider{
		id: "test",
		topicResult: &provider.TopicResult{
			Provider: "test",
			ID:       "2145735",
			Title:    "Topic Page Title",
		},
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/topic/2145735/page?page=2", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("topic/:id/page route: expected 200, got %d: %s", w.Code, w.Body.String())
	}

	// Assert the response body carries exactly the fields the client's tolerant
	// TopicDetailDto decode requires (id + title). A body missing these would let
	// the client fall through to the strict legacy TopicPageDto path.
	var result provider.TopicResult
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatalf("topic/:id/page body did not decode as TopicResult: %v (body=%s)", err, w.Body.String())
	}
	if result.ID != "2145735" {
		t.Errorf("topic/:id/page body id = %q, want %q", result.ID, "2145735")
	}
	if result.Title != "Topic Page Title" {
		t.Errorf("topic/:id/page body title = %q, want %q", result.Title, "Topic Page Title")
	}
}

// TestTopicPage_PassesPageQuery confirms the page query the client sends
// (ApiBackedTrackerClient.getTopicPage adds `?page=N`) reaches the provider's
// GetTopic(ctx, id, page, cred) method — the same provider method the
// non-paged /topic/:id route uses. This guards against the route being wired to
// a handler that drops the page number.
//
// FALSIFIABILITY REHEARSAL:
//
//	Mutation: hardcode `page := 1` in GetTopicPage (ignore the query param).
//	Observed-Failure: `GetTopic received page = 1, want 3`.
//	Reverted: yes.
func TestTopicPage_PassesPageQuery(t *testing.T) {
	var gotPage int
	fp := &pageCapturingProvider{
		fakeProvider: fakeProvider{
			id:          "test",
			topicResult: &provider.TopicResult{Provider: "test", ID: "7", Title: "T"},
		},
		onGetTopic: func(page int) { gotPage = page },
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/topic/7/page?page=3", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}
	if gotPage != 3 {
		t.Errorf("GetTopic received page = %d, want 3", gotPage)
	}
}

// pageCapturingProvider wraps fakeProvider to record the page argument passed to
// GetTopic (the fake in handlers_test.go ignores page).
type pageCapturingProvider struct {
	fakeProvider
	onGetTopic func(page int)
}

func (p *pageCapturingProvider) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	if p.onGetTopic != nil {
		p.onGetTopic(page)
	}
	return p.topicResult, nil
}
