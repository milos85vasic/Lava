package archiveorg

import (
	"encoding/json"
	"testing"
)

// LVA-020 regression. archive.org returns creator/title/year/date as a JSON
// ARRAY for multi-author / multi-date items. Before the flexString fix these
// fields were `string`/`*string`, so a single array-valued field failed
// json.Unmarshal of the ENTIRE response — breaking the user's whole search /
// browse / topic page, not just one row.

// TestSearchResponse_ArrayValuedFields_DoNotFailWholeResponse is the
// load-bearing user-visible assertion: a doc with array creator/year/title
// parses, and arrays are joined for display.
//
// Falsifiability: revert searchDoc.Creator/Year/Title/Mediatype back to
// string/*string — json.Unmarshal returns
// "cannot unmarshal array into Go struct field ... of type string" and this
// test fails at the first t.Fatalf.
func TestSearchResponse_ArrayValuedFields_DoNotFailWholeResponse(t *testing.T) {
	const body = `{"response":{"numFound":1,"start":0,"docs":[
		{"identifier":"x","title":["Bleak House","Vol I"],
		 "creator":["Charles Dickens","John Forster"],
		 "year":["1850","1851"],"mediatype":"texts","downloads":42,"item_size":1234}
	]}}`

	var sr searchResponse
	if err := json.Unmarshal([]byte(body), &sr); err != nil {
		t.Fatalf("array-valued creator/year/title MUST NOT fail the whole response: %v", err)
	}
	if len(sr.Response.Docs) != 1 {
		t.Fatalf("expected 1 doc, got %d", len(sr.Response.Docs))
	}
	d := sr.Response.Docs[0]
	if got, want := string(d.Creator), "Charles Dickens, John Forster"; got != want {
		t.Fatalf("creator join: got %q want %q", got, want)
	}
	if got, want := string(d.Title), "Bleak House, Vol I"; got != want {
		t.Fatalf("title join: got %q want %q", got, want)
	}
	if got, want := string(d.Year), "1850, 1851"; got != want {
		t.Fatalf("year join: got %q want %q", got, want)
	}
	if got, want := string(d.Mediatype), "texts"; got != want {
		t.Fatalf("scalar mediatype still works: got %q want %q", got, want)
	}
}

// TestMetadataResponse_ArrayValuedFields covers the /metadata (Topic) path.
func TestMetadataResponse_ArrayValuedFields(t *testing.T) {
	const body = `{"metadata":{"title":"T","creator":["A","B","C"],
		"description":["line one","line two"],"date":["1999","2000"],"mediatype":"movies"},
		"files":[]}`
	var mr metadataResponse
	if err := json.Unmarshal([]byte(body), &mr); err != nil {
		t.Fatalf("array-valued metadata MUST NOT fail the whole response: %v", err)
	}
	if got, want := string(mr.Metadata.Creator), "A, B, C"; got != want {
		t.Fatalf("creator join: got %q want %q", got, want)
	}
	if got, want := string(mr.Metadata.Date), "1999, 2000"; got != want {
		t.Fatalf("date join: got %q want %q", got, want)
	}
}

// TestFlexString_ScalarAndNull guards the non-array paths (regression-proofs the
// common case + null handling).
func TestFlexString_ScalarAndNull(t *testing.T) {
	cases := map[string]string{
		`"hello"`: "hello",
		`null`:    "",
		`1999`:    "1999",
		`["x"]`:   "x",
		`[]`:      "",
	}
	for in, want := range cases {
		var f flexString
		if err := f.UnmarshalJSON([]byte(in)); err != nil {
			t.Fatalf("UnmarshalJSON(%s): %v", in, err)
		}
		if string(f) != want {
			t.Fatalf("flexString(%s) = %q, want %q", in, string(f), want)
		}
	}
}
