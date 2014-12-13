package net.dean.kdown

import java.net.URL
import java.util.regex.Pattern
import java.util.HashSet
import com.fasterxml.jackson.databind.JsonNode

/**
 * The UrlTransformer trait is used to change one URL into one or many other URLs. A sample use case would be if an
 * imgur album was given to download (such as [this one](https://imgur.com/a/szz8j)). One could make use of the imgur
 * API to retrieve a list of images in said gallery and return this data in the [transform] method.
 *
 * This pattern allows for the abstraction of downloading "resources", not just files, where a resource can be consisted
 * of one or more files.
 */
public trait UrlTransformer {
    /**
     * Tests if this UrlTransformer will operate on the given URL. If true, then [transform] will be called directly
     * after.
     */
    public fun willTransform(url: URL): Boolean

    /**
     * Transforms one URL into a Set of other URLs. The resulting Set must contain at least one element, and every
     * element in the Set must be a valid URL. If this method is called, then it is guaranteed that [willTransform] has
     * already returned true.
     */
    public fun transform(url: URL): Set<String>
}

/**
 * This class is an abstraction of [UrlTransformer] that will transform a URL if and only it matches a regular
 * expression found in the key set of [regexes]. [regexes] is a Map of regular expressions to a unique identification
 * string for that resource. For example, on regex might match a single image, and another might match an entire
 * collection of images.
 */
public abstract class RegexUrlTransformer(public val regexes: Map<String, String>) : UrlTransformer {

    override fun willTransform(url: URL): Boolean {
        val urlString = url.toExternalForm()

        for (regex in regexes.keySet()) {
            if (urlString.matches(regex)) return true
        }
        return false
    }

    override fun transform(url: URL): Set<String> {
        val urlString = url.toExternalForm()

        for ((regex, resourceType) in regexes) {
            if (urlString.matches(regex)) {
                return transform(url, resourceType, regex)
            }
        }

        throw IllegalStateException("Could not find any regex that matches $url")
    }

    /**
     * This method is responsible for identifying the files in a resource. [effectiveRegex] is the key in [regexes] that
     * matched the given URL, and [resourceType] is the unique identifying value for that regular expression.
     */
    abstract fun transform(url: URL, resourceType: String, effectiveRegex: String): Set<String>

    /** Retrieves capture group [n] in the given regular expression */
    public fun captureGroup(regex: String, str: CharSequence, n: Int): String {
        val matcher = Pattern.compile(regex).matcher(str)
        matcher.matches()
        return matcher.group(n)
    }
}

/** A basic RegexUrlTransformer whose only resource type is named "it" */
public abstract class SimpleRegexUrlTransformer(regex: String) : RegexUrlTransformer(mapOf(regex to "it"))

/**
 * This class uses the imgur API to retrieve links based on the given resource URL. Only links to albums and galleries
 * are supported at this time.
 */
public class ImgurTransformer(val rest: RestClient, val clientId: String) :
        RegexUrlTransformer(mapOf(
                """http[s]?://imgur\.com/a/(\w+)""" to "album",
                """http[s]?://imgur\.com/gallery/(\w+)""" to "gallery"
        )) {

    private val headers = mapOf("Authorization" to "Client-ID $clientId")

    override fun transform(url: URL, resourceType: String, effectiveRegex: String): Set<String> {
        val urlString = url.toExternalForm()

        when (resourceType) {
            "album" -> {
                val album = captureGroup(effectiveRegex, urlString, 1)
                val json = rest.get("https://api.imgur.com/3/album/$album/images", headers = headers).json
                return parseLinks(json!!.get("data"))
            }
            "gallery" -> {
                val galleryAlbum = captureGroup(effectiveRegex, urlString, 1)
                val json = rest.get("https://api.imgur.com/3/gallery/album/$galleryAlbum", headers = headers).json
                checkError(json!!)
                return parseLinks(json.get("data").get("images"))
            }
        }

        return setOf()
    }

    private fun parseLinks(imagesNode: JsonNode): Set<String> {
        val links: MutableSet<String> = HashSet()
        for (node in imagesNode) {
            links.add(node.get("link").asText())
        }

        return links
    }

    private fun checkError(json: JsonNode?) {
        json!!

        if (!json.get("success").asBoolean(false)) {
            throw IllegalStateException("Imgur API returned an error: ${json.get("data").get("error").asText()}")
        }
    }
}

