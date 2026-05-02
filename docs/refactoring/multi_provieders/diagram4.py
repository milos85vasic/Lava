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

fig, ax = plt.subplots(1, 1, figsize=(26, 22))
fig.patch.set_facecolor(BG_COLOR)
ax.set_facecolor(BG_COLOR)
ax.set_xlim(0, 26)
ax.set_ylim(0, 22)
ax.axis('off')

def draw_rounded_box(ax, x, y, w, h, label, facecolor, edgecolor=BORDER_COLOR, fontsize=12, fontcolor=TEXT_COLOR, fontweight='normal', alpha=1.0, linewidth=1.5):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.15", facecolor=facecolor, edgecolor=edgecolor, linewidth=linewidth, alpha=alpha)
    ax.add_patch(box)
    lines = label.split('\n')
    if len(lines) == 1:
        ax.text(x + w/2, y + h/2, label, ha='center', va='center', fontsize=fontsize, fontweight=fontweight, color=fontcolor)
    else:
        line_h = h / (len(lines) + 0.5)
        for i, line in enumerate(lines):
            ax.text(x + w/2, y + h - (i + 1) * line_h, line, ha='center', va='center', fontsize=fontsize - (1 if i > 0 else 0), fontweight=fontweight if i == 0 else 'normal', color=fontcolor if i == 0 else MUTED)
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
ax.text(13, 21.3, 'Unified Search Flow', ha='center', va='center', fontsize=22, fontweight='bold', color=PRIMARY)
ax.plot([2, 24], [20.9, 20.9], color=BORDER_COLOR, linewidth=1.5)

# ========== STEP 1: USER INPUT ==========
step1_y = 19.5
ax.text(2.5, step1_y, '①', fontsize=18, fontweight='bold', color=PRIMARY)
draw_rounded_box(ax, 3.5, step1_y - 0.8, 8.0, 1.5, 'User Enters\nSearch Query', PRIMARY, PRIMARY, fontsize=14, fontweight='bold', fontcolor=WHITE, linewidth=2)

# Query example
draw_rounded_box(ax, 12.5, step1_y - 0.5, 5.0, 1.0, '"interstellar 2014"', WHITE, PRIMARY, fontsize=11, fontcolor=PRIMARY, linewidth=1.2)
draw_arrow(ax, 11.5, step1_y - 0.05, 12.5, step1_y - 0.05, color=PRIMARY, linewidth=1.5)

# No providers selected warning
draw_rounded_box(ax, 18.5, step1_y - 0.5, 6.5, 1.0, 'No providers selected?\n→ Search button disabled', '#f0e8d8', '#c09050', fontsize=9, fontcolor='#805020', linewidth=1.2)

# ========== STEP 2: PARALLEL DISPATCH ==========
step2_y_top = 17.5
step2_y_bot = 13.0
draw_rounded_box(ax, 0.5, step2_y_bot, 25, step2_y_top - step2_y_bot, '', LIGHT_PRIMARY, PRIMARY, linewidth=2, alpha=0.2)
ax.text(1.2, step2_y_top - 0.4, '② Parallel Dispatch to ALL Selected Providers', fontsize=14, fontweight='bold', color=PRIMARY)

# Search dispatcher
disp_x, disp_y = 9.5, 16.0
disp_w, disp_h = 7.0, 1.2
draw_rounded_box(ax, disp_x, disp_y, disp_w, disp_h, 'Search Dispatcher', PRIMARY, PRIMARY, fontsize=13, fontweight='bold', fontcolor=WHITE, linewidth=2)
draw_arrow(ax, 7.5, step1_y - 0.8, disp_x + disp_w/2, disp_y + disp_h, color=PRIMARY, linewidth=2.0, label='dispatch')

# Provider channels (parallel arrows going down)
providers = ['RuTracker', 'RuTor', 'NNMClub', 'Kinozal', 'ArchiveOrg', 'Gutenberg']
prov_colors = ['#217490', '#4c30a0', '#4ece8e', '#716950', '#c09050', '#8b5e3c']
prov_w = 3.2
prov_h = 1.2
prov_y = 13.5
prov_x_start = 1.5

for i, (prov, col) in enumerate(zip(providers, prov_colors)):
    x = prov_x_start + i * (prov_w + 0.5)
    # Arrow from dispatcher
    mid_x = x + prov_w/2
    draw_arrow(ax, min(max(mid_x, disp_x + 0.5), disp_x + disp_w - 0.5), disp_y, mid_x, prov_y + prov_h, color=col, linewidth=1.5)
    # Provider box
    draw_rounded_box(ax, x, prov_y, prov_w, prov_h, prov, WHITE, col, fontsize=10, fontweight='bold', fontcolor=col, linewidth=1.5)

# Parallel indicator
ax.text(13, 15.2, '⚡ Parallel Execution', ha='center', va='center', fontsize=10, color=ACCENT, fontweight='bold',
        bbox=dict(boxstyle='round,pad=0.3', facecolor=LIGHT_ACCENT, edgecolor=ACCENT, alpha=0.6))

# ========== STEP 3: STREAMING RESULTS ==========
step3_y_top = 12.5
step3_y_bot = 8.5
draw_rounded_box(ax, 0.5, step3_y_bot, 25, step3_y_top - step3_y_bot, '', LIGHT_ACCENT, ACCENT, linewidth=2, alpha=0.2)
ax.text(1.2, step3_y_top - 0.4, '③ Real-Time Result Streaming', fontsize=14, fontweight='bold', color='#2a8a5a')

# Stream arrows (wavy to indicate streaming)
for i, col in enumerate(prov_colors):
    x = prov_x_start + i * (prov_w + 0.5) + prov_w/2
    ax.annotate('', xy=(x, 10.5), xytext=(x, prov_y),
                arrowprops=dict(arrowstyle='->', color=col, lw=1.2, connectionstyle='arc3,rad=0.1', linestyle='dashed'))
    ax.text(x, 11.0, 'stream', ha='center', va='center', fontsize=7, color=col, fontweight='bold')

# Result merge
merge_y = 9.0
merge_x = 8.0
merge_w = 10.0
merge_h = 1.2
draw_rounded_box(ax, merge_x, merge_y, merge_w, merge_h, 'Result Merger & Sorter', '#2a8a5a', '#2a8a5a', fontsize=12, fontweight='bold', fontcolor=WHITE, linewidth=2)

# ========== STEP 4: DISPLAY RESULTS ==========
step4_y_top = 7.8
step4_y_bot = 1.0
draw_rounded_box(ax, 0.5, step4_y_bot, 25, step4_y_top - step4_y_bot, '', LIGHT_SECONDARY, SECONDARY, linewidth=2, alpha=0.2)
ax.text(1.2, step4_y_top - 0.4, '④ Merged Results Display', fontsize=14, fontweight='bold', color=SECONDARY)

# Merged results list
results = [
    ('Interstellar (2014) 4K UHD', 'RuTracker', '#217490', '4.8 GB'),
    ('Interstellar 2014 BluRay', 'NNMClub', '#4ece8e', '12.3 GB'),
    ('Interstellar.2014.1080p', 'RuTor', '#4c30a0', '2.1 GB'),
    ('Interstellar 2014 REMUX', 'Kinozal', '#716950', '38.5 GB'),
    ('Interstellar - Nolan 2014', 'ArchiveOrg', '#c09050', 'PDF'),
    ('Interstellar Script', 'Gutenberg', '#8b5e3c', '0.5 MB'),
]

res_x = 2.0
res_w = 14.0
res_h = 0.75
res_y_start = 6.5

draw_arrow(ax, merge_x + merge_w/2, merge_y, 9, res_y_start + res_h * len(results), color=SECONDARY, linewidth=2.0)

# Header
draw_rounded_box(ax, res_x, res_y_start + len(results) * res_h, res_w, 0.6, 'Title                    Provider Badge      Size', SECONDARY, SECONDARY, fontsize=9, fontweight='bold', fontcolor=WHITE, linewidth=1.0)

for i, (title, prov, col, size) in enumerate(results):
    y = res_y_start + (len(results) - 1 - i) * res_h
    # Result row background
    draw_rounded_box(ax, res_x, y, res_w, res_h, '', WHITE if i % 2 == 0 else LIGHT_HEADER, BORDER_COLOR, linewidth=0.5)
    # Title
    ax.text(res_x + 0.5, y + res_h/2, title, ha='left', va='center', fontsize=9, color=TEXT_COLOR, fontweight='normal')
    # Provider badge
    badge_x = res_x + 8.5
    draw_rounded_box(ax, badge_x, y + 0.1, 2.2, res_h - 0.2, prov, col, col, fontsize=7, fontcolor=WHITE, fontweight='bold', linewidth=0.5)
    # Size
    ax.text(res_x + 12.5, y + res_h/2, size, ha='left', va='center', fontsize=8, color=MUTED)

# Filter by provider
filter_x = 17.5
filter_y = 5.0
filter_w = 7.0
filter_h = 2.5
draw_rounded_box(ax, filter_x, filter_y, filter_w, filter_h, '', WHITE, SECONDARY, linewidth=1.5)
ax.text(filter_x + filter_w/2, filter_y + filter_h - 0.4, 'Filter by Provider', ha='center', va='center', fontsize=12, fontweight='bold', color=SECONDARY)

for i, (prov, col) in enumerate(zip(providers[:6], prov_colors[:6])):
    row = i // 2
    column = i % 2
    bx = filter_x + 0.5 + column * 3.3
    by = filter_y + filter_h - 1.2 - row * 0.8
    draw_rounded_box(ax, bx, by, 2.8, 0.6, prov, col if i < 4 else '#e0ddd5', col, fontsize=8, fontcolor=WHITE if i < 4 else MUTED, fontweight='bold', linewidth=0.8)

# Sort options
sort_y = 2.5
draw_rounded_box(ax, 17.5, sort_y, 7.0, 1.8, '', WHITE, HEADER_COLOR, linewidth=1.2)
ax.text(21, sort_y + 1.4, 'Sort Options', ha='center', va='center', fontsize=11, fontweight='bold', color=HEADER_COLOR)
sorts = ['Relevance', 'Date', 'Size', 'Seeders', 'Provider']
for i, s in enumerate(sorts):
    ax.text(18.2 + i * 1.3, sort_y + 0.5, s, ha='center', va='center', fontsize=8, color=MUTED,
            bbox=dict(boxstyle='round,pad=0.2', facecolor=LIGHT_HEADER, edgecolor=BORDER_COLOR, linewidth=0.5))

# Note about disabled state
ax.text(13, 1.3, '⚠ If no providers selected → search button is disabled (no search possible)', ha='center', va='center', fontsize=11, color='#c09050', fontweight='bold',
        bbox=dict(boxstyle='round,pad=0.4', facecolor='#fef8e8', edgecolor='#c09050', linewidth=1.0))

plt.tight_layout()
plt.savefig('/home/z/my-project/download/diagrams/unified_search_flow.png', dpi=300, bbox_inches='tight', facecolor=BG_COLOR)
plt.close()
print("Diagram 4 saved successfully")
