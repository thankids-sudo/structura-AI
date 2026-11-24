package com.structura.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.structura.app.ui.drawing.DrawingScreen
import com.structura.app.ui.fem.FemScreen
import com.structura.app.ui.home.HomeScreen
import com.structura.app.ui.rcc.BeamDesignScreen
import com.structura.app.ui.theme.StructuraTheme

object Routes {
    const val HOME = "home"
    const val RCC_BEAM = "rcc/beam"
    const val RCC_DRAWING = "rcc/drawing"
    const val FEM = "fem/frame"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StructuraTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        HomeScreen(onOpen = { route -> nav.navigate(route) })
                    }
                    composable(Routes.RCC_BEAM) {
                        BeamDesignScreen(
                            onBack = { nav.popBackStack() },
                            onDrawing = { nav.navigate(Routes.RCC_DRAWING) },
                        )
                    }
                    composable(Routes.RCC_DRAWING) {
                        DrawingScreen(onBack = { nav.popBackStack() })
                    }
                    composable(Routes.FEM) {
                        FemScreen(
                            onBack = { nav.popBackStack() },
                            onDesignBeam = { nav.navigate(Routes.RCC_BEAM) },
                        )
                    }
                }
            }
        }
    }
}
