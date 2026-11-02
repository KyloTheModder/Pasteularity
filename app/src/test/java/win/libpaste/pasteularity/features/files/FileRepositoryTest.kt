package win.libpaste.pasteularity.features.files

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import win.libpaste.pasteularity.shell.CommandFailedException
import win.libpaste.pasteularity.shell.ShellBackend
import win.libpaste.pasteularity.shell.ShellEvent

private class FakeShell(private val reply: (String) -> Result<String> = { Result.success("") }) : ShellBackend {
    val commands = mutableListOf<String>()
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun exec(cmd: String, timeoutMs: Long): Result<String> {
        commands += cmd
        return reply(cmd)
    }

    override fun stream(cmd: String): Flow<ShellEvent> = emptyFlow()
}

class FileRepositoryTest {
    @Test
    fun parsesDirectoriesFilesAndLinks() {
        val folder = parseStatLine("41f9|4096|1700000000|/sdcard//Android")!!
        assertTrue(folder.isDirectory)
        assertEquals("Android", folder.name)
        assertEquals("/sdcard/Android", folder.path)

        val file = parseStatLine("81b0|2048|1700000000|/sdcard/a|b.obb")!!
        assertFalse(file.isDirectory)
        assertEquals("a|b.obb", file.name)
        assertEquals(2048L, file.sizeBytes)

        assertTrue(parseStatLine("a1ff|10|1700000000|/sdcard/link")!!.isLink)
    }

    @Test
    fun ignoresFindErrorLines() {
        assertNull(parseStatLine("find: /data/foo: Permission denied"))
    }

    @Test
    fun foldersSortFirstAndHiddenFilesAreFiltered() {
        val entries = listOf(
            FileEntry("zeta.txt", "/z", false, false, 1, 1),
            FileEntry(".secret", "/s", false, false, 1, 1),
            FileEntry("beta", "/b", true, false, 0, 1),
        )
        assertEquals(
            listOf("beta", "zeta.txt"),
            sortAndFilter(entries, SortKey.Name, true, "", false).map { it.name },
        )
        assertEquals(
            listOf("beta", "zeta.txt", ".secret"),
            sortAndFilter(entries, SortKey.Name, false, "", true).map { it.name },
        )
    }

    @Test
    fun partialListingStillReturnsReadableEntries() = runTest {
        val shell = FakeShell {
            Result.failure(CommandFailedException(1, "find: /x/y: Permission denied\n81b0|5|1700000000|/x/ok.txt"))
        }
        assertEquals(listOf("ok.txt"), FileRepository(shell).list("/x").getOrThrow().map { it.name })
    }

    @Test
    fun unreadableFolderIsAFailure() = runTest {
        val shell = FakeShell { Result.failure(CommandFailedException(1, "find: /data: Permission denied")) }
        assertTrue(FileRepository(shell).list("/data").isFailure)
    }

    @Test
    fun filenamesAreQuotedInEveryCommand() = runTest {
        val shell = FakeShell()
        val repository = FileRepository(shell)
        val hostile = "/sdcard/a'; reboot; '"

        repository.delete(listOf(hostile))
        repository.createFolder("/sdcard", "b'; reboot; '")
        repository.rename(hostile, "c'; reboot; '")

        val escaped = "'\\''; reboot; '\\'''"
        shell.commands.forEach { assertTrue(it, escaped in it) }
    }

    @Test
    fun protectedFoldersCannotBeDeleted() = runTest {
        val shell = FakeShell()
        val repository = FileRepository(shell)
        listOf("/", "/sdcard", "/sdcard/", "/storage/emulated/0", OBB_ROOT, DATA_ROOT).forEach { path ->
            assertTrue(path, repository.delete(listOf(path)).isFailure)
        }
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun pasteIntoSameFolderGetsACopyName() {
        val targets = FileRepository(FakeShell()).pasteTargets(listOf("/a/game.obb", "/b/other"), "/a")
        assertEquals("/a/game copy.obb", targets[0].second)
        assertEquals("/a/other", targets[1].second)
    }

    @Test
    fun obbNamesResolveToAPackage() {
        assertEquals("com.example.game", packageFromObbName("main.1234.com.example.game.obb"))
        assertEquals("com.example.game", packageFromObbName("patch.7.com.example.game.obb"))
        assertNull(packageFromObbName("game.obb"))
    }

    @Test
    fun packageNamesRejectShellMetacharacters() {
        assertTrue(isValidPackageName("com.example.game"))
        assertFalse(isValidPackageName("com.example;reboot"))
        assertFalse(isValidPackageName("../../etc"))
        assertFalse(isValidPackageName("single"))
    }

    @Test
    fun fileNamesCannotEscapeTheFolder() {
        assertNull(validateFileName("save 1.dat"))
        assertTrue(validateFileName("a/b") != null)
        assertTrue(validateFileName("..") != null)
    }

    @Test
    fun breadcrumbsBuildCumulativePaths() {
        assertEquals(
            listOf("/" to "/", "sdcard" to "/sdcard", "Android" to "/sdcard/Android"),
            breadcrumbs("/sdcard/Android"),
        )
        assertNull(parentPath("/"))
        assertEquals("/", parentPath("/sdcard"))
    }
}
