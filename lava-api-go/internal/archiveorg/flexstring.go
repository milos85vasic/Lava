package archiveorg

import (
	"encoding/json"
	"strings"
)

// flexString tolerates archive.org fields that arrive as a string, a number,
// or an ARRAY of strings. archive.org's advancedsearch.php and /metadata
// routinely return creator/title/year/date as a JSON array for multi-author /
// multi-date items (e.g. "creator":["Charles Dickens","John Forster"]).
//
// LVA-020: previously these were decoded into `string` / `*string`, so a single
// array-valued field failed json.Unmarshal of the ENTIRE response — breaking the
// user's whole Internet Archive search / browse / topic page, not just one row.
// flexString joins arrays with ", " and maps null/absent to "".
type flexString string

func (f *flexString) UnmarshalJSON(data []byte) error {
	trimmed := strings.TrimSpace(string(data))
	if trimmed == "" || trimmed == "null" {
		*f = ""
		return nil
	}
	switch trimmed[0] {
	case '"':
		var s string
		if err := json.Unmarshal(data, &s); err != nil {
			return err
		}
		*f = flexString(s)
	case '[':
		var arr []flexString
		if err := json.Unmarshal(data, &arr); err != nil {
			return err
		}
		parts := make([]string, 0, len(arr))
		for _, e := range arr {
			if e != "" {
				parts = append(parts, string(e))
			}
		}
		*f = flexString(strings.Join(parts, ", "))
	default:
		// number / bool / other scalar — take the raw token verbatim
		// (e.g. a year emitted as the integer 1999).
		*f = flexString(trimmed)
	}
	return nil
}
