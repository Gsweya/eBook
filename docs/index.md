---
title: GBook
---

# GBook

A lightweight, open-source reader for EPUB and PDF books on Android, Linux and Windows, with highlights,
notes, a feed of ideas from your books and reading stats.

GBook can keep your library the same on your phone and computer through your own Google Drive. There is no
GBook server: your books and reading data stay on your devices and in your Drive.

- [Source code and downloads](https://github.com/Gsweya/eBook)
- [Privacy policy](privacy.html)
- [Terms of use](terms.html)

## A message from G

I kept struggling to find a good ebook reader, mostly because of how apps handle PDFs: hard to read, hard to
keep track of, hard to highlight. So I made GBook. Here's why, and how it works.

<style>
  .message { margin: 0 0 20px; }
  .message video {
    display: block;
    width: min(100%, calc(80vh * 9 / 16));
    aspect-ratio: 9 / 16;
    margin: 0 auto;
    border-radius: 16px;
    background: #000;
  }
  .message video:fullscreen { border-radius: 0; }
  .message video::cue { background: rgba(0, 0, 0, 0.75); }
  .message figcaption { margin-top: 12px; text-align: center; }
</style>

<figure class="message">
  <video controls playsinline preload="metadata" poster="video/message-from-g.jpg"
         aria-label="A message from G on why GBook exists and how it works, with captions. 5 minutes 41 seconds.">
    <source src="video/message-from-g.mp4" type="video/mp4">
    <track kind="captions" src="video/message-from-g.vtt" srclang="en" label="English" default>
    <a href="video/message-from-g.mp4">Watch the video</a>
  </video>
  <figcaption>Made with ❤️ · props to Ame</figcaption>
</figure>

**Note:** in the video I say Google Drive sync is still being worked on. It's out now, so your library can stay
the same on your phone and computer.

Want to try it, or have an idea to make it better? [Tell me on GitHub](https://github.com/Gsweya/eBook/issues).
