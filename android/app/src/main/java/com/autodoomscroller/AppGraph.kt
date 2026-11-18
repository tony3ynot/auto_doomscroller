package com.autodoomscroller

import android.content.Context
import com.autodoomscroller.data.AppDatabase
import com.autodoomscroller.data.ReelRepository
import com.autodoomscroller.data.ThumbnailStore
import com.autodoomscroller.model.SkipPredictor
import com.autodoomscroller.session.SessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Minimal manual dependency container. Swap for Hilt if the graph grows. */
object AppGraph {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var reelRepository: ReelRepository
        private set

    lateinit var thumbnailStore: ThumbnailStore
        private set

    lateinit var skipPredictor: SkipPredictor
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        reelRepository = ReelRepository(AppDatabase.build(app).reelDao())
        thumbnailStore = ThumbnailStore.default(app)
        skipPredictor = SkipPredictor(app, reelRepository).also { it.load(appScope) }
        SessionController.init(app)
    }
}
