package com.namal.rootgameeditor

import com.topjohnwu.superuser.Shell
import java.io.File

data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean
)

object RootFs {

    /** Call once at app start. Returns true if root was granted. */
    fun requestRoot(): Boolean {
        Shell.getShell() // triggers the root request popup (Magisk/SuperSU etc)
        return Shell.isAppGrantedRoot() ?: false
    }

    /** List a directory's contents via root `ls`. Works even inside /data/data/<pkg>. */
    fun listDir(path: String): List<FileEntry> {
        val result = Shell.cmd("ls -pA1 \"$path\" 2>/dev/null").exec()
        if (!result.isSuccess) return emptyList()

        return result.out
            .filter { it.isNotBlank() }
            .map { line ->
                val isDir = line.endsWith("/")
                val name = if (isDir) line.dropLast(1) else line
                FileEntry(
                    name = name,
                    path = if (path.endsWith("/")) "$path$name" else "$path/$name",
                    isDir = isDir
                )
            }
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** List installed packages that actually have a /data/data folder we can browse. */
    fun listAppDataDirs(): List<FileEntry> = listDir("/data/data")

    /**
     * Root-only files can't be opened directly by our app's normal File APIs.
     * This copies the target file into our own app-private cache dir (readable
     * without root) so we can open/edit it normally, e.g. with SQLiteDatabase
     * or as plain text.
     */
    fun copyToCache(remotePath: String, cacheDir: File): File? {
        val localFile = File(cacheDir, File(remotePath).name)
        val result = Shell.cmd(
            "cp -f \"$remotePath\" \"${localFile.absolutePath}\"",
            "chmod 666 \"${localFile.absolutePath}\""
        ).exec()
        return if (result.isSuccess && localFile.exists()) localFile else null
    }

    /**
     * After editing the local cached copy, push it back over the original
     * root-owned file, then restore sane ownership/permissions so the game
     * doesn't refuse to read its own save file.
     */
    fun writeBack(localFile: File, remotePath: String, ownerUidGid: String? = null): Boolean {
        val cmds = mutableListOf("cp -f \"${localFile.absolutePath}\" \"$remotePath\"")
        if (ownerUidGid != null) {
            cmds.add("chown $ownerUidGid \"$remotePath\"")
        }
        cmds.add("chmod 660 \"$remotePath\"")
        val result = Shell.cmd(*cmds.toTypedArray()).exec()
        return result.isSuccess
    }

    /** Get the current owner (uid:gid) of a file so we can restore it after writing back. */
    fun getOwner(remotePath: String): String? {
        val result = Shell.cmd("stat -c '%u:%g' \"$remotePath\"").exec()
        return if (result.isSuccess) result.out.firstOrNull()?.trim() else null
    }

    /** Read a small text file (e.g. shared_prefs XML) directly via root cat. */
    fun readTextFile(remotePath: String): String? {
        val result = Shell.cmd("cat \"$remotePath\"").exec()
        return if (result.isSuccess) result.out.joinToString("\n") else null
    }

    /** Write a small text file directly back via root, preserving ownership. */
    fun writeTextFile(remotePath: String, content: String): Boolean {
        val owner = getOwner(remotePath)
        val tmp = File.createTempFile("edit", ".tmp")
        tmp.writeText(content)
        val ok = writeBack(tmp, remotePath, owner)
        tmp.delete()
        return ok
    }
}
