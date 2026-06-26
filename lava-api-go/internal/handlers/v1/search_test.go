package v1

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

// TestGetSearch_BoundsSlowUpstreamByDeadline is the LVA-083 H2 regression test.
//
// It proves the single-provider GetSearch handler caps a slow upstream with a
// server-side deadline that is strictly shorter than the Android client's 30s
// OkHttp readTimeout, so the user gets a fast error ("try again") instead of a
// SocketTimeoutException ("Something went wrong"). The fake provider blocks for
// 3s but honours ctx cancellation (like a real HTTP client); the handler's
// deadline is set (via Deps.SearchTimeout) to 150ms, so the request MUST return
// well under both the 3s upstream block and the 30s client socket timeout.
//
// PRIMARY assertion (user-visible): the HTTP status the client receives is 502
// (the writeProviderError default mapping for context.DeadlineExceeded) AND the
// request returns in well under 1s — NOT a 30s hang.
//
// FALSIFIABILITY REHEARSAL (Sixth Law clause 2, §6.J clause 2):
//
//	Mutation: in search.go GetSearch, replace `h.searchTimeout` with a
//	  no-deadline path (use c.Request.Context() directly, no WithTimeout).
//	Observed: TestGetSearch_BoundsSlowUpstreamByDeadline FAILS — the handler
//	  no longer caps the upstream, so the fake's 3s block runs to completion,
//	  the request returns 200 after ~3s, and the test reports
//	  "handler did not return within deadline: elapsed=3.0s ... status=200 want 502".
//	Reverted: yes (production code restored; final commit unmutated).
func TestGetSearch_BoundsSlowUpstreamByDeadline(t *testing.T) {
	gin.SetMode(gin.TestMode)

	// upstream blocks far longer than the handler deadline, but respects ctx.
	const upstreamBlock = 3 * time.Second
	const handlerDeadline = 150 * time.Millisecond
	// The Android client's OkHttp readTimeout (the bound this deadline protects
	// against exceeding). The handler MUST return well under this.
	const clientSocketTimeout = 30 * time.Second

	fp := &fakeProvider{
		id:          "test",
		searchBlock: upstreamBlock,
		searchResult: &provider.SearchResult{
			Provider: "test", Page: 1, TotalPages: 1,
			Results: []provider.SearchItem{{ID: "1", Title: "should-never-be-returned"}},
		},
	}

	router := gin.New()
	group := router.Group("/v1/:provider")
	group.Use(func(c *gin.Context) {
		c.Set("__provider__", fp)
		c.Next()
	})
	Register(group, &Deps{Cache: newFakeCache(), SearchTimeout: handlerDeadline}, nil)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=x", nil)

	start := time.Now()
	router.ServeHTTP(w, req)
	elapsed := time.Since(start)

	// The deadline MUST have cut the upstream short (returned before the 3s
	// block could finish) AND well before the 30s client socket timeout.
	if elapsed >= upstreamBlock {
		t.Fatalf("handler did not return within deadline: elapsed=%s — the server-side "+
			"deadline did not bound the slow upstream (LVA-083 H2 regression)", elapsed)
	}
	if elapsed >= clientSocketTimeout {
		t.Fatalf("handler returned in %s — exceeds the client socket timeout %s; the user "+
			"would see a SocketTimeoutException ('Something went wrong')", elapsed, clientSocketTimeout)
	}
	// PRIMARY user-visible assertion: a deadline trip surfaces as the
	// writeProviderError default mapping (502 BadGateway), NOT a 200 carrying
	// the upstream result the deadline was supposed to abort.
	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want %d (context.DeadlineExceeded → BadGateway); elapsed=%s body=%s",
			w.Code, http.StatusBadGateway, elapsed, w.Body.String())
	}
}

func assertEventPresent(t *testing.T, events []string, eventType string) {
	t.Helper()
	for _, e := range events {
		if e == eventType {
			return
		}
	}
	t.Errorf("expected event type %q not found in %v", eventType, events)
}

func TestMultiSearchHandler_StreamsResultsForRegisteredProviders(t *testing.T) {
	gin.SetMode(gin.TestMode)

	reg := provider.NewRegistry()
	reg.Register(&fakeProvider{id: "test1", name: "Test One", searchResult: &provider.SearchResult{
		Provider:   "test1",
		Page:       1,
		TotalPages: 1,
		Results:    []provider.SearchItem{{ID: "1", Title: "Item One"}},
	}})
	reg.Register(&fakeProvider{id: "test2", name: "Test Two", searchResult: &provider.SearchResult{
		Provider:   "test2",
		Page:       1,
		TotalPages: 1,
		Results:    []provider.SearchItem{{ID: "2", Title: "Item Two"}},
	}})

	handler := NewMultiSearchHandler(reg, 0)
	router := gin.New()
	router.GET("/v1/search", handler.GetMultiSearch)

	srv := httptest.NewServer(router)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/v1/search?q=test&providers=test1,test2")
	if err != nil {
		t.Fatalf("http.Get: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	ct := resp.Header.Get("Content-Type")
	if !strings.Contains(ct, "text/event-stream") {
		t.Fatalf("expected text/event-stream, got %s", ct)
	}

	scanner := bufio.NewScanner(resp.Body)
	var events []map[string]string
	current := make(map[string]string)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			if len(current) > 0 {
				events = append(events, current)
				current = make(map[string]string)
			}
			continue
		}
		if strings.HasPrefix(line, "event: ") {
			current["event"] = strings.TrimPrefix(line, "event: ")
		}
		if strings.HasPrefix(line, "data: ") {
			current["data"] = strings.TrimPrefix(line, "data: ")
		}
	}
	if len(current) > 0 {
		events = append(events, current)
	}

	if len(events) < 7 {
		t.Fatalf("expected at least 7 events, got %d: %v", len(events), events)
	}

	eventTypes := make([]string, len(events))
	for i, e := range events {
		eventTypes[i] = e["event"]
	}

	assertEventPresent(t, eventTypes, "provider_start")
	assertEventPresent(t, eventTypes, "results")
	assertEventPresent(t, eventTypes, "provider_done")
	assertEventPresent(t, eventTypes, "stream_end")

	for _, e := range events {
		if e["event"] == "stream_end" {
			var end map[string]interface{}
			json.Unmarshal([]byte(e["data"]), &end)
			if end["providers_searched"] != float64(2) {
				t.Errorf("expected 2 searched, got %v", end["providers_searched"])
			}
			if end["total_results"] != float64(2) {
				t.Errorf("expected 2 total_results, got %v", end["total_results"])
			}
		}
	}
}

func TestMultiSearchHandler_MissingQuery(t *testing.T) {
	gin.SetMode(gin.TestMode)

	reg := provider.NewRegistry()
	handler := NewMultiSearchHandler(reg, 0)
	router := gin.New()
	router.GET("/v1/search", handler.GetMultiSearch)

	req := httptest.NewRequest(http.MethodGet, "/v1/search", nil)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400, got %d", w.Code)
	}
}

func TestMultiSearchHandler_DefaultsToAllWhenNoProvidersParam(t *testing.T) {
	gin.SetMode(gin.TestMode)

	reg := provider.NewRegistry()
	reg.Register(&fakeProvider{id: "auto1", name: "Auto One", searchResult: &provider.SearchResult{
		Provider:   "auto1",
		Page:       1,
		TotalPages: 1,
		Results:    []provider.SearchItem{{ID: "a1", Title: "Auto Item"}},
	}})

	handler := NewMultiSearchHandler(reg, 0)
	router := gin.New()
	router.GET("/v1/search", handler.GetMultiSearch)

	srv := httptest.NewServer(router)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/v1/search?q=test")
	if err != nil {
		t.Fatalf("http.Get: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	scanner := bufio.NewScanner(resp.Body)
	body := ""
	for scanner.Scan() {
		body += scanner.Text() + "\n"
	}
	if !strings.Contains(body, "provider_start") {
		t.Error("expected provider_start in response")
	}
}

func TestMultiSearchHandler_ProviderErrorEmitted(t *testing.T) {
	gin.SetMode(gin.TestMode)

	reg := provider.NewRegistry()
	reg.Register(&fakeProvider{id: "failing", name: "Failing", searchErr: fmt.Errorf("upstream down")})

	handler := NewMultiSearchHandler(reg, 0)
	router := gin.New()
	router.GET("/v1/search", handler.GetMultiSearch)

	srv := httptest.NewServer(router)
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/v1/search?q=test&providers=failing")
	if err != nil {
		t.Fatalf("http.Get: %v", err)
	}
	defer resp.Body.Close()

	scanner := bufio.NewScanner(resp.Body)
	body := ""
	for scanner.Scan() {
		body += scanner.Text() + "\n"
	}
	if !strings.Contains(body, "provider_error") {
		t.Error("expected provider_error in response")
	}
	if !strings.Contains(body, "upstream down") {
		t.Error("expected error message in response")
	}
}

// TestMultiSearchHandler_BoundsSlowProviderByDeadline proves the config-driven
// per-provider deadline (§6.AK §6.4, replacing the old bare 30s literal) bounds
// a slow upstream INSIDE the SSE fan-out: a provider that blocks far longer than
// the configured deadline is cut short and surfaced as a `provider_error` event
// (with context.DeadlineExceeded), and the stream completes well under the
// upstream block — it does NOT hang for the full block duration.
//
// The fake provider blocks for 3s but honours ctx cancellation (like a real HTTP
// client); NewMultiSearchHandler is given a 150ms per-provider timeout, so the
// stream MUST emit the provider's error + stream_end well under 3s.
//
// PRIMARY assertions (user-visible SSE stream): a `provider_error` event for the
// slow provider is present, the stream_end aggregate reports providers_failed=1,
// and the whole request returns in well under the 3s upstream block.
//
// FALSIFIABILITY REHEARSAL (Sixth Law clause 2, §6.J clause 2):
//
//	Mutation: in search.go GetMultiSearch, replace `h.perProviderTimeout` with a
//	  no-deadline path (`ctx, cancel := context.WithCancel(c.Request.Context())`).
//	Observed: TestMultiSearchHandler_BoundsSlowProviderByDeadline FAILS — the
//	  per-provider call is never bounded, the fake's 3s block runs to completion,
//	  the provider returns its results (not an error), so providers_failed=0 and
//	  the request takes ~3s; the test reports
//	  "stream did not bound slow provider: elapsed=3.0s ... providers_failed=0 want 1".
//	Reverted: yes (production code restored; final commit unmutated).
func TestMultiSearchHandler_BoundsSlowProviderByDeadline(t *testing.T) {
	gin.SetMode(gin.TestMode)

	const upstreamBlock = 3 * time.Second
	const perProviderTimeout = 150 * time.Millisecond

	reg := provider.NewRegistry()
	reg.Register(&fakeProvider{
		id:          "slow",
		name:        "Slow Provider",
		searchBlock: upstreamBlock,
		searchResult: &provider.SearchResult{
			Provider: "slow", Page: 1, TotalPages: 1,
			Results: []provider.SearchItem{{ID: "1", Title: "should-never-be-returned"}},
		},
	})

	handler := NewMultiSearchHandler(reg, perProviderTimeout)
	router := gin.New()
	router.GET("/v1/search", handler.GetMultiSearch)

	srv := httptest.NewServer(router)
	defer srv.Close()

	start := time.Now()
	resp, err := http.Get(srv.URL + "/v1/search?q=test&providers=slow")
	if err != nil {
		t.Fatalf("http.Get: %v", err)
	}
	defer resp.Body.Close()

	// Parse the SSE stream into (event, data) records.
	scanner := bufio.NewScanner(resp.Body)
	var events []map[string]string
	current := make(map[string]string)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			if len(current) > 0 {
				events = append(events, current)
				current = make(map[string]string)
			}
			continue
		}
		if strings.HasPrefix(line, "event: ") {
			current["event"] = strings.TrimPrefix(line, "event: ")
		}
		if strings.HasPrefix(line, "data: ") {
			current["data"] = strings.TrimPrefix(line, "data: ")
		}
	}
	if len(current) > 0 {
		events = append(events, current)
	}
	elapsed := time.Since(start)

	// The deadline MUST have cut the slow provider short — the whole stream
	// returns well before the 3s upstream block could finish.
	if elapsed >= upstreamBlock {
		t.Fatalf("stream did not bound slow provider: elapsed=%s — the per-provider "+
			"deadline did not cap the slow upstream (§6.AK §6.4 regression)", elapsed)
	}

	// PRIMARY user-visible assertion #1: the slow provider surfaces as a
	// provider_error event carrying its id (deadline trip → ctx.Err()).
	var providerErr map[string]string
	for _, e := range events {
		if e["event"] == "provider_error" {
			var status providerStreamStatus
			if jerr := json.Unmarshal([]byte(e["data"]), &status); jerr == nil && status.ProviderID == "slow" {
				providerErr = e
			}
		}
	}
	if providerErr == nil {
		t.Fatalf("expected a provider_error event for the slow provider within the deadline; "+
			"elapsed=%s events=%v", elapsed, events)
	}

	// PRIMARY user-visible assertion #2: stream_end aggregate counts the slow
	// provider as failed, and returns zero results (the upstream payload was
	// aborted, never streamed).
	var sawStreamEnd bool
	for _, e := range events {
		if e["event"] != "stream_end" {
			continue
		}
		sawStreamEnd = true
		var end map[string]interface{}
		if jerr := json.Unmarshal([]byte(e["data"]), &end); jerr != nil {
			t.Fatalf("stream_end data not JSON: %v (data=%s)", jerr, e["data"])
		}
		if end["providers_failed"] != float64(1) {
			t.Fatalf("stream did not bound slow provider: elapsed=%s providers_failed=%v want 1 "+
				"(the deadline trip MUST count the provider as failed, not as a success)",
				elapsed, end["providers_failed"])
		}
		if end["total_results"] != float64(0) {
			t.Fatalf("expected total_results=0 (aborted upstream), got %v", end["total_results"])
		}
	}
	if !sawStreamEnd {
		t.Fatalf("expected a stream_end event; elapsed=%s events=%v", elapsed, events)
	}
}
