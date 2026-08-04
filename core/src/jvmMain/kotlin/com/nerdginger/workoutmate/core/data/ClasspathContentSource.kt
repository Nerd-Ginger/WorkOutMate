package com.nerdginger.workoutmate.core.data

/**
 * Reads bundled content from the classpath.
 *
 * This is the JVM implementation, and it serves Android too: jar resources are
 * packaged into the APK and reachable through the same classloader, so there is
 * no second Android-specific path to keep in step. iOS would need its own.
 *
 * The classloader is taken from this class rather than the thread context,
 * because on Android the thread context classloader is not guaranteed to see
 * the app's own resources.
 */
class ClasspathContentSource(
    private val basePath: String = "content",
) : ContentSource {

    override fun read(name: String): String? {
        val path = "$basePath/$name"
        val stream = ClasspathContentSource::class.java.classLoader?.getResourceAsStream(path)
            ?: return null
        return stream.use { it.readBytes().decodeToString() }
    }
}
