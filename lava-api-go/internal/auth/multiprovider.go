// Package auth implements multi-provider credential extraction.
//
// The Auth-Token header format is extended from a bare cookie/token to:
//
//	provider_id:credential_type:credential_value
//
// Examples:
//
//	rutracker:cookie:bb_session=0-12345-...
//	rutor:cookie:...
//	nnmclub:password:username:password
//	archiveorg:none
//	gutenberg:apikey:my-key
//
// When the header does not contain a colon, it is treated as a legacy
// bare cookie forwarded verbatim (backward compatibility with existing
// Android clients).
package auth

import (
	"net/http"
	"strings"

	"digital.vasic.lava.apigo/internal/provider"
)

// ParsedCredentials is the result of parsing an Auth-Token header.
type ParsedCredentials struct {
	ProviderID string
	Creds      provider.Credentials
}

// ParseAuthToken parses the Auth-Token header into provider credentials.
// Returns nil if the header is empty or malformed.
func ParseAuthToken(header string) *ParsedCredentials {
	if header == "" {
		return nil
	}

	// Legacy bare cookie (no colons) — treat as rutracker cookie for
	// backward compatibility.
	if !strings.Contains(header, ":") {
		return &ParsedCredentials{
			ProviderID: "rutracker",
			Creds: provider.Credentials{
				Type:        "cookie",
				CookieValue: header,
			},
		}
	}

	parts := strings.SplitN(header, ":", 3)
	if len(parts) < 2 {
		return nil
	}

	providerID := parts[0]
	credType := parts[1]
	credValue := ""
	if len(parts) >= 3 {
		credValue = parts[2]
	}

	creds := provider.Credentials{Type: credType}
	switch credType {
	case "cookie":
		creds.CookieValue = credValue
	case "token":
		creds.Token = credValue
	case "apikey":
		creds.APIKey = credValue
	case "password":
		// password type has format provider:password:username:password
		pwParts := strings.SplitN(credValue, ":", 2)
		if len(pwParts) == 2 {
			creds.Username = pwParts[0]
			creds.Password = pwParts[1]
		}
	case "none":
		// anonymous
	}

	return &ParsedCredentials{
		ProviderID: providerID,
		Creds:      creds,
	}
}

// ProviderCredentials extracts provider credentials from an HTTP request.
func ProviderCredentials(r *http.Request) *ParsedCredentials {
	tok := r.Header.Get(HeaderName)
	return ParseAuthToken(tok)
}
