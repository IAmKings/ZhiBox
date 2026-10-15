package com.zhibox.memory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhibox.memory.ui.BenchmarkScreen
import com.zhibox.memory.ui.ImportScreen
import com.zhibox.memory.ui.theme.ZhiBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val autoBenchmark = intent?.getBooleanExtra(EXTRA_AUTO_BENCHMARK, false) == true
        val autoG1 = intent?.getBooleanExtra(EXTRA_AUTO_G1, false) == true
        val autoScan = intent?.getBooleanExtra(EXTRA_AUTO_SCAN, false) == true
        val autoG3 = intent?.getBooleanExtra(EXTRA_AUTO_G3, false) == true
        val autoImport = intent?.getBooleanExtra(EXTRA_AUTO_IMPORT, false) == true
        setContent {
            ZhiBoxTheme {
                var screen by remember {
                    mutableStateOf(
                        when {
                            autoBenchmark || autoG1 || autoScan || autoG3 -> "benchmark"
                            autoImport -> "import"
                            else -> "home"
                        }
                    )
                }
                when (screen) {
                    "benchmark" -> BenchmarkScreen(
                        autoStart = autoBenchmark,
                        autoStartG1 = autoG1,
                        autoStartScan = autoScan,
                        autoStartG3 = autoG3,
                        onBack = { screen = "home" },
                    )
                    "import" -> ImportScreen(autoStart = autoImport, onBack = { screen = "home" })
                    else -> AppSkeleton(
                        onOpenBenchmark = { screen = "benchmark" },
                        onOpenImport = { screen = "import" },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_AUTO_BENCHMARK = "autostart_benchmark"
        const val EXTRA_AUTO_G1 = "autostart_g1"
        const val EXTRA_AUTO_SCAN = "autostart_scan"
        const val EXTRA_AUTO_G3 = "autostart_g3"
        const val EXTRA_AUTO_IMPORT = "autostart_import"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSkeleton(onOpenBenchmark: () -> Unit = {}, onOpenImport: () -> Unit = {}) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Phase 0 · 工程骨架已就绪",
                style = MaterialTheme.typography.headlineSmall
            )
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("当前里程碑：P0-2 Universal Embedder 接入", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "后续：P0-3 判定门 G1（任务模板）→ G2–G4 → Phase 1 离线搜索 MVP",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "依据：docs/SAG_Android_MVP_Implementation_Plan_v1.0.md",
                        style = MaterialTheme.typography.bodySmall
                    )
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = onOpenBenchmark) { Text("实验/诊断") }
                        Button(onClick = onOpenImport) { Text("导入管线") }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppSkeletonPreview() {
    ZhiBoxTheme {
        AppSkeleton()
    }
}
