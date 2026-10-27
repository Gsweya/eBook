//! Colours, type and reader themes, the same tokens as the Android app's `Theme.kt`, turned into a GTK
//! stylesheet. The stylesheet is regenerated whenever the accent, light/dark mode or pure black changes.

use gbook_core::ReaderSettings;

#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Rgb(pub u8, pub u8, pub u8);

impl Rgb {
    pub const fn hex(v: u32) -> Rgb {
        Rgb((v >> 16) as u8, (v >> 8) as u8, v as u8)
    }

    pub fn from_argb(v: i64) -> Rgb {
        Rgb::hex(v as u32 & 0xFFFFFF)
    }

    pub fn css(self) -> String {
        format!("#{:02x}{:02x}{:02x}", self.0, self.1, self.2)
    }

    pub fn alpha(self, a: f32) -> String {
        format!("rgba({},{},{},{a:.3})", self.0, self.1, self.2)
    }

    pub fn lerp(self, o: Rgb, t: f32) -> Rgb {
        let m = |a: u8, b: u8| (a as f32 + (b as f32 - a as f32) * t).round() as u8;
        Rgb(m(self.0, o.0), m(self.1, o.1), m(self.2, o.2))
    }

    pub fn rgba(self, a: f32) -> gtk::gdk::RGBA {
        gtk::gdk::RGBA::new(
            self.0 as f32 / 255.0,
            self.1 as f32 / 255.0,
            self.2 as f32 / 255.0,
            a,
        )
    }
}

pub const WHITE: Rgb = Rgb(255, 255, 255);
pub const BLACK: Rgb = Rgb(0, 0, 0);

/// Accent palettes in the style of Nuvio: each tints the near-black dark surfaces slightly towards its hue.
/// `strong` replaces `accent` on light backgrounds, where the bright accent lacks contrast.
pub struct Palette {
    pub id: &'static str,
    pub name: &'static str,
    pub accent: Rgb,
    pub strong: Rgb,
    pub gradient: &'static [Rgb],
    pub on_accent: Rgb,
    pub bg: Rgb,
    pub elevated: Rgb,
    pub card: Rgb,
}

const fn h(v: u32) -> Rgb {
    Rgb::hex(v)
}

pub const PALETTES: &[Palette] = &[
    Palette {
        id: "amber",
        name: "Amber",
        accent: h(0xFB8C00),
        strong: h(0xE07800),
        gradient: &[h(0xFFB74D), h(0xFB8C00), h(0xEF6C00)],
        on_accent: h(0x111111),
        bg: h(0x0F0D0D),
        elevated: h(0x1E1A1A),
        card: h(0x24201A),
    },
    Palette {
        id: "gold",
        name: "Gold",
        accent: h(0xE8A91C),
        strong: h(0xB07A00),
        gradient: &[
            h(0x8A5700),
            h(0xE8A91C),
            h(0xFFF1A8),
            h(0xFFD45C),
            h(0x9A6200),
        ],
        on_accent: h(0x111111),
        bg: h(0x0F0E0B),
        elevated: h(0x1D1A14),
        card: h(0x262116),
    },
    Palette {
        id: "jade",
        name: "Jade",
        accent: h(0x22D37C),
        strong: h(0x0E9E5A),
        gradient: &[h(0x7BF08D), h(0x22D37C), h(0x0BBF9A)],
        on_accent: h(0x111111),
        bg: h(0x0B0F0D),
        elevated: h(0x141D18),
        card: h(0x16251D),
    },
    Palette {
        id: "rosegold",
        name: "Rose Gold",
        accent: h(0xEC70A9),
        strong: h(0xC94A88),
        gradient: &[h(0xB75AFF), h(0xEC70A9), h(0xFFB37A)],
        on_accent: h(0x111111),
        bg: h(0x100C0F),
        elevated: h(0x1F161D),
        card: h(0x281A24),
    },
    Palette {
        id: "arctic",
        name: "Arctic Blue",
        accent: h(0x3185F5),
        strong: h(0x2A6FD6),
        gradient: &[h(0x4DE3FF), h(0x3185F5), h(0x4D55E8)],
        on_accent: WHITE,
        bg: h(0x0B0E14),
        elevated: h(0x141A24),
        card: h(0x161E2A),
    },
    Palette {
        id: "graphite",
        name: "Graphite",
        accent: h(0xAAB2BE),
        strong: h(0x5B6573),
        gradient: &[h(0xF3F5F7), h(0xAAB2BE), h(0x687381)],
        on_accent: h(0x111111),
        bg: h(0x0C0D0F),
        elevated: h(0x17191D),
        card: h(0x20242A),
    },
    Palette {
        id: "crimson",
        name: "Crimson",
        accent: h(0xE53935),
        strong: h(0xC62828),
        gradient: &[h(0xFF5252), h(0xE53935), h(0xC62828)],
        on_accent: WHITE,
        bg: h(0x0D0D0D),
        elevated: h(0x1A1A1A),
        card: h(0x241A1A),
    },
    Palette {
        id: "ocean",
        name: "Ocean",
        accent: h(0x1E88E5),
        strong: h(0x1565C0),
        gradient: &[h(0x42A5F5), h(0x1E88E5), h(0x1565C0)],
        on_accent: WHITE,
        bg: h(0x0D0D0F),
        elevated: h(0x1A1A1E),
        card: h(0x1A1F24),
    },
    Palette {
        id: "violet",
        name: "Violet",
        accent: h(0xAB47BC),
        strong: h(0x8E24AA),
        gradient: &[h(0xCE93D8), h(0xAB47BC), h(0x6A1B9A)],
        on_accent: WHITE,
        bg: h(0x0D0D0F),
        elevated: h(0x1A1A1E),
        card: h(0x1F1A24),
    },
    Palette {
        id: "emerald",
        name: "Emerald",
        accent: h(0x43A047),
        strong: h(0x2E7D32),
        gradient: &[h(0x66BB6A), h(0x43A047), h(0x2E7D32)],
        on_accent: WHITE,
        bg: h(0x0D0D0D),
        elevated: h(0x1A1A1A),
        card: h(0x1A241A),
    },
    Palette {
        id: "rose",
        name: "Rose",
        accent: h(0xD81B60),
        strong: h(0xC2185B),
        gradient: &[h(0xEC407A), h(0xD81B60), h(0xC2185B)],
        on_accent: WHITE,
        bg: h(0x0D0D0D),
        elevated: h(0x1A1A1A),
        card: h(0x241A1F),
    },
];

pub fn palette(id: &str) -> &'static Palette {
    PALETTES.iter().find(|p| p.id == id).unwrap_or(&PALETTES[0])
}

/// Resolved colour tokens for the current palette and mode.
#[derive(Clone, Copy, Debug)]
pub struct Colors {
    pub bg: Rgb,
    pub surface: Rgb,
    pub fill: Rgb,
    pub text: Rgb,
    pub text2: Rgb,
    pub separator: Rgb,
    pub chrome: Rgb,
    pub accent: Rgb,
    pub on_accent: Rgb,
    pub sheet: Rgb,
    pub destructive: Rgb,
    pub dark: bool,
}

pub fn colors(p: &Palette, dark: bool, amoled: bool) -> Colors {
    if dark {
        Colors {
            bg: if amoled { BLACK } else { p.bg },
            surface: p.elevated,
            fill: p.card,
            text: h(0xF5F7F8),
            text2: h(0x969CA3),
            separator: h(0x252A2A),
            chrome: p.card,
            accent: p.accent,
            on_accent: p.on_accent,
            sheet: p.elevated,
            destructive: h(0xE36A8A),
            dark: true,
        }
    } else {
        Colors {
            bg: h(0xF6F6F7),
            surface: WHITE,
            fill: h(0xECECEF),
            text: h(0x111111),
            text2: h(0x6B7078),
            separator: h(0xE3E4E7),
            chrome: WHITE,
            accent: p.strong,
            on_accent: p.on_accent,
            sheet: h(0xF6F6F7),
            destructive: h(0xD9363E),
            dark: false,
        }
    }
}

pub struct ReaderTheme {
    pub name: &'static str,
    pub page: Rgb,
    pub text: Rgb,
    pub page_dark: Rgb,
    pub text_dark: Rgb,
    pub bold: bool,
}

pub const READER_THEMES: &[ReaderTheme] = &[
    ReaderTheme {
        name: "Original",
        page: WHITE,
        text: BLACK,
        page_dark: BLACK,
        text_dark: WHITE,
        bold: false,
    },
    ReaderTheme {
        name: "Quiet",
        page: h(0x4F4F51),
        text: h(0xB8B8BA),
        page_dark: BLACK,
        text_dark: h(0x8E8E93),
        bold: false,
    },
    ReaderTheme {
        name: "Paper",
        page: h(0xEFEFEF),
        text: h(0x1C1C1E),
        page_dark: h(0x1C1C1E),
        text_dark: h(0xE5E5E7),
        bold: false,
    },
    ReaderTheme {
        name: "Bold",
        page: WHITE,
        text: BLACK,
        page_dark: BLACK,
        text_dark: WHITE,
        bold: true,
    },
    ReaderTheme {
        name: "Calm",
        page: h(0xF3E6CC),
        text: h(0x3B2F1E),
        page_dark: h(0x3F392C),
        text_dark: h(0xE9DEC6),
        bold: false,
    },
    ReaderTheme {
        name: "Focus",
        page: h(0xFDFDF8),
        text: h(0x222222),
        page_dark: h(0x17170F),
        text_dark: h(0xE8E8E0),
        bold: false,
    },
];

pub fn reader_theme(i: i32) -> &'static ReaderTheme {
    &READER_THEMES[(i.max(0) as usize).min(READER_THEMES.len() - 1)]
}

pub const HIGHLIGHT_COLORS: [Rgb; 5] = [
    h(0xFFD60A),
    h(0x32D74B),
    h(0x64B5F6),
    h(0xFF7AB6),
    h(0xBF5AF2),
];
pub const SELECTION: Rgb = h(0xA8C0F5);

/// Bundled reading fonts: SIL Open Font License stand-ins for Apple Books' fonts, named in `like`.
pub struct ReadingFont {
    pub id: &'static str,
    pub name: &'static str,
    pub like: &'static str,
    /// Family name as Pango knows it.
    pub family: &'static str,
}

pub const READING_FONTS: &[ReadingFont] = &[
    ReadingFont {
        id: "literata",
        name: "Literata",
        like: "Original",
        family: "Literata",
    },
    ReadingFont {
        id: "charis",
        name: "Charis",
        like: "Charter",
        family: "Charis SIL",
    },
    ReadingFont {
        id: "sourceserif",
        name: "Source Serif",
        like: "Iowan",
        family: "Source Serif 4",
    },
    ReadingFont {
        id: "newsreader",
        name: "Newsreader",
        like: "New York",
        family: "Newsreader",
    },
    ReadingFont {
        id: "gelasio",
        name: "Gelasio",
        like: "Georgia",
        family: "Gelasio",
    },
    ReadingFont {
        id: "crimson",
        name: "Crimson Pro",
        like: "Athelas",
        family: "Crimson Pro",
    },
    ReadingFont {
        id: "garamond",
        name: "EB Garamond",
        like: "Palatino",
        family: "EB Garamond",
    },
    ReadingFont {
        id: "baskerville",
        name: "Libre Baskerville",
        like: "Baskerville",
        family: "Libre Baskerville",
    },
    ReadingFont {
        id: "inter",
        name: "Inter",
        like: "San Francisco",
        family: "Inter",
    },
    ReadingFont {
        id: "dmsans",
        name: "DM Sans",
        like: "Avenir Next",
        family: "DM Sans",
    },
];

pub fn reading_font(id: &str) -> &'static ReadingFont {
    READING_FONTS
        .iter()
        .find(|f| f.id == id)
        .unwrap_or(&READING_FONTS[0])
}

/// App UI sans, display serif for large titles, and serif for quotes outside the reader.
pub const SANS: &str = "DM Sans";
pub const DISPLAY: &str = "Newsreader";
pub const QUOTE: &str = "Literata";

/// Page and ink colours for the reader in the current mode.
pub fn page_colors(s: &ReaderSettings, dark: bool) -> (Rgb, Rgb) {
    let t = reader_theme(s.theme);
    if dark {
        (t.page_dark, t.text_dark)
    } else {
        (t.page, t.text)
    }
}

/// The full app stylesheet for `c`.
pub fn css(c: &Colors) -> String {
    let d = c.dark;
    let accent_hi = c.accent.lerp(WHITE, 0.16);
    let border = if d {
        WHITE.alpha(0.08)
    } else {
        BLACK.alpha(0.06)
    };
    let edge_top = if d {
        WHITE.alpha(0.20)
    } else {
        BLACK.alpha(0.05)
    };
    let glass_fill = if d {
        WHITE.alpha(0.055)
    } else {
        WHITE.alpha(0.6)
    };
    let glass_rim = if d {
        WHITE.alpha(0.16)
    } else {
        WHITE.alpha(0.9)
    };
    let glass_rim_low = if d {
        WHITE.alpha(0.05)
    } else {
        BLACK.alpha(0.07)
    };
    let shadow = if d {
        "rgba(0,0,0,0.45)"
    } else {
        "rgba(0,0,0,0.12)"
    };
    let nav_fill = if d {
        h(0x1C1C1E).alpha(0.94)
    } else {
        WHITE.alpha(0.94)
    };
    let glow = c.bg.lerp(c.accent, if d { 0.35 } else { 0.45 });
    let glow_a = if d { 0.16 } else { 0.18 };
    format!(
        r#"
@define-color bg {bg};
@define-color surface {surface};
@define-color fill {fill};
@define-color text {text};
@define-color text2 {text2};
@define-color separator {separator};
@define-color chrome {chrome};
@define-color accent {accent};
@define-color accent_hi {accent_hi};
@define-color on_accent {on_accent};
@define-color sheet {sheet};
@define-color destructive {destructive};
@define-color accent_bg {accent_soft};
@define-color theme_selected_bg_color {accent};
@define-color accent_color {accent};
@define-color accent_bg_color {accent};
@define-color accent_fg_color {on_accent};
@define-color window_bg_color {bg};
@define-color window_fg_color {text};
@define-color view_bg_color {surface};
@define-color view_fg_color {text};
@define-color popover_bg_color {sheet};
@define-color popover_fg_color {text};
@define-color card_bg_color {surface};

/* DM Sans everywhere: windows, popovers, menus and tooltips (book text sets its own font). */
* {{ font-family: "{SANS}"; }}
window.gbook {{ background: @bg; color: @text; font-weight: 500; font-size: 15px; }}
/* A rounded window frame, square when maximised, tiled or full screen. */
window.gbook.csd {{ border-radius: 14px; }}
window.gbook.maximized, window.gbook.fullscreen, window.gbook.tiled, window.gbook.tiled-top,
window.gbook.tiled-left, window.gbook.tiled-right, window.gbook.tiled-bottom {{ border-radius: 0; }}

/* Our own window buttons, top left, in the macOS style. */
.wbtns {{ margin: 25px 0 0 20px; }}
button.wbtn {{ min-width: 13px; min-height: 13px; padding: 0; margin: 0; border-radius: 999px; border: none;
  box-shadow: inset 0 0 0 0.5px rgba(0,0,0,0.18); color: rgba(0,0,0,0.6); }}
button.wbtn image {{ opacity: 0; transition: opacity 120ms ease; }}
.wbtns:hover button.wbtn image {{ opacity: 1; }}
button.wbtn.close {{ background: #FF5F57; }}
button.wbtn.min {{ background: #FEBC2E; }}
button.wbtn.max {{ background: #28C840; }}
button.wbtn:active {{ filter: brightness(0.85); }}
window:backdrop button.wbtn {{ background: alpha(@text, 0.16); box-shadow: none; }}
/* Room for them at the start of top bars that run under them. */
window.gbook.narrow .topbar {{ padding-left: 90px; }}
window.gbook.narrow .topbar.aligned {{ padding-left: 62px; }}
.reader .topbar {{ padding-left: 90px; }}

/* The sidebar on wide windows, after Apple Books on the Mac. */
.sidebar {{ background: {sidebar_bg}; box-shadow: inset -1px 0 {separator_soft}; }}
.sidebar entry.side-search {{ min-height: 34px; border-radius: 10px; background: {search_bg}; }}
.side-section {{ font-size: 12px; font-weight: 700; color: @text2; margin: 18px 10px 6px 10px; }}
button.side-item {{ border-radius: 10px; padding: 7px 10px; min-height: 0; background: none; border: none;
  box-shadow: none; color: @text; font-weight: 500; }}
button.side-item image {{ color: @accent; }}
button.side-item:hover {{ background: alpha(@text, 0.05); }}
button.side-item.on {{ background: alpha(@accent, 0.16); font-weight: 600; }}
button.side-item.dim {{ color: @text2; }}
button.side-item .count {{ color: @text2; font-size: 13px; }}
popover, tooltip {{ font-weight: 500; }}
.backdrop-glow {{
  background-image: radial-gradient(circle farthest-side at 85% 8%, {glow1}, transparent 70%),
                    radial-gradient(circle farthest-side at 8% 70%, {glow2}, transparent 70%);
}}
.large-title {{ font-family: "{DISPLAY}"; font-weight: 700; font-size: 34px; }}
.section-title {{ font-family: "{DISPLAY}"; font-weight: 700; font-size: 23px; }}
.serif-title {{ font-family: "{DISPLAY}"; font-weight: 700; }}
.quote {{ font-family: "{QUOTE}"; }}
.text2 {{ color: @text2; }}
.accent {{ color: @accent; }}
.caption {{ font-size: 13px; }}
.small {{ font-size: 12px; }}
.secondary {{ font-size: 15px; color: @text2; }}
.bold {{ font-weight: 700; }}
.semibold {{ font-weight: 600; }}
.eyebrow {{ font-size: 13px; font-weight: 600; letter-spacing: 1.2px; color: @text2; }}
.destructive {{ color: @destructive; }}

/* Top bar that floats over scrolling content: content fades out beneath it. */
.topbar {{ padding: 10px 16px 18px 16px; background-image: linear-gradient(to bottom, @bg 55%, alpha(@bg, 0)); }}
.topbar.clear {{ background: none; }}
.topbar.aligned {{ padding-left: 0; padding-right: 0; }}
windowcontrols button {{ min-width: 30px; min-height: 30px; padding: 0; margin-left: 8px; border-radius: 999px;
  background: @chrome; color: @text; box-shadow: 0 2px 8px {shadow}, inset 0 0 0 1px {border}; }}
windowcontrols button:hover {{ background: mix(@chrome, @text, 0.08); }}

/* Floating controls: a solid tonal fill, soft shadow and hairline edge. */
button.circle {{ min-width: 44px; min-height: 44px; padding: 0; border-radius: 999px; border: none;
  background: @chrome; color: @text; box-shadow: 0 3px 10px {shadow}, inset 0 1px {edge_top}, inset 0 0 0 1px {border};
  transition: transform 150ms ease, background 150ms ease; }}
button.circle:hover {{ background: mix(@chrome, @text, 0.05); }}
button.circle:active {{ transform: scale(0.94); }}
button.circle:disabled {{ color: alpha(@text, 0.35); }}
button.circle.small {{ min-width: 32px; min-height: 32px; }}
button.circle.accent {{ background-image: linear-gradient(135deg, @accent_hi, @accent); color: @on_accent; }}
button.pill {{ min-height: 44px; padding: 0 20px; border-radius: 999px; border: none; background: @chrome; color: @text;
  box-shadow: 0 3px 10px {shadow}, inset 0 0 0 1px {border}; font-weight: 500; }}
button.pill:hover {{ background: mix(@chrome, @text, 0.05); }}
button.primary {{ min-height: 48px; padding: 0 26px; border-radius: 999px; border: none;
  background-image: linear-gradient(135deg, @accent_hi, @accent); color: @on_accent; font-weight: 600; font-size: 16px; box-shadow: none; }}
button.primary:hover {{ background-image: linear-gradient(135deg, mix(@accent_hi, white, 0.08), mix(@accent, white, 0.08)); }}
button.primary:active {{ transform: scale(0.97); }}
button.flat, button.link {{ background: none; border: none; box-shadow: none; padding: 4px 8px; border-radius: 999px; color: @text; min-height: 0; }}
button.flat:hover, button.link:hover {{ background: alpha(@text, 0.06); }}
button.link {{ color: @accent; font-weight: 600; }}
button.chip {{ border-radius: 999px; padding: 6px 14px; min-height: 0; border: none; box-shadow: none;
  background: @fill; color: @text; font-size: 14px; }}
button.chip:checked, button.chip.on {{ background: alpha(@accent, 0.16); color: @accent; font-weight: 600; }}
button.chip.solid:checked, button.chip.solid.on {{ background: @accent; color: @on_accent; }}
button.card-button {{ background: none; border: none; box-shadow: none; padding: 0; border-radius: 22px; }}
button.card-button:hover {{ background: none; }}
button.card-button:hover > .glass, button.card-button:hover > .card {{ background-color: alpha(@text, 0.03); }}
button.plain {{ background: none; border: none; box-shadow: none; padding: 0; min-height: 0; min-width: 0; }}
button.plain:hover {{ opacity: 0.92; }}
button.plain:active {{ transform: scale(0.97); }}

.panel {{ background: @chrome; border-radius: 22px; box-shadow: 0 6px 20px {shadow}, inset 0 1px {edge_top}, inset 0 0 0 1px {border}; }}
.card {{ background: @surface; border-radius: 22px; box-shadow: inset 0 0 0 1px {border}; }}
.glass {{ background-color: {glass_fill}; background-image: linear-gradient(to bottom, {sheen}, transparent 60%);
  border-radius: 24px; box-shadow: inset 0 1px {glass_rim}, inset 0 0 0 1px {glass_rim_low}; }}
.fill {{ background: @fill; }}
.badge {{ background: @fill; color: @text2; border-radius: 4px; padding: 0 4px; font-size: 11px; font-weight: 700; }}
.tag {{ background: alpha(@accent, 0.16); color: @accent; border-radius: 999px; padding: 3px 10px; font-size: 13px; font-weight: 600; }}
.emoji-dot {{ background: @chrome; border-radius: 999px; padding: 2px 4px; box-shadow: 0 1px 4px {shadow}; font-size: 14px; }}

/* Progress bars and the 7-day chart. */
progressbar.gbar > trough {{ background: @fill; border: none; border-radius: 999px; min-height: 4px; min-width: 0; margin: 0; }}
progressbar.gbar > trough > progress {{ background-image: linear-gradient(to right, @accent_hi, @accent); background-color: @accent;
  border: none; border-radius: 999px; min-height: 4px; min-width: 0; margin: 0; }}
progressbar.gbar.thick > trough, progressbar.gbar.thick > trough > progress {{ min-height: 6px; }}
.track {{ background: @fill; border-radius: 999px; min-height: 4px; }}
.track.thick {{ min-height: 6px; }}
.bar {{ background-image: linear-gradient(to right, @accent_hi, @accent); border-radius: 999px; min-height: 4px; }}
.track.thick .bar {{ min-height: 6px; }}
.daybar {{ background: alpha(@accent, 0.45); border-radius: 4px; }}
.daybar.today {{ background-image: linear-gradient(to top, @accent, @accent_hi); }}
.daybar.empty {{ background: @fill; }}

/* Book covers: spine hinge and deep soft shadow. */
.cover {{ border-radius: 2px 5px 5px 2px; box-shadow: 0 8px 18px rgba(0,0,0,{cover_shadow}), 0 2px 4px rgba(0,0,0,0.18); }}
.cover.small {{ box-shadow: 0 3px 8px rgba(0,0,0,{cover_shadow}); }}
.cover .spine {{ background-image: linear-gradient(to right, rgba(0,0,0,0.22), rgba(255,255,255,0.10), transparent); min-width: 6px; }}
.generated-cover label {{ color: white; }}
.generated-cover .title {{ font-family: "{DISPLAY}"; font-weight: 700; font-size: 16px; }}
.generated-cover .author {{ font-size: 12px; color: rgba(255,255,255,0.8); }}
.avatar {{ border-radius: 999px; background-image: linear-gradient(135deg, @accent_hi, @accent); color: @on_accent; font-weight: 700; }}

/* Floating pill navigation (after Nuvio). */
.navbar {{ margin-bottom: 14px; }}
.navpill {{ background: {nav_fill}; border-radius: 999px; padding: 4px;
  box-shadow: 0 8px 26px {shadow}, inset 0 1px {edge_top}, inset 0 0 0 1px {border}; }}
.navpill button {{ min-width: 92px; min-height: 54px; border-radius: 999px; padding: 0 8px; border: none; box-shadow: none;
  background: none; color: @text2; transition: background 220ms ease, color 220ms ease; }}
.navpill button:hover {{ color: @text; }}
.navpill button.active {{ background: alpha(@accent, 0.15); color: @accent; }}
.navpill button label {{ font-size: 12px; font-weight: 500; }}
.navpill button.active label {{ font-weight: 700; }}
.navsearch {{ min-width: 62px; min-height: 62px; border-radius: 999px; background: {nav_fill}; color: @text2; border: none;
  box-shadow: 0 8px 26px {shadow}, inset 0 1px {edge_top}, inset 0 0 0 1px {border}; }}
.navsearch.active {{ background-image: linear-gradient(alpha(@accent, 0.15), alpha(@accent, 0.15)); color: @accent; }}

/* Menus, sheets and popovers. */
popover.menu > contents, popover.sheet > contents {{ background: @sheet; color: @text; border-radius: 24px; padding: 6px;
  box-shadow: 0 12px 36px {shadow}, inset 0 0 0 1px {border}; }}
popover.sheet > contents {{ padding: 18px; }}
popover > arrow {{ background: @sheet; }}
.menu-row {{ padding: 11px 16px; border-radius: 16px; background: none; border: none; box-shadow: none; color: @text; min-height: 0; }}
.menu-row:hover {{ background: alpha(@text, 0.06); }}
.menu-row.on {{ font-weight: 600; }}
.menu-row image {{ color: @text2; }}
.menu-row.on image {{ color: @accent; }}
.toast {{ background: @chrome; border-radius: 999px; padding: 12px 20px; margin-top: 14px;
  box-shadow: 0 6px 20px {shadow}, inset 0 0 0 1px {border}; }}

/* Sheets: a centred card over a dimmed page. */
.scrim {{ background: rgba(0,0,0,{scrim}); }}
.sheet-panel {{ background: @sheet; color: @text; border-radius: 28px; box-shadow: 0 18px 50px rgba(0,0,0,0.35), inset 0 0 0 1px {border}; }}
.sheet-head {{ padding: 18px 18px 6px 24px; }}
.sheet-title {{ font-size: 22px; font-weight: 600; }}

/* Settings rows in grouped cards. */
.group-title {{ font-size: 13px; font-weight: 700; letter-spacing: 1.2px; color: @text2; margin: 20px 6px 8px 6px; }}
.group {{ background: @surface; border-radius: 22px; box-shadow: inset 0 0 0 1px {border}; }}
.row {{ padding: 14px 16px; background: none; border: none; box-shadow: none; border-radius: 0; color: @text; min-height: 0; }}
button.row:hover {{ background: alpha(@text, 0.035); }}
.group > :first-child.row, .group > :first-child .row {{ border-top-left-radius: 22px; border-top-right-radius: 22px; }}
.group > :last-child.row, .group > :last-child .row {{ border-bottom-left-radius: 22px; border-bottom-right-radius: 22px; }}
.row-sep {{ background: @separator; min-height: 1px; margin-left: 66px; }}
.icon-tile {{ background: alpha(@accent, 0.14); color: @accent; border-radius: 12px; min-width: 38px; min-height: 38px; }}
.swatch {{ border-radius: 999px; min-width: 44px; min-height: 44px; padding: 0; border: none; box-shadow: inset 0 0 0 1px {border}; }}
.swatch.on {{ box-shadow: 0 0 0 3px @bg, 0 0 0 5px @text; }}

entry, entry.search {{ background: @fill; color: @text; border-radius: 999px; border: none; box-shadow: none; min-height: 44px; padding: 0 16px; outline: none; }}
entry:focus-within {{ box-shadow: inset 0 0 0 2px alpha(@accent, 0.6); }}
textview.note, textview.note text {{ background: @fill; color: @text; }}
textview.note {{ border-radius: 16px; padding: 12px; }}

scale trough {{ background: @fill; min-height: 6px; border-radius: 999px; }}
scale highlight {{ background-image: linear-gradient(to right, @accent_hi, @accent); border-radius: 999px; min-height: 6px; }}
scale slider {{ background: white; min-width: 34px; min-height: 22px; border-radius: 999px; margin: -9px 0; border: none;
  box-shadow: 0 1px 4px rgba(0,0,0,0.3); }}
switch {{ background: @fill; border-radius: 999px; border: none; }}
switch:checked {{ background: @accent; }}
switch slider {{ background: white; border-radius: 999px; box-shadow: 0 1px 3px rgba(0,0,0,0.3); }}
scrollbar {{ background: none; }}
scrollbar slider {{ background: alpha(@text, 0.25); }}

/* Feed posts. */
.post-frame {{ border-radius: 22px; }}
.slide-dot {{ background: alpha(@text2, 0.4); border-radius: 999px; min-width: 6px; min-height: 6px; }}
.slide-dot.on {{ background: @accent; min-width: 7px; min-height: 7px; }}
button.liked {{ color: #FF3B30; }}
.post-card {{ border-radius: 24px; padding: 26px; }}
.post-card label {{ color: white; }}
.post-card .kind {{ font-size: 13px; font-weight: 700; letter-spacing: 1.2px; color: rgba(255,255,255,0.7); }}
.post-card .body {{ font-family: "{QUOTE}"; font-size: 21px; }}
.post-card .mark {{ font-family: "{DISPLAY}"; font-size: 48px; color: rgba(255,255,255,0.55); }}
.post-card .brand {{ font-family: "{DISPLAY}"; font-weight: 700; color: rgba(255,255,255,0.7); }}
.post-card .meta {{ font-size: 13px; color: rgba(255,255,255,0.7); }}
.post-card .slide-dot {{ background: rgba(255,255,255,0.35); border-radius: 999px; min-width: 6px; min-height: 6px; }}
.post-card .slide-dot.on {{ background: white; }}

/* Reader. */
.reader-chrome {{ font-size: 14px; font-weight: 600; }}
.reader textview, .reader textview text {{ background: transparent; }}
.reader-hud {{ background: @chrome; color: @text; border-radius: 24px; padding: 16px 28px; font-size: 22px; font-weight: 600;
  box-shadow: 0 6px 20px {shadow}; }}
.theme-tile {{ border-radius: 16px; min-width: 96px; min-height: 64px; padding: 0; border: none;
  box-shadow: inset 0 0 0 1px {border}; }}
.theme-tile.on {{ box-shadow: 0 0 0 2px @accent; }}
.hl-swatch {{ min-width: 30px; min-height: 30px; border-radius: 999px; padding: 0; border: none; }}
.hl-swatch.off {{ background: none; box-shadow: inset 0 0 0 2px alpha(@text, 0.35); }}
.tracker-wheel {{ border-radius: 18px; box-shadow: 0 3px 12px rgba(0,0,0,0.16); }}
.hl-swatch.on {{ box-shadow: 0 0 0 3px @sheet, 0 0 0 5px @text; }}
.option-tile {{ background: @surface; border-radius: 18px; padding: 12px 6px; border: none; box-shadow: inset 0 0 0 1px {border}; color: @text; }}
.option-tile image {{ color: @accent; }}
.option-tile:hover {{ background: mix(@surface, @text, 0.04); }}
.font-picker {{ background: @surface; border-radius: 18px; box-shadow: inset 0 0 0 1px {border}; }}
.font-picker > box > title {{ padding: 12px 16px; }}
.font-picker > box > title > expander {{ min-width: 0; min-height: 0; margin: 0; -gtk-icon-size: 0; opacity: 0; }}
.segment {{ background: @surface; border-radius: 999px; box-shadow: inset 0 0 0 1px {border}; }}
.segment button {{ background: none; border: none; box-shadow: none; min-height: 44px; padding: 0 18px; border-radius: 999px; color: @text; }}
.segment button:hover {{ background: alpha(@text, 0.05); }}
.segment button.accent {{ background: alpha(@accent, 0.16); color: @accent; }}
.segment .divider {{ background: alpha(@text, 0.15); min-width: 1px; min-height: 22px; }}
.mode-card {{ background: @surface; border-radius: 18px; padding: 12px 14px; border: none; box-shadow: inset 0 0 0 1px {border}; color: @text; }}
.mode-card.on {{ background: alpha(@accent, 0.14); box-shadow: inset 0 0 0 1.5px @accent; }}
.pdf-page {{ background: white; box-shadow: 0 4px 16px rgba(0,0,0,0.18); }}
"#,
        bg = c.bg.css(),
        surface = c.surface.css(),
        fill = c.fill.css(),
        text = c.text.css(),
        text2 = c.text2.css(),
        separator = c.separator.css(),
        chrome = c.chrome.css(),
        accent = c.accent.css(),
        accent_hi = accent_hi.css(),
        on_accent = c.on_accent.css(),
        sheet = c.sheet.css(),
        destructive = c.destructive.css(),
        accent_soft = c.accent.alpha(0.15),
        glow1 = glow.alpha(glow_a),
        glow2 = glow.alpha(glow_a * 0.6),
        sheen = if d {
            WHITE.alpha(0.07)
        } else {
            WHITE.alpha(0.45)
        },
        cover_shadow = if d { 0.55 } else { 0.30 },
        scrim = if d { 0.55 } else { 0.32 },
        sidebar_bg = if d {
            c.bg.lerp(WHITE, 0.045).css()
        } else {
            c.bg.lerp(BLACK, 0.035).css()
        },
        separator_soft = if d {
            WHITE.alpha(0.06)
        } else {
            BLACK.alpha(0.07)
        },
        search_bg = if d {
            WHITE.alpha(0.07)
        } else {
            BLACK.alpha(0.06)
        },
    ) + &reader_css(d)
        + &extra_css(c)
}

/// Page colours for every reader theme in the current mode, as `.reader.t0` … `.reader.t5`.
fn reader_css(dark: bool) -> String {
    let mut out = String::new();
    for (i, t) in READER_THEMES.iter().enumerate() {
        let (page, ink) = if dark {
            (t.page_dark, t.text_dark)
        } else {
            (t.page, t.text)
        };
        out += &format!(
            r#"
.reader.t{i} {{ background: {p}; }}
.reader.t{i} textview text {{ color: {ink}; }}
.reader.t{i} textview text selection {{ background-color: {sel}; color: {ink}; }}
.reader.t{i} .page-fade-top {{ background-image: linear-gradient(to bottom, {p} 62%, {p0}); }}
.reader.t{i} .page-fade-bottom {{ background-image: linear-gradient(to top, {p} 58%, {p0}); }}
.reader.t{i} .page-mask, .reader.t{i} .page-fade-top.solid {{ background: {p}; }}
.reader.t{i} .faint {{ color: {faint}; }}
.reader.t{i} .ink {{ color: {ink}; }}
.reader.t{i} .progress-line {{ background: {line}; }}
.reader.t{i} .progress-line > .fill {{ background: {faint}; }}
.reader.t{i} button.chapter-next {{ color: {ink}; background: {chip}; }}
"#,
            p = page.css(),
            p0 = page.alpha(0.0),
            ink = ink.css(),
            faint = ink.alpha(0.45),
            line = ink.alpha(0.12),
            chip = ink.alpha(0.07),
            sel = SELECTION.alpha(0.7),
        );
        // Theme tiles in the options show each theme's own page and ink.
        out += &format!(
            ".theme-tile.t{i} {{ background: {}; color: {}; }}\n",
            page.css(),
            ink.css()
        );
    }
    out
}

fn extra_css(c: &Colors) -> String {
    let mut out = String::new();
    for (i, col) in HIGHLIGHT_COLORS.iter().enumerate() {
        out += &format!(
            ".hl-swatch.c{i} {{ background: {}; }}\n.hl-stripe-{i} {{ background: {}; border-radius: 2px; }}\n",
            col.css(),
            col.css()
        );
    }
    let ok = Rgb::hex(0x2FB463);
    let behind = Rgb::hex(0xE58A00);
    out += &format!(
        r#"
.tag.ontrack {{ background: {ok_bg}; color: {ok}; }}
.tag.behind {{ background: {be_bg}; color: {be}; }}
.tag.destructive {{ background: {de_bg}; }}
button.star-on {{ color: #F5B83D; }}
button.star-off {{ color: {star_off}; }}
button.emoji-button {{ font-size: 21px; min-width: 44px; min-height: 44px; padding: 0; border-radius: 999px; border: none; box-shadow: none; background: @fill; }}
button.emoji-button.on {{ background: alpha(@accent, 0.22); }}
button.emoji-button:hover {{ background: mix(@fill, @text, 0.06); }}
button.option-card {{ background: {card}; border-radius: 22px; padding: 16px; border: none; color: @text; box-shadow: inset 0 0 0 1px @separator; min-height: 0; }}
button.option-card:hover {{ background: mix({card}, @text, 0.04); }}
button.option-card.danger .icon-tile {{ background: alpha(@destructive, 0.14); color: @destructive; }}
button.option-card.danger .semibold {{ color: @destructive; }}
button.primary.danger {{ background-image: none; background: @destructive; color: white; }}
"#,
        ok = ok.css(),
        ok_bg = ok.alpha(0.15),
        be = behind.css(),
        be_bg = behind.alpha(0.15),
        de_bg = c.destructive.alpha(0.15),
        star_off = c.text2.alpha(0.6),
        card = if c.dark {
            c.fill.css()
        } else {
            c.surface.css()
        },
    );
    out
}
