# -*- coding: utf-8 -*-
"""
Lava Multi-Tracker SDK Architecture & Implementation Plan
Comprehensive Technical Document Generation Script
"""

import os, sys, hashlib
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import inch, cm
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.enums import TA_LEFT, TA_CENTER, TA_JUSTIFY
from reportlab.lib import colors
from reportlab.platypus import (
    Paragraph, Spacer, PageBreak, Table, TableStyle,
    KeepTogether, CondPageBreak
)
from reportlab.platypus.tableofcontents import TableOfContents
from reportlab.platypus import SimpleDocTemplate
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfbase.pdfmetrics import registerFontFamily

# ━━━━ FONTS ━━━━
pdfmetrics.registerFont(TTFont('TimesNewRoman', '/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf'))
pdfmetrics.registerFont(TTFont('Calibri', '/usr/share/fonts/truetype/english/Carlito-Regular.ttf'))
pdfmetrics.registerFont(TTFont('DejaVuSans', '/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf'))
registerFontFamily('TimesNewRoman', normal='TimesNewRoman', bold='TimesNewRoman')
registerFontFamily('Calibri', normal='Calibri', bold='Calibri')
registerFontFamily('DejaVuSans', normal='DejaVuSans', bold='DejaVuSans')

# ━━━━ COLORS ━━━━
ACCENT = colors.HexColor('#25728c')
TEXT_PRIMARY = colors.HexColor('#242321')
TEXT_MUTED = colors.HexColor('#807c74')
BG_SURFACE = colors.HexColor('#dedad3')
BG_PAGE = colors.HexColor('#f1f0ed')
TABLE_HEADER_COLOR = ACCENT
TABLE_HEADER_TEXT = colors.white
TABLE_ROW_EVEN = colors.white
TABLE_ROW_ODD = BG_SURFACE

# ━━━━ PAGE SETUP ━━━━
PAGE_W, PAGE_H = A4
L_MARGIN = 1.0 * inch
R_MARGIN = 1.0 * inch
T_MARGIN = 0.9 * inch
B_MARGIN = 0.9 * inch
AVAIL_W = PAGE_W - L_MARGIN - R_MARGIN

# ━━━━ STYLES ━━━━
styles = getSampleStyleSheet()

sH1 = ParagraphStyle('CustomH1', fontName='TimesNewRoman', fontSize=20, leading=28,
    spaceBefore=18, spaceAfter=10, textColor=TEXT_PRIMARY)
sH2 = ParagraphStyle('CustomH2', fontName='TimesNewRoman', fontSize=15, leading=22,
    spaceBefore=14, spaceAfter=8, textColor=TEXT_PRIMARY)
sH3 = ParagraphStyle('CustomH3', fontName='TimesNewRoman', fontSize=12, leading=18,
    spaceBefore=10, spaceAfter=6, textColor=TEXT_PRIMARY)
sBody = ParagraphStyle('CustomBody', fontName='TimesNewRoman', fontSize=10.5, leading=17,
    spaceBefore=2, spaceAfter=4, alignment=TA_JUSTIFY, textColor=TEXT_PRIMARY)
sBodyLeft = ParagraphStyle('CustomBodyLeft', fontName='TimesNewRoman', fontSize=10.5, leading=17,
    spaceBefore=2, spaceAfter=4, alignment=TA_LEFT, textColor=TEXT_PRIMARY)
sCode = ParagraphStyle('CodeStyle', fontName='DejaVuSans', fontSize=8.5, leading=12,
    spaceBefore=3, spaceAfter=3, alignment=TA_LEFT, textColor=TEXT_PRIMARY,
    backColor=colors.HexColor('#f5f5f5'), leftIndent=12, rightIndent=12)
sTOC1 = ParagraphStyle('TOC1', fontName='TimesNewRoman', fontSize=13, leftIndent=20, leading=22)
sTOC2 = ParagraphStyle('TOC2', fontName='TimesNewRoman', fontSize=11, leftIndent=40, leading=18)
sTOC3 = ParagraphStyle('TOC3', fontName='TimesNewRoman', fontSize=10, leftIndent=60, leading=16)
sTH = ParagraphStyle('TH', fontName='TimesNewRoman', fontSize=9.5, leading=13,
    alignment=TA_CENTER, textColor=colors.white)
sTD = ParagraphStyle('TD', fontName='TimesNewRoman', fontSize=9, leading=13, alignment=TA_LEFT)
sTDc = ParagraphStyle('TDc', fontName='TimesNewRoman', fontSize=9, leading=13, alignment=TA_CENTER)
sCaption = ParagraphStyle('Caption', fontName='TimesNewRoman', fontSize=9, leading=14,
    alignment=TA_CENTER, textColor=TEXT_MUTED, spaceBefore=3, spaceAfter=6)
sBullet = ParagraphStyle('Bullet', fontName='TimesNewRoman', fontSize=10.5, leading=17,
    leftIndent=20, bulletIndent=8, spaceBefore=1, spaceAfter=1, alignment=TA_LEFT)

# ━━━━ HELPERS ━━━━
def h1(text, level=0):
    key = 'h_%s' % hashlib.md5(text.encode()).hexdigest()[:8]
    p = Paragraph('<a name="%s"/><b>%s</b>' % (key, text), sH1)
    p.bookmark_name = text; p.bookmark_level = level; p.bookmark_text = text; p.bookmark_key = key
    return p

def h2(text, level=1):
    key = 'h_%s' % hashlib.md5(text.encode()).hexdigest()[:8]
    p = Paragraph('<a name="%s"/><b>%s</b>' % (key, text), sH2)
    p.bookmark_name = text; p.bookmark_level = level; p.bookmark_text = text; p.bookmark_key = key
    return p

def h3(text, level=2):
    key = 'h_%s' % hashlib.md5(text.encode()).hexdigest()[:8]
    p = Paragraph('<a name="%s"/><b>%s</b>' % (key, text), sH3)
    p.bookmark_name = text; p.bookmark_level = level; p.bookmark_text = text; p.bookmark_key = key
    return p

def body(text):
    return Paragraph(text, sBody)

def bodyleft(text):
    return Paragraph(text, sBodyLeft)

def code(text):
    return Paragraph(text.replace('\n', '<br/>').replace('  ', '&nbsp;&nbsp;'), sCode)

def bullet(text):
    return Paragraph('\xe2\x80\xa2 ' + text, sBullet)

def make_table(headers, rows, col_widths=None):
    """Create a styled table with proper Paragraph wrapping."""
    if col_widths is None:
        n = len(headers)
        col_widths = [AVAIL_W / n] * n
    data = [[Paragraph('<b>%s</b>' % h, sTH) for h in headers]]
    for row in rows:
        data.append([Paragraph(str(c), sTD) for c in row])
    t = Table(data, colWidths=col_widths, hAlign='CENTER', repeatRows=1)
    style_cmds = [
        ('BACKGROUND', (0, 0), (-1, 0), TABLE_HEADER_COLOR),
        ('TEXTCOLOR', (0, 0), (-1, 0), TABLE_HEADER_TEXT),
        ('GRID', (0, 0), (-1, -1), 0.5, TEXT_MUTED),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
        ('LEFTPADDING', (0, 0), (-1, -1), 6),
        ('RIGHTPADDING', (0, 0), (-1, -1), 6),
        ('TOPPADDING', (0, 0), (-1, -1), 5),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 5),
    ]
    for i in range(1, len(data)):
        bg = TABLE_ROW_EVEN if i % 2 == 1 else TABLE_ROW_ODD
        style_cmds.append(('BACKGROUND', (0, i), (-1, i), bg))
    t.setStyle(TableStyle(style_cmds))
    return t

# ━━━━ TOC TEMPLATE ━━━━
class TocDocTemplate(SimpleDocTemplate):
    def afterFlowable(self, flowable):
        if hasattr(flowable, 'bookmark_name'):
            level = getattr(flowable, 'bookmark_level', 0)
            text = getattr(flowable, 'bookmark_text', '')
            key = getattr(flowable, 'bookmark_key', '')
            self.notify('TOCEntry', (level, text, self.page, key))

# ━━━━ BUILD STORY ━━━━
story = []

# ── TOC ──
toc = TableOfContents()
toc.levelStyles = [sTOC1, sTOC2, sTOC3]
story.append(Paragraph('<b>Table of Contents</b>', ParagraphStyle('TocTitle',
    fontName='TimesNewRoman', fontSize=22, leading=30, alignment=TA_LEFT, textColor=TEXT_PRIMARY)))
story.append(Spacer(1, 12))
story.append(toc)
story.append(PageBreak())

# ══════════════════════════════════════════════════════════════
# EXECUTIVE SUMMARY
# ══════════════════════════════════════════════════════════════
story.append(h1('Executive Summary'))
story.append(Spacer(1, 6))

story.append(body(
    'This document presents a comprehensive architecture redesign and implementation plan for the '
    '<b>Lava</b> project (github.com/milos85vasic/Lava), an unofficial Android client for Russian torrent '
    'trackers. The current codebase is tightly coupled to RuTracker (rutracker.org), making it impossible '
    'to support additional trackers without duplicating massive amounts of code. The primary goal of this '
    'initiative, designated <b>SuperPower SP-3: Multi-Tracker SDK</b>, is to fully decouple the existing '
    'RuTracker implementation into a pluggable, interface-driven architecture that enables seamless '
    'incorporation of new tracker backends, beginning with RuTor (rutor.info / rutor.is).'
))
story.append(body(
    'The project currently produces three runtime artifacts: an Android application built with Kotlin '
    'and Jetpack Compose, a legacy Kotlin Ktor proxy server, and a Go API server (lava-api-go) that is '
    'being actively developed as a replacement for the Ktor proxy. Both the Kotlin and Go codebases '
    'contain RuTracker-specific HTML scraping logic that must be abstracted behind clean interfaces. '
    'The existing <b>NetworkApi</b> interface in Kotlin (15 methods) and the OpenAPI 3.0.3 specification '
    'define the current contract, but they are RuTracker-specific in their DTO structures, authentication '
    'flows, and HTML parsing assumptions.'
))
story.append(body(
    'This plan spans 8 phases with 47 tasks and 186 sub-tasks, covering: (1) Foundation interfaces and '
    'abstractions for a tracker-agnostic SDK, (2) Full decoupling of the existing RuTracker implementation '
    'behind the new abstractions, (3) Implementation of RuTor as the first additional tracker, (4) Mirror '
    'address support with automatic fallback mechanisms, (5) Integration of HTTP/3 (QUIC), Brotli '
    'compression, lock-free threading, lazy initialization, and semaphores, (6) Comprehensive anti-bluff '
    'testing achieving 100% coverage with real verification, (7) Deep user guides and engineering manuals, '
    'and (8) Constitutional updates to CLAUDE.md and AGENTS.md enforcing quality guarantees.'
))
story.append(body(
    'The anti-bluff principle is paramount: every test and challenge must guarantee that tested code '
    'actually works as expected by end users. Historical issues where tests passed but features were '
    'non-functional are unacceptable going forward. This principle will be enshrined in the project '
    'constitution at all levels, including all submodules.'
))

# ══════════════════════════════════════════════════════════════
# PART I: CURRENT STATE ANALYSIS
# ══════════════════════════════════════════════════════════════
story.append(Spacer(1, 18))
story.append(h1('Part I: Current State Analysis'))

# 1.1 Project Architecture Overview
story.append(h2('1.1 Project Architecture Overview'))

story.append(body(
    'The Lava project is a multi-module Gradle project with 32 Kotlin modules and a separate Go API server. '
    'It originated as a fork of the andrikeev/Flow project and has been significantly extended. The project '
    'follows a modular architecture pattern with clear separation between core libraries, feature modules, '
    'and application modules. All build logic is centralized in buildSrc convention plugins rather than a '
    'root build.gradle.kts file.'
))

story.append(body(
    'The Android application module (:app) uses Kotlin 2.1.0 with Jetpack Compose (BOM 2025.06.01) for '
    'the entire UI layer, Orbit MVI 7.1.0 for state management, Dagger Hilt 2.54 for dependency injection, '
    'Room 2.7.2 for local database storage, and Ktor 2.3.1 for network communication. The proxy module '
    '(:proxy) is a Kotlin Ktor/Netty server that scrapes rutracker.org HTML and exposes a JSON REST API '
    'on port 8080 with mDNS discovery. The Go API server (lava-api-go) is the newer implementation using '
    'Gin framework, PostgreSQL 16, HTTP/3 via quic-go, and comprehensive observability (Prometheus, Loki, '
    'Tempo, Grafana), running on port 8443.'
))

story.append(body(
    'The project maintains 15 vasic-digital submodules under the Submodules/ directory, covering cross-'
    'cutting concerns such as HTTP3, Mdns, Middleware (Brotli, CORS, recovery, request ID), Observability, '
    'RateLimiter, Recovery (circuit breaker), Security, Challenges, Config, Discovery, Concurrency, and '
    'Containers. These submodules are frozen by default and require explicit operator action to update.'
))

story.append(h3('Module Layout'))
story.append(make_table(
    ['Layer', 'Module', 'Language', 'Purpose'],
    [
        ['Application', ':app', 'Kotlin', 'Android client (Compose UI)'],
        ['Proxy', ':proxy', 'Kotlin', 'Ktor/Netty REST API (legacy)'],
        ['API Server', 'lava-api-go/', 'Go', 'Gin/Postgres/HTTP3 API (new)'],
        ['Core', ':core:network:api', 'Kotlin', 'NetworkApi interface + DTOs'],
        ['Core', ':core:network:impl', 'Kotlin', 'ProxyNetworkApi, SwitchingNetworkApi'],
        ['Core', ':core:network:rutracker', 'Kotlin', 'RuTracker HTML scraper (18+ UseCases)'],
        ['Core', ':core:domain', 'Kotlin', '60+ UseCases, domain models'],
        ['Core', ':core:data', 'Kotlin', 'Repositories (10), Services (8)'],
        ['Core', ':core:database', 'Kotlin', 'Room DB, 9 DAOs, 10 entities'],
        ['Core', ':core:models', 'Kotlin', 'Domain models (Auth, Topic, Search...)'],
        ['Core', ':core:designsystem', 'Kotlin', 'LavaTheme, Compose components'],
        ['Feature', ':feature:login', 'Kotlin', 'Login screen + ViewModel'],
        ['Feature', ':feature:search_result', 'Kotlin', 'Search results + categories'],
        ['Feature', ':feature:topic', 'Kotlin', 'Topic detail + comments'],
        ['Feature', ':feature:forum', 'Kotlin', 'Forum tree browsing'],
        ['Feature', ':feature:favorites', 'Kotlin', 'Favorites management'],
    ],
    col_widths=[60, 140, 55, AVAIL_W - 260]
))
story.append(Paragraph('Table 1: Key Module Layout of the Lava Project', sCaption))

# 1.2 Existing RuTracker Implementation
story.append(Spacer(1, 12))
story.append(h2('1.2 Existing RuTracker Implementation Analysis'))

story.append(body(
    'The RuTracker implementation is the heart of the Lava project and exists in two parallel implementations: '
    'a Kotlin version in core/network/rutracker/ and a Go version in lava-api-go/internal/rutracker/. Both '
    'implementations follow the same architectural pattern of making HTTP requests to rutracker.org, receiving '
    'HTML responses (Windows-1251 encoded), parsing them with CSS selectors (Jsoup in Kotlin, goquery in Go), '
    'and mapping the parsed data to DTO types.'
))

story.append(body(
    'The central abstraction in Kotlin is the <b>NetworkApi</b> interface located in '
    'core/network/api/src/main/kotlin/lava/network/api/NetworkApi.kt. This interface defines 15 suspend methods '
    'covering authorization, favorites management, forum navigation, search, topic browsing, comments, and '
    'torrent downloads. There are two concrete implementations: ProxyNetworkApi (talks to the Ktor proxy via '
    'HTTP) and a direct RuTracker scraper (in the rutracker module). The SwitchingNetworkApi acts as a '
    'delegating wrapper that routes calls to whichever implementation the user has configured.'
))

story.append(h3('NetworkApi Interface (15 Methods)'))
story.append(code(
    'interface NetworkApi {<br/>'
    '&nbsp;&nbsp;suspend fun checkAuthorized(token: String): Boolean<br/>'
    '&nbsp;&nbsp;suspend fun login(username, password, captchaSid, captchaCode, captchaValue): AuthResponseDto<br/>'
    '&nbsp;&nbsp;suspend fun getFavorites(token: String): FavoritesDto<br/>'
    '&nbsp;&nbsp;suspend fun addFavorite(token: String, id: String): Boolean<br/>'
    '&nbsp;&nbsp;suspend fun removeFavorite(token: String, id: String): Boolean<br/>'
    '&nbsp;&nbsp;suspend fun getForum(): ForumDto<br/>'
    '&nbsp;&nbsp;suspend fun getCategory(id: String, page: Int?): CategoryPageDto<br/>'
    '&nbsp;&nbsp;suspend fun getSearchPage(token, searchQuery, categories, author, authorId,<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;sortType, sortOrder, period, page): SearchPageDto<br/>'
    '&nbsp;&nbsp;suspend fun getTopic(token: String, id: String, page: Int?): ForumTopicDto<br/>'
    '&nbsp;&nbsp;suspend fun getTopicPage(token, id, page): TopicPageDto<br/>'
    '&nbsp;&nbsp;suspend fun getCommentsPage(token, id, page): CommentsPageDto<br/>'
    '&nbsp;&nbsp;suspend fun addComment(token, topicId, message): Boolean<br/>'
    '&nbsp;&nbsp;suspend fun getTorrent(token: String, id: String): TorrentDto<br/>'
    '&nbsp;&nbsp;suspend fun download(token: String, id: String): FileDto<br/>'
    '}'
))

story.append(body(
    'The Kotlin RuTracker module contains 18+ UseCase classes (GetForumUseCase, GetSearchPageUseCase, '
    'GetTopicUseCase, GetCategoryPageUseCase, GetFavoritesUseCase, LoginUseCase, etc.) that each perform '
    'a specific HTML parsing task. Each UseCase calls RuTrackerInnerApi to fetch raw HTML, then applies '
    'Jsoup CSS selectors to extract structured data and maps it to DTOs. The Go implementation mirrors '
    'these parsers 1:1 with identical CSS selectors using goquery. Key differences include: the Go client '
    'includes a circuit breaker (5 failures, 10s reset timeout), charset transcoding from Windows-1251 to '
    'UTF-8, and more comprehensive test coverage (fuzz tests, parity tests, contract tests, E2E tests, '
    'and load tests).'
))

# 1.3 Coupling Assessment
story.append(Spacer(1, 12))
story.append(h2('1.3 Coupling Assessment and Problem Analysis'))

story.append(body(
    'The current codebase suffers from several critical coupling problems that prevent multi-tracker support '
    'and reduce maintainability. These problems are categorized below with specific code references.'
))

story.append(h3('Problem 1: RuTracker-Specific DTO Structures'))
story.append(body(
    'The DTO classes (AuthResponseDto, ForumDto, CategoryPageDto, SearchPageDto, ForumTopicDto, TopicPageDto, '
    'CommentsPageDto, TorrentDto, FileDto) are defined as sealed class hierarchies with RuTracker-specific '
    'variants. For example, AuthResponseDto has three subtypes corresponding to RuTracker login states, and '
    'PostElementDto has 18 variants for different RuTracker post element types. These DTOs cannot represent '
    'data from other trackers without modification. The sealed class discriminator uses a "type" field that '
    'is RuTracker-specific in its value set. File reference: core/network/api/src/main/kotlin/lava/network/api/'
))

story.append(h3('Problem 2: Hardcoded URLs and Endpoints'))
story.append(body(
    'Both ProxyNetworkApi and the direct RuTracker scraper have rutracker.org URLs hardcoded in their '
    'implementations. The Go client in lava-api-go/internal/rutracker/client.go stores the base URL as a '
    'single string field, but the URL patterns for specific endpoints (e.g., /tracker.php, /viewforum.php, '
    '/viewtopic.php) are embedded directly in the parser code. There is no abstraction for tracker-specific '
    'URL patterns, making it impossible to redirect requests to different trackers without modifying parser '
    'code. Mirror addresses are not supported at all in the current architecture.'
))

story.append(h3('Problem 3: RuTracker-Specific Authentication Flow'))
story.append(body(
    'The login flow is tightly coupled to RuTracker\'s specific authentication mechanism, which includes '
    'CAPTCHA support (via static.t-ru.org captcha images), form token extraction from HTML, and cookie-based '
    'session management with specific cookie names. The LoginUseCase in the Kotlin rutracker module and '
    'login.go in the Go implementation both parse RuTracker-specific HTML forms and handle RuTracker-specific '
    'error states. Other trackers like RuTor use fundamentally different authentication mechanisms (simple '
    'POST form vs. RuTracker\'s multi-step process with CAPTCHA), making the current flow non-reusable.'
))

story.append(h3('Problem 4: Monolithic HTML Parsing'))
story.append(body(
    'Each UseCase class combines three responsibilities: HTTP request execution, HTML parsing with tracker-'
    'specific CSS selectors, and DTO mapping. This violates the Single Responsibility Principle and makes '
    'it impossible to reuse any part of the parsing pipeline for different trackers. For example, '
    'GetSearchPageUseCase not only fetches and parses search results but also contains RuTracker-specific '
    'URL construction logic, sorting parameter mapping, and pagination handling that is unique to '
    'rutracker.org\'s URL scheme.'
))

story.append(h3('Problem 5: No Tracker Capability Abstraction'))
story.append(body(
    'The NetworkApi interface assumes all trackers support the same 15 operations. However, RuTracker and '
    'RuTor have significantly different feature sets. RuTracker supports forums, topic categories, comments, '
    'favorites, CAPTCHA-protected login, and torrent file downloads. RuTor, being a public tracker, does '
    'not require login for basic operations, has a different category system, does not support forums or '
    'comments without authentication, and provides magnet links directly on listing pages. The current '
    'interface cannot express these capability differences, leading to either broken functionality or '
    'dummy implementations (e.g., ProxyNetworkApi has checkAuthorized() and download() as "Not implemented").'
))

story.append(h3('Problem 6: Dual-Language Duplication'))
story.append(body(
    'The same RuTracker scraping logic exists in both Kotlin and Go, with the Go version being a 1:1 port '
    'of the Kotlin version. This creates a maintenance burden where any bug fix or feature addition must be '
    'applied twice. The parsers use identical CSS selectors but different HTML parsing libraries (Jsoup vs. '
    'goquery), different error handling patterns, and different HTTP client configurations. There is no '
    'shared test suite between the two implementations beyond the parity tests in Go.'
))

# 1.4 Testing Infrastructure
story.append(Spacer(1, 12))
story.append(h2('1.4 Testing Infrastructure Assessment'))

story.append(body(
    'The testing infrastructure shows a stark contrast between the Kotlin and Go codebases. The Kotlin side '
    'has minimal test coverage with only 9 test files identified across the entire project, mostly focused on '
    'ViewModels, converters, and infrastructure contracts. Critical business logic in the 60+ UseCases, the '
    '18+ RuTracker parser classes, and the repository/service implementations has essentially zero unit test '
    'coverage. The core/testing/ module provides fake implementations (TestBookmarksRepository, '
    'TestEndpointsRepository, etc.) but these are used primarily for ViewModel testing rather than validating '
    'business logic correctness.'
))

story.append(body(
    'The Go side has significantly better testing infrastructure: unit tests for every handler and parser, '
    'fuzz tests (forum_fuzz_test.go, search_fuzz_test.go, topic_fuzz_test.go, torrent_fuzz_test.go), '
    'integration tests with real PostgreSQL, contract tests (healthcheck_contract_test.go), E2E tests, '
    'cross-backend parity tests between Kotlin and Go, load tests using k6, and mutation tests. However, '
    'even the Go tests have a potential bluff problem: they validate that the parsers produce correct output '
    'for given HTML fixtures, but they do not always verify that the actual rutracker.org website still '
    'matches those fixtures.'
))

story.append(body(
    'The Anti-Bluff Testing Pact defined in the project\'s constitutional rules establishes six laws '
    'intended to prevent false-positive test results. However, the current Kotlin test coverage is far too '
    'low for these laws to be effective. The key issue identified is that tests can pass while features '
    'remain non-functional for end users. This happens because: (a) ViewModel tests use fake repositories '
    'that may not behave identically to real ones, (b) there are no integration tests that exercise the full '
    'stack from UI to network, (c) the existing "Challenge Tests" mentioned in the constitution do not '
    'appear to have corresponding executable test files, and (d) no contract tests exist for the Kotlin proxy.'
))

# 1.5 RuTor Research Findings
story.append(Spacer(1, 12))
story.append(h2('1.5 RuTor Research Findings'))

story.append(body(
    'RuTor (rutor.info / rutor.is) is the largest public Russian torrent tracker. Unlike RuTracker, it does '
    'not require registration for browsing, searching, or downloading torrents. The site uses UTF-8 encoding '
    '(simpler than RuTracker\'s Windows-1251) and serves content over HTTPS. Multiple mirror domains exist: '
    'rutor.info, rutor.is, www.rutor.info, www.rutor.is, and 6tor.org (IPv6-only). The site is permanently '
    'blocked in Russia, so users access it via mirrors or VPN, making mirror support essential.'
))

story.append(h3('RuTor Page Structure and URL Patterns'))
story.append(body(
    'The search URL pattern is path-based: /search/{page}/{category}/{method}{scope}0/{sort}/{query}, where '
    'page is 0-indexed, category is a numeric ID (0-17), method controls matching (exact phrase, all words, '
    'any word, logical expression), scope controls search fields (title only, title and description), and '
    'sort supports 12 sort orders (date, seeders, leechers, title, size, relevance in ascending/descending). '
    'Torrent detail pages follow the pattern /torrent/{id}/{slug}, and download URLs point to '
    'd.rutor.info/download/{id}. RSS feeds are available at /rss.php?category.'
))

story.append(h3('RuTor HTML Structure (CSS Selectors for Parsing)'))
story.append(make_table(
    ['Field', 'CSS Selector', 'Notes'],
    [
        ['Row selector', 'tr:has(td:has(a[href^="magnet:?xt="]))', 'Selects all torrent rows'],
        ['Title', 'td:nth-of-type(2) a[href^="/torrent/"]', 'Torrent title link text'],
        ['Details link', 'td:nth-of-type(2) a[href^="/torrent/"]', 'href attribute'],
        ['Download URL', 'a.downgif', 'href attribute (.torrent file)'],
        ['Info hash', 'a[href^="magnet:?xt="]', 'Regex: ([A-Fa-f0-9]{40})'],
        ['Date', 'td:nth-of-type(1)', 'Format: DD MMM YY (Russian months)'],
        ['Size', 'td:contains(non-breaking space + unit)', 'Units: GB, MB, kB, B, TB'],
        ['Seeders', 'td span.green', 'Number inside span'],
        ['Leechers', 'td span.red', 'Number inside span'],
        ['Comment count', 'Adjacent td (sometimes present)', 'Absence shifts column indices'],
    ],
    col_widths=[90, 180, AVAIL_W - 275]
))
story.append(Paragraph('Table 2: RuTor HTML Parsing Selectors', sCaption))

story.append(body(
    'A critical parsing challenge is that the presence or absence of a comment-count column shifts the '
    'column indices, requiring content-based selectors rather than positional selectors for the size field. '
    'The Jackett project\'s rutor.yml definition (battle-tested in production) handles this by using '
    'selectors like td:contains(\\u00a0GB) to find the size column regardless of its position.'
))

story.append(h3('RuTor Authentication'))
story.append(body(
    'RuTor uses a simple POST form at /users.php?login with fields nick and password. Registration is '
    'optional and only required for uploading torrents and commenting. Authentication state is maintained '
    'via a userid cookie. When logged in, the menu shows additional options. Unlike RuTracker, there is '
    'no CAPTCHA on login, no form token extraction, and no multi-step authentication process. The credentials '
    'for testing are: username "nobody85perfect", password "ironman1985" (same as RuTracker).'
))

story.append(h3('RuTor vs RuTracker Feature Comparison'))
story.append(make_table(
    ['Feature', 'RuTracker', 'RuTor'],
    [
        ['Registration Required', 'Yes (mandatory)', 'No (optional)'],
        ['CAPTCHA on Login', 'Yes', 'No'],
        ['Forum Structure', 'Yes (hierarchical)', 'No (categories only)'],
        ['Comments', 'Yes (authenticated)', 'Yes (authenticated)'],
        ['Favorites', 'Yes (full CRUD)', 'Limited'],
        ['Search', 'POST form + URL params', 'Path-based URL'],
        ['Encoding', 'Windows-1251', 'UTF-8'],
        ['Magnet Links', 'On detail page', 'On listing page'],
        ['Download', '.torrent file', '.torrent file + magnet'],
        ['Category System', 'Forum-based tree', 'Numeric IDs (0-17)'],
    ],
    col_widths=[130, (AVAIL_W - 130) / 2, (AVAIL_W - 130) / 2]
))
story.append(Paragraph('Table 3: RuTracker vs RuTor Feature Comparison', sCaption))

# ══════════════════════════════════════════════════════════════
# PART II: ARCHITECTURE DESIGN
# ══════════════════════════════════════════════════════════════
story.append(Spacer(1, 18))
story.append(h1('Part II: Architecture Design - The Decoupled SDK'))

story.append(h2('2.1 Tracker Abstraction Layer Design'))

story.append(body(
    'The core architectural change is the introduction of a tracker abstraction layer that defines '
    'tracker-agnostic interfaces for all operations. Rather than a single monolithic NetworkApi interface '
    'with 15 methods that every tracker must implement (resulting in "Not implemented" stubs), we introduce '
    'a capability-based design where trackers declare which features they support and consumers can query '
    'capabilities before attempting operations.'
))

story.append(h3('2.1.1 Core Interfaces'))

story.append(body(
    '<b>TrackerDescriptor</b> - Describes a tracker instance with its metadata and capabilities. Every '
    'tracker implementation must provide a descriptor that answers: What is the tracker name? What is the '
    'base URL? What features does it support? What are its mirror addresses? What authentication mechanism '
    'does it use? The descriptor is the entry point for the tracker plugin system and is used by the '
    'TrackerRegistry to discover and instantiate tracker implementations.'
))

story.append(code(
    '// core/tracker/api/src/main/kotlin/lava/tracker/api/TrackerDescriptor.kt<br/>'
    'interface TrackerDescriptor {<br/>'
    '&nbsp;&nbsp;val trackerId: String&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Unique identifier, e.g. "rutracker", "rutor"<br/>'
    '&nbsp;&nbsp;val displayName: String&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Human-readable name<br/>'
    '&nbsp;&nbsp;val baseUrls: List&lt;MirrorUrl&gt;&nbsp;&nbsp;// Primary + mirror URLs<br/>'
    '&nbsp;&nbsp;val capabilities: Set&lt;TrackerCapability&gt; // Feature support flags<br/>'
    '&nbsp;&nbsp;val authType: AuthType&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Login mechanism type<br/>'
    '&nbsp;&nbsp;val encoding: String&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// e.g. "UTF-8", "Windows-1251"<br/>'
    '}<br/><br/>'
    'enum class TrackerCapability {<br/>'
    '&nbsp;&nbsp;SEARCH, BROWSE, FORUM, TOPIC, COMMENTS, FAVORITES,<br/>'
    '&nbsp;&nbsp;TORRENT_DOWNLOAD, MAGNET_LINK, AUTH_REQUIRED,<br/>'
    '&nbsp;&nbsp;CAPTCHA_LOGIN, RSS, UPLOAD, USER_PROFILE<br/>'
    '}<br/><br/>'
    'enum class AuthType {<br/>'
    '&nbsp;&nbsp;NONE, FORM_LOGIN, CAPTCHA_LOGIN, OAUTH, API_KEY<br/>'
    '}'
))

story.append(body(
    '<b>TrackerClient</b> - The primary interface for interacting with a tracker. Unlike the current '
    'NetworkApi which has 15 fixed methods, TrackerClient uses a capability-based approach where methods '
    'are defined in separate feature interfaces. A tracker only needs to implement the interfaces for '
    'features it supports. This eliminates the "Not implemented" problem entirely.'
))

story.append(code(
    '// core/tracker/api/src/main/kotlin/lava/tracker/api/TrackerClient.kt<br/>'
    'interface TrackerClient : AutoCloseable {<br/>'
    '&nbsp;&nbsp;val descriptor: TrackerDescriptor<br/>'
    '&nbsp;&nbsp;suspend fun healthCheck(): Boolean<br/><br/>'
    '&nbsp;&nbsp;// Feature interfaces - cast to specific interface based on capability<br/>'
    '&nbsp;&nbsp;fun &lt;T : TrackerFeature&gt; getFeature(featureClass: KClass&lt;T&gt;): T?<br/>'
    '}<br/><br/>'
    'interface TrackerFeature // Marker interface for feature extensions'
))

story.append(body(
    '<b>SearchableTracker</b> - Feature interface for search operations. Trackers that support searching '
    'implement this interface. The SearchRequest is a tracker-agnostic data class that each tracker maps '
    'to its own URL scheme and parameters. The SearchResult is also tracker-agnostic, containing only the '
    'fields common to all trackers (title, size, seeders, leechers, info hash, magnet link, detail URL, '
    'category). Tracker-specific metadata can be attached via a generic metadata map.'
))

story.append(code(
    '// core/tracker/api/src/main/kotlin/lava/tracker/api/SearchableTracker.kt<br/>'
    'interface SearchableTracker : TrackerFeature {<br/>'
    '&nbsp;&nbsp;suspend fun search(request: SearchRequest, page: Int = 0): SearchResult<br/>'
    '}<br/><br/>'
    'data class SearchRequest(<br/>'
    '&nbsp;&nbsp;val query: String,<br/>'
    '&nbsp;&nbsp;val categories: List&lt;String&gt; = emptyList(),<br/>'
    '&nbsp;&nbsp;val sort: SortField = SortField.DATE,<br/>'
    '&nbsp;&nbsp;val sortOrder: SortOrder = SortOrder.DESCENDING,<br/>'
    '&nbsp;&nbsp;val author: String? = null<br/>'
    ')<br/><br/>'
    'data class SearchResult(<br/>'
    '&nbsp;&nbsp;val items: List&lt;TorrentItem&gt;,<br/>'
    '&nbsp;&nbsp;val totalPages: Int,<br/>'
    '&nbsp;&nbsp;val currentPage: Int<br/>'
    ')'
))

story.append(body(
    '<b>BrowsableTracker</b> - Feature interface for browsing categories and forum-like structures. '
    '<b>TopicTracker</b> - For viewing topic details and comments. <b>FavoritesTracker</b> - For '
    'managing user favorites. <b>AuthenticatableTracker</b> - For login, logout, and session management. '
    '<b>DownloadableTracker</b> - For torrent file downloads. Each of these is a separate interface that '
    'trackers implement only if they support that feature. The getFeature() method on TrackerClient '
    'provides type-safe access to feature implementations.'
))

story.append(h3('2.1.2 Common Data Model'))
story.append(body(
    'A new tracker-agnostic data model replaces the current RuTracker-specific DTOs. The core data class '
    'is <b>TorrentItem</b>, which represents a single torrent across all trackers:'
))

story.append(code(
    '// core/tracker/api/src/main/kotlin/lava/tracker/api/model/TorrentItem.kt<br/>'
    'data class TorrentItem(<br/>'
    '&nbsp;&nbsp;val trackerId: String,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Source tracker identifier<br/>'
    '&nbsp;&nbsp;val torrentId: String,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Tracker-specific ID<br/>'
    '&nbsp;&nbsp;val title: String,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Display title<br/>'
    '&nbsp;&nbsp;val sizeBytes: Long?,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// File size in bytes<br/>'
    '&nbsp;&nbsp;val seeders: Int?,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Seed count<br/>'
    '&nbsp;&nbsp;val leechers: Int?,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Leech count<br/>'
    '&nbsp;&nbsp;val infoHash: String?,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// BitTorrent info hash<br/>'
    '&nbsp;&nbsp;val magnetUri: String?,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Magnet link URI<br/>'
    '&nbsp;&nbsp;val downloadUrl: String?,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// .torrent file URL<br/>'
    '&nbsp;&nbsp;val detailUrl: String?,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Detail page URL<br/>'
    '&nbsp;&nbsp;val category: String?,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Category name/ID<br/>'
    '&nbsp;&nbsp;val publishDate: Instant?,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Publication timestamp<br/>'
    '&nbsp;&nbsp;val metadata: Map&lt;String, String&gt; = emptyMap() // Tracker-specific metadata<br/>'
    ')'
))

story.append(h3('2.1.3 TrackerRegistry and Factory'))
story.append(body(
    'The TrackerRegistry is a central component that manages all available tracker implementations. It '
    'provides discovery, instantiation, and lifecycle management for tracker clients. Tracker implementations '
    'are registered either programmatically or via a service loader mechanism. The registry supports '
    'creating clients for specific trackers by ID, listing all available trackers, and querying which '
    'trackers support specific capabilities.'
))

story.append(code(
    '// core/tracker/registry/src/main/kotlin/lava/tracker/registry/TrackerRegistry.kt<br/>'
    'interface TrackerRegistry {<br/>'
    '&nbsp;&nbsp;fun getAvailableTrackers(): List&lt;TrackerDescriptor&gt;<br/>'
    '&nbsp;&nbsp;fun createClient(trackerId: String, config: TrackerConfig): TrackerClient<br/>'
    '&nbsp;&nbsp;fun getTrackersWithCapability(cap: TrackerCapability): List&lt;TrackerDescriptor&gt;<br/>'
    '&nbsp;&nbsp;fun registerFactory(factory: TrackerClientFactory)<br/>'
    '&nbsp;&nbsp;fun unregisterFactory(trackerId: String)<br/>'
    '}<br/><br/>'
    'interface TrackerClientFactory {<br/>'
    '&nbsp;&nbsp;val descriptor: TrackerDescriptor<br/>'
    '&nbsp;&nbsp;fun create(config: TrackerConfig): TrackerClient<br/>'
    '}'
))

# 2.2 Client SDK Design
story.append(Spacer(1, 12))
story.append(h2('2.2 Client SDK Design (Android)'))

story.append(body(
    'The Client SDK provides a high-level API for the Android application to interact with any configured '
    'tracker. It abstracts away the differences between trackers and provides a unified interface for '
    'common operations. The SDK is responsible for: (1) managing tracker client lifecycle, (2) handling '
    'authentication flows, (3) providing unified search/browse/download operations, (4) managing mirror '
    'fallback, and (5) caching results locally.'
))

story.append(body(
    'The SDK is organized as a new Gradle module :core:tracker:client that depends on :core:tracker:api '
    'and :core:tracker:registry. It provides a single entry point, the LavaTrackerSdk class, which '
    'encapsulates all tracker interactions. The SDK is initialized lazily on first use and can be '
    'configured with tracker preferences, cache settings, and network parameters.'
))

story.append(code(
    '// core/tracker/client/src/main/kotlin/lava/tracker/client/LavaTrackerSdk.kt<br/>'
    'class LavaTrackerSdk private constructor(<br/>'
    '&nbsp;&nbsp;private val registry: TrackerRegistry,<br/>'
    '&nbsp;&nbsp;private val mirrorManager: MirrorManager,<br/>'
    '&nbsp;&nbsp;private val cache: TrackerCache<br/>'
    ') {<br/>'
    '&nbsp;&nbsp;suspend fun search(query: String): List&lt;TorrentItem&gt;<br/>'
    '&nbsp;&nbsp;suspend fun browse(category: String?, page: Int): List&lt;TorrentItem&gt;<br/>'
    '&nbsp;&nbsp;suspend fun getTopic(torrentId: String): TopicDetail<br/>'
    '&nbsp;&nbsp;suspend fun downloadTorrent(torrentId: String): ByteArray<br/>'
    '&nbsp;&nbsp;suspend fun getMagnetLink(torrentId: String): String<br/>'
    '&nbsp;&nbsp;fun getActiveTracker(): TrackerDescriptor<br/>'
    '&nbsp;&nbsp;suspend fun switchTracker(trackerId: String)<br/>'
    '&nbsp;&nbsp;companion object { ... } // Lazy singleton via DSL builder<br/>'
    '}'
))

# 2.3 Backend SDK Design
story.append(Spacer(1, 12))
story.append(h2('2.3 Backend SDK Design (Go)'))

story.append(body(
    'The Backend SDK mirrors the Client SDK design in Go. It is organized as a Go package within '
    'lava-api-go/internal/tracker/. The Go SDK follows the same interface-based design with TrackerClient, '
    'TrackerDescriptor, TrackerRegistry, and feature interfaces. The Go implementation benefits from '
    'interface satisfaction being implicit (no "implements" keyword needed), making it easy to create '
    'new tracker implementations as long as they satisfy the interface contracts.'
))

story.append(code(
    '// lava-api-go/internal/tracker/tracker.go<br/>'
    'type TrackerClient interface {<br/>'
    '&nbsp;&nbsp;Descriptor() TrackerDescriptor<br/>'
    '&nbsp;&nbsp;HealthCheck(ctx context.Context) error<br/>'
    '&nbsp;&nbsp;Search(ctx context.Context, req SearchRequest, page int) (*SearchResult, error)<br/>'
    '&nbsp;&nbsp;Browse(ctx context.Context, category string, page int) (*BrowseResult, error)<br/>'
    '&nbsp;&nbsp;GetTopic(ctx context.Context, id string) (*TopicDetail, error)<br/>'
    '&nbsp;&nbsp;Download(ctx context.Context, id string) ([]byte, error)<br/>'
    '&nbsp;&nbsp;Close() error<br/>'
    '}<br/><br/>'
    'type TrackerDescriptor struct {<br/>'
    '&nbsp;&nbsp;ID          string<br/>'
    '&nbsp;&nbsp;DisplayName string<br/>'
    '&nbsp;&nbsp;BaseURLs    []MirrorURL<br/>'
    '&nbsp;&nbsp;Capabilities []Capability<br/>'
    '&nbsp;&nbsp;AuthType    AuthType<br/>'
    '&nbsp;&nbsp;Encoding    string<br/>'
    '}'
))

# 2.4 Mirror & Fallback Architecture
story.append(Spacer(1, 12))
story.append(h2('2.4 Mirror and Fallback Architecture'))

story.append(body(
    'Mirror support is a critical requirement given that both RuTracker and RuTor are blocked in Russia '
    'and rely on mirror addresses for accessibility. The MirrorManager component manages a list of mirror '
    'URLs for each tracker, monitors their health, and automatically switches to a working mirror when '
    'the primary address becomes unavailable. The fallback mechanism operates at two levels: the HTTP '
    'client level (automatic retry with different mirrors) and the SDK level (switching to a different '
    'tracker if all mirrors of the current tracker fail).'
))

story.append(h3('2.4.1 MirrorUrl and MirrorState'))
story.append(code(
    '// core/tracker/api/src/main/kotlin/lava/tracker/api/MirrorUrl.kt<br/>'
    'data class MirrorUrl(<br/>'
    '&nbsp;&nbsp;val url: String,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Full URL<br/>'
    '&nbsp;&nbsp;val isPrimary: Boolean = false,&nbsp;&nbsp;// Primary vs mirror<br/>'
    '&nbsp;&nbsp;val priority: Int = 0,&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Lower = higher priority<br/>'
    '&nbsp;&nbsp;val protocol: Protocol = Protocol.HTTPS,&nbsp;&nbsp;// HTTP, HTTPS, HTTP3<br/>'
    '&nbsp;&nbsp;val region: String? = null&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;// Optional region hint<br/>'
    ')<br/><br/>'
    'enum class Protocol { HTTP, HTTPS, HTTP3 }'
))

story.append(body(
    'Each mirror has a health state tracked by the MirrorManager: HEALTHY (last check succeeded), '
    'DEGRADED (slow responses or intermittent failures), UNHEALTHY (consecutive failures), and UNKNOWN '
    '(not yet checked). The health state is persisted to local storage so that health information survives '
    'app restarts. The MirrorManager periodically probes mirrors in the background (using WorkManager on '
    'Android, goroutines with ticker on Go) and updates their states.'
))

story.append(h3('2.4.2 Fallback Strategy'))
story.append(body(
    'The fallback strategy follows a waterfall pattern: (1) Try the primary URL with HTTP/3, (2) Fall back '
    'to HTTP/2 if HTTP/3 fails, (3) Try the next mirror by priority, (4) If all mirrors fail for the '
    'current tracker, switch to an alternative tracker with similar capabilities, (5) If no tracker is '
    'available, show a cached result if available, or display an error. Each step has a configurable '
    'timeout. The circuit breaker pattern (already present in the Go client) is applied per-mirror to '
    'prevent wasting time on known-bad endpoints. The Kotlin implementation will use Resilience4j or a '
    'custom coroutine-based circuit breaker.'
))

# 2.5 Module Organization
story.append(Spacer(1, 12))
story.append(h2('2.5 New Module Organization'))

story.append(body(
    'The following new Gradle modules are introduced to support the decoupled architecture. These modules '
    'follow the existing convention plugin system in buildSrc/ and use the appropriate plugin IDs for '
    'their type (Kotlin library, Android feature, etc.).'
))

story.append(make_table(
    ['New Module', 'Plugin', 'Purpose'],
    [
        [':core:tracker:api', 'lava.kotlin.library', 'Core interfaces, models, enums'],
        [':core:tracker:registry', 'lava.kotlin.library', 'TrackerRegistry, factories'],
        [':core:tracker:client', 'lava.kotlin.library', 'Client SDK (high-level API)'],
        [':core:tracker:rutracker', 'lava.kotlin.library', 'RuTracker implementation (refactored)'],
        [':core:tracker:rutor', 'lava.kotlin.library', 'RuTor implementation (new)'],
        [':core:tracker:mirror', 'lava.kotlin.library', 'MirrorManager, health checking'],
        [':core:tracker:testing', 'lava.kotlin.library', 'Test fakes, fixtures, helpers'],
        [':feature:tracker_settings', 'lava.android.feature', 'UI for tracker configuration'],
    ],
    col_widths=[130, 120, AVAIL_W - 255]
))
story.append(Paragraph('Table 4: New Gradle Modules for Multi-Tracker SDK', sCaption))

story.append(body(
    'In Go, the equivalent package structure under lava-api-go/internal/tracker/ includes: tracker.go '
    '(interfaces), registry.go (registry implementation), rutracker/ (refactored implementation), rutor/ '
    '(new implementation), mirror/ (mirror management), and testing/ (test utilities). The existing '
    'lava-api-go/internal/rutracker/ code is refactored into the new tracker/rutracker/ package with the '
    'tracker.TrackerClient interface satisfied by the existing client.Client struct.'
))

# ══════════════════════════════════════════════════════════════
# PART III: INNOVATION INTEGRATION
# ══════════════════════════════════════════════════════════════
story.append(Spacer(1, 18))
story.append(h1('Part III: Innovation Integration Plan'))

story.append(h2('3.1 HTTP/3 (QUIC) Integration'))

story.append(body(
    'HTTP/3 support is already partially available in the project through the vasic-digital/HTTP3 submodule '
    'used by the Go API server. For the Android client, Ktor does not currently support QUIC (issue KTOR-7938, '
    'no timeline). The recommended approach is to use the Android\'s built-in OkHttp engine with Cronet for '
    'HTTP/3 support, or to use a custom QUIC library. For the Go side, quic-go is already integrated via '
    'the HTTP3 submodule.'
))

story.append(body(
    'The HTTP/3 integration strategy is layered: (1) The MirrorUrl already has a Protocol enum with HTTP3, '
    'allowing per-mirror protocol selection. (2) The HTTP client factory creates different client instances '
    'based on the protocol: OkHttp for HTTP/1.1 and HTTP/2, Cronet for HTTP/3 on Android. (3) The fallback '
    'mechanism tries HTTP/3 first, then falls back to HTTP/2, then HTTP/1.1. (4) The Alt-Svc header in '
    'responses is monitored to discover HTTP/3 endpoints automatically. (5) QUIC connection migration is '
    'leveraged for seamless network transitions (WiFi to cellular).'
))

story.append(h3('Implementation Details'))
story.append(bullet('Android: Use OkHttp with Cronet engine (cronet-api, cronet-embedded) for HTTP/3'))
story.append(bullet('Go: quic-go is already integrated via vasic-digital/HTTP3 submodule'))
story.append(bullet('Kotlin Proxy: Consider replacing Ktor with Netty-based HTTP/3 or delegating to Go'))
story.append(bullet('Connection pooling per mirror with protocol-specific keep-alive settings'))
story.append(bullet('0-RTT connection resumption for returning to the same mirror'))
story.append(bullet('Automatic protocol negotiation via Alt-Svc header monitoring'))

story.append(h2('3.2 Brotli Compression'))

story.append(body(
    'Brotli compression is already partially implemented in the Go API server via the vasic-digital/Middleware '
    'submodule. For the Android client, OkHttp supports Brotli via the okhttp-brotli module. The integration '
    'plan extends Brotli to all components: (1) Enable Brotli as a response compression format on both client '
    'and server, with gzip as fallback. (2) Use Brotli for local cache compression in the Room database and '
    'file cache. (3) Apply Brotli compression to API responses from the proxy/Go server. (4) Use Brotli for '
    'HTML response decompression when fetching tracker pages.'
))

story.append(body(
    'The DecompressionMethods.Brotli flag is available in OkHttp via the okhttp-brotli artifact. On the Go '
    'side, the andybalholm/brotli package is used for both compression and decompression. Benchmarks show '
    'Brotli provides 15-25% better compression than gzip for HTML content (tracker pages are heavily text-based '
    'and benefit significantly from Brotli\'s LZ77 + Huffman + context modeling approach).'
))

story.append(h2('3.3 Lock-Free Non-Blocking Threading'))

story.append(body(
    'The current architecture uses coroutines for async operations but does not explicitly manage concurrency '
    'with semaphores or lock-free patterns. The integration plan introduces structured concurrency with '
    'semaphore-bounded parallelism for tracker operations. On Kotlin, kotlinx.coroutines.sync.Semaphore '
    'bounds the number of concurrent tracker requests. On Go, golang.org/x/sync/semaphore provides the '
    'same functionality. Channel-based communication replaces shared mutable state for cross-component data '
    'flow.'
))

story.append(body(
    'Lock-free patterns are applied where possible: (1) AtomicReference for single-value shared state '
    '(current tracker, auth token). (2) ConcurrentHashMap for thread-safe caches. (3) Mutex-free reads '
    'using copy-on-write patterns for configuration data that changes infrequently. (4) Structured '
    'concurrency with coroutine scopes ensures proper cancellation and cleanup. The key principle is '
    'that the UI thread must never block, and all tracker operations must be cancellable.'
))

story.append(h2('3.4 Lazy vs Eager Initialization'))

story.append(body(
    'Initialization strategy is carefully chosen per component to balance startup performance with '
    'first-use responsiveness. Components are categorized into three tiers:'
))

story.append(make_table(
    ['Tier', 'Strategy', 'Components'],
    [
        ['Tier 1: App Startup', 'Eager (with timeout)', 'TrackerRegistry, local DB, preferences'],
        ['Tier 2: After Auth', 'Lazy (on first use)', 'TrackerClient, MirrorManager, cache'],
        ['Tier 3: On Demand', 'Lazy (full)', 'Search indexer, torrent parser, HTML fetcher'],
    ],
    col_widths=[100, 110, AVAIL_W - 215]
))
story.append(Paragraph('Table 5: Initialization Strategy by Tier', sCaption))

story.append(body(
    'On Kotlin, lazy initialization uses the "by lazy { }" delegate with LazyThreadSafetyMode.PUBLICATION '
    'for components that can tolerate double-initialization (for speed) or SYNCHRONIZED for components '
    'that must be initialized exactly once. On Go, sync.Once is used for one-time initialization, and '
    'sync.OnceValue (Go 1.21+) provides a typed, lazy-initialized value. The Dagger Hilt graph manages '
    'eager initialization for Android components via @EntryPoint annotations.'
))

story.append(h2('3.5 Semaphore-Based Concurrency Control'))

story.append(body(
    'Semaphores are used to bound concurrency at multiple levels: (1) Per-tracker semaphore limits '
    'concurrent requests to a single tracker (default: 4 concurrent requests). (2) Global network '
    'semaphore limits total concurrent network operations (default: 8). (3) Per-mirror semaphore prevents '
    'overwhelming a single mirror endpoint. (4) I/O semaphore bounds concurrent file operations (torrent '
    'file writes, cache reads/writes). Each semaphore is configurable and exposed to the SDK consumer '
    'for fine-tuning based on device capabilities and network conditions.'
))

story.append(code(
    '// Kotlin implementation<br/>'
    'class TrackerClientImpl(...) : TrackerClient {<br/>'
    '&nbsp;&nbsp;private val requestSemaphore = Semaphore(permits = 4)<br/><br/>'
    '&nbsp;&nbsp;override suspend fun search(request: SearchRequest, page: Int): SearchResult {<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;return requestSemaphore.withPermit {<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;mirrorManager.executeWithFallback { mirror -&gt;<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;httpClient.get(mirror.url) { ... }<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;}<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;}<br/>'
    '&nbsp;&nbsp;}<br/>'
    '}'
))

# ══════════════════════════════════════════════════════════════
# PART IV: IMPLEMENTATION PLAN
# ══════════════════════════════════════════════════════════════
story.append(Spacer(1, 18))
story.append(h1('Part IV: Phase-Based Implementation Plan'))

story.append(body(
    'The implementation is organized into 8 phases with 47 tasks and 186 sub-tasks. Each phase builds '
    'on the previous one and has clear acceptance criteria. Phases 1-3 are sequential (foundation before '
    'decoupling before new implementation). Phases 4-5 can be partially parallelized. Phase 6 (testing) '
    'runs concurrently with all other phases. Phase 7 (documentation) is ongoing throughout.'
))

# Phase 1
story.append(Spacer(1, 12))
story.append(h2('Phase 1: Foundation - Interfaces and Abstractions'))
story.append(body('Duration: 2 weeks | Tasks: 7 | Sub-tasks: 32'))

story.append(h3('Task 1.1: Create core/tracker/api module'))
story.append(body(
    'Create the :core:tracker:api Gradle module using the lava.kotlin.library convention plugin. Define '
    'package structure: lava.tracker.api (interfaces), lava.tracker.api.model (data classes), '
    'lava.tracker.api.capability (enums). Reference file: buildSrc/src/main/kotlin/AndroidApplicationConventionPlugin.kt '
    'for convention plugin patterns. Register the module in settings.gradle.kts alongside existing '
    'core modules.'
))
story.append(bullet('Sub-task 1.1.1: Add module to settings.gradle.kts'))
story.append(bullet('Sub-task 1.1.2: Create build.gradle.kts with lava.kotlin.library plugin'))
story.append(bullet('Sub-task 1.1.3: Add kotlinx.serialization dependency for model classes'))
story.append(bullet('Sub-task 1.1.4: Define package structure (api, api.model, api.capability)'))

story.append(h3('Task 1.2: Define TrackerDescriptor and TrackerCapability'))
story.append(body(
    'Create TrackerDescriptor.kt, TrackerCapability.kt, AuthType.kt, Protocol.kt, and MirrorUrl.kt in '
    'the api package. These are the foundational types that all other interfaces depend on. The enums must '
    'be annotated with @Serializable for use across module boundaries. Reference the existing '
    'core/models/src/main/kotlin/lava/models/ for naming conventions and patterns used in the project.'
))
story.append(bullet('Sub-task 1.2.1: Define TrackerCapability enum with all 13 values'))
story.append(bullet('Sub-task 1.2.2: Define AuthType enum (NONE, FORM_LOGIN, CAPTCHA_LOGIN, OAUTH, API_KEY)'))
story.append(bullet('Sub-task 1.2.3: Define Protocol enum (HTTP, HTTPS, HTTP3)'))
story.append(bullet('Sub-task 1.2.4: Define MirrorUrl data class with url, isPrimary, priority, protocol, region'))
story.append(bullet('Sub-task 1.2.5: Define TrackerDescriptor interface'))
story.append(bullet('Sub-task 1.2.6: Write unit tests for all new types'))

story.append(h3('Task 1.3: Define TrackerClient and Feature Interfaces'))
story.append(body(
    'Create TrackerClient.kt, TrackerFeature.kt, and all feature interfaces: SearchableTracker.kt, '
    'BrowsableTracker.kt, TopicTracker.kt, CommentsTracker.kt, FavoritesTracker.kt, '
    'AuthenticatableTracker.kt, DownloadableTracker.kt. Each feature interface defines only the methods '
    'relevant to that feature. The TrackerClient.getFeature() method provides type-safe access. '
    'Reference the existing NetworkApi.kt at core/network/api/src/main/kotlin/lava/network/api/NetworkApi.kt '
    'for the method signatures that need to be preserved in the new interfaces.'
))
story.append(bullet('Sub-task 1.3.1: Define TrackerClient interface with descriptor, healthCheck, getFeature'))
story.append(bullet('Sub-task 1.3.2: Define SearchableTracker with search(request, page)'))
story.append(bullet('Sub-task 1.3.3: Define BrowsableTracker with browse(category, page) and getForumTree()'))
story.append(bullet('Sub-task 1.3.4: Define TopicTracker with getTopic(id) and getTopicPage(id, page)'))
story.append(bullet('Sub-task 1.3.5: Define CommentsTracker with getComments(id, page) and addComment(id, msg)'))
story.append(bullet('Sub-task 1.3.6: Define FavoritesTracker with CRUD for favorites'))
story.append(bullet('Sub-task 1.3.7: Define AuthenticatableTracker with login, logout, checkAuth'))
story.append(bullet('Sub-task 1.3.8: Define DownloadableTracker with download(id) and getMagnet(id)'))

story.append(h3('Task 1.4: Define Common Data Model'))
story.append(body(
    'Create tracker-agnostic data classes: TorrentItem.kt, SearchRequest.kt, SearchResult.kt, '
    'TopicDetail.kt, Comment.kt, ForumCategory.kt, CategoryPage.kt, LoginRequest.kt, LoginResult.kt, '
    'AuthState.kt. These replace the current RuTracker-specific DTOs. Each data class includes a '
    'trackerId field for source identification and a metadata map for tracker-specific data. Reference '
    'the existing DTOs in core/network/api/src/main/kotlin/lava/network/api/ for the field sets that '
    'need to be preserved.'
))
story.append(bullet('Sub-task 1.4.1: Define TorrentItem with all common fields + metadata map'))
story.append(bullet('Sub-task 1.4.2: Define SearchRequest with query, categories, sort, author'))
story.append(bullet('Sub-task 1.4.3: Define SearchResult with items, totalPages, currentPage'))
story.append(bullet('Sub-task 1.4.4: Define TopicDetail, Comment, ForumCategory'))
story.append(bullet('Sub-task 1.4.5: Define auth-related types (LoginRequest, LoginResult, AuthState)'))

story.append(h3('Task 1.5: Create TrackerRegistry module'))
story.append(body(
    'Create the :core:tracker:registry module with TrackerRegistry interface and DefaultTrackerRegistry '
    'implementation. The registry uses a ConcurrentHashMap of factory instances keyed by tracker ID. '
    'Include a TrackerClientFactory interface for creating tracker instances. Reference the existing '
    'SwitchingNetworkApi at core/network/impl/ for the current pattern of switching between implementations.'
))
story.append(bullet('Sub-task 1.5.1: Create module with lava.kotlin.library plugin'))
story.append(bullet('Sub-task 1.5.2: Define TrackerClientFactory interface'))
story.append(bullet('Sub-task 1.5.3: Implement DefaultTrackerRegistry with ConcurrentHashMap'))
story.append(bullet('Sub-task 1.5.4: Implement createClient() with factory lookup and config injection'))
story.append(bullet('Sub-task 1.5.5: Write unit tests for registry operations'))

story.append(h3('Task 1.6: Create Mirror Management module'))
story.append(body(
    'Create the :core:tracker:mirror module with MirrorManager interface and implementation. The '
    'MirrorManager tracks health state per mirror, performs periodic health checks, and provides '
    'the executeWithFallback() method that tries mirrors in priority order. Health state is persisted '
    'to SharedPreferences/Room. Include HealthState enum (HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN).'
))
story.append(bullet('Sub-task 1.6.1: Create module with lava.kotlin.library plugin'))
story.append(bullet('Sub-task 1.6.2: Define MirrorManager interface with getHealthyMirror, executeWithFallback'))
story.append(bullet('Sub-task 1.6.3: Implement DefaultMirrorManager with in-memory health tracking'))
story.append(bullet('Sub-task 1.6.4: Implement health check scheduling via coroutine-based ticker'))
story.append(bullet('Sub-task 1.6.5: Persist health state to SharedPreferences'))
story.append(bullet('Sub-task 1.6.6: Write unit tests with mock HTTP responses'))

story.append(h3('Task 1.7: Create Testing module'))
story.append(body(
    'Create the :core:tracker:testing module with test fakes and helpers. Include: FakeTrackerClient '
    '(implements all feature interfaces with configurable behavior), FakeMirrorManager, test fixture '
    'builders (TorrentItemBuilder, SearchRequestBuilder), and HTML fixture files for parser testing. '
    'Reference the existing core/testing/src/main/kotlin/lava/testing/ for patterns used in the project '
    '(MainDispatcherRule, fake repositories).'
))
story.append(bullet('Sub-task 1.7.1: Create module with lava.kotlin.library plugin (test source set)'))
story.append(bullet('Sub-task 1.7.2: Implement FakeTrackerClient with configurable responses'))
story.append(bullet('Sub-task 1.7.3: Implement FakeMirrorManager with configurable health states'))
story.append(bullet('Sub-task 1.7.4: Create DSL builders for test data (TorrentItem, SearchRequest)'))
story.append(bullet('Sub-task 1.7.5: Port existing Go HTML fixtures to Kotlin test resources'))

# Phase 2
story.append(Spacer(1, 12))
story.append(h2('Phase 2: RuTracker Decoupling'))
story.append(body('Duration: 3 weeks | Tasks: 8 | Sub-tasks: 41'))

story.append(h3('Task 2.1: Refactor core/network/rutracker module'))
story.append(body(
    'Rename and restructure the existing core:network:rutracker module to core:tracker:rutracker. '
    'Update settings.gradle.kts, all import paths, and build files. The module currently contains 18+ '
    'UseCase classes in the lava.network.domain package that parse RuTracker HTML. Each UseCase will be '
    'refactored to implement the corresponding feature interface from core/tracker/api. The RuTrackerInnerApi '
    'interface stays as an internal implementation detail.'
))
story.append(bullet('Sub-task 2.1.1: Rename module in settings.gradle.kts from :core:network:rutracker to :core:tracker:rutracker'))
story.append(bullet('Sub-task 2.1.2: Update build.gradle.kts to depend on :core:tracker:api instead of :core:network:api'))
story.append(bullet('Sub-task 2.1.3: Move package from lava.network.rutracker to lava.tracker.rutracker'))
story.append(bullet('Sub-task 2.1.4: Update all imports across the project'))
story.append(bullet('Sub-task 2.1.5: Run spotlessApply and verify build compiles'))

story.append(h3('Task 2.2: Implement RuTrackerDescriptor'))
story.append(body(
    'Create RuTrackerDescriptor.kt that implements TrackerDescriptor with RuTracker-specific metadata: '
    'trackerId = "rutracker", displayName = "RuTracker.org", baseUrls = [rutracker.org primary + '
    'known mirrors], capabilities = all 13 capabilities (RuTracker supports everything), authType = '
    'CAPTCHA_LOGIN, encoding = "Windows-1251". Reference the existing URL patterns in the Go client at '
    'lava-api-go/internal/rutracker/client.go for the base URL and endpoint patterns.'
))

story.append(h3('Task 2.3: Implement RuTrackerClient'))
story.append(body(
    'Create RuTrackerClient.kt that implements TrackerClient and all feature interfaces. This class '
    'wraps the existing UseCase classes and delegates to them. The RuTrackerClient creates and manages '
    'the RuTrackerInnerApi HTTP client, handles authentication state, and provides feature interface '
    'instances via getFeature(). This is the largest single task as it requires bridging the old '
    'UseCase-based architecture with the new interface-based design. Reference the existing '
    'RuTrackerNetworkApi (wherever it lives in the rutracker module) for the current delegation pattern.'
))
story.append(bullet('Sub-task 2.3.1: Create RuTrackerClient class implementing TrackerClient'))
story.append(bullet('Sub-task 2.3.2: Implement descriptor property returning RuTrackerDescriptor'))
story.append(bullet('Sub-task 2.3.3: Implement getFeature() with type-safe casts to feature interfaces'))
story.append(bullet('Sub-task 2.3.4: Implement SearchableTracker using GetSearchPageUseCase'))
story.append(bullet('Sub-task 2.3.5: Implement BrowsableTracker using GetForumUseCase + GetCategoryPageUseCase'))
story.append(bullet('Sub-task 2.3.6: Implement TopicTracker using GetTopicUseCase + GetTopicPageUseCase'))
story.append(bullet('Sub-task 2.3.7: Implement CommentsTracker using GetCommentsPageUseCase + AddCommentUseCase'))
story.append(bullet('Sub-task 2.3.8: Implement FavoritesTracker using GetFavoritesUseCase + Add/RemoveFavoriteUseCase'))
story.append(bullet('Sub-task 2.3.9: Implement AuthenticatableTracker using LoginUseCase + CheckAuthorisedUseCase'))
story.append(bullet('Sub-task 2.3.10: Implement DownloadableTracker using GetTorrentFileUseCase'))

story.append(h3('Task 2.4: Create DTO-to-Model Mappers'))
story.append(body(
    'Create mappers that convert between the old RuTracker-specific DTOs (AuthResponseDto, ForumDto, '
    'SearchPageDto, etc.) and the new tracker-agnostic models (TorrentItem, SearchResult, TopicDetail, '
    'etc.). These mappers are necessary because the existing UseCases return old DTOs, and the new '
    'SDK must return new models. The mappers extract common fields and store RuTracker-specific data '
    'in the metadata map. Reference the existing converter classes in core/data/converters/ for patterns.'
))
story.append(bullet('Sub-task 2.4.1: Create ForumDtoMapper to convert ForumDto to List<ForumCategory>'))
story.append(bullet('Sub-task 2.4.2: Create SearchMapper to convert SearchPageDto to SearchResult'))
story.append(bullet('Sub-task 2.4.3: Create TopicMapper to convert ForumTopicDto/TopicPageDto to TopicDetail'))
story.append(bullet('Sub-task 2.4.4: Create CommentsMapper for comment conversion'))
story.append(bullet('Sub-task 2.4.5: Create TorrentMapper for torrent metadata conversion'))

story.append(h3('Task 2.5: Register RuTracker in Registry'))
story.append(body(
    'Create RuTrackerClientFactory and register it in the DI module. Update Hilt modules to provide '
    'TrackerRegistry with the RuTracker factory pre-registered. Update the SwitchingNetworkApi to '
    'delegate to the new TrackerClient instead of directly to NetworkApi implementations.'
))

story.append(h3('Task 2.6: Update ProxyNetworkApi Adapter'))
story.append(body(
    'Create an adapter that wraps TrackerClient (specifically RuTrackerClient) to implement the old '
    'NetworkApi interface. This allows the existing proxy routes and feature modules to continue '
    'working while the migration is in progress. The adapter translates NetworkApi calls to '
    'TrackerClient feature interface calls and maps new model types back to old DTO types.'
))

story.append(h3('Task 2.7: Refactor Go Rutracker Package'))
story.append(body(
    'In the Go API server, refactor lava-api-go/internal/rutracker/ to implement the new '
    'tracker.TrackerClient interface. Create a wrapper type that embeds the existing client.Client '
    'and adds the HealthCheck(), Search(), Browse(), GetTopic(), Download() methods that delegate '
    'to the existing parser functions. Update the OpenAPI spec handlers to use the new interface '
    'instead of calling parser functions directly. Reference: lava-api-go/internal/rutracker/client.go '
    'for the existing Client struct and lava-api-go/internal/handlers/ for the current handler pattern.'
))

story.append(h3('Task 2.8: Verify Backward Compatibility'))
story.append(body(
    'Run all existing tests to verify that the refactored RuTracker implementation produces identical '
    'results to the original. Compare DTO output byte-for-byte with the old implementation. Run the '
    'parity tests between Kotlin and Go. Verify that the proxy routes produce identical JSON responses. '
    'This is the acceptance gate for Phase 2: zero behavioral change, only structural refactoring.'
))

# Phase 3
story.append(Spacer(1, 12))
story.append(h2('Phase 3: RuTor Implementation'))
story.append(body('Duration: 3 weeks | Tasks: 7 | Sub-tasks: 38'))

story.append(h3('Task 3.1: Create core:tracker:rutor module'))
story.append(body(
    'Create the :core:tracker:rutor Gradle module using the lava.kotlin.library convention plugin. '
    'Add dependencies on :core:tracker:api, Jsoup 1.15.3 (for HTML parsing), OkHttp (for HTTP), '
    'and kotlinx.serialization. Reference the RuTracker module structure for package layout patterns. '
    'Base URLs for RuTor: https://rutor.info (primary), https://rutor.is (mirror), https://www.rutor.info, '
    'https://www.rutor.is, http://6tor.org (IPv6-only).'
))
story.append(bullet('Sub-task 3.1.1: Add module to settings.gradle.kts'))
story.append(bullet('Sub-task 3.1.2: Create build.gradle.kts with lava.kotlin.library + Jsoup + OkHttp'))
story.append(bullet('Sub-task 3.1.3: Define package: lava.tracker.rutor'))
story.append(bullet('Sub-task 3.1.4: Create test source set with HTML fixtures'))

story.append(h3('Task 3.2: Implement RuTorDescriptor'))
story.append(body(
    'Create RuTorDescriptor with: trackerId = "rutor", displayName = "RuTor.info", baseUrls = [rutor.info, '
    'rutor.is, www.rutor.info, www.rutor.is, 6tor.org], capabilities = SEARCH, BROWSE, TOPIC, COMMENTS, '
    'TORRENT_DOWNLOAD, MAGNET_LINK, RSS (no FORUM, no FAVORITES in the same way as RuTracker), '
    'authType = FORM_LOGIN (simple, no CAPTCHA), encoding = "UTF-8". Note: RuTor does not require '
    'authentication for search, browse, or download, but does require it for comments and uploading.'
))

story.append(h3('Task 3.3: Implement RuTor HTTP Client'))
story.append(body(
    'Create RuTorHttpClient.kt that handles HTTP communication with RuTor servers. Unlike the RuTracker '
    'client which needs charset transcoding (Windows-1251 to UTF-8), RuTor uses UTF-8 natively, simplifying '
    'the client. The client must handle: (1) UTF-8 responses directly, (2) Cookie management for '
    'authenticated sessions, (3) User-Agent header to avoid blocks, (4) Request rate limiting via semaphore, '
    '(5) Connection to download server d.rutor.info for .torrent files. Test credentials: username '
    '"nobody85perfect", password "ironman1985".'
))
story.append(bullet('Sub-task 3.3.1: Create RuTorHttpClient with OkHttp'))
story.append(bullet('Sub-task 3.3.2: Implement get(url) with UTF-8 handling'))
story.append(bullet('Sub-task 3.3.3: Implement post(url, form) for login'))
story.append(bullet('Sub-task 3.3.4: Implement download(url) for .torrent files'))
story.append(bullet('Sub-task 3.3.5: Add semaphore-bounded concurrency (max 4 concurrent)'))
story.append(bullet('Sub-task 3.3.6: Add circuit breaker (3 failures, 30s reset)'))

story.append(h3('Task 3.4: Implement RuTor HTML Parsers'))
story.append(body(
    'Create parser classes for each RuTor operation using Jsoup CSS selectors. The selectors are documented '
    'in Table 2 of this document. Key parsers: (1) RuTorSearchParser - parses /search/{page}/{cat}/... '
    'pages, (2) RuTorBrowseParser - parses /browse/{page}/{cat}/... pages, (3) RuTorTopicParser - parses '
    '/torrent/{id}/{slug} pages, (4) RuTorLoginParser - handles /users.php?login form submission and '
    'response. Each parser extracts data from HTML and maps it to tracker-agnostic model types '
    '(TorrentItem, SearchResult, TopicDetail, etc.). The Jackett rutor.yml definition provides battle-tested '
    'CSS selectors that should be used as the primary reference.'
))
story.append(bullet('Sub-task 3.4.1: Create RuTorSearchParser using selectors from Table 2'))
story.append(bullet('Sub-task 3.4.2: Handle variable column count due to comment-count td'))
story.append(bullet('Sub-task 3.4.3: Parse Russian date format (DD MMM YY) with month abbreviations'))
story.append(bullet('Sub-task 3.4.4: Parse size with non-breaking space separator'))
story.append(bullet('Sub-task 3.4.5: Extract info hash from magnet links via regex'))
story.append(bullet('Sub-task 3.4.6: Create RuTorBrowseParser for category browsing'))
story.append(bullet('Sub-task 3.4.7: Create RuTorTopicParser for torrent detail pages'))
story.append(bullet('Sub-task 3.4.8: Create RuTorLoginParser for authentication'))
story.append(bullet('Sub-task 3.4.9: Create RuTorCategoryParser for category listing'))

story.append(h3('Task 3.5: Implement RuTorClient'))
story.append(body(
    'Create RuTorClient.kt implementing TrackerClient and applicable feature interfaces (SearchableTracker, '
    'BrowsableTracker, TopicTracker, CommentsTracker, AuthenticatableTracker, DownloadableTracker). Note '
    'that RuTor does NOT support FavoritesTracker in the same way as RuTracker, so this interface is not '
    'implemented. The RuTorDescriptor\'s capabilities set accurately reflects supported features, and the '
    'getFeature() method returns null for unsupported features. The client delegates parsing to the parser '
    'classes from Task 3.4.'
))

story.append(h3('Task 3.6: Implement Go RuTor Package'))
story.append(body(
    'Create lava-api-go/internal/tracker/rutor/ package mirroring the Kotlin implementation. Use goquery '
    'for HTML parsing (same library as the existing Go RuTracker parser). Port the CSS selectors from the '
    'Kotlin parsers. Create a client.go with the same pattern as the existing RuTracker client.go: HTTP '
    'client with circuit breaker, UTF-8 handling (simpler than RuTracker\'s charset transcoding), and '
    'the same Fetch/PostForm methods. Create parser files: search.go, browse.go, topic.go, login.go. '
    'Add HTML test fixtures in testdata/ directory.'
))

story.append(h3('Task 3.7: Integration and Parity Verification'))
story.append(body(
    'Register RuTor in both Kotlin and Go registries. Write integration tests that perform real searches '
    'against rutor.info and verify the results. Write parity tests comparing Kotlin and Go output for the '
    'same HTML fixtures. Verify that the tracker switching mechanism works: search on RuTracker, then switch '
    'to RuTor, search again, and verify both produce valid TorrentItem lists. Test the mirror fallback by '
    'configuring an invalid primary URL and verifying the client falls back to a working mirror.'
))

# Phase 4
story.append(Spacer(1, 12))
story.append(h2('Phase 4: Mirror and Fallback Mechanism'))
story.append(body('Duration: 1.5 weeks | Tasks: 5 | Sub-tasks: 20'))

story.append(h3('Task 4.1: Implement Health Check System'))
story.append(body(
    'Build the mirror health check system that periodically probes mirrors and updates their status. '
    'The health check sends a lightweight GET request to each mirror\'s root page and measures: '
    '(1) response time (for DEGRADED classification), (2) HTTP status code (200 = HEALTHY, 4xx/5xx = '
    'UNHEALTHY), (3) content validity (response contains expected tracker name/branding). On Android, '
    'health checks run via WorkManager with a periodic task (every 15 minutes). On Go, a goroutine with '
    'time.Ticker runs the checks. Health state is persisted to SharedPreferences (Android) and PostgreSQL '
    '(Go).'
))

story.append(h3('Task 4.2: Implement Fallback Chain Executor'))
story.append(body(
    'Build the executeWithFallback() method in MirrorManager that implements the waterfall fallback '
    'strategy: (1) sort mirrors by priority, (2) filter to HEALTHY and DEGRADED mirrors, (3) try each '
    'mirror with the configured protocol preference (HTTP3 > HTTP2 > HTTP1), (4) on failure, mark mirror '
    'as DEGRADED (1st failure) or UNHEALTHY (3rd consecutive failure), (5) proceed to next mirror, '
    '(6) if all mirrors fail, throw TrackerUnavailableException. The executor is cancellable via coroutine '
    'context (Kotlin) and context.Context (Go).'
))

story.append(h3('Task 4.3: Implement Cross-Tracker Fallback'))
story.append(body(
    'Extend the SDK to support cross-tracker fallback: if all mirrors of the current tracker fail and '
    'another tracker with compatible capabilities is available, automatically switch to the alternative '
    'tracker and retry the operation. This is implemented in the LavaTrackerSdk level, above the individual '
    'TrackerClient. The user is notified of the fallback via a UI indicator. The cross-tracker fallback '
    'is configurable: users can disable it if they prefer to use only their selected tracker.'
))

story.append(h3('Task 4.4: Configure Mirror Lists'))
story.append(body(
    'Create mirror configuration for both trackers. RuTracker mirrors include: rutracker.org (primary), '
    'rutracker.net, rutracker.cr, and other known mirrors. RuTor mirrors: rutor.info (primary), rutor.is '
    '(mirror), www.rutor.info, www.rutor.is, 6tor.org (IPv6). Mirror lists are stored in a configuration '
    'file (JSON) bundled with the app but updatable from a remote source. The MirrorManager loads mirrors '
    'from configuration and can merge with user-provided mirrors.'
))

story.append(h3('Task 4.5: Mirror Persistence and Sync'))
story.append(body(
    'Implement persistence of mirror health state and user preferences across app restarts. Health state '
    '(HEALTHY/DEGRADED/UNHEALTHY per mirror) is stored in Room database (Android) and PostgreSQL (Go). '
    'User preferences (preferred tracker, disabled mirrors, custom mirrors) are stored in SharedPreferences. '
    'Mirror configuration can be updated from a remote JSON endpoint, with graceful degradation if the '
    'endpoint is unavailable (use bundled defaults).'
))

# Phase 5
story.append(Spacer(1, 12))
story.append(h2('Phase 5: Innovation Integration'))
story.append(body('Duration: 2 weeks | Tasks: 6 | Sub-tasks: 24'))

story.append(h3('Task 5.1: HTTP/3 Client Integration'))
story.append(body(
    'Integrate HTTP/3 support into the tracker HTTP clients. On Android, add the okhttp-brotli and '
    'cronet dependencies. Create an Http3ClientFactory that creates Cronet-based OkHttp clients for '
    'mirrors with Protocol.HTTP3. Fall back to standard OkHttp for HTTP/1.1 and HTTP/2. On Go, the '
    'HTTP3 submodule already provides quic-go-based HTTP/3 support. Implement Alt-Svc header parsing '
    'to automatically discover HTTP/3 endpoints from HTTP/2 responses.'
))

story.append(h3('Task 5.2: Brotli Compression Integration'))
story.append(body(
    'Enable Brotli compression at all layers: (1) OkHttp client adds "br" to Accept-Encoding header '
    'via okhttp-brotli interceptor, (2) Go client adds Brotli encoding via the Middleware submodule, '
    '(3) Local cache stores compressed HTML responses using Brotli, (4) API responses from the Go server '
    'are Brotli-compressed. Benchmark compression ratios for tracker HTML content and document the results.'
))

story.append(h3('Task 5.3: Lock-Free Concurrency Primitives'))
story.append(body(
    'Implement the concurrency control layer: (1) Create a NetworkSemaphore class that wraps '
    'kotlinx.coroutines.sync.Semaphore with configuration, (2) Create AtomicReference-based state holders '
    'for current tracker and auth state, (3) Replace synchronized blocks with Mutex-free reads using '
    'copy-on-write patterns, (4) Implement channel-based event bus for cross-component communication, '
    '(5) Create a TaskScheduler that manages coroutine-based background operations with proper cancellation.'
))

story.append(h3('Task 5.4: Lazy Initialization Framework'))
story.append(body(
    'Implement the lazy initialization framework: (1) Create an AppInitializer class that manages the '
    'three-tier initialization (eager, post-auth, on-demand), (2) Each component declares its init tier '
    'via annotation or DSL, (3) The initializer tracks init progress and reports startup timing metrics, '
    '(4) Components that fail to initialize are retried with exponential backoff, (5) On Go, use '
    'sync.OnceValue for typed lazy values and an init manager goroutine for the tier system.'
))

story.append(h3('Task 5.5: SDK Diagnostics and Observability'))
story.append(body(
    'Add observability to the SDK: (1) Track request latency per tracker/mirror/operation, (2) Track '
    'fallback frequency and success rate, (3) Track cache hit/miss ratios, (4) Expose metrics via '
    'the existing Prometheus integration (Go) and a new AndroidMetrics class (Kotlin), (5) Add structured '
    'logging with the existing Logger interface, (6) Create a TrackerDiagnostics dashboard data class '
    'that summarizes all tracker health and performance metrics.'
))

story.append(h3('Task 5.6: Feature Module Updates'))
story.append(body(
    'Update existing feature modules to use the new SDK: (1) Update :feature:login to support multiple '
    'tracker authentication flows, (2) Update :feature:search to query the SDK instead of the old '
    'NetworkApi, (3) Update :feature:search_result to display tracker source alongside results, '
    '(4) Update :feature:topic to use the new TopicTracker interface, (5) Create :feature:tracker_settings '
    'for tracker selection, mirror configuration, and fallback preferences.'
))

# Phase 6
story.append(Spacer(1, 12))
story.append(h2('Phase 6: Anti-Bluff Testing and Verification'))
story.append(body('Duration: 3 weeks | Tasks: 7 | Sub-tasks: 18'))

story.append(h3('Task 6.1: Unit Tests for Core Abstractions'))
story.append(body(
    'Write comprehensive unit tests for all core tracker interfaces and their implementations. Every '
    'public method must have at least one test. Edge cases must be covered: null parameters, empty '
    'results, network timeouts, malformed HTML, unexpected response codes. Target: 100% line coverage '
    'for core/tracker/api and core/tracker/registry modules. Use FakeTrackerClient from the testing '
    'module for all tests that do not require real HTTP calls.'
))

story.append(h3('Task 6.2: Parser Tests with Real HTML Fixtures'))
story.append(body(
    'Write parser tests using real HTML fixtures scraped from live tracker pages. Fixtures must be '
    'dated and periodically refreshed (monthly) to catch tracker site changes. Each parser test: '
    '(1) loads an HTML fixture from test resources, (2) parses it with the parser under test, '
    '(3) asserts on specific extracted values (title, size, seeders, etc.). Fixtures must include '
    'normal pages, error pages, empty result pages, and pages with special characters (Cyrillic, '
    'special HTML entities).'
))

story.append(h3('Task 6.3: Integration Tests with Real Tracker Endpoints'))
story.append(body(
    'Write integration tests that perform real HTTP requests against live tracker endpoints (using the '
    'provided test credentials). These tests: (1) login to RuTracker and RuTor, (2) perform searches, '
    '(3) browse categories, (4) download torrent files, (5) verify magnet links. These tests are '
    'marked with a @RealTracker annotation and are excluded from normal CI runs (they require network '
    'access and valid credentials). They run as a separate "smoke test" suite.'
))

story.append(h3('Task 6.4: Contract Tests Between Kotlin and Go'))
story.append(body(
    'Extend the existing parity test framework to verify that both Kotlin and Go implementations produce '
    'identical TorrentItem lists for the same input HTML. Contract tests define: (1) input HTML fixtures, '
    '(2) expected output as JSON, (3) both Kotlin and Go must produce output that matches the expected '
    'JSON byte-for-byte. Use the existing lava-api-go/tests/parity/ infrastructure as a starting point.'
))

story.append(h3('Task 6.5: Challenge Tests'))
story.append(body(
    'Create executable Challenge Tests that verify end-to-end functionality. Each Challenge Test '
    'simulates a real user workflow: (1) User opens the app, (2) Selects a tracker, (3) Performs a '
    'search, (4) Views a topic, (5) Downloads a torrent file, (6) Switches tracker, (7) Repeats the '
    'search. These tests use the real UI (Compose testing), real network calls, and verify that every '
    'step produces user-visible results. Challenge Tests are the anti-bluff guarantee: if all Challenges '
    'pass, the feature works for real users.'
))

story.append(h3('Task 6.6: Fuzz Tests for HTML Parsers'))
story.append(body(
    'Create fuzz tests for all HTML parsers (both Kotlin and Go). Fuzz tests generate random HTML input '
    'and verify that parsers do not crash, hang, or produce invalid output. On Kotlin, use JUnit with '
    'random input generation. On Go, use the existing fuzz test infrastructure (lava-api-go/internal/rutracker/'
    '*_fuzz_test.go). Fuzz tests run continuously in CI and are designed to catch edge cases that '
    'hand-written tests miss.'
))

story.append(h3('Task 6.7: Mutation Tests'))
story.append(body(
    'Run mutation tests to verify that the test suite actually catches bugs. Mutation testing modifies '
    'production code (changing if conditions, swapping operators, removing lines) and checks if any tests '
    'fail. If a mutation survives (all tests pass despite the code change), the test suite has a gap. '
    'Use PITest for Kotlin and the existing scripts/mutation.sh for Go. Target: 85% mutation kill rate '
    'minimum, with a plan to reach 95% by the end of the project.'
))

# Phase 7
story.append(Spacer(1, 12))
story.append(h2('Phase 7: Documentation and User Guides'))
story.append(body('Duration: 2 weeks | Tasks: 4 | Sub-tasks: 10'))

story.append(h3('Task 7.1: SDK Developer Guide'))
story.append(body(
    'Write a comprehensive developer guide for the Multi-Tracker SDK. The guide covers: (1) Architecture '
    'overview with diagrams, (2) Adding a new tracker step-by-step, (3) Implementing TrackerClient and '
    'feature interfaces, (4) Writing parser classes with HTML selector reference, (5) Registering a tracker '
    'in the registry, (6) Testing a new tracker implementation, (7) Configuring mirrors and fallback. '
    'The guide includes working code examples for each step and references existing RuTracker/RuTor '
    'implementations as templates.'
))

story.append(h3('Task 7.2: Tracker Integration Manual'))
story.append(body(
    'Write a detailed manual for each supported tracker. Each tracker manual includes: (1) Tracker overview '
    'and characteristics, (2) Authentication flow details, (3) URL patterns for all operations, (4) HTML '
    'structure and CSS selectors used, (5) Data mapping rules (HTML element to model field), (6) Known '
    'quirks and edge cases, (7) Maintenance notes (what to check when the tracker site changes). The '
    'RuTracker manual documents the Windows-1251 encoding, CAPTCHA flow, forum structure, and all 18+ '
    'parser use cases. The RuTor manual documents the UTF-8 encoding, variable column count parsing, '
    'path-based search URLs, and magnet link extraction.'
))

story.append(h3('Task 7.3: Update Constitutional Documents'))
story.append(body(
    'Update CLAUDE.md, AGENTS.md, and CONSTITUTION.md at all levels (root, core/, feature/, '
    'lava-api-go/) with the new Multi-Tracker SDK architecture. Key additions: (1) Anti-Bluff Testing '
    'Constitution clause requiring that all tests and Challenges guarantee real user-visible functionality, '
    '(2) Tracker Development Guide reference, (3) Module layout updates with new tracker modules, '
    '(4) Build command updates for new modules, (5) Testing requirements for new tracker implementations '
    '(unit + integration + contract + challenge). Reference existing constitutional documents: '
    'CLAUDE.md (root), core/CLAUDE.md, feature/CLAUDE.md, lava-api-go/CLAUDE.md, lava-api-go/AGENTS.md, '
    'lava-api-go/CONSTITUTION.md.'
))

story.append(h3('Task 7.4: API Documentation'))
story.append(body(
    'Update the OpenAPI 3.0.3 specification to include tracker-selection endpoints and multi-tracker '
    'response formats. Add: (1) GET /trackers - list available trackers, (2) POST /trackers/{id}/switch '
    '- switch active tracker, (3) GET /trackers/{id}/mirrors - list mirrors with health status, '
    '(4) PUT /trackers/{id}/mirrors - update mirror configuration. Update existing endpoints to include '
    'X-Tracker-ID response header for tracker identification. Generate updated server and client code '
    'via oapi-codegen. Reference: lava-api-go/api/openapi.yaml (current spec).'
))

# Phase 8
story.append(Spacer(1, 12))
story.append(h2('Phase 8: Anti-Bluff Constitution and Quality Gates'))
story.append(body('Duration: 1 week | Tasks: 3 | Sub-tasks: 8'))

story.append(h3('Task 8.1: Constitutional Anti-Bluff Clause'))
story.append(body(
    'Add a binding constitutional clause to all CLAUDE.md and AGENTS.md files (root, core/, feature/, '
    'lava-api-go/) that enforces the following Anti-Bluff Testing Constitution. The clause mandates '
    'that: (1) Every test must exercise real code paths that users interact with, (2) Mocking is only '
    'allowed for external dependencies (network, database), never for internal business logic, (3) Fake '
    'implementations must be behaviorally equivalent to real ones, (4) Every feature must have an '
    'Integration Challenge Test that verifies end-to-end functionality, (5) Every bug fix must include '
    'a regression test that would have caught the bug, (6) CI green is necessary but not sufficient for '
    'release - Challenge Tests must also pass, (7) Tests that pass while the feature is broken for users '
    'are considered constitutional violations and must be fixed immediately.'
))

story.append(h3('Task 8.2: Quality Gate Automation'))
story.append(body(
    'Create automated quality gates that enforce the Anti-Bluff Constitution: (1) A pre-push hook that '
    'runs all unit tests, parser tests with fixtures, and Challenge Tests, (2) A coverage gate that '
    'rejects commits below 90% line coverage for new code, (3) A mutation test gate that rejects commits '
    'below 85% mutation kill rate, (4) A documentation gate that verifies all new modules have README '
    'and test files, (5) A constitutional gate that verifies all CLAUDE.md/AGENTS.md files are up to '
    'date with the current architecture. Integrate with the existing scripts/ directory CI infrastructure.'
))

story.append(h3('Task 8.3: Bluff Detection and Prevention'))
story.append(body(
    'Implement active bluff detection mechanisms: (1) Periodic smoke tests against live trackers that '
    'verify search results are returned and parseable, (2) HTML fixture freshness checks that warn when '
    'fixtures are older than 30 days, (3) Cross-implementation consistency checks between Kotlin and Go, '
    '(4) User-reported issue correlation with test coverage gaps, (5) A "trust but verify" approach where '
    'even passing tests are periodically audited for real-world relevance.'
))

# ══════════════════════════════════════════════════════════════
# PART V: ANTI-BLUFF CONSTITUTION
# ══════════════════════════════════════════════════════════════
story.append(Spacer(1, 18))
story.append(h1('Part V: Anti-Bluff Testing Constitution'))

story.append(body(
    'The following constitutes the binding Anti-Bluff Testing Constitution for the Lava Multi-Tracker SDK '
    'project. It applies to all code, tests, and documentation in this repository and all submodules. '
    'Violations of this constitution are treated as critical bugs that must be fixed before any other work '
    'proceeds.'
))

story.append(h2('5.1 The Six Laws of Anti-Bluff Testing'))

story.append(h3('Law 1: Real User Behavior Guarantee'))
story.append(body(
    'Every test must guarantee real user-visible behavior. Tests that only verify internal state changes '
    'without asserting on user-facing outputs (UI state, API responses, file downloads) are insufficient. '
    'A passing test suite that does not verify what users see and interact with is a bluff.'
))

story.append(h3('Law 2: No Mocking of Business Logic'))
story.append(body(
    'Mocking is only permitted for external dependencies: network calls, database operations, file system '
    'access, and system services. Internal business logic (UseCases, repositories, services, mappers, '
    'parsers) must never be mocked in tests. ViewModels must use real UseCases. UseCases must use real '
    'repositories or behaviorally equivalent fakes. Parsers must process real HTML, not mock Document objects.'
))

story.append(h3('Law 3: Behavioral Equivalence of Fakes'))
story.append(body(
    'Fake implementations used in tests must be behaviorally equivalent to real implementations. A '
    'FakeBookmarksRepository must support the same operations, enforce the same constraints, and return '
    'data in the same format as the real BookmarksRepository. If the fake and real implementations diverge, '
    'tests using the fake become bluffs. Fakes must have their own tests verifying their behavioral '
    'equivalence to the real implementation.'
))

story.append(h3('Law 4: Integration Challenge Tests'))
story.append(body(
    'Every feature must have at least one Integration Challenge Test that exercises the full stack from '
    'user action to final result. For tracker features, this means: user triggers search, HTTP request '
    'is sent to the tracker, HTML response is received, parsed into model objects, and results are '
    'displayed in the UI. Challenge Tests must use the same surfaces users touch (Compose UI, REST API '
    'endpoints) and verify the same state users see.'
))

story.append(h3('Law 5: Regression Immunity'))
story.append(body(
    'Every bug fix must include a retroactive test that would have caught the bug. The test must fail '
    'against the pre-fix code and pass against the fixed code. This creates an ever-growing regression '
    'suite that prevents the same class of bugs from recurring. Bug fix commits must reference the '
    'regression test in the commit message with the format: "Fix: [description] + Regression: [test name]".'
))

story.append(h3('Law 6: Falsifiability Protocol'))
story.append(body(
    'Tests must be provably falsifiable. A test that always passes (regardless of code changes) is worse '
    'than no test at all because it creates a false sense of security. Each test commit must include a '
    'falsifiability note: "Test: [what it tests] / Mutation: [what was changed to verify it fails] / '
    'Observed: [test failed as expected] / Reverted: [code restored]". This protocol is enforced via '
    'pre-commit hooks for critical test files.'
))

story.append(h2('5.2 Quality Metrics and Gates'))

story.append(make_table(
    ['Metric', 'Minimum', 'Target', 'Gate'],
    [
        ['Line Coverage (core/tracker/*)', '90%', '95%', 'Pre-push'],
        ['Line Coverage (feature/*)', '80%', '90%', 'Pre-push'],
        ['Mutation Kill Rate', '85%', '95%', 'Nightly'],
        ['Challenge Tests per Feature', '1', '2', 'PR review'],
        ['HTML Fixture Freshness', '30 days', '14 days', 'Weekly check'],
        ['Parser Test per Parser', '5 fixtures', '10 fixtures', 'PR review'],
        ['Kotlin-Go Parity Coverage', '90%', '100%', 'CI'],
    ],
    col_widths=[140, 70, 70, AVAIL_W - 285]
))
story.append(Paragraph('Table 6: Quality Metrics and Gates', sCaption))

# ══════════════════════════════════════════════════════════════
# PART VI: USER GUIDES AND MANUALS
# ══════════════════════════════════════════════════════════════
story.append(Spacer(1, 18))
story.append(h1('Part VI: User Guides and Manuals'))

story.append(h2('6.1 Guide: Developing a New Tracker Implementation'))

story.append(body(
    'This guide provides step-by-step instructions for adding a new tracker to the Lava Multi-Tracker SDK. '
    'The process follows a standardized workflow that ensures consistency across all tracker implementations. '
    'Each tracker implementation requires: a module with parsers, a TrackerClient implementation, a '
    'TrackerClientFactory for registry, HTML fixtures for testing, and documentation of site-specific details.'
))

story.append(h3('Step 1: Reconnaissance and Research'))
story.append(body(
    'Before writing any code, perform thorough research on the target tracker: (1) Identify all available '
    'mirror addresses and their accessibility, (2) Document the authentication mechanism (form fields, '
    'CAPTCHA, OAuth), (3) Record the URL patterns for all operations (search, browse, topic, download), '
    '(4) Identify the HTML encoding (UTF-8, Windows-1251, etc.), (5) Extract CSS selectors for all data '
    'fields by inspecting the HTML structure in a browser\'s developer tools, (6) Document any quirks: '
    'variable column counts, JavaScript-rendered content, rate limiting, anti-bot measures. Create a '
    'research document similar to Section 1.5 (RuTor Research Findings) in this plan.'
))

story.append(h3('Step 2: Create the Module'))
story.append(body(
    'Create a new Gradle module :core:tracker:{trackername} following the established pattern. Add the '
    'module to settings.gradle.kts with the lava.kotlin.library convention plugin. Add dependencies on '
    ':core:tracker:api, Jsoup, OkHttp, and kotlinx.serialization. Create the package structure: '
    'lava.tracker.{trackername} for the client, lava.tracker.{trackername}.parser for parser classes, '
    'lava.tracker.{trackername}.model for any tracker-specific model extensions.'
))

story.append(h3('Step 3: Implement the Descriptor'))
story.append(body(
    'Create {Tracker}Descriptor implementing TrackerDescriptor. Define all capabilities the tracker '
    'supports. List all known mirror addresses with priorities. Specify the authentication type and '
    'encoding. This descriptor is the single source of truth for what the tracker supports and is used '
    'by the UI to show/hide features and by the SDK to validate operations.'
))

story.append(h3('Step 4: Implement the HTTP Client'))
story.append(body(
    'Create a dedicated HTTP client for the tracker. Handle encoding specifics (charset transcoding for '
    'non-UTF-8 trackers). Implement cookie management for authenticated sessions. Add rate limiting via '
    'semaphore. Include a circuit breaker for resilience. The HTTP client is an internal implementation '
    'detail not exposed outside the module.'
))

story.append(h3('Step 5: Implement Parsers'))
story.append(body(
    'Create parser classes for each operation the tracker supports. Each parser takes raw HTML (string) '
    'and returns tracker-agnostic model types (TorrentItem, SearchResult, etc.). Use Jsoup CSS selectors '
    'identified in Step 1. Handle edge cases: missing fields, empty results, malformed HTML, special '
    'characters. Each parser must have at least 5 test fixtures covering normal, empty, error, and '
    'edge-case scenarios.'
))

story.append(h3('Step 6: Implement the Client'))
story.append(body(
    'Create {Tracker}Client implementing TrackerClient and the applicable feature interfaces. The client '
    'delegates to the HTTP client (Step 4) and parsers (Step 5). Implement getFeature() to return feature '
    'interface instances. Implement healthCheck() as a lightweight probe to the tracker\'s root page. '
    'Implement Close() to clean up HTTP client resources.'
))

story.append(h3('Step 7: Register and Test'))
story.append(body(
    'Create a {Tracker}ClientFactory and register it in the Hilt DI module. Write unit tests for all '
    'parsers with HTML fixtures. Write integration tests with real tracker endpoints. Write contract tests '
    'for Kotlin-Go parity. Add a Challenge Test for the new tracker. Update the OpenAPI spec if the '
    'Go API server needs to expose new endpoints. Update CLAUDE.md and AGENTS.md with the new module.'
))

story.append(h2('6.2 Guide: Mirror Configuration and Management'))

story.append(body(
    'Mirror configuration is managed through the MirrorManager component. This guide covers how mirrors '
    'are configured, monitored, and maintained. Mirrors are defined per-tracker in a JSON configuration '
    'file bundled with the app. Users can add custom mirrors via the tracker settings UI. Mirror health '
    'is tracked automatically and persisted across app restarts.'
))

story.append(h3('Mirror Configuration Format'))
story.append(code(
    '{<br/>'
    '&nbsp;&nbsp;"trackers": {<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;"rutracker": {<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"mirrors": [<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;{"url": "https://rutracker.org", "isPrimary": true, "priority": 0},<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;{"url": "https://rutracker.net", "priority": 1},<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;{"url": "https://rutracker.cr", "priority": 2}<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;]<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;},<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;"rutor": {<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"mirrors": [<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;{"url": "https://rutor.info", "isPrimary": true, "priority": 0},<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;{"url": "https://rutor.is", "priority": 1},<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;{"url": "http://6tor.org", "priority": 2, "protocol": "HTTP"}<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;]<br/>'
    '&nbsp;&nbsp;&nbsp;&nbsp;}<br/>'
    '&nbsp;&nbsp;}<br/>'
    '}'
))

story.append(h2('6.3 Guide: Testing Strategy'))

story.append(body(
    'This guide describes the complete testing strategy for the Multi-Tracker SDK. Tests are organized in '
    'four layers, each with a specific purpose and coverage target. All tests must comply with the Anti-Bluff '
    'Constitution (Part V of this document). Tests that pass but do not verify real user-visible behavior '
    'are considered harmful and must be removed or fixed.'
))

story.append(h3('Layer 1: Unit Tests'))
story.append(body(
    'Unit tests verify individual components in isolation. They use fakes and mocks only for external '
    'dependencies. Business logic (parsers, mappers, registry, mirror manager) is tested with real '
    'implementations. Target: 95% line coverage for core modules, 90% for feature modules. Tools: JUnit 5, '
    'kotlinx-coroutines-test, Turbine (for Flow testing), Room in-memory database.'
))

story.append(h3('Layer 2: Parser Tests'))
story.append(body(
    'Parser tests verify HTML parsing with real HTML fixtures. Each parser has at least 5 fixtures: '
    '(1) normal page with multiple results, (2) page with single result, (3) empty result page, '
    '(4) page with special characters (Cyrillic, HTML entities), (5) error or malformed page. Fixtures '
    'are stored as .html files in the test resources directory and are dated to track freshness. Fixtures '
    'older than 30 days trigger a freshness warning.'
))

story.append(h3('Layer 3: Integration Tests'))
story.append(body(
    'Integration tests verify full-stack functionality with real network calls. They use the provided '
    'test credentials (nobody85perfect / ironman1985) to authenticate against live tracker instances. '
    'These tests are slow and require network access, so they run in a separate CI profile. Each '
    'integration test verifies: (1) authentication succeeds, (2) search returns results, (3) results '
    'contain valid data (non-empty title, parseable size, valid info hash), (4) topic detail loads, '
    '(5) torrent file downloads successfully.'
))

story.append(h3('Layer 4: Challenge Tests'))
story.append(body(
    'Challenge Tests are the highest-level tests and the ultimate anti-bluff guarantee. They simulate '
    'complete user workflows end-to-end: (1) App starts and shows tracker selection, (2) User selects '
    'a tracker and authenticates, (3) User performs a search and sees results in the UI, (4) User taps '
    'a result and sees the topic detail screen, (5) User downloads a torrent file, (6) User switches '
    'tracker and repeats. Challenge Tests use Compose UI testing, real HTTP calls, and verify that every '
    'UI element displays correct data. If all Challenge Tests pass, the feature is guaranteed to work '
    'for real users.'
))

# ══════════════════════════════════════════════════════════════
# SUMMARY TABLE
# ══════════════════════════════════════════════════════════════
story.append(Spacer(1, 18))
story.append(h1('Implementation Summary'))

story.append(make_table(
    ['Phase', 'Name', 'Duration', 'Tasks', 'Sub-tasks'],
    [
        ['1', 'Foundation - Interfaces', '2 weeks', '7', '32'],
        ['2', 'RuTracker Decoupling', '3 weeks', '8', '41'],
        ['3', 'RuTor Implementation', '3 weeks', '7', '38'],
        ['4', 'Mirror and Fallback', '1.5 weeks', '5', '20'],
        ['5', 'Innovation Integration', '2 weeks', '6', '24'],
        ['6', 'Anti-Bluff Testing', '3 weeks', '7', '18'],
        ['7', 'Documentation', '2 weeks', '4', '10'],
        ['8', 'Constitution & Quality', '1 week', '3', '8'],
        ['Total', '', '17.5 weeks', '47', '191'],
    ],
    col_widths=[50, 160, 70, 50, AVAIL_W - 335]
))
story.append(Paragraph('Table 7: Implementation Plan Summary', sCaption))

story.append(body(
    'The total estimated effort is 17.5 weeks (approximately 4 months) with 47 tasks and 191 sub-tasks. '
    'Phases 1-3 are sequential and form the critical path (8 weeks). Phases 4-5 can be partially '
    'parallelized with Phase 3. Phase 6 (testing) should start during Phase 2 and run concurrently with '
    'all subsequent phases. Phase 7 (documentation) is ongoing. Phase 8 (constitution) should be '
    'completed early and enforced from that point forward.'
))

story.append(Spacer(1, 24))
story.append(body(
    '<b>Credentials for testing:</b> RuTracker and RuTor both use the same credentials for this POC: '
    'username "nobody85perfect", password "ironman1985". These credentials should be stored in the '
    '.env file (reference: .env.example in the project root) and must never be committed to version control.'
))

# ━━━━ BUILD PDF ━━━━
OUTPUT = '/home/z/my-project/download/Lava_Multi_Tracker_SDK_Architecture_Plan.pdf'
doc = TocDocTemplate(OUTPUT, pagesize=A4,
    leftMargin=L_MARGIN, rightMargin=R_MARGIN,
    topMargin=T_MARGIN, bottomMargin=B_MARGIN,
    title='Lava Multi-Tracker SDK Architecture and Implementation Plan',
    author='Z.ai', creator='Z.ai',
    subject='Comprehensive architecture redesign and implementation plan for multi-tracker support'
)
doc.multiBuild(story)
print(f"PDF generated: {OUTPUT}")
