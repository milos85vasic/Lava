package v1

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

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

	handler := NewMultiSearchHandler(reg)
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
	handler := NewMultiSearchHandler(reg)
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

	handler := NewMultiSearchHandler(reg)
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

	handler := NewMultiSearchHandler(reg)
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
