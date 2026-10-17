package com.zhibox.memory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhibox.memory.R
import com.zhibox.memory.core.search.SearchService

/**
 * 证据详情屏（P1-8 文案资源化）：命中 chunk 的原文、原文内定位（charStart/End）、
 * 来源与文档回溯链（source → document → chunk），保证"答案可溯源到原文"（§9.1-6）。
 *
 * 作为检索屏的内部覆盖层使用（见 [SearchScreen]），返回时检索结果保留。
 */
@Composable
fun EvidenceScreen(
    hit: SearchService.SearchHit,
    onBack: () -> Unit = {},
) {
    ZhiBoxScreen(title = stringResource(R.string.evidence_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        hit.docTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            R.string.evidence_source,
                            hit.sourceTitle.ifBlank { hit.sourceUri },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.evidence_location, hit.charStart, hit.charEnd, hit.ordinal),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(
                            R.string.evidence_channels,
                            hit.channels,
                            hit.vectorRank?.toString() ?: "—",
                            hit.keywordRank?.toString() ?: "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.evidence_content), style = MaterialTheme.typography.titleSmall)
                    Text(hit.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
