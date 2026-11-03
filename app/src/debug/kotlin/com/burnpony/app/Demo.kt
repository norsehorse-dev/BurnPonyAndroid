//
// Demo.kt (debug source set only)
// Store screenshot mode. tools/play-screenshots.sh launches MainActivity
// with two extras:
//
//   demo_lang    en, de, es, fr, it, ja, pt-BR, ru, zh-Hans
//   demo_screen  1_compose, 2_double_bottom, 3_options, 4_share, 5_sent
//
// It switches the in-app language the same way the picker does, marks the
// walkthrough as done, seeds localized Sent rows, prefills the compose form
// for the requested screen and returns the tab route to open. Nothing here
// touches the network: the share screen is built from a fixed fake link and
// Sent refresh is skipped while demo mode is on.
//
// The release source set has a stub with the same public API that does
// nothing, so none of this ships.
//

package com.burnpony.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.burnpony.app.data.CreatedLink
import com.burnpony.app.data.CreatedNote
import com.burnpony.app.data.NoteKind
import com.burnpony.app.data.NoteRepository
import com.burnpony.app.data.api.BurnPonyApi
import com.burnpony.app.data.db.SentNoteEntity
import com.burnpony.app.i18n.LanguageManager
import com.burnpony.app.i18n.SupportedLanguage
import com.burnpony.app.ui.compose.ComposeMode
import com.burnpony.app.ui.compose.ComposeUiState
import com.burnpony.app.ui.compose.DecoyCompartmentUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object Demo {

    const val EXTRA_LANG = "demo_lang"
    const val EXTRA_SCREEN = "demo_screen"

    /** True once a launch carried demo extras; stays on for the process. */
    var active: Boolean = false
        private set

    private var lang: String = "en"
    private var screen: String? = null

    private const val ID_PREFIX = "demo"
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    // Fixed so the QR code and link are identical on every run.
    private const val FAKE_NOTE_ID = "n3jD46K0zP2gAFoydVE7zB"
    private const val FAKE_KEY = "AUpx71ApoFF-CzfCUN9KISvQ8VgDbSmsWYrIJRd1P9w"
    private const val FAKE_LINK = "https://burnpony.app/n/$FAKE_NOTE_ID#$FAKE_KEY"

    /**
     * Reads the demo extras. Returns the tab route MainActivity should show
     * ("compose" or "sent"), or null when the intent is not a demo launch.
     */
    fun handle(activity: AppCompatActivity, intent: Intent?): String? {
        val langExtra = intent?.getStringExtra(EXTRA_LANG)
        val screenExtra = intent?.getStringExtra(EXTRA_SCREEN)
        if (langExtra == null && screenExtra == null) return null
        active = true
        if (langExtra != null) lang = langExtra
        screen = screenExtra

        val container = (activity.application as BurnPonyApp).container
        container.settings.setOnboardingCompleted(true)

        // Same call as the in-app picker. AppCompat recreates the Activity
        // once; on that second onCreate the language already matches, so
        // this is a no-op and nothing loops.
        val target = SupportedLanguage.resolve(lang) ?: SupportedLanguage.EN
        if (LanguageManager.current() != target) {
            LanguageManager.setLanguage(target)
        }

        seed(container)

        return when (screenExtra) {
            "5_sent" -> "sent"
            null -> null
            else -> "compose"
        }
    }

    /** Compose form for the requested screen, or null to use the normal fresh form. */
    fun composeOverride(base: ComposeUiState): ComposeUiState? {
        if (!active) return null
        val t = text()
        val common = base.copy(
            viewsAllowed = 1,
            expirySeconds = 86_400,
            autoHideSeconds = 0,
            noSelect = false,
            receiptEnabled = false,
            instantOpen = false,
            saveCopy = false,
            availableAtMillis = null,
            usePassword = false,
            label = "",
        )
        return when (screen) {
            "1_compose" -> common.copy(mode = ComposeMode.SIMPLE, text = t.sample)
            "2_double_bottom" -> common.copy(
                mode = ComposeMode.DECOY,
                compartments = listOf(
                    DecoyCompartmentUi(text = t.decoy, password = "summer-sky-1"),
                    DecoyCompartmentUi(text = t.real, password = "north-gate-77"),
                ),
            )
            "3_options" -> common.copy(
                mode = ComposeMode.SIMPLE,
                text = t.sample,
                label = t.labels[0],
                receiptEnabled = true,
                showingOptions = true,
            )
            "4_share" -> common.copy(
                mode = ComposeMode.SIMPLE,
                text = t.sample,
                created = CreatedNote(
                    id = FAKE_NOTE_ID,
                    kind = NoteKind.SIMPLE,
                    links = listOf(CreatedLink(keyIndex = null, url = FAKE_LINK, hasPassword = false)),
                    expiresAtEpochMs = System.currentTimeMillis() + DAY,
                ),
            )
            else -> null
        }
    }

    // ── Seed ────────────────────────────────────────────────────────────

    /**
     * Replaces earlier demo rows with five fresh ones. Runs blocking on IO so
     * the rows exist before the first frame; debug-only, a handful of rows.
     */
    private fun seed(container: AppContainer) {
        val dao = container.database.sentNoteDao()
        val labels = text().labels
        val now = System.currentTimeMillis()
        val base = BurnPonyApi.DEFAULT_BASE_URL

        fun row(
            n: Int,
            createdAgo: Long,
            expiresIn: Long,
            views: Pair<Int, Int>,
            kind: String,
            receipt: Boolean = false,
            receiptTimes: List<Long> = emptyList(),
            password: Boolean = false,
            burned: Boolean = false,
            gone: Boolean = false,
            savedCopy: String? = null,
        ) = SentNoteEntity(
            id = "$ID_PREFIX$n${FAKE_NOTE_ID.drop(5)}",
            managementToken = "demo-token-$n",
            serverBaseUrl = base,
            createdAtEpochMs = now - createdAgo,
            expiresAtEpochMs = now - createdAgo + expiresIn,
            viewsAllowed = views.second,
            viewsUsed = views.first,
            receiptEnabled = receipt,
            hasPassword = password,
            autoHideSeconds = 0,
            burned = burned,
            goneFromServer = gone,
            receiptTimesJson = receiptTimes.joinToString(",", "[", "]"),
            pushRegistered = true,
            label = labels[n],
            savedCopy = savedCopy,
            noteKind = kind,
        )

        val rows = listOf(
            // Active, not opened yet, receipt on.
            row(0, 12 * MINUTE, DAY, 0 to 1, NoteRepository.NOTE_KIND_SIMPLE, receipt = true),
            // Active, opened once with a receipt, passphrase.
            row(
                1, 3 * HOUR, 3 * DAY, 1 to 2, NoteRepository.NOTE_KIND_SIMPLE,
                receipt = true, receiptTimes = listOf(now - 2 * HOUR), password = true,
            ),
            // Split key with a saved copy (dummy value: only its presence is shown).
            row(2, 5 * HOUR, 7 * DAY, 0 to 1, NoteRepository.NOTE_KIND_SPLIT, savedCopy = "demo-saved-copy"),
            // Burned.
            row(3, 26 * HOUR, 3 * DAY, 1 to 1, NoteRepository.NOTE_KIND_SIMPLE, burned = true),
            // Expired (shown as gone).
            row(4, 3 * DAY, DAY, 0 to 2, NoteRepository.NOTE_KIND_DECOY, password = true, gone = true),
        )

        runBlocking {
            withContext(Dispatchers.IO) {
                dao.getAll().filter { it.id.startsWith(ID_PREFIX) }.forEach { dao.delete(it) }
                rows.forEach { dao.upsert(it) }
            }
        }
    }

    // ── Localized demo text ─────────────────────────────────────────────

    private class DemoText(
        val sample: String,
        val decoy: String,
        val real: String,
        /** Sent labels; [0] is also the label in the options sheet. */
        val labels: List<String>,
    )

    private fun text(): DemoText = TEXT[lang] ?: TEXT.getValue("en")

    private val TEXT: Map<String, DemoText> = mapOf(
        "en" to DemoText(
            sample = "Wi-Fi: cabin-guest\nPassword: amber-lantern-42\nThe router is on the shelf by the door.",
            decoy = "Gym locker code: 2580",
            real = "Backup drive passphrase: copper-river-917",
            labels = listOf("Wi-Fi password for the cabin", "Door code", "Recovery phrase", "Server login", "Alarm code"),
        ),
        "de" to DemoText(
            sample = "WLAN: cabin-guest\nPasswort: amber-lantern-42\nDer Router steht im Regal neben der Tür.",
            decoy = "Spindcode im Fitnessstudio: 2580",
            real = "Passphrase der Backup-Festplatte: copper-river-917",
            labels = listOf("WLAN-Passwort für die Hütte", "Türcode", "Wiederherstellungsphrase", "Server-Zugang", "Alarmcode"),
        ),
        "es" to DemoText(
            sample = "Wi-Fi: cabin-guest\nContraseña: amber-lantern-42\nEl router está en la estantería junto a la puerta.",
            decoy = "Código de la taquilla del gimnasio: 2580",
            real = "Clave del disco de respaldo: copper-river-917",
            labels = listOf("Contraseña del Wi-Fi de la cabaña", "Código de la puerta", "Frase de recuperación", "Acceso al servidor", "Código de la alarma"),
        ),
        "fr" to DemoText(
            sample = "Wi-Fi : cabin-guest\nMot de passe : amber-lantern-42\nLe routeur est sur l’étagère près de la porte.",
            decoy = "Code du casier de la salle de sport : 2580",
            real = "Phrase secrète du disque de sauvegarde : copper-river-917",
            labels = listOf("Mot de passe Wi-Fi du chalet", "Code de la porte", "Phrase de récupération", "Accès au serveur", "Code de l’alarme"),
        ),
        "it" to DemoText(
            sample = "Wi-Fi: cabin-guest\nPassword: amber-lantern-42\nIl router è sulla mensola vicino alla porta.",
            decoy = "Codice dell’armadietto in palestra: 2580",
            real = "Passphrase del disco di backup: copper-river-917",
            labels = listOf("Password Wi-Fi della baita", "Codice della porta", "Frase di recupero", "Accesso al server", "Codice dell’allarme"),
        ),
        "ja" to DemoText(
            sample = "Wi-Fi：cabin-guest\nパスワード：amber-lantern-42\nルーターはドア横の棚にあります。",
            decoy = "ジムのロッカー番号：2580",
            real = "バックアップ用ドライブのパスフレーズ：copper-river-917",
            labels = listOf("山小屋のWi-Fiパスワード", "ドアの暗証番号", "リカバリーフレーズ", "サーバーのログイン情報", "警報装置の暗証番号"),
        ),
        "pt-BR" to DemoText(
            sample = "Wi-Fi: cabin-guest\nSenha: amber-lantern-42\nO roteador fica na prateleira ao lado da porta.",
            decoy = "Código do armário da academia: 2580",
            real = "Senha do HD de backup: copper-river-917",
            labels = listOf("Senha do Wi-Fi do chalé", "Código da porta", "Frase de recuperação", "Acesso ao servidor", "Código do alarme"),
        ),
        "ru" to DemoText(
            sample = "Wi-Fi: cabin-guest\nПароль: amber-lantern-42\nРоутер стоит на полке у двери.",
            decoy = "Код шкафчика в спортзале: 2580",
            real = "Пароль от резервного диска: copper-river-917",
            labels = listOf("Пароль от Wi-Fi на даче", "Код от двери", "Фраза восстановления", "Доступ к серверу", "Код сигнализации"),
        ),
        "zh-Hans" to DemoText(
            sample = "Wi-Fi：cabin-guest\n密码：amber-lantern-42\n路由器在门边的架子上。",
            decoy = "健身房储物柜密码：2580",
            real = "备份硬盘口令：copper-river-917",
            labels = listOf("小屋的 Wi-Fi 密码", "门禁密码", "恢复短语", "服务器登录信息", "警报器密码"),
        ),
    )
}
