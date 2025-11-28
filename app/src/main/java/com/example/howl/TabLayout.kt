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
import androidx.compose.ui.platform.LocalContext
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

// 定义标签类型枚举
enum class TabType {
    Player,
    Generator,
    Activity,
    Settings,
    Debug
}

class TabLayoutViewModel : androidx.lifecycle.ViewModel() {
    private val fixedTabs = listOf(TabType.Player, TabType.Generator, TabType.Activity, TabType.Settings)
    private val debugTab = TabType.Debug

    private val _tabIndex = MutableStateFlow(0)
    val tabIndex: StateFlow<Int> = _tabIndex.asStateFlow()

    val miscOptionsState: StateFlow<DataRepository.MiscOptionsState> = DataRepository.miscOptionsState

    val visibleTabs: StateFlow<List<TabType>> = miscOptionsState
        .map { options ->
            if (options.showDebugLog) {
                fixedTabs + debugTab
            } else {
                fixedTabs
            }
        }
        .stateIn(this.viewModelScope, SharingStarted.Eagerly, fixedTabs)

    fun setTabIndex(index: Int) {
        _tabIndex.update { index }
    }
}

// 根据TabType获取字符串资源ID
@Composable
fun getTabStringResource(tabType: TabType): Int {
    return when (tabType) {
        TabType.Player -> R.string.tab_player
        TabType.Generator -> R.string.tab_generator
        TabType.Activity -> R.string.tab_activity
        TabType.Settings -> R.string.tab_settings
        TabType.Debug -> R.string.tab_debug
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
    val context = LocalContext.current

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
            visibleTabs.forEachIndexed { index, tabType ->
                Tab(
                    text = { Text(context.getString(getTabStringResource(tabType))) },
                    selected = tabIndex == index,
                    onClick = { tabLayoutViewModel.setTabIndex(index) },
                    modifier = Modifier.weight(1f, fill = false),
                    icon = {
                        when (tabType) {
                            TabType.Player -> Icon(painterResource(R.drawable.player), contentDescription = null)
                            TabType.Generator -> Icon(painterResource(R.drawable.wave), contentDescription = null)
                            TabType.Activity -> Icon(painterResource(R.drawable.rocket), contentDescription = null)
                            TabType.Settings -> Icon(painterResource(R.drawable.settings), contentDescription = null)
                            TabType.Debug -> Icon(painterResource(R.drawable.debug), contentDescription = null)
                        }
                    }
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, modifier = Modifier.fillMaxWidth())

        visibleTabs.getOrNull(tabIndex)?.let { currentTab ->
            when (currentTab) {
                TabType.Player -> PlayerPanel(viewModel = playerViewModel)
                TabType.Generator -> GeneratorPanel(viewModel = generatorViewModel)
                TabType.Activity -> ActivityHostPanel(viewModel = activityHostViewModel)
                TabType.Settings -> SettingsPanel(viewModel = settingsViewModel)
                TabType.Debug -> LogViewer()
            }
        }
    }
}

@Preview
@Composable
fun TabLayoutPreview() {
    HowlTheme {
        val viewModel = TabLayoutViewModel()
        val playerViewModel = PlayerViewModel()
        val settingsViewModel = SettingsViewModel()
        val generatorViewModel = GeneratorViewModel()
        val activityHostViewModel = ActivityHostViewModel()
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