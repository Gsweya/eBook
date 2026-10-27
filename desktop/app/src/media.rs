//! Images and PDFs through the system's own libraries (gdk-pixbuf, poppler): no bundled decoders. Everything
//! here is safe to call from worker threads; textures are made from the results on the UI thread.

use std::cell::RefCell;
use std::path::{Path, PathBuf};

use gbook_core::library::Media;
use gtk::{gdk, gdk_pixbuf, glib};

/// Decoded pixels, ready to become a texture.
pub struct Decoded {
    bytes: glib::Bytes,
    pub width: i32,
    pub height: i32,
    stride: usize,
    format: gdk::MemoryFormat,
}

impl Decoded {
    pub fn texture(&self) -> gdk::Texture {
        gdk::MemoryTexture::new(
            self.width,
            self.height,
            self.format,
            &self.bytes,
            self.stride,
        )
        .upcast()
    }
}

use gtk::prelude::*;

fn from_pixbuf(p: &gdk_pixbuf::Pixbuf) -> Decoded {
    Decoded {
        bytes: p.read_pixel_bytes(),
        width: p.width(),
        height: p.height(),
        stride: p.rowstride() as usize,
        format: if p.has_alpha() {
            gdk::MemoryFormat::R8g8b8a8
        } else {
            gdk::MemoryFormat::R8g8b8
        },
    }
}

/// The image at `path`, scaled down (never up) to at most `width` pixels wide.
pub fn decode_scaled(path: &Path, width: i32) -> Option<Decoded> {
    let (_, w, h) = gdk_pixbuf::Pixbuf::file_info(path)?;
    let p = if w > width {
        gdk_pixbuf::Pixbuf::from_file_at_scale(
            path,
            width,
            (h as f64 * width as f64 / w as f64).round().max(1.0) as i32,
            false,
        )
        .ok()?
    } else {
        gdk_pixbuf::Pixbuf::from_file(path).ok()?
    };
    Some(from_pixbuf(&p.apply_embedded_orientation().unwrap_or(p)))
}

thread_local! {
    /// The PDF most recently opened on this thread, so rendering page after page doesn't re-parse the file.
    static DOC: RefCell<Option<(PathBuf, poppler::Document)>> = const { RefCell::new(None) };
}

pub fn with_pdf<R>(path: &Path, f: impl FnOnce(&poppler::Document) -> R) -> Option<R> {
    DOC.with(|cell| {
        let mut slot = cell.borrow_mut();
        if slot.as_ref().is_none_or(|(p, _)| p != path) {
            let uri = glib::filename_to_uri(path, None).ok()?;
            let doc = poppler::Document::from_file(&uri, None).ok()?;
            *slot = Some((path.to_path_buf(), doc));
        }
        slot.as_ref().map(|(_, d)| f(d))
    })
}

/// Size in points of each page.
pub fn pdf_page_sizes(path: &Path) -> Vec<(f64, f64)> {
    with_pdf(path, |d| {
        (0..d.n_pages())
            .filter_map(|i| d.page(i).map(|p| p.size()))
            .collect()
    })
    .unwrap_or_default()
}

/// Renders page `index` at `width` pixels wide, on white.
pub fn render_pdf_page(path: &Path, index: i32, width: i32) -> Option<Decoded> {
    with_pdf(path, |d| {
        let page = d.page(index)?;
        let (pw, ph) = page.size();
        let scale = width as f64 / pw.max(1.0);
        let height = (ph * scale).round().max(1.0) as i32;
        let mut surface =
            gtk::cairo::ImageSurface::create(gtk::cairo::Format::Rgb24, width, height).ok()?;
        {
            let cr = gtk::cairo::Context::new(&surface).ok()?;
            cr.set_source_rgb(1.0, 1.0, 1.0);
            cr.paint().ok()?;
            cr.scale(scale, scale);
            page.render(&cr);
        }
        surface.flush();
        let stride = surface.stride() as usize;
        let bytes = glib::Bytes::from_owned(surface.data().ok()?.to_vec());
        // Cairo's RGB24 is native-endian 32-bit xRGB: B, G, R, x in memory on little-endian machines.
        let format = if cfg!(target_endian = "little") {
            gdk::MemoryFormat::B8g8r8x8
        } else {
            gdk::MemoryFormat::X8r8g8b8
        };
        Some(Decoded {
            bytes,
            width,
            height,
            stride,
            format,
        })
    })
    .flatten()
}

pub struct SystemMedia;

impl Media for SystemMedia {
    fn pdf_cover(&self, pdf: &Path, out: &Path) -> Result<i32, String> {
        let pages = with_pdf(pdf, |d| d.n_pages()).ok_or("Not a readable PDF")?;
        let img = render_pdf_page(pdf, 0, 600).ok_or("Couldn't render the first page")?;
        let tex = img.texture();
        tex.save_to_png(out).map_err(|e| e.to_string())?;
        Ok(pages)
    }

    fn pdf_metadata(&self, pdf: &Path) -> (String, String) {
        with_pdf(pdf, |d| {
            (
                d.title().map(|s| s.to_string()).unwrap_or_default(),
                d.author().map(|s| s.to_string()).unwrap_or_default(),
            )
        })
        .unwrap_or_default()
    }

    fn cover_color(&self, image: &Path) -> Option<i64> {
        let p = gdk_pixbuf::Pixbuf::from_file_at_scale(image, 16, 16, false).ok()?;
        let bytes = p.read_pixel_bytes();
        let n = p.n_channels() as usize;
        let stride = p.rowstride() as usize;
        let (mut r, mut g, mut b, mut count) = (0u64, 0u64, 0u64, 0u64);
        for y in 0..p.height() as usize {
            for x in 0..p.width() as usize {
                let i = y * stride + x * n;
                r += bytes[i] as u64;
                g += bytes[i + 1] as u64;
                b += bytes[i + 2] as u64;
                count += 1;
            }
        }
        // Darken so white text stays legible on the detail screen.
        let k = 0.62;
        let c = |v: u64| ((v / count.max(1)) as f64 * k) as i64;
        Some(0xFF00_0000 | (c(r) << 16) | (c(g) << 8) | c(b))
    }
}
