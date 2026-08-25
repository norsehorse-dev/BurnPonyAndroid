//
// OnboardingScreen.kt
// First-run walkthrough, drawn as a full-screen surface on top of the main
// scaffold while SettingsStore.onboardingCompleted is false. Replayable at
// any time from Settings.
//
// Layout:
//   +-------------------------------+
//   |                        Skip   |  <- hidden on the last page
//   +-------------------------------+
//   |        HorizontalPager        |  <- OnboardingPage, swipeable
//   +-------------------------------+
//   |  o o o o o        [Next/Done] |  <- dots + advance button
//   +-------------------------------+
//
// Slide 3 opens OnboardingSendSheet. On a successful send the sheet closes
// and the production ResultScreen is shown verbatim - the same link card,
// share sheet, copy button and QR the user will see for every note after
// this one. Dismissing it advances the pager, so finishing the tour and
// having actually used the product are the same gesture.
//
// Back is swallowed: the tour is four taps and Skip is always one tap away,
// so there is nothing to escape from, and a stray back gesture during the
// send step must not drop the user into a half-configured app.
//

package com.burnpony.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.burnpony.app.R
import com.burnpony.app.theme.BurnPonyTheme
import com.burnpony.app.ui.result.ResultScreen
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit,
) {
    val slides = OnboardingSlides.all
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // rememberSaveable, not remember: a rotation mid-tour must not re-arm the
    // "Send a note now" CTA and invite a second real note.
    var showSendSheet by rememberSaveable { mutableStateOf(false) }
    var sentAtLeastOne by rememberSaveable { mutableStateOf(false) }

    val isLastPage = pagerState.currentPage == slides.lastIndex

    BackHandler(enabled = true) { }

    // A successful send hands the screen to the result. The sheet is gated on
    // state.created directly rather than only being closed from this effect:
    // effects run after composition, so an effect-only close would compose the
    // sheet and the result together for one frame.
    LaunchedEffect(state.created) {
        if (state.created != null) {
            showSendSheet = false
            sentAtLeastOne = true
        }
    }

    fun advance() {
        if (pagerState.currentPage < slides.lastIndex) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        }
    }

    // The scaffold behind the tour is hidden from accessibility by
    // MainActivity, not from here: isTraversalGroup only orders traversal
    // WITHIN a group, it does not stop TalkBack reaching siblings.
    Surface(modifier = Modifier.fillMaxSize(), color = BurnPonyTheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (!isLastPage) {
                    TextButton(onClick = onComplete) {
                        Text(
                            stringResource(R.string.onboarding_skip),
                            color = BurnPonyTheme.dim,
                        )
                    }
                } else {
                    Box(modifier = Modifier.height(48.dp))
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { pageIndex ->
                OnboardingPage(
                    slide = slides[pageIndex],
                    alreadySent = sentAtLeastOne,
                    onSendTapped = { showSendSheet = true },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    slides.forEachIndexed { index, _ ->
                        val isActive = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(if (isActive) 10.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) BurnPonyTheme.ember
                                    else BurnPonyTheme.dim.copy(alpha = 0.3f)
                                ),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .background(BurnPonyTheme.emberGradient, RoundedCornerShape(12.dp))
                        .clickable { if (isLastPage) onComplete() else advance() }
                        .padding(horizontal = 26.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(
                            if (isLastPage) R.string.onboarding_get_started
                            else R.string.onboarding_next
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BurnPonyTheme.buttonInk,
                    )
                }
            }
        }
    }

    if (showSendSheet && state.created == null) {
        OnboardingSendSheet(
            viewModel = viewModel,
            onDismiss = { showSendSheet = false },
        )
    }

    // The real result screen, verbatim: same link card, share, copy and QR
    // the user gets for every note from here on.
    val created = state.created
    if (created != null) {
        ResultScreen(note = created) {
            // Belt and braces: the sheet is already gated on created == null,
            // but reset() makes that null again, so close it explicitly here
            // rather than relying on the LaunchedEffect having run first.
            showSendSheet = false
            viewModel.reset()
            advance()
        }
    }
}
