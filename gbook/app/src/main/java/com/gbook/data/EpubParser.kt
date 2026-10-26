package com.gbook.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Minimal EPUB 2/3 reader: metadata, spine, TOC titles, text blocks and images. */
object EpubParser {
    class Result(val title: String, val author: String, val content: BookContent, val cover: ByteArray?)

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val BLOCKS = HEADINGS + setOf(
        "div", "p", "li", "ul", "ol", "blockquote", "pre", "section", "article", "table", "tbody",
        "thead", "tr", "td", "th", "figure", "figcaption", "aside", "header", "footer", "nav", "dl",
        "dt", "dd", "body", "main", "hr", "center", "svg",
    )

    fun parse(file: File, imagesDir: File): Result = ZipFile(file).use { zip ->
        fun read(path: String): ByteArray? =
            zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

        fun readText(path: String) = read(path)?.toString(Charsets.UTF_8)

        val container = Jsoup.parse(
            readText("META-INF/container.xml") ?: error("Not an EPUB (missing container.xml)"),
            "", Parser.xmlParser(),
        )
        val opfPath = container.selectFirst("rootfile")?.attr("full-path") ?: error("No OPF")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = Jsoup.parse(readText(opfPath) ?: error("Missing OPF"), "", Parser.xmlParser())

        fun meta(name: String) = (opf.getElementsByTag("dc:$name").firstOrNull()
            ?: opf.getElementsByTag(name).firstOrNull())?.text()?.trim().orEmpty()

        val title = meta("title")
        val author = meta("creator")

        data class Item(val id: String, val path: String, val type: String, val props: String)

        val items = opf.getElementsByTag("item").map {
            Item(it.attr("id"), resolve(opfDir, it.attr("href")), it.attr("media-type"), it.attr("properties"))
        }
        val byId = items.associateBy { it.id }

        // Cover image
        val coverItem = items.firstOrNull { "cover-image" in it.props }
            ?: opf.getElementsByTag("meta").firstOrNull { it.attr("name") == "cover" }
                ?.attr("content")?.let { byId[it] }
            ?: items.firstOrNull { it.type.startsWith("image") && "cover" in it.id.lowercase() }
        val cover = coverItem?.takeIf { it.type.startsWith("image") }?.let { read(it.path) }

        // TOC titles keyed by document path (fragment stripped)
        val tocTitles = LinkedHashMap<String, String>()
        items.firstOrNull { "nav" in it.props.split(' ') }?.let { nav ->
            val html = readText(nav.path) ?: return@let
            val doc = Jsoup.parse(html)
            val navDir = nav.path.substringBeforeLast('/', "")
            val tocNav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav")
            tocNav?.select("a[href]")?.forEach { a ->
                val p = resolve(navDir, a.attr("href").substringBefore('#'))
                val t = a.text().trim()
                if (t.isNotEmpty()) tocTitles.putIfAbsent(p, t)
            }
        }
        if (tocTitles.isEmpty()) {
            val ncxId = opf.getElementsByTag("spine").firstOrNull()?.attr("toc")
            val ncx = byId[ncxId] ?: items.firstOrNull { it.type == "application/x-dtbncx+xml" }
            ncx?.let { n ->
                val doc = Jsoup.parse(readText(n.path) ?: return@let, "", Parser.xmlParser())
                val ncxDir = n.path.substringBeforeLast('/', "")
                doc.getElementsByTag("navPoint").forEach { np ->
                    val label = np.getElementsByTag("text").firstOrNull()?.text()?.trim().orEmpty()
                    val src = np.getElementsByTag("content").firstOrNull()?.attr("src") ?: return@forEach
                    if (label.isNotEmpty()) tocTitles.putIfAbsent(resolve(ncxDir, src.substringBefore('#')), label)
                }
            }
        }

        val savedImages = HashMap<String, String>()
        fun saveImage(path: String): String? = savedImages.getOrPut(path) {
            val bytes = read(path) ?: return null
            val name = path.replace('/', '_').replace(Regex("[^A-Za-z0-9._-]"), "")
            File(imagesDir, name).writeBytes(bytes)
            name
        }

        val chapters = mutableListOf<Chapter>()
        opf.getElementsByTag("itemref").forEach { ref ->
            val item = byId[ref.attr("idref")] ?: return@forEach
            if (!item.type.contains("html")) return@forEach
            val html = readText(item.path) ?: return@forEach
            val docDir = item.path.substringBeforeLast('/', "")
            val body = Jsoup.parse(html).body()
            val blocks = mutableListOf<Block>()
            walk(body, blocks) { src -> saveImage(resolve(docDir, src)) }
            if (blocks.isEmpty()) return@forEach
            // Spine documents without a TOC entry or heading are continuations of the previous chapter.
            if (tocTitles.isNotEmpty() && item.path !in tocTitles && blocks.none { it.type == 1 } && chapters.isNotEmpty()) {
                val prev = chapters.removeAt(chapters.lastIndex)
                chapters += prev.copy(blocks = prev.blocks + blocks)
                return@forEach
            }
            val chTitle = tocTitles[item.path]
                ?: blocks.firstOrNull { it.type == 1 }?.text?.take(80)
                ?: "Section ${chapters.size + 1}"
            chapters += Chapter(chTitle, blocks)
        }
        Result(title, author, BookContent(chapters), cover)
    }

    private fun walk(el: Element, out: MutableList<Block>, image: (String) -> String?) {
        val tag = el.normalName()
        when {
            tag == "script" || tag == "style" -> Unit
            tag == "img" -> image(el.attr("src"))?.let { out += Block(2, src = it) }
            tag == "image" -> image(el.attr("xlink:href").ifEmpty { el.attr("href") })?.let { out += Block(2, src = it) }
            tag in HEADINGS -> {
                el.select("img, image").forEach { walk(it, out, image) }
                val t = el.text().trim()
                if (t.isNotEmpty()) out += Block(1, t, level = tag[1].digitToInt())
            }
            tag == "pre" -> el.wholeText().trim().takeIf { it.isNotEmpty() }?.let { out += Block(0, it) }
            tag in BLOCKS && el.children().none { it.normalName() in BLOCKS || it.normalName() == "img" || it.normalName() == "image" } -> {
                val t = el.text().trim()
                if (t.isNotEmpty()) out += Block(0, t)
            }
            else -> {
                // Mixed content: inline runs become paragraphs, block children recurse.
                val buf = StringBuilder()
                fun flush() {
                    val t = buf.toString().replace(Regex("\\s+"), " ").trim()
                    if (t.isNotEmpty()) out += Block(0, t)
                    buf.clear()
                }
                for (node in el.childNodes()) {
                    when (node) {
                        is TextNode -> buf.append(node.text())
                        is Element -> {
                            val n = node.normalName()
                            val inline = n !in BLOCKS && n != "img" && n != "image" && node.select("img, image").isEmpty()
                            if (inline) buf.append(node.text()) else { flush(); walk(node, out, image) }
                        }
                    }
                }
                flush()
            }
        }
    }

    private fun resolve(baseDir: String, href: String): String {
        val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        val parts = (if (baseDir.isEmpty()) decoded else "$baseDir/$decoded").split('/')
        val stack = ArrayDeque<String>()
        for (p in parts) when (p) {
            "", "." -> Unit
            ".." -> stack.removeLastOrNull()
            else -> stack.addLast(p)
        }
        return stack.joinToString("/")
    }
}
