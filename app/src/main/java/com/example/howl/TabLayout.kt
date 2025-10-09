package com.example.howl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.howl.ui.theme.HowlTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class TabLayoutViewModel : ViewModel() {
    private val fixedTabs = listOf("Player", "Generator", "Activity", "Settings")
    private val debugTab = "Debug"

    private val _tabIndex = MutableStateFlow(0)
    val tabIndex: StateFlow<Int> = _tabIndex.asStateFlow()

    val miscOptionsState: StateFlow<DataRepository.MiscOptionsState> = DataRepository.miscOptionsState

    val visibleTabs: StateFlow<List<String>> = miscOptionsState
        .map { options ->
            if (options.showDebugLog) {
                fixedTabs + debugTab
            } else {
                fixedTabs
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, fixedTabs)

    fun setTabIndex(index: Int) {
        _tabIndex.update { index }
    }
}

@Composable
fun TabLayout(
    tabLayoutViewModel: TabLayoutViewModel,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel,
    generatorViewModel: GeneratorViewModel,
    activityHostViewModel: ActivityHostViewModel,
    modifier: Modifier = Modifier
) {
    val tabIndex by tabLayoutViewModel.tabIndex.collectAsState()
    val visibleTabs by tabLayoutViewModel.visibleTabs.collectAsState()

    // Reset tab index if current index is invalid
    LaunchedEffect(visibleTabs) {
        if (tabIndex >= visibleTabs.size) {
            tabLayoutViewModel.setTabIndex(0)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ScrollableTabRow(
            selectedTabIndex = tabIndex,
            edgePadding = 0.dp,
            divider = {},
            modifier = Modifier.fillMaxWidth()
        ) {
            visibleTabs.forEachIndexed { index, title ->
                Tab(
                    text = { Text(title) },
                    selected = tabIndex == index,
                    onClick = { tabLayoutViewModel.setTabIndex(index) },
                    modifier = Modifier.weight(1f, fill = false),
                    icon = {
                        when (title) {
                            "Player" -> Icon(painterResource(R.drawable.player), contentDescription = null)
                            "Generator" -> Icon(painterResource(R.drawable.wave), contentDescription = null)
                            "Activity" -> Icon(painterResource(R.drawable.rocket), contentDescription = null)
                            "Settings" -> Icon(painterResource(R.drawable.settings), contentDescription = null)
                            "Debug" -> Icon(painterResource(R.drawable.debug), contentDescription = null)
                        }
                    }
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, modifier = Modifier.fillMaxWidth())

        visibleTabs.getOrNull(tabIndex)?.let { currentTab ->
            when (currentTab) {
                "Player" -> PlayerPanel(viewModel = playerViewModel)
                "Generator" -> GeneratorPanel(viewModel = generatorViewModel)
                "Activity" -> ActivityHostPanel(viewModel = activityHostViewModel)
                "Settings" -> SettingsPanel(viewModel = settingsViewModel)
                "Debug" -> LogViewer()
            }
        }
    }
}

@Preview
@Composable
fun TabLayoutPreview() {
    HowlTheme {
        val viewModel: TabLayoutViewModel = viewModel()
        val playerViewModel: PlayerViewModel = viewModel()
        val settingsViewModel: SettingsViewModel = viewModel()
        val generatorViewModel: GeneratorViewModel = viewModel()
        val activityHostViewModel: ActivityHostViewModel = viewModel()
        TabLayout (
            tabLayoutViewModel = viewModel,
            playerViewModel = playerViewModel,
            settingsViewModel = settingsViewModel,
            generatorViewModel = generatorViewModel,
            activityHostViewModel = activityHostViewModel,
            modifier = Modifier.fillMaxHeight()
        )
    }
}