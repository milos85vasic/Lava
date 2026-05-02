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

fig, ax = plt.subplots(1, 1, figsize=(30, 22))
fig.patch.set_facecolor(BG_COLOR)
ax.set_facecolor(BG_COLOR)
ax.set_xlim(0, 30)
ax.set_ylim(0, 22)
ax.axis('off')

def draw_rounded_box(ax, x, y, w, h, label, facecolor, edgecolor=BORDER_COLOR, fontsize=12, fontcolor=TEXT_COLOR, fontweight='normal', alpha=1.0, linewidth=1.5):
    box = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.15", facecolor=facecolor, edgecolor=edgecolor, linewidth=linewidth, alpha=alpha)
    ax.add_patch(box)
    ax.text(x + w/2, y + h/2, label, ha='center', va='center', fontsize=fontsize, fontweight=fontweight, color=fontcolor)
    return box

def draw_table(ax, x, y, w, title, columns, rows, header_color, pk_cols=None, fk_cols=None):
    """Draw a database table with header and rows."""
    if pk_cols is None:
        pk_cols = []
    if fk_cols is None:
        fk_cols = []
    
    row_h = 0.55
    header_h = 0.65
    total_h = header_h + len(rows) * row_h
    
    # Table background
    table_bg = FancyBboxPatch((x, y - total_h), w, total_h, boxstyle="round,pad=0.1", facecolor=WHITE, edgecolor=header_color, linewidth=2)
    ax.add_patch(table_bg)
    
    # Header
    header_bg = FancyBboxPatch((x, y - header_h), w, header_h, boxstyle="round,pad=0.1", facecolor=header_color, edgecolor=header_color, linewidth=1.5)
    ax.add_patch(header_bg)
    ax.text(x + w/2, y - header_h/2, title, ha='center', va='center', fontsize=12, fontweight='bold', color=WHITE)
    
    # Column headers
    col_x_start = x + 0.2
    col_w = (w - 0.4) / len(columns)
    for i, col in enumerate(columns):
        cx = col_x_start + i * col_w + col_w/2
        ax.text(cx, y - header_h - row_h * 0.5, col, ha='center', va='center', fontsize=8, fontweight='bold', color=header_color)
    
    # Separator line after column headers
    ax.plot([x + 0.1, x + w - 0.1], [y - header_h - row_h, y - header_h - row_h], color=BORDER_COLOR, linewidth=0.8)
    
    # Data rows
    for r, row in enumerate(rows):
        ry = y - header_h - row_h - (r + 0.5) * row_h
        # Alternating row background
        if r % 2 == 0:
            row_bg = FancyBboxPatch((x + 0.05, ry - row_h/2 + 0.02), w - 0.1, row_h - 0.04, boxstyle="round,pad=0.02", facecolor=LIGHT_HEADER, edgecolor='none', alpha=0.4)
            ax.add_patch(row_bg)
        
        for i, val in enumerate(row):
            cx = col_x_start + i * col_w + col_w/2
            # Determine styling
            col_name = columns[i].lower() if i < len(columns) else ''
            is_pk = col_name in pk_cols
            is_fk = col_name in fk_cols
            
            if is_pk:
                ax.text(cx, ry, f'PK: {val}', ha='center', va='center', fontsize=8, fontweight='bold', color=SECONDARY)
            elif is_fk:
                ax.text(cx, ry, f'FK: {val}', ha='center', va='center', fontsize=8, fontweight='bold', color=PRIMARY)
            else:
                ax.text(cx, ry, val, ha='center', va='center', fontsize=8, color=TEXT_COLOR)
    
    return total_h

# ========== TITLE ==========
ax.text(15, 21.3, 'Database Schema Extensions', ha='center', va='center', fontsize=22, fontweight='bold', color=PRIMARY)
ax.plot([2, 28], [20.9, 20.9], color=BORDER_COLOR, linewidth=1.5)

# ========== TABLE 1: provider_credentials ==========
t1_x, t1_y = 1.0, 20.0
t1_w = 7.0
t1_title = 'provider_credentials'
t1_cols = ['Column', 'Type', 'Notes']
t1_rows = [
    ('id', 'UUID', 'PK, auto'),
    ('user_id', 'UUID', 'FK → users'),
    ('provider_id', 'VARCHAR(50)', 'e.g. rutracker'),
    ('cred_type', 'ENUM', 'USERNAME_PWD, TOKEN, API_KEY'),
    ('username_hash', 'VARCHAR(255)', 'Encrypted'),
    ('password_hash', 'VARCHAR(255)', 'Encrypted'),
    ('token', 'TEXT', 'Encrypted'),
    ('created_at', 'TIMESTAMP', 'Auto'),
    ('updated_at', 'TIMESTAMP', 'Auto'),
]
t1_h = draw_table(ax, t1_x, t1_y, t1_w, t1_title, t1_cols, t1_rows, PRIMARY, pk_cols=['id'], fk_cols=['user_id'])

# ========== TABLE 2: provider_configs ==========
t2_x, t2_y = 9.0, 20.0
t2_w = 7.0
t2_title = 'provider_configs'
t2_cols = ['Column', 'Type', 'Notes']
t2_rows = [
    ('id', 'UUID', 'PK, auto'),
    ('user_id', 'UUID', 'FK → users'),
    ('provider_id', 'VARCHAR(50)', 'e.g. rutracker'),
    ('enabled', 'BOOLEAN', 'Default true'),
    ('anonymous_mode', 'BOOLEAN', 'Default false'),
    ('credentials_id', 'UUID', 'FK → provider_credentials'),
    ('display_order', 'INT', 'Sort order'),
]
t2_h = draw_table(ax, t2_x, t2_y, t2_w, t2_title, t2_cols, t2_rows, SECONDARY, pk_cols=['id'], fk_cols=['user_id', 'credentials_id'])

# ========== TABLE 3: search_provider_selections ==========
t3_x, t3_y = 17.5, 20.0
t3_w = 7.0
t3_title = 'search_provider_selections'
t3_cols = ['Column', 'Type', 'Notes']
t3_rows = [
    ('id', 'UUID', 'PK, auto'),
    ('user_id', 'UUID', 'FK → users'),
    ('search_context', 'VARCHAR(50)', 'e.g. "default"'),
    ('provider_id', 'VARCHAR(50)', 'e.g. rutracker'),
    ('selected', 'BOOLEAN', 'True if checked'),
]
t3_h = draw_table(ax, t3_x, t3_y, t3_w, t3_title, t3_cols, t3_rows, ACCENT, pk_cols=['id'], fk_cols=['user_id'])

# ========== TABLE 4: forum_provider_selections ==========
t4_x, t4_y = 25.0, 20.0
t4_w = 4.5
t4_title = 'forum_provider_selections'
t4_cols = ['Column', 'Type']
t4_rows = [
    ('id', 'UUID'),
    ('user_id', 'UUID'),
    ('provider_id', 'VARCHAR(50)'),
    ('enabled', 'BOOLEAN'),
]
t4_h = draw_table(ax, t4_x, t4_y, t4_w, t4_title, t4_cols, t4_rows, HEADER_COLOR, pk_cols=['id'], fk_cols=['user_id'])

# ========== TABLE 5: user_preferences ==========
t5_x, t5_y = 1.0, 10.5
t5_w = 7.0
t5_title = 'user_preferences'
t5_cols = ['Column', 'Type', 'Notes']
t5_rows = [
    ('id', 'UUID', 'PK, auto'),
    ('user_id', 'UUID', 'FK → users'),
    ('key', 'VARCHAR(100)', 'Pref key'),
    ('value', 'TEXT', 'Pref value'),
    ('provider_id', 'VARCHAR(50)', 'Nullable, per-provider'),
]
t5_h = draw_table(ax, t5_x, t5_y, t5_w, t5_title, t5_cols, t5_rows, '#c09050', pk_cols=['id'], fk_cols=['user_id'])

# ========== RELATIONSHIP ARROWS ==========
# provider_credentials.credentials_id ← provider_configs.credentials_id
ax.annotate('', xy=(t1_x + t1_w, t1_y - 4.5), xytext=(t2_x, t2_y - 5.0),
            arrowprops=dict(arrowstyle='->', color=PRIMARY, lw=2.0, connectionstyle='arc3,rad=-0.2'))
ax.text(8.5, 14.5, 'credentials_id\nFK ref', ha='center', va='center', fontsize=8, color=PRIMARY, fontweight='bold',
        bbox=dict(boxstyle='round,pad=0.2', facecolor=LIGHT_PRIMARY, edgecolor=PRIMARY, alpha=0.5))

# All tables reference users
# Draw a central "users" table concept
users_x, users_y = 13, 8.5
users_w = 4.5
draw_table(ax, users_x, users_y, users_w, 'users (existing)', ['Column', 'Type'], 
           [('id', 'UUID'), ('username', 'VARCHAR'), ('email', 'VARCHAR'), ('created_at', 'TIMESTAMP')],
           MUTED, pk_cols=['id'])

# Arrows from users to all tables
user_cx = users_x + users_w/2
user_cy = users_y - 1.5

# To provider_credentials
ax.annotate('', xy=(t1_x + t1_w/2, t1_y - t1_h + 0.5), xytext=(user_cx - 1, user_cy),
            arrowprops=dict(arrowstyle='->', color=MUTED, lw=1.5, connectionstyle='arc3,rad=0.2'))

# To provider_configs
ax.annotate('', xy=(t2_x + t2_w/2, t2_y - t2_h + 0.5), xytext=(user_cx, user_cy),
            arrowprops=dict(arrowstyle='->', color=MUTED, lw=1.5, connectionstyle='arc3,rad=0'))

# To search_provider_selections
ax.annotate('', xy=(t3_x + t3_w/2, t3_y - t3_h + 0.5), xytext=(user_cx + 1, user_cy),
            arrowprops=dict(arrowstyle='->', color=MUTED, lw=1.5, connectionstyle='arc3,rad=-0.2'))

# To user_preferences
ax.annotate('', xy=(t5_x + t5_w/2, t5_y - 0.5), xytext=(users_x, user_cy),
            arrowprops=dict(arrowstyle='->', color=MUTED, lw=1.5, connectionstyle='arc3,rad=0.2'))

# ========== LEGEND ==========
legend_x, legend_y = 17, 6.5
draw_rounded_box(ax, legend_x, legend_y, 11.0, 4.5, '', WHITE, BORDER_COLOR, linewidth=1.5)
ax.text(legend_x + 5.5, legend_y + 4.1, 'Legend', ha='center', va='center', fontsize=13, fontweight='bold', color=TEXT_COLOR)

legend_items = [
    ('PK:', 'Primary Key (PK)', SECONDARY),
    ('FK:', 'Foreign Key (FK)', PRIMARY),
    ('UUID', 'Universally Unique Identifier', MUTED),
    ('VARCHAR(50)', 'Provider identifier string', MUTED),
    ('ENUM', 'Restricted value set', MUTED),
    ('BOOLEAN', 'True/False flag', MUTED),
]

for i, (symbol, desc, col) in enumerate(legend_items):
    ly = legend_y + 3.3 - i * 0.55
    ax.text(legend_x + 0.8, ly, symbol, ha='center', va='center', fontsize=10, color=col, fontweight='bold')
    ax.text(legend_x + 1.8, ly, desc, ha='left', va='center', fontsize=9, color=TEXT_COLOR)

# ========== INDEXES NOTE ==========
note_x, note_y = 1.0, 4.5
draw_rounded_box(ax, note_x, note_y, 12.0, 2.0, '', WHITE, PRIMARY, linewidth=1.5)
ax.text(note_x + 6.0, note_y + 1.5, 'Recommended Indexes', ha='center', va='center', fontsize=12, fontweight='bold', color=PRIMARY)
indexes = [
    'idx_provider_credentials_user_provider (user_id, provider_id)',
    'idx_provider_configs_user_enabled (user_id, enabled)',
    'idx_search_selections_user_context (user_id, search_context)',
]
for i, idx in enumerate(indexes):
    ax.text(note_x + 0.5, note_y + 0.9 - i * 0.4, f'• {idx}', ha='left', va='center', fontsize=8, color=TEXT_COLOR, fontfamily='monospace')

# ========== MIGRATION NOTE ==========
draw_rounded_box(ax, 14, 4.5, 14, 2.0, '', WHITE, ACCENT, linewidth=1.5)
ax.text(21, 6.1, 'Migration Strategy', ha='center', va='center', fontsize=12, fontweight='bold', color='#2a8a5a')
migrations = [
    '• Add new tables with IF NOT EXISTS (backward compatible)',
    '• Existing users auto-migrated with rutracker as default provider',
    '• provider_credentials populated from existing auth table',
]
for i, m in enumerate(migrations):
    ax.text(14.5, 5.5 - i * 0.4, m, ha='left', va='center', fontsize=8, color=TEXT_COLOR)

plt.tight_layout()
plt.savefig('/home/z/my-project/download/diagrams/database_schema.png', dpi=300, bbox_inches='tight', facecolor=BG_COLOR)
plt.close()
print("Diagram 6 saved successfully")
