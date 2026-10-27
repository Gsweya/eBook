//! Prints what the EPUB parser makes of each book given: `cargo run --example parse_epub -- book.epub …`
fn main() {
    let tmp = std::env::temp_dir().join("gbook-parse-images");
    for arg in std::env::args().skip(1) {
        let t = std::time::Instant::now();
        match gbook_core::epub::parse(std::path::Path::new(&arg), &tmp) {
            Ok(r) => {
                let blocks: usize = r.content.chapters.iter().map(|c| c.blocks.len()).sum();
                println!(
                    "{arg}\n  {} by {} · {} chapters · {blocks} blocks · cover {} · {:?}",
                    r.title,
                    r.author,
                    r.content.chapters.len(),
                    r.cover.map(|c| c.len()).unwrap_or(0),
                    t.elapsed()
                );
                for c in r.content.chapters.iter().take(6) {
                    let first = c
                        .blocks
                        .iter()
                        .find(|b| b.kind == 0)
                        .map(|b| b.text.chars().take(70).collect::<String>())
                        .unwrap_or_default();
                    println!("    [{}] {} — {first}", c.blocks.len(), c.title);
                }
            }
            Err(e) => println!("{arg}: ERROR {e}"),
        }
    }
}
