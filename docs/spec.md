# GBook UI/UX Spec (Apple Books iOS 26 "Liquid Glass" look-alike)

Source: screenshots in `refs/apple-books/` ("Apple Books ios Nov 2025 N.png", 1179x2676, @3x iPhone, so 1 dp = 3 px).
COVERAGE NOTE: frames actually viewed (others were not rendered to me): 0, 10, 25, 50, 75, 100, 110, 115, 120, 125, 134-139, 140, 141-149, 150-179 (frame numbers in 126-133 and 154-179 are approximate, +/-1, because images returned in request order), 185, 200, 215, 229. Frames 1-9, 11-24, 26-49, 51-74, 76-99, 101-109, 111-114, 116-119, 121-124, 126-133 returned no image and are NOT covered; 180-184, 186-199, 201-214, 216-228 were not opened. Hex values are eyeballed.
Frame-to-screen map is by observed ranges and is approximate.

## 1. Design tokens

### Colors
| Token | Light | Dark (estimated, not in sampled frames) |
|---|---|---|
| background | #FFFFFF | #000000 |
| backgroundGrouped (settings, sheets body) | #F1F2F7 | #000000 |
| surface (cards, grouped rows) | #FFFFFF | #1C1C1E |
| surfaceFill (search field, segmented, disabled btn) | #E9E9EC | #2C2C2E |
| textPrimary | #000000 | #FFFFFF |
| textSecondary | #8A8A8E | #98989F |
| separator | #E3E3E6 (0.5dp) | #38383A |
| accentDestructive (Reset Theme, Remove) | #E5483F | #FF6961 |
| toggleOn | #65C466 | #30D158 |
| toggleOffTrack | #C9C9CC | #39393D |
| primaryActionDark (Get Started, confirm check) | #1C1C1E / #2F2F31 | #FFFFFF with black glyph |
| appIconOrange (brand accent) | #F28C28 to #E8693A | same |
| star / rating | #000000 filled, stars in reviews #6B6B6B | |
| book detail page | background tinted by cover dominant color (e.g. #8F3D26 for a salmon cover); panels on it are white at 12-20% alpha | |

Reader themes (frame 150): Original = white page #FFFFFF, black text, selected ring 2.5dp black. Quiet = #4F4F51 with light-grey text #B8B8BA. Paper = #F3F3F3, serif. Bold = white, bold sans. Calm = #F3E6CC with dark-brown text #3B2F1E. Focus = #FDFDF8 with #222. Page text color on Original is #000; running header/page number #8E8E93.

### Corner radii
- Pills and circular buttons: fully rounded (radius = height/2).
- Cards / grouped lists: 22dp. Theme tiles: 20dp. Review cards: 18dp.
- Bottom sheets: 38dp top corners (device-like large radius). Alert dialog: 34dp.
- Book cover: 3dp right corners, 1-2dp left (spine hinge).
- Context bubbles / tooltips: 24dp plus small tail.

### Spacing
Screen gutter 20dp (frames show about 24dp at 3x display, use 20-24). Section title to content 12dp. Section gap 28-32dp. Row inner padding 16dp. Cover carousel gap 12dp. Glass button edge inset 12dp from screen edge, status bar to top buttons 12dp.

### Typography
- Section/large titles: New York-style serif, bold. Use a serif such as "Source Serif 4" / "Noto Serif" Bold. Large title 34sp (Home, Want to Read), section headers 24sp, sheet nav titles 17sp serif bold ("Notifications", "Customer Reviews").
- Body/UI: SF-like sans (use Inter or system Roboto) 17sp regular; secondary 15sp #8A8A8E; captions 13sp; small-caps eyebrow label ("APPLE BOOKS CLASSICS") 13sp, 600, letterSpacing 1.5sp, uppercase.
- Group labels in settings: 13sp uppercase #8A8A8E ("CHARACTER SPACING").
- Reader body: serif (Georgia-like / "Charis SIL" / Literata) 19-20sp, line height 1.45, first-line indent 1.5em, paragraph gap about 0.8em, hyphenation on, justify toggle. Chapter title 32sp serif bold, centered, with ornamental divider under it.
- Reader running header (book title) 15sp semibold #8E8E93 centered at top; page number 15sp #8E8E93 at bottom center.

### Glass (blur / translucency)
Android has no native backdrop blur on all devices. Use `Modifier.blur` on a duplicated backdrop layer (API 31+, e.g. Haze library `hazeChild`) with:
- Glass-light: blur radius 24dp, fill white at 70-78% alpha, saturation +1.4, 0.5dp inner border white 60% alpha plus outer 0.5dp #00000010.
- Glass-dark: fill #1C1C1E at 60% alpha, border white 12%.
- Over colored backgrounds (book detail) fill = tint color at 25% alpha plus white 12%.
- Fallback below API 31: solid #F7F7F8 at 94% (light) or #1C1C1ECC.
Shadows: glass buttons 0 4dp 16dp rgba(0,0,0,0.08); book covers 0 8dp 16dp rgba(0,0,0,0.25) with softer 0 24dp 40dp 0.10 beneath; sheets 0 -2dp 24dp 0.10.

### Icons
Outlined, 1.75-2dp stroke, SF Symbols weight ~medium, 22-24dp inside 44-48dp circle. Material equivalents: close = `Icons.Outlined.Close`; back chevron = `ArrowBackIosNew`; check = `Check`; more = `MoreHoriz`; plus = `Add`; search = `Search`; home = filled `Home`; library = `LocalLibrary` / `Books`; store = `ShoppingBag`; share = `IosShare`/`Share`; trash = `DeleteOutline`; list/TOC = `FormatListBulleted`; line spacing = `FormatLineSpacing`; brightness = `LightMode` (small/large); theme appearance = `Contrast`; page curl = `StickyNote2`/custom; font "Aa" = `TextFields`; undo/jump back = `Replay`; download = `CloudDownload`; thumbs up = `ThumbUpOffAlt`; star = `Star`/`StarBorder`/`StarHalf`. Primary tab icons are filled when selected, outlined when not.

## 2. Component catalog

1. **Circular glass icon button**: 48dp circle (frames show about 33dp rendered at 881w display; 48-50dp real), glass-light fill, no hard border, soft shadow. Icon 24dp. States: pressed scale 0.94 and fill alpha +10%; disabled icon alpha 0.35. Variants: confirm = filled dark circle #2F2F31 with white check (frames 50, 125), destructive icon (trash) grey.
2. **Glass pill button**: height 48dp, horizontal padding 20dp, radius full, glass fill, label 17sp 500. Example "Select All" (frame 50), "Add to..." with leading icon (disabled grey text when none selected).
3. **Grouped pill (two actions)**: one 48dp-tall capsule holding "+" and "..." with 0.5dp divider or just spacing (frame 25, over tinted cover).
4. **Primary full-width CTA**: height 56dp, radius full, black #1C1C1E, white 18sp 600 ("Get Started", frame 0). On the detail page: white pill, black text, leading icon ("Download", "Read").
5. **Floating top bar**: no bar background; circular/pill glass controls float over content at top, 12dp below the status bar. Title is large inline text below it (large-title pattern) not centered.
6. **Floating bottom bar (tab bar)**: capsule floating 16dp above the gesture area, width screen minus 48dp, height 68dp, glass-light, items Home / Library / Book Store with 24dp icon plus 12sp label; selected item has a darker inner capsule #E9E9EC at 70% with label in black (frame 125). A separate 64dp circular glass search button sits at the right, same height (frames 10, 25, 125). While searching, the bar morphs to: [home circle] [search field pill with clear x] [close circle] (frames 100, 125).
7. **Segmented / stepper control**: capsule 52dp tall, fill #E9E9EC, two halves separated by a 1dp divider; A (small) and A (large) glyph at 18sp and 26sp (frame 150). Beside it a second capsule with page-theme and appearance icons.
8. **Slider**: track 6dp, active #1C1C1E, inactive #E3E3E6; thumb is a glass capsule 56x40dp with lens-like refraction highlight (frame 150) or a white capsule with shadow (frame 175); leading/trailing icons at ends (small sun, large sun). Value label right 17sp #8A8A8E ("3%").
9. **Toggle**: iOS switch 51x31dp, on = #65C466 with white knob having a green ring, off = #C9C9CC. Use Material3 `Switch` with custom colors.
10. **Theme tile**: 3-col grid, tile 96dp tall, radius 20dp, "Aa" 36sp serif plus name 17sp; selected = 2.5dp black border. Each tile uses its theme page color.
11. **Bottom sheet**: top radius 38dp, drag handle none visible; title row with close circle at the right (frame 150) or both close (left) and confirm (right) glass circles for editing sheets (frames 110, 175). Detents: medium (about 45% height, reader settings) and large. Sheet body is #F1F2F7 with white 22dp rounded groups.
12. **Alert / confirm dialog**: 34dp radius glass card, width screen minus 80dp, title 17sp 600, body 15sp #8A8A8E, pill buttons 52dp tall with fill #00000010 (frame 110: Cancel / OK side by side; frame 115: single destructive red text button).
13. **Popover / tooltip bubble**: 24dp radius glass, tail pointing to the anchor, close x top-right, "Aa" icon left (frame 120).
14. **Toast / HUD**: 280dp square glass rounded card, 40dp radius, with 80dp icon, serif title and body ("Suggest More", frame 125).
15. **Cover card**: width about 160-170dp (about 40% of screen width with a peek of the next card), aspect 1:1.5, shadow described above. Under it, a row: GET pill (outlined 1.5dp black, 12sp 700, radius full) at left, "..." at right, or progress "16%" text. Horizontal carousel with 20dp start padding and 12dp gaps. Shelf variant: 64dp list thumbnail plus title 17sp 600, author 16sp, "Book . 1%" 14sp grey, "..." trailing, inside a 22dp-radius card (frames 10, 125). The "Continue" card uses the cover's dominant color as a translucent gradient fill.
16. **List row with rating**: cover 94x140dp, serif title 20sp 600, author 17sp, 5 stars (13-16dp) plus count in grey, drag handle (three bars) at right, selection circle (28dp, 1.5dp #C9C9CC ring) at left in edit mode (frame 50).
17. **Review card**: radius 18dp, fill #F3F3F6, padding 16dp, title 17sp 600, body 16sp, meta row stars plus "date, user" 14sp grey.
18. **Search**: Trending list with 22dp magnifier icons and 17sp rows separated by hairlines (frame 100); field is glass pill 56dp with placeholder "Apple Books" (GBook: "Search library"); keyboard shown.
19. **Grouped settings list**: white cards radius 22dp, row min height 56dp, title 17sp, subtitle 15sp #8A8A8E, toggle right, hairline inset 20dp (frame 215).
20. **Rating stars input**: 5 black stars 44dp, caption "Tap to change rating." (frame 110/115).

## 3. Screens

### 3.1 Onboarding (frame 0)
White sheet over grey status area (top radius 40dp). App icon 90dp (orange squircle), bold 34sp title in black plus subtitle same size in #8A8A8E, small legal text 14sp, black "Get Started" CTA at bottom. GBook: replace icon, keep layout.

### 3.2 Home (frames about 6-20; viewed 10, 125, 135? no - 10 and 125)
- Large serif title "Home" at top-left, 34sp; at right a 56dp glass "goal ring" circle (blue ring, "0/1" daily goal) and a 56dp black account avatar button.
- Sections stacked, each: serif 24sp heading with chevron ">" (tap goes to the full list), optional grey subtitle, then horizontal cover carousel.
- "Continue": a single wide card 270dp x 90dp, radius 22dp, gradient from cover dominant color, white text.
- "Want to Read", "All-Time Bestsellers", "Previous", "More to Explore", "Reading Goals" (centered title). Sections alternate between a very light grey gradient background (#F5F5F7 to #FFFFFF) and white.
- Floating bottom tab capsule plus search circle always visible. Content scrolls under them with glass blur.

### 3.3 Collections: Want to Read (frame 50; likely 40-60)
Back circle, "Select All" pill, confirm check; large serif title; vertical list rows with drag handles, selection circles; floating actions at bottom: trash circle (left), "Add to..." pill (center, disabled until selection), share circle (right). Rows animate during drag reorder with raised card (white, shadow).

### 3.4 Book detail (frames 25, 125, 20-35)
- Full-bleed background = cover dominant color darkened (#8F3D26); cover centered 230dp wide, large shadow.
- Top: close circle (left) and "+ ..." capsule (right) tinted with the cover color at 40% alpha.
- Eyebrow label (series/collection, underlined, chevron), serif 28sp white bold title, author 20sp with chevron, rating line "3.5 (1.2K) . Genre".
- Info card (radius 24dp, white at 12% alpha): "Book (i)", "January 1813 . 490 Pages", then white full-width pill: "Download" (cloud icon), becomes "Read" after download or "Sample".
- Below: white section on #FFFFFF: "From the Publisher" serif 22sp, text clamp with "More", "Customer Reviews" (frame 75: 3.5 big serif number, histogram bars, Sort control, review cards).
- "+" menu includes Suggest More (thumbs up HUD, frame 125).
- Write a review sheet (frames 110-115): book thumb 76dp, 5 stars, title field, body field, Remove (red) with confirm dialog; nickname prompt dialog.

### 3.5 Search (frame 100 and 100-110 range)
Bottom field replaces tab bar; keyboard above; Trending list when empty; in-book search results are excerpt rows, see 3.8. Field row: glass pill with magnifier, text, clear (x in grey circle), plus close circle.

### 3.6 Reader (frames about 120-200; viewed 120, 135, 140, 150, 160, 175, 185)
**Chrome hidden (default)**: full white page, only a running header (book title, 15sp semibold grey, centered at y=about 68dp from top) and page number (bottom center 15sp). Status bar visible with 9:41 style system clock. Text margins 40dp left/right at 3% default margin; text block starts under the header with 24dp gap. Chapter openings: "Chapter N" 32sp serif bold centered, ornament divider, first paragraph without indent differences as in print (indent 1.5em).
**Chrome shown** (frame 160, 175): tap center. Top: header text replaced by status text, e.g. "3 pages left in chapter" in a glass pill or "Line Guide: On"; circular glass close (x) top-right 52dp; left chip with undo arrow and the jump-back page number ("22", pill 44dp tall, grey fill) after a navigation jump. Bottom: left glass circle (line spacing/menu) and right glass circle (TOC / list icon: three bars with dots), 52dp; center page label "1 of 622" or "1 of 483" glass chip, 15sp #8E8E93, replaces page number. Chrome fades in 200ms.
**Line Guide** (frame 160): dims all text to 25% opacity except the current line band, shown as a white rounded rect (radius 10dp, shadow); draggable vertically.
**Tap zones**: left 25% = previous page, right 25% (or 75% to 100%) = next page, middle 50% = toggle chrome. Edge-swipe from left edge navigates back in the app (do not conflict; reader uses horizontal drag for page turn).
**Page-turn**: default "Curl" (frame 125/135: page peels from the right edge, diagonal fold, back of the page shows mirrored faded text, soft shadow gradient 0 to 30% black along the fold). Options in settings: Slide, Scroll (vertical continuous), None. Implement curl via custom Canvas bitmap warp or use Slide as v1 fallback.
**Progress scrubber**: see 3.6a: a tall vertical glass scrubber on the right edge shows a floating "Chapter 20 / Page 143" bubble.
**Chapter nav**: via TOC; or swipe past chapter end. "Jump back" chip lets user return after TOC or scrubber jumps.

### 3.6a Reader details verified from frames 134-179
- Header strip (y about 150dp): status text "N pages left in chapter" 17sp #8E8E93 centered (153, 160); in chrome-on mode it sits in a glass capsule 44dp tall (149); "Sample" (book is a sample) or book title otherwise (146, 154); "Today's reading goal achieved" with a blue #3B9BE0 check circle replaces it at goal time (136).
- Jump-back chip: black undo-arrow circle 28dp + page number (e.g. "22", "191") at top-left; when chrome is shown it is a grey pill #EEEEF0 (149, 153).
- Close: 52dp glass circle with x at top-right whenever chrome is shown (144, 153, 156).
- Footer: "n of N" (e.g. "1 of 622", "150 of 483") 20sp #8E8E93 centered; in chrome-on it becomes a pill #D6D6D8 (149, 152). Menu button bottom-right: 52dp glass circle with list-with-dots icon (144, 153, 156). Without chrome, only a plain number ("1", "2", "53") is shown (136, 157, 158).
- Scrubber: pressing the menu button area and dragging shows a tall vertical glass capsule at the right edge (about 56x200dp, #3A3A3C filled portion = progress, light track above) and a glass bubble in the middle of the screen "Chapter 20 / Page 143" (title 24sp semibold, page 22sp grey, radius full, width 70%) while dragging (151, 152).
- Page turn options in a popover from the page-icon button: Slide, Curl (check), Fast Fade, Scroll with 24dp icons, 20sp labels, popover 280dp wide, radius 34dp, glass (148). Scroll mode shows continuous text with the jump chip at top-left (149).
- Curl (134, 157): fold runs diagonally from top-left toward bottom-right with a long soft white-to-grey shadow, back of page mirrored and faded 40%. Dark version is the same with white mirrored text.
- Themes sheet (146, 147, 154-155, 161-163): half sheet starting at y about 40% of screen, 38dp top radius, fill #F7F7F7 (dark #343436). Background page dims to #CFCFCF scrim (about 20% black). Title "Themes & Settings" 28sp semibold; close glass circle 56dp. Row 1: A/A stepper capsule 56dp tall x 262dp wide #E4E4E6, glyphs 20sp and 28sp, divider; second capsule 126dp wide with page-turn icon and appearance icon (half-filled circle; moon-with-stars in dark mode, 155). Row 2: brightness slider, track 6dp #1C1C1E/#DDDDDD, thumb glass pill 40x28dp white with soft shadow, sun icons 22dp (small dotted, large dotted); dotted step indicator of 14 dots appears while the A buttons are being used (147). Grid: tiles 125dp wide x 105dp tall, radius 22dp, gap 10dp; Original #FFFFFF, Quiet #4B4B4D with grey text, Paper #EEEEEF (reading bg #EFEFEF, 162), Bold #FFFFFF with bold label, Calm #EDE3CC, Focus #FDFDF7; selected = 3dp black ring (white in dark mode). Customise: full-width pill 56dp #E4E4E6 with gear icon + label 20sp semibold.
- Appearance menu (154): Light (sunrise icon), Dark (moon-stars), Match Device (half circle, check), Match Surroundings (sun+half circle); popover 280dp, radius 34dp.
- Dark mode (155-159): page #000000, text #FFFFFF, chrome text #8E8E93, sheet #343436, capsules #4B4B4D, tiles Original #000, Quiet #000, Paper #1C1C1E, Bold #000, Calm #3F392C, Focus #17170F, selected ring white; menu row fills #3A3A3C-#6B6B6D; glass circles #1C1C1E at 80% with 0.5dp #3A3A3C border; close circle dark grey #1C1C1E.
- Customise Theme sheet (164-179): top y about 6% below status bar, radius 38dp, fixed header with x glass circle (left, 52dp) and dark confirm check circle #3A3A3C (right), title 22sp semibold centered; fixed preview area ("Aa" 44sp and 3-line sample, 22sp) above a scrolling body #F1F1F6. Section titles "Text" / "Accessibility & Layout Options" 26sp semibold. Grouped white cards radius 28dp: Font row (Aa icon, "Original >") expands inline (165) to a list: Original (check), Athelas, Avenir Next, Canela, Charter, Georgia, Iowan, ... each row 64dp in its own typeface; Bold Text switch (green #65C466); "Customise" master toggle reveals LINE SPACING (slider default 1.00, range seen 1.00-1.35, value right 22sp #8A8A8E), CHARACTER SPACING (0% to 3%), WORD SPACING, MARGINS (0% to 3%), each with 28dp icon, 13sp uppercase label (#8A8A8E, 20sp actually), slider with thumb pill 56x40 (glass lens refraction while dragging, 172); then separate card "Justify Text" switch; "Reset Theme" pill 58dp, text grey when disabled, red #E5483F when changed (176). Reset confirmation: centered glass alert 300dp wide, radius 34dp, text "Reset theme options", two 52dp pills Cancel (black) and Reset (red) (179). A small "*" appears on the edited theme tile (177).
- Large text sizes: reader body 20sp at default, line height about 1.4; bold theme/bold-text raises weight to 700; justified text with hyphenation (174-175).
- Book card in reader (136): "Pride and Prejudice / Jane Austen" glass pill card 340x70dp at the end of the sample with GET black pill button 56dp.
- Chapter heading: "Chapter 1" serif bold 40sp centered, flourish divider (140dp wide line with ornament), first paragraph indent 1.5em.

### 3.7 Themes & Settings sheet (frame 150; customise frames 175, 180-185)
Half-height sheet (about 55% of screen) over the live page, radius 38dp, glass background #FAFAFA at 92%. Header "Themes & Settings" 28sp semibold sans left, close glass circle 52dp right.
Row 1: stepper capsule [A small | A large], plus capsule [page-turn/scroll icon | appearance (half-filled circle)].
Row 2: brightness slider with small and large sun icons.
Grid 3x2 of theme tiles: Original, Quiet, Paper, Bold, Calm, Focus. Bottom: "Customise" gray pill 56dp with gear-A icon (also font choice lives inside Customise).
Customise Theme sheet (frame 175): header X and confirm check circles with center title; preview "Aa" plus live paragraph sample (bold serif in preview, fades at bottom); sections with uppercase labels: font, CHARACTER SPACING, WORD SPACING, MARGINS with sliders and 3% values; Justify Text toggle (on); red centered "Reset Theme" pill 56dp white. Line spacing slider sits in the upper part (hidden behind scroll in the frame).

### 3.8 Reader menu, Contents, Search (frames 137, 138, 139, 141, 142-145, 159)
Verified. Tapping the bottom-right glass circle (list-with-dots icon, 52dp) opens a floating glass menu anchored above it (not a sheet), width about 70% of the screen, right aligned, 16dp from the edge:
- Row 1 "Contents - 0%" (live progress percent, e.g. 78% in 141): dark capsule #3A3A3C with white text when it is the first/active item, height 60dp, fully round, 20sp; trailing icon `FormatListBulleted`. In 141 the active row shows a half-dark fill (dark section on the right where the icon is).
- Row 2 "Search Book" with `Search` icon; row 3 "Themes & Settings" with "AA" glyph. Rows 56-60dp tall, 10dp gap, fill #E9E9EC at 90%, text 20sp black.
- Row 4: four glass circles 72x56dp pills side by side: share (`IosShare`), lock/rotation (`ScreenLockRotation`), line guide (`FormatAlignJustify`/custom stacked lines), bookmark (`BookmarkBorder`; filled when page bookmarked).
- Page behind blurs and fades to white under the menu (white gradient from y about 55% down). Dark theme: rows are #3A3A3C-#6B6B6D translucent (159).
**Contents** (138, 139): large sheet, 38dp top corners, body #F7F7F7. Header: cover thumbnail 64x96dp with shadow, title 24sp semibold sans "Pride and Prejudice", subtitle "Page 1 of 622" 20sp grey (number in black), glass close circle 56dp at top-right. List: rows 78dp tall, title 22sp semibold left ("Chapter 1"), page number 20sp #8A8A8E right, 0.5dp hairline inset 20dp. Current chapter row is highlighted with a #E0E0E2 rounded rect (radius 22dp, full width minus 8dp). There is no Bookmarks/Notes segmented control visible in these frames; I did not find a highlights/notes list in frames viewed (see 3.9).
**Search Book** (142-145): sheet with centered title "Search Book" 22sp semibold, no close button on the top; bottom has a glass search field pill (56dp, placeholder "In this book", magnifier left) plus a glass close circle (x, 56dp) at right, above keyboard. Empty state: "Recent Searches" serif bold 28sp with "Clear" text button at right and rows with grey magnifier + term (e.g. "Pride") 20sp (145). Results (143): rows with chapter name 20sp semibold left, page number grey right, then 3-line serif excerpt 20sp with the match in bold; rows separated by hairline, 24dp inset; field shows clear (x in grey circle) button. Tapping a result closes the sheet and the reader jumps to the page with the match highlighted in light blue #A8C0F5 (radius 6dp rectangle) (144) and the jump-back chip ("1" with undo arrow) at the top-left.

### 3.9 Text selection and highlights (frame 144; menu itself not in viewed frames)
Observed: a selected/found word is drawn as a #A8C0F5 (about 70% alpha blue) rounded rectangle behind the word. I did not find a frame showing the selection callout menu, highlight color picker or notes list among those viewed; frames 1-133 and 180-229 are only partly covered (see coverage note), so use this as the fallback design: a floating glass capsule above the selection with Copy, Highlight, Note, Look Up, Share; Highlight opens a row of 5 colors + underline + trash (yellow #FFD60A, green #32D74B, blue #64B5F6 , pink #FF7AB6, purple #BF5AF2).

### 3.10 Review / ratings, Notifications, Settings (frames 75, 110-115, 215)
See component 17, 19, 20. Notifications is a pushed sheet with back and close glass circles flanking a centered serif title and two grouped cards of toggles with section captions.

### 3.11 PDF handling
No PDF frame found in the viewed set. Use same chrome with vertical continuous scroll, page-number chip, no theme panel except brightness.

## 4. Interactions and motion
- Navigation: sheets slide up (spring dampingRatio 0.85, stiffness 400), dim scrim 20% black, status bar goes light grey. Book open: cover scales/zooms from the tapped card to the reader (shared element, 350ms, easing FastOutSlowIn). Detail to reader: cover flips open.
- Glass controls: press scale 0.95, 120ms; on scroll, tab bar stays, may shrink slightly.
- Chrome toggle: 200ms fade plus 8dp translate.
- Slider thumb: scale 1.15 while dragging, glass lens effect.
- Haptics: `HapticFeedbackType.TextHandleMove` on slider ticks / stepper, `LongPress` on selection start and drag-reorder pickup, `Confirm` on toggle and checkmark, `Reject` on destructive confirm.
- Gestures: horizontal drag = page turn; long-press word = selection with handles; pinch = font size (optional); pull-down on Home nothing special; drag handle reorder in lists; swipe-down dismisses sheets.
- Toggle/theme change re-paginates with a 150ms crossfade.

## 5. Must-copy details (priority order)
1. Floating glass circle buttons (about 48dp) with soft shadow, no bar background behind them.
2. Floating capsule tab bar plus a separate circular search button, selected tab in inner darker capsule.
3. Serif bold large titles (Home, Want to Read) against sans body UI.
4. Cover cards with a hinge-style left edge and big soft shadow, GET pill / % progress under them.
5. Bottom sheet with very large radius (38dp) and glass close circle in the corner.
6. Book detail screen tinted by the cover dominant color, with translucent info card and white pill CTA.
7. Reader hidden chrome: only small grey running header and page number; page is nearly pure type.
8. Chrome-on state: "n of N" glass chip plus bottom-left/right circles and top-right close circle.
9. Curl page turn with mirrored back-text and fold shadow.
10. Theme tiles in a 3x2 grid with "Aa" and a thick black selection ring; Calm cream #F3E6CC and Quiet grey #4F4F51.
11. A small / A large capsule stepper plus brightness slider with sun icons.
12. Capsule slider thumb (glass lens) with 6dp track.
13. Green iOS toggle and grouped white rounded cards on #F1F2F7.
14. Alert dialogs as glass cards with pill buttons; destructive red text.
15. Jump-back chip (undo arrow plus page number) and Line Guide dim effect.

## 6. GBook additions and how to style them
- **Quote-post generator**: from the selection menu add "Share as post" -> full-height sheet (38dp radius) with a post preview card (1:1 or 4:5, radius 22dp, serif quote 28sp, cover thumb 40dp, author line) over a glass backdrop; template chooser = horizontal row of theme tiles (reuse Original/Quiet/Calm/Bold colors, same 20dp tile and selection ring); actions as glass pill ("Post", "Save image") and circular glass share button.
- **Instagram-style feed**: new tab "Feed" in the capsule tab bar (icon `DynamicFeed`); post cards radius 22dp white on #F1F2F7, serif quote text, 40dp cover avatars, meta 13sp grey; floating glass "+" circle for composing; large serif title "Feed".
- **Reactions**: row of glass circles 40dp (heart, bookmark, comment, quote) under each post, using outlined icons and fill on active with accent #F28C28; long-press shows glass popover (tooltip bubble pattern, component 13) with emoji set; counts 13sp grey.
- **Google Drive sync**: settings group (component 19) with account row, "Sync now" pill, toggle "Auto sync", status HUD toast (component 14) with cloud icon; library cards show small `CloudDone`/`CloudDownload` badge in the GET pill position.
- Everything uses the same tokens: glass-light/dark fills, full-round radii, serif titles, sans body, black primary CTA, orange accent only for reactions and brand marks.
