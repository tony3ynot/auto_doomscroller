package com.autodoomscroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.autodoomscroller.ui.HomeScreen
import com.autodoomscroller.ui.theme.AutoDoomscrollerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutoDoomscrollerTheme {
                HomeScreen()
            }
        }
    }
}
