//! The Feed: posts made from highlights, key ideas and chapter recaps, in the style of the phone's
//! Instagram-like cards: a 4:5 slide carousel, likes, reactions and saving.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use gbook_core::library::plural;
use gbook_core::{Book, Post, PostKind, REACTION_EMOJIS, posts, store};
use gtk::prelude::*;
use gtk::{gio, glib, pango};

use crate::library::empty_state;
use crate::sheet;
use crate::shell::{self, ShellRc};
use crate::state::AppRc;
use crate::theme::Rgb;
use crate::widgets::*;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Filter {
    All,
    Quotes,
    Ideas,
    Recaps,
    Saved,
}

/// Width of a post's slide; the feed column is a little wider.
const SLIDE_W: i32 = 520;

const FILTERS: [(Filter, &str); 5] = [
    (Filter::All, "All"),
    (Filter::Quotes, "My Highlights"),
    (Filter::Ideas, "Key Ideas"),
    (Filter::Recaps, "Recaps"),
    (Filter::Saved, "Saved"),
];

pub fn page(app: &AppRc, shell: &ShellRc) -> gtk::Widget {
    let filter = Rc::new(Cell::new(Filter::All));
    let content = vbox(18);
    content.set_margin_top(76);
    content.set_margin_bottom(130);
    content.set_margin_start(20);
    content.set_margin_end(20);
    let clamp = Clamp::new(&content, SLIDE_W + 40);
    let create = circle_button("auto-awesome", "Create posts");
    let a = app.clone();
    create.connect_clicked(move |_| generate_sheet(&a, None));
    let top = top_bar(&hbox(0), None, &create, false, Some((SLIDE_W + 40, 20)));
    let render: Rc<RefCell<Option<Rc<dyn Fn()>>>> = Rc::default();
    let r = {
        let (app, content, filter, render, shell) = (
            app.clone(),
            content.clone(),
            filter.clone(),
            render.clone(),
            shell.clone(),
        );
        Rc::new(move || {
            clear(&content);
            let again = render.borrow().clone().unwrap();
            build(&app, &shell, &content, &filter, again);
        }) as Rc<dyn Fn()>
    };
    *render.borrow_mut() = Some(r.clone());
    shell::scrolled_page(app, &top, &clamp.upcast(), r)
}

fn build(
    app: &AppRc,
    _shell: &ShellRc,
    content: &gtk::Box,
    filter: &Rc<Cell<Filter>>,
    rerender: Rc<dyn Fn()>,
) {
    let head = vbox(2);
    head.append(&label("Feed", &["large-title"]));
    head.append(&label("Bite-sized ideas from your books", &["secondary"]));
    content.append(&head);
    let chips = hbox(8);
    for (f, name) in FILTERS {
        let b = gtk::Button::with_label(name);
        b.add_css_class("chip");
        b.add_css_class("solid");
        if f == filter.get() {
            b.add_css_class("on");
        }
        let (fl, r) = (filter.clone(), rerender.clone());
        b.connect_clicked(move |_| {
            fl.set(f);
            r();
        });
        chips.append(&b);
    }
    let sc = gtk::ScrolledWindow::new();
    sc.set_vscrollbar_policy(gtk::PolicyType::Never);
    sc.set_propagate_natural_height(true);
    sc.set_child(Some(&chips));
    content.append(&sc);

    let d = app.data();
    let shown: Vec<(Post, Book)> = d
        .posts
        .iter()
        .filter(|p| match filter.get() {
            Filter::All => true,
            Filter::Quotes => p.kind == PostKind::QUOTE,
            Filter::Ideas => p.kind == PostKind::IDEA,
            Filter::Recaps => p.kind == PostKind::RECAP,
            Filter::Saved => p.saved,
        })
        .filter_map(|p| d.book(&p.book_id).map(|b| (p.clone(), b.clone())))
        .collect();
    drop(d);
    if shown.is_empty() {
        let a = app.clone();
        content.append(&empty_state(
            if filter.get() == Filter::All {
                "Your feed is empty"
            } else {
                "Nothing here yet"
            },
            "Turn chapters and highlights into posts you can scroll, react to and share.",
            Some(("Create Posts", Rc::new(move || generate_sheet(&a, None)))),
        ));
        return;
    }
    // Long feeds fill in as they scroll into view rather than all at once.
    let batch = 12;
    let list = vbox(18);
    content.append(&list);
    let more = Rc::new(RefCell::new(shown.into_iter()));
    let fill = {
        let (app, list, more) = (app.clone(), list.clone(), more.clone());
        Rc::new(move || {
            for (p, b) in more.borrow_mut().by_ref().take(batch) {
                list.append(&post_card(&app, &p, &b));
            }
        })
    };
    fill();
    if more.borrow().len() > 0 {
        let btn = gtk::Button::with_label("Show more");
        btn.add_css_class("pill");
        btn.set_halign(gtk::Align::Center);
        let (f, m) = (fill.clone(), more.clone());
        btn.connect_clicked(move |b| {
            f();
            if m.borrow().len() == 0 {
                b.set_visible(false);
            }
        });
        content.append(&btn);
    }
}

/// (gradient stops, text colour, accent colour) for slide style `i`, as on the phone.
fn slide_style(i: i32, cover: Rgb) -> (Vec<Rgb>, bool, Rgb, Rgb) {
    let h = Rgb::hex;
    match i.rem_euclid(6) {
        0 => (
            vec![h(0xF6EBD4), h(0xF0E1C2)],
            false,
            h(0x3B2F1E),
            h(0xB0662B),
        ),
        1 => (
            vec![h(0x242426), h(0x111112)],
            false,
            h(0xFFFFFF),
            h(0xF28C28),
        ),
        2 => (
            vec![cover, h(0x111111)],
            true,
            h(0xFFFFFF),
            h(0xFFFFFF).lerp(cover, 0.3),
        ),
        3 => (
            vec![h(0xFFFFFF), h(0xF3F3F6)],
            false,
            h(0x000000),
            h(0x8A8A8E),
        ),
        4 => (
            vec![h(0xF28C28), h(0xE8693A)],
            true,
            h(0xFFFFFF),
            h(0xFFFFFF).lerp(h(0xF28C28), 0.25),
        ),
        _ => (
            vec![h(0x5A5A5C), h(0x3F3F41)],
            false,
            h(0xE5E5E7),
            h(0xB8B8BA),
        ),
    }
}

fn attrs(family: &str, weight: pango::Weight, px: f32, color: Rgb, alpha: f32) -> pango::AttrList {
    let a = pango::AttrList::new();
    let mut fd = pango::FontDescription::new();
    fd.set_family(family);
    fd.set_weight(weight);
    fd.set_absolute_size(px as f64 * pango::SCALE as f64);
    a.insert(pango::AttrFontDesc::new(&fd));
    let c = |v: u8| v as u16 * 257;
    a.insert(pango::AttrColor::new_foreground(
        c(color.0),
        c(color.1),
        c(color.2),
    ));
    if alpha < 1.0 {
        a.insert(pango::AttrInt::new_foreground_alpha(
            (alpha * 65535.0) as u16,
        ));
    }
    a
}

fn slide(app: &AppRc, post: &Post, book: &Book, page: usize) -> gtk::Widget {
    let (stops, diagonal, ink, accent) = slide_style(post.style, Rgb::from_argb(book.cover_color));
    let ov = gtk::Overlay::new();
    let bg = gtk::DrawingArea::new();
    bg.set_draw_func(move |_, cr, w, h| {
        let g = if diagonal {
            gtk::cairo::LinearGradient::new(0.0, 0.0, w as f64, h as f64)
        } else {
            gtk::cairo::LinearGradient::new(0.0, 0.0, 0.0, h as f64)
        };
        for (i, c) in stops.iter().enumerate() {
            g.add_color_stop_rgb(
                i as f64 / (stops.len() - 1) as f64,
                c.0 as f64 / 255.0,
                c.1 as f64 / 255.0,
                c.2 as f64 / 255.0,
            );
        }
        let _ = cr.set_source(&g);
        let _ = cr.paint();
    });
    ov.set_child(Some(&bg));
    let col = vbox(0);
    col.set_margin_top(28);
    col.set_margin_bottom(24);
    col.set_margin_start(28);
    col.set_margin_end(28);
    let text = post.slides.get(page).cloned().unwrap_or_default();
    let recap_title = post.kind == PostKind::RECAP && page == 0;
    let eyebrow = match post.kind {
        PostKind::QUOTE if page == 1 => "MY NOTE".to_string(),
        PostKind::QUOTE => "HIGHLIGHT".to_string(),
        PostKind::IDEA => "KEY IDEA".to_string(),
        PostKind::RECAP if page == 0 => "CHAPTER RECAP".to_string(),
        PostKind::RECAP => format!("KEY POINT {page} OF {}", post.slides.len() - 1),
    };
    let e = gtk::Label::new(Some(&eyebrow));
    e.set_xalign(0.0);
    let ea = attrs(crate::theme::SANS, pango::Weight::Bold, 13.0, accent, 1.0);
    ea.insert(pango::AttrInt::new_letter_spacing(1300));
    e.set_attributes(Some(&ea));
    col.append(&e);
    let mid = vbox(4);
    mid.set_vexpand(true);
    mid.set_valign(gtk::Align::Center);
    if recap_title {
        let t = wrap_label(&text, &[], 5);
        t.set_attributes(Some(&attrs(
            crate::theme::DISPLAY,
            pango::Weight::Bold,
            32.0,
            ink,
            1.0,
        )));
        mid.append(&t);
        let s = label("Next slide for the key points  →", &[]);
        s.set_attributes(Some(&attrs(
            crate::theme::SANS,
            pango::Weight::Medium,
            15.0,
            accent,
            1.0,
        )));
        s.set_margin_top(14);
        mid.append(&s);
    } else {
        if post.kind != PostKind::RECAP {
            let q = label("“", &[]);
            q.set_attributes(Some(&attrs(
                crate::theme::DISPLAY,
                pango::Weight::Normal,
                64.0,
                accent,
                1.0,
            )));
            let qa = attrs(
                crate::theme::DISPLAY,
                pango::Weight::Normal,
                64.0,
                accent,
                1.0,
            );
            qa.insert(pango::AttrFloat::new_line_height(0.55));
            q.set_attributes(Some(&qa));
            mid.append(&q);
        }
        let n = text.chars().count();
        let px = match n {
            0..90 => 27.0,
            90..160 => 23.0,
            160..260 => 20.0,
            260..420 => 17.5,
            _ => 15.5,
        };
        let t = wrap_label(&text, &[], 16);
        let a = attrs(crate::theme::QUOTE, pango::Weight::Normal, px, ink, 1.0);
        a.insert(pango::AttrFloat::new_line_height(1.2));
        t.set_attributes(Some(&a));
        mid.append(&t);
    }
    col.append(&mid);
    let foot = hbox(10);
    foot.append(&Cover::new(app, book, 26));
    let fv = vbox(0);
    fv.set_hexpand(true);
    let bt = label(&book.title, &[]);
    bt.set_ellipsize(pango::EllipsizeMode::End);
    bt.set_attributes(Some(&attrs(
        crate::theme::SANS,
        pango::Weight::Semibold,
        13.5,
        ink,
        1.0,
    )));
    fv.append(&bt);
    let au = label(&book.author, &[]);
    au.set_attributes(Some(&attrs(
        crate::theme::SANS,
        pango::Weight::Medium,
        12.5,
        ink,
        0.7,
    )));
    fv.append(&au);
    foot.append(&fv);
    let brand = label("GBook", &[]);
    brand.set_attributes(Some(&attrs(
        crate::theme::DISPLAY,
        pango::Weight::Bold,
        15.0,
        accent,
        1.0,
    )));
    foot.append(&brand);
    col.append(&foot);
    ov.add_overlay(&col);
    ov.upcast()
}

/// Header, 4:5 slide carousel, action row and reactions.
fn post_card(app: &AppRc, post: &Post, book: &Book) -> gtk::Widget {
    let card = vbox(8);
    let head = gtk::Button::new();
    head.add_css_class("flat");
    let h = hbox(10);
    h.append(&Cover::new(app, book, 28));
    let hv = vbox(0);
    hv.set_hexpand(true);
    let t = label(&book.title, &["semibold"]);
    t.set_ellipsize(pango::EllipsizeMode::End);
    hv.append(&t);
    let sub = label(
        if post.chapter_title.trim().is_empty() {
            &book.author
        } else {
            &post.chapter_title
        },
        &["caption", "text2"],
    );
    sub.set_ellipsize(pango::EllipsizeMode::End);
    hv.append(&sub);
    h.append(&hv);
    h.append(&label(
        match post.kind {
            PostKind::QUOTE => "Highlight",
            PostKind::IDEA => "Key idea",
            PostKind::RECAP => "Recap",
        },
        &["badge", "caption"],
    ));
    head.set_child(Some(&h));
    head.set_tooltip_text(Some("Read this chapter"));
    let (a, bid, ch) = (app.clone(), book.id.clone(), post.chapter);
    head.connect_clicked(move |_| a.open(&bid, Some((ch, 0))));
    card.append(&head);

    // A 4:5 slide, as on the phone.
    let frame = gtk::Box::new(gtk::Orientation::Vertical, 0);
    frame.add_css_class("post-frame");
    frame.set_overflow(gtk::Overflow::Hidden);
    frame.set_size_request(-1, (SLIDE_W as f32 * 1.25) as i32);
    let stack = gtk::Stack::new();
    stack.set_transition_type(gtk::StackTransitionType::SlideLeftRight);
    // Later slides are built when first shown.
    stack.add_named(&slide(app, post, book, 0), Some("0"));
    let ov = gtk::Overlay::new();
    ov.set_child(Some(&stack));
    let page = Rc::new(Cell::new(0usize));
    let n = post.slides.len().max(1);
    let dots = hbox(6);
    dots.set_halign(gtk::Align::Center);
    let paint_dots = {
        let (dots, page) = (dots.clone(), page.clone());
        move || {
            clear(&dots);
            if n < 2 {
                return;
            }
            for i in 0..n {
                let d = vbox(0);
                d.add_css_class("slide-dot");
                if i == page.get() {
                    d.add_css_class("on");
                }
                dots.append(&d);
            }
        }
    };
    paint_dots();
    let paint_dots = Rc::new(paint_dots);
    if n > 1 {
        for (fwd, ic, al) in [
            (false, "chevron-left", gtk::Align::Start),
            (true, "chevron-right", gtk::Align::End),
        ] {
            let b = circle_button(ic, if fwd { "Next slide" } else { "Previous slide" });
            b.add_css_class("small");
            b.set_halign(al);
            b.set_valign(gtk::Align::Center);
            b.set_margin_start(10);
            b.set_margin_end(10);
            let (s, p, pd) = (stack.clone(), page.clone(), paint_dots.clone());
            let (a, post2, book2) = (app.clone(), post.clone(), book.clone());
            b.connect_clicked(move |_| {
                let next = if fwd {
                    (p.get() + 1).min(n - 1)
                } else {
                    p.get().saturating_sub(1)
                };
                p.set(next);
                let name = next.to_string();
                if s.child_by_name(&name).is_none() {
                    s.add_named(&slide(&a, &post2, &book2, next), Some(&name));
                }
                s.set_visible_child_name(&name);
                pd();
            });
            ov.add_overlay(&b);
        }
    }
    // Double-click likes, as a double tap does on the phone.
    let dbl = gtk::GestureClick::new();
    let (a, pid) = (app.clone(), post.id.clone());
    dbl.connect_pressed(move |_, n_press, _, _| {
        if n_press == 2 {
            a.update(|d| {
                if let Some(p) = d.posts.iter_mut().find(|p| p.id == pid) {
                    p.liked = true;
                }
            });
        }
    });
    ov.add_controller(dbl);
    ov.set_vexpand(true);
    frame.append(&ov);
    card.append(&frame);
    if n > 1 {
        let dd = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        dd.add_css_class("post-dots");
        dd.append(&dots);
        dd.set_halign(gtk::Align::Center);
        card.append(&dd);
    }

    let actions = hbox(2);
    let act = |ic: &str, tip: &str, classes: &[&str]| {
        let b = gtk::Button::new();
        b.add_css_class("flat");
        for c in classes {
            b.add_css_class(c);
        }
        b.set_child(Some(&icon(ic, 26)));
        b.set_tooltip_text(Some(tip));
        b
    };
    let like = act(
        if post.liked {
            "favorite"
        } else {
            "favorite-border"
        },
        "Like",
        if post.liked { &["liked"] } else { &[] },
    );
    let (a, pid) = (app.clone(), post.id.clone());
    like.connect_clicked(move |_| {
        a.update(|d| {
            if let Some(p) = d.posts.iter_mut().find(|p| p.id == pid) {
                p.liked = !p.liked;
            }
        })
    });
    actions.append(&like);
    let react = gtk::Button::new();
    react.add_css_class("flat");
    match &post.my_reaction {
        Some(e) => react.set_child(Some(&gtk::Label::new(Some(e)))),
        None => react.set_child(Some(&icon("add-reaction", 26))),
    }
    react.set_tooltip_text(Some("React"));
    let (a, pid) = (app.clone(), post.id.clone());
    react.connect_clicked(move |b| {
        let row = hbox(2);
        let pop = popover(b, &row, false);
        for e in REACTION_EMOJIS {
            let eb = gtk::Button::with_label(e);
            eb.add_css_class("emoji-button");
            let (a2, pid2, p2) = (a.clone(), pid.clone(), pop.clone());
            eb.connect_clicked(move |_| {
                p2.popdown();
                a2.update(|d| d.react_post(&pid2, e));
            });
            row.append(&eb);
        }
        pop.popup();
    });
    actions.append(&react);
    let share = act("ios-share", "Copy quote or save as image", &[]);
    let (a, p2, b2, st, pg) = (
        app.clone(),
        post.clone(),
        book.clone(),
        stack.clone(),
        page.clone(),
    );
    share.connect_clicked(move |btn| share_menu(&a, btn, &p2, &b2, &st, pg.get()));
    actions.append(&share);
    actions.append(&spacer());
    let del = act("delete-outline", "Remove post", &["text2"]);
    let (a, pid) = (app.clone(), post.id.clone());
    del.connect_clicked(move |_| a.update(|d| d.posts.retain(|p| p.id != pid)));
    actions.append(&del);
    let save = act(
        if post.saved {
            "bookmark"
        } else {
            "bookmark-border"
        },
        "Save",
        &[],
    );
    let (a, pid) = (app.clone(), post.id.clone());
    save.connect_clicked(move |_| {
        a.update(|d| {
            if let Some(p) = d.posts.iter_mut().find(|p| p.id == pid) {
                p.saved = !p.saved;
            }
        })
    });
    actions.append(&save);
    card.append(&actions);
    let mut summary = Vec::new();
    if post.liked {
        summary.push("Liked by you".to_string());
    }
    if !post.reactions.is_empty() {
        summary.push(
            post.reactions
                .iter()
                .map(|(k, v)| format!("{k} {v}"))
                .collect::<Vec<_>>()
                .join(" "),
        );
    }
    if !summary.is_empty() {
        let l = label(&summary.join("  ·  "), &["semibold"]);
        l.set_margin_start(8);
        card.append(&l);
    }
    card.upcast()
}

fn share_menu(
    app: &AppRc,
    anchor: &gtk::Button,
    post: &Post,
    book: &Book,
    stack: &gtk::Stack,
    page: usize,
) {
    let col = vbox(0);
    let pop = popover(anchor, &col, false);
    let text = post.slides.get(page).cloned().unwrap_or_default();
    let copy = gtk::Button::new();
    copy.add_css_class("menu-row");
    let h = hbox(12);
    h.append(&icon("content-copy", 20));
    h.append(&label("Copy quote", &[]));
    copy.set_child(Some(&h));
    let (a, p, t, b) = (app.clone(), pop.clone(), text.clone(), book.clone());
    copy.connect_clicked(move |w| {
        p.popdown();
        w.clipboard()
            .set_text(&format!("“{t}” — {}, {} · via GBook", b.title, b.author));
        a.toast("Quote copied");
    });
    col.append(&copy);
    let img = gtk::Button::new();
    img.add_css_class("menu-row");
    let h = hbox(12);
    h.append(&icon("photo-camera", 20));
    h.append(&label("Save as image…", &[]));
    img.set_child(Some(&h));
    let (a, p, st, b) = (app.clone(), pop.clone(), stack.clone(), book.clone());
    img.connect_clicked(move |_| {
        p.popdown();
        save_slide_image(&a, &st, &b);
    });
    col.append(&img);
    pop.popup();
}

/// Renders the visible slide to a PNG the user picks a place for.
fn save_slide_image(app: &AppRc, stack: &gtk::Stack, book: &Book) {
    let Some(child) = stack.visible_child() else {
        return;
    };
    let (w, h) = (child.width(), child.height());
    let Some(renderer) = child.native().and_then(|n| n.renderer()) else {
        return;
    };
    let paintable = gtk::WidgetPaintable::new(Some(&child));
    let snap = gtk::Snapshot::new();
    // Twice the on-screen size, for a sharp image.
    snap.scale(2.0, 2.0);
    paintable.snapshot(&snap, w as f64, h as f64);
    let Some(node) = snap.to_node() else { return };
    let texture = renderer.render_texture(
        &node,
        Some(&gtk::graphene::Rect::new(
            0.0,
            0.0,
            (w * 2) as f32,
            (h * 2) as f32,
        )),
    );
    let name = format!("{} - GBook.png", book.title.replace(['/', '\\'], "-"));
    let dialog = gtk::FileDialog::builder()
        .title("Save Post Image")
        .initial_name(name)
        .modal(true)
        .build();
    let a = app.clone();
    let win = app.window();
    dialog.save(win.as_ref(), gio::Cancellable::NONE, move |res| {
        let Some(path) = res.ok().and_then(|f| f.path()) else {
            return;
        };
        match texture.save_to_png(&path) {
            Ok(_) => a.toast("Image saved"),
            Err(e) => a.toast(&format!("Couldn't save the image: {e}")),
        }
    });
}

/// Choose books and kinds of post, then generate them off the UI thread.
pub fn generate_sheet(app: &AppRc, preselect: Option<&str>) {
    let d = app.data();
    let books: Vec<Book> = d.books.iter().filter(|b| b.text_ready).cloned().collect();
    drop(d);
    let selected: Rc<RefCell<Vec<String>>> = Rc::new(RefCell::new(match preselect {
        Some(id) => vec![id.to_string()],
        None => books.iter().map(|b| b.id.clone()).collect(),
    }));
    let opts = Rc::new(Cell::new((true, true, true)));
    let col = vbox(14);
    col.set_margin_start(24);
    col.set_margin_end(24);
    col.set_margin_bottom(24);
    col.append(&wrap_label(
        "GBook picks the most important sentences in each chapter and your highlights, then turns them into posts you can scroll through to remember the ideas.",
        &["secondary"],
        4,
    ));
    let group = vbox(0);
    group.add_css_class("group");
    let hl_count = {
        let d = app.data();
        let sel = selected.borrow();
        d.highlights
            .iter()
            .filter(|h| sel.contains(&h.book_id))
            .count()
    };
    for (i, (name, sub)) in [
        ("From my highlights", plural(hl_count, "highlight")),
        (
            "Key ideas",
            "The strongest sentence of each chapter".to_string(),
        ),
        ("Chapter recaps", "A slide carousel per chapter".to_string()),
    ]
    .into_iter()
    .enumerate()
    {
        if i > 0 {
            let sep = vbox(0);
            sep.add_css_class("row-sep");
            sep.set_margin_start(16);
            group.append(&sep);
        }
        let row = hbox(12);
        row.add_css_class("row");
        let v = vbox(0);
        v.set_hexpand(true);
        v.append(&label(name, &[]));
        v.append(&label(&sub, &["caption", "text2"]));
        row.append(&v);
        let sw = gtk::Switch::new();
        sw.set_active(true);
        sw.set_valign(gtk::Align::Center);
        let o = opts.clone();
        sw.connect_active_notify(move |s| {
            let (mut a, mut b, mut c) = o.get();
            match i {
                0 => a = s.is_active(),
                1 => b = s.is_active(),
                _ => c = s.is_active(),
            }
            o.set((a, b, c));
        });
        row.append(&sw);
        group.append(&row);
    }
    col.append(&group);
    col.append(&label("BOOKS", &["eyebrow"]));
    let list = vbox(0);
    list.add_css_class("group");
    for b in &books {
        let row = gtk::Button::new();
        row.add_css_class("row");
        let r = crate::library::book_row(app, b, &b.author);
        let on = selected.borrow().contains(&b.id);
        let check = icon(
            if on {
                "check-circle"
            } else {
                "radio-button-unchecked"
            },
            22,
        );
        if on {
            check.add_css_class("accent");
        }
        r.append(&check);
        row.set_child(Some(&r));
        let (sel, id) = (selected.clone(), b.id.clone());
        row.connect_clicked(move |_| {
            let mut s = sel.borrow_mut();
            let on = if let Some(i) = s.iter().position(|x| *x == id) {
                s.remove(i);
                false
            } else {
                s.push(id.clone());
                true
            };
            check.set_icon_name(Some(if on {
                "gbook-check-circle-symbolic"
            } else {
                "gbook-radio-button-unchecked-symbolic"
            }));
            if on {
                check.add_css_class("accent");
            } else {
                check.remove_css_class("accent");
            }
        });
        list.append(&row);
    }
    if books.is_empty() {
        col.append(&label("Add a book first.", &["secondary"]));
    } else {
        col.append(&list);
    }
    let go = gtk::Button::with_label("Create Posts");
    go.add_css_class("primary");
    col.append(&go);
    let close = sheet::present(app, Some("Create Posts"), &col, 480);
    let a = app.clone();
    go.connect_clicked(move |btn| {
        let ids = selected.borrow().clone();
        let (from_hl, ideas, recaps) = opts.get();
        if ids.is_empty() {
            a.toast("Pick at least one book");
            return;
        }
        btn.set_sensitive(false);
        btn.set_label("Creating…");
        let d = a.data();
        let jobs: Vec<(Book, Vec<gbook_core::Highlight>)> = ids
            .iter()
            .filter_map(|id| {
                d.book(id).map(|b| {
                    (
                        b.clone(),
                        d.highlights
                            .iter()
                            .filter(|h| h.book_id == *id)
                            .cloned()
                            .collect(),
                    )
                })
            })
            .collect();
        drop(d);
        let paths = a.paths.clone();
        let (a2, close2) = (a.clone(), close.clone());
        glib::spawn_future_local(async move {
            let fresh = gio::spawn_blocking(move || {
                let mut out = Vec::new();
                for (b, hs) in jobs {
                    let Some(content) = store::load_content(&paths, &b.id) else {
                        continue;
                    };
                    if from_hl {
                        for h in &hs {
                            let t = content
                                .chapters
                                .get(h.chapter as usize)
                                .map(|c| c.title.clone())
                                .unwrap_or_default();
                            out.push(posts::from_highlight(&b, h, &t));
                        }
                    }
                    if ideas || recaps {
                        out.extend(posts::from_book(
                            &b,
                            &content,
                            if ideas { 1 } else { 0 },
                            recaps,
                        ));
                    }
                }
                out
            })
            .await
            .unwrap_or_default();
            let added = a2.update(|d| posts::add_posts(d, fresh));
            close2();
            a2.toast(&if added == 0 {
                "No new posts: these books are already in your Feed".to_string()
            } else {
                format!("Added {} to your Feed", plural(added, "post"))
            });
        });
    });
}
