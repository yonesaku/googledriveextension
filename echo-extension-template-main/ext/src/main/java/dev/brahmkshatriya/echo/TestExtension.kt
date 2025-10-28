package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

class TestExtension : ExtensionClient, HomeFeedClient, TrackClient, AlbumClient {

    private val httpClient = OkHttpClient()
    private lateinit var settings: Settings
    private var musicLinks = mutableListOf<String>()
    private val metadataCache = mutableMapOf<String, TrackMetadata>()
    private val albumsCache = mutableMapOf<String, AlbumData>()

    data class TrackMetadata(
        val title: String,
        val artist: String,
        val album: String?,
        val year: String?,
        val genre: String?,
        val albumArt: String?,
        val duration: Long?
    )

    data class AlbumData(
        val name: String,
        val artist: String,
        val year: String?,
        val genre: String?,
        val artwork: String?,
        val trackIds: MutableList<String>
    )

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            Setting.TextInput(
                title = "Google Drive Links",
                key = "drive_links",
                summary = "Paste your Google Drive share links (one per line)",
                value = ""
            ),
            Setting.Switch(
                title = "Read Metadata",
                key = "read_metadata",
                summary = "Read song info from MP3 files",
                value = true
            )
        )
    }

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun onInitialize() {
        val linksText = settings.getString("drive_links") ?: ""
        musicLinks = linksText.lines()
            .filter { it.isNotBlank() }
            .toMutableList()
        
        println("Loaded ${musicLinks.size} music links")
    }

    // HomeFeedClient - show albums on home
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val readMetadata = settings.getBoolean("read_metadata") ?: true

        if (readMetadata && metadataCache.isEmpty()) {
            musicLinks.forEach { link ->
                val fileId = extractFileId(link)
                loadMetadataFromDrive(fileId)
            }
            organizeIntoAlbums()
        }

        val shelves = if (albumsCache.isNotEmpty()) {
            val albums = albumsCache.values.sortedBy { it.name }.map { albumData ->
                EchoMediaItem.Lists.AlbumItem(
                    Album(
                        id = albumData.name,
                        title = albumData.name,
                        cover = albumData.artwork?.let { ImageHolder.URL(it) },
                        artists = listOf(
                            Artist(
                                id = albumData.artist,
                                name = albumData.artist
                            )
                        ),
                        subtitle = buildAlbumSubtitle(albumData)
                    )
                )
            }

            listOf(
                Shelf.Lists.Items(
                    title = "Albums",
                    list = albums
                )
            )
        } else emptyList()

        return Feed.Single { shelves }
    }

    private fun buildAlbumSubtitle(albumData: AlbumData): String {
        val parts = mutableListOf<String>()
        albumData.year?.let { parts.add(it) }
        albumData.genre?.let { parts.add(it) }
        parts.add("${albumData.trackIds.size} tracks")
        return parts.joinToString(" • ")
    }

    private fun organizeIntoAlbums() {
        albumsCache.clear()

        metadataCache.forEach { (fileId, metadata) ->
            val albumName = metadata.album ?: "Unknown Album"
            val artistName = metadata.artist

            val albumData = albumsCache.getOrPut(albumName) {
                AlbumData(
                    name = albumName,
                    artist = artistName,
                    year = metadata.year,
                    genre = metadata.genre,
                    artwork = metadata.albumArt,
                    trackIds = mutableListOf()
                )
            }
            albumData.trackIds.add(fileId)
        }
    }

    // AlbumClient methods
    override suspend fun loadAlbum(album: Album): Album {
        return album
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        return null
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val albumData = albumsCache[album.id] ?: return null

        val tracks = albumData.trackIds.mapIndexed { index, fileId ->
            createTrackFromMetadata(fileId, index)
        }

        return Feed.Single { tracks }
    }

    // TrackClient methods
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val directUrl = getDriveDirectUrl(track.id)
        return Track(
            id = track.id,
            title = track.title,
            artists = track.artists,
            album = track.album,
            duration = track.duration,
            cover = track.cover,
            audioUrl = directUrl
        )
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        // Return default media - this might need adjustment based on Echo's API
        return Streamable.Media.Direct(streamable.id)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return null
    }

    private suspend fun loadMetadataFromDrive(fileId: String) {
        try {
            val directUrl = getDriveDirectUrl(fileId)
            
            val request = Request.Builder()
                .url(directUrl)
                .header("Range", "bytes=0-524288")
                .build()

            val response = httpClient.newCall(request).await()
            
            if (response.isSuccessful) {
                val bytes = response.body?.bytes() ?: return
                
                val tempFile = File.createTempFile("temp_audio_", ".mp3")
                FileOutputStream(tempFile).use { it.write(bytes) }
                
                val audioFile = AudioFileIO.read(tempFile)
                val tag = audioFile.tag
                
                val title = tag?.getFirst(FieldKey.TITLE) ?: "Unknown Title"
                val artist = tag?.getFirst(FieldKey.ARTIST) ?: "Unknown Artist"
                val album = tag?.getFirst(FieldKey.ALBUM)
                val year = tag?.getFirst(FieldKey.YEAR)
                val genre = tag?.getFirst(FieldKey.GENRE)
                val duration = audioFile.audioHeader?.trackLength?.toLong()
                
                var albumArtUrl: String? = null
                val artwork = tag?.firstArtwork
                if (artwork != null) {
                    val artBytes = artwork.binaryData
                    albumArtUrl = "data:image/jpeg;base64," + 
                        Base64.getEncoder().encodeToString(artBytes)
                }
                
                metadataCache[fileId] = TrackMetadata(
                    title = title,
                    artist = artist,
                    album = album,
                    year = year,
                    genre = genre,
                    albumArt = albumArtUrl,
                    duration = duration
                )
                
                tempFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createTrackFromMetadata(fileId: String, index: Int): Track {
        val metadata = metadataCache[fileId]
        
        return Track(
            id = fileId,
            title = metadata?.title ?: "Track ${index + 1}",
            artists = listOf(
                Artist(
                    id = metadata?.artist ?: "unknown",
                    name = metadata?.artist ?: "Unknown Artist"
                )
            ),
            album = metadata?.album?.let { 
                Album(
                    id = it,
                    title = it,
                    cover = metadata.albumArt?.let { art -> ImageHolder.URL(art) }
                )
            },
            duration = metadata?.duration,
            cover = metadata?.albumArt?.let { ImageHolder.URL(it) }
        )
    }

    private fun extractFileId(link: String): String {
        var fileId = link.substringAfter("/file/d/").substringBefore("/")
        
        if (fileId == link) {
            fileId = link.substringAfter("id=").substringBefore("&")
        }
        
        return fileId
    }

    private fun getDriveDirectUrl(fileId: String): String {
        return "https://drive.google.com/uc?export=download&id=$fileId"
    }
}