package im.angry.easyeuicc

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.*
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.ui.minimal.ProfileCardView
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.util.preferenceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** These fixtures live only in the test APK. No sample profiles are included in the app. */
@RunWith(AndroidJUnit4::class)
class NativeUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun aboutCertificateCopiesInlineWithoutOpeningAnotherPage() {
        val info = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val expected = info.signingInfo!!.apkContentsSigners.map {
            java.security.MessageDigest.getInstance("SHA-1").digest(it.toByteArray())
                .joinToString("") { byte -> "%02X".format(byte) }
        }.distinct().filterNot { it == "D1C0F48B370E74D4EA4770ED4C3CD70A3198D31F" }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SCREEN, "about")).use { scenario ->
            waitFor(R.string.minimal_ara_m)
            scenario.onActivity { activity ->
                val values = views(activity.window.decorView).filterIsInstance<TextView>()
                    .filter { it.id == R.id.about_certificate_hash }
                assertEquals("All non-community signing certificates must be visible", expected.size, values.size)
                values.zip(expected).forEach { (value, hash) ->
                    val text = value.text as android.text.Spanned
                    assertEquals(hash, text.toString().substringBefore('\u00A0'))
                    assertEquals("All characters and the trailing icon remain visible", text.length,
                        value.layout.getLineEnd(value.lineCount - 1))
                    repeat(value.lineCount) { line -> assertEquals(0, value.layout.getEllipsisCount(line)) }
                    val span = text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java).single()
                    assertEquals("Only the trailing icon is clickable", text.length - 1, text.getSpanStart(span))
                    assertFalse("The fingerprint card must not navigate", (value.parent as View).isClickable)
                    span.onClick(value)
                    assertEquals("Copy must omit display line breaks", hash,
                        activity.getSystemService(android.content.ClipboardManager::class.java)
                            .primaryClip!!.getItemAt(0).text.toString())
                }
                assertTrue(activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                val registered = context.packageManager.getPackageInfo(context.packageName,
                    android.content.pm.PackageManager.GET_ACTIVITIES).activities.orEmpty()
                assertFalse("The retired viewer must not remain registered", registered.any {
                    it.name.endsWith(".TextContentActivity")
                })
            }
            capture("about-inline-certificate")
        }
    }

    @Test fun aidListAddsDeletesOnlyByButtonAndRestoresDefaults() {
        val prefs = context.preferenceRepository
        val original = runBlocking { prefs.isdrAidListFlow.first() }
        val standard = im.angry.openeuicc.util.EUICC_DEFAULT_ISDR_AID
        val custom = "A0000005591010FFFFFFFF8900001000"
        try {
            runBlocking { prefs.isdrAidListFlow.updatePreference("# Hidden comment\n$standard") }
            ActivityScenario.launch<IsdrAidListActivity>(Intent(context, IsdrAidListActivity::class.java)).use { scenario ->
                fun ready() {
                    repeat(100) {
                        var enabled = false
                        scenario.onActivity { enabled = it.findViewById<View>(R.id.aid_add).isEnabled }
                        if (enabled) { instrumentation.waitForIdleSync(); return }
                        Thread.sleep(50)
                    }
                    fail("AID list did not finish loading or saving")
                }
                fun submit(value: String) {
                    scenario.onActivity {
                        it.findViewById<android.widget.EditText>(R.id.isdr_aid_list_editor).setText(value)
                        it.findViewById<View>(R.id.aid_add).performClick()
                    }
                }
                ready()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.aid_entries)
                    assertEquals(2, list.adapter!!.itemCount)
                    val input = activity.findViewById<android.widget.EditText>(R.id.isdr_aid_list_editor)
                    assertTrue("Single-line mode must retain the taller input", input.height >= 48 * activity.resources.displayMetrics.density)
                    val addBounds = android.graphics.Rect()
                    val cardBounds = android.graphics.Rect()
                    activity.findViewById<View>(R.id.aid_add).getGlobalVisibleRect(addBounds)
                    list.findViewHolderForAdapterPosition(0)!!.itemView.getGlobalVisibleRect(cardBounds)
                    assertEquals("The add circle aligns with the cards' right edge", cardBounds.right, addBounds.right)
                    assertFalse(views(list).filterIsInstance<TextView>().any { it.text.contains("#") })
                }
                submit("a0 00 00 05 59 10 10 ff ff ff ff 89 00 00 10 00")
                capture("aid-after-add")
                assertEquals("Adding must persist", "$standard\n$custom", runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(10000) { prefs.isdrAidListFlow.first { it == "$standard\n$custom" } }
                        ?: prefs.isdrAidListFlow.first()
                })
                ready()
                submit(custom.lowercase())
                scenario.onActivity {
                    assertNotNull(it.findViewById<android.widget.EditText>(R.id.isdr_aid_list_editor).error)
                }
                submit("invalid-aid")
                scenario.onActivity {
                    assertNotNull(it.findViewById<android.widget.EditText>(R.id.isdr_aid_list_editor).error)
                }
                assertEquals("$standard\n$custom", runBlocking { prefs.isdrAidListFlow.first() })
                scenario.recreate()
                ready()
                val bounds = android.graphics.Rect()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.aid_entries)
                    assertEquals("Adding must survive reopening", 3, list.adapter!!.itemCount)
                    val defaultCard = list.findViewHolderForAdapterPosition(0)!!.itemView
                    assertFalse("The standard AID cannot be removed", defaultCard.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS, null))
                    val card = list.findViewHolderForAdapterPosition(1)!!.itemView
                    assertFalse(defaultCard.findViewById<View>(R.id.aid_delete).isEnabled)
                    val value = card.findViewById<TextView>(R.id.aid_value)
                    assertEquals("The value has no trailing copy control", custom, value.text.toString())
                    assertNull("The value is plain text", value.movementMethod)
                    assertTrue(views(card).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.aid_custom) })
                    card.getGlobalVisibleRect(bounds)
                }
                capture("aid-before-swipe")
                // Exercise a real left swipe on the isolated test activity.
                val start = android.os.SystemClock.uptimeMillis()
                fun touch(action: Int, x: Float, offset: Long) {
                    val event = android.view.MotionEvent.obtain(start, start + offset, action, x,
                        bounds.centerY().toFloat(), 0)
                    instrumentation.sendPointerSync(event)
                    event.recycle()
                }
                touch(android.view.MotionEvent.ACTION_DOWN, bounds.right - 60f, 0)
                for (step in 1..10) touch(android.view.MotionEvent.ACTION_MOVE,
                    bounds.right - 60f - bounds.width() * .08f * step, step * 30L)
                touch(android.view.MotionEvent.ACTION_UP, bounds.left + 10f, 330)
                instrumentation.waitForIdleSync()
                Thread.sleep(400)
                assertEquals("Swiping must not remove an AID", "$standard\n$custom",
                    runBlocking { prefs.isdrAidListFlow.first() })
                scenario.onActivity { activity ->
                    val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.aid_entries)
                    list.findViewHolderForAdapterPosition(1)!!.itemView.findViewById<View>(R.id.aid_delete).performClick()
                }
                assertEquals("Deletion must persist", standard, runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(10000) { prefs.isdrAidListFlow.first { it == standard } }
                        ?: prefs.isdrAidListFlow.first()
                })
                ready()
                scenario.onActivity { it.findViewById<View>(R.id.reset).performClick() }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertFalse("Reset must not open a confirmation dialog",
                        android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap { views(it) }
                            .any { it.id == android.R.id.button1 && it.isShown })
                }
                val restored = runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(10000) { prefs.isdrAidListFlow.first { it.contains("#") } }
                        ?: prefs.isdrAidListFlow.first()
                }
                assertTrue("One tap must restore the defaults: $restored", restored.contains("#"))
                ready()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.aid_entries)
                    assertTrue(list.adapter!!.itemCount > 1)
                    assertTrue("Known AID shows its platform", views(list).filterIsInstance<TextView>().any { it.text == "Xesim" })
                    assertFalse("Restored comments stay hidden", views(list).filterIsInstance<TextView>().any { it.text.contains("#") })
                }
                capture("aid-list-cards")
                scenario.onActivity { activity ->
                    val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.aid_entries)
                    list.scrollToPosition(list.adapter!!.itemCount - 1)
                }
                instrumentation.waitForIdleSync()
                capture("aid-list-footer")
                scenario.onActivity { activity ->
                    val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.aid_entries)
                    val footer = list.findViewHolderForAdapterPosition(list.adapter!!.itemCount - 1)!!.itemView
                    val reset = footer.findViewById<com.google.android.material.button.MaterialButton>(R.id.reset)
                    assertTrue("Reset is part of the scrollable list", reset.isShown)
                    assertEquals(activity.getColor(R.color.minimal_red), reset.currentTextColor)
                }
            }
        } finally { runBlocking { prefs.isdrAidListFlow.updatePreference(original) } }
    }

    @Test fun defaultSwitchSettingsAreEnabledAndExplicitChoicesSurvive() {
        val prefs = context.preferenceRepository
        val safeguard = runBlocking { prefs.disableSafeguardFlow.first() }
        val notifications = runBlocking { prefs.notificationSwitchFlow.first() }
        try {
            runBlocking {
                prefs.disableSafeguardFlow.removePreference()
                prefs.notificationSwitchFlow.removePreference()
                assertTrue("New installations allow operations on active profiles", prefs.disableSafeguardFlow.first())
                assertTrue("Switch notifications are enabled by default", prefs.notificationSwitchFlow.first())
            }
            ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SCREEN, "advanced")).use { scenario ->
                waitFor(R.string.pref_advanced_disable_safeguard_removable_esim)
                scenario.onActivity { activity ->
                    assertTrue(views(activity.window.decorView)
                        .filterIsInstance<im.angry.openeuicc.ui.minimal.MinimalSwitch>().single {
                            it.contentDescription == activity.getText(R.string.pref_advanced_disable_safeguard_removable_esim)
                        }.isChecked)
                }
            }
            ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
                waitFor(R.string.pref_notifications_switch)
                scenario.onActivity { activity ->
                    assertTrue(views(activity.window.decorView)
                        .filterIsInstance<im.angry.openeuicc.ui.minimal.MinimalSwitch>().single {
                            it.contentDescription == activity.getText(R.string.pref_notifications_switch)
                        }.isChecked)
                }
            }
            runBlocking {
                prefs.disableSafeguardFlow.updatePreference(false)
                prefs.notificationSwitchFlow.updatePreference(false)
                val reopened = im.angry.openeuicc.util.PreferenceRepository(context)
                assertFalse("Do not override a saved safeguard choice", reopened.disableSafeguardFlow.first())
                assertFalse("Do not override a saved notification choice", reopened.notificationSwitchFlow.first())
            }
        } finally {
            runBlocking {
                prefs.disableSafeguardFlow.updatePreference(safeguard)
                prefs.notificationSwitchFlow.updatePreference(notifications)
            }
        }
    }

    @Test fun aidAndLogsUseTheirNewSettingsSections() {
        val prefs = context.preferenceRepository
        val original = runBlocking { prefs.developerOptionsEnabledFlow.first() }
        try {
            runBlocking { prefs.developerOptionsEnabledFlow.updatePreference(false) }
            ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SCREEN, "advanced")).use { scenario ->
                waitFor(R.string.pref_developer_isdr_aid_list)
                scenario.onActivity { activity ->
                    assertFalse("Verbose logging belongs in developer options",
                        views(activity.window.decorView).filterIsInstance<TextView>().any {
                            it.text.toString() == activity.getString(R.string.pref_advanced_verbose_logging)
                        })
                    assertFalse("Logs no longer belong to advanced settings",
                        views(activity.window.decorView).filterIsInstance<TextView>().any {
                            it.text.toString() == activity.getString(R.string.pref_advanced_logs)
                        })
                }
                capture("advanced-aid-entry")
                click(R.string.pref_developer_isdr_aid_list)
                waitFor(R.string.isdr_aid_list)
                instrumentation.runOnMainSync {
                    assertTrue("AID editing is available without developer mode", resumed() is IsdrAidListActivity)
                    resumed().finish()
                }
                instrumentation.waitForIdleSync()
            }
            runBlocking { prefs.developerOptionsEnabledFlow.updatePreference(true) }
            ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SCREEN, "developer")).use { scenario ->
                waitFor(R.string.pref_advanced_logs)
                assertVisible(R.string.pref_advanced_verbose_logging)
                scenario.onActivity { activity ->
                    assertFalse("AID editing no longer belongs to developer options",
                        views(activity.window.decorView).filterIsInstance<TextView>().any {
                            it.text.toString() == activity.getString(R.string.pref_developer_isdr_aid_list)
                        })
                }
                capture("developer-logs-entry")
                click(R.string.pref_advanced_logs)
                waitFor(R.string.pref_advanced_logs)
                instrumentation.runOnMainSync {
                    assertTrue("The moved logs entry opens the log viewer", resumed() is LogsActivity)
                    resumed().finish()
                }
                instrumentation.waitForIdleSync()
            }
        } finally { runBlocking { prefs.developerOptionsEnabledFlow.updatePreference(original) } }
    }

    @Test fun settingsNavigationAndNativeCards() {
        instrumentation.runOnMainSync {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags("zh-CN")
            } else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            waitFor(R.string.pref_notifications_download)
            capture("settings")
            instrumentation.runOnMainSync {
                assertNull(find(R.string.pref_advanced))
                assertNull(find(R.string.minimal_about))
                assertNull(find(R.string.pref_developer))
            }
        }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SCREEN, "advanced")).use {
            waitFor(R.string.pref_advanced_language)
            capture("advanced")
            verifyLanguageMenu()
            click(R.string.pref_advanced_http_proxy)
            waitFor(R.string.minimal_save)
            capture("proxy")
            instrumentation.runOnMainSync { (resumed() as SettingsActivity).onBackPressedDispatcher.onBackPressed() }
            waitFor(R.string.pref_advanced_language)
            assertVisible(R.string.pref_developer_isdr_aid_list)
        }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SCREEN, "about")).use {
            waitFor(R.string.pref_info_app_version)
            capture("about")
            repeat(7) { click(R.string.pref_info_app_version) }
            runBlocking {
                kotlinx.coroutines.withTimeout(5000) { context.preferenceRepository.developerOptionsEnabledFlow.first { it } }
            }
        }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SCREEN, "developer")).use { scenario ->
            waitFor(R.string.pref_developer_es10x_mss)
            capture("developer")
            verifyMssMenu()
            assertVisible(R.string.pref_advanced_logs)
            // Render the actual native profile component at phone width, including long metadata.
            scenario.onActivity { activity ->
                val ui = MinimalUi(activity)
                val page = ui.page()
                page.addView(ui.text("EasyEUICC · UI test fixture", 20f))
                listOf("Travel eSIM" to "US", "日本のデータプラン" to "JP", "工作号卡" to "").forEach { (name, country) ->
                    page.addView(ProfileCardView(activity).apply {
                        this.name.text = name
                        provider.text = "Example operator"
                        iccid.text = "89860012345678901234"
                        profileClass.visibility = View.GONE
                        toggle.isChecked = country == "US"
                        canDelete = !toggle.isChecked
                        setCountry(country)
                        layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                            topMargin = ui.dp(7); bottomMargin = ui.dp(7)
                        }
                    })
                }
                val container = activity.requireViewById<ViewGroup>(R.id.settings_container)
                container.removeAllViews()
                container.addView(ui.scroll(page))
            }
            capture("profile-component-fixture")
            scenario.onActivity { activity ->
                ProfileRenameFragment.newInstance(0, 0, EuiccChannel.SecureElementId.DEFAULT, "test-iccid", "Travel eSIM")
                    .show(activity.supportFragmentManager, ProfileRenameFragment.TAG)
            }
            waitFor(R.string.profile_rename)
            capture("edit-profile")
        }
        ActivityScenario.launch<LogsActivity>(Intent(context, LogsActivity::class.java)).use {
            waitFor(R.string.pref_advanced_logs)
            capture("logs")
        }
    }

    private fun verifyMssMenu() {
        val prefs = context.preferenceRepository
        val original = runBlocking { prefs.es10xMssFlow.first() }
        val values = context.resources.getStringArray(R.array.pref_developer_es10x_entry_values)
        val target = values.first { it.toInt() != original }.toInt()
        val targetLabel = context.resources.getStringArray(R.array.pref_developer_es10x_entry_keys)[values.indexOf(target.toString())]
        val fieldBounds = android.graphics.Rect()
        instrumentation.runOnMainSync {
            val field = resumed().findViewById<View>(im.angry.easyeuicc.R.id.es10x_mss_selector)
            val titleBounds = android.graphics.Rect()
            requireNotNull(find(R.string.pref_developer_es10x_mss)).getGlobalVisibleRect(titleBounds)
            field.getGlobalVisibleRect(fieldBounds)
            assertEquals("MSS selector must use its compact width", MinimalUi(resumed()).dp(110), fieldBounds.width())
            assertTrue("MSS title and selector must share one row",
                kotlin.math.abs(titleBounds.centerY() - fieldBounds.centerY()) <= 2)
            assertTrue("MSS selector must follow its title", fieldBounds.left > titleBounds.right)
            val label = views(field).filterIsInstance<TextView>().single()
            assertEquals("The selected MSS label must remain complete", 0, label.layout.getEllipsisCount(0))
            field.performClick()
        }
        capture("es10x-menu")
        instrumentation.runOnMainSync {
            val labels = resumed().resources.getStringArray(R.array.pref_developer_es10x_entry_keys)
            val menuTexts = android.view.inspector.WindowInspector.getGlobalWindowViews()
                .filter { it.hasWindowFocus() }
                .flatMap { views(it) }.filterIsInstance<TextView>()
            labels.forEach { label ->
                val item = menuTexts.first { it.isShown && it.text.toString() == label }
                assertEquals("MSS options must fit on one line", 1, item.lineCount)
                assertEquals("Single-line MSS options must keep compact vertical spacing",
                    MinimalUi(resumed()).dp(40), item.height)
                assertEquals("MSS options must display their full value", 0, item.layout.getEllipsisCount(0))
            }
        }
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        fun nodes(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let { nodes(it) }.orEmpty() }
        val menu = nodes(root).first { it.className?.toString()?.endsWith("ListView") == true }
        val menuBounds = android.graphics.Rect().also { menu.getBoundsInScreen(it) }
        assertEquals("MSS menu must match the selector width", fieldBounds.width(), menuBounds.width())
        assertEquals("MSS menu must align with the selector", fieldBounds.left, menuBounds.left)
        assertTrue("MSS menu must open below the selector", menuBounds.top >= fieldBounds.bottom)
        try {
            assertTrue(nodes(menu).first { it.text?.toString() == targetLabel }
                .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            capture("es10x-selected")
            instrumentation.runOnMainSync {
                val field = resumed().findViewById<View>(im.angry.easyeuicc.R.id.es10x_mss_selector)
                assertTrue("Choosing MSS $target must update the field: ${field.contentDescription}",
                    field.contentDescription.toString().endsWith(": $targetLabel"))
                val label = views(field).filterIsInstance<TextView>().single()
                assertEquals("Changing MSS must keep the full label visible", 0, label.layout.getEllipsisCount(0))
            }
            runBlocking {
                kotlinx.coroutines.withTimeout(5000) { prefs.es10xMssFlow.first { it == target } }
            }
            instrumentation.runOnMainSync { resumed().recreate() }
            instrumentation.waitForIdleSync()
            waitFor(R.string.pref_developer_es10x_mss)
            instrumentation.runOnMainSync {
                val field = resumed().findViewById<View>(im.angry.easyeuicc.R.id.es10x_mss_selector)
                assertTrue("The chosen MSS value must survive reopening the page",
                    field.contentDescription.toString().endsWith(": $targetLabel"))
            }
        } finally {
            runBlocking { prefs.es10xMssFlow.updatePreference(original) }
        }
    }

    private fun verifyLanguageMenu() {
        waitForLanguageSelector("zh-CN")
        val fieldBounds = android.graphics.Rect()
        instrumentation.runOnMainSync {
            val field = resumed().findViewById<View>(im.angry.easyeuicc.R.id.language_selector)
            val title = requireNotNull(find(R.string.pref_advanced_language))
            val titleBounds = android.graphics.Rect()
            title.getGlobalVisibleRect(titleBounds)
            field.getGlobalVisibleRect(fieldBounds)
            assertTrue("Language title and selector must share one row",
                kotlin.math.abs(titleBounds.centerY() - fieldBounds.centerY()) <= 2)
            assertTrue("The selector must follow the label", fieldBounds.left > titleBounds.right)
            field.performClick()
        }
        capture("language-menu")
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        fun nodes(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let { nodes(it) }.orEmpty() }
        val list = nodes(root).first { it.className?.toString()?.endsWith("ListView") == true }
        val menuBounds = android.graphics.Rect().also { list.getBoundsInScreen(it) }
        assertEquals("Language options must use the same compact row height",
            (40 * context.resources.displayMetrics.density).toInt() * 5, menuBounds.height())
        assertEquals("Language menu must match the selector width", fieldBounds.width(), menuBounds.width())
        assertEquals("Language menu must align with the selector", fieldBounds.left, menuBounds.left)
        assertTrue("Language menu must open below its field", menuBounds.top >= fieldBounds.bottom)
        assertTrue(root.findAccessibilityNodeInfosByText("English").first { it.text?.toString() == "English" }
            .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        waitForLanguageSelector("en-US")
        instrumentation.runOnMainSync {
            assertEquals("Selecting a language must still update the app locale", "en-US",
                AppCompatDelegate.getApplicationLocales().toLanguageTags())
            resumed().findViewById<View>(im.angry.easyeuicc.R.id.language_selector).performClick()
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(350)
        val englishRoot = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        assertTrue(englishRoot.findAccessibilityNodeInfosByText("简体中文").first { it.text?.toString() == "简体中文" }
            .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        waitForLanguageSelector("zh-CN")
    }

    private fun waitForLanguageSelector(tag: String) {
        repeat(50) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = runCatching {
                    val activity = resumed()
                    val field = activity.findViewById<View>(im.angry.easyeuicc.R.id.language_selector)
                    activity.resources.configuration.locales[0].toLanguageTag() == tag &&
                        field?.isShown == true && field.isLaidOut && field.hasWindowFocus()
                }.getOrDefault(false)
            }
            if (ready) { instrumentation.waitForIdleSync(); return }
            Thread.sleep(100)
        }
        fail("Language selector did not finish rendering locale $tag")
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun resumed(): Activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
    private fun find(id: Int): TextView? {
        val activity = resumed()
        val text = activity.getString(id)
        val roots = listOf(activity.window.decorView) + (activity as? androidx.fragment.app.FragmentActivity)
            ?.supportFragmentManager?.fragments.orEmpty().filterIsInstance<androidx.fragment.app.DialogFragment>()
            .mapNotNull { it.dialog?.window?.decorView }
        return roots.flatMap { views(it) }.filterIsInstance<TextView>().firstOrNull { it.text.toString() == text && it.isShown }
    }
    private fun waitFor(id: Int) {
        repeat(50) {
            var found = false
            instrumentation.runOnMainSync { found = runCatching { find(id) != null }.getOrDefault(false) }
            if (found) { instrumentation.waitForIdleSync(); return }
            Thread.sleep(100)
        }
        fail("UI did not show string resource $id")
    }
    private fun assertVisible(id: Int) { instrumentation.runOnMainSync { assertNotNull(find(id)) } }
    private fun click(id: Int) {
        instrumentation.runOnMainSync {
            var view: View = requireNotNull(find(id))
            while (!view.isClickable) view = view.parent as View
            view.performClick()
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(350)
        val dir = File(context.getExternalFilesDir(null), "ui-verification").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
