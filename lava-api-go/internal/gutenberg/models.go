package gutenberg

type bookList struct {
	Count    int     `json:"count"`
	Next     *string `json:"next"`
	Previous *string `json:"previous"`
	Results  []book  `json:"results"`
}

type book struct {
	ID            int               `json:"id"`
	Title         string            `json:"title"`
	Authors       []author          `json:"authors"`
	Formats       map[string]string `json:"formats"`
	DownloadCount int               `json:"download_count"`
	Subjects      []string          `json:"subjects"`
}

type author struct {
	Name string `json:"name"`
}
