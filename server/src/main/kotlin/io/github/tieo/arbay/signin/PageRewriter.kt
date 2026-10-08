package io.github.tieo.arbay.signin

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode

/**
 * Rewrites the addresses in a page the site sent so the page keeps working behind the proxy. Every
 * address of an allowed host becomes an address under [SignInHosts.PREFIX], so the browser asks Arbay
 * for it and Arbay asks the site. Addresses of any other host are left as the page wrote them.
 */
object PageRewriter {
    private val absolute = Regex("""(?:https?:)?\\?/\\?/(${SignInHosts.allowed.joinToString("|") { it.replace(".", "\\.") }})(?![A-Za-z0-9.-])""")
    private val cssUrl = Regex("""url\(\s*(['"]?)([^'")]*)\1\s*\)""")
    private val cssImport = Regex("""@import\s+(['"])([^'"]+)\1""")
    private val refreshUrl = Regex("""(?i)(url\s*=\s*)(['"]?)([^'";]+)\2""")
    private val whitespace = Regex("\\s+")

    /** Attributes whose value is an address of the page's own host or another one. */
    private val urlAttributes = setOf("href", "src", "action", "formaction", "poster", "data", "background", "longdesc", "cite", "icon", "manifest", "usemap")

    /** The proxied address for [url] as written in a page at [base], or null when it is not an address of an allowed host. */
    fun proxied(url: String, base: URI): String? {
        val trimmed = url.trim()
        // An address already under the prefix, or one that only names a place on the page, stays as it is.
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(SignInHosts.PREFIX)) return null
        val resolved = runCatching { base.resolve(trimmed) }.getOrNull() ?: return null
        val host = resolved.host?.lowercase() ?: return null
        if (!SignInHosts.allows(host)) return null
        val path = resolved.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return SignInHosts.PREFIX + host + path + (resolved.rawQuery?.let { "?$it" } ?: "")
    }

    /** A Location header, rewritten the way a page's own addresses are. */
    fun location(value: String, base: URI): String = proxied(value, base) ?: value

    /** A stylesheet, or the value of a style attribute: the addresses in its url() and @import. */
    fun css(text: String, base: URI): String {
        val withUrls = cssUrl.replace(text) { m ->
            val quote = m.groupValues[1]
            "url($quote${proxied(m.groupValues[2], base) ?: m.groupValues[2]}$quote)"
        }
        return cssImport.replace(withUrls) { m ->
            val quote = m.groupValues[1]
            "@import $quote${proxied(m.groupValues[2], base) ?: m.groupValues[2]}$quote"
        }
    }

    /** Script text, or any text: every absolute address of an allowed host, which the page builds as a string. */
    fun script(text: String): String = absolute.replace(text) { SignInHosts.PREFIX + it.groupValues[1] }

    /** A whole HTML document, with its addresses rewritten and a shim placed before its own scripts. */
    fun html(html: String, base: URI): String {
        // A base element moves where relative addresses resolve, so the rest of the page follows it.
        val effective = Jsoup.parse(html, base.toString()).selectFirst("base[href]")?.attr("href")
            ?.takeIf { it.isNotBlank() }?.let { runCatching { base.resolve(it.trim()) }.getOrNull() }
            ?.takeIf { it.host != null && SignInHosts.allows(it.host.lowercase()) } ?: base

        val doc = Jsoup.parse(script(html), base.toString())
        doc.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
        // The page's own policy would stop Arbay framing it, and its integrity hashes no longer match once rewritten.
        doc.select("meta[http-equiv]")
            .filter { it.attr("http-equiv").lowercase() in setOf("content-security-policy", "content-security-policy-report-only", "x-frame-options") }
            .forEach { it.remove() }
        doc.select("[integrity]").removeAttr("integrity")
        doc.select("style").forEach { element ->
            element.dataNodes().forEach { node -> node.setWholeData(css(node.wholeData, effective)) }
        }
        for (element in doc.getAllElements()) {
            for (attribute in element.attributes().asList()) {
                val rewritten = when {
                    attribute.key in urlAttributes -> proxied(attribute.value, effective)
                    attribute.key == "srcset" || attribute.key == "imagesrcset" -> srcset(attribute.value, effective)
                    attribute.key == "style" -> css(attribute.value, effective)
                    attribute.key == "content" && element.tagName() == "meta" && element.attr("http-equiv").equals("refresh", ignoreCase = true) ->
                        refreshUrl.replace(attribute.value) { m ->
                            val quote = m.groupValues[2]
                            m.groupValues[1] + quote + (proxied(m.groupValues[3], effective) ?: m.groupValues[3]) + quote
                        }
                    else -> null
                }
                if (rewritten != null) element.attr(attribute.key, rewritten)
            }
        }
        doc.head().prependElement("script").appendChild(DataNode(shim()))
        return doc.outerHtml()
    }

    private fun srcset(value: String, base: URI): String = value.split(',').joinToString(", ") { candidate ->
        val parts = candidate.trim().split(whitespace, limit = 2)
        val url = parts[0]
        (proxied(url, base) ?: url) + (parts.getOrNull(1)?.let { " $it" } ?: "")
    }

    /**
     * Runs before the page's own scripts. A request the page builds at run time from a root-relative
     * or an allowed absolute address goes through the proxy too, since a rewrite of the page's text
     * cannot see those.
     */
    private fun shim(): String {
        val hosts = SignInHosts.allowed.joinToString("|") { it.replace(".", "\\.") }
        return """
(function () {
  var prefix = '/signin-proxy/';
  var host = location.pathname.split('/')[2] || '';
  var absolute = /^https?:\/\/($hosts)(?![A-Za-z0-9.-])/;
  function proxied(u) {
    if (typeof u !== 'string' || u.indexOf(prefix) === 0) return u;
    if (u.charAt(0) === '/' && u.charAt(1) !== '/') return prefix + host + u;
    return u.replace(absolute, function (all, h) { return prefix + h; });
  }
  if (window.fetch) {
    var nativeFetch = window.fetch;
    window.fetch = function (input, init) {
      if (typeof input === 'string') input = proxied(input);
      else if (input && typeof input.url === 'string' && proxied(input.url) !== input.url) input = new Request(proxied(input.url), input);
      return nativeFetch.call(this, input, init);
    };
  }
  var nativeOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    var rest = Array.prototype.slice.call(arguments, 2);
    return nativeOpen.apply(this, [method, proxied(String(url))].concat(rest));
  };
})();
"""
    }
}
