package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// cmdExport renders the four tracker docs to the requested format.
//
//   - html: pure-Go (hand-rolled markdown→HTML for the table-shaped trackers).
//     Always available, never fails for tooling reasons.
//   - pdf/docx: attempted via a container runtime (podman/docker) running pandoc.
//     If no runtime/pandoc is available, prints an honest operator-actionable
//     message and exits 3 — it NEVER fabricates the file (§6.J anti-bluff).
func cmdExport(args []string) error {
	f, err := parseFlags(args)
	if err != nil {
		return err
	}
	format := f["format"]
	if format == "" {
		return fmt.Errorf("export requires --format html|pdf|docx")
	}
	// Source markdown is where `gen` wrote it (--out / default docs/tickets).
	// Destination is always <src>/export/<format> unless --export-dir overrides.
	srcDir := outDir(f)
	dstDir := f["export-dir"]
	if dstDir == "" {
		dstDir = filepath.Join(srcDir, "export", format)
	}
	if err := ensureDir(dstDir); err != nil {
		return err
	}
	names := []string{docIssues, docFixed, docIssuesSummary, docFixedSummary}

	switch format {
	case "html":
		for _, name := range names {
			src := filepath.Join(srcDir, name)
			md, err := os.ReadFile(src)
			if err != nil {
				return fmt.Errorf("export html: %s not found — run `lava-tickets gen` first: %w", src, err)
			}
			html := mdToHTML(string(md), strings.TrimSuffix(name, ".md"))
			dst := filepath.Join(dstDir, strings.TrimSuffix(name, ".md")+".html")
			if err := os.WriteFile(dst, []byte(html), 0o644); err != nil {
				return err
			}
			fmt.Printf("wrote %s (%d bytes, pure-Go)\n", dst, len(html))
		}
		return nil

	case "pdf", "docx":
		runtime := detectContainerRuntime()
		if runtime == "" {
			fmt.Fprintf(os.Stderr, `export %s: no container runtime found (podman/docker) and per §6.U/§6.J this tool
will NOT fake the export. To produce %s, the operator must EITHER:
  1. Install podman or docker (rootless, no sudo), then re-run:
       lava-tickets export --format %s
     (this tool will run pandoc inside a pandoc/core container), OR
  2. Run pandoc directly on the host on the generated markdown:
       pandoc %s/Issues.md -o out.%s
HTML export is unaffected (pure-Go): lava-tickets export --format html
`, format, format, format, srcDir, format)
			os.Exit(3)
		}
		// Run pandoc inside the container, mounting srcDir + dstDir.
		absSrc, _ := filepath.Abs(srcDir)
		absDst, _ := filepath.Abs(dstDir)
		failed := false
		for _, name := range names {
			base := strings.TrimSuffix(name, ".md")
			outName := base + "." + format
			// pandoc/core image is the canonical containerized pandoc.
			cmd := exec.Command(runtime, "run", "--rm",
				"-v", absSrc+":/src:ro",
				"-v", absDst+":/dst",
				"pandoc/core:latest",
				"/src/"+name, "-o", "/dst/"+outName, "--standalone")
			cmd.Stdout = os.Stdout
			cmd.Stderr = os.Stderr
			fmt.Printf("running: %s\n", strings.Join(cmd.Args, " "))
			if err := cmd.Run(); err != nil {
				fmt.Fprintf(os.Stderr, "export %s of %s FAILED: %v\n", format, name, err)
				failed = true
			} else {
				fmt.Printf("wrote %s\n", filepath.Join(dstDir, outName))
			}
		}
		if failed {
			fmt.Fprintf(os.Stderr, `export %s: the container ran but pandoc conversion failed (likely image pull
needs network, or pandoc/core unavailable offline). This tool will NOT fake the
file. Operator action: pre-pull the image (%s pull pandoc/core) with network
available, then re-run. HTML export is unaffected (pure-Go).
`, format, runtime)
			os.Exit(3)
		}
		return nil

	default:
		return fmt.Errorf("export: unknown --format %q (want html|pdf|docx)", format)
	}
}

func detectContainerRuntime() string {
	for _, rt := range []string{"podman", "docker"} {
		if _, err := exec.LookPath(rt); err == nil {
			return rt
		}
	}
	return ""
}

// mdToHTML is a small, targeted markdown→HTML converter for the tracker docs.
// It handles: H1/H2/H3 headings, pipe tables, paragraphs, bold (**x**), and HTML
// comments (passed through as comments). It is NOT a general markdown engine — it
// covers exactly the constructs `gen` emits, which is sufficient + dependency-free.
func mdToHTML(md, title string) string {
	var b strings.Builder
	b.WriteString("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
	b.WriteString("<title>" + htmlEscape(title) + " — LVA Tickets</title>\n")
	b.WriteString("<style>\n")
	b.WriteString("body{font-family:system-ui,-apple-system,sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;line-height:1.5}\n")
	b.WriteString("table{border-collapse:collapse;width:100%;margin:1rem 0}\n")
	b.WriteString("th,td{border:1px solid #ccc;padding:.4rem .6rem;text-align:left;vertical-align:top}\n")
	b.WriteString("th{background:#f4f4f4}\ncode{background:#f0f0f0;padding:.1rem .3rem;border-radius:3px}\n")
	b.WriteString("</style>\n</head>\n<body>\n")

	lines := strings.Split(md, "\n")
	i := 0
	for i < len(lines) {
		line := lines[i]
		trimmed := strings.TrimSpace(line)
		switch {
		case trimmed == "":
			i++
		case strings.HasPrefix(trimmed, "<!--"):
			b.WriteString(trimmed + "\n")
			i++
		case strings.HasPrefix(trimmed, "### "):
			b.WriteString("<h3>" + inlineHTML(strings.TrimPrefix(trimmed, "### ")) + "</h3>\n")
			i++
		case strings.HasPrefix(trimmed, "## "):
			b.WriteString("<h2>" + inlineHTML(strings.TrimPrefix(trimmed, "## ")) + "</h2>\n")
			i++
		case strings.HasPrefix(trimmed, "# "):
			b.WriteString("<h1>" + inlineHTML(strings.TrimPrefix(trimmed, "# ")) + "</h1>\n")
			i++
		case strings.HasPrefix(trimmed, "|"):
			// consume a contiguous table block
			var tbl []string
			for i < len(lines) && strings.HasPrefix(strings.TrimSpace(lines[i]), "|") {
				tbl = append(tbl, strings.TrimSpace(lines[i]))
				i++
			}
			b.WriteString(tableHTML(tbl))
		default:
			b.WriteString("<p>" + inlineHTML(trimmed) + "</p>\n")
			i++
		}
	}
	b.WriteString("</body>\n</html>\n")
	return b.String()
}

func tableHTML(rows []string) string {
	if len(rows) < 2 {
		return ""
	}
	var b strings.Builder
	b.WriteString("<table>\n")
	header := splitRow(rows[0])
	b.WriteString("<thead><tr>")
	for _, h := range header {
		b.WriteString("<th>" + inlineHTML(h) + "</th>")
	}
	b.WriteString("</tr></thead>\n<tbody>\n")
	// rows[1] is the |---| separator; data starts at rows[2]
	for _, r := range rows[2:] {
		cells := splitRow(r)
		b.WriteString("<tr>")
		for _, c := range cells {
			b.WriteString("<td>" + inlineHTML(c) + "</td>")
		}
		b.WriteString("</tr>\n")
	}
	b.WriteString("</tbody>\n</table>\n")
	return b.String()
}

// inlineHTML escapes HTML then applies **bold**.
func inlineHTML(s string) string {
	s = htmlEscape(s)
	for {
		first := strings.Index(s, "**")
		if first < 0 {
			break
		}
		second := strings.Index(s[first+2:], "**")
		if second < 0 {
			break
		}
		second += first + 2
		s = s[:first] + "<strong>" + s[first+2:second] + "</strong>" + s[second+2:]
	}
	return s
}

func htmlEscape(s string) string {
	s = strings.ReplaceAll(s, "&", "&amp;")
	s = strings.ReplaceAll(s, "<", "&lt;")
	s = strings.ReplaceAll(s, ">", "&gt;")
	return s
}
