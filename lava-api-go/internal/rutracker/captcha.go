// Package rutracker — captcha.go ports the captcha-image proxy from the
// Kotlin Auth route (proxy/.../routes/Auth.kt:40-48):
//
//	get("/captcha/{path}") {
//	    val url = Base64.UrlSafe.decode(path).decodeToString()
//	    val response = httpClient.get(url)
//	    call.respondBytes(
//	        status = HttpStatusCode.OK,
//	        contentType = response.contentType(),
//	        bytes = response.readBytes(),
//	    )
//	}
//
// The captcha image lives on an arbitrary upstream URL (rutracker hosts
// captchas on auxiliary domains like static.t-ru.org), so the flow is:
// the Android client receives a CaptchaDto whose `url` is encoded into
// the API path with URL-safe Base64; this package decodes it back to
// the absolute URL, fetches the image bytes, and returns them verbatim
// alongside the upstream Content-Type.
package rutracker

import (
	"context"
	"encoding/base64"
	"fmt"
)

// CaptchaImage carries the captcha image bytes alongside the upstream
// Content-Type header so the Phase 7 handler can stream both back to
// the API client without re-encoding.
type CaptchaImage struct {
	ContentType string
	Bytes       []byte
}

// DecodeCaptchaPath URL-safe-Base64-decodes the `path` route parameter
// into the upstream captcha image URL. Mirrors the Kotlin Auth route's
// `Base64.UrlSafe.decode(path).decodeToString()`. Returns ("", false) on
// decode failure.
//
// Kotlin's Base64.UrlSafe accepts both padded and unpadded inputs (it
// follows the RFC 4648 §5 alphabet and treats trailing '=' as optional).
// Go's encoding/base64 splits the difference into two encoders —
// URLEncoding (with padding) and RawURLEncoding (without). We try the
// padded form first, then fall back to the unpadded form, so callers on
// either side of the padding question Just Work.
func DecodeCaptchaPath(path string) (string, bool) {
	if decoded, err := base64.URLEncoding.DecodeString(path); err == nil {
		return string(decoded), true
	}
	if decoded, err := base64.RawURLEncoding.DecodeString(path); err == nil {
		return string(decoded), true
	}
	return "", false
}

// FetchCaptcha downloads the captcha image at the URL encoded in
// `encodedPath` and returns the bytes plus the upstream Content-Type
// header. The path is the URL-safe Base64 representation accepted by the
// API route — DecodeCaptchaPath unpacks it before fetching.
//
// No cookie or auth is attached: rutracker captcha images are public
// per the upstream's design (the same image is served to anonymous and
// authenticated requests).
//
// The returned bytes are the EXACT upstream payload — no transcoding —
// so the API handler can proxy them straight back to the API client.
func (c *Client) FetchCaptcha(ctx context.Context, encodedPath string) (*CaptchaImage, error) {
	fullURL, ok := DecodeCaptchaPath(encodedPath)
	if !ok || fullURL == "" {
		return nil, fmt.Errorf("rutracker: invalid captcha path %q", encodedPath)
	}
	body, status, headers, err := c.GetURL(ctx, fullURL, "")
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("rutracker: GET %s → %d", fullURL, status)
	}
	return &CaptchaImage{
		ContentType: headers.Get("Content-Type"),
		Bytes:       body,
	}, nil
}
