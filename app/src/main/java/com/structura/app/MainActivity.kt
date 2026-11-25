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
import com.structura.app.ui.standards.StandardsScreen
import com.structura.app.ui.studio.StudioScreen
import com.structura.app.ui.rcc.BeamDesignScreen
import com.structura.app.ui.theme.StructuraTheme

object Routes {
    const val HOME = "home"
    const val RCC_BEAM = "rcc/beam"
    const val RCC_DRAWING = "rcc/drawing"
    const val RCC_SLAB = "rcc/slab"
    const val STEEL = "steel"
    const val RCC_COLUMN = "rcc/column"
    const val RCC_FOOTING = "rcc/footing"
    const val FEM = "fem/frame"
    const val STUDIO = "fem/studio"
    const val STANDARDS = "standards"
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
                    composable(Routes.STEEL) { com.structura.app.ui.steel.SteelScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.RCC_SLAB) { com.structura.app.ui.rcc.SlabScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.RCC_COLUMN) { com.structura.app.ui.rcc.ColumnScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.RCC_FOOTING) { com.structura.app.ui.rcc.FootingScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.RCC_DRAWING) {
                        DrawingScreen(onBack = { nav.popBackStack() })
                    }
                    composable(Routes.FEM) {
                        FemScreen(
                            onBack = { nav.popBackStack() },
                            onDesignBeam = { nav.navigate(Routes.RCC_BEAM) },
                            onStudio = { nav.navigate(Routes.STUDIO) { popUpTo(Routes.HOME) } },
                        )
                    }
                    composable(Routes.STANDARDS) {
                        StandardsScreen(onBack = { nav.popBackStack() })
                    }
                    composable(Routes.STUDIO) {
                        StudioScreen(
                            onBack = { nav.popBackStack() },
                            onWizard = { nav.navigate(Routes.FEM) },
                            onStandards = { nav.navigate(Routes.STANDARDS) },
                            onDesignBeam = { nav.navigate(Routes.RCC_BEAM) },
                        )
                    }
                }
            }
        }
    }
}
