package com.n7folder.player.data

/**
 * Arborescence réelle des dossiers contenant de l'audio (l'explorateur de dossiers).
 * Construite à partir des pistes déjà indexées : aucun nouvel accès disque.
 * Le premier niveau est constitué des sources ; [path] = [id de la source, dossier, sous-dossier…].
 */
class FolderNode internal constructor(val name: String, val path: List<String>) {
    internal val childMap = LinkedHashMap<String, FolderNode>()
    private val direct = ArrayList<TrackEntry>()

    /** Nombre de pistes dans ce dossier et tous ses sous-dossiers. */
    var totalTracks: Int = 0
        internal set

    /** Sous-dossiers, en tri naturel (valide après [FolderTree.build]). */
    var children: List<FolderNode> = emptyList()
        private set

    /** Pistes posées directement dans ce dossier, dans l'ordre de lecture. */
    var tracks: List<TrackEntry> = emptyList()
        private set

    internal fun addDirect(track: TrackEntry) {
        direct.add(track)
    }

    internal fun finish() {
        children = childMap.values.sortedWith(NAME_ORDER)
        tracks = direct.sortedWith(TRACK_ORDER)
        direct.clear()
        for (child in children) child.finish()
    }

    /** Toutes les pistes du dossier : d'abord les siennes, puis celles des sous-dossiers, de A à Z. */
    fun allTracks(): List<TrackEntry> {
        val out = ArrayList<TrackEntry>(totalTracks)
        collectInto(out)
        return out
    }

    private fun collectInto(out: MutableList<TrackEntry>) {
        out.addAll(tracks)
        for (child in children) child.collectInto(out)
    }

    private companion object {
        val NAME_ORDER = Comparator<FolderNode> { a, b -> PathNormalizer.naturalCompare(a.name, b.name) }
    }
}

class FolderTree private constructor(val root: FolderNode) {

    /** Retrouve un dossier à partir de son chemin ; le chemin vide désigne la racine (liste des sources). */
    fun find(path: List<String>): FolderNode? {
        var node = root
        for (segment in path) {
            node = node.childMap[segment] ?: return null
        }
        return node
    }

    companion object {
        fun build(tracks: List<TrackEntry>, sources: List<MusicSource>): FolderTree {
            val labels = HashMap<String, String>()
            for (source in sources) labels[source.id] = source.label.ifBlank { "Dossier" }

            val root = FolderNode("", emptyList())
            root.totalTracks = tracks.size
            for (track in tracks) {
                var node = root
                node = step(node, track.sourceId, labels[track.sourceId] ?: "Dossier")
                for (segment in track.dirPath) {
                    node = step(node, segment, segment)
                }
                node.addDirect(track)
            }
            root.finish()
            return FolderTree(root)
        }

        /** Descend d'un niveau (en créant le dossier au besoin) et compte la piste au passage. */
        private fun step(parent: FolderNode, key: String, displayName: String): FolderNode {
            val existing = parent.childMap[key]
            val child = if (existing != null) {
                existing
            } else {
                val created = FolderNode(displayName, parent.path + key)
                parent.childMap[key] = created
                created
            }
            child.totalTracks++
            return child
        }
    }
}
