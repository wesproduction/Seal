package com.junkfood.seal.util

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class FileUtilTest {
    @Test
    fun redditTemplateFindsFinalVideoWhenYtDlpFilenameUsesADifferentId() {
        val directory = Files.createTempDirectory("walrus-reddit-finalization-test").toFile()
        try {
            val expected =
                directory.resolve("Walrus Reddit").resolve("A post [reddit-post-id].mp4").apply {
                    requireNotNull(parentFile).mkdirs()
                    writeText("video")
                }
            directory.resolve("unrelated.mp4").writeText("other")

            assertEquals(
                listOf(expected.absolutePath),
                FileUtil.findFilesPostDownload(
                    title = "A post [hosted-media-id].mp4",
                    downloadDir = directory.absolutePath,
                    outputTemplate = "Walrus Reddit/A post [reddit-post-id].%(ext)s",
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun redditPostFolderTemplateFindsEveryFinalMediaFile() {
        val directory = Files.createTempDirectory("walrus-reddit-folder-test").toFile()
        try {
            val postDirectory =
                directory.resolve("Walrus Reddit").resolve("A post [reddit-post-id]").apply {
                    mkdirs()
                }
            val video =
                postDirectory.resolve("A post [hosted-media-id].mp4").apply { writeText("video") }
            val subtitle =
                postDirectory.resolve("A post [hosted-media-id].vtt").apply {
                    writeText("subtitle")
                }

            assertEquals(
                listOf(video.absolutePath, subtitle.absolutePath),
                FileUtil.findFilesPostDownload(
                    title = "A post [hosted-media-id].mp4",
                    downloadDir = directory.absolutePath,
                    outputTemplate =
                        "Walrus Reddit/A post [reddit-post-id]/%(title)s [%(id)s].%(ext)s",
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dynamicDefaultTemplateKeepsEstablishedFilenameLookup() {
        val directory = Files.createTempDirectory("walrus-default-finalization-test").toFile()
        try {
            val expected =
                directory.resolve("Ordinary video [media-id].mp4").apply { writeText("video") }

            assertEquals(
                listOf(expected.absolutePath),
                FileUtil.findFilesPostDownload(
                    title = expected.name,
                    downloadDir = directory.absolutePath,
                    outputTemplate = "%(title).200B [%(id)s].%(ext)s",
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
