// Package discovery is a thin glue around submodules/mdns/pkg/service
// that publishes lava-api-go's _lava-api._tcp service advertisement on
// the LAN. The TXT records are spec'd in §8.2 of the SP-2 design doc;
// they are byte-equal to what the Android client's discovery layer
// expects from the Go backend (engine=go, protocols=h3,h2, etc.).
//
// Lava-domain choices that live in this file (and explicitly NOT in the
// upstream Mdns submodule, which is consumer-agnostic):
//
//   - The service-type "_lava-api._tcp" is a Lava project name; the Mdns
//     submodule rejects mistakes via validateServiceType but does not
//     have an opinion on what a Lava service is called.
//   - The default port 8443 matches the HTTP/3 listen address used in
//     internal/server and the Docker compose mapping.
//   - The TXT-record set comes from the SP-2 design doc §8.2 and is
//     byte-equal to what the Android client's NsdManager-based
//     LocalNetworkDiscoveryService parses to decide whether to talk
//     HTTP/3 or fall back to HTTP/2 against this endpoint.
//
// Sixth Law note: the upstream submodule's Announce signature is
// `Announce(Announcement) (*Service, error)` — no context. The plan's
// sketch suggested a context.Context arg but the upstream does not take
// one, so we match the upstream signature exactly rather than plumbing
// a parameter that would be discarded. If the upstream surface ever
// grows a context, plumb it through here.
package discovery

import (
	"digital.vasic.lava.apigo/internal/version"
	"digital.vasic.mdns/pkg/service"
)

// DefaultServiceType is the RFC 6763 service-type advertised on the LAN
// by lava-api-go. Android's LocalNetworkDiscoveryService browses for
// exactly this string.
const DefaultServiceType = "_lava-api._tcp"

// DefaultInstanceName is the human-friendly mDNS instance name. Operators
// who run more than one lava-api-go on the same LAN should override this
// per host (e.g. "lava-api-go on living-room-pi").
const DefaultInstanceName = "lava-api-go"

// DefaultPort is the HTTP/3 listen port; matches internal/server defaults
// and the Docker compose UDP exposure.
const DefaultPort = 8443

// Announce publishes the lava-api-go mDNS advertisement. Returns the
// running *service.Service so the caller can call Service.Stop() on
// shutdown.
//
// The TXT-record set is the constant Lava-domain set defined by SP-2
// §8.2 and is NOT caller-overridable — that is intentional: a divergent
// TXT set across deployed lava-api-go instances would be a Sixth Law
// failure (the Android client would silently mis-classify the backend
// engine). Override only `instance`, `serviceType`, and `port`, all of
// which are deployment-configuration concerns; pass the empty-string /
// zero defaults to use the canonical values.
func Announce(instance, serviceType string, port int) (*service.Service, error) {
	if instance == "" {
		instance = DefaultInstanceName
	}
	if serviceType == "" {
		serviceType = DefaultServiceType
	}
	if port == 0 {
		port = DefaultPort
	}
	return service.Announce(service.Announcement{
		Name:        instance,
		ServiceType: serviceType,
		Port:        port,
		TXT: map[string]string{
			"engine":      "go",
			"version":     version.Name,
			"protocols":   "h3,h2",
			"compression": "br,gzip",
			"tls":         "required",
			"path":        "/",
		},
	})
}
