package dev.herdr.android.ui.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.herdr_mobile.ChangesEntry
import uniffi.herdr_mobile.ChangesLine
import uniffi.herdr_mobile.ChangesList

// Spec "A file's diff is readable on the phone", "Long diffs show their start
// and load in full on request", "The list shows what is not committed yet",
// "The screen's texts are fixed" (OpenSpec change changes-view, task 4.1).
class ChangesTextTest {
    private fun ls(vararg t: String) = t.map { ChangesLine(it, false) }

    private val head = ls("diff --git a/f.txt b/f.txt", "index 1111111..2222222 100644", "--- a/f.txt", "+++ b/f.txt")

    private fun body(kind: String, lines: List<ChangesLine>, headers: Int = 0, tooLarge: Boolean = false, whole: Boolean = false) =
        diffBody(kind, lines, headers, tooLarge, whole)

    private fun entry(path: String, kind: String, added: Long? = null, removed: Long? = null, opens: Boolean = true) =
        ChangesEntry(path, kind, added?.toULong(), removed?.toULong(), opens)

    // --- Line kinds ---

    @Test
    fun addedRemovedContextAndHunkLinesAreTold() {
        val b = body("changed", head + ls("@@ -1,3 +1,3 @@ fn main() {", " keep", "-old", "+new"), headers = 4)
        assertEquals(
            listOf(
                DiffRow.Hunk("@@ -1,3 +1,3 @@ fn main() {"),
                DiffRow.Code(Mark.Context, "keep"),
                DiffRow.Code(Mark.Removed, "old"),
                DiffRow.Code(Mark.Added, "new"),
            ),
            b.rows,
        )
        assertNull(b.showWhole)
        assertFalse(b.tooLarge)
    }

    @Test
    fun fileHeadersAreNotDrawn() {
        val b = body("changed", head + ls("@@ -1 +1 @@", "-a", "+b"), headers = 4)
        assertTrue(b.rows.none { it is DiffRow.Code && (it.text.startsWith("++ ") || it.text.startsWith("-- ")) })
        assertEquals(3, b.rows.size)
    }

    @Test
    fun aMissingNewlineIsANote() {
        val b = body("changed", head + ls("@@ -1 +1 @@", "-a", "+b", "\\ No newline at end of file"), headers = 4)
        assertEquals(DiffRow.Note(NO_NEWLINE), b.rows.last())
        assertEquals("No newline at end of file", NO_NEWLINE)
    }

    @Test
    fun aBinaryFileIsANote() {
        val b = body("changed", ls("diff --git a/logo.png b/logo.png", "index 1..2 100644", "Binary files a/logo.png and b/logo.png differ"), headers = 2)
        assertEquals(listOf(DiffRow.Note("Binary file, not shown")), b.rows)
        val n = body("new", ls("diff --git a/b.bin b/b.bin", "new file mode 100644", "index 0000000..1", "Binary files /dev/null and b/b.bin differ"), headers = 3)
        assertEquals(listOf(DiffRow.Note("Binary file, not shown")), n.rows)
    }

    @Test
    fun anEmptyNewFileIsANote() {
        val b = body("new", ls("diff --git a/e.txt b/e.txt", "new file mode 100644", "index 0000000..e69de29"), headers = 3)
        assertEquals(listOf(DiffRow.Note("Empty file")), b.rows)
        val d = body("deleted", ls("diff --git a/e.txt b/e.txt", "deleted file mode 100644", "index e69de29..0000000"), headers = 3)
        assertEquals(listOf(DiffRow.Note("Empty file")), d.rows)
    }

    @Test
    fun aModeOnlyChangeNamesBothModes() {
        val b = body("changed", ls("diff --git a/run.sh b/run.sh", "old mode 100644", "new mode 100755"), headers = 3)
        assertEquals(listOf(DiffRow.Note("Only the file mode changed (100644 to 100755)")), b.rows)
    }

    @Test
    fun aModeChangeWithEditsShowsTheEdits() {
        val lines = ls("diff --git a/run.sh b/run.sh", "old mode 100644", "new mode 100755", "index 1..2", "--- a/run.sh", "+++ b/run.sh", "@@ -1 +1 @@", "-a", "+b")
        val b = body("changed", lines, headers = 6)
        assertEquals(DiffRow.Code(Mark.Added, "b"), b.rows.last())
    }

    @Test
    fun aSubmoduleShowsItsOldAndNewCommitShort() {
        val old = "3f2a9c1d0e5b6a7c8d9e0f1a2b3c4d5e6f7a8b9c"
        val new = "8b04d7e1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7"
        val lines = ls("diff --git a/vendor/theme b/vendor/theme", "index 3f2a9c1..8b04d7e 160000", "--- a/vendor/theme", "+++ b/vendor/theme", "@@ -1 +1 @@", "-Subproject commit $old", "+Subproject commit $new")
        assertEquals(listOf(DiffRow.Note("Submodule: 3f2a9c1 to 8b04d7e")), body("submodule", lines, headers = 4).rows)
    }

    @Test
    fun aNewSubmoduleHasNoOldCommit() {
        val new = "8b04d7e1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7"
        val lines = ls("diff --git a/sdk b/sdk", "new file mode 160000", "index 0000000..8b04d7e", "--- /dev/null", "+++ b/sdk", "@@ -0,0 +1 @@", "+Subproject commit $new")
        assertEquals(listOf(DiffRow.Note("Submodule: 0000000 to 8b04d7e")), body("submodule", lines, headers = 5).rows)
    }

    @Test
    fun aNewFileIsAllAdded() {
        val b = body("new", ls("diff --git a/n.txt b/n.txt", "new file mode 100644", "index 0000000..1", "--- /dev/null", "+++ b/n.txt", "@@ -0,0 +1,3 @@", "+a", "+b", "+c"), headers = 5)
        assertEquals(3, b.rows.count { it is DiffRow.Code && it.mark == Mark.Added })
        assertEquals(0, b.rows.count { it is DiffRow.Code && it.mark != Mark.Added })
    }

    @Test
    fun conflictMarkersOnDiskAreDrawnAsLines() {
        val b = body("conflict", head + ls("@@ -1 +1,5 @@", "+<<<<<<< HEAD", "+a", "+=======", "+b", "+>>>>>>> topic"), headers = 4)
        assertEquals(DiffRow.Code(Mark.Added, "<<<<<<< HEAD"), b.rows[1])
    }

    @Test
    fun anEmptyLineInTheBodyIsContext() {
        assertEquals(DiffRow.Code(Mark.Context, ""), body("changed", ls(""), headers = 0).rows.single())
    }

    // --- Carriage return, cut, tabs ---

    @Test
    fun aCarriageReturnIsAMarkNotText() {
        val b = body("changed", head + ls("@@ -1 +1 @@", "-first line", "+first line\r"), headers = 4)
        assertEquals(DiffRow.Code(Mark.Removed, "first line", cr = false), b.rows[1])
        assertEquals(DiffRow.Code(Mark.Added, "first line", cr = true), b.rows[2])
        assertEquals("CR", CR_MARK)
    }

    @Test
    fun aLineCutAt4000CharactersCarriesTheMark() {
        val long = "+" + "x".repeat(3999)
        val b = diffBody("new", listOf(ChangesLine("@@ -0,0 +1 @@", false), ChangesLine(long, true)), 0, false, false)
        val row = b.rows[1] as DiffRow.Code
        assertTrue(row.cut)
        assertEquals("… (cut)", CUT_MARK)
    }

    @Test
    fun tabsBecomeSpacesToTheNextStopOfFour() {
        assertEquals("a   b", expandTabs("a\tb"))
        assertEquals("    x", expandTabs("\tx"))
        assertEquals("abcd    e", expandTabs("abcd\te"))
        val b = body("changed", ls("+\tindented"), headers = 0)
        assertEquals(DiffRow.Code(Mark.Added, "    indented"), b.rows.single())
    }

    // --- Wrapping ---

    @Test
    fun aShortLineIsOnePart() {
        assertEquals(listOf("short"), wrap("short", 40))
        assertEquals(listOf(""), wrap("", 40))
    }

    @Test
    fun aLongLineWrapsAndContinuationsLeaveRoomForTheMark() {
        val text = "x".repeat(300)
        val parts = wrap(text, 40)
        assertEquals(40, parts[0].length)
        assertTrue(parts.drop(1).all { it.length <= 40 - CONTINUES.length })
        assertEquals(text, parts.joinToString(""))
        assertEquals("↪ ", CONTINUES)
    }

    @Test
    fun wrappingPrefersASpaceInTheSecondHalf() {
        val parts = wrap("  <a href=\"/guide\" aria-current={active === \"guide\" ? \"page\" : undefined}>Guide</a>", 50)
        assertTrue(parts[0], parts[0].endsWith(" "))
        assertEquals("  <a href=\"/guide\" aria-current={active === \"guide\" ? \"page\" : undefined}>Guide</a>", parts.joinToString(""))
        assertTrue(parts.all { it.length <= 50 })
    }

    @Test
    fun aSurrogatePairIsNeverSplit() {
        val text = "a".repeat(39) + "😀" + "b".repeat(10)
        val parts = wrap(text, 40)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.none { p -> p.isNotEmpty() && (p.first().isLowSurrogate() || p.last().isHighSurrogate()) })
    }

    @Test
    fun theTailMarkFitsOnTheLastPart() {
        // A carriage return box after a line that fills the width moves to its own continuation.
        val parts = wrap("x".repeat(40), 40, tail = 3)
        assertEquals("x".repeat(40), parts.joinToString(""))
        assertTrue(parts.last().length + 3 <= 40 - CONTINUES.length)
        assertEquals(listOf("x".repeat(37)), wrap("x".repeat(37), 40, tail = 3))
    }

    @Test
    fun aNarrowWidthStillMakesProgress() {
        val parts = wrap("abcdefgh", 1)
        assertEquals("abcdefgh", parts.joinToString(""))
        assertTrue(parts.all { it.isNotEmpty() })
    }

    // --- Long and too large ---

    @Test
    fun aDiffOver2000LinesShowsItsStartAndTheControl() {
        val lines = head + ls("@@ -1,20000 +1,20000 @@") + List(19_999) { ChangesLine("+v$it", false) }
        val b = body("changed", lines, headers = 4)
        assertEquals(2000, b.rows.size)
        assertEquals("Show the whole diff (20,000 lines)", b.showWhole)
        val all = body("changed", lines, headers = 4, whole = true)
        assertEquals(20_000, all.rows.size)
        assertNull(all.showWhole)
    }

    @Test
    fun exactly2000LinesNeedNoControl() {
        val lines = head + ls("@@ -1 +1,1999 @@") + List(1999) { ChangesLine("+v$it", false) }
        val b = body("changed", lines, headers = 4)
        assertEquals(2000, b.rows.size)
        assertNull(b.showWhole)
    }

    @Test
    fun tooLargeShowsWhatArrivedWithoutTheControl() {
        val lines = ls("diff --git a/huge.log b/huge.log", "new file mode 100644", "index 0000000..1", "--- /dev/null", "+++ b/huge.log", "@@ -0,0 +1,900000 @@") +
            List(3000) { ChangesLine("+2026-11-16T09:12:01Z start worker $it", false) }
        val b = body("new", lines, headers = 5, tooLarge = true)
        assertEquals(3001, b.rows.size)
        assertNull(b.showWhole)
        assertTrue(b.tooLarge)
        assertEquals("Too large to show on the phone", TOO_LARGE)
    }

    @Test
    fun numbersAreGroupedWithCommas() {
        assertEquals("1", grouped(1))
        assertEquals("999", grouped(999))
        assertEquals("1,000", grouped(1000))
        assertEquals("20,118", grouped(20_118))
        assertEquals("1,234,567", grouped(1_234_567))
    }

    // --- List rows ---

    @Test
    fun aRowSplitsItsPathAndKeepsTheWord() {
        val r = rowText(entry("src/components/DocsNav.astro", "changed", 3, 1))
        assertEquals(RowText("DocsNav.astro", "src/components", "changed", Tone.Plain, "+3", "−1"), r.copy(description = ""))
        val top = rowText(entry("README.md", "changed", 1, 0))
        assertEquals("", top.dir)
    }

    @Test
    fun aNestedRepositoryKeepsItsSlashAndHasNoCounts() {
        val r = rowText(entry("vendor/upstream-sdk/", "repository", opens = false))
        assertEquals("upstream-sdk/", r.file)
        assertEquals("vendor", r.dir)
        assertNull(r.plus)
        assertNull(r.minus)
    }

    @Test
    fun countsShowOnlyWhatWasCounted() {
        assertEquals(null to "−22", counts("deleted", 0uL, 22uL))
        assertEquals(null to null, counts("changed", null, null))
        assertEquals("+11,204" to "−8,733", counts("changed", 11_204uL, 8_733uL))
        // A changed file with nothing added still shows its 0, as git's numstat does.
        assertEquals("+0" to "−4", counts("changed", 0uL, 4uL))
        // A new or added file never has removed lines: no "−0"; a deleted one no "+0".
        assertEquals("+48" to null, counts("new", 48uL, 0uL))
        assertEquals("+2" to null, counts("added", 2uL, 0uL))
    }

    @Test
    fun statusWordsAndTheirTones() {
        assertEquals(listOf("changed", "added", "deleted", "new", "conflict", "submodule", "repository"), STATUS_WORDS)
        assertEquals(Tone.Added, toneOf("new"))
        assertEquals(Tone.Added, toneOf("added"))
        assertEquals(Tone.Removed, toneOf("deleted"))
        assertEquals(Tone.Removed, toneOf("conflict"))
        assertEquals(Tone.Plain, toneOf("changed"))
        assertEquals(Tone.Plain, toneOf("submodule"))
        assertEquals(Tone.Plain, toneOf("repository"))
    }

    @Test
    fun aRowsDescriptionIsPathWordAndCounts() {
        assertEquals("src/components/DocsNav.astro, changed, 3 lines added, 1 line removed", rowText(entry("src/components/DocsNav.astro", "changed", 3, 1)).description)
        assertEquals("tests/docs-nav.test.ts, new, 48 lines added", rowText(entry("tests/docs-nav.test.ts", "new", 48, 0)).description)
        assertEquals("src/styles/nav.css, deleted, 22 lines removed", rowText(entry("src/styles/nav.css", "deleted", 0, 22)).description)
        assertEquals("public/logo.png, changed", rowText(entry("public/logo.png", "changed")).description)
        assertEquals("a.txt, added, 1 line added", rowText(entry("a.txt", "added", 1, 0)).description)
        assertEquals("b.txt, changed, 0 lines added, 4 lines removed", rowText(entry("b.txt", "changed", 0, 4)).description)
    }

    // --- Title bar ---

    @Test
    fun theListsSubtitleCountsFiles() {
        val six = ChangesList.Listed(List(6) { entry("f$it", "changed") }, false)
        assertEquals("docs nav · website · 6 files", listSubtitle("docs nav", "website", six))
        assertEquals("docs nav · website · 1 file", listSubtitle("docs nav", "website", ChangesList.Listed(listOf(entry("a", "new")), false)))
        val capped = ChangesList.Listed(List(1000) { entry("f$it", "new") }, true)
        assertEquals("api tests · api · 1,000+ files", listSubtitle("api tests", "api", capped))
        assertEquals("docs nav · website", listSubtitle("docs nav", "website", ChangesList.Listed(emptyList(), false)))
        assertEquals("docs nav · website", listSubtitle("docs nav", "website", ChangesList.Reading))
        assertEquals("scratch", listSubtitle("scratch", "", ChangesList.NotARepo))
    }

    @Test
    fun theDiffsSubtitleIsFolderWordAndCounts() {
        assertEquals("src/components · changed · +3 −1", diffSubtitle(entry("src/components/DocsNav.astro", "changed", 3, 1)))
        assertEquals("changed · +11,204 −8,733", diffSubtitle(entry("package-lock.json", "changed", 11_204, 8_733)))
        assertEquals("scripts · changed", diffSubtitle(entry("scripts/run.sh", "changed")))
    }

    // --- The table of texts ---

    @Test
    fun theFixedTextsAreTheSpecs() {
        assertEquals("Changes", CHANGES)
        assertEquals("Show the files this agent changed", CHANGES_DESCRIPTION)
        assertEquals("Reading the changes…", READING)
        assertEquals("Not a git repository", NOT_A_REPO)
        assertEquals("No changes since the last commit", NONE)
        assertEquals("Committed changes do not show here.", NONE_DETAIL)
        assertEquals("Could not read the changes", FAILED)
        assertEquals("Retry", RETRY)
        assertEquals("Showing the first 1,000 changed files", CAPPED)
        assertEquals("No longer changed", NO_LONGER_CHANGED)
    }
}
