import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch
import numpy as np

plt.rcParams['font.sans-serif'] = ['Noto Sans SC', 'DejaVu Sans']
plt.rcParams['axes.unicode_minus'] = False

# Color scheme
PRIMARY = '#217490'
SECONDARY = '#4c30a0'
ACCENT = '#4ece8e'
BG_COLOR = '#f6f6f6'
HEADER_COLOR = '#716950'
BORDER_COLOR = '#c6c2b5'
TEXT_COLOR = '#21211e'
MUTED = '#7e7b74'
LIGHT_PRIMARY = '#d0e8ef'
LIGHT_SECONDARY = '#ddd0f0'
LIGHT_ACCENT = '#d0f0de'
LIGHT_HEADER = '#e8e4d8'
WHITE = '#ffffff'
RED_SOFT = '#c05050'

fig, ax = plt.subplots(1, 1, figsize=(28, 20))
fig.patch.set_facecolor(BG_COLOR)
ax.set_facecolor(BG_COLOR)
ax.set_xlim(0, 28)
ax.set_ylim(0, 20)
ax.axis('off')

def draw_rounded_box(ax, x, y, w, h, label, facecolor, edgecolor=BORDER_COLOR, fontsize=12, fontcolor=TEXT_COLOR, fontweight='normal', alpha=1.0, linewidth=1.5):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.15", facecolor=facecolor, edgecolor=edgecolor, linewidth=linewidth, alpha=alpha)
    ax.add_patch(box)
    ax.text(x + w/2, y + h/2, label, ha='center', va='center', fontsize=fontsize, fontweight=fontweight, color=fontcolor)
    return box

def draw_arrow(ax, x1, y1, x2, y2, color=MUTED, style='->', linewidth=1.5, label=None, label_offset=(0, 0)):
    ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
                arrowprops=dict(arrowstyle=style, color=color, lw=linewidth, connectionstyle='arc3,rad=0'))
    if label:
        mx = (x1 + x2)/2 + label_offset[0]
        my = (y1 + y2)/2 + label_offset[1]
        ax.text(mx, my, label, ha='center', va='center', fontsize=9, color=color, fontweight='bold',
                bbox=dict(boxstyle='round,pad=0.2', facecolor=BG_COLOR, edgecolor='none', alpha=0.8))

# ========== TITLE ==========
ax.text(14, 19.3, 'Go API Route Structure — Before & After', ha='center', va='center', fontsize=22, fontweight='bold', color=PRIMARY)
ax.plot([2, 26], [18.9, 18.9], color=BORDER_COLOR, linewidth=1.5)

# ========== LEFT: CURRENT ROUTE STRUCTURE ==========
ax.text(7, 18.3, 'Current (RuTracker Only)', ha='center', va='center', fontsize=16, fontweight='bold', color=RED_SOFT)

# Current routes box
cur_x, cur_y = 1.0, 2.0
cur_w, cur_h = 12.0, 15.5
draw_rounded_box(ax, cur_x, cur_y, cur_w, cur_h, '', '#f5e8e8', RED_SOFT, linewidth=2, alpha=0.3)

# Current route header
draw_rounded_box(ax, cur_x + 0.5, cur_y + cur_h - 1.2, cur_w - 1.0, 0.9, 'base: /api/v1/', RED_SOFT, RED_SOFT, fontsize=12, fontweight='bold', fontcolor=WHITE, linewidth=1.5)

current_routes = [
    ('GET', '/forum', 'List forum categories'),
    ('GET', '/forum/{id}', 'Get forum category'),
    ('GET', '/search', 'Search torrents'),
    ('GET', '/topic/{id}', 'Get topic details'),
    ('GET', '/torrent/{id}', 'Get torrent info'),
    ('POST', '/auth/login', 'Login (RuTracker)'),
    ('POST', '/auth/logout', 'Logout'),
    ('GET', '/favorites', 'List favorites'),
    ('POST', '/favorites/{id}', 'Add to favorites'),
    ('GET', '/comments/{id}', 'Get comments'),
    ('GET', '/download/{id}', 'Download torrent'),
]

route_y = cur_y + cur_h - 2.5
route_h = 0.95
method_colors = {'GET': ACCENT, 'POST': SECONDARY, 'PUT': '#c09050', 'DELETE': RED_SOFT}

for i, (method, path, desc) in enumerate(current_routes):
    y = route_y - i * route_h
    mc = method_colors.get(method, MUTED)
    # Method badge
    draw_rounded_box(ax, cur_x + 1.0, y, 1.5, route_h - 0.15, method, mc, mc, fontsize=8, fontcolor=WHITE, fontweight='bold', linewidth=0.8)
    # Path
    ax.text(cur_x + 3.0, y + (route_h - 0.15)/2, path, ha='left', va='center', fontsize=9, fontweight='bold', color=TEXT_COLOR, fontfamily='monospace')
    # Description
    ax.text(cur_x + 8.5, y + (route_h - 0.15)/2, desc, ha='left', va='center', fontsize=8, color=MUTED)

# Problem note
ax.text(7, cur_y + 0.8, '⚠ All routes hardcoded to RuTracker', ha='center', va='center', fontsize=10, color=RED_SOFT, fontweight='bold',
        bbox=dict(boxstyle='round,pad=0.3', facecolor='#fef0f0', edgecolor=RED_SOFT, linewidth=1.0))

# ========== CENTER: TRANSFORMATION ARROW ==========
ax.annotate('', xy=(15.5, 10), xytext=(13.5, 10),
            arrowprops=dict(arrowstyle='->', color=PRIMARY, lw=3.0))
ax.text(14.5, 10.8, 'Refactor', ha='center', va='center', fontsize=13, fontweight='bold', color=PRIMARY)

# ========== RIGHT: PROPOSED ROUTE STRUCTURE ==========
ax.text(21, 18.3, 'Proposed (Multi-Provider)', ha='center', va='center', fontsize=16, fontweight='bold', color=PRIMARY)

# Proposed routes box
prop_x, prop_y = 15.0, 2.0
prop_w, prop_h = 12.5, 15.5
draw_rounded_box(ax, prop_x, prop_y, prop_w, prop_h, '', LIGHT_PRIMARY, PRIMARY, linewidth=2, alpha=0.3)

# Provider dispatch middleware
draw_rounded_box(ax, prop_x + 0.5, prop_y + prop_h - 1.2, prop_w - 1.0, 0.9, 'Provider Dispatch Middleware', PRIMARY, PRIMARY, fontsize=12, fontweight='bold', fontcolor=WHITE, linewidth=2)

# Proposed route header
draw_rounded_box(ax, prop_x + 0.5, prop_y + prop_h - 2.5, prop_w - 1.0, 0.9, 'base: /api/v1/{provider}/', LIGHT_PRIMARY, PRIMARY, fontsize=11, fontweight='bold', fontcolor=PRIMARY, linewidth=1.5)

proposed_routes = [
    ('GET', '/{provider}/forum', 'List forum (per-provider)'),
    ('GET', '/{provider}/forum/{id}', 'Get forum category'),
    ('GET', '/{provider}/search', 'Search (per-provider)'),
    ('GET', '/{provider}/topic/{id}', 'Get topic details'),
    ('GET', '/{provider}/torrent/{id}', 'Get torrent info'),
    ('POST', '/{provider}/auth/login', 'Login (per-provider)'),
    ('POST', '/{provider}/auth/logout', 'Logout'),
    ('GET', '/{provider}/favorites', 'List favorites'),
    ('POST', '/{provider}/favorites/{id}', 'Add to favorites'),
    ('GET', '/{provider}/comments/{id}', 'Get comments'),
    ('GET', '/{provider}/download/{id}', 'Download torrent'),
]

route_y2 = prop_y + prop_h - 3.8

for i, (method, path, desc) in enumerate(proposed_routes):
    y = route_y2 - i * route_h
    mc = method_colors.get(method, MUTED)
    # Method badge
    draw_rounded_box(ax, prop_x + 0.8, y, 1.5, route_h - 0.15, method, mc, mc, fontsize=8, fontcolor=WHITE, fontweight='bold', linewidth=0.8)
    # Path with {provider} highlighted
    path_display = path.replace('{provider}', '{provider}')
    ax.text(prop_x + 2.8, y + (route_h - 0.15)/2, path_display, ha='left', va='center', fontsize=9, fontweight='bold', color=PRIMARY, fontfamily='monospace')
    # Description
    ax.text(prop_x + 9.5, y + (route_h - 0.15)/2, desc, ha='left', va='center', fontsize=8, color=MUTED)

# ========== PROVIDER VALUES ==========
ax.text(21, prop_y + 1.5, 'Provider values: rutracker | rutor | nnmclub | kinozal | archiveorg | gutenberg', ha='center', va='center', fontsize=9, color=PRIMARY, fontweight='bold',
        bbox=dict(boxstyle='round,pad=0.3', facecolor=LIGHT_PRIMARY, edgecolor=PRIMARY, linewidth=0.8))

# ========== MIDDLE: MIDDLEWARE DETAIL ==========
mid_x = 14.5
mid_y = 0.5
draw_rounded_box(ax, mid_x - 6, mid_y, 12, 1.2, '', WHITE, PRIMARY, linewidth=1.5)
ax.text(mid_x, mid_y + 0.8, 'Provider Dispatch Middleware', ha='center', va='center', fontsize=10, fontweight='bold', color=PRIMARY)
ax.text(mid_x, mid_y + 0.35, '1. Extract {provider} from URL  →  2. Resolve Provider implementation  →  3. Inject into Handler  →  4. Handle Request', ha='center', va='center', fontsize=8, color=MUTED)

plt.tight_layout()
plt.savefig('/home/z/my-project/download/diagrams/api_routes_structure.png', dpi=300, bbox_inches='tight', facecolor=BG_COLOR)
plt.close()
print("Diagram 5 saved successfully")
