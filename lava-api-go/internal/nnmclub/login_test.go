package nnmclub

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

func TestIsAuthorised_LoggedIn(t *testing.T) {
	html := loadFixture(t, "login", "index_logged_in.html")
	if !IsAuthorised(html) {
		t.Error("expected IsAuthorised=true for logged-in page")
	}
}

func TestIsAuthorised_Anonymous(t *testing.T) {
	html := loadFixture(t, "login", "index_anon.html")
	if IsAuthorised(html) {
		t.Error("expected IsAuthorised=false for anonymous page")
	}
}

func TestLogin_Success(t *testing.T) {
	fixture := loadFixture(t, "login", "index_logged_in.html")
	var capturedBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		capturedBody = string(body)
		w.Header().Set("Set-Cookie", "phpbb2mysql_4_data=session-abc; Path=/")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Login(context.Background(), provider.LoginOpts{
		Username: "testuser",
		Password: "testpass",
	})
	if err != nil {
		t.Fatalf("Login error: %v", err)
	}
	if !result.Success {
		t.Error("expected Success=true")
	}
	if result.AuthToken == "" {
		t.Error("expected non-empty AuthToken")
	}
	if !strings.Contains(capturedBody, "username=testuser") {
		t.Errorf("body should contain username=testuser, was: %s", capturedBody)
	}
	if !strings.Contains(capturedBody, "password=testpass") {
		t.Errorf("body should contain password=testpass, was: %s", capturedBody)
	}
}

func TestLogin_Unauthorized(t *testing.T) {
	fixture := loadFixture(t, "login", "index_anon.html")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.Login(context.Background(), provider.LoginOpts{
		Username: "bad",
		Password: "bad",
	})
	if err == nil {
		t.Fatal("expected error for failed login")
	}
	if err != provider.ErrUnauthorized {
		t.Errorf("expected ErrUnauthorized, got %v", err)
	}
}
