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

fig, ax = plt.subplots(1, 1, figsize=(26, 20))
fig.patch.set_facecolor(BG_COLOR)
ax.set_facecolor(BG_COLOR)
ax.set_xlim(0, 26)
ax.set_ylim(0, 20)
ax.axis('off')

def draw_rounded_box(ax, x, y, w, h, label, facecolor, edgecolor=BORDER_COLOR, fontsize=12, fontcolor=TEXT_COLOR, fontweight='normal', alpha=1.0, linewidth=1.5, sublabel=None):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.15", facecolor=facecolor, edgecolor=edgecolor, linewidth=linewidth, alpha=alpha)
    ax.add_patch(box)
    if sublabel:
        ax.text(x + w/2, y + h*0.65, label, ha='center', va='center', fontsize=fontsize, fontweight=fontweight, color=fontcolor)
        ax.text(x + w/2, y + h*0.3, sublabel, ha='center', va='center', fontsize=fontsize-2, color=MUTED, fontstyle='italic')
    else:
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
ax.text(13, 19.3, 'Credentials Management Flow', ha='center', va='center', fontsize=22, fontweight='bold', color=PRIMARY)
ax.plot([2, 24], [18.9, 18.9], color=BORDER_COLOR, linewidth=1.5)

# ========== SETTINGS SCREEN FLOW ==========
ax.text(6, 18.3, 'Path 1: Settings → Credentials', ha='center', va='center', fontsize=14, fontweight='bold', color=SECONDARY)

# Settings Screen
draw_rounded_box(ax, 1.5, 16.5, 4.0, 1.3, 'Settings Screen', WHITE, SECONDARY, fontsize=12, fontweight='bold', fontcolor=SECONDARY, linewidth=1.5)

# Credentials Management
draw_rounded_box(ax, 7, 16.5, 4.5, 1.3, 'Credentials\nManagement', LIGHT_SECONDARY, SECONDARY, fontsize=11, fontweight='bold', fontcolor=SECONDARY, linewidth=1.5)
draw_arrow(ax, 5.5, 17.15, 7, 17.15, color=SECONDARY, linewidth=1.5, label='manage')

# CRUD operations
crud_ops = [
    ('Create\nCredential', ACCENT),
    ('Edit\nCredential', PRIMARY),
    ('Delete\nCredential', '#c05050')
]
crud_y = 14.8
crud_w = 2.5
crud_h = 1.1
for i, (op, col) in enumerate(crud_ops):
    x = 6.0 + i * 3.0
    draw_rounded_box(ax, x, crud_y, crud_w, crud_h, op, WHITE, col, fontsize=10, fontweight='bold', fontcolor=col, linewidth=1.2)
    draw_arrow(ax, 9.25, 16.5, x + crud_w/2, crud_y + crud_h, color=col, linewidth=1.0)

# ========== LOGIN SCREEN FLOW ==========
ax.text(20, 18.3, 'Path 2: Login Flow', ha='center', va='center', fontsize=14, fontweight='bold', color=PRIMARY)

# Login Screen
draw_rounded_box(ax, 16, 16.5, 4.0, 1.3, 'Login Screen', WHITE, PRIMARY, fontsize=12, fontweight='bold', fontcolor=PRIMARY, linewidth=1.5)

# Select Provider
draw_rounded_box(ax, 16, 14.8, 4.0, 1.2, 'Select Provider', LIGHT_PRIMARY, PRIMARY, fontsize=11, fontweight='bold', fontcolor=PRIMARY, linewidth=1.2)
draw_arrow(ax, 18, 16.5, 18, 16.0, color=PRIMARY, linewidth=1.5, label='choose')

# Associate Credentials
draw_rounded_box(ax, 16, 13.0, 4.0, 1.2, 'Associate\nCredentials', LIGHT_PRIMARY, PRIMARY, fontsize=11, fontweight='bold', fontcolor=PRIMARY, linewidth=1.2)
draw_arrow(ax, 18, 14.8, 18, 14.2, color=PRIMARY, linewidth=1.5, label='link')

# Anonymous option
draw_rounded_box(ax, 21.5, 13.0, 3.5, 1.2, 'Anonymous\nAccess', '#e0ddd5', BORDER_COLOR, fontsize=10, fontcolor=MUTED, linewidth=1.0)
draw_arrow(ax, 20, 13.6, 21.5, 13.6, color=MUTED, linewidth=1.0, label='or', label_offset=(0, 0.25))

# ========== PROVIDER SETUP FLOW ==========
ax.text(20, 11.5, 'Path 3: Provider Setup', ha='center', va='center', fontsize=14, fontweight='bold', color=ACCENT)

# Provider Setup Screen
draw_rounded_box(ax, 16, 10.0, 4.0, 1.2, 'Provider Setup\nScreen', WHITE, ACCENT, fontsize=11, fontweight='bold', fontcolor='#2a8a5a', linewidth=1.5)

# Create New Credentials button
draw_rounded_box(ax, 21.5, 10.0, 3.5, 1.2, 'Create New\nCredentials', ACCENT, ACCENT, fontsize=10, fontweight='bold', fontcolor=WHITE, linewidth=1.5)
draw_arrow(ax, 20, 10.6, 21.5, 10.6, color=ACCENT, linewidth=1.5, label='new')

# Arrow back to Settings
draw_arrow(ax, 23.25, 10.0, 23.25, 9.2, color=ACCENT, linewidth=1.0)
draw_arrow(ax, 23.25, 9.2, 5.5, 9.2, color=ACCENT, linewidth=1.0, label='returns to')
draw_arrow(ax, 5.5, 9.2, 5.5, 8.5, color=ACCENT, linewidth=1.0)
draw_rounded_box(ax, 3.5, 8.5, 4.0, 1.0, 'Settings', LIGHT_SECONDARY, SECONDARY, fontsize=10, fontcolor=SECONDARY, linewidth=1.0)

# ========== CREDENTIAL TYPES SECTION ==========
ax.text(13, 7.5, 'Credential Types', ha='center', va='center', fontsize=16, fontweight='bold', color=HEADER_COLOR)
ax.plot([3, 23], [7.2, 7.2], color=BORDER_COLOR, linewidth=1.0)

cred_types = [
    ('Username / Password', 'AuthType.USERNAME_PASSWORD\nUsed by: RuTracker, NNMClub, Kinozal', PRIMARY),
    ('Token', 'AuthType.TOKEN\nUsed by: Custom providers', SECONDARY),
    ('API Key', 'AuthType.API_KEY\nUsed by: Archive.org, APIs', ACCENT)
]

ct_w = 6.0
ct_h = 2.0
ct_y = 4.8
ct_x_start = 3.5

for i, (name, desc, col) in enumerate(cred_types):
    x = ct_x_start + i * (ct_w + 0.8)
    # Type box
    draw_rounded_box(ax, x, ct_y, ct_w, ct_h, '', WHITE, col, linewidth=2, alpha=1.0)
    ax.text(x + ct_w/2, ct_y + ct_h*0.72, name, ha='center', va='center', fontsize=13, fontweight='bold', color=col)
    ax.plot([x+0.4, x+ct_w-0.4], [ct_y + ct_h*0.55, ct_y + ct_h*0.55], color=BORDER_COLOR, linewidth=0.8, linestyle='--')
    ax.text(x + ct_w/2, ct_y + ct_h*0.28, desc, ha='center', va='center', fontsize=8, color=MUTED)

# ========== SHARED CREDENTIALS SECTION ==========
ax.text(13, 4.0, 'Shared Credentials Pattern', ha='center', va='center', fontsize=14, fontweight='bold', color=SECONDARY)

# Central credential store
draw_rounded_box(ax, 9.5, 1.5, 5.0, 1.8, 'Credential Store\n(Shared)', SECONDARY, SECONDARY, fontsize=12, fontweight='bold', fontcolor=WHITE, linewidth=2)

# Multiple providers sharing
shared_provs = [
    ('Provider A\n(RuTracker)', 2.5),
    ('Provider B\n(NNMClub)', 9.0),
    ('Provider C\n(Kinozal)', 15.5)
]

sp_y = 1.8
for name, x_off in shared_provs:
    x = x_off
    draw_rounded_box(ax, x, 0.5, 3.0, 1.0, name, WHITE, SECONDARY, fontsize=9, fontcolor=SECONDARY, linewidth=1.0)
    draw_arrow(ax, x + 1.5, 1.5, 9.5 + 2.5, sp_y, color=SECONDARY, linewidth=1.0, style='<->')

# Note
ax.text(22, 1.2, 'Multiple providers can\nshare the same credentials', ha='center', va='center', fontsize=9, color=MUTED, fontstyle='italic',
        bbox=dict(boxstyle='round,pad=0.3', facecolor=LIGHT_SECONDARY, edgecolor=BORDER_COLOR, alpha=0.5))

plt.tight_layout()
plt.savefig('/home/z/my-project/download/diagrams/credentials_flow.png', dpi=300, bbox_inches='tight', facecolor=BG_COLOR)
plt.close()
print("Diagram 3 saved successfully")
