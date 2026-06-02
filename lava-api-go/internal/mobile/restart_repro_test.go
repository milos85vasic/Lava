package mobile

import "testing"

// TestStartStopStartSamePort_RestartRebinds reproduces the on-device restart
// path: ApiEngineController.restart() reuses its FIXED port, so the second
// Start must rebind the SAME port the first Start (and its now-stopped listener)
// used. TestCertPersistsAcrossRestart only covers DIFFERENT ports per start, so
// same-port rebind was never exercised — C03/C04 (:api-app) time out on restart.
func TestStartStopStartSamePort_RestartRebinds(t *testing.T) {
	bind := "127.0.0.1"
	port := freePort(t)
	dbPath := tempSQLitePath(t)

	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("first Start: %v", err)
	}
	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	// Restart on the SAME port + SAME db path (the controller's contract).
	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("restart Start on SAME port %d MUST succeed: %v", port, err)
	}
	t.Cleanup(func() { _ = Stop() })
}
