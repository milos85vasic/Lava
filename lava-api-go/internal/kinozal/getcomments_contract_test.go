package kinozal

import (
	"context"
	"encoding/json"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// TestProviderAdapterGetCommentsContract pins the user-visible contract of the
// kinozal GetComments stub. kinozal declares provider.CapComments
// (see Capabilities) but does not expose a paginated comments endpoint; the
// adapter therefore returns a well-formed empty result rather than an error.
//
// A real client calling GET /v1/<id>/comments for a kinozal topic receives the
// struct this test asserts on. The load-bearing assertions are user-visible:
//
//   - Provider == "kinozal" — wrong value would mislabel the response.
//   - Page echoes the requested page — a dropped page breaks client paging UI.
//   - Total == 0 — the honest "no comments available" signal.
//   - Items is a NON-NIL empty slice — a nil slice marshals to JSON `null`,
//     which breaks clients that iterate over an array. This is the branch most
//     likely to regress silently and is verified via real json.Marshal output.
func TestProviderAdapterGetCommentsContract(t *testing.T) {
	// Real SUT. GetComments never touches the client, so a nil client is a
	// faithful, side-effect-free construction — no DB, no network.
	p := NewProviderAdapter(nil)

	cases := []struct {
		name string
		id   string
		page int
	}{
		{name: "first page", id: "1234567", page: 1},
		{name: "later page", id: "42", page: 5},
		{name: "zero page", id: "0", page: 0},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			res, err := p.GetComments(context.Background(), tc.id, tc.page, provider.Credentials{})
			if err != nil {
				t.Fatalf("GetComments returned error: %v", err)
			}
			if res == nil {
				t.Fatal("GetComments returned nil result")
			}
			if res.Provider != "kinozal" {
				t.Errorf("Provider = %q, want \"kinozal\"", res.Provider)
			}
			if res.Page != tc.page {
				t.Errorf("Page = %d, want %d (requested page must echo back)", res.Page, tc.page)
			}
			if res.Total != 0 {
				t.Errorf("Total = %d, want 0", res.Total)
			}
			if res.Items == nil {
				t.Fatal("Items is nil; want a non-nil empty slice (nil marshals to JSON null)")
			}
			if len(res.Items) != 0 {
				t.Errorf("len(Items) = %d, want 0", len(res.Items))
			}

			// The decisive user-visible check: the JSON a client receives must
			// carry "items":[] and not "items":null.
			b, err := json.Marshal(res)
			if err != nil {
				t.Fatalf("json.Marshal(result) failed: %v", err)
			}
			var decoded struct {
				Items json.RawMessage `json:"items"`
			}
			if err := json.Unmarshal(b, &decoded); err != nil {
				t.Fatalf("json.Unmarshal failed: %v", err)
			}
			if string(decoded.Items) != "[]" {
				t.Errorf("serialized items = %s, want []", string(decoded.Items))
			}
		})
	}
}
