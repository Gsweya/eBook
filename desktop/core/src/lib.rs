//! Everything in GBook that doesn't touch the screen: the library schema shared with the Android app, its
//! on-disk store, EPUB parsing, reading statistics and feed posts.

pub mod backup;
pub mod dates;
pub mod dom;
pub mod epub;
pub mod library;
pub mod model;
pub mod plans;
pub mod posts;
pub mod store;
pub mod tracker;

pub use model::*;
