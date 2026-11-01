@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.updater.UpdateViewModel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.ui.components.MemberBrandWordmark

@Composable
fun AboutScreen(
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.about_title),
        subtitle = stringResource(R.string.about_subtitle)
    ) {
        AboutSettingsContent(
            onNavigateToSupportersContributors = onNavigateToSupportersContributors,
            onNavigateToLicensesAttributions = onNavigateToLicensesAttributions
        )
    }
}

@Composable
fun AboutSettingsContent(
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    initialFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val aboutScrollState = rememberScrollState()
    var firstRowFocused by remember { mutableStateOf(false) }
    val firstRowModifier = Modifier.onFocusChanged { firstRowFocused = it.isFocused }
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val aboutBringIntoViewSpec = remember(aboutScrollState, defaultBringIntoViewSpec) {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec = defaultBringIntoViewSpec.scrollAnimationSpec

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                return if (firstRowFocused && offset + aboutScrollState.value + size <= containerSize) {
                    -aboutScrollState.value.toFloat()
                } else {
                    defaultBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.about_title),
            subtitle = stringResource(R.string.about_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = null
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides aboutBringIntoViewSpec) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(aboutScrollState),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultBringIntoViewSpec) {
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

                            MemberBrandWordmark(
                                height = 64.dp,
                                contentDescription = stringResource(R.string.cd_nuvio_logo)
                            )

                            Text(
                                text = stringResource(R.string.about_made_with_love),
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.colors.TextSecondary,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.colors.TextSecondary,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))

                            SettingsActionRow(
                                title = stringResource(R.string.about_privacy_policy),
                                subtitle = stringResource(R.string.about_privacy_policy_subtitle),
                                trailingIcon = Icons.Default.OpenInNew,
                                modifier = firstRowModifier.then(
                                    if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester)
                                    else Modifier
                                ),
                                onClick = {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/abbaskip/Nuvio-ReSeq/blob/dev/PRIVACY.md")
                                    )
                                    context.startActivity(intent)
                                }
                            )

                            val activity = LocalActivity.current as? ComponentActivity
                            if (AppFeaturePolicy.inAppUpdatesEnabled && activity != null) {
                                // Share the Activity's updater so its existing banner handles the result.
                                val updater: UpdateViewModel = hiltViewModel(activity)
                                val updateState by updater.uiState.collectAsStateWithLifecycle()
                                SettingsActionRow(
                                    title = stringResource(R.string.about_check_for_updates),
                                    subtitle = stringResource(
                                        if (updateState.isChecking) R.string.update_checking
                                        else R.string.about_check_for_updates_subtitle
                                    ),
                                    enabled = !updateState.isChecking,
                                    onClick = { updater.checkForUpdates(force = true, showNoUpdateFeedback = true) }
                                )
                            }

                            if (AppFeaturePolicy.supportNuvioEnabled) {
                                SettingsActionRow(
                                    title = stringResource(R.string.support_nuvio_name),
                                    subtitle = stringResource(R.string.about_supporters_contributors_subtitle),
                                    trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    onClick = onNavigateToSupportersContributors
                                )
                            }

                            SettingsActionRow(
                                title = stringResource(R.string.about_licenses_attributions),
                                subtitle = stringResource(R.string.about_licenses_attributions_subtitle),
                                trailingIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                onClick = onNavigateToLicensesAttributions
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = aboutScrollState)
            }
        }
    }
}
