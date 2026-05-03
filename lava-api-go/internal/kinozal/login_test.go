package kinozal

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestClientLoginSuccess(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/takelogin.php" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		http.SetCookie(w, &http.Cookie{Name: "uid", Value: "123"})
		http.SetCookie(w, &http.Cookie{Name: "pass", Value: "secret"})
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Login(context.Background(), "user", "pass")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !result.Success {
		t.Fatal("expected success")
	}
	if result.AuthToken == "" {
		t.Fatal("expected auth token")
	}
}

func TestClientLoginFailure(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write(loadTestData("login/failure.html"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.Login(context.Background(), "user", "wrong")
	if err == nil {
		t.Fatal("expected error")
	}
}
