package rutracker

import (
	"testing"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// TestParseStyle covers parseStyle (post.go:506) and the styleSpec tagged-union
// impl methods (post.go:498-500) — both at 0.0% statement coverage before this
// test. parseStyle ports Kotlin's `Element.getStyle()` BBCode-style attribute
// parser. The assertions are on the concrete returned styleSpec type AND its
// extracted value (alignment enum / font size int32 / decoded ColorValue),
// which is exactly what dispatchElement consumes downstream to decide how a
// rendered post element is styled for the end user.
func TestParseStyle(t *testing.T) {
	// text-align → styleAlignment{<enum>}; titleASCII upper-cases first byte.
	t.Run("text-align center yields Center alignment", func(t *testing.T) {
		got := parseStyle("text-align: center")
		spec, ok := got.(styleAlignment)
		if !ok {
			t.Fatalf("want styleAlignment, got %T (%v)", got, got)
		}
		if spec.alignment != gen.Center {
			t.Fatalf("want alignment %q, got %q", gen.Center, spec.alignment)
		}
	})

	t.Run("text-align justify is recognised", func(t *testing.T) {
		got := parseStyle("text-align: justify")
		spec, ok := got.(styleAlignment)
		if !ok {
			t.Fatalf("want styleAlignment, got %T", got)
		}
		if spec.alignment != gen.Justify {
			t.Fatalf("want alignment %q, got %q", gen.Justify, spec.alignment)
		}
	})

	t.Run("unknown text-align value falls through to nil", func(t *testing.T) {
		// Kotlin: TextAlignment.valueOf("Bogus") throws → runCatching → null.
		if got := parseStyle("text-align: bogus"); got != nil {
			t.Fatalf("want nil for unknown alignment, got %T (%v)", got, got)
		}
	})

	// font-size → styleSize{<digits-only int>}; keepDigits strips "px".
	t.Run("font-size with unit yields digit-only int32 size", func(t *testing.T) {
		got := parseStyle("font-size: 14px")
		spec, ok := got.(styleSize)
		if !ok {
			t.Fatalf("want styleSize, got %T (%v)", got, got)
		}
		if spec.size != int32(14) {
			t.Fatalf("want size 14, got %d", spec.size)
		}
	})

	t.Run("font-size with no digits falls through to nil", func(t *testing.T) {
		if got := parseStyle("font-size: px"); got != nil {
			t.Fatalf("want nil for digit-less font-size, got %T (%v)", got, got)
		}
	})

	// color: #NNN — Kotlin's drop("#").toLong() parses radix-10. "255" → 255.
	t.Run("hash color with decimal digits yields Hex ColorValue", func(t *testing.T) {
		got := parseStyle("color: #255")
		spec, ok := got.(styleColor)
		if !ok {
			t.Fatalf("want styleColor, got %T (%v)", got, got)
		}
		hex, err := spec.color.AsColorValueHex()
		if err != nil {
			t.Fatalf("AsColorValueHex: %v", err)
		}
		if hex.Type != gen.Hex {
			t.Fatalf("want type %q, got %q", gen.Hex, hex.Type)
		}
		if hex.Hex != int64(255) {
			t.Fatalf("want hex 255 (decimal parse of \"255\"), got %d", hex.Hex)
		}
	})

	t.Run("hash color with hex letters falls through to nil", func(t *testing.T) {
		// "#ff0000" → drop("#")="ff0000" → ParseInt base10 fails → nil.
		if got := parseStyle("color: #ff0000"); got != nil {
			t.Fatalf("want nil for non-decimal hash color, got %T (%v)", got, got)
		}
	})

	t.Run("named color yields Name ColorValue", func(t *testing.T) {
		got := parseStyle("color: red")
		spec, ok := got.(styleColor)
		if !ok {
			t.Fatalf("want styleColor, got %T (%v)", got, got)
		}
		name, err := spec.color.AsColorValueName()
		if err != nil {
			t.Fatalf("AsColorValueName: %v", err)
		}
		if name.Type != gen.Name {
			t.Fatalf("want type %q, got %q", gen.Name, name.Type)
		}
		if name.Name != "red" {
			t.Fatalf("want name \"red\", got %q", name.Name)
		}
	})

	// First-match-wins: text-align is checked before font-size/color.
	t.Run("first-match-wins prefers text-align over font-size", func(t *testing.T) {
		got := parseStyle("font-size: 18px; text-align: right")
		spec, ok := got.(styleAlignment)
		if !ok {
			t.Fatalf("want styleAlignment (first-match-wins), got %T (%v)", got, got)
		}
		if spec.alignment != gen.Right {
			t.Fatalf("want alignment %q, got %q", gen.Right, spec.alignment)
		}
	})

	t.Run("no recognised key yields nil", func(t *testing.T) {
		if got := parseStyle("margin: 4px; padding: 2px"); got != nil {
			t.Fatalf("want nil for unrecognised keys, got %T (%v)", got, got)
		}
	})

	t.Run("empty and malformed pairs are skipped", func(t *testing.T) {
		// Leading ";;" empty pairs + a key without ":" are skipped; the valid
		// "font-size:9" pair still produces a size.
		got := parseStyle(";; nocolon ;font-size:9")
		spec, ok := got.(styleSize)
		if !ok {
			t.Fatalf("want styleSize after skipping malformed pairs, got %T (%v)", got, got)
		}
		if spec.size != int32(9) {
			t.Fatalf("want size 9, got %d", spec.size)
		}
	})
}
