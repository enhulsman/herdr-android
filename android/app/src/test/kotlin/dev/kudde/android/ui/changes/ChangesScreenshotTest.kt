package dev.kudde.android.ui.changes

import androidx.compose.foundation.lazy.LazyListState
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.ChangesDiff
import uniffi.kudde_mobile.ChangesEntry
import uniffi.kudde_mobile.ChangesLine
import uniffi.kudde_mobile.ChangesList
import uniffi.kudde_mobile.ChangesView

// The Changes screen with invented repositories, in paper and graphite, held
// to the signed-off renders 14-23 in design/renders/screens.html (task 4.2).
// Goldens: src/test/snapshots/changes-<scene>-<theme>.png.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChangesScreenshotTest {
    private fun e(path: String, kind: String, added: Long? = null, removed: Long? = null, opens: Boolean = true) =
        ChangesEntry(path, kind, added?.toULong(), removed?.toULong(), opens)

    private fun ls(vararg t: String) = t.map { ChangesLine(it, false) }

    private val six = ChangesList.Listed(
        listOf(
            e("src/components/DocsNav.astro", "changed", 3, 1),
            e("src/layouts/Docs.astro", "changed", 12, 4),
            e("tests/docs-nav.test.ts", "new", 48, 0),
            e("src/styles/nav.css", "deleted", 0, 22),
            e("public/logo.png", "changed"),
            e("vendor/theme", "submodule"),
        ),
        false,
    )

    /** A diff of [headers] file header lines (not drawn) and [body]. */
    private fun lines(headers: Int, body: List<ChangesLine>, tooLarge: Boolean = false) =
        ChangesDiff.Lines(List(headers) { ChangesLine(if (it == 0) "diff --git a/x b/x" else "index 1..2 100644", false) } + body, headers.toUInt(), tooLarge)

    private val docsNav = ls(
        "@@ -38,9 +38,11 @@ const { active } = Astro.props;",
        " ---",
        " ",
        " <nav class=\"docs-nav\" aria-label=\"Docs\">",
        "-  <a href=\"/guide\">Guide</a>",
        "+  <a href=\"/guide\" aria-current={active === \"guide\" ? \"page\" : undefined}>Guide</a>",
        "+  <a href=\"/api\" aria-current={active === \"api\" ? \"page\" : undefined}>API</a>",
        "+  <a href=\"/faq\" aria-current={active === \"faq\" ? \"page\" : undefined}>FAQ</a>",
        " </nav>",
        " ",
        " <style>",
        "@@ -71,4 +73,4 @@ .docs-nav a {",
        "   padding: 6px 10px;",
        "   border-radius: 6px;",
        " }",
        "-.docs-nav a.active {",
        "+.docs-nav a[aria-current=\"page\"] {",
        "   color: var(--accent);",
        " }",
        " </style>",
        "\\ No newline at end of file",
    )

    /** [from]: the first item shown, to bring the end of a long list or diff into the shot. */
    private fun capture(scene: String, view: ChangesView, agent: String = "docs nav", space: String = "website", whole: Boolean = false, themes: List<Boolean> = listOf(false, true), from: Int = 0) {
        for (dark in themes) {
            captureRoboImage("src/test/snapshots/changes-$scene-${if (dark) "graphite" else "paper"}.png") {
                KuddeTheme(dark = dark) {
                    val at = LazyListState(firstVisibleItemIndex = from)
                    ChangesScreen(agent, space, view, whole, onBack = {}, onOpen = {}, onRetry = {}, onWhole = {}, listScroll = at, diffScroll = at)
                }
            }
        }
    }

    private fun diff(list: ChangesList, path: String, d: ChangesDiff) = ChangesView(list, path, d)

    @Test
    fun list() = capture("list", ChangesView(six, null, null))

    @Test
    fun diffWrapped() = capture("diff", diff(six, "src/components/DocsNav.astro", lines(4, docsNav)))

    @Test
    fun longDiff() {
        val body = mutableListOf(ChangesLine("@@ -1,10000 +1,10118 @@", false))
        var i = 0
        while (body.size < 20_118) {
            val v = "${7 + i % 5}.${i * 3 % 10}.${i % 7}"
            val w = "${8 + i % 5}.${i * 7 % 10}.${i % 4}"
            body += ChangesLine("     \"node_modules/pkg-${'a' + i % 26}$i\": {", false)
            body += ChangesLine("-      \"version\": \"$v\",", false)
            body += ChangesLine("+      \"version\": \"$w\",", false)
            i++
        }
        val list = ChangesList.Listed(listOf(e("package-lock.json", "changed", 11_204, 8_733)), false)
        capture("long", diff(list, "package-lock.json", lines(4, body.take(20_118))), from = 1975)
    }

    @Test
    fun wholeDiffShown() {
        val body = listOf(ChangesLine("@@ -1,2001 +1,2001 @@", false)) + List(2001) { ChangesLine("+line $it", false) }
        val list = ChangesList.Listed(listOf(e("big.txt", "changed", 2001, 0)), false)
        capture("whole", diff(list, "big.txt", lines(4, body)), whole = true)
    }

    @Test
    fun loading() = capture("loading", ChangesView(ChangesList.Reading, null, null))

    @Test
    fun nothingUncommitted() = capture("none", ChangesView(ChangesList.Listed(emptyList(), false), null, null))

    @Test
    fun notARepository() = capture("norepo", ChangesView(ChangesList.NotARepo, null, null), agent = "scratch", space = "")

    @Test
    fun failed() = capture("error", ChangesView(ChangesList.Failed("fatal: detected dubious ownership in repository at '/home/dev/src/website'"), null, null))

    @Test
    fun otherKindsAndTheCap() {
        val entries = listOf(
            e("src/client.ts", "conflict", 14, 6),
            e("scripts/run.sh", "changed"),
            e("fixtures/empty.txt", "new"),
            e("upstream-sdk/", "repository", opens = false),
        ) + List(996) { e("out/report-%04d.json".format(it + 1), "new", 212, 0) }
        capture("kinds", ChangesView(ChangesList.Listed(entries, true), null, null), agent = "api tests", space = "api")
        capture("cap", ChangesView(ChangesList.Listed(entries, true), null, null), agent = "api tests", space = "api", from = 990)
    }

    private val kinds = ChangesList.Listed(
        listOf(
            e("public/logo.png", "changed"),
            e("vendor/theme", "submodule"),
            e("fixtures/notes.txt", "changed", 2, 2),
            e("fixtures/empty.txt", "new"),
            e("scripts/run.sh", "changed"),
            e("src/client.ts", "conflict", 5, 1),
            e("out/huge.log", "new"),
            e("src/app.ts", "new", 3, 0),
        ),
        false,
    )

    @Test
    fun binaryFile() = capture("binary", diff(kinds, "public/logo.png", lines(2, ls("Binary files a/public/logo.png and b/public/logo.png differ"))))

    @Test
    fun submodule() = capture(
        "submodule",
        diff(
            kinds, "vendor/theme",
            lines(4, ls("@@ -1 +1 @@", "-Subproject commit 3f2a9c1d0e5b6a7c8d9e0f1a2b3c4d5e6f7a8b9c", "+Subproject commit 8b04d7e1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7")),
        ),
    )

    @Test
    fun lineEndings() = capture(
        "crlf",
        diff(kinds, "fixtures/notes.txt", lines(4, ls("@@ -1,2 +1,2 @@", "-first line", "-second line", "+first line\r", "+second line\r"))),
    )

    @Test
    fun newFile() = capture("new", diff(kinds, "src/app.ts", lines(5, ls("@@ -0,0 +1,3 @@", "+export function start() {", "+\treturn run(\"app\");", "+}"))))

    @Test
    fun emptyFile() = capture(
        "empty",
        diff(kinds, "fixtures/empty.txt", ChangesDiff.Lines(ls("diff --git a/fixtures/empty.txt b/fixtures/empty.txt", "new file mode 100644", "index 0000000..e69de29"), 3u, false)),
    )

    @Test
    fun modeOnly() = capture(
        "mode",
        diff(kinds, "scripts/run.sh", ChangesDiff.Lines(ls("diff --git a/scripts/run.sh b/scripts/run.sh", "old mode 100644", "new mode 100755"), 3u, false)),
    )

    @Test
    fun conflict() = capture(
        "conflict",
        diff(kinds, "src/client.ts", lines(4, ls("@@ -10,3 +10,7 @@ export class Client {", "   retries = 3;", "+<<<<<<< HEAD", "+  timeout = 10_000;", "+=======", "+  timeout = 30_000;", "+>>>>>>> retry-backoff", "-  timeout = 5_000;"))),
    )

    @Test
    fun tooLarge() = capture(
        "too-large",
        diff(kinds, "out/huge.log", lines(5, ls("@@ -0,0 +1,900000 @@") + List(60) { ChangesLine("+2026-11-16T09:12:%02dZ start worker %d".format(it % 60, it), false) }, tooLarge = true)),
    )

    @Test
    fun noLongerChanged() = capture("reverted", diff(kinds, "src/client.ts", ChangesDiff.NoLongerChanged))

    @Test
    fun diffReading() = capture("diff-loading", diff(kinds, "src/client.ts", ChangesDiff.Reading))

    @Test
    fun aCutLine() = capture(
        "cut",
        diff(kinds, "src/app.ts", ChangesDiff.Lines(ls("@@ -0,0 +1,2 @@") + ChangesLine("+const data = \"" + "x".repeat(3985), true) + ls("+export default data;"), 0u, false)),
        themes = listOf(true),
        from = 70,
    )
}
