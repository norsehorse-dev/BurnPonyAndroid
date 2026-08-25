//
// LanguagePicker.kt
// The nine-row language list, shared verbatim by onboarding slide 0 and the
// Settings "Language" section. One composable rather than two so the two
// entry points cannot drift - they call the same LanguageManager.setLanguage
// and therefore behave identically.
//
// Rows are labelled in their OWN language (Deutsch, not German). Someone who
// has landed in an app they cannot read needs to recognise their language
// without being able to read the surrounding UI, which is the entire reason
// this picker exists.
//
// Tapping a row does two things:
//   1. Updates LanguageState so the checkmark moves on this frame.
//   2. Calls AppCompatDelegate.setApplicationLocales, which recreates the
//      Activity within a few hundred ms. The whole tree rebuilds in the new
//      locale, so the rest of the walkthrough is already translated by the
//      time the user swipes to it.
//

package com.burnpony.app.ui.language

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.burnpony.app.R
import com.burnpony.app.i18n.LanguageManager
import com.burnpony.app.i18n.LanguageState
import com.burnpony.app.i18n.SupportedLanguage
import com.burnpony.app.theme.BurnPonyTheme

@Composable
fun LanguagePickerRows(
    modifier: Modifier = Modifier,
    /** Onboarding is vertically tight; Settings can breathe. */
    compact: Boolean = false,
) {
    val current by LanguageState.current
    val languages = SupportedLanguage.entries
    val rowPadding = if (compact) 12.dp else 14.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BurnPonyTheme.fieldBackground),
    ) {
        languages.forEachIndexed { index, language ->
            val selected = current == language.tag
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.RadioButton) {
                        LanguageManager.setLanguage(language)
                    }
                    .padding(horizontal = 16.dp, vertical = rowPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = language.nativeName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = BurnPonyTheme.ink,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.language_selected),
                        tint = BurnPonyTheme.ember,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (index < languages.size - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    thickness = 0.5.dp,
                    color = BurnPonyTheme.line,
                )
            }
        }
    }
}
