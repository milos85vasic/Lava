package observability

import (
	"context"
	"fmt"

	obstrace "digital.vasic.observability/pkg/trace"
)

// TracerConfig configures the OTel tracer.
type TracerConfig struct {
	ServiceName    string
	ServiceVersion string
	Environment    string  // "development", "production", etc.
	OTLPEndpoint   string  // empty = no exporter (NoOp tracer)
	SampleRate     float64 // 0..1; <=0 → never sample, >=1 → always sample
}

// Tracer wraps the upstream tracer so callers don't have to import
// the upstream package directly.
//
// Adaptation note: the plan template referenced
// `obstrace.ExporterOTLPHTTP` and `obstrace.InitTracer(&obstrace.TracerConfig{
// ExporterEndpoint: ...})`. The actual upstream constants are
// `ExporterOTLP` (HTTP transport is the only one currently
// implemented for the OTLP family in upstream `setupOTLPExporter`)
// and the field name is `Endpoint`, not `ExporterEndpoint`. A nil /
// empty endpoint maps to `ExporterNone`, producing a no-op tracer.
type Tracer struct {
	t *obstrace.Tracer
}

// NewTracer creates and registers a global tracer provider per the
// supplied config.
func NewTracer(_ context.Context, cfg TracerConfig) (*Tracer, error) {
	if cfg.SampleRate <= 0 {
		cfg.SampleRate = 1.0
	}
	upstreamCfg := &obstrace.TracerConfig{
		ServiceName:    cfg.ServiceName,
		ServiceVersion: cfg.ServiceVersion,
		Environment:    cfg.Environment,
		ExporterType:   exporterFor(cfg.OTLPEndpoint),
		Endpoint:       cfg.OTLPEndpoint,
		SampleRate:     cfg.SampleRate,
		Insecure:       true, // OTLP collector is always reached over a private network
	}
	t, err := obstrace.InitTracer(upstreamCfg)
	if err != nil {
		return nil, fmt.Errorf("observability: tracer init: %w", err)
	}
	return &Tracer{t: t}, nil
}

// Shutdown flushes any pending spans. It is safe to call on a nil-or-
// zero-value Tracer (Shutdown becomes a no-op).
func (tr *Tracer) Shutdown(ctx context.Context) error {
	if tr == nil || tr.t == nil {
		return nil
	}
	return tr.t.Shutdown(ctx)
}

// Upstream returns the wrapped upstream tracer for callers that need
// to start spans. Exposing the upstream type here is acceptable
// because `*obstrace.Tracer` IS the public lava-api-go tracing
// surface — re-implementing every Start* method would be churn for
// no decoupling benefit.
func (tr *Tracer) Upstream() *obstrace.Tracer {
	if tr == nil {
		return nil
	}
	return tr.t
}

func exporterFor(endpoint string) obstrace.ExporterType {
	if endpoint == "" {
		return obstrace.ExporterNone
	}
	return obstrace.ExporterOTLP
}
