package com.weiting.timeline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.weiting.timeline.scheduler.SchedulerScreen
import com.weiting.timeline.ui.theme.TimelineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimelineTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SchedulerScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
